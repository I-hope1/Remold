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
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end probe for the two-phase protocol, run on a DCEVM runtime <b>outside</b> the production
 * driver: {@code stage A -> redefine -> stage B (patch) -> stage C -> redefine}.
 *
 * <p>It chains the three pieces that already exist as pure functions and proves the orchestration is
 * sound before any of it is wired into {@code RemoldAgent.processChanges}:</p>
 * <ol>
 *   <li>{@link StageASkeleton} synthesises stage A (new shape, old bodies);</li>
 *   <li>{@link ConstructorTailInjector} closes the constructor gap for objects created after stage A;</li>
 *   <li>{@code InitFix} supplies the real patch driver for stage B — note it is fed the <b>stage C</b>
 *       bytes, because stage A's restored constructor no longer contains the new field initializer.</li>
 * </ol>
 *
 * <p>Two scenarios run: one with tail injection, and one without as a control that demonstrates the
 * exact defect injection removes. The interesting instance is the one created <b>after</b> the stage-B
 * enumeration and <b>before</b> stage C: that is the residual gap stage A leaves behind.</p>
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
			scenarioJitWarmup(inst, javac, base);
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
		    public static Object sink;
		    public static void loop(int n) {
		        Object last = null;
		        for (int i = 0; i < n; i++) { last = B.make(); }
		        sink = last;
		    }
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
			Object sink = c.getField("sink").get(null);
			check(sink != null && sink.getClass() == a, "the warmed loop still produces instances of A");
			checkEq("v2:1,42", call(a, sink, "hi"),
				"the warmed allocation site produces correctly initialized objects after redefinition");
			checkEq(42, getInt(a, sink, "b"), "the added field is readable on objects from the warmed loop");

			// 日志分析放在最后：unified logging 有缓冲，运行中读文件会读到滞后内容。
			Thread.sleep(800);
			long[] window = lastRedefineWindow();
			int liveBefore = countMatchingBefore("compilation.log", "\\s4\\s+jit\\.C::loop", window[0]);
			int invalidatedInside = countMatchingInWindow("compilation.log",
				"jit\\.C::loop.*made not entrant", window[0], window[1]);
			System.out.println("   redefine 窗口 = [" + window[0] + ", " + window[1] + "]ns（" + us + "us）");
			System.out.println("   窗口前已有 level-4 编译行数=" + liveBefore
			                   + "，窗口内 made-not-entrant 行数=" + invalidatedInside);
			check(window[0] > 0, "the RedefineClasses safepoint window must be visible in the safepoint log");
			check(liveBefore > 0,
				"a level-4 version of jit.C::loop must exist before the redefinition, otherwise there is no"
				+ " compiled allocation site to invalidate and the scenario proves nothing");
			check(invalidatedInside > 0,
				"that compiled loop must be invalidated *inside* the RedefineClasses window;"
				+ " made-not-entrant lines from ordinary tiered compilation prove nothing");
		} finally {
			cl.close();
		}
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

	/**
	 * 取最后一次 {@code RedefineClasses} safepoint 的 [开始, 结束] 窗口（uptimenanos）。
	 *
	 * <p>日志行是在 safepoint <b>结束时</b>打印的，所以直接用它的时间戳会漏掉窗口内发生的事件
	 * （实测编译日志里的 {@code made not entrant} 就落在 Total 那一段里）。开始时刻 = 时间戳 − Total。</p>
	 */
	static long[] lastRedefineWindow() {
		String text = readLog("safepoint.log");
		if (text.isEmpty()) return new long[]{-1, -1};
		java.util.regex.Pattern p = java.util.regex.Pattern.compile(
			"^\\[(\\d+)ns\\].*Safepoint \"RedefineClasses\".*Total: (\\d+) ns");
		long end = -1, total = 0;
		for (String line : text.split("\n")) {
			java.util.regex.Matcher m = p.matcher(line);
			if (!m.find()) continue;
			long ts = Long.parseLong(m.group(1));
			if (ts > end) {
				end = ts;
				total = Long.parseLong(m.group(2));
			}
		}
		return new long[]{end < 0 ? -1 : end - total, end};
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
		runCmd(javac, "-nowarn", "-encoding", "UTF-8",
		       "-cp", outDir.getAbsolutePath(),
		       "-d", outDir.getAbsolutePath(), f.getAbsolutePath());
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
