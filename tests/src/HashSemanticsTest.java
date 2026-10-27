import org.junit.jupiter.api.Test;
import remold.ref.HashSemantics;
import remold.ref.HashSemantics.Finding;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hash-semantics gate ({@code docs/status.md} risk 10).
 *
 * <p>Adding a field that {@code hashCode()} / {@code equals()} / {@code compareTo()} reads invalidates
 * every existing hash container, and patching the field does not help: the damage comes from the
 * formula changing, not from the value written. So the check lives at the gate and only has to answer
 * one question: does one of those methods read a field that is being added?</p>
 */
class HashSemanticsTest {

	@Test
	void detectsHashCodeReadingAddedField() throws Exception {
		byte[] bytes = compile("hash.A", """
			package hash;
			public class A {
			    public int id;
			    public int other;
			    @Override public int hashCode() { return id * 31; }
			}
			""");

		List<Finding> findings = HashSemantics.findDependencies(bytes, Set.of("id"));

		assertEquals(1, findings.size(), "hashCode() reading the added field must be reported, got " + findings);
		assertEquals("hashCode", findings.get(0).method());
		assertEquals("id", findings.get(0).field());
	}

	@Test
	void detectsEqualsReadingAddedField() throws Exception {
		byte[] bytes = compile("hash.B", """
			package hash;
			public class B {
			    public int id;
			    @Override public boolean equals(Object o) {
			        return o instanceof B && ((B) o).id == this.id;
			    }
			    @Override public int hashCode() { return 1; }
			}
			""");

		List<Finding> findings = HashSemantics.findDependencies(bytes, Set.of("id"));

		assertTrue(findings.stream().anyMatch(f -> f.method().equals("equals") && f.field().equals("id")),
			"equals() reading the added field must be reported, got " + findings);
	}

	@Test
	void detectsCompareToReadingAddedField() throws Exception {
		byte[] bytes = compile("hash.C", """
			package hash;
			public class C implements Comparable<C> {
			    public int id;
			    @Override public int compareTo(C o) { return Integer.compare(id, o.id); }
			}
			""");

		List<Finding> findings = HashSemantics.findDependencies(bytes, Set.of("id"));

		assertTrue(findings.stream().anyMatch(f -> f.method().equals("compareTo") && f.field().equals("id")),
			"compareTo() reading the added field must be reported (TreeMap keys break the same way), got " + findings);
	}

	@Test
	void ignoresExistingFields() throws Exception {
		byte[] bytes = compile("hash.D", """
			package hash;
			public class D {
			    public int id;
			    public int other;
			    @Override public int hashCode() { return other * 31; }
			}
			""");

		List<Finding> findings = HashSemantics.findDependencies(bytes, Set.of("id"));

		assertTrue(findings.isEmpty(),
			"only added fields matter; reading an existing field is out of this gate's scope, got " + findings);
	}

	@Test
	void ignoresClassWithoutHashSemanticOverride() throws Exception {
		byte[] bytes = compile("hash.E", """
			package hash;
			public class E {
			    public int id;
			}
			""");

		assertTrue(HashSemantics.findDependencies(bytes, Set.of("id")).isEmpty(),
			"a class that does not override hashCode/equals/compareTo cannot break containers");
	}

	@Test
	void ignoresStaticFieldRead() throws Exception {
		byte[] bytes = compile("hash.F", """
			package hash;
			public class F {
			    public static int id;
			    @Override public int hashCode() { return id * 31; }
			}
			""");

		assertTrue(HashSemantics.findDependencies(bytes, Set.of("id")).isEmpty(),
			"a static field read is GETSTATIC: static state does not participate in per-object hashing");
	}

	@Test
	void emptyAddedSetShortCircuits() throws Exception {
		byte[] bytes = compile("hash.G", """
			package hash;
			public class G {
			    public int id;
			    @Override public int hashCode() { return id; }
			}
			""");

		assertFalse(HashSemantics.findDependencies(bytes, Set.of()).iterator().hasNext(),
			"nothing added means nothing to warn about");
	}

	// ------------------------------------------------------------------------------------------------

	private static byte[] compile(String className, String source) throws Exception {
		File dir = Files.createTempDirectory("hash_fixture").toFile();
		File src = new File(dir, className.replace('.', '/') + ".java");
		if (!src.getParentFile().mkdirs() && !src.getParentFile().isDirectory()) {
			throw new IllegalStateException("cannot create fixture dir");
		}
		Files.writeString(src.toPath(), source, StandardCharsets.UTF_8);

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("a JDK (not a JRE) is required to compile fixtures");
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			Boolean ok = javac.getTask(null, fm, null,
				List.of("-d", dir.getAbsolutePath(), "-g", "-nowarn"),
				null, fm.getJavaFileObjects(src)).call();
			if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("fixture compilation failed: " + className);
		}
		return Files.readAllBytes(new File(dir, className.replace('.', '/') + ".class").toPath());
	}
}
