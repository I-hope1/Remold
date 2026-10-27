import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import remold.ref.ConstructorTailInjector;
import remold.ref.ConstructorTailInjector.FieldInit;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression suite for constructor tail injection ({@code docs/initfix/04-target-design.md} §4).
 *
 * <p>Fixtures model the stage-A skeleton shape: the new field is <em>declared but not assigned</em>
 * by the old constructor body, and the injector is what gives freshly created instances their real
 * value. Every case asserts behaviour at runtime (the transformed class is actually loaded and
 * instantiated), so a broken stack depth or an invalidated stack map frame surfaces as a
 * {@code VerifyError} instead of a silently wrong value.</p>
 */
class ConstructorTailInjectorTest {

	private static final String INT_DESC = "I";

	// ------------------------------------------------------------------------------------------------
	// cases
	// ------------------------------------------------------------------------------------------------

	@Test
	void injectsIntoDefaultConstructor() throws Exception {
		byte[] original = compile("cti.A", """
			package cti;
			public class A {
			    public int a = 1;
			    public int b;
			}
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(intField("b", 42)));

		assertTrue(result.changed(), "a root constructor without the field assignment must be injected");
		assertEquals(1, result.rootConstructors(), "one implicit constructor is expected");
		assertEquals(1, result.injectionSites(), "one RETURN means one injection site");
		assertEquals(42, readInt(result.bytes(), "cti.A", "b"),
			"an instance created after injection must carry the injected value");
	}

	@Test
	void injectsEveryRootConstructor() throws Exception {
		byte[] original = compile("cti.B", """
			package cti;
			public class B {
			    public int a;
			    public int b;
			    public B() { this.a = 1; }
			    public B(int a) { this.a = a; }
			}
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(intField("b", 42)));

		assertEquals(2, result.rootConstructors(), "both constructors call super directly");
		assertEquals(2, result.injectionSites(), "each root constructor needs its own injection");
		assertEquals(42, readInt(result.bytes(), "cti.B", "b", new Class<?>[0], new Object[0]),
			"the no-arg constructor must initialize the field");
		assertEquals(42, readInt(result.bytes(), "cti.B", "b", new Class<?>[]{int.class}, new Object[]{7}),
			"the parameterized constructor must initialize the field too");
	}

	@Test
	void skipsDelegatingConstructorToAvoidDoubleInitialization() throws Exception {
		byte[] original = compile("cti.C", """
			package cti;
			public class C {
			    public int a;
			    public int b;
			    public C() { this(1); }
			    public C(int a) { this.a = a; }
			}
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(intField("b", 42)));

		assertEquals(1, result.rootConstructors(), "the this(...) constructor is not a root constructor");
		assertEquals(1, result.injectionSites(), "only the root constructor is injected");
		assertEquals(0, countPuts(result.bytes(), "()V", "b"),
			"the delegating constructor must stay untouched, otherwise the slice runs twice");
		assertEquals(42, readInt(result.bytes(), "cti.C", "b"),
			"objects built through the delegating constructor are still initialized by the root one");
	}

	@Test
	void injectsBeforeEveryReturnOfBranchingConstructor() throws Exception {
		byte[] original = compile("cti.D", """
			package cti;
			public class D {
			    public int a;
			    public int b;
			    public D(boolean early) {
			        this.a = 1;
			        if (early) { return; }
			        this.a = 2;
			    }
			}
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(intField("b", 42)));

		assertEquals(2, result.injectionSites(), "both the early return and the fall-through return need injection");
		assertEquals(42, readInt(result.bytes(), "cti.D", "b", new Class<?>[]{boolean.class}, new Object[]{true}),
			"the early-return path must initialize the field (a stale stack map frame would fail verification here)");
		assertEquals(42, readInt(result.bytes(), "cti.D", "b", new Class<?>[]{boolean.class}, new Object[]{false}),
			"the normal path must initialize the field");
	}

