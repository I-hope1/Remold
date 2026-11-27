package remold.ref;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static remold.util.Diag.isDebugEnabled;
import static remold.util.Diag.log;

/**
 * Kotlin 主构造默认参数属性的初值切片提取器（{@code DefaultConstructorMarker} 合成构造器）。
 *
 * <p>背景：Kotlin 类或 {@code data class} 在主构造器里新增带默认值的属性（例如
 * {@code val extra: Int = 99}、{@code val tag: String = "def"}）时，主构造器本身只做
 * {@code this.extra = extra}，默认表达式落在 kotlinc 生成的合成委托构造器
 * {@code <init>(..., int mask, kotlin.jvm.internal.DefaultConstructorMarker)} 的
 * {@code if ((mask & bit) != 0) { paramSlot = <expr>; }} 分支里。</p>
 *
 * <p>如果只分析主构造器，这类字段会被判为 {@code self-assignment from constructor parameter}，
 * 存量实例落到类型零值（基本类型为 0、Kotlin 非空引用类型为 {@code null}）。本提取器在单根
 * 主构造器 + 匹配的 {@code DefaultConstructorMarker} 构造器同时存在时，将对应 bit 守卫块内的
 * 默认表达式包装成与主构造器同签名的最小合成方法，并完整复用 {@link InitFixSliceExtractor}
 * 的数据流切片、参数回溯与安全门检查：</p>
 * <ul>
 *   <li>直线且通过效应门/不可变证明的默认表达式：产出 {@link InitFix.FieldExtract}
 *       （{@code originalInsns} 指向主构造器的原 {@code PUTFIELD} 节点），接入后续 T0、依赖闭包与补丁生成。</li>
 *   <li>存在默认表达式但无法安全切片（含分支、非确定性调用、依赖可变/非字段入参等）：以
 *       {@link InitFix#UNEXPRESSIBLE_INIT_PREFIX} 记入 {@code instanceReasons} 并移出
 *       {@code selfAssignedFields}，由批次门 {@code [INITFIX-REFUSE]} 拒收整类，避免非空字段静默读到 null。</li>
 *   <li>无默认值的必填主构造参数（未命中任何 mask bit 守卫）：完全不介入，保持原参数自赋值语义。</li>
 * </ul>
 */
final class KotlinDefaultCtorExtractor {

	private static final String DEFAULT_CONSTRUCTOR_MARKER_DESC =
		"Lkotlin/jvm/internal/DefaultConstructorMarker;";

	private KotlinDefaultCtorExtractor() {}

	/** 主构造器里"参数槽直接赋给字段"的绑定信息。 */
	private record RootParamBinding(int paramIndex, int paramSlot, FieldNode field, FieldInsnNode putInsn) {}

	/** 默认构造器里命中某个 mask bit 的守卫区间。 */
	private record DefaultGuardBlock(int bodyStart, int endIndex, LabelNode endLabel) {}

