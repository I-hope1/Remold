package remold;

import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil.ClassDiff;
import remold.ref.InitFix;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The classifier behind {@code [INITFIX-REFUSE]}: which newly added fields make the whole class
 * un-hot-swappable.
 *
 * <p>The distinction it must keep is the whole point of the gate: an initializer that <b>exists in source
 * but cannot be sliced</b> leaves a live field at its type default (a Kotlin non-null {@code val} reads
 * {@code null}), so the class must be refused; an initializer that comes from a constructor parameter, or
 * no initializer at all, is ordinary and must stay hot-swappable.</p>
 *
 * <p>Scope note: this covers the classifier only. The wiring (class dropped from the batch, live instance
 * untouched, no false success line) is proven end-to-end on JBR by
 * {@code :tests:initFixUnexpressibleProbe}.</p>
 */
class InitFixUnexpressibleInitTest {

	private static final String UNEXPRESSIBLE =
		InitFix.UNEXPRESSIBLE_INIT_PREFIX + " in <init>()V: contains branch";

	@Test
	void unexpressibleInitializerOnAddedFieldIsReported() {
		ClassDiff diff = diffWithAdded("label");
		InitFix.PatchReport report = reportWith(Map.of(
			"label", InitFix.FieldDecision.rejected(UNEXPRESSIBLE)));

		Map<String, String> refused = InitFix.classifyUnexpressibleNewFieldInits(report, diff);

		assertEquals(1, refused.size(), "the unsliceable initializer must be reported: " + refused);
		assertTrue(refused.containsKey("label"), refused.toString());
		assertTrue(refused.get("label").startsWith(InitFix.UNEXPRESSIBLE_INIT_PREFIX),
			"the reason must stay in the stable unexpressible family: " + refused.get("label"));
	}

