import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 轻量验证：<b>普通（javac）匿名类的非合成字段</b>在三种形态下热更时的真实行为，用来验两道边界。
 *
 * <p><b>要回答的两个问题</b>（主指挥官裁定口径）：</p>
 * <ol>
 *   <li><b>护城河</b>：{@code remold.agent.layout_gate=reject} 时，匿名类非合成字段的
 *       <i>新增 / 改类型 / 缩容</i> 三种形态是否都被拦死（或按既有契约合法放行），有没有漏网；</li>
 *   <li><b>连坐边界</b>：{@code layout_gate=warn}（照旧原地重定义、只告警）时，除了被改的字段，
 *       夹具里<b>没被修改的旁观字段</b>是否纹丝不动 —— 用来确认 JBR 21 的实例重排不会跨槽位污染。</li>
 * </ol>
 *
 * <p><b>为什么读数走实例方法而不走反射字段元数据</b>：redefine 之后
 * {@code Class.getDeclaredFields()} 里的偏移可能仍是旧布局，用它读数会得到"看起来没变"的假读数；
 * 方法体走 JVM 当前布局，才是业务可观测终局（与 {@code ShadowFieldInitFixProbe} 同约定）。
 * 反射只用于诊断打印字段表形状。</p>
 *
 * <p><b>夹具形态</b>：{@code drillprobe.AnonHost.make()} 返回一个普通匿名类实例
 * （{@code AnonHost$1}），它声明三个非合成字段：</p>
 * <ul>
 *   <li>{@code payload}：被试字段（新增/改类型/缩容都作用在它或它的邻居上）；</li>
 *   <li>{@code bystander}：<b>旁观对照字段</b>，三个版本里签名与值都不变 —— 它才是"连坐边界"的读数；</li>
 *   <li>{@code dropped}：缩容形态的牺牲品（v2drop 里被删掉）。</li>
 * </ul>
 *
 * <p><b>判定口径</b>（对齐 {@code docs/topology/07-layout-gate-and-risks.md} §1.1 的既有契约）：</p>
 * <ul>
 *   <li>新增字段 → 放行，新字段由 InitFix 补值，既有字段不动；</li>
 *   <li>改类型（{@code Object → String}，有存活实例）→ {@code LAYOUT-REJECT}，整类不重定义；</li>
 *   <li>删除字段 → 按"批次外读者为空"判据，本夹具的读者只在类内（批次内）⇒ {@code WAIVE} 放行，
 *       且既有字段值必须保留（JBR 删字段不压缩布局）。</li>
 * </ul>
 *
 * <p>跑法：{@code ./gradlew :tests:anonFieldShapeProbe -Pp05.jbr=/opt/idea/jbr -Pdrill.shape=retype -Pdrill.gate=warn}。</p>
 */
public class AnonFieldShapeProbe {

	private static final String PKG = "drillprobe";

	private static Path srcDir;
	private static Path classesDir;

	private static final String HOST_V1 = ""
		+ "package " + PKG + ";\n"
		+ "public class AnonHost {\n"
		+ "    public static Object make() {\n"
		+ "        return new Object() {\n"
		+ "            public Object payload = \"P-v1\";\n"
		+ "            public Object bystander = \"B-v1\";\n"
		+ "            public Object dropped = \"D-v1\";\n"
		+ "            public int ver() { return 1; }\n"
		+ "            public String read() { return payload + \"|\" + bystander + \"|\" + dropped; }\n"
		+ "        };\n"
		+ "    }\n"
		+ "}\n";

	/** 新增形态：只多一个字段，三个既有字段的值与签名一模一样。 */
	private static final String HOST_ADD = ""
		+ "package " + PKG + ";\n"
		+ "public class AnonHost {\n"
		+ "    public static Object make() {\n"
		+ "        return new Object() {\n"
		+ "            public Object payload = \"P-v1\";\n"
		+ "            public Object bystander = \"B-v1\";\n"
		+ "            public Object dropped = \"D-v1\";\n"
		+ "            public Object extra = \"E-v2\";\n"
		+ "            public int ver() { return 2; }\n"
		+ "            public String read() { return payload + \"|\" + bystander + \"|\" + dropped + \"|\" + extra; }\n"
		+ "        };\n"
		+ "    }\n"
		+ "}\n";

	/** 改类型形态：{@code payload} 由 Object 变 String（JBR 会丢弃旧值），其余字段原样。 */
	private static final String HOST_RETYPE = ""
		+ "package " + PKG + ";\n"
		+ "public class AnonHost {\n"
		+ "    public static Object make() {\n"
		+ "        return new Object() {\n"
		+ "            public String payload = \"P-v2\";\n"
		+ "            public Object bystander = \"B-v1\";\n"
		+ "            public Object dropped = \"D-v1\";\n"
		+ "            public int ver() { return 2; }\n"
		+ "            public String read() { return payload + \"|\" + bystander + \"|\" + dropped; }\n"
		+ "        };\n"
		+ "    }\n"
		+ "}\n";

