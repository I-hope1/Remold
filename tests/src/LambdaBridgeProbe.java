import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import remold.LambdaAligner;

import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 「删除捕获变量」之后，<b>redefine 之前就已经存在的</b>那个 lambda 实例到底会怎样？
 *
 * <p>桥接方案的全部收益都建立在一条运行时事实上：老 lambda 实例（LambdaMetafactory 现场
 * spin 出来的隐藏类）在常量池里钉着 {@code Methodref Host.lambda$install$0(<旧捕获表>)V}，
 * redefine 之后这条引用解析不到，于是抛 {@code NoSuchMethodError}。若真实行为不是这样
 * ——比如 VM 会按<b>方法名</b>把老引用接到同名但异描述符的新方法上——那么"桥接"就没有收益，
 * 甚至可能已经在静默错配。</p>
 *
 * <p>JVMS 5.4.3.2 说字段/方法解析是按 {@code name + descriptor} 的，理论上必然 NSME。但这个
 * 项目里对齐器明确允许"同名不同描述符共存"（{@code LambdaAlignTortureTest} 的 UD-a 用例），
 * 收益计算全押在这条结论上，所以必须实测，不能推。</p>
 *
 * <p><b>四个场景</b>（都以"调用一次 → 让 callsite 真正解析掉 → redefine → 再调用"为骨架）：</p>
 * <ol>
 *   <li>{@code drop-same-name}：删捕获，javac 自然复用 {@code lambda$install$0} 这个名字，
 *       描述符从 {@code (String,int)V} 缩成 {@code (int)V}。<b>这是最关键的一例</b>。</li>
 *   <li>{@code drop-renamed}：删捕获，同时换了宿主方法名，老名字整个消失
 *       （对齐器改名后的形态）。</li>
 *   <li>{@code capture-added}：反向——加了捕获，描述符变大。</li>
 *   <li>{@code body-only}：对照组，捕获表不动、只改方法体。老实例必须照常工作，
 *       用来证明"能跑通"这件事本身是可达的，避免把探针自身的失效当成 VM 行为。</li>
 * </ol>
 *
 * <p>断言分两层：每个场景先用 {@code [PASS]/[FAIL]} 钉住<b>前提</b>（v1/v2 的合成方法确实是
 * 预期的那对 name+desc），再把终局行为<b>记录</b>下来。前提不成立时后续观测毫无意义。</p>
 */
public class LambdaBridgeProbe {

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

