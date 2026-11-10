import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import remold.ref.MixinMemberAlign;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the second defect found in the live client: re-weaving a Mixin target renames every
 * Mixin-generated member ({@code handler$zce000$cfg$foo} becomes {@code handler$zzc000$cfg$foo}) and
 * reorders the method table. To JBR that is "remove N methods, add N methods" instead of a pure body
 * change, and its enhanced redefine then fails with a bare {@code InternalError}.
 *
 * <p>MixinMemberAlign maps the generated names back to the loaded baseline and restores member order,
 * so that a body-only hot-swap becomes structurally identical to the live class.</p>
 */
public class MixinMemberAlignTest {

	private static final String SELF = "fixture/MixTarget";

	private static final String H_TICK      = "$cfg$tick";
	private static final String R_FOO       = "$cfg$foo";
	private static final String W_BAR       = "$cfg$bar";
	private static final String L_BAZ       = "$cfg$baz";

	// ------------------------------------------------------------------------------------------
	// Fixtures: baseline uses one unique-id set; candidate uses another set and is reordered.
	// ------------------------------------------------------------------------------------------

	private static byte[] baseline() {
		return buildClass("zce000", "zke000", false, false);
	}

	private static byte[] candidate() {
		return buildClass("zzc000", "zmp000", true, true);
	}

	/**
	 * @param id1         unique id used by handler/redirect
	 * @param id2         unique id used by wrapOperation/localvar
	 * @param reorder     reverse the member order (simulates Mixin re-weave ordering churn)
	 * @param changedBody give {@code call()} a different body constant (the real hot-swap edit)
	 */
	private static byte[] buildClass(String id1, String id2, boolean reorder, boolean changedBody) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, SELF, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "honkai$erosion", "D", null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();

		String handler = "handler$" + id1 + H_TICK;
		String redirect = "redirect$" + id2 + R_FOO;
		String wrap = "wrapOperation$" + id2 + W_BAR;
		String localvar = "localvar$" + id2 + L_BAZ;

		// call(): invokes the generated handler, so name remapping must rewrite the call site.
		MethodVisitor call = cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()I", null, null);
		call.visitCode();
		call.visitVarInsn(Opcodes.ALOAD, 0);
		call.visitMethodInsn(Opcodes.INVOKESPECIAL, SELF, handler, "()V", false);
		call.visitLdcInsn(changedBody ? 2 : 1);
		call.visitInsn(Opcodes.IRETURN);
		call.visitMaxs(2, 1);
		call.visitEnd();

		// lambda holder: bsmArgs carries a Handle to the generated handler; must be remapped too.
		MethodVisitor indy = cw.visitMethod(Opcodes.ACC_PUBLIC, "indy", "()V", null, null);
		indy.visitCode();
		indy.visitInvokeDynamicInsn("x", "()V",
			new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;", false),
			new Handle(Opcodes.H_INVOKESTATIC, SELF, handler, "()V", false));
		indy.visitInsn(Opcodes.RETURN);
		indy.visitMaxs(1, 1);
		indy.visitEnd();

		MethodVisitor tick = cw.visitMethod(Opcodes.ACC_PRIVATE, handler, "()V", null, null);
		tick.visitCode();
		tick.visitInsn(Opcodes.RETURN);
		tick.visitMaxs(0, 1);
		tick.visitEnd();

		MethodVisitor foo = cw.visitMethod(Opcodes.ACC_PRIVATE, redirect, "(I)I", null, null);
		foo.visitCode();
		foo.visitVarInsn(Opcodes.ILOAD, 1);
		foo.visitInsn(Opcodes.IRETURN);
		foo.visitMaxs(1, 2);
		foo.visitEnd();

		MethodVisitor bar = cw.visitMethod(Opcodes.ACC_PRIVATE, wrap, "()V", null, null);
		bar.visitCode();
		bar.visitInsn(Opcodes.RETURN);
		bar.visitMaxs(0, 1);
		bar.visitEnd();

		MethodVisitor baz = cw.visitMethod(Opcodes.ACC_PRIVATE, localvar, "()V", null, null);
		baz.visitCode();
		baz.visitInsn(Opcodes.RETURN);
		baz.visitMaxs(0, 1);
		baz.visitEnd();

