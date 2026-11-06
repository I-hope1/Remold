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
}
