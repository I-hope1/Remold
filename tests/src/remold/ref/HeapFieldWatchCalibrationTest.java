package remold.ref;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import remold.RemoldAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code HeapFieldWatch} 的已知答案校准（不变量 C1）。
 *
 * <p>校准问题：这个哨兵是"字段在 redefine 窗口里被置 null"这类结论的唯一观测手段。它自己没被校准过时，
 * {@code nullCount=0} 既可能是"真的干净"，也可能是"根本没看到字段"。本用例用**已知答案**把它钉死：</p>
 * <ol>
 *   <li>12 个实例里人为置 5 个 null → 同一份哨兵、同一条 {@code LibTool.getInstances} 通道，在**同一个
 *       {@code redefineClasses} 前后**都必须恰好报 5；业务终局由实例自己的 {@code probeFieldIsNull()} 复核；</li>
 *   <li>同名同类型字段的对照类一个 null 都没有 → 必须报 0（换无关输入的对照）；</li>
 *   <li>跨切面分母差必须被分成 created / collected，且"非空 → null"只由 {@code newlyNull} 报告；</li>
 *   <li>字段名写错时必须报 {@code absent=N} 并告警，不得退化成 {@code nullCount=0} 的假阴性。</li>
 * </ol>
 *
 * <p>子 JVM（{@link HeapWatchCalibrationFixture}）用与真机相同的 {@code -Dremold.probe.heapwatch.*} 开关和
 * {@code -javaagent} 启动：测的是要上真机的那个仪器，不是测试里的复刻品。</p>
 */
class HeapFieldWatchCalibrationTest {

	private static final String TARGET_CLASS = HeapWatchCalibrationFixture.Target.class.getName();
	private static final String CONTROL_CLASS = HeapWatchCalibrationFixture.Control.class.getName();

	@Test
	void reportsExactlyKNullsAcrossRedefine() throws Exception {
		Child child = run("calibration", TARGET_CLASS, "probeField");
		child.assertExitZero();

		// 前置基线：置 null 的动作确实生效，且 12 个实例都还活着（否则"报 k"没有分母可谈）。
		assertEquals("5", child.result("truth", "nulls", "before-redefine"),
			"precondition: the fixture must really have 5 of 12 instances null before the scan");
		assertEquals("12", child.result("truth", "live", "before-redefine"),
			"precondition: all 12 calibrated instances must be reachable at the first phase");
		assertEquals("v1", child.resultRaw("version", "value", "before-redefine"),
			"precondition: redefine has not run yet at the before-redefine phase");

		// 已知答案：before-redefine 必须报 total=12 nullCount=5。
		assertEquals("12", child.snapshot("total", "before-redefine"), "denominator before redefine");
		assertEquals("5", child.snapshot("nullCount", "before-redefine"),
			"the instrument must report exactly the 5 nulls that were injected (known answer)");
		assertEquals("0", child.snapshot("absent", "before-redefine"), "field is present on every instance");
		assertEquals("0", child.snapshot("unreadable", "before-redefine"), "field must be readable on every instance");

		// 因果链：redefine 真的发生了（方法体常量 v1 -> v2），否则"跨 redefine"这条就没有边界。
		assertEquals("ok", child.resultRaw("redefine", "value", null), "a real redefineClasses must have run");
		assertEquals("v2", child.resultRaw("version", "value", "after-redefine"),
			"the redefine must be visible through the instance's own method after the phase");

		// 同一个 redefine 之后：分母 11（回收 3、新建 2），null 4（被回收的 3 个里有 1 个原本是 null）。
		assertEquals("11", child.snapshot("total", "after-redefine"), "denominator after churn");
		assertEquals("4", child.snapshot("nullCount", "after-redefine"),
			"after the redefine the instrument must still report only the surviving nulls (known answer)");
		assertEquals("4", child.result("truth", "nulls", "after-redefine"),
			"business truth must agree with the instrument (terminal read through the instance's own method)");

		// 余数分组：244/230 这类分母差必须被拆成 created/collected，不得被读成"字段被置空"。
		String diff = child.diff("after-redefine");
		assertEquals("9", diffField(diff, "same"), "9 instances survive both phases: " + diff);
		assertEquals("2", diffField(diff, "created"), "2 instances were created between phases: " + diff);
		assertEquals("3", diffField(diff, "collected"), "3 instances were collected between phases: " + diff);
		assertEquals("4", diffField(diff, "persisted"), "the 4 surviving nulls persist: " + diff);
		assertEquals("1", diffField(diff, "vanished"),
			"one null left the heap with its instance (benign, not a field wipe): " + diff);
		assertEquals("0", diffField(diff, "newlyNull"),
			"nothing went non-null -> null across the redefine: " + diff);

		// 反面对照：人为把存活实例置空后，分组必须报 newlyNull=1 —— 否则上面的 newlyNull=0 只是空转。
		assertEquals("5", child.snapshot("nullCount", "after-field-patch"),
			"the deliberately wiped instance must be counted at the last phase");
		assertEquals("1", diffField(child.diff("after-field-patch"), "newlyNull"),
			"the grouping must flag exactly the instance that went non-null -> null: "
				+ child.diff("after-field-patch"));
		assertEquals("0", diffField(child.diff("after-field-patch"), "vanished"),
			"no instance left the heap between the last two phases: " + child.diff("after-field-patch"));
	}

