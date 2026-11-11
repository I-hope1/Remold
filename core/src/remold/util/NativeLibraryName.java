package remold.util;

import java.util.Locale;

/**
 * 解析当前平台应加载的 native JVMTI 库文件名（纯函数，不做任何 I/O）。
 *
 * <p>同一份 agent jar 里按文件名区分各平台/ABI 的构建产物：</p>
 * <ul>
 *   <li>{@code tool64.dll} —— Windows x86_64；</li>
 *   <li>{@code libtool64.dylib} / {@code libtoolarm64.dylib} —— macOS x86_64 / arm64；</li>
 *   <li>{@code libtool64.so} —— Linux x86_64（glibc）；</li>
 *   <li>{@code libtoolaarch64.so} —— Linux aarch64（glibc，{@code core/cpp/compile.bat} 用 zig
 *       {@code aarch64-linux-gnu.2.17} 交叉编译）；</li>
 *   <li>{@code libtoolarm64.so} —— Android arm64（NDK {@code aarch64-linux-android30}）。</li>
 * </ul>
 *
 * <p><b>Android arm64 与 Linux aarch64 必须按产物区分</b>：两者 {@code os.name}/{@code os.arch}
 * 都是 {@code Linux}/{@code aarch64}，但 ABI 不同——Android 产物的 {@code NEEDED} 是 bionic 的
 * {@code libc.so}（Android 30），在 glibc 的 {@code ld-linux-aarch64.so.1} 下无法装载，
 * 表现为 {@link UnsatisfiedLinkError}；因此 Android 走 {@code isAndroid} 分支保留 NDK 产物，
 * glibc 走独立的 {@code libtoolaarch64.so}。</p>
 */
public final class NativeLibraryName {
	/** Windows x86_64（MSVC/zig PE 构建）。 */
	public static final String WINDOWS_X64 = "tool64.dll";
	/** macOS x86_64。 */
	public static final String MACOS_X64 = "libtool64.dylib";
	/** macOS arm64。 */
	public static final String MACOS_ARM64 = "libtoolarm64.dylib";
	/** Linux x86_64（glibc）。 */
	public static final String LINUX_X64 = "libtool64.so";
	/** Linux aarch64（glibc）。 */
	public static final String LINUX_ARM64 = "libtoolaarch64.so";
	/** Android arm64（bionic，NDK 构建）。 */
	public static final String ANDROID_ARM64 = "libtoolarm64.so";

	private NativeLibraryName() {}

	/**
	 * 解析平台对应的 native 库文件名。
	 *
	 * @param osName   {@code System.getProperty("os.name")} 的原始值（可为 null，大小写不敏感）
	 * @param osArch   {@code System.getProperty("os.arch")} 的原始值（可为 null，大小写不敏感）
	 * @param android  是否运行在 Android/ART（{@code android.os.Build} 可加载）
	 * @return jar 内 {@code /libs/} 资源与 {@code core/libs/} 目录共用的文件名
	 */
	public static String resolve(String osName, String osArch, boolean android) {
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);

		// 先判 macOS/Darwin 再判 Windows：字符串 "Darwin" 里也含子串 "win"。
		if (os.contains("mac") || os.contains("darwin")) return isArm64(arch) ? MACOS_ARM64 : MACOS_X64;
		if (os.contains("win")) return WINDOWS_X64;
		if (android) return isArm64(arch) ? ANDROID_ARM64 : LINUX_X64;
		if (isArm64(arch)) return LINUX_ARM64;
		return LINUX_X64;
	}

	/** 64 位 ARM 的 {@code os.arch} 取值：JDK/Android 为 {@code aarch64}，部分运行时为 {@code arm64}。 */
	public static boolean isArm64(String osArch) {
		if (osArch == null) return false;
		String arch = osArch.toLowerCase(Locale.ROOT);
		return arch.contains("aarch64") || arch.contains("arm64");
	}
}
