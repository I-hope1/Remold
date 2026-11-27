package remold.ref;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.util.Names;

import java.lang.instrument.ClassDefinition;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class born in the same batch (a new anonymous class or helper) must stay visible to the hierarchy oracle
 * even after {@link InitFix#setBatchDefinitions(List)} rebuilds the batch view from the redefine list only.
 *
 * <p>This is the lazy_load form of the field failure: the host gains a field whose initializer is a new class
 * that is only mounted for lazy loading, never injected through {@code defineClass}, so it never reaches the
 * redefine list. Without the side table the constructor lookup returns "unknown" and the host is dropped as
 * {@code INITFIX-REFUSE}.</p>
 */
class BatchBornInitializerVisibilityTest {

	private static final class ByteLoader extends ClassLoader {
		ByteLoader() { super(BatchBornInitializerVisibilityTest.class.getClassLoader()); }
		Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
	}

	@BeforeEach
	void setUp() {
		RemoldAgent.INIT_FIX = true;
		InitFix.clearExtraBatchBytes();
	}

	@AfterEach
	void tearDown() {
		InitFix.endBatchBytecodeView();
		InitFix.clearExtraBatchBytes();
	}

	/** Old host: no field at all, so the new field is genuinely added. */
	private static byte[] hostWithoutField(String name) {
		return host(name, null);
	}

	/** New host: one instance field initialized by {@code NEW owner; INVOKESPECIAL owner.<init>()V}. */
	private static byte[] hostWithInitializer(String name, String owner) {
		return host(name, owner);
	}

	private static byte[] host(String name, String owner) {
		String internal = Names.internal(name);
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal, null, "java/lang/Object", null);
		if (owner != null) {
			cw.visitField(Opcodes.ACC_PUBLIC, "movedOp", "L" + Names.internal(owner) + ";", null, null).visitEnd();
		}
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (owner != null) {
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitTypeInsn(Opcodes.NEW, Names.internal(owner));
			init.visitInsn(Opcodes.DUP);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, Names.internal(owner), "<init>", "()V", false);
			init.visitFieldInsn(Opcodes.PUTFIELD, internal, "movedOp", "L" + Names.internal(owner) + ";");
		}
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(3, 1);
		init.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] bornClass(String name, String marker) {
		String internal = Names.internal(name);
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internal, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		MethodVisitor who = cw.visitMethod(Opcodes.ACC_PUBLIC, "who", "()Ljava/lang/String;", null, null);
		who.visitCode();
		who.visitLdcInsn(marker);
		who.visitInsn(Opcodes.ARETURN);
		who.visitMaxs(1, 1);
		who.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Map<ClassLoader, Map<String, byte[]>> view(ClassLoader loader, String host, byte[] hostBytes,
	                                                           String born, byte[] bornBytes) {
		Map<ClassLoader, Map<String, byte[]>> view = new IdentityHashMap<>();
		Map<String, byte[]> bytes = new LinkedHashMap<>();
		bytes.put(host, hostBytes);
		bytes.put(born, bornBytes);
		view.put(loader, bytes);
		return view;
	}

	@Test
	void registeredBornClassSurvivesSetBatchDefinitionsAndResolvesTheConstructor() {
		ByteLoader loader = new ByteLoader();
		String hostName = "bornbatch.Host";
		String bornName = "bornbatch.Host$movedOp$1";
		byte[] oldHost = hostWithoutField(hostName);
		byte[] newHost = hostWithInitializer(hostName, bornName);
		byte[] bornBytes = bornClass(bornName, "born");
		Class<?> host = loader.define(hostName, oldHost);

		InitFix.beginBatchBytecodeView(view(loader, hostName, newHost, bornName, bornBytes));
		try {
			// lazy_load registers the mounted class with whatever name form its batch entry uses; the side
			// table normalizes both forms to dotted names, matching the oracle's lookup.
			InitFix.registerBatchClassBytes(loader, Map.of(Names.internal(bornName), bornBytes));
			InitFix.setBatchDefinitions(List.of(new ClassDefinition(host, newHost)));

			assertNotNull(new HierarchyTreeOracle(loader).classBytes(Names.internal(bornName)),
			 "the born class must remain visible after the redefine list replaces the batch view");

			InitFix.transform(host, newHost, ClassDiffUtil.diff(oldHost, newHost));
			InitFix.finishBatchTransforms();

			assertEquals(InitFix.FieldStatus.ACCEPTED,
			 InitFix.getLastReport(host).instanceFields().get("movedOp").status(),
			 "the host must not be refused just because its initializer class was born in this batch");
			assertTrue(InitFix.pendingPlanForTest(host).instanceTasks().stream()
			 .anyMatch(task -> "movedOp".equals(task.fieldName())),
			 "an instance patch must be staged for the new field");
		} finally {
			InitFix.afterRedefineFailed(host);
		}
	}

	@Test
	void unregisteredBornClassStillFailsClosed() {
		ByteLoader loader = new ByteLoader();
		String hostName = "bornbatch.UnregisteredHost";
		String bornName = "bornbatch.UnregisteredHost$1";
		byte[] oldHost = hostWithoutField(hostName);
		byte[] newHost = hostWithInitializer(hostName, bornName);
		byte[] bornBytes = bornClass(bornName, "born");
		Class<?> host = loader.define(hostName, oldHost);

		InitFix.beginBatchBytecodeView(view(loader, hostName, newHost, bornName, bornBytes));
		try {
			// The born class is a known candidate but gets dropped before redefine: no registration means the
			// oracle must fail closed rather than invent visibility.
			InitFix.setBatchDefinitions(List.of(new ClassDefinition(host, newHost)));
			assertNull(new HierarchyTreeOracle(loader).classBytes(Names.internal(bornName)),
			 "an unregistered candidate must stay invisible");

			InitFix.transform(host, newHost, ClassDiffUtil.diff(oldHost, newHost));
			InitFix.finishBatchTransforms();
			assertEquals(InitFix.FieldStatus.REJECTED,
			 InitFix.getLastReport(host).instanceFields().get("movedOp").status(),
			 "without registration the constructor status is unknown and the field must be rejected");
		} finally {
			InitFix.afterRedefineFailed(host);
		}
	}

	@Test
	void batchEndClearsTheSideTableSoTheNextRoundDoesNotInheritBytes() {
		ByteLoader loader = new ByteLoader();
		String name = "bornbatch.Leak";
		byte[] bytes = bornClass(name, "leak");

		InitFix.beginBatchBytecodeView(view(loader, name + "Host", hostWithoutField(name + "Host"),
		 name, bytes));
		InitFix.registerBatchClassBytes(loader, Map.of(name, bytes));
		assertNotNull(new HierarchyTreeOracle(loader).classBytes(Names.internal(name)));
		InitFix.endBatchBytecodeView();

		// Next round: the same loader is in the view, but this class is not part of it anymore.
		Map<ClassLoader, Map<String, byte[]>> nextRound = new IdentityHashMap<>();
		nextRound.put(loader, new LinkedHashMap<>());
		InitFix.beginBatchBytecodeView(nextRound);
		try {
			assertNull(new HierarchyTreeOracle(loader).classBytes(Names.internal(name)),
			 "the previous batch's side-table bytes must not leak into the next round");
		} finally {
			InitFix.endBatchBytecodeView();
		}
	}
}
