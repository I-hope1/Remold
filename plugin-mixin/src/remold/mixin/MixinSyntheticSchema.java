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

	/**
	 * 规则 1：同名异型合成字段的「重命名追加」。
	 *
	 * <p>候选里 {@code (name, candDesc)} 与加载基线 {@code (name, baseDesc)} 同名异型时：基线槽位原封不动
	 * （由 {@link #padFields} 补墓碑），候选字段确定性地重命名为 {@code name$remold$<descHash>}，并改写候选
	 * 类内所有对该字段的 {@code GETFIELD}/{@code PUTFIELD}。这样字段表只增不减，且新旧两套捕获变量并存。</p>
	 *
	 * @return 重命名映射（旧名 → 新名），供日志/诊断
	 */
	static java.util.Map<String, String> renameCollisions(ClassNode fresh, List<String> loaded) {
		java.util.Map<String, String> loadedDesc = new java.util.LinkedHashMap<>();
		for (String key : loaded) {
			int colon = key.indexOf(':');
			if (colon > 0) {
				loadedDesc.putIfAbsent(key.substring(0, colon), key.substring(colon + 1));
			}
		}
		java.util.Map<String, String> rename = new java.util.LinkedHashMap<>();
		for (FieldNode f : fresh.fields) {
			String baseDesc = loadedDesc.get(f.name);
			if (baseDesc == null || baseDesc.equals(f.desc)) {
				continue;
			}
			String renamed = f.name + "$remold$" + shortHash(f.desc);
			rename.put(f.name, renamed);
			f.name = renamed;
		}
		if (!rename.isEmpty()) {
			rewriteFieldAccess(fresh, rename);
		}
		return rename;
	}

	/** 把候选类内对已重命名字段的 {@code GETFIELD}/{@code PUTFIELD} 指向新名。 */
	static void rewriteFieldAccess(ClassNode fresh, java.util.Map<String, String> rename) {
		for (org.objectweb.asm.tree.MethodNode m : fresh.methods) {
			if (m.instructions == null) {
				continue;
			}
			for (org.objectweb.asm.tree.AbstractInsnNode insn = m.instructions.getFirst(); insn != null;
				insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) {
					if (fresh.name != null && fresh.name.equals(field.owner) && rename.containsKey(field.name)) {
						field.name = rename.get(field.name);
					}
				}
			}
		}
	}

	/**
	 * 规则 2：{@code <init>} 重载墓碑并集。
	 *
	 * <p>候选的 {@code <init>(candDesc)} 与基线 {@code <init>(baseDesc)} 不同时，把基线构造器原样保留
	 * （优先从 {@code LOADED} 的旧字节里复制原始构造体；没有旧字节且父类是 {@code java/lang/Object} 时
	 * 合成最小合法构造体）。这样新旧两个构造器同时存在，任何调用点（含在途旧栈帧）都不会
	 * {@code NoSuchMethodError}。</p>
	 *
	 * @return 保留的构造器描述符列表；无法保留时返回 {@code null}（调用方必须 fail-closed 并中止宿主重定义）
	 */
	static List<String> retainConstructors(ClassNode fresh, byte[] loadedBytes) {
		java.util.Set<String> present = new java.util.HashSet<>();
		for (org.objectweb.asm.tree.MethodNode m : fresh.methods) {
			if ("<init>".equals(m.name)) {
				present.add(m.desc);
			}
		}
		List<String> retained = new ArrayList<>();
		if (loadedBytes != null) {
			ClassNode loaded = new ClassNode();
			new org.objectweb.asm.ClassReader(loadedBytes).accept(loaded, 0);
			for (org.objectweb.asm.tree.MethodNode m : loaded.methods) {
				if ("<init>".equals(m.name) && !present.contains(m.desc)) {
					fresh.methods.add(m);
					present.add(m.desc);
					retained.add(m.desc);
				}
			}
			return retained;
		}
		return retained;   // 没有旧字节：没有可保留的基线构造器（首次加载路径）
	}

	private static String shortHash(String text) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				sb.append(String.format("%02x", hash[i]));
			}
			return sb.toString();
		} catch (Throwable t) {
			return "h" + Integer.toHexString(text.hashCode());
		}
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

	/**
	 * 候选字节里相对已加载字段表<b>新增</b>的字段签名（{@code fresh - loaded}）。
	 *
	 * <p>必须在 {@link #padFields} 之前调用：补齐会给候选追加墓碑字段，之后就分不清"新增"与
	 * "基线槽位补墓碑"。</p>
	 */
	static List<String> appendedFields(ClassNode fresh, List<String> loaded) {
		List<String> out = new ArrayList<>();
		List<String> baseline = loaded == null ? java.util.List.of() : loaded;
		for (String key : nodeFields(fresh)) {
			if (!baseline.contains(key)) {
				out.add(key);
			}
		}
		return out;
	}

	/**
	 * 一次合成类重定义相对上一版（已加载）的字段表变化分类（战报的"逐次拆分"口径）。
	 *
	 * <p>四类：{@code TYPE_CHANGE}（同名异型字段被重命名追加）&gt; {@code WOULD_SHRINK}
	 * （基线字段靠墓碑补齐，否则会缩容）&gt; {@code APPEND_ONLY}（只追加新字段）&gt;
	 * {@code UNCHANGED}（字段表无变化）。优先级只影响"同时命中多类"时的单值标签，计数三项都在
	 * 括号里给出，可据此复核。</p>
	 */
	static String describeFieldTableChange(List<String> appended, java.util.Map<String, String> renamed,
			List<String> padded) {
		int appendedCount = appended == null ? 0 : appended.size();
		int renamedCount = renamed == null ? 0 : renamed.size();
		int paddedCount = padded == null ? 0 : padded.size();
		String kind;
		if (renamedCount > 0) {
			kind = "TYPE_CHANGE";
		} else if (paddedCount > 0) {
			kind = "WOULD_SHRINK";
		} else if (appendedCount > 0) {
			kind = "APPEND_ONLY";
		} else {
			kind = "UNCHANGED";
		}
		return kind + "(appended=" + appendedCount + ", renamed=" + renamedCount
			+ ", tombstone=" + paddedCount + ")";
	}
}
