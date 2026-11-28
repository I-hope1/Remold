package remold.ref;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Normalized bytecode fingerprint used by the value-type replay channel.
 *
 * <p>The fingerprint covers the semantic content of a class or method: access flags, class / field /
 * method headers, the referenced semantic constants (including raw bit patterns for {@code float} /
 * {@code double}), the instruction sequence, the exception table and the {@code StackMapTable}. Debug
 * attributes are deliberately <b>not</b> covered, so adding blank lines / comments / renaming local
 * variables / moving the source file cannot change a hash (see {@code docs/initfix/14-value-type-new-replay.md}
 * section 4).</p>
 *
 * <p>This class is pure analysis code: it never touches {@code RemoldAgent}, class loaders or any
 * runtime state.</p>
 */
public final class BytecodeNormalFingerprint {

	private BytecodeNormalFingerprint() {
	}

	/** Parses {@code classBytes} with all attributes and returns its normalized SHA-256 (hex). */
	public static String of(byte[] classBytes) {
		Objects.requireNonNull(classBytes, "classBytes");
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		return of(node);
	}

	/**
	 * Normalized SHA-256 (hex) of the given node.
	 *
	 * <p>Only attributes present in {@code node} are covered: a node parsed with
	 * {@code ClassReader.SKIP_FRAMES} has no stack map frames and therefore hashes differently from a
	 * full parse. Callers that need a stable, environment-independent value should use {@link #of(byte[])}
	 * or parse the raw bytes without skip flags.</p>
	 */
	public static String of(ClassNode node) {
		Objects.requireNonNull(node, "node");
		return sha256(normalized(node));
	}

	/** Normalized SHA-256 (hex) of one method (instructions + exception table + stack map frames). */
	public static String ofMethod(MethodNode method) {
		Objects.requireNonNull(method, "method");
		return sha256(normalizedMethod(method));
	}

	/** Normalized SHA-256 (hex) of an arbitrary text; exposed for compositional fingerprints. */
	public static String sha256Hex(String text) {
		return sha256(Objects.requireNonNull(text, "text"));
	}

