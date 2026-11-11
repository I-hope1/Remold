import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.util.NativeLibraryName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native JVMTI 库文件名解析的回归断言。
 *
 * <p>背景：{@code os.name=Linux} 且 {@code os.arch=aarch64} 同时命中 Android arm64 与
 * Linux aarch64；修复前按名字解析时 Linux aarch64 会选中 NDK 构建的 {@code libtoolarm64.so}
 * （bionic ABI，{@code NEEDED libc.so}），在 glibc 的 {@code ld-linux-aarch64.so.1} 下
 * 直接 {@link UnsatisfiedLinkError}。这里的断言锁住「Linux aarch64 选 glibc 产物、
 * Android arm64 选 NDK 产物」的区分。</p>
 */
@DisplayName("Native library name resolution (platform/ABI matrix)")
class NativeLibraryNameTest {

	@Test
	@DisplayName("Linux aarch64 resolves to the glibc build, not the Android NDK build")
	void linuxAarch64UsesGlibcBuild() {
		assertEquals("libtoolaarch64.so", NativeLibraryName.resolve("Linux", "aarch64", false),
			"Linux aarch64 must load the glibc build; the NDK build's NEEDED libc.so cannot be"
			+ " resolved by ld-linux-aarch64.so.1 (regression: was libtoolarm64.so)");
		assertEquals("libtoolaarch64.so", NativeLibraryName.resolve("Linux", "arm64", false));
	}

	@Test
	@DisplayName("Android aarch64 keeps the NDK build")
	void androidAarch64KeepsNdkBuild() {
		assertEquals("libtoolarm64.so", NativeLibraryName.resolve("Linux", "aarch64", true),
			"Android/ART reports Linux/aarch64 but links bionic; it must keep its own NDK artifact");
	}

	@Test
	@DisplayName("Linux x86_64 resolves to libtool64.so")
	void linuxX64UsesX64Build() {
		assertEquals("libtool64.so", NativeLibraryName.resolve("Linux", "amd64", false));
		assertEquals("libtool64.so", NativeLibraryName.resolve("Linux", "x86_64", false));
	}

	@Test
	@DisplayName("macOS selects the dylib by CPU architecture")
	void macosSelectsByArchitecture() {
		assertEquals("libtoolarm64.dylib", NativeLibraryName.resolve("Mac OS X", "aarch64", false),
			"Apple Silicon must not load the x86_64 dylib");
		assertEquals("libtool64.dylib", NativeLibraryName.resolve("Mac OS X", "x86_64", false));
		assertEquals("libtool64.dylib", NativeLibraryName.resolve("Darwin", "x86_64", false));
	}

	@Test
	@DisplayName("Windows resolves to tool64.dll and property values are case/position tolerant")
	void windowsUsesDll() {
		assertEquals("tool64.dll", NativeLibraryName.resolve("Windows 11", "amd64", false));
		assertEquals("libtoolaarch64.so", NativeLibraryName.resolve("LINUX", "AARCH64", false),
			"os.name/os.arch are not guaranteed to be lowercase");
		// 组装 JDK 的常见 os.arch 取值
		assertTrue(NativeLibraryName.isArm64("aarch64"));
		assertTrue(NativeLibraryName.isArm64("arm64"));
		assertFalse(NativeLibraryName.isArm64("amd64"));
		assertFalse(NativeLibraryName.isArm64(null));
		assertDoesNotThrow(() -> NativeLibraryName.resolve(null, null, false));
	}
}
