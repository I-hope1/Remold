import remold.LambdaAligner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 孤儿 lambda 保留上一轮方法体（{@code -Dremold.agent.orphan_retain_body}）的端到端用例。
 *
 * <p><b>要回答的问题</b>：非 {@code void} 的孤儿 lambda 默认返回 {@code null}/0，调用方
 * 拿到返回值后直接链式调用或拆箱就炸；保留旧方法体至少让老 {@code CallSite} 拿到合法值。
 * 但旧体会引用本类成员，这些成员在新版本里可能已被删除——直接抄过去会在重定义校验或
 * 运行期抛 {@code NoSuchFieldError}/{@code NoSuchMethodError}/{@code VerifyError}。</p>
 *
 * <p><b>判据</b>（三条，全部落在可执行终局）：</p>
 * <ol>
 *   <li>保留体：删除一个非 void lambda，产物里的幽灵方法体必须<b>继续执行旧逻辑</b>
 *       （反射调用返回旧值，而不是 {@code null}）；</li>
 *   <li>存活性回退：旧体引用的本类私有方法被同时删除时，不得保留旧体（否则运行期
 *       {@code NoSuchMethodError}），必须回退成返回类型默认值桩；</li>
 *   <li>void 默认不变：{@code void} 孤儿仍走 no-op，删掉的副作用不得继续执行；打开
 *       {@code orphan_retain_void} 后才保留。</li>
 * </ol>
 *
 * <p>另有多轮搬运（第 2、3 轮产物仍保留旧体，不因再次幽灵化而退回默认值桩）与身份判定
 * （保留体仍被 {@code isGhostMethod} 认出、不参与配对）两个不变量用例。</p>
 */
public class OrphanBodyRetentionTest {

	@AfterEach
	void restoreDefaults() {
		LambdaAligner.setOrphanBodyRetention(true, false);
		LambdaAligner.clearLoggedOrphans();
	}

	// ------------------------------------------------------------------ 夹具

	/** V1：两个非 void lambda，B 的体自包含（不引用本类成员）。 */
	static String v1() {
		return "import java.util.function.*;\n"
			+ "public class Ghost {\n"
			+ "  static final Function<Integer, String> A = x -> \"keep:\" + x + \":v1\";\n"
			+ "  static final Function<Integer, String> B = x -> \"drop:\" + x + \":v1\";\n"
			+ "  static void reg(Object o) { }\n"
			+ "  static { reg(A); reg(B); }\n"
			+ "}\n";
	}

	/** V2：只删 B（A 原样保留）。 */
	static String v2DropB() {
		return "import java.util.function.*;\n"
			+ "public class Ghost {\n"
			+ "  static final Function<Integer, String> A = x -> \"keep:\" + x + \":v1\";\n"
			+ "  static void reg(Object o) { }\n"
			+ "  static { reg(A); }\n"
			+ "}\n";
	}

	/** V2 变体：B 的体调用本类私有静态方法 drop()，且 drop() 在 V2 也被删除。 */
	static String v1WithHelper() {
		return "import java.util.function.*;\n"
			+ "public class Ghost {\n"
			+ "  static final Function<Integer, String> A = x -> \"keep:\" + x + \":v1\";\n"
			+ "  static final Function<Integer, String> C = x -> drop(x);\n"
			+ "  static String drop(int x) { return \"drop:\" + x + \":v1\"; }\n"
			+ "  static void reg(Object o) { }\n"
			+ "  static { reg(A); reg(C); }\n"
			+ "}\n";
	}

	static String v2DropHelperAndC() {
		return "import java.util.function.*;\n"
			+ "public class Ghost {\n"
			+ "  static final Function<Integer, String> A = x -> \"keep:\" + x + \":v1\";\n"
			+ "  static void reg(Object o) { }\n"
			+ "  static { reg(A); }\n"
			+ "}\n";
	}

	/** void 夹具：唯一 lambda 有副作用（静态计数器）。 */
	static String voidV1() {
		return "public class VGhost {\n"
			+ "  static int counter;\n"
			+ "  static final Runnable R = () -> effect();\n"
			+ "  static void effect() { counter++; }\n"
			+ "  static int counter() { return counter; }\n"
			+ "}\n";
	}

	static String voidV2() {
		return "public class VGhost {\n"
			+ "  static int counter;\n"
			+ "  static void effect() { counter++; }\n"
			+ "  static int counter() { return counter; }\n"
			+ "}\n";
	}

	// ------------------------------------------------------------------ 用例

	@Test
	void nonVoidOrphanKeepsPreviousBodyAndReturnsOldValue(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = read(compile(javac, tmp, "a/v1", v1()), "Ghost");
		byte[] v2 = read(compile(javac, tmp, "a/v2", v2DropB()), "Ghost");

		byte[] alignedBytes = LambdaAligner.align(v1, v2);
		ClassNode aligned = parse(alignedBytes);
		String ghost = onlyGhost(aligned).name;
		MethodNode body = onlyGhost(aligned);
		assertTrue(callsMethod(body, "onOrphanInvoked"), "幽灵必须仍调用 onOrphanInvoked 做策略分发/熔断");
		assertEquals(3, countOrphanInvokeArgs(body),
			"保留体的幽灵必须用三参 onOrphanInvoked(String,Z,Z) 标记：" + table(aligned));

		// 执行终局：旧 CallSite 拿到的是旧逻辑的合法返回值，而不是 null。
		Object result = new Loaded(alignedBytes, "Ghost").invoke(ghost, 7);
		assertEquals("drop:7:v1", result,
			"保留旧方法体后，被删除的非 void lambda 必须仍返回旧逻辑的合法值（不是 null）");
	}

