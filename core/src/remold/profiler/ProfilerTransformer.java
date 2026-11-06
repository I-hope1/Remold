package remold.profiler;

import remold.*;
import remold.util.ClassIdentity;
import remold.AnnotationTransformer.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

public class ProfilerTransformer implements ClassFileTransformer {
	public static final Map<Class<?>, Set<String>> targetMethods = new java.util.concurrent.ConcurrentHashMap<>();

	public static void addTargetMethods(Class<?> clazz, String... methodNames) {
		targetMethods.computeIfAbsent(ClassIdentity.canonicalize(clazz), k -> new HashSet<>())
		 .addAll(Arrays.asList(methodNames));
	}
	public static void addTargetMethod(Class<?> clazz, String methodName) {
		targetMethods.computeIfAbsent(ClassIdentity.canonicalize(clazz), k -> new HashSet<>()).add(methodName);
	}
	public static void clearTargetMethods(Class<?> clazz) {
		targetMethods.remove(ClassIdentity.canonicalize(clazz));
	}
	public static void removeTargetMethod(Class<?> clazz,
	                                      String n) {
		Class<?> canonical = ClassIdentity.canonicalize(clazz);
		targetMethods.computeIfPresent(canonical, (k, set) -> {
			set.remove(n);
			return set.isEmpty() ? null : set;
		});
	}
	public static void removeTargetMethods(Class<?> clazz, String... ns) {
		Class<?> canonical = ClassIdentity.canonicalize(clazz);
		targetMethods.computeIfPresent(canonical, (k, set) -> {
			for (String n : ns) set.remove(n);
			return set.isEmpty() ? null : set;
		});
	}
	public static boolean hasTargetMethod(Class<?> clazz, String n) {
		Set<String> set = targetMethods.get(ClassIdentity.canonicalize(clazz));
		return set != null && set.contains(n);
	}

	/**
	 * A shadow Class object (JBR rewrite of a static-field Class reference) has the same name and
	 * loader as the canonical one but is a different reference. Match it by name+loader so a
	 * stale key cannot silently disable profiling.
	 */
	/**
	 * Forensic dump for the ClassFormatError investigation (opt-in):
	 * {@code -Dremold.profiler.dump.dir=<dir>} writes in/out bytes and logs duplicate members.
	 */
	private static void dump(String className, byte[] in, byte[] out, boolean profiled) {
		String dir = System.getProperty("remold.profiler.dump.dir");
		if (dir == null || dir.isEmpty()) return;
		try {
			java.io.File d = new java.io.File(dir);
			if (!d.exists()) d.mkdirs();
			String simple = className.replace('/', '_');
			java.nio.file.Files.write(new java.io.File(d, simple + "-in.class").toPath(), in);
			java.nio.file.Files.write(new java.io.File(d, simple + "-out.class").toPath(), out);
			RemoldAgent.info("[PROFILER-DUMP] " + className + " profiled=" + profiled
				+ " in=" + in.length + " out=" + out.length
				+ " dupMembers=" + duplicateMembers(out));
		} catch (Throwable t) {
			RemoldAgent.warn("[PROFILER-DUMP] failed for " + className + ": " + t);
		}
	}

	/** Counts duplicate field/method name+descriptor pairs in the produced class file. */
	private static String duplicateMembers(byte[] bytes) {
		try {
			java.util.Set<String> seen = new java.util.HashSet<>();
			java.util.List<String> dups = new java.util.ArrayList<>();
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public org.objectweb.asm.FieldVisitor visitField(int access, String name,
				                                                  String descriptor, String signature,
				                                                  Object value) {
					if (!seen.add("F:" + name + ":" + descriptor)) dups.add("F:" + name + ":" + descriptor);
					return null;
				}
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
				                                 String signature, String[] exceptions) {
					if (!seen.add("M:" + name + ":" + descriptor)) dups.add("M:" + name + ":" + descriptor);
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return dups.toString();
		} catch (Throwable t) {
			return "scan-failed:" + t;
		}
	}

