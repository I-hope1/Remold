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
 * 3. 常量集合变化（末尾追加 / 删除）不改变已有常量的相对序号，顺序门看不到；但热更不重跑
 *    {@code <clinit>}，新增常量恒为 null、删除的常量仍留在旧 {@code $VALUES} 数组里——必须由
 *    ENUM_CONSTANT_SET_CHANGED 拒绝并提示重启（"热更成功但没生效"是不可接受的假成功）。
 * 4. 对照组：只给枚举加普通字段（非常量）或仅修改方法体时必须放行，不得误伤。
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
	void appendedEnumConstantIsRejected() {
		// 末尾追加常量不改变存量常量的 ordinal 映射，但热更不会重跑 <clinit>：新常量不会被构造、
		// $VALUES 不会扩容，运行期 values()/valueOf() 里仍没有 PURPLE。放行就是"成功但没生效"。
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST, COLOR_APPENDED);

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertFalse(diff.enumOrderChanged, "末尾追加常量未改变已有常量的相对序号，不得标记 enumOrderChanged");
		assertEquals(List.of("PURPLE"), diff.addedEnumConstants,
			"新增常量必须被识别出来：" + diff.addedEnumConstants);
		assertTrue(diff.removedEnumConstants.isEmpty());

		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertEquals(Verdict.ENUM_CONSTANT_SET_CHANGED, res.verdict(),
			"追加常量必须判定为 ENUM_CONSTANT_SET_CHANGED");
		assertFalse(res.compatible(), "ENUM_CONSTANT_SET_CHANGED 绝不可兼容放行");
		assertTrue(res.detail().contains("PURPLE"), "判决明细必须点名新增常量：" + res.detail());

		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, v1, v2, "reject", Liveness.LIVE,
			Set.of(ENUM_HOST));
		assertEquals(LayoutAction.REJECT, d.action(),
			"<clinit> 不重跑，追加常量在运行期恒为 null，必须拒绝");
		assertTrue(d.reason() != null && d.reason().contains("enum constant set changed"), d.reason());
		assertTrue(d.reason().contains("<clinit>"),
			"拒绝原因必须说明机制（<clinit> 不重跑）：" + d.reason());
	}

	@Test
	void removedEnumConstantIsRejectedEvenWhenScanCompleted() {
		// 删除常量：旧 $VALUES 仍持有该实例，values()/valueOf() 仍能见到它。即使读者扫描完成且
		// 批外无读者（旧逻辑会 WAIVE_NO_READERS 放行），也必须拒绝——放行是"删了但还在"的假成功。
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST,
			"package test;\n"
			+ "public enum Color {\n"
			+ "    RED, GREEN;\n"
			+ "    public String tag() { return \"v2\"; }\n"
			+ "}\n");

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertFalse(diff.enumOrderChanged, "删除末尾常量不改变存量常量的相对序号");
		assertEquals(List.of("BLUE"), diff.removedEnumConstants, "删除的常量必须被识别：" + diff.removedEnumConstants);

		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertEquals(Verdict.ENUM_CONSTANT_SET_CHANGED, res.verdict());

		RemoldAgent.RemovedMemberScan nobodyReads =
			new RemoldAgent.RemovedMemberScan(true, List.of(), List.of(), false, false, List.of());
		LayoutDecision d = RemoldAgent.decideLayout(ENUM_HOST, diff.changedFields, "reject", Liveness.LIVE,
			Set.of(ENUM_HOST), nobodyReads);
		assertEquals(LayoutAction.REJECT, d.action(),
			"删除常量不能被 WAIVE_NO_READERS 放行：旧 $VALUES 仍持有该实例");
		assertTrue(d.reason() != null && d.reason().contains("enum constant set changed"),
			"必须以常量集合门拒绝并说明原因（而不是落到通用布局拒绝）：" + d.reason());
		assertTrue(d.reason().contains("<clinit>"),
			"拒绝原因必须说明 <clinit> 不重跑的机制：" + d.reason());
	}

	@Test
	void enumAddingRegularFieldStillPasses() {
		// 对照组：枚举新增非常量字段（普通实例字段）不属于常量集变化，仍交给 InitFix / 布局门常规路径。
		byte[] v1 = compileOne(ENUM_HOST, COLOR_V1);
		byte[] v2 = compileOne(ENUM_HOST,
			"package test;\n"
			+ "public enum Color {\n"
			+ "    RED, GREEN, BLUE;\n"
			+ "    public int counter;\n"
			+ "    public String tag() { return \"v2\"; }\n"
			+ "}\n");

		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertTrue(diff.addedEnumConstants.isEmpty(), "counter 不是枚举常量");
		assertTrue(diff.removedEnumConstants.isEmpty());
		LayoutGate.Result res = LayoutGate.checkChangedFields(diff.changedFields);
		assertNotEquals(Verdict.ENUM_CONSTANT_SET_CHANGED, res.verdict(),
			"普通字段新增不得误判为常量集合变化：" + res.detail());
		assertEquals(LayoutAction.PASS,
			RemoldAgent.decideLayout(ENUM_HOST, v1, v2, "reject", Liveness.LIVE, Set.of(ENUM_HOST)).action());
	}

	@Test
	void batchClassesReferencingChangedConstantAreDropped() {
		// 级联阻断：批次里按新 enum 编译、直接引用 PURPLE 的调用方必须随枚举一起移出，
		// 否则枚举保持旧版加载后，它会在运行期解析到不存在的字段（NoSuchFieldError）。
		Map<String, byte[]> compiled = compile(Map.of(
			ENUM_HOST, COLOR_APPENDED,
			"test.UsesPurple", "package test; public class UsesPurple {"
				+ " public static Color c() { return Color.PURPLE; } }",
			"test.UsesRed", "package test; public class UsesRed {"
				+ " public static Color c() { return Color.RED; } }"
		));

		Map<String, byte[]> batch = new LinkedHashMap<>(compiled);
		assertTrue(batch.containsKey("test.UsesPurple") && batch.containsKey("test.UsesRed"));
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		Map<String, java.nio.file.Path> paths = new HashMap<>();
		for (String k : batch.keySet()) paths.put(k, java.nio.file.Path.of(k + ".class"));

		RemoldAgent.dropEnumConstantDependents(batch, paths, ENUM_HOST, List.of("PURPLE"), skips);

		assertFalse(batch.containsKey("test.UsesPurple"), "引用新增常量的调用方必须被移出批次");
		assertTrue(batch.containsKey("test.UsesRed"), "引用未变常量的类不得被误移出");
		assertTrue(skips.contains("test.UsesPurple"));
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
