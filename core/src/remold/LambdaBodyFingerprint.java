package remold;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成方法体的逐条等价归一化指纹计算器。
 *
 * <p>脱离常量池索引、重编号局部变量槽位与跳转标签、屏蔽本类不稳定匿名类/合成方法后缀，
 * 并执行一元/二元纯常量折叠，为 {@link LambdaAligner} 的兜底配对提供方法体等价证明。</p>
 */
final class LambdaBodyFingerprint {
	private LambdaBodyFingerprint() {}

	/** 测试钩子：对给定类字节里 {@code name+desc} 的方法计算体指纹（取不到返回 0）。 */
	static long bodyFingerprintForTest(byte[] classBytes, String name, String desc) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return bodyFingerprint(mn, cn.name);
		}
		return 0L;
	}

	/** 测试钩子：把两个常量 token 交给 {@link #foldBinary}，返回折叠结果的位模式；不可折叠返回 {@code null}。 */
	static Long foldBinaryForTest(int aKind, long aBits, int bKind, long bBits, int op) {
		BodyTok r = foldBinary(BodyTok.constant(aKind, aBits), BodyTok.constant(bKind, bBits), op);
		return r == null ? null : r.a;
	}

	static long bodyFingerprint(MethodNode mn, String owner) {
		if (mn == null || mn.instructions == null) return 0L;

		List<BodyTok>           toks     = new ArrayList<>(mn.instructions.size() + 8);
		Map<LabelNode, Integer> labelIds = new IdentityHashMap<>();
		Map<Integer, Integer>   slotIds  = new HashMap<>();
		Map<String, Integer>    anonIds  = new HashMap<>();

		// 异常表先编码（与 MethodFingerprinter 的 visitTryCatchBlock 先行一致，纯结构信息）
		if (mn.tryCatchBlocks != null) {
			for (TryCatchBlockNode tb : mn.tryCatchBlocks) {
				long h = mix64(0x7F000014L);
				h = mix64(h ^ labelId(tb.start, labelIds));
				h = mix64(h ^ labelId(tb.end, labelIds));
				h = mix64(h ^ labelId(tb.handler, labelIds));
				h = mix64(h ^ hashName(maskName(tb.type, owner, anonIds)));
				toks.add(BodyTok.other(OpcodeConst.TRY_CATCH, h));
			}
		}

		for (AbstractInsnNode insn : mn.instructions) {
			int op = insn.getOpcode();
			if (op < 0) continue;                       // Label / LineNumber / Frame

			// —— 常量：只按值编码（常量池索引不参与）——
			if (insn instanceof InsnNode) {
				BodyTok c = smallConst(op);
				if (c != null) {
					toks.add(c);
					continue;
				}
			}
			if (insn instanceof IntInsnNode ii && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
				toks.add(BodyTok.constant(BodyTok.K_INT, ii.operand));
				continue;
			}
			if (insn instanceof LdcInsnNode ldc) {
				BodyTok c = ldcConst(ldc.cst);
				if (c != null) {
					toks.add(c);
					continue;
				}
				toks.add(BodyTok.other(op, mix64(0x7F000010L)
				                         ^ mix64(hashCst(ldc.cst, owner, anonIds))));
				continue;
			}

			// —— 变量槽位：按首次出现顺序重编号 ——
			if (insn instanceof VarInsnNode v) {
				toks.add(BodyTok.other(op, slotId(v.var, slotIds)));
				continue;
			}
			if (insn instanceof IincInsnNode inc) {
				toks.add(BodyTok.other(OpcodeConst.IINC,
				                       mix64(slotId(inc.var, slotIds) ^ mix64(inc.incr))));
				continue;
			}

			if (insn instanceof MethodInsnNode m) {
				long h = mix64(hashName(maskName(m.owner, owner, anonIds)));
				h = mix64(h ^ hashName(maskDesc(m.desc, owner, anonIds)));
				h = mix64(h ^ (MethodFingerprinter.isSyntheticName(m.name)
				               ? hashName("#SYNTHETIC_METHOD#") : hashName(m.name)));
				h = mix64(h ^ (m.itf ? 1 : 0));
				toks.add(BodyTok.other(op, h));
				continue;
			}
			if (insn instanceof FieldInsnNode f) {
				// final 字段与普通字段同样处理：读的是同一个字段（owner+name+desc 逐字符相同）才等价。
				long h = mix64(hashName(maskName(f.owner, owner, anonIds)));
				h = mix64(h ^ hashName(f.name));
				h = mix64(h ^ hashName(maskDesc(f.desc, owner, anonIds)));
				toks.add(BodyTok.other(op, h));
				continue;
			}
			if (insn instanceof TypeInsnNode t) {
				toks.add(BodyTok.other(op, hashName(maskDesc(t.desc, owner, anonIds))));
				continue;
			}
			if (insn instanceof InvokeDynamicInsnNode indy) {
				long h = mix64(hashName(indy.name));
				h = mix64(h ^ hashName(maskDesc(indy.desc, owner, anonIds)));
				if (indy.bsm != null) {
					h = mix64(h ^ hashName(indy.bsm.getOwner()));
					h = mix64(h ^ hashName(indy.bsm.getName()));
					h = mix64(h ^ hashName(indy.bsm.getDesc()));
				}
				if (indy.bsmArgs != null) {
					for (Object arg : indy.bsmArgs) {
						h = mix64(h ^ hashCst(arg, owner, anonIds));
					}
				}
				toks.add(BodyTok.other(OpcodeConst.INDY, h));
				continue;
			}
			if (insn instanceof JumpInsnNode j) {
				toks.add(BodyTok.other(op, labelId(j.label, labelIds)));
				continue;
			}
			if (insn instanceof TableSwitchInsnNode ts) {
				long h = mix64(labelId(ts.dflt, labelIds));
				h = mix64(h ^ ts.min);
				h = mix64(h ^ ts.max);
				for (LabelNode l : ts.labels) h = mix64(h ^ labelId(l, labelIds));
				toks.add(BodyTok.other(OpcodeConst.TABLESWITCH, h));
				continue;
			}
			if (insn instanceof LookupSwitchInsnNode ls) {
				long h = mix64(labelId(ls.dflt, labelIds));
				for (int i = 0; i < ls.keys.size(); i++) {
					h = mix64(h ^ ls.keys.get(i));
					h = mix64(h ^ labelId(ls.labels.get(i), labelIds));
				}
				toks.add(BodyTok.other(OpcodeConst.LOOKUPSWITCH, h));
				continue;
			}
			if (insn instanceof MultiANewArrayInsnNode ma) {
				toks.add(BodyTok.other(op, mix64(hashName(maskDesc(ma.desc, owner, anonIds)) ^ mix64(ma.dims))));
				continue;
			}
			toks.add(BodyTok.other(op, 0L));
		}

		foldConstants(toks);

		long h = 0x9E3779B97F4A7C15L;
		for (BodyTok t : toks) {
			h = mix64(h ^ t.kind);
			h = mix64(h ^ t.opcode);
			h = mix64(h ^ t.a);
			h = mix64(h ^ t.b);
		}
		return h == 0L ? 1L : h;                        // 0 保留为"未知"哨兵
	}

	/** 常量折叠：把"常量, 常量, 可折叠算子"三元组折叠成单个常量，直到不再变化。 */
	private static void foldConstants(List<BodyTok> toks) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = 0; i + 1 < toks.size(); i++) {
				BodyTok x = toks.get(i);
				if (!x.isConstant()) continue;
				BodyTok y = toks.get(i + 1);
				// 一元
				if (!y.isConstant() && y.opcode == Opcodes.I2L && x.kind == BodyTok.K_INT) {
					toks.set(i, BodyTok.constant(BodyTok.K_LONG, (int) x.a));
					toks.remove(i + 1);
					changed = true;
					break;
				}
				if (!y.isConstant() && y.opcode == Opcodes.L2I && x.kind == BodyTok.K_LONG) {
					toks.set(i, BodyTok.constant(BodyTok.K_INT, (int) x.a));
					toks.remove(i + 1);
					changed = true;
					break;
				}
				if (!y.isConstant() && y.opcode == Opcodes.INEG && x.kind == BodyTok.K_INT) {
					toks.set(i, BodyTok.constant(BodyTok.K_INT, -(int) x.a));
					toks.remove(i + 1);
					changed = true;
					break;
				}
				if (!y.isConstant() && y.opcode == Opcodes.LNEG && x.kind == BodyTok.K_LONG) {
					toks.set(i, BodyTok.constant(BodyTok.K_LONG, -x.a));
					toks.remove(i + 1);
					changed = true;
					break;
				}
				// 二元
				if (i + 2 >= toks.size()) continue;
				BodyTok z = toks.get(i + 2);
				if (z.isConstant()) continue;
				BodyTok folded = foldBinary(x, y, z.opcode);
				if (folded == null) continue;
				toks.set(i, folded);
				toks.remove(i + 2);
				toks.remove(i + 1);
				changed = true;
				break;
			}
		}
	}

	/** 对两个同类型常量执行可折叠二元运算；不可折叠返回 {@code null}。 */
	private static BodyTok foldBinary(BodyTok a, BodyTok b, int op) {
		// long 移位的右操作数按 JLS 固定是 int（K_INT），不能要求两侧都是 K_LONG。
		if (a.kind == BodyTok.K_LONG && b.kind == BodyTok.K_INT
		    && (op == Opcodes.LSHL || op == Opcodes.LSHR || op == Opcodes.LUSHR)) {
			long x = a.a;
			int y = (int) b.a;
			return switch (op) {
				case Opcodes.LSHL -> BodyTok.constant(BodyTok.K_LONG, x << y);
				case Opcodes.LSHR -> BodyTok.constant(BodyTok.K_LONG, x >> y);
				default -> BodyTok.constant(BodyTok.K_LONG, x >>> y);
			};
		}
		boolean isInt  = a.kind == BodyTok.K_INT && b.kind == BodyTok.K_INT;
		boolean isLong = a.kind == BodyTok.K_LONG && b.kind == BodyTok.K_LONG;
		if (!isInt && !isLong) return null;
		if (isInt) {
			int x = (int) a.a, y = (int) b.a;
			return switch (op) {
				case Opcodes.IADD -> BodyTok.constant(BodyTok.K_INT, x + y);
				case Opcodes.ISUB -> BodyTok.constant(BodyTok.K_INT, x - y);
				case Opcodes.IMUL -> BodyTok.constant(BodyTok.K_INT, x * y);
				case Opcodes.IXOR -> BodyTok.constant(BodyTok.K_INT, x ^ y);
				case Opcodes.IAND -> BodyTok.constant(BodyTok.K_INT, x & y);
				case Opcodes.IOR -> BodyTok.constant(BodyTok.K_INT, x | y);
				case Opcodes.ISHL -> BodyTok.constant(BodyTok.K_INT, x << y);
				case Opcodes.ISHR -> BodyTok.constant(BodyTok.K_INT, x >> y);
				case Opcodes.IUSHR -> BodyTok.constant(BodyTok.K_INT, x >>> y);
				default -> null;
			};
		}
		long x = a.a, y = b.a;
		return switch (op) {
			case Opcodes.LADD -> BodyTok.constant(BodyTok.K_LONG, x + y);
			case Opcodes.LSUB -> BodyTok.constant(BodyTok.K_LONG, x - y);
			case Opcodes.LMUL -> BodyTok.constant(BodyTok.K_LONG, x * y);
			case Opcodes.LXOR -> BodyTok.constant(BodyTok.K_LONG, x ^ y);
			case Opcodes.LAND -> BodyTok.constant(BodyTok.K_LONG, x & y);
			case Opcodes.LOR -> BodyTok.constant(BodyTok.K_LONG, x | y);
			case Opcodes.LSHL -> BodyTok.constant(BodyTok.K_LONG, x << y);
			case Opcodes.LSHR -> BodyTok.constant(BodyTok.K_LONG, x >> y);
			case Opcodes.LUSHR -> BodyTok.constant(BodyTok.K_LONG, x >>> y);
			default -> null;
		};
	}

	/** {@code iconst_*} / {@code lconst_*} / {@code fconst_*} / {@code dconst_*} 归一成常量 token。 */
	private static BodyTok smallConst(int op) {
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
			return BodyTok.constant(BodyTok.K_INT, op - Opcodes.ICONST_0);
		}
		if (op == Opcodes.LCONST_0) return BodyTok.constant(BodyTok.K_LONG, 0L);
		if (op == Opcodes.LCONST_1) return BodyTok.constant(BodyTok.K_LONG, 1L);
		if (op == Opcodes.FCONST_0) return BodyTok.constant(BodyTok.K_FLOAT, Float.floatToRawIntBits(0f));
		if (op == Opcodes.FCONST_1) return BodyTok.constant(BodyTok.K_FLOAT, Float.floatToRawIntBits(1f));
		if (op == Opcodes.FCONST_2) return BodyTok.constant(BodyTok.K_FLOAT, Float.floatToRawIntBits(2f));
		if (op == Opcodes.DCONST_0) return BodyTok.constant(BodyTok.K_DOUBLE, Double.doubleToRawLongBits(0d));
		if (op == Opcodes.DCONST_1) return BodyTok.constant(BodyTok.K_DOUBLE, Double.doubleToRawLongBits(1d));
		return null;
	}

	/** {@code ldc} 常量归一：数字/字符串按值，其余返回 {@code null} 走普通 token。 */
	private static BodyTok ldcConst(Object cst) {
		return switch (cst) {
			case Integer i -> BodyTok.constant(BodyTok.K_INT, i);
			case Long l -> BodyTok.constant(BodyTok.K_LONG, l);
			case Float f -> BodyTok.constant(BodyTok.K_FLOAT, Float.floatToRawIntBits(f));
			case Double d -> BodyTok.constant(BodyTok.K_DOUBLE, Double.doubleToRawLongBits(d));
			case String s -> BodyTok.constant(BodyTok.K_STRING, hashName(s));
			default -> null;
		};
	}

	/** 常量/操作数的值哈希（Type/Handle/ConstantDynamic 也按值编码）。 */
	private static long hashCst(Object cst, String owner, Map<String, Integer> anonIds) {
		if (cst == null) return 0L;
		if (cst instanceof Integer i) return mix64(i);
		if (cst instanceof Long l) return mix64(l);
		if (cst instanceof Float f) return mix64(Float.floatToRawIntBits(f));
		if (cst instanceof Double d) return mix64(Double.doubleToRawLongBits(d));
		if (cst instanceof String s) return hashName(s);
		if (cst instanceof Type t) return hashName(maskDesc(t.getDescriptor(), owner, anonIds));
		if (cst instanceof Handle hd) {
			long h = hashName(maskName(hd.getOwner(), owner, anonIds));
			h = mix64(h ^ (owner.equals(hd.getOwner()) && MethodFingerprinter.isSyntheticName(hd.getName())
				? hashName("#SYNTHETIC_METHOD#") : hashName(hd.getName())));
			h = mix64(h ^ hashName(maskDesc(hd.getDesc(), owner, anonIds)));
			return mix64(h ^ hd.getTag());
		}
		if (cst instanceof ConstantDynamic cd) {
			long h = hashName(cd.getName());
			h = mix64(h ^ hashName(maskDesc(cd.getDescriptor(), owner, anonIds)));
			h = mix64(h ^ hashCst(cd.getBootstrapMethod(), owner, anonIds));
			for (int i = 0; i < cd.getBootstrapMethodArgumentCount(); i++) {
				h = mix64(h ^ hashCst(cd.getBootstrapMethodArgument(i), owner, anonIds));
			}
			return h;
		}
		return hashName(String.valueOf(cst));
	}

	/** 槽位归一：按首次出现顺序重编号（捕获表增删造成的整体位移不参与指纹）。 */
	private static long slotId(int var, Map<Integer, Integer> slotIds) {
		Integer id = slotIds.get(var);
		if (id == null) {
			id = slotIds.size();
			slotIds.put(var, id);
		}
		return mix64(0x7F000018L ^ id);
	}

	/** 跳转目标归一：按首次引用顺序重编号。 */
	private static long labelId(LabelNode label, Map<LabelNode, Integer> labelIds) {
		if (label == null) return -1L;
		Integer id = labelIds.get(label);
		if (id == null) {
			id = labelIds.size();
			labelIds.put(label, id);
		}
		return mix64(0x7F000017L ^ id);
	}

	/** 把本类的匿名/局部类序号归一成方法内相对 ID（{@code Owner$1} → {@code #ANON_0#}）。 */
	private static String maskName(String name, String owner, Map<String, Integer> anonIds) {
		if (name == null || owner == null) return name;
		if (name.startsWith("[")) {
			int dims = 0;
			while (dims < name.length() && name.charAt(dims) == '[') dims++;
			if (dims < name.length() && name.charAt(dims) == 'L' && name.endsWith(";")) {
				String inner  = name.substring(dims + 1, name.length() - 1);
				String masked = maskName(inner, owner, anonIds);
				return masked.equals(inner) ? name : name.substring(0, dims) + "L" + masked + ";";
			}
			return name;
		}
		String prefix = owner + "$";
		if (!name.startsWith(prefix)) return name;
		String suffix = name.substring(prefix.length());
		if (!MethodFingerprinter.isUnstableNestedSuffix(suffix)) return name;
		Integer id = anonIds.get(name);
		if (id == null) {
			id = anonIds.size();
			anonIds.put(name, id);
		}
		return prefix + "#ANON_" + id + "#";
	}

	/** 描述符里的匿名类序号归一（只改本类不稳定后缀，普通类型原样保留）。 */
	private static String maskDesc(String desc, String owner, Map<String, Integer> anonIds) {
		if (desc == null || owner == null || desc.indexOf('$') < 0) return desc;
		String marker = "L" + owner + "$";
		if (!desc.contains(marker)) return desc;
		StringBuilder sb  = new StringBuilder(desc.length() + 8);
		int           pos = 0;
		while (true) {
			int idx = desc.indexOf(marker, pos);
			if (idx < 0) { sb.append(desc, pos, desc.length()); break; }
			int end = desc.indexOf(';', idx);
			if (end < 0) { sb.append(desc, pos, desc.length()); break; }
			sb.append(desc, pos, idx);
			sb.append('L').append(maskName(desc.substring(idx + 1, end), owner, anonIds)).append(';');
			pos = end + 1;
		}
		return sb.toString();
	}

	private static long mix64(long v) {
		v ^= v >>> 33;
		v *= 0xFF51AFD7ED558CCDL;
		v ^= v >>> 33;
		v *= 0xC4CEB9FE1A85EC53L;
		v ^= v >>> 33;
		return v;
	}

	private static long hashName(String s) {
		if (s == null) return -1L;
		long h = 0x9E3779B97F4A7C15L ^ s.length();
		for (int i = 0; i < s.length(); i++) h = mix64(h ^ s.charAt(i));
		return h;
	}

	/** 体指纹 token。 */
	private static final class BodyTok {
		static final int K_OTHER  = 0;
		static final int K_INT    = 1;
		static final int K_LONG   = 2;
		static final int K_FLOAT  = 3;
		static final int K_DOUBLE = 4;
		static final int K_STRING = 5;

		final int  kind;
		final int  opcode;
		final long a;
		final long b;

		private BodyTok(int kind, int opcode, long a, long b) {
			this.kind = kind;
			this.opcode = opcode;
			this.a = a;
			this.b = b;
		}

		static BodyTok other(int opcode, long operandHash) {
			return new BodyTok(K_OTHER, opcode, operandHash, 0L);
		}

		static BodyTok constant(int kind, long bits) {
			return new BodyTok(kind, -1, bits, 0L);
		}

		boolean isConstant() {
			return kind != K_OTHER;
		}
	}

	/** 非标准操作码占位（异常表 / invokedynamic / switch），避免与真实 JVM 操作码撞值。 */
	private static final class OpcodeConst {
		static final int TRY_CATCH    = 0x7F000014;
		static final int INDY         = 0x7F000015;
		static final int TABLESWITCH  = 0x7F000012;
		static final int LOOKUPSWITCH = 0x7F000013;
		static final int IINC         = 0x7F000011;
	}
}
