package io.github.ihope1.remold.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code cleanRemoldCache}: explicit cache cleanup, the Remold analogue of {@code clean} for {@code build/}.
 *
 * <p>Assertions are terminal (directories gone / siblings survive) and lock the <b>project-scoped</b>
 * default: the machine-wide agent download cache must not be deleted unless explicitly requested
 * ({@code --global} / {@code -Premold.cleanGlobalCache=true}), and the task must not be wired into
 * {@code clean} (that would force re-downloads).</p>
 */
class CleanRemoldCacheTaskTest {

    @TempDir
    File tempDir;

    @Test
    void isRegisteredButNotWiredIntoClean() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        CleanRemoldCacheTask task = (CleanRemoldCacheTask) project.getTasks().findByName("cleanRemoldCache");
        Assertions.assertNotNull(task, "cleanRemoldCache must be registered");
        Assertions.assertEquals("remold", task.getGroup());
        Assertions.assertFalse(task.isIncludeGlobalCache(),
                "the shared cache must be opt-in, not the project-task default");
        // Wiping the shared agent cache on every `clean` would force re-downloads; keep it explicit.
        Assertions.assertTrue(project.getTasks().getByName("clean").getDependsOn().isEmpty(),
                "clean must not depend on cleanRemoldCache");
    }

    @Test
    void deletesProjectAndBuildCachesButKeepsSharedGlobalCacheByDefault() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);
        CleanRemoldCacheTask task = (CleanRemoldCacheTask) project.getTasks().getByName("cleanRemoldCache");

        File projectCache = new File(tempDir, ".gradle/remold");
        File globalCache = new File(tempDir, "gradle-home/caches/remold");
        File buildCache = new File(tempDir, "build/remold");
        seed(projectCache, "remold-agent.jar");
        seed(globalCache, "remold-agent-0.2.7.jar");
        seed(buildCache, "run-remold.bat");
        // Siblings that must survive: another .gradle subtree and the Gradle user-home parent.
        File gradleSibling = new File(tempDir, ".gradle/other/keep.txt");
        File globalSibling = new File(tempDir, "gradle-home/caches/other/keep.txt");
        write(gradleSibling);
        write(globalSibling);

        task.setProjectCacheDir(projectCache);
        task.setGlobalCacheDir(globalCache);
        task.setBuildCacheDir(buildCache);
        task.cleanRemoldCache();

        Assertions.assertFalse(projectCache.exists(), "project cache must be deleted");
        Assertions.assertFalse(buildCache.exists(), "generated launcher cache must be deleted");
        Assertions.assertTrue(globalCache.isDirectory(),
                "the machine-wide shared cache is NOT project state; it must survive the default run");
        Assertions.assertTrue(new File(globalCache, "remold-agent-0.2.7.jar").isFile(),
                "the shared agent cache contents must be untouched by default");
        Assertions.assertTrue(gradleSibling.isFile(), "unrelated .gradle content must survive");
        Assertions.assertTrue(globalSibling.isFile(), "unrelated Gradle-home content must survive");

        // Idempotent: running again on absent project directories is a no-op, not an error.
        Assertions.assertDoesNotThrow(task::cleanRemoldCache);
    }

    @Test
    void globalCacheIsDeletedOnlyWhenExplicitlyRequested() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);
        CleanRemoldCacheTask task = (CleanRemoldCacheTask) project.getTasks().getByName("cleanRemoldCache");

        File projectCache = new File(tempDir, ".gradle/remold");
        File globalCache = new File(tempDir, "gradle-home/caches/remold");
        File buildCache = new File(tempDir, "build/remold");
        seed(projectCache, "remold-agent.jar");
        seed(globalCache, "remold-agent-0.2.7.jar");
        seed(buildCache, "run-remold.bat");

        task.setProjectCacheDir(projectCache);
        task.setGlobalCacheDir(globalCache);
        task.setBuildCacheDir(buildCache);
        task.setIncludeGlobalCache(true);   // the effect of --global
        task.cleanRemoldCache();

        Assertions.assertFalse(projectCache.exists());
        Assertions.assertFalse(buildCache.exists());
        Assertions.assertFalse(globalCache.exists(), "--global must delete the shared cache");
    }

    @Test
    void projectPropertyEnablesGlobalCleanup() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        project.getExtensions().getExtraProperties().set("remold.cleanGlobalCache", "true");
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        CleanRemoldCacheTask task = (CleanRemoldCacheTask) project.getTasks().getByName("cleanRemoldCache");
        Assertions.assertTrue(task.isIncludeGlobalCache(),
                "-Premold.cleanGlobalCache=true must opt the task into the shared-cache cleanup");
    }

    private static void seed(File dir, String fileName) throws Exception {
        Assertions.assertTrue(dir.mkdirs() || dir.isDirectory());
        write(new File(dir, fileName));
    }

    private static void write(File file) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8));
    }
}
