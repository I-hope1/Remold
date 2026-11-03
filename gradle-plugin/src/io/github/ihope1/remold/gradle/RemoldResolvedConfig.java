package io.github.ihope1.remold.gradle;

import java.io.File;
import java.util.Collections;
import java.util.List;
import org.gradle.api.file.FileCollection;

/**
 * Resolved configuration snapshot for Remold hot-reloading execution.
 */
public final class RemoldResolvedConfig {

    private final String mainClass;
    private final String executable;
    private final String executableHome;
    private final File agentFile;
    private final List<File> allWatchDirs;
    private final String watchPath;
    private final List<String> jvmArgs;
    private final List<String> appArgs;
    private final FileCollection runtimeClasspath;
    private final File targetJar;

    public RemoldResolvedConfig(
            String mainClass,
            String executable,
            String executableHome,
            File agentFile,
            List<File> allWatchDirs,
            String watchPath,
            List<String> jvmArgs,
            List<String> appArgs,
            FileCollection runtimeClasspath,
            File targetJar) {
        this.mainClass = mainClass;
        this.executable = executable;
        this.executableHome = executableHome;
        this.agentFile = agentFile;
        this.allWatchDirs = allWatchDirs != null ? Collections.unmodifiableList(allWatchDirs) : Collections.emptyList();
        this.watchPath = watchPath != null ? watchPath : "";
        this.jvmArgs = jvmArgs != null ? Collections.unmodifiableList(jvmArgs) : Collections.emptyList();
        this.appArgs = appArgs != null ? Collections.unmodifiableList(appArgs) : Collections.emptyList();
        this.runtimeClasspath = runtimeClasspath;
        this.targetJar = targetJar;
    }

    public String getMainClass() {
        return mainClass;
    }

    public String getExecutable() {
        return executable;
    }

    public String getExecutableHome() {
        return executableHome;
    }

    public File getAgentFile() {
        return agentFile;
    }

    public List<File> getAllWatchDirs() {
        return allWatchDirs;
    }

    public String getWatchPath() {
        return watchPath;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public List<String> getAppArgs() {
        return appArgs;
    }

    public FileCollection getRuntimeClasspath() {
        return runtimeClasspath;
    }

    public File getTargetJar() {
        return targetJar;
    }
}
