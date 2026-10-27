package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashMap;
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
 * <h2>为什么不能只看直接读取</h2>
 * <p>最常见的写法恰恰不是直接读字段：Lombok 的 {@code @EqualsAndHashCode} 生成的方法优先调用 getter
 * （字节码是 {@code invokevirtual getId()}），手写的 {@code hashCode()} 也常转调一个私有
 * {@code key()} / {@code computeHash()}。只看 {@code GETFIELD} 会把这两种全部漏掉。
 * 因此这里在本类内建一张小调用图：从三个入口方法出发，沿<b>同类</b>的 {@code invoke*} 求可达方法集，
 * 再在集合里找对新增字段的读取。</p>
 *
 * <h2>边界</h2>
 * <ul>
 *   <li><b>只沿同类调用</b>：owner 必须等于本类。跨类调用不跟进——哈希语义的读取通常就在本类，
 *       跟进外部类会把误报面扩到整条依赖链。</li>
 *   <li><b>保守命中</b>：getter 常是 public 且可被覆写，这里不区分是否被覆写，一律按命中处理，宁可多告警。</li>
 *   <li><b>只看新增的实例字段</b>：静态字段不参与实例的哈希/相等语义。</li>
 *   <li><b>不判断容器是否真的存在</b>：只做静态分析，运行时有没有实例进过哈希容器无从得知。</li>
 * </ul>
 */
public final class HashSemantics {

	/**
	 * 一个「某个哈希语义方法经某条路径读了这个新增字段」的发现。
	 * @param entry  入口方法（{@code hashCode} / {@code equals} / {@code compareTo}）
	 * @param method 实际读取字段的方法；等于 {@code entry} 表示直接读取
	 * @param field  被读取的新增字段
	 */
	public record Finding(String entry, String method, String field) { }

	private HashSemantics() { }

	/**
	 * 扫描新类，找出被哈希/相等语义方法（含其同类调用链）读取的新增实例字段。
	 *
	 * @param classBytes          新类字节码
	 * @param addedInstanceFields 本轮新增的实例字段名
	 * @return 命中的（入口, 读取方法, 字段）组合；空表示没有发现
	 */
	public static List<Finding> findDependencies(byte[] classBytes, Set<String> addedInstanceFields) {
		Objects.requireNonNull(classBytes, "classBytes");
		if (addedInstanceFields == null || addedInstanceFields.isEmpty()) return List.of();

		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		Map<String, MethodNode> byKey = new HashMap<>();
		for (MethodNode mn : cn.methods) byKey.put(mn.name + mn.desc, mn);

		Set<Finding> findings = new LinkedHashSet<>();
		for (MethodNode seed : cn.methods) {
			if (!isHashSemanticMethod(seed)) continue;
			for (MethodNode reachable : reachableFrom(cn.name, seed, byKey)) {
				for (String field : fieldsRead(cn.name, reachable)) {
					if (addedInstanceFields.contains(field)) {
						findings.add(new Finding(seed.name, reachable.name, field));
					}
				}
			}
		}
		return new ArrayList<>(findings);
	}

	/**
	 * 从入口方法出发、沿同类 {@code invoke*} 求可达方法集（含入口自身）。
	 *
	 * <p>用显式队列而不是递归：方法之间可以互相调用（{@code a()→b()→a()}），递归会栈溢出。
	 * {@code visited} 同时承担去重与终止两件事。</p>
	 */
	private static Set<MethodNode> reachableFrom(String className, MethodNode seed, Map<String, MethodNode> byKey) {
		Set<MethodNode> visited = new LinkedHashSet<>();
		Deque<MethodNode> queue = new ArrayDeque<>();
		queue.add(seed);
		visited.add(seed);

		while (!queue.isEmpty()) {
			MethodNode current = queue.poll();
			for (AbstractInsnNode n : current.instructions) {
				if (!(n instanceof MethodInsnNode m)) continue;
				if (!m.owner.equals(className)) continue;
				MethodNode callee = byKey.get(m.name + m.desc);
				if (callee == null || !visited.add(callee)) continue;
				queue.add(callee);
			}
		}
		return visited;
	}

	/** 该方法直接读取的本类实例字段名。 */
	private static Set<String> fieldsRead(String className, MethodNode mn) {
		Set<String> fields = new LinkedHashSet<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (!(n instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.GETFIELD) continue;
			if (f.owner.equals(className)) fields.add(f.name);
		}
		return fields;
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

	/** 供日志使用的可读描述，例如 {@code hashCode() 经 getId() 读取了新增字段 'id'}。 */
	public static String describe(List<Finding> findings) {
		StringBuilder sb = new StringBuilder();
		for (Finding f : findings) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(f.entry()).append("()");
			if (!f.entry().equals(f.method())) sb.append(" 经 ").append(f.method()).append("()");
			sb.append(" 读取了新增字段 '").append(f.field()).append('\'');
		}
		return sb.toString();
	}
}
