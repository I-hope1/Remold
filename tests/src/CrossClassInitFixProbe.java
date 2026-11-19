import remold.LayoutGate;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.util.LibTool;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CrossClassInitFixProbe {
	private static int passed;
	private static int failed;
	private static Instrumentation instrumentation;

	private static void check(boolean condition, String message) {
		System.out.println((condition ? "[PASS] " : "[FAIL] ") + message);
		if (condition) passed++; else failed++;
	}

	public static void main(String[] args) throws Throwable {
		instrumentation = P05Agent.instrumentation();
		if (instrumentation == null) throw new IllegalStateException("instrumentation unavailable");
		Field instField = RemoldAgent.class.getDeclaredField("inst");
		instField.setAccessible(true);
		instField.set(null, instrumentation);
		try { if (!LibTool.initialized()) LibTool.init(); }
		catch (Throwable failure) { throw new IllegalStateException("native LibTool unavailable", failure); }
		RemoldAgent.INIT_FIX = true;
		RemoldAgent.ANON_ALIGN = true;
		RemoldAgent.LAYOUT_GATE = LayoutGate.MODE_REJECT;
		RemoldAgent.OFFSET_STRICT = false;
		RemoldAgent.DEBUG = false;
		RemoldAgent.TWO_PHASE = false;
		scenarioSameBatchStaticOrder();
		scenarioRuntimePrerequisiteFailure();
		System.out.println("CROSS-CLASS INITFIX SUMMARY: passed=" + passed + ", failed=" + failed);
		if (failed != 0) System.exit(1);
	}

	private static void scenarioSameBatchStaticOrder() throws Throwable {
		String pkg = "ccprobe.order";
		String y1 = "package " + pkg + "; public class Y { }";
		String x1 = "package " + pkg + "; public class X { }";
		String x2 = "package " + pkg + "; public class X { public static int read = Y.base + 4; }";
		Fixture f = fixture(pkg, Map.of(pkg + ".X", x1, pkg + ".Y", y1), Map.of(pkg + ".X", x2,
		 pkg + ".Y", "package " + pkg + "; public class Y { public static int base = 9; }"));
		Class<?> x = f.load(pkg + ".X"); Class<?> targetY = f.load(pkg + ".Y");
		check(x.getDeclaredFields().length == 0, "precondition: X has no target field before redefine");
		f.hotSwap(pkg + ".X", pkg + ".Y");
		check(targetY.getField("base").getInt(null) == 9, "Y.base receives its new initializer");
		check(x.getField("read").getInt(null) == 13, "X.read observes Y.base after prerequisite host patch");
		check(InitFix.getUnpatchedFields(x).stream().noneMatch(field -> field.fieldName().equals("read")),
		 "successful dependent field is absent from FieldLedger");
		f.close();
	}

	private static void scenarioRuntimePrerequisiteFailure() throws Throwable {
		String pkg = "ccprobe.failure";
		String y1 = "package " + pkg + "; public class Y { public static int compute() { return 1; } }";
		String y2 = "package " + pkg + "; public class Y { public static int base = compute(); public static int compute() { throw new IllegalStateException(\"expected\"); } }";
		String x1 = "package " + pkg + "; public class X { }";
		String x2 = "package " + pkg + "; public class X { public static int blocked = Y.base + 1; public static int independent = 31; }";
		Fixture f = fixture(pkg, Map.of(pkg + ".X", x1, pkg + ".Y", y1), Map.of(pkg + ".X", x2, pkg + ".Y", y2));
		Class<?> x = f.load(pkg + ".X"); Class<?> y = f.load(pkg + ".Y");
		check(x.getDeclaredFields().length == 0 && y.getMethod("compute").invoke(null).equals(1),
		 "precondition: target fields are absent and old helper is active");
		f.hotSwap(pkg + ".X", pkg + ".Y");
		check(x.getField("blocked").getInt(null) == 0, "X.blocked remains at its default after Y.base runtime failure");
		check(x.getField("independent").getInt(null) == 31, "X.independent still executes");
		check(InitFix.getUnpatchedFields(x).stream().anyMatch(field -> field.fieldName().equals("blocked")),
		 "dependent skip is recorded in X FieldLedger");
		f.close();
	}

	private static Fixture fixture(String pkg, Map<String, String> oldSources, Map<String, String> newSources)
	 throws Exception {
		Map<String, byte[]> oldBytes = LayoutGateAssert.compile(oldSources);
		Map<String, byte[]> newBytes = LayoutGateAssert.compile(newSources);
		FixtureLoader loader = new FixtureLoader(oldBytes);
		Path root = Files.createTempDirectory("cross_class_initfix");
		Fixture fixture = new Fixture(loader, root);
		for (Map.Entry<String, byte[]> entry : oldBytes.entrySet()) {
			RemoldAgent.bytecodeCache.put(entry.getKey(), entry.getValue());
			RemoldAgent.sourceBytecodeCache.put(entry.getKey(), entry.getValue());
		}
		for (Map.Entry<String, byte[]> entry : newBytes.entrySet()) {
			Path path = root.resolve(entry.getKey().replace('.', '/') + ".class");
			Files.createDirectories(path.getParent()); Files.write(path, entry.getValue());
			fixture.paths.put(entry.getKey(), path);
		}
		return fixture;
	}

	private static final class Fixture implements AutoCloseable {
		final FixtureLoader loader; final Path root; final java.util.Map<String, Path> paths = new java.util.LinkedHashMap<>();
		Fixture(FixtureLoader loader, Path root) { this.loader = loader; this.root = root; }
		Class<?> load(String name) throws Exception { return Class.forName(name, true, loader); }
		void hotSwap(String... names) throws Exception {
			Set<Path> changed = new LinkedHashSet<>(); for (String name : names) changed.add(paths.get(name));
			Method process = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
			process.setAccessible(true); process.invoke(null, changed, instrumentation.getAllLoadedClasses());
		}
		public void close() throws Exception { try (var walk = Files.walk(root)) { walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} }); } }
	}

	private static final class FixtureLoader extends ClassLoader {
		final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) { super(CrossClassInitFixProbe.class.getClassLoader()); this.classes = classes; }
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name); if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
