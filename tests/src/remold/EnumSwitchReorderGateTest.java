package remold;

import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil.ClassDiff;
import remold.LayoutGate.Verdict;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.RemoldAgent.Liveness;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守卫 R6b：枚举常量重排导致 switch-on-enum 静默走错分支的安全门测试。
 *
 * <p>【因果链与对照组要求】
 * 1. 前置基线：枚举常量顺序翻转/前插移位时，必须被 ClassDiff 与 LayoutGate 捕捉并判定为 CHANGED_ENUM_ORDER；
 * 2. 终局断言：decideLayout 必须判定为 REJECT，阻止静默重定义导致的 switch 错位；
 * 3. 对照组：末尾追加常量（旧常量 ordinal 不变）或仅修改枚举方法体时，不得误伤，必须放行（PASS）。
 */
class EnumSwitchReorderGateTest {

	private static final String ENUM_HOST = "test.Color";

	private static final String COLOR_V1 =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    RED, GREEN, BLUE;\n"
		+ "    public String tag() { return \"v1\"; }\n"
		+ "}\n";

	/** R6b 典型形态：已有枚举常量顺序重排 (RED=0,GREEN=1,BLUE=2 -> BLUE=0,RED=1,GREEN=2) */
	private static final String COLOR_REORDERED =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    BLUE, RED, GREEN;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	/** 前插常量：插入 YELLOW 导致原先所有常量 ordinal 全部漂移 (RED:0->1, GREEN:1->2, BLUE:2->3) */
	private static final String COLOR_PREPENDED =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    YELLOW, RED, GREEN, BLUE;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	/** 对照组 A：末尾追加新常量 (PURPLE: 3)，已有常量 RED(0), GREEN(1), BLUE(2) 的 ordinal 完全未变 */
	private static final String COLOR_APPENDED =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    RED, GREEN, BLUE, PURPLE;\n"
		+ "    public String tag() { return \"v2\"; }\n"
		+ "}\n";

	/** 对照组 B：常量集合与声明顺序完全未变，仅修改内部方法体 */
	private static final String COLOR_BODY_ONLY =
		"package test;\n"
		+ "public enum Color {\n"
		+ "    RED, GREEN, BLUE;\n"
		+ "    public String tag() { return \"v2-updated\"; }\n"
		+ "}\n";

	private static byte[] compileOne(String dotName, String source) {
		Map<String, byte[]> out = compile(Map.of(dotName, source));
		byte[] b = out.get(dotName);
		assertNotNull(b, "编译失败：" + dotName);
		return b;
	}