	static void extractMissingDefaults(
		Class<?> host,
		ClassNode classNode,
		List<MethodNode> initMethods,
		Map<MethodNode, Boolean> rootCtorCache,
		int rootCtorCount,
		Set<String> targetFields,
		Set<String> privateMethods,
		InitFixSafetyGate.NestView nest,
		ClassHierarchyOracle oracle,
		Map<String, List<InitFix.FieldExtract>> instanceExtracts,
		Set<String> selfAssignedFields,
		Map<String, String> instanceReasons) {

		if (classNode == null || initMethods == null || targetFields == null || targetFields.isEmpty()) return;
		if (rootCtorCount != 1) return;

		MethodNode rootCtor = findSingleRootCtor(classNode, initMethods, rootCtorCache);
		if (rootCtor == null) return;

		MethodNode defaultCtor = findMatchingDefaultCtor(classNode, initMethods, rootCtor);
		if (defaultCtor == null) return;

		Type[] rootArgs = Type.getArgumentTypes(rootCtor.desc);
		if (rootArgs.length == 0) return;

		Map<String, RootParamBinding> bindings = scanDirectRootParamBindings(classNode, rootCtor, rootArgs, targetFields);
		if (bindings.isEmpty()) return;

		int firstMaskSlot = computeFirstMaskSlot(rootArgs);
		InitFix.ParamScan rootScan = InitFixSafetyGate.scanParamFields(host, classNode, rootCtor, nest);

		for (Map.Entry<String, RootParamBinding> entry : bindings.entrySet()) {
			String fieldName = entry.getKey();
			if (instanceExtracts.containsKey(fieldName)) continue;

			RootParamBinding binding = entry.getValue();
			int maskSlot = firstMaskSlot + (binding.paramIndex() / 32);
			int maskBit = 1 << (binding.paramIndex() % 32);

			List<DefaultGuardBlock> guards = findMaskGuardBlocks(defaultCtor.instructions, maskSlot, maskBit);
			if (guards.isEmpty()) {
				// 该主构造参数没有默认值（必填参数）：保持原有的参数自赋值行为。
				continue;
			}

			// 一旦命中 mask 守卫，源码中确定存在该字段的默认表达式；无论提取成败都不再算"无默认值自赋值"。
			if (selfAssignedFields != null) selfAssignedFields.remove(fieldName);

			if (guards.size() > 1) {
				recordUnexpressible(instanceReasons, fieldName, defaultCtor,
					"multiple default-value guard blocks for parameter index " + binding.paramIndex());
				continue;
			}

			DefaultGuardBlock guard = guards.get(0);
			MethodNode synth = buildSyntheticInitMethod(
				classNode.name, rootCtor.desc, defaultCtor, guard,
				binding.paramSlot(), rootArgs[binding.paramIndex()], binding.field(),
				instanceReasons, fieldName);
			if (synth == null) {
				continue;
			}

			Map<String, String> synthReasons = new LinkedHashMap<>();
			Set<String> synthSelfAssigned = new HashSet<>();
			Map<String, InitFix.FieldExtract> extracted = InitFixSliceExtractor.extractFieldInits(
				host, classNode.name, synth, Set.of(fieldName), false, privateMethods,
				rootScan.accepted(), rootScan.rejected(), true, synthSelfAssigned, synthReasons, oracle);

			InitFix.FieldExtract fe = extracted.get(fieldName);
			if (fe != null && !synthSelfAssigned.contains(fieldName)) {
				// originalInsns 指向主构造器里的原 PUTFIELD，让 subsequentProcessingReason 认出本次写入已覆盖。
				Set<AbstractInsnNode> origInsns = new LinkedHashSet<>();
				origInsns.add(binding.putInsn());
				InitFix.FieldExtract wrapped = new InitFix.FieldExtract(
					fe.instructions(), fe.protectedAccesses(), fe.dependsOnParam(), true, origInsns);
				instanceExtracts.computeIfAbsent(fieldName, k -> new ArrayList<>()).add(wrapped);
				if (instanceReasons != null) instanceReasons.remove(fieldName);
				if (isDebugEnabled()) {
					log("Extracted Kotlin default primary-constructor initializer for "
						+ classNode.name + "." + fieldName + " from " + defaultCtor.name + defaultCtor.desc);
				}
			} else if (synthSelfAssigned.contains(fieldName)) {
				recordUnexpressible(instanceReasons, fieldName, defaultCtor,
					"default-value expression self-references parameter slot " + binding.paramSlot());
			} else {
				String reason = synthReasons.get(fieldName);
				if (reason == null) {
					recordUnexpressible(instanceReasons, fieldName, defaultCtor,
						"default-value expression could not be extracted");
				} else if (reason.startsWith(InitFix.UNEXPRESSIBLE_INIT_PREFIX)) {
					if (instanceReasons != null) instanceReasons.put(fieldName, reason);
				} else {
					recordUnexpressible(instanceReasons, fieldName, defaultCtor, reason);
				}
			}
		}
	}

