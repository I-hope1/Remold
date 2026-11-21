import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 真机探针：子类热更时新增一个与祖先同名同描述符的字段（字段遮蔽）。
 *
 * <p>缺陷现场（外部压力测试报告）：DCEVM 重定义把继承槽位的值复制进子类新增的同名槽位，
 * 于是存量实例上该槽位已有非默认值（等于祖先字段值）；InitFix 的条件 CAS 以"类型零值"为期望值，
 * 判定"已被写过"并跳过，新增字段停在祖先值上，读到 {@code mid.x == 10} 而不是新初值 {@code 200}。</p>
 *
 * <p>探针形态与外部套件一致：先编译 v1 并建立实例，再写入 v2 字节码（两张子类各新增 {@code int x}），
 * 由 agent 的文件监听触发真实 DCEVM 重定义；随后再写 v3（两张子类各新增一个非遮蔽字段），
 * 用来证明非遮蔽字段的既有语义没有改变。</p>
 *
 * <p><b>读数一律走实例方法，不走反射字段</b>：redefine 之后 {@code Class.getDeclaredFields()} 的
 * 元数据与偏移可能仍是旧布局（实测 {@code MidG1.x} 在 {@code getDeclaredFields()} 里缺席、
 * {@code LeafG2.lx} 的偏移还是旧值），用它读会得到"看起来没修"的假读数；
 * 方法体读字段走的是 JVM 当前布局，才是业务可观测终局。</p>
 *
 * <p>跑法：{@code ./gradlew :tests:shadowFieldProbe -Pp05.jbr=/opt/idea/jbr}。</p>
 */
public class ShadowFieldInitFixProbe {

	/** {@code baseX()/baseBx()} 定义在永不重定义的 BaseG0 上，用来读祖先槽位的真实值。 */
	private static final String V1 = ""
		+ "package shadowprobe;\n"
		+ "public class BaseG0 {\n"
		+ "    public int x = 10;\n"
		+ "    public int bx = 15;\n"
		+ "    public int baseX() { return x; }\n"
		+ "    public int baseBx() { return bx; }\n"
		+ "}\n";

	private static final String V1_MID = ""
		+ "package shadowprobe;\n"
		+ "public class MidG1 extends BaseG0 {\n"
		+ "    public int mx = 31;\n"
		+ "    public int ver() { return 1; }\n"
		+ "    public int selfX() { return x; }\n"
		+ "    public int selfMx() { return mx; }\n"
		+ "}\n";

	private static final String V1_LEAF = ""
		+ "package shadowprobe;\n"
		+ "public class LeafG2 extends MidG1 {\n"
		+ "    public int lx = 2 * x + bx + mx;\n"
		+ "    public int ver() { return 1; }\n"
		+ "    public int selfX() { return x; }\n"
		+ "    public int selfLx() { return lx; }\n"
		+ "}\n";

	private static final String V2_MID = ""
		+ "package shadowprobe;\n"
		+ "public class MidG1 extends BaseG0 {\n"
		+ "    public int mx = 31;\n"
		+ "    public int x = 200;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int selfX() { return x; }\n"
		+ "    public int selfMx() { return mx; }\n"
		+ "}\n";

	private static final String V2_LEAF = ""
		+ "package shadowprobe;\n"
		+ "public class LeafG2 extends MidG1 {\n"
		+ "    public int x = 300;\n"
		+ "    public int lx = 2 * x + bx + mx;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int selfX() { return x; }\n"
		+ "    public int selfLx() { return lx; }\n"
		+ "}\n";

	private static final String V3_MID = ""
		+ "package shadowprobe;\n"
		+ "public class MidG1 extends BaseG0 {\n"
		+ "    public int mx = 31;\n"
		+ "    public int x = 200;\n"
		+ "    public int nm = 41;\n"
		+ "    public int ver() { return 3; }\n"
		+ "    public int selfX() { return x; }\n"
		+ "    public int selfMx() { return mx; }\n"
		+ "    public int selfNm() { return nm; }\n"
		+ "}\n";

	private static final String V3_LEAF = ""
		+ "package shadowprobe;\n"
		+ "public class LeafG2 extends MidG1 {\n"
		+ "    public int x = 300;\n"
		+ "    public int lx = 2 * x + bx + mx;\n"
		+ "    public int nl = 42;\n"
		+ "    public int ver() { return 3; }\n"
		+ "    public int selfX() { return x; }\n"
		+ "    public int selfLx() { return lx; }\n"
		+ "    public int selfNl() { return nl; }\n"
		+ "}\n";

	private static Path classesDir;
	private static Path srcDir;
	private static Object midInst;
	private static Object leafInst;

	public static void main(String[] args) throws Exception {
		String dirProp = System.getProperty("remold.shadowfield.dir");
		if (dirProp == null) {
			System.out.println("PROBE-ERROR: -Dremold.shadowfield.dir is required");
			System.exit(2);
		}
		classesDir = Paths.get(dirProp).toAbsolutePath().normalize();
		srcDir = classesDir.getParent().resolve("src");
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);

		boot();
		System.out.println("PROBE-BOOT baseline: " + describe("v1"));

		swap(V2_MID, V2_LEAF, 2);
		System.out.println("PROBE-READING v2: " + describe("v2"));
		// 红读数（修复前）：mid.selfX()=10、leaf.selfX()=10，两者都停在祖先 BaseG0.x 的 10 上。
		check(call(midInst, "selfX") == 200,
		 "shadowed field MidG1.x must be patched to its new initializer 200, was "
			+ call(midInst, "selfX"));
		check(call(leafInst, "selfX") == 300,
		 "shadowed field LeafG2.x must be patched to its new initializer 300, was "
			+ call(leafInst, "selfX"));
		check(call(midInst, "baseX") == 10 && call(leafInst, "baseX") == 10,
		 "ancestor BaseG0.x must keep its existing value 10 on both instances, was "
			+ call(midInst, "baseX") + "/" + call(leafInst, "baseX"));

