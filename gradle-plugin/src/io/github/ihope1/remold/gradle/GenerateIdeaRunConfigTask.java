package io.github.ihope1.remold.gradle;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Configuration-cache-safe task that generates {@code .idea/runConfigurations/Run_with_Remold.xml}
 * and synchronizes existing Fabric Loom IDEA run configurations.
 */
@DisableCachingByDefault(because = "Generates IDE run configuration XML under .idea outside buildDir")
public abstract class GenerateIdeaRunConfigTask extends DefaultTask {

    private File rootDir;
    private String projectName;
    private String mainClass;
    private String executableHome;
    private File targetJar;
    private String buildMode;
    private List<String> jvmArgs = Collections.emptyList();
    private List<String> appArgs = Collections.emptyList();
    private boolean shouldAutoDeploy;
    private boolean loom;
    private String missingConfigMessage;
    private boolean skipWhenUnconfigured;
    private Map<File, File> pendingSyncs = Collections.emptyMap();

    @Internal
    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    @Internal
    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @Internal
    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    @Internal
    public String getExecutableHome() {
        return executableHome;
    }

    public void setExecutableHome(String executableHome) {
        this.executableHome = executableHome;
    }

    @Internal
    public File getTargetJar() {
        return targetJar;
    }

    public void setTargetJar(File targetJar) {
        this.targetJar = targetJar;
    }

    @Internal
    public String getBuildMode() {
        return buildMode;
    }

    public void setBuildMode(String buildMode) {
        this.buildMode = buildMode;
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
    public boolean isShouldAutoDeploy() {
        return shouldAutoDeploy;
    }

    public void setShouldAutoDeploy(boolean shouldAutoDeploy) {
        this.shouldAutoDeploy = shouldAutoDeploy;
    }

    @Internal
    public boolean isLoom() {
        return loom;
    }

    public void setLoom(boolean loom) {
        this.loom = loom;
    }

    @Internal
    public String getMissingConfigMessage() {
        return missingConfigMessage;
    }

    public void setMissingConfigMessage(String missingConfigMessage) {
        this.missingConfigMessage = missingConfigMessage;
    }

    @Internal
    public boolean isSkipWhenUnconfigured() {
        return skipWhenUnconfigured;
    }

    public void setSkipWhenUnconfigured(boolean skipWhenUnconfigured) {
        this.skipWhenUnconfigured = skipWhenUnconfigured;
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

    @TaskAction
    public void generate() {
        if (missingConfigMessage != null) {
            if (skipWhenUnconfigured) {
                getLogger().info("[Remold] Skipping Run_with_Remold.xml generation: " + missingConfigMessage);
                return;
            }
            throw new GradleException(missingConfigMessage);
        }
        RemoldConfigResolver.syncAll(pendingSyncs);
        RemoldResolvedConfig conf = new RemoldResolvedConfig(
                mainClass,
                "",
                executableHome,
                null,
                Collections.emptyList(),
                "",
                jvmArgs,
                appArgs,
                null,
                targetJar,
                buildMode
        );
        File xmlFile = IdeaRunConfigGenerator.generate(rootDir, projectName, conf, shouldAutoDeploy, loom);
        if (xmlFile != null) {
            getLogger().lifecycle("[Remold] Generated IntelliJ IDEA run configuration: " + xmlFile.getAbsolutePath());
            getLogger().lifecycle("[Remold] Open this project in IDEA to launch directly with the run/debug configuration.");
        }
    }
}
