package remold.ref;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import remold.RemoldAgent;

/**
 * {@link HeapFieldWatch} 的已知答案校准夹具（在**子 JVM** 里跑，父进程是
 * {@code HeapFieldWatchCalibrationTest}）。
 *
 * <p>为什么必须开子 JVM：哨兵的 {@code ENABLED}/{@code CLASS_NAME}/{@code FIELD_NAME} 是类初始化时读
 * {@code -Dremold.probe.heapwatch.*} 得到的静态终值，且一次真实 {@code redefineClasses} 需要
 * {@link Instrumentation}（{@code :tests:test} 自身不带 {@code -javaagent}）。子 JVM 用与真机完全相同的
 * 开关与同一条 {@code LibTool.getInstances} 通道，因此校准的是**要上真机的那个仪器**，而不是测试里的复刻品。</p>
 *
 * <p>输出是给父进程解析的 {@code KEY=value} 行；哨兵自己的日志行（{@code [HEAPWATCH]}/{@code [HEAPWATCH-DIFF]}）
 * 原样透传到 stdout，父进程同时断言这两条人读日志真的出现过。</p>
 *
 * <p>场景：</p>
 * <ul>
 *   <li>{@code calibration}：12 个实例置 5 个 null → 跨 redefine 前后读数必须是 5；中途回收 3 个（其中 1 个为 null）
 *       并新建 2 个，验证分母差被分成 created/collected，且 {@code newlyNull=0}；最后人为置空 1 个存活实例，
 *       验证 {@code newlyNull=1} 这条告警不是摆设。</li>
 *   <li>{@code control}：同名字段的对照类、一个 null 都没有 → 必须报 0。</li>
 *   <li>{@code absent}：字段名写错 → 必须报 {@code absent=N} 并告警，不得静默报 {@code nullCount=0}。</li>
 * </ul>
 */
public final class HeapWatchCalibrationFixture {

	/** 被测类：可空引用字段 {@code probeField}。 */
	public static final class Target {
		final int id;
		String probeField;

		Target(int id, String probeField) {
			this.id = id;
			this.probeField = probeField;
		}

		/** 业务终局读法：实例自己报告字段是否为 null（不经过反射）。 */
		boolean probeFieldIsNull() {
			return probeField == null;
		}

		String version() {
			return "v1";
		}

		/**
		 * 故意让全部实例共享同一个业务 {@code hashCode}：真机上的类（Mixin/Lombok/record）经常覆盖
		 * {@code equals}/{@code hashCode}，跨切面分组必须按**对象身份**做，不能落到业务 hashCode 上。
		 */
		@Override
		public int hashCode() {
			return 42;
		}

		@Override
		public boolean equals(Object other) {
			return this == other;
		}
	}

	/** 对照类：字段同名、同样可空，但一个都不置 null。 */
	public static final class Control {
		final int id;
		String probeField;

		Control(int id, String probeField) {
			this.id = id;
			this.probeField = probeField;
		}
	}

	/** 强引用登记表：保证被校准的实例在扫描时真的活着。 */
	private static final List<Object> LIVE = new ArrayList<Object>();

	private HeapWatchCalibrationFixture() {
	}

	public static void main(String[] args) throws Exception {
		String scenario = args.length > 0 ? args[0] : "calibration";
		Instrumentation inst = RemoldAgent.getInstrumentation();
		System.out.println("RESULT agent state=" + (inst == null ? "absent" : "attached"));
		if (inst == null) {
			System.out.println("RESULT fatal reason=no-instrumentation");
			return;
		}
		if ("control".equals(scenario)) {
			runControl();
		} else if ("absent".equals(scenario)) {
			runAbsent();
		} else {
			runCalibration(inst);
		}
	}

	private static void runCalibration(Instrumentation inst) throws Exception {
		int[] nullIndexes = {0, 2, 4, 6, 8};
		for (int i = 0; i < 12; i++) {
			LIVE.add(new Target(i, isNullIndex(nullIndexes, i) ? null : "cond-" + i));
		}
		int truthBefore = businessNullCount();
		System.out.println("RESULT truth phase=before-redefine nulls=" + truthBefore + " live=" + LIVE.size());
		System.out.println("RESULT version phase=before-redefine value=" + ((Target) LIVE.get(1)).version());

		HeapFieldWatch.snapshot("before-redefine", Target.class.getName());
		printSnapshot("before-redefine");

		// 分母扰动：回收 3 个（下标 8 是 null、9/10 非空）再新建 2 个非空实例。
		dropAndCollect(10, 9, 8);
		LIVE.add(new Target(100, "cond-100"));
		LIVE.add(new Target(101, "cond-101"));
		System.out.println("RESULT churn collected=3 created=2 live=" + LIVE.size());

		redefineTarget(inst);
		System.out.println("RESULT version phase=after-redefine value=" + ((Target) LIVE.get(1)).version());

		HeapFieldWatch.snapshot("after-redefine", Target.class.getName());
		printSnapshot("after-redefine");
		System.out.println("RESULT truth phase=after-redefine nulls=" + businessNullCount()
			+ " live=" + LIVE.size());

		// 反面对照：把存活实例之一人为置空，跨切面分组必须报 newlyNull=1（这是置空信号的唯一判据）。
		((Target) LIVE.get(1)).probeField = null;
		HeapFieldWatch.snapshot("after-field-patch", Target.class.getName());
		printSnapshot("after-field-patch");
		System.out.println("RESULT truth phase=after-field-patch nulls=" + businessNullCount()
			+ " live=" + LIVE.size());
	}

