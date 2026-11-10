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
	 * 用已加载字段表补齐候选：缺失的字段以墓碑字段追加（同名/同描述符，{@code ACC_SYNTHETIC}）。
	 *
	 * @return 追加的字段签名列表（空表示字段表已经恒等）
	 */
	static List<String> padFields(ClassNode fresh, List<String> loaded) {
		java.util.Map<String, FieldNode> byKey = new java.util.LinkedHashMap<>();
		for (FieldNode f : fresh.fields) {
			byKey.putIfAbsent(f.name + ":" + f.desc, f);
		}
		List<FieldNode> ordered = new ArrayList<>(Math.max(loaded.size(), fresh.fields.size()));
		List<String> added = new ArrayList<>();
		java.util.Set<String> used = new java.util.HashSet<>();
		for (String key : loaded) {
			FieldNode existing = byKey.get(key);
			if (existing != null) {
				ordered.add(existing);
				used.add(key);
				continue;
			}
			int colon = key.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			FieldNode tombstone = new FieldNode(
				org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
				key.substring(0, colon), key.substring(colon + 1), null, null);
			ordered.add(tombstone);
			used.add(key);
			added.add(key);
		}
		// 候选里基线没有的新字段追加在末尾（字段表只增不减，且基线槽位不移动）。
		for (FieldNode f : fresh.fields) {
			if (!used.contains(f.name + ":" + f.desc)) {
				ordered.add(f);
			}
		}
		if (added.isEmpty()) {
			return added;
		}
		fresh.fields = ordered;
		return added;
	}

	/**
	 * 字段表是否可安全重定义：已加载的每个字段都必须在候选中同名同描述符存在，
	 * 且候选不得出现「同名不同描述符」的字段（类型漂移无法用墓碑表达）。
	 */
	static boolean canRedefineWithoutInstanceRewrite(ClassNode fresh, List<String> loaded) {
		List<String> present = nodeFields(fresh);
		for (String key : loaded) {
			if (!present.contains(key)) {
				return false;
			}
		}
		for (String key : present) {
			int colon = key.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String name = key.substring(0, colon);
			for (String old : loaded) {
				int oldColon = old.indexOf(':');
				if (oldColon > 0 && old.substring(0, oldColon).equals(name) && !old.equals(key)) {
					return false;   // 类型漂移
				}
			}
		}
		return true;
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
