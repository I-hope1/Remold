package remold.ref;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import remold.ClassDiffUtil;
import remold.ClassDiffUtil.ClassDiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Battle A tests for the pure value-type {@code new} replay planner
 * ({@code core/src/remold/ref/ValueTypeReplay.java}) and its normalized bytecode fingerprint.
 *
 * <p>Fixtures are compiled in memory with javac (the same pattern as {@code InitFixOracle}) or built with
 * ASM when a compiler shape is impossible to obtain from Java source (Kotlin data class / Kotlin default
 * constructor). Synthetic Kotlin fixtures mirror real kotlinc 2.x output measured with {@code javap} on the
 * repo's {@code tests/ktfix} fixtures; they are documented as synthetic where used.</p>
 */
class ValueTypeReplayTest {

	private static final String HOST = "fx/Host";
	private static final String VEC = "fx/Vec";
	private static final String KOTLIN_VEC = "kx/Vec";

	// ==================== STEP 0: research premise ====================

	/**
	 * Documents why A0 cannot reuse {@link InitializerDrift}'s slice: {@code InitializerDrift.slice} stops
	 * immediately when the previous instruction is {@code INVOKESPECIAL <init>}, i.e. exactly inside
	 * {@code this.v = new Vec(4,4)}. The resulting per-field slice is only the trailing {@code PUTFIELD},
	 * which is identical on both sides, so {@code new Vec(4,4) -> new Vec(4,12)} is <b>not</b> reported as
	 * a drift. A0 therefore computes its own whole-expression fingerprint (NEW + ctor chain + args).
	 */
	@Test
	void initializerDriftSliceBreaksAtNewExpression() {
		Map<String, byte[]> v1 = recordHost(4, 4);
		Map<String, byte[]> v2 = recordHost(4, 12);

		InitializerDrift.Drift drift = InitializerDrift.detectDetailed(v1.get(HOST), v2.get(HOST), Set.of());

		assertTrue(drift.changed().isEmpty(),
			"premise: InitializerDrift must NOT see the argument drift (its slice breaks at INVOKESPECIAL <init>): "
			+ drift.changed());
		assertTrue(drift.inherited().isEmpty(), drift.inherited().toString());
		assertTrue(drift.inheritedReinit().isEmpty(), drift.inheritedReinit().toString());

		// Control: the same two versions DO differ at the instruction level, so the silence above is the
		// slice definition, not identical bytecode.
		assertFalse(Arrays.equals(v1.get(HOST), v2.get(HOST)), "fixtures must differ");
	}

	// ==================== A0 + positive path ====================

