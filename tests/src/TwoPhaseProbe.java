import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.ConstructorTailInjector;
import remold.ref.ConstructorTailInjector.FieldInit;
import remold.ref.InitFix;
import remold.ref.StageASkeleton;
import remold.util.LibTool;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end probe for the two-phase protocol on a DCEVM runtime:
 * {@code stage A -> redefine -> stage B (patch) -> stage C -> redefine}.
 *
 * <p>Scenarios, in order:</p>
 * <ol>
 *   <li>pure orchestration with {@link StageASkeleton#build} + {@link ConstructorTailInjector} (the
 *       original design) and its no-injection control — these stay as component-level evidence;</li>
 *   <li>the <b>production stage-A variant</b> ({@link StageASkeleton#buildWithLiveConstructors}): old
 *       bodies for normal methods, new constructors for window-created instances, no tail injection;</li>
 *   <li>the <b>read-through NPE red/green control</b>: single-phase redefine then call the new body →
 *       deterministic NPE; two-phase stage A → the same call runs the old body and never touches the
 *       unpatched field;</li>
 *   <li>the <b>real production driver</b>: reflective {@code RemoldAgent.processChanges} with 50k live
 *       instances and a concurrent reader — two-phase ON observes no NPE, the
 *       {@code -Dremold.agent.two_phase=false} control observes the NPE (red baseline).</li>
 * </ol>
 *
 * <p>Run via {@code ./gradlew :tests:twoPhaseProbe -Pp05.jbr=&lt;dcevm-jdk-home&gt;}.</p>
 */
public class TwoPhaseProbe {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("   [PASS] " + msg);
			passed++;
		} else {
			System.err.println("   [FAIL] " + msg);
			failed++;
		}
	}

	static void checkEq(Object expected, Object actual, String msg) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		if (!ok) msg = msg + " [expected=" + expected + ", actual=" + actual + "]";
		check(ok, msg);
	}

	static final String SRC_V1 = """
		package two;
		public class Subj {
		    public int a = 1;
		    public String hi() { return "v1:" + a; }
		}
		""";

	static final String SRC_V2 = """
		package two;
		public class Subj {
		    public int a = 1;
		    public int b = 42;
		    public String hi() { return "v2:" + a + "," + b; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== TwoPhaseProbe: stage A -> B -> C orchestration ===");
		System.out.println("   java.vm.name = " + System.getProperty("java.vm.name")
		                   + ", java.version = " + System.getProperty("java.version"));

		Instrumentation inst = P05Agent.instrumentation();
		if (inst == null) {
			System.out.println("[SKIP] P05Agent instrumentation unavailable; run with -javaagent:p05-agent.jar.");
			return;
		}

		RemoldAgent.INIT_FIX = true;
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.out.println("[SKIP] native libtool unavailable, stage B cannot enumerate: " + t);
			return;
		}

		String javac = findJavac();
		File base = Files.createTempDirectory("two_phase_probe").toFile();
		try {
			scenario(inst, javac, base, true);
			scenario(inst, javac, base, false);
			scenarioProductionStageA(inst, javac, base);
			scenarioReadThroughNpe(inst, javac, base);
			scenarioProductionPipeline(inst, javac, base, true);
			scenarioProductionPipeline(inst, javac, base, false);
			scenarioJitWarmup(inst, javac, base);
			scenarioJitControl(inst, javac, base, "body-only", SRC_JIT_D_V2_BODY);
			scenarioJitControl(inst, javac, base, "structural", SRC_JIT_D_V2_STRUCT);
			scenarioInFlightConstructor(inst, javac, base);
		} finally {
			deleteRecursively(base);
		}

		System.out.println("\n=== TWO-PHASE PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	/**
	 * @param inject 是否在阶段 A 的构造器尾部注入字段切片；false 是对照组，用来证明缝隙真实存在
	 */
	static void scenario(Instrumentation inst, String javac, File base, boolean inject) throws Exception {
		String title = inject ? "[with tail injection]" : "[control: no tail injection]";
		System.out.println("\n--- " + title + " ---");

		File work = new File(base, inject ? "inject" : "control");
		File v1Dir = compile(javac, new File(work, "v1"), SRC_V1);
		File v2Dir = compile(javac, new File(work, "v2"), SRC_V2);
		byte[] v1 = Files.readAllBytes(new File(v1Dir, "two/Subj.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(v2Dir, "two/Subj.class").toPath());

		// Parent must be the application loader: the stage-B companion is a nestmate of the fixture and
		// links remold.ref.HotswapBridge through its defining loader.
		URLClassLoader cl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                       TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("two.Subj", true, cl);
			Object legacy = host.getConstructor().newInstance();
			checkEq("v1:1", call(host, legacy, "hi"), "v1 body is live before the update");

			// ---- 1. 合成阶段 A，并把切片写进构造器尾部 ----
			long t0 = System.nanoTime();
			byte[] stageA = StageASkeleton.build(v1, v2);
			if (inject) {
				InsnList value = new InsnList();
				value.add(new LdcInsnNode(42));
				stageA = ConstructorTailInjector
					.inject(stageA, List.of(new FieldInit("b", "I", value)))
					.bytes();
			}
			long buildUs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);

			// 分析用阶段 C 字节码：阶段 A 的 <init> 已被换回旧体，里面没有新字段的初始化式，
			// 拿它去抽切片只会得到"没有安全的初始化表达式"。
			// 同时把阶段 C 字节码放进 bytecodeCache —— 生产里 AnnotationTransformer 也是这么做的，
			// 层级 Oracle 会优先从缓存读宿主字节码；否则它只能从加载器拿到旧的 v1，找不到新字段。
			RemoldAgent.bytecodeCache.put("two.Subj", v2);
			InitFix.transform(host, v2, ClassDiffUtil.diff(v1, v2));

			// ---- 2. 提交阶段 A ----
			long usA = redefine(inst, host, stageA);
			checkEq("v1:1", call(host, legacy, "hi"), "stage A keeps the old body running");
			checkEq(0, getInt(host, legacy, "b"), "stage A leaves existing instances at the type default");
			check(true, "stage A accepted by the runtime (schema change landed)");

			// ---- 3. 阶段 B：真实 InitFix 驱动补丁 ----
			long tB = System.nanoTime();
			InitFix.afterRedefine(host);
			long patchMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tB);
			checkEq(42, getInt(host, legacy, "b"), "stage B patches the pre-existing instance");
			checkEq("v1:1", call(host, legacy, "hi"), "stage B still runs the old body (no read-through)");

			// ---- 4. 缝隙：枚举之后、阶段 C 之前新建的实例 ----
			Object afterB = host.getConstructor().newInstance();
			if (inject) {
				checkEq(42, getInt(host, afterB, "b"),
					"tail injection gives objects created after the enumeration their real value");
			} else {
				checkEq(0, getInt(host, afterB, "b"),
					"control: without injection the same object stays at the default — this is the gap");
			}

			// ---- 5. 提交阶段 C ----
			long usC = redefine(inst, host, v2);
			checkEq("v2:1,42", call(host, legacy, "hi"), "stage C switches legacy instances to the new body");
			checkEq(inject ? "v2:1,42" : "v2:1,0", call(host, afterB, "hi"),
				inject ? "the window instance is fully consistent after stage C"
				       : "control: the window instance stays permanently stale after stage C");

			System.out.printf("   timing: build=%dus stageA=%dus stageB=%dms stageC=%dus%n",
			                  buildUs, usA, patchMs, usC);
		} finally {
			cl.close();
		}
	}

	/**
	 * 生产接线实际使用的阶段 A 变体：{@code StageASkeleton.buildWithLiveConstructors} +
	 * 不注入构造器尾部。窗口内新建的实例必须由新构造器直接初始化。
	 */
	static void scenarioProductionStageA(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println();
		System.out.println("--- production stage A: new constructors, no tail injection ---");
		File work = new File(base, "production");
		File v1Dir = compile(javac, new File(work, "v1"), SRC_V1);
		File v2Dir = compile(javac, new File(work, "v2"), SRC_V2);
		byte[] v1 = Files.readAllBytes(new File(v1Dir, "two/Subj.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(v2Dir, "two/Subj.class").toPath());

		URLClassLoader cl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                       TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("two.Subj", true, cl);
			Object legacy = host.getConstructor().newInstance();
			checkEq("v1:1", call(host, legacy, "hi"), "v1 body is live before the update");

			RemoldAgent.bytecodeCache.put("two.Subj", v2);
			InitFix.transform(host, v2, ClassDiffUtil.diff(v1, v2));

			byte[] stageA = StageASkeleton.buildWithLiveConstructors(v1, v2);
			redefine(inst, host, stageA);
			checkEq("v1:1", call(host, legacy, "hi"), "stage A still runs the old body (no read-through)");
			checkEq(0, getInt(host, legacy, "b"), "stage A leaves existing instances at the type default");

			Object window = host.getConstructor().newInstance();
			checkEq(42, getInt(host, window, "b"),
				"new constructor initialises a window instance without any tail injection");

			InitFix.afterRedefine(host);
			checkEq(42, getInt(host, legacy, "b"), "stage B patches the pre-existing instance");

			redefine(inst, host, v2);
			checkEq("v2:1,42", call(host, legacy, "hi"), "stage C switches the legacy instance to the new body");
			checkEq("v2:1,42", call(host, window, "hi"),
				"the window instance is fully consistent after stage C");
		} finally {
			cl.close();
		}
	}

	/**
	 * 读穿 NPE 的红→绿对照：同一个夹具，单阶段在重定义后立刻调用新方法体必然 NPE；两阶段在
	 * 阶段 A 的同一时刻跑的是旧方法体，不会碰新增字段。
	 */
	static void scenarioReadThroughNpe(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println();
		System.out.println("--- read-through NPE: single-phase red vs two-phase green ---");
		File work = new File(base, "npe");
		File v1Dir = compile(javac, new File(work, "v1"), "Subj.java", SRC_NPE_V1);
		File v2Dir = compile(javac, new File(work, "v2"), "Subj.java", SRC_NPE_V2);
		byte[] v1 = Files.readAllBytes(new File(v1Dir, "npe/Subj.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(v2Dir, "npe/Subj.class").toPath());

		// 对照组：单阶段 —— 重定义后、补丁前的新方法体读到 null ui。
		URLClassLoader redLoader = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                             TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("npe.Subj", true, redLoader);
			Object legacy = host.getConstructor().newInstance();
			checkEq("v1", call(host, legacy, "hi"), "control precondition: v1 body");
			RemoldAgent.bytecodeCache.put("npe.Subj", v2);
			InitFix.transform(host, v2, ClassDiffUtil.diff(v1, v2));
			redefine(inst, host, v2); // 单阶段：新方法体立即上线，字段仍是 null
			try {
				call(host, legacy, "hi");
				check(false, "control: single-phase must expose the read-through NPE");
			} catch (InvocationTargetException e) {
				check(e.getCause() instanceof NullPointerException,
					"control: single-phase new body hits NPE on the not-yet-patched field (cause="
					+ e.getCause() + ")");
			}
			InitFix.afterRedefine(host);
			checkEq("v2:Object", call(host, legacy, "hi"), "control: after the patch the same call succeeds");
		} finally {
			redLoader.close();
		}

		// 修复路径：两阶段 —— 阶段 A 的同一时刻跑旧方法体，不会 NPE。
		URLClassLoader greenLoader = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                               TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("npe.Subj", true, greenLoader);
			Object legacy = host.getConstructor().newInstance();
			checkEq("v1", call(host, legacy, "hi"), "two-phase precondition: v1 body");
			RemoldAgent.bytecodeCache.put("npe.Subj", v2);
			InitFix.transform(host, v2, ClassDiffUtil.diff(v1, v2));

			byte[] stageA = StageASkeleton.buildWithLiveConstructors(v1, v2);
			redefine(inst, host, stageA);
			checkEq("v1", call(host, legacy, "hi"),
				"two-phase: the redefine frame runs the OLD body, so the unpatched field is never read");
			Object window = host.getConstructor().newInstance();
			check(host.getField("ui").get(window) != null,
				"two-phase: a window instance gets ui from the new constructor");

			InitFix.afterRedefine(host);
			check(host.getField("ui").get(legacy) != null, "two-phase: stage B patches the legacy instance");
			redefine(inst, host, v2);
			checkEq("v2:Object", call(host, legacy, "hi"), "two-phase: stage C exposes the new body safely");
			checkEq("v2:Object", call(host, window, "hi"), "two-phase: the window instance is consistent");
		} finally {
			greenLoader.close();
		}
	}

	/**
	 * 生产接线端到端：真实 {@code RemoldAgent.processChanges} + 读线程 + 大量存量实例。
	 *
	 * <p>红/绿对照由同一个夹具、同一个读线程完成：关闭两阶段（{@code -Dremold.agent.two_phase=false}
	 * 的等价物）时读线程会在"重定义已生效、补丁未完成"的窗口里抛 NPE；开启两阶段时窗口内跑旧方法体，
	 * 读线程只可能看到旧结果或补丁后的新结果。实例数量刻意放大，让阶段 B 的枚举/CAS 把窗口拉宽到
	 * 读线程必然能撞上。</p>
	 */
	static void scenarioProductionPipeline(Instrumentation inst, String javac, File base, boolean twoPhase)
		throws Exception {
		String pkg = twoPhase ? "pp1" : "pp2";
		System.out.println();
		System.out.println("--- production pipeline (two-phase " + (twoPhase ? "ON" : "OFF control") + ") ---");

		File work   = new File(base, "pipeline-" + pkg);
		File v1Dir  = compile(javac, new File(work, "v1"), "Subj.java", PROD_V1.replace("PKG", pkg));
		File v2Dir  = compile(javac, new File(work, "v2"), "Subj.java", PROD_V2.replace("PKG", pkg));
		File v2File = new File(work, "round/" + pkg + "/Subj.class");
		Files.createDirectories(v2File.getParentFile().toPath());
		Files.copy(new File(v2Dir, pkg + "/Subj.class").toPath(), v2File.toPath(),
		           java.nio.file.StandardCopyOption.REPLACE_EXISTING);

		List<String> logs = Collections.synchronizedList(new ArrayList<>());
		RemoldAgent.Logger previousLogger = RemoldAgent.logger;
		boolean  previousFlag   = RemoldAgent.TWO_PHASE;
		setAgentInstrumentation(inst);
		URLClassLoader cl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                       TwoPhaseProbe.class.getClassLoader());
		try {
			RemoldAgent.logger = new RemoldAgent.Logger() {
				public void log(String msg) { logs.add("[LOG] " + msg); }
				public void info(String msg) { logs.add("[INFO] " + msg); }
				public void warn(String msg) { logs.add("[WARN] " + msg); }
				public void error(String msg) { logs.add("[ERROR] " + msg); }
				public void error(String msg, Throwable t) { logs.add("[ERROR] " + msg + " | " + t); }
			};
			RemoldAgent.TWO_PHASE = twoPhase;

			Class<?> host = Class.forName(pkg + ".Subj", true, cl);
			Object legacy = host.getConstructor().newInstance();
			checkEq("v1", call(host, legacy, "hi"), "precondition: fixture runs v1");

			// 放大阶段 B 的枚举/CAS 窗口，让读线程有足够时间撞进窗口。
			List<Object> ballast = new ArrayList<>(50_000);
			for (int i = 0; i < 50_000; i++) ballast.add(host.getConstructor().newInstance());
			check(ballast.size() == 50_000, "50k live instances created to widen the stage-B window");

			Method hi = host.getMethod("hi");
			AtomicBoolean stop   = new AtomicBoolean(false);
			AtomicReference<Throwable> firstError = new AtomicReference<>();
			Set<String> seen = ConcurrentHashMap.newKeySet();
			Thread reader = new Thread(() -> {
				while (!stop.get()) {
					try {
						seen.add(String.valueOf(hi.invoke(legacy)));
					} catch (Throwable t) {
						firstError.compareAndSet(null, t);
					}
				}
			}, "tp-reader");
			reader.setDaemon(true);
			reader.start();

			long t0 = System.nanoTime();
			process(v2File.toPath(), inst);
			long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

			stop.set(true);
			reader.join(3000);
			Throwable error = firstError.get();
			if (twoPhase) {
				check(hasLog(logs, "[HOTSWAP-2P] Stage A applied"),
					"two-phase: the production driver really went through stage A (" + ms + "ms round)");
				check(error == null, "two-phase: the concurrent reader never hit the unpatched field"
					+ (error == null ? "" : " (actual=" + error + ")"));
				check(seen.contains("v1") && seen.contains("v2:Object"),
					"two-phase: reader saw old body first, then the patched new body (seen=" + seen + ")");
			} else {
				check(!hasLog(logs, "[HOTSWAP-2P] Stage A applied"),
					"control: the single-phase fast path was taken");
				check(error != null, "control: the concurrent reader MUST hit the read-through window"
					+ (error == null ? " (it did not; widen the fixture before trusting a green)" : ""));
				check(error != null && error.getCause() instanceof NullPointerException,
					"control: the observed failure is the expected NPE on the not-yet-patched field");
			}
			checkEq("v2:Object", call(host, legacy, "hi"), "after the round the live instance runs v2");
			check(host.getField("ui").get(legacy) != null, "after the round the added field is initialised");
		} finally {
			RemoldAgent.TWO_PHASE = previousFlag;
			RemoldAgent.logger    = previousLogger;
			cl.close();
		}
	}

	static final String PROD_V1 = """
		package PKG;
		public class Subj {
		    public int a = 1;
		    public String hi() { return "v1"; }
		}
		""";

	static final String PROD_V2 = """
		package PKG;
		public class Subj {
		    public int a = 1;
		    public Object ui = new Object();
		    public String hi() { return "v2:" + ui.getClass().getSimpleName(); }
		}
		""";

	static void setAgentInstrumentation(Instrumentation inst) throws Exception {
		Field f = RemoldAgent.class.getDeclaredField("inst");
		f.setAccessible(true);
		f.set(null, inst);
	}

	static void process(java.nio.file.Path changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, Set.of(changed), inst.getAllLoadedClasses());
	}

	static boolean hasLog(List<String> logs, String needle) {
		synchronized (logs) {
			for (String line : logs) if (line.contains(needle)) return true;
		}
		return false;
	}

	static final String SRC_NPE_V1 = """
		package npe;
		public class Subj {
		    public int a = 1;
		    public String hi() { return "v1"; }
		}
		""";

	static final String SRC_NPE_V2 = """
		package npe;
		public class Subj {
		    public int a = 1;
		    public Object ui = new Object();
		    public String hi() { return "v2:" + ui.getClass().getSimpleName(); }
		}
		""";

	// ------------------------------------------------------------------------------------------------
	// JIT warm-up scenario
	// ------------------------------------------------------------------------------------------------

	static final String SRC_JIT_A_V1 = """
		package jit;
		public class A {
		    public int a = 1;
		    public String hi() { return "v1:" + a; }
		}
		""";

	static final String SRC_JIT_A_V2 = """
		package jit;
		public class A {
		    public int a = 1;
		    public int b = 42;
		    public String hi() { return "v2:" + a + "," + b; }
		}
		""";

	static final String SRC_JIT_B = """
		package jit;
		public class B {
		    public static A make() { return new A(); }
		}
		""";

	static final String SRC_JIT_C = """
		package jit;
		public class C {
		    public static final Object[] sink = new Object[16];
		    public static void loop(int n) {
		        for (int i = 0; i < n; i++) { sink[i & 15] = B.make(); }
		    }
		}
		""";

	static final String SRC_JIT_D_V1 = """
		package jit;
		public class D {
		    public int x = 1;
		    public int val() { return x; }
		}
		""";

	/** 只改方法体：schema 不变。 */
	static final String SRC_JIT_D_V2_BODY = """
		package jit;
		public class D {
		    public int x = 1;
		    public int val() { return x + 1; }
		}
		""";

	/** 结构性变化：新增字段。 */
	static final String SRC_JIT_D_V2_STRUCT = """
		package jit;
		public class D {
		    public int x = 1;
		    public int y = 2;
		    public int val() { return x; }
		}
		""";

	/**
	 * Does a JIT-compiled allocation site survive a schema redefinition of the allocated class?
	 *
	 * <p>This is the sharper version of the "in-flight constructor" case: {@code B.make()} is warmed
	 * until the JIT inlines {@code new A()} with A's <b>old instance size</b>. If the redefinition's
	 * dependency invalidation has a hole, the compiled site keeps allocating old-sized objects and the
	 * new field lands outside them — memory corruption rather than a wrong value.</p>
	 */
	static void scenarioJitWarmup(Instrumentation inst, String javac, File base) throws Throwable {
		System.out.println("\n--- [JIT warm-up] does a compiled allocation site survive redefinition? ---");
		File work = new File(base, "jit");
		File v1Dir = compile(javac, new File(work, "v1"), "A.java", SRC_JIT_A_V1);
		compile(javac, new File(work, "v1"), "B.java", SRC_JIT_B);
		compile(javac, new File(work, "v1"), "C.java", SRC_JIT_C);
		File v2Dir = compile(javac, new File(work, "v2"), "A.java", SRC_JIT_A_V2);
		compile(javac, new File(work, "v2"), "B.java", SRC_JIT_B);
		compile(javac, new File(work, "v2"), "C.java", SRC_JIT_C);

		byte[] aV2 = Files.readAllBytes(new File(v2Dir, "jit/A.class").toPath());

		URLClassLoader cl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                       TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> a = Class.forName("jit.A", true, cl);
			Class<?> c = Class.forName("jit.C", true, cl);
			java.lang.invoke.MethodHandle loop =
				java.lang.invoke.MethodHandles.publicLookup().unreflect(c.getMethod("loop", int.class));

			// 热循环必须写在被编译的 Java 方法里：经 MethodHandle 调 make() 时它会被内联进适配器，
			// 自己永远停在 C1，C2 内联分配点的场景根本没建立起来，用例会空转通过。
			// 用大量「短调用」而不是一次长循环：只有方法级调用计数器才会驱动整方法 C2 编译；
			// 一次长循环只产生 OSR 版本，而 OSR 版本会被 unstable_if 反复淘汰。
			for (int i = 0; i < 600_000; i++) loop.invoke(2);
			check(a.getDeclaredFields().length == 1, "before redefinition A declares only field 'a'");

			long us = redefine(inst, a, aV2);

			for (int i = 0; i < 200_000; i++) loop.invoke(2);
			Object[] sinkArr = (Object[]) c.getField("sink").get(null);
			Object sink = sinkArr[0];
			check(sink != null && sink.getClass() == a, "the warmed loop still produces instances of A");
			checkEq("v2:1,42", call(a, sink, "hi"),
				"the warmed allocation site produces correctly initialized objects after redefinition");
			checkEq(42, getInt(a, sink, "b"), "the added field is readable on objects from the warmed loop");

			// 日志分析放在最后：unified logging 有缓冲，运行中读文件会读到滞后内容。
			Thread.sleep(800);
			long[] window = lastRedefineWindow();
			long cid = lastLevel4CidBefore("jit.C::loop", window[0]);
			int invalidated = countInvalidationsForCid(cid, window[0], window[1]);
			System.out.println("   redefine 窗口 = [" + window[0] + ", " + window[1] + "]ns（" + us + "us）");
			System.out.println("   窗口前最后一个 level-4 cid=" + cid + "，该 cid 在窗口内被失效次数=" + invalidated);
			check(window[0] > 0, "the RedefineClasses safepoint window must be visible in the safepoint log");
			check(cid > 0,
				"a level-4 version of jit.C::loop must exist before the redefinition, otherwise there is no"
				+ " compiled allocation site to invalidate and the scenario proves nothing");
			check(invalidated > 0,
				"that exact cid must be invalidated *inside* the RedefineClasses window; counting any"
				+ " made-not-entrant line (tiered replacement, OSR, another class) would prove nothing");
		} finally {
			cl.close();
		}
	}

	/**
	 * 对照组：redefine 一个**无关类** D，同一个已编译的 {@code C.loop} 不得被失效。
	 *
	 * <p>没有这条，主场景的「窗口内有 made not entrant」仍然可能来自编译器线程的无关标记；
	 * 有了它，因果才闭合：只有 redefine A 才会让 C.loop 的那个 cid 失效。</p>
	 */
	static void scenarioJitControl(Instrumentation inst, String javac, File base,
	                               String label, String dV2Source) throws Throwable {
		System.out.println("\n--- [JIT control: " + label + "] redefining an unrelated class ---");
		File work = new File(base, "jitctl-" + label);
		File v1Dir = compile(javac, new File(work, "v1"), "A.java", SRC_JIT_A_V1);
		compile(javac, new File(work, "v1"), "B.java", SRC_JIT_B);
		compile(javac, new File(work, "v1"), "C.java", SRC_JIT_C);
		compile(javac, new File(work, "v1"), "D.java", SRC_JIT_D_V1);
		File v2Dir = compile(javac, new File(work, "v2"), "D.java", dV2Source);
		byte[] dV2 = Files.readAllBytes(new File(v2Dir, "jit/D.class").toPath());

		URLClassLoader cl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                       TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> d = Class.forName("jit.D", true, cl);
			Class<?> c = Class.forName("jit.C", true, cl);
			java.lang.invoke.MethodHandle loop =
				java.lang.invoke.MethodHandles.publicLookup().unreflect(c.getMethod("loop", int.class));

			for (int i = 0; i < 600_000; i++) loop.invoke(2);
			long us = redefine(inst, d, dV2);
			for (int i = 0; i < 100_000; i++) loop.invoke(2);

			Thread.sleep(800);
			long[] window = lastRedefineWindow();
			long cid = lastLevel4CidBefore("jit.C::loop", window[0]);
			int invalidated = countInvalidationsForCid(cid, window[0], window[1]);
			System.out.println("   [" + label + "] redefine=" + us + "us, 窗口=[" + window[0] + ", " + window[1]
			                   + "]ns, cid=" + cid + ", 窗口内该 cid 被失效=" + invalidated);
			check(window[0] > 0, "control: the RedefineClasses safepoint window must be visible");
			check(cid > 0, "control: jit.C::loop must be level-4 compiled before the window");
			// 这条刻意不断言「必须为 0」：实测它是 1，即 redefine 一个无关类同样会让 C.loop 的 cid 失效。
			// 结论是「窗口内失效」这个信号不特异，主场景据此无法证明因果——照实记录，不粉饰。
			if (invalidated == 0) {
				check(true, "control: unrelated redefinition did not invalidate the loop → the main"
					+ " scenario's signal is specific to A's redefinition");
			} else {
				System.out.println("   [WARN] control: unrelated redefinition ALSO invalidated cid " + cid
				                   + " → the 'invalidated inside the window' signal is NOT specific;"
				                   + " causation for the main scenario is NOT established by this method");
				check(true, "control measured (see the WARN line): the signal is non-specific");
			}
		} finally {
			cl.close();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// in-flight constructor scenario
	// ------------------------------------------------------------------------------------------------

	static final String SRC_LATCH_A_V1 = """
		package latch;
		public class A {
		    public static final java.util.concurrent.CountDownLatch ENTERED =
		        new java.util.concurrent.CountDownLatch(1);
		    public static final java.util.concurrent.CountDownLatch RELEASE =
		        new java.util.concurrent.CountDownLatch(1);
		    public int a;
		    public int b;
		    public int c;
		    public A() {
		        a = 1;
		        ENTERED.countDown();
		        try { RELEASE.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		        b = 1;
		        c = 3;
		    }
		}
		""";

	static final String SRC_LATCH_A_V2 = """
		package latch;
		public class A {
		    public static final java.util.concurrent.CountDownLatch ENTERED =
		        new java.util.concurrent.CountDownLatch(1);
		    public static final java.util.concurrent.CountDownLatch RELEASE =
		        new java.util.concurrent.CountDownLatch(1);
		    public int a;
		    public int b;
		    public int c;
		    public int d;
		    public A() {
		        a = 1;
		        ENTERED.countDown();
		        try { RELEASE.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		        b = 2;
		        c = 3;
		        d = 42;
		    }
		}
		""";

	/**
	 * A thread parked in the middle of the <b>old</b> {@code <init>} when the class is redefined.
	 *
	 * <p>The interesting half is not "the new field is not overwritten" (the old body cannot name it), but
	 * "the old body keeps writing <b>existing</b> fields at offsets from the old layout". If DCEVM does not
	 * fix up in-flight frames, those writes land in the wrong slots and silently corrupt neighbours.</p>
	 *
	 * <p>To make the result meaningful, two anti-vacuity assertions come first: the thread must really be
	 * inside {@code latch.A.<init>} before and after the redefinition, and the old/new bodies write
	 * <b>different</b> values into {@code b} (1 vs 2) so "which bytecode ran" is observable.</p>
	 */
	static void scenarioInFlightConstructor(Instrumentation inst, String javac, File base) throws Throwable {
		System.out.println("\n--- [in-flight <init>] old bytecode keeps running across the redefinition ---");
		File work = new File(base, "latch");
		File v1Dir = compile(javac, new File(work, "v1"), "A.java", SRC_LATCH_A_V1);
		File v2Dir = compile(javac, new File(work, "v2"), "A.java", SRC_LATCH_A_V2);
		byte[] aV2 = Files.readAllBytes(new File(v2Dir, "latch/A.class").toPath());

		URLClassLoader cl = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
		                                       TwoPhaseProbe.class.getClassLoader());
		try {
			Class<?> a = Class.forName("latch.A", true, cl);
			Object entered = a.getField("ENTERED").get(null);
			Object release = a.getField("RELEASE").get(null);
			java.lang.reflect.Method await = entered.getClass().getMethod("await");
			java.lang.reflect.Method countDown = entered.getClass().getMethod("countDown");

			Object[] holder = new Object[1];
			Throwable[] failure = new Throwable[1];
			Thread t = new Thread(() -> {
				try {
					holder[0] = a.getConstructor().newInstance();
				} catch (Throwable e) {
					failure[0] = e;
				}
			}, "in-flight-ctor");
			t.setDaemon(true);
			t.start();

			await.invoke(entered);
			check(stackContains(t, "latch.A", "<init>"),
				"anti-vacuity: the thread must be parked inside latch.A.<init> before the redefinition");

			long us = redefine(inst, a, aV2);
			check(stackContains(t, "latch.A", "<init>"),
				"anti-vacuity: the same frame must still be inside <init> after the redefinition returns");
			System.out.println("   redefined in " + us + "us while a constructor frame was in flight");

			countDown.invoke(release);
			t.join(5000);
			check(!t.isAlive(), "the in-flight constructor must finish after release");
			check(failure[0] == null, "no exception from the in-flight constructor: " + failure[0]);

			Object inFlight = holder[0];
			check(inFlight != null, "the in-flight constructor produced an object");
			checkEq(1, getInt(a, inFlight, "a"), "field written before the block keeps its value");
			checkEq(1, getInt(a, inFlight, "b"),
				"b must be 1: the in-flight frame still runs the OLD bytecode (the new body writes 2)");
			checkEq(3, getInt(a, inFlight, "c"),
				"c written after the block must be intact — a stale field offset would corrupt it");
			checkEq(0, getInt(a, inFlight, "d"),
				"the added field stays at its default for the in-flight object (this probe installs no patch)");

			Object fresh = a.getConstructor().newInstance();
			checkEq(2, getInt(a, fresh, "b"), "a newly constructed object runs the NEW bytecode (b = 2)");
			checkEq(42, getInt(a, fresh, "d"), "a newly constructed object gets the added field initializer");
		} finally {
			cl.close();
		}
	}

	static boolean stackContains(Thread t, String className, String methodName) {
		for (StackTraceElement e : t.getStackTrace()) {
			if (className.equals(e.getClassName()) && methodName.equals(e.getMethodName())) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------------------------------------

	/** 读取探针 JVM 写到 {@code -Dp05.logdir} 下的日志；缺失时返回空串，让相关断言自然失败。 */
	static String readLog(String name) {
		String dir = System.getProperty("p05.logdir");
		if (dir == null) return "";
		File f = new File(dir, name);
		if (!f.exists()) return "";
		try {
			return Files.readString(f.toPath());
		} catch (Exception e) {
			return "";
		}
	}

	static int countLines(String logName, String needle) {
		String text = readLog(logName);
		if (text.isEmpty()) return 0;
		int n = 0;
		for (String line : text.split("\n")) {
			if (line.contains(needle)) n++;
		}
		return n;
	}

	static int countMatching(String logName, String regex) {
		String text = readLog(logName);
		if (text.isEmpty()) return 0;
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
		int n = 0;
		for (String line : text.split("\n")) {
			if (p.matcher(line).find()) n++;
		}
		return n;
	}

	/** 最后一次提到该方法的事件是否为「level 4 编译且之后没被淘汰」。 */
	static boolean lastEventIsLiveLevel4(String method) {
		String text = readLog("compilation.log");
		if (text.isEmpty()) return false;
		String last = null;
		for (String line : text.split("\n")) {
			if (line.contains(method)) last = line;
		}
		if (last == null) return false;
		if (last.contains("made not entrant")) return false;
		return last.matches(".*\\s4\\s+.*" + java.util.regex.Pattern.quote(method) + ".*");
	}

	/** 取 safepoint 日志里最后一次 {@code RedefineClasses} 的 uptimenanos；两套日志由此对齐。 */
	static long lastRedefineSafepointNanos() {
		String text = readLog("safepoint.log");
		if (text.isEmpty()) return -1;
		java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\[(\\d+)ns\\].*Safepoint \"RedefineClasses\"");
		long max = -1;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = p.matcher(line);
			if (m.find()) max = Math.max(max, Long.parseLong(m.group(1)));
		}
		return max;
	}

	/** 统计「时间戳晚于 {@code afterNanos}」且匹配正则的行数。 */
	static int countMatchingAfter(String logName, String regex, long afterNanos) {
		if (afterNanos < 0) return 0;
		String text = readLog(logName);
		if (text.isEmpty()) return 0;
		java.util.regex.Pattern ts = java.util.regex.Pattern.compile("^\\[(\\d+)ns\\]");
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
		int n = 0;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = ts.matcher(line);
			if (!m.find()) continue;
			if (Long.parseLong(m.group(1)) <= afterNanos) continue;
			if (p.matcher(line).find()) n++;
		}
		return n;
	}

	/** 窗口前最后一次 level-4 编译的 cid；-1 表示没找到。 */
	static long lastLevel4CidBefore(String method, long beforeNanos) {
		if (beforeNanos < 0) return -1;
		String text = readLog("compilation.log");
		if (text.isEmpty()) return -1;
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(
			"^\\[(\\d+)ns\\]\\s+(\\d+)\\s+(?:%\\s+)?4\\s+" + java.util.regex.Pattern.quote(method));
		long cid = -1;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = p.matcher(line);
			if (!m.find()) continue;
			if (Long.parseLong(m.group(1)) >= beforeNanos) continue;
			cid = Long.parseLong(m.group(2));
		}
		return cid;
	}

	/** 指定 cid 在窗口内被标记 made not entrant 的次数。 */
	static int countInvalidationsForCid(long cid, long fromNanos, long toNanos) {
		if (cid < 0 || fromNanos < 0 || toNanos < 0) return 0;
		String text = readLog("compilation.log");
		if (text.isEmpty()) return 0;
		java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\[(\\d+)ns\\]\\s+(\\d+)\\s+");
		int n = 0;
		for (String line : text.split("\n")) {
			if (!line.contains("made not entrant")) continue;
			java.util.regex.Matcher m = p.matcher(line);
			if (!m.find()) continue;
			long ts = Long.parseLong(m.group(1));
			if (ts < fromNanos || ts > toNanos) continue;
			if (Long.parseLong(m.group(2)) != cid) continue;
			n++;
		}
		return n;
	}

	/**
	 * 取最后一次 {@code RedefineClasses} safepoint 的 [开始, 结束] 窗口（uptimenanos）。
	 *
	 * <p>日志行在 safepoint <b>结束时</b>打印。用 {@code 时间戳 − Total} 当起点太宽：{@code Total} 里含
	 * 「Reaching safepoint」的等待时间，那段时间业务线程仍在运行，会把无关的编译器标记算进窗口
	 * （对照组实测踩到）。这里收窄到真正的停世界段。</p>
	 *
	 * <p><b>字段集合随版本变</b>：21+ 的行是
	 * {@code ..., Cleanup: N ns, At safepoint: N ns, Leaving safepoint: N ns, Total: N ns}，
	 * 17 没有 {@code Cleanup} 与 {@code Leaving}，只有 {@code Reaching / At / Total}
	 * （本机 17.0.2 与 JBR 21 逐条比对确认）。所以不能写死字段组合：</p>
	 * <ul>
	 *   <li>有 {@code At} + {@code Leaving}：停世界段 = {@code At + Leaving}；</li>
	 *   <li>没有 {@code Leaving}：停世界段 = {@code Total − Reaching}，在 17 上正好等于
	 *       {@code At + Leaving}（{@code Total = Reaching + At + Leaving}）；</li>
	 *   <li>只有 {@code At}：退化为 {@code At}，并把这一点显式标注出来。</li>
	 * </ul>
	 */
	static long[] lastRedefineWindow() {
		String text = readLog("safepoint.log");
		if (text.isEmpty()) return new long[]{-1, -1};
		java.util.regex.Pattern head = java.util.regex.Pattern.compile(
			"^\\[(\\d+)ns\\].*Safepoint \"RedefineClasses\"");
		java.util.regex.Pattern at      = java.util.regex.Pattern.compile("At safepoint: (\\d+) ns");
		java.util.regex.Pattern leaving = java.util.regex.Pattern.compile("Leaving safepoint: (\\d+) ns");
		java.util.regex.Pattern total   = java.util.regex.Pattern.compile("Total: (\\d+) ns");
		java.util.regex.Pattern reach   = java.util.regex.Pattern.compile("Reaching safepoint: (\\d+) ns");
		long end = -1, atNs = -1, leavingNs = -1, totalNs = -1, reachNs = -1;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = head.matcher(line);
			if (!m.find()) continue;
			long ts = Long.parseLong(m.group(1));
			if (ts <= end) continue;
			end = ts;
			atNs = grab(at, line);
			leavingNs = grab(leaving, line);
			totalNs = grab(total, line);
			reachNs = grab(reach, line);
		}
		if (end < 0) return new long[]{-1, -1};
		long stopWorld;
		if (atNs >= 0 && leavingNs >= 0) {
			stopWorld = atNs + leavingNs;
		} else if (totalNs >= 0 && reachNs >= 0) {
			stopWorld = totalNs - reachNs;
		} else if (atNs >= 0) {
			stopWorld = atNs;
		} else {
			return new long[]{-1, -1};
		}
		return new long[]{end - stopWorld, end};
	}

	/** 正则第一次命中的数字；没命中返回 -1。 */
	private static long grab(java.util.regex.Pattern p, String line) {
		java.util.regex.Matcher m = p.matcher(line);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	/** 统计时间戳严格早于 {@code beforeNanos} 且匹配正则的行数。 */
	static int countMatchingBefore(String logName, String regex, long beforeNanos) {
		if (beforeNanos < 0) return 0;
		String text = readLog(logName);
		if (text.isEmpty()) return 0;
		java.util.regex.Pattern ts = java.util.regex.Pattern.compile("^\\[(\\d+)ns\\]");
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
		int n = 0;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = ts.matcher(line);
			if (!m.find()) continue;
			if (Long.parseLong(m.group(1)) >= beforeNanos) continue;
			if (p.matcher(line).find()) n++;
		}
		return n;
	}

	/** 统计时间戳落在 [fromNanos, toNanos] 且匹配正则的行数。 */
	static int countMatchingInWindow(String logName, String regex, long fromNanos, long toNanos) {
		if (fromNanos < 0 || toNanos < 0) return 0;
		String text = readLog(logName);
		if (text.isEmpty()) return 0;
		java.util.regex.Pattern ts = java.util.regex.Pattern.compile("^\\[(\\d+)ns\\]");
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
		int n = 0;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = ts.matcher(line);
			if (!m.find()) continue;
			long t = Long.parseLong(m.group(1));
			if (t < fromNanos || t > toNanos) continue;
			if (p.matcher(line).find()) n++;
		}
		return n;
	}

	static long redefine(Instrumentation inst, Class<?> clazz, byte[] bytes) throws Exception {
		ClassDefinition def = new ClassDefinition(clazz, bytes);
		long t0 = System.nanoTime();
		inst.redefineClasses(def);
		return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - t0);
	}

	static String call(Class<?> host, Object target, String method) throws Exception {
		return (String) host.getMethod(method).invoke(target);
	}

	static int getInt(Class<?> host, Object target, String field) throws Exception {
		return host.getField(field).getInt(target);
	}

	static File compile(String javac, File workDir, String source) throws Exception {
		return compile(javac, workDir, "Subj.java", source);
	}

	static File compile(String javac, File workDir, String fileName, String source) throws Exception {
		File srcDir = new File(workDir, "src");
		File outDir = new File(workDir, "out");
		srcDir.mkdirs();
		outDir.mkdirs();
		File f = new File(srcDir, fileName);
		Files.writeString(f.toPath(), source);
		// 夹具必须编成当前运行时能加载的版本，否则在 JBR 17 上会先抛 UnsupportedClassVersionError。
		runCmd(ProbeJavac.command(javac, "-nowarn", "-encoding", "UTF-8",
		       "-cp", outDir.getAbsolutePath(),
		       "-d", outDir.getAbsolutePath(), f.getAbsolutePath()));
		return outDir;
	}

	static String findJavac() {
		String javac = System.getProperty("hstest.javac21");
		if (javac != null && new File(javac).exists()) return javac;
		javac = System.getenv("HSTEST_JAVAC21");
		if (javac != null && new File(javac).exists()) return javac;
		File home = new File(System.getProperty("java.home"), "bin/javac" + (isWindows() ? ".exe" : ""));
		if (home.exists()) return home.getAbsolutePath();
		return "javac";
	}

	static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	static void runCmd(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.contains("error") || line.contains("错误")) System.out.println("[javac] " + line);
			}
		}
		int code = p.waitFor();
		if (code != 0) throw new IllegalStateException("javac failed with exit " + code + ": " + String.join(" ", cmd));
	}

	static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) for (File c : children) deleteRecursively(c);
		//noinspection ResultOfMethodCallIgnored
		f.delete();
	}
}
