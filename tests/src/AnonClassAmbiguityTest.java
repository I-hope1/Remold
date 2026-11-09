import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import remold.AnonClassAligner;
import remold.AnonClassHasher;
import remold.RemoldAgent;

/**
 * Tier 3.5 / Tier 4.5 的判定力测试（ANON_ALIGN 同形匿名类歧义）。
 */
class AnonClassAmbiguityTest {

	private static final String HOST_DOT = "amb.Host";
	private static final String HOST_SLASH = "amb/Host";

	/** v1：同一个方法里两个同接口同结构的匿名类（R2b 的最小同形歧义形态）。 */
	private static final String V1_PAIR =
		"package amb;\n"
		+ "public class Host {\n"
		+ "  public java.util.function.Supplier<String> held;\n"
		+ "  public String localValue() {\n"
		+ "    java.util.function.Supplier<String> a = new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return \"a1\"; }\n"
		+ "    };\n"
		+ "    java.util.function.Supplier<String> b = new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return \"b1\"; }\n"
		+ "    };\n"
		+ "    return a.get() + b.get();\n"
		+ "  }\n"
		+ "}\n";

	/** v2：两个匿名类都只改了字面量 —— 结构完全同形，Tier 3 / Tier 3.5 都无法区分。 */
	private static final String V2_PAIR =
		"package amb;\n"
		+ "public class Host {\n"
		+ "  public java.util.function.Supplier<String> held;\n"
		+ "  public String localValue() {\n"
		+ "    java.util.function.Supplier<String> a = new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return \"a2\"; }\n"
		+ "    };\n"
		+ "    java.util.function.Supplier<String> b = new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return \"b2\"; }\n"
		+ "    };\n"
		+ "    return a.get() + b.get();\n"
		+ "  }\n"
		+ "}\n";

	/**
	 * 持有者点名夹具：字段声明为匿名类**所实现的接口**（真实客户端形态），赋值经局部变量中转，
	 * 因此赋值流必须放弃、只能给出"父类型/接口候选"。
	 */
	private static final String V_INTERFACE_HELD_VIA_LOCAL =
		"package amb;\n"
		+ "public class Host {\n"
		+ "  public java.util.function.Supplier<String> held;\n"
		+ "  public void install() {\n"
		+ "    java.util.function.Supplier<String> s = new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return \"held\"; }\n"
		+ "    };\n"
		+ "    this.held = s;\n"
		+ "  }\n"
		+ "}\n";

	/** 持有者点名夹具：`this.held = new Supplier(){...}` 直接赋值，赋值流可证实。 */
	private static final String V_DIRECT_ASSIGNMENT =
		"package amb;\n"
		+ "public class Host {\n"
		+ "  public java.util.function.Supplier<String> held;\n"
		+ "  public void install() {\n"
		+ "    this.held = new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return \"held\"; }\n"
		+ "    };\n"
		+ "  }\n"
		+ "}\n";

	/** 持有者点名夹具：匿名类捕获宿主局部变量，其构造器会 `putfield val$captured`（不是宿主赋值）。 */
	private static final String V_ANON_CAPTURES_LOCAL =
		"package amb;\n"
		+ "public class Host {\n"
		+ "  public java.util.function.Supplier<String> held;\n"
		+ "  public Object make() {\n"
		+ "    final Object captured = new Object();\n"
		+ "    return new java.util.function.Supplier<String>() {\n"
		+ "      public String get() { return String.valueOf(captured); }\n"
		+ "    };\n"
		+ "  }\n"
		+ "}\n";

	// ---------------------------------------------------------------- 1. 重编号不改变内容哈希

	@Test
	void renumberingDoesNotChangeContentHash() {
		byte[] original   = syntheticAnon("amb/Host$1", "a1");
		byte[] renumbered = renameClass(original, "amb/Host$1", "amb/Host$2");

		Long h1 = AnonClassHasher.hash("amb/Host$1", original, HOST_SLASH, name -> null, null, null, 0);
		Long h2 = AnonClassHasher.hash("amb/Host$2", renumbered, HOST_SLASH, name -> null, null, null, 0);

		assertNotNull(h1, "content hash must be computable for the fixture");
		assertNotNull(h2, "content hash must be computable after renaming");
		assertEquals(h1, h2,
			"an anonymous class that is only renumbered must keep its content hash, otherwise inserting a"
				+ " sibling anonymous class silently disables Tier 1/2 for the whole host");
	}

