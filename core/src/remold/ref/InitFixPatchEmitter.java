package remold.ref;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

import static remold.util.Diag.isDebugEnabled;
import static remold.util.Diag.log;
import static remold.ref.InitFix.*;

/**
 * InitFix 伴生补丁类指令改写与批量方法发射器。
 */
final class InitFixPatchEmitter {
	private InitFixPatchEmitter() {}

	/** 一个字段的完整补丁体：protected 桥接 → 私有调用改写 → 写入协议改写。 */
	static List<AbstractInsnNode> rewriteFieldSlice(
	 String hostInternal, String className, List<AbstractInsnNode> insns,
	 Map<AbstractInsnNode, ProtectedAccess> protectedAccesses,
	 Set<String> conditionalFields, Set<String> forceFields,
	 Map<String, Object> replayArguments, Map<String, String> shadowAncestors) {

		insns = rewriteProtectedAccesses(hostInternal, insns, protectedAccesses);
		insns = rewritePrivateInvokes(className, insns);
		insns = rewriteFieldPuts(className, insns, conditionalFields, forceFields, replayArguments, shadowAncestors);
		return insns;
	}

	/** 生成一个静态直线方法：{@code methodName(desc)V}，体内为切片 + RETURN。 */
	static MethodNode straightLineMethod(
	 String methodName, String argDesc, List<AbstractInsnNode> body) {
		MethodNode m = new MethodNode(
		 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
		 methodName, argDesc + "V", null, null);
		for (AbstractInsnNode i : body) m.instructions.add(i);
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	/**
	 * 生成一个静态工厂方法：{@code methodName()Ljava/lang/Object;}，体内为初值表达式 + {@code ARETURN}。
	 *
	 * <p>值类型 {@code new} 初值重放（{@code docs/initfix/14-value-type-new-replay.md}）用它把
	 * {@code oldExpr}/{@code newExpr} 的直线构造表达式固化成补丁类里的两个工厂方法：
	 * {@code buildOld$F()} 重建旧值、{@code buildNew$F()} 每个实例独立构造新值。
	 * 表达式只含常量、{@code NEW}、装箱与 {@code T.<init>}（A1 已保证），因此无局部变量、无控制流。</p>
	 *
	 * <p>返回类型固定为 {@code Object}：宿主的字段声明类型可能是 {@code Object} 或某个接口
	 * （{@code Object v = new Vec(...)}），与表达式实际构造类型 {@code T} 不必相同；运行期绑定统一按
	 * {@code ()Object} 查找并适配。{@code T} 的引用与 {@code Object} 赋值兼容，字节码校验通过。</p>
	 *
	 * @param methodName 方法名（调用方保证唯一）
	 * @param expression 初值表达式指令（来自 {@link ValueTypeReplay.Carved}；此处 clone 后插入，
	 *                   避免节点同时挂到两个指令表上）
	 */
	static MethodNode expressionFactoryMethod(
	 String methodName, List<AbstractInsnNode> expression) {
		MethodNode m = new MethodNode(
		 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
		 methodName, "()Ljava/lang/Object;", null, null);
		Map<LabelNode, LabelNode> noLabels = Collections.emptyMap();
		for (AbstractInsnNode insn : expression) {
			m.instructions.add(insn.clone(noLabels));
		}
		m.instructions.add(new InsnNode(Opcodes.ARETURN));
		return m;
	}

	private static final int    BATCH_LOCAL_TARGETS  = 0;
	private static final int    BATCH_LOCAL_FROM     = 1;
	private static final int    BATCH_LOCAL_TO       = 2;
	private static final int    BATCH_LOCAL_ERROUT   = 3;
	private static final int    BATCH_LOCAL_INDEX    = 4;
	private static final int    BATCH_LOCAL_WRITTEN  = 5;
	private static final int    BATCH_LOCAL_SKIPPED  = 7;
	private static final int    BATCH_LOCAL_TARGET   = 9;
	private static final String BATCH_METHOD_DESC    = "([Ljava/lang/Object;II[Ljava/lang/Throwable;)I";
	private static final String FUSED_BATCH_DESC     = "([Ljava/lang/Object;II[Z[Ljava/lang/Throwable;[I)I";
	private static final String BRIDGE_INTERNAL      = Type.getInternalName(HotswapBridge.class);

	record BatchSliceSpec(
	 String fieldName, List<AbstractInsnNode> body, int lastRealIdx,
	 int statsKind, int statsIndyIdx, boolean selfOnly) { }

	/**
	 * 校验并提取可内联进批循环的直线切片元信息；含控制流、额外局部变量或非预期 indy 形态时返回 {@code null}。
	 */
	static BatchSliceSpec inspectBatchSlice(
	 String fieldName, List<AbstractInsnNode> body, boolean selfOnly) {
		if (body == null || body.isEmpty()) return null;

		int lastRealIdx = -1;
		for (int i = body.size() - 1; i >= 0; i--) {
			AbstractInsnNode insn = body.get(i);
			if (!(insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode)) {
				lastRealIdx = i;
				break;
			}
		}
		if (lastRealIdx < 0) return null;

		int statsKind = 0; // 0 = none (force write), 1 = conditional, 2 = replay-expected
		int statsIndyIdx = -1;
		for (int i = 0; i <= lastRealIdx; i++) {
			AbstractInsnNode insn = body.get(i);
			if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
				continue;
			}
			if (insn instanceof JumpInsnNode
			    || insn instanceof TableSwitchInsnNode
			    || insn instanceof LookupSwitchInsnNode
			    || insn instanceof IincInsnNode) {
				return null;
			}
			int op = insn.getOpcode();
			if (op == Opcodes.RETURN || op == Opcodes.IRETURN || op == Opcodes.LRETURN
			    || op == Opcodes.FRETURN || op == Opcodes.DRETURN || op == Opcodes.ARETURN
			    || op == Opcodes.ATHROW || op == Opcodes.RET) {
				return null;
			}
			if (insn instanceof VarInsnNode v) {
				if (v.getOpcode() != Opcodes.ALOAD || v.var != 0) return null;
			}
			if (insn instanceof InvokeDynamicInsnNode indy) {
				int detected = classifyBatchIndy(indy, fieldName);
				if (detected < 0) return null;
				if (detected > 0) {
					if (statsKind != 0 || i != lastRealIdx) return null;
					statsKind = detected;
					statsIndyIdx = i;
				}
			}
		}
		return new BatchSliceSpec(fieldName, body, lastRealIdx, statsKind, statsIndyIdx, selfOnly);
	}

