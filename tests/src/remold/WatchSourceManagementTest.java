package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证多来源监控路径（Watch Source Separation）的隔离性与合并机制：
 * 1. CLI 启动路径与模组动态路径各自独立管理；
 * 2. 模组更新或清空自身路径时，绝不冲刷 CLI 来源的路径；
 * 3. activeWatchDirs 始终为全部来源的动态并集。
 */
class WatchSourceManagementTest {

	@TempDir
	Path tempDir;

	private Path dirCli;
	private Path dirModTools;
	private Path dirThirdParty;

	@BeforeEach
	void setUp() throws IOException {
		dirCli = Files.createDirectories(tempDir.resolve("cli-classes"));
		dirModTools = Files.createDirectories(tempDir.resolve("modtools-classes"));
		dirThirdParty = Files.createDirectories(tempDir.resolve("thirdparty-classes"));
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.removeWatchSource(RemoldAgent.SOURCE_CLI);
		RemoldAgent.removeWatchSource("mod-tools");
		RemoldAgent.removeWatchSource("third-party");
	}

	@Test
	void testMultiSourceWatchPathsIsolation() {
		// 1. 模拟 CLI 启动传入路径
		RemoldAgent.setWatchPaths(RemoldAgent.SOURCE_CLI, List.of(dirCli), Collections.emptyList());
		assertEquals(Set.of(dirCli), RemoldAgent.getWatchDirs(RemoldAgent.SOURCE_CLI));
		assertTrue(RemoldAgent.activeWatchDirs.contains(dirCli));
		assertEquals(1, RemoldAgent.activeWatchDirs.size());

		// 2. 模拟游戏内 mod-tools 注入新监控路径
		RemoldAgent.setWatchPaths("mod-tools", List.of(dirModTools), Collections.emptyList());
		assertEquals(Set.of(dirModTools), RemoldAgent.getWatchDirs("mod-tools"));

		// 验证并集：两个来源均在 activeWatchDirs 中生效
		assertEquals(Set.of(dirCli, dirModTools), RemoldAgent.activeWatchDirs);

		// 3. 模拟 mod-tools 界面修改路径为 dirThirdParty
		RemoldAgent.setWatchPaths("mod-tools", List.of(dirThirdParty), Collections.emptyList());
		assertEquals(Set.of(dirThirdParty), RemoldAgent.getWatchDirs("mod-tools"));
		// CLI 路径不受任何影响
		assertEquals(Set.of(dirCli), RemoldAgent.getWatchDirs(RemoldAgent.SOURCE_CLI));
		assertEquals(Set.of(dirCli, dirThirdParty), RemoldAgent.activeWatchDirs);

		// 4. 模拟 mod-tools 移除其监控路径
		RemoldAgent.removeWatchSource("mod-tools");
		assertTrue(RemoldAgent.getWatchDirs("mod-tools").isEmpty());
		// CLI 路径依然完好常驻
		assertEquals(Set.of(dirCli), RemoldAgent.activeWatchDirs);

		// 5. 验证 getAllWatchDirSources 来源分布可观测性
		RemoldAgent.setWatchPaths("mod-tools", List.of(dirModTools), Collections.emptyList());
		var sources = RemoldAgent.getAllWatchDirSources();
		assertTrue(sources.containsKey(RemoldAgent.SOURCE_CLI));
		assertTrue(sources.containsKey("mod-tools"));
		assertEquals(Set.of(dirCli), sources.get(RemoldAgent.SOURCE_CLI));
		assertEquals(Set.of(dirModTools), sources.get("mod-tools"));
	}
}