	@Test
	void referencesToRemovedOwnMembersFallBackToDefaultStub(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = read(compile(javac, tmp, "b/v1", v1WithHelper()), "Ghost");
		byte[] v2 = read(compile(javac, tmp, "b/v2", v2DropHelperAndC()), "Ghost");

		byte[] alignedBytes = LambdaAligner.align(v1, v2);
		ClassNode aligned = parse(alignedBytes);
		MethodNode ghost = onlyGhost(aligned);

		// 旧体调用了本类私有 drop(int)，而 drop(int) 在本轮被删除 → 保留旧体必然 NoSuchMethodError，
		// 判据必须拒绝并回退默认值桩（2 参标记，且体内不再出现 drop 引用）。
		assertTrue(callsMethod(ghost, "onOrphanInvoked"), "回退后仍是幽灵桩（要能熔断/告警）");
		assertEquals(2, countOrphanInvokeArgs(ghost),
			"引用已删本类成员的旧体不得保留，必须回退默认值桩：" + table(aligned));
		assertFalse(callsMethod(ghost, "drop"), "回退后不得继续引用被删方法 drop：" + table(aligned));

		Object result = new Loaded(alignedBytes, "Ghost").invoke(ghost.name, 7);
		assertNull(result, "回退为默认值桩后，非 void 返回值是 null（这是既有的安全降级口径）");
	}

	@Test
	void voidOrphanStaysNoopByDefaultAndCanBeOptedIn(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = read(compile(javac, tmp, "c/v1", voidV1()), "VGhost");
		byte[] v2 = read(compile(javac, tmp, "c/v2", voidV2()), "VGhost");

		LambdaAligner.setOrphanBodyRetention(true, false);   // void 保留默认关
		byte[] defaultBytes = LambdaAligner.align(v1, v2);
		ClassNode alignedDefault = parse(defaultBytes);
		MethodNode ghost = onlyGhost(alignedDefault);
		assertFalse(callsMethod(ghost, "effect"),
			"void 孤儿默认必须 no-op：删掉的副作用不得继续执行：" + table(alignedDefault));
		Loaded defaultLoaded = new Loaded(defaultBytes, "VGhost");
		defaultLoaded.invoke(ghost.name);
		assertEquals(0, defaultLoaded.staticInt("counter"),
			"默认 no-op 下计数器不得增加");

		LambdaAligner.setOrphanBodyRetention(true, true);    // 显式开启 void 保留
		byte[] optInBytes = LambdaAligner.align(v1, v2);
		ClassNode alignedOptIn = parse(optInBytes);
		MethodNode optInGhost = onlyGhost(alignedOptIn);
		assertTrue(callsMethod(optInGhost, "effect"),
			"orphan_retain_void=true 时必须保留 void 旧体：" + table(alignedOptIn));
		Loaded optInLoaded = new Loaded(optInBytes, "VGhost");
		optInLoaded.invoke(optInGhost.name);
		assertEquals(1, optInLoaded.staticInt("counter"),
			"保留 void 旧体后，副作用必须真实执行（终局断言）");
	}

	@Test
	void retainedBodySurvivesFurtherRounds(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = read(compile(javac, tmp, "d/v1", v1()), "Ghost");
		byte[] v2 = read(compile(javac, tmp, "d/v2", v2DropB()), "Ghost");

		byte[] round2 = LambdaAligner.align(v1, v2);
		ClassNode align2 = parse(round2);
		String ghost = onlyGhost(align2).name;

		// 第二轮：上一轮产物是"旧字节码"，本轮新字节码仍是 v2（lambda 依然不存在）。
		byte[] round3 = LambdaAligner.align(round2, v2);
		ClassNode align3 = parse(round3);
		MethodNode ghost3 = byName(align3, ghost);
		assertNotNull(ghost3, "第二轮产物必须保留幽灵槽位：" + table(align3));
		assertEquals(3, countOrphanInvokeArgs(ghost3),
			"多轮搬运不得把保留体退回默认值桩：" + table(align3));
		assertTrue(callsMethod(ghost3, "onOrphanInvoked"), "第二轮幽灵仍须走策略分发");

		Object result = new Loaded(round3, "Ghost").invoke(ghost, 7);
		assertEquals("drop:7:v1", result, "第二轮旧实例仍应拿到旧逻辑返回值");
	}

