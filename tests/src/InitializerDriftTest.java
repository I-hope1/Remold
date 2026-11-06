import org.junit.jupiter.api.Test;
import remold.ref.InitializerDrift;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class InitializerDriftTest {

	private static Map<String, byte[]> compile(String pkg, String host, String parent) throws Exception {
		return LayoutGateAssert.compile(Map.of(
			pkg + ".Host", host,
			pkg + ".Parent", parent));
	}


	private static java.util.List<String> warningsFor(String key, byte[] oldBytes, Map<String, byte[]> batch) {
		remold.RemoldAgent.bytecodeCache.put(key, oldBytes);
		java.util.List<String> warns = new java.util.ArrayList<>();
		remold.RemoldAgent.Logger old = remold.RemoldAgent.logger;
		remold.RemoldAgent.logger = new remold.RemoldAgent.Logger() {
			public void log(String msg) {}
			public void info(String msg) {}
			public void warn(String msg) { warns.add(msg); }
			public void error(String msg) {}
			public void error(String msg, Throwable t) {}
		};
		try {
			InitializerDrift.report(batch);
		} finally {
			remold.RemoldAgent.logger = old;
		}
		return warns;
	}

	@Test
	void changeEmitsExactlyOneWarn() throws Exception {
		Map<String, byte[]> v1 = compile("idl6", """
			package idl6;
			public class Host { public Parent make() { return new Parent() {{ property = 1; }}; } }
			""", """
			package idl6;
			public class Parent { public int property; }
			""");
		Map<String, byte[]> v2 = compile("idl6", """
			package idl6;
			public class Host { public Parent make() { return new Parent() {{ property = 2; }}; } }
			""", """
			package idl6;
			public class Parent { public int property; }
			""");
		java.util.List<String> warns = warningsFor("idl6.Host$1", v1.get("idl6.Host$1"),
			Map.of("idl6.Host$1", v2.get("idl6.Host$1")));
		assertEquals(1, warns.size(), "one warn per class/round, actual=" + warns);
		assertTrue(warns.get(0).contains("property"), warns.get(0));
	}

	@Test
	void unchangedEmitsNoWarn() throws Exception {
		Map<String, byte[]> v1 = compile("idl7", """
			package idl7;
			public class Host { public Parent make() { return new Parent() {{ property = 7; }}; } }
			""", """
			package idl7;
			public class Parent { public int property; }
			""");
		Map<String, byte[]> v2 = compile("idl7", """
			package idl7;
			public class Host { public Parent make() { return new Parent() {{ property = 7; }}; } }
			""", """
			package idl7;
			public class Parent { public int property; }
			""");
		java.util.List<String> warns = warningsFor("idl7.Host$1", v1.get("idl7.Host$1"),
			Map.of("idl7.Host$1", v2.get("idl7.Host$1")));
		assertTrue(warns.isEmpty(), "unchanged initializer must be silent, actual=" + warns);
	}

	@Test
	void twoChangedFieldsStillOneWarnPerClass() throws Exception {
		Map<String, byte[]> v1 = compile("idl8", """
			package idl8;
			public class Host { public Parent make() { return new Parent() {{ a = 1; b = 2; }}; } }
			""", """
			package idl8;
			public class Parent { public int a; public int b; }
			""");
		Map<String, byte[]> v2 = compile("idl8", """
			package idl8;
			public class Host { public Parent make() { return new Parent() {{ a = 3; b = 4; }}; } }
			""", """
			package idl8;
			public class Parent { public int a; public int b; }
			""");
		java.util.List<String> warns = warningsFor("idl8.Host$1", v1.get("idl8.Host$1"),
			Map.of("idl8.Host$1", v2.get("idl8.Host$1")));
		assertEquals(1, warns.size(), "two fields must aggregate into one warn, actual=" + warns);
		assertTrue(warns.get(0).contains("a") && warns.get(0).contains("b"), warns.get(0));
	}

	@Test
	void existingDoubleBraceFieldChangeIsDetected() throws Exception {
		String pkg = "idl";
		Map<String, byte[]> v1 = compile(pkg, """
			package idl;
			public class Host {
			    public Parent make() { return new Parent() {{ property = 1; }}; }
			}
			""", """
			package idl;
			public class Parent { public int property; }
			""");
		Map<String, byte[]> v2 = compile(pkg, """
			package idl;
			public class Host {
			    public Parent make() { return new Parent() {{ property = 2; }}; }
			}
			""", """
			package idl;
			public class Parent { public int property; }
			""");
		List<String> changed = InitializerDrift.detect(v1.get("idl.Host$1"), v2.get("idl.Host$1"));
		assertTrue(changed.contains("property"), "changed=" + changed);
	}

	@Test
	void inheritedFieldWrittenByAnonymousInitIsDetected() throws Exception {
		String pkg = "idl2";
		Map<String, byte[]> v1 = compile(pkg, """
			package idl2;
			public class Host {
			    public Parent make() { return new Parent() {{ health = 90; }}; }
			}
			""", """
			package idl2;
			public class Parent { public int health; }
			""");
		Map<String, byte[]> v2 = compile(pkg, """
			package idl2;
			public class Host {
			    public Parent make() { return new Parent() {{ health = 100; }}; }
			}
			""", """
			package idl2;
			public class Parent { public int health; }
			""");
		List<String> changed = InitializerDrift.detect(v1.get("idl2.Host$1"), v2.get("idl2.Host$1"));
		assertTrue(changed.contains("health"), "inherited PUTFIELD must be seen; changed=" + changed);
	}

	@Test
	void addedFieldAndUnchangedInitializerAreNotReported() throws Exception {
		String pkg = "idl3";
		Map<String, byte[]> v1 = compile(pkg, """
			package idl3;
			public class Host {
			    public Parent make() { return new Parent() {{ property = 1; }}; }
			}
			""", """
			package idl3;
			public class Parent { public int property; }
			""");
		Map<String, byte[]> v2 = compile(pkg, """
			package idl3;
			public class Host {
			    public Parent make() { return new Parent() { int extra = 5; { property = 1; } }; }
			}
			""", """
			package idl3;
			public class Parent { public int property; }
			""");
		List<String> changed = InitializerDrift.detect(v1.get("idl3.Host$1"), v2.get("idl3.Host$1"));
		assertTrue(changed.isEmpty(), "added extra must not be reported; changed=" + changed);
	}

	@Test
	void sameInitializerIsNotReported() throws Exception {
		String pkg = "idl4";
		Map<String, byte[]> v1 = compile(pkg, """
			package idl4;
			public class Host {
			    public Parent make() { return new Parent() {{ property = 7; }}; }
			}
			""", """
			package idl4;
			public class Parent { public int property; }
			""");
		Map<String, byte[]> v2 = compile(pkg, """
			package idl4;
			public class Host {
			    public Parent make() { return new Parent() {{ property = 7; }}; }
			}
			""", """
			package idl4;
			public class Parent { public int property; }
			""");
		List<String> changed = InitializerDrift.detect(v1.get("idl4.Host$1"), v2.get("idl4.Host$1"));
		assertTrue(changed.isEmpty(), "unchanged initializer must not be reported; changed=" + changed);
	}

	@Test
	void inheritedFieldWithParentReinitGetsDedicatedWarning() throws Exception {
		String pkg = "idl5";
		Map<String, byte[]> v1 = compile(pkg, """
			package idl5;
			public class Host {
			    public Parent make() { return new Parent() {{ health = 90; }}; }
			}
			""", """
			package idl5;
			public class Parent { public int health; }
			""");
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			"idl5.Host", """
				package idl5;
				public class Host {
				    public Parent make() { return new Parent() {{ health = 100; }}; }
				}
				""",
			"idl5.Parent", """
				package idl5;
				import remold.annotation.RemoldReinit;
				public class Parent {
				    @RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE)
				    public int health;
				}
				"""));
		remold.RemoldAgent.bytecodeCache.put("idl5.Host$1", v1.get("idl5.Host$1"));

		java.util.List<String> warns = new java.util.ArrayList<>();
		remold.RemoldAgent.Logger old = remold.RemoldAgent.logger;
		remold.RemoldAgent.logger = new remold.RemoldAgent.Logger() {
			public void log(String msg) {}
			public void info(String msg) {}
			public void warn(String msg) { warns.add(msg); }
			public void error(String msg) {}
			public void error(String msg, Throwable t) {}
		};
		try {
			InitializerDrift.report(Map.of(
				"idl5.Host$1", v2.get("idl5.Host$1"),
				"idl5.Parent", v2.get("idl5.Parent")));
		} finally {
			remold.RemoldAgent.logger = old;
		}
		assertTrue(warns.stream().anyMatch(w -> w.contains("Parent @RemoldReinit")
			&& w.contains("resets ALL subclass instances")
			&& w.contains("does NOT replay this class's initializer")),
			"expected inherited-reinit warning, actual=" + warns);
	}
}