	@Test
	void otherRejectionsAreNotTreatedAsUnexpressible() {
		// A value that comes from a constructor parameter has no computable source at all; that is the
		// documented boundary, not a silent-null landmine, so it must NOT refuse the class.
		ClassDiff diff = diffWithAdded("fromParam");
		InitFix.PatchReport report = reportWith(Map.of(
			"fromParam", InitFix.FieldDecision.rejected("self-assignment from its own constructor parameter")));

		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(report, diff).isEmpty(),
			"param-sourced initializers keep the existing per-field behaviour");
	}

	@Test
	void preExistingFieldsAreIgnored() {
		// The same rejection on a field that is NOT newly added must not refuse the class: InitFix was
		// asked about it for another reason (reinit/ledger), and the baseline already has it.
		ClassDiff diff = diffWithAdded("other");
		InitFix.PatchReport report = reportWith(Map.of(
			"legacy", InitFix.FieldDecision.rejected(UNEXPRESSIBLE)));

		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(report, diff).isEmpty(),
			"only added fields can make the class un-hot-swappable");
	}

	@Test
	void staticAddedFieldsAreCoveredToo() {
		ClassDiff diff = new ClassDiff();
		diff.addedStaticFields.add("CONST");
		InitFix.PatchReport report = new InitFix.PatchReport(
			Map.of(), Map.of("CONST", InitFix.FieldDecision.rejected(UNEXPRESSIBLE)), false, Map.of());

		Map<String, String> refused = InitFix.classifyUnexpressibleNewFieldInits(report, diff);
		assertTrue(refused.containsKey("CONST"), "static added fields follow the same rule: " + refused);
	}

	@Test
	void acceptedAddedFieldIsNotReported() {
		ClassDiff diff = diffWithAdded("added");
		InitFix.PatchReport report = reportWith(Map.of("added", InitFix.FieldDecision.ACCEPTED));
		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(report, diff).isEmpty());
	}

	@Test
	void missingInitializerInConstructorsIsNotTreatedAsUnexpressible() {
		// Fields with no initializer in <init> (e.g. Kotlin lateinit var or Java field populated via setter)
		// must NOT trigger [INITFIX-REFUSE], even if the legacy fallback text was passed.
		ClassDiff diff = diffWithAdded("lazyTag");
		InitFix.PatchReport newFallback = reportWith(Map.of(
			"lazyTag", InitFix.FieldDecision.rejected("no field initializer found in constructors")));
		InitFix.PatchReport legacyFallback = reportWith(Map.of(
			"lazyTag", InitFix.FieldDecision.rejected("no safe initialization expression found in constructors")));

		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(newFallback, diff).isEmpty(),
			"a field with no constructor initializer must stay hot-swappable");
		assertTrue(InitFix.classifyUnexpressibleNewFieldInits(legacyFallback, diff).isEmpty(),
			"the missing-initializer fallback string must never collide with UNEXPRESSIBLE_INIT_PREFIX");
	}

	@Test
	void endToEndUninitializedFieldWithSetterDoesNotTriggerInitFixRefuse() throws Exception {
		// Synthesize v1 (plain class) and v2 (adds `private String lazyTag;` + `setLazyTag(String)` only,
		// zero PUTFIELD in <init>), then run InitFix.transform end-to-end.
		String internal = "remold/uninit/SetterBean";
		String dot = "remold.uninit.SetterBean";
		byte[] v1 = buildSetterBean(internal, false);
		byte[] v2 = buildSetterBean(internal, true);

		ClassLoader loader = new ClassLoader(InitFixUnexpressibleInitTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (dot.equals(name)) return defineClass(name, v2, 0, v2.length);
				return super.findClass(name);
			}
		};
		Class<?> host = Class.forName(dot, true, loader);
		ClassDiff diff = ClassDiffUtil.diff(v1, v2);
		assertTrue(diff.addedInstanceFields.contains("lazyTag"), "diff must detect added lazyTag");

		InitFix.transform(host, v2, diff);
		Map<String, String> refused = InitFix.unexpressibleNewFieldInits(host, diff);
		assertTrue(refused.isEmpty(),
			"plain Java uninitialized field + setter (and Kotlin lateinit var) must NOT trigger [INITFIX-REFUSE]: "
				+ refused);
	}

	@Test
	void batchCascadeIgnoresAddedOnlyFieldsAndCatchesRemovalsOrTypeChanges() {
		ClassDiff addedOnly = new ClassDiff();
		addedOnly.changedFields.add("+ extra");
		addedOnly.changedFields.add("+ *STATIC_CONST");
		assertFalse(BatchHotswapExecutor.hasRemovedOrIncompatibleMembers(addedOnly),
			"adding fields only does not rely on a no-reader proof and must not be cascaded out");

		ClassDiff removedField = new ClassDiff();
		removedField.changedFields.add("- legacy");
		assertTrue(BatchHotswapExecutor.hasRemovedOrIncompatibleMembers(removedField),
			"removing a field must still trigger cascade when a batch reader was dropped");

		ClassDiff typeChangedField = new ClassDiff();
		typeChangedField.changedFields.add("- tag");
		typeChangedField.changedFields.add("+ tag");
		assertTrue(BatchHotswapExecutor.hasRemovedOrIncompatibleMembers(typeChangedField),
			"changing a field type must still trigger cascade");

		ClassDiff removedMethod = new ClassDiff();
		removedMethod.removedMethods.add("oldHelper()V");
		assertTrue(BatchHotswapExecutor.hasRemovedOrIncompatibleMembers(removedMethod),
			"removing a method must still trigger cascade");
	}

	private static byte[] buildSetterBean(String internalName, boolean withLazyTag) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
			internalName, null, "java/lang/Object", null);
		if (withLazyTag) {
			cw.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "lazyTag", "Ljava/lang/String;", null, null).visitEnd();
		}
		org.objectweb.asm.MethodVisitor init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		if (withLazyTag) {
			org.objectweb.asm.MethodVisitor setter = cw.visitMethod(
				org.objectweb.asm.Opcodes.ACC_PUBLIC, "setLazyTag", "(Ljava/lang/String;)V", null, null);
			setter.visitCode();
			setter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
			setter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
			setter.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, internalName, "lazyTag", "Ljava/lang/String;");
			setter.visitInsn(org.objectweb.asm.Opcodes.RETURN);
			setter.visitMaxs(2, 2);
			setter.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassDiff diffWithAdded(String field) {
		ClassDiff diff = new ClassDiff();
		diff.addedInstanceFields.add(field);
		return diff;
	}

	private static InitFix.PatchReport reportWith(Map<String, InitFix.FieldDecision> instanceFields) {
		return new InitFix.PatchReport(instanceFields, Map.of(), true, Map.of());
	}
}
