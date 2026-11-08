import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil;
import remold.RemoldAgent;
import remold.ref.InitializerReplay;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the L1 plan (whitelist + inherited constant initializer drift).
 * No DCEVM needed: the plan is pure bytecode analysis.
 */
public class InitializerReplayTest {

	@AfterEach
	void clearProperty() {
		System.clearProperty(InitializerReplay.PARENTS_PROPERTY);
	}

	private static InitializerReplay.Plan planFor(String pkg, String hostV1, String hostV2,
	                                              String parentV1, String parentV2) throws Exception {
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			pkg + ".Host", hostV1,
			pkg + ".Parent", parentV1));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			pkg + ".Host", hostV2,
			pkg + ".Parent", parentV2));
		// Older/newer parent bytes are resolved through the agent cache (no class loading).
		RemoldAgent.bytecodeCache.put(pkg + ".Parent", v2.get(pkg + ".Parent"));
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			v1.get(pkg + ".Host$1"), v2.get(pkg + ".Host$1"));
		return InitializerReplay.plan(null, diff);
	}

	@Test
	void whitelistedParentPlansConstantReplay() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp1.Parent");
		InitializerReplay.Plan plan = planFor("irp1",
			"""
			package irp1;
			public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
			""",
			"""
			package irp1;
			public class Host { public Parent make() { return new Parent() {{ health = 100; }}; } }
			""",
			"""
			package irp1;
			public class Parent { public int health; }
			""",
			"""
			package irp1;
			public class Parent { public int health; }
			""");
		assertTrue(plan.notices().isEmpty(), "unexpected notices: " + plan.notices());
		InitializerReplay.ReplayField field = plan.fields().get("health");
		assertNotNull(field, "health must be planned, fields=" + plan.fields());
		assertEquals("irp1/Parent", field.declaringClass(), "declaring class");
		assertEquals(Integer.valueOf(90), field.expected(), "expected = OLD initializer");
		assertEquals(Integer.valueOf(90), field.bootstrapArgument(), "bootstrap constant");
		assertEquals("90", field.oldText());
		assertEquals("100", field.newText());
	}

	@Test
	void noWhitelistMeansL1Off() throws Exception {
		InitializerReplay.Plan plan = planFor("irp2",
			"""
			package irp2;
			public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
			""",
			"""
			package irp2;
			public class Host { public Parent make() { return new Parent() {{ health = 100; }}; } }
			""",
			"""
			package irp2;
			public class Parent { public int health; }
			""",
			"""
			package irp2;
			public class Parent { public int health; }
			""");
		assertTrue(plan.fields().isEmpty(), "L1 must be off with an empty whitelist: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "no notices while L1 is off: " + plan.notices());
	}

	@Test
	void unrelatedWhitelistIsIgnored() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "other.Parent");
		InitializerReplay.Plan plan = planFor("irp3",
			"""
			package irp3;
			public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
			""",
			"""
			package irp3;
			public class Host { public Parent make() { return new Parent() {{ health = 100; }}; } }
			""",
			"""
			package irp3;
			public class Parent { public int health; }
			""",
			"""
			package irp3;
			public class Parent { public int health; }
			""");
		assertTrue(plan.fields().isEmpty(), "non-ancestor whitelist must not replay: " + plan.fields());
	}

	@Test
	void ancestorEntryMatchesGrandparentField() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp4.Base");
		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"irp4.Host", """
				package irp4;
				public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
				""",
			"irp4.Parent", """
				package irp4;
				public class Parent extends Base { }
				""",
			"irp4.Base", """
				package irp4;
				public class Base { public int health; }
				"""));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			"irp4.Host", """
				package irp4;
				public class Host { public Parent make() { return new Parent() {{ health = 100; }}; } }
				""",
			"irp4.Parent", """
				package irp4;
				public class Parent extends Base { }
				""",
			"irp4.Base", """
				package irp4;
				public class Base { public int health; }
				"""));
		RemoldAgent.bytecodeCache.put("irp4.Parent", v2.get("irp4.Parent"));
		RemoldAgent.bytecodeCache.put("irp4.Base", v2.get("irp4.Base"));
		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(
			v1.get("irp4.Host$1"), v2.get("irp4.Host$1"));
		InitializerReplay.Plan plan = InitializerReplay.plan(null, diff);
		InitializerReplay.ReplayField field = plan.fields().get("health");
		assertNotNull(field, "grandparent field must be planned, fields=" + plan.fields()
			+ ", notices=" + plan.notices());
		assertEquals("irp4/Base", field.declaringClass(), "declaring class is the grandparent");
	}

	@Test
	void declaredFieldIsNotReplayedByL1() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp5.Parent");
		InitializerReplay.Plan plan = planFor("irp5",
			"""
			package irp5;
			public class Host {
			    public Parent make() { return new Parent() { int own; { own = 1; } }; }
			}
			""",
			"""
			package irp5;
			public class Host {
			    public Parent make() { return new Parent() { int own; { own = 2; } }; }
			}
			""",
			"""
			package irp5;
			public class Parent { }
			""",
			"""
			package irp5;
			public class Parent { }
			""");
		assertTrue(plan.fields().isEmpty(), "own declared field is the ordinary path: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "own field must stay silent: " + plan.notices());
	}

	@Test
	void nonConstantOldInitializerIsRefusedWithNotice() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp6.Parent");
		InitializerReplay.Plan plan = planFor("irp6",
			"""
			package irp6;
			public class Host {
			    public Parent make() { return new Parent() {{ health = base + 10; }}; }
			}
			""",
			"""
			package irp6;
			public class Host {
			    public Parent make() { return new Parent() {{ health = base + 20; }}; }
			}
			""",
			"""
			package irp6;
			public class Parent { public int base = 5; public int health; }
			""",
			"""
			package irp6;
			public class Parent { public int base = 5; public int health; }
			""");
		assertTrue(plan.fields().isEmpty(), "non-constant old value must not be planned: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("not a compile-time constant")),
			"expected a constant-refusal notice, actual=" + plan.notices());
	}

	@Test
	void nonConstantNewInitializerIsRefusedWithNotice() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp7.Parent");
		InitializerReplay.Plan plan = planFor("irp7",
			"""
			package irp7;
			public class Host {
			    public Parent make() { return new Parent() {{ health = 90; }}; }
			}
			""",
			"""
			package irp7;
			public class Host {
			    public Parent make() { return new Parent() {{ health = base + 20; }}; }
			}
			""",
			"""
			package irp7;
			public class Parent { public int base = 5; public int health; }
			""",
			"""
			package irp7;
			public class Parent { public int base = 5; public int health; }
			""");
		assertTrue(plan.fields().isEmpty(), "non-constant new value must not be planned: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("self-contained constant")),
			"expected a self-contained-constant notice, actual=" + plan.notices());
	}

	@Test
	void referenceTypedFieldIsRefusedWithNotice() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp8.Parent");
		InitializerReplay.Plan plan = planFor("irp8",
			"""
			package irp8;
			public class Host { public Parent make() { return new Parent() {{ name = "a"; }}; } }
			""",
			"""
			package irp8;
			public class Host { public Parent make() { return new Parent() {{ name = "b"; }}; } }
			""",
			"""
			package irp8;
			public class Parent { public String name; }
			""",
			"""
			package irp8;
			public class Parent { public String name; }
			""");
		assertTrue(plan.fields().isEmpty(), "reference fields are out of v1 scope: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("reference-typed")),
			"expected a reference-type notice, actual=" + plan.notices());
	}

	@Test
	void parentReinitAnnotationWinsAndIsRefusedWithNotice() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp9.Parent");
		InitializerReplay.Plan plan = planFor("irp9",
			"""
			package irp9;
			public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
			""",
			"""
			package irp9;
			public class Host { public Parent make() { return new Parent() {{ health = 100; }}; } }
			""",
			"""
			package irp9;
			import remold.annotation.RemoldReinit;
			public class Parent {
			    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
			    public int health;
			}
			""",
			"""
			package irp9;
			import remold.annotation.RemoldReinit;
			public class Parent {
			    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
			    public int health;
			}
			""");
		assertTrue(plan.fields().isEmpty(), "@RemoldReinit path must win: " + plan.fields());
		assertTrue(plan.notices().stream().anyMatch(n -> n.contains("@RemoldReinit")),
			"expected the annotation-priority notice, actual=" + plan.notices());
	}

	@Test
	void sameValueIsNotPlanned() throws Exception {
		System.setProperty(InitializerReplay.PARENTS_PROPERTY, "irp10.Parent");
		InitializerReplay.Plan plan = planFor("irp10",
			"""
			package irp10;
			public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
			""",
			"""
			package irp10;
			public class Host { public Parent make() { return new Parent() {{ health = 90; }}; } }
			""",
			"""
			package irp10;
			public class Parent { public int health; }
			""",
			"""
			package irp10;
			public class Parent { public int health; }
			""");
		assertTrue(plan.fields().isEmpty(), "same value has nothing to replay: " + plan.fields());
		assertTrue(plan.notices().isEmpty(), "no notices for an unchanged value: " + plan.notices());
	}

	@Test
	void appliedBookkeepingIsDrained() {
		InitializerReplay.recordApplied("irp11.Host$1", "health");
		Map<String, Set<String>> drained = InitializerReplay.drainApplied();
		assertEquals(Set.of("health"), drained.get("irp11.Host$1"));
		assertTrue(InitializerReplay.drainApplied().isEmpty(), "second drain must be empty");
	}
}
