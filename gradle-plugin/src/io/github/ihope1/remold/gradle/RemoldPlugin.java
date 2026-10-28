package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Remold Gradle 插件：把仓库根的脚本插件（{@code remold.gradle} / {@code remold.gradle.kts}）从插件 jar 中展开到
 * {@code <rootDir>/.gradle/remold/scripts/} 后应用。
 *
 * <p>这样做的好处是「插件坐标」与「脚本实现」只有一份真相：脚本继续按文件维护、可单独下载调试，
 * 而消费者可以用 {@code plugins { id("io.github.i-hope1.remold") version "..." }} 获得 Gradle 原生的版本锁定、
 * 依赖缓存与离线构建能力。
 *
 * <p>脚本选择依据消费方构建文件的扩展名：Kotlin DSL 工程用 {@code remold.gradle.kts}，Groovy 工程用 {@code remold.gradle}。
 */
public final class RemoldPlugin implements Plugin<Project> {

    private static final String GROOVY_SCRIPT = "/remold/remold.gradle";
    private static final String KOTLIN_SCRIPT = "/remold/remold.gradle.kts";

    @Override
    public void apply(Project project) {
        File buildFile = project.getBuildFile();
        boolean kotlinDsl = (buildFile != null && buildFile.getName().endsWith(".kts"))
                || new File(project.getProjectDir(), "build.gradle.kts").isFile();
        String resource = kotlinDsl ? KOTLIN_SCRIPT : GROOVY_SCRIPT;
        String fileName = kotlinDsl ? "remold.gradle.kts" : "remold.gradle";
        File script = materialize(project, resource, fileName);
        Map<String, Object> applyArgs = Collections.<String, Object>singletonMap("from", script);
        project.apply(applyArgs);
    }

    private static File materialize(Project project, String resource, String fileName) {
        File dir = new File(project.getRootDir(), ".gradle/remold/scripts");
        File target = new File(dir, fileName);
        try (InputStream in = RemoldPlugin.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new GradleException("[Remold] 插件 jar 内缺少脚本资源: " + resource);
            }
            byte[] bytes = in.readAllBytes();
            if (target.isFile() && target.length() == bytes.length && Arrays.equals(Files.readAllBytes(target.toPath()), bytes)) {
                return target;
            }
            Files.createDirectories(dir.toPath());
            Path part = new File(dir, fileName + "." + System.nanoTime() + ".part").toPath();
            Files.write(part, bytes);
            try {
                Files.move(part, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(part, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new GradleException("[Remold] 无法展开脚本插件到 " + target.getAbsolutePath(), e);
        }
    }
}
