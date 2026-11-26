import remold.RemoldAgent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Field-type migration matrix, BARE redefine column (no Remold pipeline, no patch, no InitFix).
 *
 * <p>Question the matrix answers: when a field's declared type changes, what does DCEVM leave in the slot of an
 * existing instance? The first dimension is <b>the actual class of the stored value vs the new declared type</b>, not
 * the declared-type relation: DCEVM can only look at the object it finds in the slot.</p>
 *
 * <p>Each cell reports three readings: what {@code getfield} yields, what a raw reflective read yields, and what
 * happens when the value is actually <b>consumed</b> (a {@code checkcast} to the new type followed by a call) - a
 * reference that merely sits there is not observable until something uses it.</p>
 */
public class FieldTypeMatrixProbe {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "FTM-PASS " : "FTM-FAIL ") + msg);
		if (ok) passed++; else failed++;
	}

	/** One cell: value class, declared type before, declared type after, expected verdict. */
	record Cell(String name, String valueClass, String before, String after, boolean assignable) { }

	static final List<Cell> CELLS = List.of(
		new Cell("same-class",     "java/lang/String",       "Ljava/lang/Object;", "Ljava/lang/String;", true),
		new Cell("unrelated",      "java/lang/Integer",      "Ljava/lang/Object;", "Ljava/lang/String;", false),
		new Cell("subclass",       "java/lang/Integer",      "Ljava/lang/Object;", "Ljava/lang/Number;", true),
		new Cell("superclass",     "java/util/concurrent/atomic/AtomicInteger", "Ljava/lang/Number;", "Ljava/lang/Integer;", false),
		new Cell("new-interface",  "java/util/ArrayList",    "Ljava/lang/Object;", "Ljava/util/List;",   true),
		new Cell("null-value",     null,                     "Ljava/lang/Object;", "Ljava/lang/String;", true)
	);

	private static final String CLS = "ftm/Slot";

	/** Builds the class: field of {@code declared}, constructor stores either a fresh instance of {@code valueClass} or null. */
	private static byte[] fixture(String declared, String valueClass) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CLS, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "slot", declared, null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		if (valueClass == null) {
			init.visitInsn(Opcodes.ACONST_NULL);
		} else if ("java/lang/String".equals(valueClass)) {
			init.visitLdcInsn("payload");
		} else {
			init.visitTypeInsn(Opcodes.NEW, valueClass);
			init.visitInsn(Opcodes.DUP);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, valueClass, "<init>", "()V", false);
		}
		init.visitFieldInsn(Opcodes.PUTFIELD, CLS, "slot", declared);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(3, 1);
		init.visitEnd();
		// getfield 读法
		MethodVisitor peek = cw.visitMethod(Opcodes.ACC_PUBLIC, "peek", "()Ljava/lang/Object;", null, null);
		peek.visitCode();
		peek.visitVarInsn(Opcodes.ALOAD, 0);
		peek.visitFieldInsn(Opcodes.GETFIELD, CLS, "slot", declared);
		peek.visitInsn(Opcodes.ARETURN);
		peek.visitMaxs(1, 1);
		peek.visitEnd();
		// 真正消费：checkcast 到新类型并调用 hashCode()（错类型引用在这里才会炸）
		MethodVisitor use = cw.visitMethod(Opcodes.ACC_PUBLIC, "consume", "(Ljava/lang/Class;)I", null, null);
		use.visitCode();
		use.visitVarInsn(Opcodes.ALOAD, 0);
		use.visitFieldInsn(Opcodes.GETFIELD, CLS, "slot", declared);
		use.visitVarInsn(Opcodes.ALOAD, 1);
		use.visitInsn(Opcodes.SWAP);
		use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "cast",
			"(Ljava/lang/Object;)Ljava/lang/Object;", false);
		use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
		use.visitInsn(Opcodes.IRETURN);
		use.visitMaxs(4, 2);
		use.visitEnd();
		return cw.toByteArray();
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("=== FieldTypeMatrixProbe: bare DCEVM redefine, who writes what into a migrated slot ===");
		Instrumentation inst = RemoldAgent.getInstrumentation();
		check(inst != null && RemoldAgent.isEnhancedHotswapEnabled(),
			"P0 enhanced redefinition is active");
		if (inst == null) { summary(); return; }
		int index = 0;
		for (Cell cell : CELLS) {
			runCell(inst, cell, "ftm/Slot" + (index++));
		}
		summary();
	}

	static void runCell(Instrumentation inst, Cell cell, String className) throws Throwable {
		// 每格独立类名，避免上一格的字节残留影响
		byte[] v1 = fixtureFor(className, cell.before(), cell.valueClass());
		Loader loader = new Loader();
		Class<?> cls = loader.define(className.replace('/', '.'),
			new ClassVisitorRename(className, CLS).apply(v1));
		Object survivor = cls.getDeclaredConstructor().newInstance();
		Object before = call(survivor, "peek");
		Field slot = cls.getField("slot");
		Object beforeReflective = slot.get(survivor);

		byte[] v2 = new ClassVisitorRename(className, CLS).apply(fixtureFor(className, cell.after(), cell.valueClass()));
		Throwable err = null;
		try {
			inst.redefineClasses(new ClassDefinition(cls, v2));
		} catch (Throwable t) {
			err = t;
		}
		String label = cell.name() + " value=" + simple(cell.valueClass()) + " " + cell.before() + " -> " + cell.after();
		if (err != null) {
			check(false, label + " | redefine REJECTED: " + err.getClass().getSimpleName() + ": " + err.getMessage());
			return;
		}
		Object afterGetfield = call(survivor, "peek");
		Object afterReflective;
		try {
			afterReflective = cls.getField("slot").get(survivor);
		} catch (Throwable t) {
			afterReflective = "<threw " + t.getClass().getSimpleName() + ">";
		}
		String consume = consume(survivor, cls.getField("slot").getType());
		System.out.println("FTM-CELL " + label
			+ " | before=" + describe(before) + "/" + describe(beforeReflective)
			+ " | after.getfield=" + describe(afterGetfield)
			+ " | after.reflect=" + describe(afterReflective)
			+ " | consume=" + consume);

		// 假设（待矩阵确认）：可赋值 → 保留；不可赋值 → DCEVM 自己置 null。
		if (cell.assignable()) {
			check(describe(afterGetfield).equals(describe(before)) || (cell.valueClass() == null && afterGetfield == null),
				label + ": an ASSIGNABLE value is expected to survive the migration");
		} else {
			check(afterGetfield == null,
				label + ": a NON-assignable value is expected to be gone (null), not left behind");
		}
	}

	/** 消费：把值 cast 到新类型再调 hashCode；记录结果或抛出的异常类型。 */
	static String consume(Object instance, Class<?> newType) {
		try {
			Method m = instance.getClass().getMethod("consume", Class.class);
			m.setAccessible(true);
			return String.valueOf(m.invoke(instance, newType));
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
			return "<threw " + cause.getClass().getSimpleName() + ">";
		}
	}

	static Object call(Object instance, String method) {
		try {
			Method m = instance.getClass().getMethod(method);
			m.setAccessible(true);
			return m.invoke(instance);
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
			return "<threw " + cause.getClass().getSimpleName() + ">";
		}
	}

	/** 直接按给定类名和字段描述符生成夹具（不重命名）。 */
	static byte[] fixtureFor(String className, String declared, String valueClass) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "slot", declared, null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		if (valueClass == null) {
			init.visitInsn(Opcodes.ACONST_NULL);
		} else if ("java/lang/String".equals(valueClass)) {
			init.visitLdcInsn("payload");
		} else if ("java/lang/Integer".equals(valueClass)) {
			// Integer 没有无参构造器：走 valueOf
			init.visitIntInsn(Opcodes.BIPUSH, 7);
			init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
				"(I)Ljava/lang/Integer;", false);
		} else {
			init.visitTypeInsn(Opcodes.NEW, valueClass);
			init.visitInsn(Opcodes.DUP);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, valueClass, "<init>", "()V", false);
		}
		init.visitFieldInsn(Opcodes.PUTFIELD, className, "slot", declared);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(3, 1);
		init.visitEnd();
		MethodVisitor peek = cw.visitMethod(Opcodes.ACC_PUBLIC, "peek", "()Ljava/lang/Object;", null, null);
		peek.visitCode();
		peek.visitVarInsn(Opcodes.ALOAD, 0);
		peek.visitFieldInsn(Opcodes.GETFIELD, className, "slot", declared);
		peek.visitInsn(Opcodes.ARETURN);
		peek.visitMaxs(1, 1);
		peek.visitEnd();
		MethodVisitor use = cw.visitMethod(Opcodes.ACC_PUBLIC, "consume", "(Ljava/lang/Class;)I", null, null);
		use.visitCode();
		use.visitVarInsn(Opcodes.ALOAD, 1);
		use.visitVarInsn(Opcodes.ALOAD, 0);
		use.visitFieldInsn(Opcodes.GETFIELD, className, "slot", declared);
		use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "cast",
			"(Ljava/lang/Object;)Ljava/lang/Object;", false);
		use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
		use.visitInsn(Opcodes.IRETURN);
		use.visitMaxs(2, 2);
		use.visitEnd();
		return cw.toByteArray();
	}

	/** 重命名夹具：把内部名与字段 owner 换成唯一类名。 */
	static final class ClassVisitorRename {
		private final String from;
		private final String to;
		ClassVisitorRename(String from, String to) { this.from = from; this.to = to; }
		byte[] apply(byte[] bytes) {
			org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
			ClassWriter cw = new ClassWriter(0);
			cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) { }, 0);
			return cw.toByteArray();
		}
	}

	static String simple(String internal) {
		if (internal == null) return "null";
		int i = internal.lastIndexOf('/');
		return i < 0 ? internal : internal.substring(i + 1);
	}

	static String describe(Object value) {
		if (value == null) return "null";
		if (value instanceof String s) return "str:" + s;
		if (value instanceof Throwable) return value.toString();
		if (value instanceof String) return value.toString();
		return value.getClass().getSimpleName();
	}

	static final class Loader extends ClassLoader {
		Loader() { super(FieldTypeMatrixProbe.class.getClassLoader()); }
		Class<?> define(String dotted, byte[] bytes) { return defineClass(dotted, bytes, 0, bytes.length); }
	}

	static void summary() {
		System.out.println("FTM-SUMMARY passed=" + passed + " failed=" + failed);
	}
}
