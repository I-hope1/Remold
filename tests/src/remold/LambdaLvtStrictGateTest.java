package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.LambdaAligner.LambdaAlignmentStrictException;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缺 {@code -g} 时捕获 Lambda 缺少 {@code LocalVariableTable} 警告与 Strict 模式安全门断言。
 *
 * <p><b>问题溯源（v6 报告 §10.1）</b>：
 * <ul>
 *   <li><b>现象</b>：{@code isPermutedPair} 依赖 {@code captureIdentity}（LVT 变量名优先，其次宿主形参槽号）；
 *       捕获来自<b>局部变量</b>且类无 {@code LocalVariableTable} 时返回 null → 换序检测失效，旧实例静默沿用旧行为。</li>
 *   <li><b>编译期不可逆信息丢失</b>：无 {@code -g} 时 javac 会把捕获顺序相反、方法体相同的 lambda 去重合并成同一个合成方法，
 *       两个 indy 调用点共用它、靠实参顺序区分；带 {@code -g} 时 LVT 参数名不同，javac 会保留两个方法不去重。</li>
 *   <li><b>对策</b>：检测到"含捕获 lambda 的类缺 {@code LocalVariableTable}"时输出 WARN（提示用 {@code -g} 重编）；
 *       在 strict 模式（{@code -Dremold.agent.lambda_strict=true}）下拒绝重定义（抛出 {@link LambdaAlignmentStrictException}），
 *       阻断不安全换序；无捕获 lambda 或仅捕获 {@code this} 的 lambda 精准放行。</li>
 * </ul>
 * </p>
 */
class LambdaLvtStrictGateTest {

	private boolean origStrict;

	@BeforeEach
	void setUp() {
		origStrict = RemoldAgent.LAMBDA_STRICT;
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.LAMBDA_STRICT = origStrict;
	}

	private static final String CAPTURED_V1 = """
		package remold.test;
		public class CapturedLocalHost {
		    public java.util.function.IntSupplier create(int a) {
		        int b = a * 2;
		        return () -> a + b;
		    }
		}
		""";

	private static final String CAPTURED_V2 = """
		package remold.test;
		public class CapturedLocalHost {
		    public java.util.function.IntSupplier create(int a) {
		        int b = a * 2;
		        return () -> b + a;
		    }
		}
		""";

	private static final String NON_CAPTURED_V1 = """
		package remold.test;
		public class NonCapturedHost {
		    public java.util.function.IntSupplier create() {
		        return () -> 42;
		    }
		}
		""";

	private static final String NON_CAPTURED_V2 = """
		package remold.test;
		public class NonCapturedHost {
		    public java.util.function.IntSupplier create() {
		        return () -> 100;
		    }
		}
		""";

	private static final String THIS_ONLY_V1 = """
		package remold.test;
		public class ThisOnlyHost {
		    public int base() { return 10; }
		    public java.util.function.IntSupplier create() {
		        return () -> base() + 1;
		    }
		}
		""";

	private static final String THIS_ONLY_V2 = """
		package remold.test;
		public class ThisOnlyHost {
		    public int base() { return 10; }
		    public java.util.function.IntSupplier create() {
		        return () -> base() + 2;
		    }
		}
		""";

