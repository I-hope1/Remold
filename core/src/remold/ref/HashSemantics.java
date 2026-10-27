package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 哈希语义依赖检测（{@code docs/status.md} 风险 10）。
 *
 * <p>热更新把新增字段补进存量实例后，如果 {@code hashCode()} / {@code equals()} / {@code compareTo()}
 * 引用了该字段，已经放进 {@code HashMap} / {@code HashSet} / {@code TreeMap} 的实例会落到错误的桶或
 * 比较位置，{@code get} / {@code contains} / {@code remove} 静默失效——不抛异常，只是查不到。</p>
 *
 * <p><b>为什么这是门控问题而不是补丁问题</b>：破坏的成因是「哈希公式变了」，不是「补丁写了什么值」。
 * 哪怕新字段的初值是类型默认值，只要公式里出现它，旧容器就已经对不上了；因此拒绝补丁并不能修复，
 * 只会让新代码读到 0。检测的用途是<b>在热更前把这件事告诉用户</b>，由用户决定重建容器或重启。</p>
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>只看<b>直接</b>读取：方法体里直接出现 {@code GETFIELD this.<新增字段>}。经辅助方法间接读取
 *       不在检测范围内（与 {@code 01-safety-gate.md} §6「间接依赖读脏」同一条边界）。</li>
 *   <li>只看新增的<b>实例</b>字段：静态字段不参与实例的哈希/相等语义（静态字段变的是类的状态，
 *       不是对象的哈希）。</li>
 *   <li>不判断容器是否真的存在：本类只做静态分析，运行时有没有实例进过哈希容器无从得知。</li>
 * </ul>
 */
public final class HashSemantics {

	/** 一个「方法读了这个新增字段」的发现。 */
	public record Finding(String method, String field) { }

	private HashSemantics() { }

	/**
	 * 扫描新类，找出被哈希/相等语义方法直接读取的新增实例字段。
	 *
	 * @param classBytes            新类字节码
	 * @param addedInstanceFields   本轮新增的实例字段名
	 * @return 命中的（方法, 字段）组合；空表示没有发现
	 */
	public static List<Finding> findDependencies(byte[] classBytes, Set<String> addedInstanceFields) {
		Objects.requireNonNull(classBytes, "classBytes");
		if (addedInstanceFields == null || addedInstanceFields.isEmpty()) return List.of();

		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		List<Finding> findings = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!isHashSemanticMethod(mn)) continue;
			for (AbstractInsnNode n : mn.instructions) {
				if (!(n instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETFIELD) continue;
				if (!f.owner.equals(cn.name)) continue;
				if (!addedInstanceFields.contains(f.name)) continue;
				findings.add(new Finding(mn.name, f.name));
			}
		}
		return findings;
	}

	/**
	 * 是否是参与哈希/相等/排序语义的方法。
	 *
	 * <p>{@code compareTo} 的形参类型随 {@code Comparable<T>} 而变，因此只校验「一个引用参数、返回 int」。</p>
	 */
	private static boolean isHashSemanticMethod(MethodNode mn) {
		if ("hashCode".equals(mn.name) && "()I".equals(mn.desc)) return true;
		if ("equals".equals(mn.name) && "(Ljava/lang/Object;)Z".equals(mn.desc)) return true;
		return "compareTo".equals(mn.name) && mn.desc.startsWith("(L") && mn.desc.endsWith(")I");
	}

	/** 供日志使用的可读描述，例如 {@code hashCode() 读取了新增字段 'id'}。 */
	public static String describe(List<Finding> findings) {
		StringBuilder sb = new StringBuilder();
		for (Finding f : findings) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(f.method()).append("() 读取了新增字段 '").append(f.field()).append('\'');
		}
		return sb.toString();
	}
}
