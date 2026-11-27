import remold.RemoldAgent;
import remold.ref.InitFix;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Two field-reported failures, both about information that only exists inside one batch.
 *
 * <p><b>A - a new field whose initializer is a class born in this same batch.</b> InitFix decides whether a constructor
 * is {@code protected} by consulting the hierarchy oracle, which can only look at the batch's byte view, the runtime
 * bytes, or the on-disk baseline. A class that this very batch compiled / renumbered is in none of them yet, so the
 * check returns "unknown" and the whole host is dropped as {@code INITFIX-REFUSE} - which is what happened to
 * {@code new field val movedOp = object : IntUnaryOperator { ... }} in the field.</p>
 *
 * <p><b>B - dropping a host must invalidate decisions that were made because it was in the batch.</b> A member removal
 * is waived when no reader remains <i>in this batch</i>; if the reader class is then dropped, the waiver's premise is
 * gone. The field hit this twice: {@code MovedOuterTarget$Inner.getMovedOp()} was removed after its reader
 * {@code MovedOuterTarget} had been dropped (NoSuchMethodError from the old code), and
 * {@code CounterAnonTarget$1}'s constructor was re-signatured after {@code CounterAnonTarget} was dropped
 * (NoSuchMethodError on instantiation).</p>
 */
public class BatchCrossClassProbe {

	static int passed = 0;
	static int failed = 0;
	static final List<String> logs = new ArrayList<>();

