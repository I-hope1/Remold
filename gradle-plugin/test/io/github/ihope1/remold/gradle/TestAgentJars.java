package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Builders for agent-jar fixtures used by the plugin tests.
 *
 * <p>Two shapes matter: {@link #current} (the {@code remold.AgentBootstrap} trampoline plus the API
 * the plugin relies on) and {@link #legacy} (the pre-trampoline jar that the released {@code v0.2.8}
 * actually ships — same version label, older structure). The distinction is what the resolver's
 * structural check keys on, so tests need both.</p>
 */
final class TestAgentJars {

    private TestAgentJars() {
    }

    /** A jar shaped like the current build. */
    static File current(File dir, String version, String fileName) throws IOException {
        return write(dir, fileName, version, RemoldAgentJarCheck.EXPECTED_ENTRY_CLASS, true);
    }

    /** A jar shaped like the released v0.2.8 artifact: legacy entry point, no current API marker. */
    static File legacy(File dir, String version, String fileName) throws IOException {
        return write(dir, fileName, version, RemoldAgentJarCheck.LEGACY_ENTRY_CLASS, false);
    }

    /**
     * Writes a jar with an explicit entry point and marker presence.
     *
     * <p>Tests need the four combinations (current/legacy entry × marker present/absent), so the
     * generic form is exposed instead of only the two named shapes.</p>
     */
    static File write(File dir, String fileName, String version, String entryClass, boolean withMarker)
            throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (entryClass != null) {
            attrs.putValue("Agent-Class", entryClass);
            attrs.putValue("Premain-Class", entryClass);
        }
        if (version != null) attrs.putValue("Remold-Version", version);

        dir.mkdirs();
        File jar = new File(dir, fileName);
        Set<String> written = new LinkedHashSet<>();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            if (entryClass != null) {
                String path = entryClass.replace('.', '/') + ".class";
                written.add(path);
                writeEntry(out, path, classBytes(withMarker));
            }
            for (String path : List.of("remold/RemoldAgent.class", "remold/LambdaAligner.class")) {
                if (!written.add(path)) continue;
                writeEntry(out, path, classBytes("remold/LambdaAligner.class".equals(path) && withMarker));
            }
        }
        return jar;
    }

    private static void writeEntry(JarOutputStream out, String path, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(path));
        out.write(bytes);
        out.closeEntry();
    }

    /**
     * Minimal class-like bytes: the structural check looks for UTF-8 constants, never parses
     * bytecode, so a fixture only needs the marker present or absent.
     */
    private static byte[] classBytes(boolean withMarker) {
        String body = "fake-class-body " + (withMarker ? "lastResultFor" : "no-such-symbol");
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
