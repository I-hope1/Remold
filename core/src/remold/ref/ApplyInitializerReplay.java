package remold.ref;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import remold.ClassDiffUtil.ClassDiff;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A-tier replay for a Kotlin {@code object} / top-level {@code val} holder whose static
 * initializer creates one object and configures it through an inline {@code apply} block:
 *
 * <pre>{@code
 * object Blocks { val duo = Turret("duo").apply { health = 250 } }
 * }</pre>
 *
 * <p>Kotlin inlines {@code apply}, so the whole write happens inside the holder's
 * {@code <clinit>}: {@code NEW T; ...; ASTORE; ALOAD; <constant>; PUTFIELD T.field;
 * ALOAD; PUTSTATIC holder.dest}. A redefinition never re-runs {@code <clinit>}, so the live
 * object reachable through {@code GETSTATIC holder.dest} keeps the old initializer value.
 * This class only <b>plans</b> the replay: {@link InitFix} emits one static patch method per
 * accepted field ({@code GETSTATIC dest; <new constant>; indy(KIND_CONDITIONAL_EXPECTED)})
 * and {@link HotswapBridge} performs the conditional CAS at runtime.</p>
 *
 * <p>Default is off ({@code -Dremold.agent.init_drift.apply_holders} empty). Only holders
 * listed there are analysed. The analysis is deliberately narrow and fail-closed:</p>
 * <ul>
 *   <li>both old and new {@code <clinit>} must be straight-line (no branches, no try/catch);</li>
 *   <li>the applied object must be created by exactly one {@code NEW T} site whose reference
 *       never escapes except into the one {@code PUTSTATIC holder.dest} and the constant
 *       {@code PUTFIELD}s;</li>
 *   <li>the receiver of each replayed write is proven, through the local-slot copies Kotlin
 *       emits, to be that same {@code NEW} value (adjacent-instruction guessing is not enough);</li>
 *   <li>the destination must be a static field declared by the holder with descriptor
 *       {@code LT;}; the target field must be primitive, non-final, non-synthetic and not
 *       annotated {@code @RemoldReinit};</li>
 *   <li>old and new values must both be compile-time constants and must differ.</li>
 * </ul>
 *
 * <p>Nothing is written to {@code FieldLedger} and {@code patchGenerated} is not changed:
 * this is an explicitly enabled best-effort replay, not an "owed" field. Design spec:
 * {@code docs/initfix/13-kotlin-apply-replay.md}.</p>
 */
public final class ApplyInitializerReplay {

	/** Holder whitelist (dot or slash separated, comma separated); empty = the channel is off. */
	public static final String HOLDERS_PROPERTY = "remold.agent.init_drift.apply_holders";

	private static final String REINIT_DESC = "Lremold/annotation/RemoldReinit;";

	private ApplyInitializerReplay() {
	}

	/** One replayable static-holder field write. */
	public record ReplayField(
	 String dest, String destDesc, String targetClass, int destAccess,
	 String fieldName, String fieldDesc, int fieldAccess,
	 Object expected, Object bootstrapArgument, Object newRaw,
	 String oldText, String newText) {

		/** Stable diagnostic key: {@code dest -> target.dot.Name.field}. */
		public String key() {
			return dest + " -> " + targetClass.replace('/', '.') + "." + fieldName;
		}
	}