		// F3 对照：非遮蔽新增字段保持既有零值 CAS 语义（当前值 = 类型零值 -> 写入新初值）。
		swap(V3_MID, V3_LEAF, 3);
		System.out.println("PROBE-READING v3: " + describe("v3"));
		check(call(midInst, "selfNm") == 41,
		 "non-shadowed field MidG1.nm must be patched to 41, was " + call(midInst, "selfNm"));
		check(call(leafInst, "selfNl") == 42,
		 "non-shadowed field LeafG2.nl must be patched to 42, was " + call(leafInst, "selfNl"));
		check(call(midInst, "selfX") == 200 && call(leafInst, "selfX") == 300,
		 "shadowed values must stay at their patched initializers across the next round, was "
			+ call(midInst, "selfX") + "/" + call(leafInst, "selfX"));

		System.out.println("PROBE-PASS: shadowed and non-shadowed added fields all carry their initializers");
	}

	// ---------------------------------------------------------------- driver

	private static void boot() throws Exception {
		compile("BaseG0", V1);
		compile("MidG1", V1_MID);
		compile("LeafG2", V1_LEAF);
		Class<?> midClass = Class.forName("shadowprobe.MidG1");
		Class<?> leafClass = Class.forName("shadowprobe.LeafG2");
		midInst = midClass.getDeclaredConstructor().newInstance();
		leafInst = leafClass.getDeclaredConstructor().newInstance();
		check(call(leafInst, "baseX") == 10, "precondition: BaseG0.x is 10 on the leaf instance");
		check(call(midInst, "selfMx") == 31, "precondition: MidG1.mx is 31 on the mid instance");
		check(call(leafInst, "selfLx") == 2 * 10 + 15 + 31,
		 "precondition: LeafG2.lx is derived from the v1 fields, was " + call(leafInst, "selfLx"));
	}

	private static void swap(String midSource, String leafSource, int expectedVer) throws Exception {
		compile("MidG1", midSource);
		compile("LeafG2", leafSource);
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
			if (version(leafInst) == expectedVer && version(midInst) == expectedVer) {
				// redefine 已生效；InitFix 后处理与 redefine 在同一线程先后执行，
				// 再等读数稳定，避免把"补丁尚未落地"误判成"补丁被跳过"。
				awaitStable();
				return;
			}
		}
		System.out.println("PROBE-FAIL: round " + expectedVer
		                   + " did not take effect within 30s, state=" + describe("timeout"));
		System.exit(1);
	}

	/** 轮询到连续四次读数一致（或超时），只用于区分"尚未落地"与"已跳过"。 */
	private static void awaitStable() throws Exception {
		String last = null;
		int    same = 0;
		long   deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			String now = snapshot();
			if (now.equals(last)) {
				if (++same >= 4) return;
			} else {
				same = 0;
				last = now;
			}
			Thread.sleep(100);
		}
	}

	private static String snapshot() {
		try {
			return call(midInst, "ver") + "/" + call(leafInst, "ver") + "/"
			       + call(midInst, "selfX") + "/" + call(leafInst, "selfX");
		} catch (Throwable t) {
			return "<ERR " + t + ">";
		}
	}

	private static int version(Object instance) {
		try {
			return call(instance, "ver");
		} catch (Throwable t) {
			return -1;
		}
	}

	private static String describe(String tag) {
		return tag + ": mid.ver=" + version(midInst) + " leaf.ver=" + version(leafInst)
		       + " base.x=" + call(midInst, "baseX") + "/" + call(leafInst, "baseX")
		       + " mid.x=" + call(midInst, "selfX") + " leaf.x=" + call(leafInst, "selfX")
		       + " bx=" + call(leafInst, "baseBx") + " mx=" + call(leafInst, "selfMx")
		       + " leaf.lx=" + call(leafInst, "selfLx")
		       + " " + optional("mid.nm", midInst, "selfNm") + optional("leaf.nl", leafInst, "selfNl");
	}

	private static String optional(String label, Object instance, String method) {
		try {
			return label + "=" + call(instance, method);
		} catch (Throwable t) {
			return label + "=<absent>";
		}
	}

	/** 读数入口：调用实例方法，避免 redefine 之后反射字段元数据仍是旧布局。 */
	private static int call(Object instance, String method) {
		try {
			return (Integer) instance.getClass().getMethod(method).invoke(instance);
		} catch (ReflectiveOperationException t) {
			throw new IllegalStateException("cannot invoke " + instance.getClass().getName()
			                                + "." + method + "()", t);
		}
	}

	// ---------------------------------------------------------------- utils

	private static void compile(String simpleName, String source) throws IOException {
		Path srcFile = srcDir.resolve(simpleName + ".java");
		Files.write(srcFile, source.getBytes(StandardCharsets.UTF_8));
		javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
		int rc = javac.run(null, System.out, System.err,
			"-proc:none", "-d", classesDir.toString(), srcFile.toString());
		if (rc != 0) throw new IllegalStateException("javac failed for " + simpleName + " rc=" + rc);
	}

	private static void check(boolean cond, String msg) {
		if (!cond) {
			System.out.println("PROBE-FAIL: " + msg);
			System.exit(1);
		}
	}
}
