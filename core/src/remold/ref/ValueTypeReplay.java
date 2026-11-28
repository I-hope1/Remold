package remold.ref;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import remold.ClassDiffUtil.ClassDiff;

/**
 * Pure analysis core of the value-type {@code new} replay channel
 * ({@code docs/initfix/14-value-type-new-replay.md}, gates A0-A5).
 *
 * <p>Given a class diff plus a byte source, this class decides per existing field whether its
 * initializer expression is a straight-line {@code new T(<compile-time constants>)} on both sides and
 * whether replaying old instances is provably safe. It performs <b>no</b> I/O, no class loading and no
 * reflection: every input (diff, {@code T}/{@code super} bytes, batch-candidate predicate, enhanced
 * hotswap flag) is injected, so the module is testable with in-memory bytecode and never references
 * {@code RemoldAgent}.</p>
 *
 * <p>Lifecycle: call {@link #plan(ClassDiff, Function, Predicate, boolean)} once per class per
 * redefine round. The returned {@link Plan} is intentionally narrow:</p>
 * <ul>
 *   <li>{@link Plan#fields()} contains only fields whose old/new initializer slice fingerprints differ
 *       (A0) and that pass all gates; each entry carries the fingerprints the runtime needs for the
 *       applied-generation record.</li>
 *   <li>{@link Plan#rejected()} contains one fail-closed {@link Reason} per rejected field.</li>
 *   <li>Fields whose old and new expressions are identical ("not triggered") appear in neither map.</li>
 * </ul>
 *
 * <p>Write protocol (W0-W4), applied generation storage, reporting and runtime wiring are outside this
 * class; see {@code docs/status.md} for the implementation-state source of truth.</p>
 */
public final class ValueTypeReplay {

	/** Fail-closed rejection reasons. Javadoc states the matching spec judgment code when one exists. */
	public enum Reason {
		/** A1: expression / constructor argument is not reconstructible from constants (also Kotlin default ctor). */
		EXPR_NOT_CONSTANT("VALUE-REPLAY-EXPR-NOT-CONSTANT"),
		/** A2: the constructed type, its super chain or the verified call closure is a redefine candidate this batch. */
		CONSTRUCTED_CHANGED("VALUE-REPLAY-CONSTRUCTED-CHANGED"),
		/** A3: type shape is not a supported Java record / Kotlin data class (also composite components, var, extra fields). */
		EQ_NOT_PURE("VALUE-REPLAY-EQ-NOT-PURE"),
		/** A3: {@code equals}/{@code hashCode} are hand-written instead of compiler-generated. */
		EQ_CUSTOM("VALUE-REPLAY-EQ-CUSTOM"),
		/** A3: constructor / super-constructor / {@code <clinit>} purity allowlist violated. */
		CTOR_IMPURE("VALUE-REPLAY-CTOR-IMPURE"),
		/** A4: instance field is hosted by a record or a hidden class. */
		HOST_RECORD("VALUE-REPLAY-HOST-RECORD"),
		/** A4: {@code static final} target without enhanced hotswap support. */
		STATIC_FINAL_NEEDS_ENHANCED("VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED"),
		/** A5: root constructors disagree on the old or the new initialization expression. */
		MULTI_ROOT_MISMATCH("VALUE-REPLAY-MULTI-ROOT-MISMATCH"),
		/** A2: bytes for {@code T} or a super class on the constructor chain are unavailable (fail-closed). */
		CTOR_CHAIN_UNRESOLVED("VALUE-REPLAY-CTOR-CHAIN-UNRESOLVED");

		private final String code;

		Reason(String code) {
			this.code = code;
		}

		/**
		 * Stable judgment code for logs / reports.
		 *
		 * <p>The first seven codes are defined by spec section 6. {@link #MULTI_ROOT_MISMATCH} and
		 * {@link #CTOR_CHAIN_UNRESOLVED} have no dedicated spec code; their codes are provisional and
		 * additive so that a caller can still emit one English warn per rejection.</p>
		 */
		public String code() {
			return code;
		}
	}

	/**
	 * One accepted field.
	 *
	 * @param name              field name (the only key used by InitFix; same-name-different-type is unsupported)
	 * @param isStatic          static target
	 * @param desc              field descriptor; the replay channel only accepts reference descriptors
	 * @param oldFingerprint    A0 fingerprint of the old initializer slice (whole {@code new T(c...)} chain)
	 * @param newFingerprint    A0 fingerprint of the new initializer slice
	 * @param ctorFingerprint   normalized bytecode hash of the {@code T} constructor chain (section 4.2)
	 * @param equalsFingerprint normalized bytecode hash of {@code T.equals} + {@code T.hashCode} (section 4.3)
	 */
	public record Field(String name, boolean isStatic, String desc, String oldFingerprint,
	                    String newFingerprint, String ctorFingerprint, String equalsFingerprint) {
		public Field {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(desc, "desc");
			Objects.requireNonNull(oldFingerprint, "oldFingerprint");
			Objects.requireNonNull(newFingerprint, "newFingerprint");
			Objects.requireNonNull(ctorFingerprint, "ctorFingerprint");
			Objects.requireNonNull(equalsFingerprint, "equalsFingerprint");
		}
	}