	static void note(String msg) { System.out.println("BXP-NOTE " + msg); }

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "BXP-PASS " : "BXP-FAIL ") + msg);
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
			System.out.println("BXP-AGENT [" + level + "] " + msg);
		}
	}

	private static final String HOST = "bxp/Host";
	private static final String CFG  = "bxp/Cfg";
	private static final String LAZY_HOST = "bxplazy/LazyHost";
	private static final String LAZY_CFG  = "bxplazy/LazyCfg";
	private static final String HOST2 = "bxp/Host2";
	private static final String ANON2 = "bxp/Host2$1";

	private static byte[] hostV1(String host) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, host, null, "java/lang/Object", null);
		ctor(cw, host, "()V", null);
		return cw.toByteArray();
	}

	/** v2 adds {@code cfg} and initializes it with a class that is born in this same batch. */
	private static byte[] hostV2(String host, String cfg) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, host, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "cfg", "L" + cfg + ";", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitTypeInsn(Opcodes.NEW, cfg);
		init.visitInsn(Opcodes.DUP);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, cfg, "<init>", "()V", false);
		init.visitFieldInsn(Opcodes.PUTFIELD, host, "cfg", "L" + cfg + ";");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(3, 1);
		init.visitEnd();
		peekField(cw, host, "cfg", "L" + cfg + ";");
		return cw.toByteArray();
	}

	private static byte[] cfg(String name, String marker) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		ctor(cw, name, "()V", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "who", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(marker);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	// ---------------------------------------------------------------- B fixtures

	/** Host whose new field initializer is environment dependent, so InitFix cannot extract it. */
	private static byte[] host2V2() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST2, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "stamp", "J", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
		init.visitFieldInsn(Opcodes.PUTFIELD, HOST2, "stamp", "J");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(3, 1);
		init.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] host2V1() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST2, null, "java/lang/Object", null);
		ctor(cw, HOST2, "()V", null);
		return cw.toByteArray();
	}

	/** Anonymous class owned by {@code Host2}; its body changes across the round. */
	private static byte[] anon2(String marker) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, ANON2, null, "java/lang/Object", null);
		cw.visitOuterClass(HOST2, "run", "()V");
		ctor(cw, ANON2, "()V", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "who", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(marker);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	// ---------------------------------------------------------------- helpers

	private static void ctor(ClassWriter cw, String owner, String desc, String ignore) {
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
	}

	private static void peekField(ClassWriter cw, String owner, String field, String desc) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "peek", "()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== BatchCrossClassProbe: batch-born initializer class + cascade on host drop ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null && RemoldAgent.isEnhancedHotswapEnabled(), "P0 enhanced redefinition is active");
		if (inst == null) { summary(); return; }
		RemoldAgent.logger = new CaptureLogger();
		AgentFreshness.verify(BatchCrossClassProbe::check, BatchCrossClassProbe::note);
		try { partA(inst); } catch (Throwable t) { check(false, "part A aborted: " + describe(t)); }
		try { partALazy(inst); } catch (Throwable t) { check(false, "part A-lazy aborted: " + describe(t)); }
		try { partB(inst); } catch (Throwable t) { check(false, "part B aborted: " + describe(t)); }
		summary();
	}

	/** A: the initializer class is born in this batch, so the protected-status lookup must still succeed. */
	static void partA(Instrumentation inst) throws Throwable {
		System.out.println("-- A: new field initializer references a class from this same batch --");
		Path dir = Files.createTempDirectory("remold-batch-cross-a");
		Map<String, byte[]> v1 = new HashMap<>();
		v1.put(HOST, hostV1(HOST));
		v1.put(CFG, cfg(CFG, "cfg-v1"));
		write(dir, v1);
		Loader loader = new Loader(v1);
		Class<?> hostCls = Class.forName(HOST.replace('/', '.'), true, loader);
		Object survivor = hostCls.getDeclaredConstructor().newInstance();

		logs.clear();
		Map<String, byte[]> v2 = new HashMap<>();
		v2.put(HOST, hostV2(HOST, CFG));
		v2.put(CFG, cfg(CFG, "cfg-v2"));
		Set<Path> changed = write(dir, v2);
		process(changed, inst);

		for (String line : snapshot()) if (line.contains("INITFIX") || line.contains("BATCH") || line.contains("bxp")) System.out.println("BXP-LOG " + line);
		boolean refused = false;
		for (String line : snapshot()) if (line.contains("[INITFIX-REFUSE]") && line.contains("bxp.Host")) refused = true;
		check(!refused, "A1: the host is not dropped as INITFIX-REFUSE just because its initializer class is new in this batch");
		Object value = call(survivor, "peek");
		check(value != null && !String.valueOf(value).startsWith("<threw"),
			"A2: the surviving instance received the new field value (" + value + ")");
		check("cfg-v2".equals(call(value, "who")),
			"A3: the value really is the class from this batch (" + call(value, "who") + ")");
	}

	/**
	 * A-lazy: the same initializer class is born in this batch, but {@code lazy_load} mounts it by name instead
	 * of injecting it through {@code defineClass}. The injection-site registration never runs on this path, so
	 * the batch side table is the only thing keeping the class visible to InitFix.
	 */
	static void partALazy(Instrumentation inst) throws Throwable {
		System.out.println("-- A-lazy: the born class is lazy-loaded, not injected --");
		RemoldAgent.RedefineMode previousMode = RemoldAgent.REDEFINE_MODE;
		boolean previousUcp = RemoldAgent.UCP_APPEND;
		RemoldAgent.REDEFINE_MODE = RemoldAgent.RedefineMode.lazy_load;
		RemoldAgent.UCP_APPEND = true;
		try {
			Path dir = Files.createTempDirectory("remold-batch-cross-a-lazy");
			Map<String, byte[]> v1 = new HashMap<>();
			v1.put(LAZY_HOST, hostV1(LAZY_HOST));
			v1.put(LAZY_CFG, cfg(LAZY_CFG, "lazy-cfg-v1"));
			write(dir, v1);
			// The loader stands in for the on-disk build output: lazy_load resolves a class by asking the loader,
			// so the new round's bytes must be visible there without the class having been defined yet.
			Map<String, byte[]> loaderClasses = new HashMap<>(v1);
			Loader loader = new Loader(loaderClasses);
			Class<?> hostCls = Class.forName(LAZY_HOST.replace('/', '.'), true, loader);
			Object survivor = hostCls.getDeclaredConstructor().newInstance();

			logs.clear();
			Map<String, byte[]> v2 = new HashMap<>();
			v2.put(LAZY_HOST, hostV2(LAZY_HOST, LAZY_CFG));
			v2.put(LAZY_CFG, cfg(LAZY_CFG, "lazy-cfg-v2"));
			Set<Path> changed = write(dir, v2);
			loaderClasses.putAll(v2);
			process(changed, inst);

			boolean refused = false;
			boolean injectedBorn = false;
			for (String line : snapshot()) {
				if (line.contains("[INITFIX-REFUSE]") && line.contains("bxplazy.LazyHost")) refused = true;
				if (line.contains("[INJECTED]") && (line.contains("bxplazy.LazyCfg") || line.contains("bxplazy/LazyCfg"))) {
					injectedBorn = true;
				}
			}
			check(!refused, "A4: lazy_load does not refuse the host when its initializer class is only mounted");
			check(!injectedBorn, "A5: the born class really took the lazy path (no defineClass injection)");
			Object value = call(survivor, "peek");
			check(value != null && "lazy-cfg-v2".equals(call(value, "who")),
			 "A6: the surviving instance received the lazy-born class (" + call(value, "who") + ")");
			check(extraBytesEmpty(), "A7: the per-batch side table is empty again after the batch ends");
		} finally {
			RemoldAgent.REDEFINE_MODE = previousMode;
			RemoldAgent.UCP_APPEND = previousUcp;
		}
	}

	private static boolean extraBytesEmpty() {
		try {
			Field field = InitFix.class.getDeclaredField("EXTRA_BYTES");
			field.setAccessible(true);
			Object value = field.get(null);
			return value instanceof Map<?, ?> map && map.isEmpty();
		} catch (Throwable t) {
			return false;
		}
	}

	/** B: dropping a host must take its family with it instead of leaving a half-applied batch. */
	static void partB(Instrumentation inst) throws Throwable {
		System.out.println("-- B: host dropped by INITFIX-REFUSE must cascade to its anonymous family --");
		Path dir = Files.createTempDirectory("remold-batch-cross-b");
		Map<String, byte[]> v1 = new HashMap<>();
		v1.put(HOST2, host2V1());
		v1.put(ANON2, anon2("anon-v1"));
		write(dir, v1);
		Loader loader = new Loader(v1);
		Class.forName(HOST2.replace('/', '.'), true, loader);   // 先让宿主成为"已加载的旧类"
		Class<?> anonCls = Class.forName(ANON2.replace('/', '.'), true, loader);
		Object survivor = anonCls.getDeclaredConstructor().newInstance();
		check("anon-v1".equals(call(survivor, "who")), "B0 baseline: the anon runs v1");

		logs.clear();
		Map<String, byte[]> v2 = new HashMap<>();
		v2.put(HOST2, host2V2());
		v2.put(ANON2, anon2("anon-v2"));
		process(write(dir, v2), inst);

		for (String line : snapshot()) if (line.contains("INITFIX") || line.contains("BATCH") || line.contains("bxp")) System.out.println("BXP-LOG " + line);
		boolean hostRefused = false;
		boolean familyDropped = false;
		for (String line : snapshot()) {
			if (line.contains("[INITFIX-REFUSE]") && line.contains("bxp.Host2")) hostRefused = true;
			if (line.contains("BATCH-CASCADE-FAMILY") || line.contains("[BATCH-CASCADE]")) familyDropped = true;
		}
		check(hostRefused || familyDropped,
			"B1: the host is refused or the family is cascaded out (one of the two must be observable)");
		check(familyDropped,
			"B2: the anonymous family of the dropped host is cascaded out of the batch instead of being applied alone");
		String who = String.valueOf(call(survivor, "who"));
		check("anon-v1".equals(who),
			"B3: the surviving anon instance keeps running the old body, so the old host code still works (" + who + ")");
	}

	// ---------------------------------------------------------------- plumbing

	static Set<Path> write(Path dir, Map<String, byte[]> classes) throws Throwable {
		Set<Path> out = new LinkedHashSet<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			Path p = dir.resolve(e.getKey() + ".class");
			Files.createDirectories(p.getParent());
			Files.write(p, e.getValue());
			out.add(p);
		}
		return out;
	}

	static void process(Set<Path> changed, Instrumentation inst) throws Exception {
		Method m = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		m.setAccessible(true);
		m.invoke(null, changed, inst.getAllLoadedClasses());
	}

	static Object call(Object instance, String method) {
		if (instance == null) return "<null>";
		try {
			Method m = instance.getClass().getMethod(method);
			m.setAccessible(true);
			return m.invoke(instance);
		} catch (Throwable t) {
			Throwable cause = t instanceof InvocationTargetException ite ? ite.getCause() : t;
			return "<threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ">";
		}
	}

	static String describe(Throwable t) {
		if (t == null) return "none";
		Throwable cause = t instanceof InvocationTargetException ite ? ite.getCause() : t;
		return cause.getClass().getName() + ": " + cause.getMessage();
	}

	static List<String> snapshot() { synchronized (logs) { return List.copyOf(logs); } }

	static final class Loader extends ClassLoader {
		private final Map<String, byte[]> classes;
		Loader(Map<String, byte[]> classes) { super(BatchCrossClassProbe.class.getClassLoader()); this.classes = classes; }
		@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	static void summary() { System.out.println("BXP-SUMMARY passed=" + passed + " failed=" + failed); }
}
