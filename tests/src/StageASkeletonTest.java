import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import remold.ref.ConstructorTailInjector;
import remold.ref.ConstructorTailInjector.FieldInit;
import remold.ref.StageASkeleton;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage A skeleton contract ({@code docs/initfix/04-target-design.md} §1).
 *
 * <p>Stage A must change the <em>shape</em> of a class without changing its <em>behaviour</em>:
 * new fields and new members exist, but every method that already existed keeps running its old
 * bytecode, so nothing in stage A can read a not-yet-patched field. Everything is asserted by
 * loading the synthesised class and calling it — a broken body transplant would surface as a
 * {@code VerifyError} or a wrong return value, not as a silent pass.</p>
 */
class StageASkeletonTest {

	private static final String INT_DESC = "I";

	// ------------------------------------------------------------------------------------------------
	// cases
	// ------------------------------------------------------------------------------------------------

	@Test
	void keepsOldMethodBodyWhileDeclaringNewField() throws Exception {
		byte[] v1 = compile("stage.A", """
			package stage;
			public class A {
			    public int a = 1;
			    public String hi() { return "v1:" + a; }
			}
			""");
		byte[] v2 = compile("stage.A", """
			package stage;
			public class A {
			    public int a = 1;
			    public int b = 42;
			    public String hi() { return "v2:" + a + "," + b; }
			}
			""");

		byte[] stageA = StageASkeleton.build(v1, v2);
		Class<?> type = load(stageA, "stage.A");

		assertNotNull(type.getField("b"), "the new field must already be declared in stage A");
		assertEquals("v1:1", call(type, "hi"),
			"stage A must keep running the old body: the new body reads the new field, which is"
			+ " still at its default for every existing instance");
	}

	@Test
	void preInjectsNewMethodsDormant() throws Exception {
		byte[] v1 = compile("stage.B", """
			package stage;
			public class B {
			    public int a = 1;
			}
			""");
		byte[] v2 = compile("stage.B", """
			package stage;
			public class B {
			    public int a = 1;
			    public String nf() { return "nf"; }
			}
			""");

		Class<?> type = load(StageASkeleton.build(v1, v2), "stage.B");

		assertEquals("nf", call(type, "nf"),
			"newly added methods must be pre-injected so the stage-B companion can link them");
	}

	@Test
	void keepsMethodsRemovedByNewVersion() throws Exception {
		byte[] v1 = compile("stage.C", """
			package stage;
			public class C {
			    public int a = 1;
			    public String hi() { return helper() + ":v1"; }
			    private String helper() { return "H"; }
			}
			""");
		byte[] v2 = compile("stage.C", """
			package stage;
			public class C {
			    public int a = 1;
			    public String hi() { return "H:v2"; }
			}
			""");

		Class<?> type = load(StageASkeleton.build(v1, v2), "stage.C");

		// The old body still calls helper(). Dropping it in stage A would be a NoSuchMethodError.
		assertEquals("H:v1", call(type, "hi"),
			"a method removed by the new version must survive stage A because the old body still calls it");
	}

	@Test
	void keepsOldStaticInitializer() throws Exception {
		byte[] v1 = compile("stage.D", """
			package stage;
			public class D {
			    public static String S;
			    static { S = "old"; }
			}
			""");
		byte[] v2 = compile("stage.D", """
			package stage;
			public class D {
			    public static String S;
			    static { S = "new"; }
			}
			""");

		Class<?> type = load(StageASkeleton.build(v1, v2), "stage.D");

		assertEquals("old", type.getField("S").get(null),
			"<clinit> is an existing method: stage A must not run the new static initializer");
	}

	@Test
	void newInstancesStayAtDefaultUntilInjectionRuns() throws Exception {
		byte[] v1 = compile("stage.E", """
			package stage;
			public class E {
			    public int a = 1;
			}
			""");
		byte[] v2 = compile("stage.E", """
			package stage;
			public class E {
			    public int a = 1;
			    public int b = 42;
			}
			""");

		Class<?> type = load(StageASkeleton.build(v1, v2), "stage.E");

		assertEquals(0, readInt(type, "b"),
			"stage A restores the old constructor, so new instances start at the type default —"
			+ " this is exactly the gap constructor tail injection has to close");
	}

	@Test
	void composesWithConstructorTailInjection() throws Exception {
		byte[] v1 = compile("stage.F", """
			package stage;
			public class F {
			    public int a = 1;
			}
			""");
		byte[] v2 = compile("stage.F", """
			package stage;
			public class F {
			    public int a = 1;
			    public int b = 42;
			}
			""");

		InsnList value = new InsnList();
		value.add(new LdcInsnNode(42));
		byte[] stageA = ConstructorTailInjector
			.inject(StageASkeleton.build(v1, v2), List.of(new FieldInit("b", INT_DESC, value)))
			.bytes();

		Class<?> type = load(stageA, "stage.F");

		assertEquals(42, readInt(type, "b"),
			"stage A + tail injection must give freshly constructed instances their real value");
		assertEquals(1, readInt(type, "a"), "the pre-existing field must keep its old initializer");
	}

	@Test
	void unchangedClassStaysUnchanged() throws Exception {
		byte[] v1 = compile("stage.G", """
			package stage;
			public class G {
			    public int a = 1;
			    public String hi() { return "v1:" + a; }
			}
			""");
		byte[] v2 = compile("stage.G", """
			package stage;
			public class G {
			    public int a = 1;
			    public String hi() { return "v1:" + a; }
			}
			""");

		Class<?> type = load(StageASkeleton.build(v1, v2), "stage.G");

		assertEquals("v1:1", call(type, "hi"), "an unchanged class must behave identically");
		assertTrue(type.getDeclaredMethods().length >= 1, "the class must still be loadable and usable");
	}

	// ------------------------------------------------------------------------------------------------
	// fixtures
	// ------------------------------------------------------------------------------------------------

	private static String call(Class<?> type, String method) throws Exception {
		Method m = type.getMethod(method);
		return (String) m.invoke(type.getConstructor().newInstance());
	}

	private static int readInt(Class<?> type, String field) throws Exception {
		Field f = type.getField(field);
		return f.getInt(type.getConstructor().newInstance());
	}

	private static byte[] compile(String className, String source) throws Exception {
		File dir = Files.createTempDirectory("stagea_fixture").toFile();
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

	private static Class<?> load(byte[] bytes, String className) {
		ClassLoader loader = new ClassLoader(StageASkeletonTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!name.equals(className)) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		try {
			return Class.forName(className, true, loader);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("stage A class failed to load (verification error?): " + e, e);
		}
	}
}
