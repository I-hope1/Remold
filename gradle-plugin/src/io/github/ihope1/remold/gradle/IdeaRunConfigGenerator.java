package io.github.ihope1.remold.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.Project;

/**
 * Generates and synchronizes IntelliJ IDEA run configurations for Remold.
 */
public final class IdeaRunConfigGenerator {

    private IdeaRunConfigGenerator() {}

    public static File generate(Project project, RemoldResolvedConfig conf, boolean shouldAutoDeploy, boolean isLoom) {
        File ideaRunDir = new File(project.getRootDir(), ".idea/runConfigurations");
        if (!ideaRunDir.exists()) {
            ideaRunDir.mkdirs();
        }

        StringBuilder jvmArgsBuilder = new StringBuilder();
        for (String arg : conf.getJvmArgs()) {
            if (jvmArgsBuilder.length() > 0) jvmArgsBuilder.append(' ');
            jvmArgsBuilder.append(makePortable(arg, project.getRootDir()));
        }
        String portableJvmArgs = jvmArgsBuilder.toString();

        StringBuilder appArgsBuilder = new StringBuilder();
        for (String arg : conf.getAppArgs()) {
            if (appArgsBuilder.length() > 0) appArgsBuilder.append(' ');
            appArgsBuilder.append(makePortable(arg, project.getRootDir()));
        }
        String portableAppArgs = appArgsBuilder.toString();

        String jrePath = conf.getExecutableHome() != null ? conf.getExecutableHome().replace('\\', '/') : "";
        boolean hasCustomJre = !jrePath.isEmpty();

        File xmlFile = new File(ideaRunDir, "Run_with_Remold.xml");
        StringBuilder sb = new StringBuilder();
        sb.append("<component name=\"ProjectRunConfigurationManager\">\n");

        if (isLoom) {
            sb.append("  <configuration default=\"false\" name=\"Run with Remold\" type=\"GradleRunConfiguration\" factoryName=\"Gradle\">\n");
            sb.append("    <ExternalSystemSettings>\n");
            sb.append("      <option name=\"executionName\" />\n");
            sb.append("      <option name=\"externalProjectPath\" value=\"$PROJECT_DIR$\" />\n");
            sb.append("      <option name=\"externalSystemIdString\" value=\"GRADLE\" />\n");
            sb.append("      <option name=\"scriptParameters\" value=\"\" />\n");
            sb.append("      <option name=\"taskDescriptions\">\n");
            sb.append("        <list />\n");
            sb.append("      </option>\n");
            sb.append("      <option name=\"taskNames\">\n");
            sb.append("        <list>\n");
            sb.append("          <option value=\"runClient\" />\n");
            sb.append("        </list>\n");
            sb.append("      </option>\n");
            sb.append("      <option name=\"vmOptions\" value=\"\" />\n");
            sb.append("    </ExternalSystemSettings>\n");
            sb.append("    <ExternalSystemDebugServerProcess>true</ExternalSystemDebugServerProcess>\n");
            sb.append("    <ExternalSystemReAttachDebugProcess>true</ExternalSystemReAttachDebugProcess>\n");
            sb.append("    <DebugAllEnabled>false</DebugAllEnabled>\n");
            sb.append("    <RunAsTest>false</RunAsTest>\n");
            sb.append("    <method v=\"2\" />\n");
            sb.append("  </configuration>\n");
        } else if (conf.getTargetJar() != null) {
            String portableJar = makePortable(conf.getTargetJar().getAbsolutePath(), project.getRootDir());
            sb.append("  <configuration default=\"false\" name=\"Run with Remold\" type=\"JarApplication\">\n");
            sb.append("    <option name=\"JAR_PATH\" value=\"").append(xmlEscape(portableJar)).append("\" />\n");
            sb.append("    <option name=\"VM_PARAMETERS\" value=\"").append(xmlEscape(portableJvmArgs)).append("\" />\n");
            if (!portableAppArgs.isEmpty()) {
                sb.append("    <option name=\"PROGRAM_PARAMETERS\" value=\"").append(xmlEscape(portableAppArgs)).append("\" />\n");
            }
            sb.append("    <option name=\"WORKING_DIRECTORY\" value=\"$PROJECT_DIR$\" />\n");
            if (hasCustomJre) {
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH\" value=\"").append(xmlEscape(jrePath)).append("\" />\n");
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH_ENABLED\" value=\"true\" />\n");
            }
            sb.append("    <method v=\"2\">\n");
            if ("idea".equals(conf.getBuildMode())) {
                sb.append("      <option name=\"Make\" enabled=\"true\" />\n");
            } else if (shouldAutoDeploy) {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"deployArtifact\" externalProjectPath=\"$PROJECT_DIR$\" />\n");
            } else {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"classes\" externalProjectPath=\"$PROJECT_DIR$\" />\n");
            }
            sb.append("    </method>\n");
            sb.append("  </configuration>\n");
        } else {
            String moduleName = project.getName() + ".main";
            sb.append("  <configuration default=\"false\" name=\"Run with Remold\" type=\"Application\" factoryName=\"Application\">\n");
            sb.append("    <option name=\"MAIN_CLASS_NAME\" value=\"").append(xmlEscape(conf.getMainClass())).append("\" />\n");
            sb.append("    <module name=\"").append(xmlEscape(moduleName)).append("\" />\n");
            sb.append("    <option name=\"VM_PARAMETERS\" value=\"").append(xmlEscape(portableJvmArgs)).append("\" />\n");
            if (!portableAppArgs.isEmpty()) {
                sb.append("    <option name=\"PROGRAM_PARAMETERS\" value=\"").append(xmlEscape(portableAppArgs)).append("\" />\n");
            }
            sb.append("    <option name=\"WORKING_DIRECTORY\" value=\"$PROJECT_DIR$\" />\n");
            if (hasCustomJre) {
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH\" value=\"").append(xmlEscape(jrePath)).append("\" />\n");
                sb.append("    <option name=\"ALTERNATIVE_JRE_PATH_ENABLED\" value=\"true\" />\n");
            }
            sb.append("    <method v=\"2\">\n");
            if ("idea".equals(conf.getBuildMode())) {
                sb.append("      <option name=\"Make\" enabled=\"true\" />\n");
            } else if (shouldAutoDeploy) {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"deployArtifact\" externalProjectPath=\"$PROJECT_DIR$\" />\n");
            } else {
                sb.append("      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"classes\" externalProjectPath=\"$PROJECT_DIR$\" />\n");
            }
            sb.append("    </method>\n");
            sb.append("  </configuration>\n");
        }
        sb.append("</component>\n");

        try {
            Files.write(xmlFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            project.getLogger().warn("[Remold] Failed to write " + xmlFile.getAbsolutePath() + ": " + e.getMessage());
        }

        syncLoomRunConfigs(project, conf, ideaRunDir);
        return xmlFile;
    }

    public static void syncLoomRunConfigs(Project project, RemoldResolvedConfig conf, File ideaRunDir) {
        String[] loomRunConfigs = new String[]{"Minecraft_Client.xml", "Minecraft_Server.xml"};
        Pattern vmParamPattern = Pattern.compile("<option name=\"VM_PARAMETERS\" value=\"([^\"]*)\"\\s*/>");
        Pattern agentRegex = Pattern.compile("(?:^|\\s)-javaagent:[^\\s=]+remold-agent\\.jar[^\\s]*");
        Pattern methodRegex = Pattern.compile("(?s)<method v=\"2\">.*?</method>|<method v=\"2\"\\s*/>");
        String beforeRunBlock = "    <method v=\"2\">\n      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\"deployArtifact\" externalProjectPath=\"$PROJECT_DIR$\" vmOptions=\"\" scriptParameters=\"\" />\n    </method>";

        for (String configName : loomRunConfigs) {
            File clientXml = new File(ideaRunDir, configName);
            if (!clientXml.isFile()) continue;

            try {
                String content = new String(Files.readAllBytes(clientXml.toPath()), StandardCharsets.UTF_8);
                boolean modified = false;

                Matcher match = vmParamPattern.matcher(content);
                if (match.find()) {
                    String existing = match.group(1);
                    String updated = existing;
                    for (String arg : conf.getJvmArgs()) {
                        String portableArg = makePortable(arg, project.getRootDir());
                        if (arg.startsWith("-javaagent:") && arg.contains("remold-agent.jar")) {
                            Matcher am = agentRegex.matcher(updated);
                            if (am.find()) {
                                updated = am.replaceAll(Matcher.quoteReplacement(" " + portableArg)).trim();
                            } else if (!updated.contains(portableArg)) {
                                updated = !updated.trim().isEmpty() ? updated + " " + portableArg : portableArg;
                            }
                        } else if (arg.startsWith("-Dremold.plugin.jars=")) {
                            Pattern p = Pattern.compile("(?:^|\\s)-Dremold\\.plugin\\.jars=[^\\s]*");
                            Matcher m = p.matcher(updated);
                            if (m.find()) {
                                updated = m.replaceAll("").trim();
                            }
                            updated = !updated.trim().isEmpty() ? updated + " " + portableArg : portableArg;
                        } else if (arg.startsWith("-Dremold.plugins=")) {
                            Pattern p = Pattern.compile("(?:^|\\s)-Dremold\\.plugins=[^\\s]*");
                            Matcher m = p.matcher(updated);
                            if (m.find()) {
                                updated = m.replaceAll("").trim();
                            }
                            updated = !updated.trim().isEmpty() ? updated + " " + portableArg : portableArg;
                        } else if (arg.startsWith("-Dremold.build_mode=")) {
                            Pattern p = Pattern.compile("(?:^|\\s)-Dremold\\.build_mode=[^\\s]*");
                            Matcher m = p.matcher(updated);
                            if (m.find()) {
                                updated = m.replaceAll("").trim();
                            }
                            updated = !updated.trim().isEmpty() ? updated + " " + portableArg : portableArg;
                        } else if (!updated.contains(portableArg) && !updated.contains(arg)) {
                            updated = !updated.trim().isEmpty() ? updated + " " + portableArg : portableArg;
                        }
                    }
                    if (!updated.equals(existing)) {
                        content = content.replace(match.group(0), "<option name=\"VM_PARAMETERS\" value=\"" + xmlEscape(updated) + "\"/>");
                        modified = true;
                    }
                }

                String expectedBeforeRunTask = "idea".equals(conf.getBuildMode())
                        ? "      <option name=\"Make\" enabled=\"true\" />\n"
                        : "      <option name=\"Gradle.BeforeRunTask\" enabled=\"true\" tasks=\":classes\" externalProjectPath=\"$PROJECT_DIR$\" vmOptions=\"\" scriptParameters=\"\" />\n";
                String expectedBeforeRunBlock = "    <method v=\"2\">\n" + expectedBeforeRunTask + "    </method>";

                if (content.contains("tasks=\"deployArtifact\"") || ("idea".equals(conf.getBuildMode()) && !content.contains("<option name=\"Make\" enabled=\"true\" />"))) {
                    Matcher mm = methodRegex.matcher(content);
                    if (mm.find()) {
                        content = mm.replaceFirst(Matcher.quoteReplacement(expectedBeforeRunBlock));
                        modified = true;
                    }
                }

                if (modified) {
                    Files.write(clientXml.toPath(), content.getBytes(StandardCharsets.UTF_8));
                    project.getLogger().lifecycle("[Remold] Automatically synchronized Remold parameters into " + configName);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static String makePortable(String p, File rootDir) {
        if (p == null) return "";
        String rootPath = rootDir.getAbsolutePath().replace('\\', '/');
        String norm = p.replace('\\', '/');
        String rootLower = rootPath.toLowerCase();
        while (true) {
            String normLower = norm.toLowerCase();
            int idx = normLower.indexOf(rootLower);
            if (idx == -1) break;
            norm = norm.substring(0, idx) + "$PROJECT_DIR$" + norm.substring(idx + rootPath.length());
        }
        return norm;
    }
}
