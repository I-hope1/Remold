import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Field shape: the host of a nested anonymous class is itself an anonymous class, and the child's name carries an
 * extra middle segment ({@code VerAnonTarget$3$innerObj$1}) — kotlinc puts the property name there.
 *
 * <p>Reported symptom: for host {@code VerAnonTarget$3} the old side is always empty
 * ({@code level 2: old=0, new=1}), so every edit — even a body-only one — allocates a NEW class number
 * ({@code $innerObj$2}, {@code $innerObj$3}) and injects it. Live instances keep the old body forever and the
 * layout gate never runs, because no pairing happens at all.</p>
 *
 * <p>Cause: the old-side collection resolved the host from the CLASS NAME when no bytes were at hand. Stripping the
 * trailing digits from {@code $3$innerObj$1} yields {@code $3$innerObj}, which never exists, so the class was never
 * admitted into the old bucket. The host must come from the attribute (EnclosingMethod), which names {@code $3}.
 * </p>
 *
 * <p>Rounds on a real JVM (production driver {@code RemoldAgent.processChanges}):</p>
 * <ol>
 *   <li><b>R1 body-only</b>: the existing instance of the inner class must run the NEW body (pairing), and no new
 *       class number may be allocated for it.</li>
 *   <li><b>R2 capture growth</b>: adding a capture field to the inner class must go through the layout gate
 *       (refuse in place), not be silently renumbered into {@code $innerObj$2}/{@code $innerObj$3}.</li>
 * </ol>
 */
public class NestedAnonHostOldSideProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "NAH-PASS " : "NAH-FAIL ") + msg);
		if (ok) passed++; else failed++;
	}

	static final class CaptureLogger implements RemoldAgent.Logger {
		@Override public void log(String msg) { record("LOG", msg); }
		@Override public void info(String msg) { record("INFO", msg); }
		@Override public void warn(String msg) { record("WARN", msg); }
		@Override public void error(String msg) { record("ERROR", msg); }
		@Override public void error(String msg, Throwable t) { record("ERROR", msg + (t == null ? "" : " | " + t)); }
		private void record(String level, String msg) {
			synchronized (logs) { logs.add("[" + level + "] " + msg); }
			System.out.println("NAH-AGENT [" + level + "] " + msg);
		}
	}

	private static final String HOST  = "nah/VerAnonTarget";
	private static final String ANON  = HOST + "$3";
	private static final String CHILD = ANON + "$innerObj$1";

	/** A class with one method returning a constant — enough to observe which body is running. */
	private static byte[] plain(String name, String method, String value) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(value);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	/** kotlinc-shaped anonymous class: EnclosingMethod points at the parent, optional capture field. */
	private static byte[] anon(String name, String enclosing, String method, String value, boolean withCapture) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
			name, null, "java/lang/Object", null);
		cw.visitOuterClass(enclosing, null, null);
		cw.visitInnerClass(name, null, null, Opcodes.ACC_FINAL | Opcodes.ACC_STATIC);
		if (withCapture) cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "val$cap", "I", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
			withCapture ? "(I)V" : "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		if (withCapture) {
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitVarInsn(Opcodes.ILOAD, 1);
			init.visitFieldInsn(Opcodes.PUTFIELD, name, "val$cap", "I");
		}
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(2, 2);
		init.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(value);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== NestedAnonHostOldSideProbe: old side of an anonymous host ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation is available");
		check(RemoldAgent.isEnhancedHotswapEnabled(), "P0 enhanced class redefinition is enabled (JBR/DCEVM)");
		if (inst == null) { summary(); System.exit(1); return; }
		RemoldAgent.logger = new CaptureLogger();
		// 现场证据就是这几行层级读数（level 2: old=/new=），探针按同一口径断言。
		RemoldAgent.ANON_DEBUG = true;
		try {
			run(inst);
		} catch (Throwable t) {
			check(false, "probe aborted: " + describe(t));
			t.printStackTrace();
		}
		summary();
		if (failed > 0) System.exit(1);
	}

	static void run(Instrumentation inst) throws Throwable {
		Map<String, byte[]> v1 = new LinkedHashMap<>();
		v1.put(HOST, plain(HOST, "stage", "v1"));
		v1.put(ANON, anon(ANON, HOST, "outer", "outer-v1", false));
		v1.put(CHILD, anon(CHILD, ANON, "inner", "inner-v1", false));

		Path dir = Files.createTempDirectory("remold-nested-host-old");
		for (Map.Entry<String, byte[]> e : v1.entrySet()) {
			Path p = dir.resolve(e.getKey() + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
		}

		Map<String, byte[]> initial = new HashMap<>(v1);
		FixtureLoader loader = new FixtureLoader(initial);
		Class<?> hostClass  = Class.forName(HOST.replace('/', '.'), true, loader);
		Class<?> anonClass  = Class.forName(ANON.replace('/', '.'), true, loader);
		Class<?> childClass = Class.forName(CHILD.replace('/', '.'), true, loader);
		Object survivor = childClass.getDeclaredConstructor().newInstance();
		check("inner-v1".equals(call(survivor, "inner")),
			"R0-baseline: the surviving inner instance runs v1 (" + call(survivor, "inner") + ")");
		check(anonClass.getDeclaredConstructor().newInstance() != null, "R0-baseline: the anonymous host instantiates");

		// ================= R1: body-only edit of the nested class =================
		System.out.println("-- R1: body-only edit of " + CHILD + " --");
		logs.clear();
		byte[] childV2 = anon(CHILD, ANON, "inner", "inner-v2", false);
		Files.write(dir.resolve(CHILD + ".class"), childV2);
		// 真实宿主同轮也改了：现场就是这样，于是本轮要同时对齐 VerAnonTarget 与 VerAnonTarget$3 两个宿主组。
		// 只有"单宿主"时执行器会走 singleHost 快捷路径，恰好绕过旧侧宿主解析，测不出本缺陷。
		Files.write(dir.resolve(HOST + ".class"), plain(HOST, "stage", "v2"));
		Set<Path> changed = new LinkedHashSet<>();
		changed.add(dir.resolve(CHILD + ".class"));
		changed.add(dir.resolve(HOST + ".class"));
		process(changed, inst);
		check("v2".equals(hostClass.getMethod("stage").invoke(hostClass.getDeclaredConstructor().newInstance())),
			"R1: the real host also took its new version");

		boolean paired = false;
		boolean renumbered = false;
		for (String line : snapshot()) {
			if (line.contains("level 2: old=1")) paired = true;
			if (line.contains("$innerObj$2") || line.contains("$innerObj$3")) renumbered = true;
		}
		check(paired, "R1: the old side of the nested host was collected (level 2: old=1)");
		check(!renumbered, "R1: no new class number was allocated for a body-only edit");
		check("inner-v2".equals(call(survivor, "inner")),
			"R1: the SURVIVING instance runs the new body (" + call(survivor, "inner") + ")");

		// ================= R2: the compiler shifted the outer anon (compiled $4, old $3) =================
		System.out.println("-- R2: outer anon shifted to $4, nested class compiled as $4$innerObj$1 --");
		logs.clear();
		String anonShifted  = HOST + "$4";
		String childShifted = anonShifted + "$innerObj$1";
		Files.write(dir.resolve(anonShifted + ".class"), anon(anonShifted, HOST, "outer", "outer-v2", false));
		Files.write(dir.resolve(childShifted + ".class"), anon(childShifted, anonShifted, "inner", "inner-shifted", false));
		Set<Path> shifted = new LinkedHashSet<>();
		shifted.add(dir.resolve(anonShifted + ".class"));
		shifted.add(dir.resolve(childShifted + ".class"));
		process(shifted, inst);

		boolean collectedAfterShift = false;
		for (String line : snapshot()) {
			if (line.contains("level 2: old=1")) collectedAfterShift = true;
		}
		check(collectedAfterShift,
			"R2: after the outer class shifted, the old nested class is still in the old-side bucket"
				+ " (otherwise every edit renumbers and live instances never update)");
		check("inner-shifted".equals(call(survivor, "inner")),
			"R2: the SURVIVING instance follows the shifted nested class (" + call(survivor, "inner") + ")");

		// ================= R3: capture growth on the nested class =================
		System.out.println("-- R2: the nested class gains a capture field --");
		logs.clear();
		byte[] childV3 = anon(CHILD, ANON, "inner", "inner-v3", true);
		Files.write(dir.resolve(CHILD + ".class"), childV3);
		Throwable escaped = null;
		try {
			process(Set.of(dir.resolve(CHILD + ".class")), inst);
		} catch (Throwable t) {
			escaped = t;
		}
		check(escaped == null, "R3: the round did not throw (" + describe(escaped) + ")");
		boolean gateMentioned = false;
		for (String line : snapshot()) {
			if (line.contains("ANON-LAYOUT") || line.contains("layout") && line.contains("refus")) gateMentioned = true;
		}
		check(gateMentioned,
			"R3: capture growth goes through the layout gate instead of being silently renumbered");
		check("inner-shifted".equals(call(survivor, "inner")),
			"R3: the surviving instance keeps the last accepted body, not a broken one ("
				+ call(survivor, "inner") + ")");
		check(hostClass.getMethod("stage").invoke(hostClass.getDeclaredConstructor().newInstance()) != null,
			"R3: the real host still instantiates");
	}

	// ------------------------------------------------------------------ helpers

	static String call(Object instance, String method) {
		try {
			Method m = instance.getClass().getMethod(method);
			m.setAccessible(true);
			return String.valueOf(m.invoke(instance));
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
			return "<threw " + cause.getClass().getSimpleName() + ">";
		}
	}

	static String describe(Throwable t) {
		if (t == null) return "none";
		Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
		return cause.getClass().getName() + ": " + cause.getMessage();
	}

	static List<String> snapshot() {
		synchronized (logs) { return List.copyOf(logs); }
	}

	static void process(Set<Path> changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, changed, inst.getAllLoadedClasses());
	}

	static final class FixtureLoader extends ClassLoader {
		private final Map<String, byte[]> classes;
		FixtureLoader(Map<String, byte[]> classes) {
			super(NestedAnonHostOldSideProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static void summary() {
		System.out.println("NAH-SUMMARY passed=" + passed + " failed=" + failed);
	}
}
