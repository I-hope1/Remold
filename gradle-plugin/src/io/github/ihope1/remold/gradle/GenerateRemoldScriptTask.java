package io.github.ihope1.remold.gradle;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Configuration-cache-safe task that emits standalone launcher scripts and {@code remold.args}.
 */
@DisableCachingByDefault(because = "Writes machine-local launcher scripts and absolute-path args files")
public abstract class GenerateRemoldScriptTask extends DefaultTask {

    private File targetJar;
    private File buildDir;
    private String executable;
    private String mainClass;
    private List<String> jvmArgs = Collections.emptyList();
    private List<String> appArgs = Collections.emptyList();
    private Map<File, File> pendingSyncs = Collections.emptyMap();

    @Internal
    public File getTargetJar() {
        return targetJar;
    }

    public void setTargetJar(File targetJar) {
        this.targetJar = targetJar;
    }

    @Internal
    public File getBuildOutputDir() {
        return buildDir;
    }

    public void setBuildOutputDir(File buildDir) {
        this.buildDir = buildDir;
    }

    @Internal
    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    @Internal
    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    @Internal
    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs != null ? jvmArgs : Collections.emptyList();
    }

    @Internal
    public List<String> getAppArgs() {
        return appArgs;
    }

    public void setAppArgs(List<String> appArgs) {
        this.appArgs = appArgs != null ? appArgs : Collections.emptyList();
    }

    @Internal
    public Map<File, File> getPendingSyncs() {
        return pendingSyncs;
    }

    public void setPendingSyncs(Map<File, File> pendingSyncs) {
        this.pendingSyncs = pendingSyncs != null && !pendingSyncs.isEmpty()
                ? new LinkedHashMap<>(pendingSyncs)
                : Collections.emptyMap();
    }

    @Internal
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @TaskAction
    public void generate() {
        if (targetJar != null && !targetJar.exists()) {
            throw new GradleException("[Remold] Target launch JAR does not exist; cannot launch application: "
                    + targetJar.getAbsolutePath());
        }
        RemoldConfigResolver.syncAll(pendingSyncs);
        File remoldDir = new File(buildDir, "remold");
        remoldDir.mkdirs();

        File argsFile = new File(remoldDir, "remold.args");
        List<String> lines = new ArrayList<>(jvmArgs);
        lines.add("-cp");
        lines.add(getRuntimeClasspath().getAsPath());
        lines.add(mainClass);
        lines.addAll(appArgs);

        try (BufferedWriter writer = Files.newBufferedWriter(argsFile.toPath(), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                if (line.contains(" ") || line.contains("\t")) {
                    writer.write("\"" + line.replace("\\", "\\\\") + "\"\n");
                } else {
                    writer.write(line + "\n");
                }
            }
        } catch (IOException e) {
            throw new GradleException("[Remold] Failed to write args file: " + argsFile.getAbsolutePath(), e);
        }

        File batFile = new File(buildDir, "run-remold.bat");
        String batContent = "@echo off\r\nset \"JAVA_EXE=" + executable
                + "\"\r\nif not exist \"%JAVA_EXE%\" set \"JAVA_EXE=java\"\r\n\"%JAVA_EXE%\" @\"%~dp0remold\\remold.args\" %*\r\n";
        try {
            Files.write(batFile.toPath(), batContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GradleException("[Remold] Failed to write bat script: " + batFile.getAbsolutePath(), e);
        }

        File shFile = new File(buildDir, "run-remold.sh");
        String shContent = "#!/usr/bin/env bash\nDIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"\nJAVA_EXE=\""
                + executable
                + "\"\nif [ ! -f \"$JAVA_EXE\" ]; then\n    JAVA_EXE=\"java\"\nfi\nexec \"$JAVA_EXE\" \"@$DIR/remold/remold.args\" \"$@\"\n";
        try {
            Files.write(shFile.toPath(), shContent.getBytes(StandardCharsets.UTF_8));
            shFile.setExecutable(true, false);
        } catch (Exception ignored) {
        }

        getLogger().lifecycle("[Remold] Generated native launch script:");
        getLogger().lifecycle("  - Windows: " + batFile.getAbsolutePath());
        getLogger().lifecycle("  - Linux/macOS: " + shFile.getAbsolutePath());
        getLogger().lifecycle("  - Args file: " + argsFile.getAbsolutePath());
        getLogger().lifecycle("[Remold] Run the script directly to launch the application without Gradle daemon overhead.");
    }
}