	private static boolean sameClassIdentity(Class<?> a, Class<?> b) {
		return a == b || (a != null && b != null
			&& a.getName().equals(b.getName())
			&& a.getClassLoader() == b.getClassLoader());
	}

	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		Set<String> matchedMethods = null;

		for (var entry : targetMethods.entrySet()) {
			Set<String> methodNames = entry.getValue();
			if (methodNames == null) continue;

			Class<?> clazz = entry.getKey();

			boolean isMatched = (classBeingRedefined != null)
			 ? (clazz.isAssignableFrom(classBeingRedefined)
			    || sameClassIdentity(clazz, classBeingRedefined)
			    || (className != null && HierarchyTree.isAssignableFrom(
			        AnnotationTransformer.internalName(clazz), className, loader)))
			 : HierarchyTree.isAssignableFrom(AnnotationTransformer.internalName(clazz), className, loader);

			if (isMatched) {
				matchedMethods = methodNames;
				break;
			}
		}

		if (matchedMethods == null) return null;

		// All state is local: a single shared ProfilerTransformer instance can be re-entered
		// (ClassWriter.getCommonSuperClass may load classes, which triggers another transform)
		// or called concurrently. Instance fields here used to be clobbered, producing
		// NullPointerException / ClassFormatError in Forge/ModLauncher retransforms.
		final ClassReader classReader = new ClassReader(classfileBuffer);
		final ClassWriter classWriter = new AnnotationTransformer.MyClassWriter(classReader, loader);
		final Set<String> targetMethodNames = matchedMethods;
		final boolean[] anyProfiled = {false};

		var cv = new ClassVisitor(Opcodes.ASM9, classWriter) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (name.startsWith("<") ||
				    (access & Opcodes.ACC_SYNTHETIC) != 0 ||
				    (access & Opcodes.ACC_BRIDGE) != 0) { return mv; }

				final String simpleClass = className.substring(className.lastIndexOf('/') + 1);
				final String methodKey   = simpleClass + "." + name;

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					final boolean isProfiled = targetMethodNames.contains(name);
					int startTimeVar;
					int durationVar;

					@Override
					protected void onMethodEnter() {
						if (!isProfiled) return;
						anyProfiled[0] = true;
						startTimeVar = newLocal(Type.LONG_TYPE);
						durationVar = newLocal(Type.LONG_TYPE);

						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LSTORE, startTimeVar);

						visitLdcInsn(methodKey);
						visitMethodInsn(INVOKESTATIC, "remold/profiler/ProfilerData",
						 "recordEntry", "(Ljava/lang/String;)V", false);
					}

					@Override
					protected void onMethodExit(int opcode) {
						if (!isProfiled) return;

						if (opcode == ATHROW) {
							visitLdcInsn(methodKey);
							visitMethodInsn(INVOKESTATIC, "remold/profiler/ProfilerData",
							 "recordCancel", "(Ljava/lang/String;)V", false);
							return;
						}

						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LLOAD, startTimeVar);
						visitInsn(LSUB);
						visitVarInsn(LSTORE, durationVar);

						visitLdcInsn(methodKey);
						visitVarInsn(LLOAD, durationVar);
						visitMethodInsn(INVOKESTATIC, "remold/profiler/ProfilerData",
						 "recordExit", "(Ljava/lang/String;J)V", false);
					}
				};
			}
		};

		try {
			classReader.accept(cv, ClassReader.EXPAND_FRAMES);
			byte[] out = anyProfiled[0] ? classWriter.toByteArray() : classfileBuffer;
			dump(className, classfileBuffer, out, anyProfiled[0]);
			return out;
		} catch (Throwable e) {
			RemoldAgent.error("Profiler injection failed for " + className, e);
			return null;
		}
	}
}