	private static Map<String, byte[]> compileWithDebug(Map<String, String> sources, String debugOption) {
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
			List.of("-proc:none", "-nowarn", debugOption), null, units).call());
		if (!ok) {
			StringBuilder sb = new StringBuilder("夹具编译失败：");
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append("\n      ").append(d.getKind()).append(' ').append(d.getMessage(null));
			}
			throw new IllegalStateException(sb.toString());
		}
		return out;
	}

	@Test
	@DisplayName("基线确认：-g:none 下形参 LVT 为空，-g 下形参 LVT 正常解析出源码变量名")
	void baselineLvtPresenceAndAbsence() {
		byte[] withoutG = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V1), "-g:none")
			.get("remold.test.CapturedLocalHost");
		byte[] withG = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V1), "-g")
			.get("remold.test.CapturedLocalHost");
		assertNotNull(withoutG);
		assertNotNull(withG);

		Map<String, String[]> noGParams = LambdaAligner.readLambdaParamNames(withoutG);
		Map<String, String[]> gParams = LambdaAligner.readLambdaParamNames(withG);

		assertFalse(noGParams.isEmpty(), "基线确认：应识别到合成 lambda 方法");
		assertFalse(gParams.isEmpty(), "基线确认：应识别到合成 lambda 方法");

		// 在 -g:none 下，所有参数名均为 null
		for (String[] names : noGParams.values()) {
			for (String name : names) {
				assertNull(name, "基线确认：-g:none 下形参名必须全为 null（缺少 LocalVariableTable）");
			}
		}

		// 在 -g 下，捕获形参具有明确的源码变量名
		boolean foundNamedParam = false;
		for (String[] names : gParams.values()) {
			for (String name : names) {
				if (name != null) foundNamedParam = true;
			}
		}
		assertTrue(foundNamedParam, "基线确认：-g 下必须包含来自 LocalVariableTable 的源码参数名");
	}

	@Test
	@DisplayName("默认模式：未带 -g 的含捕获 lambda 不崩溃，正常对齐并记录 missingLvtLambdas")
	void nonStrictModeWarnsAndRecordsMissingLvt() {
		RemoldAgent.LAMBDA_STRICT = false;

		byte[] v1 = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V1), "-g:none")
			.get("remold.test.CapturedLocalHost");
		byte[] v2 = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V2), "-g:none")
			.get("remold.test.CapturedLocalHost");

		byte[] aligned = LambdaAligner.align(v1, v2, null, null, null);
		assertNotNull(aligned, "非 strict 模式下应降级放行或完成对齐");

		LambdaAligner.AlignmentResult res = LambdaAligner.LAST_RESULT;
		assertNotNull(res, "应发布对齐结果快照");
		assertFalse(res.missingLvtLambdas.isEmpty(), "应识别并记录缺少 LVT 的捕获 lambda");
		assertTrue(res.missingLvtLambdas.stream().anyMatch(m -> m.contains("lambda$create$0")),
			"缺少 LVT 记录中应包含 lambda$create$0，实际为: " + res.missingLvtLambdas);
	}

	@Test
	@DisplayName("Strict 模式终局断言：未带 -g 的含捕获 lambda 坚决抛出 LambdaAlignmentStrictException")
	void strictModeRejectsCapturedLambdaWithoutLvt() {
		RemoldAgent.LAMBDA_STRICT = true;

		byte[] v1 = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V1), "-g:none")
			.get("remold.test.CapturedLocalHost");
		byte[] v2 = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V2), "-g:none")
			.get("remold.test.CapturedLocalHost");

		LambdaAlignmentStrictException ex = assertThrows(LambdaAlignmentStrictException.class, () ->
			LambdaAligner.align(v1, v2, null, null, null)
		);
		assertEquals("remold.test.CapturedLocalHost", ex.className);
		assertTrue(ex.reason.contains("lambda$create$0"), "异常原因应指出缺失 LVT 的方法名，实际为: " + ex.reason);
	}

	@Test
	@DisplayName("对照组一（带 -g 重编）：Strict 模式下带 -g 编译的含捕获 lambda 安全放行且 missingLvtLambdas 为空")
	void strictModePassesWhenCompiledWithG() {
		RemoldAgent.LAMBDA_STRICT = true;

		byte[] v1 = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V1), "-g")
			.get("remold.test.CapturedLocalHost");
		byte[] v2 = compileWithDebug(Map.of("remold.test.CapturedLocalHost", CAPTURED_V2), "-g")
			.get("remold.test.CapturedLocalHost");

		byte[] aligned = assertDoesNotThrow(() -> LambdaAligner.align(v1, v2, null, null, null));
		assertNotNull(aligned);

		LambdaAligner.AlignmentResult res = LambdaAligner.LAST_RESULT;
		assertNotNull(res);
		assertTrue(res.missingLvtLambdas.isEmpty(), "带 -g 时 missingLvtLambdas 必须为空，实际为: " + res.missingLvtLambdas);
	}

	@Test
	@DisplayName("对照组二（无捕获 lambda）：Strict 模式下无捕获 lambda 即使未带 -g 也精准放行")
	void strictModePassesNonCapturedLambdaWithoutG() {
		RemoldAgent.LAMBDA_STRICT = true;

		byte[] v1 = compileWithDebug(Map.of("remold.test.NonCapturedHost", NON_CAPTURED_V1), "-g:none")
			.get("remold.test.NonCapturedHost");
		byte[] v2 = compileWithDebug(Map.of("remold.test.NonCapturedHost", NON_CAPTURED_V2), "-g:none")
			.get("remold.test.NonCapturedHost");

		byte[] aligned = assertDoesNotThrow(() -> LambdaAligner.align(v1, v2, null, null, null));
		assertNotNull(aligned);

		LambdaAligner.AlignmentResult res = LambdaAligner.LAST_RESULT;
		assertNotNull(res);
		assertTrue(res.missingLvtLambdas.isEmpty(), "无捕获 lambda 即使无 -g 也不应误报，实际为: " + res.missingLvtLambdas);
	}

	@Test
	@DisplayName("对照组三（仅捕获 this）：Strict 模式下仅捕获 this 的 lambda 即使未带 -g 也精准放行")
	void strictModePassesThisOnlyCapturedLambdaWithoutG() {
		RemoldAgent.LAMBDA_STRICT = true;

		byte[] v1 = compileWithDebug(Map.of("remold.test.ThisOnlyHost", THIS_ONLY_V1), "-g:none")
			.get("remold.test.ThisOnlyHost");
		byte[] v2 = compileWithDebug(Map.of("remold.test.ThisOnlyHost", THIS_ONLY_V2), "-g:none")
			.get("remold.test.ThisOnlyHost");

		byte[] aligned = assertDoesNotThrow(() -> LambdaAligner.align(v1, v2, null, null, null));
		assertNotNull(aligned);

		LambdaAligner.AlignmentResult res = LambdaAligner.LAST_RESULT;
		assertNotNull(res);
		assertTrue(res.missingLvtLambdas.isEmpty(), "仅捕获 this 的 lambda 即使无 -g 也不应误报，实际为: " + res.missingLvtLambdas);
	}
}
