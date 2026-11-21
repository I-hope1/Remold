import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 真机探针：子类热更时新增一个与祖先同名同描述符的字段（字段遮蔽）。
 *
 * <p>缺陷现场（外部压力测试报告）：DCEVM 重定义把继承槽位的值复制进子类新增的同名槽位，
 * 于是存量实例上该槽位已有非默认值（等于祖先字段值）；InitFix 的条件 CAS 以"类型零值"为期望值，
 * 判定"已被写过"并跳过，新增字段停在祖先值上，读到 {@code mid.x == 10} 而不是新初值 {@code 200}。</p>
 *
 * <p>三组夹具，全部走真实 DCEVM 重定义：</p>
 * <ol>
 *   <li><b>threeLayer</b>：外部报告的三层形态 {@code BaseG0 -> MidG1 -> LeafG2}。V2 给三层各加一个
 *       由前面的字段算出来的 {@code final} 字段，并让两张子类各新增 {@code int x} 遮蔽 {@code BaseG0.x}。
 *       关键读数 {@code lx} 来自 {@code ((BaseG0) this).x + super.x + this.x + this.l}，
 *       只有"祖先槽位保持 10、中间槽位回写 200、叶槽位回写 300"三者同时成立才会得到 {@code 540}；
 *       另含一个匿名子类实例 {@code anonInst}，验证补丁作用域覆盖匿名子类实例。</li>
 *   <li><b>t0</b>：{@code T0Base.x = 10}，子类 V2 新增 {@code public int x = 0}（以及"只声明不赋值
 *       {@code public int x;}"的变体）。若不把遮蔽字段从 T0 零值等价的豁免里摘出来，字段在补丁生成前就被
 *       判成"本来就该是 0"而丢掉，槽位留在 DCEVM 复制进来的 10 上。</li>
 *   <li><b>private</b>：{@code PrivBase { private int x = 10 }}，子类 V2 新增 {@code public int x = 200}。
 *       private 祖先必须支持（用完整权限的 lookup 读私有槽），不得判成"祖先不可读 ⇒ 跳过"。</li>
 * </ol>
 *
 * <p><b>读数一律走实例方法，不走反射字段元数据</b>：redefine 之后 {@code Class.getDeclaredFields()} 的
 * 偏移可能仍是旧布局，用它读会得到"看起来没修"的假读数；方法体读字段走的是 JVM 当前布局，才是业务
 * 可观测终局。夹具因此带只读观测方法（{@code ver()} / {@code midX()} / {@code supX()} 等），
 * 字段声明与初值保持外部报告给出的形态不变。反射只用于诊断打印，以及读<b>永不被重定义</b>的祖先类的
 * 私有槽（{@code T0Base} / {@code PrivBase}）。</p>
 *
 * <p>跑法：{@code ./gradlew :tests:shadowFieldProbe -Pp05.jbr=/opt/idea/jbr}。</p>
 */
public class ShadowFieldInitFixProbe {

	private static final String PKG = "shadowprobe";

	// ================================================================ fixture 1: three layers

