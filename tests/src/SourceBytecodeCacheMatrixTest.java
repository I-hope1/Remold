import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import remold.AnnotationTransformer;
import remold.RemoldAgent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast JUnit half of the {@code sourceBytecodeCache} matrix (item 5A).
 *
 * <p>Runs under {@code check} without a JBR: it drives the real
 * {@link AnnotationTransformer#transform} over in-memory fixture classes but stops short of a
 * JVM {@code redefineClasses}. The real-redefine half (including the live-instance business
 * end) lives in {@code CacheSourceMatrixProbe}, which needs a DCEVM-capable JBR.</p>
 *
 * <p>Contract covered:</p>
 * <ol>
 *   <li>first load writes the source bytes;</li>
 *   <li>an external redefine updates the source bytes and the runtime baseline;</li>
 *   <li>weaving splits them: the source baseline keeps the pre-weave bytes, the runtime
 *       baseline gets the woven bytes;</li>
 *   <li>two ClassLoaders loading the same binary name do not overwrite each other's source
 *       bytes (first writer wins — the cache key is the dot class name, hence name-keyed reads).</li>
 * </ol>
 */
public class SourceBytecodeCacheMatrixTest {

	static final String SRC_V1 = """
		package csmj;
		public class Host {
		    public String hi() { return "v1"; }
		}
		""";

	static final String SRC_V2 = """
		package csmj;
		public class Host {
		    public String hi() { return "v2"; }
		}
		""";

	static final String SRC_V3 = """
		package csmj;
		public class Host {
		    public String hi() { return "v3"; }
		}
		""";

	static final String SRC_MULTI_A = """
		package csmj;
		public class Multi {
		    public String hi() { return "A"; }
		}
		""";

	static final String SRC_MULTI_B = """
		package csmj;
		public class Multi {
		    public String hi() { return "B"; }
		}
		""";

	@Test
	void sourceCacheMatrix() throws Exception {
		String hostName = "csmj.Host";
		String hostInternal = "csmj/Host";
		String multiName = "csmj.Multi";
		String multiInternal = "csmj/Multi";

		byte[] v1 = compileOne(hostName, SRC_V1);
		byte[] v2 = compileOne(hostName, SRC_V2);
		byte[] v3 = compileOne(hostName, SRC_V3);
		byte[] multiA = compileOne(multiName, SRC_MULTI_A);
		byte[] multiB = compileOne(multiName, SRC_MULTI_B);

		FixtureLoader loader = new FixtureLoader(Map.of(hostName, v1, multiName, multiA));
		Class<?> host = Class.forName(hostName, true, loader);

		AnnotationTransformer transformer = new AnnotationTransformer();
		AnnotationTransformer.pendingAlignedClasses.remove(hostInternal);
		AnnotationTransformer.pendingAlignedClasses.remove(hostName);
		try {
			// ---------- 1) first load writes the source bytes ----------
			RemoldAgent.sourceBytecodeCache.remove(hostName);
			RemoldAgent.bytecodeCache.remove(hostName);
			transformer.transform(loader, hostInternal, null, null, v1);
			assertArrayEquals(v1, RemoldAgent.sourceBytecodeCache.get(hostName),
				"first load must write the source bytes into sourceBytecodeCache");
			assertArrayEquals(v1, RemoldAgent.getSourceBytecode(hostName),
				"getSourceBytecode must read the recorded source bytes");
			assertArrayEquals(v1, RemoldAgent.bytecodeCache.get(hostName),
				"first load must also seed the runtime baseline with the same bytes");

			// ---------- 2) external redefine updates the source bytes ----------
			assertFalse(Arrays.equals(v1, v2), "precondition: v2 differs from v1");
			transformer.transform(loader, hostInternal, host, null, v2);
			assertArrayEquals(v2, RemoldAgent.sourceBytecodeCache.get(hostName),
				"an external redefine must update sourceBytecodeCache to the new bytes");
			assertArrayEquals(v2, RemoldAgent.getSourceBytecode(hostName),
				"getSourceBytecode must return the new source bytes after the redefine");
			assertArrayEquals(v2, RemoldAgent.bytecodeCache.get(hostName),
				"the runtime baseline must equal the new bytes while no weaver is registered");

			// ---------- 3) weaving keeps the pre-weave source bytes ----------
			FieldWeaver weaver = new FieldWeaver();
			RemoldAgent.addTransformerBridge(weaver);
			try {
				transformer.transform(loader, hostInternal, host, null, v3);
				byte[] source = RemoldAgent.sourceBytecodeCache.get(hostName);
				byte[] runtime = RemoldAgent.bytecodeCache.get(hostName);
				assertArrayEquals(v3, source,
					"weaving must keep the ORIGINAL (pre-weave) source bytes");
				assertFalse(Arrays.equals(runtime, v3),
					"the runtime baseline must differ from the pre-weave source bytes");
				assertTrue(fields(runtime).contains("woven$marker"),
					"the woven field must exist in the runtime baseline");
				assertFalse(fields(source).contains("woven$marker"),
					"the woven field must NOT leak into the source baseline");
				assertArrayEquals(runtime, RemoldAgent.getRuntimeBytecode(hostName),
					"getRuntimeBytecode must read the woven bytes");
				assertArrayEquals(v3, RemoldAgent.getSourceBytecode(hostName),
					"getSourceBytecode must keep reading the pre-weave bytes");
			} finally {
				RemoldAgent.removeTransformerBridge(weaver);
			}

			// ---------- 4) multi-ClassLoader same-name is not overwritten ----------
			assertFalse(Arrays.equals(multiA, multiB),
				"precondition: the two loaders carry different bytes for the same binary name");
			FixtureLoader loaderA = new FixtureLoader(Map.of(multiName, multiA));
			FixtureLoader loaderB = new FixtureLoader(Map.of(multiName, multiB));
			RemoldAgent.sourceBytecodeCache.remove(multiName);
			RemoldAgent.bytecodeCache.remove(multiName);
			transformer.transform(loaderA, multiInternal, null, null, multiA);
			assertArrayEquals(multiA, RemoldAgent.sourceBytecodeCache.get(multiName),
				"precondition: the first loader recorded its source bytes");
			transformer.transform(loaderB, multiInternal, null, null, multiB);
			assertArrayEquals(multiA, RemoldAgent.sourceBytecodeCache.get(multiName),
				"the second loader's first load must NOT overwrite the first loader's source bytes");
			assertArrayEquals(multiA, RemoldAgent.bytecodeCache.get(multiName),
				"the runtime baseline must also keep the first loader's bytes (putIfAbsent)");
			assertEquals("A", invoke(loaderA.loadClass(multiName).getConstructor().newInstance(), "hi"),
				"precondition: loader A really runs version A");
			assertEquals("B", invoke(loaderB.loadClass(multiName).getConstructor().newInstance(), "hi"),
				"precondition: loader B really runs version B");
			assertArrayEquals(multiA, RemoldAgent.getSourceBytecode(multiName),
				"the read API stays on the first recorded bytes (name-keyed contract, no overwrite)");
		} finally {
			RemoldAgent.sourceBytecodeCache.remove(hostName);
			RemoldAgent.bytecodeCache.remove(hostName);
			RemoldAgent.sourceBytecodeCache.remove(multiName);
			RemoldAgent.bytecodeCache.remove(multiName);
		}
	}

	// ------------------------------------------------------------------ helpers

	static byte[] compileOne(String dotName, String source) {
		byte[] bytes = LayoutGateAssert.compile(Map.of(dotName, source)).get(dotName);
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

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(SourceBytecodeCacheMatrixTest.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	/** Mirror of the probe weaver: a synthetic field makes runtime bytes differ from source bytes. */
	static final class FieldWeaver implements remold.BytecodeTransformerBridge {
		@Override
		public byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode) {
			if (!"csmj.Host".equals(dotClassName)) return bytecode;
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
