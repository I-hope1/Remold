import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 夹具编译的公共约定：让夹具的 class 版本落在<b>被测运行时能加载</b>的范围内。
 *
 * <p>CI 的 {@code p05-probe} 矩阵里，跑探针的 JVM 是 JBR 17 / 21，而夹具用的 javac 来自 Gradle
 * 的 toolchain 21。直接用它出 v65 字节码，在 17 上会在<b>加载夹具</b>时就抛
 * {@code UnsupportedClassVersionError}——那跟被测的重定义机制毫无关系，纯粹是测试脚手架
 * 自己踩了自己的脚。</p>
 *
 * <p>所以夹具的 target 取 {@code min(运行时 feature, javac feature)}：既不超出 javac 的能力，
 * 也不超出运行时的接受范围。{@code --release} 是 9 才有的选项，低于 9 时不加（此时两边都是 8，
 * 本来也不需要降）。</p>
 */
final class ProbeJavac {

	private ProbeJavac() {}

	/** 只缓存一次：一次探针运行里 javac 不会换。{@code 0} 表示还没探测。 */
	private static String cachedJavac;
	private static int cachedFeature;

	/**
	 * 拼出完整的 javac 命令行：{@code [javac, --release, N, args...]}。
	 * 运行时与 javac 同为 8 时退化为 {@code [javac, args...]}。
	 */
	static String[] command(String javac, String... args) {
		int release = release(javac);
		String[] head = release < 9
			? new String[]{javac}
			: new String[]{javac, "--release", String.valueOf(release)};
		String[] out = new String[head.length + args.length];
		System.arraycopy(head, 0, out, 0, head.length);
		System.arraycopy(args, 0, out, head.length, args.length);
		return out;
	}

	/** 夹具应当编译成的 feature 版本；拿不到 javac 版本时退回运行时版本。 */
	static int release(String javac) {
		int runtime = Runtime.version().feature();
		int jc = javacFeature(javac);
		return jc > 0 ? Math.min(runtime, jc) : runtime;
	}

	/**
	 * 运行时能接受的 class 文件主版本号（Java 8 = 52，每代 +1）。
	 *
	 * <p>ASM 直接生成的字节码同样要按这个落版本：写死 {@code Opcodes.V21} 在 17 上会在
	 * {@code defineHiddenClass} 处被 VM 的类文件解析器直接拒掉，跟被测机制无关。</p>
	 */
	static int classFileVersion() {
		return Runtime.version().feature() + 44;
	}

	private static int javacFeature(String javac) {
		if (cachedFeature != 0 && javac.equals(cachedJavac)) return cachedFeature;
		int feature = -1;
		try {
			Process p = new ProcessBuilder(javac, "-version").redirectErrorStream(true).start();
			String line = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
			p.waitFor();
			if (line != null) {
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(line);
				if (m.find()) feature = Integer.parseInt(m.group(1));
			}
		} catch (Exception ignored) {
			// 探测不出来就交给调用方退回运行时版本。
		}
		cachedJavac = javac;
		cachedFeature = feature;
		return feature;
	}
}