	private static void recordUnexpressible(Map<String, String> instanceReasons, String fieldName,
	                                        MethodNode defaultCtor, String detail) {
		if (instanceReasons == null) return;
		instanceReasons.put(fieldName,
			InitFix.UNEXPRESSIBLE_INIT_PREFIX + " in " + defaultCtor.name + defaultCtor.desc + ": " + detail);
	}

	private static MethodNode findSingleRootCtor(ClassNode classNode, List<MethodNode> initMethods,
	                                             Map<MethodNode, Boolean> rootCtorCache) {
		MethodNode found = null;
		for (MethodNode m : initMethods) {
			if (!InitFixSliceExtractor.isRootConstructor(classNode, m, rootCtorCache)) continue;
			if (found != null) return null;
			found = m;
		}
		return found;
	}

	/**
	 * 寻找与唯一主构造器严格匹配的 Kotlin {@code DefaultConstructorMarker} 合成委托构造器。
	 */
	private static MethodNode findMatchingDefaultCtor(ClassNode classNode, List<MethodNode> initMethods,
	                                                  MethodNode rootCtor) {
		Type[] rootArgs = Type.getArgumentTypes(rootCtor.desc);
		if (rootArgs.length == 0) return null;
		int maskCount = (rootArgs.length + 31) / 32;
		int expectedLen = rootArgs.length + maskCount + 1;

		MethodNode match = null;
		for (MethodNode m : initMethods) {
			if (m == rootCtor || m.instructions == null) continue;
			Type[] args = Type.getArgumentTypes(m.desc);
			if (args.length != expectedLen) continue;
			if (!DEFAULT_CONSTRUCTOR_MARKER_DESC.equals(args[args.length - 1].getDescriptor())) continue;

			boolean prefixMatches = true;
			for (int i = 0; i < rootArgs.length; i++) {
				if (!rootArgs[i].equals(args[i])) {
					prefixMatches = false;
					break;
				}
			}
			if (!prefixMatches) continue;

			boolean masksAreInt = true;
			for (int i = 0; i < maskCount; i++) {
				if (args[rootArgs.length + i].getSort() != Type.INT) {
					masksAreInt = false;
					break;
				}
			}
			if (!masksAreInt) continue;

			if (!delegatesToRootCtor(classNode.name, m, rootCtor.desc)) continue;
			if (match != null) return null;
			match = m;
		}
		return match;
	}

	/** 确认默认构造器以 {@code invokespecial <this>.<init>(rootDesc)} 结尾，不调用其它构造器。 */
	private static boolean delegatesToRootCtor(String ownerInternal, MethodNode defaultCtor, String rootDesc) {
		int matchingDelegates = 0;
		for (AbstractInsnNode n : defaultCtor.instructions) {
			if (n instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && "<init>".equals(m.name)
			    && ownerInternal.equals(m.owner)) {
				if (!rootDesc.equals(m.desc)) return false;
				matchingDelegates++;
			}
		}
		return matchingDelegates == 1;
	}

	private static int computeFirstMaskSlot(Type[] rootArgs) {
		int slot = 1; // slot 0 = this
		for (Type t : rootArgs) {
			slot += t.getSize();
		}
		return slot;
	}

