package remold;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

/**
 * 孤儿 lambda 能否"保留上一轮方法体"的纯判据。
 *
 * <p><b>为什么需要它</b>：空壳桩对非 {@code void} 的 Lambda 返回 {@code null}/0，
 * 调用方拿到返回值后通常直接链式调用或拆箱，紧接着就是 {@code NullPointerException} /
 * 除零 / 数组越界。保留旧方法体能维持合法返回值，但旧体是"上一轮类版本"的代码，
 * 它引用的本类成员在新版本里可能已经不存在——直接抄过去会在重定义校验或运行期抛
 * {@code NoSuchFieldError} / {@code NoSuchMethodError} / {@code VerifyError}。</p>
 *
 * <p><b>判据</b>：只检查<b>指向本类</b>的成员引用（字段 / 方法 / {@code invokedynamic}
 * 引导参数里的句柄 / {@code LDC} 句柄常量）。这些引用必须满足：</p>
 * <ul>
 *   <li>新类里仍声明同 {@code name + desc} 的成员，且静态性与旧调用点一致；或</li>
 *   <li>该成员会被本轮作为幽灵桩/捕获桥重新注入（名字在 {@code plannedMethodKeys} 里，
 *       静态性按旧声明复核）；或</li>
 *   <li>该成员在旧类里<b>也没有声明</b>——说明它是继承成员，解析沿继承链继续，类层级
 *       变更本来就会摘批，这里放行。</li>
 * </ul>
 * <p>旧类声明的成员在新类里消失 → 拒绝保留。拒绝原因直接进 WARN（英文、用户可见），
 * 然后回退到原有默认值桩。</p>
 *
 * <p><b>不覆盖的引用</b>：指向<b>其它类</b>的成员不做存活性检查（那里没有"本次删除"的
 * 语义，且这些类通常来自游戏/框架 jar，没有可靠的字节码来源可查）。因此"上一轮引用的
 * 某个兄弟类被删掉"这种跨类残留仍可能让保留体在重定义校验阶段失败——这是本判据的已知
 * 边界，属残余风险，不是可证明安全的放行。</p>
 *
 * <p><b>帧（StackMapTable）</b>：旧方法体的 StackMapTable 随指令一起搬运。方法描述符
 * 不变、类名不变、前缀指令栈中性，因此旧帧仍然有效；本类只做成员引用存活性，
 * 不重算帧（{@code LambdaAligner} 写入侧刻意不用 {@code COMPUTE_FRAMES}）。</p>
 */
final class OrphanBodyRetention {

	private OrphanBodyRetention() { }