	private static void runControl() {
		for (int i = 0; i < 9; i++) {
			LIVE.add(new Control(i, "cond-" + i));
		}
		HeapFieldWatch.snapshot("before-redefine", Control.class.getName());
		printSnapshot("before-redefine");
		System.out.println("RESULT truth phase=before-redefine nulls=" + businessNullCount() + " live=" + LIVE.size());
	}

	private static void runAbsent() {
		for (int i = 0; i < 12; i++) {
			LIVE.add(new Target(i, "cond-" + i));
		}
		HeapFieldWatch.snapshot("before-redefine", Target.class.getName());
		printSnapshot("before-redefine");
		System.out.println("RESULT truth phase=before-redefine nulls=" + businessNullCount() + " live=" + LIVE.size());
	}

	private static void printSnapshot(String phase) {
		HeapFieldWatch.Snapshot s = HeapFieldWatch.lastSnapshot();
		if (s == null) {
			System.out.println("RESULT snapshot phase=" + phase + " value=missing");
			return;
		}
		System.out.println("RESULT snapshot phase=" + phase
			+ " total=" + s.total
			+ " nullCount=" + s.nullCount
			+ " absent=" + s.absent
			+ " unreadable=" + s.unreadable);
		String diff = HeapFieldWatch.lastDiff();
		if (diff.isEmpty()) {
			System.out.println("RESULT diff phase=" + phase + " value=none");
		} else {
			System.out.println("RESULT diff phase=" + phase + " value=present");
			System.out.println("DIFF phase=" + phase + " " + diff);
		}
	}

	/** 业务终局：实例自己报告是否为 null（不经过反射，也不经过哨兵）。 */
	private static int businessNullCount() {
		int nulls = 0;
		for (Object instance : LIVE) {
			if (instance instanceof Target && ((Target) instance).probeFieldIsNull()) {
				nulls++;
			} else if (instance instanceof Control && ((Control) instance).probeField == null) {
				nulls++;
			}
		}
		return nulls;
	}

	private static boolean isNullIndex(int[] indexes, int i) {
		for (int index : indexes) {
			if (index == i) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 从 {@link #LIVE} 里摘掉给定下标的实例并确认它们真的被回收：哨兵的分母来自堆枚举，
	 * "掉出堆"与"还在堆里"必须可区分，否则 {@code collected} 分组就是自说自话。
	 */
	private static void dropAndCollect(int... indexes) throws Exception {
		List<WeakReference<Object>> dropped = new ArrayList<WeakReference<Object>>();
		for (int index : indexes) {
			dropped.add(new WeakReference<Object>(LIVE.get(index)));
		}
		// 从大到小移除，避免下标漂移。
		java.util.Arrays.sort(indexes);
		for (int i = indexes.length - 1; i >= 0; i--) {
			LIVE.remove(indexes[i]);
		}
		for (int attempt = 0; attempt < 100; attempt++) {
			System.gc();
			Thread.sleep(20L);
			boolean cleared = true;
			for (WeakReference<Object> ref : dropped) {
				if (ref.get() != null) {
					cleared = false;
					break;
				}
			}
			if (cleared) {
				return;
			}
		}
		throw new IllegalStateException("JVM did not collect " + indexes.length
			+ " unreferenced fixture instance(s); the denominator grouping cannot be calibrated");
	}

	/**
	 * 真实 {@code redefineClasses}：把 {@code Target.version()} 的方法体常量 {@code "v1"} 改成 {@code "v2"}。
	 * 只动方法体、不动 schema，因此标准 HotSpot 也能做；它给"跨 redefine 前后"提供真实的切面边界。
	 */
	private static void redefineTarget(Instrumentation inst) throws Exception {
		byte[] original = readClassBytes(Target.class);
		byte[] patched = patchVersionConstant(original);
		inst.redefineClasses(new java.lang.instrument.ClassDefinition(Target.class, patched));
		System.out.println("RESULT redefine value=ok bytes=" + original.length + "->" + patched.length);
	}

	private static byte[] readClassBytes(Class<?> type) throws Exception {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		InputStream in = type.getResourceAsStream(resource);
		if (in == null) {
			throw new IllegalStateException("cannot read bytes of " + type.getName() + " (resource " + resource + ")");
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		} finally {
			in.close();
		}
	}

	private static byte[] patchVersionConstant(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
											 String signature, String[] exceptions) {
				MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!"version".equals(name)) {
					return delegate;
				}
				return new MethodVisitor(Opcodes.ASM9, delegate) {
					@Override
					public void visitLdcInsn(Object value) {
						super.visitLdcInsn("v1".equals(value) ? "v2" : value);
					}
				};
			}
		}, 0);
		return writer.toByteArray();
	}
}
