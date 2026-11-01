import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
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

	// ------------------------------------------------------------------------------------------------
	// 工具
	// ------------------------------------------------------------------------------------------------

	static Path compile(String javac, Path root, String sub, String source) throws Exception {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("public (?:class|interface) (\\w+)").matcher(source);
		if (!m.find()) throw new IllegalArgumentException("夹具源码里找不到 public class/interface");
		Path srcDir = Files.createDirectories(root.resolve(sub).resolve("src"));
		Path outDir = Files.createDirectories(root.resolve(sub).resolve("out"));
		Path file = srcDir.resolve(m.group(1) + ".java");
		Files.writeString(file, source);
		HstestFixtures.compile(javac, outDir, file.toFile());
		return outDir;
	}

	static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
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

	/** 方法体里所有变量加载指令（按出现顺序）。 */
	static List<AbstractInsnNode> loadsOf(MethodNode mn) {
		List<AbstractInsnNode> out = new ArrayList<>();
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

	static String table(ClassNode cn) {
		StringBuilder sb = new StringBuilder("[");
		for (MethodNode mn : cn.methods) {
			if (sb.length() > 1) sb.append(", ");
			sb.append(mn.name).append(mn.desc);
		}
		return sb.append(']').toString();
	}
}
