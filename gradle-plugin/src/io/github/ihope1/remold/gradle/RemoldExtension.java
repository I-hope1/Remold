package io.github.ihope1.remold.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

/**
 * Gradle DSL extension for Remold hot-reloading configuration.
 *
 * <p>Example usage in {@code build.gradle.kts}:
 * <pre>{@code
 * plugins {
 *     id("io.github.i-hope1.remold") version "0.2.8"
 * }
 * remold {
 *     version = "0.2.8"
 *     plugins("arc", "mixin")
 *     debug = true
 * }
 * }</pre>
 */
public class RemoldExtension {

    private String version;
    private String agentJar;
    private String jbrPath;
    private boolean debug = false;
    private int debugPort = 5005;
    private String mainClass;
    private Object jar;
    private final List<Object> args = new ArrayList<>();
    private final List<File> watchDirs = new ArrayList<>();
    private final List<String> extraJvmArgs = new ArrayList<>();
    private Integer fileShakeMs;
    private Integer assetShakeMs;
    private final List<Object> dependsOn = new ArrayList<>();
    private Object deployDir;
    private Object modsDir;
    private Boolean autoDeploy;
    private String buildMode;
    private final List<String> plugins = new ArrayList<>();
    private final List<Object> pluginJars = new ArrayList<>();
    private final List<Object> attachTargets = new ArrayList<>();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAgentJar() {
        return agentJar;
    }

    public void setAgentJar(String agentJar) {
        this.agentJar = agentJar;
    }

    public String getJbrPath() {
        return jbrPath;
    }

    public void setJbrPath(String jbrPath) {
        this.jbrPath = jbrPath;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public int getDebugPort() {
        return debugPort;
    }

    public void setDebugPort(int debugPort) {
        this.debugPort = debugPort;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public Object getJar() {
        return jar;
    }

    public void setJar(Object jar) {
        this.jar = jar;
    }

    public void jar(Object target) {
        this.jar = target;
    }

    public List<Object> getArgs() {
        return args;
    }

    public void setArgs(List<?> args) {
        this.args.clear();
        if (args != null) this.args.addAll(args);
    }

    public void args(Object... newArgs) {
        if (newArgs != null) this.args.addAll(Arrays.asList(newArgs));
    }

    public List<File> getWatchDirs() {
        return watchDirs;
    }

    public void setWatchDirs(List<File> watchDirs) {
        this.watchDirs.clear();
        if (watchDirs != null) this.watchDirs.addAll(watchDirs);
    }

    public void watchDir(File dir) {
        if (dir != null) this.watchDirs.add(dir);
    }

    /**
     * Accepts a {@link File} or a path string.
     *
     * <p>Script-plugin users wrote {@code watchDirs = ["build/classes"]} with strings; the typed
     * {@link #watchDir(File)} overload cannot take those, so this one keeps that syntax working.</p>
     */
    public void watchDir(Object dir) {
        if (dir == null) return;
        if (dir instanceof File) {
            this.watchDirs.add((File) dir);
        } else {
            this.watchDirs.add(new File(dir.toString()));
        }
    }

    public List<String> getExtraJvmArgs() {
        return extraJvmArgs;
    }

    public void setExtraJvmArgs(List<String> extraJvmArgs) {
        this.extraJvmArgs.clear();
        if (extraJvmArgs != null) this.extraJvmArgs.addAll(extraJvmArgs);
    }

    public void extraJvmArgs(String... args) {
        if (args != null) this.extraJvmArgs.addAll(Arrays.asList(args));
    }

    public Integer getFileShakeMs() {
        return fileShakeMs;
    }

    public void setFileShakeMs(Integer fileShakeMs) {
        this.fileShakeMs = fileShakeMs;
    }

    public Integer getAssetShakeMs() {
        return assetShakeMs;
    }

    public void setAssetShakeMs(Integer assetShakeMs) {
        this.assetShakeMs = assetShakeMs;
    }

    public List<Object> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<?> dependsOn) {
        this.dependsOn.clear();
        if (dependsOn != null) this.dependsOn.addAll(dependsOn);
    }

    public void dependsOn(Object... tasks) {
        if (tasks != null) this.dependsOn.addAll(Arrays.asList(tasks));
    }

    public Object getDeployDir() {
        return deployDir;
    }

    public void setDeployDir(Object deployDir) {
        this.deployDir = deployDir;
    }

    public void deployDir(Object dir) {
        this.deployDir = dir;
        this.autoDeploy = true;
    }

    public Object getModsDir() {
        return modsDir;
    }

    public void setModsDir(Object modsDir) {
        this.modsDir = modsDir;
    }

    public void modsDir(Object dir) {
        this.modsDir = dir;
        this.autoDeploy = true;
    }

    public Boolean getAutoDeploy() {
        return autoDeploy;
    }

    public void setAutoDeploy(Boolean autoDeploy) {
        this.autoDeploy = autoDeploy;
    }

    public List<String> getPlugins() {
        return plugins;
    }

    public void plugins(String... p) {
        if (p != null) this.plugins.addAll(Arrays.asList(p));
    }

    /**
     * Script-plugin compatible form: {@code plugins("arc")}, {@code plugins(["arc", "mixin"])}.
     *
     * <p>The script implementation flattened one level, so {@code plugins(["arc"])} must not add a
     * {@code List} object as a plugin name.</p>
     */
    public void plugins(Object... p) {
        if (p == null) return;
        for (Object item : p) {
            if (item == null) continue;
            if (item instanceof Collection) {
                for (Object nested : (Collection<?>) item) {
                    if (nested != null) this.plugins.add(nested.toString());
                }
            } else {
                this.plugins.add(item.toString());
            }
        }
    }

    /** Property-style assignment: {@code plugins = ["arc"]}. */
    public void setPlugins(List<String> plugins) {
        this.plugins.clear();
        if (plugins != null) this.plugins.addAll(plugins);
    }

    public List<Object> getPluginJars() {
        return pluginJars;
    }

    public void pluginJar(Object file) {
        if (file != null) this.pluginJars.add(file);
    }

    public String getBuildMode() {
        return buildMode;
    }

    public void setBuildMode(String buildMode) {
        this.buildMode = buildMode;
    }

    public void buildMode(String buildMode) {
        this.buildMode = buildMode;
    }

    public List<Object> getAttachTargets() {
        return attachTargets;
    }

    public void attach(Object... targets) {
        if (targets == null) return;
        for (Object target : targets) {
            if (target instanceof Collection<?>) {
                for (Object item : (Collection<?>) target) {
                    if (item != null) this.attachTargets.add(item);
                }
            } else if (target != null) {
                this.attachTargets.add(target);
            }
        }
    }

    public void attach(TaskProvider<?> target) {
        if (target != null) this.attachTargets.add(target);
    }

    public void attach(Task target) {
        if (target != null) this.attachTargets.add(target);
    }

    public void attach(String taskName) {
        if (taskName != null) this.attachTargets.add(taskName);
    }
}
