package remold.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：类名形态转换只允许出现在 {@link Names}，存量清单只减不增。
 *
 * <p>背景是两次真机静默 miss——出生点补丁的待决键、动态重定义的提交键，两次都是手写
 * {@code replace('.', '/')} / {@code replace('/', '.')} 且方向写反，测试用同形态造数据时全绿。</p>
 *
 * <p>存量清单 {@code name-conversion-baseline.txt} 记录仍然手写的 40 多处：一次性批量替换会把
 * 字符串字面量里的转换也改掉，风险大于收益；因此先冻结存量、堵住新增，改动某个文件时顺手把它
 * 的条目删掉（条目消失而不删行会被 {@link #baselineHasNoStaleEntries()} 顶出来）。</p>
 */
class NameConversionGuardTest {

	private static final Pattern CONVERSION =
	 Pattern.compile("replace\\(\\s*'\\.'\\s*,\\s*'/'|replace\\(\\s*'/'\\s*,\\s*'\\.'");

	private static final List<String> SCANNED_MODULES = List.of(
		"core/src", "api/src", "plugin-mixin/src", "plugin-fabric/src",
		"plugin-forge/src", "plugin-arc/src", "gradle-plugin/src");

	@Test
	void newConversionsMustGoThroughNames() throws IOException {
		Path root = repoRoot();
		assertTrue(Files.isDirectory(root.resolve("core/src")), "repo root not resolved: " + root);

		Map<String, Integer> current = currentConversions(root);
		Map<String, Integer> baseline = readBaseline(root);
		assertTrue(baseline.size() > 1, "baseline looks empty: " + baseline);

		List<String> newFiles = new ArrayList<>();
		List<String> grownFiles = new ArrayList<>();
		for (Map.Entry<String, Integer> e : current.entrySet()) {
			Integer allowed = baseline.get(e.getKey());
			if (allowed == null) {
				newFiles.add(e.getKey() + " (" + e.getValue() + ")");
			} else if (e.getValue() > allowed) {
				grownFiles.add(e.getKey() + " " + allowed + "->" + e.getValue());
			}
		}
		assertEquals(List.of(), newFiles,
		 "new hand-written class-name conversions: use remold.util.Names instead");
		assertEquals(List.of(), grownFiles,
		 "hand-written conversions grew in a baselined file; route the new ones through Names");
	}

	@Test
	void baselineHasNoStaleEntries() throws IOException {
		Path root = repoRoot();
		Map<String, Integer> current = currentConversions(root);
		List<String> stale = readBaseline(root).keySet().stream()
		 .filter(key -> !current.containsKey(key)).sorted().toList();
		assertEquals(List.of(), stale,
		 "these files no longer contain raw conversions; drop them from name-conversion-baseline.txt");
	}

	@Test
	void internalAndExternalAreInverse() {
		assertEquals("a/b/C", Names.internal("a.b.C"));
		assertEquals("a.b.C", Names.external("a/b/C"));
		assertEquals("a/b/C", Names.internal("a/b/C"), "already internal names must be untouched");
		assertEquals("a.b.C", Names.external("a.b.C"), "already external names must be untouched");
		assertNull(Names.internal(null));
		assertNull(Names.external(null));
	}

	@Test
	void innerClassDollarSignsSurvive() {
		assertEquals("a/b/C$D", Names.internal("a.b.C$D"));
		assertEquals("a.b.C$D", Names.external("a/b/C$D"));
	}

	private static Map<String, Integer> currentConversions(Path root) throws IOException {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String module : SCANNED_MODULES) {
			Path dir = root.resolve(module);
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
					String rel = root.relativize(file).toString().replace('\\', '/');
					if ("core/src/remold/util/Names.java".equals(rel)) continue;
					String text = Files.readString(file, StandardCharsets.UTF_8);
					Matcher m = CONVERSION.matcher(text);
					int n = 0;
					while (m.find()) n++;
					if (n > 0) counts.put(rel, n);
				}
			}
		}
		return counts;
	}

	private static Map<String, Integer> readBaseline(Path root) throws IOException {
		Map<String, Integer> baseline = new LinkedHashMap<>();
		Path file = root.resolve("tests/src/remold/util/name-conversion-baseline.txt");
		if (!Files.isRegularFile(file)) return baseline;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) continue;
			String[] parts = t.split("\\t");
			if (parts.length != 2) continue;
			baseline.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
		}
		return baseline;
	}

	private static Path repoRoot() {
		// tests 模块是独立 Gradle 构建（自带 settings.gradle.kts），所以不能拿它当根：
		// 以"同时存在 core/src 与 docs"为准向上找。
		Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (dir != null && !(Files.isDirectory(dir.resolve("core/src"))
		 && Files.isDirectory(dir.resolve("docs")))) {
			dir = dir.getParent();
		}
		return dir == null ? Path.of("").toAbsolutePath() : dir;
	}
}
