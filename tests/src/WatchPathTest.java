import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import remold.RemoldAgent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Watch Path Auto-Discovery Suite")
public class WatchPathTest {

	@Test
	@DisplayName("RemoldAgent automatically discovers out/production when given build/classes path")
	public void testAutoDiscoverIdeaOutProduction() throws Exception {
		Path tempProject = Files.createTempDirectory("remold-test-proj-");
		try {
			Path buildClasses = tempProject.resolve("build").resolve("classes").resolve("java").resolve("main");
			Files.createDirectories(buildClasses);

			Path ideaOutProd = tempProject.resolve("out").resolve("production");
			Files.createDirectories(ideaOutProd);

			String sourceId = "test-auto-discover";
			// 前置基线确认：未配置前来源监控集为空
			assertTrue(RemoldAgent.getWatchDirs(sourceId).isEmpty(), "Baseline: watch dirs for source must initially be empty");

			// 触发：仅传入 buildClasses 路径
			RemoldAgent.setWatchPaths(sourceId, buildClasses.toString());

			Set<Path> watched = RemoldAgent.getWatchDirs(sourceId);
			// 终局断言：不仅监控了 buildClasses，还自动嗅探到了 out/production
			assertTrue(watched.contains(buildClasses.toAbsolutePath().normalize()), "Must watch buildClasses");
			assertTrue(watched.contains(ideaOutProd.toAbsolutePath().normalize()), "Must auto-discover and watch out/production");

			// 对照组：换无关输入（纯临时目录，父目录无 build/ 或 out/production）
			Path unrelatedDir = Files.createTempDirectory("unrelated-");
			try {
				String unrelatedSource = "test-unrelated";
				RemoldAgent.setWatchPaths(unrelatedSource, unrelatedDir.toString());
				Set<Path> unrelatedWatched = RemoldAgent.getWatchDirs(unrelatedSource);
				assertEquals(1, unrelatedWatched.size(), "Unrelated directory must not auto-discover any phantom out/production");
				assertTrue(unrelatedWatched.contains(unrelatedDir.toAbsolutePath().normalize()));
				RemoldAgent.removeWatchSource(unrelatedSource);
			} finally {
				deleteRecursively(unrelatedDir);
			}

			RemoldAgent.removeWatchSource(sourceId);
		} finally {
			deleteRecursively(tempProject);
		}
	}

	@Test
	@DisplayName("RemoldAgent with buildMode=gradle does not auto-discover out/production")
	public void testGradleBuildModeBlocksAutoDiscovery() throws Exception {
		String prevMode = RemoldAgent.getBuildMode();
		RemoldAgent.setBuildMode("gradle");
		Path tempProject = Files.createTempDirectory("remold-test-gradle-mode-");
		try {
			Path buildClasses = tempProject.resolve("build").resolve("classes").resolve("java").resolve("main");
			Files.createDirectories(buildClasses);

			Path ideaOutProd = tempProject.resolve("out").resolve("production");
			Files.createDirectories(ideaOutProd);

			String sourceId = "test-gradle-mode";
			assertTrue(RemoldAgent.getWatchDirs(sourceId).isEmpty(), "Baseline: watch dirs initially empty");

			RemoldAgent.setWatchPaths(sourceId, buildClasses.toString());

			Set<Path> watched = RemoldAgent.getWatchDirs(sourceId);
			// 终局断言：仅监控 buildClasses，绝不引入 out/production
			assertTrue(watched.contains(buildClasses.toAbsolutePath().normalize()), "Must watch buildClasses");
			assertFalse(watched.contains(ideaOutProd.toAbsolutePath().normalize()), "Must NOT auto-discover out/production in gradle mode");

			RemoldAgent.removeWatchSource(sourceId);
		} finally {
			RemoldAgent.setBuildMode(prevMode);
			deleteRecursively(tempProject);
		}
	}

	@Test
	@DisplayName("RemoldAgent creates non-existent out/production path if explicitly specified")
	public void testCreateNonExistentIdeaOutProduction() throws Exception {
		Path tempProject = Files.createTempDirectory("remold-test-proj-create-");
		try {
			Path ideaOutProd = tempProject.resolve("out").resolve("production");
			assertFalse(Files.exists(ideaOutProd), "Baseline: out/production must not exist before test");

			String sourceId = "test-create-out";
			RemoldAgent.setWatchPaths(sourceId, ideaOutProd.toString());

			// 终局断言：目录被自动建立且被加入监控集
			assertTrue(Files.isDirectory(ideaOutProd), "out/production must be created by RemoldAgent");
			Set<Path> watched = RemoldAgent.getWatchDirs(sourceId);
			assertTrue(watched.contains(ideaOutProd.toAbsolutePath().normalize()));

			RemoldAgent.removeWatchSource(sourceId);
		} finally {
			deleteRecursively(tempProject);
		}
	}

	private static void deleteRecursively(Path root) {
		try {
			Files.walk(root)
			     .sorted((a, b) -> b.compareTo(a))
			     .forEach(p -> {
				     try { Files.deleteIfExists(p); } catch (Exception ignored) {}
			     });
		} catch (Exception ignored) {}
	}
}
