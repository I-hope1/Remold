import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import remold.AnnotationTransformer;
import remold.LambdaAligner;
import remold.OrphanBreakerPredicate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 捕获桥（单向：只支持"删除捕获变量"）的对齐期判定。
 *
 * <p>桥要解决的问题：老 lambda 实例的隐藏类在常量池里钉着 {@code (name, 旧描述符)}，
 * 删掉一个捕获变量后这条引用就解析不到了。{@code LambdaBridgeProbe} 实测确认它会以
 * {@code NoSuchMethodError} 终止（且报错文本里带的是旧描述符，说明解析键是 name+desc）。
 * 对齐器过去只能给它一个幽灵桩——对 void 回调来说就是"点了没反应"。</p>
 *
 * <p>本类钉的是<b>判定逻辑</b>，不需要 DCEVM：给 {@link LambdaAligner#align} 喂两份 javac
 * 产物，检查产物里那座桥的形状。</p>
 *
 * <p><b>三道门</b>（任一不成立都必须退回幽灵桩）：SAM 不变 / 宿主相同 / 映射唯一。
 * 外加"只支持删除方向"与布局自检。每个负向用例都配一条正向对照，避免"全都不建桥"
 * 也能全绿。</p>
 */
public class LambdaBridgeAlignTest {

	// ------------------------------------------------------------------------------------------------
	// 正向：删掉捕获变量，建桥
	// ------------------------------------------------------------------------------------------------

	@Test
	void droppingACaptureBuildsABridgeOntoTheNewBody(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_CAPTURES).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_DROP_CAPTURE).resolve("B.class"));

		// 前提：javac 复用同一个名字，描述符缩水。桥接的整个前提就建立在这个形态上。
		assertNotNull(byKey(parse(v1), "lambda$f$0", "(Ljava/lang/String;I)V"),
			"夹具前提：v1 的合成方法是 lambda$f$0(Ljava/lang/String;I)V");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(I)V"),
			"夹具前提：v2 的合成方法是 lambda$f$0(I)V");

		LambdaAligner.LAST_RESULT = null;
		ClassNode out = parse(LambdaAligner.align(v1, v2));

		MethodNode bridge = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "旧键必须仍然存在（否则老实例会抛 NoSuchMethodError）：" + table(out));
		assertFalse(containsCall(bridge, "onOrphanInvoked"),
			"这座桥不该是幽灵桩——它要转发给新方法，不是返回默认值");

		// 桥体：LDC loc → onBridgeInvoked → 只加载活下来的那个 int 形参（槽 1）→ 调新方法。
		List<AbstractInsnNode> real = real(bridge);
		assertTrue(real.get(0) instanceof LdcInsnNode, "桥体第一条必须是 location 常量");
		assertTrue(real.get(1) instanceof MethodInsnNode m1 && m1.name.equals("onBridgeInvoked"),
			"桥体必须走 onBridgeInvoked 标记（不能复用 onOrphanInvoked 的熔断入口）");
		assertTrue(hasVar(bridge, Opcodes.ILOAD, 1),
			"必须加载活下来的 int 形参（槽 1）");
		assertFalse(hasVar(bridge, Opcodes.ALOAD, 0),
			"被删掉的 String 捕获（槽 0）不能再被加载");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("lambda$f$0", call.name, "桥必须转发给 v2 的合成方法");
		assertEquals("(I)V", call.desc, "转发目标用的是 v2 的描述符");
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), "静态 lambda 用 INVOKESTATIC 转发");

		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount, "本轮应当只建一座桥");
		assertNotNull(byKey(out, "lambda$f$0", "(I)V"), "新方法本身必须原样保留（桥不改名）");
	}

	// ------------------------------------------------------------------------------------------------
	// 带形参的 SAM（Function/Consumer 这类）：桥还必须转发尾部 SAM 实参
	// ------------------------------------------------------------------------------------------------

	/**
	 * 捕获表缩水 + SAM 本身带形参时，桥体必须把尾部 SAM 实参一起转发。
	 *
	 * <p>失效形态：桥体只压捕获表、漏掉尾部 SAM 实参。重定义路径不跑字节码校验器，漏压不会
	 * 变成 {@code VerifyError}；缺失的那个实参可能是 null（JBR 21 探针实测 NPE），也可能是
	 * JIT 下的垃圾引用（真机报告形态 {@code EXCEPTION_ACCESS_VIOLATION}）。</p>
	 *
	 * <p>本用例不需要 DCEVM：对齐产物直接在独立 ClassLoader 里定义，按"老隐藏类"的调用形态
	 * 调用旧键方法，断言落在业务终局（返回的字符串），而不是"桥建出来了"。</p>
	 */
	@Test
	void parameterizedSamForwardsItsArguments(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_FUNCTION_SAM).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_FUNCTION_SAM_DROP_CAPTURE).resolve("B.class"));

		// 前提：合成方法 = [捕获...] + [SAM 形参]，捕获按使用顺序排在前面。
		assertNotNull(byKey(parse(v1), "lambda$make$0", "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;"),
			"夹具前提：v1 的合成方法是 String 捕获 + int 捕获 + SAM 形参");
		assertNotNull(byKey(parse(v2), "lambda$make$0", "(ILjava/lang/String;)Ljava/lang/String;"),
			"夹具前提：v2 删掉 String 捕获后，尾部 SAM 形参仍在描述符里");

		byte[] aligned = LambdaAligner.align(v1, v2);
		ClassNode out = parse(aligned);
		MethodNode bridge = byKey(out, "lambda$make$0", "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;");
		assertNotNull(bridge, "旧键必须仍然存在（否则老实例会抛 NoSuchMethodError）：" + table(out));
		assertFalse(containsCall(bridge, "onOrphanInvoked"),
			"带形参的 SAM 也必须建桥：老实例的 apply 应当转发给新方法，而不是返回默认值");

		// 桥体：先搬活下来的 int 捕获（槽 1），再压尾部 SAM 实参（槽 2）。顺序不能反。
		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(2, pushes.size(),
			"桥体必须压两个实参（活下来的捕获 + 尾部 SAM 形参），实际压了 " + pushes.size());
		assertLoad(pushes.get(0), Opcodes.ILOAD, 1, "第一个实参是活下来的 int 捕获（槽 1）");
		assertLoad(pushes.get(1), Opcodes.ALOAD, 2, "第二个实参是尾部 SAM 形参（槽 2）：漏压它会栈欠载");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("lambda$make$0", call.name, "桥必须转发给 v2 的合成方法");
		assertEquals("(ILjava/lang/String;)Ljava/lang/String;", call.desc, "转发目标用的是 v2 的描述符");

		// 终局：定义对齐产物，按老描述符调用旧键方法 → 必须拿到新方法体的返回值。
		Class<?> b = define(aligned);
		java.lang.reflect.Method old = b.getDeclaredMethod(
			"lambda$make$0", String.class, int.class, String.class);
		old.setAccessible(true);
		assertEquals("7:x", (Object) old.invoke(null, "A", 7, "x"),
			"桥必须把尾部 SAM 实参转发给新方法体；漏压时这里会 VerifyError 或直接崩 VM");
	}

	/** 第二轮：带形参 SAM 的桥必须仍被认出来并重新生成（不能退化成死幽灵）。 */
	@Test
	void secondRoundKeepsTheParameterizedSamBridge(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_FUNCTION_SAM).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_FUNCTION_SAM_DROP_CAPTURE).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_FUNCTION_SAM_BODY_ONLY).resolve("B.class"));

		byte[] r1 = LambdaAligner.align(v1, v2);
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount, "第一轮必须建出桥");

		byte[] aligned = LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames);
		ClassNode out = parse(aligned);
		MethodNode bridge = byKey(out, "lambda$make$0", "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;");
		assertNotNull(bridge, "第二轮桥不能消失（否则老实例退化成死幽灵）：" + table(out));
		assertFalse(containsCall(bridge, "onOrphanInvoked"),
			"第二轮桥必须仍是转发桩：把尾部 SAM 实参算进 sources 会让长度校验对不上，进而整座桥退化成幽灵");
		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("(ILjava/lang/String;)Ljava/lang/String;", call.desc, "重生成之后目标描述符不变");

		Class<?> b = define(aligned);
		java.lang.reflect.Method old = b.getDeclaredMethod(
			"lambda$make$0", String.class, int.class, String.class);
		old.setAccessible(true);
		assertEquals("7|x", (Object) old.invoke(null, "A", 7, "x"),
			"第二轮也必须把尾部 SAM 实参转发给新方法体");
	}

	/** S23 现场形态 V3：外层 varargs lambda 捕获 [a, b, extra]，内层 AutoCloseable 捕获 sb。 */
	static final String V3_MIXED_RUN = """
		public class Pair {
		    public interface VarFn { String join(String prefix, String... rest); }
		    public static VarFn retained;
		    private final String a = "rec";
		    private final int b = 7;
		    public static void init() { retained = new Pair().buildFn("ext"); }
		    public VarFn buildFn(String extra) {
		        return (prefix, rest) -> {
		            final StringBuilder sb = new StringBuilder();
		            try (AutoCloseable c = () -> sb.append(":closed-v3")) {
		                sb.append(a).append(":").append(b).append(":").append(extra)
		                  .append(":").append(prefix).append(":").append(String.join(",", rest));
		            } catch (Exception e) {
		                throw new RuntimeException(e);
		            }
		            return sb.append("-v3").toString();
		        };
		    }
		}
		""";

	/** S23 现场形态 V4：外层去掉全部捕获（前插兄弟 Supplier），内层改成 closed-v4。 */
	static final String V4_MIXED_RUN = """
		import java.util.function.Supplier;
		public class Pair {
		    public interface VarFn { String join(String prefix, String... rest); }
		    public static VarFn retained;
		    private final String a = "rec";
		    private final int b = 7;
		    public static void init() { retained = new Pair().buildFn("ext"); }
		    public VarFn buildFn(String extra) {
		        Supplier<String> sib = () -> a + ":" + b + ":" + extra;
		        if (sib.get().isEmpty()) return (p, r) -> "";
		        return (prefix, rest) -> {
		            final StringBuilder sb = new StringBuilder();
		            try (AutoCloseable c = () -> sb.append(":closed-v4")) {
		                sb.append("NO_CAP:").append(prefix).append(":").append(String.join(",", rest));
		            } catch (Exception e) {
		                throw new RuntimeException(e);
		            }
		            return sb.append("-v4").toString();
		        };
		    }
		}
		""";

	/** S23 现场形态：内层被原位更新时，外层旧实例仍保留旧体（旧实例语义优先），混跑风险由 warn 点名。 */
	@Test
	void retainedBodyKeepsOldBodyEvenWhenNestedLambdaWasUpdated(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_MIXED_RUN).resolve("Pair.class"));
		byte[] v4 = Files.readAllBytes(compile(javac, tmp, "v4", V4_MIXED_RUN).resolve("Pair.class"));

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode out = parse(LambdaAligner.align(v3, v4));

		boolean retained = false;
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("GHOST_KEEP_BODY".equals(e.kind) && e.oldName != null && e.oldName.startsWith("lambda$buildFn$1")) {
				retained = true;
			}
		}
		assertTrue(retained, "旧实例语义优先：外层孤儿必须保留旧体（不得退回默认值桩）");

		MethodNode outer = null;
		for (MethodNode mn : out.methods) {
			if (mn.name.startsWith("lambda$buildFn$1")) outer = mn;
		}
		assertNotNull(outer, "旧外层必须仍留桩兜住老 callsite");
		// 保留体与默认值桩都会调用 onOrphanInvoked；用描述符区分：
		// 保留体走 (String,boolean,boolean) 的 retained 标记，默认值桩走 (String,boolean)（可能带委托）。
		boolean retainedMarker = false;
		boolean stubMarker = false;
		for (AbstractInsnNode i : outer.instructions) {
			if (i instanceof MethodInsnNode m && m.name.equals("onOrphanInvoked")) {
				if ("(Ljava/lang/String;ZZ)V".equals(m.desc)) retainedMarker = true;
				else stubMarker = true;
			}
		}
		assertTrue(retainedMarker, "保留体必须带 retained 标记（旧实例语义优先）");
		assertFalse(stubMarker, "不得退回默认值桩");
		assertFalse(containsCall(outer, "buildFn"), "不得转发到新方法（旧实例语义不同）");
	}

	/**
	 * S45 现场形态：同宿主 2 条同形 lambda 同时丢捕获时，按宿主调用序列序号消歧（长度一致 ⇒ 无插入/删除），
	 * 两个旧键各接一个目标，而不是因为"两个候选都通过 planBridge"就整体放弃。
	 */
	@Test
	void ambiguousCaptureBridgesDisambiguateByHostOrdinal(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_AMBIGUOUS_DROP).resolve("SwitchHost.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_AMBIGUOUS_DROP).resolve("SwitchHost.class"));

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode out = parse(LambdaAligner.align(v1, v2));

		List<LambdaAligner.AlignmentEvent> bridges = new ArrayList<>();
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if ("BRIDGE".equals(e.kind)) bridges.add(e);
		}
		assertEquals(2, bridges.size(),
			"both old capture-dropped lambdas must get a bridge by host ordinal: " + bridges);
		for (LambdaAligner.AlignmentEvent e : bridges) {
			assertEquals(e.oldName.replace("$0", "$X").replace("$1", "$X"),
				e.newName.replace("$0", "$X").replace("$1", "$X"),
				"bridge must keep the same ordinal (old " + e.oldName + " -> new " + e.newName + "): " + bridges);
		}
	}

	/** 多轮保留体：V2 里 lambda 被删、helper 仍在 → 保留旧体；V3 删掉 helper → 必须回退默认值桩。 */
	static final String V1_RETAINED_HELPER = """
		import java.util.function.Supplier;
		public class RetainedHost {
		    public static Supplier<String> s;
		    static String helper() { return "h"; }
		    static void make() { s = () -> "v1:" + helper(); }
		}
		""";

	static final String V2_RETAINED_HELPER = """
		import java.util.function.Supplier;
		public class RetainedHost {
		    public static Supplier<String> s;
		    static String helper() { return "h"; }
		    static void make() { s = null; }
		}
		""";

	static final String V3_HELPER_DELETED = """
		import java.util.function.Supplier;
		public class RetainedHost {
		    public static Supplier<String> s;
		    static void make() { s = null; }
		}
		""";

	/**
	 * 多轮保留体：上一轮的保留体幽灵在本轮成员被删后必须重新过存活校验（否则旧实例执行时
	 * NoSuchMethodError / NoSuchFieldError）。
	 */
	@Test
	void carriedOverRetainedBodyIsRevalidatedWhenItsMembersAreDeleted(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "rh/v1", V1_RETAINED_HELPER).resolve("RetainedHost.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "rh/v2", V2_RETAINED_HELPER).resolve("RetainedHost.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "rh/v3", V3_HELPER_DELETED).resolve("RetainedHost.class"));

		// 第一轮：lambda 被删、helper 仍在 → 保留旧体（里面的 helper() 调用还在）。
		byte[] aligned12 = LambdaAligner.align(v1, v2);
		MethodNode kept = byKey(parse(aligned12), "lambda$make$0", "()Ljava/lang/String;");
		assertNotNull(kept, "第一轮必须保留旧体幽灵");
		assertTrue(containsCall(kept, "helper"), "第一轮保留体应仍引用 helper（前提校验）");

		// 第二轮：helper 被删 → 搬运前必须重新校验，回退默认值桩，不得保留 helper() 调用。
		byte[] aligned23 = LambdaAligner.align(aligned12, v3);
		MethodNode carried = byKey(parse(aligned23), "lambda$make$0", "()Ljava/lang/String;");
		assertNotNull(carried, "旧键必须仍在（要么保留体要么默认值桩）");
		assertFalse(containsCall(carried, "helper"),
			"helper 已删除：不得继续搬运调用 helper 的旧体（否则运行时 NoSuchMethodError）");
		assertTrue(containsCall(carried, "onOrphanInvoked"), "应回退默认值桩并留痕");
	}

	/** S14 现场形态：两个宿主方法交换 this 形状时，旧键不得被跨宿主桥接/改名。 */
	@Test
	void thisShapeSwapAcrossHostsIsNotBridged(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_THIS_FLIP).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_THIS_FLIP).resolve("B.class"));

		LambdaAligner.TEST_EVENTS.clear();
		ClassNode out = parse(LambdaAligner.align(v1, v2));

		StringBuilder diag = new StringBuilder("events:\n");
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			diag.append("  ").append(e).append('\n');
		}
		diag.append("table=").append(table(out));

		// 桥的硬前提是 sameHost（bridgeGatesHold）；任何跨宿主的 BRIDGE 事件都是错配。
		for (LambdaAligner.AlignmentEvent e : LambdaAligner.TEST_EVENTS) {
			if (!"BRIDGE".equals(e.kind) && !"BRIDGE_REPOINT".equals(e.kind) && !"BRIDGE_SYNTH".equals(e.kind)) {
				continue;
			}
			assertEquals(hostOf(e.oldName), hostOf(e.newName),
				"跨宿主桥接是静默错值（S14）：旧键 " + e.oldName + " 不应接到 " + e.newName + "\n" + diag);
		}

		// 旧键必须仍存在（兜住老 callsite），且不能变成转发到另一宿主的桥。
		for (String oldKey : new String[]{
			"lambda$makeStaticToInst$2", "lambda$makeInstToStatic$0", "lambda$makeInstToStatic$1"}) {
			MethodNode mn = byKey(out, oldKey, "(Ljava/lang/String;)Ljava/lang/String;");
			if (mn == null) {
				continue;                     // 该形态本就没有这个键
			}
			if (containsCall(mn, "onBridgeInvoked")) {
				MethodInsnNode call = onlyCoreCall(mn);
				assertEquals(hostOf(oldKey), hostOf(call.name),
					"旧键 " + oldKey + " 的桥目标必须在同一宿主（实际 " + call.name + "）\n" + diag);
			}
		}
	}

	/** {@code lambda$makeInstToStatic$3} → {@code makeInstToStatic}；非合成名原样返回。 */
	static String hostOf(String methodName) {
		if (methodName == null) return "";
		int first = methodName.indexOf('$');
		int last = methodName.lastIndexOf('$');
		return first < 0 || last <= first ? methodName : methodName.substring(first + 1, last);
	}

	@Test
	void secondRoundKeepsTheBridgeAndRepointsIt(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_CAPTURES).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_DROP_CAPTURE).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_BODY_ONLY).resolve("B.class"));

		// 第一轮：建桥。基准必须是"上一轮对齐之后的字节码"，不是原始编译产物。
		byte[] r1 = LambdaAligner.align(v1, v2);
		assertNotNull(byKey(parse(r1), "lambda$f$0", "(Ljava/lang/String;I)V"), "第一轮必须建出桥");
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);

		// 第二轮：用户只改了方法体，没再动捕获表。
		// 这里的失败形态是"桥被当成又被删了一次，重新幽灵化"——第一轮成果当场作废。
		ClassNode out = parse(LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames));

		MethodNode bridge = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "第二轮桥不能消失：" + table(out));
		assertFalse(containsCall(bridge, "onOrphanInvoked"),
			"第二轮桥必须仍是转发桩，不能退化成死幽灵（getPureDelegate 的严格槽位判据会把它判成 null）");
		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("lambda$f$0", call.name, "重生成之后目标名仍是本轮定稿的名字");
		assertEquals("(I)V", call.desc);
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount, "第二轮应当重新生成同一座桥");
	}

	// ------------------------------------------------------------------------------------------------
	// 负向：每一道门各一个用例
	// ------------------------------------------------------------------------------------------------

	@Test
	void addingACaptureIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V2_DROP_CAPTURE).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V1_TWO_CAPTURES).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"新增捕获必须拒绝：老实例没有那个值，桥只能编 0/null 塞进去，那是静默假值");
		MethodNode ghost = byKey(out, "lambda$f$0", "(I)V");
		assertNotNull(ghost, "旧键仍然要留桩（兜住 NoSuchMethodError）");
		assertTrue(containsCall(ghost, "onOrphanInvoked"), "拒绝桥接的形态是幽灵桩");
	}

	@Test
	void changedCaptureTypeIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_CAPTURES).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_LONG_CAPTURE).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"捕获类型变了就不是子序列，不能靠位置硬搬");
		assertTrue(containsCall(byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V"), "onOrphanInvoked"),
			"拒绝桥接的形态是幽灵桩");
	}

	@Test
	void ambiguousDuplicateCaptureTypesAreRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_INTS).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ONE_INT).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"[int,int] → [int] 有两种删法，任选其一都可能搬错值：必须拒绝");
		assertTrue(containsCall(byKey(out, "lambda$f$0", "(II)V"), "onOrphanInvoked"),
			"拒绝桥接的形态是幽灵桩");
	}

	@Test
	void changedFunctionalInterfaceIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_CAPTURES).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_INTCONSUMER).resolve("B.class"));

		// 这一例的捕获表恰好是合法子序列（[I] ⊂ [String,int]），唯一挡住它的就是 SAM 门：
		// Runnable 的 ()V 与 IntConsumer 的 (I)V 不同，老实例对象本身还绑在旧接口上。
		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"功能接口变了就不是「同一个 lambda 少了个捕获」，桥接 impl 救不了接口不匹配");
		assertTrue(containsCall(byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V"), "onOrphanInvoked"),
			"拒绝桥接的形态是幽灵桩");
	}

	@Test
	void bridgeDegradesToGhostWhenTheTargetDisappears(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_CAPTURES).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_DROP_CAPTURE).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_NO_LAMBDA).resolve("B.class"));

		byte[] r1 = LambdaAligner.align(v1, v2);
		assertNotNull(byKey(parse(r1), "lambda$f$0", "(Ljava/lang/String;I)V"), "第一轮必须建出桥");

		// 第二轮把 lambda 整个删了：目标没了，桥只能退化成幽灵桩（不是继续转发到空气）。
		ClassNode out = parse(LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames));
		MethodNode stub = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(stub, "旧键仍要留桩：" + table(out));
		assertTrue(containsCall(stub, "onOrphanInvoked"),
			"目标消失后必须退化成幽灵桩，不能还挂着转发");
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount, "本轮没有可转发的目标");
	}

	// ------------------------------------------------------------------------------------------------
	// 接口宿主：合成方法在接口里，常量池条目是 InterfaceMethodref
	// ------------------------------------------------------------------------------------------------

	/**
	 * 接口 {@code default} 方法里的 lambda，合成方法是接口自己的 {@code private static} 方法。
	 * 桥转发它的调用指令必须带 {@code itf=true}，否则常量池里会写成 {@code Methodref}，
	 * 解析时直接失败。
	 */
	@Test
	void bridgeIntoAnInterfaceUsesInterfaceMethodref(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_INTERFACE).resolve("I.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_INTERFACE).resolve("I.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(Ljava/lang/String;I)V"), "夹具前提：接口里的合成方法");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(I)V"), "夹具前提：删掉 String 捕获");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "接口宿主同样要建桥：" + table(out));
		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("lambda$f$0", call.name);
		assertEquals("(I)V", call.desc);
		assertTrue(call.itf, "接口宿主的转发调用必须是 InterfaceMethodref（itf=true）");
	}

	// ------------------------------------------------------------------------------------------------
	// 重排与槽位证明
	// ------------------------------------------------------------------------------------------------

	/**
	 * 只交换两个捕获的使用顺序：{@code a + b} → {@code b + a}。
	 *
	 * <p>javac 按<b>首次引用顺序</b>排列捕获，所以描述符从 {@code (String,int)} 变成
	 * {@code (int,String)}。旧值集合完全一样，只是位置换了——这类编辑此前会被
	 * "新捕获表必须是旧表的子序列"判否，退化成幽灵桩（void 回调静默 no-op）。</p>
	 */
	@Test
	void reorderOnlyEditIsBridgedWithReorderedLoads(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_CAPTURES).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_SWAPPED_USE).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(Ljava/lang/String;I)V"), "夹具前提：v1 捕获顺序 (String,int)");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(ILjava/lang/String;)V"), "夹具前提：v2 捕获顺序 (int,String)");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "重排也必须建桥：" + table(out));
		assertFalse(containsCall(bridge, "onOrphanInvoked"), "不能退化成幽灵桩");

		// 老实例按 (String,int) 调用；新方法要 (int,String)，所以先压 int（老槽 1）再压 String（老槽 0）。
		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(2, loads.size(), "桥必须恰好加载两个形参");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1, "第一个压栈的必须是 int（新方法的第 0 个形参）");
		assertLoad(loads.get(1), Opcodes.ALOAD, 0, "第二个压栈的必须是 String（新方法的第 1 个形参）");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("(ILjava/lang/String;)V", call.desc, "转发目标用 v2 的描述符");
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);
	}

	/**
	 * 两个同类型捕获里只留下一个：类型层面无解（{@code [int,int] → [int]} 有两种删法），
	 * 但宿主描述符没变 ⇒ 槽号可比 ⇒ 能<b>证明</b>留下的是哪一个。
	 */
	@Test
	void slotIdentityResolvesSameTypeAmbiguity(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_INTS).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ONE_INT_SAME_HOST).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(II)V"), "夹具前提：v1 捕获 (int,int)");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(I)V"), "夹具前提：v2 只捕获第二个 int");
		assertEquals(byKey(parse(v1), "f", "(II)V") != null, byKey(parse(v2), "f", "(II)V") != null,
			"夹具前提：宿主描述符不变，槽号才可比");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(II)V");
		assertNotNull(bridge, "槽号证明成立时必须建桥（类型推断在这里是无解的）：" + table(out));
		assertFalse(containsCall(bridge, "onOrphanInvoked"));

		// 留下的那个 int 是宿主槽 1（变量 b），所以桥必须加载槽 1 而不是槽 0。
		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "桥只该加载一个形参");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1, "必须加载宿主槽 1（b），不能按位置取槽 0（a）");
		assertEquals("(I)V", onlyCoreCall(bridge).desc);
	}

	/**
	 * 实例 lambda：工厂描述符里带接收者（{@code LC;}），而 impl 是实例方法、描述符里不含它。
	 * 布局自检必须先剥掉第一个捕获才能成立，否则实例 lambda 永远建不出桥。
	 */
	@Test
	void instanceLambdaKeepsItsReceiverOutOfTheDescriptor(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_INSTANCE).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_INSTANCE).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$d$0", "(Ljava/lang/String;I)V"), "夹具前提：实例 impl 不含接收者");
		assertNotNull(byKey(parse(v2), "lambda$d$0", "(I)V"), "夹具前提：删掉 String 捕获");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "实例 lambda 也要能建桥：" + table(out));
		assertEquals(0, (bridge.access & Opcodes.ACC_STATIC), "桥必须跟着目标保持实例方法");
		assertTrue(containsCall(bridge, "onBridgeInvoked"));

		// 实例方法的形参从槽 1 起：String 在 1、int 在 2；转发给 (I)V 只压 int（槽 2），
		// 接收者用 aload 0。
		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertLoad(loads.get(0), Opcodes.ALOAD, 0, "先压接收者");
		assertLoad(loads.get(1), Opcodes.ILOAD, 2, "再压活下来的 int（老槽 2）");
		assertEquals("(I)V", onlyCoreCall(bridge).desc);
		assertEquals(Opcodes.INVOKESPECIAL, onlyCoreCall(bridge).getOpcode(), "私有实例方法用 INVOKESPECIAL 转发");
	}

	/**
	 * 实例桥的第二轮：上一轮的桥把 {@code (String, int)} 里的 String 丢了，这一轮只改方法体。
	 *
	 * <p>钉的是"捕获表下标"与"描述符形参下标"不能混用：实例方法的捕获表第 0 项是接收者，
	 * 两者差 1。解析旧桥体时若按描述符下标记、重建时又按捕获下标读，就会把 String 的槽
	 * 当成 int 加载——{@code ILOAD} 一个引用槽，验证期直接失败。</p>
	 */
	@Test
	void secondRoundKeepsTheInstanceBridgeAndItsParamMapping(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_INSTANCE).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_INSTANCE).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_INSTANCE_BODY).resolve("B.class"));

		byte[] r1 = LambdaAligner.align(v1, v2);
		MethodNode first = byKey(parse(r1), "lambda$d$0", "(Ljava/lang/String;I)V");
		assertNotNull(first, "第一轮必须建出实例桥");
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);

		ClassNode out = parse(LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames));
		MethodNode bridge = byKey(out, "lambda$d$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "第二轮桥不能消失：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "第二轮必须仍认得出这是桥");

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(2, loads.size(), "接收者 + 活下来的 int");
		assertLoad(loads.get(0), Opcodes.ALOAD, 0, "先压接收者");
		assertLoad(loads.get(1), Opcodes.ILOAD, 2,
			"int 在槽 2；按描述符下标当成捕获下标会去 ILOAD 槽 1（String）");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode());
		assertEquals("(I)V", call.desc);
	}

	// ------------------------------------------------------------------------------------------------
	// LVT 形参名：比槽号更硬的证据
	// ------------------------------------------------------------------------------------------------

	/**
	 * 捕获的是宿主<b>局部变量</b>：删掉前一个同类型局部变量后，`b` 从槽 1 挪到槽 0。
	 *
	 * <p>这一例把三种判据的边界摆在一起：类型层面 {@code [int,int] → [int]} 无解；
	 * 槽号层面更糟——新槽 0 恰好等于旧槽 0，按槽号会<b>把 a 的值喂给 b</b>；
	 * 只有源码变量名能证明"留下的是 b"。</p>
	 */
	@Test
	void localVariableNamesProveTheMappingWhenSlotsShift(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_TWO_LOCAL_INTS, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ONE_LOCAL_INT, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(II)V"), "夹具前提：v1 捕获两个局部 int");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(I)V"), "夹具前提：v2 只捕获 b");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(II)V");
		assertNotNull(bridge, "有 -g 时必须按名字建桥（类型无解、槽号会指错）：" + table(out));

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "桥只该加载一个形参");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1,
			"必须加载旧槽 1（b）；按槽号相等取槽 0 会把 a 的值喂给 b");
		assertEquals("(I)V", onlyCoreCall(bridge).desc);
	}

	/**
	 * 同描述符、同类型、只换捕获顺序：老隐藏类会按<b>旧顺序</b>压栈，新方法体按<b>新顺序</b>解读。
	 *
	 * <p>这种形态没有"名字变化"可依，配对器原本会把它当成同一个 lambda 原地更新——静默错值。
	 * 必须拦住配对，让新方法改名、由捕获桥重排实参。</p>
	 */
	@Test
	void sameSignatureCaptureSwapIsBridgedInsteadOfPairedInPlace(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_ORDER_AB, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ORDER_BA, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(II)V"), "夹具前提：v1 捕获 (a,b)");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(II)V"), "夹具前提：v2 捕获 (b,a)，描述符相同");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(II)V");
		assertNotNull(bridge, "换序必须走桥，不能原地配对：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "换序的产物必须是转发桥，不是新方法体本身");

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(2, loads.size(), "桥要按新顺序搬两个实参");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1, "新方法第 0 个形参是 b（旧槽 1）");
		assertLoad(loads.get(1), Opcodes.ILOAD, 0, "新方法第 1 个形参是 a（旧槽 0）");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals("(II)V", call.desc);
		assertNotEquals("lambda$f$0", call.name,
			"目标必须被改名——否则它会和桥占同一个键");
	}

	/**
	 * 换序桥的第二轮：捕获表不动、只改方法体。
	 *
	 * <p>这是"多轮保活"里最容易漏的一档。旧版解析器要求被加载的槽号<b>递增</b>，而换序桥
	 * 就是<b>故意</b>倒着压栈的（先 b 后 a），于是它在第二轮被判定成"不认识"，直接幽灵化——
	 * 第一轮建立的交换当场作废，而且没有任何异常提示。</p>
	 */
	@Test
	void secondRoundKeepsTheReorderBridge(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_ORDER_AB, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ORDER_BA, true).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_ORDER_BA_BODY, true).resolve("B.class"));

		byte[] r1 = LambdaAligner.align(v1, v2);
		assertNotNull(byKey(parse(r1), "lambda$f$0", "(II)V"), "第一轮必须建出换序桥");
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);

		ClassNode out = parse(LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames));
		MethodNode bridge = byKey(out, "lambda$f$0", "(II)V");
		assertNotNull(bridge, "换序桥第二轮不能消失：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"),
			"必须仍是转发桥；退化成幽灵桩说明倒序压栈的桥体没被认出来");

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(2, loads.size(), "仍要按新顺序搬两个实参");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1, "先搬 b（老槽 1）");
		assertLoad(loads.get(1), Opcodes.ILOAD, 0, "再搬 a（老槽 0）——顺序与槽号递增相反");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
		assertEquals("(II)V", call.desc);
		assertNotEquals("lambda$f$0", call.name, "目标仍是本轮定稿的避障名");
	}

	// ------------------------------------------------------------------------------------------------
	// 实例 → 静态：删掉 this 引用
	// ------------------------------------------------------------------------------------------------

	/**
	 * V1 的合成方法是<b>实例</b>方法、V2 的是<b>静态</b>方法，两者的 name+desc 完全相同
	 * （{@code lambda$d$0(I)V}），只差 {@code ACC_STATIC}。
	 *
	 * <p>两个陷阱叠在一起：</p>
	 * <ol>
	 *   <li>同名同描述符不能共存（{@code ClassFormatError}）——新方法必须被改成避障名，
	 *       老键才腾得出来给桥；</li>
	 *   <li>桥必须<b>保持实例方法</b>（老隐藏类按 {@code INVOKESPECIAL} 调它），而转发目标是
	 *       静态方法。"桥的形态"与"目标的形态"是两位，共用一个 {@code isStatic} 就会写出
	 *       "实例桥去 {@code INVOKESTATIC} 一个要接收者的目标"这类非法字节码。</li>
	 * </ol>
	 */
	@Test
	void droppingTheReceiverBridgesInstanceToStaticWithoutKeyCollision(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_THIS_AND_PARAM).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_THIS_DROPPED).resolve("B.class"));

		// 前提：同 name+desc，只差 ACC_STATIC。夹具一旦变形，这个用例就测不到目标形态。
		MethodNode oldM = byKey(parse(v1), "lambda$d$0", "(I)V");
		MethodNode newM = byKey(parse(v2), "lambda$d$0", "(I)V");
		assertNotNull(oldM, "夹具前提：v1 的合成方法是 lambda$d$0(I)V");
		assertNotNull(newM, "夹具前提：v2 的合成方法是 lambda$d$0(I)V");
		assertEquals(0, oldM.access & Opcodes.ACC_STATIC, "v1 必须是实例方法（引用了 this）");
		assertNotEquals(0, newM.access & Opcodes.ACC_STATIC, "v2 必须是静态方法（不再引用 this）");

		ClassNode out = parse(LambdaAligner.align(v1, v2));

		// 1) 同 name+desc 只能有一个方法：新方法必须让位（改名），否则就是 ClassFormatError
		int sameKey = 0;
		for (MethodNode mn : out.methods) {
			if (mn.name.equals("lambda$d$0") && mn.desc.equals("(I)V")) sameKey++;
		}
		assertEquals(1, sameKey, "同名同描述符只能留一个方法（桥），新方法必须换成避障名：" + table(out));

		// 2) 老键仍在，且是实例方法——老隐藏类按 INVOKESPECIAL 调它，形态不能变
		MethodNode bridge = byKey(out, "lambda$d$0", "(I)V");
		assertNotNull(bridge, "老键必须仍然存在：" + table(out));
		assertEquals(0, bridge.access & Opcodes.ACC_STATIC,
			"桥必须保持实例方法：老调用点是 INVOKESPECIAL，改成静态会 IncompatibleClassChangeError");
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须是转发桥，不是幽灵桩");
		assertFalse(containsCall(bridge, "onOrphanInvoked"), "桥不能走熔断入口");

		// 3) 桥体：丢弃接收者（不压 this），只搬 k（实例方法里在槽 1），静态转发
		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "只该搬 k；接收者必须被丢弃");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1, "k 在实例方法的槽 1");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
			"目标是静态方法，必须 INVOKESTATIC，不能先压接收者再 INVOKESPECIAL");
		assertEquals("(I)V", call.desc);
		assertNotEquals("lambda$d$0", call.name, "目标必须被改名，否则与桥占同一个键");
		assertNotNull(byKey(out, call.name, "(I)V"), "转发目标必须真实存在于产物里：" + table(out));
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);
	}

	/**
	 * 上一轮建的是"实例桥 → 静态目标"，这一轮只改方法体。
	 *
	 * <p>桥体里<b>没有</b> {@code aload_0}（目标静态，不需要接收者），而旧版解析器无条件要求
	 * 桥体以 {@code aload_0} 开头，于是这类桥在第二轮认不出来、退化成死幽灵。这条用例钉的是
	 * "接收者压栈是可选的"。</p>
	 */
	@Test
	void secondRoundKeepsTheInstanceToStaticBridge(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_THIS_AND_PARAM).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_THIS_DROPPED).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_THIS_DROPPED_BODY).resolve("B.class"));

		byte[] r1 = LambdaAligner.align(v1, v2);
		assertNotNull(byKey(parse(r1), "lambda$d$0", "(I)V"), "第一轮必须建出实例桥");
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);

		ClassNode out = parse(LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames));

		MethodNode bridge = byKey(out, "lambda$d$0", "(I)V");
		assertNotNull(bridge, "第二轮桥不能消失：" + table(out));
		assertEquals(0, bridge.access & Opcodes.ACC_STATIC, "第二轮桥仍须是实例方法");
		assertTrue(containsCall(bridge, "onBridgeInvoked"),
			"第二轮必须仍认得出这是桥（桥体可以不以 aload_0 开头）");

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "第二轮仍只该搬 k");
		assertLoad(loads.get(0), Opcodes.ILOAD, 1, "k 在槽 1");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), "目标仍是静态方法");
		assertEquals("(I)V", call.desc);
	}

	/**
	 * 实例 lambda 的捕获换序：描述符完全相同（{@code (II)V}），只是实参顺序反了。
	 *
	 * <p>与静态版同一个道理，但多一层：桥是实例方法，接收者走 {@code aload_0}，两个 int 的
	 * 槽号是 1 和 2。若把"捕获表下标"与"描述符形参下标"混用（差 1），这里会搬到错的值。</p>
	 */
	@Test
	void instanceLambdaCaptureSwapIsBridged(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_INSTANCE_ORDER_AB, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_INSTANCE_ORDER_BA, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$d$0", "(II)V"), "夹具前提：v1 是实例方法 (II)V");
		assertNotNull(byKey(parse(v2), "lambda$d$0", "(II)V"), "夹具前提：v2 也是 (II)V，仅顺序不同");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(II)V");
		assertNotNull(bridge, "换序必须走桥：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "产物必须是转发桥，不是原地配对的新方法体");
		assertEquals(0, bridge.access & Opcodes.ACC_STATIC, "实例桥必须保持实例方法");

		// 新顺序是 (b, a)：先压 b（老槽 2）、再压 a（老槽 1）。
		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(3, loads.size(), "接收者 + 两个实参");
		assertLoad(loads.get(0), Opcodes.ALOAD, 0, "先压接收者");
		assertLoad(loads.get(1), Opcodes.ILOAD, 2, "新第 0 个形参是 b（老槽 2）");
		assertLoad(loads.get(2), Opcodes.ILOAD, 1, "新第 1 个形参是 a（老槽 1）");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode(), "目标是私有实例方法，走 INVOKESPECIAL");
		assertEquals("(II)V", call.desc);
		assertNotEquals("lambda$d$0", call.name, "目标必须改名，否则与桥同键");
	}

	// ------------------------------------------------------------------------------------------------
	// forceStaticLambdas 开启：接收者变成显式首形参
	// ------------------------------------------------------------------------------------------------

	/**
	 * {@code forceStaticLambdas} 把实例方法改成静态方法，{@code this} 变成显式首形参。
	 *
	 * <p>接收者槽的名字取决于来源：被改写的实例方法在 LVT 里留着 javac 写的 {@code "this"}；
	 * 而给零捕获静态 lambda 前置的 receiver 槽<b>没有 LVT 条目</b>，读出来是 {@code null}。
	 * 不归一的话，"任一位缺名字就整体放弃按名映射"会把这类 lambda 判成没证据——这里捕获来自
	 * 宿主<b>局部变量</b>，槽位证明本来就不成立（局部槽会随前序变量位移），按名映射是唯一出路。</p>
	 */
	@Test
	void forcedStaticReceiverIsNamedSoNameMappingStillWorks(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = force(Files.readAllBytes(
			compile(javac, tmp, "v1", V1_FORCED_LOCALS, true).resolve("B.class")));
		byte[] v2 = force(Files.readAllBytes(
			compile(javac, tmp, "v2", V2_FORCED_LOCALS, true).resolve("B.class")));

		// 前提：接收者被前置成显式首形参，且名字已被归一（不能留 null）
		String[] p1 = LambdaAligner.readLambdaParamNames(v1).get("lambda$d$0(LB;II)V");
		assertNotNull(p1, "夹具前提：forceStatic 之后是静态的 lambda$d$0(LB;II)V");
		assertEquals(LambdaAligner.RECEIVER_NAME, p1[0], "接收者槽必须归一，不能留 null");
		assertEquals("a", p1[1], "其余形参保留源码名");
		assertEquals("b", p1[2]);

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(LB;II)V");
		assertNotNull(bridge, "删掉一个捕获仍要建桥（只能靠名字，槽位证明对局部变量不成立）：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"));

		// 目标是 (I)V（v2 只剩一个捕获 b，forceStatic 不再给它前置 receiver）：
		// 桥要搬捕获 2（b，老槽 2），丢弃 a 与接收者。
		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "只该搬 b");
		assertLoad(loads.get(0), Opcodes.ILOAD, 2, "b 在老方法的槽 2（a 与接收者都不要）");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), "两侧都是静态方法");
		assertEquals("(I)V", call.desc);
	}

	/**
	 * 接收者的名字是<b>唯一</b>能定下映射的证据：捕获表里两个 {@code LB;}，一个是局部变量
	 * {@code other}、一个是接收者。
	 *
	 * <p>类型层面两个同类型候选 → 歧义；槽位层面 {@code other} 在宿主局部槽，槽位证明本来
	 * 就弃权。此时若接收者名字是 {@code null}，"任一位缺名字就整体放弃按名映射"会把这一例
	 * 判成"没证据"，桥建不出来。</p>
	 */
	@Test
	void forcedStaticReceiverNameBreaksTheTieBetweenSameTypedCaptures(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = force(Files.readAllBytes(
			compile(javac, tmp, "v1", V1_FORCED_TWO_SAME_TYPES, true).resolve("B.class")));
		byte[] v2 = force(Files.readAllBytes(
			compile(javac, tmp, "v2", V2_FORCED_NO_CAPTURE, true).resolve("B.class")));

		assertNotNull(byKey(parse(v1), "lambda$d$0", "(LB;LB;)V"),
			"夹具前提：v1 是 (LB;LB)V（接收者 + other）");
		assertNotNull(byKey(parse(v2), "lambda$d$0", "(LB;)V"),
			"夹具前提：v2 是零捕获 + 前置 receiver");

		String[] p1 = LambdaAligner.readLambdaParamNames(v1).get("lambda$d$0(LB;LB;)V");
		assertNotNull(p1);
		assertEquals(LambdaAligner.RECEIVER_NAME, p1[0], "前置的 receiver 必须归一，不能留 null");
		assertEquals("other", p1[1]);

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(LB;LB;)V");
		assertNotNull(bridge,
			"两个同类型捕获只能靠名字定映射；接收者名字为 null 就会整体弃权：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"),
			"必须是转发桥；退化成幽灵桩就说明按名映射整体弃权了");

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "只该搬接收者（other 被删）");
		assertLoad(loads.get(0), Opcodes.ALOAD, 0, "接收者：老方法的槽 0");

		MethodInsnNode call = onlyCoreCall(bridge);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
		assertEquals("(LB;)V", call.desc);
	}

	/**
	 * 零捕获静态 lambda 被前置 receiver：那个槽是新增的，<b>没有 LVT 条目</b>。
	 *
	 * <p>这是 {@code [null, ...]} 形态的唯一真实来源，直接钉住提取器的归一行为。</p>
	 */
	@Test
	void forcedStaticReceiverWithNoLvtEntryIsNamedInsteadOfNull(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] forced = force(Files.readAllBytes(
			compile(javac, tmp, "v1", V1_ZERO_CAPTURE, true).resolve("B.class")));

		assertNotNull(byKey(parse(forced), "lambda$d$0", "(LB;)V"),
			"夹具前提：零捕获静态 lambda 被前置了 receiver");

		String[] names = LambdaAligner.readLambdaParamNames(forced).get("lambda$d$0(LB;)V");
		assertNotNull(names, "必须有这条合成方法的记录");
		assertEquals(1, names.length);
		assertEquals(LambdaAligner.RECEIVER_NAME, names[0],
			"新增的 receiver 槽没有 LVT 条目，必须归一成 $this 而不是留 null");
	}

	// ------------------------------------------------------------------------------------------------
	// kotlinc 兼容：外层 this 在 LVT 里叫 this$0（不是 this）
	// ------------------------------------------------------------------------------------------------

	/**
	 * kotlinc 给"捕获到的外层 {@code this}"写的 LVT 名字是 {@code this$0}，不是 javac 的
	 * {@code this}——而且它的 Indy lambda 天生就是 {@code ACC_STATIC}，不经过
	 * {@code forceStaticLambdas}。实测（kotlin-compiler-embeddable 2.1.20，{@code -jvm-target 21}）：
	 *
	 * <pre>
	 * private static final void d$lambda$0(K, int);        // flags: ACC_PRIVATE|STATIC|FINAL
	 *     LocalVariableTable:
	 *         0      11     0 this$0   LK;
	 *         0      11     1    $k   I
	 * </pre>
	 *
	 * <p>两侧都出自同一个编译器时名字自然一致；危险的是<b>跨来源</b>——老侧是 javac +
	 * {@code forceStatic}（{@code "this"}），新侧是 kotlinc 原生产物（{@code "this$0"}），
	 * 按名映射会因 {@code "this" != "this$0"} 整体弃权，退回类型推断后撞上两个同类型捕获
	 * 的歧义，桥建不出来。</p>
	 *
	 * <p>这里用"把 javac 夹具槽 0 的 LVT 名字改成 {@code this$0}"来复刻那个形态：除了名字，
	 * 其余（indy 形状、捕获表、宿主）都是真实 javac 产物，变量只有名字一个。</p>
	 */
	@Test
	void kotlincStyleReceiverNameIsStillMappedToTheReceiver(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw = force(Files.readAllBytes(
			compile(javac, tmp, "v1", V1_FORCED_TWO_SAME_TYPES, true).resolve("B.class")));
		byte[] v1 = renameLvt(raw, "lambda$d$0", "(LB;LB;)V", 0, "this$0");
		byte[] v2 = force(Files.readAllBytes(
			compile(javac, tmp, "v2", V2_FORCED_NO_CAPTURE, true).resolve("B.class")));

		// 提取器只报告 LVT 里写了什么：this$0 原样返回，归一是下一层的事。
		String[] names = LambdaAligner.readLambdaParamNames(v1).get("lambda$d$0(LB;LB;)V");
		assertNotNull(names);
		assertEquals("this$0", names[0], "提取器不该替编译器改名字");
		assertEquals("other", names[1]);

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(LB;LB;)V");
		assertNotNull(bridge, "this$0 与 $this 必须归一到同一个名字，否则按名映射整体弃权：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须是转发桥");

		List<AbstractInsnNode> loads = loadsOf(bridge);
		assertEquals(1, loads.size(), "只该搬接收者（other 被删）");
		assertLoad(loads.get(0), Opcodes.ALOAD, 0, "接收者：老方法的槽 0");
	}

	/**
	 * 捕获槽回溯失败时（生产者的形状不是直接的局部变量加载），只能靠名字判断哪一位是接收者。
	 *
	 * <p>{@code this$0} 必须和 {@code "this"} / {@code null} 一样被认成接收者，否则这一档会
	 * 退化成"没证据"。这里在 indy 前插一条 {@code NOP} 让回溯失败——{@code NOP} 不改栈、
	 * 不改语义，只让"紧邻 indy 的那几条指令是局部变量加载"这个前提不成立。</p>
	 */
	@Test
	void kotlincStyleReceiverNameWorksWhenCaptureSlotsAreUnknown(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] raw = force(Files.readAllBytes(
			compile(javac, tmp, "v1", V1_FORCED_TWO_SAME_TYPES, true).resolve("B.class")));
		byte[] v1 = insertNopBeforeIndy(
			renameLvt(raw, "lambda$d$0", "(LB;LB;)V", 0, "this$0"), "d", "()V");
		byte[] v2 = force(Files.readAllBytes(
			compile(javac, tmp, "v2", V2_FORCED_NO_CAPTURE, true).resolve("B.class")));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(LB;LB;)V");
		assertNotNull(bridge,
			"槽号回溯失败后只剩名字可用；this$0 没归一就整体弃权，两个同类型捕获只能靠类型猜：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须是转发桥");
	}

	/**
	 * 反向：源码里真的有一个叫 {@code this$0} 的局部变量时，<b>不得</b>把它当接收者。
	 *
	 * <p>{@code this$0} 是合法的 Java 标识符，{@code void d(B this$0)} 能编过。这个变量的值
	 * 与 {@code this} 是<b>两个不同的对象</b>：把它当成接收者，桥就会把接收者喂给一个本该是
	 * 那个变量的位置——静默错值。结构证据（捕获槽 1 ≠ 0）能证伪，此时必须弃权。</p>
	 *
	 * <p>两个版本的同签名 lambda 一个捕获形参、一个捕获 {@code this}，映射没有任何证据能
	 * 证明"这是同一个值"，正确结果是<b>不建桥</b>（退回幽灵桩），而不是建一座喂错对象的桥。</p>
	 */
	@Test
	void realLocalNamedThisDollarZeroIsNotTreatedAsReceiver(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = force(Files.readAllBytes(
			compile(javac, tmp, "v1", V1_PARAM_NAMED_THIS_0, true).resolve("B.class")));
		byte[] v2 = force(Files.readAllBytes(
			compile(javac, tmp, "v2", V2_CAPTURES_THIS, true).resolve("B.class")));

		assertEquals("this$0",
			LambdaAligner.readLambdaParamNames(v1).get("lambda$d$0(LB;LB;)V")[0],
			"夹具前提：v1 捕获的形参就叫 this$0");
		assertEquals("other",
			LambdaAligner.readLambdaParamNames(v1).get("lambda$d$0(LB;LB;)V")[1],
			"夹具前提：v1 还有第二个同类型捕获");
		assertNotNull(byKey(parse(v2), "lambda$d$0", "(LB;)V"),
			"夹具前提：v2 只捕获接收者");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"this$0 是形参、不是接收者：把它归一成 $this 就会把形参对象当成接收者喂给新体");
		assertTrue(containsCall(byKey(out, "lambda$d$0", "(LB;LB;)V"), "onOrphanInvoked"),
			"应当退回幽灵桩：" + table(out));
	}

	// ------------------------------------------------------------------------------------------------
	// 新增捕获：纯源重放
	// ------------------------------------------------------------------------------------------------

	/**
	 * 新增一个"常量捕获"：老实例里没有 {@code margin}，但它的值可证明恒为 10，桥直接压常量。
	 *
	 * <p>这是唯一能证明"新增捕获"合法的方式：不是编一个 0 塞进去，而是把宿主方法里
	 * 那唯一一处 {@code istore} 的<b>生产者</b>原样重放。</p>
	 */
	@Test
	void addedConstantCaptureIsSynthesized(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARGIN, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_CONST, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(Ljava/lang/String;I)V"), "夹具前提：v1 捕获 (String,int)");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(Ljava/lang/String;II)V"),
			"夹具前提：v2 多捕获一个 int（margin）");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "新增的常量捕获必须能建桥：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须是转发桥，不是幽灵桩");

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(3, pushes.size(), "String + int + 常量，各压一次");
		assertLoad(pushes.get(0), Opcodes.ALOAD, 0, "s（老槽 0）");
		assertLoad(pushes.get(1), Opcodes.ILOAD, 1, "k（老槽 1）");
		assertEquals(Integer.valueOf(10), intConstOf(pushes.get(2)),
			"新增的 margin 必须压常量 10——不是 0，也不是别的什么");

		assertEquals("(Ljava/lang/String;II)V", onlyCoreCall(bridge).desc);
	}

	/**
	 * 合成位跨轮保活：第二轮只改方法体。
	 *
	 * <p>桥体里同时有形参加载、常量、核心调用，解析器必须把"常量"也认出来——只认形参加载
	 * 的话第二轮会判不出这一位，桥直接退化成幽灵桩（老实例又变哑）。</p>
	 */
	@Test
	void secondRoundKeepsTheSynthesizedConstant(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARGIN, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_CONST, true).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_ADDED_CONST_BODY, true).resolve("B.class"));

		byte[] r1 = LambdaAligner.align(v1, v2);
		assertNotNull(byKey(parse(r1), "lambda$f$0", "(Ljava/lang/String;I)V"), "第一轮必须建出带常量的桥");
		assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);

		ClassNode out = parse(LambdaAligner.align(r1, v3, null, null, LambdaAligner.LAST_RESULT.inventedNames));
		MethodNode bridge = byKey(out, "lambda$f$0", "(Ljava/lang/String;I)V");
		assertNotNull(bridge, "第二轮桥不能消失：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"),
			"必须仍认得出这是桥（桥体里既有形参加载也有常量）");

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(3, pushes.size(), "String + int + 常量");
		assertEquals(Integer.valueOf(10), intConstOf(pushes.get(2)), "常量位必须仍然重放 10");
	}

	/** 新增捕获来自 {@code static final} 字段：桥里重放一条 {@code GETSTATIC}。 */
	@Test
	void addedStaticFinalCaptureIsSynthesized(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_STATIC_FINAL, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$f$0", "(I)V"), "夹具前提：v1 只捕获 k");
		assertNotNull(byKey(parse(v2), "lambda$f$0", "(ILjava/lang/Object;)V"),
			"夹具前提：v2 多捕获一个 Object（MARK 的副本）");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(I)V");
		assertNotNull(bridge, "static final 字段可重放：" + table(out));

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(2, pushes.size(), "k + MARK");
		assertLoad(pushes.get(0), Opcodes.ILOAD, 0, "k");
		assertTrue(pushes.get(1) instanceof FieldInsnNode f
		           && f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("MARK"),
			"新增位必须是 GETSTATIC B.MARK，不能是 null");
	}

	/** 新增捕获来自 {@code this.field}（final）：桥里重放 {@code ALOAD 0; GETFIELD}。 */
	@Test
	void addedFinalFieldCaptureIsSynthesized(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_INSTANCE_PARAM, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_FINAL_FIELD, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$d$0", "(I)V"), "夹具前提：v1 实例 lambda 捕获 (this,k)");
		assertNotNull(byKey(parse(v2), "lambda$d$0", "(ILjava/lang/Object;)V"),
			"夹具前提：v2 多捕获一个 Object（x）");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$d$0", "(I)V");
		assertNotNull(bridge, "final 实例字段可重放：" + table(out));
		assertEquals(0, bridge.access & Opcodes.ACC_STATIC, "桥仍是实例方法");

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(4, pushes.size(), "接收者 + k + (ALOAD 0, GETFIELD)");
		assertLoad(pushes.get(0), Opcodes.ALOAD, 0, "先压接收者");
		assertLoad(pushes.get(1), Opcodes.ILOAD, 1, "k 在实例方法的槽 1");
		assertLoad(pushes.get(2), Opcodes.ALOAD, 0, "字段读的接收者");
		assertTrue(pushes.get(3) instanceof FieldInsnNode g
		           && g.getOpcode() == Opcodes.GETFIELD && g.name.equals("BASE"),
			"新增位必须是 GETFIELD B.BASE");
	}

	/** 非 final 字段会在创建之后被改写，读它等于编一个"看起来像"的值：必须拒绝。 */
	@Test
	void addedNonFinalFieldCaptureIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_INSTANCE_PARAM, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_NONFINAL_FIELD, true).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"非 final 字段不构成「值恒定」的证明，宁可退化成幽灵桩");
		assertTrue(containsCall(byKey(out, "lambda$d$0", "(I)V"), "onOrphanInvoked"),
			"拒绝桥接的形态是幽灵桩");
	}

	/** 新增捕获来自宿主<b>形参</b>：值由调用方给，方法体里没有"它从哪来"，无从重放。 */
	@Test
	void addedCaptureFromHostParamIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_TWO_PARAMS, true).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"宿主形参没有「唯一 XSTORE」可回溯，不能凭类型猜一个值");
		assertTrue(containsCall(byKey(out, "lambda$f$0", "(I)V"), "onOrphanInvoked"));
	}

	/** 新增捕获来自算式：值依赖创建时的栈帧，桥执行时已经不存在。 */
	@Test
	void addedComputedCaptureIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_COMPUTED_CAPTURE, true).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"int x = k + 1 的生产者是三条指令且依赖局部变量，不是纯源");
		assertTrue(containsCall(byKey(out, "lambda$f$0", "(I)V"), "onOrphanInvoked"));
	}

	/**
	 * 新增捕获是<b>算出来的</b>，但整段能在编译期折成常量：{@code int pad = 10; float scaled = pad * 2;}
	 *
	 * <p>折叠不是"把原指令搬进桥"（那要保证栈形状、类型、操作码全对），而是算成一个常量。
	 * 失败模式只有一种：算不出来就放弃。</p>
	 */
	@Test
	void addedComputedButFoldableCaptureIsSynthesized(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_FOLDED_EXPR, true).resolve("B.class"));

		assertNotNull(byKey(parse(v2), "lambda$f$0", "(IF)V"), "夹具前提：v2 捕获 (int, float)");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(I)V");
		assertNotNull(bridge, "可折叠的算式应当能建桥：" + table(out));
		assertTrue(containsCall(bridge, "onBridgeInvoked"));

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(2, pushes.size(), "k + 折出来的常量");
		assertLoad(pushes.get(0), Opcodes.ILOAD, 0, "k");
		assertTrue(pushes.get(1) instanceof LdcInsnNode l && Float.valueOf(20.0f).equals(l.cst),
			"pad * 2 必须折成 20.0f（不是 0，也不是 10）");
	}

	/**
	 * long 移位：{@code base << 40}。
	 *
	 * <p>钉的是"移位量是 int 不是 long"——按 long 去弹第二个操作数会失败，整段放弃。</p>
	 */
	@Test
	void addedLongShiftCaptureIsFolded(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_LONG_SHIFT, true).resolve("B.class"));

		assertNotNull(byKey(parse(v2), "lambda$f$0", "(IJ)V"), "夹具前提：v2 捕获 (int, long)");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(I)V");
		assertNotNull(bridge, "long 移位应当能折：" + table(out));

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(2, pushes.size(), "k + 折出来的 long");
		assertTrue(pushes.get(1) instanceof LdcInsnNode l
		           && Long.valueOf(1L << 40).equals(l.cst),
			"base << 40 必须折成 1099511627776L");
	}

	/** float 乘法：{@code half * 2.0f}。钉的是 float 二元分支（不能被 int 区间误吞）。 */
	@Test
	void addedFloatMultiplyCaptureIsFolded(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_FLOAT_MUL, true).resolve("B.class"));

		assertNotNull(byKey(parse(v2), "lambda$f$0", "(IF)V"), "夹具前提：v2 捕获 (int, float)");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$f$0", "(I)V");
		assertNotNull(bridge, "float 乘法应当能折：" + table(out));

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(2, pushes.size(), "k + 折出来的 float");
		assertTrue(pushes.get(1) instanceof LdcInsnNode l && Float.valueOf(3.0f).equals(l.cst),
			"1.5f * 2.0f 必须折成 3.0f");
	}

	/**
	 * 构造器参数 → {@code final} 字段：桥用 {@code ALOAD 0; GETFIELD} 把当年那个参数取回来。
	 *
	 * <p>前提是桥自己持有 {@code this}——所以老 lambda 必须已经是实例 lambda。</p>
	 */
	@Test
	void addedCaptureFromConstructorParamIsSynthesizedViaField(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_CTOR_THIS_ONLY, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_CTOR_USES_PARAM, true).resolve("B.class"));

		assertNotNull(byKey(parse(v1), "lambda$new$0", "()V"), "夹具前提：v1 只捕获 this");
		assertNotNull(byKey(parse(v2), "lambda$new$0", "(Ljava/lang/String;)V"),
			"夹具前提：v2 多捕获构造器参数 tag");

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		MethodNode bridge = byKey(out, "lambda$new$0", "()V");
		assertNotNull(bridge, "构造器参数经不可变字段可重放：" + table(out));
		assertEquals(0, bridge.access & Opcodes.ACC_STATIC, "桥必须是实例方法（要靠 this 取字段）");

		List<AbstractInsnNode> pushes = valuePushes(bridge);
		assertEquals(3, pushes.size(), "接收者 + (ALOAD 0, GETFIELD TAG)");
		assertLoad(pushes.get(0), Opcodes.ALOAD, 0, "先压接收者");
		assertLoad(pushes.get(1), Opcodes.ALOAD, 0, "字段读的接收者");
		assertTrue(pushes.get(2) instanceof FieldInsnNode g
		           && g.getOpcode() == Opcodes.GETFIELD && g.name.equals("TAG"),
			"新增位必须是 GETFIELD B.TAG");
	}

	/** 字段不是 final：构造完成后可能被改写，不能当"参数的值"用。 */
	@Test
	void addedCaptureFromConstructorParamIsRefusedWhenFieldIsMutable(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_CTOR_THIS_ONLY, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_CTOR_USES_PARAM_NONFINAL, true).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"非 final 字段不构成「构造完成后不再变」的证明");
		assertTrue(containsCall(byKey(out, "lambda$new$0", "()V"), "onOrphanInvoked"));
	}

	/**
	 * 静态桥没有接收者：构造器参数经字段重放这条路走不通。
	 *
	 * <p>这条是<b>已知限制</b>，不是缺陷——桥手里根本没有那个实例。</p>
	 */
	@Test
	void addedCaptureFromConstructorParamIsRefusedForStaticBridge(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] old = Files.readAllBytes(compile(javac, tmp, "old", V1_CTOR_STATIC_ONLY, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_CTOR_USES_PARAM_STATIC, true).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(old, v2));

		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"静态桥拿不到 this，读不到 this.TAG");
		assertTrue(containsCall(byKey(out, "lambda$new$0", "()V"), "onOrphanInvoked"));
	}

	// ------------------------------------------------------------------------------------------------
	// 运行时常量：显式登记的无参静态访问器
	// ------------------------------------------------------------------------------------------------

	/**
	 * 已登记的"运行时常量"访问器（这里用 {@code System.lineSeparator()} 代替 Arc 的 {@code Scl.scl()}）。
	 *
	 * <p>桥里重放的就是<b>同一次调用</b>，不是编一个值——等价性由登记方承诺："整个运行期返回
	 * 同一个值"。没登记的同形调用必须仍然被拒，否则等于把任意静态调用都当成常量。</p>
	 */
	@Test
	void registeredRuntimeConstantIsReplayed(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_RUNTIME_CONST, true).resolve("B.class"));

		assertNotNull(byKey(parse(v2), "lambda$f$0", "(ILjava/lang/String;)V"),
			"夹具前提：v2 捕获 (int, String)");

		remold.RuntimeConstants.register("java/lang/System", "lineSeparator", "()Ljava/lang/String;");
		try {
			ClassNode out = parse(LambdaAligner.align(v1, v2));
			MethodNode bridge = byKey(out, "lambda$f$0", "(I)V");
			assertNotNull(bridge, "登记过的访问器可重放：" + table(out));
			assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须是转发桥");

			List<AbstractInsnNode> pushes = valuePushes(bridge);
			assertEquals(2, pushes.size(), "k + 访问器调用");
			assertLoad(pushes.get(0), Opcodes.ILOAD, 0, "k");
			assertTrue(pushes.get(1) instanceof MethodInsnNode c
			           && c.getOpcode() == Opcodes.INVOKESTATIC
			           && c.owner.equals("java/lang/System") && c.name.equals("lineSeparator"),
				"新增位必须重放 INVOKESTATIC System.lineSeparator()");
		} finally {
			remold.RuntimeConstants.unregister("java/lang/System", "lineSeparator", "()Ljava/lang/String;");
		}
	}

	/**
	 * 运行时常量位跨轮保活：第二轮只改方法体。
	 *
	 * <p>解析旧桥体时要能把"已登记的 {@code INVOKESTATIC}"认成压值，而不是核心调用——
	 * 两者 opcode 相同，只能靠登记表区分。</p>
	 */
	@Test
	void secondRoundKeepsTheRuntimeConstant(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_RUNTIME_CONST, true).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_ADDED_RUNTIME_CONST_BODY, true).resolve("B.class"));

		remold.RuntimeConstants.register("java/lang/System", "lineSeparator", "()Ljava/lang/String;");
		try {
			byte[] r1 = LambdaAligner.align(v1, v2);
			assertNotNull(byKey(parse(r1), "lambda$f$0", "(I)V"), "第一轮必须建出桥");
			assertEquals(1, LambdaAligner.LAST_RESULT.bridgeCount);

			ClassNode out = parse(LambdaAligner.align(r1, v3, null, null,
				LambdaAligner.LAST_RESULT.inventedNames));
			MethodNode bridge = byKey(out, "lambda$f$0", "(I)V");
			assertNotNull(bridge, "第二轮桥不能消失：" + table(out));
			assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须仍认得出这是桥");

			List<AbstractInsnNode> pushes = valuePushes(bridge);
			assertEquals(2, pushes.size(), "k + 访问器调用");
			assertTrue(pushes.get(1) instanceof MethodInsnNode c && c.name.equals("lineSeparator"),
				"运行时常量位必须仍然重放那条调用");
		} finally {
			remold.RuntimeConstants.unregister("java/lang/System", "lineSeparator", "()Ljava/lang/String;");
		}
	}

	/** 同一个夹具，但<b>没有登记</b>：普通静态调用不是常量，必须拒绝。 */
	@Test
	void unregisteredStaticCallIsStillRefused(@TempDir Path tmp) throws Exception {		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_RUNTIME_CONST, true).resolve("B.class"));

		ClassNode out = parse(LambdaAligner.align(v1, v2));
		assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
			"没登记就不是常量：把任意静态调用当常量等于编值");
		assertTrue(containsCall(byKey(out, "lambda$f$0", "(I)V"), "onOrphanInvoked"));
	}

	/** 登记口自身的契约：返回 void 的调用没有值可用，登记时就该失败。 */
	@Test
	void runtimeConstantRegistryRejectsVoidReturnOnly() {
		assertThrows(IllegalArgumentException.class,
			() -> remold.RuntimeConstants.register("x/Y", "f", "()V"),
			"返回 void 的调用没有值可用");
		assertDoesNotThrow(() -> {
			remold.RuntimeConstants.register("x/Y", "f", "()I");        // 无参
			remold.RuntimeConstants.register("x/Y", "g", "(I)I");       // 带参纯函数
			remold.RuntimeConstants.unregister("x/Y", "f", "()I");
			remold.RuntimeConstants.unregister("x/Y", "g", "(I)I");
		}, "带参形态（纯函数）是允许登记的");
	}

	/**
	 * 带参的运行时常量访问器：{@code Scl.scl(float) = v * Scl.scl()} 那一类。
	 *
	 * <p>它本身是<b>纯函数</b>——给定实参则结果恒定。所以桥里要先把折出来的实参压回去，
	 * 再重放调用。实参不是常量时无从重放（下一个用例钉住）。</p>
	 */
	@Test
	void registeredRuntimeConstantWithConstantArgsIsReplayed(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_RUNTIME_CONST_ARGS, true).resolve("B.class"));
		byte[] v3 = Files.readAllBytes(compile(javac, tmp, "v3", V3_ADDED_RUNTIME_CONST_ARGS_BODY, true).resolve("B.class"));

		assertNotNull(byKey(parse(v2), "lambda$f$0", "(II)V"), "夹具前提：v2 捕获 (int, int)");

		remold.RuntimeConstants.register("java/lang/Math", "max", "(II)I");
		try {
			byte[] r1 = LambdaAligner.align(v1, v2);
			MethodNode bridge = byKey(parse(r1), "lambda$f$0", "(I)V");
			assertNotNull(bridge, "带参的运行时常量可重放：" + table(parse(r1)));
			assertTrue(containsCall(bridge, "onBridgeInvoked"), "必须是转发桥");

			List<AbstractInsnNode> pushes = valuePushes(bridge);
			assertEquals(4, pushes.size(), "k + 两个实参 + 调用");
			assertLoad(pushes.get(0), Opcodes.ILOAD, 0, "k");
			assertEquals(Integer.valueOf(7), intConstOf(pushes.get(1)), "第 0 个实参是 7");
			assertEquals(Integer.valueOf(3), intConstOf(pushes.get(2)), "第 1 个实参是 3");
			assertTrue(pushes.get(3) instanceof MethodInsnNode c
			           && c.getOpcode() == Opcodes.INVOKESTATIC && c.name.equals("max"),
				"最后重放 Math.max(II)I");

			// 第二轮：解析旧桥体时要把"两个常量 + 一次调用"重新拼回一个带参来源。
			ClassNode out = parse(LambdaAligner.align(r1, v3, null, null,
				LambdaAligner.LAST_RESULT.inventedNames));
			MethodNode again = byKey(out, "lambda$f$0", "(I)V");
			assertNotNull(again, "第二轮桥不能消失：" + table(out));
			assertTrue(containsCall(again, "onBridgeInvoked"), "必须仍认得出这是桥");
			List<AbstractInsnNode> p2 = valuePushes(again);
			assertEquals(4, p2.size(), "第二轮仍是 k + 两个实参 + 调用");
			assertEquals(Integer.valueOf(7), intConstOf(p2.get(1)), "实参 7 必须被重新拼回");
			assertEquals(Integer.valueOf(3), intConstOf(p2.get(2)), "实参 3 必须被重新拼回");
		} finally {
			remold.RuntimeConstants.unregister("java/lang/Math", "max", "(II)I");
		}
	}

	/** 带参形态但实参依赖宿主形参：折不出常量，必须拒绝。 */
	@Test
	void registeredRuntimeConstantWithDynamicArgIsRefused(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = Files.readAllBytes(compile(javac, tmp, "v1", V1_NO_MARK, true).resolve("B.class"));
		byte[] v2 = Files.readAllBytes(compile(javac, tmp, "v2", V2_ADDED_RUNTIME_CONST_DYNAMIC_ARG, true).resolve("B.class"));

		remold.RuntimeConstants.register("java/lang/Math", "max", "(II)I");
		try {
			ClassNode out = parse(LambdaAligner.align(v1, v2));
			assertEquals(0, LambdaAligner.LAST_RESULT.bridgeCount,
				"实参依赖宿主形参时折不出常量，不能重放");
			assertTrue(containsCall(byKey(out, "lambda$f$0", "(I)V"), "onOrphanInvoked"));
		} finally {
			remold.RuntimeConstants.unregister("java/lang/Math", "max", "(II)I");
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 熔断豁免：桥必须能在受熔断器保护的调用者面前活下来
	// ------------------------------------------------------------------------------------------------

	/**
	 * {@code onOrphanInvoked} 在 SMART_ADAPTIVE 下<b>先</b>判熔断器、<b>后</b>看 willForward，
	 * 所以复用那个入口的桥会在转发之前被直接炸断。桥走独立入口，永远不查熔断器。
	 *
	 * <p>这条用例把对比摆在同一个谓词下：同一个"直接调用者"，幽灵桩抛、桥不抛。</p>
	 */
	@Test
	void bridgeIsExemptFromOrphanBreakers() {
		OrphanBreakerPredicate always = (cls, method) -> true;
		LambdaAligner.addOrphanBreaker(always);
		try {
			assertThrows(NoSuchMethodError.class,
				() -> LambdaAligner.onOrphanInvoked("probe#lambda$f$0(Ljava/lang/String;I)V", true),
				"前提：熔断器命中时，转发桩同样会被 onOrphanInvoked 炸断（这正是不能复用它当桥入口的原因）");
			assertDoesNotThrow(
				() -> LambdaAligner.onBridgeInvoked("probe#lambda$f$0(Ljava/lang/String;I)V"),
				"桥不是死孤儿：目标方法存在且被三道门证明过兼容，不该走熔断");
		} finally {
			LambdaAligner.removeOrphanBreaker(always);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 夹具
	// ------------------------------------------------------------------------------------------------

	/** 捕获 (String, int) → {@code lambda$f$0(Ljava/lang/String;I)V}。 */
	static final String V1_TWO_CAPTURES = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(String s, int k) { r = () -> last = s + k; }
		}
		""";

	/** 删掉 String 捕获 → {@code lambda$f$0(I)V}。 */
	static final String V2_DROP_CAPTURE = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) { r = () -> last = "v2:" + k; }
		}
		""";

	/** 第二轮：捕获表不动，只改方法体。 */
	static final String V3_BODY_ONLY = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) { r = () -> last = "v3:" + k; }
		}
		""";

	/** 第二轮：lambda 被整个删掉，桥的目标不复存在。 */
	static final String V3_NO_LAMBDA = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) { last = "no lambda:" + k; }
		}
		""";

	/** 捕获类型从 int 变 long → {@code (Ljava/lang/String;J)V}，不是子序列。 */
	static final String V2_LONG_CAPTURE = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(String s, long k) { r = () -> last = s + k; }
		}
		""";

	/** 两个同类型捕获 → {@code lambda$f$0(II)V}。 */
	static final String V1_TWO_INTS = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int a, int b) { r = () -> last = "" + a + b; }
		}
		""";

	/** 删掉其中一个 int → {@code (I)V}，删法不唯一。 */
	static final String V2_ONE_INT = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int b) { r = () -> last = "v2:" + b; }
		}
		""";

	/** 换成 IntConsumer：捕获表是合法子序列，但 SAM 变了。 */
	static final String V2_INTCONSUMER = """
		import java.util.function.IntConsumer;
		public class B {
		    public static IntConsumer r;
		    public static String      last = "";
		    public static void f() { r = x -> last = "v2:" + x; }
		}
		""";

	/** 接口宿主：合成方法落在接口自己身上。 */
	static final String V1_INTERFACE = """
		public interface I {
		    default void f(String s, int k) { Runnable r = () -> System.out.println(s + k); }
		}
		""";

	/** 接口宿主 + 删掉 String 捕获。 */
	static final String V2_INTERFACE = """
		public interface I {
		    default void f(int k) { Runnable r = () -> System.out.println("v2:" + k); }
		}
		""";

	/** 只交换使用顺序：{@code a + b} → {@code b + a}，捕获顺序随之变成 (int,String)。 */
	static final String V2_SWAPPED_USE = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(String s, int k) { r = () -> last = k + s; }
		}
		""";

	/** 宿主描述符不变、只留第二个 int：类型推断无解，槽号可证。 */
	static final String V2_ONE_INT_SAME_HOST = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int a, int b) { r = () -> last = "v2:" + b; }
		}
		""";

	/** 实例 lambda：捕获表里带接收者，impl 是实例方法。 */
	static final String V1_INSTANCE = """
		public class B {
		    public Runnable r;
		    public String   last = "";
		    public void d(String s, int k) { r = () -> last = s + k + this; }
		}
		""";

	/** 实例 lambda + 删掉 String 捕获。 */
	static final String V2_INSTANCE = """
		public class B {
		    public Runnable r;
		    public String   last = "";
		    public void d(int k) { r = () -> last = "v2:" + k + this; }
		}
		""";

	/** 第二轮：只改方法体，捕获表不再动（仍是实例方法、仍是 {@code (LString;I)V} 的老键）。 */
	static final String V3_INSTANCE_BODY = """
		public class B {
		    public Runnable r;
		    public String   last = "";
		    public void d(int k) { r = () -> last = "v3:" + k + this; }
		}
		""";

	/** 捕获两个宿主<b>局部变量</b>：槽 0 = a、槽 1 = b。 */
	static final String V1_TWO_LOCAL_INTS = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f() { int a = 1; int b = 2; r = () -> last = "" + a + "-" + b; }
		}
		""";

	/** 删掉局部变量 a：b 从槽 1 挪到槽 0，槽号证明失效，只剩名字可用。 */
	static final String V2_ONE_LOCAL_INT = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f() { int b = 2; r = () -> last = "v2:" + b; }
		}
		""";

	/** 捕获顺序 (a, b)。 */
	static final String V1_ORDER_AB = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int a, int b) { r = () -> last = "" + a + "-" + b; }
		}
		""";

	/** 捕获顺序 (b, a)：描述符不变，但实参顺序反了。 */
	static final String V2_ORDER_BA = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int a, int b) { r = () -> last = "" + b + "-" + a; }
		}
		""";

	/** 第三轮：捕获顺序仍是 (b, a)，只改方法体。 */
	static final String V3_ORDER_BA_BODY = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int a, int b) { r = () -> last = "v3:" + b + "-" + a; }
		}
		""";

	// --- 实例 lambda：捕获表带接收者 ---------------------------------------------------------------

	/** 实例 lambda，捕获 (this, k)：合成方法是实例方法 {@code lambda$d$0(I)V}。 */
	static final String V1_THIS_AND_PARAM = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(int k) { r = () -> LAST = "" + k + this; }
		}
		""";

	/**
	 * 删掉 this 引用：合成方法变成<b>静态</b>的 {@code lambda$d$0(I)V}。
	 *
	 * <p>与 V1 的 name+desc 完全相同，只差 {@code ACC_STATIC}——这正是"同名同描述符
	 * 必须避障改名"的形态。</p>
	 */
	static final String V2_THIS_DROPPED = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(int k) { r = () -> LAST = "v2:" + k; }
		}
		""";

	/** 第二轮：只改方法体，捕获表不再动。 */
	static final String V3_THIS_DROPPED_BODY = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(int k) { r = () -> LAST = "v3:" + k; }
		}
		""";

	/** 实例 lambda，捕获顺序 (a, b)。 */
	static final String V1_INSTANCE_ORDER_AB = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(int a, int b) { r = () -> LAST = "" + a + "-" + b + this; }
		}
		""";

	/** 实例 lambda，捕获顺序 (b, a)：描述符与 V1 完全相同。 */
	static final String V2_INSTANCE_ORDER_BA = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(int a, int b) { r = () -> LAST = "" + b + "-" + a + this; }
		}
		""";

	// --- forceStaticLambdas 开启后的形态 ------------------------------------------------------------

	/** 实例 lambda 捕获两个宿主局部变量 + 接收者：会被改成静态 {@code (LB;II)V}。 */
	static final String V1_FORCED_LOCALS = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d() { int a = 1; int b = 2; r = () -> LAST = "" + a + "-" + b + this; }
		}
		""";

	/** 删掉 a、也不再引用 this：静态 {@code (I)V}（捕获数 1，forceStatic 不再前置 receiver）。 */
	static final String V2_FORCED_LOCALS = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d() { int b = 2; r = () -> LAST = "v2:" + b; }
		}
		""";

	/** 零捕获静态 lambda：forceStatic 会给它前置一个<b>没有 LVT 条目</b>的 receiver。 */
	static final String V1_ZERO_CAPTURE = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d() { r = () -> LAST = "v1"; }
		}
		""";

	/**
	 * 捕获表里两个<b>同类型</b>值：{@code other}（局部变量）与 {@code this}（接收者）。
	 *
	 * <p>类型层面无法区分，槽位层面也不行（局部槽会位移）。只有名字能定，而名字里恰好有一个
	 * 是"被前置的接收者"——它没有 LVT 条目。</p>
	 */
	static final String V1_FORCED_TWO_SAME_TYPES = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d() { B other = this; r = () -> LAST = "" + other + this; }
		}
		""";

	/** 同一宿主，改成零捕获（不再引用 other / this）。 */
	static final String V2_FORCED_NO_CAPTURE = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d() { r = () -> LAST = "v2"; }
		}
		""";

	/**
	 * 形参<b>就叫</b> {@code this$0}：{@code $} 是合法的 Java 标识符字符，这个名字编得过，
	 * 但它指的是一个普通对象，不是接收者。
	 */
	static final String V1_PARAM_NAMED_THIS_0 = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(B this$0) { B other = new B(); r = () -> LAST = "v1:" + this$0 + other; }
		}
		""";

	/** 同宿主，但只捕获接收者 {@code this}——与上一份的两个 B 都不是同一个对象。 */
	static final String V2_CAPTURES_THIS = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public void d(B this$0) { r = () -> LAST = "v2:" + this; }
		}
		""";

	// --- 新增捕获的纯源 ---------------------------------------------------------------

	/** 基线：捕获 (String, int)。 */
	static final String V1_NO_MARGIN = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(String s, int k) { r = () -> last = s + k; }
		}
		""";

	/** 多捕获一个 {@code int margin = 10}：常量可重放。 */
	static final String V2_ADDED_CONST = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(String s, int k) { int margin = 10; r = () -> last = "v2:" + s + k + margin; }
		}
		""";

	/** 第三轮：捕获表不动，只改方法体（合成位必须跨轮保留）。 */
	static final String V3_ADDED_CONST_BODY = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(String s, int k) { int margin = 10; r = () -> last = "v3:" + s + k + margin; }
		}
		""";

	/** 基线：只捕获 {@code int k}。 */
	static final String V1_NO_MARK = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) { r = () -> last = "" + k; }
		}
		""";

	/** 多捕获一个 {@code Object m = MARK}（static final，非编译期常量 → 真 GETSTATIC）。 */
	static final String V2_ADDED_STATIC_FINAL = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    private static final Object MARK = new Object();
		    public static void f(int k) { Object m = MARK; r = () -> last = "v2:" + k + m; }
		}
		""";

	/** 多捕获一个 {@code int j}，而 j 是宿主<b>形参</b>：无从重放。 */
	static final String V2_TWO_PARAMS = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k, int j) { r = () -> last = "" + k + j; }
		}
		""";

	/** 多捕获一个 {@code int x = k + 1}：生产者是算式且依赖局部变量。 */
	static final String V2_COMPUTED_CAPTURE = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) { int x = k + 1; r = () -> last = "v2:" + k + x; }
		}
		""";

	/**
	 * 实例 lambda 基线：捕获 (this, k)。
	 *
	 * <p>{@code BASE} 刻意用 {@code Object} 而不是 {@code final int base = 7}：后者是<b>常量变量</b>
	 * （JLS §4.12.4），javac 会直接内联成 {@code bipush 7}，根本不会出现 {@code GETFIELD}——
	 * 那样测到的其实是常量路径，不是字段路径。</p>
	 */
	static final String V1_INSTANCE_PARAM = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public final Object BASE = new Object();
		    public void d(int k) { r = () -> LAST = "" + k + this; }
		}
		""";

	/** 多捕获一个 {@code Object x = BASE}，BASE 是 final 实例字段（非编译期常量 → 真 GETFIELD）。 */
	static final String V2_ADDED_FINAL_FIELD = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public final Object BASE = new Object();
		    public void d(int k) { Object x = BASE; r = () -> LAST = "" + k + x + this; }
		}
		""";

	/** 同上，但 BASE 不是 final：读到的值可能在创建之后被改写，必须拒绝。 */
	static final String V2_ADDED_NONFINAL_FIELD = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public Object BASE = new Object();
		    public void d(int k) { Object x = BASE; r = () -> LAST = "" + k + x + this; }
		}
		""";

	/** 新增捕获是<b>算出来的</b>：{@code float scaled = pad * 2}，pad 是局部常量。 */
	static final String V2_ADDED_FOLDED_EXPR = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        int pad = 10;
		        float scaled = pad * 2;
		        r = () -> last = "v2:" + k + scaled;
		    }
		}
		""";

	/** 新增捕获是 long 移位：{@code long big = base << 40}。 */
	static final String V2_ADDED_LONG_SHIFT = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        long base = 1L;
		        long big = base << 40;
		        r = () -> last = "v2:" + k + big;
		    }
		}
		""";

	/** 新增捕获是 float 乘法：{@code float scaled = half * 2.0f}。 */
	static final String V2_ADDED_FLOAT_MUL = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        float half = 1.5f;
		        float scaled = half * 2.0f;
		        r = () -> last = "v2:" + k + scaled;
		    }
		}
		""";

	/** 新增捕获来自一个"运行时常量"访问器（无参静态调用）。 */
	static final String V2_ADDED_RUNTIME_CONST = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        String sep = System.lineSeparator();
		        r = () -> last = "v2:" + k + sep;
		    }
		}
		""";

	/** 第三轮：只改方法体，运行时常量位必须跨轮保留。 */
	static final String V3_ADDED_RUNTIME_CONST_BODY = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        String sep = System.lineSeparator();
		        r = () -> last = "v3:" + k + sep;
		    }
		}
		""";

	/** 带参形态：{@code Math.max(7, 3)}——实参全是编译期常量。 */
	static final String V2_ADDED_RUNTIME_CONST_ARGS = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        int c = Math.max(7, 3);
		        r = () -> last = "v2:" + k + c;
		    }
		}
		""";

	/** 第三轮：只改方法体，带参的运行时常量位必须跨轮保留。 */
	static final String V3_ADDED_RUNTIME_CONST_ARGS_BODY = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        int c = Math.max(7, 3);
		        r = () -> last = "v3:" + k + c;
		    }
		}
		""";

	/** 带参形态但实参<b>不是</b>常量：无从重放。 */
	static final String V2_ADDED_RUNTIME_CONST_DYNAMIC_ARG = """
		public class B {
		    public static Runnable r;
		    public static String   last = "";
		    public static void f(int k) {
		        int c = Math.max(k, 3);
		        r = () -> last = "v2:" + k + c;
		    }
		}
		""";

	/** 构造器把参数存进了 final 字段；lambda 只捕获 this。 */
	static final String V1_CTOR_THIS_ONLY = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public final String TAG;
		    public B(String tag) { this.TAG = tag; r = () -> LAST = "v1:" + this; }
		}
		""";

	/** 同一构造器，lambda 开始<b>直接用参数 tag</b>：桥靠 {@code this.TAG} 把它取回来。 */
	static final String V2_CTOR_USES_PARAM = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public final String TAG;
		    public B(String tag) { this.TAG = tag; r = () -> LAST = "v2:" + tag + this; }
		}
		""";

	/** 同上，但字段不是 final：构造完成后可能被改写，不能当"参数的值"用。 */
	static final String V2_CTOR_USES_PARAM_NONFINAL = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public String TAG;
		    public B(String tag) { this.TAG = tag; r = () -> LAST = "v2:" + tag + this; }
		}
		""";

	/** 静态 lambda（不持有 this）想用构造器参数：桥没有接收者，取不到那个实例。 */
	static final String V2_CTOR_USES_PARAM_STATIC = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public final String TAG;
		    public B(String tag) { this.TAG = tag; r = () -> LAST = "v2:" + tag; }
		}
		""";

	/** 同上，但老侧是"静态、零捕获"的 lambda。 */
	static final String V1_CTOR_STATIC_ONLY = """
		public class B {
		    public Runnable r;
		    public static String LAST = "";
		    public final String TAG;
		    public B(String tag) { this.TAG = tag; r = () -> LAST = "v1"; }
		}
		""";

	/** 带形参的 SAM：{@code Function<String,String>}，捕获 [String a, int k] + 尾部 SAM 实参 String s。 */
	static final String V1_FUNCTION_SAM = """
		import java.util.function.Function;
		public class B {
		    public static Function<String, String> f;
		    public static void make(String a, int k) { f = s -> a + k + ":" + s; }
		}
		""";

	/** 删掉 String 捕获 → {@code lambda$make$0(ILjava/lang/String;)Ljava/lang/String;}。 */
	static final String V2_FUNCTION_SAM_DROP_CAPTURE = """
		import java.util.function.Function;
		public class B {
		    public static Function<String, String> f;
		    public static void make(int k) { f = s -> k + ":" + s; }
		}
		""";

	/** 第二轮：捕获表不动，只改方法体。 */
	static final String V3_FUNCTION_SAM_BODY_ONLY = """
		import java.util.function.Function;
		public class B {
		    public static Function<String, String> f;
		    public static void make(int k) { f = s -> k + "|" + s; }
		}
		""";

	/** S45 现场形态 V1：同一宿主方法里的 2 条同形 Supplier lambda，各捕获一个 int。 */
	static final String V1_AMBIGUOUS_DROP = """
		import java.util.function.Supplier;
		public class SwitchHost {
		    public static Supplier<String>[] held = new Supplier[2];
		    public static void make(int n) {
		        Supplier<String> a = () -> "UP-" + n;
		        Supplier<String> b = () -> "DN-" + n;
		        held[0] = a;
		        held[1] = b;
		    }
		}
		""";

	/** S45 现场形态 V2：两条 lambda 都丢掉 int 捕获，宿主不变（调用序列长度仍为 2）。 */
	static final String V2_AMBIGUOUS_DROP = """
		import java.util.function.Supplier;
		public class SwitchHost {
		    public static Supplier<String>[] held = new Supplier[2];
		    public static void make(int n) {
		        Supplier<String> a = () -> "UP";
		        Supplier<String> b = () -> "DN";
		        held[0] = a;
		        held[1] = b;
		    }
		}
		""";

	/** S14 现场形态：同类两个宿主方法；V2 交换 this 形状（i2s 丢 this、s2i 加 this）。 */
	static final String V1_THIS_FLIP = """
		import java.util.function.Function;
		import java.util.function.Supplier;
		public class B {
		    private final String instTag = "INST";
		    private static final B HOLDER = new B();
		    public static Function<String, String> i2s;
		    public static Function<String, String> s2i;
		    public static void init() { i2s = HOLDER.makeInstToStatic(); s2i = HOLDER.makeStaticToInst(); }
		    private Function<String, String> makeInstToStatic() {
		        return s -> ((Supplier<String>) () -> instTag + ":" + s + "-v1").get();
		    }
		    private Function<String, String> makeStaticToInst() {
		        return s -> ((Supplier<String>) () -> "static:" + s + "-v1").get();
		    }
		}
		""";

	/** S14 V2：i2s 体不再读 this、s2i 体开始读 this；两侧各自前插一个兄弟 Supplier。 */
	static final String V2_THIS_FLIP = """
		import java.util.function.Function;
		import java.util.function.Supplier;
		public class B {
		    private final String instTag = "INST";
		    private static final B HOLDER = new B();
		    public static Function<String, String> i2s;
		    public static Function<String, String> s2i;
		    public static void init() { i2s = HOLDER.makeInstToStatic(); s2i = HOLDER.makeStaticToInst(); }
		    private Function<String, String> makeInstToStatic() {
		        Supplier<String> sib = () -> "sib-i2s";
		        if (sib.get().isEmpty()) return Function.identity();
		        return s -> ((Supplier<String>) () -> "noThis:" + s + "-v2").get();
		    }
		    private Function<String, String> makeStaticToInst() {
		        Supplier<String> sib = () -> "sib-s2i";
		        if (sib.get().isEmpty()) return Function.identity();
		        return s -> ((Supplier<String>) () -> instTag + ":" + s + "-v2").get();
		    }
		}
		""";

	// ------------------------------------------------------------------------------------------------
	// 工具
	// ------------------------------------------------------------------------------------------------

	static Path compile(String javac, Path root, String sub, String source) throws Exception {
		return compile(javac, root, sub, source, false);
	}

	/** @param debug 是否带 {@code -g}（带 LVT 才有捕获变量的源码名） */
	static Path compile(String javac, Path root, String sub, String source, boolean debug) throws Exception {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("public (?:class|interface) (\\w+)").matcher(source);
		if (!m.find()) throw new IllegalArgumentException("夹具源码里找不到 public class/interface");
		Path srcDir = Files.createDirectories(root.resolve(sub).resolve("src"));
		Path outDir = Files.createDirectories(root.resolve(sub).resolve("out"));
		Path file = srcDir.resolve(m.group(1) + ".java");
		Files.writeString(file, source);
		HstestFixtures.compile(javac, outDir, debug ? List.of("-g") : List.of(), file.toFile());
		return outDir;
	}

	/**
	 * 走一遍 {@code AnnotationTransformer.forceStaticLambdas}，把类变成"实例 lambda 也已静态化"
	 * 的形态——这正是线上 {@code RemoldAgent} 在开关打开时喂给对齐器的字节码。
	 */
	static byte[] force(byte[] bytes) {
		String slash = new ClassReader(bytes).getClassName();
		AnnotationTransformer.HierarchyTree.register(bytes);
		return AnnotationTransformer.forceStaticLambdas(
			bytes, slash, LambdaBridgeAlignTest.class.getClassLoader());
	}

	static ClassNode parse(byte[] bytes) {		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	/** 连调试信息一起读（{@link #parse} 会丢掉 LVT，改写 LVT 的辅助方法不能用它）。 */
	static ClassNode parseWithDebug(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	/**
	 * 改掉某个方法里指定槽位的 LVT 名字，用来复刻 kotlinc 给外层 {@code this} 写的
	 * {@code this$0}。除了这个名字，其余字节码原样保留。
	 */
	static byte[] renameLvt(byte[] bytes, String name, String desc, int slot, String newName) {
		ClassNode cn = parseWithDebug(bytes);
		MethodNode mn = byKey(cn, name, desc);
		assertNotNull(mn, "夹具前提：找不到 " + name + desc);
		assertNotNull(mn.localVariables, "夹具前提：该夹具必须带 -g");
		boolean hit = false;
		for (LocalVariableNode lv : mn.localVariables) {
			if (lv.index == slot) {
				lv.name = newName;
				hit = true;
			}
		}
		assertTrue(hit, "夹具前提：槽 " + slot + " 必须有 LVT 条目");
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/**
	 * 在指定方法的第一条 indy 之前插一条 {@code NOP}：不改栈、不改语义，只让"紧邻 indy 的
	 * 那几条指令是局部变量加载"这个前提不成立，从而逼捕获槽回溯失败。
	 */
	static byte[] insertNopBeforeIndy(byte[] bytes, String name, String desc) {
		ClassNode cn = parseWithDebug(bytes);
		MethodNode mn = byKey(cn, name, desc);
		assertNotNull(mn, "夹具前提：找不到 " + name + desc);
		boolean hit = false;
		for (AbstractInsnNode i : mn.instructions.toArray()) {
			if (i instanceof InvokeDynamicInsnNode) {
				mn.instructions.insertBefore(i, new InsnNode(Opcodes.NOP));
				hit = true;
				break;
			}
		}
		assertTrue(hit, "夹具前提：该方法里必须有 indy");
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static MethodNode byKey(ClassNode cn, String name, String desc) {
		if (cn == null) return null;
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
		}
		return null;
	}

	static List<AbstractInsnNode> real(MethodNode mn) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) {
			if (i.getOpcode() >= 0) out.add(i);
		}
		return out;
	}

	static boolean hasVar(MethodNode mn, int opcode, int slot) {
		for (AbstractInsnNode i : mn.instructions) {
			if (i instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot) return true;
		}
		return false;
	}

	/**
	 * 桥体里"压值"的那段指令：跳过 {@code LDC loc; onBridgeInvoked} 标记，到<b>核心调用</b>为止。
	 *
	 * <p>不能"遇到第一条方法调用就停"——运行时常量访问器本身就是一条 {@code INVOKESTATIC}，
	 * 它属于"压值"而不是转发。核心调用恒是 RETURN 之前那一条，所以取<b>最后</b>一条方法调用作边界。</p>
	 */
	static List<AbstractInsnNode> valuePushes(MethodNode mn) {
		List<AbstractInsnNode> real = real(mn);
		int lastCall = -1;
		for (int i = 2; i < real.size(); i++) {
			if (real.get(i) instanceof MethodInsnNode) lastCall = i;
		}
		if (lastCall < 0) return new ArrayList<>();
		return new ArrayList<>(real.subList(2, lastCall));
	}

	/** 这条指令压的是哪个 int 常量（ICONST_* / BIPUSH / SIPUSH / LDC 都算）。 */
	static Integer intConstOf(AbstractInsnNode n) {
		int op = n.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (n instanceof IntInsnNode i) return i.operand;
		if (n instanceof LdcInsnNode l && l.cst instanceof Integer) return (Integer) l.cst;
		return null;
	}

	/** 方法体里所有变量加载指令（按出现顺序）。 */
	static List<AbstractInsnNode> loadsOf(MethodNode mn) {		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) {
			if (!(i instanceof VarInsnNode v)) continue;
			int op = v.getOpcode();
			if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) out.add(i);
		}
		return out;
	}

	static void assertLoad(AbstractInsnNode insn, int opcode, int slot, String msg) {
		assertTrue(insn instanceof VarInsnNode, msg + "（不是变量加载指令）");
		VarInsnNode v = (VarInsnNode) insn;
		assertEquals(opcode, v.getOpcode(), msg + "（opcode）");
		assertEquals(slot, v.var, msg + "（槽号）");
	}

	static boolean containsCall(MethodNode mn, String name) {
		if (mn == null) return false;
		for (AbstractInsnNode i : mn.instructions) {
			if (i instanceof MethodInsnNode m && m.name.equals(name)) return true;
		}
		return false;
	}

	/** 桥体里除标记与转发之外的调用不该存在；返回那条唯一的转发调用。 */
	static MethodInsnNode onlyCoreCall(MethodNode mn) {
		MethodInsnNode core = null;
		int n = 0;
		for (AbstractInsnNode i : mn.instructions) {
			if (!(i instanceof MethodInsnNode m)) continue;
			if (m.name.equals("onBridgeInvoked") || m.name.equals("onOrphanInvoked")) continue;
			core = m;
			n++;
		}
		assertEquals(1, n, "桥体应当恰好有一条转发调用");
		return core;
	}

	/** 独立 ClassLoader 定义对齐产物：桥体里的 {@code onBridgeInvoked} 由父加载器提供。 */
	static Class<?> define(byte[] bytes) {
		return new ClassLoader(LambdaBridgeAlignTest.class.getClassLoader()) {
			Class<?> defineOne() {
				return defineClass(null, bytes, 0, bytes.length);
			}
		}.defineOne();
	}

	static String table(ClassNode cn) {
		StringBuilder sb = new StringBuilder("[");
		for (MethodNode mn : cn.methods) {
			if (sb.length() > 1) sb.append(", ");
			sb.append(mn.name).append(mn.desc);
		}
		return sb.append(']').toString();
	}
}