	/**
	 * 扫描主构造器里的 {@code ALOAD 0; <xLOAD paramSlot>; PUTFIELD owner.f}，
	 * 建立目标字段名到主构造参数位置及槽号的映射（兼容 {@code val} 与 {@code var}）。
	 */
	private static Map<String, RootParamBinding> scanDirectRootParamBindings(
		ClassNode classNode, MethodNode rootCtor, Type[] rootArgs, Set<String> targetFields) {

		Map<String, FieldNode> fieldsByName = new HashMap<>();
		if (classNode.fields != null) {
			for (FieldNode fn : classNode.fields) {
				fieldsByName.put(fn.name, fn);
			}
		}

		// 建立 paramSlot -> paramIndex 映射
		Map<Integer, Integer> slotToIndex = new HashMap<>();
		int curSlot = 1;
		for (int idx = 0; idx < rootArgs.length; idx++) {
			slotToIndex.put(curSlot, idx);
			curSlot += rootArgs[idx].getSize();
		}

		InsnList insns = rootCtor.instructions;
		List<AbstractInsnNode> real = new ArrayList<>(insns.size());
		for (AbstractInsnNode n : insns) {
			if (n.getOpcode() != -1) real.add(n);
		}

		// 统计主构造器内对各目标字段的 PUTFIELD 次数：多重写入不参与默认参数提取。
		Map<String, Integer> putCounts = new HashMap<>();
		for (AbstractInsnNode n : real) {
			if (n instanceof FieldInsnNode f
			    && f.getOpcode() == Opcodes.PUTFIELD
			    && classNode.name.equals(f.owner)
			    && targetFields.contains(f.name)) {
				putCounts.merge(f.name, 1, Integer::sum);
			}
		}

		Map<String, RootParamBinding> result = new LinkedHashMap<>();
		for (int i = 0; i + 2 < real.size(); i++) {
			AbstractInsnNode a = real.get(i);
			AbstractInsnNode b = real.get(i + 1);
			AbstractInsnNode c = real.get(i + 2);

			if (!(a instanceof VarInsnNode va) || va.getOpcode() != Opcodes.ALOAD || va.var != 0) continue;
			if (!(b instanceof VarInsnNode vb) || !InitFixSafetyGate.isLoadOfParam(vb)) continue;
			if (!(c instanceof FieldInsnNode fc) || fc.getOpcode() != Opcodes.PUTFIELD) continue;
			if (!classNode.name.equals(fc.owner) || !targetFields.contains(fc.name)) continue;
			if (putCounts.getOrDefault(fc.name, 0) != 1) continue;

			Integer paramIndex = slotToIndex.get(vb.var);
			if (paramIndex == null) continue;

			FieldNode fn = fieldsByName.get(fc.name);
			if (fn == null || !fn.desc.equals(fc.desc)) continue;
			if (!rootArgs[paramIndex].getDescriptor().equals(fn.desc)) continue;
			if (loadOpcodeFor(rootArgs[paramIndex]) != vb.getOpcode()) continue;

			int startOrig = insns.indexOf(a);
			int putOrig = insns.indexOf(c);
			if (InitFixSliceExtractor.checkControlDependency(insns, startOrig, putOrig) != null) continue;
			if (isSlotOverwrittenBefore(real, i, vb.var, rootArgs[paramIndex].getSize())) continue;

			result.put(fc.name, new RootParamBinding(paramIndex, vb.var, fn, fc));
		}
		return result;
	}

	private static boolean isSlotOverwrittenBefore(List<AbstractInsnNode> real, int beforeRealIdx,
	                                               int slot, int width) {
		for (int k = 0; k < beforeRealIdx; k++) {
			AbstractInsnNode n = real.get(k);
			if (n instanceof VarInsnNode v && isStoreOpcode(v.getOpcode())) {
				int sw = (v.getOpcode() == Opcodes.LSTORE || v.getOpcode() == Opcodes.DSTORE) ? 2 : 1;
				if (slot < v.var + sw && v.var < slot + width) return true;
			} else if (n instanceof IincInsnNode inc) {
				if (slot < inc.var + 1 && inc.var < slot + width) return true;
			}
		}
		return false;
	}

