package remold.ref;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HotswapBridgeWarmupTest {

	static final class DummyHost {
		@SuppressWarnings("unused")
		int intField;
		@SuppressWarnings("unused")
		Object refField;
		@SuppressWarnings("unused")
		int replayIntField = 7;
	}

	@Test
	void warmUpIsIdempotentAndLeavesStatsClean() {
		// Clear any prior test statistics before verifying warmup isolation.
		HotswapBridge.drainConditionalStats();
		HotswapBridge.drainReplayStats();

		HotswapBridge.warmUp();
		HotswapBridge.warmUp();

		assertTrue(HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, int.class));
		assertTrue(HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, Object.class));

		Map<String, long[]> conditional = HotswapBridge.drainConditionalStats();
		Map<String, long[]> replay = HotswapBridge.drainReplayStats();

		assertTrue(conditional.isEmpty(),
			"warmup must not pollute CONDITIONAL_STATS, got: " + conditional.keySet());
		assertTrue(replay.isEmpty(),
			"warmup must not pollute REPLAY_STATS, got: " + replay.keySet());
	}

	@Test
	void unsupportedValueTypeFailsWithoutPoisoningValidSlots() throws Throwable {
		assertThrows(IllegalArgumentException.class, () ->
			HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, void.class));

		BootstrapMethodError expectedRefErr = assertThrows(BootstrapMethodError.class, () ->
			HotswapBridge.bootstrapExpected(
				MethodHandles.lookup(), "refField",
				MethodType.methodType(void.class, DummyHost.class, String.class),
				HotswapBridge.KIND_CONDITIONAL_EXPECTED, Opcodes.PUTFIELD,
				DummyHost.class, DummyHost.class, "ignored"));
		assertInstanceOf(IllegalStateException.class, expectedRefErr.getCause());
		assertFalse(HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL_EXPECTED, Object.class));
		assertFalse(HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL_EXPECTED, String.class));

		CallSite expectedIntSite = HotswapBridge.bootstrapExpected(
			MethodHandles.lookup(), "replayIntField",
			MethodType.methodType(void.class, DummyHost.class, int.class),
			HotswapBridge.KIND_CONDITIONAL_EXPECTED, Opcodes.PUTFIELD,
			DummyHost.class, DummyHost.class, 7);
		DummyHost host = new DummyHost();
		expectedIntSite.dynamicInvoker().invoke(host, 42);
		assertEquals(42, host.replayIntField);
	}

	@Test
	void customReferenceTypeDoesNotPinClassLoader() throws Throwable {
		HotswapBridge.warmUp();
		int beforeCount = HotswapBridge.cachedPutterCount(HotswapBridge.KIND_CONDITIONAL);

		WeakReference<Class<?>> typeRef = linkAndDropCustomType(DummyHost.class);
		assertEquals(beforeCount, HotswapBridge.cachedPutterCount(HotswapBridge.KIND_CONDITIONAL),
			"custom reference type must reuse Object slot without allocating a new cache entry");

		for (int i = 0; i < 10 && typeRef.get() != null; i++) {
			System.gc();
			Thread.sleep(20);
		}
		assertNull(typeRef.get(), "HotswapBridge must not retain user Class/ClassLoader keys");
	}

	private static void emitDefaultInit(ClassWriter cw) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	private static WeakReference<Class<?>> linkAndDropCustomType(Class<?> hostClass) throws Throwable {
		String customName = "remold.ref.DynamicValueType";
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, customName.replace('.', '/'),
			null, "java/lang/Object", null);
		emitDefaultInit(cw);
		cw.visitEnd();
		byte[] customBytes = cw.toByteArray();

		String holderName = "remold.ref.DynamicHolder";
		ClassWriter hw = new ClassWriter(0);
		hw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, holderName.replace('.', '/'),
			null, "java/lang/Object", null);
		emitDefaultInit(hw);
		FieldVisitor fv = hw.visitField(Opcodes.ACC_PUBLIC, "val", "L" + customName.replace('.', '/') + ";", null, null);
		fv.visitEnd();
		hw.visitEnd();
		byte[] holderBytes = hw.toByteArray();

		ClassLoader loader = new ClassLoader(HotswapBridge.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (customName.equals(name)) return defineClass(name, customBytes, 0, customBytes.length);
				if (holderName.equals(name)) return defineClass(name, holderBytes, 0, holderBytes.length);
				throw new ClassNotFoundException(name);
			}
		};
		Class<?> customType = Class.forName(customName, true, loader);
		Class<?> holderType = Class.forName(holderName, true, loader);
		Object holderInstance = holderType.getDeclaredConstructor().newInstance();
		Object customInstance = customType.getDeclaredConstructor().newInstance();

		CallSite site = HotswapBridge.bootstrap(
			MethodHandles.lookup(), "val",
			MethodType.methodType(void.class, holderType, customType),
			HotswapBridge.KIND_CONDITIONAL, Opcodes.PUTFIELD,
			holderType, hostClass);
		site.dynamicInvoker().invoke(holderInstance, customInstance);
		assertSame(customInstance, holderType.getField("val").get(holderInstance));

		return new WeakReference<>(customType);
	}

	@Test
	void clinitAndWarmupStayLazyAcrossColdTablesInFreshJvm() throws Exception {
		Path argFile = Files.createTempFile("hotswap-bridge-cp", ".args");
		try {
			String cp = System.getProperty("java.class.path");
			Files.writeString(argFile, "-cp\n\"" + cp.replace("\\", "\\\\") + "\"\n", StandardCharsets.UTF_8);

			String javaBin = ProcessHandle.current().info().command()
				.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
			List<String> cmd = new ArrayList<>();
			cmd.add(javaBin);
			for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
				if (!arg.startsWith("-agentlib:") && !arg.startsWith("-Xrunjdwp")) {
					cmd.add(arg);
				}
			}
			cmd.add("@" + argFile.toAbsolutePath());
			cmd.add(FreshJvmCheck.class.getName());

			Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = proc.waitFor();
			assertEquals(0, exit, "fresh JVM laziness check failed:\n" + out);
		} finally {
			Files.deleteIfExists(argFile);
		}
	}

	public static final class FreshJvmCheck {
		@SuppressWarnings("unused")
		int v = 5;

		public static void main(String[] args) throws Throwable {
			// 1. After <clinit>: only int conditional probe initialized; force & expected stay empty.
			assertState(1, 0, 0, "after <clinit>");
			if (!HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, int.class)) {
				throw new AssertionError("expected int conditional putter after <clinit>");
			}
			if (HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, Object.class)) {
				throw new AssertionError("Object conditional putter must stay lazy during <clinit>");
			}
			if (HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, double.class)) {
				throw new AssertionError("double conditional putter must stay lazy during <clinit>");
			}

			// 2. After warmUp(): only int + Object conditional slots populated.
			HotswapBridge.warmUp();
			assertState(2, 0, 0, "after warmUp()");
			if (!HotswapBridge.isPutterCached(HotswapBridge.KIND_CONDITIONAL, Object.class)) {
				throw new AssertionError("expected Object conditional putter after warmUp()");
			}

			// 3. On-demand expected-CAS link touches only the requested slot.
			CallSite cs = HotswapBridge.bootstrapExpected(
				MethodHandles.lookup(), "v",
				MethodType.methodType(void.class, FreshJvmCheck.class, int.class),
				HotswapBridge.KIND_CONDITIONAL_EXPECTED, Opcodes.PUTFIELD,
				FreshJvmCheck.class, FreshJvmCheck.class, 5);
			FreshJvmCheck inst = new FreshJvmCheck();
			cs.dynamicInvoker().invoke(inst, 9);
			if (inst.v != 9) throw new AssertionError("expected CAS did not write");
			assertState(2, 0, 1, "after lazy expected(int)");
		}

		private static void assertState(int cond, int force, int expected, String phase) {
			int actualCond = HotswapBridge.cachedPutterCount(HotswapBridge.KIND_CONDITIONAL);
			int actualForce = HotswapBridge.cachedPutterCount(HotswapBridge.KIND_FORCE);
			int actualExpected = HotswapBridge.cachedPutterCount(HotswapBridge.KIND_CONDITIONAL_EXPECTED);
			if (actualCond != cond || actualForce != force || actualExpected != expected) {
				throw new AssertionError(phase + " mismatch: expected ("
					+ cond + ", " + force + ", " + expected + ") but got ("
					+ actualCond + ", " + actualForce + ", " + actualExpected + ")");
			}
		}
	}
}