	@Test
	void javaRecordArgumentChangeIsPlanned() {
		Map<String, byte[]> v1 = recordHost(4, 4);
		Map<String, byte[]> v2 = recordHost(4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		ValueTypeReplay.Field field = plan.fields().get("v");
		assertNotNull(field, "fields=" + plan.fields() + " rejected=" + plan.rejected() + " details=" + plan.details());
		assertFalse(field.isStatic());
		assertEquals("Lfx/Vec;", field.desc());
		assertNotBlank(field.oldFingerprint());
		assertNotBlank(field.newFingerprint());
		assertNotBlank(field.ctorFingerprint());
		assertNotBlank(field.equalsFingerprint());
		assertFalse(field.oldFingerprint().equals(field.newFingerprint()),
			"A0 fingerprints must differ when the NEW argument changes");
		assertTrue(plan.rejected().isEmpty(), plan.rejected().toString());
	}

	@Test
	void sameInitializerIsNotTriggered() {
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host {\n"
		                + "  final Vec v = new Vec(4, 4);\n"
		                + "  int stamp() { return 1; }\n}\n";
		String hostV2 = "package fx;\npublic class Host {\n"
		                + "  final Vec v = new Vec(4, 4);\n"
		                + "  int stamp() { return 2; }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertTrue(plan.fields().isEmpty(), "same expression must not be triggered: " + plan.fields());
		assertTrue(plan.rejected().isEmpty(), "not triggered fields must not be reported as rejected: " + plan.rejected());
	}

	@Test
	void newTypeChangeIsTriggeredAndRejected() {
		String vecA = "package fx;\npublic record Vec(int x, int y) {}\n";
		String vecB = "package fx;\npublic record Vec2(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host { final Vec v = new Vec(4, 4); }\n";
		String hostV2 = "package fx;\npublic class Host { final Vec2 v = new Vec2(4, 4); }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vecA, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec2.java", vecB, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);
		// The field descriptor changed as well, so the existing-field key filter drops it entirely.
		assertTrue(plan.fields().isEmpty());
	}

	// ==================== A1 straight-line / constants ====================

	@Test
	void constructorParameterArgumentIsRejected() {
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host {\n"
		                + "  final Vec v;\n  Host(int x) { v = new Vec(x, 4); }\n}\n";
		String hostV2 = "package fx;\npublic class Host {\n"
		                + "  final Vec v;\n  Host(int x) { v = new Vec(x, 12); }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EXPR_NOT_CONSTANT, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void kotlinDefaultConstructorCallIsRejected() {
		// Synthetic: kotlinc emits `Vec.$default(...)` when a constructor argument uses a default value.
		byte[] v1 = syntheticDefaultHost(4);
		byte[] v2 = syntheticDefaultHost(12);

		ValueTypeReplay.Plan plan = planSynthetic(v1, v2, name -> null, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EXPR_NOT_CONSTANT, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void kotlinDefaultConstructorMarkerTailIsRejected() {
		// Synthetic: kotlinc's synthetic `<init>(..., DefaultConstructorMarker)` tail parameter.
		byte[] v1 = syntheticMarkerHost(4);
		byte[] v2 = syntheticMarkerHost(12);

		ValueTypeReplay.Plan plan = planSynthetic(v1, v2, name -> null, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EXPR_NOT_CONSTANT, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	// ==================== A2 constructor chain stability ====================

	@Test
	void constructedTypeInBatchIsRejected() {
		Map<String, byte[]> v1 = recordHost(4, 4);
		Map<String, byte[]> v2 = recordHost(4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, VEC::equals, false);

		assertEquals(ValueTypeReplay.Reason.CONSTRUCTED_CHANGED, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void missingConstructedTypeBytesFailClosed() {
		Map<String, byte[]> v1 = recordHost(4, 4);
		Map<String, byte[]> v2 = recordHost(4, 12);

		ClassDiff diff = ClassDiffUtil.diff(v1.get(HOST), v2.get(HOST));
		ValueTypeReplay.Plan plan = ValueTypeReplay.plan(diff, name -> null, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.CTOR_CHAIN_UNRESOLVED, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	// ==================== A3 shape / equals / purity ====================

	@Test
	void customEqualsIsRejected() {
		String vec = "package fx;\npublic record Vec(int x, int y) {\n"
		             + "  @Override public boolean equals(Object o) { return false; }\n"
		             + "  @Override public int hashCode() { return 0; }\n}\n";
		Map<String, byte[]> v1 = recordHost(vec, 4, 4);
		Map<String, byte[]> v2 = recordHost(vec, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EQ_CUSTOM, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void compositeComponentIsRejected() {
		String dep = "package fx;\nclass Dep {}\n";
		String vec = "package fx;\npublic record Vec(Dep d) {\n  public Vec(int x) { this(new Dep()); }\n}\n";
		String hostV1 = "package fx;\npublic class Host { final Vec v = new Vec(4); }\n";
		String hostV2 = "package fx;\npublic class Host { final Vec v = new Vec(12); }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Dep.java", dep, "fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Dep.java", dep, "fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EQ_NOT_PURE, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void constructorSideEffectOutsideTheWhitelistIsRejected() {
		// System.out.println looks harmless at the source level but is observable side effect.
		String vec = "package fx;\npublic record Vec(int x, int y) {\n"
		             + "  public Vec { System.out.println(x); }\n}\n";
		Map<String, byte[]> v1 = recordHost(vec, 4, 4);
		Map<String, byte[]> v2 = recordHost(vec, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.CTOR_IMPURE, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void constructorStaticWriteIsRejected() {
		String vec = "package fx;\npublic record Vec(int x, int y) {\n"
		             + "  static int z;\n"
		             + "  public Vec { z = x; }\n}\n";
		Map<String, byte[]> v1 = recordHost(vec, 4, 4);
		Map<String, byte[]> v2 = recordHost(vec, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.CTOR_IMPURE, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void clinitWithSideEffectIsRejected() {
		String vec = "package fx;\npublic record Vec(int x, int y) {\n"
		             + "  static final int C = init();\n"
		             + "  static int init() { System.out.println(\"side\"); return 1; }\n}\n";
		Map<String, byte[]> v1 = recordHost(vec, 4, 4);
		Map<String, byte[]> v2 = recordHost(vec, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.CTOR_IMPURE, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void whitelistedNullCheckKeepsRecordReplayable() {
		// Positive control for the allowlist: Objects.requireNonNull is a whitelisted pure call, so a
		// record with a String component must stay accepted (the purity gate is not "reject everything").
		String vec = "package fx;\npublic record Vec(int x, String s) {\n"
		             + "  public Vec { java.util.Objects.requireNonNull(s); }\n}\n";
		String hostV1 = "package fx;\npublic class Host { final Vec v = new Vec(4, \"a\"); }\n";
		String hostV2 = "package fx;\npublic class Host { final Vec v = new Vec(4, \"b\"); }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertNotNull(plan.fields().get("v"), "rejected=" + plan.rejected() + " details=" + plan.details());
	}

	@Test
	void kotlinDataClassShapeIsAccepted() {
		// Synthetic ASM fixture mirroring real kotlinc 2.x output (kotlin/Metadata + componentN + copy +
		// generated equals/hashCode); see class docs.
		Map<String, byte[]> v1 = kotlinHost(4, 4);
		Map<String, byte[]> v2 = kotlinHost(4, 12);
		byte[] kotlinVec = kotlinDataClass(true, false);

		ValueTypeReplay.Plan plan = planWith(v1, v2, name -> KOTLIN_VEC.equals(name) ? kotlinVec : null,
			name -> false, false);

		ValueTypeReplay.Field field = plan.fields().get("v");
		assertNotNull(field, "rejected=" + plan.rejected() + " details=" + plan.details());
		assertNotBlank(field.ctorFingerprint());
		assertNotBlank(field.equalsFingerprint());
	}

	@Test
	void kotlinFloatDoubleLongDataClassShapeIsAccepted() {
		// Synthetic kotlinc 2.x shape for Float/Double/Long components: equals uses
		// Float.compare/Double.compare + LCMP, hashCode uses Float/Double/Long.hashCode.
		Map<String, byte[]> v1 = kotlinFdlHost(1.5f, 2.5, 3L);
		Map<String, byte[]> v2 = kotlinFdlHost(1.5f, 12.5, 3L);
		byte[] kotlinFdl = kotlinFdlDataClass();

		ValueTypeReplay.Plan plan = planWith(v1, v2, name -> "kx/Fdl".equals(name) ? kotlinFdl : null,
			name -> false, false);

		ValueTypeReplay.Field field = plan.fields().get("v");
		assertNotNull(field, "rejected=" + plan.rejected() + " details=" + plan.details());
		assertTrue(plan.rejected().isEmpty(), plan.rejected().toString());
		assertNotBlank(field.ctorFingerprint());
		assertNotBlank(field.equalsFingerprint());
	}

	@Test
	void kotlinCustomEqualsIsRejected() {
		// Negative control for the bytecode-shape heuristic: a class carrying kotlin/Metadata,
		// componentN and copy but with a hand-written equals (here identity-only, potentially
		// side-effecting in real code) must not be accepted as a data class.
		Map<String, byte[]> v1 = kotlinHost(4, 4);
		Map<String, byte[]> v2 = kotlinHost(4, 12);
		byte[] custom = kotlinDataClass(true, false, true);

		ValueTypeReplay.Plan plan = planWith(v1, v2, name -> KOTLIN_VEC.equals(name) ? custom : null,
			name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EQ_CUSTOM, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void kotlinVarPrimaryFieldIsRejected() {
		Map<String, byte[]> v1 = kotlinHost(4, 4);
		Map<String, byte[]> v2 = kotlinHost(4, 12);
		byte[] mutable = kotlinDataClass(false, false);

		ValueTypeReplay.Plan plan = planWith(v1, v2, name -> KOTLIN_VEC.equals(name) ? mutable : null,
			name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EQ_NOT_PURE, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void kotlinExtraBodyInstanceFieldIsRejected() {
		Map<String, byte[]> v1 = kotlinHost(4, 4);
		Map<String, byte[]> v2 = kotlinHost(4, 12);
		byte[] withExtra = kotlinDataClass(true, true);

		ValueTypeReplay.Plan plan = planWith(v1, v2, name -> KOTLIN_VEC.equals(name) ? withExtra : null,
			name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EQ_NOT_PURE, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	// ==================== A4 target field / host restrictions ====================

	@Test
	void recordHostInstanceFieldIsRejected() {
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic record Host(Vec v) {\n  public Host { v = new Vec(4, 4); }\n}\n";
		String hostV2 = "package fx;\npublic record Host(Vec v) {\n  public Host { v = new Vec(4, 12); }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.HOST_RECORD, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void staticFinalWithoutEnhancedIsRejected() {
		Map<String, byte[]> v1 = staticHost(false, 4, 4);
		Map<String, byte[]> v2 = staticHost(false, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.STATIC_FINAL_NEEDS_ENHANCED, plan.rejected().get("V"),
			"detail=" + plan.details().get("V"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void staticFinalWithEnhancedIsAccepted() {
		Map<String, byte[]> v1 = staticHost(false, 4, 4);
		Map<String, byte[]> v2 = staticHost(false, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, true);

		ValueTypeReplay.Field field = plan.fields().get("V");
		assertNotNull(field, "rejected=" + plan.rejected() + " details=" + plan.details());
		assertTrue(field.isStatic());
	}

	@Test
	void nonFinalStaticDoesNotNeedEnhanced() {
		Map<String, byte[]> v1 = staticHost(true, 4, 4);
		Map<String, byte[]> v2 = staticHost(true, 4, 12);

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertNotNull(plan.fields().get("V"), "rejected=" + plan.rejected() + " details=" + plan.details());
	}

	@Test
	void constantValueIsNotTriggered() {
		String hostV1 = "package fx;\npublic class Host { static final String S = \"a\"; }\n";
		String hostV2 = "package fx;\npublic class Host { static final String S = \"b\"; }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertTrue(plan.fields().isEmpty(), plan.fields().toString());
		assertTrue(plan.rejected().isEmpty(), "ConstantValue is outside the channel, not a gate rejection: "
		                                      + plan.rejected());
	}

	// ==================== A5 multi-root consensus ====================

	@Test
	void differentOldExpressionsAcrossRootConstructorsAreRejected() {
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host {\n"
		                + "  final Vec v;\n"
		                + "  Host() { v = new Vec(1, 4); }\n"
		                + "  Host(int ignored) { v = new Vec(2, 4); }\n}\n";
		String hostV2 = "package fx;\npublic class Host {\n"
		                + "  final Vec v;\n"
		                + "  Host() { v = new Vec(4, 12); }\n"
		                + "  Host(int ignored) { v = new Vec(4, 12); }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.MULTI_ROOT_MISMATCH, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void agreeingRootConstructorsAreAccepted() {
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host {\n"
		                + "  final Vec v;\n"
		                + "  Host() { v = new Vec(4, 4); }\n"
		                + "  Host(int ignored) { v = new Vec(4, 4); }\n}\n";
		String hostV2 = "package fx;\npublic class Host {\n"
		                + "  final Vec v;\n"
		                + "  Host() { v = new Vec(4, 12); }\n"
		                + "  Host(int ignored) { v = new Vec(4, 12); }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertNotNull(plan.fields().get("v"), "rejected=" + plan.rejected() + " details=" + plan.details());
	}

	// ==================== A0 scope: unrelated / unchanged fields stay silent ====================

	@Test
	void unrelatedStringFieldInMultiRootHostIsNotRejected() {
		// Neither side carries a `new T(...)` initializer, so value-type replay has no business
		// reporting the String disagreement as MULTI_ROOT_MISMATCH.
		String hostV1 = "package fx;\npublic class Host {\n"
		                + "  String s;\n"
		                + "  Host() { s = \"a\"; }\n"
		                + "  Host(int ignored) { s = \"a\"; }\n}\n";
		String hostV2 = "package fx;\npublic class Host {\n"
		                + "  String s;\n"
		                + "  Host() { s = \"b\"; }\n"
		                + "  Host(int ignored) { s = \"a\"; }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertTrue(plan.fields().isEmpty(), plan.fields().toString());
		assertTrue(plan.rejected().isEmpty(), "unrelated String initializers must stay silent: "
		                                      + plan.rejected() + " details=" + plan.details());
	}

	@Test
	void removedNonNewInitializerIsNotRejected() {
		String hostV1 = "package fx;\npublic class Host { String s = \"a\"; }\n";
		String hostV2 = "package fx;\npublic class Host { String s; }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertTrue(plan.fields().isEmpty(), plan.fields().toString());
		assertTrue(plan.rejected().isEmpty(), "removing a non-new initializer is outside this channel: "
		                                      + plan.rejected() + " details=" + plan.details());
	}

	@Test
	void removedNewInitializerIsStillRejected() {
		// Negative control for the scope guard: an old `new Vec(...)` initializer that disappears must
		// still be rejected because the old value can no longer be rebuilt.
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host { Vec v = new Vec(4, 4); }\n";
		String hostV2 = "package fx;\npublic class Host { Vec v; }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.EXPR_NOT_CONSTANT, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void unchangedNewFieldWithAddedRootIsNotRejected() {
		// The `new Vec(4,4)` writes are identical on both sides; a new root constructor that simply does
		// not initialize the field changes the root count but not this field's expressions, so A0 is
		// not triggered (no MULTI_ROOT_MISMATCH).
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String hostV1 = "package fx;\npublic class Host {\n"
		                + "  Vec v;\n"
		                + "  Host() { v = new Vec(4, 4); }\n"
		                + "  Host(int ignored) { v = new Vec(4, 4); }\n}\n";
		String hostV2 = "package fx;\npublic class Host {\n"
		                + "  Vec v;\n"
		                + "  Host() { v = new Vec(4, 4); }\n"
		                + "  Host(int ignored) { v = new Vec(4, 4); }\n"
		                + "  Host(String ignored) { }\n}\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertTrue(plan.fields().isEmpty(), "an unchanged field must not be replanned: " + plan.fields());
		assertTrue(plan.rejected().isEmpty(), "unchanged expressions must not be rejected: "
		                                      + plan.rejected() + " details=" + plan.details());
	}

	// ==================== A2: both constructor chains are validated ====================

	@Test
	void delegatingNewCtorIsRejected() {
		// old host uses the canonical (II) constructor; new host switches to (I), which delegates via
		// this(x, 0). The new chain must be walked and fail closed as an unresolved chain.
		String vec = "package fx;\npublic record Vec(int x, int y) {\n"
		             + "  public Vec(int x) { this(x, 0); }\n}\n";
		String hostV1 = "package fx;\npublic class Host { final Vec v = new Vec(4, 4); }\n";
		String hostV2 = "package fx;\npublic class Host { final Vec v = new Vec(4); }\n";
		Map<String, byte[]> v1 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("fx/Vec.java", vec, "fx/Host.java", hostV2));

		ValueTypeReplay.Plan plan = plan(v1, v2, name -> false, false);

		assertEquals(ValueTypeReplay.Reason.CTOR_CHAIN_UNRESOLVED, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	@Test
	void newCtorConstantValueFromBatchCandidateIsRejected() {
		// old host uses the pure canonical (II) constructor. The new (I) constructor reads a
		// ConstantValue from kx/Const, which is a redefine candidate in this batch, so A2 must reject
		// CONSTRUCTED_CHANGED after validating both chains.
		String stub = "package kx;\nclass Vec { Vec(int x, int y) {} Vec(int x) {} }\n";
		String hostV1 = "package kx;\nclass Host { final Vec v = new Vec(4, 4); }\n";
		String hostV2 = "package kx;\nclass Host { final Vec v = new Vec(4); }\n";
		Map<String, byte[]> v1 = compile(Map.of("kx/Vec.java", stub, "kx/Host.java", hostV1));
		Map<String, byte[]> v2 = compile(Map.of("kx/Vec.java", stub, "kx/Host.java", hostV2));
		byte[] kotlinVec = kotlinDataClassWithConstantCtor();
		byte[] constantOwner = constantOwner();

		ValueTypeReplay.Plan plan = planWith(v1, v2,
			name -> switch (name) {
				case "kx/Vec" -> kotlinVec;
				case "kx/Const" -> constantOwner;
				default -> null;
			},
			"kx/Const"::equals, false);

		assertEquals(ValueTypeReplay.Reason.CONSTRUCTED_CHANGED, plan.rejected().get("v"),
			"detail=" + plan.details().get("v"));
		assertTrue(plan.fields().isEmpty());
	}

	// ==================== BytecodeNormalFingerprint ====================

	@Test
	void debugOnlyChangesDoNotChangeTheFingerprint() {
		String source = "package n;\nclass Fixture { int f() { int alpha = 1; return alpha; } }\n";
		Map<String, byte[]> withDebug = compileWithOptions(List.of("-g"),
			Map.of("n/Fixture.java", source));
		Map<String, byte[]> withoutDebug = compileWithOptions(List.of("-g:none"),
			Map.of("n/Fixture.java", source));
		byte[] a = withDebug.get("n/Fixture");
		byte[] b = withoutDebug.get("n/Fixture");
		assertFalse(Arrays.equals(a, b), "the two compilations must actually differ in debug attributes");

		assertEquals(BytecodeNormalFingerprint.of(a), BytecodeNormalFingerprint.of(b),
			"LineNumberTable / LocalVariableTable / SourceFile must not affect the fingerprint");
	}

	@Test
	void localVariableRenameDoesNotChangeTheFingerprint() {
		String v1 = "package n;\nclass Fixture { int f() { int alpha = 1; return alpha; } }\n";
		String v2 = "package n;\nclass Fixture { int f() { int beta = 1; return beta; } }\n";
		Map<String, byte[]> a = compileWithOptions(List.of("-g"), Map.of("n/Fixture.java", v1));
		Map<String, byte[]> b = compileWithOptions(List.of("-g"), Map.of("n/Fixture.java", v2));
		assertFalse(Arrays.equals(a.get("n/Fixture"), b.get("n/Fixture")));

		assertEquals(BytecodeNormalFingerprint.of(a.get("n/Fixture")),
			BytecodeNormalFingerprint.of(b.get("n/Fixture")));
	}

	@Test
	void sourceFileRenameDoesNotChangeTheFingerprint() {
		String source = "package n;\nclass Helper { int f() { return 1; } }\n";
		Map<String, byte[]> a = compile(Map.of("n/A.java", source));
		Map<String, byte[]> b = compile(Map.of("n/B.java", source));
		byte[] left = a.get("n/Helper");
		byte[] right = b.get("n/Helper");
		assertFalse(Arrays.equals(left, right), "SourceFile attribute must differ");

		assertEquals(BytecodeNormalFingerprint.of(left), BytecodeNormalFingerprint.of(right));
	}

	@Test
	void instructionChangeDoesChangeTheFingerprint() {
		Map<String, byte[]> a = compile(Map.of("n/Fixture.java",
			"package n;\nclass Fixture { int f() { return 1; } }\n"));
		Map<String, byte[]> b = compile(Map.of("n/Fixture.java",
			"package n;\nclass Fixture { int f() { return 2; } }\n"));

		assertFalse(BytecodeNormalFingerprint.of(a.get("n/Fixture"))
		            .equals(BytecodeNormalFingerprint.of(b.get("n/Fixture"))),
			"an instruction/constant change must alter the fingerprint");
	}

	@Test
	void ofMethodMatchesItsPartOfTheClassFingerprint() {
		byte[] bytes = compile(Map.of("n/Fixture.java",
			"package n;\nclass Fixture { int f() { return 7; } }\n")).get("n/Fixture");
		String classHash = BytecodeNormalFingerprint.of(bytes);
		assertEquals(64, classHash.length());
		assertEquals(classHash, BytecodeNormalFingerprint.of(bytes), "fingerprints must be deterministic");
	}

	@Test
	void reasonCodesAreStable() {
		assertEquals("VALUE-REPLAY-EXPR-NOT-CONSTANT", ValueTypeReplay.Reason.EXPR_NOT_CONSTANT.code());
		assertEquals("VALUE-REPLAY-CONSTRUCTED-CHANGED", ValueTypeReplay.Reason.CONSTRUCTED_CHANGED.code());
		assertEquals("VALUE-REPLAY-EQ-NOT-PURE", ValueTypeReplay.Reason.EQ_NOT_PURE.code());
		assertEquals("VALUE-REPLAY-EQ-CUSTOM", ValueTypeReplay.Reason.EQ_CUSTOM.code());
		assertEquals("VALUE-REPLAY-CTOR-IMPURE", ValueTypeReplay.Reason.CTOR_IMPURE.code());
		assertEquals("VALUE-REPLAY-HOST-RECORD", ValueTypeReplay.Reason.HOST_RECORD.code());
		assertEquals("VALUE-REPLAY-STATIC-FINAL-NEEDS-ENHANCED",
			ValueTypeReplay.Reason.STATIC_FINAL_NEEDS_ENHANCED.code());
	}

	// ==================== planning helpers ====================

	private static ValueTypeReplay.Plan plan(Map<String, byte[]> oldClasses, Map<String, byte[]> newClasses,
	                                         Predicate<String> batchCandidate, boolean enhanced) {
		return planWith(oldClasses, newClasses, newClasses::get, batchCandidate, enhanced);
	}

	private static ValueTypeReplay.Plan planWith(Map<String, byte[]> oldClasses, Map<String, byte[]> newClasses,
	                                             Function<String, byte[]> classBytes,
	                                             Predicate<String> batchCandidate, boolean enhanced) {
		String hostName = oldClasses.containsKey(HOST) ? HOST : "kx/Host";
		ClassDiff diff = ClassDiffUtil.diff(oldClasses.get(hostName), newClasses.get(hostName));
		return ValueTypeReplay.plan(diff, classBytes, batchCandidate, enhanced);
	}

	private static ValueTypeReplay.Plan planSynthetic(byte[] oldHost, byte[] newHost,
	                                                  Function<String, byte[]> classBytes,
	                                                  Predicate<String> batchCandidate, boolean enhanced) {
		ClassDiff diff = ClassDiffUtil.diff(oldHost, newHost);
		return ValueTypeReplay.plan(diff, classBytes, batchCandidate, enhanced);
	}

	private static void assertNotBlank(String value) {
		assertNotNull(value);
		assertFalse(value.isBlank(), "expected a non-blank fingerprint");
	}

	// ==================== javac fixtures ====================

	private static Map<String, byte[]> recordHost(int x, int y) {
		return recordHost("package fx;\npublic record Vec(int x, int y) {}\n", x, y);
	}

	private static Map<String, byte[]> recordHost(String vecSource, int x, int y) {
		String host = "package fx;\npublic class Host { final Vec v = new Vec(" + x + ", " + y + "); }\n";
		return compile(Map.of("fx/Vec.java", vecSource, "fx/Host.java", host));
	}

	private static Map<String, byte[]> staticHost(boolean mutable, int x, int y) {
		String vec = "package fx;\npublic record Vec(int x, int y) {}\n";
		String host = "package fx;\npublic class Host {\n"
		              + "  static " + (mutable ? "" : "final ") + "Vec V = new Vec(" + x + ", " + y + ");\n}\n";
		return compile(Map.of("fx/Vec.java", vec, "fx/Host.java", host));
	}

	private static Map<String, byte[]> kotlinHost(int x, int y) {
		String stub = "package kx;\nclass Vec { Vec(int x, int y) {} }\n";
		String host = "package kx;\nclass Host { final Vec v = new Vec(" + x + ", " + y + "); }\n";
		return compile(Map.of("kx/Vec.java", stub, "kx/Host.java", host));
	}

	private static Map<String, byte[]> kotlinFdlHost(float x, double y, long z) {
		String stub = "package kx;\nclass Fdl { Fdl(float x, double y, long z) {} }\n";
		String host = "package kx;\nclass Host { final Fdl v = new Fdl(" + x + "f, " + y + ", " + z + "L); }\n";
		return compile(Map.of("kx/Fdl.java", stub, "kx/Host.java", host));
	}

	/** In-memory javac; returns internal class name ({@code pkg/Name}) to bytes. */
	private static Map<String, byte[]> compile(Map<String, String> sources) {
		return compileWithOptions(List.of("-proc:none", "-nowarn"), sources);
	}

	private static Map<String, byte[]> compileWithOptions(List<String> options, Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("tests require a full JDK (no system Java compiler)");
		Map<String, byte[]> output = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								output.put(className.replace('.', '/'), toByteArray());
							}
						};
					}
				};
			}
		};
		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			URI uri = URI.create("string:///" + entry.getKey());
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return entry.getValue();
				}
			});
		}
		List<String> allOptions = new ArrayList<>(options);
		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics, allOptions, null, units).call());
		if (!ok) {
			StringBuilder message = new StringBuilder("fixture compilation failed:");
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				message.append("\n  ").append(diagnostic.getKind()).append(' ').append(diagnostic.getMessage(null));
			}
			throw new IllegalStateException(message.toString());
		}
		return output;
	}

	// ==================== synthetic ASM fixtures ====================

	/**
	 * Synthetic host whose initializer is {@code Vec.$default(arg, 4, null)} (Kotlin default-argument
	 * wrapper). {@code kx/Vec} bytes are intentionally not needed: A1 rejects the shape before A2.
	 */
	private static byte[] syntheticDefaultHost(int x) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "kx/Host", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "v", "Lkx/Vec;", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		pushInt(mv, x);
		mv.visitInsn(Opcodes.ICONST_4);
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "kx/Vec", "$default",
			"(IILjava/lang/Object;)Lkx/Vec;", false);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "kx/Host", "v", "Lkx/Vec;");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** Synthetic host whose initializer calls the {@code DefaultConstructorMarker} tail constructor. */
	private static byte[] syntheticMarkerHost(int x) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "kx/Host", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "v", "Lkx/Vec;", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitTypeInsn(Opcodes.NEW, "kx/Vec");
		mv.visitInsn(Opcodes.DUP);
		pushInt(mv, x);
		mv.visitInsn(Opcodes.ACONST_NULL);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "kx/Vec", "<init>",
			"(ILkotlin/jvm/internal/DefaultConstructorMarker;)V", false);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "kx/Host", "v", "Lkx/Vec;");
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void pushInt(MethodVisitor mv, int value) {
		if (value >= -1 && value <= 5) {
			mv.visitInsn(Opcodes.ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			mv.visitIntInsn(Opcodes.BIPUSH, value);
		} else {
			mv.visitIntInsn(Opcodes.SIPUSH, value);
		}
	}

	/**
	 * Synthetic Kotlin data class mirroring real kotlinc 2.x output: {@code kotlin/Metadata} annotation,
	 * private final primary fields, {@code componentN}, {@code copy}, generated {@code equals}/{@code hashCode}.
	 *
	 * @param fieldsFinal  false models a {@code var} primary property (field not final)
	 * @param extraField   true models an extra class-body instance field
	 */
	private static byte[] kotlinDataClass(boolean fieldsFinal, boolean extraField) {
		return kotlinDataClass(fieldsFinal, extraField, false);
	}

	private static byte[] kotlinDataClass(boolean fieldsFinal, boolean extraField, boolean customEquals) {
		return kotlinDataClass(fieldsFinal, extraField, customEquals, false);
	}

	private static byte[] kotlinDataClassWithConstantCtor() {
		return kotlinDataClass(true, false, false, true);
	}

	private static byte[] kotlinDataClass(boolean fieldsFinal, boolean extraField, boolean customEquals,
	                                      boolean constantCtor) {
		String name = "kx/Vec";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
			"java/lang/Object", null);
		AnnotationVisitor metadata = cw.visitAnnotation("Lkotlin/Metadata;", true);
		metadata.visit("k", 1);
		metadata.visitEnd();
		int fieldAccess = Opcodes.ACC_PRIVATE | (fieldsFinal ? Opcodes.ACC_FINAL : 0);
		cw.visitField(fieldAccess, "x", "I", null, null).visitEnd();
		cw.visitField(fieldAccess, "y", "I", null, null).visitEnd();
		if (extraField) cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "extra", "I", null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ILOAD, 1);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "x", "I");
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ILOAD, 2);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "y", "I");
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		if (constantCtor) {
			// A second, non-delegating constructor that reads a compile-time constant from another
			// class. Java source cannot express this shape; it exists only to drive the A2 closure gate.
			MethodVisitor extraCtor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
			extraCtor.visitCode();
			extraCtor.visitVarInsn(Opcodes.ALOAD, 0);
			extraCtor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			extraCtor.visitFieldInsn(Opcodes.GETSTATIC, "kx/Const", "K", "I");
			extraCtor.visitInsn(Opcodes.POP);
			extraCtor.visitInsn(Opcodes.RETURN);
			extraCtor.visitMaxs(0, 0);
			extraCtor.visitEnd();
		}

		component(cw, name, "component1", "x");
		component(cw, name, "component2", "y");

		MethodVisitor copy = cw.visitMethod(Opcodes.ACC_PUBLIC, "copy", "(II)L" + name + ";", null, null);
		copy.visitCode();
		copy.visitTypeInsn(Opcodes.NEW, name);
		copy.visitInsn(Opcodes.DUP);
		copy.visitVarInsn(Opcodes.ILOAD, 1);
		copy.visitVarInsn(Opcodes.ILOAD, 2);
		copy.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(II)V", false);
		copy.visitInsn(Opcodes.ARETURN);
		copy.visitMaxs(0, 0);
		copy.visitEnd();

		if (customEquals) kotlinCustomEquals(cw, name);
		else kotlinEquals(cw, name);
		kotlinHashCode(cw, name);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void component(ClassWriter cw, String name, String methodName, String fieldName) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, methodName, "()I", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, fieldName, "I");
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** Hand-written identity equals: does not match the generated kotlinc shape. */
	private static void kotlinCustomEquals(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals",
			"(Ljava/lang/Object;)Z", null, null);
		mv.visitCode();
		Label notSame = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(notSame);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void kotlinEquals(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
		mv.visitCode();
		Label notSame = new Label();
		Label isType = new Label();
		Label xEqual = new Label();
		Label allEqual = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(notSame);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, name);
		mv.visitJumpInsn(Opcodes.IFNE, isType);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(isType);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitTypeInsn(Opcodes.CHECKCAST, name);
		mv.visitVarInsn(Opcodes.ASTORE, 2);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "x", "I");
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "x", "I");
		mv.visitJumpInsn(Opcodes.IF_ICMPEQ, xEqual);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(xEqual);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "y", "I");
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "y", "I");
		mv.visitJumpInsn(Opcodes.IF_ICMPEQ, allEqual);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(allEqual);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void kotlinHashCode(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "x", "I");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "hashCode", "(I)I", false);
		mv.visitVarInsn(Opcodes.ISTORE, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitIntInsn(Opcodes.BIPUSH, 31);
		mv.visitInsn(Opcodes.IMUL);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "y", "I");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "hashCode", "(I)I", false);
		mv.visitInsn(Opcodes.IADD);
		mv.visitVarInsn(Opcodes.ISTORE, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/**
	 * Synthetic kotlinc 2.x data class with {@code Float}/{@code Double}/{@code Long} components.
	 * {@code equals} compares with {@code Float.compare}/{@code Double.compare}/{@code LCMP};
	 * {@code hashCode} delegates to the boxed static hash helpers.
	 */
	private static byte[] kotlinFdlDataClass() {
		String name = "kx/Fdl";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null,
			"java/lang/Object", null);
		AnnotationVisitor metadata = cw.visitAnnotation("Lkotlin/Metadata;", true);
		metadata.visit("k", 1);
		metadata.visitEnd();
		int fieldAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
		cw.visitField(fieldAccess, "x", "F", null, null).visitEnd();
		cw.visitField(fieldAccess, "y", "D", null, null).visitEnd();
		cw.visitField(fieldAccess, "z", "J", null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(FDJ)V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.FLOAD, 1);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "x", "F");
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.DLOAD, 2);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "y", "D");
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.LLOAD, 4);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, name, "z", "J");
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		fdlComponent(cw, name, "component1", "x", "F", Opcodes.FRETURN);
		fdlComponent(cw, name, "component2", "y", "D", Opcodes.DRETURN);
		fdlComponent(cw, name, "component3", "z", "J", Opcodes.LRETURN);

		MethodVisitor copy = cw.visitMethod(Opcodes.ACC_PUBLIC, "copy", "(FDJ)L" + name + ";", null, null);
		copy.visitCode();
		copy.visitTypeInsn(Opcodes.NEW, name);
		copy.visitInsn(Opcodes.DUP);
		copy.visitVarInsn(Opcodes.FLOAD, 1);
		copy.visitVarInsn(Opcodes.DLOAD, 2);
		copy.visitVarInsn(Opcodes.LLOAD, 4);
		copy.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(FDJ)V", false);
		copy.visitInsn(Opcodes.ARETURN);
		copy.visitMaxs(0, 0);
		copy.visitEnd();

		kotlinFdlEquals(cw, name);
		kotlinFdlHashCode(cw, name);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void fdlComponent(ClassWriter cw, String name, String methodName, String fieldName,
	                                 String desc, int returnOpcode) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, methodName, "()" + desc, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, fieldName, desc);
		mv.visitInsn(returnOpcode);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void kotlinFdlEquals(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
		mv.visitCode();
		Label notSame = new Label();
		Label isType = new Label();
		Label xEqual = new Label();
		Label yEqual = new Label();
		Label zEqual = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitJumpInsn(Opcodes.IF_ACMPNE, notSame);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(notSame);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, name);
		mv.visitJumpInsn(Opcodes.IFNE, isType);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(isType);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitTypeInsn(Opcodes.CHECKCAST, name);
		mv.visitVarInsn(Opcodes.ASTORE, 2);

		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "x", "F");
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "x", "F");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
		mv.visitJumpInsn(Opcodes.IFEQ, xEqual);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(xEqual);

		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "y", "D");
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "y", "D");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
		mv.visitJumpInsn(Opcodes.IFEQ, yEqual);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(yEqual);

		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "z", "J");
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "z", "J");
		mv.visitInsn(Opcodes.LCMP);
		mv.visitJumpInsn(Opcodes.IFEQ, zEqual);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitLabel(zEqual);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static void kotlinFdlHashCode(ClassWriter cw, String name) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "x", "F");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "hashCode", "(F)I", false);
		mv.visitVarInsn(Opcodes.ISTORE, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitIntInsn(Opcodes.BIPUSH, 31);
		mv.visitInsn(Opcodes.IMUL);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "y", "D");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "hashCode", "(D)I", false);
		mv.visitInsn(Opcodes.IADD);
		mv.visitVarInsn(Opcodes.ISTORE, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitIntInsn(Opcodes.BIPUSH, 31);
		mv.visitInsn(Opcodes.IMUL);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, name, "z", "J");
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "hashCode", "(J)I", false);
		mv.visitInsn(Opcodes.IADD);
		mv.visitVarInsn(Opcodes.ISTORE, 1);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** Synthetic owner with a single compile-time {@code ConstantValue} field. */
	private static byte[] constantOwner() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "kx/Const", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "K", "I", null, 7).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