	@Test
	void sliceMayReadFieldsOfThis() throws Exception {
		byte[] original = compile("cti.E", """
			package cti;
			public class E {
			    public int a = 5;
			    public int b;
			}
			""");

		// b = a + 1, the shape produced by parameter backtracking (ALOAD 0; GETFIELD a)
		InsnList value = new InsnList();
		value.add(new VarInsnNode(Opcodes.ALOAD, 0));
		value.add(new FieldInsnNode(Opcodes.GETFIELD, "cti/E", "a", INT_DESC));
		value.add(new LdcInsnNode(1));
		value.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IADD));

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(new FieldInit("b", INT_DESC, value)));

		assertTrue(result.changed(), "the tail must accept slices that read this fields");
		assertEquals(6, readInt(result.bytes(), "cti.E", "b"),
			"a this-reading slice must see the already initialized field");
	}

	@Test
	void refusesToOverwriteConstructorAssignedField() throws Exception {
		byte[] original = compile("cti.F", """
			package cti;
			public class F {
			    public int a;
			    public int b;
			    public F() { this.a = 1; this.b = 7; }
			}
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(intField("b", 42)));

		assertFalse(result.changed(), "a constructor that already assigns the field must not be touched");
		assertEquals(1, result.skipped().size(), "the skip must be reported so the caller can decide");
		assertTrue(result.skipped().get(0).contains("'b'"),
			"the skip reason must name the field, got: " + result.skipped());
		assertEquals(7, readInt(result.bytes(), "cti.F", "b"),
			"constructor logic wins; the injected value must not override it");
	}

	@Test
	void emptyFieldListReturnsInputUntouched() throws Exception {
		byte[] original = compile("cti.G", """
			package cti;
			public class G { public int a = 1; }
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(original, List.of());

		assertFalse(result.changed(), "nothing to inject means nothing to rewrite");
		assertArrayEquals(original, result.bytes(), "the input bytes must be returned as-is");
	}

	@Test
	void injectsMultipleFieldsInDeclarationOrder() throws Exception {
		byte[] original = compile("cti.H", """
			package cti;
			public class H {
			    public int a;
			    public int b;
			    public int c;
			}
			""");

		ConstructorTailInjector.Result result = ConstructorTailInjector.inject(
			original, List.of(intField("b", 42), intField("c", 99)));

		assertEquals(1, result.injectionSites(), "one constructor, one site, both fields inside it");
		assertEquals(42, readInt(result.bytes(), "cti.H", "b"), "first field must be initialized");
		assertEquals(99, readInt(result.bytes(), "cti.H", "c"), "second field must be initialized");
	}

	// ------------------------------------------------------------------------------------------------
	// fixtures
	// ------------------------------------------------------------------------------------------------

	private static FieldInit intField(String name, int value) {
		InsnList list = new InsnList();
		list.add(new LdcInsnNode(value));
		return new FieldInit(name, INT_DESC, list);
	}

	private static byte[] compile(String className, String source) throws Exception {
		File dir = Files.createTempDirectory("cti_fixture").toFile();
		File src = new File(dir, className.replace('.', '/') + ".java");
		assertTrue(src.getParentFile().mkdirs() || src.getParentFile().isDirectory(), "cannot create fixture dir");
		Files.writeString(src.toPath(), source, StandardCharsets.UTF_8);

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "a JDK (not a JRE) is required to compile fixtures");
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			Boolean ok = javac.getTask(null, fm, null,
				List.of("-d", dir.getAbsolutePath(), "-g", "-nowarn"),
				null, fm.getJavaFileObjects(src)).call();
			assertTrue(ok, "fixture compilation failed: " + className);
		}
		return Files.readAllBytes(new File(dir, className.replace('.', '/') + ".class").toPath());
	}

	private static Class<?> load(byte[] bytes, String className) {
		ClassLoader loader = new ClassLoader(null) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!name.equals(className)) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		try {
			return Class.forName(className, true, loader);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("transformed class failed to load (verification error?): " + e, e);
		}
	}

	private static int readInt(byte[] bytes, String className, String field, Class<?>[] argTypes, Object[] args)
		throws Exception {
		Class<?> type = load(bytes, className);
		Object instance = type.getConstructor(argTypes).newInstance(args);
		return type.getField(field).getInt(instance);
	}

	private static int readInt(byte[] bytes, String className, String field) throws Exception {
		return readInt(bytes, className, field, new Class<?>[0], new Object[0]);
	}

	/** Counts {@code PUTFIELD <field>} inside the constructor with the given descriptor. */
	private static int countPuts(byte[] bytes, String ctorDesc, String field) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (MethodNode mn : cn.methods) {
			if (!"<init>".equals(mn.name) || !ctorDesc.equals(mn.desc)) continue;
			int count = 0;
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && field.equals(f.name)) count++;
			}
			return count;
		}
		return -1;
	}
}
