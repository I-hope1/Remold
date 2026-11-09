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
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V1_8;
		cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		cn.name = HOST_SLASH;
		cn.superName = "java/lang/Object";
		cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "held", "L" + anonInternal + ";", null, null));
		ClassWriter writer = new ClassWriter(0);
		cn.accept(writer);
		return writer.toByteArray();
	}
}
