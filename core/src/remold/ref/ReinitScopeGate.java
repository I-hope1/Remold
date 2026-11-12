package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code @RemoldReinit} 的继承作用域安全门（纯函数，{@code docs/initfix/03-runtime-driver.md} §4）。
 *
 * <h2>问题</h2>
 * <p>注解的默认作用域是 {@code INCLUDE_SUBCLASSES}：字段声明在宿主类、值从<b>声明类自己的</b>
 * {@code <init>} 切片提取，却写给<b>声明类及其全部子类/匿名子类实例</b>。若某个子类在自己的
 * {@code <init>} 里给这个继承字段写了不同的值（典型是双括号初始化
 * {@code new Sub() {{ health = 100; }}}），重置会把声明类的初值写进去，<b>覆盖子类自己的初值</b>；
 * 而且子类构造器里的赋值不会被重放（实测见文档 B2：oldA 90→1、oldB 91→1、未修改的 oldC 50→1）。</p>
 *
 * <h2>本门回答的问题</h2>
 * <p>给定宿主类新字节码、注解字段集合、<b>已登记的子类型</b>及其字节码，判断是否存在
 * "子类在初始化器里写同一字段"的形态。命中即说明当前配置会静默改写子类初值，应拒绝本轮重定义
 * （调用方给出 {@code [REINIT-SCOPE]} 拒绝），而不是靠用户记得改 {@code scope = SELF}。</p>
 *
 * <h2>为什么枚举"已登记子类型"就够</h2>
 * <p>子类要产生存活实例，必先被加载；类的加载必然经过 agent 的 transform 并登记进继承图
 * （见 {@link AnnotationTransformer.HierarchyTree#knownSubtypes(String, ClassLoader)}）。
 * 因此<b>没有登记 ⇒ 不可能有该子类的存活实例</b>。未加载的第三方向子类即便存在，也不会有实例被
 * 本轮补丁看到，不构成覆盖风险。</p>
 *
 * <h2>保守方向</h2>
 * <ul>
 *   <li>子类字节码读不到 → 记为"无法证明"，与命中同等拒绝（fail-closed）；</li>
 *   <li>子类自己声明了同名同描述符的字段时，只有 {@code owner} 指向子类自身的写才被认为是写它自己的
 *       遮蔽字段；{@code owner} 指向宿主/更上层的写仍算命中（宁可多拒，不静默覆盖）；</li>
 *   <li>只扫描子类 {@code <init>}：这正是"子类自己的初值"所在。运行期方法里的写入不属于初始化语义，
 *       由业务自行负责。</li>
 * </ul>
 */
public final class ReinitScopeGate {

	private ReinitScopeGate() { }

	private static final String REINIT_DESC  = "Lremold/annotation/RemoldReinit;";
	private static final String SCOPE_DESC   = "Lremold/annotation/RemoldReinit$Scope;";
	private static final String SCOPE_INCLUDE = "INCLUDE_SUBCLASSES";

	/**
	 * @param host          宿主类的新字节码节点（字段上的注解以它为准）
	 * @param reinitFields  本轮被 {@code @RemoldReinit} 拉进候选集的字段名
	 * @param knownSubtypes 已登记的子类型内部名（不含宿主自身；由 {@code HierarchyTree} 提供）
	 * @param subtypeBytes  子类型内部名 → 字节码；缺失的按"无法证明"处理
	 * @return 字段名 → 拒绝原因；空表示未发现"子类初始化器写继承字段"的危险形态
	 */
	public static Map<String, String> unsafeInheritedFields(ClassNode host,
	                                                        Set<String> reinitFields,
	                                                        Set<String> knownSubtypes,
	                                                        Map<String, byte[]> subtypeBytes) {
		if (host == null || reinitFields == null || reinitFields.isEmpty()
		    || knownSubtypes == null || knownSubtypes.isEmpty()) {
			return Map.of();
		}
		Map<String, FieldNode> hostFields = new LinkedHashMap<>();
		for (FieldNode f : host.fields) hostFields.put(f.name, f);

		Map<String, String> unsafe = new LinkedHashMap<>();
		for (String name : reinitFields) {
			FieldNode field = hostFields.get(name);
			if (field == null) continue;
			// 静态字段只有一个槽位，scope 对它没有意义（InitFix 只打一条 warn 并忽略）。
			if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
			if (Scope.SELF == scopeOf(field)) continue;   // 显式逃生口：只写宿主自身实例

			List<String> writers = new ArrayList<>();
			List<String> uninspectable = new ArrayList<>();
			for (String subtype : knownSubtypes) {
				if (subtype == null || subtype.equals(host.name)) continue;
				byte[] bytes = subtypeBytes == null ? null : subtypeBytes.get(subtype);
				if (bytes == null) {
					uninspectable.add(subtype.replace('/', '.'));
					continue;
				}
				if (writesFieldInInitializer(bytes, subtype, host.name, field.name, field.desc)) {
					writers.add(subtype.replace('/', '.'));
				}
			}
			if (!writers.isEmpty()) {
				unsafe.put(name, "inherited field is assigned in subclass initializer(s) " + preview(writers)
					+ "; scope = INCLUDE_SUBCLASSES would overwrite the subclass-specific initializer with"
					+ " the declaring class's value (and the subclass assignment is not replayed) — move the"
					+ " annotation to where the value is initialized, or use scope = SELF (host instances only),"
					+ " or @OnReload");
			} else if (!uninspectable.isEmpty()) {
				unsafe.put(name, "cannot inspect registered subclass(es) " + preview(uninspectable)
					+ " to rule out a subclass initializer writing this inherited field (fail-closed)");
			}
		}
		return unsafe;
	}

	private static String preview(List<String> names) {
		if (names.size() <= 3) return names.toString();
		return names.subList(0, 3) + " (+" + (names.size() - 3) + " more)";
	}

	private enum Scope { SELF, INCLUDE_SUBCLASSES }

	/** 读字段上的 {@code @RemoldReinit.scope()}；未显式写时按注解默认值 {@code INCLUDE_SUBCLASSES}。 */
	private static Scope scopeOf(FieldNode field) {
		if (field.visibleAnnotations == null) return Scope.INCLUDE_SUBCLASSES;
		for (AnnotationNode an : field.visibleAnnotations) {
			if (!REINIT_DESC.equals(an.desc) || an.values == null) continue;
			for (int i = 0; i + 1 < an.values.size(); i += 2) {
				if (!"scope".equals(an.values.get(i))) continue;
				Object v = an.values.get(i + 1);
				if (v instanceof String[] pair && pair.length == 2 && SCOPE_DESC.equals(pair[0])) {
					return "SELF".equals(pair[1]) ? Scope.SELF : Scope.INCLUDE_SUBCLASSES;
				}
			}
		}
		return Scope.INCLUDE_SUBCLASSES;
	}

	/**
	 * 子类是否在 {@code <init>} 里写了自己继承来的 {@code hostName.fieldName:fieldDesc}。
	 *
	 * <p>javac 对 {@code this.field = ...}（field 声明在父类）发射的 {@code Fieldref} owner 通常是
	 * 接收者的静态类型（即子类自身），JVMS 5.4.3.2 再从子类向上解析到声明类。因此这里按
	 * name+desc 匹配，只有"子类自己也声明了同名同描述符字段且 owner 是子类"才判为写遮蔽字段。</p>
	 */
	private static boolean writesFieldInInitializer(byte[] subtypeBytes, String subtypeInternalName,
	                                                String hostInternalName, String fieldName, String fieldDesc) {
		ClassNode sub = new ClassNode();
		try {
			new ClassReader(subtypeBytes).accept(sub, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable t) {
			return true;   // 解析不了就按"可能写"处理（fail-closed）
		}
		boolean declaresOwn = false;
		for (FieldNode f : sub.fields) {
			if (f.name.equals(fieldName) && f.desc.equals(fieldDesc)) { declaresOwn = true; break; }
		}
		for (MethodNode m : sub.methods) {
			if (!"<init>".equals(m.name) || m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode fin) || fin.getOpcode() != Opcodes.PUTFIELD) continue;
				if (!fin.name.equals(fieldName) || !fin.desc.equals(fieldDesc)) continue;
				if (declaresOwn && fin.owner.equals(subtypeInternalName)) continue;   // 写自己的遮蔽字段
				return true;
			}
		}
		return false;
	}
}