	/** 缩容形态：删掉 {@code dropped}（读者只在类内 ⇒ 批次外读者为空）。 */
	private static final String HOST_DROP = ""
		+ "package " + PKG + ";\n"
		+ "public class AnonHost {\n"
		+ "    public static Object make() {\n"
		+ "        return new Object() {\n"
		+ "            public Object payload = \"P-v1\";\n"
		+ "            public Object bystander = \"B-v1\";\n"
		+ "            public int ver() { return 2; }\n"
		+ "            public String read() { return payload + \"|\" + bystander; }\n"
		+ "        };\n"
		+ "    }\n"
		+ "}\n";

	private static final String CTRL_V1 = ""
		+ "package " + PKG + ";\n"
		+ "/** 旁观对照类：三个版本逐字节相同，用来确认未改动的类完全不动。 */\n"
		+ "public class DrillCtl {\n"
		+ "    public static Object make() {\n"
		+ "        return new Object() {\n"
		+ "            public Object keep = \"K-v1\";\n"
		+ "            public int ver() { return 1; }\n"
		+ "            public String read() { return String.valueOf(keep); }\n"
		+ "        };\n"
		+ "    }\n"
		+ "}\n";

	public static void main(String[] args) throws Exception {
		String shape = System.getProperty("drill.shape", "retype").trim().toLowerCase(Locale.ROOT);
		String gate = System.getProperty("drill.gate", "reject").trim().toLowerCase(Locale.ROOT);
		String dirProp = System.getProperty("drill.dir");
		if (dirProp == null) {
			System.out.println("DRILL-HARNESS-ERROR: -Ddrill.dir is required");
			System.exit(2);
		}
		Path root = Path.of(dirProp).toAbsolutePath();
		classesDir = root.resolve("classes");
		srcDir = root.resolve("src");
		Files.createDirectories(classesDir);
		Files.createDirectories(srcDir);
		System.out.println("DRILL-CONFIG shape=" + shape + " gate=" + gate + " dir=" + root
			+ " layoutGate=" + System.getProperty("remold.agent.layout_gate", "<unset>")
			+ " heapwatch=" + System.getProperty("remold.probe.heapwatch", "<unset>")
			+ " watchField=" + System.getProperty("remold.probe.heapwatch.field", "<unset>")
			+ " watchClass=" + System.getProperty("remold.probe.heapwatch.class", "<unset>"));

		compile("AnonHost", HOST_V1);
		compile("DrillCtl", CTRL_V1);

		ClassLoader loader = AnonFieldShapeProbe.class.getClassLoader();
		Class<?> host = Class.forName(PKG + ".AnonHost", true, loader);
		Class<?> ctl  = Class.forName(PKG + ".DrillCtl", true, loader);
		// 两个存活实例：门槛判据要看"有存活实例"，一个实例也能测出丢值，两个便于看出是"全实例"还是"单例"。
		Object anon1 = host.getMethod("make").invoke(null);
		Object anon2 = host.getMethod("make").invoke(null);
		Object ctl1  = ctl.getMethod("make").invoke(null);
		System.out.println("DRILL-PRE before ver=" + ver(anon1) + "/" + ver(anon2)
			+ " read1=" + read(anon1) + " read2=" + read(anon2)
			+ " ctl=" + read(ctl1) + " fields=" + fieldTable(anon1.getClass()));

		compile("AnonHost", switch (shape) {
			case "add" -> HOST_ADD;
			case "retype" -> HOST_RETYPE;
			case "drop" -> HOST_DROP;
			default -> throw new IllegalArgumentException("unknown drill.shape=" + shape);
		});

		int expectedVer = 2;
		boolean changed = awaitVer(anon1, expectedVer, 15_000);
		String read1 = read(anon1);
		String read2 = read(anon2);
		String ctlRead = read(ctl1);
		int anonVer = ver(anon1);
		System.out.println("DRILL-READING shape=" + shape + " gate=" + gate
			+ " applied=" + changed + " ver=" + anonVer
			+ " read1=" + read1 + " read2=" + read2
			+ " bystander1=" + field(read1, 1) + " bystander2=" + field(read2, 1)
			+ " ctl=" + ctlRead + " fields=" + fieldTable(anon1.getClass()));

		List<String> violations = verdict(shape, gate, changed, anonVer, read1, read2, ctlRead);
		for (String v : violations) System.out.println("DRILL-VIOLATION " + v);
		System.out.println("DRILL-SUMMARY shape=" + shape + " gate=" + gate
			+ " result=" + (violations.isEmpty() ? "PASS" : "FAIL")
			+ " violations=" + violations.size());
		// 观测量与契约不符 = 护城河漏网/连坐污染，属于要报账的结论，不能让 harness 静默吞掉。
		System.exit(violations.isEmpty() ? 0 : 1);
	}

