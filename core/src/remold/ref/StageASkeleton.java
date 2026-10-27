package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 阶段 A 骨架合成（{@code docs/initfix/04-target-design.md} §1「阶段 A（骨架先行）」）。
 *
 * <p>阶段 A 的契约是：<b>行为不变，只让新结构就位</b>。新字段声明进类、新方法/新 lambda/新内部类
 * 预注入但休眠（旧代码没有调用点），而<b>所有旧方法的执行体原样保留</b>——旧逻辑绝不读新字段，
 * 于是阶段 B 可以慢慢补实例而不产生读穿。</p>
 *
 * <p>实现上不是「复制旧方法体」，而是反过来：以新类为骨架，把旧方法的执行体<b>搬</b>进来。
 * 旧 {@link ClassNode} 用完即弃，所以搬运不需要深拷贝，也不需要重映射 label——旧方法的
 * {@code InsnList}、{@code tryCatchBlocks}、{@code localVariables} 与栈帧节点都自成一体，
 * 整体挂到新方法节点上即可，栈帧因此天然保持有效（写入侧只用 {@code COMPUTE_MAXS}，
 * 不重算帧，与匿名类对齐器同源的防类加载死锁约束）。</p>
 *
 * <h2>为什么是「旧 ∪ 新」而不是「新」</h2>
 * <p>阶段 A 里跑的是旧方法体，所以旧代码可能引用的成员一个都不能少。若新版本删掉了某个被旧方法体
 * 调用的私有方法，阶段 A 必须把它<b>留着</b>（休眠、无人调用），否则旧方法体一执行就
 * {@code NoSuchMethodError}。字段与内部类元数据同理：删除在布局门处已被拒绝，这里仍按并集处理，
 * 保证阶段 A 自身自洽。阶段 C 才提交严格的新结构。</p>
 */
public final class StageASkeleton {

	private StageASkeleton() { }

	/**
	 * 合成阶段 A 字节码：新结构 + 旧执行体。
	 *
	 * @param oldBytes 当前生效的旧字节码
	 * @param newBytes 目标新字节码
	 * @return 阶段 A 字节码
	 */
	public static byte[] build(byte[] oldBytes, byte[] newBytes) {
		Objects.requireNonNull(oldBytes, "oldBytes");
		Objects.requireNonNull(newBytes, "newBytes");

		ClassNode oldCn = read(oldBytes);
		ClassNode newCn = read(newBytes);

		Map<String, MethodNode> oldByKey = new HashMap<>();
		for (MethodNode mn : oldCn.methods) oldByKey.put(methodKey(mn), mn);

		Set<String> newKeys = new HashSet<>();
		for (MethodNode mn : newCn.methods) newKeys.add(methodKey(mn));

		// 1. 既有方法：保留旧执行体（新注解/新修饰符保留，只换 body）
		for (MethodNode mn : newCn.methods) {
			MethodNode old = oldByKey.get(methodKey(mn));
			if (old == null) continue;              // 新增方法：休眠体原样保留
			if (!bodyCompatible(old, mn)) continue; // 静态性/抽象性变了，旧体对新签名非法
			adoptBody(mn, old);
		}

		// 2. 新版本删掉、但旧执行体仍可能调用的方法：整条搬过来休眠
		for (MethodNode mn : oldCn.methods) {
			if (newKeys.contains(methodKey(mn))) continue;
			newCn.methods.add(mn);
		}

		// 3. 字段并集（删除已被布局门拒绝，这里只保证阶段 A 自洽）
		Set<String> newFieldKeys = new HashSet<>();
		for (FieldNode f : newCn.fields) newFieldKeys.add(f.name + ":" + f.desc);
		for (FieldNode f : oldCn.fields) {
			if (!newFieldKeys.contains(f.name + ":" + f.desc)) newCn.fields.add(f);
		}

		// 4. 内部类 / Nest 元数据并集：新内部类要在阶段 A 就位，旧内部类可能仍被旧体引用
		newCn.innerClasses = union(oldCn.innerClasses, newCn.innerClasses, n -> n.name);
		newCn.nestMembers = union(oldCn.nestMembers, newCn.nestMembers, s -> s);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		newCn.accept(cw);
		return cw.toByteArray();
	}

	/**
	 * 旧执行体能否挂到新方法上。
	 *
	 * <p>描述符相同已由 key 保证；静态性、抽象性、native 性若变了，旧 body 对新签名非法
	 * （例如旧体里的 {@code ALOAD 0} 在静态方法里没有意义）。这类变化本身属于签名/结构变更，
	 * 由上游的安全门拒绝，这里不替它兜底，只是不制造非法字节码。</p>
	 */
	private static boolean bodyCompatible(MethodNode old, MethodNode target) {
		int mask = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
		return (old.access & mask) == (target.access & mask);
	}

	/**
	 * 把旧方法的执行体整体挂到目标方法上。
	 *
	 * <p>直接移交节点引用而不是深拷贝：旧 {@link ClassNode} 在这次调用后即被丢弃，
	 * 而旧 body 的 label / 栈帧 / 局部变量表彼此自洽，搬过去仍然有效。</p>
	 */
	private static void adoptBody(MethodNode target, MethodNode source) {
		target.instructions = source.instructions;
		target.tryCatchBlocks = source.tryCatchBlocks;
		target.localVariables = source.localVariables;
		target.maxStack = source.maxStack;
		target.maxLocals = source.maxLocals;
		target.visibleLocalVariableAnnotations = source.visibleLocalVariableAnnotations;
		target.invisibleLocalVariableAnnotations = source.invisibleLocalVariableAnnotations;
	}

	private static String methodKey(MethodNode mn) {
		return mn.name + mn.desc;
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	/** 按 key 去重合并两个列表，旧列表在前。 */
	private static <T> List<T> union(List<T> oldList, List<T> newList, Function<T, String> key) {
		List<T> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		if (oldList != null) {
			for (T item : oldList) {
				if (item == null) continue;
				if (seen.add(key.apply(item))) out.add(item);
			}
		}
		if (newList != null) {
			for (T item : newList) {
				if (item == null) continue;
				if (seen.add(key.apply(item))) out.add(item);
			}
		}
		return out;
	}
}
