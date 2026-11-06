import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import remold.RemoldAgent;
import remold.util.LibTool;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regression probe: {@code injectNewClass}'s LinkageError branch must not report success when the
 * already-loaded class has different bytes.
 *
 * <p><b>Defect</b>: {@code catch (LinkageError) { return true; }} was unconditional. The caller then
 * did {@code bytecodeCache.put(className, bytecode)} and recorded the disk fingerprint, pinning a
 * runtime baseline that never became live. Later InitFix / alignment decisions then work from bytes
 * the JVM does not actually run.</p>
 *
 * <p>The probe covers both the direct branch (inconsistent = false, identical = true) and the real
 * caller contract: a class that is already defined in its loader but absent from the snapshot is fed
 * through {@code processChanges}; the inconsistent bytes must not overwrite the runtime baseline.</p>
 *
 * <p>Run via {@code ./gradlew :tests:injectConflictProbe -Pp05.jbr=<DCEVM/JBR home>}.</p>
 */
public class InjectConflictProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> INFO = new ArrayList<>();
	static final List<String> WARNS = new ArrayList<>();

	static void check(boolean ok, String msg) {
		if (ok) {
			System.out.println("   [PASS] " + msg);
			passed++;
		} else {
			System.err.println("   [FAIL] " + msg);
			failed++;
		}
	}

	static final String CONFLICT_V1 = """
		package inject;
		public class Conflict {
		    public int a = 1;
		    public String hi() { return "v1"; }
		}
		""";

	static final String CONFLICT_V2 = """
		package inject;
		public class Conflict {
		    public int a = 1;
		    public int b = 2;
		    public String hi() { return "v2:" + b; }
		}
		""";

	static final String SIBLING = """
		package inject;
		public class Sibling {
		    public int marker;
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== InjectConflictProbe: LinkageError with inconsistent / identical bytes ===");
		System.out.println("   java.vm.name = " + System.getProperty("java.vm.name"));

		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] Instrumentation unavailable; run with -javaagent:remold-agent.jar");
			System.exit(1);
		}
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.err.println("[WARN] native libtool unavailable: " + t);
		}

		Map<String, byte[]> v1 = LayoutGateAssert.compile(Map.of(
			"inject.Conflict", CONFLICT_V1,
			"inject.Sibling", SIBLING));
		Map<String, byte[]> v2 = LayoutGateAssert.compile(Map.of(
			"inject.Conflict", CONFLICT_V2,
			"inject.Sibling", SIBLING));
		byte[] conflictV1 = v1.get("inject.Conflict");
		byte[] conflictV2 = v2.get("inject.Conflict");

		FixtureLoader loader = new FixtureLoader(v1);
		Class<?> conflict = Class.forName("inject.Conflict", true, loader);
		Object instance = conflict.getConstructor().newInstance();
		Class<?> sibling = Class.forName("inject.Sibling", true, loader);
		check("v1".equals(call(conflict, instance, "hi")), "precondition: Conflict is loaded with v1 bytes");

		Method inject = RemoldAgent.class.getDeclaredMethod(
			"injectNewClass", String.class, Path.class, byte[].class);
		inject.setAccessible(true);

		ConcurrentHashMap<String, Class<?>> previousLoaded = RemoldAgent.loadedClassesMap;
		try {
			RemoldAgent.loadedClassesMap = new ConcurrentHashMap<>(
				Map.of("inject.Conflict", conflict, "inject.Sibling", sibling));
			Object identical = inject.invoke(null, "inject.Conflict", null, conflictV1);
			check(Boolean.TRUE.equals(identical),
				"LinkageError with identical bytes is still a success (actual=" + identical + ")");
			Object inconsistent = inject.invoke(null, "inject.Conflict", null, conflictV2);
			check(Boolean.FALSE.equals(inconsistent),
				"LinkageError with different bytes returns false (actual=" + inconsistent + ")");
		} finally {
			RemoldAgent.loadedClassesMap = previousLoaded;
		}

		// Caller contract: only a true result may update bytecodeCache. Feed the conflicting bytes
		// through the real processChanges path, with Conflict missing from the loaded snapshot.
		Path root = Files.createTempDirectory("inject-conflict-probe");
		Path classFile = root.resolve("inject/Conflict.class");
		Files.createDirectories(classFile.getParent());
		Files.write(classFile, conflictV2);
		RemoldAgent.bytecodeCache.put("inject.Conflict", conflictV1);
		RemoldAgent.bytecodeCache.put("inject.Sibling", v1.get("inject.Sibling"));

		RemoldAgent.Logger oldLogger = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { }
			public void info(String msg) { INFO.add(msg); }
			public void warn(String msg) { WARNS.add(msg); }
			public void error(String msg) { }
			public void error(String msg, Throwable t) { }
		};
		try {
			Method process = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
			process.setAccessible(true);
			process.invoke(null, Set.of(classFile), new Class<?>[]{sibling});
		} finally {
			RemoldAgent.logger = oldLogger;
		}

		byte[] baseline = RemoldAgent.bytecodeCache.get("inject.Conflict");
		check(Arrays.equals(conflictV1, baseline),
			"caller must not overwrite the runtime baseline with bytes that never became live"
			+ " (baselineEqual=" + Arrays.equals(conflictV1, baseline) + ")");
		check(INFO.stream().noneMatch(s -> s.contains("[INJECTED]") && s.contains("inject.Conflict")),
			"a conflicting class must not be reported as [INJECTED] (info=" + INFO + ")");
		check("v1".equals(call(conflict, instance, "hi")),
			"the already-loaded class still runs its original v1 bytes");

		// Already-loaded class was modified by a transformer: live bytes differ from the
		// bytes we are asked to inject. This must be a conflict, not success.
		Instrumentation inst = RemoldAgent.getInstrumentation();
		ClassFileTransformer marker = new ClassFileTransformer() {
			@Override public byte[] transform(ClassLoader l, String name, Class<?> cbr,
				ProtectionDomain pd, byte[] buf) {
				return "inject/Conflict".equals(name) ? addMarker(buf) : null;
			}
		};
		inst.addTransformer(marker, true);
		List<String> transformerWarns = new ArrayList<>();
		RemoldAgent.Logger oldLogger2 = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			public void log(String msg) { }
			public void info(String msg) { }
			public void warn(String msg) { transformerWarns.add(msg); }
			public void error(String msg) { }
			public void error(String msg, Throwable t) { }
		};
		try {
			Object transformed = inject.invoke(null, "inject.Conflict", null, conflictV1);
			check(Boolean.FALSE.equals(transformed),
				"transformer-modified already-loaded bytes must return false (actual=" + transformed + ")");
			check(transformerWarns.stream().anyMatch(w -> w.contains("[INJECT-CONFLICT]")),
				"transformer-modified bytes emit [INJECT-CONFLICT] (warns=" + transformerWarns + ")");
		} finally {
			RemoldAgent.logger = oldLogger2;
			inst.removeTransformer(marker);
		}

		System.out.println("\n=== INJECT CONFLICT PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	static byte[] addMarker(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(0);
		cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
			@Override public void visitEnd() {
				FieldVisitor fv = cv.visitField(Opcodes.ACC_PUBLIC, "transformedMarker", "I", null, null);
				fv.visitEnd();
				super.visitEnd();
			}
		}, 0);
		return cw.toByteArray();
	}

	static Object call(Class<?> type, Object instance, String method) throws Exception {
		return type.getMethod(method).invoke(instance);
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes = new LinkedHashMap<>();

		FixtureLoader(Map<String, byte[]> classes) {
			super(InjectConflictProbe.class.getClassLoader());
			this.classes.putAll(classes);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
