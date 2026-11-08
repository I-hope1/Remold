package remold.ref;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import remold.ClassDiffUtil.ClassDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * L1 v1: replay a changed initializer expression of an <b>existing inherited</b> field on
 * anonymous subclasses whose superclass chain is on an explicit whitelist.
 *
 * <p>This is the annotation-free counterpart of {@code @RemoldReinit}: it never touches
 * fields declared by the anonymous class itself (those are the ordinary InitFix new-field
 * path) and it never falls back to enumerating parent-class instances. The patch is written
 * only to instances of the anonymous class, and only when the live value still equals the
 * <b>old</b> initializer constant.</p>
 *
 * <p>Scope of the first version: anonymous class only, {@code PUTFIELD} owned by the
 * anonymous class itself (the javac double-brace shape), primitive field, non-final,
 * and both old and new write expressions must be compile-time constants. Anything else is
 * refused with a notice; default is off ({@code -Dremold.agent.init_drift.parents} empty).</p>
 *
 * <p>This class only <b>plans</b>; patch generation and writing stay in {@link InitFix} and
 * {@link HotswapBridge}. It never holds a {@link Class} reference beyond the call.</p>
 */
public final class InitializerReplay {

	/** Whitelist of parent classes (dot or slash separated, comma separated); empty = L1 off. */
	public static final String PARENTS_PROPERTY = "remold.agent.init_drift.parents";

	private static final String REINIT_DESC = "Lremold/annotation/RemoldReinit;";

	/**
	 * "class#field" of replay writes that were actually attempted this round.
	 * Read once per round by {@link InitializerDrift}, then cleared. Keys are dot class names;
	 * values are field names (never {@link Class} references).
	 */
	private static final Map<String, Set<String>> APPLIED = new LinkedHashMap<>();

	private InitializerReplay() {
	}

	/**
	 * One replayable inherited-field write.
	 *
	 * @param name               field name
	 * @param desc               field descriptor
	 * @param declaringClass     internal name of the class that declares the field
	 * @param access             field access flags (from the declaring class)
	 * @param expected           OLD initializer value, natural boxed type (used in logs)
	 * @param bootstrapArgument  OLD initializer value encoded as an ASM bootstrap constant
	 *                           ({@code Integer} for Z/B/C/S/I, otherwise the matching box)
	 * @param oldText            human readable OLD value
	 * @param newText            human readable NEW value
	 */
	public record ReplayField(
	 String name, String desc, String declaringClass, int access,
	 Object expected, Object bootstrapArgument, String oldText, String newText) {
	}

	/** Result of one plan: replayable fields + refusal notices for drifted but non-replayable fields. */
	public record Plan(Map<String, ReplayField> fields, List<String> notices) {
		public static final Plan EMPTY = new Plan(Map.of(), List.of());
	}

	private record PutSite(MethodNode method, FieldInsnNode instruction) {
	}

	public static boolean enabled() {
		return !parents().isEmpty();
	}