	private static final String TL_BASE_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class BaseG0 {\n"
		+ "    public int x = 10;\n"
		+ "    public int ver() { return 1; }\n"
		+ "}\n";

	private static final String TL_MID_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class MidG1 extends BaseG0 {\n"
		+ "    public int m = 20;\n"
		+ "    public int ver() { return 1; }\n"
		+ "}\n";

	private static final String TL_LEAF_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class LeafG2 extends MidG1 {\n"
		+ "    public int l = 30;\n"
		+ "    public int ver() { return 1; }\n"
		+ "}\n";

	/** V1 实例持有者：{@code init()} 建三个实例（含匿名子类），V2 起不再重建。 */
	private static final String TL_BOOT_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class Boot {\n"
		+ "    public static MidG1 midInst;\n"
		+ "    public static LeafG2 leafInst;\n"
		+ "    public static LeafG2 anonInst;\n"
		+ "    public static void init() {\n"
		+ "        midInst = new MidG1();\n"
		+ "        leafInst = new LeafG2();\n"
		+ "        anonInst = new LeafG2() { };\n"
		+ "    }\n"
		+ "}\n";

	private static final String TL_BASE_V2 = ""
		+ "package " + PKG + ";\n"
		+ "public class BaseG0 {\n"
		+ "    public int x = 10;\n"
		+ "    public final int bx = this.x + 5;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int baseBx() { return this.bx; }\n"
		+ "}\n";

	private static final String TL_MID_V2 = ""
		+ "package " + PKG + ";\n"
		+ "public class MidG1 extends BaseG0 {\n"
		+ "    public int m = 20;\n"
		+ "    public int x = 200;\n"
		+ "    public final int mx = super.x + this.m + 1;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int midX() { return this.x; }\n"
		+ "    public int midMx() { return this.mx; }\n"
		+ "    public int supX() { return super.x; }\n"
		+ "}\n";

	private static final String TL_LEAF_V2 = ""
		+ "package " + PKG + ";\n"
		+ "public class LeafG2 extends MidG1 {\n"
		+ "    public int l = 30;\n"
		+ "    public int x = 300;\n"
		+ "    public final int lx = ((BaseG0) this).x + super.x + this.x + this.l;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int leafX() { return this.x; }\n"
		+ "    public int selfLx() { return this.lx; }\n"
		+ "}\n";

	private static final String TL_MID_V3 = ""
		+ "package " + PKG + ";\n"
		+ "public class MidG1 extends BaseG0 {\n"
		+ "    public int m = 20;\n"
		+ "    public int x = 200;\n"
		+ "    public final int mx = super.x + this.m + 1;\n"
		+ "    public final int v3m = this.mx + this.bx;\n"
		+ "    public int ver() { return 3; }\n"
		+ "    public int midX() { return this.x; }\n"
		+ "    public int midMx() { return this.mx; }\n"
		+ "    public int supX() { return super.x; }\n"
		+ "    public int selfV3M() { return this.v3m; }\n"
		+ "}\n";

	private static final String TL_LEAF_V3 = ""
		+ "package " + PKG + ";\n"
		+ "public class LeafG2 extends MidG1 {\n"
		+ "    public int l = 30;\n"
		+ "    public int x = 300;\n"
		+ "    public final int lx = ((BaseG0) this).x + super.x + this.x + this.l;\n"
		+ "    public final int v3l = this.lx + this.v3m;\n"
		+ "    public int ver() { return 3; }\n"
		+ "    public int leafX() { return this.x; }\n"
		+ "    public int selfLx() { return this.lx; }\n"
		+ "    public int selfV3L() { return this.v3l; }\n"
		+ "}\n";

	private static final int TL_LX = 540;
	private static final int TL_V3M = 46;
	private static final int TL_V3L = 586;

	// ================================================================ fixture 2: T0 zero-value shadow

	private static final String T0_BASE_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class T0Base {\n"
		+ "    public int x = 10;\n"
		+ "    public int baseX() { return this.x; }\n"
		+ "}\n";

	private static final String T0_ZERO_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class T0Zero extends T0Base {\n"
		+ "    public int ver() { return 1; }\n"
		+ "}\n";

	private static final String T0_BARE_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class T0Bare extends T0Base {\n"
		+ "    public int ver() { return 1; }\n"
		+ "}\n";

	/** 显式零值初值：T0 判据（"压入类型零值 + 写字段"）会把它当成"本来就已经是对的"丢掉。 */
	private static final String T0_ZERO_V2 = ""
		+ "package " + PKG + ";\n"
		+ "public class T0Zero extends T0Base {\n"
		+ "    public int x = 0;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int selfX() { return this.x; }\n"
		+ "}\n";

	/** 只声明不赋值：T0 判据里 {@code writes == 0} 直接返回 true，同样会被丢掉。 */
	private static final String T0_BARE_V2 = ""
		+ "package " + PKG + ";\n"
		+ "public class T0Bare extends T0Base {\n"
		+ "    public int x;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int selfX() { return this.x; }\n"
		+ "}\n";

	// ================================================================ fixture 3: private ancestor

	private static final String PRIV_BASE_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class PrivBase {\n"
		+ "    private int x = 10;\n"
		+ "    public int baseX() { return this.x; }\n"
		+ "}\n";

	private static final String PRIV_SUB_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class PrivSub extends PrivBase {\n"
		+ "    public int ver() { return 1; }\n"
		+ "}\n";

	private static final String PRIV_SUB_V2 = ""
		+ "package " + PKG + ";\n"
		+ "public class PrivSub extends PrivBase {\n"
		+ "    public int x = 200;\n"
		+ "    public int ver() { return 2; }\n"
		+ "    public int selfX() { return this.x; }\n"
		+ "}\n";

	// ================================================================

	private static Path classesDir;
	private static Path srcDir;

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

		threeLayer();
		t0Shadow();
		privateAncestor();

		System.out.println("PROBE-PASS: shadowed added fields carry their initializers;"
		                   + " T0 zero-value shadows are rewritten; private ancestors are read");
	}

	// ---------------------------------------------------------------- fixture 1

	private static void threeLayer() throws Exception {
		compile("BaseG0", TL_BASE_V1);
		compile("MidG1", TL_MID_V1);
		compile("LeafG2", TL_LEAF_V1);
		compile("Boot", TL_BOOT_V1);
		Class<?> boot = Class.forName(PKG + ".Boot");
		boot.getMethod("init").invoke(null);
		Object mid  = staticField(boot, "midInst");
		Object leaf = staticField(boot, "leafInst");
		Object anon = staticField(boot, "anonInst");
		check(call(mid, "ver") == 1 && call(leaf, "ver") == 1 && call(anon, "ver") == 1,
			"precondition: the three v1 instances are live");
		check(reflectInt(Class.forName(PKG + ".BaseG0"), mid, "x") == 10,
			"precondition: BaseG0.x is 10 on the mid instance");

		compile("BaseG0", TL_BASE_V2);
		compile("MidG1", TL_MID_V2);
		compile("LeafG2", TL_LEAF_V2);
		awaitRounds(mid, leaf, anon, 2, () -> call(mid, "ver") + "/" + call(leaf, "ver") + "/"
		                                          + call(anon, "ver") + "/"
		                                          + call(mid, "midX") + "/" + call(leaf, "selfLx"));
		check(call(mid, "ver") == 2 && call(leaf, "ver") == 2 && call(anon, "ver") == 2,
			"round v2 must reach all three instances");

		// 硬断言：遮蔽字段回写 + 祖先槽位不变 + 由三者算出的 final 字段。
		for (Object instance : new Object[]{mid, leaf, anon}) {
			check(call(instance, "midX") == 200,
				label(instance) + " mid.x (shadowed) must be 200, was " + call(instance, "midX"));
			check(call(instance, "supX") == 10,
				label(instance) + " BaseG0.x (ancestor slot) must stay 10, was " + call(instance, "supX"));
			check(call(instance, "baseBx") == 15,
				label(instance) + " BaseG0.bx must be 10 + 5 = 15, was " + call(instance, "baseBx"));
			check(call(instance, "midMx") == 31,
				label(instance) + " MidG1.mx must be super.x + m + 1 = 31, was " + call(instance, "midMx"));
		}
		for (Object instance : new Object[]{leaf, anon}) {
			check(call(instance, "leafX") == 300,
				label(instance) + " leaf.x (shadowed) must be 300, was " + call(instance, "leafX"));
			check(call(instance, "selfLx") == TL_LX,
				label(instance) + " leaf.lx must be ((BaseG0) this).x + super.x + this.x + l = "
					+ TL_LX + ", was " + call(instance, "selfLx"));
		}
		System.out.println("PROBE-READING three-layer v2: mid.x=" + call(mid, "midX")
		                   + " mid.bx=" + call(mid, "baseBx") + " mid.mx=" + call(mid, "midMx")
		                   + " mid.ancestorX=" + call(mid, "supX")
		                   + " | leaf.x=" + call(leaf, "leafX") + " leaf.midX=" + call(leaf, "midX")
		                   + " leaf.ancestorX=" + call(leaf, "supX")
		                   + " leaf.lx=" + call(leaf, "selfLx")
		                   + " | anon.x=" + call(anon, "leafX") + " anon.ancestorX=" + call(anon, "supX")
		                   + " anon.lx=" + call(anon, "selfLx")
		                   + " [reflection diagnostics: BaseG0.x="
		                   + reflectInt(Class.forName(PKG + ".BaseG0"), leaf, "x") + "]");

		compile("MidG1", TL_MID_V3);
		compile("LeafG2", TL_LEAF_V3);
		awaitRounds(mid, leaf, anon, 3, () -> call(mid, "ver") + "/" + call(leaf, "ver") + "/"
		                                          + call(anon, "ver") + "/" + call(mid, "selfV3M")
		                                          + "/" + call(leaf, "selfV3L"));
		check(call(mid, "selfV3M") == TL_V3M,
			"v3 non-shadowed MidG1.v3m must be mx + bx = " + TL_V3M + ", was " + call(mid, "selfV3M"));
		check(call(leaf, "selfV3L") == TL_V3L,
			"v3 non-shadowed LeafG2.v3l must be lx + v3m = " + TL_V3L + ", was " + call(leaf, "selfV3L"));
		check(call(anon, "selfV3L") == TL_V3L,
			"v3 non-shadowed LeafG2.v3l on the anonymous subclass must be " + TL_V3L
				+ ", was " + call(anon, "selfV3L"));
		check(call(mid, "midX") == 200 && call(leaf, "leafX") == 300 && call(anon, "leafX") == 300,
			"shadowed values must survive the next round, was " + call(mid, "midX") + "/"
				+ call(leaf, "leafX") + "/" + call(anon, "leafX"));
		System.out.println("PROBE-READING three-layer v3: mid.v3m=" + call(mid, "selfV3M")
		                   + " leaf.v3l=" + call(leaf, "selfV3L")
		                   + " anon.v3l=" + call(anon, "selfV3L")
		                   + " mid.x=" + call(mid, "midX") + " leaf.x=" + call(leaf, "leafX")
		                   + " leaf.lx=" + call(leaf, "selfLx"));
	}

	// ---------------------------------------------------------------- fixture 2

	/**
	 * T0 零值遮蔽：{@code Base.x = 10}，子类 V2 新增零值同名字段。
	 *
	 * <p>红读数（未豁免）：字段在补丁生成前被判成"零值等价"丢掉，槽位留在 DCEVM 复制进来的 10 上，
	 * 读到 {@code selfX() == 10}；祖先槽位仍是 10。</p>
	 */
	private static void t0Shadow() throws Exception {
		compile("T0Base", T0_BASE_V1);
		compile("T0Zero", T0_ZERO_V1);
		compile("T0Bare", T0_BARE_V1);
		Class<?> zeroClass = Class.forName(PKG + ".T0Zero");
		Class<?> bareClass = Class.forName(PKG + ".T0Bare");
		Object zero = zeroClass.getDeclaredConstructor().newInstance();
		Object bare = bareClass.getDeclaredConstructor().newInstance();
		Class<?> base = Class.forName(PKG + ".T0Base");
		check(call(zero, "ver") == 1 && call(bare, "ver") == 1, "precondition: t0 v1 instances are live");
		check(reflectInt(base, zero, "x") == 10, "precondition: T0Base.x is 10 on the zero instance");

		compile("T0Zero", T0_ZERO_V2);
		compile("T0Bare", T0_BARE_V2);
		awaitRounds(zero, bare, 2, () -> call(zero, "ver") + "/" + call(bare, "ver") + "/"
		                                + call(zero, "selfX") + "/" + call(bare, "selfX"));
		check(call(zero, "selfX") == 0,
			"T0 explicit-zero shadowed field must be written back to 0, was " + call(zero, "selfX"));
		check(call(bare, "selfX") == 0,
			"T0 declared-only shadowed field must be written back to 0, was " + call(bare, "selfX"));
		check(reflectInt(base, zero, "x") == 10 && reflectInt(base, bare, "x") == 10,
			"T0Base.x must stay 10 on both instances, was "
				+ reflectInt(base, zero, "x") + "/" + reflectInt(base, bare, "x"));
		System.out.println("PROBE-READING t0 v2: zero.x=" + call(zero, "selfX")
		                   + " zero.ancestorX=" + reflectInt(base, zero, "x")
		                   + " bare.x=" + call(bare, "selfX")
		                   + " bare.ancestorX=" + reflectInt(base, bare, "x")
		                   + " [baseX() diagnostics: " + call(zero, "baseX") + "/" + call(bare, "baseX") + "]");
	}

	// ---------------------------------------------------------------- fixture 3

	/** private 祖先：{@code PrivBase.x} 私有，子类 V2 新增 {@code public int x = 200}。 */
	private static void privateAncestor() throws Exception {
		compile("PrivBase", PRIV_BASE_V1);
		compile("PrivSub", PRIV_SUB_V1);
		Class<?> subClass = Class.forName(PKG + ".PrivSub");
		Object sub = subClass.getDeclaredConstructor().newInstance();
		Class<?> base = Class.forName(PKG + ".PrivBase");
		check(call(sub, "ver") == 1, "precondition: private-ancestor v1 instance is live");

		compile("PrivSub", PRIV_SUB_V2);
		awaitRounds(sub, 2, () -> call(sub, "ver") + "/" + call(sub, "selfX"));
		check(call(sub, "selfX") == 200,
			"a field shadowing a private ancestor field must still get its initializer 200, was "
				+ call(sub, "selfX"));
		check(reflectInt(base, sub, "x") == 10,
			"PrivBase.x (private ancestor slot) must stay 10, was " + reflectInt(base, sub, "x"));
		System.out.println("PROBE-READING private v2: sub.x=" + call(sub, "selfX")
		                   + " sub.ancestorPrivX=" + reflectInt(base, sub, "x")
		                   + " [baseX() diagnostics: " + call(sub, "baseX") + "]");
	}

	// ---------------------------------------------------------------- driver helpers

	private static void awaitRounds(Object a, Object b, Object c, int expectedVer,
	                                Supplier<String> snapshot) throws Exception {
		awaitRounds(new Object[]{a, b, c}, expectedVer, snapshot);
	}

	private static void awaitRounds(Object a, Object b, int expectedVer,
	                                Supplier<String> snapshot) throws Exception {
		awaitRounds(new Object[]{a, b}, expectedVer, snapshot);
	}

	private static void awaitRounds(Object single, int expectedVer, Supplier<String> snapshot)
		throws Exception {
		awaitRounds(new Object[]{single}, expectedVer, snapshot);
	}

	/**
	 * 等到所有实例报告期望版本号、且读数连续四次一致。
	 *
	 * <p>只等版本号不够：补丁在 redefine 之后才写值，中间会读到"新方法体 + 旧字段值"的过渡态。
	 * 连续一致用作"已落地"的判据，避免把"补丁尚未落地"误判成"补丁被跳过"。</p>
	 */
	private static void awaitRounds(Object[] instances, int expectedVer, Supplier<String> snapshot)
		throws Exception {
		long deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
			boolean allSet = true;
			for (Object instance : instances) {
				if (version(instance) != expectedVer) {
					allSet = false;
					break;
				}
			}
			if (allSet) {
				awaitStable(snapshot);
				return;
			}
		}
		StringBuilder state = new StringBuilder();
		for (Object instance : instances) state.append(label(instance)).append(".ver=")
		                                            .append(version(instance)).append(' ');
		System.out.println("PROBE-FAIL: round " + expectedVer
		                   + " did not take effect within 30s, state=" + state);
		System.exit(1);
	}

	private static void awaitStable(Supplier<String> snapshot) throws Exception {
		String last = null;
		int    same = 0;
		long   deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			String now;
			try {
				now = snapshot.get();
			} catch (Throwable t) {
				now = "<ERR " + t + ">";
			}
			if (Objects.equals(now, last)) {
				if (++same >= 4) return;
			} else {
				same = 0;
				last = now;
			}
			Thread.sleep(100);
		}
	}

	private static int version(Object instance) {
		try {
			return call(instance, "ver");
		} catch (Throwable t) {
			return -1;
		}
	}

	private static String label(Object instance) {
		return instance.getClass().getName().replace(PKG + ".", "");
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

	/**
	 * 直接读某个声明类上的字段槽位，只用于<b>永不被重定义</b>的祖先类（{@code T0Base} / {@code PrivBase}）：
	 * 这两类不参与本轮重定义，字段对象与其偏移是稳定的。
	 */
	private static int reflectInt(Class<?> declaring, Object instance, String name) {
		try {
			Field field = declaring.getDeclaredField(name);
			field.setAccessible(true);
			return field.getInt(instance);
		} catch (ReflectiveOperationException t) {
			throw new IllegalStateException("cannot read " + declaring.getName() + "." + name, t);
		}
	}

	private static Object staticField(Class<?> holder, String name) throws Exception {
		return holder.getField(name).get(null);
	}

	private static void compile(String simpleName, String source) throws IOException {
		Path pkgDir = srcDir.resolve(PKG);
		Files.createDirectories(pkgDir);
		Path srcFile = pkgDir.resolve(simpleName + ".java");
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