	/** Normalized description of a method without hashing it (used to compose chain fingerprints). */
	static String normalizedMethod(MethodNode method) {
		StringBuilder sb = new StringBuilder();
		Map<LabelNode, Integer> labels = labelIds(method);
		int realIndex = 0;
		sb.append("method|").append(method.access).append('|').append(method.name).append('|')
		  .append(method.desc).append('\n');
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
			if (insn instanceof LabelNode label) {
				Integer id = labels.get(label);
				if (id != null) sb.append("  label ").append(id).append('\n');
				continue;
			}
			if (insn.getOpcode() < 0) continue;
			sb.append("  ").append(realIndex++).append(' ').append(describe(insn, labels)).append('\n');
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
				sb.append("  try ").append(labelId(tcb.start, labels)).append(' ')
				  .append(labelId(tcb.end, labels)).append(' ')
				  .append(labelId(tcb.handler, labels)).append(' ')
				  .append(tcb.type == null ? "any" : tcb.type).append('\n');
			}
		}
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FrameNode frame)) continue;
			sb.append("  frame ").append(frame.type).append(" locals[")
			  .append(joinFrame(frame.local, labels)).append("] stack[")
			  .append(joinFrame(frame.stack, labels)).append("]\n");
		}
		return sb.toString();
	}

	private static String normalized(ClassNode node) {
		StringBuilder sb = new StringBuilder();
		sb.append("class|").append(node.access).append('|').append(node.name).append('|')
		  .append(node.superName).append('|').append(node.interfaces == null ? "" : String.join(",", node.interfaces))
		  .append('\n');
		if (node.recordComponents != null) {
			List<String> components = new ArrayList<>();
			for (var component : node.recordComponents) {
				components.add(component.name + "|" + component.descriptor);
			}
			components.sort(String::compareTo);
			for (String component : components) sb.append("record-component|").append(component).append('\n');
		}
		List<FieldNode> fields = new ArrayList<>(node.fields);
		fields.sort((a, b) -> {
			int byName = a.name.compareTo(b.name);
			return byName != 0 ? byName : a.desc.compareTo(b.desc);
		});
		for (FieldNode field : fields) {
			sb.append("field|").append(field.access).append('|').append(field.name).append('|')
			  .append(field.desc).append('|').append(constantDescription(field.value)).append('\n');
		}
		List<MethodNode> methods = new ArrayList<>(node.methods);
		methods.sort((a, b) -> {
			int byName = a.name.compareTo(b.name);
			return byName != 0 ? byName : a.desc.compareTo(b.desc);
		});
		for (MethodNode method : methods) sb.append(normalizedMethod(method));
		return sb.toString();
	}

	/**
	 * Only labels that are semantically referenced (branch / switch / exception table / stack map frame)
	 * get an id. Debug-only labels (every {@code LineNumberTable} entry carries one) are ignored, which is
	 * what keeps the fingerprint stable across {@code -g} / {@code -g:none} compilations.
	 */
	private static Map<LabelNode, Integer> labelIds(MethodNode method) {
		Set<LabelNode> referenced = newIdentitySet();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof JumpInsnNode jump) {
				referenced.add(jump.label);
			} else if (insn instanceof TableSwitchInsnNode table) {
				referenced.add(table.dflt);
				for (LabelNode label : table.labels) referenced.add(label);
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				referenced.add(lookup.dflt);
				for (LabelNode label : lookup.labels) referenced.add(label);
			} else if (insn instanceof FrameNode frame) {
				collectFrameLabels(frame.local, referenced);
				collectFrameLabels(frame.stack, referenced);
			}
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
				referenced.add(tcb.start);
				referenced.add(tcb.end);
				referenced.add(tcb.handler);
			}
		}
		Map<LabelNode, Integer> labels = new IdentityHashMap<>();
		int index = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof LabelNode label && referenced.contains(label)) labels.put(label, index++);
		}
		return labels;
	}

	private static void collectFrameLabels(List<Object> items, Set<LabelNode> out) {
		if (items == null) return;
		for (Object item : items) {
			if (item instanceof LabelNode label) out.add(label);
		}
	}

	private static Set<LabelNode> newIdentitySet() {
		return Collections.newSetFromMap(new IdentityHashMap<>());
	}

	private static String labelId(LabelNode label, Map<LabelNode, Integer> labels) {
		Integer id = labels.get(label);
		return id == null ? "?" : "L" + id;
	}

	private static String joinFrame(List<Object> items, Map<LabelNode, Integer> labels) {
		if (items == null || items.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) sb.append(',');
			Object item = items.get(i);
			if (item instanceof LabelNode label) {
				sb.append("u:").append(labelId(label, labels));
			} else if (item instanceof String text) {
				sb.append("s:").append(text);
			} else {
				sb.append("i:").append(item);
			}
		}
		return sb.toString();
	}

	private static String describe(AbstractInsnNode insn, Map<LabelNode, Integer> labels) {
		int opcode = insn.getOpcode();
		if (insn instanceof FieldInsnNode field) {
			return opcode + " field " + field.owner + '.' + field.name + ':' + field.desc;
		}
		if (insn instanceof MethodInsnNode call) {
			return opcode + " call " + call.owner + '.' + call.name + call.desc + (call.itf ? " itf" : "");
		}
		if (insn instanceof InvokeDynamicInsnNode indy) {
			StringBuilder sb = new StringBuilder();
			sb.append(opcode).append(" indy ").append(indy.name).append(indy.desc)
			  .append(" bsm=").append(handleDescription(indy.bsm));
			if (indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) sb.append(" arg=").append(constantDescription(arg));
			}
			return sb.toString();
		}
		if (insn instanceof LdcInsnNode ldc) {
			return opcode + " ldc " + constantDescription(ldc.cst);
		}
		if (insn instanceof TypeInsnNode type) {
			return opcode + " type " + type.desc;
		}
		if (insn instanceof IntInsnNode integer) {
			return opcode + " int " + integer.operand;
		}
		if (insn instanceof VarInsnNode variable) {
			return opcode + " var " + variable.var;
		}
		if (insn instanceof IincInsnNode increment) {
			return opcode + " iinc var " + increment.var + " by " + increment.incr;
		}
		if (insn instanceof JumpInsnNode jump) {
			return opcode + " jump " + labelId(jump.label, labels);
		}
		if (insn instanceof TableSwitchInsnNode table) {
			StringBuilder sb = new StringBuilder();
			sb.append(opcode).append(" tableswitch ").append(table.min).append("..").append(table.max)
			  .append(" default=").append(labelId(table.dflt, labels));
			for (LabelNode label : table.labels) sb.append(" L").append(labelId(label, labels));
			return sb.toString();
		}
		if (insn instanceof LookupSwitchInsnNode lookup) {
			StringBuilder sb = new StringBuilder();
			sb.append(opcode).append(" lookupswitch default=").append(labelId(lookup.dflt, labels));
			for (int i = 0; i < lookup.keys.size(); i++) {
				sb.append(' ').append(lookup.keys.get(i)).append("->")
				  .append(labelId(lookup.labels.get(i), labels));
			}
			return sb.toString();
		}
		if (insn instanceof MultiANewArrayInsnNode multi) {
			return opcode + " multianewarray " + multi.desc + " dims " + multi.dims;
		}
		if (insn instanceof LabelNode label) {
			return "label " + labelId(label, labels);
		}
		return Integer.toString(opcode);
	}

	private static String constantDescription(Object constant) {
		if (constant == null) return "null";
		if (constant instanceof Float value) return "f32:" + Float.floatToRawIntBits(value);
		if (constant instanceof Double value) return "f64:" + Double.doubleToRawLongBits(value);
		if (constant instanceof Long value) return "l64:" + value;
		if (constant instanceof Integer value) return "i32:" + value;
		if (constant instanceof String value) return "str:" + value;
		if (constant instanceof Type type) return "type:" + type.getDescriptor();
		if (constant instanceof Handle handle) return "handle:" + handleDescription(handle);
		if (constant instanceof ConstantDynamic dynamic) {
			StringBuilder sb = new StringBuilder("cdy:").append(dynamic.getName()).append(dynamic.getDescriptor())
				.append(" bsm=").append(handleDescription(dynamic.getBootstrapMethod()));
			for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
				sb.append(" arg=").append(constantDescription(dynamic.getBootstrapMethodArgument(i)));
			}
			return sb.toString();
		}
		return String.valueOf(constant);
	}

	private static String handleDescription(Handle handle) {
		return handle.getTag() + ':' + handle.getOwner() + '.' + handle.getName() + handle.getDesc()
		       + (handle.isInterface() ? " itf" : "");
	}

	private static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