	@Test
	void renamedClassIsActuallyRenamed() {
		byte[] renamed = renameClass(syntheticAnon("amb/Host$1", "a1"), "amb/Host$1", "amb/Host$2");
		assertEquals("amb/Host$2", parse(renamed).name,
			"precondition: the fixture's class name must really change");
	}

	// ---------------------------------------------------------------- 2. 体相似度口径

	@Test
	void bodySimilarityIsHighForLiteralOnlyChangeAndLowForStructuralChange() {
		byte[] base   = syntheticAnon("amb/Host$1", "a1");
		byte[] relit  = syntheticAnon("amb/Host$1", "a2");
		byte[] struct = syntheticStructuredAnon("amb/Host$1");

		double literalOnly = AnonClassAligner.bodySimilarity(base, relit, HOST_SLASH);
		double structural  = AnonClassAligner.bodySimilarity(base, struct, HOST_SLASH);

		assertTrue(literalOnly >= 0.99,
			"changing only a literal must keep the body structure identical (got " + literalOnly + ")");
		assertTrue(structural < 0.80,
			"a structurally different body must fall below the adoption floor (got " + structural + ")");
		assertTrue(literalOnly - structural >= 0.15,
			"the accepted candidate must beat the other by the strict margin (got "
				+ literalOnly + " vs " + structural + ")");
	}

	@Test
	void bodySimilarityTiesWhenBothCandidatesOnlyChangedLiterals() {
		byte[] old1 = syntheticAnon("amb/Host$1", "a1");
		byte[] newA = syntheticAnon("amb/Host$1", "a2");
		byte[] newB = syntheticAnon("amb/Host$1", "b2");

		double sA = AnonClassAligner.bodySimilarity(old1, newA, HOST_SLASH);
		double sB = AnonClassAligner.bodySimilarity(old1, newB, HOST_SLASH);

		assertEquals(sA, sB, 1e-9,
			"two literal-only variants are indistinguishable by body structure — the tier must see a tie so"
				+ " that the decision falls through to the liveness fallback instead of being guessed");
	}

	// ---------------------------------------------------------------- 3. 全组零存活确定性兜底

