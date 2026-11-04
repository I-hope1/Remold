import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 复现探针：连续三轮热更（改初值 → 改字段类型 → 新增字段 + 改含 lambda 的方法体），
 * 对齐真机报告的操作序列。
 *
 * <p>真机现象（Minecraft + Fabric + Mixin）：第三轮新增字段后，日志里 redefine 成功，
 * 但既没有 InitFix 补丁日志、也没有 {@code @OnReload} 回调，新字段停在 0。</p>
 *
 * <p>本探针刻意让 {@code describe()} 里带一个 lambda：真机那个 {@code use()} 里就有
 * {@code style -> ...}，而 lambda 对齐（{@code Lambda Align}）默认开启——前三轮若不动它，
 * 就不会走到对齐路径，也就复现不到问题。</p>
 *
 * <p><b>回归的是"预测副本顶掉活类"那个缺陷</b>：{@code LayoutPredictor} 只在
 * {@code offsetIndex} 非空时才工作，而 {@code OffsetScanner} 只登记真实存在的绑定。
 * 因此本类静态初始化里真的调一次 {@code objectFieldOffset}（见 {@link #probeAnchorOffset()}）——
 * 少了这一条，探针在缺陷版本上也会全绿（第一版探针就是这样漏掉该 bug 的）。</p>
 *
 * <p>跑法：{@code ./gradlew :tests:addFieldProbe -Pp05.jbr=<DCEVM JDK>}。</p>
 */
public class AddFieldReloadProbe {

	private static final String CLASS_NAME = "probe.probe_Case";

	/** 供 {@code OffsetScanner} 登记绑定用的锚点字段（类型在两版之间保持不变）。 */
	private int probeAnchor;

	/**
	 * 在索引里造一条真实的 offset 绑定。
	 *
	 * <p>{@code RemoldAgent.checkSuperclassDriftIfApplicable} 在 {@code offsetIndex} 为空时直接返回，
	 * 于是 {@code LayoutPredictor} 不会预测布局、也不会创建抛弃式加载器副本——那条缺陷路径就
	 * 根本不会被执行。探针必须先把这条前置条件造出来，否则是假绿。</p>
	 */
	private static final long PROBE_ANCHOR_OFFSET = probeAnchorOffset();

	private static long probeAnchorOffset() {
		try {
			return remold.Reflect.UNSAFE.objectFieldOffset(
				AddFieldReloadProbe.class.getDeclaredField("probeAnchor"));
		} catch (Throwable t) {
			System.out.println("PROBE-WARN: cannot create an offset binding (case may not reproduce): " + t);
			return -1L;
		}
	}

	private static Path classesDir;
	private static Path srcDir;
	private static Class<?> caseClass;
	private static Object instance;

	public static void main(String[] args) throws Exception {
		String dirProp = System.getProperty("remold.addfield.dir");
		if (dirProp == null) {
			System.out.println("PROBE-ERROR: -Dremold.addfield.dir is required");
			System.exit(2);
		}
		classesDir = Paths.get(dirProp).toAbsolutePath().normalize();
		srcDir = classesDir.getParent().resolve("src");
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);

		boot(v1());
		System.out.println("PROBE-ROUND0 " + describe());

		swap(v2(), 1);
		System.out.println("PROBE-ROUND1 " + describe());

		swap(v3(), 2);
		System.out.println("PROBE-ROUND2 " + describe());

		swap(v4(), 3);
		System.out.println("PROBE-ROUND3 " + describe());

		String describe = describe();
		check(describe.startsWith("v4:L4:"), "round3: new lambda body must be in effect, was " + describe);
		check(describe.endsWith(":10"), "round3: added field must be patched to 10 by InitFix, was " + describe);
		check(onReloadFired(), "round3: @OnReload must fire after the successful redefine");
		System.out.println("PROBE-PASS: all three rounds applied");
	}

	// ---------------------------------------------------------------- driver

	private static void boot(String source) throws Exception {
		compile(source);
		caseClass = Class.forName(CLASS_NAME);
		instance = caseClass.getDeclaredConstructor().newInstance();
	}

	/** 覆盖源码并等待该轮的可观测状态推进到位（redefine 与后处理在同一线程先后执行，必须等补丁完成）。 */
	private static void swap(String source, int roundNo) throws Exception {
		compile(source);
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
			if (expectedStateReached(roundNo)) return;
		}
		System.out.println("PROBE-FAIL: round " + roundNo + " did not become observable within 20s, describe="
		                   + describe());
		System.exit(1);
	}

	private static boolean expectedStateReached(int roundNo) {
		try {
			String d = describe();
			if (roundNo == 1) return d.startsWith("v2:");
			if (roundNo == 2) return d.startsWith("v3:L1:MAX");
			return d.startsWith("v4:L4:") && d.endsWith(":10") && onReloadFired();
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean onReloadFired() {
		try {
			Class<?> marker = Class.forName("probe.probe_Marker");
			return ((Integer) marker.getField("hits").get(null)) > 0;
		} catch (Throwable t) {
			return false;
		}
	}

	private static String describe() {
		try {
			return (String) caseClass.getMethod("describe").invoke(instance);
		} catch (Throwable t) {
			return "<ERR " + t.getClass().getSimpleName() + ": " + t.getMessage() + ">";
		}
	}

	// ---------------------------------------------------------------- fixtures

	private static String v1() {
		return ""
			+ "package probe;\n"
			+ "public class probe_Case {\n"
			+ "    public String message = \"hello\";\n"
			+ "    public int power = 1;\n"
			+ "    public String describe() {\n"
			+ "        java.util.function.Supplier<String> s = () -> \"L1\";\n"
			+ "        return \"v1:\" + s.get() + \":\" + power;\n"
			+ "    }\n"
			+ "}\n";
	}

	private static String v2() {
		return ""
			+ "package probe;\n"
			+ "import remold.annotation.RemoldReinit;\n"
			+ "public class probe_Case {\n"
			+ "    @RemoldReinit public String message = \"hello-2\";\n"
			+ "    public int power = 1;\n"
			+ "    public String describe() {\n"
			+ "        java.util.function.Supplier<String> s = () -> \"L1\";\n"
			+ "        return \"v2:\" + s.get() + \":\" + power;\n"
			+ "    }\n"
			+ "}\n";
	}

	private static String v3() {
		return ""
			+ "package probe;\n"
			+ "import remold.annotation.RemoldReinit;\n"
			+ "public class probe_Case {\n"
			+ "    @RemoldReinit public String message = \"hello-2\";\n"
			+ "    @RemoldReinit public String power = \"MAX\";\n"
			+ "    public String describe() {\n"
			+ "        java.util.function.Supplier<String> s = () -> \"L1\";\n"
			+ "        return \"v3:\" + s.get() + \":\" + power;\n"
			+ "    }\n"
			+ "}\n";
	}

	private static String v4() {
		return ""
			+ "package probe;\n"
			+ "import remold.annotation.OnReload;\n"
			+ "import remold.annotation.RemoldReinit;\n"
			+ "public class probe_Case {\n"
			+ "    @RemoldReinit public String message = \"hello-2\";\n"
			+ "    @RemoldReinit public String power = \"MAX\";\n"
			+ "    public int added = 10;\n"
			+ "    public boolean reloaded = false;\n"
			+ "    public String describe() {\n"
			+ "        java.util.function.Supplier<String> s = () -> \"L4\";\n"
			+ "        return \"v4:\" + s.get() + \":\" + power + \":\" + added;\n"
			+ "    }\n"
			+ "    @OnReload public void onReload() { this.reloaded = true; probe.probe_Marker.hit(); }\n"
			+ "}\n";
	}

	// ---------------------------------------------------------------- utils

	private static void compile(String source) throws IOException {
		Path marker = srcDir.resolve("probe_Marker.java");
		if (!Files.exists(marker)) {
			String markerSrc = "package probe;" + (char) 10
				+ "public class probe_Marker { public static int hits; public static void hit() { hits++; } }" + (char) 10;
			Files.write(marker, markerSrc.getBytes(StandardCharsets.UTF_8));
			javax.tools.JavaCompiler javac0 = javax.tools.ToolProvider.getSystemJavaCompiler();
			if (javac0.run(null, System.out, System.err, "-proc:none", "-d", classesDir.toString(), marker.toString()) != 0) {
				throw new IllegalStateException("marker javac failed");
			}
		}
		Path srcFile = srcDir.resolve("probe_Case.java");
		Files.write(srcFile, source.getBytes(StandardCharsets.UTF_8));
		javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
		int rc = javac.run(null, System.out, System.err,
			"-proc:none", "-d", classesDir.toString(), srcFile.toString());
		if (rc != 0) throw new IllegalStateException("javac failed with rc=" + rc);
	}

	private static void check(boolean cond, String msg) {
		if (!cond) {
			System.out.println("PROBE-FAIL: " + msg);
			System.exit(1);
		}
	}
}
