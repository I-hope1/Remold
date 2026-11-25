import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-to-end evidence for the capture bridge on the <b>bytecode shape kotlinc actually emits</b>: the lambda's
 * implementation method uses a primitive descriptor ({@code (III)I}) while the indy's
 * {@code instantiatedMethodType} is boxed ({@code (Ljava/lang/Integer;)Ljava/lang/Integer;}).
 *
 * <p>javac never produces that split, which is why the family used to fall through the strict layout self-check
 * on both sides and every bridge became a ghost stub. The unit test
 * ({@code LambdaCaptureBridgeBoxingLayoutTest}) proves the predicate on synthetic descriptors; this probe proves
 * the whole pipeline: the class is really redefined on JBR/DCEVM, an old lambda instance spun from v1 keeps
 * working through a freshly created bridge, and the call resolves into the v2 implementation.</p>
 *
 * <p>Shape: {@code F1.invoke(Object)Object} is the erased SAM (Kotlin {@code Function1}); the impl handle takes
 * the captures first, then the SAM argument; the instantiated type is {@code (Integer)Integer}.</p>
 */
public class KotlinShapeBridgeProbe implements Opcodes {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   [PASS] " : "   [FAIL] ") + msg);
		if (ok) passed++; else failed++;
	}

	static void summary() {
		System.out.println("=== SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
	}

	static final class CaptureLogger implements RemoldAgent.Logger {
		@Override public void log(String msg) { record("LOG", msg); }
		@Override public void info(String msg) { record("INFO", msg); }
		@Override public void warn(String msg) { record("WARN", msg); }
		@Override public void error(String msg) { record("ERROR", msg); }
		@Override public void error(String msg, Throwable t) {
			record("ERROR", msg + (t == null ? "" : " | " + t));
		}
		private void record(String level, String msg) {
			String line = "[" + level + "] " + msg;
			synchronized (logs) { logs.add(line); }
			System.out.println("AGENT " + line);
		}
	}

	static final String OWNER = "bk/Host";
	static final String FI = "bk/F1";
	static final String SAM_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
	static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
		"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
		+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
		+ "Ljava/lang/invoke/CallSite;", false);

	public static void main(String[] args) throws Throwable {
		System.out.println("=== KotlinShapeBridgeProbe ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available (run with -javaagent remold-agent.jar)");
		check(RemoldAgent.isEnhancedHotswapEnabled(), "P0 enhanced class redefinition is enabled (JBR/DCEVM)");
		if (inst == null) {
			summary();
			System.exit(1);
			return;
		}
		RemoldAgent.logger = new CaptureLogger();
		try {
			run(inst);
		} catch (Throwable t) {
			check(false, "probe aborted: " + t);
			t.printStackTrace();
		}
		summary();
		if (failed > 0) System.exit(1);
	}

	static void run(Instrumentation inst) throws Throwable {
		byte[] fi = fi();
		byte[] hostV1 = host(1);
		byte[] hostV2 = host(2);

		FixtureLoader loader = new FixtureLoader(Map.of(
			"bk.F1", fi, "bk.Host", hostV1));
		Class<?> fiClass = Class.forName("bk.F1", true, loader);
		Class<?> hostClass = Class.forName("bk.Host", true, loader);
		Object lambdaV1 = hostClass.getMethod("make", int.class, String.class).invoke(null, 1, "ab");
		Method invoke = fiClass.getMethod("invoke", Object.class);
		check(Integer.valueOf(8).equals(invoke.invoke(lambdaV1, 5)),
			"R0 baseline: v1 lambda computes a + b + x = 8 (actual=" + invoke.invoke(lambdaV1, 5) + ")");

		Path dir = Files.createTempDirectory("remold-kotlin-shape-bridge");
		Path hostPath = dir.resolve("bk/Host.class");
		Files.createDirectories(hostPath.getParent());
		Files.write(hostPath, hostV1);

		System.out.println("-- R1: redefine to the one-capture version (old lambda must survive through a bridge) --");
		Files.write(hostPath, hostV2);
		process(hostPath, inst);

		// The new version really is live: a freshly built lambda uses the v2 body (a + 5 + x = 17).
		Object lambdaV2 = hostClass.getMethod("make", int.class).invoke(null, 7);
		check(Integer.valueOf(17).equals(invoke.invoke(lambdaV2, 5)),
			"R1: the redefined class serves the v2 body (7 + 5 + 5 = 17, actual=" + invoke.invoke(lambdaV2, 5) + ")");
		check(hasField(hostClass, "make", int.class),
			"R1: the class definition now exposes the one-argument factory");

		// The decisive assertion: the v1 lambda instance still works. Its hidden class calls the OLD synthetic
		// descriptor (III)I, which only exists after the round because a capture bridge was emitted for it.
		Object oldResult;
		try {
			oldResult = invoke.invoke(lambdaV1, 5);
		} catch (Throwable t) {
			check(false, "R1: the v1 lambda threw after the round: " + t);
			return;
		}
		check(Integer.valueOf(11).equals(oldResult),
			"R1: the v1 lambda still resolves — bridge forwards its kept capture (1) into v2 (1 + 5 + 5 = 11, actual="
				+ oldResult + ")");
		boolean bridgeEvent = false;
		StringBuilder events = new StringBuilder();
		for (remold.LambdaAligner.AlignmentEvent e : remold.LambdaAligner.TEST_EVENTS) {
			events.append('\n').append(e);
			if (e.kind != null && e.kind.startsWith("BRIDGE")) bridgeEvent = true;
		}
		check(bridgeEvent,
			"R1: the aligner really planned a capture bridge for this class:" + events);
		check(!hasLog("CAPTURE-BRIDGE-SKIPPED"),
			"R1: no bridge was skipped for this family");
	}

	// ------------------------------------------------------------------ fixtures (kotlinc bytecode shape)

	static byte[] fi() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(V1_8, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, FI, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "invoke", SAM_DESC, null, null);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * @param version 1 = two int captures, impl {@code (III)I}; 2 = one int capture, impl {@code (II)I}
	 */
	static byte[] host(int version) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(V1_8, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);

		if (version == 1) {
			// Two captures of DIFFERENT types (int, String): the bridge mapping from the old capture table to
			// the surviving one must be unambiguous, so a same-typed pair is the wrong fixture.
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make",
				"(ILjava/lang/String;)L" + FI + ";", null, null);
			mv.visitCode();
			mv.visitVarInsn(ILOAD, 0);
			mv.visitVarInsn(ALOAD, 1);
			mv.visitInvokeDynamicInsn("invoke", "(ILjava/lang/String;)L" + FI + ";", METAFACTORY,
				Type.getType(SAM_DESC),
				new Handle(H_INVOKESTATIC, OWNER, "lambda$make$0", "(ILjava/lang/String;I)I", false),
				Type.getType("(Ljava/lang/Integer;)Ljava/lang/Integer;"));
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			MethodVisitor impl = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$make$0",
				"(ILjava/lang/String;I)I", null, null);
			impl.visitCode();
			impl.visitVarInsn(ILOAD, 0);
			impl.visitVarInsn(ALOAD, 1);
			impl.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
			impl.visitInsn(IADD);
			impl.visitVarInsn(ILOAD, 2);
			impl.visitInsn(IADD);
			impl.visitInsn(IRETURN);
			impl.visitMaxs(0, 0);
			impl.visitEnd();
		} else {
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make",
				"(I)L" + FI + ";", null, null);
			mv.visitCode();
			mv.visitVarInsn(ILOAD, 0);
			mv.visitInvokeDynamicInsn("invoke", "(I)L" + FI + ";", METAFACTORY,
				Type.getType(SAM_DESC),
				new Handle(H_INVOKESTATIC, OWNER, "lambda$make$0", "(II)I", false),
				Type.getType("(Ljava/lang/Integer;)Ljava/lang/Integer;"));
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			MethodVisitor impl = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$make$0",
				"(II)I", null, null);
			impl.visitCode();
			impl.visitVarInsn(ILOAD, 0);
			impl.visitInsn(ICONST_5);
			impl.visitInsn(IADD);
			impl.visitVarInsn(ILOAD, 1);
			impl.visitInsn(IADD);
			impl.visitInsn(IRETURN);
			impl.visitMaxs(0, 0);
			impl.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------ helpers

	static void process(Path changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, Set.of(changed), inst.getAllLoadedClasses());
	}

	static boolean hasField(Class<?> type, String methodName, Class<?> arg) {
		try {
			type.getMethod(methodName, arg);
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	static boolean hasLog(String needle) {
		synchronized (logs) {
			for (String line : logs) if (line.contains(needle)) return true;
		}
		return false;
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		FixtureLoader(Map<String, byte[]> classes) {
			super(KotlinShapeBridgeProbe.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
