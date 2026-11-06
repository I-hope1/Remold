package remold.ref;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import remold.RemoldAgent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L0: report changes to an <b>existing</b> field's initializer expression.
 *
 * <p>InitFix deliberately only patches fields added in this redefine. A changed
 * initializer on an existing field is therefore not replayed on live instances. This
 * detector makes that divergence visible with one warn per class; it never writes a
 * patch and never touches FieldLedger.</p>
 *
 * <p>It compares per-field write slices from &lt;init&gt;, not the whole constructor.
 * The PUTFIELD owner is intentionally not restricted to the class itself: a
 * double-brace anonymous class writes an inherited field as
 * {@code PUTFIELD Parent.field}.</p>
 */
public final class InitializerDrift {

	private static final String REINIT_DESC = "Lremold/annotation/RemoldReinit;";

	/**
	 * 诊断计数：L0 尝试枚举存活实例的次数。
	 *
	 * <p>只用于探针断言"大批次聚合路径不做实例枚举"；不参与任何判定。</p>
	 */
	public static final AtomicLong INSTANCE_ENUMERATIONS = new AtomicLong();

	private InitializerDrift() {
	}

	/** One warn per class per call; no-op when old bytes are unavailable. */
	public static void report(Map<String, byte[]> newBatchBytes) {
		report(newBatchBytes, null);
	}

	public static void report(Map<String, byte[]> newBatchBytes, Map<String, byte[]> oldBytesByClass) {
		if (newBatchBytes == null || newBatchBytes.isEmpty()) return;
		Set<String> reinitFields = new HashSet<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			ClassNode node = read(e.getValue());
			if (node == null) continue;
			for (FieldNode f : node.fields) {
				if (hasReinit(f)) reinitFields.add(node.name + "." + f.name);
			}
		}
		List<DriftEntry> entries = new ArrayList<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			String className = e.getKey();
			byte[] newBytes = e.getValue();
			if (className == null || newBytes == null) continue;
			byte[] oldBytes = oldBytesByClass != null
				? oldBytesByClass.get(className) : RemoldAgent.bytecodeCache.get(className);
			if (oldBytes == null) continue;
			Drift drift;
			try {
				drift = detectDetailed(oldBytes, newBytes, reinitFields);
			} catch (Throwable t) {
				continue;
			}
			if (drift.changed().isEmpty() && drift.inherited().isEmpty()
				&& drift.inheritedReinit().isEmpty()) continue;

