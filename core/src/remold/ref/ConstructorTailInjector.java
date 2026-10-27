package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 构造器尾部插桩（{@code docs/initfix/04-target-design.md} §4）。
 *
 * <p>把「新增字段的初始化切片」写进每一个**根构造器**的 {@code RETURN} 之前，使阶段 A 之后新建的
 * 实例在构造期就带上真实初值，从而物理封死「堆遍历快照 → 重定义生效」之间的并发实例化缝隙
 * （{@code docs/status.md} 风险 1 与风险 6）。</p>
 *
 * <p>本类是纯函数：输入类字节码与每个字段的取值切片，输出改写后的字节码，不读写任何全局状态、
 * 不触发类加载。切片约定为「从空栈开始、执行后栈顶为该字段的值」，由调用方（{@code InitFix}）
 * 用同一套安全门与参数回溯改写产出。</p>
 *
 * <h2>为什么手工插入而不重算栈帧</h2>
 * <p>与匿名类对齐器同源：{@code ClassWriter(COMPUTE_FRAMES)} 会在 {@code getCommonSuperClass}
 * 里触发类加载，热更新期间可能死锁（{@code docs/topology/01-invariants-and-remapping.md} §2）。
 * 这里只在 {@code RETURN} 前插入直线代码、不改动任何跳转目标，因此原有 {@code FrameNode} 依然成立：
 * 帧描述的是「执行下一条真实指令之前的状态」，而 {@code RETURN} 前与本类插入的 {@code ALOAD 0}
 * 前都是空栈、同样的局部变量表。写入侧只用 {@code COMPUTE_MAXS} 重算栈深。</p>
 *
 * <h2>保守边界</h2>
 * <ul>
 *   <li><b>只处理根构造器</b>：以 {@code this(...)} 开头的构造器整条跳过，避免同一条初始化被
 *       根构造器与其委托链重复执行。</li>
 *   <li><b>构造器已自行赋值的目标字段</b>：整条构造器跳过并记录原因，绝不覆盖用户代码算出的值
 *       （对应 {@code 01-safety-gate.md} §6「构造器里使用新增字段」的 fail-closed 取向）。</li>
 *   <li><b>没有 {@code RETURN} 的构造器</b>（恒抛异常）：跳过并记录，此类路径不会让对象逸出。</li>
 * </ul>
 */
public final class ConstructorTailInjector {

	/**
	 * 一个字段的初始化切片。
	 * @param name  字段名
	 * @param desc  字段描述符
	 * @param value 取值指令序列：从空栈开始，执行后栈顶为该字段的值
	 */
	public record FieldInit(String name, String desc, InsnList value) {
		public FieldInit {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(desc, "desc");
			Objects.requireNonNull(value, "value");
		}
	}

	/**
	 * 插桩结果。
	 * @param bytes                 改写后的字节码（无插入点时原样返回入参）
	 * @param rootConstructors      识别到的根构造器数量
	 * @param injectedConstructors  实际被插桩的根构造器数量
	 * @param injectionSites        插入点总数（多返回路径的构造器会贡献多个）
	 * @param skipped               被跳过的构造器及原因，供调用方决定是否整体拒绝
	 */
	public record Result(byte[] bytes, int rootConstructors, int injectedConstructors,
	                     int injectionSites, List<String> skipped) {
		public boolean changed() {
			return injectionSites > 0;
		}
	}

	private ConstructorTailInjector() { }

	/**
	 * 把每个 {@link FieldInit} 的取值切片追加到所有根构造器的每个 {@code RETURN} 之前。
	 *
	 * @param classBytes 目标类字节码（通常是阶段 A 的骨架字节码）
	 * @param inits      需要预初始化的字段切片；为空时原样返回
	 */
	public static Result inject(byte[] classBytes, List<FieldInit> inits) {
		Objects.requireNonNull(classBytes, "classBytes");
		if (inits == null || inits.isEmpty()) return new Result(classBytes, 0, 0, 0, List.of());

		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		int rootConstructors = 0;
		int injectedConstructors = 0;
		int injectionSites = 0;
		List<String> skipped = new ArrayList<>();

		for (MethodNode mn : cn.methods) {
			if (!"<init>".equals(mn.name)) continue;

			String signature = "<init>" + mn.desc;

			if (isDelegatingConstructor(cn.name, mn)) {
				skipped.add(signature + ": delegates to this(...), the root constructor already initializes");
				continue;
			}
			rootConstructors++;

			String assigned = alreadyAssignedField(cn.name, mn, inits);
			if (assigned != null) {
				skipped.add(signature + ": assigns field '" + assigned
				            + "' in its own body; refusing to overwrite it at the tail");
				continue;
			}

			List<AbstractInsnNode> returns = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n.getOpcode() == Opcodes.RETURN) returns.add(n);
			}
			if (returns.isEmpty()) {
				skipped.add(signature + ": no RETURN (always throws), nothing can escape");
				continue;
			}

			for (AbstractInsnNode ret : returns) {
				mn.instructions.insertBefore(ret, blockFor(cn.name, inits));
				injectionSites++;
			}
			injectedConstructors++;
		}

		if (injectionSites == 0) {
			return new Result(classBytes, rootConstructors, 0, 0, List.copyOf(skipped));
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return new Result(cw.toByteArray(), rootConstructors, injectedConstructors, injectionSites,
		                  List.copyOf(skipped));
	}

	/** 每个字段一段 {@code ALOAD 0; <value>; PUTFIELD this.field}。 */
	private static InsnList blockFor(String className, List<FieldInit> inits) {
		InsnList block = new InsnList();
		for (FieldInit fi : inits) {
			block.add(new VarInsnNode(Opcodes.ALOAD, 0));
			block.add(copy(fi.value()));
			block.add(new FieldInsnNode(Opcodes.PUTFIELD, className, fi.name(), fi.desc()));
		}
		return block;
	}

	/**
	 * 构造器是否以 {@code this(...)} 开头。
	 *
	 * <p>JVM 校验规则保证构造器里第一条 {@code <init>} 调用只能是 {@code super(...)} 或
	 * {@code this(...)}，因此取「第一条 {@code INVOKESPECIAL <init>}」即可判定，无需数据流分析。</p>
	 */
	private static boolean isDelegatingConstructor(String className, MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && "<init>".equals(m.name)) {
				return m.owner.equals(className);
			}
		}
		return false;
	}

	/** 构造器体内是否已经写过某个目标字段；返回第一个命中的字段名，没有则返回 null。 */
	private static String alreadyAssignedField(String className, MethodNode mn, List<FieldInit> inits) {
		for (AbstractInsnNode n : mn.instructions) {
			if (!(n instanceof FieldInsnNode f) || f.getOpcode() != Opcodes.PUTFIELD) continue;
			if (!f.owner.equals(className)) continue;
			for (FieldInit fi : inits) {
				if (fi.name().equals(f.name) && fi.desc().equals(f.desc)) return f.name;
			}
		}
		return null;
	}

	/** 深拷贝指令序列；同一个切片要被插入多个构造器/多个返回点，不能共享节点实例。 */
	private static InsnList copy(InsnList src) {
		Map<LabelNode, LabelNode> labels = new HashMap<>();
		for (AbstractInsnNode n : src) {
			if (n instanceof LabelNode l) labels.put(l, new LabelNode());
		}
		InsnList out = new InsnList();
		for (AbstractInsnNode n : src) out.add(n.clone(labels));
		return out;
	}
}
