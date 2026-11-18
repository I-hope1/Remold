package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Structural validation of the agent JAR that a build is about to launch.
 *
 * <p>Why a version check alone is not enough: a detached coordinate resolves to whatever the
 * repository happens to publish for that version, and a stale local copy can carry the <i>same</i>
 * version label while being a structurally older artifact. That case used to be silent — the
 * resolver compared {@code Remold-Version}, saw {@code 0.2.8 == 0.2.8}, and launched a jar whose
 * entry point and API are not what this plugin needs. The run then looked healthy (hot swap
 * "succeeded"), while anything that reflected into the agent failed with
 * {@code NoSuchMethodException}. False-green is the worst possible outcome, so the shape of the
 * jar is now checked explicitly before it is used.</p>
 *
 * <p>Two kinds of problem are distinguished:</p>
 * <ul>
 *   <li><b>broken</b> — the archive is unreadable, or the manifest names an entry point whose class
 *       is not inside the archive. Nothing can launch such a jar.</li>
 *   <li><b>stale</b> — the entry point is a legacy one ({@code remold.RemoldAgent} instead of the
 *       {@code remold.AgentBootstrap} trampoline), or a capability that this plugin depends on is
 *       missing from the classes. A jar like that launches but does not behave like the release the
 *       build asked for.</li>
 * </ul>
 *
 * <p>Everything here is pure inspection: no Gradle types, no class loading, no mutation.</p>
 */
public final class RemoldAgentJarCheck {

    /** The entry point the current agent JAR declares in {@code Agent-Class} / {@code Premain-Class}. */
    public static final String EXPECTED_ENTRY_CLASS = "remold.AgentBootstrap";

    /** Legacy entry point: the pre-trampoline agent. Still loadable, but not this plugin's contract. */
    public static final String LEGACY_ENTRY_CLASS = "remold.RemoldAgent";

    /**
     * Classes the agent must contain for this plugin's launch path, and (for one of them) a marker
     * string that must appear in the class file.
     *
     * <p>The marker is how "same version label, older API" is caught without parsing bytecode: the
     * constant pool of a class always contains the names of the methods it declares, so a missing
     * method means a missing UTF-8 entry. {@code LambdaAligner.lastResultFor} is the concrete symbol
     * that a stale agent fails to provide (the observed real-world symptom was a probe reflecting on
     * it and getting {@code NoSuchMethodException} while hot swap still reported success).</p>
     */
    private static final Map<String, String> REQUIRED_CLASSES;

    static {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("remold/AgentBootstrap.class", null);
        required.put("remold/RemoldAgent.class", null);
        required.put("remold/LambdaAligner.class", "lastResultFor");
        REQUIRED_CLASSES = Collections.unmodifiableMap(required);
    }

    private RemoldAgentJarCheck() {
    }

    /**
     * Inspects {@code agentJar} and returns the human-readable problems found, or an empty list when
     * the jar looks like a current agent build.
     *
     * @param agentJar candidate agent jar (may be {@code null} or missing → reported as a problem)
     */
    public static List<String> problems(File agentJar) {
        List<String> problems = new ArrayList<>();
        if (agentJar == null || !agentJar.isFile()) {
            problems.add("agent jar does not exist: " + (agentJar == null ? "<null>" : agentJar.getAbsolutePath()));
            return problems;
        }
        try (JarFile jar = new JarFile(agentJar)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null || manifest.getMainAttributes() == null) {
                problems.add("agent jar has no manifest: " + agentJar.getAbsolutePath());
                return problems;
            }
            Attributes attrs = manifest.getMainAttributes();
            String agentClass = attrs.getValue("Agent-Class");
            String premainClass = attrs.getValue("Premain-Class");
            String entryClass = agentClass != null ? agentClass : premainClass;
            if (entryClass == null || entryClass.trim().isEmpty()) {
                problems.add("manifest declares neither Agent-Class nor Premain-Class, so nothing can be"
                        + " launched from this jar");
            } else {
                entryClass = entryClass.trim();
                String entryPath = entryClass.replace('.', '/') + ".class";
                if (jar.getJarEntry(entryPath) == null) {
                    problems.add("manifest entry point " + entryClass + " is not inside the jar (expected "
                            + entryPath + ")");
                }
                if (!EXPECTED_ENTRY_CLASS.equals(entryClass)) {
                    String hint = LEGACY_ENTRY_CLASS.equals(entryClass)
                            ? " (legacy pre-trampoline entry point: this build expects "
                                + EXPECTED_ENTRY_CLASS + ")"
                            : "";
                    problems.add("manifest entry point is " + entryClass + ", expected "
                            + EXPECTED_ENTRY_CLASS + hint);
                }
            }
            for (Map.Entry<String, String> required : REQUIRED_CLASSES.entrySet()) {
                JarEntry entry = jar.getJarEntry(required.getKey());
                if (entry == null) {
                    problems.add("missing class " + required.getKey());
                    continue;
                }
                String marker = required.getValue();
                if (marker != null && !containsUtf8Constant(jar, entry, marker)) {
                    problems.add("class " + required.getKey() + " has no '" + marker
                            + "' method/constant: this agent predates the API the plugin relies on");
                }
            }
            String version = RemoldConfigResolver.readAgentVersion(agentJar);
            if (version == null) {
                problems.add("the jar carries no Remold-Version/Implementation-Version metadata, so its"
                        + " release cannot be verified");
            }
        } catch (IOException | RuntimeException e) {
            problems.add("agent jar is unreadable (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
        return problems;
    }

    /**
     * Whether the constant pool of {@code entry} contains {@code marker} as a UTF-8 constant.
     *
     * <p>A declared method name always ends up in the constant pool, so a documented-name search is
     * enough to answer "does this class declare it". It is a heuristic (a string literal would also
     * match), and that direction is deliberate: the check must never reject a good jar.</p>
     */
    private static boolean containsUtf8Constant(JarFile jar, JarEntry entry, String marker) throws IOException {
        byte[] needle = marker.getBytes(StandardCharsets.UTF_8);
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] data = in.readAllBytes();
            return indexOf(data, needle) >= 0;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        final int last = haystack.length - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** A single message listing every problem plus how to override the jar explicitly. */
    public static String failureMessage(File agentJar, List<String> problems, String configuredVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("Refusing to launch a structurally incompatible remold-agent.jar (configured version: ")
                .append(configuredVersion == null ? "<unknown>" : configuredVersion)
                .append(").\n  jar: ")
                .append(agentJar == null ? "<none>" : agentJar.getAbsolutePath())
                .append('\n');
        for (String problem : problems) {
            sb.append("  - ").append(problem).append('\n');
        }
        sb.append("A jar with the right version label but an older shape makes hot swap look healthy while"
                + " reflective entry points fail (NoSuchMethodException), i.e. a false green.\n")
                .append("Fix: point the build at the matching build of this repository's agent, e.g.\n")
                .append("  ./gradlew :core:shadowJar\n")
                .append("  -Premold.agentJar=<absolute path to core/build/libs/remold-agent.jar>\n")
                .append("If you intentionally pin an older agent release, set -Premold.agentJarCheck=warn to"
                        + " downgrade this error to a warning.");
        return sb.toString();
    }

    /** Every archive entry name, for diagnostics. Never used as an assertion. */
    static List<String> entryNames(File agentJar) {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(agentJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        } catch (IOException ignored) {
            return names;
        }
        return names;
    }
}