			StringBuilder text = new StringBuilder("[INIT-DRIFT] ").append(className).append(": ");
			if (!drift.inheritedReinit().isEmpty()) {
				text.append("inherited field(s) written by this class's initializer are annotated @RemoldReinit on the parent: ")
					.append(String.join(", ", drift.inheritedReinit()))
					.append(". Parent @RemoldReinit resets ALL subclass instances to the PARENT initializer;")
					.append(" it does NOT replay this class's initializer value. Do not use @RemoldReinit on inherited fields")
					.append(" when the anonymous subclass sets its own value; 不会重放，请手动处理（或把值放到子类自己声明的字段上）。");
			} else if (!drift.inherited().isEmpty()) {
				text.append("inherited field initializer changed but will NOT be replayed: ")
					.append(String.join(", ", drift.inherited()))
					.append(" (default InitFix only patches added fields; 不会重放，请手动处理（或把值放到子类自己声明的字段上）。")
					.append(" Do NOT use parent @RemoldReinit: it resets all subclass instances to the parent initializer.)");
			} else {
				text.append("existing field initializer changed but will NOT be replayed: ")
					.append(String.join(", ", drift.changed()))
					.append(" (default InitFix only patches added fields; 不会重放，请手动处理（如需重置，标 @RemoldReinit）)");
			}
			entries.add(new DriftEntry(className, text.toString()));
		}
		if (entries.size() > 5) {
			// 大批次刻意不枚举实例：6 个类以上时 per-class JVMTI 堆遍历可能是数百毫秒，
			// 而"这一轮有 N 个类初值变化了"本身就是可行动的信号，不值得为此扫堆。
			StringBuilder summary = new StringBuilder("[INIT-DRIFT] ")
				.append(entries.size()).append(" classes have initializer drift this round")
				.append(" (aggregated; 实例数未统计, instance enumeration skipped): ");
			for (int i = 0; i < entries.size(); i++) {
				if (i > 0) summary.append("; ");
				summary.append(entries.get(i).text());
			}
			RemoldAgent.warn(summary.toString());
			return;
		}
		for (DriftEntry entry : entries) {
			// Enumerate only after drift is detected; this is the expensive JVMTI step.
			long t0 = System.nanoTime();
			int instances = -1;
			try {
				Class<?> clazz = RemoldAgent.loadedClassesMap.get(entry.className);
				if (clazz != null) {
					INSTANCE_ENUMERATIONS.incrementAndGet();
					instances = remold.util.LibTool.getInstances(clazz).length;
				}
			} catch (Throwable ignored) {
			}
			long enumUs = (System.nanoTime() - t0) / 1_000;
			String text = entry.text + " [live instances=" + (instances < 0 ? "unknown" : instances)
				+ ", enum=" + enumUs + "us]";
			if (instances != 0) RemoldAgent.warn(text);
			else RemoldAgent.info(text);
		}
	}

	private record DriftEntry(String className, String text) {
	}

	public record Drift(List<String> changed, List<String> inherited, List<String> inheritedReinit) {
	}

	public static List<String> detect(byte[] oldBytes, byte[] newBytes) {
		Drift d = detectDetailed(oldBytes, newBytes, Set.of());
		List<String> all = new ArrayList<>(d.changed());
		all.addAll(d.inherited());
		all.addAll(d.inheritedReinit());
		return all;
	}

	public static Drift detectDetailed(byte[] oldBytes, byte[] newBytes, Set<String> reinitFields) {
		ClassNode oldNode = read(oldBytes);
		ClassNode newNode = read(newBytes);
		if (oldNode == null || newNode == null) return new Drift(List.of(), List.of(), List.of());

		Map<String, FieldNode> oldFields = fields(oldNode);
		Map<String, FieldNode> newFields = fields(newNode);
		Map<String, Set<String>> oldSlices = slices(oldNode, oldFields);
		Map<String, Set<String>> newSlices = slices(newNode, newFields);

		List<String> changed = new ArrayList<>();
		List<String> inherited = new ArrayList<>();
		List<String> inheritedReinit = new ArrayList<>();
		Set<String> names = new HashSet<>(oldSlices.keySet());
		names.addAll(newSlices.keySet());
		for (String name : names) {
			if (name.startsWith("this$") || name.startsWith("val$")) continue;
			FieldNode oldField = oldFields.get(name);
			FieldNode newField = newFields.get(name);
			if (oldField != null && newField != null) {
				if (!oldField.desc.equals(newField.desc)) continue;
				if (isSynthetic(oldField) || isSynthetic(newField)) continue;
			}
			Set<String> oldSet = oldSlices.getOrDefault(name, Set.of());
			Set<String> newSet = newSlices.getOrDefault(name, Set.of());
			if (oldSet.isEmpty() || newSet.isEmpty()) continue;
			if (oldSet.equals(newSet)) continue;

			boolean hostDeclared = newFields.containsKey(name);
			boolean hostAnnotated = reinitFields.contains(newNode.name + "." + name);
			if (hostDeclared && hostAnnotated) continue; // explicit host-level reinit: no warning

			boolean parentAnnotated = reinitFields.stream().anyMatch(s -> s.endsWith("." + name));
			if (!hostDeclared && parentAnnotated) {
				inheritedReinit.add(name);
			} else if (!hostDeclared) {
				inherited.add(name);
			} else {
				changed.add(name);
			}
		}
		return new Drift(changed, inherited, inheritedReinit);
	}


	private static ClassNode read(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (Throwable t) {
			return null;
		}
	}

	private static Map<String, FieldNode> fields(ClassNode node) {
		Map<String, FieldNode> map = new HashMap<>();
		for (FieldNode f : node.fields) {
			if ((f.access & Opcodes.ACC_STATIC) != 0) continue;
			map.put(f.name, f);
		}
		return map;
	}

	private static boolean isSynthetic(FieldNode f) {
		return (f.access & Opcodes.ACC_SYNTHETIC) != 0 || f.name.startsWith("this$") || f.name.startsWith("val$");
	}

	private static boolean hasReinit(FieldNode f) {
		if (f.visibleAnnotations != null) {
			for (var a : f.visibleAnnotations) if (REINIT_DESC.equals(a.desc)) return true;
		}
		if (f.invisibleAnnotations != null) {
			for (var a : f.invisibleAnnotations) if (REINIT_DESC.equals(a.desc)) return true;
		}
		return false;
	}

	private static String ownerOf(ClassNode node, String fieldName) {
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || m.instructions == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD
					&& fieldName.equals(fi.name)) return fi.owner;
			}
		}
		return null;
	}

	private static Map<String, Set<String>> slices(ClassNode node, Map<String, FieldNode> fields) {
		Map<String, Set<String>> out = new HashMap<>();
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || m.instructions == null) continue;
			List<AbstractInsnNode> insns = new ArrayList<>();
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				insns.add(insn);
			}
			for (int i = 0; i < insns.size(); i++) {
				AbstractInsnNode insn = insns.get(i);
				if (!(insn instanceof FieldInsnNode fi) || fi.getOpcode() != Opcodes.PUTFIELD) continue;
				if (fi.name.startsWith("this$") || fi.name.startsWith("val$")) continue;
				String slice = slice(insns, i);
				if (!slice.isEmpty()) out.computeIfAbsent(fi.name, k -> new LinkedHashSet<>()).add(slice);
			}
		}
		return out;
	}

	/** Collect the write expression from the previous statement boundary to the PUTFIELD. */
	private static String slice(List<AbstractInsnNode> insns, int putIndex) {
		int start = putIndex;
		while (start > 0) {
			AbstractInsnNode prev = insns.get(start - 1);
			int op = prev.getOpcode();
			if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC
				|| op == Opcodes.RETURN || op == Opcodes.ARETURN
				|| op == Opcodes.IRETURN || op == Opcodes.LRETURN
				|| op == Opcodes.FRETURN || op == Opcodes.DRETURN
				|| op == Opcodes.ATHROW) {
				break;
			}
			if (prev instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL
				&& "<init>".equals(mi.name)) {
				break;
			}
			start--;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = start; i <= putIndex; i++) {
			if (sb.length() > 0) sb.append(';');
			sb.append(normalize(insns.get(i)));
		}
		return sb.toString();
	}

	private static String normalize(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (insn instanceof FieldInsnNode f) return op + ":" + f.owner + "." + f.name + ":" + f.desc;
		if (insn instanceof MethodInsnNode m) return op + ":" + m.owner + "." + m.name + m.desc;
		if (insn instanceof LdcInsnNode l) return op + ":" + l.cst;
		if (insn instanceof IntInsnNode i) return op + ":" + i.operand;
		if (insn instanceof VarInsnNode v) return op + ":" + v.var;
		if (insn instanceof TypeInsnNode t) return op + ":" + t.desc;
		if (insn instanceof InvokeDynamicInsnNode d) return op + ":" + d.name + d.desc;
		return Integer.toString(op);
	}
}
