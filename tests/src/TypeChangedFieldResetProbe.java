import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A field whose DESCRIPTOR changes is treated by DCEVM as "delete the old field, add the new one" — but the value
 * already stored in existing instances is preserved (measured by {@link DcevmCrossHostProbe}). If the patcher only
 * considers genuinely new fields, such a field gets no patch at all and a value of the OLD type stays in the heap
 * under the new type's name: later casts/method calls fail, or — when the types happen to be assignable — code
 * silently runs on the wrong object.
 *
 * <p>This probe drives the production pipeline ({@code RemoldAgent.processChanges}) and asserts the value is
 * explicitly reset, so the read-back is NOT the old object.</p>
 */
public class TypeChangedFieldResetProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "TCF-PASS " : "TCF-FAIL ") + msg);
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
			System.out.println("TCF-AGENT [" + level + "] " + msg);
		}
	}

	private static final String CLS = "txh/Slot";
	// 旧值刻意选 String：它对新类型 String **可赋值**，所以"保留旧值"不会抛异常，只会静默地作用在错对象上 ——
	// 比 ClassCastException 更难发现，正是这条修复要挡的形态。
	private static final Object OLD_PAYLOAD = "old-payload";
	private static final String NEW_PAYLOAD = "fresh-payload";

	/**
	 * {@code slot()} returns {@code Object} in both versions, so reading the stale value cannot fail on its own — the
	 * probe would otherwise measure a ClassCastException instead of the reset.
	 */
	private static byte[] fixture(String fieldDesc, Object payload) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CLS, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "slot", fieldDesc, null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitLdcInsn(payload);
		init.visitFieldInsn(Opcodes.PUTFIELD, CLS, "slot", fieldDesc);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(2, 1);
		init.visitEnd();
		MethodVisitor peek = cw.visitMethod(Opcodes.ACC_PUBLIC, "peek", "()Ljava/lang/Object;", null, null);
		peek.visitCode();
		peek.visitVarInsn(Opcodes.ALOAD, 0);
		peek.visitFieldInsn(Opcodes.GETFIELD, CLS, "slot", fieldDesc);
		peek.visitInsn(Opcodes.ARETURN);
		peek.visitMaxs(1, 1);
		peek.visitEnd();
		return cw.toByteArray();
	}

	private static final String REF = "txh/HostRef";

	/**
	 * {@code this$0} 形态：字段由构造器**参数**赋值，因此没有可提取的常量初值。
	 * 字段名用 {@code outer} 而不是字面量 {@code this$0}：后者会被 diff 层按合成字段特殊对待，
	 * 那样测到的就不是"参数赋值"这一条通路。
	 */
	private static byte[] hostRef(String fieldDesc, boolean firstCtor) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, REF, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "outer", fieldDesc, null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + fieldDesc + ")V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitVarInsn(Opcodes.ALOAD, 1);
		init.visitFieldInsn(Opcodes.PUTFIELD, REF, "outer", fieldDesc);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(2, 2);
		init.visitEnd();
		MethodVisitor peek = cw.visitMethod(Opcodes.ACC_PUBLIC, "peek", "()Ljava/lang/Object;", null, null);
		peek.visitCode();
		peek.visitVarInsn(Opcodes.ALOAD, 0);
		peek.visitFieldInsn(Opcodes.GETFIELD, REF, "outer", fieldDesc);
		peek.visitInsn(Opcodes.ARETURN);
		peek.visitMaxs(1, 1);
		peek.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] marker(String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		return cw.toByteArray();
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== TypeChangedFieldResetProbe: descriptor change must not leave the old object behind ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation available");
		check(RemoldAgent.isEnhancedHotswapEnabled(), "P0 enhanced redefinition is active");
		if (inst == null) { summary(); System.exit(1); return; }
		RemoldAgent.logger = new CaptureLogger();
		try {
			run(inst);
		} catch (Throwable t) {
			check(false, "probe aborted: " + describe(t));
		}
		summary();
		if (failed > 0) System.exit(1);
	}

	static void run(Instrumentation inst) throws Throwable {
		Path dir = Files.createTempDirectory("remold-type-changed");
		Path file = dir.resolve(CLS + ".class");
		Files.createDirectories(file.getParent());
		Files.write(file, fixture("Ljava/lang/Object;", OLD_PAYLOAD));

		Map<String, byte[]> initial = new HashMap<>();
		initial.put(CLS, fixture("Ljava/lang/Object;", OLD_PAYLOAD));
		FixtureLoader loader = new FixtureLoader(initial);
		Class<?> cls = Class.forName(CLS.replace('/', '.'), true, loader);
		Object survivor = cls.getDeclaredConstructor().newInstance();

		Object before = call(survivor, "peek");
		check(before != null && before.equals(OLD_PAYLOAD),
			"R0 baseline: the surviving instance holds the old payload (" + before + ")");

		// ================= descriptor change: Object -> String =================
		System.out.println("-- R1: slot goes from Ljava/lang/Object; to Ljava/lang/String; --");
		logs.clear();
		Files.write(file, fixture("Ljava/lang/String;", NEW_PAYLOAD));
		process(Set.of(file), inst);

		// R1：默认模式（layout_gate=reject）—— 有存活实例时不应该真把描述符换掉，而应整轮拒绝并给出口。
		boolean refused = false;
		for (String line : snapshot()) {
			if (line.contains("LAYOUT-REJECT") && line.contains("slot")) refused = true;
		}
		check(refused, "R1: default mode refuses the redefine and names the field (fail-closed, no stale value is possible)");
		check(OLD_PAYLOAD.equals(call(survivor, "peek")),
			"R1: the surviving instance keeps running the OLD code, as the refusal message promises");

		// R2：强制模式（layout_gate=warn）—— 现在允许 redefine，此时旧类型的值必须被显式清零，
		// 否则就以新类型的身份留在堆里（本例新旧类型可赋值，不抛异常 = 静默错对象）。
		System.out.println("-- R2: layout gate forced to warn, the redefine proceeds --");
		logs.clear();
		java.lang.reflect.Field gate = RemoldAgent.class.getField("LAYOUT_GATE");
		gate.set(null, "warn");
		try {
			process(Set.of(file), inst);
		} finally {
			gate.set(null, "reject");
		}
		Object after = call(survivor, "peek");
		check(!OLD_PAYLOAD.equals(after),
			"R2: the surviving instance no longer reads the OLD object (" + after + ")");
		check(NEW_PAYLOAD.equals(after),
			"R2: a provable initializer is written UNCONDITIONALLY over the migrated old value (" + after + ")");

		Object fresh = call(cls.getDeclaredConstructor().newInstance(), "peek");
		check(NEW_PAYLOAD.equals(fresh),
			"R2: a NEW instance still gets the constructor's value through the new descriptor (" + fresh + ")");

		// R3：构造器参数赋值的形态（等价于 this$0 的 LA; -> LB;）：没有任何可提取的初值，
		// 只能清成新类型的零值。这是"不许留旧对象"的底线场景。
		System.out.println("-- R3: field assigned from a constructor parameter (the this$0 shape) --");
		logs.clear();
		Path refFile = dir.resolve(REF + ".class");
		Files.write(refFile, hostRef("Ltxh/Old;", true));
		Map<String, byte[]> refs = new HashMap<>(initial);
		refs.put(REF, hostRef("Ltxh/Old;", true));
		refs.put("txh/Old", marker("txh/Old"));
		refs.put("txh/New", marker("txh/New"));
		FixtureLoader refLoader = new FixtureLoader(refs);
		Class<?> refCls = Class.forName(REF.replace('/', '.'), true, refLoader);
		Object oldPayload = Class.forName("txh.Old", true, refLoader).getDeclaredConstructor().newInstance();
		Object refSurvivor = refCls.getDeclaredConstructor(Class.forName("txh.Old", true, refLoader))
			.newInstance(oldPayload);
		check(oldPayload.equals(call(refSurvivor, "peek")),
			"R3 baseline: the surviving instance reaches its outer object through the captured field");

		Files.write(refFile, hostRef("Ltxh/New;", true));
		gate.set(null, "warn");
		try {
			process(Set.of(refFile), inst);
		} finally {
			gate.set(null, "reject");
		}
		Object refAfter = call(refSurvivor, "peek");
		check(!oldPayload.equals(refAfter),
			"R3: the captured reference of the OLD type is gone (" + refAfter + ")");
		check(refAfter == null,
			"R3: with no provable initializer the field is reset to null rather than keeping a wrong-typed object");
		boolean warned = false;
		for (String line : snapshot()) {
			if (line.contains("[TYPE-CHANGED-FIELD]")) warned = true;
		}
		check(warned, "R3: the reset is announced (a silent reset would be as confusing as a silent stale value)");
	}

	// ------------------------------------------------------------------ helpers

	static Object call(Object instance, String method) {
		try {
			Method m = instance.getClass().getMethod(method);
			m.setAccessible(true);
			return m.invoke(instance);
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
			return "<threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ">";
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
			super(TypeChangedFieldResetProbe.class.getClassLoader());
			this.classes = classes;
		}
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static void summary() {
		System.out.println("TCF-SUMMARY passed=" + passed + " failed=" + failed);
	}
}
