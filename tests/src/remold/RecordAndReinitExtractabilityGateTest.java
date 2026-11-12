package remold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Record 组件变更与 {@code @RemoldReinit} 初值提取可行性安全门断言（防 B4 丢值与 R1 错位）。
 *
 * <p><b>问题溯源（v6 报告 §10.1）</b>：
 * <ul>
 *   <li><b>B4</b>：Record 组件或普通类构造器参数赋值字段变更类型时标注 {@code @RemoldReinit}，
 *       布局门若仅凭注离开绿灯（{@code WAIVE_REINIT}），DCEVM 重定义后槽位被重置为默认值（null/0），
 *       而 {@code InitFix} 提取切片失败，导致存量实例发生静默数据丢失。必须前置核验切片提取可行性，不可提取时坚决拒绝。</li>
 *   <li><b>R1</b>：Record 类新增组件（实例字段）。Record 禁止声明实例变量，组件全由构造器赋值且无切片可提取。
 *       若堆上有存活实例，存量实例新组件恒为 0/null，破坏 Record 不变式与等价性；无存活实例时方可安全放行。</li>
 * </ul>
 * </p>
 */
class RecordAndReinitExtractabilityGateTest {

	private static byte[] cls(String dotName, String source) {
		Map<String, byte[]> out = compile(Map.of(dotName, source));
		byte[] b = out.get(dotName);
		assertNotNull(b, "夹具编译失败: " + dotName);
		return b;
	}

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

	@Test
	@DisplayName("R1: Record 类新增组件在有存活实例时必须坚决拒绝 (LAYOUT-REJECT)")
	void recordComponentAddedMustBeRejectedWithLiveInstances() {
		String host = "test.R";
		byte[] v1 = cls(host, "package test;\npublic record R(int a) {}");
		byte[] v2 = cls(host, "package test;\npublic record R(int a, int b) {}");

		LayoutDecision d = RemoldAgent.decideLayout(host, v1, v2, "reject", Liveness.LIVE, Set.of(host));

		assertEquals(LayoutAction.REJECT, d.action(),
			"Record 类在有存活实例时新增组件必须被布局门拒绝以防存量组件为 0");
		assertEquals(Verdict.RECORD_COMPONENT_ADDED, d.layout().verdict());
		assertTrue(d.reason().contains("record component added"), "拒绝原因必须指明新增 Record 组件: " + d.reason());
		assertTrue(d.reason().contains("cannot have field initializers"), "拒绝原因必须说明 Record 无法提供初值: " + d.reason());
		assertTrue(d.dropped().contains(host), "被拒绝的 Record 必须移出批次");
	}

	@Test
	@DisplayName("R1 对照组: Record 类新增组件在无存活实例时应安全放行 (WAIVE)")
	void recordComponentAddedWaivedWithNoLiveInstances() {
		String host = "test.R";
		byte[] v1 = cls(host, "package test;\npublic record R(int a) {}");
		byte[] v2 = cls(host, "package test;\npublic record R(int a, int b) {}");

		LayoutDecision d = RemoldAgent.decideLayout(host, v1, v2, "reject", Liveness.NONE, Set.of(host));

		assertEquals(LayoutAction.WAIVE, d.action(),
			"Record 类在无存活实例时新增组件可安全通过新规范构造器初始化，应放行 (WAIVE)");
		assertEquals(Verdict.RECORD_COMPONENT_ADDED, d.layout().verdict());
		assertTrue(d.dropped().isEmpty(), "无存活实例放行时不应丢弃类");
	}

	@Test
	@DisplayName("B4: Record 组件改类型标注 @RemoldReinit 时因无提取切片必须拒绝 (LAYOUT-REJECT)")
	void recordComponentTypeChangeWithRemoldReinitMustBeRejected() {
		String host = "test.R";
		byte[] v1 = cls(host, "package test;\npublic record R(int a) {}");
		byte[] v2 = cls(host, "package test;\nimport remold.annotation.RemoldReinit;\npublic record R(@RemoldReinit String a) {}");

		LayoutDecision d = RemoldAgent.decideLayout(host, v1, v2, "reject", Liveness.LIVE, Set.of(host));

		assertEquals(LayoutAction.REJECT, d.action(),
			"Record 组件即便标注了 @RemoldReinit 也无法提取初值切片，必须拒绝放行以防静默置 null");
		assertEquals(Verdict.CHANGED_FIELD_TYPE, d.layout().verdict());
		assertTrue(d.reason().contains("@RemoldReinit cannot reinitialize"),
			"拒绝原因必须明确指出 @RemoldReinit 无法提取初值: " + d.reason());
		assertTrue(d.reason().contains("record component"),
			"拒绝原因必须明确指明原因是 Record 组件: " + d.reason());
	}

	@Test
	@DisplayName("B4 变体: 普通类构造器参数自赋值字段标 @RemoldReinit 时因无初值切片必须拒绝")
	void normalClassConstructorParamSelfAssignmentWithRemoldReinitMustBeRejected() {
		String host = "test.User";
		byte[] v1 = cls(host, "package test;\npublic class User { private final String name; public User(String name) { this.name = name; } }");
		byte[] v2 = cls(host, "package test;\nimport remold.annotation.RemoldReinit;\npublic class User { @RemoldReinit private final Object name; public User(Object name) { this.name = name; } }");

		LayoutDecision d = RemoldAgent.decideLayout(host, v1, v2, "reject", Liveness.LIVE, Set.of(host));

		assertEquals(LayoutAction.REJECT, d.action(),
			"构造器参数自赋值字段标 @RemoldReinit 无法提取初值切片，必须拒绝放行以防静默置 null");
		assertEquals(Verdict.CHANGED_FIELD_TYPE, d.layout().verdict());
		assertTrue(d.reason().contains("@RemoldReinit cannot reinitialize"),
			"拒绝原因必须说明 @RemoldReinit 无法提取初值: " + d.reason());
		assertTrue(d.reason().contains("self-assignment from constructor parameter"),
			"拒绝原因必须指明构造器参数自赋值: " + d.reason());
	}

	@Test
	@DisplayName("B3 对照组: 普通类具备有效初值表达式并标注 @RemoldReinit 应正常放行 (WAIVE_REINIT)")
	void normalClassFieldWithValidInitializerAndRemoldReinitWaived() {
		String host = "test.Hot";
		byte[] v1 = cls(host, "package test;\npublic class Hot { int x = 7; }");
		byte[] v2 = cls(host, "package test;\nimport remold.annotation.RemoldReinit;\npublic class Hot { @RemoldReinit String x = \"MAX\"; }");

		LayoutDecision d = RemoldAgent.decideLayout(host, v1, v2, "reject", Liveness.LIVE, Set.of(host));

		assertEquals(LayoutAction.WAIVE_REINIT, d.action(),
			"字段具备合法初值表达式且标有 @RemoldReinit 时，应正常授权放行 (WAIVE_REINIT)");
		assertEquals(Verdict.CHANGED_FIELD_TYPE, d.layout().verdict());
		assertTrue(d.dropped().isEmpty(), "WAIVE_REINIT 放行时不应丢弃类");
	}
}
