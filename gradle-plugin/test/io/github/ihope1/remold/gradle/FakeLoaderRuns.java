package io.github.ihope1.remold.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.provider.ListProperty;

/**
 * Test doubles for the loader run containers. Fabric Loom, ForgeGradle and NeoForge
 * ModDevGradle expose different run-config APIs, so the plugin injects through
 * {@code vmArg(String)}, {@code jvmArg(String)} or {@code getJvmArguments()} reflectively.
 */
public final class FakeLoaderRuns {

    private FakeLoaderRuns() {}

    /** Container lookalike: exposes the single method the plugin calls reflectively. */
    public static final class Runs {
        private final Map<String, Object> runs = new LinkedHashMap<>();

        public Runs add(String name, Object run) {
            runs.put(name, run);
            return this;
        }

        public Object findByName(String name) {
            return runs.get(name);
        }
    }

    /** Fabric Loom style: {@code vmArg(String)}. */
    public static final class VmArgRun {
        public final List<String> args = new ArrayList<>();

        public void vmArg(String arg) {
            args.add(arg);
        }
    }

    /** ForgeGradle style: {@code jvmArg(String)}. */
    public static final class JvmArgRun {
        public final List<String> args = new ArrayList<>();

        public void jvmArg(String arg) {
            args.add(arg);
        }
    }

    /** NeoForge ModDevGradle style: {@code getJvmArguments()} list property. */
    public static final class JvmListRun {
        private final ListProperty<String> jvmArguments;

        public JvmListRun(ListProperty<String> jvmArguments) {
            this.jvmArguments = jvmArguments;
        }

        public ListProperty<String> getJvmArguments() {
            return jvmArguments;
        }
    }
}
