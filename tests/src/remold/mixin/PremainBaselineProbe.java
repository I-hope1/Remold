package remold.mixin;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.util.Arrays;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import remold.RemoldAgent;

/**
 * premain 基线录口的 JVM 级实证（不变量 C2）。
 *
 * <p><b>为什么需要它</b>：单测只能证明录口"收到什么就记什么、恒返回 null"。C2 要的是
 * **JVM 定义的那份最终字节**（Mixin/Knot 在 {@code defineClass} 之前改完的产物）。本探针把整条
 * 链路真的跑一遍：</p>
 * <ol>
 *   <li>用 {@code -javaagent:remold-agent.jar} 让 agent 走真实 premain（含 initTransformer 的挂点）；</li>
 *   <li>通过插件真实的 {@code onPremain} 入口安装录口（幂等）；</li>
 *   <li>内存 javac 现编一个带 ASCII 标记的夹具类，模拟 Knot/Mixin 在 loader 侧把字节改掉
 *       （{@code PROBE_AAAA} → {@code PROBE_BBBB}），再 {@code defineClass}；</li>
 *   <li>断言三件事：录到的基线 == 改写后的字节（说明是 post-Knot 的最终字节）、JVM 里跑的
 *       {@code value()} 返回改写后的常量（说明录口返回 null 没有扰动改写链）、闸对同一加载器放行、
 *       对没录过的加载器拒绝并给出 restart。</li>
 * </ol>
 *
 * <p>运行（{@code :tests:compileJava} 先编译；ASM jar 取工程 {@code asmVersion} 的同版本依赖即可）：
 * {@code java -javaagent:core/build/libs/remold-agent.jar -cp
 * "tests/build/classes/java/main;plugin-mixin/build/classes/java/main;core/build/libs/remold-agent.jar;&lt;asm-core,tree,commons,analysis,util jars&gt;"
 * remold.mixin.PremainBaselineProbe}。</p>
 *
 * <p>三个分母（{@code seen}/{@code recorded}/{@code missed}）由最后一行 PASS 输出；其中 {@code missed}
 * 的 1 来自本探针自己的负向对照（换无关加载器查不到基线）。</p>
 */
public final class PremainBaselineProbe {

	private static final String FIXTURE_INTERNAL = "probe/Target$Anonymous$premain";
	private static final String FIXTURE_DOTTED = "probe.Target$Anonymous$premain";
	private static final String ORIGINAL = "PROBE_AAAA";
	private static final String REWOVEN = "PROBE_BBBB";

	private PremainBaselineProbe() {
	}

	public static void main(String[] args) throws Exception {
		Instrumentation inst = RemoldAgent.getInstrumentation();
		require(inst != null, "run this probe with -javaagent:core/build/libs/remold-agent.jar");

		// 真实安装点：插件 premain 窗口（内部幂等；agent 的 initTransformer 之后才挂到 JVM）。
		new MixinHotswapPlugin().onPremain(null);

		byte[] source = compileFixture();
		require(indexOf(source, ORIGINAL) >= 0, "fixture bytecode must carry the ASCII marker " + ORIGINAL);

		// 模拟 Knot/Mixin：改字节发生在 defineClass 之前，loader 交出去的已经是最终字节。
		byte[] rewove = replaceAll(source, ORIGINAL, REWOVEN);
		require(indexOf(rewove, REWOVEN) > 0, "the simulated weave must rewrite the marker");

		IsolatedLoader loader = new IsolatedLoader();
		Class<?> live = loader.define(FIXTURE_INTERNAL, rewove);
		Object instance = live.getDeclaredConstructor().newInstance();
		String viaBytecode = (String) live.getMethod("value").invoke(instance);

		// 终局断言 1：JVM 里真正执行的常量是改写后的值 —— 录口没有扰动改写链。
		require(REWOVEN.equals(viaBytecode),
			"the JVM must define exactly the bytes the loader supplied (recorder returns null); got " + viaBytecode);

		// 终局断言 2：录到的基线就是 post-Knot 的最终字节。
		byte[] baseline = MixinClassBaseline.baselineOf(loader, FIXTURE_DOTTED);
		require(baseline != null, "the premain recorder must capture the class loaded after the agent registered");
		require(Arrays.equals(rewove, baseline),
			"the captured baseline must be the FINAL (post-weave) bytes, not the pre-weave ones");
		require(indexOf(baseline, REWOVEN) > 0,
			"a pre-weave baseline would make the gate compare against bytes that never ran");

		// 终局断言 3：闸按键放行 / 拒绝（换无关加载器作对照）。
		require(MixinInnerClassHotSwap.baselineMissingReason(loader, FIXTURE_DOTTED) == null,
			"the gate must accept a class whose premain baseline was captured");
		String refused = MixinInnerClassHotSwap.baselineMissingReason(new IsolatedLoader(), FIXTURE_DOTTED);
		require(refused != null, "a loader without a baseline must be refused (negative control)");
		require(refused.contains("restart"), "the refusal must ask for a restart: " + refused);

		System.out.println("PASS premain baseline recorder (JVM level): " + MixinClassBaseline.metricsSummary());
	}

