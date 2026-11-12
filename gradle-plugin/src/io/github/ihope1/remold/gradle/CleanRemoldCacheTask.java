package io.github.ihope1.remold.gradle;

import java.io.File;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/**
 * Deletes Remold's caches — the Remold analogue of {@code clean} for {@code build/}.
 *
 * <p><b>Project-scoped by default.</b> A project-level task must not silently wipe state shared by
 * every project on the machine, so the default only removes:</p>
 * <ol>
 *   <li>{@code <rootProject>/.gradle/remold/} — this project's resolved {@code remold-agent.jar} copy
 *       and ecosystem plugin jars;</li>
 *   <li>{@code <buildDir>/remold/} — this project's generated launcher scripts and argument file
 *       (normally covered by {@code clean} too; included so this task is self-sufficient).</li>
 * </ol>
 *
 * <p>The shared download cache {@code <gradleUserHome>/caches/remold/} (<b>agent jars and ecosystem
 * plugin jars</b>, one file per version/module) is <b>not</b> touched unless
 * explicitly requested — pass {@code --global} on the command line or set
 * {@code -Premold.cleanGlobalCache=true}. Deleting it forces a re-download for every project using
 * that Gradle user home.</p>
 *
 * <p>The task is never wired into {@code clean} (that would force an agent re-download on every
 * clean). Run it explicitly after upgrading {@code remoldVersion} or when a project-local cache is
 * suspected to be stale/corrupt.</p>
 *
 * <p>Paths are {@link Internal} inputs (the task has no outputs and always runs); this keeps the task
 * compatible with the configuration cache and avoids build-cache bookkeeping for deletions.</p>
 */
@DisableCachingByDefault(because = "Deletes cache directories; it has no outputs to cache or reuse")
public abstract class CleanRemoldCacheTask extends DefaultTask {

    private File projectCacheDir;
    private File globalCacheDir;
    private File buildCacheDir;
    private boolean includeGlobalCache;

    /** Project-local cache: {@code <rootProject>/.gradle/remold}. */
    @Internal
    public File getProjectCacheDir() {
        return projectCacheDir;
    }

    public void setProjectCacheDir(File projectCacheDir) {
        this.projectCacheDir = projectCacheDir;
    }

    /** Shared download cache ({@code remold-agent-*.jar} + {@code remold-plugin-*.jar}). Only deleted with {@code --global}. */
    @Internal
    public File getGlobalCacheDir() {
        return globalCacheDir;
    }

    public void setGlobalCacheDir(File globalCacheDir) {
        this.globalCacheDir = globalCacheDir;
    }

    /** Generated launcher scripts / args file: {@code <buildDir>/remold}. */
    @Internal
    public File getBuildCacheDir() {
        return buildCacheDir;
    }

    public void setBuildCacheDir(File buildCacheDir) {
        this.buildCacheDir = buildCacheDir;
    }

    /** Whether to also delete the machine-wide {@code caches/remold} download cache (agent + plugin jars). Default {@code false}. */
    @Internal
    public boolean isIncludeGlobalCache() {
        return includeGlobalCache;
    }

    @Option(option = "global",
            description = "Also delete the shared download cache under the Gradle user home (caches/remold: agent + plugin jars).")
    public void setIncludeGlobalCache(boolean includeGlobalCache) {
        this.includeGlobalCache = includeGlobalCache;
    }

    @TaskAction
    public void cleanRemoldCache() {
        StringBuilder removed = new StringBuilder();
        boolean any = false;
        any |= remove(projectCacheDir, "project", removed);
        any |= remove(buildCacheDir, "build", removed);
        if (includeGlobalCache) {
            any |= remove(globalCacheDir, "gradle-user-home", removed);
        }
        if (any) {
            getLogger().lifecycle("[Remold] Removed caches: " + removed);
            getLogger().lifecycle("[Remold] The next runRemold/classes will resolve the agent again.");
        } else {
            getLogger().lifecycle("[Remold] No project-level Remold caches to remove.");
        }
        if (!includeGlobalCache && globalCacheDir != null && globalCacheDir.exists()) {
            getLogger().lifecycle("[Remold] Shared agent cache kept at " + globalCacheDir.getAbsolutePath()
                    + " (pass --global or -Premold.cleanGlobalCache=true to delete it too).");
        }
    }

    private static boolean remove(File dir, String label, StringBuilder removed) {
        if (dir == null || !dir.exists()) return false;
        IOExceptionlessDelete.delete(dir);
        if (dir.exists()) {
            throw new org.gradle.api.GradleException("[Remold] Failed to delete cache directory: " + dir.getAbsolutePath());
        }
        if (removed.length() > 0) removed.append(", ");
        removed.append(label).append("=").append(dir.getAbsolutePath());
        return true;
    }

    /** Small indirection so the deletion helper is unit-testable without Gradle's file utilities. */
    static final class IOExceptionlessDelete {
        private IOExceptionlessDelete() { }

        static void delete(File file) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) delete(child);
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