	/**
	 * Immutable planning result.
	 *
	 * @param fields   accepted fields keyed by field name
	 * @param rejected rejected fields keyed by field name
	 * @param details  one human-readable detail line per rejected field (English, for logs)
	 */
	public record Plan(Map<String, Field> fields, Map<String, Reason> rejected, Map<String, String> details) {
		public Plan {
			fields = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(fields, "fields")));
			rejected = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(rejected, "rejected")));
			details = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(details, "details")));
		}
	}

	private static final String OBJECT = "java/lang/Object";
	private static final String RECORD = "java/lang/Record";
	private static final String KOTLIN_METADATA = "Lkotlin/Metadata;";
	private static final String DEFAULT_MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;";
	private static final String OBJECT_METHODS = "java/lang/runtime/ObjectMethods";
	private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";
	private static final Set<String> BOX_OWNERS = Set.of(
		"java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
		"java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double");
	private static final Set<String> BOOTSTRAP_PREFIXES = Set.of(
		"java/", "jdk/", "javax/", "sun/", "com/sun/");

	private ValueTypeReplay() {
	}

	/**
	 * Plans value-type replay for one class.
	 *
	 * <p>The diff must carry method bodies on both sides ({@link ClassDiffUtil#diff} output). A
	 * structural diff (code stripped) yields an empty plan, because this channel can only observe
	 * initializer expressions through the instruction trees; the caller is responsible for requesting
	 * full bodies for candidate hosts.</p>
	 *
	 * @param diff            full old/new class diff of the host class
	 * @param classBytes      internal class name (e.g. {@code "oracle/Vec"}) to bytes; must resolve
	 *                        {@code T} and every non-bootstrap super class on its constructor chain,
	 *                        otherwise the field is rejected {@link Reason#CTOR_CHAIN_UNRESOLVED}
	 * @param batchCandidate  reports whether a class is a redefine candidate in this batch (bytecode,
	 *                        structure or replay target changes)
	 * @param enhanced        whether enhanced hotswap is enabled ({@code -XX:+AllowEnhancedClassRedefinition})
	 * @return immutable plan; never {@code null}
	 */
	public static Plan plan(ClassDiff diff, Function<String, byte[]> classBytes,
	                        Predicate<String> batchCandidate, boolean enhanced) {
		Objects.requireNonNull(classBytes, "classBytes");
		Objects.requireNonNull(batchCandidate, "batchCandidate");
		Map<String, Field> fields = new LinkedHashMap<>();
		Map<String, Reason> rejected = new LinkedHashMap<>();
		Map<String, String> details = new LinkedHashMap<>();
		if (diff == null || diff.oldClass == null || diff.newClass == null) {
			return new Plan(fields, rejected, details);
		}
		ClassNode oldHost = diff.oldClass;
		ClassNode newHost = diff.newClass;
		Map<String, FieldNode> oldDeclared = declaredFields(oldHost);
		Map<String, FieldNode> newDeclared = declaredFields(newHost);
		Set<String> oldRoots = new HashSet<>();
		Set<String> newRoots = new HashSet<>();
		boolean rootsComputed = false;

		for (FieldNode newField : newHost.fields) {
			if (skipField(newField)) continue;
			if (!isReferenceDescriptor(newField.desc)) continue;
			FieldNode oldField = oldDeclared.get(newField.name);
			if (oldField == null) continue;
			if (!oldField.desc.equals(newField.desc)) continue;
			boolean isStatic = (newField.access & Opcodes.ACC_STATIC) != 0;
			if (((oldField.access & Opcodes.ACC_STATIC) != 0) != isStatic) continue;
			// Compile-time constants (ConstantValue) are inlined by readers and are outside this
			// channel; the existing InitFix gate keeps rejecting them, so they are "not triggered" here.
			if (isStatic && (oldField.value != null || newField.value != null)) continue;
			String name = newField.name;
			if (isStatic) {
				planStaticField(name, newField, oldHost, newHost, classBytes, batchCandidate, enhanced,
					fields, rejected, details);
				continue;
			}
			if (!rootsComputed) {
				oldRoots.addAll(rootConstructors(oldHost));
				newRoots.addAll(rootConstructors(newHost));
				rootsComputed = true;
			}
			planInstanceField(name, newField, oldHost, newHost, oldRoots, newRoots,
				classBytes, batchCandidate, enhanced, fields, rejected, details);
		}
		return new Plan(fields, rejected, details);
	}

	/** Reference descriptors only; arrays are not a value-type target. */
	public static boolean isReferenceDescriptor(String desc) {
		return desc != null && desc.length() > 1 && desc.charAt(0) == 'L';
	}

	// ==================== per-field planning ====================

	private static void planInstanceField(String name, FieldNode newField, ClassNode oldHost, ClassNode newHost,
	                                      Set<String> oldRoots, Set<String> newRoots,
	                                      Function<String, byte[]> classBytes,
	                                      Predicate<String> batchCandidate, boolean enhanced,
	                                      Map<String, Field> fields, Map<String, Reason> rejected,
	                                      Map<String, String> details) {
		FieldSide oldSide = instanceSide(oldHost, name, newField.desc, oldRoots);
		FieldSide newSide = instanceSide(newHost, name, newField.desc, newRoots);
		decide(name, newField, oldHost, newHost, oldSide, newSide, classBytes, batchCandidate, enhanced,
			fields, rejected, details);
	}

	private static void planStaticField(String name, FieldNode newField, ClassNode oldHost, ClassNode newHost,
	                                    Function<String, byte[]> classBytes,
	                                    Predicate<String> batchCandidate, boolean enhanced,
	                                    Map<String, Field> fields, Map<String, Reason> rejected,
	                                    Map<String, String> details) {
		FieldSide oldSide = staticSide(oldHost, name, newField.desc);
		FieldSide newSide = staticSide(newHost, name, newField.desc);
		decide(name, newField, oldHost, newHost, oldSide, newSide, classBytes, batchCandidate, enhanced,
			fields, rejected, details);
	}

	private static void decide(String name, FieldNode newField, ClassNode oldHost, ClassNode newHost,
	                           FieldSide oldSide, FieldSide newSide,
	                           Function<String, byte[]> classBytes,
	                           Predicate<String> batchCandidate, boolean enhanced,
	                           Map<String, Field> fields, Map<String, Reason> rejected,
	                           Map<String, String> details) {
		boolean isStatic = (newField.access & Opcodes.ACC_STATIC) != 0;
		if (oldSide.writes().isEmpty() && newSide.writes().isEmpty()) return; // not triggered: no initializer write
		if (oldSide.writes().isEmpty()) return; // newly initialized from nothing: no old value to rebuild
		if (newSide.writes().isEmpty()) {
			reject(name, Reason.EXPR_NOT_CONSTANT, "initializer expression disappeared in the new version", rejected, details);
			return;
		}
		if (oldSide.writes().size() < oldSide.rootCount() || newSide.writes().size() < newSide.rootCount()) {
			reject(name, Reason.MULTI_ROOT_MISMATCH,
				"field is not initialized in every root constructor", rejected, details);
			return;
		}
		if (distinct(oldSide.writes()) > 1 || distinct(newSide.writes()) > 1) {
			reject(name, Reason.MULTI_ROOT_MISMATCH,
				"root constructors use different initializer expressions", rejected, details);
			return;
		}
		Expr oldExpr = oldSide.writes().get(0);
		Expr newExpr = newSide.writes().get(0);
		if (oldExpr.fingerprint().equals(newExpr.fingerprint())) return; // A0: same expression -> not triggered

		if (oldExpr.kind() == ExprKind.SYNTHETIC_DEFAULT || newExpr.kind() == ExprKind.SYNTHETIC_DEFAULT) {
			reject(name, Reason.EXPR_NOT_CONSTANT,
				"Kotlin default-parameter synthetic constructor ($default / DefaultConstructorMarker) is not supported",
				rejected, details);
			return;
		}
		if (oldExpr.kind() != ExprKind.NEW_EXPR || newExpr.kind() != ExprKind.NEW_EXPR) {
			if (oldExpr.kind() == ExprKind.NEW_EXPR) {
				reject(name, Reason.EXPR_NOT_CONSTANT,
					"new expression is no longer a straight-line new T(constants)", rejected, details);
			}
			// old side outside the channel (literal/method call): cannot rebuild the old value, stay silent.
			return;
		}
		if (!oldExpr.type().equals(newExpr.type())) {
			reject(name, Reason.EXPR_NOT_CONSTANT,
				"constructed type changed (" + oldExpr.type() + " -> " + newExpr.type() + ")", rejected, details);
			return;
		}
		if (oldExpr.rejectDetail() != null) {
			reject(name, Reason.EXPR_NOT_CONSTANT, oldExpr.rejectDetail(), rejected, details);
			return;
		}
		if (newExpr.rejectDetail() != null) {
			reject(name, Reason.EXPR_NOT_CONSTANT, newExpr.rejectDetail(), rejected, details);
			return;
		}

		// A4 target field / host restrictions.
		Reason hostReason = hostReason(oldHost, newHost, newField, enhanced);
		if (hostReason != null) {
			String detail = hostReason == Reason.HOST_RECORD
				? "instant field is hosted by a record or hidden class"
				: "static final reference target requires enhanced hotswap";
			reject(name, hostReason, detail, rejected, details);
			return;
		}

		// A2 + A3: resolve T, validate the constructor chain purity/shape, then fingerprint it.
		Fingerprints fingerprints = new Fingerprints();
		Reason chainReason = checkType(oldExpr.type(), oldExpr.ctorDesc(), newExpr.ctorDesc(),
			classBytes, batchCandidate, fingerprints, details, name);
		if (chainReason != null) {
			reject(name, chainReason, details.getOrDefault(name, chainReason.code()), rejected, details);
			return;
		}
		fields.put(name, new Field(name, isStatic, newField.desc, oldExpr.fingerprint(),
			newExpr.fingerprint(), fingerprints.ctorFingerprint, fingerprints.equalsFingerprint));
	}

	private static int distinct(List<Expr> writes) {
		Set<String> fingerprints = new LinkedHashSet<>();
		for (Expr expr : writes) fingerprints.add(expr.fingerprint());
		return fingerprints.size();
	}

	private static void reject(String name, Reason reason, String detail,
	                           Map<String, Reason> rejected, Map<String, String> details) {
		rejected.put(name, reason);
		details.put(name, detail);
	}

	private static Reason hostReason(ClassNode oldHost, ClassNode newHost, FieldNode field, boolean enhanced) {
		boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
		if (isStatic) {
			boolean isFinal = (field.access & Opcodes.ACC_FINAL) != 0;
			if (isFinal && !enhanced) return Reason.STATIC_FINAL_NEEDS_ENHANCED;
			return null;
		}
		if (isRecord(newHost) || isRecord(oldHost)) return Reason.HOST_RECORD;
		if (isHiddenName(newHost.name) || isHiddenName(oldHost.name)) return Reason.HOST_RECORD;
		return null;
	}

	// ==================== A0: initializer expression extraction ====================

	private enum ExprKind { NEW_EXPR, SYNTHETIC_DEFAULT, OTHER }

	/**
	 * One carved initialization expression.
	 *
	 * @param key        unique key of the method that owns the write (for multi-root bookkeeping)
	 * @param fingerprint A0 fingerprint over the whole value expression ({@code NEW}+ctor chain+args)
	 * @param kind       {@code new T(constants)} vs Kotlin default ctor vs unsupported
	 * @param type       constructed type (internal name) for {@link ExprKind#NEW_EXPR}
	 * @param ctorDesc   constructor descriptor for {@link ExprKind#NEW_EXPR}
	 * @param rejectDetail A1 violation detail, {@code null} when the arguments are reconstructible
	 */
	private record Expr(String key, String fingerprint, ExprKind kind, String type, String ctorDesc,
	                    String rejectDetail) { }

	private record FieldSide(int rootCount, List<Expr> writes) { }

	private static FieldSide instanceSide(ClassNode host, String fieldName, String desc, Set<String> roots) {
		List<Expr> writes = new ArrayList<>();
		for (MethodNode method : host.methods) {
			if (!"<init>".equals(method.name)) continue;
			if (!roots.contains(methodKey(method))) continue;
			Expr expr = extract(host, method, fieldName, desc, false);
			if (expr != null) writes.add(expr);
		}
		return new FieldSide(roots.size(), writes);
	}

	private static FieldSide staticSide(ClassNode host, String fieldName, String desc) {
		List<Expr> writes = new ArrayList<>();
		for (MethodNode method : host.methods) {
			if (!"<clinit>".equals(method.name)) continue;
			Expr expr = extract(host, method, fieldName, desc, true);
			if (expr != null) writes.add(expr);
		}
		return new FieldSide(1, writes);
	}

	private static String methodKey(MethodNode method) {
		return method.name + method.desc;
	}

	/**
	 * Carves the value expression of every write to {@code fieldName} in {@code method}.
	 *
	 * <p>Returns {@code null} when the method never writes the field. When a method writes it more than
	 * once (never produced by javac/kotlinc for field initializers) the expression is reported as
	 * {@link ExprKind#OTHER} so the field simply stays outside the channel.</p>
	 */
	private static Expr extract(ClassNode host, MethodNode method, String fieldName, String desc, boolean isStatic) {
		List<AbstractInsnNode> nodes = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			nodes.add(insn);
		}
		List<Integer> writes = new ArrayList<>();
		int wantedOpcode = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
		for (int i = 0; i < nodes.size(); i++) {
			AbstractInsnNode insn = nodes.get(i);
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != wantedOpcode) continue;
			if (!field.owner.equals(host.name) || !field.name.equals(fieldName) || !field.desc.equals(desc)) continue;
			writes.add(i);
		}
		if (writes.isEmpty()) return null;
		if (writes.size() > 1) {
			return new Expr(methodKey(method), "multiple-writes", ExprKind.OTHER, null, null, null);
		}
		int putIndex = writes.get(0);
		int start = expressionStart(nodes, putIndex, Type.getType(desc).getSize());
		if (start < 0) {
			return new Expr(methodKey(method), "unresolved", ExprKind.OTHER, null, null, null);
		}
		List<AbstractInsnNode> expression = slice(nodes, start, putIndex);
		// javac records / compact constructors first assign the new expression to the component
		// parameter and then copy it into the field (`new Vec(...); astore_1; aload_0; aload_1; putfield`).
		// Follow that one-hop parameter provenance so the A0 slice still sees the whole NEW chain.
		for (int depth = 0; depth < 4; depth++) {
			if (expression.size() != 1 || !(expression.get(0) instanceof VarInsnNode load) || load.var == 0) break;
			int valueSize = loadValueSize(load.getOpcode());
			if (valueSize <= 0) break;
			int slot = load.var;
			int storeIndex = -1;
			for (int i = start - 1; i >= 0; i--) {
				if (isStoreTo(nodes.get(i), slot)) {
					storeIndex = i;
					break;
				}
			}
			if (storeIndex < 0) break;
			boolean clobbered = false;
			for (int i = storeIndex + 1; i < start; i++) {
				if (isStoreTo(nodes.get(i), slot)) {
					clobbered = true;
					break;
				}
			}
			if (clobbered) break;
			int valueStart = expressionStart(nodes, storeIndex, valueSize);
			if (valueStart < 0) break;
			start = valueStart;
			putIndex = storeIndex;
			expression = slice(nodes, start, putIndex);
		}
		if (expression.isEmpty()) {
			return new Expr(methodKey(method), "empty", ExprKind.OTHER, null, null, null);
		}
		boolean hasBranch = containsBranch(nodes, start, putIndex);
		String fingerprint = expressionFingerprint(expression);
		MethodInsnNode terminal = terminalCtorCall(expression);
		if (terminal == null) {
			if (containsSyntheticDefaultCall(expression)) {
				return new Expr(methodKey(method), fingerprint, ExprKind.SYNTHETIC_DEFAULT, null, null, null);
			}
			return new Expr(methodKey(method), fingerprint, ExprKind.OTHER, null, null, null);
		}
		if (isDefaultMarkerCtor(terminal)) {
			return new Expr(methodKey(method), fingerprint, ExprKind.SYNTHETIC_DEFAULT, null, null, null);
		}
		if (!hasNewOf(expression, terminal.owner)) {
			return new Expr(methodKey(method), fingerprint, ExprKind.OTHER, null, null, null);
		}
		String reject = hasBranch
			? "initializer expression is not straight-line (contains a branch or switch)"
			: validateArguments(expression);
		return new Expr(methodKey(method), fingerprint, ExprKind.NEW_EXPR, terminal.owner, terminal.desc, reject);
	}

	private static List<AbstractInsnNode> slice(List<AbstractInsnNode> nodes, int start, int end) {
		List<AbstractInsnNode> expression = new ArrayList<>();
		for (int i = start; i < end; i++) {
			AbstractInsnNode insn = nodes.get(i);
			if (insn.getOpcode() < 0) continue;
			expression.add(insn);
		}
		return expression;
	}

	private static boolean containsBranch(List<AbstractInsnNode> nodes, int start, int end) {
		for (int i = start; i < end; i++) {
			AbstractInsnNode insn = nodes.get(i);
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
				|| insn instanceof LookupSwitchInsnNode) {
				return true;
			}
		}
		return false;
	}

	private static int loadValueSize(int opcode) {
		return switch (opcode) {
			case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> 1;
			case Opcodes.LLOAD, Opcodes.DLOAD -> 2;
			default -> -1;
		};
	}

	private static boolean isStoreTo(AbstractInsnNode insn, int slot) {
		if (!(insn instanceof VarInsnNode variable)) return false;
		int opcode = variable.getOpcode();
		boolean store = opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE
		                || opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE;
		return store && variable.var == slot;
	}

	/**
	 * Walks backwards from a field write and returns the index of the first instruction of the value
	 * expression, or {@code -1} when the instruction shapes cannot be modelled.
	 *
	 * <p>The reverse stack simulation is what makes the A0 slice cover the whole
	 * {@code NEW T(c...); INVOKESPECIAL T.<init>} chain: a simple "stop at the previous
	 * {@code INVOKESPECIAL <init>}" rule would stop <b>inside</b> the new expression and leave only the
	 * trailing {@code PUTFIELD}, which is exactly the drift detector behaviour this channel must not
	 * reuse.</p>
	 */
	private static int expressionStart(List<AbstractInsnNode> nodes, int putIndex, int valueSize) {
		int need = valueSize;
		for (int i = putIndex - 1; i >= 0; i--) {
			AbstractInsnNode insn = nodes.get(i);
			if (insn.getOpcode() < 0) continue;
			int[] effect = stackEffect(insn);
			if (effect == null) return -1;
			need = need + effect[0] - effect[1];
			if (need == 0) return i;
			if (need < 0) return -1;
		}
		return -1;
	}

	private static MethodInsnNode terminalCtorCall(List<AbstractInsnNode> expression) {
		AbstractInsnNode last = expression.get(expression.size() - 1);
		if (!(last instanceof MethodInsnNode call)) return null;
		if (call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) return null;
		return call;
	}

	private static boolean containsSyntheticDefaultCall(List<AbstractInsnNode> expression) {
		for (AbstractInsnNode insn : expression) {
			if (insn instanceof MethodInsnNode call && call.name.endsWith("$default")) return true;
		}
		return false;
	}

	private static boolean isDefaultMarkerCtor(MethodInsnNode call) {
		Type[] args = Type.getArgumentTypes(call.desc);
		return args.length > 0 && args[args.length - 1].getDescriptor().equals(DEFAULT_MARKER);
	}

	private static boolean hasNewOf(List<AbstractInsnNode> expression, String internalName) {
		for (AbstractInsnNode insn : expression) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
				&& type.desc.equals(internalName)) return true;
		}
		return false;
	}

	/**
	 * A1 argument reconstruction: every {@code new T(c...)} argument must be a primitive/String
	 * compile-time constant or a whitelisted boxing value; {@code $default} / {@code DefaultConstructorMarker}
	 * shapes are rejected.
	 *
	 * @return {@code null} when the expression is reconstructible, otherwise an English rejection detail
	 */
	private static String validateArguments(List<AbstractInsnNode> expression) {
		Deque<Object> stack = new ArrayDeque<>();
		Object newMarker = new Object();
		for (AbstractInsnNode insn : expression) {
			int opcode = insn.getOpcode();
			if (insn instanceof TypeInsnNode type && opcode == Opcodes.NEW) {
				stack.push(newMarker);
				continue;
			}
			if (opcode == Opcodes.DUP) {
				if (stack.isEmpty() || stack.peek() != newMarker) {
					return "constructor argument is not a compile-time constant";
				}
				stack.push(newMarker);
				continue;
			}
			ArgConst constant = constantArgument(insn);
			if (constant != null) {
				stack.push(constant);
				continue;
			}
			if (insn instanceof MethodInsnNode call) {
				if (call.getOpcode() == Opcodes.INVOKESTATIC && isBoxingValueOf(call)) {
					if (stack.isEmpty() || !(stack.peek() instanceof ArgConst primitive)) {
						return "boxing call argument is not a compile-time constant";
					}
					ArgConst boxed = box(primitive, call.owner, call.desc);
					if (boxed == null) return "unsupported boxing conversion " + call.owner + "." + call.name + call.desc;
					stack.pop();
					stack.push(boxed);
					continue;
				}
				if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)) {
					Type[] argTypes = Type.getArgumentTypes(call.desc);
					Object[] args = new Object[argTypes.length];
					for (int i = argTypes.length - 1; i >= 0; i--) {
						if (stack.isEmpty()) return "constructor argument stack underflow";
						args[i] = stack.pop();
					}
					if (stack.isEmpty() || stack.pop() != newMarker) {
						return "constructor receiver was not produced by NEW";
					}
					for (int i = 0; i < argTypes.length; i++) {
						if (!(args[i] instanceof ArgConst constantArg)) {
							return "constructor argument #" + (i + 1) + " is not a compile-time constant";
						}
						if (!constantArg.matches(argTypes[i])) {
							return "constructor argument #" + (i + 1) + " constant does not match "
							       + argTypes[i].getDescriptor();
						}
					}
					continue;
				}
			}
			return "initializer expression contains an instruction that cannot be reconstructed from constants";
		}
		if (stack.size() != 1 || stack.pop() != newMarker) {
			return "initializer expression did not produce exactly one constructed value";
		}
		return null;
	}

	/** A constant argument kind that can be re-emitted by a patch. */
	private record ArgConst(char kind, String boxedDesc) {

		boolean matches(Type expected) {
			String desc = expected.getDescriptor();
			if (boxedDesc != null) return boxedDesc.equals(desc);
			return switch (kind) {
				case 'I' -> desc.equals("I") || desc.equals("Z") || desc.equals("B") || desc.equals("C") || desc.equals("S");
				case 'J' -> desc.equals("J");
				case 'F' -> desc.equals("F");
				case 'D' -> desc.equals("D");
				case 'S' -> desc.equals("Ljava/lang/String;");
				default -> false;
			};
		}
	}

	private static ArgConst constantArgument(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return new ArgConst('I', null);
		if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return new ArgConst('I', null);
		if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return new ArgConst('J', null);
		if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return new ArgConst('F', null);
		if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return new ArgConst('D', null);
		if (insn instanceof LdcInsnNode ldc) {
			Object cst = ldc.cst;
			if (cst instanceof Integer) return new ArgConst('I', null);
			if (cst instanceof Long) return new ArgConst('J', null);
			if (cst instanceof Float) return new ArgConst('F', null);
			if (cst instanceof Double) return new ArgConst('D', null);
			if (cst instanceof String) return new ArgConst('S', null);
		}
		return null;
	}

	private static boolean isBoxingValueOf(MethodInsnNode call) {
		if (!"valueOf".equals(call.name) || !BOX_OWNERS.contains(call.owner)) return false;
		Type[] args = Type.getArgumentTypes(call.desc);
		Type ret = Type.getReturnType(call.desc);
		return args.length == 1 && ret.getDescriptor().equals("L" + call.owner + ";");
	}

	private static ArgConst box(ArgConst primitive, String owner, String desc) {
		Type[] args = Type.getArgumentTypes(desc);
		if (args.length != 1) return null;
		ArgConst expected = switch (owner) {
			case "java/lang/Integer" -> new ArgConst('I', null);
			case "java/lang/Long" -> new ArgConst('J', null);
			case "java/lang/Short" -> new ArgConst('I', null);
			case "java/lang/Byte" -> new ArgConst('I', null);
			case "java/lang/Character" -> new ArgConst('I', null);
			case "java/lang/Boolean" -> new ArgConst('I', null);
			case "java/lang/Float" -> new ArgConst('F', null);
			case "java/lang/Double" -> new ArgConst('D', null);
			default -> null;
		};
		if (expected == null || primitive.kind() != expected.kind()) return null;
		if (!primitive.matches(args[0])) return null;
		return new ArgConst('B', "L" + owner + ";");
	}

	/** Normalizes one expression into the A0 fingerprint text; raw bits keep float/double exact. */
	private static String expressionFingerprint(List<AbstractInsnNode> expression) {
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode insn : expression) {
			sb.append(insn.getOpcode()).append(':').append(instructionText(insn)).append(';');
		}
		return sha256(sb.toString());
	}

	private static String instructionText(AbstractInsnNode insn) {
		if (insn instanceof FieldInsnNode field) return field.owner + '.' + field.name + ':' + field.desc;
		if (insn instanceof MethodInsnNode call) {
			return call.owner + '.' + call.name + call.desc + (call.itf ? ":itf" : "");
		}
		if (insn instanceof TypeInsnNode type) return type.desc;
		if (insn instanceof VarInsnNode variable) return "v" + variable.var;
		if (insn instanceof IntInsnNode integer) return "int" + integer.operand;
		if (insn instanceof IincInsnNode increment) return "iinc" + increment.var + "+" + increment.incr;
		if (insn instanceof LdcInsnNode ldc) return "ldc(" + constantText(ldc.cst) + ")";
		if (insn instanceof InvokeDynamicInsnNode indy) {
			StringBuilder sb = new StringBuilder("indy:").append(indy.name).append(indy.desc)
				.append(":bsm=").append(handleText(indy.bsm));
			if (indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) sb.append("|a=").append(constantText(arg));
			}
			return sb.toString();
		}
		return "";
	}

	private static String constantText(Object constant) {
		if (constant == null) return "null";
		if (constant instanceof Float value) return "f32:" + Float.floatToRawIntBits(value);
		if (constant instanceof Double value) return "f64:" + Double.doubleToRawLongBits(value);
		if (constant instanceof Long value) return "l64:" + value;
		if (constant instanceof Integer value) return "i32:" + value;
		if (constant instanceof String value) return "str:" + value;
		if (constant instanceof Type type) return "type:" + type.getDescriptor();
		if (constant instanceof Handle handle) return "handle:" + handleText(handle);
		if (constant instanceof ConstantDynamic dynamic) return "cdy:" + dynamic.getName() + dynamic.getDescriptor();
		return String.valueOf(constant);
	}

	private static String handleText(Handle handle) {
		return handle.getTag() + ':' + handle.getOwner() + '.' + handle.getName() + handle.getDesc();
	}

	// ==================== root constructors / A5 consensus helpers ====================

	private static Set<String> rootConstructors(ClassNode host) {
		Set<String> roots = new LinkedHashSet<>();
		for (MethodNode method : host.methods) {
			if (!"<init>".equals(method.name)) continue;
			if (isRootConstructor(host, method)) roots.add(methodKey(method));
		}
		return roots;
	}

	/**
	 * A constructor is a root when it does not delegate to another constructor of the same class via
	 * {@code this(...)}. Mirroring {@code InitFixSliceExtractor.isRootConstructor}: delegate detection
	 * uses the abstract stack frame, so a {@code new Host(...)} field initializer inside the constructor
	 * is not mistaken for delegation.
	 */
	private static boolean isRootConstructor(ClassNode host, MethodNode method) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(host.name, method);
		} catch (AnalyzerException | RuntimeException e) {
			return true; // cannot prove delegation: treat as root (fail-closed for consensus)
		}
		for (int i = 0; i < method.instructions.size(); i++) {
			AbstractInsnNode insn = method.instructions.get(i);
			if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
				|| !"<init>".equals(call.name) || !call.owner.equals(host.name)) continue;
			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;
			int argCount = Type.getArgumentTypes(call.desc).length;
			int receiverIndex = frame.getStackSize() - 1 - argCount;
			if (receiverIndex < 0) continue;
			SourceValue receiver = frame.getStack(receiverIndex);
			if (receiver == null) continue;
			for (AbstractInsnNode source : receiver.insns) {
				if (source instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == 0) return false;
			}
		}
		return true;
	}

	private static Map<String, FieldNode> declaredFields(ClassNode host) {
		Map<String, FieldNode> fields = new LinkedHashMap<>();
		for (FieldNode field : host.fields) fields.put(field.name, field);
		return fields;
	}

	private static boolean skipField(FieldNode field) {
		if ((field.access & Opcodes.ACC_SYNTHETIC) != 0) return true;
		return field.name.startsWith("this$") || field.name.startsWith("val$");
	}

	private static boolean isRecord(ClassNode node) {
		return (node.access & Opcodes.ACC_RECORD) != 0 || RECORD.equals(node.superName);
	}

	private static boolean isHiddenName(String internalName) {
		return internalName != null && internalName.contains("/0x");
	}

	// ==================== A2 + A3: type chain validation ====================

	private static final class Fingerprints {
		private String ctorFingerprint;
		private String equalsFingerprint;
	}

	/**
	 * Resolves {@code T} bytes, validates shape / generated equals / constructor purity across the super
	 * chain (A2 + A3) and fills the constructor and equals fingerprints.
	 *
	 * @param oldCtorDesc constructor descriptor used by the old expression (for the applied key)
	 * @param newCtorDesc constructor descriptor used by the new expression
	 */
	private static Reason checkType(String typeName, String oldCtorDesc, String newCtorDesc,
	                                Function<String, byte[]> classBytes, Predicate<String> batchCandidate,
	                                Fingerprints fingerprints, Map<String, String> details, String fieldName) {
		if (batchCandidate.test(typeName)) {
			details.put(fieldName, "constructed type " + typeName
			                       + " is redefined in this batch; rebuilding the old value would use the new constructor");
			return Reason.CONSTRUCTED_CHANGED;
		}
		byte[] bytes = classBytes.apply(typeName);
		if (bytes == null) {
			details.put(fieldName, "bytes for constructed type " + typeName + " are unavailable");
			return Reason.CTOR_CHAIN_UNRESOLVED;
		}
		ClassNode type;
		try {
			type = parse(bytes);
		} catch (RuntimeException e) {
			details.put(fieldName, "bytes for constructed type " + typeName + " cannot be parsed");
			return Reason.CTOR_CHAIN_UNRESOLVED;
		}
		if (!typeName.equals(type.name)) {
			details.put(fieldName, "bytes for constructed type " + typeName + " declare " + type.name);
			return Reason.CTOR_CHAIN_UNRESOLVED;
		}
		Reason shape = shapeReason(type);
		if (shape != null) {
			details.put(fieldName, shapeDetail(type, shape));
			return shape;
		}

		List<String> chainTexts = new ArrayList<>();
		Set<String> closureOwners = new LinkedHashSet<>();
		String currentName = typeName;
		String currentCtorDesc = oldCtorDesc;
		ClassNode current = type;
		Set<String> visited = new HashSet<>();
		while (true) {
			if (!visited.add(currentName)) {
				details.put(fieldName, "constructor chain cycle at " + currentName);
				return Reason.CTOR_CHAIN_UNRESOLVED;
			}
			MethodNode ctor = findConstructor(current, currentCtorDesc);
			if (ctor == null) {
				details.put(fieldName, "constructor " + currentName + "." + currentCtorDesc + " is missing");
				return Reason.CTOR_CHAIN_UNRESOLVED;
			}
			Reason clinit = clinitReason(current);
			if (clinit != null) {
				details.put(fieldName, "static initializer of " + currentName + " has side effects");
				return clinit;
			}
			Purity purity = validateConstructor(current, ctor, classBytes);
			if (purity.problem() != null) {
				details.put(fieldName, "constructor " + currentName + "." + currentCtorDesc + " is impure: "
				                       + purity.problem());
				return Reason.CTOR_IMPURE;
			}
			closureOwners.addAll(purity.closureOwners());
			chainTexts.add(currentName + "#" + currentCtorDesc + '\n'
			               + BytecodeNormalFingerprint.normalizedMethod(ctor));
			if (purity.superOwner() == null) {
				details.put(fieldName, "constructor " + currentName + "." + currentCtorDesc
				                       + " does not call a super or this constructor");
				return Reason.CTOR_CHAIN_UNRESOLVED;
			}
			if (batchCandidate.test(purity.superOwner())) {
				details.put(fieldName, "super class " + purity.superOwner()
				                       + " is redefined in this batch; the old value cannot be rebuilt");
				return Reason.CONSTRUCTED_CHANGED;
			}
			if (isBootstrap(purity.superOwner())) break;
			byte[] superBytes = classBytes.apply(purity.superOwner());
			if (superBytes == null) {
				details.put(fieldName, "bytes for super class " + purity.superOwner() + " are unavailable");
				return Reason.CTOR_CHAIN_UNRESOLVED;
			}
			ClassNode superNode = parse(superBytes);
			if (!purity.superOwner().equals(superNode.name)) {
				details.put(fieldName, "bytes for super class " + purity.superOwner()
				                       + " declare " + superNode.name);
				return Reason.CTOR_CHAIN_UNRESOLVED;
			}
			currentName = purity.superOwner();
			currentCtorDesc = purity.superDesc();
			current = superNode;
		}
		for (String owner : closureOwners) {
			if (batchCandidate.test(owner)) {
				details.put(fieldName, "verified call closure class " + owner
				                       + " is redefined in this batch; the constructor is not provably stable");
				return Reason.CONSTRUCTED_CHANGED;
			}
		}

		MethodNode newCtor = findConstructor(type, newCtorDesc);
		if (newCtor == null) {
			details.put(fieldName, "constructor " + typeName + "." + newCtorDesc + " is missing");
			return Reason.CTOR_CHAIN_UNRESOLVED;
		}
		if (!newCtorDesc.equals(oldCtorDesc)) {
			Purity newPurity = validateConstructor(type, newCtor, classBytes);
			if (newPurity.problem() != null) {
				details.put(fieldName, "constructor " + typeName + "." + newCtorDesc + " is impure: "
				                       + newPurity.problem());
				return Reason.CTOR_IMPURE;
			}
			chainTexts.add(typeName + "#" + newCtorDesc + '\n'
			               + BytecodeNormalFingerprint.normalizedMethod(newCtor));
			closureOwners.addAll(newPurity.closureOwners());
		}
		fingerprints.ctorFingerprint = sha256(String.join("\n", chainTexts));
		MethodNode equals = findMethod(type, "equals", "(Ljava/lang/Object;)Z");
		MethodNode hashCode = findMethod(type, "hashCode", "()I");
		if (equals == null || hashCode == null) {
			details.put(fieldName, "type " + typeName + " has no generated equals/hashCode pair");
			return Reason.EQ_NOT_PURE;
		}
		fingerprints.equalsFingerprint = sha256(
			BytecodeNormalFingerprint.normalizedMethod(equals) + '\n'
			+ BytecodeNormalFingerprint.normalizedMethod(hashCode));
		return null;
	}

	private static boolean isBootstrap(String internalName) {
		for (String prefix : BOOTSTRAP_PREFIXES) {
			if (internalName.startsWith(prefix)) return true;
		}
		return false;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode findConstructor(ClassNode type, String desc) {
		return findMethod(type, "<init>", desc);
	}

	private static MethodNode findMethod(ClassNode type, String name, String desc) {
		for (MethodNode method : type.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static String shapeDetail(ClassNode type, Reason reason) {
		if (reason == Reason.EQ_CUSTOM) {
			return "type " + type.name + " declares a custom equals/hashCode (compiler-generated shape expected)";
		}
		return "type " + type.name
		       + " is not an accepted Java record / Kotlin data class shape (primitive or String components only, "
		       + "no extra instance fields, no stateful super class)";
	}

	// ==================== A3 shape gate ====================

	private static Reason shapeReason(ClassNode type) {
		if (isRecord(type)) return recordShapeReason(type);
		if (hasKotlinMetadata(type)) return kotlinShapeReason(type);
		return Reason.EQ_NOT_PURE;
	}

	private static Reason recordShapeReason(ClassNode type) {
		if (!RECORD.equals(type.superName)) return Reason.EQ_NOT_PURE;
		if ((type.access & Opcodes.ACC_FINAL) == 0) return Reason.EQ_NOT_PURE;
		if (type.recordComponents == null) return Reason.EQ_NOT_PURE;
		Set<String> components = new LinkedHashSet<>();
		for (var component : type.recordComponents) {
			if (!isPrimitiveOrString(component.descriptor)) return Reason.EQ_NOT_PURE;
			components.add(component.name + ":" + component.descriptor);
		}
		Set<String> instanceFields = new LinkedHashSet<>();
		for (FieldNode field : type.fields) {
			if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
			int required = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
			if ((field.access & required) != required) return Reason.EQ_NOT_PURE;
			if (!isPrimitiveOrString(field.desc)) return Reason.EQ_NOT_PURE;
			instanceFields.add(field.name + ":" + field.desc);
		}
		if (!instanceFields.equals(components)) return Reason.EQ_NOT_PURE;

		MethodNode equals = findMethod(type, "equals", "(Ljava/lang/Object;)Z");
		MethodNode hashCode = findMethod(type, "hashCode", "()I");
		if (equals == null || hashCode == null) return Reason.EQ_NOT_PURE;
		if (!isGeneratedRecordEquals(type, equals)) return Reason.EQ_CUSTOM;
		if (!isGeneratedRecordHashCode(type, hashCode)) return Reason.EQ_CUSTOM;
		return null;
	}

	private static boolean isGeneratedRecordEquals(ClassNode type, MethodNode method) {
		List<AbstractInsnNode> nodes = realInstructions(method);
		if (nodes.size() != 4) return false;
		if (!isVarLoad(nodes.get(0), 0) || !isVarLoad(nodes.get(1), 1)) return false;
		if (!isObjectMethodsIndy(nodes.get(2), "equals", type)) return false;
		return nodes.get(3).getOpcode() == Opcodes.IRETURN;
	}

	private static boolean isGeneratedRecordHashCode(ClassNode type, MethodNode method) {
		List<AbstractInsnNode> nodes = realInstructions(method);
		if (nodes.size() != 3) return false;
		if (!isVarLoad(nodes.get(0), 0)) return false;
		if (!isObjectMethodsIndy(nodes.get(1), "hashCode", type)) return false;
		return nodes.get(2).getOpcode() == Opcodes.IRETURN;
	}

	private static boolean isObjectMethodsIndy(AbstractInsnNode insn, String expectedName, ClassNode type) {
		if (!(insn instanceof InvokeDynamicInsnNode indy)) return false;
		if (!expectedName.equals(indy.name) || !OBJECT_METHODS.equals(indy.bsm.getOwner())
			|| !"bootstrap".equals(indy.bsm.getName())) return false;
		if (indy.bsmArgs == null || indy.bsmArgs.length < 2) return false;
		if (!(indy.bsmArgs[0] instanceof Type bsType) || !bsType.getInternalName().equals(type.name)) return false;
		return indy.bsmArgs[1] instanceof String;
	}

	private static boolean hasKotlinMetadata(ClassNode type) {
		if (type.visibleAnnotations != null) {
			for (var annotation : type.visibleAnnotations) {
				if (KOTLIN_METADATA.equals(annotation.desc)) return true;
			}
		}
		if (type.invisibleAnnotations != null) {
			for (var annotation : type.invisibleAnnotations) {
				if (KOTLIN_METADATA.equals(annotation.desc)) return true;
			}
		}
		return false;
	}

	private static Reason kotlinShapeReason(ClassNode type) {
		if (!OBJECT.equals(type.superName)) return Reason.EQ_NOT_PURE;
		if ((type.access & Opcodes.ACC_FINAL) == 0) return Reason.EQ_NOT_PURE;
		List<FieldNode> instanceFields = new ArrayList<>();
		for (FieldNode field : type.fields) {
			if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
			int required = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
			if ((field.access & required) != required) return Reason.EQ_NOT_PURE;
			if (!isPrimitiveOrString(field.desc)) return Reason.EQ_NOT_PURE;
			instanceFields.add(field);
		}
		if (instanceFields.isEmpty()) return Reason.EQ_NOT_PURE;

		List<MethodNode> components = new ArrayList<>();
		for (int index = 1; index <= instanceFields.size(); index++) {
			MethodNode component = findMethod(type, "component" + index, "()" + instanceFields.get(index - 1).desc);
			if (component == null) return Reason.EQ_NOT_PURE;
			if (!returnsField(type, component, instanceFields.get(index - 1))) return Reason.EQ_NOT_PURE;
			components.add(component);
		}
		if (components.size() != instanceFields.size()) return Reason.EQ_NOT_PURE;
		if (!hasCopyMethod(type, instanceFields)) return Reason.EQ_NOT_PURE;

		MethodNode equals = findMethod(type, "equals", "(Ljava/lang/Object;)Z");
		MethodNode hashCode = findMethod(type, "hashCode", "()I");
		if (equals == null || hashCode == null) return Reason.EQ_NOT_PURE;
		if (!looksLikeKotlinEquals(type, equals)) return Reason.EQ_CUSTOM;
		if (!looksLikeKotlinHashCode(type, hashCode)) return Reason.EQ_CUSTOM;
		return null;
	}

	private static boolean returnsField(ClassNode type, MethodNode method, FieldNode field) {
		List<AbstractInsnNode> nodes = realInstructions(method);
		if (nodes.size() != 3) return false;
		if (!isVarLoad(nodes.get(0), 0)) return false;
		if (!(nodes.get(1) instanceof FieldInsnNode get) || get.getOpcode() != Opcodes.GETFIELD) return false;
		if (!get.owner.equals(type.name) || !get.name.equals(field.name) || !get.desc.equals(field.desc)) return false;
		int opcode = nodes.get(2).getOpcode();
		return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
		       || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN;
	}

	private static boolean hasCopyMethod(ClassNode type, List<FieldNode> fields) {
		StringBuilder desc = new StringBuilder("(");
		for (FieldNode field : fields) desc.append(field.desc);
		desc.append(")L").append(type.name).append(';');
		MethodNode copy = findMethod(type, "copy", desc.toString());
		if (copy == null) return false;
		boolean newFound = false;
		boolean ctorFound = false;
		for (AbstractInsnNode insn : realInstructions(copy)) {
			if (insn instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == Opcodes.NEW
				&& typeInsn.desc.equals(type.name)) newFound = true;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
				&& "<init>".equals(call.name) && call.owner.equals(type.name)) ctorFound = true;
		}
		return newFound && ctorFound;
	}

	private static boolean looksLikeKotlinEquals(ClassNode type, MethodNode method) {
		List<AbstractInsnNode> nodes = realInstructions(method);
		if (nodes.size() < 10) return false;
		// kotlinc 2.x prologue exactly as measured on tests/ktfix fixtures:
		// aload_0; aload_1; if_acmpne; iconst_1; ireturn; aload_1; instanceof T; ifne;
		// iconst_0; ireturn; aload_1; checkcast T; astore; ...
		if (!isVarLoad(nodes.get(0), 0) || !isVarLoad(nodes.get(1), 1)) return false;
		if (nodes.get(2).getOpcode() != Opcodes.IF_ACMPNE) return false;
		if (nodes.get(3).getOpcode() != Opcodes.ICONST_1 || nodes.get(4).getOpcode() != Opcodes.IRETURN) return false;
		if (!isVarLoad(nodes.get(5), 1)) return false;
		if (!(nodes.get(6) instanceof TypeInsnNode instanceofInsn)
			|| instanceofInsn.getOpcode() != Opcodes.INSTANCEOF
			|| !instanceofInsn.desc.equals(type.name)) return false;
		if (nodes.get(7).getOpcode() != Opcodes.IFNE) return false;
		if (nodes.get(8).getOpcode() != Opcodes.ICONST_0 || nodes.get(9).getOpcode() != Opcodes.IRETURN) return false;
		AbstractInsnNode last = nodes.get(nodes.size() - 1);
		AbstractInsnNode beforeLast = nodes.get(nodes.size() - 2);
		if (last.getOpcode() != Opcodes.IRETURN || beforeLast.getOpcode() != Opcodes.ICONST_1) return false;
		boolean castFound = false;
		boolean storeFound = false;
		boolean getFieldFound = false;
		for (AbstractInsnNode insn : nodes) {
			int opcode = insn.getOpcode();
			if (insn instanceof TypeInsnNode cast && opcode == Opcodes.CHECKCAST) {
				if (!cast.desc.equals(type.name)) return false;
				castFound = true;
				continue;
			}
			if (insn instanceof VarInsnNode store && opcode == Opcodes.ASTORE) {
				storeFound = true;
				continue;
			}
			if (insn instanceof FieldInsnNode field) {
				if (opcode != Opcodes.GETFIELD || !field.owner.equals(type.name)) return false;
				getFieldFound = true;
				continue;
			}
			if (insn instanceof MethodInsnNode call) {
				if (call.getOpcode() != Opcodes.INVOKESTATIC || !INTRINSICS.equals(call.owner)
					|| !"areEqual".equals(call.name)) return false;
				continue;
			}
			if (insn instanceof VarInsnNode load && opcode == Opcodes.ALOAD) continue;
			if (isAllowedKotlinEqualsOpcode(opcode)) continue;
			return false;
		}
		return castFound && storeFound && getFieldFound;
	}

	private static boolean isAllowedKotlinEqualsOpcode(int opcode) {
		return opcode == Opcodes.IF_ACMPNE || opcode == Opcodes.IFNE || opcode == Opcodes.IFEQ
		       || opcode == Opcodes.INSTANCEOF || opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE
		       || opcode == Opcodes.ICONST_0 || opcode == Opcodes.ICONST_1
		       || opcode == Opcodes.IRETURN || opcode == Opcodes.GOTO;
	}

	private static boolean looksLikeKotlinHashCode(ClassNode type, MethodNode method) {
		List<AbstractInsnNode> nodes = realInstructions(method);
		if (nodes.size() < 2) return false;
		if (!isVarLoad(nodes.get(0), 0)) return false;
		if (nodes.get(nodes.size() - 1).getOpcode() != Opcodes.IRETURN) return false;
		boolean getFieldFound = false;
		for (AbstractInsnNode insn : nodes) {
			int opcode = insn.getOpcode();
			if (insn instanceof FieldInsnNode field) {
				if (opcode != Opcodes.GETFIELD || !field.owner.equals(type.name)) return false;
				getFieldFound = true;
				continue;
			}
			if (insn instanceof MethodInsnNode call) {
				if (!isKotlinHashCall(call)) return false;
				continue;
			}
			if (insn instanceof LdcInsnNode ldc) {
				if (!(ldc.cst instanceof Integer) && !(ldc.cst instanceof Long)) return false;
				continue;
			}
			if (insn instanceof VarInsnNode variable) {
				if (opcode == Opcodes.ALOAD && variable.var == 0) continue;
				if (opcode == Opcodes.ILOAD || opcode == Opcodes.ISTORE) continue;
				return false;
			}
			if (isAllowedKotlinHashOpcode(opcode)) continue;
			return false;
		}
		return getFieldFound;
	}

	private static boolean isKotlinHashCall(MethodInsnNode call) {
		if (call.getOpcode() == Opcodes.INVOKESTATIC) {
			if (BOX_OWNERS.contains(call.owner) && "hashCode".equals(call.name)) {
				Type[] args = Type.getArgumentTypes(call.desc);
				return args.length == 1 && Type.getReturnType(call.desc).getSort() == Type.INT;
			}
			return INTRINSICS.equals(call.owner) && "hashCode".equals(call.name);
		}
		if (call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
			if (!"hashCode".equals(call.name) || !"()I".equals(call.desc)) return false;
			return "java/lang/String".equals(call.owner) || OBJECT.equals(call.owner);
		}
		return false;
	}

	private static boolean isAllowedKotlinHashOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
				Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH,
				Opcodes.IMUL, Opcodes.IADD, Opcodes.ISUB, Opcodes.INEG, Opcodes.ISHL, Opcodes.ISHR,
				Opcodes.IUSHR, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.IRETURN,
				Opcodes.NOP -> true;
			default -> false;
		};
	}

	private static boolean isPrimitiveOrString(String desc) {
		if ("Ljava/lang/String;".equals(desc)) return true;
		return desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
	}

	// ==================== A3 purity gate ====================

	/**
	 * Purity outcome.
	 *
	 * @param problem       English problem description, {@code null} when pure
	 * @param superOwner    internal name passed to the super/this constructor call
	 * @param superDesc     descriptor of that constructor call
	 * @param closureOwners owners of whitelisted calls (A2 batch-stability closure)
	 */
	private record Purity(String problem, String superOwner, String superDesc, Set<String> closureOwners) {
		static Purity rejected(String problem) {
			return new Purity(problem, null, null, Set.of());
		}
	}

	/**
	 * A3 constructor purity allowlist: reads constructor parameters, writes {@code this} fields,
	 * boxing/unboxing, arithmetic/constants, {@code ConstantValue} {@code GETSTATIC}, validation
	 * exceptions and a small whitelist of provably pure calls. Everything else is denied.
	 */
	private static Purity validateConstructor(ClassNode type, MethodNode ctor,
	                                                Function<String, byte[]> classBytes) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(type.name, ctor);
		} catch (AnalyzerException | RuntimeException e) {
			return Purity.rejected("flow analysis failed (" + e.getClass().getSimpleName() + ")");
		}
		List<AbstractInsnNode> nodes = new ArrayList<>();
		for (AbstractInsnNode insn = ctor.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			nodes.add(insn);
		}
		Set<Integer> validationThrows = validationThrowInstructions(nodes);
		int lastRealIndex = -1;
		for (int i = nodes.size() - 1; i >= 0; i--) {
			if (nodes.get(i).getOpcode() >= 0) {
				lastRealIndex = i;
				break;
			}
		}
		int paramSlots = argumentSlots(ctor.desc);
		Set<String> closureOwners = new LinkedHashSet<>();
		String superOwner = null;
		String superDesc = null;
		for (int i = 0; i < nodes.size(); i++) {
			AbstractInsnNode insn = nodes.get(i);
			if (insn.getOpcode() < 0) continue;
			if (validationThrows.contains(i)) continue;
			int opcode = insn.getOpcode();
			if (insn instanceof VarInsnNode variable) {
				if (variable.var < 0 || variable.var > paramSlots) {
					return Purity.rejected("touches local slot " + variable.var + " outside the constructor parameters");
				}
				boolean load = opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD
				               || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD;
				boolean store = opcode == Opcodes.ISTORE || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE
				                || opcode == Opcodes.DSTORE || opcode == Opcodes.ASTORE;
				if (!load && !store) return Purity.rejected("unsupported local variable instruction");
				if (store && variable.var == 0) return Purity.rejected("overwrites 'this'");
				continue;
			}
			if (insn instanceof IincInsnNode increment) {
				if (increment.var < 1 || increment.var > paramSlots) {
					return Purity.rejected("increments a non-parameter local slot");
				}
				continue;
			}
			if (isConstantPush(insn)) continue;
			if (isStackOrArithmeticOpcode(opcode)) continue;
			if (insn instanceof FieldInsnNode field) {
				if (opcode == Opcodes.GETSTATIC) {
					if (!isConstantValueField(field, classBytes)) {
						return Purity.rejected("reads a static field that is not a compile-time ConstantValue ("
						                       + field.owner + '.' + field.name + ")");
					}
					closureOwners.add(field.owner);
					continue;
				}
				if (opcode == Opcodes.GETFIELD) return Purity.rejected("reads an instance field");
				if (opcode == Opcodes.PUTFIELD) {
					if (!field.owner.equals(type.name)) {
						return Purity.rejected("writes an instance field of a foreign class " + field.owner);
					}
					Frame<SourceValue> frame = frames[i];
					if (frame == null) return Purity.rejected("missing stack frame for a field write");
					int valueSize = Type.getType(field.desc).getSize();
					int receiverIndex = frame.getStackSize() - 1 - valueSize;
					if (receiverIndex < 0) return Purity.rejected("invalid field write stack shape");
					if (!isThis(frame.getStack(receiverIndex))) {
						return Purity.rejected("writes a field on a receiver that is not 'this'");
					}
					continue;
				}
				return Purity.rejected("writes a static field (PUTSTATIC)");
			}
			if (insn instanceof MethodInsnNode call) {
				if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)) {
					if (!call.owner.equals(type.name) && !call.owner.equals(type.superName)) {
						return Purity.rejected("constructs another object (" + call.owner + ")");
					}
					if (superOwner != null) return Purity.rejected("calls more than one super/this constructor");
					superOwner = call.owner;
					superDesc = call.desc;
					continue;
				}
				if (opcode == Opcodes.INVOKESTATIC && isWhitelistedCall(call)) {
					closureOwners.add(call.owner);
					continue;
				}
				return Purity.rejected("calls a non-whitelisted method " + call.owner + '.' + call.name + call.desc);
			}
			if (insn instanceof TypeInsnNode typeInsn && opcode == Opcodes.NEW) {
				return Purity.rejected("constructs another object (" + typeInsn.desc + ")");
			}
			if (opcode == Opcodes.ATHROW) continue;
			if (opcode == Opcodes.RETURN) {
				if (i != lastRealIndex) return Purity.rejected("returns before the end of the constructor");
				continue;
			}
			return Purity.rejected("instruction opcode " + opcode + " is not in the constructor purity allowlist");
		}
		if (superOwner == null) return Purity.rejected("never calls a super or this constructor");
		return new Purity(null, superOwner, superDesc, closureOwners);
	}

	private static boolean isThis(SourceValue value) {
		if (value == null) return false;
		for (AbstractInsnNode source : value.insns) {
			if (source instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == 0) return true;
		}
		return false;
	}

	private static int argumentSlots(String methodDesc) {
		int slots = 0;
		for (Type argument : Type.getArgumentTypes(methodDesc)) slots += argument.getSize();
		return slots;
	}

	private static boolean isConstantPush(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode == Opcodes.ACONST_NULL) return true;
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return true;
		if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return true;
		if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return true;
		if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return true;
		if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return true;
		if (insn instanceof LdcInsnNode ldc) {
			return ldc.cst instanceof Integer || ldc.cst instanceof Long || ldc.cst instanceof Float
			       || ldc.cst instanceof Double || ldc.cst instanceof String;
		}
		return false;
	}

	private static boolean isStackOrArithmeticOpcode(int opcode) {
		return switch (opcode) {
			case Opcodes.NOP, Opcodes.POP, Opcodes.POP2, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2,
				Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.SWAP,
				Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
				Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
				Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
				Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
				Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM,
				Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,
				Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
				Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LAND,
				Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR, Opcodes.LXOR,
				Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
				Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
				Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
				Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> true;
			default -> false;
		};
	}

	private static boolean isWhitelistedCall(MethodInsnNode call) {
		if ("java/util/Objects".equals(call.owner) && "requireNonNull".equals(call.name)) return true;
		if (INTRINSICS.equals(call.owner)
			&& ("checkNotNullParameter".equals(call.name) || "checkNotNull".equals(call.name))) return true;
		return isBoxingValueOf(call);
	}

	/**
	 * A {@code GETSTATIC} is pure only when it reads a compile-time {@code ConstantValue} field, which
	 * the JVM has no reason to observe. Bootstrap/JDK owners cannot be resolved through the injected
	 * byte source, so reads such as {@code Integer.MAX_VALUE} are rejected fail-closed.
	 */
	private static boolean isConstantValueField(FieldInsnNode read, Function<String, byte[]> classBytes) {
		byte[] bytes = classBytes.apply(read.owner);
		if (bytes == null) return false;
		ClassNode owner;
		try {
			owner = parse(bytes);
		} catch (RuntimeException e) {
			return false;
		}
		if (!owner.name.equals(read.owner)) return false;
		for (FieldNode field : owner.fields) {
			if (!field.name.equals(read.name) || !field.desc.equals(read.desc)) continue;
			int required = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
			if ((field.access & required) != required) return false;
			return field.value != null;
		}
		return false;
	}

	/**
	 * Marks index ranges of a "validation exception" throw ({@code NEW X; DUP; <consts/params>;
	 * INVOKESPECIAL X.<init>; ATHROW}), with X a {@code java.lang.*Exception}/{@code *Error}. Only these
	 * ranges may contain {@code NEW} / {@code <init>} outside the whitelist.
	 */
	private static Set<Integer> validationThrowInstructions(List<AbstractInsnNode> nodes) {
		Set<Integer> allowed = new HashSet<>();
		List<Integer> real = new ArrayList<>();
		for (int i = 0; i < nodes.size(); i++) {
			if (nodes.get(i).getOpcode() >= 0) real.add(i);
		}
		for (int position = 0; position < real.size(); position++) {
			AbstractInsnNode insn = nodes.get(real.get(position));
			if (!(insn instanceof TypeInsnNode typeInsn) || typeInsn.getOpcode() != Opcodes.NEW) continue;
			if (!isValidationException(typeInsn.desc)) continue;
			if (position + 1 >= real.size() || nodes.get(real.get(position + 1)).getOpcode() != Opcodes.DUP) continue;
			int cursor = position + 2;
			while (cursor < real.size()) {
				AbstractInsnNode current = nodes.get(real.get(cursor));
				if (current instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& "<init>".equals(call.name) && call.owner.equals(typeInsn.desc)) {
					if (cursor + 1 < real.size() && nodes.get(real.get(cursor + 1)).getOpcode() == Opcodes.ATHROW) {
						for (int k = position; k <= cursor + 1; k++) allowed.add(real.get(k));
					}
					break;
				}
				if (isConstantPush(current) || isParameterLoad(current)) {
					cursor++;
					continue;
				}
				break;
			}
		}
		return allowed;
	}

	private static boolean isParameterLoad(AbstractInsnNode insn) {
		if (!(insn instanceof VarInsnNode variable)) return false;
		int opcode = variable.getOpcode();
		return opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD
		       || opcode == Opcodes.FLOAD || opcode == Opcodes.DLOAD;
	}

	private static boolean isValidationException(String internalName) {
		if (!internalName.startsWith("java/lang/")) return false;
		return internalName.endsWith("Exception") || internalName.endsWith("Error");
	}

	private static boolean isParameterLoad(AbstractInsnNode insn, List<AbstractInsnNode> nodes, int start) {
		return insn instanceof VarInsnNode variable && (variable.getOpcode() == Opcodes.ALOAD
		                                                || variable.getOpcode() == Opcodes.ILOAD
		                                                || variable.getOpcode() == Opcodes.LLOAD
		                                                || variable.getOpcode() == Opcodes.FLOAD
		                                                || variable.getOpcode() == Opcodes.DLOAD);
	}

	private static Reason clinitReason(ClassNode type) {
		MethodNode clinit = findMethod(type, "<clinit>", "()V");
		if (clinit == null) return null;
		for (AbstractInsnNode insn : realInstructions(clinit)) {
			if (insn.getOpcode() != Opcodes.RETURN) {
				return Reason.CTOR_IMPURE;
			}
		}
		return null;
	}

	// ==================== instruction helpers ====================

	private static List<AbstractInsnNode> realInstructions(MethodNode method) {
		List<AbstractInsnNode> nodes = new ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) continue;
			nodes.add(insn);
		}
		return nodes;
	}

	private static boolean isVarLoad(AbstractInsnNode insn, int slot) {
		return insn instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == slot;
	}

	/** JVM stack effect: {@code [pops, pushes]} including long/double slot widths; {@code null} = unknown. */
	private static int[] stackEffect(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode < 0) return null;
		return switch (opcode) {
			case Opcodes.NOP -> new int[]{0, 0};
			case Opcodes.ACONST_NULL -> new int[]{0, 1};
			case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
				Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH -> new int[]{0, 1};
			case Opcodes.LCONST_0, Opcodes.LCONST_1 -> new int[]{0, 2};
			case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> new int[]{0, 1};
			case Opcodes.DCONST_0, Opcodes.DCONST_1 -> new int[]{0, 2};
			case Opcodes.LDC -> new int[]{0, ldcSize(insn)};
			case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> new int[]{0, 1};
			case Opcodes.LLOAD, Opcodes.DLOAD -> new int[]{0, 2};
			case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> new int[]{1, 0};
			case Opcodes.LSTORE, Opcodes.DSTORE -> new int[]{2, 0};
			case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
				Opcodes.SALOAD -> new int[]{2, 1};
			case Opcodes.LALOAD, Opcodes.DALOAD -> new int[]{2, 2};
			case Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE,
				Opcodes.SASTORE -> new int[]{3, 0};
			case Opcodes.LASTORE, Opcodes.DASTORE -> new int[]{4, 0};
			case Opcodes.POP -> new int[]{1, 0};
			case Opcodes.POP2 -> new int[]{2, 0};
			case Opcodes.DUP -> new int[]{1, 2};
			case Opcodes.DUP_X1 -> new int[]{2, 3};
			case Opcodes.DUP_X2 -> new int[]{3, 4};
			case Opcodes.DUP2 -> new int[]{2, 4};
			case Opcodes.DUP2_X1 -> new int[]{3, 5};
			case Opcodes.DUP2_X2 -> new int[]{4, 6};
			case Opcodes.SWAP -> new int[]{2, 2};
			case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
				Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR -> new int[]{2, 1};
			case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
				Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> new int[]{4, 2};
			case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR -> new int[]{3, 2};
			case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM -> new int[]{2, 1};
			case Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM -> new int[]{4, 2};
			case Opcodes.INEG, Opcodes.FNEG -> new int[]{1, 1};
			case Opcodes.LNEG, Opcodes.DNEG -> new int[]{2, 2};
			case Opcodes.IINC -> new int[]{0, 0};
			case Opcodes.I2L, Opcodes.I2D, Opcodes.F2L, Opcodes.F2D -> new int[]{1, 2};
			case Opcodes.L2I, Opcodes.L2F, Opcodes.D2I, Opcodes.D2F -> new int[]{2, 1};
			case Opcodes.L2D, Opcodes.D2L -> new int[]{2, 2};
			case Opcodes.I2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.F2I -> new int[]{1, 1};
			case Opcodes.LCMP, Opcodes.DCMPL, Opcodes.DCMPG -> new int[]{4, 1};
			case Opcodes.FCMPL, Opcodes.FCMPG -> new int[]{2, 1};
			case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
				Opcodes.IFNULL, Opcodes.IFNONNULL -> new int[]{1, 0};
			case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
				Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> new int[]{2, 0};
			case Opcodes.GOTO -> new int[]{0, 0};
			case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> new int[]{1, 0};
			case Opcodes.IRETURN, Opcodes.FRETURN, Opcodes.ARETURN -> new int[]{1, 0};
			case Opcodes.LRETURN, Opcodes.DRETURN -> new int[]{2, 0};
			case Opcodes.RETURN -> new int[]{0, 0};
			case Opcodes.GETSTATIC -> new int[]{0, fieldSize(insn)};
			case Opcodes.PUTSTATIC -> new int[]{fieldSize(insn), 0};
			case Opcodes.GETFIELD -> new int[]{1, fieldSize(insn)};
			case Opcodes.PUTFIELD -> new int[]{1 + fieldSize(insn), 0};
			case Opcodes.NEW -> new int[]{0, 1};
			case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.ARRAYLENGTH, Opcodes.INSTANCEOF,
				Opcodes.CHECKCAST -> new int[]{1, 1};
			case Opcodes.ATHROW, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> new int[]{1, 0};
			case Opcodes.MULTIANEWARRAY -> new int[]{((MultiANewArrayInsnNode) insn).dims, 1};
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
				MethodInsnNode call = (MethodInsnNode) insn;
				int pops = Type.getArgumentTypes(call.desc).length == 0 ? 0 : argumentSlots(call.desc);
				if (opcode != Opcodes.INVOKESTATIC) pops++;
				yield new int[]{pops, Type.getReturnType(call.desc).getSize()};
			}
			case Opcodes.INVOKEDYNAMIC -> {
				InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
				yield new int[]{argumentSlots(indy.desc), Type.getReturnType(indy.desc).getSize()};
			}
			default -> null;
		};
	}

	private static int ldcSize(AbstractInsnNode insn) {
		Object constant = ((LdcInsnNode) insn).cst;
		if (constant instanceof Long || constant instanceof Double) return 2;
		return 1;
	}

	private static int fieldSize(AbstractInsnNode insn) {
		return Type.getType(((FieldInsnNode) insn).desc).getSize();
	}

	private static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
