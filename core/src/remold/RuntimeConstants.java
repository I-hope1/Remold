package remold;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「运行时常量」访问器注册表。
 *
 * <p>有些值在源码里不是 {@code final} 字段，但<b>语义上是常量</b>——典型是启动时算一次、
 * 之后整个 JVM 运行期都不再变的全局量（UI 缩放系数、屏幕尺寸、平台标识之类）。它们通常
 * 藏在外部框架的一个无参静态访问器后面（{@code Scl.scl()}），分析引擎按字面规则只能看到
 * 一次"方法调用"，于是：</p>
 * <ul>
 *   <li>{@code InitFix} 的效应审查会把它当成普通调用（除非恰好在它自己的白名单里）；</li>
 *   <li>{@code LambdaAligner} 的常量合成会整段放弃。</li>
 * </ul>
 *
 * <p>这里提供一个<b>显式</b>登记口：登记过的访问器被两个引擎当作常量来源。为什么必须是
 * 显式登记而不是推断——"这个调用返回值永远不变"是<b>运行期性质</b>，从字节码里看不出来，
 * 只能由知道那个 API 的人断言。</p>
 *
 * <p><b>登记即承诺</b>：被登记的访问器在同一个 JVM 运行期内必须返回同一个值。违反承诺的
 * 后果不是崩溃，而是"老对象看到的是当前值而不是当年快照"——一次可观测的行为变化。所以
 * 只登记真正稳定的量，不要图省事把"大多数时候不变"的东西放进来。</p>
 *
 * <p>要求：静态、无参、有返回值。带参数的调用不可能是常量，直接拒绝登记（快速失败，
 * 避免登记了一个永远不会命中的键）。</p>
 */
public final class RuntimeConstants {

	private static final Set<String> KEYS = ConcurrentHashMap.newKeySet();

	private RuntimeConstants() {}

	/**
	 * 登记一个运行时常量访问器。
	 *
	 * @param owner 内部名（如 {@code arc/scene/ui/layout/Scl}）
	 * @param name  方法名
	 * @param desc  描述符，必须无参且有返回值
	 * @throws IllegalArgumentException 描述符带参数或返回 {@code void}
	 */
	public static void register(String owner, String name, String desc) {
		if (owner == null || name == null || desc == null) {
			throw new IllegalArgumentException("runtime constant needs owner/name/desc");
		}
		if (Type.getArgumentTypes(desc).length != 0) {
			throw new IllegalArgumentException(
				"runtime constant must take no arguments: " + owner + "." + name + desc);
		}
		if (Type.getReturnType(desc).getSort() == Type.VOID) {
			throw new IllegalArgumentException(
				"runtime constant must return a value: " + owner + "." + name + desc);
		}
		KEYS.add(key(owner, name, desc));
	}

	/** 撤销登记（主要给测试用）。 */
	public static boolean unregister(String owner, String name, String desc) {
		return KEYS.remove(key(owner, name, desc));
	}

	public static boolean isRuntimeConstant(String owner, String name, String desc) {
		return KEYS.contains(key(owner, name, desc));
	}

	/**
	 * 这条调用是不是已登记的运行时常量访问器。
	 *
	 * <p>只认 {@code INVOKESTATIC}：实例方法读的是某个对象的状态，不可能是常量。</p>
	 */
	public static boolean isRuntimeConstant(MethodInsnNode m) {
		return m != null
		       && m.getOpcode() == Opcodes.INVOKESTATIC
		       && isRuntimeConstant(m.owner, m.name, m.desc);
	}

	private static String key(String owner, String name, String desc) {
		return owner + "." + name + desc;
	}
}
