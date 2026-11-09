import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import remold.ref.AccessAlign;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Access Monotonicity Alignment regression.
 *
 * <p>Background: Remold re-weaves an already-loaded Mixin target from the <b>raw</b> jar/disk bytes.
 * Loader-time access transforms (Fabric dev {@code PackageAccessFixer}, {@code AccessWidener},
 * Forge {@code AccessTransformer}) are not replayed, so the re-woven class can carry narrower access
 * flags than the class currently installed in the JVM. Sending those bytes to
 * {@code redefineClasses} makes JBR re-link/verify subclasses; their cross-package
 * {@code invokevirtual} on a formerly-public method then fails with
 * {@code VerifyError: Bad access to protected data in invokevirtual}.</p>
 *
 * <p>These tests build the exact shape of that bug synthetically: a baseline class whose members
 * were widened to {@code public} at load time (mimicking {@code PackageAccessFixer}), a re-woven
 * candidate that regressed them to {@code protected}, a cross-package subclass doing the call, and
 * a real JVM verification of the subclass. No Minecraft classes are needed.</p>
 */
public class AccessAlignTest {

	private static final int     MEMBER_COUNT = 120;
	private static final String  BASE         = "fixture/BaseSample";
	private static final String  SUB          = "fixture/sub/SubSample";

	// ------------------------------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------------------------------

	/** Baseline / candidate class with {@code MEMBER_COUNT} methods; access flags are the only knob. */
	private static byte[] baseBytes(int methodAccess) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BASE, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();

		for (int i = 0; i < MEMBER_COUNT; i++) {
			MethodVisitor mv = cw.visitMethod(methodAccess, "m" + i, "()I", null, null);
			mv.visitCode();
			mv.visitLdcInsn(i);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Cross-package subclass: {@code call(BaseSample)} performs the exact protected-access call. */
	private static byte[] subBytes() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, SUB, null, BASE, null);

