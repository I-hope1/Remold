import remold.RemoldAgent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Two questions that decide whether cross-host anonymous-class reuse is feasible at all on JBR/DCEVM.
 *
 * <p><b>Probe A — field descriptor change.</b> On standard HotSpot a redefine that changes a field's descriptor is
 * rejected outright. DCEVM treats it as "delete the old field, add the new one". This probe measures that claim on
 * the real runtime instead of assuming it, and also records what happens to the value already stored in the old
 * instance (the "new field has no value source" problem that gates the whole reuse design).</p>
 *
 * <p><b>Probe B — nest host rewrite.</b> A class moved from one host to another must keep its OLD class name, so its
 * bytes get renamed to the old name while their {@code NestHost} points at the NEW host. The new host must list the
 * old name in {@code NestMembers}. If the JVM refuses the attribute rewrite, or the nest-private access is denied
 * afterwards, cross-host reuse is dead and only the diagnostic (no reuse) path remains.</p>
 *
 * <p>Both experiments run through the production instrumentation instance, so the readings describe the same
 * enhanced-redefinition mode the agent uses.</p>
 */
public class DcevmCrossHostProbe {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "DXH-PASS " : "DXH-FAIL ") + msg);
		if (ok) passed++; else failed++;
	}

	static void note(String msg) {
		System.out.println("DXH-NOTE " + msg);
	}

	// ------------------------------------------------------------------ Probe A fixtures

	private static final String FIELD_CLASS = "dxh/FieldHost";

	/** Class with one instance field; the constructor stores a value into it. */
	private static byte[] fieldHost(String fieldDesc, String boxedValue) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, FIELD_CLASS, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "slot", fieldDesc, null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitLdcInsn(boxedValue);
		init.visitFieldInsn(Opcodes.PUTFIELD, FIELD_CLASS, "slot", fieldDesc);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(2, 1);
		init.visitEnd();
		MethodVisitor peek = cw.visitMethod(Opcodes.ACC_PUBLIC, "peek", "()" + fieldDesc, null, null);
		peek.visitCode();
		peek.visitVarInsn(Opcodes.ALOAD, 0);
		peek.visitFieldInsn(Opcodes.GETFIELD, FIELD_CLASS, "slot", fieldDesc);
		peek.visitInsn(Opcodes.ARETURN);
		peek.visitMaxs(1, 1);
		peek.visitEnd();
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------ Probe B fixtures

	private static final String HOST   = "dxh/Host";
	private static final String NESTED = "dxh/Host$Inner";
	private static final String MOVED  = "dxh/OldAnon";
	private static final String SECRET = "s3cr3t";

	/** A host with a private static method and a nested class admitted as a nest member. */
	private static byte[] nestHost(boolean includeMoved) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST, null, "java/lang/Object", null);
		cw.visitNestMember(NESTED);
		if (includeMoved) cw.visitNestMember(MOVED);
		MethodVisitor secret = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret",
			"()Ljava/lang/String;", null, null);
		secret.visitCode();
		secret.visitLdcInsn(SECRET);
		secret.visitInsn(Opcodes.ARETURN);
		secret.visitMaxs(1, 0);
		secret.visitEnd();
		// 公开入口，便于探针从外部调用（真正的验证点是私有静态方法的跨类访问）。
		MethodVisitor pub = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "expose",
			"()Ljava/lang/String;", null, null);
		pub.visitCode();
		pub.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "secret", "()Ljava/lang/String;", false);
		pub.visitInsn(Opcodes.ARETURN);
		pub.visitMaxs(1, 0);
		pub.visitEnd();
		return cw.toByteArray();
	}

	/** {@code probe()} returns a constant — the "moved implementation" body. */
	private static byte[] plainProbe(String name, String value, String nestHost) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		if (nestHost != null) cw.visitNestHost(nestHost);
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "probe", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(value);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		return cw.toByteArray();
	}

	/** {@code probe()} calls Host's PRIVATE static method — legal only for a nestmate. */
	private static byte[] probingNest(final String name, String nestHost) {
		ClassReader cr = new ClassReader(plainProbe(name, "unused", nestHost));
		ClassWriter cw = new ClassWriter(0);
		cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
			@Override public MethodVisitor visitMethod(int access, String n, String desc, String sig, String[] ex) {
				MethodVisitor mv = super.visitMethod(access, n, desc, sig, ex);
				if (!"probe".equals(n)) return mv;
				return new MethodVisitor(Opcodes.ASM9, mv) {
					@Override public void visitCode() {
						super.visitCode();
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOST, "secret", "()Ljava/lang/String;", false);
						mv.visitInsn(Opcodes.ARETURN);
					}
				};
			}
		}, 0);
		return cw.toByteArray();
	}

	// ------------------------------------------------------------------ driver

	public static void main(String[] args) throws Throwable {
		System.out.println("=== DcevmCrossHostProbe: field descriptor change + nest host rewrite ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null, "P0 instrumentation available");
		AgentFreshness.verify(DcevmCrossHostProbe::check, DcevmCrossHostProbe::note);
		check(inst != null && RemoldAgent.isEnhancedHotswapEnabled(),
			"P0 enhanced redefinition (AllowEnhancedClassRedefinition) is active");
		check(inst != null && inst.isRedefineClassesSupported(), "P0 redefineClasses is supported");
		if (inst == null) { summary(); return; }

		try {
			probeA(inst);
		} catch (Throwable t) {
			check(false, "probe A aborted: " + describe(t));
		}
		try {
			probeB(inst);
		} catch (Throwable t) {
			check(false, "probe B aborted: " + describe(t));
		}
		summary();
	}

	/** Probe A: does an in-place field DESCRIPTOR change get accepted, and what happens to the stored value? */
	static void probeA(Instrumentation inst) throws Exception {
		System.out.println("-- Probe A: field descriptor Object -> String --");
		FixtureLoader loader = new FixtureLoader();
		Class<?> cls = loader.define(FIELD_CLASS, fieldHost("Ljava/lang/Object;", "hello"));
		Object live = cls.getDeclaredConstructor().newInstance();
		Field slot = cls.getField("slot");
		check("hello".equals(slot.get(live)), "A1 baseline: the live instance carries the old value (" + slot.get(live) + ")");

		Throwable rejected = null;
		try {
			inst.redefineClasses(new ClassDefinition(cls, fieldHost("Ljava/lang/String;", "hello")));
		} catch (Throwable t) {
			rejected = t;
		}
		if (rejected != null) {
			check(false, "A2 DCEVM ACCEPTED the field descriptor change (was rejected: " + describe(rejected) + ")");
			note("A-verdict: standard-HotSpot rule still applies -> cross-host this$0 reuse is blocked at the JVM level");
			return;
		}
		check(true, "A2 DCEVM ACCEPTED the field descriptor change (delete + add, no UnsupportedOperationException)");

		// 读回：新字段在新实例里由构造器赋值；存量实例的那一份要么保留要么归零，二选一都会影响"值来源"设计。
		Object fresh = cls.getDeclaredConstructor().newInstance();
		Object freshValue = cls.getField("slot").get(fresh);
		check("hello".equals(freshValue), "A3 a NEW instance gets the value through the new descriptor (" + freshValue + ")");
		Object liveValue;
		try {
			liveValue = cls.getField("slot").get(live);
		} catch (Throwable t) {
			liveValue = "<threw " + describe(t) + ">";
		}
		note("A4 LIVE instance after the descriptor change reads: " + liveValue
			+ "  (null = the old value was dropped with the old field, i.e. a value source is still required)");
		note("A-verdict: descriptor change is accepted by the JVM; the open question is only where the new value comes from");
	}

	/** Probe B: rename a moved class to its old name, point NestHost at the new host, then use a private member. */
	static void probeB(Instrumentation inst) throws Exception {
		System.out.println("-- Probe B: NestHost rewrite + nest-private access across hosts --");
		FixtureLoader loader = new FixtureLoader();
		Class<?> hostCls  = loader.define(HOST, nestHost(false));
		Class<?> movedCls = loader.define(MOVED, plainProbe(MOVED, "old-body", null));
		Object live = movedCls.getDeclaredConstructor().newInstance();
		check("old-body".equals(call(live, "probe")),
			"B1 baseline: the live instance runs its own body (" + call(live, "probe") + ")");
		check(SECRET.equals(hostCls.getMethod("expose").invoke(null)), "B2 baseline: the host's own private path works");

		// 改名 + 换 NestHost：新代码搬进旧类名，同时宿主把旧类名收为 nest member。
		byte[] renamedMoved = probingNest(MOVED, HOST);
		Throwable rejected = null;
		try {
			inst.redefineClasses(
				new ClassDefinition(movedCls, renamedMoved),
				new ClassDefinition(hostCls, nestHost(true))
			);
		} catch (Throwable t) {
			rejected = t;
		}
		if (rejected != null) {
			check(false, "B3 the JVM accepted the NestHost/NestMembers rewrite (was rejected: " + describe(rejected) + ")");
			note("B-verdict: attribute rewrite is refused -> cross-host reuse is dead, keep the diagnostic-only path");
			return;
		}
		check(true, "B3 the JVM ACCEPTED the NestHost/NestMembers rewrite in a single redefine round");

		String after = call(live, "probe");
		check(SECRET.equals(after),
			"B4 the LIVE instance, under the OLD class name, now reaches the NEW host's private member (" + after + ")");
		if (!SECRET.equals(after)) {
			note("B-verdict: nest-private access denied (" + after + ") -> reuse requires the moved code to avoid the host's"
				+ " private members, or the diagnostic-only path");
		} else {
			note("B-verdict: reuse is feasible at the JVM level; the remaining gates are value source + naming conflicts");
		}
	}

	// ------------------------------------------------------------------ helpers

	static String call(Object instance, String method) {
		try {
			Method m = instance.getClass().getMethod(method);
			m.setAccessible(true);
			return String.valueOf(m.invoke(instance));
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

	static final class FixtureLoader extends ClassLoader {
		FixtureLoader() { super(DcevmCrossHostProbe.class.getClassLoader()); }
		Class<?> define(String slashName, byte[] bytes) {
			String dotted = slashName.replace('/', '.');
			return defineClass(dotted, bytes, 0, bytes.length);
		}
	}

	static void summary() {
		System.out.println("DXH-SUMMARY passed=" + passed + " failed=" + failed);
	}
}
