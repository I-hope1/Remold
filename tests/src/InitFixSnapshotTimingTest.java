import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import remold.ClassDiffUtil;
import remold.Reflect;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.util.LibTool;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Snapshot timing regression: an instance that appears <em>after</em> the instance snapshot was taken
 * but <em>before</em> the patch is applied must still be patched.
 *
 * <p>The defect: {@code InitFix.transform} (and later {@code beforeRedefine}) capture a weak-reference
 * snapshot before {@code redefineClasses} lands, and {@code afterRedefine} patches only that snapshot.
 * Anything constructed in between keeps the type default forever — silently, with no error and no
 * warning ({@code docs/status.md} risk 6).</p>
 *
 * <p>DCEVM is not required here: the fixture class is loaded straight from the v2 bytes, so the new
 * field already exists and no schema change has to be redefined. Instances are created with
 * {@code Unsafe.allocateInstance} so that their new field stays at the type default, which is exactly
 * the observable state of an instance that was constructed before the redefinition took effect.</p>
 */
class InitFixSnapshotTimingTest {

	private static final String V1 = """
		package snap;
		public class S {
		    public int a = 1;
			public String hi() { return "v1:" + a; }
		}
		""";

	private static final String V2 = """
		package snap;
		public class S {
		    public int a = 1;
			public int b = 42;
			public String hi() { return "v1:" + a; }
		}
		""";

	private boolean originalInitFix;

	@BeforeEach
	void requireNativeHeapWalk() {
		originalInitFix = RemoldAgent.INIT_FIX;
		RemoldAgent.INIT_FIX = true;
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			fail("this regression needs the native heap walk to see Unsafe-allocated instances: " + t);
		}
	}

	@AfterEach
	void restore() {
		RemoldAgent.INIT_FIX = originalInitFix;
	}

	@Test
	void instanceCreatedAfterSnapshotIsStillPatched() throws Exception {
		byte[] v1 = compile("snap.S", V1);
		byte[] v2 = compile("snap.S", V2);

		Class<?> host = load(v2, "snap.S");

		// Control: an instance that exists before the snapshot is the case the current code handles.
		Object preexisting = Reflect.UNSAFE.allocateInstance(host);
		assertEquals(0, readInt(host, preexisting, "b"),
			"precondition: an Unsafe-allocated instance starts at the type default");

		InitFix.transform(host, v2, ClassDiffUtil.diff(v1, v2));

		// This instance is created after the patch plan was assembled but before the patch runs. In
		// production that is a concurrent `new S()` on another thread while redefineClasses lands;
		// its new field is still the type default.
		Object inWindow = Reflect.UNSAFE.allocateInstance(host);

		// redefineClasses already landed at this point, so the driver runs against the live schema.
		InitFix.afterRedefine(host);

		assertEquals(42, readInt(host, preexisting, "b"),
			"an instance captured by the snapshot must be patched");
		assertEquals(42, readInt(host, inWindow, "b"),
			"an instance created after the snapshot must be patched too, otherwise its new field"
			+ " stays at the type default forever (status.md risk 6)");
	}

	// ------------------------------------------------------------------------------------------------
	// fixtures
	// ------------------------------------------------------------------------------------------------

	private static int readInt(Class<?> host, Object target, String field) throws Exception {
		Field f = host.getField(field);
		return f.getInt(target);
	}

	private static byte[] compile(String className, String source) throws Exception {
		File dir = Files.createTempDirectory("snap_fixture").toFile();
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
		String resource = className.replace('.', '/') + ".class";
		// Parent must be the application loader: the generated patch class is a nestmate of the
		// fixture and links against remold.ref.HotswapBridge through its defining loader.
		ClassLoader loader = new ClassLoader(InitFixSnapshotTimingTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!name.equals(className)) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}

			// The hierarchy oracle reads the host bytes through the loader to decide whether a field
			// access crosses a protected boundary; without this the safety gate refuses every field.
			@Override
			public java.io.InputStream getResourceAsStream(String name) {
				if (resource.equals(name)) return new java.io.ByteArrayInputStream(bytes);
				return super.getResourceAsStream(name);
			}
		};
		try {
			return Class.forName(className, true, loader);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("fixture failed to load: " + e, e);
		}
	}
}
