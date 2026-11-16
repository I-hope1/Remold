package io.github.ihope1.remold.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

import org.gradle.api.logging.Logger;

/**
 * Writes the build-completion marker consumed by the Remold agent.
 *
 * <p>The marker is a small text file (<code>&lt;buildDir&gt;/remold/build-trigger</code>) written from the
 * {@code classes} task's {@code doLast}. The agent watches exactly that file and, on a valid marker,
 * triggers a class batch immediately instead of waiting for the {@code fileShakeMs} fallback window.</p>
 *
 * <p><b>Atomicity</b>: the content is written to a sibling temporary file first and then moved over the
 * target with {@link StandardCopyOption#ATOMIC_MOVE} (falling back to
 * {@link StandardCopyOption#REPLACE_EXISTING} when the file system cannot do an atomic move), so the
 * agent never reads a partial marker.</p>
 *
 * <p><b>Configuration cache</b>: the {@code doLast} action only captures a {@link File} and a
 * {@link String}; this class holds no {@code Project}/{@code Task} references.</p>
 */
public final class BuildTriggerWriter {

    /** Marker format version line; must match the agent-side parser. */
    static final String MAGIC = "remold-build-trigger v1";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private BuildTriggerWriter() {
    }

    /** The marker file for the given Gradle build directory. */
    public static File markerFile(File buildDir) {
        return new File(new File(buildDir, "remold"), "build-trigger");
    }

    /**
     * Writes a marker for {@code taskName} using the current wall clock.
     *
     * <p>Failures are reported as a warning and never fail the build: the agent keeps working with the
     * regular fallback window when a marker cannot be written.</p>
     */
    public static void write(File markerFile, String taskName, Logger logger) {
        write(markerFile, taskName, System.currentTimeMillis(), logger);
    }

    static void write(File markerFile, String taskName, long endMillis, Logger logger) {
        if (markerFile == null) return;
        File dir = markerFile.getParentFile();
        try {
            if (dir != null) {
                Files.createDirectories(dir.toPath());
            }
            long seq = SEQUENCE.incrementAndGet();
            String buildId = taskName + "-" + endMillis + "-" + Long.toHexString(seq);
            String content = MAGIC
                    + "\nbuild_end_millis=" + endMillis
                    + "\nbuild_id=" + buildId
                    + "\ntask=" + taskName
                    + "\n";
            File tmp = new File(dir, markerFile.getName() + ".tmp-" + seq);
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), markerFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), markerFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.warn("[Remold] Failed to write build-trigger marker "
                        + markerFile.getAbsolutePath() + ": " + t);
            }
        }
    }
}
