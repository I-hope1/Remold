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

	private InitializerDrift() {
	}

	/** One warn per class per call; no-op when old bytes are unavailable. */
	public static void report(Map<String, byte[]> newBatchBytes) {
		if (newBatchBytes == null || newBatchBytes.isEmpty()) return;
		Set<String> reinitFields = new HashSet<>();
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			ClassNode node = read(e.getValue());
			if (node == null) continue;
			for (FieldNode f : node.fields) {
				if (hasReinit(f)) {
					reinitFields.add(node.name + "." + f.name);
					reinitFields.add(f.name);
				}
			}
		}
		for (Map.Entry<String, byte[]> e : newBatchBytes.entrySet()) {
			String className = e.getKey();
			byte[] newBytes = e.getValue();
			if (className == null || newBytes == null) continue;
			byte[] oldBytes = RemoldAgent.bytecodeCache.get(className);
			if (oldBytes == null) continue;
			List<String> changed;
			try {
				changed = detect(oldBytes, newBytes, reinitFields);
			} catch (Throwable t) {
				continue;
			}
			if (!changed.isEmpty()) {
				RemoldAgent.warn("[INIT-DRIFT] " + className
					+ ": existing field initializer changed but will NOT be replayed: "
					+ String.join(", ", changed)
					+ " (default InitFix only patches added fields; use @RemoldReinit or @OnReload to reset)");
			}
		}
	}

	/**
	 * Returns the names of fields present in both versions whose &lt;init&gt; write
	 * slice changed. Added/removed fields and synthetic/@RemoldReinit fields are excluded.
	 */
	public static List<String> detect(byte[] oldBytes, byte[] newBytes) {
		return detect(oldBytes, newBytes, Set.of());
	}

	public static List<String> detect(byte[] oldBytes, byte[] newBytes, Set<String> reinitFields) {
		ClassNode oldNode = read(oldBytes);
		ClassNode newNode = read(newBytes);
		if (oldNode == null || newNode == null) return List.of();

		Map<String, FieldNode> oldFields = fields(oldNode);
		Map<String, FieldNode> newFields = fields(newNode);
		Map<String, Set<String>> oldSlices = slices(oldNode, oldFields, reinitFields);
		Map<String, Set<String>> newSlices = slices(newNode, newFields, reinitFields);

		List<String> changed = new ArrayList<>();
		Set<String> names = new HashSet<>(oldSlices.keySet());
		names.addAll(newSlices.keySet());
		for (String name : names) {
			if (name.startsWith("this$") || name.startsWith("val$")) continue;
			FieldNode oldField = oldFields.get(name);
			FieldNode newField = newFields.get(name);
			if (oldField != null && newField != null) {
				if (!oldField.desc.equals(newField.desc)) continue; // type change handled elsewhere
				if (isSynthetic(oldField) || isSynthetic(newField)) continue;
				if (hasReinit(oldField) || hasReinit(newField)) continue;
			}
			Set<String> oldSet = oldSlices.getOrDefault(name, Set.of());
			Set<String> newSet = newSlices.getOrDefault(name, Set.of());
			// Added field: only the new side has a write. Removed: only the old side.
			if (oldSet.isEmpty() || newSet.isEmpty()) continue;
			if (!oldSet.equals(newSet)) changed.add(name);
		}
		return changed;
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

	private static Map<String, Set<String>> slices(ClassNode node, Map<String, FieldNode> fields,
		Set<String> reinitFields) {
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
				if (reinitFields.contains(fi.owner + "." + fi.name) || reinitFields.contains(fi.name)) continue;
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
