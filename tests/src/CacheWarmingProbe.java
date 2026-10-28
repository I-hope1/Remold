import remold.RemoldAgent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.ProtectionDomain;
import java.util.Arrays;

/**
 * 集成探针：验证"缓存里存的是**对齐/织入之后**的字节"，以及 transformer 的**分组执行顺序**。
 *
 * <p>被验证的两件事（{@code docs/initfix/07-p05-probe-results.md} §14.7）：</p>
 * <ol>
 *   <li><b>分组顺序</b>：JVM 先跑完所有 {@code canRetransform=false} 的 transformer，再跑
 *       {@code canRetransform=true} 的那组；注册顺序只在**组内**生效。所以
 *       {@link RemoldAgent.CacheWarmingTransformer} 必须注册为 {@code true}，否则首次加载时它会
 *       先于 {@code AnnotationTransformer} 执行，拿到对齐/织入**之前**的原始字节。</li>
 *   <li><b>拿到的是最终字节</b>：用一个"替换字节"的 transformer 模拟
 *       {@code pendingAlignedClasses} 的对齐替换，断言缓存里是替换**之后**的那份。</li>
 * </ol>
 *
 * <p>两个断言在同一次类加载里完成：注册三个 transformer——一个 {@code false} 组的前置观察者、
 * 一个 {@code true} 组的字节替换者、以及被验证的 {@code CacheWarmingTransformer}（{@code true} 组，
 * 排在替换者之后）。于是：</p>
 * <ul>
 *   <li>前置观察者（{@code false} 组）看到的是 <b>v1</b> —— 这就是"非可重转换组先跑"的证据；</li>
 *   <li>缓存里必须是 <b>v2</b> —— 说明被验证者看到的是替换之后的字节。</li>
 * </ul>
 *
 * <p>跑法：{@code ./gradlew :tests:cacheWarmingProbe}（只需 {@code -javaagent}，不需要 DCEVM）。</p>
 */
public final class CacheWarmingProbe {

	private static int passed;
	private static int failed;

	private static final String V1 = """
		package cw;
		public class Cw {
		    public int a = 1;
		    public String hi() { return "v1"; }
		}
		""";

	private static final String V2 = """
		package cw;
		public class Cw {
		    public int a = 1;
		    public int b = 2;
		    public String hi() { return "v2"; }
		}
		""";

	public static void main(String[] args) throws Exception {
		System.out.println("=== Cache warming probe ===");
		Instrumentation inst = P05Agent.instrumentation();
		if (inst == null) {
			System.out.println("[SKIP] Instrumentation unavailable; run with -javaagent:p05-agent.jar");
			return;
		}

		File v1Dir = Files.createTempDirectory("cw_v1").toFile();
		File v2Dir = Files.createTempDirectory("cw_v2").toFile();
		compile(v1Dir, V1);
		compile(v2Dir, V2);

		byte[] v1 = Files.readAllBytes(new File(v1Dir, "cw/Cw.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(v2Dir, "cw/Cw.class").toPath());
		check(!Arrays.equals(v1, v2), "precondition: the two variants must differ");

		// 前置观察者：canRetransform = false，按规范它整组先跑，看到的是替换之前的字节。
		byte[][] earlySeen = {null};
		ClassFileTransformer early = new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain pd, byte[] buffer) {
				if ("cw/Cw".equals(className) && classBeingRedefined == null) earlySeen[0] = buffer;
				return null;
			}
		};

		// 字节替换者：模拟 AnnotationTransformer 的 pendingAlignedClasses 对齐替换。
		ClassFileTransformer modifier = new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain pd, byte[] buffer) {
				if ("cw/Cw".equals(className) && classBeingRedefined == null) return v2;
				return null;
			}
		};

		inst.addTransformer(early, false);                                  // false 组：整组先跑
		inst.addTransformer(modifier, true);                                // true 组第 1 个
		inst.addTransformer(new RemoldAgent.CacheWarmingTransformer(), true); // true 组第 2 个（被验证者）

		try {
			ClassLoader loader = new URLClassLoader(new URL[]{v1Dir.toURI().toURL()},
			                                        CacheWarmingProbe.class.getClassLoader());
			Class<?> cw = Class.forName("cw.Cw", true, loader);

			// 先确认替换真的生效了，否则下面的断言都是空转
			check(cw.getDeclaredFields().length == 2,
				"precondition: the modifier actually replaced the bytes (v2 has two fields)");
			check("v2".equals(cw.getMethod("hi").invoke(cw.getConstructor().newInstance())),
				"precondition: the loaded class behaves like v2");

			check(earlySeen[0] != null, "precondition: the non-retransformable observer did see the load");
			check(Arrays.equals(v1, earlySeen[0]),
				"canRetransform=false transformers run before the retransformable group (they still see v1)"
				+ " — this is why CacheWarmingTransformer must be registered with canRetransform=true");

			byte[] cached = RemoldAgent.bytecodeCache.get("cw.Cw");
			check(cached != null, "the first load must have populated bytecodeCache");
			check(Arrays.equals(v2, cached),
				"bytecodeCache must hold the bytes AFTER alignment/weaving, not the pre-alignment original");
		} finally {
			inst.removeTransformer(early);
			inst.removeTransformer(modifier);
		}

		System.out.println("\n=== CACHE WARMING PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	private static void compile(File outDir, String source) throws Exception {
		File src = new File(outDir, "src/cw/Cw.java");
		src.getParentFile().mkdirs();
		Files.writeString(src.toPath(), source, StandardCharsets.UTF_8);
		String javac = findJavac();
		Process p = new ProcessBuilder(javac, "-nowarn", "-encoding", "UTF-8",
		                               "-d", outDir.getAbsolutePath(), src.getAbsolutePath())
			.redirectErrorStream(true).start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (p.waitFor() != 0) throw new IllegalStateException("fixture compilation failed: " + output);
	}

	private static String findJavac() {
		String home = System.getProperty("java.home");
		for (String candidate : new String[]{"bin/javac.exe", "bin/javac"}) {
			File f = new File(home, candidate);
			if (f.isFile()) return f.getAbsolutePath();
		}
		return "javac";
	}

	private static void check(boolean cond, String msg) {
		if (cond) {
			passed++;
			System.out.println("   [PASS] " + msg);
		} else {
			failed++;
			System.out.println("   [FAIL] " + msg);
		}
	}
}