	public static void main(String[] args) throws Exception {
		System.out.println("=== Lambda bridge probe: old callsite behaviour after capture changes ===");
		System.out.println("   java.vm.name = " + System.getProperty("java.vm.name")
		                   + ", java.version = " + System.getProperty("java.version"));

		Instrumentation inst = P05Agent.instrumentation();
		if (inst == null) {
			System.out.println("[SKIP] P05Agent instrumentation unavailable; run with -javaagent:p05-agent.jar.");
			return;
		}
		if (!inst.isRedefineClassesSupported()) {
			System.out.println("[SKIP] redefineClasses not supported by this runtime.");
			return;
		}

		String javac = findJavac();
		File tempDir = Files.createTempDirectory("lambda_bridge").toFile();
		try {
			// 1. 删捕获：javac 复用同名，描述符缩水。
			scenario(inst, javac, tempDir, "drop-same-name", V1_TWO_CAPTURES, V2_ONE_CAPTURE,
			         "lambda$install$0(Ljava/lang/String;I)V", "lambda$install$0(I)V",
			         "java.lang.String", "A1",
			         "A", 1);

			// 2. 删捕获 + 宿主方法改名：老名字整个消失。
			scenario(inst, javac, tempDir, "drop-renamed", V1_TWO_CAPTURES, V2_RENAMED_HOST,
			         "lambda$install$0(Ljava/lang/String;I)V", "lambda$installB$0(I)V",
			         "java.lang.String", "A1",
			         "A", 1);

			// 3. 加捕获：描述符变大。桥接方向不成立的那一侧。
			scenario(inst, javac, tempDir, "capture-added", V2_ONE_CAPTURE, V1_TWO_CAPTURES,
			         "lambda$install$0(I)V", "lambda$install$0(Ljava/lang/String;I)V",
			         "int", "v2:1",
			         1);

			// 4. 对照组：捕获表不动，只改方法体。
			scenario(inst, javac, tempDir, "body-only", V1_TWO_CAPTURES, V2_BODY_ONLY,
			         "lambda$install$0(Ljava/lang/String;I)V", "lambda$install$0(Ljava/lang/String;I)V",
			         null, "v2:A1",
			         "A", 1);

			// 5. 端到端：把对齐器的产物 redefine 进去，老实例必须继续工作。
			bridgeEndToEnd(inst, javac, tempDir);

			// 6. 端到端（实例 lambda）：桥本身是实例方法，转发走 INVOKESPECIAL。
			bridgeEndToEndInstance(inst, javac, tempDir);

			// 7. 端到端（带形参的 SAM）：桥还必须把尾部 SAM 实参转发给新方法。
			bridgeEndToEndFunction(inst, javac, tempDir);

			// 8. 端到端（多层 Stream 嵌套，现场报障形态 S01）。
			bridgeEndToEndNestedStreams(inst, javac, tempDir);

			// 9. 端到端（lambda 体内拓扑翻转，现场报障形态 S04）。
			bridgeEndToEndShapeFlip(inst, javac, tempDir);
		} finally {
			deleteRecursively(tempDir);
		}

		System.out.println("\n=== LAMBDA BRIDGE PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
		System.out.println("   结论：只有「名字 + 描述符」都不变时，redefine 前建好的 lambda 实例才能继续工作；"
		                   + "删除或新增捕获都以 NoSuchMethodError 终止，且报错文本里带的是旧描述符 —— "
		                   + "解析键是 name+desc，不会按名字错配到同名的新方法上。"
		                   + "这正是「单向桥接」有收益、且必须保留旧描述符的原因。"
		                   + "端到端场景进一步证明：把对齐器产物 redefine 进去后，同一个实例跑的是新方法体。");
	}

	/**
	 * 单个场景：编译两版 → 建实例并跑一次（让 callsite 真正解析）→ redefine → 再跑同一次调用。
	 *
	 * @param expectV1 期望的 v1 合成方法 {@code name+desc}；不符即前提失败
	 * @param expectV2 期望的 v2 合成方法 {@code name+desc}
	 * @param expectErrorDesc 期望在 {@code NoSuchMethodError} 里看到的<b>旧描述符</b>片段；
	 *                        {@code null} 表示本场景应当正常跑通
	 * @param expectLastAfter 调用之后 {@code last} 应当是什么
	 * @param installArgs 调用 v1 的 {@code install} 时传的实参
	 */
	static void scenario(Instrumentation inst, String javac, File base, String id,
	                     String v1Src, String v2Src, String expectV1, String expectV2,
	                     String expectErrorDesc, String expectLastAfter,
	                     Object... installArgs) throws Exception {
		System.out.println("\n--- [" + id + "] ---");
		File work = new File(base, id);
		File outV1 = compile(javac, work, "v1", v1Src);
		File outV2 = compile(javac, work, "v2", v2Src);
		byte[] v2Bytes = Files.readAllBytes(new File(outV2, "lam/Host.class").toPath());

		List<String> v1Lambdas = lambdas(outV1);
		List<String> v2Lambdas = lambdas(outV2);
		System.out.println("   v1 合成方法 = " + v1Lambdas + "   v2 合成方法 = " + v2Lambdas);
		check(v1Lambdas.size() == 1 && v1Lambdas.get(0).equals(expectV1),
		      id + " 前提：v1 的合成方法是 " + expectV1);
		check(v2Lambdas.size() == 1 && v2Lambdas.get(0).equals(expectV2),
		      id + " 前提：v2 的合成方法是 " + expectV2);

		URLClassLoader cl = new URLClassLoader(
			new URL[]{outV1.toURI().toURL()}, LambdaBridgeProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("lam.Host", true, cl);
			callInstall(host, installArgs);
			Runnable old = cached(host);

			// 关键一步：先跑一次，让隐藏类的常量池真正解析到旧合成方法。
			String firstCall = invoke(old);
			String afterFirst = last(host);
			check(firstCall.equals("ok") && !afterFirst.isEmpty(),
			      id + " 前提：redefine 前老实例可正常调用（last=" + afterFirst + "）");

			inst.redefineClasses(new ClassDefinition(host, v2Bytes));

			// 老实例还是那个对象，只是它背后的方法没了 / 换了。
			String afterRedefine = invoke(old);
			String lastAfter = last(host);
			System.out.println("   redefine 后同一实例调用 -> " + afterRedefine
			                   + "   （last=" + (lastAfter.isEmpty() ? "<空>" : lastAfter) + "）");
			check(cached(host) == old, id + " 前提：cached 字段仍指向同一个实例");

			if (expectErrorDesc == null) {
				check(afterRedefine.equals("ok"), id + " 终局：老实例仍然可调用（" + afterRedefine + "）");
			} else {
				// 报错文本里带的必须是<b>旧描述符</b>：这正好证明解析键是 name+desc，
				// 没有按名字错配到同名的新方法上（那样文本里会是新描述符，或干脆不报错）。
				check(afterRedefine.startsWith("NoSuchMethodError")
				      && afterRedefine.contains(expectErrorDesc),
				      id + " 终局：按 name+desc 解析失败，且文本含旧描述符 " + expectErrorDesc
				      + "（" + afterRedefine + "）");
				// 抛在调用点之前：旧方法体一次都没跑，所以 last 不会变。
				check(lastAfter.equals(afterFirst),
				      id + " 终局：旧方法体未执行（last 保持 " + afterFirst + "）");
			}
			check(lastAfter.equals(expectLastAfter),
			      id + " 终局：last = " + expectLastAfter + "（实际 " + lastAfter + "）");
		} finally {
			cl.close();
		}
	}

	/**
	 * 端到端：把 {@link LambdaAligner#align} 的产物 redefine 进去，老实例必须继续工作。
	 *
	 * <p>这是本机制的验收点。前面四个场景证明的是"不建桥会怎样"，这里证明"建了桥会怎样"：
	 * 同一个 {@code Runnable} 实例，redefine 之前跑的是 v1 的方法体，redefine 之后
	 * 必须跑 v2 的方法体——而不是抛 {@code NoSuchMethodError}，也不是静默 no-op。</p>
	 */
	static void bridgeEndToEnd(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [bridge-end-to-end] 对齐器产物 + redefine ---");
		File work = new File(base, "e2e");
		File outV1 = compile(javac, work, "v1", V1_TWO_CAPTURES);
		File outV2 = compile(javac, work, "v2", V2_ONE_CAPTURE);
		byte[] v1 = Files.readAllBytes(new File(outV1, "lam/Host.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "lam/Host.class").toPath());

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("   对齐器建桥数 = " + LambdaAligner.LAST_RESULT.bridgeCount);
		check(LambdaAligner.LAST_RESULT.bridgeCount == 1, "前提：对齐器必须为这组编辑建一座捕获桥");

		URLClassLoader cl = new URLClassLoader(
			new URL[]{outV1.toURI().toURL()}, LambdaBridgeProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("lam.Host", true, cl);
			callInstall(host, "A", 1);
			Runnable old = cached(host);
			check(invoke(old).equals("ok") && "A1".equals(last(host)),
			      "前提：redefine 前老实例跑的是 v1 方法体（last=" + last(host) + "）");

			inst.redefineClasses(new ClassDefinition(host, aligned));

			String after = invoke(old);
			String lastAfter = last(host);
			System.out.println("   redefine 后同一实例调用 -> " + after + "（last=" + lastAfter + "）");
			check(after.equals("ok"), "建桥后老实例必须继续可调用，而不是 NoSuchMethodError");
			check("v2:1".equals(lastAfter),
			      "必须执行新方法体（last=" + lastAfter + "，期望 v2:1）");
		} finally {
			cl.close();
		}
	}

	/**
	 * 端到端（实例 lambda）：{@code () -> use(this.x)} 编译成实例 impl，捕获表里带接收者，
	 * 而 impl 描述符里没有它。桥必须跟着做实例方法，转发走 {@code INVOKESPECIAL}。
	 */
	static void bridgeEndToEndInstance(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [bridge-end-to-end-instance] 实例 lambda ---");
		File work = new File(base, "e2e-inst");
		File outV1 = compile(javac, work, "v1", V1_INSTANCE);
		File outV2 = compile(javac, work, "v2", V2_INSTANCE);
		byte[] v1 = Files.readAllBytes(new File(outV1, "lam/Host.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "lam/Host.class").toPath());

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("   对齐器建桥数 = " + LambdaAligner.LAST_RESULT.bridgeCount);
		check(LambdaAligner.LAST_RESULT.bridgeCount == 1, "前提：实例 lambda 也要建出桥");

		URLClassLoader cl = new URLClassLoader(
			new URL[]{outV1.toURI().toURL()}, LambdaBridgeProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("lam.Host", true, cl);
			Object self = host.getDeclaredConstructor().newInstance();
			host.getDeclaredMethod("d", String.class, int.class).invoke(self, "A", 1);

			Runnable old = cached(host);
			check(invoke(old).equals("ok") && "A17".equals(last(host)),
			      "前提：redefine 前老实例跑的是 v1 方法体（last=" + last(host) + "）");

			inst.redefineClasses(new ClassDefinition(host, aligned));

			String after = invoke(old);
			String lastAfter = last(host);
			System.out.println("   redefine 后同一实例调用 -> " + after + "（last=" + lastAfter + "）");
			check(after.equals("ok"), "实例 lambda 建桥后老实例必须继续可调用（实测 " + after + "）");
			check("v2:17".equals(lastAfter), "必须执行新方法体（last=" + lastAfter + "，期望 v2:17）");
		} finally {
			cl.close();
		}
	}

	/**
	 * 端到端（带形参的 SAM）：{@code Function<String,String>} 的 SAM 有尾部实参，
	 * 桥必须把它连同活下来的捕获一起转发给新方法。
	 *
	 * <p>崩溃形态（JBR 21 真机）：桥体漏压尾部 SAM 实参。重定义路径不跑字节码校验器，
	 * 漏压不会先变成 {@code VerifyError}，而是让旧桥实例在解释器/JIT 里栈欠载，触发
	 * native {@code EXCEPTION_ACCESS_VIOLATION}。</p>
	 */
	static void bridgeEndToEndFunction(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [bridge-end-to-end-function] 带形参的 SAM ---");
		File work = new File(base, "e2e-fn");
		File outV1 = compile(javac, work, "v1", V1_FUNCTION);
		File outV2 = compile(javac, work, "v2", V2_FUNCTION_DROP_CAPTURE);
		byte[] v1 = Files.readAllBytes(new File(outV1, "lam/Host.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "lam/Host.class").toPath());

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("   对齐器建桥数 = " + LambdaAligner.LAST_RESULT.bridgeCount);
		check(LambdaAligner.LAST_RESULT.bridgeCount == 1, "前提：带形参的 SAM 也必须建桥");

		URLClassLoader cl = new URLClassLoader(
			new URL[]{outV1.toURI().toURL()}, LambdaBridgeProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("lam.Host", true, cl);
			callInstall(host, "A", 1);
			Function<Object, Object> old = cachedFunction(host);
			// 先跑一次：让老隐藏类的常量池真正解析到旧合成方法（旧描述符带 SAM 形参）。
			check("A1:x".equals(apply(old, "x")), "前提：redefine 前老实例跑的是 v1 方法体");

			inst.redefineClasses(new ClassDefinition(host, aligned));

			String after = apply(old, "x");
			String lastAfter = last(host);
			System.out.println("   redefine 后同一实例 apply(\"x\") -> " + after + "（last=" + lastAfter + "）");
			check("1:x".equals(after),
			      "带形参 SAM：桥必须转发尾部实参并执行新方法体（实际 " + after + "）");
			check("1:x".equals(lastAfter), "新方法体必须读到转发过来的 SAM 实参（last=" + lastAfter + "）");
		} finally {
			cl.close();
		}
	}

	/**
	 * 端到端（S01 现场形态）：多层 Stream 嵌套 {@code flatMap + filter + map + groupingBy}，
	 * 捕获 {@code [min, prefix, factor]} + 尾部 SAM 实参 {@code List}；v2 去掉 {@code min} 捕获。
	 *
	 * <p>真机报障：redefine 后调用保留的 V1 实例，JVM 在毫秒级崩在
	 * {@code LinkResolver::resolve_invoke}。这里用同一形状在 JBR 21 上复跑。</p>
	 */
	static void bridgeEndToEndNestedStreams(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [bridge-end-to-end-nested-streams] 多层 Stream 嵌套 ---");
		File work = new File(base, "e2e-s01");
		File outV1 = compile(javac, work, "v1", V1_STREAM_NESTED, true);
		File outV2 = compile(javac, work, "v2", V2_STREAM_NESTED, true);
		byte[] v1 = Files.readAllBytes(new File(outV1, "lam/Host.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "lam/Host.class").toPath());

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("   对齐器建桥数 = " + LambdaAligner.LAST_RESULT.bridgeCount);
		check(LambdaAligner.LAST_RESULT.bridgeCount >= 1, "前提：这组编辑必须至少建一座捕获桥");

		URLClassLoader cl = new URLClassLoader(
			new URL[]{outV1.toURI().toURL()}, LambdaBridgeProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("lam.Host", true, cl);
			host.getDeclaredMethod("install").invoke(null);
			Object old = cachedRaw(host);

			List<List<Integer>> input = List.of(List.of(1, 3), List.of(2, 4));
			String before = String.valueOf(applyRaw(old, input));
			check(before.contains("v1:") && !before.contains("-v2"),
			      "前提：redefine 前老实例跑的是 v1 方法体（结果=" + before + "）");

			inst.redefineClasses(new ClassDefinition(host, aligned));

			String after = String.valueOf(applyRaw(old, input));
			System.out.println("   redefine 后同一实例 apply(rows) -> " + after);
			check(after.contains("-v2"),
			      "S01：桥必须转发 SAM 实参并执行 v2 方法体（结果=" + after + "）");
		} finally {
			cl.close();
		}
	}

	/**
	 * 端到端（S04 现场形态）：V2 在外层 lambda 内新增第 2 个内层 lambda，
	 * 外层捕获 {@code [p]} + 尾部 SAM 实参 {@code String x}；v2 丢掉 {@code p} 捕获。
	 *
	 * <p>真机报障：同一次调用崩在 {@code Klass::method_at_vtable}。</p>
	 */
	static void bridgeEndToEndShapeFlip(Instrumentation inst, String javac, File base) throws Exception {
		System.out.println("\n--- [bridge-end-to-end-shape-flip] lambda 体内拓扑翻转 ---");
		File work = new File(base, "e2e-s04");
		File outV1 = compile(javac, work, "v1", V1_SHAPE_FLIP, true);
		File outV2 = compile(javac, work, "v2", V2_SHAPE_FLIP, true);
		byte[] v1 = Files.readAllBytes(new File(outV1, "lam/Host.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "lam/Host.class").toPath());

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("   对齐器建桥数 = " + LambdaAligner.LAST_RESULT.bridgeCount);
		check(LambdaAligner.LAST_RESULT.bridgeCount >= 1, "前提：外层 lambda 必须建出捕获桥");

		URLClassLoader cl = new URLClassLoader(
			new URL[]{outV1.toURI().toURL()}, LambdaBridgeProbe.class.getClassLoader());
		try {
			Class<?> host = Class.forName("lam.Host", true, cl);
			host.getDeclaredMethod("install").invoke(null);
			Object old = cachedRaw(host);

			check("shape:k-v1".equals(applyRaw(old, "k")),
			      "前提：redefine 前老实例跑的是 v1 方法体（结果=" + applyRaw(old, "k") + "）");

			inst.redefineClasses(new ClassDefinition(host, aligned));

			String after = String.valueOf(applyRaw(old, "k"));
			System.out.println("   redefine 后同一实例 apply(\"k\") -> " + after);
			check("shape:k-v2".equals(after),
			      "S04：桥必须转发 SAM 实参并执行 v2 方法体（结果=" + after + "）");
		} finally {
			cl.close();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 夹具：捕获表变化 + 方法体变化
	// ------------------------------------------------------------------------------------------------	/** v1：两个捕获（String, int）→ {@code lambda$install$0(Ljava/lang/String;I)V}。 */
	static final String V1_TWO_CAPTURES = """
		package lam;
		public class Host {
		    public static Runnable cached;
		    public static String   last = "";
		    public static void install(String s, int k) {
		        cached = () -> last = s + k;
		    }
		}
		""";

	/** v2：去掉 String 捕获，javac 仍然把它编号成 {@code lambda$install$0} → {@code (I)V}。 */
	static final String V2_ONE_CAPTURE = """
		package lam;
		public class Host {
		    public static Runnable cached;
		    public static String   last = "";
		    public static void install(int k) {
		        cached = () -> last = "v2:" + k;
		    }
		}
		""";

	/** v2'：去掉捕获且换了宿主方法名，老名字彻底不存在。 */
	static final String V2_RENAMED_HOST = """
		package lam;
		public class Host {
		    public static Runnable cached;
		    public static String   last = "";
		    public static void installB(int k) {
		        cached = () -> last = "v2b:" + k;
		    }
		}
		""";

	/** v2''：捕获表完全不变，只改方法体。 */
	static final String V2_BODY_ONLY = """
		package lam;
		public class Host {
		    public static Runnable cached;
		    public static String   last = "";
		    public static void install(String s, int k) {
		        cached = () -> last = "v2:" + s + k;
		    }
		}
		""";

	/** 带形参的 SAM：{@code Function<String,String>}，捕获 [String a, int k] + 尾部 SAM 实参 String x。 */
	static final String V1_FUNCTION = """
		package lam;
		import java.util.function.Function;
		public class Host {
		    public static Function<String, String> cached;
		    public static String last = "";
		    public static void install(String a, int k) {
		        cached = x -> last = a + k + ":" + x;
		    }
		}
		""";

	/** 删掉 String 捕获 → {@code lambda$install$0(ILjava/lang/String;)Ljava/lang/String;}。 */
	static final String V2_FUNCTION_DROP_CAPTURE = """
		package lam;
		import java.util.function.Function;
		public class Host {
		    public static Function<String, String> cached;
		    public static String last = "";
		    public static void install(int k) {
		        cached = x -> last = k + ":" + x;
		    }
		}
		""";

	/** S01 现场形态：多层 Stream 嵌套，捕获 [min, prefix, factor] + 尾部 SAM 实参 List。 */
	static final String V1_STREAM_NESTED = """
		package lam;
		import java.util.List;
		import java.util.Map;
		import java.util.function.Function;
		import java.util.stream.Collectors;
		public class Host {
		    public static Function<List<List<Integer>>, Map<String, List<String>>> cached;
		    public static void install() {
		        int min = 2;
		        int factor = 10;
		        String prefix = "v1";
		        cached = rows -> rows.stream()
		            .flatMap(row -> row.stream()
		                .filter(n -> n > min)
		                .map(n -> prefix + ":" + (n * factor) + "-v1"))
		            .collect(Collectors.groupingBy(s -> s.substring(0, 2)));
		    }
		}
		""";

	/** S01 v2：去掉 factor 捕获（内联 10），捕获表缩水 → 桥必须转发 SAM 实参。 */
	static final String V2_STREAM_NESTED = """
		package lam;
		import java.util.List;
		import java.util.Map;
		import java.util.function.Function;
		import java.util.stream.Collectors;
		public class Host {
		    public static Function<List<List<Integer>>, Map<String, List<String>>> cached;
		    public static void install() {
		        int min = 2;
		        String prefix = "v1";
		        cached = rows -> rows.stream()
		            .flatMap(row -> row.stream()
		                .filter(n -> n > min)
		                .map(n -> prefix + ":" + (n * 10) + "-v2"))
		            .collect(Collectors.groupingBy(s -> s.substring(0, 2)));
		    }
		}
		""";

	/** S04 现场形态：外层 lambda 捕获 [p] + 尾部 SAM 实参 x，体内再建内层 Supplier。 */
	static final String V1_SHAPE_FLIP = """
		package lam;
		import java.util.function.Function;
		import java.util.function.Supplier;
		public class Host {
		    public static Function<String, String> cached;
		    public static void install() {
		        String p = "shape";
		        cached = x -> {
		            Supplier<String> inner1 = () -> p + ":" + x + "-v1";
		            return inner1.get();
		        };
		    }
		}
		""";

	/** S04 v2：丢掉 p 捕获，内层 lambda 改成常量大头 → 外层捕获表缩水 → 桥。 */
	static final String V2_SHAPE_FLIP = """
		package lam;
		import java.util.function.Function;
		import java.util.function.Supplier;
		public class Host {
		    public static Function<String, String> cached;
		    public static void install() {
		        cached = x -> {
		            Supplier<String> inner1 = () -> "shape:" + x + "-v2";
		            return inner1.get();
		        };
		    }
		}
		""";

	/** 实例 lambda：捕获表里带接收者，impl 是实例方法。 */
	static final String V1_INSTANCE = """
		package lam;
		public class Host {
		    public static Runnable cached;
		    public static String   last = "";
		    public int tag = 7;
		    public void d(String s, int k) { cached = () -> last = s + k + tag; }
		}
		""";

	/** 实例 lambda + 删掉 String 捕获。 */
	static final String V2_INSTANCE = """
		package lam;
		public class Host {
		    public static Runnable cached;
		    public static String   last = "";
		    public int tag = 7;
		    public void d(int k) { cached = () -> last = "v2:" + k + tag; }
		}
		""";

	// ------------------------------------------------------------------------------------------------
	// 工具
	// ------------------------------------------------------------------------------------------------

	static File compile(String javac, File workDir, String variant, String src) throws Exception {
		return compile(javac, workDir, variant, src, false);
	}

	/**
	 * @param debug 是否带 {@code -g}（Gradle/ModDevGradle 的 JavaCompile 默认带调试信息；
	 *              捕获桥的"按名字映射"依赖 {@code LocalVariableTable}）
	 */
	static File compile(String javac, File workDir, String variant, String src, boolean debug) throws Exception {
		File srcDir = new File(workDir, variant + "/src");
		File outDir = new File(workDir, variant + "/out");
		srcDir.mkdirs();
		outDir.mkdirs();
		File f = new File(srcDir, "Host.java");
		Files.writeString(f.toPath(), src);
		List<String> args = new ArrayList<>(List.of("-nowarn", "-encoding", "UTF-8",
			"-cp", outDir.getAbsolutePath(), "-d", outDir.getAbsolutePath()));
		if (debug) args.add("-g");
		args.add(f.getAbsolutePath());
		Process p = new ProcessBuilder(ProbeJavac.command(javac, args.toArray(new String[0])))
			.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		if (p.waitFor() != 0) throw new IllegalStateException("javac failed: " + out);
		return outDir;
	}

	/** 类里所有 {@code lambda$} 合成方法的 {@code name+desc}。 */
	static List<String> lambdas(File outDir) throws Exception {
		ClassNode cn = new ClassNode();
		new ClassReader(Files.readAllBytes(new File(outDir, "lam/Host.class").toPath()))
			.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$")) out.add(mn.name + mn.desc);
		}
		return out;
	}

	static void callInstall(Class<?> host, Object... args) throws Exception {
		for (Method m : host.getDeclaredMethods()) {
			if (!m.getName().equals("install")) continue;
			m.setAccessible(true);
			m.invoke(null, args);
			return;
		}
		throw new IllegalStateException("fixture has no install method: " + host);
	}

	static Runnable cached(Class<?> host) throws Exception {
		Field f = host.getDeclaredField("cached");
		f.setAccessible(true);
		return (Runnable) f.get(null);
	}

	@SuppressWarnings("unchecked")
	static Function<Object, Object> cachedFunction(Class<?> host) throws Exception {
		Field f = host.getDeclaredField("cached");
		f.setAccessible(true);
		return (Function<Object, Object>) f.get(null);
	}

	/** 不关心 SAM 形状时直接取对象（S01 的 Function 实参是 List，用 {@link #applyRaw} 调）。 */
	static Object cachedRaw(Class<?> host) throws Exception {
		Field f = host.getDeclaredField("cached");
		f.setAccessible(true);
		return f.get(null);
	}

	/** 用反射调旧实例的 SAM，异常压成摘要（不带栈，探针输出要可扫）。 */
	@SuppressWarnings("unchecked")
	static Object applyRaw(Object fn, Object arg) {
		try {
			return ((Function<Object, Object>) fn).apply(arg);
		} catch (Throwable t) {
			return "THREW " + describe(t);
		}
	}

	/** 调用旧实例的 SAM：返回业务结果，异常压成摘要（不带栈，探针输出要可扫）。 */
	static String apply(Function<Object, Object> f, Object arg) {
		try {
			return String.valueOf(f.apply(arg));
		} catch (Throwable t) {
			return describe(t);
		}
	}

	static String last(Class<?> host) throws Exception {
		Field f = host.getDeclaredField("last");
		f.setAccessible(true);
		return (String) f.get(null);
	}

	static String invoke(Runnable r) {
		try {
			r.run();
			return "ok";
		} catch (Throwable t) {
			return describe(t);
		}
	}

	/** 带 cause 链的异常摘要：{@code LinkageError} 的真实原因常藏在包装里。 */
	static String describe(Throwable t) {
		if (t == null) return "none";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; t != null && i < 4; i++, t = t.getCause()) {
			if (i > 0) sb.append(" <- ");
			sb.append(t.getClass().getSimpleName());
			if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
		}
		return sb.toString();
	}

	static String findJavac() {
		String javac = System.getProperty("hstest.javac21");
		if (javac != null && new File(javac).exists()) return javac;
		javac = System.getenv("HSTEST_JAVAC21");
		if (javac != null && new File(javac).exists()) return javac;
		File home = new File(System.getProperty("java.home"), "bin/javac"
			+ (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : ""));
		if (home.exists()) return home.getAbsolutePath();
		return "javac";
	}

	static void deleteRecursively(File f) {
		File[] kids = f.listFiles();
		if (kids != null) for (File k : kids) deleteRecursively(k);
		f.delete();
	}
}
