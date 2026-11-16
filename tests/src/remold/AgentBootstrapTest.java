package remold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBootstrapTest {

	@Test
	void entrypointsMatchInstrumentationContract() throws Exception {
		for (String name : new String[]{"premain", "agentmain"}) {
			Method m = AgentBootstrap.class.getMethod(name, String.class, Instrumentation.class);
			assertTrue(Modifier.isPublic(m.getModifiers()));
			assertTrue(Modifier.isStatic(m.getModifiers()));
		}
	}

	@Test
	void bytecodeHasNoDirectSymbolicReferenceToOtherRemoldClasses() throws Exception {
		byte[] bytes;
		try (InputStream in = AgentBootstrap.class.getResourceAsStream("AgentBootstrap.class")) {
			assertNotNull(in, "AgentBootstrap.class must be readable");
			bytes = in.readAllBytes();
		}

		Set<String> referencedRemoldTypes = new LinkedHashSet<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			private void recordIfRemold(String owner) {
				if (owner != null && owner.startsWith("remold/") && !"remold/AgentBootstrap".equals(owner)) {
					referencedRemoldTypes.add(owner);
				}
			}

			@Override
			public void visit(int version, int access, String name, String signature, String superName,
			                  String[] interfaces) {
				recordIfRemold(superName);
				if (interfaces != null) {
					for (String itf : interfaces) recordIfRemold(itf);
				}
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitTypeInsn(int opcode, String type) {
						recordIfRemold(type);
					}

					@Override
					public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
						recordIfRemold(owner);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc,
					                            boolean isInterface) {
						recordIfRemold(owner);
					}
				};
			}
		}, 0);

		assertEquals(Set.of(), referencedRemoldTypes,
			"AgentBootstrap must stay isolated from other remold.* classes before appending JAR to bootstrap search");
	}

	@Test
	void agentJarManifestOmitsBootClassPath() throws Exception {
		Path agentJar = locateBuiltAgentJar();
		try (JarFile jar = new JarFile(agentJar.toFile())) {
			Manifest manifest = jar.getManifest();
			assertNotNull(manifest, "remold-agent.jar must have a manifest");
			Attributes attrs = manifest.getMainAttributes();
			assertNull(attrs.getValue("Boot-Class-Path"),
				"Boot-Class-Path must be omitted so renamed agent JARs do not load a sibling remold-agent.jar");
			assertEquals("remold.AgentBootstrap", attrs.getValue("Premain-Class"));
			assertEquals("remold.AgentBootstrap", attrs.getValue("Agent-Class"));
		}
	}

	@Test
	void renamedAgentJarIgnoresSiblingRemoldAgentJar(@TempDir Path tempDir) throws Exception {
		Path builtJar = locateBuiltAgentJar();
		Path renamedJar = tempDir.resolve("agent-renamed.jar");
		Files.copy(builtJar, renamedJar);

		// A stale sibling remold-agent.jar in the same directory must not shadow the renamed -javaagent JAR.
		writeTestAgentJar(tempDir.resolve("remold-agent.jar"), false);

		Path appDir = Files.createDirectory(tempDir.resolve("app"));
		Files.write(appDir.resolve("ProbeMain.class"), buildBootstrapProbeMainClass());

		runBootstrapProbe(tempDir, renamedJar, appDir.toAbsolutePath().toString());
	}

	@Test
	void resolvesFatAgentJarWhenRawCoreJarShadowsAppClasspath(@TempDir Path tempDir) throws Exception {
		Path builtJar = locateBuiltAgentJar();
		Path renamedJar = tempDir.resolve("agent-renamed.jar");
		Files.copy(builtJar, renamedJar);

		// Simulate Gradle/IDE runtimeClasspath having raw :core classes ahead of -javaagent without :api bundled.
		Path rawCoreJar = tempDir.resolve("remold-core-raw.jar");
		writeTestAgentJar(rawCoreJar, true);

		Path appDir = Files.createDirectory(tempDir.resolve("app"));
		Files.write(appDir.resolve("ProbeMain.class"), buildBootstrapProbeMainClass());

		String cp = rawCoreJar.toAbsolutePath() + File.pathSeparator + appDir.toAbsolutePath();
		runBootstrapProbe(tempDir, renamedJar, cp);
	}

	private static void runBootstrapProbe(Path workDir, Path agentJar, String classpath) throws Exception {
		Process process = new ProcessBuilder(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-javaagent:" + agentJar.toAbsolutePath(),
			"-cp", classpath,
			"ProbeMain"
		)
			.directory(workDir.toFile())
			.redirectErrorStream(true)
			.start();

		boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		if (!exited) {
			process.destroyForcibly();
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(exited, "probe JVM timed out:\n" + output);
		assertEquals(0, process.exitValue(), "probe JVM failed:\n" + output);
	}

	private static Path locateBuiltAgentJar() {
		Path[] candidates = {
			Path.of("core", "build", "libs", "remold-agent.jar"),
			Path.of("..", "core", "build", "libs", "remold-agent.jar")
		};
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		throw new AssertionError("remold-agent.jar not found; run :core:shadowJar first");
	}

	private static void writeTestAgentJar(Path jarPath, boolean includeBootstrap) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
			if (includeBootstrap) {
				try (InputStream in = AgentBootstrap.class.getResourceAsStream("AgentBootstrap.class")) {
					assertNotNull(in, "AgentBootstrap.class must be readable");
					out.putNextEntry(new JarEntry("remold/AgentBootstrap.class"));
					out.write(in.readAllBytes());
					out.closeEntry();
				}
			}
			out.putNextEntry(new JarEntry("remold/RemoldAgent.class"));
			out.write(buildPoisonRemoldAgentClass());
			out.closeEntry();
		}
	}

	private static byte[] buildPoisonRemoldAgentClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "remold/RemoldAgent", null, "java/lang/Object", null);
		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitLdcInsn("decoy sibling remold-agent.jar was loaded");
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>",
			"(Ljava/lang/Object;)V", false);
		clinit.visitInsn(Opcodes.ATHROW);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] buildBootstrapProbeMainClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ProbeMain", null, "java/lang/Object", null);
		MethodVisitor main = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main",
			"([Ljava/lang/String;)V", null, new String[]{"java/lang/Exception"});
		main.visitCode();
		main.visitLdcInsn("remold.RemoldAgent");
		main.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
			"(Ljava/lang/String;)Ljava/lang/Class;", false);
		main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
			"()Ljava/lang/ClassLoader;", false);
		Label onBootstrap = new Label();
		main.visitJumpInsn(Opcodes.IFNULL, onBootstrap);
		main.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
		main.visitInsn(Opcodes.DUP);
		main.visitLdcInsn("RemoldAgent must be loaded by bootstrap classloader");
		main.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>",
			"(Ljava/lang/Object;)V", false);
		main.visitInsn(Opcodes.ATHROW);
		main.visitLabel(onBootstrap);
		main.visitInsn(Opcodes.RETURN);
		main.visitMaxs(0, 0);
		main.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