	@Test
	void reorderedEnumConstantsMustBeRejected() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_REORDERED);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertTrue(diff.enumOrderChanged, "枚举常量声明顺序发生重排，ClassDiff 必须捕捉到 enumOrderChanged");
		assertFalse(diff.changedFields.isEmpty(), "changedFields 必须包含枚举重排变更记录");

		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertEquals(Verdict.CHANGED_ENUM_ORDER, res.verdict(),
			"LayoutGate 必须给出 CHANGED_ENUM_ORDER 判定");
		assertFalse(res.compatible(), "CHANGED_ENUM_ORDER 绝不可兼容放行");
		assertTrue(res.detail().contains("RED") && res.detail().contains("BLUE"),
			"判决明细必须点名发生顺序漂移的常量：" + res.detail());

		Set<String> batch = Set.of(ENUM_HOST, "test.Color$1", "test.Other");
		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, v1, v2, "reject", Liveness.LIVE, batch);
		assertEquals(LayoutAction.REJECT, d.action(),
			"存量枚举实例的 ordinal 在运行期不可变，顺序重排必须由安全门坚决拒绝");
		assertTrue(d.dropped().contains(ENUM_HOST), "拒绝时必须移出该枚举类");
		assertNotNull(d.reason());
		assertTrue(d.reason().contains("enum constant order changed"),
			"拒绝原因必须指明枚举常量顺序变更：" + d.reason());
	}

	@Test
	void prependedEnumConstantMustBeRejected() {
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_PREPENDED);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertTrue(diff.enumOrderChanged, "前插常量导致已有常量 ordinal 漂移，必须检测出 enumOrderChanged");

		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertEquals(Verdict.CHANGED_ENUM_ORDER, res.verdict());
		assertFalse(res.compatible());

		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, v1, v2, "reject", Liveness.LIVE, Set.of(ENUM_HOST));
		assertEquals(LayoutAction.REJECT, d.action(), "前插常量引起 ordinal 偏移必须拒绝");
	}

	@Test
	void appendedEnumConstantAllowed() {
		// 对照组：末尾追加常量不改变存量常量的 ordinal 映射，InitFix 会处理新增静态字段，不应当触发 CHANGED_ENUM_ORDER
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_APPENDED);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertFalse(diff.enumOrderChanged, "末尾追加常量未改变已有常量的相对序号，不得标记 enumOrderChanged");

		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertNotEquals(Verdict.CHANGED_ENUM_ORDER, res.verdict(), "末尾追加不得判定为 CHANGED_ENUM_ORDER");
		assertTrue(res.compatible(), "纯新增字段应兼容放行交给 InitFix");
	}

	@Test
	void methodBodyChangeOnlyAllowed() {
		// 对照组：常量列表完全未改，仅改方法体，必须顺畅放行
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_BODY_ONLY);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertFalse(diff.enumOrderChanged, "常量未变，不得标记 enumOrderChanged");
		assertTrue(diff.changedFields.isEmpty(), "字段未变，changedFields 必须为空");

		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertEquals(Verdict.COMPATIBLE, res.verdict());
		assertTrue(res.compatible());

		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, v1, v2, "reject", Liveness.LIVE, Set.of(ENUM_HOST));
		assertEquals(LayoutAction.PASS, d.action(), "纯方法体修改必须通过");
	}

	@Test
	void switchDependentsInBatchAreDroppedOnEnumOrderChange() {
		// 验证级联防护：当枚举被判拒绝时，同批中针对该枚举的 switch 调用类与 SwitchMap 映射类必须被连带移出批次
		String switchSrc =
			"package test;\n"
			+ "public class ColorSwitch {\n"
			+ "    public static String test(Color c) {\n"
			+ "        switch (c) {\n"
			+ "            case RED: return \"r\";\n"
			+ "            case GREEN: return \"g\";\n"
			+ "            case BLUE: return \"b\";\n"
			+ "            default: return \"other\";\n"
			+ "        }\n"
			+ "    }\n"
			+ "}\n";

		Map<String, byte[]> compiled = compile(Map.of(
			ENUM_HOST, COLOR_V1,
			"test.ColorSwitch", switchSrc
		));

		Map<String, byte[]> batch = new LinkedHashMap<>(compiled);
		assertTrue(batch.containsKey("test.ColorSwitch"), "必须包含 ColorSwitch");
		boolean hasSwitchMap = batch.keySet().stream().anyMatch(k -> k.startsWith("test.ColorSwitch$"));
		assertTrue(hasSwitchMap, "javac 编译 switch-on-enum 必须生成 SwitchMap 合成类 (如 ColorSwitch$1)");

		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		Map<String, java.nio.file.Path> paths = new HashMap<>();
		for (String k : batch.keySet()) paths.put(k, java.nio.file.Path.of(k + ".class"));

		// 触发级联阻断
		RemoldAgent.dropEnumSwitchDependents(batch, paths, ENUM_HOST, skips);

		// 断言所有与该枚举相关的 switch 类均已被移出批次
		assertFalse(batch.containsKey("test.ColorSwitch"), "ColorSwitch 必须被连带移出批次");
		assertTrue(batch.keySet().stream().noneMatch(k -> k.startsWith("test.ColorSwitch$")),
			"所有 ColorSwitch$* 映射类必须全部移出批次");

		// 断言 skips 记录中包含详细告警点名
		assertTrue(skips.contains("test.ColorSwitch"));
	}

	/** 内存编译工具 */
	private static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		StandardJavaFileManager std = compiler.getStandardFileManager(null, null, null);
		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(std) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(className, toByteArray());
							}
						};
					}
				};
			}
		};

		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			URI uri = URI.create("string:///" + e.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return e.getValue();
				}
			});
		}

		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics,
			List.of("-proc:none", "-nowarn"), null, units).call());
		if (!ok) {
			StringBuilder sb = new StringBuilder("测试夹具编译失败：");
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append("\n      ").append(d.getKind()).append(' ').append(d.getMessage(null));
			}
			throw new IllegalStateException(sb.toString());
		}
		return out;
	}
}
