package io.github.ihope1.remold.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildTriggerWriter} 的写入契约：协议字段、原子替换、临时文件清理、失败不抛异常。
 */
class BuildTriggerWriterTest {

    @Test
    void writesMarkerWithProtocolFields(@TempDir Path tmp) throws Exception {
        File marker = BuildTriggerWriter.markerFile(tmp.resolve("build").toFile());
        BuildTriggerWriter.write(marker, "classes", null);

        assertTrue(marker.isFile(), "marker must be written");
        String content = Files.readString(marker.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.startsWith("remold-build-trigger v1\n"), content);
        assertTrue(content.contains("\nbuild_end_millis="), content);
        assertTrue(content.contains("\nbuild_id=classes-"), content);
        assertTrue(content.contains("\ntask=classes"), content);

        try (Stream<Path> files = Files.list(marker.getParentFile().toPath())) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().contains(".tmp-")),
                    "temporary file must not survive the atomic move");
        }
    }

    @Test
    void secondWriteReplacesTheSameFile(@TempDir Path tmp) throws Exception {
        File marker = BuildTriggerWriter.markerFile(tmp.resolve("build").toFile());
        BuildTriggerWriter.write(marker, "classes", null);
        String first = Files.readString(marker.toPath(), StandardCharsets.UTF_8);

        Thread.sleep(2L);
        BuildTriggerWriter.write(marker, "classes", null);
        String second = Files.readString(marker.toPath(), StandardCharsets.UTF_8);

        assertFalse(first.equals(second), "each build must produce a distinct build_id/end timestamp");
        try (Stream<Path> files = Files.list(marker.getParentFile().toPath())) {
            assertTrue(files.filter(p -> p.getFileName().toString().equals("build-trigger")).count() == 1);
        }
    }

    @Test
    void writeFailureIsSwallowed(@TempDir Path tmp) throws Exception {
        // Make the parent path a regular file so createDirectories() must fail.
        Path blocking = tmp.resolve("blocked");
        Files.writeString(blocking, "not a directory");
        File marker = new File(blocking.toFile(), "remold/build-trigger");

        BuildTriggerWriter.write(marker, "classes", null); // must not throw
        assertFalse(marker.isFile());
    }
}
