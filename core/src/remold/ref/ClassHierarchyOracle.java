package remold.ref;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.List;
import java.util.Optional;

/**
 * 离线类层级与元数据查询契约（{@code docs/initfix/01-safety-gate.md} §5）。
 *
 * <p>供 {@link InitFix} 在补丁分析与切片提取期使用，全程基于字节码抽象查询，<b>严禁触发类加载或 {@link Class#forName}</b>。
 * 所有类名均使用 JVM 内部名（Internal form，如 {@code java/lang/String}）。</p>
 *
 * <h2>核心能力与失败语义契约</h2>
 * <ul>
 *   <li><b>层级与接口查询</b>（{@link #isAssignableFrom}、{@link #isInterface}）：无法解析时返回 {@code false}。</li>
 *   <li><b>类修饰符查询</b>（{@link #getClassModifiers}）：元数据不可用时抛出 {@link IllegalArgumentException}。</li>
 *   <li><b>成员修饰符与解析</b>（{@link #getFieldModifiers}、{@link #getMethodModifiers}、{@link #resolveMember}）：
 *       未找到或解析失败时返回 {@link Optional#empty()}，调用方按"无法证明"保守处理（拒绝放行）。</li>
 *   <li><b>Nest 成员与写入证明</b>（{@link #getNestMembers}、{@link #getNestFieldWrites}）：
 *       若任一声明的 Nest 成员字节码无法读取，抛出 {@link IllegalStateException}，由调用方捕获并记 warning，
 *       进而拒绝全 Nest 不可变性写入证明（条件 B）。</li>
 * </ul>
 *
 * @see HierarchyTreeOracle
 * @see InitFix
 */
public interface ClassHierarchyOracle {
	enum HierarchyCheck {
		YES, NO, UNKNOWN
	}

	boolean isAssignableFrom(String superType, String subType);

	default HierarchyCheck checkAssignableFrom(String superType, String subType) {
		return isAssignableFrom(superType, subType) ? HierarchyCheck.YES : HierarchyCheck.UNKNOWN;
	}

	boolean isInterface(String className);

	int getClassModifiers(String className);

	Optional<Integer> getFieldModifiers(String className, String fieldName, String desc);

	Optional<Integer> getMethodModifiers(String className, String methodName, String desc);

	Optional<MemberRef> resolveMember(String className, String name, String desc, boolean isField);

	/**
	 * 查找 {@code className} 的<b>祖先</b>（超类链与接口，<b>不含</b> {@code className} 自身）中
	 * 同名同描述符的字段声明，返回最近的一条。
	 *
	 * <p>用于识别"本次新增字段遮蔽了祖先字段"：{@code empty} 表示祖先链上没有任何同名同描述符声明
	 * （不是遮蔽）。访问权限不作为返回条件 —— {@code private} / 跨包包内的声明照样返回，
	 * 由调用方按"静态性是否与实例槽位相符"决定是否采用。</p>
	 */
	Optional<InheritedField> findInheritedField(String className, String fieldName, String desc);

	/**
	 * {@code className} 的超类链（不含接口）是否全部可由字节码元数据解析。
	 *
	 * <p>为 {@code false} 时"祖先链上没有同名字段"这个结论不可信（读不到祖先字节码），
	 * 调用方必须 fail-closed 退回原有行为并告警，不得据此认定"不是遮蔽"。</p>
	 */
	boolean isSuperclassChainResolvable(String className);

	/**
	 * 祖先字段声明：最近的声明类与其修饰符。
	 *
	 * <p>刻意<b>不</b>携带"从被查询类视角是否可见"：DCEVM 的迁移复制不区分访问权限，遮蔽判定只看
	 * {@code (name, desc)} 与静态性，运行期读取祖先槽位走完整权限的 lookup，{@code private} 声明同样可读。
	 * 多带一个可见性字段只会让人以为它是判定门。</p>
	 */
	final class InheritedField {
		public final String declaringClass;
		public final int    access;

		public InheritedField(String declaringClass, int access) {
			this.declaringClass = declaringClass;
			this.access = access;
		}
	}

	final class MemberRef {
		public final String declaringClass;
		public final int    access;

		public MemberRef(String declaringClass, int access) {
			this.declaringClass = declaringClass;
			this.access = access;
		}
	}

	/** Returns the complete nest, or throws when any declared nest member is unavailable. */
	List<ClassNode> getNestMembers(String className);

	/** Returns a compact index of PUTFIELD/PUTSTATIC instructions across the complete nest. */
	List<NestFieldWrite> getNestFieldWrites(String className);

	final class NestFieldWrite {
		public final String        className;
		public final String        methodName;
		public final String        methodDesc;
		public final FieldInsnNode instruction;

		public NestFieldWrite(String className, String methodName, String methodDesc, FieldInsnNode instruction) {
			this.className = className;
			this.methodName = methodName;
			this.methodDesc = methodDesc;
			this.instruction = instruction;
		}
	}
}
