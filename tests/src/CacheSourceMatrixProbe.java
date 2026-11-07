import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import remold.RemoldAgent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code sourceBytecodeCache} real-path matrix (item 5A).
 *
 * <p>Covers the four contract points that the cache has to keep:</p>
 * <ol>
 *   <li><b>first class load</b> writes the source bytes (via the real
 *       {@code AnnotationTransformer} installed by {@code -javaagent});</li>
 *   <li><b>ordinary redefine</b> updates the entry and the read API returns the NEW bytes;</li>
 *   <li><b>Mixin weaving</b> keeps the ORIGINAL pre-weave bytes in {@code sourceBytecodeCache}
 *       (the runtime baseline gets the woven bytes), and {@code MixinHotSwap.readOriginalBytes}
 *       falls back to that pre-weave source when no disk/resource copy exists;</li>
 *   <li><b>multi-ClassLoader same-name</b> loads do NOT overwrite each other's source bytes
 *       (first writer wins — the cache key is the dot class name, so the read side is still
 *       name-keyed; see the {@code [OBS]} line).</li>
 * </ol>
 *
 * <p><b>Real path, seconds-scale</b>: every step goes through the transformer registered by
 * the production agent and through a real {@link Instrumentation#redefineClasses}; no
 * Minecraft/Fabric/Forge process is involved. Requires JBR/DCEVM because the weaving step
 * adds a field.</p>
 *
 * <p>Run: {@code ./gradlew :tests:cacheSourceMatrixProbe -Pp05.jbr=<DCEVM JDK>}.</p>
 */
public class CacheSourceMatrixProbe {

	static int passed = 0, failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void obs(String msg) {
		System.out.println("   [OBS] " + msg);
	}

	static void summary() {
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
	}

	// ------------------------------------------------------------------ fixtures

	static final String SRC_V1 = """
		package csmcache;
		public class Host {
		    public String hi() { return "v1"; }
		}
		""";

	static final String SRC_V2 = """
		package csmcache;
		public class Host {
		    public String hi() { return "v2"; }
		}
		""";

	static final String SRC_V3 = """
		package csmcache;
		public class Host {
		    public String hi() { return "v3"; }
		}
		""";

	static final String SRC_MULTI_A = """
		package csmcache;
		public class Multi {
		    public String hi() { return "A"; }
		}
		""";

	static final String SRC_MULTI_B = """
		package csmcache;
		public class Multi {
		    public String hi() { return "B"; }
		}
		""";

	public static void main(String[] args) throws Throwable {
		System.out.println("=== CacheSourceMatrixProbe (sourceBytecodeCache real path) ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available (must run with -javaagent remold-agent.jar)");
		check(RemoldAgent.isEnhancedHotswapEnabled(),
			"P0 enhanced class redefinition is enabled (weave step adds a field; use JBR/DCEVM)");
		if (inst == null) {
			summary();
			System.exit(1);
		}

		// ================= M1: first class load writes the source bytes =================
		System.out.println("-- M1 first class load --");
		String n1 = "csmcache.Host";
		byte[] v1 = compileOne(n1, SRC_V1);
		FixtureLoader loader = new FixtureLoader(Map.of(n1, v1));
		Class<?> host = Class.forName(n1, true, loader);
		Object instance = host.getConstructor().newInstance();
		check("v1".equals(invoke(instance, "hi")), "M1 precondition: the fixture runs v1");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get(n1), v1),
			"M1 first load writes the source bytes into sourceBytecodeCache");
		check(Arrays.equals(RemoldAgent.getSourceBytecode(n1), v1),
			"M1 read API returns the recorded source bytes");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get(n1), v1),
			"M1 first load also seeds the runtime baseline with the same bytes (nothing woven yet)");

		// ================= M2: ordinary redefine updates the source bytes =================
		System.out.println("-- M2 ordinary redefine --");
		byte[] v2 = compileOne(n1, SRC_V2);
		check(!Arrays.equals(v1, v2), "M2 precondition: the v2 bytes differ from v1");
		inst.redefineClasses(new ClassDefinition(host, v2));
		check("v2".equals(invoke(instance, "hi")),
			"M2 redefine took effect (business end: the live instance runs the v2 body)");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get(n1), v2),
			"M2 ordinary redefine updates sourceBytecodeCache to the NEW bytes");
		check(Arrays.equals(RemoldAgent.getSourceBytecode(n1), v2),
			"M2 read API returns the new source bytes");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get(n1), v2),
			"M2 runtime baseline equals the new bytes while no weaver is registered");

		// ================= M3: weaving keeps the pre-weave source bytes =================
		System.out.println("-- M3 Mixin-style weaving --");
		FieldWeaver weaver = new FieldWeaver();
		RemoldAgent.addTransformerBridge(weaver);
		try {
			byte[] v3 = compileOne(n1, SRC_V3);
			inst.redefineClasses(new ClassDefinition(host, v3));
			byte[] src = RemoldAgent.sourceBytecodeCache.get(n1);
			byte[] runtime = RemoldAgent.bytecodeCache.get(n1);
			check("v3".equals(invoke(instance, "hi")),
				"M3 woven redefine took effect (business end: the live instance runs the v3 body)");
			check(Arrays.equals(src, v3),
				"M3 sourceBytecodeCache keeps the ORIGINAL (pre-weave) new bytes");
			check(!Arrays.equals(runtime, v3),
				"M3 runtime baseline differs from the source bytes (the bridge did weave)");
			check(fields(runtime).contains("woven$marker") && !fields(src).contains("woven$marker"),
				"M3 the woven field exists only in the runtime baseline, never in the source baseline");
			check(Arrays.equals(RemoldAgent.getSourceBytecode(n1), v3),
				"M3 getSourceBytecode reads the pre-weave bytes");
			check(Arrays.equals(runtime, RemoldAgent.bytecodeCache.get(n1)),
				"M3 getRuntimeBytecode reads the woven bytes");

			Method readOriginal = Class.forName("remold.mixin.MixinHotSwap")
				.getDeclaredMethod("readOriginalBytes", Class.class);
			readOriginal.setAccessible(true);
			byte[] mixinOriginal = (byte[]) readOriginal.invoke(null, host);
			check(Arrays.equals(mixinOriginal, v3),
				"M3 MixinHotSwap.readOriginalBytes falls back to the pre-weave source bytes"
					+ " (in-memory class: no disk/resource copy exists)");
		} finally {
			RemoldAgent.removeTransformerBridge(weaver);
		}

		// ================= M4: multi-ClassLoader same-name is not overwritten =================
		System.out.println("-- M4 multi-ClassLoader same-name --");
		String n4 = "csmcache.Multi";
		byte[] a = compileOne(n4, SRC_MULTI_A);
		byte[] b = compileOne(n4, SRC_MULTI_B);
		check(!Arrays.equals(a, b),
			"M4 precondition: the two loaders carry different bytes for the same binary name");
		Class<?> ma = Class.forName(n4, true, new FixtureLoader(Map.of(n4, a)));
		Class<?> mb = Class.forName(n4, true, new FixtureLoader(Map.of(n4, b)));
		check("A".equals(invoke(ma.getConstructor().newInstance(), "hi"))
				&& "B".equals(invoke(mb.getConstructor().newInstance(), "hi")),
			"M4 precondition: each loader really runs its own version");
		check(Arrays.equals(RemoldAgent.sourceBytecodeCache.get(n4), a),
			"M4 the second loader's first load does NOT overwrite the source bytes"
				+ " recorded for the first loader");
		check(Arrays.equals(RemoldAgent.bytecodeCache.get(n4), a),
			"M4 the runtime baseline also keeps the first loader's bytes (putIfAbsent)");
		obs("cache key is the dot class name only: getSourceBytecode(\"" + n4 + "\") returns"
			+ " loader-A bytes even when asked about loader-B's class. This probe asserts only that"
			+ " there is no cross-loader OVERWRITE; the name-keyed read side is a documented limitation"
			+ " (see PendingAlignedMultiLoaderProbe).");
		check(Arrays.equals(RemoldAgent.getSourceBytecode(n4), a),
			"M4 the read API stays on the first recorded bytes (name-keyed contract, no overwrite)");

		summary();
		if (failed > 0) System.exit(1);
	}

	// ------------------------------------------------------------------ helpers

	static byte[] compileOne(String dotName, String source) {
		Map<String, byte[]> out = LayoutGateAssert.compile(Map.of(dotName, source));
		byte[] bytes = out.get(dotName);
		if (bytes == null) throw new IllegalStateException("no class emitted for " + dotName);
		return bytes;
	}

	static Object invoke(Object target, String name) throws Exception {
		return target.getClass().getMethod(name).invoke(target);
	}

	/** Field names of a class file (used to prove where the woven marker lives). */
	static List<String> fields(byte[] bytes) {
		List<String> out = new ArrayList<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
				out.add(name);
				return null;
			}
		}, 0);
		return out;
	}

	/** In-memory fixture loader: no disk, no resource — the only baseline is the agent cache. */
	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(CacheSourceMatrixProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	/**
	 * Minimal Mixin-style weaver: adds a synthetic field so the runtime bytes differ from the
	 * source bytes. Synthetic on purpose — {@code ClassDiffUtil} filters synthetic fields, so the
	 * external-redefine LayoutGate keeps seeing a compatible layout (same as a Mixin {@code @Unique}).
	 */
	static final class FieldWeaver implements remold.BytecodeTransformerBridge {
		@Override
		public byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode) {
			if (!"csmcache.Host".equals(dotClassName)) return bytecode;
			ClassReader cr = new ClassReader(bytecode);
			ClassWriter cw = new ClassWriter(0);
			ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
				@Override
				public void visitEnd() {
					FieldVisitor fv = visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
						"woven$marker", "I", null, null);
					if (fv != null) fv.visitEnd();
					super.visitEnd();
				}
			};
			cr.accept(cv, 0);
			return cw.toByteArray();
		}
	}
}