	@Test
	void controlClassWithSameFieldReportsZero() throws Exception {
		// 换无关输入的对照：同一个字段名、同一条通道、同样的可空引用字段，只是这个类一个 null 都没有。
		Child child = run("control", CONTROL_CLASS, "probeField");
		child.assertExitZero();
		assertEquals("9", child.snapshot("total", "before-redefine"), "9 control instances must be enumerated");
		assertEquals("0", child.snapshot("nullCount", "before-redefine"),
			"the control class must report 0 nulls — a non-zero count here would mean the instrument counts"
				+ " something other than the configured class/field");
		assertEquals("0", child.snapshot("absent", "before-redefine"), "control field is present");
	}

	@Test
	void missingFieldIsReportedLoudlyInsteadOfZero() throws Exception {
		// 假阴性防线：字段名写错时，"看不到"绝不能伪装成"干净"。
		Child child = run("absent", TARGET_CLASS, "noSuchProbeField");
		child.assertExitZero();
		assertEquals("12", child.snapshot("total", "before-redefine"), "12 instances must still be enumerated");
		assertEquals("0", child.snapshot("nullCount", "before-redefine"),
			"an absent field must not be reported as a null hit");
		assertEquals("12", child.snapshot("absent", "before-redefine"),
			"an absent field must be reported as absent=N for every instance, not swallowed");
		assertTrue(child.outputContains("[HEAPWATCH] field cannot be observed on 12 instance(s)"),
			"the instrument must warn that nullCount is not evidence when the field is unobservable;"
				+ " output was:\n" + child.output);
	}

	@Test
	void instrumentLogLinesAreActuallyEmitted() throws Exception {
		// 人读日志是运维唯一的观测面：结构化读数为真但日志没打，真机上依旧等于没校准。
		Child child = run("calibration", TARGET_CLASS, "probeField");
		child.assertExitZero();
		assertTrue(child.outputContains("[HEAPWATCH] phase=before-redefine"), "the phase log line must be emitted");
		assertTrue(child.outputContains("total=12 nullCount=5"), "the phase log line must carry the known answer");
		assertTrue(child.outputContains("[HEAPWATCH-DIFF] before=before-redefine after=after-redefine"),
			"the cross-phase grouping line must be emitted");
	}

	// ------------------------------------------------------------------------------------------------
	// 子 JVM 驱动
	// ------------------------------------------------------------------------------------------------

