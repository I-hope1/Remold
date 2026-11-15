package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
