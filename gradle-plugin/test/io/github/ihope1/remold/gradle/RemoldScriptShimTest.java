package io.github.ihope1.remold.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code apply from:} entry point must stay a thin shim.
 *
 * <p>Regression target: the shim has to put the binary plugin on the build classpath and apply it
 * <b>by class</b>. Applying by plugin id does not work here — the plugin registry is fixed when the
 * script is compiled, so a classpath added by the script's own {@code buildscript} block is
 * invisible to a string-id lookup (verified empirically: it fails with "plugin not found").
 * If someone "simplifies" the shim back to {@code apply plugin: 'io.github.i-hope1.remold'},
 * this test fails because no Remold task is registered.</p>
 *
 * <p>Second target: the DSL surface the old all-in-one script exposed must still be accepted by the
 * binary plugin's extension ({@code plugins('arc')}, {@code watchDirs = [...]}). Those forms come
 * from Groovy scripts and would silently break on a typed-only Java extension.</p>
 */
class RemoldScriptShimTest {

	@Test
	void groovyShimAppliesPluginAndAcceptsScriptDsl(@TempDir Path projectDir) throws IOException {
		String pluginJar = System.getProperty("remold.pluginJar");
		String shim = System.getProperty("remold.shimGroovy");
		assertNotNull(pluginJar, "test must be wired with -Dremold.pluginJar (see gradle-plugin/build.gradle)");
		assertNotNull(shim, "test must be wired with -Dremold.shimGroovy");

		write(projectDir.resolve("settings.gradle"), "rootProject.name = 'shim-groovy'\n");
		write(projectDir.resolve("build.gradle"), String.join("\n",
			"plugins { id 'java' }",
			"apply from: '" + shim.replace("\\", "/") + "'",
			"remold {",
			"    plugins('arc')",
			"    watchDirs = ['build/classes']",
			"}",
			"tasks.register('dumpPlugins') {",
			"    doLast { println 'PLUGINS=' + remold.plugins }",
			"}",
			""));

		BuildResult result = run(projectDir, pluginJar, "tasks", "--all");
		String output = result.getOutput();
		assertTrue(output.contains("runRemold"),
			"shim must apply the plugin by class so its tasks exist; output was:\n" + output);
		assertTrue(output.contains("generateRemoldScript"),
			"deploy/generate tasks must be registered through the shim");

		BuildResult dumped = run(projectDir, pluginJar, "dumpPlugins");
		assertTrue(dumped.getOutput().contains("PLUGINS=[arc]"),
			"script-style plugins('arc') must reach the plugin extension; output was:\n" + dumped.getOutput());
	}

	@Test
	void kotlinShimAppliesPlugin(@TempDir Path projectDir) throws IOException {
		String pluginJar = System.getProperty("remold.pluginJar");
		String shim = System.getProperty("remold.shimKts");
		assertNotNull(pluginJar);
		assertNotNull(shim);

		write(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"shim-kts\"\n");
		write(projectDir.resolve("build.gradle.kts"), String.join("\n",
			"plugins { java }",
			"apply(from = \"" + shim.replace("\\", "/") + "\")",
			""));

		BuildResult result = run(projectDir, pluginJar, "tasks", "--all");
		assertTrue(result.getOutput().contains("runRemold"),
			"kotlin shim must apply the plugin; output was:\n" + result.getOutput());
	}

	private static BuildResult run(Path projectDir, String pluginJar, String... args) {
		String[] all = new String[args.length + 2];
		all[0] = "-Premold.pluginJar=" + pluginJar;
		all[1] = "--console=plain";
		System.arraycopy(args, 0, all, 2, args.length);
		return GradleRunner.create()
			.withProjectDir(projectDir.toFile())
			.withArguments(all)
			.build();
	}

	private static void write(Path file, String content) throws IOException {
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}
}
