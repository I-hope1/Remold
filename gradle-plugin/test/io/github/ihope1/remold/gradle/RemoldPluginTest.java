package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;

class RemoldPluginTest {

    @TempDir
    File tempDir;

    @org.junit.jupiter.api.Test
    void appliesPluginAndRegistersExtensionAndTasks() {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        RemoldExtension ext = (RemoldExtension) project.getExtensions().findByName("remold");
        Assertions.assertNotNull(ext, "remold extension should be registered");

        // Verify task registrations
        Assertions.assertNotNull(project.getTasks().findByName("runRemold"), "runRemold task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("deployArtifact"), "deployArtifact task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("deployMod"), "deployMod task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("generateIdeaRunConfig"), "generateIdeaRunConfig task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("generateRemoldScript"), "generateRemoldScript task should be registered");
        Assertions.assertNotNull(project.getTasks().findByName("startRemold"), "startRemold task should be registered");

        JavaCompile compileJava = (JavaCompile) project.getTasks().getByName("compileJava");
        Assertions.assertTrue(compileJava.getOptions().isDebug(), "compileJava should have debug enabled");
        Assertions.assertEquals("source,lines,vars", compileJava.getOptions().getDebugOptions().getDebugLevel(), "debugLevel should be source,lines,vars");
    }

    @org.junit.jupiter.api.Test
    void extensionDslAndConfigResolution() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        RemoldExtension ext = (RemoldExtension) project.getExtensions().getByName("remold");
        ext.setMainClass("com.example.Main");
        ext.setVersion("0.2.1");
        ext.plugins("arc", "mixin");
        ext.args("arg1", "arg2");
        ext.setDebug(true);
        ext.setDebugPort(5006);

        // Create dummy agent jar locally to satisfy resolution without remote download
        File dummyAgent = new File(tempDir, "remold-agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        RemoldConfigResolver resolver = new RemoldConfigResolver();
        RemoldResolvedConfig conf = resolver.resolve(project, ext);

        Assertions.assertEquals("com.example.Main", conf.getMainClass());
        Assertions.assertEquals(dummyAgent.getName(), conf.getAgentFile().getName());
        Assertions.assertEquals(Arrays.asList("arg1", "arg2"), conf.getAppArgs());
        Assertions.assertTrue(conf.getJvmArgs().stream().anyMatch(a -> a.contains("-agentlib:jdwp") && a.contains("5006")), "JDWP port should be 5006");
        Assertions.assertTrue(conf.getJvmArgs().stream().anyMatch(a -> a.contains("-Dremold.plugins=arc,mixin")), "Plugins arg should be assembled");

        // Verify resolver caching idempotency
        RemoldResolvedConfig cached = resolver.resolve(project, ext);
        Assertions.assertSame(conf, cached, "RemoldConfigResolver should return cached instance");
    }

    @org.junit.jupiter.api.Test
    void autoDeployResolution() {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        RemoldExtension ext = new RemoldExtension();

        Assertions.assertFalse(RemoldConfigResolver.shouldAutoDeploy(project, ext));
        Assertions.assertNull(RemoldConfigResolver.resolveDeployDir(project, ext));

        ext.deployDir(new File(tempDir, "mods"));
        Assertions.assertTrue(RemoldConfigResolver.shouldAutoDeploy(project, ext));
        Assertions.assertEquals(new File(tempDir, "mods").getAbsolutePath(), RemoldConfigResolver.resolveDeployDir(project, ext).getAbsolutePath());
    }

    @org.junit.jupiter.api.Test
    void ideaRunConfigGeneratorPortabilityAndXml() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");

        File dummyAgent = new File(tempDir, "agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        RemoldResolvedConfig conf = new RemoldResolvedConfig(
                "com.example.App",
                "java",
                new File(tempDir, "dummy-jdk").getAbsolutePath(),
                dummyAgent,
                Collections.emptyList(),
                "",
                Arrays.asList("-javaagent:" + dummyAgent.getAbsolutePath()),
                Arrays.asList("hello"),
                project.files(),
                null
        );