		MethodVisitor extra = cw.visitMethod(Opcodes.ACC_PRIVATE, "honkai$newHelper", "()V", null, null);
		extra.visitCode();
		extra.visitInsn(Opcodes.RETURN);
		extra.visitMaxs(0, 1);
		extra.visitEnd();

		cw.visitEnd();
		byte[] bytes = cw.toByteArray();
		return reorder ? reorderMethods(bytes) : bytes;
	}

	/** Reverse the method table order (Mixin re-weave ordering churn). */
	private static byte[] reorderMethods(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<MethodNode> reversed = new ArrayList<>(node.methods);
		java.util.Collections.reverse(reversed);
		node.methods = reversed;
		ClassWriter cw = new ClassWriter(0);
		node.accept(cw);
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	private static List<String> generatedMethodNames(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Set<String> set = new TreeSet<>();
		for (MethodNode m : node.methods) {
			if (m.name.matches("^(handler|redirect|wrapOperation|modify|localvar|args|md)\\$[a-z0-9]+\\$.+$")) {
				set.add(m.name + m.desc);
			}
		}
		return new ArrayList<>(set);
	}

	private static List<String> methodOrder(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().map(m -> m.name + m.desc).collect(Collectors.toList());
	}

	private static MethodNode method(byte[] bytes, String name) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		return null;
	}

	// ------------------------------------------------------------------------------------------
	// Tests
	// ------------------------------------------------------------------------------------------

	@Test
	public void generatedNamesAreMappedBackAndCallSitesRewrite() {
		byte[] baseline = baseline();
		byte[] candidate = candidate();
		MixinMemberAlign.Result result = MixinMemberAlign.align(candidate, baseline);

		assertTrue(result.renamed > 0, "the renamed Mixin-generated members must be reported");
		assertEquals(generatedMethodNames(baseline), generatedMethodNames(result.bytes),
			"every generated member must end up with the loaded baseline's name");
		assertTrue(generatedMethodNames(result.bytes).stream().noneMatch(n -> n.contains("zzc000") || n.contains("zmp000")),
			"no new unique-id prefix may survive alignment: " + generatedMethodNames(result.bytes));
		assertTrue(result.missingFromCandidate.isEmpty(), "no baseline generated member may be lost: " + result.missingFromCandidate);

		// call site: invokevirtual/invokespecial target must point at the baseline name
		MethodNode call = method(result.bytes, "call");
		assertEquals("handler$zce000" + H_TICK, firstMethodInsnOwnerName(call),
			"the generated call site must be rewritten to the baseline name");

		// invokedynamic bsmArgs Handle must be rewritten as well (MixinExtras wrapOperation shape)
		MethodNode indy = method(result.bytes, "indy");
		assertEquals("handler$zce000" + H_TICK, firstInvokeDynamicHandleName(indy),
			"Handle arguments in InvokeDynamicInsnNode.bsmArgs must be rewritten too");
	}

	@Test
	public void methodOrderMatchesBaselineAndNewMembersGoLast() {
		byte[] baseline = baseline();
		byte[] result = MixinMemberAlign.align(candidate(), baseline).bytes;

		List<String> baselineOrder = methodOrder(baseline);
		List<String> alignedOrder = methodOrder(result);
		List<String> alignedExisting = alignedOrder.stream()
			.filter(baselineOrder::contains).collect(Collectors.toList());
		assertEquals(baselineOrder, alignedExisting,
			"existing members must be in exact baseline order (JBR sees a pure body change)");
		assertEquals("honkai$newHelper()V", alignedOrder.get(alignedOrder.size() - 1),
			"a genuinely new member must be appended after the baseline members");
	}

	@Test
	public void modifiedBodyIsKeptNotReverted() {
		byte[] baseline = baseline();
		byte[] result = MixinMemberAlign.align(candidate(), baseline).bytes;
		assertEquals(2, firstLdcInt(result),
			"the edited body (LDC 2) must survive alignment; alignment must not simply return the baseline bytes");
		assertEquals(1, firstLdcInt(baseline), "precondition: the baseline body really used a different constant");
	}

	@Test
	public void reorderedInputWithoutRenameStillGetsOrdered() {
		byte[] baseline = baseline();
		byte[] candidate = buildClass("zce000", "zke000", true, false); // same names, reordered only
		MixinMemberAlign.Result result = MixinMemberAlign.align(candidate, baseline);
		assertEquals(methodOrder(baseline), methodOrder(result.bytes),
			"order-only churn must also be repaired");
		assertTrue(result.reordered, "reorder must be reported");
	}

	@Test
	public void classMismatchIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Other", null, "java/lang/Object", null);
			cw.visitEnd();
			MixinMemberAlign.align(cw.toByteArray(), baseline());
		});
	}

	// ------------------------------------------------------------------------------------------
	// Optional captured-byte regression (populate tests/build/mixin-align-fixtures/ to enable)
	// ------------------------------------------------------------------------------------------

	@Test
	public void capturedLivingEntityBaselineIsFullyRestored() throws Exception {
		Path dir = Path.of(System.getProperty("user.dir"), "build", "mixin-align-fixtures");
		Path baseFile = dir.resolve("baseline.class");
		Path candFile = dir.resolve("candidate.class");
		Assumptions.assumeTrue(Files.exists(baseFile) && Files.exists(candFile),
			"captured LivingEntity fixtures not present (manual verification only)");
		byte[] baseline = Files.readAllBytes(baseFile);
		byte[] candidate = Files.readAllBytes(candFile);

		List<String> baseGenerated = generatedMethodNames(baseline);
		assertFalse(baseGenerated.isEmpty(), "precondition: the captured baseline must contain Mixin-generated members");

		// Within one Mixin session the unique ids are stable (observed on the live client), so this
		// captured pair carries the same generated names; MixinMemberAlign must then be a no-op and
		// must not disturb the config (renaming protection is covered by the synthetic fixtures above).
		assertEquals(baseGenerated, generatedMethodNames(candidate),
			"captured precondition: same-session re-weave keeps the generated unique ids");
		MixinMemberAlign.Result result = MixinMemberAlign.align(candidate, baseline);
		assertEquals(0, result.renamed,
			"captured regression: same-session captured bytes must not need renaming");
		assertArrayEquals(candidate, result.bytes,
			"captured regression: alignment must return the candidate byte-for-byte when names/order already match");
		assertTrue(result.missingFromCandidate.isEmpty(),
			"captured regression: no baseline generated member may be lost: " + result.missingFromCandidate);
		assertTrue(result.addedInCandidate.isEmpty(),
			"captured regression: no new generated member may appear: " + result.addedInCandidate);
		List<String> baseOrder = methodOrder(baseline);
		List<String> alignedOrder = methodOrder(result.bytes);
		List<String> existing = alignedOrder.stream().filter(baseOrder::contains).collect(Collectors.toList());
		assertEquals(baseOrder, existing, "captured regression: member order must match the baseline");
	}

	// ------------------------------------------------------------------------------------------
	// Tombstone stub retention (fault A: in-flight stack frames calling a physically removed handler)
	// ------------------------------------------------------------------------------------------

	private static final String TOMB = "fixture/TombstoneTarget";
	private static final String T_HANDLER = "handler$zjf000$cfg$anonRoundTrip";
	private static final String T_MODIFY = "modify$zjf000$cfg$stack";
	private static final String T_REDIRECT = "redirect$zjf000$cfg$value";
	private static final String CB_DESC = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";

	/** Baseline: the live class already carries the Mixin-generated members (handler/modify/redirect). */
	private static byte[] tombstoneBaseline() {
		return tombstoneClass(true, true);
	}

	/** Re-weave after the Mixin was retargeted away: the generated members are gone from the output. */
	private static byte[] tombstoneCandidate() {
		return tombstoneClass(false, true);
	}

	/**
	 * @param withGenerated keep the Mixin-generated members (baseline) or drop them (candidate)
	 * @param keepBusiness  keep the ordinary business method {@code helper()V}
	 */
	private static byte[] tombstoneClass(boolean withGenerated, boolean keepBusiness) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, TOMB, null, "java/lang/Object", null);

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();

		if (keepBusiness) {
			MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PUBLIC, "helper", "()V", null, null);
			helper.visitCode();
			helper.visitInsn(Opcodes.RETURN);
			helper.visitMaxs(0, 1);
			helper.visitEnd();
		}

		// call(): the OLD tick() bytecode still invokes the generated handler. After a retarget the
		// re-weave produces a call() without that call site, but an in-flight old frame keeps this one.
		MethodVisitor call = cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()V", null, null);
		call.visitCode();
		if (withGenerated) {
			call.visitVarInsn(Opcodes.ALOAD, 0);
			call.visitInsn(Opcodes.ACONST_NULL);
			call.visitMethodInsn(Opcodes.INVOKESPECIAL, TOMB, T_HANDLER, CB_DESC, false);
		}
		call.visitInsn(Opcodes.RETURN);
		call.visitMaxs(2, 1);
		call.visitEnd();

		if (withGenerated) {
			MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				T_HANDLER, CB_DESC, null, null);
			handler.visitCode();
			handler.visitInsn(Opcodes.RETURN);
			handler.visitMaxs(0, 2);
			handler.visitEnd();

			MethodVisitor modify = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				T_MODIFY, "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
			modify.visitCode();
			modify.visitVarInsn(Opcodes.ALOAD, 1);
			modify.visitInsn(Opcodes.ARETURN);
			modify.visitMaxs(1, 2);
			modify.visitEnd();

			MethodVisitor redirect = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				T_REDIRECT, "(I)I", null, null);
			redirect.visitCode();
			redirect.visitVarInsn(Opcodes.ILOAD, 1);
			redirect.visitInsn(Opcodes.IRETURN);
			redirect.visitMaxs(1, 2);
			redirect.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static final class ByteLoader extends ClassLoader {
		Class<?> define(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	@Test
	public void removedGeneratedMethodsAreRetainedAsTombstones() throws Exception {
		byte[] baseline = tombstoneBaseline();
		byte[] candidate = tombstoneCandidate();
		MixinMemberAlign.Result result = MixinMemberAlign.align(candidate, baseline);

		assertTrue(result.tombstoned.contains(T_HANDLER + CB_DESC),
			"the removed @Inject handler must be retained as a tombstone: " + result.tombstoned);
		assertTrue(result.tombstoned.contains(T_MODIFY + "(Ljava/lang/Object;)Ljava/lang/Object;"),
			"the removed @ModifyVariable handler must be retained: " + result.tombstoned);
		assertTrue(result.missingFromCandidate.isEmpty(),
			"nothing may be reported missing once the tombstones are in place: " + result.missingFromCandidate);

		MethodNode baseHandler = method(baseline, T_HANDLER);
		MethodNode keptHandler = method(result.bytes, T_HANDLER);
		assertEquals(baseHandler.desc, keptHandler.desc);
		assertEquals(baseHandler.access, keptHandler.access,
			"the tombstone must keep the baseline access flags (same method-table identity)");

		// method table order: the tombstone sits where the baseline had it
		List<String> baseOrder = methodOrder(baseline);
		List<String> alignedOrder = methodOrder(result.bytes);
		assertEquals(baseOrder.indexOf(T_HANDLER + CB_DESC), alignedOrder.indexOf(T_HANDLER + CB_DESC),
			"the tombstone must keep the baseline method-table position: " + alignedOrder);
		assertEquals(baseOrder.indexOf(T_REDIRECT + "(I)I"), alignedOrder.indexOf(T_REDIRECT + "(I)I"));

		// terminal assertion: an old in-flight frame calling the removed handler keeps linking
		ByteLoader loader = new ByteLoader();
		Class<?> live = loader.define("fixture.TombstoneTarget", result.bytes);
		Object instance = live.getDeclaredConstructor().newInstance();
		live.getMethod("call").invoke(instance);   // must not throw NoSuchMethodError
		java.lang.reflect.Method modify = live.getDeclaredMethod(T_MODIFY, Object.class);
		modify.setAccessible(true);
		Object token = new Object();
		assertTrue(modify.invoke(instance, token) == token,
			"a @ModifyVariable-shaped tombstone must pass the argument through");
		java.lang.reflect.Method redirect = live.getDeclaredMethod(T_REDIRECT, int.class);
		redirect.setAccessible(true);
		assertEquals(7, redirect.invoke(instance, 7),
			"a redirect-shaped tombstone must return the incoming value unchanged");
	}

	// ------------------------------------------------------------------------------------------
	// Tombstone field retention (fault B: @Unique field removal/re-add shrinks+expands the heap)
	// ------------------------------------------------------------------------------------------

	private static final String T_UNIQUE_FIELD = "honkai$anonState";

	private static byte[] fieldBaseline() {
		return fieldClass(true);
	}

	private static byte[] fieldCandidate() {
		return fieldClass(false);
	}

	/** Baseline carries the Mixin @Unique field; the retargeted re-weave drops it. */
	private static byte[] fieldClass(boolean withUniqueField) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/FieldTombstone", null, "java/lang/Object", null);
		if (withUniqueField) {
			cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, T_UNIQUE_FIELD, "I", null, null).visitEnd();
		}
		cw.visitField(Opcodes.ACC_PRIVATE, "payload", "I", null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	public void removedUniqueFieldIsRetainedAsTombstone() {
		byte[] baseline = fieldBaseline();
		byte[] candidate = fieldCandidate();
		MixinMemberAlign.Result result = MixinMemberAlign.align(candidate, baseline);

		assertTrue(result.tombstonedFields.contains(T_UNIQUE_FIELD + "I"),
			"the dropped @Unique field must be retained as a tombstone: " + result.tombstonedFields);
		assertEquals(fieldNames(baseline), fieldNames(result.bytes),
			"the field table must be identical to the baseline (no shrink => no DCEVM ModifyInstances)");
		assertEquals(fieldIndex(baseline, T_UNIQUE_FIELD), fieldIndex(result.bytes, T_UNIQUE_FIELD),
			"the tombstone field must keep its baseline slot position");

		// terminal assertion: the retained field is readable and zero (no reader in the new bytecode)
		ClassNode node = new ClassNode();
		new ClassReader(result.bytes).accept(node, 0);
		assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(T_UNIQUE_FIELD) && f.desc.equals("I")));
	}

	@Test
	public void fieldTableStaysConstantWhenNothingWasDropped() {
		byte[] baseline = fieldBaseline();
		MixinMemberAlign.Result result = MixinMemberAlign.align(fieldBaseline(), baseline);

		assertTrue(result.tombstonedFields.isEmpty(), "no tombstone may be invented when nothing is missing");
		assertEquals(fieldNames(baseline), fieldNames(result.bytes));
	}

	private static List<String> fieldNames(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.fields.stream().map(f -> f.name + ":" + f.desc).collect(Collectors.toList());
	}

	private static int fieldIndex(byte[] bytes, String name) {
		return fieldNames(bytes).indexOf(name + ":I");
	}

	@Test
	public void businessMethodsAreNotResurrectedAsTombstones() {
		byte[] baseline = tombstoneBaseline();
		byte[] candidate = tombstoneClass(true, false);   // helper() removed on purpose
		MixinMemberAlign.Result result = MixinMemberAlign.align(candidate, baseline);

		assertTrue(result.tombstoned.isEmpty(),
			"only Mixin-generated members may be tombstoned, never business methods: " + result.tombstoned);
		assertTrue(method(result.bytes, "helper") == null,
			"a deliberately removed business method must stay removed (fail-closed)");
	}

	private static String firstMethodInsnOwnerName(MethodNode node) {
		for (org.objectweb.asm.tree.AbstractInsnNode insn : node.instructions) {
			if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
				return ((org.objectweb.asm.tree.MethodInsnNode) insn).name;
			}
		}
		return null;
	}

	private static Integer firstLdcInt(byte[] bytes) {
		MethodNode call = method(bytes, "call");
		if (call == null) return null;
		for (org.objectweb.asm.tree.AbstractInsnNode insn : call.instructions) {
			if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
				Object cst = ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
				if (cst instanceof Integer) return (Integer) cst;
			}
		}
		return null;
	}

	private static String firstInvokeDynamicHandleName(MethodNode node) {
		for (org.objectweb.asm.tree.AbstractInsnNode insn : node.instructions) {
			if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
				Object[] args = ((org.objectweb.asm.tree.InvokeDynamicInsnNode) insn).bsmArgs;
				for (Object arg : args) {
					if (arg instanceof Handle) return ((Handle) arg).getName();
				}
			}
		}
		return null;
	}
}
