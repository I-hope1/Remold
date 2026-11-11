package remold.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * 合成内部类（{@code <Target>$Anonymous$<hash>}）的「字段 Schema 锚定」纯函数。
 *
 * <p><b>为什么必须禁止字段缩容</b>：JBR/DCEVM 的增强重定义只在<b>字段表</b>变化时做
 * {@code ModifyInstances}（全堆对象重排）。合成匿名类的捕获变量列表变化（例如
 * {@code (this, int, Entity)} → {@code (this, Target)}）会让字段表缩容，一次
 * {@code redefineClasses} 就可能把堆内无关对象的引用字段冲成 {@code null}（真机
 * {@code LootContextPredicate.combinedCondition} 被置 null 的实测根因）。</p>
 *
 * <p>做法：把<b>已加载</b>合成类的字段表作为锚，重生成产物缺哪个字段就补一个同名/同描述符的
 * 墓碑字段（{@code ACC_SYNTHETIC}，无读者）；这样字段表恒等 → JVM 只做方法体替换
 * （{@code ModifyMethodBodies}），不触发 {@code ModifyInstances}。</p>
 *
 * <p>类型漂移（同名不同描述符）不补：那种情况无法用墓碑表达，调用方必须 fail-closed 拒绝重定义。</p>
 */
final class MixinSyntheticSchema {

	private MixinSyntheticSchema() {
	}

	/** 已加载类的字段签名（{@code name:desc}），顺序与 class 文件一致。 */
	static List<String> loadedFields(Class<?> loaded) {
		List<String> out = new ArrayList<>();
		for (Field f : loaded.getDeclaredFields()) {
			out.add(f.getName() + ":" + Type.getDescriptor(f.getType()));
		}
		return out;
	}

	/** 候选 ClassNode 的字段签名。 */
	static List<String> nodeFields(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (FieldNode f : node.fields) {
			out.add(f.name + ":" + f.desc);
		}
		return out;
	}

	/**
	 * 已加载字段表的**带标志位**签名（{@code name:desc:static:synthetic}，顺序与 class 文件一致）。
	 *
	 * <p>{@link #loadedFields} / {@link #nodeFields} 只比 {@code name:desc}，看不见"同名同描述符但
	 * {@code static} 翻转"或"{@code ACC_SYNTHETIC} 被摘掉"这类结构变化。闸门必须把两者都比一遍：
	 * 前者用于墓碑补齐/重命名的身份判据，后者用于"这一版到底算不算改了成员 schema"。</p>
	 *
	 * <p>标志位口径与 {@code AnonClassAligner} 的 Tier 4 布局门一致（它也把 {@code isStatic} /
	 * {@code isSynthetic} 纳入判据），避免同一件事在两层给出不同答案。</p>
	 */
	static List<String> loadedFieldSignatures(Class<?> loaded) {
		List<String> out = new ArrayList<>();
		for (Field f : loaded.getDeclaredFields()) {
			out.add(f.getName() + ":" + Type.getDescriptor(f.getType())
				+ ":" + Modifier.isStatic(f.getModifiers())
				+ ":" + f.isSynthetic());
		}
		return out;
	}

	/** 候选字段表的带标志位签名（与 {@link #loadedFieldSignatures} 同口径）。 */
	static List<String> nodeFieldSignatures(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (FieldNode f : node.fields) {
			out.add(f.name + ":" + f.desc
				+ ":" + ((f.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0)
				+ ":" + ((f.access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) != 0));
		}
		return out;
	}







	/** 字段表 + 构造器描述符的 Schema 指纹（诊断/日志用；未来可用于命名哈希）。 */
	static String schemaHash(ClassNode node) {
		List<String> parts = new ArrayList<>(nodeFields(node));
		for (org.objectweb.asm.tree.MethodNode m : node.methods) {
			if ("<init>".equals(m.name)) {
				parts.add("<init>" + m.desc);
			}
		}
		java.util.Collections.sort(parts);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(String.join("|", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				sb.append(String.format("%02x", hash[i]));
			}
			return sb.toString();
		} catch (Throwable t) {
			return "unknown";
		}
	}


}