	// ---------------------------------------------------------------- in-memory fixture

	/**
	 * 现编夹具类（内存 javac）：常量以 ASCII 标记写在常量池里，便于字节级模拟 knot 改写。
	 *
	 * <p>名字里带 {@code $}：录口只收候选（见 {@link MixinClassBaseline}），这也是合成内部类的形态。</p>
	 */
	private static byte[] compileFixture() throws Exception {
		String source = "package probe;\n"
			+ "public class Target$Anonymous$premain {\n"
			+ "  public static final String MARKER = \"" + ORIGINAL + "\";\n"
			+ "  public String value() { return MARKER; }\n"
			+ "}\n";
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		require(compiler != null, "in-memory javac unavailable (a JDK, not a JRE, is required)");
		StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, null);
		MemoryFileManager files = new MemoryFileManager(standard);
		JavaFileObject unit = new StringSource("probe/Target$Anonymous$premain.java", source);
		Boolean ok = compiler.getTask(null, files, null, Arrays.asList("-g:none", "-proc:none"),
			null, Arrays.asList(unit)).call();
		require(Boolean.TRUE.equals(ok), "the in-memory fixture must compile");
		byte[] out = files.bytes();
		require(out != null, "the in-memory fixture must produce class bytes");
		return out;
	}

	private static final class StringSource extends SimpleJavaFileObject {
		private final String code;

		StringSource(String path, String code) {
			super(URI.create("string:///" + path), Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return this.code;
		}
	}

	private static final class MemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
		private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

		MemoryFileManager(JavaFileManager delegate) {
			super(delegate);
		}

		@Override
		public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
				FileObject sibling) {
			return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
				@Override
				public OutputStream openOutputStream() {
					return MemoryFileManager.this.sink;
				}
			};
		}

		byte[] bytes() {
			byte[] out = this.sink.toByteArray();
			return out.length == 0 ? null : out;
		}
	}

	// ---------------------------------------------------------------- helpers

	private static final class IsolatedLoader extends ClassLoader {
		IsolatedLoader() {
			super("premain-baseline-probe", null);
		}

		Class<?> define(String internalName, byte[] bytes) {
			return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
		}
	}

	private static int indexOf(byte[] haystack, String needle) {
		return indexOf(haystack, needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static byte[] replaceAll(byte[] bytes, String from, String to) {
		byte[] needle = from.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] patch = to.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		require(needle.length == patch.length, "the simulated weave must keep the constant-pool layout stable");
		byte[] out = bytes.clone();
		int hits = 0;
		for (int i = 0; i + needle.length <= out.length; i++) {
			boolean hit = true;
			for (int j = 0; j < needle.length; j++) {
				if (out[i + j] != needle[j]) {
					hit = false;
					break;
				}
			}
			if (hit) {
				System.arraycopy(patch, 0, out, i, patch.length);
				hits++;
				i += needle.length - 1;
			}
		}
		require(hits > 0, "the simulated weave must hit the marker at least once");
		return out;
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException("premain baseline probe FAILED: " + message);
		}
	}
}
