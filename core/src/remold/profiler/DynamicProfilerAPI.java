package remold.profiler;

import remold.RemoldAgent;
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
					System.out.println("捕获到线程 A 的栈帧: " + frame);
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
	public static void toggleEntityProbe(Class<?> baseClass, String methodName, boolean enable) {
		if (enable) {
			ProfilerTransformer.addTargetMethod(baseClass, methodName);
		} else {
			ProfilerTransformer.removeTargetMethod(baseClass, methodName);
		}
		// 获取所有已加载的子类并重转换
		List<Class<?>>  toRetransform = new ArrayList<>();
		Instrumentation inst          = RemoldAgent.getInst();
		for (Class<?> clazz : inst.getAllLoadedClasses()) {
			if (baseClass.isAssignableFrom(clazz) && !clazz.isInterface() && inst.isModifiableClass(clazz)) {
				toRetransform.add(clazz);
			}
		}

		try {
			inst.retransformClasses(toRetransform.toArray(new Class[0]));
			RemoldAgent.info("Retransformed " + toRetransform.size() + " classes for " + methodName);
		} catch (Exception e) {
			RemoldAgent.error("Retransform failed", e);
		}
	}

}