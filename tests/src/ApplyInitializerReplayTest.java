import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.ApplyInitializerReplay;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the A-tier Kotlin-apply replay analysis (whitelisted static holder,
 * straight-line {@code <clinit>}, constant {@code PUTFIELD} + single {@code PUTSTATIC} dest).
 *
 * <p>No DCEVM needed: the plan is pure bytecode analysis. Java fixtures are used because
 * javac emits the same static-initializer shape as the Kotlin {@code apply} inlining
 * (javap comparison of both shapes is recorded in {@code docs/initfix/13-kotlin-apply-replay.md} §2).</p>
 */
public class ApplyInitializerReplayTest {

	private static final String PROPERTY = ApplyInitializerReplay.HOLDERS_PROPERTY;

	@AfterEach
	void cleanup() {
		System.clearProperty(PROPERTY);
		for (String cls : new String[]{
			"ahr1.Turret", "ahp1.Turret", "ahnw1.Turret", "ahnc1.Turret", "ahref1.Turret",
			"ahfin1.Turret", "ahdesc1.Turret", "ahmulti1.Turret", "ahkind1.Turret", "ahsetter1.Turret",
			"ahold1.Turret", "ahinit1.Turret"}) {
			RemoldAgent.bytecodeCache.remove(cls);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Positive: whitelisted holder, constant drift, one object reachable from one static field.
	// ------------------------------------------------------------------------------------------------
	@Test
	void whitelistedHolderPlansConstantReplay() throws Exception {
		System.setProperty(PROPERTY, "ahr1.Blocks");
		Plan plan = planFor("ahr1", v1(), v2());
		assertTrue(plan.notices().isEmpty(), "unexpected notices: " + plan.notices());
		assertEquals(1, plan.fields().size(), "one field must be planned: " + plan.fields());
		ApplyInitializerReplay.ReplayField field = plan.fields().values().iterator().next();
		assertEquals("duo -> ahr1.Turret.health", field.key(), "diagnostic key");
		assertEquals("ahr1/Turret", field.targetClass(), "target class is the apply object type");
		assertEquals("duo", field.dest(), "destination static field");
		assertEquals("Lahr1/Turret;", field.destDesc(), "destination descriptor");
		assertEquals(Integer.valueOf(90), field.expected(), "expected = OLD initializer constant");
		assertEquals(Integer.valueOf(90), field.bootstrapArgument(), "old bootstrap constant");
		assertEquals(Integer.valueOf(100), field.newRaw(), "new constant pushed by the patch");
		assertEquals("90", field.oldText());
		assertEquals("100", field.newText());
		assertEquals("replayed", plan.decisions().get(field.key()), "decision");
	}

	@Test
	void emptyWhitelistMeansChannelOff() throws Exception {
		Plan plan = planFor("ahr1", v1(), v2());
		assertTrue(plan.fields().isEmpty(), "channel must be off: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "no notices while off: " + plan.notices());
		assertTrue(plan.decisions().isEmpty(), "no decisions while off: " + plan.decisions());
	}

	@Test
	void nonWhitelistedHolderIsIgnored() throws Exception {
		System.setProperty(PROPERTY, "other.Blocks");
		Plan plan = planFor("ahr1", v1(), v2());
		assertTrue(plan.fields().isEmpty(), "non-whitelisted holder must not be planned: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "non-whitelisted holder stays silent: " + plan.notices());
	}

	@Test
	void sameConstantIsNotPlanned() throws Exception {
		System.setProperty(PROPERTY, "ahr1.Blocks");
		Plan plan = planFor("ahr1", v1(), v1());
		assertTrue(plan.fields().isEmpty(), "same value has nothing to replay: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "no notices for an unchanged value: " + plan.notices());
	}

	@Test
	void primitiveFieldKindsArePlanned() throws Exception {
		String pkg = "ahkind1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", """
				package ahkind1;
				public class Turret {
				    public long cooldown;
				    public float speed;
				    public double armor;
				    public boolean active;
				    public Turret(String n) { }
				}
				""",
			pkg + ".Blocks", """
				package ahkind1;
				public class Blocks {
				    public static final Turret duo;
				    static {
				        Turret t = new Turret("duo");
				        t.cooldown = 5L;
				        t.speed = 1.5f;
				        t.armor = 2.5d;
				        t.active = true;
				        duo = t;
				    }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahkind1;
				public class Blocks {
				    public static final Turret duo;
				    static {
				        Turret t = new Turret("duo");
				        t.cooldown = 7L;
				        t.speed = 2.5f;
				        t.armor = 3.5d;
				        t.active = false;
				        duo = t;
				    }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.notices().isEmpty(), "unexpected notices: " + plan.notices());
		assertEquals(4, plan.fields().size(), "all four primitive kinds must be planned: " + plan.fields());
		assertEquals(Long.valueOf(5L), plan.fields().get("duo -> ahkind1.Turret.cooldown").bootstrapArgument());
		assertEquals(Long.valueOf(7L), plan.fields().get("duo -> ahkind1.Turret.cooldown").newRaw());
		assertEquals(Float.valueOf(1.5f), plan.fields().get("duo -> ahkind1.Turret.speed").bootstrapArgument());
		assertEquals(Double.valueOf(2.5d), plan.fields().get("duo -> ahkind1.Turret.armor").bootstrapArgument());
		assertEquals(Boolean.TRUE, plan.fields().get("duo -> ahkind1.Turret.active").expected());
		assertEquals(Integer.valueOf(1), plan.fields().get("duo -> ahkind1.Turret.active").bootstrapArgument());
	}

	// ------------------------------------------------------------------------------------------------
	// Negative: non-constant initializers are refused with a notice (no drift can be proven).
	// ------------------------------------------------------------------------------------------------
	@Test
	void nonConstantOldInitializerIsRefusedWithNotice() throws Exception {
		String pkg = "ahnc1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahnc1; public class Turret { public int health; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahnc1;
				public class Blocks {
				    static int base = 5;
				    public static final Turret duo;
				    static {
				        Turret t = new Turret("duo");
				        t.health = base + 10;
				        duo = t;
				    }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahnc1;
				public class Blocks {
				    static int base = 5;
				    public static final Turret duo;
				    static {
				        Turret t = new Turret("duo");
				        t.health = base + 20;
				        duo = t;
				    }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "non-constant old value must not be planned: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("not a compile-time constant")),
			"expected a constant-refusal notice, actual=" + plan.notices());
	}

	@Test
	void nonConstantNewInitializerIsRefusedWithNotice() throws Exception {
		String pkg = "ahnw1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahnw1; public class Turret { public int health; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahnw1;
				public class Blocks {
				    static int base = 5;
				    public static final Turret duo;
				    static {
				        Turret t = new Turret("duo");
				        t.health = 90;
				        duo = t;
				    }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahnw1;
				public class Blocks {
				    static int base = 5;
				    public static final Turret duo;
				    static {
				        Turret t = new Turret("duo");
				        t.health = base + 20;
				        duo = t;
				    }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "non-constant new value must not be planned: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("not a compile-time constant")),
			"expected a constant-refusal notice, actual=" + plan.notices());
	}

	@Test
	void referenceTypedFieldIsRefusedWithDecision() throws Exception {
		String pkg = "ahref1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahref1; public class Turret { public String name; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahref1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.name = "a"; duo = t; }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahref1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.name = "b"; duo = t; }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "reference fields are out of scope: " + plan.fields());
		assertTrue(plan.decisions().values().stream().anyMatch(v -> v.contains("reference-typed")),
			"expected a reference-type decision, actual=" + plan.decisions());
	}

	@Test
	void finalTargetFieldIsRefusedWithDecision() throws Exception {
		String pkg = "ahfin1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahfin1; public class Turret { public int health; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahfin1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.health = 90; duo = t; }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahfin1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.health = 100; duo = t; }
				}
				""");
		Map<String, byte[]> v1 = LayoutGateAssert.compile(a);
		Map<String, byte[]> v2 = LayoutGateAssert.compile(b);
		// javac refuses to write a final field from another class's static initializer, so the
		// fixture marks the field final in bytecode only (same PUTFIELD shape, different metadata).
		v2.put(pkg + ".Turret", markFinal(v2.get(pkg + ".Turret"), "health", "I"));
		RemoldAgent.bytecodeCache.put(pkg + ".Turret", v2.get(pkg + ".Turret"));
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1.get(pkg + ".Blocks"), v2.get(pkg + ".Blocks"));
		ApplyInitializerReplay.Plan plan = ApplyInitializerReplay.plan(null, diff);
		assertTrue(plan.fields().isEmpty(), "final target must not be planned: " + plan.fields());
		assertTrue(plan.decisions().values().stream().anyMatch(v -> v.contains("final")),
			"expected a final-field decision, actual=" + plan.decisions());
	}

	@Test
	void destinationDescriptorMismatchIsRefusedWithNotice() throws Exception {
		String pkg = "ahdesc1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahdesc1; public class Turret { public int health; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahdesc1;
				public class Blocks {
				    public static final Object duo;
				    static { Turret t = new Turret("duo"); t.health = 90; duo = t; }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahdesc1;
				public class Blocks {
				    public static final Object duo;
				    static { Turret t = new Turret("duo"); t.health = 100; duo = t; }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "descriptor mismatch must not be planned: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("does not match the apply object type")),
			"expected a descriptor-mismatch notice, actual=" + plan.notices());
	}

	@Test
	void multipleDestinationsForOneObjectAreRefusedWithNotice() throws Exception {
		String pkg = "ahmulti1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahmulti1; public class Turret { public int health; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahmulti1;
				public class Blocks {
				    public static Turret duoA;
				    public static Turret duoB;
				    static {
				        Turret t = new Turret("duo");
				        t.health = 90;
				        duoA = t;
				        duoB = t;
				    }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahmulti1;
				public class Blocks {
				    public static Turret duoA;
				    public static Turret duoB;
				    static {
				        Turret t = new Turret("duo");
				        t.health = 100;
				        duoA = t;
				        duoB = t;
				    }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "shared object must not be planned: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("multiple static fields")),
			"expected a multiple-destination notice, actual=" + plan.notices());
	}

	@Test
	void kotlinVarSetterShapeIsRefusedAsEscape() throws Exception {
		String pkg = "ahsetter1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", """
				package ahsetter1;
				public class Turret {
				    private int health;
				    public Turret(String n) { }
				    public void setHealth(int h) { health = h; }
				    public int health() { return health; }
				}
				""",
			pkg + ".Blocks", """
				package ahsetter1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.setHealth(90); duo = t; }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahsetter1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.setHealth(100); duo = t; }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "method-call write is out of scope: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("escapes") && n.contains("method call")),
			"expected an escape notice naming the method call, actual=" + plan.notices());
	}

	@Test
	void holderWithoutApplyShapeIsSilent() throws Exception {
		String pkg = "ahinit1";
		System.setProperty(PROPERTY, pkg + ".Blocks");
		Map<String, String> a = Map.of(
			pkg + ".Turret", "package ahinit1; public class Turret { public int health; public Turret(String n) { } }",
			pkg + ".Blocks", """
				package ahinit1;
				public class Blocks {
				    public static int counter = 1;
				    public static final Turret duo;
				    static { duo = new Turret("duo"); }
				}
				""");
		Map<String, String> b = Map.of(
			pkg + ".Turret", a.get(pkg + ".Turret"),
			pkg + ".Blocks", """
				package ahinit1;
				public class Blocks {
				    public static int counter = 2;
				    public static final Turret duo;
				    static { duo = new Turret("duo"); }
				}
				""");
		Plan plan = planFor(pkg, a, b);
		assertTrue(plan.fields().isEmpty(), "no apply write site: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "no notice for a plain holder: " + plan.notices());
	}

	// ==================== fixture helpers ====================

	private record Plan(Map<String, ApplyInitializerReplay.ReplayField> fields,
	                    Map<String, String> decisions,
	                    java.util.List<String> notices) {
	}

	private static Plan planFor(String pkg, Map<String, String> v1, Map<String, String> v2) throws Exception {
		Map<String, byte[]> b1 = LayoutGateAssert.compile(v1);
		Map<String, byte[]> b2 = LayoutGateAssert.compile(v2);
		RemoldAgent.bytecodeCache.put(pkg + ".Turret", b2.get(pkg + ".Turret"));
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(b1.get(pkg + ".Blocks"), b2.get(pkg + ".Blocks"));
		ApplyInitializerReplay.Plan plan = ApplyInitializerReplay.plan(null, diff);
		return new Plan(plan.fields(), plan.decisions(), plan.notices());
	}

	private static Map<String, String> v1() {
		return Map.of(
			"ahr1.Turret", "package ahr1; public class Turret { public int health; public Turret(String n) { } }",
			"ahr1.Blocks", """
				package ahr1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.health = 90; duo = t; }
				}
				""");
	}

	private static Map<String, String> v2() {
		return Map.of(
			"ahr1.Turret", "package ahr1; public class Turret { public int health; public Turret(String n) { } }",
			"ahr1.Blocks", """
				package ahr1;
				public class Blocks {
				    public static final Turret duo;
				    static { Turret t = new Turret("duo"); t.health = 100; duo = t; }
				}
				""");
	}

	/** Mark a field {@code ACC_FINAL} in place (fixture-only bytecode patch; shape unchanged). */
	private static byte[] markFinal(byte[] bytes, String fieldName, String desc) {
		ClassReader reader = new ClassReader(bytes);
		var        node   = new org.objectweb.asm.tree.ClassNode();
		reader.accept(node, 0);
		boolean found = false;
		for (var field : node.fields) {
			if (field.name.equals(fieldName) && field.desc.equals(desc)) {
				field.access |= Opcodes.ACC_FINAL;
				found = true;
			}
		}
		assertTrue(found, "fixture field not found: " + fieldName + " " + desc);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
