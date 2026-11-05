package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code redefineDynamic} 的单类布局判决接线。
 *
 * <p>target 自己必须算作“本批”：读者扫描只能用它的<b>新</b>字节，不能把磁盘上的旧版本
 * 当成批次外读者。本用例在临时目录里生成一个旧版本（字段 + 自读方法）和一个新版本（删字段），
 * 先用无外部读者的目录断言 WAIVE_NO_READERS，再写入一个真实读者断言 REJECT。</p>
 *
 * <p>若把 {@code decideDynamicLayout} 里的 {@code Map.of(className, runtimeBytes)} 改成空批次，
 * 第一段会因旧 target 被当成读者而变红——这正是“inBatch 必须包含 target”的回归。</p>
 */
public class DynamicGateWiringTest {

	@Test
	void targetInBatchIsNotTreatedAsItsOwnReader() throws Exception {
		Path root = Files.createTempDirectory("remold-gate-");
		URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
		String internal = "probe/GateTarget";
		String dotted = "probe.GateTarget";
		try {
			byte[] oldBytes = target(true);
			write(root, internal, oldBytes);
			Class<?> target = loader.loadClass(dotted);
			RemoldAgent.bytecodeCache.put(dotted, oldBytes);

			byte[] newBytes = target(false);
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytes, newBytes);

			RemoldAgent.LayoutDecision withoutReader = decide(target, dotted, diff, newBytes);
			assertEquals(RemoldAgent.LayoutAction.WAIVE_NO_READERS, withoutReader.action(),
				"target itself is in-batch; its old self-read must not count as an out-of-batch reader");

			write(root, "probe/GateReader", reader());
			RemoldAgent.LayoutDecision withReader = decide(target, dotted, diff, newBytes);
			assertEquals(RemoldAgent.LayoutAction.REJECT, withReader.action(),
				"a real out-of-batch reader must keep the field removal blocked");
		} finally {
			loader.close();
			RemoldAgent.bytecodeCache.remove(dotted);
			deleteRecursively(root);
		}
	}

	private static RemoldAgent.LayoutDecision decide(Class<?> target, String name,
	                                                  ClassDiffUtil.ClassDiff diff, byte[] newBytes)
		throws Exception {
		Method method = RemoldAgent.class.getDeclaredMethod("decideDynamicLayout",
			Class.class, String.class, ClassDiffUtil.ClassDiff.class, byte[].class);
		method.setAccessible(true);
		return (RemoldAgent.LayoutDecision) method.invoke(null, target, name, diff, newBytes);
	}

	private static byte[] target(boolean withField) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/GateTarget", null, "java/lang/Object", null);
		if (withField) {
			cw.visitField(Opcodes.ACC_PUBLIC, "gone", "I", null, null).visitEnd();
		}
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(1, 1);
		ctor.visitEnd();

		MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC, "read", "()I", null, null);
		read.visitCode();
		if (withField) {
			read.visitVarInsn(Opcodes.ALOAD, 0);
			read.visitFieldInsn(Opcodes.GETFIELD, "probe/GateTarget", "gone", "I");
		} else {
			read.visitInsn(Opcodes.ICONST_0);
		}
		read.visitInsn(Opcodes.IRETURN);
		read.visitMaxs(1, 1);
		read.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] reader() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/GateReader", null, "java/lang/Object", null);
		MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			"read", "(Lprobe/GateTarget;)I", null, null);
		read.visitCode();
		read.visitVarInsn(Opcodes.ALOAD, 0);
		read.visitFieldInsn(Opcodes.GETFIELD, "probe/GateTarget", "gone", "I");
		read.visitInsn(Opcodes.IRETURN);
		read.visitMaxs(1, 1);
		read.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void write(Path root, String internalName, byte[] bytes) throws Exception {
		Path file = root.resolve(internalName + ".class");
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static void deleteRecursively(Path root) throws Exception {
		try (var walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