	private static Child run(String scenario, String className, String fieldName) throws Exception {
		File java = javaExecutable();
		File agentJar = agentJar();
		assertNotNull(agentJar, "the agent jar must be built before this test runs (:tests:test depends on"
			+ " :core:shadowJar); without it the instrument cannot be calibrated at all");
		List<String> command = new ArrayList<String>();
		command.add(java.getAbsolutePath());
		command.add("-javaagent:" + agentJar.getAbsolutePath());
		command.add("-Dremold.probe.heapwatch=true");
		command.add("-Dremold.probe.heapwatch.class=" + className);
		command.add("-Dremold.probe.heapwatch.field=" + fieldName);
		String nativeLib = System.getProperty("remold.path.libtool");
		if (nativeLib != null) {
			command.add("-Dremold.path.libtool=" + nativeLib);
		}
		if (majorJavaVersion() >= 9) {
			command.add("--add-exports");
			command.add("java.base/jdk.internal.misc=ALL-UNNAMED");
		}
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(HeapWatchCalibrationFixture.class.getName());
		command.add(scenario);

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		StringBuilder output = new StringBuilder();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
		String line;
		while ((line = reader.readLine()) != null) {
			output.append(line).append('\n');
		}
		if (!process.waitFor(180, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			fail("calibration child JVM did not finish within 180s; output was:\n" + output);
		}
		dumpChildOutput(scenario, output.toString());
		return new Child(scenario, process.exitValue(), output.toString());
	}

	/**
	 * 可选：把子 JVM 的完整输出落盘（{@code -Dremold.heapwatch.dumpDir=<dir>} 或环境变量
	 * {@code REMOLD_HEAPWATCH_DUMP_DIR}；Gradle 的 test 任务不透传 {@code -D}，但会透传环境变量），
	 * 便于人工核对真机上要看的 {@code [HEAPWATCH]} 日志。默认不落盘。
	 */
	private static void dumpChildOutput(String scenario, String output) {
		String dir = System.getProperty("remold.heapwatch.dumpDir");
		if (dir == null || dir.trim().isEmpty()) {
			dir = System.getenv("REMOLD_HEAPWATCH_DUMP_DIR");
		}
		if (dir == null || dir.trim().isEmpty()) {
			return;
		}
		try {
			File target = new File(dir, "heapwatch-calibration-" + scenario + ".log");
			File parent = target.getParentFile();
			if (parent != null && !parent.isDirectory()) {
				parent.mkdirs();
			}
			java.io.Writer writer = new java.io.OutputStreamWriter(
				new java.io.FileOutputStream(target), "UTF-8");
			try {
				writer.write(output);
			} finally {
				writer.close();
			}
		} catch (Exception e) {
			// 诊断落盘失败不得影响校准结论。
			System.out.println("[heapwatch-calibration] cannot dump child output: " + e);
		}
	}

	private static File javaExecutable() {
		String home = System.getProperty("java.home");
		String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
		return new File(new File(home, "bin"), exe);
	}

	private static int majorJavaVersion() {
		String spec = System.getProperty("java.specification.version", "8");
		if (spec.startsWith("1.")) {
			return Integer.parseInt(spec.substring(2));
		}
		int dot = spec.indexOf('.');
		return Integer.parseInt(dot < 0 ? spec : spec.substring(0, dot));
	}

	/**
	 * 定位 {@code -javaagent} 用的 jar：{@code :tests:test} 的 classpath 上是 core 的 raw jar，
	 * 同目录下就是 shadow 出来的 {@code remold-agent.jar}。
	 */
	private static File agentJar() {
		String override = System.getProperty("remold.heapwatch.agentJar");
		if (override != null && !override.trim().isEmpty()) {
			File file = new File(override);
			return file.isFile() ? file : null;
		}
		File sibling = new File(codeSourceDirOfAgentClasses(), "remold-agent.jar");
		if (sibling.isFile()) {
			return sibling;
		}
		File fromWorkingDir = new File("..", "core/build/libs/remold-agent.jar").getAbsoluteFile();
		return fromWorkingDir.isFile() ? fromWorkingDir : null;
	}

	private static File codeSourceDirOfAgentClasses() {
		try {
			File here = new File(RemoldAgent.class.getProtectionDomain().getCodeSource()
				.getLocation().toURI());
			return here.isDirectory() ? here : here.getParentFile();
		} catch (Exception e) {
			throw new IllegalStateException("cannot locate the remold agent classes/jar: " + e, e);
		}
	}

	/** 一次子 JVM 运行的输出与退出码。 */
	private static final class Child {
		final String scenario;
		final int exitCode;
		final String output;

		Child(String scenario, int exitCode, String output) {
			this.scenario = scenario;
			this.exitCode = exitCode;
			this.output = output;
		}

		void assertExitZero() {
			if (exitCode != 0) {
				fail("calibration child JVM (" + scenario + ") exited " + exitCode + "; output was:\n" + output);
			}
		}

		boolean outputContains(String needle) {
			return output.contains(needle);
		}

		/** {@code RESULT snapshot phase=<phase> <field>=<value> ...} 中的字段值。 */
		String snapshot(String field, String phase) {
			return result("snapshot", field, phase);
		}

		/** {@code DIFF phase=<phase> <grouping line>} 里的分组行。 */
		String diff(String phase) {
			String prefix = "DIFF phase=" + phase + " ";
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (trimmed.startsWith(prefix)) {
					return trimmed.substring(prefix.length());
				}
			}
			fail("no cross-phase grouping line was produced for phase=" + phase + "; output was:\n" + output);
			return null;
		}

		/** 取 {@code RESULT <kind> ... <field>=<value>} 的原始字符串。 */
		String result(String kind, String field, String phase) {
			String value = resultRaw(kind, field, phase);
			if (value == null) {
				fail("missing RESULT " + kind + " " + field + "=" + field + " for phase=" + phase
					+ "; output was:\n" + output);
			}
			return value;
		}

		/**
		 * 解析 {@code RESULT <kind> [phase=<phase>] key=value ...}：返回 {@code field} 的原始值。
		 *
		 * @param phase 为 null 时不按 phase 过滤
		 */
		String resultRaw(String kind, String field, String phase) {
			String prefix = "RESULT " + kind;
			for (String line : output.split("\n")) {
				String trimmed = line.trim();
				if (!trimmed.startsWith(prefix)) {
					continue;
				}
				Map<String, String> fields = parse(trimmed.substring(prefix.length()).trim());
				if (phase != null && !phase.equals(fields.get("phase"))) {
					continue;
				}
				if (fields.containsKey(field)) {
					return fields.get(field);
				}
			}
			return null;
		}
	}