	/** Whitelist entries as internal names. */
	public static Set<String> parents() {
		String raw = System.getProperty(PARENTS_PROPERTY, "");
		if (raw == null || raw.isBlank()) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String name = part.trim();
			if (!name.isEmpty()) out.add(name.replace('.', '/'));
		}
		return out;
	}

	/**
	 * Cheap trigger for the external redefine channel: only anonymous classes can produce L1 work.
	 * Does not parse constructors.
	 */
	public static boolean mightReplay(ClassDiff diff) {
		if (diff == null || diff.oldClass == null || diff.newClass == null) return false;
		if (parents().isEmpty()) return false;
		return isAnonymous(diff.newClass);
	}

	/**
	 * Plan replay fields for one class. {@code host} is only used to obtain a ClassLoader for
	 * offline hierarchy lookups; it may be {@code null} (then the system loader is used).
	 */
	public static Plan plan(Class<?> host, ClassDiff diff) {
		if (diff == null || diff.oldClass == null || diff.newClass == null) return Plan.EMPTY;
		Set<String> parents = parents();
		if (parents.isEmpty()) return Plan.EMPTY;
		ClassNode oldClass = diff.oldClass;
		ClassNode newClass = diff.newClass;
		if (!isAnonymous(newClass)) return Plan.EMPTY;

		ClassLoader loader = host == null ? null : host.getClassLoader();
		HierarchyTreeOracle oracle = new HierarchyTreeOracle(loader, newClass);
		if (!whitelisted(parents, oracle, newClass)) return Plan.EMPTY;

		HierarchyTreeOracle oldOracle = new HierarchyTreeOracle(loader, oldClass);
		Map<String, PutSite> oldPuts = puts(oldClass);
		Map<String, PutSite> newPuts = puts(newClass);

		List<String> notices = new ArrayList<>();
		Map<String, ReplayField> fields = new LinkedHashMap<>();

		for (Map.Entry<String, PutSite> e : newPuts.entrySet()) {
			String name = e.getKey();
			PutSite newPut = e.getValue();
			PutSite oldPut = oldPuts.get(name);
			if (newPut == null || oldPut == null) continue; // absent or ambiguous (multiple writes)

			FieldInsnNode oldInsn = oldPut.instruction();
			FieldInsnNode newInsn = newPut.instruction();
			if (!oldInsn.desc.equals(newInsn.desc)) continue; // type change: not L1's job

			// A field declared by the anonymous class itself is the ordinary existing own-field
			// path, never an inherited replay.
			if (declares(oldClass, name, oldInsn.desc) || declares(newClass, name, newInsn.desc)) continue;

			String oldSignature = InitializerDrift.writeSliceSignature(oldPut.method(), oldInsn);
			String newSignature = InitializerDrift.writeSliceSignature(newPut.method(), newInsn);
			if (oldSignature.equals(newSignature)) continue; // no drift

			Optional<ClassHierarchyOracle.MemberRef> newRef =
			 oracle.resolveMember(newClass.name, name, newInsn.desc, true);
			Optional<ClassHierarchyOracle.MemberRef> oldRef =
			 oldOracle.resolveMember(oldClass.name, name, oldInsn.desc, true);
			if (!newRef.isPresent() || !oldRef.isPresent()) {
				notices.add(name + ": cannot resolve the inherited field from bytecode metadata; not replayed");
				continue;
			}
			if (!newRef.get().declaringClass.equals(oldRef.get().declaringClass)) {
				notices.add(name + ": declaring class differs between old and new bytecode; not replayed");
				continue;
			}
			String declaring = newRef.get().declaringClass;
			if (declaring.equals(newClass.name)) continue; // own field again (defensive)

			Optional<FieldNode> declared = oracle.findFieldNode(newClass.name, name, newInsn.desc);
			if (!declared.isPresent()) {
				notices.add(name + ": field metadata unavailable; not replayed");
				continue;
			}
			FieldNode field = declared.get();
			int access = field.access;
			if ((access & Opcodes.ACC_STATIC) != 0 || (access & Opcodes.ACC_SYNTHETIC) != 0) continue;
			if ((access & Opcodes.ACC_FINAL) != 0) {
				notices.add(name + ": field is final (only @RemoldReinit(OVERWRITE) may rewrite"
				            + " final fields); not replayed");
				continue;
			}
			if (!isPrimitiveDescriptor(newInsn.desc)) {
				notices.add(name + ": reference-typed fields are not replayed in v1; not replayed");
				continue;
			}
			if (hasReinit(field)) {
				notices.add(name + ": declaring field is annotated @RemoldReinit, the explicit path"
				            + " wins; not replayed");
				continue;
			}

			Object oldRaw = constantValue(oldPut);
			Object newRaw = constantValue(newPut);
			if (oldRaw == null) {
				notices.add(name + ": old initializer is not a compile-time constant, cannot condition"
				            + " on it; not replayed");
				continue;
			}
			if (newRaw == null) {
				notices.add(name + ": new initializer is not a self-contained constant; not replayed");
				continue;
			}
			Object naturalOld = natural(oldRaw, newInsn.desc);
			Object naturalNew = natural(newRaw, newInsn.desc);
			if (naturalOld == null || naturalNew == null) {
				notices.add(name + ": initializer constant does not match field type " + newInsn.desc
				            + "; not replayed");
				continue;
			}
			if (naturalOld.equals(naturalNew)) continue; // same value: nothing to replay

			fields.put(name, new ReplayField(name, newInsn.desc, declaring, access,
			 naturalOld, oldRaw, String.valueOf(naturalOld), String.valueOf(naturalNew)));
		}

		if (fields.isEmpty() && notices.isEmpty()) return Plan.EMPTY;
		return new Plan(fields, notices);
	}

	/** Anonymous class detection: InnerClasses entry without a simple name, or {@code Host$1} fallback. */
	public static boolean isAnonymous(ClassNode node) {
		if (node == null) return false;
		if (node.innerClasses != null) {
			for (InnerClassNode inner : node.innerClasses) {
				if (node.name.equals(inner.name)) return inner.innerName == null;
			}
		}
		return node.outerClass != null && node.name.matches(".*\\$\\d+$");
	}

	// ==================== applied bookkeeping (L0 suppression) ====================

	/** Record that a replay attempt ran for a field (dot class name), so L0 does not warn twice. */
	public static synchronized void recordApplied(String dotClassName, String fieldName) {
		if (dotClassName == null || fieldName == null) return;
		APPLIED.computeIfAbsent(dotClassName, k -> new LinkedHashSet<>()).add(fieldName);
	}

	/** Drain and clear the applied records (called once per hotswap round). */
	public static synchronized Map<String, Set<String>> drainApplied() {
		if (APPLIED.isEmpty()) return Map.of();
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> e : APPLIED.entrySet()) {
			copy.put(e.getKey(), Set.copyOf(e.getValue()));
		}
		APPLIED.clear();
		return copy;
	}

	// ==================== bytecode helpers ====================

	private static boolean whitelisted(Set<String> parents, HierarchyTreeOracle oracle, ClassNode newClass) {
		for (String parent : parents) {
			if (parent.equals(newClass.superName)) return true;
			if (oracle.isAssignableFrom(parent, newClass.name)) return true;
		}
		return false;
	}

	/**
	 * Map field name -> the single {@code PUTFIELD} site in this class's constructors.
	 * A field with multiple write sites is stored as {@code null} (ambiguous, never replayed).
	 */
	private static Map<String, PutSite> puts(ClassNode node) {
		Map<String, PutSite> out = new LinkedHashMap<>();
		if (node.methods == null) return out;
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name) || method.instructions == null) continue;
			InsnList insns = method.instructions;
			for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD) continue;
				if (!field.owner.equals(node.name)) continue;
				if (field.name.startsWith("this$") || field.name.startsWith("val$")) continue;
				if (out.containsKey(field.name)) {
					out.put(field.name, null); // ambiguous
				} else {
					out.put(field.name, new PutSite(method, field));
				}
			}
		}
		return out;
	}

	private static boolean declares(ClassNode node, String name, String desc) {
		if (node.fields == null) return false;
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return true;
		}
		return false;
	}

	private static boolean hasReinit(FieldNode field) {
		if (field.visibleAnnotations != null) {
			for (var annotation : field.visibleAnnotations) {
				if (REINIT_DESC.equals(annotation.desc)) return true;
			}
		}
		if (field.invisibleAnnotations != null) {
			for (var annotation : field.invisibleAnnotations) {
				if (REINIT_DESC.equals(annotation.desc)) return true;
			}
		}
		return false;
	}

	private static boolean isPrimitiveDescriptor(String desc) {
		if (desc == null || desc.isEmpty()) return false;
		char c = desc.charAt(0);
		return c == 'Z' || c == 'B' || c == 'C' || c == 'S' || c == 'I' || c == 'J' || c == 'F' || c == 'D';
	}

	// ==================== constant evaluation ====================

	/**
	 * Evaluate the write expression of a double-brace assignment when it is exactly
	 * {@code ALOAD 0; <constant>[conversion]; PUTFIELD}. Returns {@code null} for any other shape
	 * (field reads, calls, arithmetic that the compiler did not fold, multi-instruction trees).
	 */
	private static Object constantValue(PutSite site) {
		if (site == null) return null;
		InsnList insns = site.method().instructions;
		int putIndex = insns.indexOf(site.instruction());
		if (putIndex < 2) return null;

		int index = putIndex - 1;
		AbstractInsnNode valueInsn = insns.get(index);
		Object constant;
		if (isConversion(valueInsn.getOpcode())) {
			AbstractInsnNode source = insns.get(index - 1);
			Object raw = rawConstant(source);
			if (raw == null) return null;
			constant = convert(raw, valueInsn.getOpcode());
			if (constant == null) return null;
			index--;
		} else {
			constant = rawConstant(valueInsn);
			if (constant == null) return null;
		}

		AbstractInsnNode receiver = insns.get(index - 1);
		if (!(receiver instanceof VarInsnNode v)
		    || v.getOpcode() != Opcodes.ALOAD || v.var != 0) {
			return null;
		}
		return constant;
	}

	private static Object rawConstant(AbstractInsnNode insn) {
		if (insn == null) return null;
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (op == Opcodes.LCONST_0) return 0L;
		if (op == Opcodes.LCONST_1) return 1L;
		if (op == Opcodes.FCONST_0) return 0.0f;
		if (op == Opcodes.FCONST_1) return 1.0f;
		if (op == Opcodes.FCONST_2) return 2.0f;
		if (op == Opcodes.DCONST_0) return 0.0d;
		if (op == Opcodes.DCONST_1) return 1.0d;
		if (insn instanceof IntInsnNode integer
		    && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
			return integer.operand;
		}
		if (insn instanceof LdcInsnNode ldc) {
			Object cst = ldc.cst;
			if (cst instanceof Integer || cst instanceof Long
			    || cst instanceof Float || cst instanceof Double) {
				return cst;
			}
		}
		return null;
	}

	private static boolean isConversion(int op) {
		return switch (op) {
			case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
			     Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
			     Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
			     Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
			     Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> true;
			default -> false;
		};
	}

	private static Object convert(Object raw, int op) {
		if (!(raw instanceof Number number)) return null;
		return switch (op) {
			case Opcodes.I2L -> number.longValue();
			case Opcodes.I2F -> number.floatValue();
			case Opcodes.I2D -> number.doubleValue();
			case Opcodes.L2I -> number.intValue();
			case Opcodes.L2F -> number.floatValue();
			case Opcodes.L2D -> number.doubleValue();
			case Opcodes.F2I -> number.intValue();
			case Opcodes.F2L -> number.longValue();
			case Opcodes.F2D -> number.doubleValue();
			case Opcodes.D2I -> number.intValue();
			case Opcodes.D2L -> number.longValue();
			case Opcodes.D2F -> number.floatValue();
			case Opcodes.I2B -> (int) (byte) number.intValue();
			case Opcodes.I2C -> (int) (char) number.intValue();
			case Opcodes.I2S -> (int) (short) number.intValue();
			default -> null;
		};
	}

	/** Convert a raw constant to the field's natural boxed type; {@code null} on type mismatch. */
	private static Object natural(Object raw, String desc) {
		if (raw == null || desc == null || desc.isEmpty()) return null;
		char c = desc.charAt(0);
		if (!(raw instanceof Number number)) return null;
		return switch (c) {
			case 'Z' -> number.intValue() != 0;
			case 'B' -> number instanceof Integer ? (byte) number.intValue() : null;
			case 'C' -> number instanceof Integer ? (char) number.intValue() : null;
			case 'S' -> number instanceof Integer ? (short) number.intValue() : null;
			case 'I' -> number instanceof Integer ? number.intValue() : null;
			case 'J' -> number instanceof Long ? number.longValue() : null;
			case 'F' -> number instanceof Float ? number.floatValue() : null;
			case 'D' -> number instanceof Double ? number.doubleValue() : null;
			default -> null;
		};
	}
}