	/**
	 * 判定：按 {@code docs/topology/07-layout-gate-and-risks.md} §1.1 的既有契约核对读数。
	 *
	 * <p>关键点：判据是<b>被哪个门拦住</b>与<b>旁观字段是否纹丝不动</b>，不是"是否报错"。</p>
	 */
	private static List<String> verdict(String shape, String gate, boolean applied, int ver,
	                                    String read1, String read2, String ctlRead) {
		List<String> out = new ArrayList<>();
		String b1 = field(read1, 1);
		String b2 = field(read2, 1);
		if (!Objects.equals(b1, "B-v1") || !Objects.equals(b2, "B-v1")) {
			// 旁观字段被改 = 跨槽位污染：无论哪个档位、哪个形态都是硬失败。
			out.add("bystander-polluted read1=" + read1 + " read2=" + read2);
		}
		if (!"K-v1".equals(ctlRead)) {
			out.add("control-class-drifted read=" + ctlRead);
		}
		switch (shape) {
			case "add" -> {
				// 新增字段合法：reject 档也应放行（新字段交给 InitFix），既有字段不动。
				if (ver != 2) out.add("add-field-was-not-applied ver=" + ver);
				if (!read1.startsWith("P-v1|")) out.add("add-field-clobbered-existing read1=" + read1);
			}
			case "retype" -> {
				// 实测契约（docs/runtime/06-anon-field-shape-drill.md）：改类型在**两档都被拒**——
				// 拦截来自匿名类侧两道门（`ANON-LAYOUT` 配对门 + 无档位的后置换名布局校验），
				// 全局 `layout_gate` 只是第三道。因此这里断言"拒绝且值不变"；
				// 若将来把后置校验改成可放宽，这一格会变红，逼迫重新取读数。
				if (ver != 1) out.add("retype-not-blocked ver=" + ver + " read=" + read1);
				if (!"P-v1|B-v1|D-v1".equals(read1)) out.add("retype-changed-values read=" + read1);
			}
			case "drop" -> {
				// 契约：批次外读者为空 ⇒ WAIVE 放行；既有字段值必须保留（JBR 删字段不压缩布局）。
				if (ver != 2) out.add("drop-not-applied ver=" + ver);
				if (!read1.startsWith("P-v1|")) out.add("drop-clobbered-payload read1=" + read1);
			}
			default -> out.add("unknown-shape " + shape);
		}
		return out;
	}

	private static boolean awaitVer(Object instance, int expected, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (ver(instance) == expected) return true;
			Thread.sleep(100);
		}
		return false;
	}

	private static int ver(Object instance) {
		try {
			var m = instance.getClass().getMethod("ver");
			m.setAccessible(true);   // 匿名类是包级私有的：跨包反射调用需要放开访问检查
			return (Integer) m.invoke(instance);
		} catch (ReflectiveOperationException t) {
			throw new IllegalStateException("cannot invoke ver() on " + instance.getClass().getName(), t);
		}
	}

	private static String read(Object instance) {
		try {
			var m = instance.getClass().getMethod("read");
			m.setAccessible(true);
			return (String) m.invoke(instance);
		} catch (ReflectiveOperationException t) {
			return "<ERR " + t + ">";
		}
	}

	/** 按 {@code |} 取第 n 段（0 基）；缺失返回 {@code "<absent>"}。 */
	private static String field(String read, int index) {
		if (read == null) return "<absent>";
		String[] parts = read.split("\\|", -1);
		return index < parts.length ? parts[index] : "<absent>";
	}

	/** 诊断打印字段表形状（只作旁证：redefine 后元数据偏移可能滞后，判据仍看方法体读数）。 */
	private static String fieldTable(Class<?> type) {
		var out = new StringBuilder("[");
		for (var f : type.getDeclaredFields()) {
			if (out.length() > 1) out.append(",");
			out.append(f.getName()).append(':').append(f.getType().getSimpleName());
		}
		return out.append(']').toString();
	}

	private static void compile(String simpleName, String source) throws IOException {
		Path pkgDir = srcDir.resolve(PKG);
		Files.createDirectories(pkgDir);
		Path srcFile = pkgDir.resolve(simpleName + ".java");
		Files.write(srcFile, source.getBytes(StandardCharsets.UTF_8));
		javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
		int rc = javac.run(null, System.out, System.err,
			"-proc:none", "-g", "-d", classesDir.toString(), srcFile.toString());
		if (rc != 0) throw new IllegalStateException("javac failed for " + simpleName + " rc=" + rc);
	}
}