		MethodVisitor call = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			"call", "(L" + BASE + ";)I", null, null);
		call.visitCode();
		call.visitVarInsn(Opcodes.ALOAD, 0);
		call.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BASE, "m0", "()I", false);
		call.visitInsn(Opcodes.IRETURN);
		call.visitMaxs(1, 1);
		call.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Loads a fixed set of classes from byte arrays; everything else goes to the parent. */
	private static final class FixLoader extends ClassLoader {
		private final Map<String, byte[]> classes = new HashMap<>();

		FixLoader(ClassLoader parent, Map<String, byte[]> classes) {
			super(parent);
			this.classes.putAll(classes);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) return super.findClass(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	private static Map<String, Integer> readAccessFlags(byte[] bytes) {
		Map<String, Integer> flags = new HashMap<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				if (name.startsWith("<")) return null;   // <init>/<clinit> are not members under test
				flags.put(name, access);
				return null;
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor,
			                               String signature, Object value) {
				flags.put("field:" + name, access);
				return null;
			}
		}, ClassReader.SKIP_CODE);
		return flags;
	}

	private static int protectedCount(Map<String, Integer> flags) {
		int n = 0;
		for (int access : flags.values()) {
			if ((access & Opcodes.ACC_PROTECTED) != 0) n++;
		}
		return n;
	}

	private static int publicCount(Map<String, Integer> flags) {
		int n = 0;
		for (int access : flags.values()) {
			if ((access & Opcodes.ACC_PUBLIC) != 0) n++;
		}
		return n;
	}

	// ------------------------------------------------------------------------------------------
	// Layer 1: monotonic alignment
	// ------------------------------------------------------------------------------------------

	@Test
	public void publicizedBaselineIsRestored() {
		byte[] baseline  = baseBytes(Opcodes.ACC_PUBLIC);      // what the JVM really loaded
		byte[] candidate = baseBytes(Opcodes.ACC_PROTECTED);   // what re-weaving from raw bytes produces

		Map<String, Integer> before = readAccessFlags(candidate);
		assertEquals(MEMBER_COUNT, protectedCount(before),
			"precondition: every candidate member must start protected (otherwise the test is vacuous)");
		assertEquals(0, publicCount(before), "precondition: no candidate member may start public");

		AccessAlign.Result result = AccessAlign.align(candidate, baseline);

		assertTrue(result.changed(), "alignment must report that it adjusted access flags");
		assertEquals(0, protectedCount(readAccessFlags(result.bytes)),
			"after alignment no member may remain protected — this is exactly the 112-member regression");
		assertEquals(MEMBER_COUNT, publicCount(readAccessFlags(result.bytes)),
			"every member must be public again, matching the loaded baseline");
		assertTrue(result.narrowed.isEmpty(), "no narrowing may remain after alignment");
	}

	@Test
	public void classAndFinalFlagsAreMonotonic() {
		byte[] baseline = baseBytes(Opcodes.ACC_PUBLIC);                       // plain public class
		byte[] candidate = baseBytes(Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL); // class also final

		AccessAlign.Result result = AccessAlign.align(candidate, baseline);

		ClassReader reader = new ClassReader(result.bytes);
		int[] classAccess = new int[1];
		reader.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature,
			                  String superName, String[] interfaces) {
				classAccess[0] = access;
			}
		}, ClassReader.SKIP_CODE);
		assertTrue((classAccess[0] & Opcodes.ACC_PUBLIC) != 0, "class must stay public");
		assertEquals(0, classAccess[0] & Opcodes.ACC_FINAL,
			"final must be stripped when the loaded baseline is not final (AccessWidener/AT behaviour)");
		assertEquals(0, protectedCount(readAccessFlags(result.bytes)),
			"members must be widened back to public");
	}

	@Test
	public void newMembersAreNotConstrained() {
		byte[] baseline = baseBytes(Opcodes.ACC_PUBLIC);
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, BASE, null, "java/lang/Object", null);
		for (int i = 0; i < MEMBER_COUNT; i++) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m" + i, "()I", null, null);
			mv.visitCode();
			mv.visitLdcInsn(i);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		MethodVisitor added = cw.visitMethod(Opcodes.ACC_PRIVATE, "brandNew", "()V", null, null);
		added.visitCode();
		added.visitInsn(Opcodes.RETURN);
		added.visitMaxs(0, 1);
		added.visitEnd();
		cw.visitEnd();
		byte[] candidate = cw.toByteArray();

		AccessAlign.Result result = AccessAlign.align(candidate, baseline);
		Map<String, Integer> flags = readAccessFlags(result.bytes);
		assertNotNull(flags.get("brandNew"), "the newly added member must survive alignment");
		assertTrue((flags.get("brandNew") & Opcodes.ACC_PRIVATE) != 0,
			"a member without a baseline entry must keep its authored visibility (no invented widening)");
	}

	@Test
	public void narrowingIsDetectableWhenAlignmentIsSkipped() {
		byte[] baseline  = baseBytes(Opcodes.ACC_PUBLIC);
		byte[] candidate = baseBytes(Opcodes.ACC_PROTECTED);

		List<String> narrowed = AccessAlign.narrowed(candidate, baseline);
		assertFalse(narrowed.isEmpty(), "the raw re-woven candidate must be reported as narrowed");
		assertTrue(narrowed.stream().anyMatch(n -> n.contains("m0")),
			"the gate must name the narrowed members so [ACCESS-GATE] is actionable: " + narrowed);
	}

	// ------------------------------------------------------------------------------------------
	// Layer 3 proof: real JVM verification (red without alignment, green with alignment)
	// ------------------------------------------------------------------------------------------

	@Test
	public void unalignedBytesFailSubclassVerificationButAlignedBytesPass() throws Exception {
		byte[] publicized = baseBytes(Opcodes.ACC_PUBLIC);
		byte[] raw        = baseBytes(Opcodes.ACC_PROTECTED);

		// RED: the raw re-woven candidate (what Remold used to send) breaks the cross-package subclass.
		Map<String, byte[]> redClasses = new HashMap<>();
		redClasses.put(BASE, raw);
		redClasses.put(SUB, subBytes());
		FixLoader redLoader = new FixLoader(getClass().getClassLoader(), redClasses);
		Throwable red = assertThrows(VerifyError.class, () ->
			Class.forName("fixture.sub.SubSample", true, redLoader),
			"a re-woven class with narrowed access must fail subclass verification — this is the exact"
				+ " 'Bad access to protected data in invokevirtual' crash shape");
		assertTrue(String.valueOf(red.getMessage()).contains("Bad access to protected data")
				|| red.getMessage() == null,
			"expected the JVM's protected-access VerifyError, got: " + red.getMessage());

		// GREEN: monotonic alignment restores the loaded baseline, so the same subclass verifies and runs.
		byte[] aligned = AccessAlign.align(raw, publicized).bytes;
		Map<String, byte[]> greenClasses = new HashMap<>();
		greenClasses.put(BASE, aligned);
		greenClasses.put(SUB, subBytes());
		FixLoader greenLoader = new FixLoader(getClass().getClassLoader(), greenClasses);
		Class<?> sub = Class.forName("fixture.sub.SubSample", true, greenLoader);
		Class<?> base = Class.forName("fixture.BaseSample", true, greenLoader);
		assertEquals(getClass().getClassLoader(), base.getClassLoader().getParent(),
			"precondition: the fixture classes must be loaded by our own loader, not the test class loader");

		Object instance = base.getDeclaredConstructor().newInstance();
		Method call = sub.getMethod("call", base);
		Object value = call.invoke(null, instance);
		assertEquals(0, value, "the aligned class must keep its method bodies and actually return m0()");
	}
}
