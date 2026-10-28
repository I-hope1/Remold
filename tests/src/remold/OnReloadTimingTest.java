package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import remold.ref.InitFix;
import remold.util.LibTool;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verification that @OnReload callback strictly executes AFTER InitFix static & instance
 * field patches have landed, eliminating the causal inversion where callbacks observe unpatched zero/null.
 */
public class OnReloadTimingTest {

	private static final String V1_SRC = """
		package remold.test;
		import remold.annotation.OnReload;
		public class TargetFixture {
		    public static int existingStatic = 1;
		    public static int observedStaticOnReload = -1;
		    public int existingInstance = 2;
		    public int observedInstanceOnReload = -1;

		    @OnReload
		    public static void onStaticReload() {
		        observedStaticOnReload = existingStatic;
		    }

		    @OnReload
		    public void onInstanceReload() {
		        observedInstanceOnReload = existingInstance;
		    }
		}
		""";

	private static final String V2_SRC = """
		package remold.test;
		import remold.annotation.OnReload;
		public class TargetFixture {
		    public static int existingStatic = 1;
		    public static int newStatic = 8888;
		    public static int observedStaticOnReload = -1;
		    public int existingInstance = 2;
		    public int newInstance = 9999;
		    public int observedInstanceOnReload = -1;

		    @OnReload
		    public static void onStaticReload() {
		        observedStaticOnReload = newStatic;
		    }

		    @OnReload
		    public void onInstanceReload() {
		        observedInstanceOnReload = newInstance;
		    }
		}
		""";

	private boolean originalInitFix;

	@BeforeEach
	void setUp() {
		originalInitFix = RemoldAgent.INIT_FIX;
		RemoldAgent.INIT_FIX = true;
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable ignored) {}
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.INIT_FIX = originalInitFix;
	}

	@Test
	void onReloadRunsAfterStaticFieldPatchIsApplied() throws Exception {
		byte[] v1Bytes = compile("remold.test.TargetFixture", V1_SRC);
		byte[] v2Bytes = compile("remold.test.TargetFixture", V2_SRC);

		Class<?> host = load(v2Bytes, "remold.test.TargetFixture");

		// 1. 前置基线确认：初始状态下 observedStaticOnReload 为 -1，newStatic 尚未修补（默认为 0）
		host.getField("newStatic").setInt(null, 0); // 模拟 JVM redefineClasses 刚分配完毕的零值初始态
		assertEquals(-1, host.getField("observedStaticOnReload").getInt(null),
			"基线确认：重载回调尚未触发前，observedStaticOnReload 必须为初始值 -1");

		// 2. 准备 InitFix 补丁
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1Bytes, v2Bytes);
		InitFix.transform(host, v2Bytes, diff);

		// 3. 正向管道：严格按照修正后的流水线执行：InitFix.afterRedefine -> processAnnotations
		InitFix.afterRedefine(host);
		RemoldAgent.processAnnotations(Collections.singletonList(new ClassDefinition(host, v2Bytes)));

		// 4. 因果链终局断言：@OnReload 执行时，newStatic 必须已由 InitFix 完成补丁写入（8888），而非零值 0
		int observed = host.getField("observedStaticOnReload").getInt(null);
		assertEquals(8888, observed,
			"终局断言：@OnReload 回调必须观察到已被 InitFix 初始化的静态字段新值 (8888)，杜绝时序反转读到 0");
		assertEquals(8888, host.getField("newStatic").getInt(null),
			"终局断言：静态字段自身必须已被真正赋予新初值");
	}

	@Test
	void counterfactualControl_invertedOrderObservesZero() throws Exception {
		// 对照组一（因果归因）：若时序反转（重现旧代码在 InitFix 前执行 @OnReload），断言其读到 0 而非 8888
		byte[] v1Bytes = compile("remold.test.TargetFixture", V1_SRC);
		byte[] v2Bytes = compile("remold.test.TargetFixture", V2_SRC);

		Class<?> host = load(v2Bytes, "remold.test.TargetFixture");
		host.getField("newStatic").setInt(null, 0);

		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1Bytes, v2Bytes);
		InitFix.transform(host, v2Bytes, diff);

		// 故意先跑 @OnReload，模拟旧版 BUG 时序
		RemoldAgent.processAnnotationsInternal(host);

		int buggyObserved = host.getField("observedStaticOnReload").getInt(null);
		assertEquals(0, buggyObserved,
			"反事实对照验证：若先跑 @OnReload，必然读到未修补的 0，证明正向用例的 8888 确实归因于修正后的时序");

		// 之后再跑 InitFix，此时 static 补丁才写入，但 @OnReload 已错过
		InitFix.afterRedefine(host);
		assertEquals(8888, host.getField("newStatic").getInt(null));
		assertNotEquals(8888, buggyObserved, "旧时序下 @OnReload 无法捕获后续写入的静态值");
	}

	@Test
	void irrelevantInputControl_unrelatedClassDoesNotTriggerTargetReload() throws Exception {
		// 对照组二（无关输入排除）：对无关类触发 processAnnotations，目标类的 observed 字段保持不变
		byte[] v2Bytes = compile("remold.test.TargetFixture", V2_SRC);
		Class<?> host = load(v2Bytes, "remold.test.TargetFixture");

		assertEquals(-1, host.getField("observedStaticOnReload").getInt(null));

		// 传入无关类 String.class
		RemoldAgent.processAnnotations(Collections.singletonList(new ClassDefinition(String.class, new byte[0])));

		assertEquals(-1, host.getField("observedStaticOnReload").getInt(null),
			"无关输入对照：非目标类重定义不得触发目标类的 @OnReload 回调");
	}

	private static byte[] compile(String className, String source) throws Exception {
		File dir = Files.createTempDirectory("onreload_fixture").toFile();
		File src = new File(dir, className.replace('.', '/') + ".java");
		src.getParentFile().mkdirs();
		Files.writeString(src.toPath(), source, StandardCharsets.UTF_8);

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("JDK compiler required");
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			Boolean ok = javac.getTask(null, fm, null,
				List.of("-d", dir.getAbsolutePath(), "-cp", System.getProperty("java.class.path")),
				null, fm.getJavaFileObjects(src)).call();
			if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("Failed to compile " + className);
		}
		return Files.readAllBytes(new File(dir, className.replace('.', '/') + ".class").toPath());
	}

	private static Class<?> load(byte[] bytes, String className) {
		String resource = className.replace('.', '/') + ".class";
		ClassLoader loader = new ClassLoader(OnReloadTimingTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!name.equals(className)) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}

			@Override
			public java.io.InputStream getResourceAsStream(String name) {
				if (resource.equals(name)) return new java.io.ByteArrayInputStream(bytes);
				return super.getResourceAsStream(name);
			}
		};
		try {
			return Class.forName(className, true, loader);
		} catch (ClassNotFoundException e) {
			throw new AssertionError(e);
		}
	}
}