	/**
	 * 判断旧方法体能否原样保留。
	 *
	 * @param oldClass          上一轮类结构（成员表以它判断"是删除还是继承"）
	 * @param newClass          本轮新类结构（幽灵桩注入前的最终成员表）
	 * @param body              上一轮的方法体（含帧），不可为 {@code null}
	 * @param plannedMethodKeys 本轮将被重新注入的方法键（{@code name + desc}：幽灵桩 + 捕获桥）
	 * @return {@code null} 表示可保留；否则返回英文的不可保留原因
	 */
	static String rejectionReason(ClassNode oldClass, ClassNode newClass, MethodNode body,
	                              Set<String> plannedMethodKeys) {
		if (oldClass == null || newClass == null) return "class structure is unavailable";
		if (body == null || body.instructions == null) return "the previous body is unavailable";

		for (AbstractInsnNode n : body.instructions) {
			if (n instanceof FieldInsnNode f && oldClass.name.equals(f.owner)) {
				String why = fieldReason(oldClass, newClass, f.name, f.desc, isStaticFieldAccess(f.getOpcode()));
				if (why != null) return why;
			} else if (n instanceof MethodInsnNode m && oldClass.name.equals(m.owner)) {
				String why = methodReason(oldClass, newClass, m.name, m.desc,
					m.getOpcode() == Opcodes.INVOKESTATIC, plannedMethodKeys);
				if (why != null) return why;
			} else if (n instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof Handle h && oldClass.name.equals(h.getOwner())) {
						String why = handleReason(oldClass, newClass, h, plannedMethodKeys);
						if (why != null) return why;
					}
				}
			} else if (n instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h
			           && oldClass.name.equals(h.getOwner())) {
				String why = handleReason(oldClass, newClass, h, plannedMethodKeys);
				if (why != null) return why;
			}
		}
		return null;
	}

	private static boolean isStaticFieldAccess(int opcode) {
		return opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
	}

	private static String fieldReason(ClassNode oldClass, ClassNode newClass,
	                                  String name, String desc, boolean wantStatic) {
		FieldNode neu = findField(newClass, name, desc);
		if (neu != null) {
			return ((neu.access & Opcodes.ACC_STATIC) != 0) == wantStatic
			 ? null
			 : "field " + name + " changed its static modifier";
		}
		return findField(oldClass, name, desc) == null
		 ? null                                                    // 继承成员：解析沿父链继续
		 : "references removed field " + name + " " + desc;
	}

	private static String methodReason(ClassNode oldClass, ClassNode newClass,
	                                   String name, String desc, boolean wantStatic,
	                                   Set<String> plannedMethodKeys) {
		MethodNode neu = findMethod(newClass, name, desc);
		if (neu != null) {
			return ((neu.access & Opcodes.ACC_STATIC) != 0) == wantStatic
			 ? null
			 : "method " + name + desc + " changed its static modifier";
		}
		String key = name + desc;
		if (plannedMethodKeys != null && plannedMethodKeys.contains(key)) {
			// 幽灵桩/捕获桥的 access 从旧方法头复制，静态性沿用旧声明。
			MethodNode old = findMethod(oldClass, name, desc);
			return old == null || ((old.access & Opcodes.ACC_STATIC) != 0) == wantStatic
			 ? null
			 : "planned ghost " + key + " would change its static modifier";
		}
		return findMethod(oldClass, name, desc) == null
		 ? null                                                    // 继承成员
		 : "references removed method " + name + desc;
	}

	private static String handleReason(ClassNode oldClass, ClassNode newClass,
	                                   Handle h, Set<String> plannedMethodKeys) {
		// 嵌套 lambda：旧体通过 indy 句柄引用本类的合成方法。若这个目标本轮被配对成新方法
		// （不在 plannedMethodKeys 里、却在新类中存在），保留旧体就会跑出"旧外层 + 新内层"
		// 的混跑——静默错值（S23 现场形态：外层 varargs lambda 被删、内层 AutoCloseable
		// lambda 被原位更新，输出 closed-v4-v3）。判据取保守侧：目标没进幽灵计划就拒绝保留。
		if (isSyntheticLambda(h.getName()) && oldClass.name.equals(h.getOwner())
		    && (plannedMethodKeys == null || !plannedMethodKeys.contains(h.getName() + h.getDesc()))
		    && findMethod(newClass, h.getName(), h.getDesc()) != null) {
			return "its nested lambda " + h.getName() + h.getDesc()
			     + " was updated in place (the old body would mix old outer with new inner semantics)";
		}
		switch (h.getTag()) {
			case Opcodes.H_GETFIELD:
			case Opcodes.H_GETSTATIC:
			case Opcodes.H_PUTFIELD:
			case Opcodes.H_PUTSTATIC:
				return fieldReason(oldClass, newClass, h.getName(), h.getDesc(),
					h.getTag() == Opcodes.H_GETSTATIC || h.getTag() == Opcodes.H_PUTSTATIC);
			case Opcodes.H_INVOKESTATIC:
				return methodReason(oldClass, newClass, h.getName(), h.getDesc(), true, plannedMethodKeys);
			case Opcodes.H_INVOKEVIRTUAL:
			case Opcodes.H_INVOKESPECIAL:
			case Opcodes.H_INVOKEINTERFACE:
			case Opcodes.H_NEWINVOKESPECIAL:
				return methodReason(oldClass, newClass, h.getName(), h.getDesc(), false, plannedMethodKeys);
			default:
				return null;                                          // 非成员句柄（不可能出现），保守放行
		}
	}

	/** 合成 lambda 方法名（javac {@code lambda$...} 与 MethodFingerprinter 认可的自造名）。 */
	/**
	 * 合成 lambda 方法名：javac {@code lambda$...}、Kotlin {@code $lambda}、Scala {@code $anonfun$}。
	 *
	 * <p>刻意<b>不</b>复用 {@link MethodFingerprinter#isSyntheticName}：那条判据还含 {@code access$}
	 * 访问器桩，而访问器是直接调用目标（方法引用也可能指到它），并不是 indy 的 lambda impl——
	 * 把它算成"嵌套 lambda"会让本闸门误杀。</p>
	 */
	private static boolean isSyntheticLambda(String name) {
		return name != null
		     && !name.startsWith("access$")
		     && (name.contains("lambda$") || name.contains("$lambda") || name.contains("$anonfun$"));
	}

	private static FieldNode findField(ClassNode cn, String name, String desc) {
		if (cn == null || cn.fields == null) return null;
		for (FieldNode f : cn.fields) {
			if (f.name.equals(name) && f.desc.equals(desc)) return f;
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode cn, String name, String desc) {
		if (cn == null || cn.methods == null) return null;
		for (MethodNode m : cn.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}
}