	/**
	 * 在默认构造器字节码中寻找 {@code ILOAD maskSlot; <const maskBit>; IAND; IFEQ L_end} 守卫块。
	 */
	private static List<DefaultGuardBlock> findMaskGuardBlocks(InsnList insns, int maskSlot, int maskBit) {
		List<DefaultGuardBlock> found = new ArrayList<>();
		int size = insns.size();
		for (int i = 0; i < size; i++) {
			AbstractInsnNode n0 = insns.get(i);
			if (!(n0 instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ILOAD || v.var != maskSlot) continue;

			int i1 = nextRealIndex(insns, i + 1);
			if (i1 < 0) break;
			Integer cst = intConstantValue(insns.get(i1));
			if (cst == null || cst != maskBit) continue;

			int i2 = nextRealIndex(insns, i1 + 1);
			if (i2 < 0 || insns.get(i2).getOpcode() != Opcodes.IAND) continue;

			int i3 = nextRealIndex(insns, i2 + 1);
			if (i3 < 0) continue;
			AbstractInsnNode n3 = insns.get(i3);
			if (!(n3 instanceof JumpInsnNode j) || j.getOpcode() != Opcodes.IFEQ) continue;

			int endIdx = insns.indexOf(j.label);
			if (endIdx <= i3 + 1) continue;

			found.add(new DefaultGuardBlock(i3 + 1, endIdx, j.label));
		}
		return found;
	}

	/**
	 * 将守卫块 {@code [bodyStart, endIndex)} 改写为最小合成方法：
	 * {@code ALOAD 0; <cloned expr>; PUTFIELD owner.field; RETURN}。
	 *
	 * <p>结构不符合预期（缺少尾部 {@code xSTORE paramSlot}、跳转越界到块外等）时返回 {@code null}，
	 * 并在 {@code instanceReasons} 写入 {@link InitFix#UNEXPRESSIBLE_INIT_PREFIX}。</p>
	 */
	private static MethodNode buildSyntheticInitMethod(
		String ownerInternal,
		String rootDesc,
		MethodNode defaultCtor,
		DefaultGuardBlock guard,
		int paramSlot,
		Type paramType,
		FieldNode field,
		Map<String, String> instanceReasons,
		String fieldName) {

		InsnList src = defaultCtor.instructions;
		int storeIdx = prevRealIndex(src, guard.endIndex() - 1, guard.bodyStart());
		if (storeIdx < guard.bodyStart()) {
			recordUnexpressible(instanceReasons, fieldName, defaultCtor,
				"empty default-value block for parameter slot " + paramSlot);
			return null;
		}
		AbstractInsnNode storeNode = src.get(storeIdx);
		int expectedStoreOp = storeOpcodeFor(paramType);
		if (!(storeNode instanceof VarInsnNode sv)
		    || sv.getOpcode() != expectedStoreOp
		    || sv.var != paramSlot) {
			recordUnexpressible(instanceReasons, fieldName, defaultCtor,
				"default-value block does not end with store to parameter slot " + paramSlot);
			return null;
		}

		// 收集块内标签，禁止任何跳转落到块外（包括跳转到 endLabel）。
		Map<LabelNode, LabelNode> labelMap = new HashMap<>();
		for (int i = guard.bodyStart(); i <= storeIdx; i++) {
			AbstractInsnNode n = src.get(i);
			if (n instanceof LabelNode l) {
				labelMap.put(l, new LabelNode());
			}
		}
		for (int i = guard.bodyStart(); i < storeIdx; i++) {
			AbstractInsnNode n = src.get(i);
			if (n instanceof JumpInsnNode j) {
				if (!labelMap.containsKey(j.label)) {
					recordUnexpressible(instanceReasons, fieldName, defaultCtor,
						"default-value expression jumps outside its guard block");
					return null;
				}
			} else if (n instanceof TableSwitchInsnNode ts) {
				if (!labelMap.containsKey(ts.dflt) || !labelMap.keySet().containsAll(ts.labels)) {
					recordUnexpressible(instanceReasons, fieldName, defaultCtor,
						"default-value switch jumps outside its guard block");
					return null;
				}
			} else if (n instanceof LookupSwitchInsnNode ls) {
				if (!labelMap.containsKey(ls.dflt) || !labelMap.keySet().containsAll(ls.labels)) {
					recordUnexpressible(instanceReasons, fieldName, defaultCtor,
						"default-value switch jumps outside its guard block");
					return null;
				}
			}
		}

		// 任何与守卫块重叠的 try-catch 都不可直线切片，直接拒绝。
		if (defaultCtor.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : defaultCtor.tryCatchBlocks) {
				int ts = src.indexOf(tcb.start);
				int te = src.indexOf(tcb.end);
				int th = src.indexOf(tcb.handler);
				if ((ts <= storeIdx && te > guard.bodyStart())
				    || (th >= guard.bodyStart() && th <= storeIdx)) {
					recordUnexpressible(instanceReasons, fieldName, defaultCtor,
						"default-value block overlaps try-catch");
					return null;
				}
			}
		}

		MethodNode synth = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", rootDesc, null, null);
		synth.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = guard.bodyStart(); i < storeIdx; i++) {
			AbstractInsnNode n = src.get(i);
			// 跳过调试/栈帧伪指令，仅保留标签与真实指令
			if (n instanceof LabelNode l) {
				synth.instructions.add(labelMap.get(l));
			} else if (n.getOpcode() != -1) {
				synth.instructions.add(n.clone(labelMap));
			}
		}
		// 若末尾 store 之前紧挨着一个块内标签（如 if/else 汇合点），clone 循环里已经加在 PUTFIELD 之前。
		synth.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, ownerInternal, field.name, field.desc));
		synth.instructions.add(new InsnNode(Opcodes.RETURN));
		synth.maxStack = Math.max(defaultCtor.maxStack + 1, 4);
		synth.maxLocals = Math.max(defaultCtor.maxLocals, computeFirstMaskSlot(Type.getArgumentTypes(rootDesc)));
		return synth;
	}

	private static int nextRealIndex(InsnList insns, int from) {
		for (int i = from; i < insns.size(); i++) {
			if (insns.get(i).getOpcode() != -1) return i;
		}
		return -1;
	}

	private static int prevRealIndex(InsnList insns, int from, int minInclusive) {
		for (int i = from; i >= minInclusive; i--) {
			if (insns.get(i).getOpcode() != -1) return i;
		}
		return -1;
	}

	private static Integer intConstantValue(AbstractInsnNode n) {
		int op = n.getOpcode();
		return switch (op) {
			case Opcodes.ICONST_M1 -> -1;
			case Opcodes.ICONST_0 -> 0;
			case Opcodes.ICONST_1 -> 1;
			case Opcodes.ICONST_2 -> 2;
			case Opcodes.ICONST_3 -> 3;
			case Opcodes.ICONST_4 -> 4;
			case Opcodes.ICONST_5 -> 5;
			case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) n).operand;
			case Opcodes.LDC -> (((LdcInsnNode) n).cst instanceof Integer iv) ? iv : null;
			default -> null;
		};
	}

	private static int loadOpcodeFor(Type t) {
		return switch (t.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD;
			case Type.LONG -> Opcodes.LLOAD;
			case Type.FLOAT -> Opcodes.FLOAD;
			case Type.DOUBLE -> Opcodes.DLOAD;
			default -> Opcodes.ALOAD;
		};
	}

	private static int storeOpcodeFor(Type t) {
		return switch (t.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ISTORE;
			case Type.LONG -> Opcodes.LSTORE;
			case Type.FLOAT -> Opcodes.FSTORE;
			case Type.DOUBLE -> Opcodes.DSTORE;
			default -> Opcodes.ASTORE;
		};
	}

	private static boolean isStoreOpcode(int op) {
		return op == Opcodes.ASTORE || op == Opcodes.ISTORE || op == Opcodes.LSTORE
		       || op == Opcodes.FSTORE || op == Opcodes.DSTORE;
	}
}
