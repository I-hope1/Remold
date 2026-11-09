package remold.reflect.mixin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机械不变量（C1）：热更管线插件类<b>不得</b>在常量池里硬链接 core 的可选类型。
 *
 * <p><b>为什么需要这条断言</b>：插件 jar 与 core jar 版本错位时，插件类在类链接期解析常量池
 * 中缺失的 core 类型会抛 {@code NoClassDefFoundError}，把成功与失败两条分支一起打死（真实客户端
 * 表现是 {@code Mixin hot-swap handler unavailable: NoClassDefFoundError:
 * LoadedTargetSuppressor}）。抑制器能力现在只以 {@code java.util.function.Function} /
 * {@code Consumer} 方法引用的形式跨过 core 边界，因此插件常量池里不应再出现
 * {@code remold/reflect/mixin/LoadedTargetSuppressor}，也不应让插件类 {@code implements} /
 * {@code extends} 任何 core mixin 类型。</p>
 *
 * <p>扫描对象直接从源码树枚举并按其资源名读取 <b>已编译</b> 的 class 字节，新增插件类会自动
 * 纳入检查，不会因为忘记登记白名单而漏检。</p>
 */
class MixinPluginClassLinkageTest {

	/** 插件模块（相对仓库根）。缺失的模块跳过，存在即全量扫描。 */
	private static final List<String> PLUGIN_MODULES = List.of("plugin-mixin", "plugin-forge", "plugin-fabric");

	/** core 侧反射引擎：同样不得引用被删除的抑制器接口。 */
	private static final String CORE_MIXIN_SOURCES = "core/src/remold/reflect/mixin";

	/** 被废除的硬链接点（内部名形式 + 点分形式，日志文案里出现点分形式同样算引用）。 */
	private static final List<String> FORBIDDEN = List.of(
		"remold/reflect/mixin/LoadedTargetSuppressor",
		"remold.reflect.mixin.LoadedTargetSuppressor");

	@Test
	void noPluginClassHardLinksTheOptionalCoreSuppressorType() throws Exception {
		Path root = repositoryRoot();
		Set<String> scanned = new LinkedHashSet<>();
		for (String module : PLUGIN_MODULES) {
			Path sources = root.resolve(module).resolve("src");
			if (!Files.isDirectory(sources)) {
				continue;
			}
			for (Path source : javaSources(sources)) {
				String relative = sources.relativize(source).toString().replace('\\', '/');
				String resource = relative.substring(0, relative.length() - ".java".length()) + ".class";
				byte[] bytes = readClassBytes(resource);
				assertNotNull(bytes, "compiled class missing from the test classpath: " + module + "/" + resource);
				assertClassFileFreeness(module + "/" + resource, bytes);
				scanned.add(module + "/" + resource);
			}
		}
		assertTrue(scanned.size() >= 20,
			"the scan must cover the whole plugin surface, but only found " + scanned.size() + " classes: " + scanned);
	}

	@Test
	void coreMixinEngineNoLongerReferencesTheRemovedSuppressorInterface() throws Exception {
		Path sources = repositoryRoot().resolve(CORE_MIXIN_SOURCES);
		assertTrue(Files.isDirectory(sources), "missing core mixin engine sources at " + sources);
		int scanned = 0;
		for (Path source : javaSources(sources)) {
			String relative = sources.relativize(source).toString().replace('\\', '/');
			String resource = "remold/reflect/mixin/"
				+ relative.substring(0, relative.length() - ".java".length()) + ".class";
			byte[] bytes = readClassBytes(resource);
			assertNotNull(bytes, "compiled class missing from the test classpath: " + resource);
			assertClassFileFreeness(resource, bytes);
			scanned++;
		}
		assertTrue(scanned >= 4, "the core engine scan found " + scanned + " classes");
	}

	/**
	 * 正向对照：证明扫描确实读到了真实的、带跨类型引用的 class 字节，防止“扫到空数组也算绿”。
	 * 两个 Fabric 适配器各自必须引用它真正调用的那个 core 引擎类型。
	 */
	@Test
	void scanPositivelySeesCoreTypeReferences() throws Exception {
		Map<String, String> expected = Map.of(
			"remold/mixin/MixinConfigDiff.class", "remold/reflect/mixin/MixinConfigContext",
			"remold/mixin/NewMixinRegistrar.class", "remold/reflect/mixin/NewMixinRegistrar");
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			byte[] bytes = readClassBytes(entry.getKey());
			assertNotNull(bytes, "compiled class missing from the test classpath: " + entry.getKey());
			assertTrue(containsAscii(bytes, entry.getValue()),
				entry.getKey() + " must reference " + entry.getValue() + " (scan sanity check)");
		}
	}

	private static void assertClassFileFreeness(String label, byte[] bytes) {
		for (String forbidden : FORBIDDEN) {
			assertFalse(containsAscii(bytes, forbidden),
				label + " still hard-links " + forbidden
					+ "; the suppressor must cross the core boundary as java.util.function members only,"
					+ " otherwise a core/plugin version skew throws NoClassDefFoundError at link time");
		}
		ClassReader reader = new ClassReader(bytes);
		assertFalse(reader.getSuperName().startsWith("remold/reflect/mixin/"),
			label + " must not extend a core mixin type: " + reader.getSuperName());
		for (String itf : reader.getInterfaces()) {
			assertFalse(itf.startsWith("remold/reflect/mixin/"),
				label + " must not implement a core mixin type: " + itf);
		}
	}

	private static Set<Path> javaSources(Path dir) throws Exception {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.sorted()
				.forEach(out::add);
		}
		return new LinkedHashSet<>(out);
	}

	private static byte[] readClassBytes(String resource) throws Exception {
		try (InputStream in = MixinPluginClassLinkageTest.class.getClassLoader().getResourceAsStream(resource)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	private static boolean containsAscii(byte[] haystack, String needle) {
		byte[] target = needle.getBytes(StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + target.length <= haystack.length; i++) {
			for (int j = 0; j < target.length; j++) {
				if (haystack[i + j] != target[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static Path repositoryRoot() {
		Path current = Path.of("").toAbsolutePath();
		for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
			if (Files.isRegularFile(candidate.resolve("settings.gradle"))) {
				return candidate;
			}
		}
		throw new IllegalStateException("unable to locate the repository root above " + current);
	}
}
