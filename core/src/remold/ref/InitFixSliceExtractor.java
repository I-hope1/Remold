package remold.ref;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import remold.RuntimeConstants;
import remold.util.Diag;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static remold.util.Diag.log;

/**
 * InitFix 切片提取器（AliasInterpreter 数据流分析、多构造器根判定与安全检查）。
 */
final class InitFixSliceExtractor {
	private InitFixSliceExtractor() {}

	// ==================== Analyzer 封装 ====================

	static Frame<SourceValue>[] analyze(String className, MethodNode method)
	 throws AnalyzerException {
		Analyzer<SourceValue> analyzer = new Analyzer<>(new AliasInterpreter()) {
			@Override
			protected boolean newControlFlowExceptionEdge(int insnIndex, TryCatchBlockNode tcb) {
				return false;
			}
		};
		return analyzer.analyze(className, method);
	}

	// ==================== 根构造器判定 ====================

	static boolean isRootConstructor(ClassNode hostClass, MethodNode init,
	                                         Map<MethodNode, Boolean> cache) {
		Boolean cached = cache.get(init);
		if (cached != null) return cached;

		boolean result = computeIsRootConstructor(hostClass, init);
		cache.put(init, result);
		return result;
	}

	private static boolean computeIsRootConstructor(ClassNode hostClass, MethodNode init) {
		Frame<SourceValue>[] frames;
		try {
			frames = analyze(hostClass.name, init);
		} catch (AnalyzerException e) {
			return true;
		}

		InsnList insns = init.instructions;
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode n = insns.get(i);
			if (!(n instanceof MethodInsnNode m)
			    || m.getOpcode() != Opcodes.INVOKESPECIAL
			    || !"<init>".equals(m.name)
			    || !m.owner.equals(hostClass.name)) { continue; }

			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;

			int argCount = Type.getArgumentTypes(m.desc).length;
			int recvIdx  = frame.getStackSize() - 1 - argCount;
			if (recvIdx < 0) continue;

			SourceValue sv = frame.getStack(recvIdx);
			if (sv == null) continue;

			for (AbstractInsnNode src : sv.insns) {
				if (src instanceof VarInsnNode v
				    && v.getOpcode() == Opcodes.ALOAD
				    && v.var == 0) {
					return false;
				}
			}
		}
		return true;
	}

	// ==================== 基于 ASM Analyzer 的字段初始化提取 ====================

	static class AliasInterpreter extends SourceInterpreter {
		AliasInterpreter() { super(Opcodes.ASM9); }

		@Override
		public SourceValue copyOperation(AbstractInsnNode insn, SourceValue v) {
			int op = insn.getOpcode();
			if (op >= Opcodes.DUP && op <= Opcodes.SWAP) return v;
			return super.copyOperation(insn, v);
		}

		@Override
		public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue v) {
			return withOperands(super.unaryOperation(insn, v), List.of(v));
		}

		@Override
		public SourceValue binaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b) {
			return withOperands(super.binaryOperation(insn, a, b), List.of(a, b));
		}

		@Override
		public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> vs) {
			return withOperands(super.naryOperation(insn, vs), vs);
		}

		private static SourceValue withOperands(SourceValue base,
		                                        List<? extends SourceValue> ops) {
			if (ops.isEmpty()) return base;
			Set<AbstractInsnNode> s = new HashSet<>(base.insns);
			for (SourceValue o : ops) s.addAll(o.insns);
			return new SourceValue(base.size, s);
		}
	}

	static Map<String, InitFix.FieldExtract> extractFieldInits(
	 Class<?> host, String className, MethodNode method, Set<String> targetFields,
	 boolean isStatic, Set<String> privateMethods,
	 Map<Integer, InitFix.ParamField> paramFields, Map<Integer, String> rejectedParamSlots,
	 boolean fromRootCtor, Set<String> outSelfAssigned,
	 Map<String, String> outReasons, ClassHierarchyOracle oracle) {

		if (method == null || targetFields.isEmpty()) return Map.of();

		Frame<SourceValue>[] frames;
		try {
			frames = analyze(className, method);
		} catch (AnalyzerException e) {
			Diag.warn("Analysis failed for " + method.name + method.desc
			                  + ": " + e.getMessage());
			if (outReasons != null) {
				for (String f : targetFields) {
					outReasons.putIfAbsent(f, "bytecode analysis failed in "
					                          + method.name + method.desc + ": " + e.getMessage());
				}
			}
			return Map.of();
		}

		InsnList                                            insns             = method.instructions;
		Set<LabelNode>                                      jumpTargets       = collectJumpTargets(method);
		Map<String, Set<AbstractInsnNode>>                  perFieldCollected = new LinkedHashMap<>();
		Map<String, Map<AbstractInsnNode, InitFix.ProtectedAccess>> perFieldProtected = new HashMap<>();
		Set<String>                                         refusedFields     = new HashSet<>();

		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (!(insn instanceof FieldInsnNode f)) continue;

			int op = f.getOpcode();
			if (isStatic ? op != Opcodes.PUTSTATIC : op != Opcodes.PUTFIELD) continue;
			if (!f.owner.equals(className) || !targetFields.contains(f.name)) continue;

			if (refusedFields.contains(f.name)) continue;

			Frame<SourceValue> frame = frames[i];
			if (frame == null) {
				InitFixSafetyGate.recordReason(outReasons, f.name,
				 "no stack frame at the write site in " + method.name + method.desc);
				continue;
			}

			int stackSize = frame.getStackSize();
			if (isStatic ? stackSize < 1 : stackSize < 2) {
				InitFixSafetyGate.recordReason(outReasons, f.name,
				 "unexpected stack shape at the write site in " + method.name + method.desc);
				continue;
			}

			SourceValue value    = frame.getStack(stackSize - 1);
			SourceValue receiver = isStatic ? null : frame.getStack(stackSize - 2);

			Set<AbstractInsnNode> collected = new HashSet<>(value.insns);
			if (receiver != null) collected.addAll(receiver.insns);
			collected.add(insn);

			Map<AbstractInsnNode, InitFix.ProtectedAccess> localProtected = new HashMap<>();

			String unsafeReason;
			try {
				expandAssociatedCalls(collected, insns, frames, i);

				int minIdx = Integer.MAX_VALUE, maxIdx = -1;
				for (AbstractInsnNode n : collected) {
					int idx = insns.indexOf(n);
					if (idx < 0) continue;
					if (idx < minIdx) minIdx = idx;
					if (idx > maxIdx) maxIdx = idx;
				}

				if (minIdx == Integer.MAX_VALUE) {
					unsafeReason = "empty collection";
				} else if (maxIdx > i) {
					unsafeReason = "collected instructions after the put";
				} else {
					unsafeReason = checkSafe(host, className, method, insns, jumpTargets,
					 frames, receiver, collected, minIdx, i, isStatic, privateMethods,
					 paramFields, rejectedParamSlots, localProtected, oracle);
					if (unsafeReason == null && !isStackBalanced(frames, minIdx, i, isStatic)) {
						unsafeReason = "unbalanced stack after extraction";
					}
				}
			} catch (RuntimeException e) {
				unsafeReason = "extraction threw " + e.getClass().getSimpleName()
				               + ": " + e.getMessage();
			}

			if (unsafeReason != null) {
				Diag.warn("Field '" + f.name + "' initialization skipped: " + unsafeReason);
				InitFixSafetyGate.recordReason(outReasons, f.name,
				 "no safe initialization expression in " + method.name + method.desc
				 + ": " + unsafeReason);
				continue;
			}

			if (perFieldCollected.containsKey(f.name)) {
				String reason = "multiple safe writes to the same field in "
				                + className + "." + method.name + "()";
				log("Field '" + f.name + "' has multiple safe PUTFIELD in "
				    + className + "." + method.name + "(): refusing");
				perFieldCollected.remove(f.name);
				perFieldProtected.remove(f.name);
				refusedFields.add(f.name);
				InitFixSafetyGate.recordReason(outReasons, f.name, reason);
				continue;
			}
			perFieldCollected.put(f.name, collected);
			perFieldProtected.put(f.name, localProtected);
		}

		Map<String, InitFix.FieldExtract> perField = new LinkedHashMap<>();
		for (Map.Entry<String, Set<AbstractInsnNode>> e : perFieldCollected.entrySet()) {
			String                fieldName  = e.getKey();
			Set<AbstractInsnNode> fieldInsns = e.getValue();
			Map<AbstractInsnNode, InitFix.ProtectedAccess> fieldProtected =
			 perFieldProtected.getOrDefault(fieldName, Map.of());

			Map<LabelNode, LabelNode>              labelMap        = new HashMap<>();
			List<AbstractInsnNode>                 cloned          = new ArrayList<>();
			Map<AbstractInsnNode, InitFix.ProtectedAccess> clonedProtected = new HashMap<>();
			boolean                                dependsOnParam  = false;
			boolean                                selfAssign      = false;

			for (int i = 0; i < insns.size(); i++) {
				AbstractInsnNode insn = insns.get(i);
				if (!fieldInsns.contains(insn)) continue;

				if (insn instanceof VarInsnNode v
				    && InitFixSafetyGate.isLoadOfParam(v)
				    && paramFields.containsKey(v.var)) {
					FieldNode fn = paramFields.get(v.var).field();
					if (fn.name.equals(fieldName)) {
						selfAssign = true;
						break;
					}
					cloned.add(new VarInsnNode(Opcodes.ALOAD, 0));
					cloned.add(new FieldInsnNode(Opcodes.GETFIELD, className, fn.name, fn.desc));
					dependsOnParam = true;
					continue;
				}

				AbstractInsnNode c = insn.clone(labelMap);
				cloned.add(c);
				InitFix.ProtectedAccess pa = fieldProtected.get(insn);
				if (pa != null) clonedProtected.put(c, pa);
			}

			if (selfAssign) {
				if (outSelfAssigned != null) outSelfAssigned.add(fieldName);
				log("Field '" + fieldName + "' is self-assigned from its own constructor "
				    + "parameter in " + className + "." + method.name
				    + "(); patch would be a no-op. Refusing.");
				InitFixSafetyGate.recordReason(outReasons, fieldName,
				 "self-assignment from its own constructor parameter");
				continue;
			}
			perField.put(fieldName, new InitFix.FieldExtract(
			 cloned, clonedProtected, dependsOnParam, fromRootCtor, fieldInsns));
		}
		return perField;
	}

	private static void expandAssociatedCalls(Set<AbstractInsnNode> collected,
	                                          InsnList insns,
	                                          Frame<SourceValue>[] frames,
	                                          int putIdx) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = 0; i < putIdx; i++) {
				AbstractInsnNode insn = insns.get(i);
				if (collected.contains(insn)) continue;

				Frame<SourceValue> frame = frames[i];
				if (frame == null) continue;

				int op = insn.getOpcode();
				int ss = frame.getStackSize();

				if (op == Opcodes.INVOKESPECIAL
				    && insn instanceof MethodInsnNode m
				    && "<init>".equals(m.name)) {
					int argCount = Type.getArgumentTypes(m.desc).length;
					int recvIdx  = ss - 1 - argCount;
					if (recvIdx >= 0 && intersects(frame.getStack(recvIdx), collected)) {
						collected.add(insn);
						collected.addAll(frame.getStack(recvIdx).insns);
						for (int k = 0; k < argCount; k++) {
							int idx = recvIdx + 1 + k;
							if (idx < ss) collected.addAll(frame.getStack(idx).insns);
						}
						changed = true;
					}

				} else if (op == Opcodes.INVOKESTATIC
				           && insn instanceof MethodInsnNode m
				           && "kotlin/jvm/internal/Intrinsics".equals(m.owner)
				           && m.desc.endsWith(")V")) {
					int argCount = Type.getArgumentTypes(m.desc).length;
					int base     = ss - argCount;
					if (argCount > 0 && base >= 0 && intersects(frame.getStack(base), collected)) {
						collected.add(insn);
						for (int k = 0; k < argCount; k++) {
							collected.addAll(frame.getStack(base + k).insns);
						}
						changed = true;
					}

				} else if (insn instanceof MethodInsnNode m && isNullCheck(m)) {
					int args  = Type.getArgumentTypes(m.desc).length;
					int total = args + (op == Opcodes.INVOKESTATIC ? 0 : 1);
					int base  = ss - total;
					if (base < 0) continue;

					boolean matched = false;
					for (int k = 0; k < total; k++) {
						if (intersects(frame.getStack(base + k), collected)) {
							matched = true;
							break;
						}
					}
					if (matched) {
						collected.add(insn);
						for (int k = 0; k < total; k++) {
							collected.addAll(frame.getStack(base + k).insns);
						}
						changed = true;
					}

				} else if (op == Opcodes.POP || op == Opcodes.POP2) {
					int n = entriesFor(frame, wordsOf(op));
					if (n <= 0) continue;
					boolean matched = false;
					for (int k = 0; k < n && k < ss; k++) {
						if (intersects(frame.getStack(ss - 1 - k), collected)) {
							matched = true;
							break;
						}
					}
					if (matched) {
						collected.add(insn);
						changed = true;
					}

				} else if (op >= Opcodes.DUP && op <= Opcodes.SWAP) {
					int n = entriesFor(frame, wordsOf(op));
					if (n <= 0 || ss < n) continue;

					boolean matched = false;
					for (int k = 0; k < n; k++) {
						if (intersects(frame.getStack(ss - 1 - k), collected)) {
							matched = true;
							break;
						}
					}
					if (matched) {
						collected.add(insn);
						for (int k = 0; k < n; k++) {
							collected.addAll(frame.getStack(ss - 1 - k).insns);
						}
						changed = true;
					}

				} else if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
					int base = ss - 3;
					if (base < 0) continue;
					if (intersects(frame.getStack(base), collected)) {
						collected.add(insn);
						for (int k = 0; k < 3; k++) {
							collected.addAll(frame.getStack(base + k).insns);
						}
						changed = true;
					}
				}
			}
		}
	}

	private static int wordsOf(int op) {
		return switch (op) {
			case Opcodes.DUP, Opcodes.POP -> 1;
			case Opcodes.DUP_X1, Opcodes.SWAP, Opcodes.DUP2, Opcodes.POP2 -> 2;
			case Opcodes.DUP_X2, Opcodes.DUP2_X1 -> 3;
			case Opcodes.DUP2_X2 -> 4;
			default -> 0;
		};
	}

	private static int entriesFor(Frame<SourceValue> f, int words) {
		int n = 0, w = 0, ss = f.getStackSize();
		while (w < words && n < ss) {
			w += f.getStack(ss - 1 - n++).getSize();
		}
		return n;
	}

	private static boolean intersects(SourceValue sv, Set<AbstractInsnNode> collected) {
		if (sv == null) return false;
		for (AbstractInsnNode n : sv.insns) {
			if (collected.contains(n)) return true;
		}
		return false;
	}

	static String checkSafe(Class<?> host, String className, MethodNode method,
	                                InsnList insns, Set<LabelNode> jumpTargets,
	                                Frame<SourceValue>[] frames,
	                                SourceValue receiver,
	                                Set<AbstractInsnNode> collected,
	                                int minIdx, int putIdx, boolean isStatic,
	                                Set<String> privateMethods,
	                                Map<Integer, InitFix.ParamField> paramFields,
	                                Map<Integer, String> rejectedParamSlots,
	                                Map<AbstractInsnNode, InitFix.ProtectedAccess> outProtectedAccesses,
	                                ClassHierarchyOracle oracle) {
		if (!isStatic) {
			if (receiver == null || receiver.insns.size() != 1) return "unexpected receiver";
			AbstractInsnNode recv = receiver.insns.iterator().next();
			if (!(recv instanceof VarInsnNode v
			      && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) {
				return "unexpected receiver";
			}
		}

		if (oracle == null) {
			oracle = new HierarchyTreeOracle(host == null ? null : host.getClassLoader());
		}
		for (int k = minIdx; k <= putIdx; k++) {
			AbstractInsnNode n = insns.get(k);
			if (n.getOpcode() != -1 && !collected.contains(n)) {
				return "contains instructions outside the expression tree";
			}
		}
		for (int k = minIdx + 1; k <= putIdx; k++) {
			if (insns.get(k) instanceof LabelNode l && jumpTargets.contains(l)) {
				return "contains branch";
			}
		}

		for (TryCatchBlockNode tc : method.tryCatchBlocks) {
			int tryStart   = insns.indexOf(tc.start);
			int tryEnd     = insns.indexOf(tc.end);
			int handlerIdx = insns.indexOf(tc.handler);

			boolean overlaps = tryStart <= putIdx && tryEnd > minIdx;
			if (overlaps) {
				return "extraction range overlaps try-catch block";
			}
			if (handlerIdx >= minIdx && handlerIdx <= putIdx) {
				return "extraction range contains exception handler";
			}
		}

		String ctrlReason = checkControlDependency(insns, minIdx, putIdx);
		if (ctrlReason != null) return ctrlReason;

		// 按<b>字节码顺序</b>遍历 collected（而不是 HashSet 的迭代顺序）：
		// 同一个切片里可能同时命中多条门（例如"依赖构造器参数"与"IO 调用"），
		// HashSet 的迭代顺序由 identity hash 决定、每次 JVM 运行都可能不同，
		// 会让 InitFix.PatchReport 里的 reason 飘忽。按序取第一条，报告才稳定且指向最早的问题。
		for (int k = minIdx; k <= putIdx; k++) {
			AbstractInsnNode n = insns.get(k);
			if (!collected.contains(n)) continue;
			if (n instanceof VarInsnNode v) {
				if (isStatic) return "depends on local variables";
				if (v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
					// this，放行
				} else if (InitFixSafetyGate.isLoadOfParam(v) && paramFields.containsKey(v.var)) {
					// 构造器参数，克隆阶段会替换为 ALOAD 0; GETFIELD
				} else if (InitFixSafetyGate.isLoadOfParam(v) && rejectedParamSlots != null
				           && rejectedParamSlots.containsKey(v.var)) {
					// 该槽位本来是"参数->字段"模式的候选，但被 docs/initfix/01-safety-gate.md §3 不可变证明或
					// 单赋值检查拒掉了：把真实原因透传出去，而不是笼统的"局部变量"。
					return "constructor parameter slot " + v.var
					       + " cannot be back-tracked: " + rejectedParamSlots.get(v.var);
				} else {
					return "depends on local variables";
				}
			}
			if (n.getOpcode() == Opcodes.IINC) {
				return "depends on local variables";
			}

			// docs/initfix/01-safety-gate.md §2 的最小效应防御（P0 版）：明确非确定性 / 环境依赖 / IO 的调用直接拒绝。
			String effect = InitFixSafetyGate.effectReason(n, insns, frames, collected);
			if (effect != null) return effect;

			// docs/initfix/01-safety-gate.md §2 局部逃逸豁免的接收者敏感判定：可复用 builder 的接收者必须来自本切片。
			if (n instanceof MethodInsnNode mm) {
				String builderReason = InitFixSafetyGate.builderMutatorReason(mm, insns, frames);
				if (builderReason != null) return builderReason;
			}

			if (n instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && !"<init>".equals(m.name)) {
				if (!m.owner.equals(className)) {
					return "invokespecial non-<init> on foreign owner not usable from hidden class";
				}
				if (!privateMethods.contains(m.name + m.desc)) {
					return "invokespecial non-<init> target is not a private method of host";
				}
			}

			if (n instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && "<init>".equals(m.name)) {
				Boolean prot = isProtectedCrossPackageAccess(oracle, host, className, m.owner, m.name, m.desc, false);
				if (prot == null) {
					return "cannot determine protected status of constructor "
					       + m.owner + "." + m.name + m.desc + InitFixSafetyGate.missingBytecodeHint(m.owner);
				}
				if (prot) {
					return "protected constructor across packages not bridgeable";
				}
			}

			if (n instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof Handle h
					    && h.getTag() == Opcodes.H_INVOKESPECIAL
					    && !"<init>".equals(h.getName())) {
						if (!h.getOwner().equals(className)) {
							return "invokedynamic bsmArgs contains H_INVOKESPECIAL "
							       + "on foreign owner: " + h.getOwner()
							       + "." + h.getName() + h.getDesc();
						}
						if (!privateMethods.contains(h.getName() + h.getDesc())) {
							return "invokedynamic bsmArgs contains H_INVOKESPECIAL "
							       + "target not a private method of host: "
							       + h.getName() + h.getDesc();
						}
					}
				}
			}

			if (n instanceof FieldInsnNode f && isFieldAccessOpcode(f.getOpcode())) {
				Boolean prot = isProtectedCrossPackageAccess(oracle, host, className, f.owner, f.name, f.desc, true);
				if (prot == null) {
					return "cannot determine protected status of field access "
					       + f.owner + "." + f.name + ":" + f.desc + InitFixSafetyGate.missingBytecodeHint(f.owner);
				}
				if (prot) {
					outProtectedAccesses.put(n,
					 new InitFix.ProtectedAccess(f.getOpcode(), f.owner, f.name, f.desc));
				}
			}
			if (n instanceof MethodInsnNode m && !m.owner.startsWith("[")) {
				Boolean prot = isProtectedCrossPackageAccess(oracle, host, className, m.owner, m.name, m.desc, false);
				if (prot == null) {
					return "cannot determine protected status of method access "
					       + m.owner + "." + m.name + m.desc + InitFixSafetyGate.missingBytecodeHint(m.owner);
				}
				if (prot) {
					outProtectedAccesses.put(n,
					 new InitFix.ProtectedAccess(m.getOpcode(), m.owner, m.name, m.desc));
				}
			}
		}
		return null;
	}

	private static boolean isFieldAccessOpcode(int op) {
		return op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD
		       || op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC;
	}

	static String checkControlDependency(InsnList insns, int minIdx, int putIdx) {
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode n       = insns.get(i);
			int              op      = n.getOpcode();
			boolean          inRange = i >= minIdx && i <= putIdx;
			boolean          before  = i < minIdx;
			boolean          after   = i > putIdx;

			if (n instanceof JumpInsnNode j) {
				if (inRange) return "jump instruction inside extraction range";
				int t = insns.indexOf(j.label);
				if (before && t > putIdx) {
					return "jump at " + i + " skips put (target=" + t + ")";
				}
				if (after && t <= putIdx) {
					return "back-edge at " + i + " into put (target=" + t + ")";
				}
			} else if (n instanceof TableSwitchInsnNode s) {
				if (inRange) return "switch instruction inside extraction range";
				String r = switchTargetCheck(insns, s.dflt, s.labels,
				 before, after, minIdx, putIdx);
				if (r != null) return r;
			} else if (n instanceof LookupSwitchInsnNode s) {
				if (inRange) return "switch instruction inside extraction range";
				String r = switchTargetCheck(insns, s.dflt, s.labels,
				 before, after, minIdx, putIdx);
				if (r != null) return r;
			} else if (op == Opcodes.RETURN || op == Opcodes.IRETURN
			           || op == Opcodes.LRETURN || op == Opcodes.FRETURN
			           || op == Opcodes.DRETURN || op == Opcodes.ARETURN) {
				if (inRange) return "return inside extraction range";
				if (before) return "return before put at index " + i;
			} else if (op == Opcodes.ATHROW) {
				if (inRange) return "athrow inside extraction range";
			}
		}
		return null;
	}

	private static String switchTargetCheck(InsnList insns, LabelNode dflt,
	                                        List<LabelNode> labels,
	                                        boolean before, boolean after,
	                                        int minIdx, int putIdx) {
		List<LabelNode> all = new ArrayList<>(labels.size() + 1);
		all.add(dflt);
		all.addAll(labels);
		for (LabelNode l : all) {
			int t = insns.indexOf(l);
			if (before && t > putIdx) {
				return "switch target at " + t + " skips put";
			}
			if (after && t <= putIdx) {
				return "switch back-edge to " + t + " into put";
			}
		}
		return null;
	}

	private static Boolean isProtectedCrossPackageAccess(
	 ClassHierarchyOracle oracle, Class<?> host, String hostName, String ownerInternal,
	 String name, String desc, boolean isField) {

		if (ownerInternal.isEmpty() || ownerInternal.charAt(0) == '[') return Boolean.FALSE;

		Optional<ClassHierarchyOracle.MemberRef> member =
		 oracle.resolveMember(ownerInternal, name, desc, isField);
		if (member.isEmpty()) return null;

		String hostInternal = host != null ? host.getName().replace('.', '/') : hostName;
		if (hostInternal == null) return null;
		return Modifier.isProtected(member.get().access)
		       && !packageName(member.get().declaringClass).equals(packageName(hostInternal));
	}

	private static String packageName(String internalName) {
		int separator = internalName.lastIndexOf('/');
		return separator < 0 ? "" : internalName.substring(0, separator);
	}


	private static boolean isStackBalanced(Frame<SourceValue>[] frames,
	                                       int first, int putIdx, boolean isStatic) {
		Frame<SourceValue> a = frames[first];
		Frame<SourceValue> b = frames[putIdx];
		if (a == null || b == null) return false;
		return b.getStackSize() - a.getStackSize() == (isStatic ? 1 : 2);
	}

	private static Set<LabelNode> collectJumpTargets(MethodNode m) {
		Set<LabelNode> t = new HashSet<>();
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof JumpInsnNode j) {
				t.add(j.label);
			} else if (n instanceof TableSwitchInsnNode s) {
				t.add(s.dflt);
				t.addAll(s.labels);
			} else if (n instanceof LookupSwitchInsnNode s) {
				t.add(s.dflt);
				t.addAll(s.labels);
			}
		}
		for (TryCatchBlockNode tc : m.tryCatchBlocks) {
			t.add(tc.start);
			t.add(tc.end);
			t.add(tc.handler);
		}
		return t;
	}

	private static boolean isNullCheck(MethodInsnNode m) {
		return (m.getOpcode() == Opcodes.INVOKESTATIC
		        && "java/util/Objects".equals(m.owner) && "requireNonNull".equals(m.name))
		       || (m.getOpcode() == Opcodes.INVOKEVIRTUAL
		           && "java/lang/Object".equals(m.owner) && "getClass".equals(m.name));
	}
}