	@Test
	void retainedBodyIsStillTreatedAsGhostForPairing(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = read(compile(javac, tmp, "e/v1", v1()), "Ghost");
		byte[] v2 = read(compile(javac, tmp, "e/v2", v2DropB()), "Ghost");

		ClassNode aligned = parse(LambdaAligner.align(v1, v2));
		MethodNode ghost = onlyGhost(aligned);
		// 身份解耦：保留体里有旧业务指令，但幽灵判定只看 onOrphanInvoked（不是"空方法/短方法"形态），
		// 因此不会在下一轮被当成活 lambda 参与配对。
		assertTrue(isGhostMethod(ghost), "保留体必须仍被识别为幽灵：" + table(aligned));

		Map<String, String> mapping = LambdaAligner.LAST_RESULT.methodMapping;
		assertFalse(mapping.containsKey(ghost.name + ":" + ghost.desc),
			"保留体幽灵不得出现在配对映射里（它是死名字，只能被兜住）：" + mapping);
	}

	@Test
	void retentionCanBeDisabledAsControl(@TempDir Path tmp) throws Exception {
		String javac = HstestFixtures.javac(21);
		byte[] v1 = read(compile(javac, tmp, "f/v1", v1()), "Ghost");
		byte[] v2 = read(compile(javac, tmp, "f/v2", v2DropB()), "Ghost");

		LambdaAligner.setOrphanBodyRetention(false, false);
		byte[] alignedBytes = LambdaAligner.align(v1, v2);
		ClassNode aligned = parse(alignedBytes);
		MethodNode ghost = onlyGhost(aligned);
		assertEquals(2, countOrphanInvokeArgs(ghost),
			"关闭保留后必须退回既有默认值桩（对照组）：" + table(aligned));
		assertNull(new Loaded(alignedBytes, "Ghost").invoke(ghost.name, 7),
			"关闭保留后返回值必须是 null（对照组证明保留行为确实由开关驱动）");
	}

	// ------------------------------------------------------------------ 工具

	static byte[] read(Path outDir, String simpleName) throws Exception {
		return Files.readAllBytes(outDir.resolve(simpleName + ".class"));
	}

	static Path compile(String javac, Path root, String sub, String source) throws Exception {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("public class (\\w+)").matcher(source);
		if (!m.find()) throw new IllegalArgumentException("夹具源码里找不到 public class");
		Path srcDir = Files.createDirectories(root.resolve(sub).resolve("src"));
		Path outDir = Files.createDirectories(root.resolve(sub).resolve("out"));
		Path file = srcDir.resolve(m.group(1) + ".java");
		Files.writeString(file, source);
		HstestFixtures.compile(javac, outDir, file.toFile());
		return outDir;
	}

	static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	static MethodNode byName(ClassNode cn, String name) {
		for (MethodNode mn : cn.methods) if (mn.name.equals(name)) return mn;
		return null;
	}

	static MethodNode onlyGhost(ClassNode cn) {
		MethodNode found = null;
		for (MethodNode mn : cn.methods) {
			if (isGhostMethod(mn)) {
				assertNull(found, "夹具前提：本用例只允许一个幽灵方法：" + table(cn));
				found = mn;
			}
		}
		assertNotNull(found, "产物里找不到幽灵方法：" + table(cn));
		return found;
	}

	static boolean isGhostMethod(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.name.equals("onOrphanInvoked")) return true;
		}
		return false;
	}

	/** 幽灵桩调用 onOrphanInvoked 的实参个数（1/2/3）；保留体是 3，默认值桩是 2。 */
	static int countOrphanInvokeArgs(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.name.equals("onOrphanInvoked")) {
				return org.objectweb.asm.Type.getArgumentTypes(m.desc).length;
			}
		}
		return -1;
	}

	static boolean callsMethod(MethodNode mn, String name) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.name.equals(name)) return true;
		}
		return false;
	}

	static String table(ClassNode cn) {
		StringBuilder sb = new StringBuilder();
		for (MethodNode mn : cn.methods) sb.append(mn.name).append(mn.desc).append(' ');
		return sb.toString();
	}

	/**
	 * 独立 ClassLoader 里定义一份对齐产物：反射调用幽灵方法与读静态字段必须在<b>同一个</b> Class 上，
	 * 否则静态状态不共享（每次 defineClass 都是一份新的类初始化状态）。
	 */
	static final class Loaded {
		final Class<?> cls;

		Loaded(byte[] bytes, String binaryName) throws Exception {
			ClassLoader loader = new ClassLoader(OrphanBodyRetentionTest.class.getClassLoader()) {
				Class<?> define(String n, byte[] b) { return defineClass(n, b, 0, b.length); }
			};
			this.cls = (Class<?>) loader.getClass()
			                         .getDeclaredMethod("define", String.class, byte[].class)
			                         .invoke(loader, binaryName, bytes);
		}

		Object invoke(String ghostName, Object... args) throws Exception {
			Class<?>[] types = new Class<?>[args.length];
			for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
			Method m = cls.getDeclaredMethod(ghostName, types);
			m.setAccessible(true);
			return m.invoke(null, args);
		}

		int staticInt(String field) throws Exception {
			java.lang.reflect.Field f = cls.getDeclaredField(field);
			f.setAccessible(true);
			return f.getInt(null);
		}
	}

}