        File xml = IdeaRunConfigGenerator.generate(project, conf, false, false);
        Assertions.assertTrue(xml.isFile());
        String content = Files.readString(xml.toPath());
        Assertions.assertTrue(content.contains("type=\"Application\""));
        Assertions.assertTrue(content.contains("MAIN_CLASS_NAME\" value=\"com.example.App\""));
        Assertions.assertTrue(content.contains("$PROJECT_DIR$"), "Paths relative to rootDir should use $PROJECT_DIR$");
    }

    @org.junit.jupiter.api.Test
    void syncLoomRunConfigsFixesDeployArtifactAndDeduplicatesPluginJars() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");

        File ideaRunDir = new File(tempDir, ".idea/runConfigurations");
        Files.createDirectories(ideaRunDir.toPath());
        File clientXml = new File(ideaRunDir, "Minecraft_Client.xml");

        // Existing xml with duplicate/stale plugin jars and deployArtifact
        String existingXml = "<component name=\"ProjectRunConfigurationManager\">\n" +
                "  <configuration default=\"false\" factoryName=\"Application\" name=\"Minecraft Client\" type=\"Application\">\n" +
                "    <option name=\"VM_PARAMETERS\" value=\"-Dremold.plugins=mixin -Dremold.plugin.jars=$PROJECT_DIR$/.gradle/remold/plugins/remold-plugin-mixin-0.2.0.jar\"/>\n" +
                "    <method v=\"2\">\n" +
                "      <option enabled=\"true\" externalProjectPath=\"$PROJECT_DIR$\" name=\"Gradle.BeforeRunTask\" scriptParameters=\"\" tasks=\"deployArtifact\" vmOptions=\"\"/>\n" +
                "    </method>\n" +
                "  </configuration>\n" +
                "</component>\n";
        Files.writeString(clientXml.toPath(), existingXml);

        File dummyAgent = new File(tempDir, "agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        RemoldResolvedConfig conf = new RemoldResolvedConfig(
                "net.fabricmc.devlaunchinjector.Main",
                "java",
                "java",
                dummyAgent,
                Collections.emptyList(),
                "",
                Arrays.asList(
                        "-javaagent:" + dummyAgent.getAbsolutePath(),
                        "-Dremold.plugins=mixin",
                        "-Dremold.plugin.jars=" + tempDir.getAbsolutePath() + "/.gradle/remold/plugins/remold-plugin-mixin-0.2.1.jar",
                        "-Dremold.build_mode=gradle"
                ),
                Collections.emptyList(),
                project.files(),
                null,
                "gradle"
        );

        IdeaRunConfigGenerator.syncLoomRunConfigs(project, conf, ideaRunDir);

        String syncedXml = Files.readString(clientXml.toPath());
        // Verify old 0.2.0 jar is gone, replaced by 0.2.1 jar
        Assertions.assertFalse(syncedXml.contains("0.2.0.jar"), "Stale 0.2.0 jar should be replaced");
        Assertions.assertTrue(syncedXml.contains("0.2.1.jar"), "New 0.2.1 jar should be present");
        Assertions.assertEquals(1, syncedXml.split("-Dremold\\.plugin\\.jars=").length - 1, "There should only be one -Dremold.plugin.jars");

        // Verify deployArtifact was replaced with Make
        Assertions.assertFalse(syncedXml.contains("tasks=\"deployArtifact\""), "Loom beforeRun should not use deployArtifact");
        Assertions.assertTrue(syncedXml.contains("<option name=\"Make\" enabled=\"true\" />"), "Loom beforeRun should use Make");
    }

    @org.junit.jupiter.api.Test
    void classBasedEnvironmentDetectionAndAutoDeployBlocking() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("java");

        RemoldExtension ext = new RemoldExtension();
        ext.setAutoDeploy(true);
        ext.setDeployDir("run/mods");

        // Normal java project: shouldAutoDeploy is true
        Assertions.assertFalse(RemoldConfigResolver.isClassBasedEnvironment(project));
        Assertions.assertTrue(RemoldConfigResolver.shouldAutoDeploy(project, ext));

        // When loom extension is present (Minecraft / Loom environment)
        project.getExtensions().add("loom", new Object());
        Assertions.assertTrue(RemoldConfigResolver.isClassBasedEnvironment(project));
        // In class-based environment, shouldAutoDeploy must be blocked (return false)
        Assertions.assertFalse(RemoldConfigResolver.shouldAutoDeploy(project, ext));
    }

    @org.junit.jupiter.api.Test
    void embeddedScriptResourcesArePresentAndValid() throws Exception {
        try (InputStream groovy = RemoldPlugin.class.getResourceAsStream("/remold/remold.gradle");
             InputStream kotlin = RemoldPlugin.class.getResourceAsStream("/remold/remold.gradle.kts")) {
            Assertions.assertNotNull(groovy, "remold.gradle resource must be bundled in plugin jar");
            Assertions.assertNotNull(kotlin, "remold.gradle.kts resource must be bundled in plugin jar");

            byte[] gBytes = groovy.readAllBytes();
            byte[] kBytes = kotlin.readAllBytes();
            Assertions.assertTrue(gBytes.length > 1000, "remold.gradle should be a non-trivial script");
            Assertions.assertTrue(kBytes.length > 1000, "remold.gradle.kts should be a non-trivial script");
        }
    }

    @org.junit.jupiter.api.Test
    void injectsAgentIntoClientServerAndGameTestRuns() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        FakeLoaderRuns.Runs runs = new FakeLoaderRuns.Runs()
                .add("client", new FakeLoaderRuns.VmArgRun())
                .add("server", new FakeLoaderRuns.VmArgRun())
                .add("clientGameTest", new FakeLoaderRuns.VmArgRun())
                .add("gameTest", new FakeLoaderRuns.VmArgRun());
        RemoldResolvedConfig conf = resolvedConfig(project,
                Collections.singletonList("-javaagent:/opt/remold-agent.jar=build/classes"));

        RemoldPlugin.injectIntoLoaderRuns(project, conf, runs, "TestLoader");

        FakeLoaderRuns.VmArgRun client = (FakeLoaderRuns.VmArgRun) runs.findByName("client");
        FakeLoaderRuns.VmArgRun server = (FakeLoaderRuns.VmArgRun) runs.findByName("server");
        FakeLoaderRuns.VmArgRun clientGameTest = (FakeLoaderRuns.VmArgRun) runs.findByName("clientGameTest");
        FakeLoaderRuns.VmArgRun gameTest = (FakeLoaderRuns.VmArgRun) runs.findByName("gameTest");
        Assertions.assertEquals(1, client.args.size(), "client run should get the agent");
        Assertions.assertEquals(1, server.args.size(), "dedicated-server run must get the agent too");
        Assertions.assertEquals(1, clientGameTest.args.size(), "client gametest run must get the agent too");
        Assertions.assertEquals(1, gameTest.args.size(), "server gametest run must get the agent too");
        Assertions.assertTrue(server.args.get(0).startsWith("-javaagent:"), "server run got a non-agent JVM argument");
        Assertions.assertTrue(clientGameTest.args.get(0).startsWith("-javaagent:"), "gametest run got a non-agent JVM argument");
    }

    @org.junit.jupiter.api.Test
    void injectRunJvmArgsSupportsAllLoaderApis() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();

        FakeLoaderRuns.JvmArgRun forge = new FakeLoaderRuns.JvmArgRun();
        Assertions.assertTrue(RemoldPlugin.injectRunJvmArgs(forge,
                Collections.singletonList("-javaagent:/x/remold-agent.jar")));
        Assertions.assertEquals(1, forge.args.size(), "ForgeGradle jvmArg(String) should receive the argument");

        ListProperty<String> list = project.getObjects().listProperty(String.class);
        FakeLoaderRuns.JvmListRun neoForge = new FakeLoaderRuns.JvmListRun(list);
        Assertions.assertTrue(RemoldPlugin.injectRunJvmArgs(neoForge,
                Collections.singletonList("-Dremold.plugins=mixin")));
        Assertions.assertEquals(Collections.singletonList("-Dremold.plugins=mixin"), list.get(),
                "NeoForge getJvmArguments() list property should receive the argument");

        Assertions.assertFalse(RemoldPlugin.injectRunJvmArgs(new Object(),
                Collections.singletonList("-javaagent:/x")), "unknown run API must not report a successful injection");
    }

    @org.junit.jupiter.api.Test
    void syncLoomRunConfigsInjectsAgentIntoMinecraftServer() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        File ideaRunDir = new File(tempDir, ".idea/runConfigurations");
        Files.createDirectories(ideaRunDir.toPath());
        File serverXml = new File(ideaRunDir, "Minecraft_Server.xml");
        Files.writeString(serverXml.toPath(), "<component name=\"ProjectRunConfigurationManager\">\n"
                + "  <configuration default=\"false\" factoryName=\"Application\" name=\"Minecraft Server\" type=\"Application\">\n"
                + "    <option name=\"VM_PARAMETERS\" value=\"-Dfabric.dli.env=server\"/>\n"
                + "    <method v=\"2\">\n"
                + "      <option enabled=\"true\" externalProjectPath=\"$PROJECT_DIR$\" name=\"Gradle.BeforeRunTask\" scriptParameters=\"\" tasks=\"deployArtifact\" vmOptions=\"\"/>\n"
                + "    </method>\n"
                + "  </configuration>\n"
                + "</component>\n");

        File agent = new File(tempDir, "remold-agent.jar");
        Files.write(agent.toPath(), new byte[2048]);
        RemoldResolvedConfig conf = resolvedConfig(agent, project, Arrays.asList(
                "-javaagent:" + agent.getAbsolutePath() + "=" + tempDir.getAbsolutePath(),
                "-Dremold.plugins=mixin",
                "-Dremold.build_mode=gradle"));

        IdeaRunConfigGenerator.syncLoomRunConfigs(project, conf, ideaRunDir);

        String synced = Files.readString(serverXml.toPath());
        Assertions.assertTrue(synced.contains("remold-agent.jar"), "server config must carry the javaagent");
        Assertions.assertTrue(synced.contains("-Dremold.plugins=mixin"), "server config must carry the plugin selection");
        Assertions.assertFalse(synced.contains("tasks=\"deployArtifact\""), "server config must use the Make before-run step");
        Assertions.assertTrue(synced.contains("<option name=\"Make\" enabled=\"true\" />"));
    }

    @org.junit.jupiter.api.Test
    void syncLoomRunConfigsUpdatesGradleStyleServerVmOptions() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build();
        File ideaRunDir = new File(tempDir, ".idea/runConfigurations");
        Files.createDirectories(ideaRunDir.toPath());
        File serverXml = new File(ideaRunDir, "Minecraft_Server.xml");
        Files.writeString(serverXml.toPath(), "<component name=\"ProjectRunConfigurationManager\">\n"
                + "  <configuration default=\"false\" name=\"Minecraft Server\" type=\"GradleRunConfiguration\" factoryName=\"Gradle\">\n"
                + "    <ExternalSystemSettings>\n"
                + "      <option name=\"vmOptions\" value=\"-Dfabric.dli.env=server\" />\n"
                + "      <option name=\"taskNames\"><list><option value=\"runServer\" /></list></option>\n"
                + "    </ExternalSystemSettings>\n"
                + "    <method v=\"2\" />\n"
                + "  </configuration>\n"
                + "</component>\n");

        File agent = new File(tempDir, "remold-agent.jar");
        Files.write(agent.toPath(), new byte[2048]);
        RemoldResolvedConfig conf = resolvedConfig(agent, project, Arrays.asList(
                "-javaagent:" + agent.getAbsolutePath() + "=" + tempDir.getAbsolutePath(),
                "-Dremold.plugins=mixin"));

        IdeaRunConfigGenerator.syncLoomRunConfigs(project, conf, ideaRunDir);

        String synced = Files.readString(serverXml.toPath());
        Assertions.assertTrue(synced.contains("remold-agent.jar"), "Gradle-style server config vmOptions must carry the agent");
        Assertions.assertTrue(synced.contains("vmOptions"), "the vmOptions option must be preserved");
        Assertions.assertFalse(synced.contains("<option name=\"Make\" enabled=\"true\" />"),
                "Gradle-style configs must not be converted into Make before-run configs");
    }

    @org.junit.jupiter.api.Test
    void resolveForAttachDoesNotRequireMainClassOrJar() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        RemoldExtension ext = (RemoldExtension) project.getExtensions().getByName("remold");
        File dummyAgent = new File(tempDir, "remold-agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        RemoldConfigResolver resolver = new RemoldConfigResolver();
        Assertions.assertThrows(GradleException.class, () -> resolver.resolve(project, ext),
                "strict resolve should still require mainClass or jar");

        RemoldResolvedConfig attachConf = resolver.resolveForAttach(project, ext);
        Assertions.assertNull(attachConf.getMainClass());
        Assertions.assertTrue(attachConf.getJvmArgs().stream()
                .anyMatch(a -> a.startsWith("-javaagent:") && a.contains("remold-agent.jar")));
    }

    @org.junit.jupiter.api.Test
    void attachInjectsJavaExecAndTestWithoutDuplicatesAndFailsClosed() throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(tempDir)
                .build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(RemoldPlugin.class);

        RemoldExtension ext = (RemoldExtension) project.getExtensions().getByName("remold");
        File dummyAgent = new File(tempDir, "remold-agent.jar");
        Files.write(dummyAgent.toPath(), new byte[2048]);

        JavaExec customRun = project.getTasks().create("customRun", JavaExec.class);
        Test testTask = (Test) project.getTasks().getByName("test");
        RemoldResolvedConfig conf = new RemoldConfigResolver().resolveForAttach(project, ext);

        RemoldPlugin.applyAttachTarget(project, ext, conf, "customRun", false);
        RemoldPlugin.applyAttachTarget(project, ext, conf, customRun, false);
        RemoldPlugin.applyAttachTarget(project, ext, conf, testTask, false);

        long runAgentCount = customRun.getJvmArgs().stream()
                .filter(a -> a.startsWith("-javaagent:") && a.contains("remold-agent.jar"))
                .count();
        Assertions.assertEquals(1, runAgentCount, "duplicate attach must not duplicate -javaagent");
        Assertions.assertTrue(testTask.getJvmArgs().stream()
                .anyMatch(a -> a.startsWith("-javaagent:") && a.contains("remold-agent.jar")));

        FakeLoaderRuns.VmArgRun customRunObj = new FakeLoaderRuns.VmArgRun();
        RemoldPlugin.applyAttachTarget(project, ext, conf, customRunObj, false);
        Assertions.assertTrue(customRunObj.args.stream().anyMatch(a -> a.startsWith("-javaagent:")));

        GradleException missingEx = Assertions.assertThrows(GradleException.class,
                () -> RemoldPlugin.applyAttachTarget(project, ext, conf, "doesNotExist", false));
        Assertions.assertTrue(missingEx.getMessage().contains("does not exist"));

        Task compileJava = project.getTasks().getByName("compileJava");
        GradleException nonForkEx = Assertions.assertThrows(GradleException.class,
                () -> RemoldPlugin.applyAttachTarget(project, ext, conf, compileJava, false));
        Assertions.assertTrue(nonForkEx.getMessage().contains("JavaForkOptions"));

        GradleException unsupportedEx = Assertions.assertThrows(GradleException.class,
                () -> RemoldPlugin.applyAttachTarget(project, ext, conf, new Object(), false));
        Assertions.assertTrue(unsupportedEx.getMessage().contains("Unsupported remold.attach target type"));
    }

    @org.junit.jupiter.api.Test
    void standaloneLaunchersSupportLoomExtractionAndRejectUnsupportedModLoaders() {
        Project plain = ProjectBuilder.builder().withProjectDir(tempDir).build();
        plain.getPlugins().apply("java");
        Assertions.assertDoesNotThrow(() -> RemoldPlugin.ensureStandaloneLauncherSupported(plain, "startRemold", true));
        Assertions.assertDoesNotThrow(() -> RemoldPlugin.ensureStandaloneLauncherSupported(plain, "runRemold", false));

        Project loomWithoutRuns = ProjectBuilder.builder().withProjectDir(tempDir).build();
        loomWithoutRuns.getPlugins().apply("java");
        loomWithoutRuns.getExtensions().add("loom", new Object());
        GradleException noRunEx = Assertions.assertThrows(GradleException.class,
                () -> RemoldPlugin.ensureStandaloneLauncherSupported(loomWithoutRuns, "startRemold", true));
        Assertions.assertTrue(noRunEx.getMessage().contains("No Loom 'runClient'/'runServer' JavaExec task"));

        Project loomWithRunClient = ProjectBuilder.builder().withProjectDir(tempDir).build();
        loomWithRunClient.getPlugins().apply("java");
        loomWithRunClient.getExtensions().add("loom", new Object());
        loomWithRunClient.getTasks().register("runClient", org.gradle.api.tasks.JavaExec.class);
        Assertions.assertDoesNotThrow(
                () -> RemoldPlugin.ensureStandaloneLauncherSupported(loomWithRunClient, "startRemold", true));
        Assertions.assertDoesNotThrow(
                () -> RemoldPlugin.ensureStandaloneLauncherSupported(loomWithRunClient, "generateRemoldScript", true));
        GradleException runRemoldEx = Assertions.assertThrows(GradleException.class,
                () -> RemoldPlugin.ensureStandaloneLauncherSupported(loomWithRunClient, "runRemold", false));
        Assertions.assertTrue(runRemoldEx.getMessage().contains("startRemold"));

        Project neoProject = ProjectBuilder.builder().withProjectDir(tempDir).build();
        neoProject.getPlugins().apply("java");
        neoProject.getExtensions().add("neoForge", new Object());
        GradleException neoEx = Assertions.assertThrows(GradleException.class,
                () -> RemoldPlugin.ensureStandaloneLauncherSupported(neoProject, "startRemold", true));
        Assertions.assertTrue(neoEx.getMessage().contains("ModLauncher"));

        java.util.List<String> stripped = RemoldPlugin.stripClasspathArgs(java.util.Arrays.asList(
                "-Xmx2G", "-cp", "a.jar;b.jar", "-Dfoo=bar", "--class-path=c.jar", "-classpath", "d.jar",
                "@E:\\proj\\build\\loom-cache\\argFiles\\runClient", "@custom/keep.args", "-XX:+UseG1GC"),
                "custom/other.args");
        Assertions.assertEquals(
                java.util.Arrays.asList("-Xmx2G", "-Dfoo=bar", "@custom/keep.args", "-XX:+UseG1GC"),
                stripped);

        Assertions.assertEquals("-Dplain=C:\\a\\b", GenerateRemoldScriptTask.formatArgFileToken("-Dplain=C:\\a\\b"));
        Assertions.assertEquals("\"-Dspace=C:\\\\a b\\\\c\"",
                GenerateRemoldScriptTask.formatArgFileToken("-Dspace=C:\\a b\\c"));
    }

    private RemoldResolvedConfig resolvedConfig(Project project, List<String> jvmArgs) throws Exception {
        File agent = new File(tempDir, "remold-agent.jar");
        Files.write(agent.toPath(), new byte[2048]);
        return resolvedConfig(agent, project, jvmArgs);
    }

    private RemoldResolvedConfig resolvedConfig(File agent, Project project, List<String> jvmArgs) {
        return new RemoldResolvedConfig(
                "net.fabricmc.devlaunchinjector.Main",
                "java",
                "java",
                agent,
                Collections.emptyList(),
                "",
                jvmArgs,
                Collections.emptyList(),
                project.files(),
                null,
                "gradle"
        );
    }
}