	@Test
	void zeroLiveInstancesAllowDeterministicDeadPair() {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(HOST_DOT, V1_PAIR));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(HOST_DOT, V2_PAIR));

		assertEquals(2, anonNames(v1).size(), "fixture precondition: v1 must declare exactly 2 anonymous classes");
		assertEquals(2, anonNames(v2).size(), "fixture precondition: v2 must declare exactly 2 anonymous classes");

		AnonClassAligner.Result r = align(v1, v2, name -> false);

		assertTrue(r.stats.deterministicDeadPairs >= 2,
			"both ambiguous old classes have zero live instances, so the approved fallback must pair them; state="
				+ r.stats + " orphans=" + r.orphanOldClasses);
		assertEquals(0, r.stats.ambiguousPairs,
			"a fallback-resolved group must not be reported as ambiguous any more; state=" + r.stats);
		assertTrue(r.orphanOldClasses.isEmpty(),
			"the dead-pair fallback must leave no orphan at all; orphans=" + r.orphanOldClasses);
	}

	@Test
	void liveInstancesBlockTheFallback() {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(HOST_DOT, V1_PAIR));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(HOST_DOT, V2_PAIR));

		String liveDotted = anonNames(v1).get(0);
		AnonClassAligner.Result r = align(v1, v2, name -> name.equals(liveDotted));

		assertEquals(0, r.stats.deterministicDeadPairs,
			"guardrail: one live candidate disables the liveness fallback for the whole group; state=" + r.stats);
		assertTrue(r.stats.ambiguousPairs >= 1,
			"the ambiguity must still be counted when the fallback is blocked; state=" + r.stats);
		assertFalse(r.orphanOldClasses.isEmpty(),
			"with live instances the old classes must stay orphans (live objects keep running old code)");
		assertTrue(r.stats.ambiguousDetail.stream().anyMatch(x -> x.contains("live=1")),
			"the diagnostic must record live-instance counts: " + r.stats.ambiguousDetail);
	}

	// ---------------------------------------------------------------- 4. 字段持有者点名

	@Test
	void holdingFieldIsNamedForOrphanedAnonClass() {
		ClassNode host = parse(syntheticHostWithField("amb/Host$2"));
		List<String> holders = AnonClassAligner.findHoldingFields(host, null,
			new LinkedHashSet<>(List.of("amb/Host$2")));

		assertEquals(1, holders.size(), "the field whose descriptor references the orphan must be named: " + holders);
		assertTrue(holders.get(0).contains("amb/Host.") && holders.get(0).contains("held"),
			"the reported holder must name host + field + descriptor: " + holders);

		assertTrue(AnonClassAligner.findHoldingFields(host, null, Set.of("amb/Host$9")).isEmpty(),
			"control: an unrelated anonymous class must produce no holder");
	}

	/**
	 * (a) Real shape from the client: the host field is declared as the *interface* the anonymous class
	 * implements (`Supplier<String> held`), so an exact-descriptor scan can never see it. The value here
	 * travels through a local variable, so the assignment-flow layer must refuse to guess and the
	 * supertype/interface layer must name it as a **candidate**.
	 */
	@Test
	void interfaceTypedHolderIsNamedAsCandidate() {
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(HOST_DOT, V_INTERFACE_HELD_VIA_LOCAL));
		ClassNode host      = parse(v2.get(HOST_DOT));
		String    anonName  = anonNames(v2).get(0);
		ClassNode anon      = parse(v2.get(anonName));

		AnonClassAligner.HoldingFields r = holders(host, null, anonName, anon);

		assertTrue(r.proven.isEmpty(),
			"an assignment that goes through a local variable must not be reported as proven: " + r);
		assertEquals(1, r.candidates.size(),
			"the interface-typed host field must be named as a candidate: " + r);
		assertTrue(r.candidates.get(0).contains("amb/Host.held"),
			"the candidate must name host + field: " + r.candidates);
		assertEquals(1, r.all().size(), "candidate must be visible through the legacy list contract: " + r);
	}

	/**
	 * (b) `this.held = new Supplier(){...}` in the method body: the value flows straight from `NEW`/`<init>`
	 * into a `PUTFIELD` of a host field, which is proof — not a candidate.
	 */
	@Test
	void assignmentFlowProvesHoldingField() {
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(HOST_DOT, V_DIRECT_ASSIGNMENT));
		ClassNode host     = parse(v2.get(HOST_DOT));
		String    anonName = anonNames(v2).get(0);
		ClassNode anon     = parse(v2.get(anonName));

		AnonClassAligner.HoldingFields r = holders(host, null, anonName, anon);

		assertEquals(1, r.proven.size(),
			"NEW <anon> -> <init> -> PUTFIELD host.field must be proven by the assignment flow: " + r);
		assertTrue(r.proven.get(0).contains("amb/Host.held"),
			"the proven holder must name host + field: " + r.proven);
		assertTrue(r.candidates.isEmpty(),
			"once the assignment flow proves a holder there is no need to fall back to candidates: " + r);
	}

	/**
	 * (c) The previous exact-descriptor behaviour is preserved: a synthetic host field whose static type
	 * *is* the orphaned anonymous class still hits. The guard half: an anonymous class's own
	 * `this$0` capture field is not a host holder and must not be reported.
	 */
	@Test
	void syntheticExactDescriptorHolderStillHitsButAnonCaptureDoesNot() {
		ClassNode host  = parse(syntheticHostWithSyntheticField("amb/Host$2", "this$1"));
		ClassNode anon  = parse(syntheticAnonWithCapture("amb/Host$2", "amb/Host"));
		ClassNode anon2 = parse(syntheticAnonWithCapture("amb/Host$3", "amb/Host"));

		List<String> holders = AnonClassAligner.findHoldingFields(host, anon,
			new LinkedHashSet<>(List.of("amb/Host$2")));

		assertEquals(1, holders.size(),
			"exactly the synthetic host field typed as the orphan must be named (this$0 of the anonymous"
				+ " class itself is a capture of the HOST, not a holder of the anonymous class): " + holders);
		assertTrue(holders.get(0).contains("this$1"), "the exact-descriptor hit must be the host field: " + holders);

		assertTrue(AnonClassAligner.findHoldingFields(host, anon2, Set.of("amb/Host$3")).isEmpty(),
			"control: an orphan whose type no host field declares must produce no holder");
	}

	/**
	 * (e) The anonymous class captures an outer local, so *its own* constructor emits
	 * `putfield <anon>.val$captured`. That is not a host assignment — it must never surface as a proven
	 * holder, even while the host's interface-typed field is still named as a candidate.
	 */
	@Test
	void anonConstructorCaptureIsNotMistakenForHostAssignment() {
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(HOST_DOT, V_ANON_CAPTURES_LOCAL));
		ClassNode host     = parse(v2.get(HOST_DOT));
		String    anonName = anonNames(v2).get(0);
		ClassNode anon     = parse(v2.get(anonName));

		assertNotNull(anon.fields, "fixture precondition: javac must emit the captured-local field");
		assertTrue(anon.fields.stream().anyMatch(f -> f.name.startsWith("val$")),
			"fixture precondition: the anonymous class must really capture a local (val$*): " + anon.fields);

		AnonClassAligner.HoldingFields r = holders(host, anon, anonName, anon);

		assertTrue(r.proven.isEmpty(),
			"a `putfield val$*` inside the anonymous class' own constructor is not a host assignment: " + r);
		assertTrue(r.proven.stream().noneMatch(e -> e.contains("val$")), "no capture field may be named: " + r);
		assertEquals(1, r.candidates.size(),
			"the interface-typed host field is still reported as a candidate: " + r);
	}

	/**
	 * (d) Negative control: the host field's type has no subtype/interface relation to the orphan and no
	 * assignment flow reaches it — all three layers must stay silent (no random naming).
	 */
	@Test
	void unrelatedTypedFieldIsNeverNamed() {
		ClassNode host = parse(syntheticHostWithFieldTyped("held", "Ljava/lang/Runnable;"));
		ClassNode anon = parse(syntheticAnon("amb/Host$2", "held"));   // implements Supplier, not Runnable

		AnonClassAligner.HoldingFields r = holders(host, null, "amb/Host$2", anon);

		assertTrue(r.isEmpty(), "unrelated field must not be named by any layer: " + r);
		assertTrue(r.all().isEmpty(), "legacy contract must stay empty too: " + r);
	}

	/**
	 * (f) End-to-end wiring of the C4 diagnostic: when the ambiguity falls back to "refused to guess", the
	 * emitted warning must name the interface-typed host field as a candidate instead of claiming that no
	 * host field holds the orphan (the client's `Supplier<String> HELD6` shape).
	 */
	@Test
	void orphanWarningNamesCandidateFieldForInterfaceTypedHolder() {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(HOST_DOT, V1_PAIR));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(HOST_DOT, V2_PAIR));
		String liveDotted = anonNames(v1).get(0);

		List<String> warns = new ArrayList<>();
		RemoldAgent.Logger saved = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override public void log(String msg) { }
			@Override public void info(String msg) { }
			@Override public void warn(String msg) { warns.add(msg); }
			@Override public void error(String msg) { warns.add("ERROR " + msg); }
			@Override public void error(String msg, Throwable t) { warns.add("ERROR " + msg); }
		};
		AnonClassAligner.Result r;
		try {
			r = align(v1, v2, name -> name.equals(liveDotted));
		} finally {
			RemoldAgent.logger = saved;
		}

		assertTrue(r.stats.ambiguousPairs >= 1,
			"precondition: the ambiguity must still be reported (otherwise no warning to inspect): " + r.stats);
		String refusal = warns.stream()
			.filter(w -> w.contains("[ANON_ALIGN]") && w.contains("Orphan old anonymous classes"))
			.findFirst().orElse(null);
		assertNotNull(refusal, "the refusal warning must be emitted: " + warns);
		assertFalse(refusal.contains("No host field holds them"),
			"the Supplier-typed host field holds orphaned instances, so the warning must not claim otherwise: " + refusal);
		assertTrue(refusal.contains("Candidate holder field(s) (declared as the supertype/interface)"),
			"the warning must separate the weaker candidate strength: " + refusal);
		assertTrue(refusal.contains("amb/Host.held"),
			"the warning must name host + field: " + refusal);
		assertTrue(refusal.contains("@RemoldReinit"),
			"the warning must keep the no-restart escape hatch: " + refusal);
	}

	// ---------------------------------------------------------------- helpers

	private static AnonClassAligner.Result align(Map<String, byte[]> oldClasses, Map<String, byte[]> newClasses,
	                                            Predicate<String> live) {
		return AnonClassAligner.align(HOST_DOT, newClasses.get(HOST_DOT),
			anonUnder(oldClasses), anonUnder(newClasses),
			name -> oldClasses.get(name.replace('/', '.')),
			name -> newClasses.get(name.replace('/', '.')),
			live);
	}

	private static Map<String, byte[]> anonUnder(Map<String, byte[]> classes) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			if (AnonClassAligner.isAnonymousClassName(HOST_DOT, e.getKey())) out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	private static List<String> anonNames(Map<String, byte[]> classes) {
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			if (AnonClassAligner.isAnonymousClassName(HOST_DOT, e.getKey())) names.add(e.getKey());
		}
		names.sort(Comparator.comparingInt(name -> AnonClassAligner.parseIndex(HOST_DOT, name)));
		return names;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] renameClass(byte[] bytes, String fromInternal, String toInternal) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		ClassWriter writer = new ClassWriter(0);
		Map<String, String> mapping = new LinkedHashMap<>();
		mapping.put(fromInternal, toInternal);
		node.accept(new ClassRemapper(writer, new SimpleRemapper(mapping)));
		return writer.toByteArray();
	}

	private static byte[] syntheticAnon(String name, String literal) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V1_8;
		cn.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
		cn.name = name;
		cn.superName = "java/lang/Object";
		cn.interfaces.add("java/util/function/Supplier");
		cn.outerClass = HOST_SLASH;
		cn.outerMethod = "localValue";
		cn.outerMethodDesc = "()Ljava/lang/String;";

		MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();
		cn.methods.add(ctor);

		MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
		get.visitLdcInsn(literal);
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(1, 1);
		get.visitEnd();
		cn.methods.add(get);

		ClassWriter writer = new ClassWriter(0);
		cn.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] syntheticStructuredAnon(String name) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V1_8;
		cn.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
		cn.name = name;
		cn.superName = "java/lang/Object";
		cn.interfaces.add("java/util/function/Supplier");
		cn.outerClass = HOST_SLASH;
		cn.outerMethod = "localValue";
		cn.outerMethodDesc = "()Ljava/lang/String;";

		MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();
		cn.methods.add(ctor);

		MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
		get.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
		get.visitInsn(Opcodes.DUP);
		get.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
		get.visitLdcInsn("n2");
		get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
			"(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
		get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
			"()Ljava/lang/String;", false);
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(3, 1);
		get.visitEnd();
		cn.methods.add(get);

		ClassWriter writer = new ClassWriter(0);
		cn.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] syntheticHostWithField(String anonInternal) {
		return syntheticHostWithFieldTyped("held", "L" + anonInternal + ";");
	}

	private static byte[] syntheticHostWithFieldTyped(String fieldName, String desc) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V1_8;
		cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		cn.name = HOST_SLASH;
		cn.superName = "java/lang/Object";
		cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, fieldName, desc, null, null));
		ClassWriter writer = new ClassWriter(0);
		cn.accept(writer);
		return writer.toByteArray();
	}

	/** 合成字段（`ACC_SYNTHETIC`）形态：静态类型精确等于匿名类。 */
	private static byte[] syntheticHostWithSyntheticField(String anonInternal, String fieldName) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V1_8;
		cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		cn.name = HOST_SLASH;
		cn.superName = "java/lang/Object";
		cn.fields.add(new FieldNode(Opcodes.ACC_SYNTHETIC, fieldName, "L" + anonInternal + ";", null, null));
		ClassWriter writer = new ClassWriter(0);
		cn.accept(writer);
		return writer.toByteArray();
	}

	/** 匿名类捕获外部实例：`this$0` 字段 + 构造器里的 `putfield <self>.this$0`。 */
	private static byte[] syntheticAnonWithCapture(String name, String outerInternal) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V1_8;
		cn.access = Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
		cn.name = name;
		cn.superName = "java/lang/Object";
		cn.interfaces.add("java/util/function/Supplier");
		cn.outerClass = HOST_SLASH;
		cn.fields.add(new FieldNode(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "this$0", "L" + outerInternal + ";", null, null));

		MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(L" + outerInternal + ";)V", null, null);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 1);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "this$0", "L" + outerInternal + ";");
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(2, 2);
		ctor.visitEnd();
		cn.methods.add(ctor);

		MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
		get.visitLdcInsn("held");
		get.visitInsn(Opcodes.ARETURN);
		get.visitMaxs(1, 1);
		get.visitEnd();
		cn.methods.add(get);

		ClassWriter writer = new ClassWriter(0);
		cn.accept(writer);
		return writer.toByteArray();
	}

	/** 走"父类型/接口候选"需要匿名类自身的 superName/interfaces，故显式给解析器。 */
	private static AnonClassAligner.HoldingFields holders(ClassNode oldHost, ClassNode newHost,
	                                                    String anonDottedName, ClassNode anonNode) {
		String anonInternal = anonDottedName.replace('.', '/');
		return AnonClassAligner.findHoldingFields(oldHost, newHost, List.of(anonDottedName),
			name -> anonInternal.equals(name) || anonDottedName.equals(name) ? anonNode : null);
	}
}