	/**
	 * Result of one plan.
	 *
	 * @param fields      accepted replays keyed by {@link ReplayField#key()}; insertion order is
	 *                    the patch-method order {@link InitFix} and the runtime driver rely on
	 * @param decisions   every candidate key -> {@code replayed} / {@code rejected: reason}
	 *                    (diagnostic only; never touches the ledger)
	 * @param notices     holder-level refusals without a candidate key (warned once per round)
	 */
	public record Plan(
	 Map<String, ReplayField> fields,
	 Map<String, String> decisions,
	 List<String> notices) {

		public static final Plan EMPTY = new Plan(Map.of(), Map.of(), List.of());

		public Plan {
			fields = fields == null || fields.isEmpty()
			 ? Map.of()
			 : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
			decisions = decisions == null || decisions.isEmpty()
			 ? Map.of()
			 : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(decisions));
			notices = notices == null ? List.of() : List.copyOf(notices);
		}
	}

	public static boolean enabled() {
		return !holders().isEmpty();
	}

	/** Whitelist entries as internal names. */
	public static Set<String> holders() {
		String raw = System.getProperty(HOLDERS_PROPERTY, "");
		if (raw == null || raw.isBlank()) return Set.of();
		Set<String> out = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String name = part.trim();
			if (!name.isEmpty()) out.add(name.replace('.', '/'));
		}
		return out;
	}

	/** Cheap trigger for the external redefine channel: only whitelisted holders can produce work. */
	public static boolean mightReplay(ClassDiff diff) {
		if (diff == null || diff.newClass == null) return false;
		Set<String> holders = holders();
		return !holders.isEmpty() && holders.contains(diff.newClass.name);
	}

	/**
	 * Plan replay fields for one (possibly) whitelisted holder.
	 *
	 * @param host holder class, used only to obtain a {@link ClassLoader} for offline hierarchy
	 *             lookups; may be {@code null} (system loader is used)
	 */
	public static Plan plan(Class<?> host, ClassDiff diff) {
		if (diff == null || diff.oldClass == null || diff.newClass == null) return Plan.EMPTY;
		Set<String> whitelist = holders();
		if (whitelist.isEmpty() || !whitelist.contains(diff.newClass.name)) return Plan.EMPTY;

		ClassNode newClass = diff.newClass;
		ClassNode oldClass = diff.oldClass;
		MethodNode newClinit = clinit(newClass);
		if (newClinit == null) return Plan.EMPTY;

		Shape newShape = analyse(newClass, newClinit);
		if (newShape.objects().isEmpty()) {
			return newShape.notices().isEmpty()
			 ? Plan.EMPTY
			 : new Plan(Map.of(), Map.of(), newShape.notices());
		}

		MethodNode oldClinit = clinit(oldClass);
		if (oldClinit == null) {
			return new Plan(Map.of(), Map.of(),
			 List.of("<clinit> is new in this version; no live apply object exists to replay"));
		}
		Shape oldShape = analyse(oldClass, oldClinit);
		if (oldShape.objects().isEmpty() && !oldShape.notices().isEmpty()) {
			List<String> notices = new ArrayList<>(newShape.notices());
			notices.add("old <clinit>: " + oldShape.notices().get(0));
			return new Plan(Map.of(), Map.of(), notices);
		}

		ClassLoader loader = host == null ? null : host.getClassLoader();
		HierarchyTreeOracle oracle = new HierarchyTreeOracle(loader, newClass);

		Map<String, ReplayField> fields = new LinkedHashMap<>();
		Map<String, String> decisions = new LinkedHashMap<>();
		List<String> notices = new ArrayList<>(newShape.notices());

		for (ObjectShape shape : newShape.objects().values()) {
			String dest = shape.dest();
			ObjectShape old = oldShape.objects().get(dest);
			if (old == null) {
				notices.add("static field " + dest + " is not written by the old <clinit>;"
				            + " the live value is unknown; not replayed");
				continue;
			}
			if (!old.destDesc().equals(shape.destDesc())) {
				notices.add("static field " + dest + " changed descriptor "
				            + old.destDesc() + " -> " + shape.destDesc()
				            + "; a destination type change is not replayed");
				continue;
			}
			if (!old.alloc().type.equals(shape.alloc().type)) {
				notices.add("static field " + dest + " changed object type "
				            + old.alloc().type.replace('/', '.') + " -> "
				            + shape.alloc().type.replace('/', '.') + "; not replayed");
				continue;
			}

			Map<String, Write> oldWrites = writesByField(old.alloc());
			Map<String, Write> newWrites = writesByField(shape.alloc());
			String target = shape.alloc().type;
			for (Write write : shape.alloc().writes) {
				String suffix = fieldKey(write.insn());
				String key = dest + " -> " + target.replace('/', '.') + "." + write.insn().name;
				Write oldWrite = oldWrites.get(suffix);
				if (oldWrite == null) {
					notices.add(key + ": the old <clinit> does not write this field;"
					            + " the live value cannot be conditioned on; not replayed");
					continue;
				}

				String reason = targetFieldReason(oracle, target, write.insn());
				if (reason != null) {
					decisions.put(key, "rejected: " + reason);
					continue;
				}

				Object oldRaw = oldWrite.value().constant;
				Object newRaw = write.value().constant;
				if (oldRaw == null) {
					notices.add(key + ": the old initializer is not a compile-time constant;"
					            + " cannot condition on it; not replayed");
					continue;
				}
				if (newRaw == null) {
					notices.add(key + ": the new initializer is not a compile-time constant;"
					            + " not replayed");
					continue;
				}
				Object naturalOld = natural(oldRaw, write.insn().desc);
				Object naturalNew = natural(newRaw, write.insn().desc);
				if (naturalOld == null || naturalNew == null) {
					decisions.put(key, "rejected: initializer constant does not match field type "
					                   + write.insn().desc);
					continue;
				}
				if (naturalOld.equals(naturalNew)) continue; // nothing drifted

				FieldNode targetField = oracle.findFieldNode(target, write.insn().name,
				 write.insn().desc).orElseThrow();
				fields.put(key, new ReplayField(dest, shape.destDesc(), target,
				 shape.destAccess(), write.insn().name, write.insn().desc, targetField.access,
				 naturalOld, oldRaw, newRaw, String.valueOf(naturalOld), String.valueOf(naturalNew)));
				decisions.put(key, "replayed");
			}

			for (Write oldWrite : old.alloc().writes) {
				if (newWrites.containsKey(fieldKey(oldWrite.insn()))) continue;
				notices.add("static field " + dest + "." + oldWrite.insn().name
				            + ": the new <clinit> no longer writes this field;"
				            + " a removed initializer write is not replayed");
			}
		}

		if (fields.isEmpty() && decisions.isEmpty() && notices.isEmpty()) return Plan.EMPTY;
		return new Plan(fields, decisions, notices);
	}

	// ==================== plan helpers ====================

	private static MethodNode clinit(ClassNode node) {
		if (node == null || node.methods == null) return null;
		for (MethodNode method : node.methods) {
			if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) return method;
		}
		return null;
	}

	private static FieldNode fieldOf(ClassNode node, String name, String desc) {
		if (node.fields == null) return null;
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return field;
		}
		return null;
	}

	private static Map<String, Write> writesByField(Alloc alloc) {
		Map<String, Write> out = new LinkedHashMap<>();
		for (Write write : alloc.writes) out.put(fieldKey(write.insn()), write);
		return out;
	}

	private static String fieldKey(FieldInsnNode insn) {
		return insn.name + ":" + insn.desc;
	}

	/**
	 * Target field safety gate for one replayed write. Returns a rejection reason, or
	 * {@code null} when the field is a primitive, non-final, non-synthetic instance field that
	 * is resolvable from bytecode metadata and not annotated {@code @RemoldReinit}.
	 */
	private static String targetFieldReason(HierarchyTreeOracle oracle, String target, FieldInsnNode write) {
		if (!isPrimitiveDescriptor(write.desc)) {
			return "reference-typed fields are not replayed by the apply channel";
		}
		Optional<FieldNode> resolved = oracle.findFieldNode(target, write.name, write.desc);
		if (!resolved.isPresent()) {
			return "the target field cannot be resolved from bytecode metadata";
		}
		FieldNode field = resolved.get();
		int access = field.access;
		if ((access & Opcodes.ACC_STATIC) != 0) {
			return "the PUTFIELD target resolves to a static field";
		}
		if ((access & Opcodes.ACC_FINAL) != 0) {
			return "field is final (only @RemoldReinit(OVERWRITE) may rewrite final fields)";
		}
		if ((access & Opcodes.ACC_SYNTHETIC) != 0) {
			return "field is synthetic";
		}
		if (hasReinit(field)) {
			return "field is annotated @RemoldReinit, the explicit path wins";
		}
		if (field.name.startsWith("this$") || field.name.startsWith("val$")) {
			return "compiler-generated capture field";
		}
		return null;
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

	/** Convert a raw constant to the field's natural boxed type; {@code null} on type mismatch. */
	private static Object natural(Object raw, String desc) {
		if (raw == null || desc == null || desc.isEmpty()) return null;
		if (!(raw instanceof Number number)) return null;
		return switch (desc.charAt(0)) {
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

	// ==================== <clinit> shape analysis ====================

	/** One {@code PUTFIELD} of a tracked allocation (receiver is the apply object). */
	private record Write(FieldInsnNode insn, Value value) {
	}

	/** Immutable abstract value: either a {@code NEW} allocation site or a compile-time constant. */
	private record Value(Alloc alloc, Object constant) {
		static final Value UNKNOWN = new Value(null, null);
	}

	/** One {@code NEW} site plus everything proven about that object inside {@code <clinit>}. */
	private static final class Alloc {
		final int index;
		final String type;
		final List<Write> writes = new ArrayList<>();
		final List<FieldInsnNode> destPuts = new ArrayList<>();
		String escape;
		String badWrite;

		Alloc(int index, String type) {
			this.index = index;
			this.type = type;
		}

		void escape(String reason) {
			if (escape == null) escape = reason;
		}

		void badWrite(String reason) {
			if (badWrite == null) badWrite = reason;
		}

		String describe() {
			return "apply object #" + index + " of type " + type.replace('/', '.');
		}
	}

	/** An allocation that is uniquely reachable from one static holder field. */
	private record ObjectShape(String dest, String destDesc, int destAccess, Alloc alloc) {
	}

	private record Shape(Map<String, ObjectShape> objects, List<String> notices) {
		static final Shape EMPTY = new Shape(Map.of(), List.of());
	}

	/**
	 * Straight-line abstract interpretation of {@code <clinit>}. Only the Kotlin
	 * {@code apply}-inlined instruction repertoire is accepted; anything else rejects the whole
	 * static initializer with a notice (fail-closed) instead of guessing.
	 */
	private static Shape analyse(ClassNode node, MethodNode clinit) {
		if (clinit.tryCatchBlocks != null && !clinit.tryCatchBlocks.isEmpty()) {
			return new Shape(Map.of(),
			 List.of("<clinit> has exception handlers; the apply replay requires a straight-line static initializer"));
		}

		List<AbstractInsnNode> insns = new ArrayList<>();
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) continue; // labels / line numbers / frames
			insns.add(insn);
		}

		ArrayDeque<Value> stack = new ArrayDeque<>();
		Value[] locals = new Value[Math.max(clinit.maxLocals, 1)];
		List<Alloc> allocs = new ArrayList<>();
		boolean sawReturn = false;

		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			int op = insn.getOpcode();
			switch (op) {
				case Opcodes.NEW -> {
					Alloc alloc = new Alloc(allocs.size(), ((TypeInsnNode) insn).desc);
					allocs.add(alloc);
					stack.push(new Value(alloc, null));
				}
				case Opcodes.DUP -> {
					Value top = stack.peek();
					if (top == null) return unsupported(insn, "stack underflow");
					stack.push(top);
				}
				case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
				     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
					stack.push(new Value(null, op - Opcodes.ICONST_0));
				case Opcodes.LCONST_0 -> stack.push(new Value(null, 0L));
				case Opcodes.LCONST_1 -> stack.push(new Value(null, 1L));
				case Opcodes.FCONST_0 -> stack.push(new Value(null, 0.0f));
				case Opcodes.FCONST_1 -> stack.push(new Value(null, 1.0f));
				case Opcodes.FCONST_2 -> stack.push(new Value(null, 2.0f));
				case Opcodes.DCONST_0 -> stack.push(new Value(null, 0.0d));
				case Opcodes.DCONST_1 -> stack.push(new Value(null, 1.0d));
				case Opcodes.BIPUSH, Opcodes.SIPUSH ->
					stack.push(new Value(null, ((IntInsnNode) insn).operand));
				case Opcodes.LDC -> {
					Object cst = ((LdcInsnNode) insn).cst;
					if (cst instanceof Integer || cst instanceof Long
					    || cst instanceof Float || cst instanceof Double) {
						stack.push(new Value(null, cst));
					} else {
						stack.push(Value.UNKNOWN); // String / Class / MethodType: never a replayed value
					}
				}
				case Opcodes.ACONST_NULL -> stack.push(Value.UNKNOWN);
				case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
					int var = ((VarInsnNode) insn).var;
					Value value = var < locals.length ? locals[var] : null;
					stack.push(value == null ? Value.UNKNOWN : value);
				}
				case Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
					Value value = stack.poll();
					if (value == null) return unsupported(insn, "stack underflow");
					int var = ((VarInsnNode) insn).var;
					if (var < locals.length) locals[var] = value;
				}
				case Opcodes.IINC -> {
					int var = ((org.objectweb.asm.tree.IincInsnNode) insn).var;
					if (var < locals.length) locals[var] = Value.UNKNOWN;
				}
				case Opcodes.GETSTATIC -> stack.push(Value.UNKNOWN);
				case Opcodes.GETFIELD -> {
					if (pop(stack) == null) return unsupported(insn, "stack underflow");
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.PUTFIELD -> {
					Value value = pop(stack);
					Value receiver = pop(stack);
					if (value == null || receiver == null) return unsupported(insn, "stack underflow");
					FieldInsnNode field = (FieldInsnNode) insn;
					if (receiver.alloc() != null) {
						if (receiver.alloc().type.equals(field.owner)) {
							receiver.alloc().writes.add(new Write(field, value));
						} else {
							receiver.alloc().badWrite("the apply object is written through owner "
							                          + field.owner.replace('/', '.')
							                          + " instead of its own type");
						}
					}
					if (value.alloc() != null) {
						value.alloc().escape("stored into field " + field.owner.replace('/', '.')
						                     + "." + field.name);
					}
				}
				case Opcodes.PUTSTATIC -> {
					Value value = pop(stack);
					if (value == null) return unsupported(insn, "stack underflow");
					FieldInsnNode field = (FieldInsnNode) insn;
					if (value.alloc() != null) {
						if (field.owner.equals(node.name)) {
							value.alloc().destPuts.add(field);
						} else {
							value.alloc().escape("stored into another class's static field "
							                     + field.owner.replace('/', '.') + "." + field.name);
						}
					}
				}
				case Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC,
				     Opcodes.INVOKEINTERFACE, Opcodes.INVOKEDYNAMIC -> {
					if (!simulateInvoke(insn, stack)) return unsupported(insn, "stack underflow");
				}
				case Opcodes.CHECKCAST -> {
					if (stack.peek() == null) return unsupported(insn, "stack underflow");
				}
				case Opcodes.INSTANCEOF, Opcodes.ARRAYLENGTH -> {
					if (pop(stack) == null) return unsupported(insn, "stack underflow");
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> {
					if (pop(stack) == null) return unsupported(insn, "stack underflow");
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.MULTIANEWARRAY -> {
					int dims = ((org.objectweb.asm.tree.MultiANewArrayInsnNode) insn).dims;
					for (int d = 0; d < dims; d++) {
						if (pop(stack) == null) return unsupported(insn, "stack underflow");
					}
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
				     Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> {
					if (pop(stack) == null || pop(stack) == null) {
						return unsupported(insn, "stack underflow");
					}
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
				     Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
					if (pop(stack) == null || pop(stack) == null || pop(stack) == null) {
						return unsupported(insn, "stack underflow");
					}
				}
				case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
				     Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
				     Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> {
					Value value = pop(stack);
					if (value == null) return unsupported(insn, "stack underflow");
					Object converted = foldConversion(value.constant, op);
					stack.push(converted == null ? Value.UNKNOWN : new Value(null, converted));
				}
				case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
				     Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
				     Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
				     Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
				     Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
				     Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> {
					if (pop(stack) == null || pop(stack) == null) {
						return unsupported(insn, "stack underflow");
					}
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> {
					if (pop(stack) == null) return unsupported(insn, "stack underflow");
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
					if (pop(stack) == null || pop(stack) == null) {
						return unsupported(insn, "stack underflow");
					}
					stack.push(Value.UNKNOWN);
				}
				case Opcodes.POP -> {
					if (pop(stack) == null) return unsupported(insn, "stack underflow");
				}
				case Opcodes.NOP -> {
				}
				case Opcodes.RETURN -> {
					sawReturn = true;
					if (i != insns.size() - 1) {
						return new Shape(Map.of(),
						 List.of("<clinit> has instructions after RETURN; not a straight-line"
						         + " static initializer; not replayed"));
					}
				}
				default -> {
					return unsupported(insn, "opcode " + op + " (" + insn.getClass().getSimpleName()
					                           + ") is not part of the supported apply shape");
				}
			}
		}
		if (!sawReturn) {
			return new Shape(Map.of(), List.of("<clinit> has no RETURN; not a straight-line"
			                                   + " static initializer; not replayed"));
		}

		Map<String, ObjectShape> objects = new LinkedHashMap<>();
		List<String> notices = new ArrayList<>();
		for (Alloc alloc : allocs) {
			// Silent skip: neither a field write nor an escape -> a plain `new T()` stored into a
			// field, which has no initializer expression to replay. A holder's own INSTANCE
			// self-registration (`object Foo { init { Registry.add(this) } }`) also lands here:
			// it is not an apply target, so it must not produce an "escapes" notice.
			if (alloc.writes.isEmpty()) {
				if (alloc.escape == null && alloc.badWrite == null) continue;
				if (alloc.type.equals(node.name)) continue;
			}
			if (alloc.escape != null) {
				notices.add(alloc.describe() + " escapes the initializer (" + alloc.escape
				            + "); unique provenance is not proven; not replayed");
				continue;
			}
			if (alloc.badWrite != null) {
				notices.add(alloc.describe() + ": " + alloc.badWrite + "; not replayed");
				continue;
			}
			if (alloc.destPuts.isEmpty()) {
				notices.add(alloc.describe() + " is not stored in a static field of the holder;"
				            + " there is no live object to replay");
				continue;
			}
			if (alloc.destPuts.size() > 1) {
				List<String> names = new ArrayList<>();
				for (FieldInsnNode put : alloc.destPuts) names.add(put.name);
				notices.add(alloc.describe() + " is stored in multiple static fields " + names
				            + "; unique provenance is not proven; not replayed");
				continue;
			}
			FieldInsnNode destPut = alloc.destPuts.get(0);
			String expectedDesc = "L" + alloc.type + ";";
			if (!destPut.desc.equals(expectedDesc)) {
				notices.add("static field " + destPut.name + " descriptor " + destPut.desc
				            + " does not match the apply object type " + alloc.type.replace('/', '.')
				            + " (expected " + expectedDesc + "); not replayed");
				continue;
			}
			FieldNode destField = fieldOf(node, destPut.name, destPut.desc);
			if (destField == null) {
				notices.add("static field " + destPut.name
				            + " referenced by <clinit> is not declared by the holder; not replayed");
				continue;
			}
			if ((destField.access & Opcodes.ACC_STATIC) == 0) {
				notices.add("static field " + destPut.name + " is not static; not replayed");
				continue;
			}
			if (objects.containsKey(destPut.name)) {
				notices.add("more than one apply object is stored in static field " + destPut.name
				            + "; not replayed");
				continue;
			}
			objects.put(destPut.name, new ObjectShape(destPut.name, destPut.desc, destField.access, alloc));
		}
		return new Shape(objects, notices);
	}

	/**
	 * Simulate one invocation: pop arguments/receiver, record escapes of tracked allocations,
	 * and push a fresh unknown for a non-void return.
	 */
	private static boolean simulateInvoke(AbstractInsnNode insn, ArrayDeque<Value> stack) {
		String desc;
		if (insn instanceof MethodInsnNode method) {
			desc = method.desc;
		} else {
			desc = ((org.objectweb.asm.tree.InvokeDynamicInsnNode) insn).desc;
		}
		Type[] args = Type.getArgumentTypes(desc);
		for (int i = args.length - 1; i >= 0; i--) {
			Value value = stack.poll();
			if (value == null) return false;
			if (value.alloc() != null) value.alloc().escape("passed as an argument to a method call");
		}

		if (insn instanceof MethodInsnNode method) {
			if (method.getOpcode() != Opcodes.INVOKESTATIC) {
				Value receiver = stack.poll();
				if (receiver == null) return false;
				if (receiver.alloc() != null) {
					boolean initCall = method.getOpcode() == Opcodes.INVOKESPECIAL
					                   && "<init>".equals(method.name);
					if (!initCall) {
						receiver.alloc().escape("method call on the apply object: "
						                        + method.owner.replace('/', '.') + "." + method.name);
					}
				}
			}
		}

		Type ret = Type.getReturnType(desc);
		if (ret.getSort() != Type.VOID) stack.push(Value.UNKNOWN);
		return true;
	}

	private static Value pop(ArrayDeque<Value> stack) {
		return stack.poll();
	}

	private static Shape unsupported(AbstractInsnNode insn, String detail) {
		return new Shape(Map.of(),
		 List.of("<clinit> contains unsupported bytecode at opcode " + insn.getOpcode()
		         + " (" + detail + "); the Kotlin apply-inlined shape is not matched;"
		         + " this holder is not replayed"));
	}

	/** Fold a primitive conversion of a constant; {@code null} when the source is not constant. */
	private static Object foldConversion(Object raw, int op) {
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
}
