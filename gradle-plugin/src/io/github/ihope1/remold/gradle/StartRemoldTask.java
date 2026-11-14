package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.IOException;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Configuration-cache-safe task that spawns a detached Remold process.
 */
@DisableCachingByDefault(because = "Spawns a detached interactive JVM process")
public abstract class StartRemoldTask extends DefaultTask {

    private File targetJar;
    private File projectDir;
    private File buildDir;
    private String projectName;
    private String executable;

    @Internal
    public File getTargetJar() {
        return targetJar;
    }

    public void setTargetJar(File targetJar) {
        this.targetJar = targetJar;
    }

    @Internal
    public File getWorkingDir() {
        return projectDir;
    }

    public void setWorkingDir(File projectDir) {
        this.projectDir = projectDir;
    }

    @Internal
    public File getBuildOutputDir() {
        return buildDir;
    }

    public void setBuildOutputDir(File buildDir) {
        this.buildDir = buildDir;
    }

    @Internal
    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @Internal
    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    @TaskAction
    public void start() {
        if (targetJar != null && !targetJar.exists()) {
            throw new GradleException("[Remold] Target launch JAR does not exist; cannot launch application: "
                    + targetJar.getAbsolutePath());
        }
        File argsFile = new File(buildDir, "remold/remold.args");
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        try {
            if (isWin) {
                new ProcessBuilder("cmd", "/c", "start", "\"Remold [" + projectName + "]\"", executable,
                        "@" + argsFile.getAbsolutePath())
                        .directory(projectDir)
                        .start();
                getLogger().lifecycle("[Remold] Application launched in a separate console window.");
            } else {
                File logFile = new File(buildDir, "remold/remold.log");
                new ProcessBuilder(executable, "@" + argsFile.getAbsolutePath())
                        .directory(projectDir)
                        .redirectOutput(logFile)
                        .redirectErrorStream(true)
                        .start();
                getLogger().lifecycle("[Remold] Application launched in background. Output log: " + logFile.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new GradleException("[Remold] Failed to start application process", e);
        }
        getLogger().lifecycle("[Remold] Gradle Daemon released (IDLE). Run ./gradlew compileJava in terminal anytime to trigger hot-reload.");
    }
}