	/**
	 * 为单字段无分支切片生成隐藏补丁类内的批量循环方法
	 * {@code initBatch$F(Object[] targets, int from, int to, Throwable[] errOut)}。
	 */
	static MethodNode emitBatchMethod(
	 String batchMethodName, String className, BatchSliceSpec spec) {
		MethodNode m = new MethodNode(
		 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
		 batchMethodName, BATCH_METHOD_DESC, null, null);
		InsnList out = m.instructions;
		Type hostType = Type.getObjectType(className);
		Object[] baseLocals = new Object[]{
		 "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER,
		 "[Ljava/lang/Throwable;", Opcodes.INTEGER, Opcodes.LONG, Opcodes.LONG
		};
		Object[] targetLocals = new Object[]{
		 "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER,
		 "[Ljava/lang/Throwable;", Opcodes.INTEGER, Opcodes.LONG, Opcodes.LONG,
		 className
		};
		Object[] catchLocals = new Object[]{
		 "[Ljava/lang/Object;", Opcodes.INTEGER, Opcodes.INTEGER,
		 "[Ljava/lang/Throwable;", Opcodes.INTEGER, Opcodes.LONG, Opcodes.LONG,
		 "java/lang/Throwable"
		};

		LabelNode tryStart = new LabelNode();
		LabelNode tryEnd = new LabelNode();
		LabelNode loopCond = new LabelNode();
		LabelNode loopEnd = new LabelNode();
		LabelNode nextIter = new LabelNode();
		LabelNode catchHandler = new LabelNode();
		m.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/Throwable"));

		out.add(new VarInsnNode(Opcodes.ILOAD, BATCH_LOCAL_FROM));
		out.add(new VarInsnNode(Opcodes.ISTORE, BATCH_LOCAL_INDEX));
		out.add(new InsnNode(Opcodes.LCONST_0));
		out.add(new VarInsnNode(Opcodes.LSTORE, BATCH_LOCAL_WRITTEN));
		out.add(new InsnNode(Opcodes.LCONST_0));
		out.add(new VarInsnNode(Opcodes.LSTORE, BATCH_LOCAL_SKIPPED));

		out.add(tryStart);
		out.add(loopCond);
		out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		out.add(new VarInsnNode(Opcodes.ILOAD, BATCH_LOCAL_INDEX));
		out.add(new VarInsnNode(Opcodes.ILOAD, BATCH_LOCAL_TO));
		out.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

		out.add(new VarInsnNode(Opcodes.ALOAD, BATCH_LOCAL_TARGETS));
		out.add(new VarInsnNode(Opcodes.ILOAD, BATCH_LOCAL_INDEX));
		out.add(new InsnNode(Opcodes.AALOAD));
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, className));
		out.add(new VarInsnNode(Opcodes.ASTORE, BATCH_LOCAL_TARGET));

		if (spec.selfOnly()) {
			out.add(new VarInsnNode(Opcodes.ALOAD, BATCH_LOCAL_TARGET));
			out.add(new MethodInsnNode(
			 Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
			out.add(new LdcInsnNode(hostType));
			out.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nextIter));
		}

		emitBatchSliceBody(
		 out, spec, BATCH_LOCAL_TARGET, BATCH_LOCAL_WRITTEN, BATCH_LOCAL_SKIPPED, nextIter, targetLocals);

		if (spec.selfOnly() || spec.statsKind() != 0) {
			out.add(nextIter);
			out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		}
		out.add(new IincInsnNode(BATCH_LOCAL_INDEX, 1));
		out.add(new JumpInsnNode(Opcodes.GOTO, loopCond));
		out.add(tryEnd);

		out.add(loopEnd);
		out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		if (spec.statsKind() != 0) {
			LabelNode returnSuccess = new LabelNode();
			emitBatchStatsFlush(out, hostType, spec.fieldName(), spec.statsKind(),
			 BATCH_LOCAL_WRITTEN, BATCH_LOCAL_SKIPPED, returnSuccess);
			out.add(returnSuccess);
			out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		}
		out.add(new InsnNode(Opcodes.ICONST_M1));
		out.add(new InsnNode(Opcodes.IRETURN));

		out.add(catchHandler);
		out.add(new FrameNode(
		 Opcodes.F_FULL, baseLocals.length, baseLocals, 1, new Object[]{"java/lang/Throwable"}));
		out.add(new VarInsnNode(Opcodes.ASTORE, BATCH_LOCAL_TARGET));
		if (spec.statsKind() != 0) {
			LabelNode skipFlushOnErr = new LabelNode();
			emitBatchStatsFlush(out, hostType, spec.fieldName(), spec.statsKind(),
			 BATCH_LOCAL_WRITTEN, BATCH_LOCAL_SKIPPED, skipFlushOnErr);
			out.add(skipFlushOnErr);
			out.add(new FrameNode(Opcodes.F_FULL, catchLocals.length, catchLocals, 0, new Object[0]));
		}
		out.add(new VarInsnNode(Opcodes.ALOAD, BATCH_LOCAL_ERROUT));
		out.add(new InsnNode(Opcodes.ICONST_0));
		out.add(new VarInsnNode(Opcodes.ALOAD, BATCH_LOCAL_TARGET));
		out.add(new InsnNode(Opcodes.AASTORE));
		out.add(new VarInsnNode(Opcodes.ILOAD, BATCH_LOCAL_INDEX));
		out.add(new InsnNode(Opcodes.IRETURN));
		return m;
	}

	/**
	 * 将同一批内多个无依赖字段融合为单次数组遍历 {@code initBatchGroup$G}，
	 * 让百万级实例在字段数 ≥ 2 时只需承担一轮堆访存与局部性开销，同时保持按字段异常隔离与预算上限。
	 */
	static MethodNode emitFusedBatchMethod(
	 String methodName, String className, List<BatchSliceSpec> specs) {
		final int localTargets    = 0;
		final int localFrom       = 1;
		final int localTo         = 2;
		final int localActive     = 3;
		final int localErrOut     = 4;
		final int localErrSlotOut = 5;
		final int localIndex      = 6;
		final int localIsSelf     = 7;

		int g = specs.size();
		int[] writtenLocals = new int[g];
		int[] skippedLocals = new int[g];
		int nextLocal = 8;
		int statsFieldCount = 0;
		boolean allSelfOnly = true;
		boolean anySelfOnly = false;
		for (int j = 0; j < g; j++) {
			BatchSliceSpec s = specs.get(j);
			allSelfOnly &= s.selfOnly();
			anySelfOnly |= s.selfOnly();
			if (s.statsKind() != 0) {
				writtenLocals[j] = nextLocal;
				skippedLocals[j] = nextLocal + 2;
				nextLocal += 4;
				statsFieldCount++;
			} else {
				writtenLocals[j] = -1;
				skippedLocals[j] = -1;
			}
		}
		final int localTarget = nextLocal;

		int baseFrameLen = 8 + 2 * statsFieldCount;
		Object[] baseLocals = new Object[baseFrameLen];
		baseLocals[0] = "[Ljava/lang/Object;";
		baseLocals[1] = Opcodes.INTEGER;
		baseLocals[2] = Opcodes.INTEGER;
		baseLocals[3] = "[Z";
		baseLocals[4] = "[Ljava/lang/Throwable;";
		baseLocals[5] = "[I";
		baseLocals[6] = Opcodes.INTEGER;
		baseLocals[7] = Opcodes.INTEGER;
		for (int k = 0; k < 2 * statsFieldCount; k++) {
			baseLocals[8 + k] = Opcodes.LONG;
		}
		Object[] targetLocals = new Object[baseFrameLen + 1];
		System.arraycopy(baseLocals, 0, targetLocals, 0, baseFrameLen);
		targetLocals[baseFrameLen] = className;

		MethodNode m = new MethodNode(
		 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
		 methodName, FUSED_BATCH_DESC, null, null);
		InsnList out = m.instructions;
		Type hostType = Type.getObjectType(className);

		LabelNode loopCond = new LabelNode();
		LabelNode loopEnd = new LabelNode();
		LabelNode nextIter = new LabelNode();
		LabelNode catchFlushReturn = new LabelNode();
		LabelNode[] fieldNext = new LabelNode[g];
		LabelNode[] tryStart = new LabelNode[g];
		LabelNode[] tryEnd = new LabelNode[g];
		LabelNode[] catchHandler = new LabelNode[g];
		for (int j = 0; j < g; j++) {
			fieldNext[j] = (j == g - 1) ? nextIter : new LabelNode();
			tryStart[j] = new LabelNode();
			tryEnd[j] = new LabelNode();
			catchHandler[j] = new LabelNode();
			m.tryCatchBlocks.add(new TryCatchBlockNode(tryStart[j], tryEnd[j], catchHandler[j], "java/lang/Throwable"));
		}

		out.add(new VarInsnNode(Opcodes.ILOAD, localFrom));
		out.add(new VarInsnNode(Opcodes.ISTORE, localIndex));
		out.add(new InsnNode(Opcodes.ICONST_0));
		out.add(new VarInsnNode(Opcodes.ISTORE, localIsSelf));
		for (int j = 0; j < g; j++) {
			if (specs.get(j).statsKind() != 0) {
				out.add(new InsnNode(Opcodes.LCONST_0));
				out.add(new VarInsnNode(Opcodes.LSTORE, writtenLocals[j]));
				out.add(new InsnNode(Opcodes.LCONST_0));
				out.add(new VarInsnNode(Opcodes.LSTORE, skippedLocals[j]));
			}
		}

		out.add(loopCond);
		out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		out.add(new VarInsnNode(Opcodes.ILOAD, localIndex));
		out.add(new VarInsnNode(Opcodes.ILOAD, localTo));
		out.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));

		out.add(new VarInsnNode(Opcodes.ALOAD, localTargets));
		out.add(new VarInsnNode(Opcodes.ILOAD, localIndex));
		out.add(new InsnNode(Opcodes.AALOAD));
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, className));
		out.add(new VarInsnNode(Opcodes.ASTORE, localTarget));

		if (allSelfOnly) {
			out.add(new VarInsnNode(Opcodes.ALOAD, localTarget));
			out.add(new MethodInsnNode(
			 Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
			out.add(new LdcInsnNode(hostType));
			out.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nextIter));
		} else if (anySelfOnly) {
			LabelNode notSelf = new LabelNode();
			LabelNode selfDone = new LabelNode();
			out.add(new VarInsnNode(Opcodes.ALOAD, localTarget));
			out.add(new MethodInsnNode(
			 Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
			out.add(new LdcInsnNode(hostType));
			out.add(new JumpInsnNode(Opcodes.IF_ACMPNE, notSelf));
			out.add(new InsnNode(Opcodes.ICONST_1));
			out.add(new JumpInsnNode(Opcodes.GOTO, selfDone));
			out.add(notSelf);
			out.add(new FrameNode(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]));
			out.add(new InsnNode(Opcodes.ICONST_0));
			out.add(selfDone);
			out.add(new FrameNode(
			 Opcodes.F_FULL, targetLocals.length, targetLocals, 1, new Object[]{Opcodes.INTEGER}));
			out.add(new VarInsnNode(Opcodes.ISTORE, localIsSelf));
		}

		for (int j = 0; j < g; j++) {
			BatchSliceSpec spec = specs.get(j);
			if (j > 0) {
				out.add(fieldNext[j - 1]);
				out.add(new FrameNode(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]));
			}
			out.add(new VarInsnNode(Opcodes.ALOAD, localActive));
			out.add(new IntInsnNode(Opcodes.BIPUSH, j));
			out.add(new InsnNode(Opcodes.BALOAD));
			out.add(new JumpInsnNode(Opcodes.IFEQ, fieldNext[j]));
			if (!allSelfOnly && spec.selfOnly()) {
				out.add(new VarInsnNode(Opcodes.ILOAD, localIsSelf));
				out.add(new JumpInsnNode(Opcodes.IFEQ, fieldNext[j]));
			}
			out.add(tryStart[j]);
			emitBatchSliceBody(
			 out, spec, localTarget, writtenLocals[j], skippedLocals[j], fieldNext[j], targetLocals);
			out.add(tryEnd[j]);
		}

		out.add(nextIter);
		out.add(new FrameNode(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]));
		out.add(new IincInsnNode(localIndex, 1));
		out.add(new JumpInsnNode(Opcodes.GOTO, loopCond));

		out.add(loopEnd);
		out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		emitFusedStatsFlushes(out, hostType, specs, writtenLocals, skippedLocals, baseLocals);
		out.add(new InsnNode(Opcodes.ICONST_M1));
		out.add(new InsnNode(Opcodes.IRETURN));

		for (int j = 0; j < g; j++) {
			out.add(catchHandler[j]);
			out.add(new FrameNode(
			 Opcodes.F_FULL, baseLocals.length, baseLocals, 1, new Object[]{"java/lang/Throwable"}));
			out.add(new VarInsnNode(Opcodes.ASTORE, localTarget));
			out.add(new VarInsnNode(Opcodes.ALOAD, localErrOut));
			out.add(new InsnNode(Opcodes.ICONST_0));
			out.add(new VarInsnNode(Opcodes.ALOAD, localTarget));
			out.add(new InsnNode(Opcodes.AASTORE));
			out.add(new VarInsnNode(Opcodes.ALOAD, localErrSlotOut));
			out.add(new InsnNode(Opcodes.ICONST_0));
			out.add(new IntInsnNode(Opcodes.BIPUSH, j));
			out.add(new InsnNode(Opcodes.IASTORE));
			out.add(new JumpInsnNode(Opcodes.GOTO, catchFlushReturn));
		}

		out.add(catchFlushReturn);
		out.add(new FrameNode(Opcodes.F_FULL, baseLocals.length, baseLocals, 0, new Object[0]));
		emitFusedStatsFlushes(out, hostType, specs, writtenLocals, skippedLocals, baseLocals);
		out.add(new VarInsnNode(Opcodes.ILOAD, localIndex));
		out.add(new InsnNode(Opcodes.IRETURN));
		return m;
	}

	private static void emitBatchSliceBody(
	 InsnList out, BatchSliceSpec spec, int targetLocal,
	 int writtenLocal, int skippedLocal, LabelNode nextTargetLabel, Object[] targetLocals) {
		Map<LabelNode, LabelNode> emptyLabels = Collections.emptyMap();
		for (int i = 0; i <= spec.lastRealIdx(); i++) {
			AbstractInsnNode insn = spec.body().get(i);
			if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
				continue;
			}
			if (i == spec.statsIndyIdx()) {
				InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
				Object[] argsCopy = indy.bsmArgs.clone();
				argsCopy[0] = spec.statsKind() == 1
				 ? HotswapBridge.KIND_CONDITIONAL_BATCH
				 : HotswapBridge.KIND_CONDITIONAL_EXPECTED_BATCH;
				String batchIndyDesc = indy.desc.substring(0, indy.desc.length() - 1) + "Z";
				LabelNode skipStats = new LabelNode();
				out.add(new InvokeDynamicInsnNode(indy.name, batchIndyDesc, indy.bsm, argsCopy));
				out.add(new JumpInsnNode(Opcodes.IFEQ, skipStats));
				out.add(new VarInsnNode(Opcodes.LLOAD, writtenLocal));
				out.add(new InsnNode(Opcodes.LCONST_1));
				out.add(new InsnNode(Opcodes.LADD));
				out.add(new VarInsnNode(Opcodes.LSTORE, writtenLocal));
				out.add(new JumpInsnNode(Opcodes.GOTO, nextTargetLabel));

				out.add(skipStats);
				out.add(new FrameNode(Opcodes.F_FULL, targetLocals.length, targetLocals, 0, new Object[0]));
				out.add(new VarInsnNode(Opcodes.LLOAD, skippedLocal));
				out.add(new InsnNode(Opcodes.LCONST_1));
				out.add(new InsnNode(Opcodes.LADD));
				out.add(new VarInsnNode(Opcodes.LSTORE, skippedLocal));
				continue;
			}
			if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
				out.add(new VarInsnNode(Opcodes.ALOAD, targetLocal));
			} else {
				out.add(insn.clone(emptyLabels));
			}
		}
	}

	private static void emitFusedStatsFlushes(
	 InsnList out, Type hostType, List<BatchSliceSpec> specs,
	 int[] writtenLocals, int[] skippedLocals, Object[] frameLocals) {
		for (int j = 0; j < specs.size(); j++) {
			BatchSliceSpec s = specs.get(j);
			if (s.statsKind() == 0) continue;
			LabelNode nextFlush = new LabelNode();
			emitBatchStatsFlush(out, hostType, s.fieldName(), s.statsKind(),
			 writtenLocals[j], skippedLocals[j], nextFlush);
			out.add(nextFlush);
			out.add(new FrameNode(Opcodes.F_FULL, frameLocals.length, frameLocals, 0, new Object[0]));
		}
	}

	/** 识别批循环中的 bridge indy：0=无需统计(protected/force)，1=conditional，2=replay-expected，-1=不兼容。 */
	private static int classifyBatchIndy(InvokeDynamicInsnNode indy, String fieldName) {
		if (indy.bsmArgs == null || indy.bsmArgs.length < 2 || !(indy.bsmArgs[0] instanceof Integer kind)) {
			return 0;
		}
		if (BRIDGE_BSM.equals(indy.bsm)) {
			if (kind == HotswapBridge.KIND_CONDITIONAL) {
				if (!fieldName.equals(indy.name) || indy.desc == null || !indy.desc.endsWith(")V")) return -1;
				return 1;
			}
			return 0;
		}
		if (BRIDGE_BSM_EXPECTED.equals(indy.bsm)) {
			if (kind == HotswapBridge.KIND_CONDITIONAL_EXPECTED) {
				if (!fieldName.equals(indy.name) || indy.desc == null || !indy.desc.endsWith(")V")) return -1;
				return 2;
			}
			return -1;
		}
		// 遮蔽字段的 indy 需要同实例上的祖先读数，无法在批循环里用裸 target 局部量重放。
		if (BRIDGE_BSM_ANCESTOR.equals(indy.bsm)) return -1;
		return 0;
	}

	/**
	 * 识别形如 {@code ALOAD 0; <字面量常量>; INVOKEDYNAMIC (Lhost;T)V (CONDITIONAL|FORCE)} 的切片，
	 * 供 {@link HotswapBridge#constantBatchLoop} 走常驻 C2 批循环而免去每轮新隐藏类的编译延迟。
	 */
	static ConstantInitSpec extractConstantInitSpec(
	 String fieldName, String className, List<AbstractInsnNode> body) {
		if (body == null || body.isEmpty()) return null;
		AbstractInsnNode insn0 = null;
		AbstractInsnNode insn1 = null;
		AbstractInsnNode insn2 = null;
		int realCount = 0;
		for (AbstractInsnNode insn : body) {
			if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) {
				continue;
			}
			if (realCount == 0) insn0 = insn;
			else if (realCount == 1) insn1 = insn;
			else if (realCount == 2) insn2 = insn;
			else return null;
			realCount++;
		}
		if (realCount != 3) return null;
		if (!(insn0 instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != 0) return null;
		if (!(insn2 instanceof InvokeDynamicInsnNode indy)) return null;
		if (!BRIDGE_BSM.equals(indy.bsm) || !fieldName.equals(indy.name)) return null;
		if (indy.bsmArgs == null || indy.bsmArgs.length < 2 || !(indy.bsmArgs[0] instanceof Integer kind)) {
			return null;
		}
		if (kind != HotswapBridge.KIND_CONDITIONAL && kind != HotswapBridge.KIND_FORCE) return null;
		Type[] args = Type.getArgumentTypes(indy.desc);
		if (args.length != 2 || Type.getReturnType(indy.desc).getSort() != Type.VOID) return null;
		if (args[0].getSort() != Type.OBJECT || !className.equals(args[0].getInternalName())) return null;
		String fieldDesc = args[1].getDescriptor();
		Object value = decodeConstantPush(insn1, fieldDesc);
		if (value == UNSUPPORTED_CONST) return null;
		return new ConstantInitSpec(fieldDesc, kind == HotswapBridge.KIND_FORCE, value);
	}

	private static final Object UNSUPPORTED_CONST = new Object();

	private static Object decodeConstantPush(AbstractInsnNode insn, String fieldDesc) {
		int op = insn.getOpcode();
		return switch (fieldDesc) {
			case "Z" -> {
				Integer iv = decodeIntPush(insn);
				yield iv == null ? UNSUPPORTED_CONST : Boolean.valueOf(iv != 0);
			}
			case "B" -> {
				Integer iv = decodeIntPush(insn);
				yield iv == null ? UNSUPPORTED_CONST : Byte.valueOf((byte) iv.intValue());
			}
			case "C" -> {
				Integer iv = decodeIntPush(insn);
				yield iv == null ? UNSUPPORTED_CONST : Character.valueOf((char) iv.intValue());
			}
			case "S" -> {
				Integer iv = decodeIntPush(insn);
				yield iv == null ? UNSUPPORTED_CONST : Short.valueOf((short) iv.intValue());
			}
			case "I" -> {
				Integer iv = decodeIntPush(insn);
				yield iv == null ? UNSUPPORTED_CONST : iv;
			}
			case "J" -> {
				if (op == Opcodes.LCONST_0) yield 0L;
				if (op == Opcodes.LCONST_1) yield 1L;
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l) yield l;
				yield UNSUPPORTED_CONST;
			}
			case "F" -> {
				if (op == Opcodes.FCONST_0) yield 0.0f;
				if (op == Opcodes.FCONST_1) yield 1.0f;
				if (op == Opcodes.FCONST_2) yield 2.0f;
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Float f) yield f;
				yield UNSUPPORTED_CONST;
			}
			case "D" -> {
				if (op == Opcodes.DCONST_0) yield 0.0d;
				if (op == Opcodes.DCONST_1) yield 1.0d;
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double d) yield d;
				yield UNSUPPORTED_CONST;
			}
			default -> {
				if (!fieldDesc.startsWith("L") && !fieldDesc.startsWith("[")) yield UNSUPPORTED_CONST;
				if (op == Opcodes.ACONST_NULL) yield null;
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) yield s;
				yield UNSUPPORTED_CONST;
			}
		};
	}

	private static Integer decodeIntPush(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return ((IntInsnNode) insn).operand;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) return i;
		return null;
	}

	static Class<?> descriptorValueClass(String fieldDesc) {
		return switch (fieldDesc) {
			case "Z" -> boolean.class;
			case "B" -> byte.class;
			case "C" -> char.class;
			case "S" -> short.class;
			case "I" -> int.class;
			case "J" -> long.class;
			case "F" -> float.class;
			case "D" -> double.class;
			default -> Object.class;
		};
	}

	private static void emitBatchStatsFlush(
	 InsnList out, Type hostType, String fieldName, int statsKind,
	 int writtenLocal, int skippedLocal, LabelNode skipLabel) {
		out.add(new VarInsnNode(Opcodes.LLOAD, writtenLocal));
		out.add(new VarInsnNode(Opcodes.LLOAD, skippedLocal));
		out.add(new InsnNode(Opcodes.LOR));
		out.add(new InsnNode(Opcodes.LCONST_0));
		out.add(new InsnNode(Opcodes.LCMP));
		out.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
		out.add(new LdcInsnNode(hostType));
		out.add(new LdcInsnNode(fieldName));
		out.add(new VarInsnNode(Opcodes.LLOAD, writtenLocal));
		out.add(new VarInsnNode(Opcodes.LLOAD, skippedLocal));
		out.add(new MethodInsnNode(
		 Opcodes.INVOKESTATIC,
		 BRIDGE_INTERNAL,
		 statsKind == 1 ? "addConditionalStats" : "addReplayStats",
		 "(Ljava/lang/Class;Ljava/lang/String;JJ)V",
		 false));
	}

	/** 字段名里可能含 {@code . ; [ / < >} 等非法方法名字符（混淆器/字节码注入），替换掉。 */
	static String uniqueMethodName(String prefix, String fieldName, Set<String> used) {
		StringBuilder sb = new StringBuilder(prefix);
		for (int i = 0; i < fieldName.length(); i++) {
			char c = fieldName.charAt(i);
			sb.append(c == '.' || c == ';' || c == '[' || c == '/' || c == '<' || c == '>' ? '_' : c);
		}
		String base = sb.toString();
		String name = base;
		for (int n = 2; !used.add(name); n++) name = base + "$" + n;
		return name;
	}

	// ==================== 指令改写 ====================

	private static List<AbstractInsnNode> rewriteProtectedAccesses(
	 String hostInternal,
	 List<AbstractInsnNode> insns,
	 Map<AbstractInsnNode, ProtectedAccess> accesses) {

		if (accesses.isEmpty()) return insns;

		Type hostType = Type.getObjectType(hostInternal);

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			ProtectedAccess pa = accesses.get(insn);
			if (pa == null) {
				rewritten.add(insn);
				continue;
			}

			String ownerInternal = pa.owner();
			String indyDesc      = indyDescFor(pa, hostInternal);

			Object[] bsmArgs = new Object[]{
			 HotswapBridge.KIND_PROTECTED,
			 pa.opcode(),
			 Type.getObjectType(ownerInternal),
			 hostType
			};

			rewritten.add(new InvokeDynamicInsnNode(pa.name(), indyDesc, BRIDGE_BSM, bsmArgs));
			if (isDebugEnabled()) {
				log("Bridging protected access via indy: "
				    + ownerInternal + "." + pa.name() + " " + pa.desc()
				    + " (opcode=" + pa.opcode() + ", host=" + hostInternal + ")");
			}
		}
		return rewritten;
	}

	private static String indyDescFor(ProtectedAccess pa, String hostInternal) {
		return switch (pa.opcode()) {
			case Opcodes.GETFIELD -> "(L" + hostInternal + ";)" + pa.desc();
			case Opcodes.PUTFIELD -> "(L" + hostInternal + ";" + pa.desc() + ")V";
			case Opcodes.GETSTATIC -> "()" + pa.desc();
			case Opcodes.PUTSTATIC -> "(" + pa.desc() + ")V";
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> "(L" + hostInternal + ";" + pa.desc().substring(1);
			case Opcodes.INVOKESTATIC -> pa.desc();
			default -> throw new IllegalStateException(
			 "unexpected opcode for protected bridge: " + pa.opcode());
		};
	}

	private static List<AbstractInsnNode> rewritePrivateInvokes(
	 String className, List<AbstractInsnNode> insns) {
		if (insns.isEmpty()) return insns;

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			if (insn instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && !"<init>".equals(m.name)
			    && m.owner.equals(className)) {
				int newOpcode = m.itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
				rewritten.add(new MethodInsnNode(newOpcode, m.owner, m.name, m.desc, m.itf));

			} else if (insn instanceof InvokeDynamicInsnNode indy) {
				InvokeDynamicInsnNode replaced = rewriteIndyHandles(className, indy);
				rewritten.add(replaced != null ? replaced : indy);

			} else {
				rewritten.add(insn);
			}
		}
		return rewritten;
	}

	private static InvokeDynamicInsnNode rewriteIndyHandles(
	 String className, InvokeDynamicInsnNode indy) {
		Object[] args = indy.bsmArgs;
		if (args == null) return null;
		Object[] copy = null;
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof Handle h
			    && h.getTag() == Opcodes.H_INVOKESPECIAL
			    && !"<init>".equals(h.getName())
			    && h.getOwner().equals(className)) {
				if (copy == null) copy = args.clone();
				int newTag = h.isInterface()
				 ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
				copy[i] = new Handle(newTag, h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
			}
		}
		if (copy == null) return null;
		return new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, copy);
	}

	/**
	 * 把补丁片段里对目标字段的 {@code PUTFIELD}/{@code PUTSTATIC} 改写为
	 * {@code invokedynamic}，由 {@link HotswapBridge} 在链接期算好 Unsafe offset 后写入：
	 * {@link HotswapBridge#KIND_CONDITIONAL} 条件 CAS（仅当字段仍是类型默认值），
	 * {@link HotswapBridge#KIND_FORCE} 无条件写（{@code @RemoldReinit(mode = OVERWRITE)}），
	 * {@link HotswapBridge#KIND_CONDITIONAL_EXPECTED} 对旧初值常量的条件写（L1 存量重放）。
	 * <p>final 与非 final 走同一条路径，语义统一。</p>
	 * @param conditionalFields 走条件 CAS 的字段
	 * @param forceFields       走强制写的字段（两者不相交；force 优先）
	 * @param replayArguments   L1 重放字段 -> 旧初值常量（ASM bootstrap 常量）
	 * @param shadowAncestors   遮蔽新增字段 -> 祖先声明类内部名（期望值改为运行期读祖先字段）
	 */
	private static List<AbstractInsnNode> rewriteFieldPuts(
	 String className, List<AbstractInsnNode> insns,
	 Set<String> conditionalFields, Set<String> forceFields,
	 Map<String, Object> replayArguments, Map<String, String> shadowAncestors) {
		if (insns.isEmpty()
		    || (conditionalFields.isEmpty() && forceFields.isEmpty() && replayArguments.isEmpty())) {
			return insns;
		}

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			if (!(insn instanceof FieldInsnNode f)
			    || !f.owner.equals(className)
			    || (f.getOpcode() != Opcodes.PUTFIELD && f.getOpcode() != Opcodes.PUTSTATIC)) {
				rewritten.add(insn);
				continue;
			}

			Object replayExpected = replayArguments.get(f.name);
			if (replayExpected != null) {
				boolean isStatic = f.getOpcode() == Opcodes.PUTSTATIC;
				String  indyDesc = (isStatic ? "(" : "(L" + className + ";") + f.desc + ")V";
				Type    hostType = Type.getObjectType(className);
				Object[] bsmArgs = new Object[]{
				 HotswapBridge.KIND_CONDITIONAL_EXPECTED,
				 f.getOpcode(),
				 hostType,
				 hostType,
				 replayExpected
				};
				rewritten.add(new InvokeDynamicInsnNode(f.name, indyDesc, BRIDGE_BSM_EXPECTED, bsmArgs));
				if (isDebugEnabled()) {
					log("Rewriting field write via indy(replay-expected): "
					    + className + "." + f.name + " " + f.desc + ", expected=" + replayExpected);
				}
				continue;
			}

			boolean forced = forceFields.contains(f.name);
			if (!forced && !conditionalFields.contains(f.name)) {
				rewritten.add(insn);
				continue;
			}

			boolean isStatic = f.getOpcode() == Opcodes.PUTSTATIC;
			String  indyDesc = (isStatic ? "(" : "(L" + className + ";") + f.desc + ")V";
			Type    hostType = Type.getObjectType(className);

			String shadowAncestor = forced || isStatic ? null : shadowAncestors.get(f.name);
			if (shadowAncestor != null) {
				Object[] ancestorArgs = new Object[]{
				 HotswapBridge.KIND_CONDITIONAL_ANCESTOR,
				 f.getOpcode(),
				 hostType,
				 hostType,
				 Type.getObjectType(shadowAncestor)
				};
				rewritten.add(new InvokeDynamicInsnNode(f.name, indyDesc, BRIDGE_BSM_ANCESTOR, ancestorArgs));
				if (isDebugEnabled()) {
					log("Rewriting field write via indy(ancestor-conditional): "
					    + className + "." + f.name + " " + f.desc + ", ancestor=" + shadowAncestor);
				}
				continue;
			}

			Object[] bsmArgs = new Object[]{
			 forced ? HotswapBridge.KIND_FORCE : HotswapBridge.KIND_CONDITIONAL,
			 f.getOpcode(),
			 hostType,
			 hostType
			};

			rewritten.add(new InvokeDynamicInsnNode(f.name, indyDesc, BRIDGE_BSM, bsmArgs));
			if (isDebugEnabled()) {
				log("Rewriting field write via indy(" + (forced ? "force" : "conditional") + "): "
				    + className + "." + f.name + " " + f.desc);
			}
		}
		return rewritten;
	}

}