	/** 把 {@code a=1 b=2 c{x=1 ...}} 解析成扁平表（括号内的 key 用右括号前缀区分，见 {@link #diffField}）。 */
	private static Map<String, String> parse(String text) {
		Map<String, String> fields = new LinkedHashMap<String, String>();
		int i = 0;
		while (i < text.length()) {
			int eq = text.indexOf('=', i);
			if (eq < 0) {
				break;
			}
			String key = text.substring(i, eq).trim();
			int space = key.lastIndexOf(' ');
			if (space >= 0) {
				key = key.substring(space + 1);
			}
			if (key.endsWith("}")) {
				key = key.substring(0, key.length() - 1);
			}
			int end = eq + 1;
			int depth = 0;
			while (end < text.length()) {
				char c = text.charAt(end);
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
				} else if (depth == 0 && c == ' ') {
					break;
				}
				end++;
			}
			String value = text.substring(eq + 1, end).trim();
			if (value.startsWith("{")) {
				value = value.substring(1);
			}
			if (value.endsWith("}")) {
				value = value.substring(0, value.length() - 1);
			}
			fields.put(key, value);
			i = end + 1;
		}
		return fields;
	}

	/** 从 {@code HEAPWATCH-DIFF} 行里取 {@code total{...}} / {@code null{...}} 分组内的某个计数。 */
	private static String diffField(String diffLine, String field) {
		int groupAt = diffLine.indexOf("total{");
		if (groupAt < 0) {
			fail("grouping line has no total{...} group: " + diffLine);
		}
		int open = diffLine.indexOf('{', groupAt);
		int close = diffLine.indexOf('}', open);
		while (open >= 0 && close > open) {
			for (String pair : diffLine.substring(open + 1, close).split(" ")) {
				int eq = pair.indexOf('=');
				if (eq > 0 && pair.substring(0, eq).equals(field)) {
					return pair.substring(eq + 1);
				}
			}
			open = diffLine.indexOf('{', close);
			close = open < 0 ? -1 : diffLine.indexOf('}', open);
		}
		fail("grouping line has no " + field + "=... entry: " + diffLine);
		return null;
	}
}
