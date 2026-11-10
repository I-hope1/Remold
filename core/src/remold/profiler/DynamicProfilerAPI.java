package remold.profiler;

import remold.RemoldAgent;
import remold.util.ClassIdentity;
import org.objectweb.asm.*;

import java.lang.instrument.*;
import java.util.*;

public class DynamicProfilerAPI {
	public static final ProfilerTransformer transformer = new ProfilerTransformer();

	public static void init() {
		try {
			Instrumentation inst = RemoldAgent.getInst();
			inst.addTransformer(transformer, true);
		} catch (Throwable t) {
			RemoldAgent.error("Failed to initialize DynamicProfilerAPI", t);
		}
	}
	static void loadWalker() throws Exception {
		Instrumentation inst   = RemoldAgent.getInst();
		Class<?>        Walker = Class.forName("java.lang.StackStreamFactory$AbstractStackWalker");
		byte[]          bytes  = RemoldAgent.fetchOriginalBytecode(Walker);
		if (bytes == null) {
			RemoldAgent.error("Failed to fetch original bytecode for " + Walker.getName());
			return;
		}
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
		cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
			                                 String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				// 目标：精准打击 checkState 方法
				// 它的签名通常是 void checkState() -> ()V
				if ("checkState".equals(name) && "()V".equals(descriptor)) {
					RemoldAgent.info("Clear " + Walker.getName() + "#checkState()");
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitCode() {
							mv.visitCode();
							mv.visitInsn(Opcodes.RETURN); // 只插入这一句
							mv.visitMaxs(0, 0);          // 剩下的交给 COMPUTE_FRAMES
							mv.visitEnd();
						}
						@Override
						public void visitInsn(int opcode) { /* 拦截所有原指令，什么都不做 */ }
						@Override
						public void visitVarInsn(int opcode, int var) { /* 忽略 */ }
						@Override
						public void visitFieldInsn(int op, String owner, String name, String desc) { /* 忽略 */ }
					};
				}
				return mv;
			}
		}, 0);
		bytes = cw.toByteArray();
		RemoldAgent.enterInternalRedefine(Collections.singleton(Walker.getName()));
		try {
			inst.redefineClasses(new ClassDefinition(Walker, bytes));
		} finally {
			RemoldAgent.exitInternalRedefine();
		}

		StackWalker internalWalker = StackWalker.getInstance(
		 StackWalker.Option.RETAIN_CLASS_REFERENCE
		);
		new Thread(() -> {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			internalWalker.walk(s -> {
				s.forEach(frame -> {
					System.out.println("[Profiler] Captured stack frame of Thread A: " + frame);
				});
				return null;
			});
		}).start();
	}
	/**
	 * @param baseClass  基类，如 Building.class, Unit.class
	 * @param methodName 方法名
	 * @param enable     开启或关闭
	 */
	public static boolean toggleEntityProbe(Class<?> baseClass, String methodName, boolean enable) {
		Instrumentation inst = RemoldAgent.getInst();
		if (inst == null) {
			RemoldAgent.warn("[Profiler] Instrumentation unavailable; entity probe retransform skipped.");
			return false;
		}
		return toggleEntityProbe(inst, baseClass, methodName, enable);
	}

	/**
	 * Failure-safe toggle: registers/removes the target, retransforms, and rolls back on any
	 * {@link Throwable} (including {@link ClassFormatError}) so a failed enable never leaves a
	 * half-registered target in {@link ProfilerTransformer#targetMethods}.
	 */
	static boolean toggleEntityProbe(Instrumentation inst, Class<?> baseClass, String methodName, boolean enable) {
		baseClass = ClassIdentity.canonicalize(baseClass);
		if (baseClass == null || methodName == null) return false;
		Set<String> prev = ProfilerTransformer.targetMethods.get(baseClass);
		Set<String> prevSnapshot = prev == null ? null : new HashSet<>(prev);
		if (enable) {
			ProfilerTransformer.addTargetMethod(baseClass, methodName);
		} else {
			ProfilerTransformer.removeTargetMethod(baseClass, methodName);
		}
		try {
			retransformForProbe(inst, baseClass, methodName);
			return true;
		} catch (Throwable e) {
			RemoldAgent.error("Retransform failed for " + baseClass.getName() + "#" + methodName, e);
			if (enable) {
				// enable failed: restore the pre-call registration exactly
				if (prevSnapshot == null || prevSnapshot.isEmpty()) {
					ProfilerTransformer.targetMethods.remove(baseClass);
				} else {
					ProfilerTransformer.targetMethods.put(baseClass, prevSnapshot);
				}
			} else {
				// disable failed: the probe bytecode is still live, so bookkeeping must keep it
				ProfilerTransformer.addTargetMethod(baseClass, methodName);
			}
			return false;
		}
	}

	/** Test seam (kept for existing callers): retransform only, never throws. */
	static void toggleEntityProbe(Instrumentation inst, Class<?> baseClass, String methodName) {
		try {
			retransformForProbe(inst, baseClass, methodName);
		} catch (Throwable e) {
			RemoldAgent.error("Retransform failed", e);
		}
	}

	private static void retransformForProbe(Instrumentation inst, Class<?> baseClass, String methodName)
	 throws Exception {
		baseClass = ClassIdentity.canonicalize(baseClass);
		// 获取所有已加载的子类并重转换
		Map<String, Class<?>> byName = new java.util.LinkedHashMap<>();
		for (Class<?> clazz : inst.getAllLoadedClasses()) {
			if (!baseClass.isAssignableFrom(clazz) || clazz.isInterface() || !inst.isModifiableClass(clazz)) continue;
			Class<?> previous = byName.get(clazz.getName());
			byName.put(clazz.getName(), remold.ClassCopySelector.prefer(previous, clazz));
		}
		List<Class<?>> toRetransform = new ArrayList<>(byName.values());
		if (toRetransform.isEmpty()) {
			RemoldAgent.info("No loaded subclasses to retransform for " + methodName);
			return;
		}
		Set<String> names = new HashSet<>();
		for (Class<?> clazz : toRetransform) {
			names.add(clazz.getName());
		}

		RemoldAgent.enterInternalRetransform(names);
		try {
			inst.retransformClasses(toRetransform.toArray(new Class[0]));
			RemoldAgent.info("Retransformed " + toRetransform.size() + " classes for " + methodName);
		} finally {
			RemoldAgent.exitInternalRedefine();
		}
	}
}