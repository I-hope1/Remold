import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import remold.RemoldAgent;
import remold.util.LibTool;

/**
 * 接线探针：证明"参数注解漂移"在真实 {@code processChanges} 流水线里**确实把 WARN 发出来了**。
 *
 * <p><b>为什么需要它</b>：成员级点名函数有单测，但"流水线有没有调用它"此前无人把关——
 * 把 {@code RemoldAgent} 里那一行接线短路成 {@code false}/删掉后，所有单测仍然是绿的。
 * 本探针驱动真实批次：v1 → v2 只改参数注解值，redefine，然后断言
 * ①（前置基线）调用前没有任何 {@code [SIG-DRIFT]} 告警；
 * ②（终局）重新解析到的参数注解是 v2 值，证明 redefine 真的生效（不是"没改所以没告警"的假阴性）；
 * ③（被测机制）日志里出现点名该成员的 {@code [SIG-DRIFT]} WARN。
 *
 * <p>断言失败即 {@code System.exit(1)}，因此它可以直接作为变异检查的 test_cmd。
 * 需要 {@code -javaagent} 提供 Instrumentation（走 :core 的 shadowJar agent），不需要 DCEVM：
 * 属性级变化在标准 HotSpot 上也能 redefine（见 {@code docs/offset/01-offset-and-reflection-cache.md} §5）。
 */
public final class ParameterMetadataDriftWarnProbe {

	/** 捕获到的 WARN（与 DoubleBraceProbe 同法：替换 RemoldAgent.logger）。 */
	static final List<String> WARNINGS = new ArrayList<>();

	private static final String TARGET_DOT = "sample.DriftedTarget";

	private static final String TARGET_INTERNAL = "sample/DriftedTarget";

	public static void main(String[] args) throws Exception {
		System.out.println("=== parameter-metadata drift WARN wiring probe ===");
		RemoldAgent.INIT_FIX = true;
		RemoldAgent.ANON_ALIGN = true;
		RemoldAgent.DEBUG = false;
		RemoldAgent.Logger base = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { base.log(msg); }
			public void info(String msg) { base.info(msg); }
			public void warn(String msg) { WARNINGS.add(msg); base.warn(msg); }
			public void error(String msg) { base.error(msg); }
			public void error(String msg, Throwable t) { base.error(msg, t); }
		};
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable ignored) {
			// 堆遍历不可用不影响本探针：它只看"告警有没有发"。
		}

		long driftBefore = WARNINGS.stream().filter(w -> w.contains("[SIG-DRIFT]")).count();
		check(driftBefore == 0, "baseline: no [SIG-DRIFT] warning may exist before the batch");

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"sample.Mark", "package sample; import java.lang.annotation.*;"
				+ " @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER})"
				+ " public @interface Mark { String value(); }",
			TARGET_DOT, targetSource("v1")));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			"sample.Mark", "package sample; import java.lang.annotation.*;"
				+ " @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER})"
				+ " public @interface Mark { String value(); }",
			TARGET_DOT, targetSource("v2-changed")));

		FixtureLoader loader = new FixtureLoader(v1, TARGET_DOT);
		Class<?> target = loader.loadClass(TARGET_DOT);
		// 预热一次旧句柄，贴近真实使用方（框架在重定义前已解析过成员）。
		Method stale = target.getDeclaredMethods()[0];
		String before = annotationValue(stale);
		check("v1".equals(before), "baseline: the resolved parameter annotation must be v1 before the batch, got " + before);

		for (Map.Entry<String, byte[]> e : v1.entrySet()) {
			RemoldAgent.bytecodeCache.put(e.getKey(), e.getValue());
			RemoldAgent.sourceBytecodeCache.put(e.getKey(), e.getValue());
		}
		Path dir = Files.createTempDirectory("k-drift-warn");
		Set<Path> changed = new LinkedHashSet<>();
		for (Map.Entry<String, byte[]> e : v2.entrySet()) {
			Path p = dir.resolve(e.getKey().replace('.', '/') + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
			if (TARGET_DOT.equals(e.getKey())) {
				changed.add(p);
			}
		}

		Method process = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		process.setAccessible(true);
		process.invoke(null, changed, RemoldAgent.getInstrumentation().getAllLoadedClasses());

		// 终局：重新解析必须读到 v2，证明本批次真的重定义了（否则"没告警"无从归因）。
		Method fresh = target.getDeclaredMethods()[0];
		String after = annotationValue(fresh);
		check("v2-changed".equals(after),
			"terminal: the re-resolved parameter annotation must be v2-changed after the batch, got " + after);

		boolean named = WARNINGS.stream().anyMatch(w -> w.contains("[SIG-DRIFT]")
			&& w.contains(TARGET_DOT) && w.contains("parameter annotations"));
		if (!named) {
			System.err.println("[FAIL] expected a [SIG-DRIFT] warning naming " + TARGET_DOT
				+ " parameter annotations; captured warnings: " + WARNINGS);
			System.exit(1);
		}
		System.out.println("[PASS] drift warn emitted: "
			+ WARNINGS.stream().filter(w -> w.contains("[SIG-DRIFT]")).findFirst().orElse("?"));
		System.exit(0);
	}

	private static String targetSource(String value) {
		return "package sample; public class DriftedTarget {"
			+ " public void resolve(@Mark(\"" + value + "\") String name) { } }";
	}

	/** 读第一个参数的注解值：读不到 / 抛任何 Throwable 都返回 "(none)"，由调用方断言判红。 */
	private static String annotationValue(Method method) {
		try {
			Annotation[][] annotations = method.getParameterAnnotations();
			for (Annotation[] group : annotations) {
				for (Annotation annotation : group) {
					Method value = annotation.annotationType().getMethod("value");
					value.setAccessible(true);
					return String.valueOf(value.invoke(annotation));
				}
			}
			return "(none)";
		} catch (Throwable t) {
			return "(thrown:" + t.getClass().getSimpleName() + ")";
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			System.err.println("[FAIL] " + message);
			System.exit(1);
		}
		System.out.println("[ok] " + message);
	}

	/** child-first：只对本探针登记的类生效，其余委托父加载器。 */
	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;
		private final String firstHost;

		FixtureLoader(Map<String, byte[]> classes, String firstHost) {
			super(ParameterMetadataDriftWarnProbe.class.getClassLoader());
			this.classes = classes;
			this.firstHost = firstHost;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					byte[] bytes = classes.get(name);
					loaded = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}
}
