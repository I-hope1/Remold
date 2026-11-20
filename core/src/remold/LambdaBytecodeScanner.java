package remold;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import remold.LambdaAligner.AlignmentEvent;
import remold.LambdaAligner.MatchContext;
import remold.LambdaAligner.SyntheticInfo;
import remold.vcs.DerivedDataCache;
import remold.util.Diag;
import remold.util.LongObjectMap;

import java.util.*;

/**
 * Lambda 对齐字节码转换、消费者数据流分析、匿名类引用扫描与 LVT 形参提取助手（从 {@link LambdaAligner} 拆出）。
 */
final class LambdaBytecodeScanner {

	private LambdaBytecodeScanner() {}

	/**
	 * 应用转换规则到字节码，返回 remapped 后的 {@link ClassNode}。
	 *
	 * <p>为什么返回 {@code ClassNode} 而不是 {@code byte[]}：让调用方在写出之前
	 * 有机会直接从同一份节点上收集 {@code name + desc} 集合（供
	 * {@link LambdaOrphanEmitter#resurrectOrphanedLambdas} 判断孤儿），避免重复解析 newBytes。</p>
	 *
	 * <p>本方法使用 {@code ClassReader(bytes).accept(cn, 0)} 完整读取，
	 * 因为要写出合法的 Class 文件必须保留 StackMapTable 帧数据。</p>
	 *
	 * <p>整体流程：</p>
	 * <ol>
	 *   <li>读入 {@link ClassNode}。</li>
	 *   <li>通过 {@link ClassRemapper} 应用方法名与字符串常量的重命名。</li>
	 *   <li>对 {@code $deserializeLambda$} 做 {@code lookupswitch} 的 key 后处理。</li>
	 *   <li>返回 remapped 后的节点，由调用方写出。</li>
	 * </ol>
	 */
	static ClassNode applyTransform(byte[] bytes, MatchContext ctx) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);

		ClassNode remapped = new ClassNode();
		Remapper remapper = new Remapper(Opcodes.ASM9) {
			@Override
			public String mapMethodName(String owner, String name, String desc) {
				if (owner.equals(ctx.currentClass)) {
					String nn = ctx.renameMap.get(name + desc);
					if (nn != null) return nn;
				}
				return name;
			}

			@Override
			public Object mapValue(Object value) {
				if (value instanceof String s && MethodFingerprinter.isSyntheticName(s)) {
					// 同名歧义时不动字符串常量，避免误改
					if (!ctx.ambiguousNames.contains(s)) {
						String nn = ctx.renameBySimpleName.get(s);
						if (nn != null) return nn;
					}
				}
				return super.mapValue(value);
			}
		};
		cn.accept(new ClassRemapper(remapped, remapper));

		// 后处理：$deserializeLambda$ 里的 lookupswitch hash key 必须跟着字符串常量一起更新
		for (MethodNode mn : remapped.methods) {
			if ("$deserializeLambda$".equals(mn.name)) {
				fixDeserializeSwitch(mn);
			}
		}
		return remapped;
	}

	/** 把 {@link ClassNode} 序列化为字节码。 */
	static byte[] writeClass(ClassNode cn) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 收集一个类里所有方法的 {@code name + desc}，并校验方法键唯一性（防重复定义引发 ClassFormatError）。 */
	static Set<String> collectMethodKeys(ClassNode cn) {
		Set<String> keys = new HashSet<>(cn.methods.size() * 2);
		for (MethodNode mn : cn.methods) {
			if (!keys.add(mn.name + mn.desc)) {
				throw new IllegalStateException("Duplicate method after align: " + mn.name + mn.desc);
			}
		}
		return keys;
	}

	/** 后置廉价结构校验：确保类内所有指向本类的 invokedynamic 目标方法实际存在。 */
	static void validateIndyTargets(ClassNode cn, Set<String> presentKeys) {
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy) {
					if (indy.bsmArgs != null && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
						if (cn.name.equals(h.getOwner()) && MethodFingerprinter.isSyntheticName(h.getName())) {
							String key = h.getName() + h.getDesc();
							if (!presentKeys.contains(key)) {
								throw new IllegalStateException("Missing indy target method: " + key + " in " + cn.name);
							}
						}
					}
				}
			}
		}
	}

	/**
	 * 重建 {@code $deserializeLambda$} 中 {@code String.hashCode()} 分派的
	 * {@code lookupswitch} 键值。
	 *
	 * <p>javac 生成的模式：</p>
	 * <pre>
	 *   s.hashCode() -> LOOKUPSWITCH
	 *     Label_1: LDC "lambda$foo$1"; s.equals(...); ...
	 *     Label_2: LDC "lambda$foo$2"; s.equals(...); ...
	 * </pre>
	 *
	 * <p>重命名器会改写 LDC 字符串，但 int 型 key 是常量，不会被自动更新，
	 * 因此必须重新计算并按升序重排。</p>
	 *
	 * <p><b>容错策略</b>：某个 case 块内找不到“符合合成名特征的 implMethodName
	 * 字面量”时，<b>沿用该 case 的原 key</b>，而不是放弃整个 switch 修复。
	 * 这保证了“同时包含方法引用（如 {@code "trim"}）和 lambda 的类”也能被
	 * 正确对齐——方法引用名不会被 Remapper 改写，其原 key 天然正确。</p>
	 *
	 * <p>重算后若 key 之间发生碰撞，才放弃整个 switch 的改写，并打一条警告。
	 * 不抛异常的原因：与其让整次热更失败，不如保留原 switch（对没被改名的
	 * lambda 仍然可用）。</p>
	 */
	private static void fixDeserializeSwitch(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions.toArray()) {
			if (!(n instanceof LookupSwitchInsnNode sw)) continue;
			if (!isStringHashSwitch(sw)) continue;

			// 边界：本 switch 的所有 case label 以及 default label
			Set<LabelNode> boundaries = new HashSet<>(sw.labels);
			if (sw.dflt != null) boundaries.add(sw.dflt);

			int   size    = sw.labels.size();
			int[] newKeys = new int[size];
			for (int i = 0; i < size; i++) {
				String s = uniqueStringLdcInBlock(sw.labels.get(i), boundaries);
				if (s != null) {
					newKeys[i] = s.hashCode();
				} else {
					// 块内没有可识别的合成方法名 —— 沿用原 key。
					// 常见于：方法引用（"trim" / "doWork"）或描述符（"()V"）之类
					// 不会被 Remapper 改写的字符串，其 hash 保持不变，原 key 天然有效。
					newKeys[i] = sw.keys.get(i);
				}
			}

			// 碰撞检测：重算后的 key 不允许重复
			Set<Integer> seen = new HashSet<>();
			boolean      ok   = true;
			for (int k : newKeys) {
				if (!seen.add(k)) {
					ok = false;
					break;
				}
			}
			if (!ok) {
				warnSkipSwitch(mn, "hashCode collision detected after recalculation");
				continue;
			}

			// 按新 key 升序重排 labels（lookupswitch 的 key 必须升序）
			Integer[] order = new Integer[size];
			for (int i = 0; i < size; i++) order[i] = i;
			final int[] nk = newKeys;
			Arrays.sort(order, Comparator.comparingInt(i -> nk[i]));

			List<LabelNode> newLabels  = new ArrayList<>(size);
			List<Integer>   newKeyList = new ArrayList<>(size);
			for (int i : order) {
				newLabels.add(sw.labels.get(i));
				newKeyList.add(newKeys[i]);
			}
			sw.labels = newLabels;
			sw.keys = newKeyList;
		}
	}

	/**
	 * 判断一个 {@link LookupSwitchInsnNode} 是否是 {@code String.hashCode()} 分派。
	 * <p>启发式规则：其前一条有效指令为 {@code java/lang/String.hashCode()I}。</p>
	 */
	private static boolean isStringHashSwitch(LookupSwitchInsnNode sw) {
		AbstractInsnNode p = sw.getPrevious();
		while (p != null && (p.getOpcode() < 0 || p.getOpcode() == Opcodes.NOP)) {
			p = p.getPrevious();
		}
		if (!(p instanceof MethodInsnNode mi)) return false;
		return "java/lang/String".equals(mi.owner)
		       && "hashCode".equals(mi.name)
		       && "()I".equals(mi.desc);
	}

	/**
	 * 从 case 块中提取“implMethodName 比较用的”字符串常量。
	 *
	 * <p><b>定位规则</b>：扫描块内所有 {@code java/lang/String.equals} 调用，
	 * 只看与它关联的 LDC 参数（见下），且该字符串必须满足
	 * {@link MethodFingerprinter#isSyntheticName}。这会过滤掉：</p>
	 * <ul>
	 *   <li>ECJ 单 switch 布局下 {@code getFunctionalInterfaceClass} /
	 *       {@code getImplMethodSignature} 比较用的 {@code "com/foo/Bar"}、
	 *       {@code "()V"} 等字符串；</li>
	 *   <li>方法引用名（如 {@code "trim"}）—— 它们不会被 Remapper 改写，
	 *       原 key 天然正确，无需重算。</li>
	 * </ul>
	 *
	 * <p><b>两种字节码形态</b>都要覆盖：</p>
	 * <ol>
	 *   <li><b>标准形式</b>{@code s.equals("const")}：栈序 {@code [ALOAD, LDC]}，
	 *       则 equals 的直接前驱就是 LDC。</li>
	 *   <li><b>反向形式</b>{@code "const".equals(s)}：栈序 {@code [LDC, ALOAD]}，
	 *       则 equals 的前驱是 ALOAD，需要再往前一步才是 LDC。
	 *       某些字节码优化器或非标准编译产出会出现这种形态。</li>
	 * </ol>
	 *
	 * <p>注意 ASM 在读取时会把 {@code aload_0}~{@code aload_3} 这类无操作数形式
	 * 归一化为 {@code visitVarInsn(ALOAD, n)}，因此只需判断
	 * {@code VarInsnNode} 且 {@code getOpcode() == Opcodes.ALOAD} 即可覆盖全部
	 * 变量加载变体。</p>
	 *
	 * <p>块内若出现两个 <b>不同</b> 的合成名字符串（hash 碰撞导致的合并 case），
	 * 返回 {@code null} 由调用方沿用原 key；只出现一个合成名时返回它。</p>
	 *
	 * <p><b>终止条件</b>：</p>
	 * <ol>
	 *   <li>遇到 {@code boundaries} 中任一标签（本 switch 的其它 case 或 default）。</li>
	 *   <li>遇到无条件跳转或返回指令 —— 当前 case 逻辑已终结，后续指令属于其它
	 *       基本块，不应计入。若缺此截断，“最后一个 case”会一路穿透到
	 *       {@code $deserializeLambda$} 尾部的 LDC，使统计结果错误。</li>
	 *   <li>指令流自然结束。</li>
	 * </ol>
	 */
	private static String uniqueStringLdcInBlock(LabelNode start, Set<LabelNode> boundaries) {
		String found = null;
		for (AbstractInsnNode n = start.getNext(); n != null; n = n.getNext()) {
			if (n instanceof LabelNode l && boundaries.contains(l)) break;

			if (n instanceof MethodInsnNode mi
			    && "java/lang/String".equals(mi.owner)
			    && "equals".equals(mi.name)
			    && "(Ljava/lang/Object;)Z".equals(mi.desc)) {

				AbstractInsnNode prev = previousRealInsn(mi);

				// 反向形式 "const".equals(s)：前一条是 ALOAD，再往前才是 LDC。
				// 标准形式 s.equals("const")：前一条就是 LDC，不做这一跳。
				// ASM 会把 aload_0~aload_3 归一化为 VarInsnNode(ALOAD, n)，
				// 所以只需判断 opcode == ALOAD 就能覆盖全部变体。
				if (prev instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD) {
					prev = previousRealInsn(prev);
				}

				if (prev instanceof LdcInsnNode ldc
				    && ldc.cst instanceof String s
				    && MethodFingerprinter.isSyntheticName(s)) {
					if (found == null) {
						found = s;
					} else if (!found.equals(s)) {
						// 块内出现两个不同的合成名（hash 碰撞合并 case），
						// 保守返回 null，由调用方沿用原 key。
						return null;
					}
				}
			}

			// 遇到无条件跳转或返回指令：当前 case 逻辑已终结，立即截断
			int op = n.getOpcode();
			if (op == Opcodes.GOTO
			    || op == Opcodes.RETURN
			    || op == Opcodes.IRETURN || op == Opcodes.LRETURN
			    || op == Opcodes.FRETURN || op == Opcodes.DRETURN
			    || op == Opcodes.ARETURN
			    || op == Opcodes.ATHROW) {
				break;
			}
		}
		return found;
	}

	/**
	 * 跳过 {@link FrameNode} / {@link LabelNode} / {@link LineNumberNode}
	 * 以及插桩工具插入的 {@code NOP}，返回前一条真正的指令节点；
	 * 没有则返回 {@code null}。
	 *
	 * <p>与 {@link #isStringHashSwitch} 内部的过滤保持对称：都同时跳过
	 * “伪节点（opcode &lt; 0）”和 {@code NOP}。</p>
	 */
	private static AbstractInsnNode previousRealInsn(AbstractInsnNode n) {
		AbstractInsnNode p = n.getPrevious();
		while (p != null && (p.getOpcode() < 0 || p.getOpcode() == Opcodes.NOP)) {
			p = p.getPrevious();
		}
		return p;
	}

	/** 放弃修复时留痕，避免“静默失效”。 */
	private static void warnSkipSwitch(MethodNode mn, String reason) {
		Diag.info("[LambdaAligner] Skipped $deserializeLambda$ switch repair ("
		                  + mn.name + mn.desc + "): " + reason);
	}


	/**
	 * 按 blob 内容缓存的"方法 → 引用的匿名类"索引（Per-Blob Derived Data）。
	 *
	 * <p>纯函数：只由 {@code blob}（及其自带类名）决定，与轮次、loader、resolver 状态无关；
	 * 值是不可变 {@code Map<String, Set<String>>}，不持有 {@code ClassNode}/{@code Class}/{@code ClassLoader}。
	 * 连续热更中"上一轮的 new 字节 == 这一轮的 old 字节"时，old 侧可直接复用该索引。</p>
	 */
	static Map<String, Set<String>> deriveAnonRefs(String blobKey, ClassNode cn) {
		if (blobKey == null) return collectAnonRefs(cn);
		long estimate = Math.max(64L, cn.methods.size() * 48L);
		return DerivedDataCache.getByKey("anonRefs", blobKey, estimate, () -> collectAnonRefs(cn));
	}

	private static Map<String, Set<String>> collectAnonRefs(ClassNode cn) {
		Map<String, Set<String>> out = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			Set<String> refs = referencedAnonClasses(mn, cn.name);
			if (refs != null && !refs.isEmpty()) out.put(mn.name + mn.desc, Set.copyOf(new HashSet<>(refs)));
		}
		return Map.copyOf(out);
	}

	/** 收集方法体内直接引用的、属于当前宿主类的匿名类内部名。 */
	private static Set<String> referencedAnonClasses(MethodNode mn, String hostClassName) {
		Set<String> set = null;
		for (AbstractInsnNode insn : mn.instructions) {
			String owner = null;
			if (insn instanceof TypeInsnNode tin) {
				owner = tin.desc;
			} else if (insn instanceof MethodInsnNode min) {
				owner = min.owner;
			} else if (insn instanceof FieldInsnNode fin) {
				owner = fin.owner;
			} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t) {
				if (t.getSort() == Type.OBJECT) owner = t.getInternalName();
			}
			if (owner != null && isAnonClassOf(owner, hostClassName)) {
				if (set == null) set = new HashSet<>(4);
				set.add(owner);
			}
		}
		return set == null ? Collections.emptySet() : set;
	}

	private static boolean isAnonClassOf(String className, String hostClassName) {
		if (className == null) return false;
		if (className.startsWith("[")) {
			int idx = className.lastIndexOf('[');
			if (idx + 2 < className.length() && className.charAt(idx + 1) == 'L' && className.endsWith(";")) {
				className = className.substring(idx + 2, className.length() - 1);
			}
		}
		if (!className.startsWith(hostClassName + "$")) return false;
		String suffix = className.substring(hostClassName.length() + 1);
		return MethodFingerprinter.isUnstableNestedSuffix(suffix);
	}

	/**
	 * 基于 ASM {@link Analyzer} 和 {@link SourceInterpreter} 进行反向数据流栈分析，
	 * 精确提取方法内所有 Lambda 实例化指令（invokedynamic / method call）的操作数栈消费目标。
	 *
	 * <p>相较于单纯向后线性扫描，本方法具备完全的栈感知能力，能免疫中间指令及参数前置计算的干扰：
	 * <ul>
	 *   <li>{@code foo(() -> a, () -> b)}：两个 indy 均能准确识别为同一 {@code CALL:...foo} 的入参；</li>
	 *   <li>{@code a(() -> x, 5)}：indy 之后的常量加载指令不会干扰消费者判定；</li>
	 *   <li>{@code a(() -> x, bar())}：作为参数的嵌套调用 {@code bar()} 不会被误判为消费端；</li>
	 *   <li>三元表达式 / 分支合并（多生产者）：返回空证据，严守 Fail-Closed 拒绝猜测；</li>
	 *   <li>排除本地变量存储（{@code ASTORE}）作为特征，防止前插局部变量导致槽位位移误判。</li>
	 * </ul>
	 * </p>
	 */
	private static Map<AbstractInsnNode, String> findConsumers(String owner, MethodNode mn) {
		if (LambdaAligner.isMutationActive("4")) return Collections.emptyMap();
		Map<AbstractInsnNode, String> map = new HashMap<>();
		if (mn.instructions == null || mn.instructions.size() == 0) return map;
		Frame<SourceValue>[] frames;
		try {
			Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
			frames = analyzer.analyze(owner, mn);
		} catch (AnalyzerException | RuntimeException t) {
			// 分析失败时保守降级，返回空映射
			return Collections.emptyMap();
		}
		if (frames == null) return Collections.emptyMap();

		AbstractInsnNode[] insns = mn.instructions.toArray();
		for (int i = 0; i < insns.length; i++) {
			AbstractInsnNode insn = insns[i];
			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;

			String consumerTag = null;
			int consumedCount = 0;

			if (insn instanceof MethodInsnNode min) {
				Type[] args = Type.getArgumentTypes(min.desc);
				consumedCount = args.length + (min.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
				consumerTag = "CALL:" + min.owner + "." + min.name + min.desc;
			} else if (insn instanceof InvokeDynamicInsnNode indy) {
				Type[] args = Type.getArgumentTypes(indy.desc);
				consumedCount = args.length;
				consumerTag = "INDY:" + indy.name + indy.desc;
			} else if (insn instanceof FieldInsnNode fin) {
				if (fin.getOpcode() == Opcodes.PUTFIELD) {
					consumedCount = 2; // objectref + value
					consumerTag = "PUT:" + fin.owner + "." + fin.name + fin.desc;
				} else if (fin.getOpcode() == Opcodes.PUTSTATIC) {
					consumedCount = 1; // value
					consumerTag = "PUT:" + fin.owner + "." + fin.name + fin.desc;
				}
			} else if (insn.getOpcode() == Opcodes.ARETURN) {
				consumedCount = 1;
				consumerTag = "RETURN";
			} else if (insn.getOpcode() == Opcodes.ATHROW) {
				consumedCount = 1;
				consumerTag = "THROW";
			}

			if (consumerTag != null && consumedCount > 0) {
				int stackSize = frame.getStackSize();
				int startSlot = Math.max(0, stackSize - consumedCount);
				for (int slot = startSlot; slot < stackSize; slot++) {
					SourceValue val = frame.getStack(slot);
					if (val == null || val.insns == null) continue;
					if (val.insns.size() == 1) {
						AbstractInsnNode producer = val.insns.iterator().next();
						if (!map.containsKey(producer)) {
							map.put(producer, consumerTag);
						} else if (!consumerTag.equals(map.get(producer))) {
							map.put(producer, ""); // 冲突歧义置空
						}
					} else {
						// 多生产者（三元表达式/分支合并等）：标记歧义，拒绝猜测
						for (AbstractInsnNode producer : val.insns) {
							map.put(producer, "");
						}
					}
				}
			}
		}
		return map;
	}


	/**
	 * 单个方法的消费者标记表：按指令流下标对齐，{@code null} 表示该指令不是消费者入口。
	 *
	 * <p>粒度选在<b>方法</b>而不是"整类一次性派生"：整类急切派生会在未命中时把类里所有方法都分析一遍，
	 * 而扫描只走得到其中一部分；实测"旧侧命中 + 新侧未命中"的真实批次形态下，整类粒度的净收益被
	 * 未命中侧的过度派生吃掉（{@code DerivedDataBench} 的读数）。按方法派生的未命中量恰好等于
	 * 扫描真正需要的那几个方法。</p>
	 */
	private static List<String> deriveConsumerTags(String owner, MethodNode mn) {
		Map<AbstractInsnNode, String> byNode = findConsumers(owner, mn);
		if (byNode.isEmpty()) return List.of();
		AbstractInsnNode[] insns = mn.instructions.toArray();
		String[] tags = new String[insns.length];
		boolean any = false;
		for (int i = 0; i < insns.length; i++) {
			String tag = byNode.get(insns[i]);
			if (tag == null) continue;
			tags[i] = tag;
			any = true;
		}
		return any ? Collections.unmodifiableList(Arrays.asList(tags)) : List.of();
	}

	/**
	 * 该方法体里是否存在"消费者标记会被读"的指令位点。
	 *
	 * <p>调用方只在<b>本类的 lambda 调用位点</b>（{@code invokedynamic} 的引导参数指向本类方法、
	 * 或 {@code MethodInsnNode} 指向本类且名字命中合成名模式）上读消费者标记；一个方法里没有这种位点时，
	 * 它的标记表<b>永远不会被读到</b>。</p>
	 *
	 * <p>所以这一层预判必须与读取端逐字对齐：判据偏移一位就会让某类方法静默丢掉消费者证据。
	 * 超大类里通常只有少数方法写了 lambda，其余方法若照常跑 ASM {@code Analyzer}，既白白消耗 CPU，
	 * 又会挤占缓存条目池。</p>
	 */
	private static boolean hasConsumerSite(ClassNode cn, MethodNode mn) {
		if (mn.instructions == null) return false;
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode indy) {
				if (indy.bsmArgs == null || indy.bsmArgs.length < 2) continue;
				if (indy.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())
				    && MethodFingerprinter.isSyntheticName(h.getName())) {
					return true;
				}
			} else if (n instanceof MethodInsnNode m) {
				if (cn.name.equals(m.owner) && MethodFingerprinter.isSyntheticName(m.name)) return true;
			}
		}
		return false;
	}

	/**
	 * {@link #findConsumers} 的按方法缓存版：命中时按指令下标重建本轮节点映射，未命中走原实现。
	 *
	 * <p>缓存键 = {@code (blob 内容, 方法 name+desc)}：方法维度必须带<b>描述符</b>（同名重载是合法形态，
	 * 只用名字会互相覆盖），且同一份 blob 里不同方法的标记表完全不同，漏掉维度会让标记整表错位。
	 * 值里存下标而不是 {@code AbstractInsnNode}：节点是本轮解析出来的可变对象，跨轮持有既会过期也会驻留整棵解析树。</p>
	 *
	 * <p>以下情形一律走原实现（不读不写缓存）：方法里没有消费者位点（标记永远不会被读，
	 * 见 {@link #hasConsumerSite}）、缓存已关闭、blob 键不可用、{@link LambdaAligner#ACTIVE_MUTATION} 非空
	 * ——最后一项是设计级变异测试的钩子，其结果不得跨变异档位复用。</p>
	 */
	static Map<AbstractInsnNode, String> consumersOf(String blobKey, ClassNode cn, MethodNode mn) {
		if (!hasConsumerSite(cn, mn)) return Collections.emptyMap();
		if (blobKey == null || !DerivedDataCache.enabled()
		    || (LambdaAligner.ACTIVE_MUTATION != null && !LambdaAligner.ACTIVE_MUTATION.isEmpty())) {
			return findConsumers(cn.name, mn);
		}
		List<String> tags = DerivedDataCache.getByKey(
			LambdaAligner.CONSUMER_CACHE_KIND + '/' + mn.name + mn.desc, blobKey,
			Math.max(64L, 16L + 8L * mn.instructions.size()),
			() -> deriveConsumerTags(cn.name, mn));
		if (tags.isEmpty()) return Collections.emptyMap();
		AbstractInsnNode[] insns = mn.instructions.toArray();
		if (tags.size() != insns.length) {
			// 指令流与标记表对不上：说明缓存条目与本次解析口径不一致（例如未来某处换了 ClassReader 标志）。
			// 这种情形绝不能按下标硬映射——直接退回重算，宁可少一次命中，也不能让标记错位。
			return findConsumers(cn.name, mn);
		}
		Map<AbstractInsnNode, String> out = new HashMap<>();
		for (int i = 0; i < insns.length; i++) {
			String tag = tags.get(i);
			if (tag != null) out.put(insns[i], tag);
		}
		return out;
	}


	/**
	 * 只读一遍字节码，取合成方法<b>形参槽</b>的 {@code LocalVariableTable} 名字。
	 *
	 * <p>为什么单开一趟而不是把 {@code scan} 的 {@code SKIP_DEBUG} 去掉：{@code scan} 要读
	 * 指令流算指纹，多带调试信息会拖慢主路径，也会让指纹受 {@code -g} 设置影响。这里只关心
	 * 名字，非合成方法直接 {@code return null}，连 {@code Code} 都不展开。</p>
	 *
	 * <p>名字是比槽号更硬的证据：宿主方法里增删前序局部变量会让槽号整体位移，名字不会。
	 * 未带 {@code -g} 编译时整张表为空，调用方退回槽号/类型判据。</p>
	 *
	 * <p><b>静态方法的首形参可能是被前置的接收者</b>：{@code forceStaticLambdas} 会把实例方法
	 * 改成静态方法并把 {@code this} 变成显式首形参，而那个槽是<b>新加的、没有 LVT 条目</b>的，
	 * 读出来是 {@code null}。这里统一记成 {@link #LambdaAligner.RECEIVER_NAME}——否则"任一位缺名字就整体
	 * 放弃按名映射"那条严格判据会把这类 lambda 全部判成没证据。</p>
	 *
	 * <p><b>这里只归一 {@code null} 与 {@code "this"}，不碰 {@code "this$0"}</b>：前两者是
	 * 无歧义的（{@code this} 是关键字，不可能是变量名；{@code null} 表示没有 LVT 条目），
	 * 而 {@code this$0} 是合法的 Java 标识符——一个真叫 {@code this$0} 的局部变量会被误归一。
	 * 那一档留给 {@link #captureNamesOf}：它手里有捕获的宿主槽号，能证明"这一位到底是不是
	 * 宿主 {@code aload 0} 压上来的"。本方法只报告 LVT 里写了什么。</p>
	 *
	 * @return {@code name + desc -> 形参名字数组}（按形参顺序；实例方法不含接收者）
	 */
	static Map<String, String[]> readLambdaParamNames(byte[] bytes) {
		Map<String, String[]> out = new HashMap<>();
		if (bytes == null || bytes.length == 0) return out;
		ClassReader cr = new ClassReader(bytes);
		final String owner = cr.getClassName();
		cr.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int acc, String name, String desc,
			                                 String sig, String[] ex) {
				return wrapForParamNames(null, owner, acc, name, desc, out, false);
			}
		}, ClassReader.SKIP_FRAMES);
		return out;
	}

	/**
	 * 包装可选下游 {@link MethodVisitor}，为合成 lambda 方法提取形参 LVT 名字；
	 * {@code stripDebugNodes} 为 true 时不向 {@code mv} 转发调试事件，使生成的
	 * {@link MethodNode} 与 {@code ClassReader.SKIP_DEBUG} 保持一致。
	 */
	static MethodVisitor wrapForParamNames(
	 MethodVisitor mv, String owner, int acc, String name, String desc,
	 Map<String, String[]> out, boolean stripDebugNodes) {
		boolean collect = out != null
			&& MethodFingerprinter.isSyntheticName(name)
			&& !name.startsWith("access$");
		if (!collect) {
			if (!stripDebugNodes || mv == null) return mv;
			return new MethodVisitor(Opcodes.ASM9, mv) {
				@Override
				public void visitLineNumber(int line, Label start) {}

				@Override
				public void visitLocalVariable(String n, String d, String sg,
				                               Label st, Label en, int idx) {}

				@Override
				public AnnotationVisitor visitLocalVariableAnnotation(
				 int typeRef, TypePath typePath, Label[] start, Label[] end,
				 int[] index, String descriptor, boolean visible) {
					return null;
				}
			};
		}
		boolean isStatic = (acc & Opcodes.ACC_STATIC) != 0;
		Type[] args = Type.getArgumentTypes(desc);
		int[] slotOf = new int[args.length];
		int s = isStatic ? 0 : 1;
		for (int i = 0; i < args.length; i++) {
			slotOf[i] = s;
			s += args[i].getSize();
		}
		int paramEnd = s;
		String[] names = new String[args.length];
		out.put(name + desc, names);
		return new MethodVisitor(Opcodes.ASM9, mv) {
			@Override
			public void visitLineNumber(int line, Label start) {
				if (!stripDebugNodes) super.visitLineNumber(line, start);
			}

			@Override
			public void visitLocalVariable(String n, String d, String sg,
			                               Label st, Label en, int idx) {
				if (idx < paramEnd) {              // 只要形参，丢弃方法体局部变量
					for (int i = 0; i < slotOf.length; i++) {
						if (slotOf[i] == idx && args[i].getDescriptor().equals(d) && names[i] == null) {
							names[i] = n;
						}
					}
				}
				if (!stripDebugNodes) {
					super.visitLocalVariable(n, d, sg, st, en, idx);
				}
			}

			@Override
			public AnnotationVisitor visitLocalVariableAnnotation(
			 int typeRef, TypePath typePath, Label[] start, Label[] end,
			 int[] index, String descriptor, boolean visible) {
				return stripDebugNodes
					? null
					: super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, descriptor, visible);
			}

			@Override
			public void visitEnd() {
				// 静态方法的首形参可能是 forceStaticLambdas 前置的接收者：槽是新增的，
				// 没有 LVT 条目，读出来是 null；若它本来就是实例方法、只是被改成静态，
				// LVT 里留着 javac 写的 "this"。两者指同一个东西，统一记成 LambdaAligner.RECEIVER_NAME，
				// 否则同一个 lambda 在两种来源之间切换会因 "this" vs null 对不上而丢掉按名映射。
				// 判据是"类型是本类"而不是"名字为空"——真正的同名局部变量会带自己的名字。
				if (isStatic && names.length > 0
				    && (names[0] == null || "this".equals(names[0]))
				    && isReceiverType(args[0].getDescriptor(), owner)) {
					names[0] = LambdaAligner.RECEIVER_NAME;
				}
				super.visitEnd();
			}
		};
	}


	/**
	 * 把合成方法的形参名裁成"捕获名"：前 {@code captured - offset} 项即捕获变量
	 * （实例 lambda 的形参表不含接收者，故 {@code captured[0]} 由这里补上）。
	 *
	 * <p>接收者槽一律记作 {@link #LambdaAligner.RECEIVER_NAME}。静态方法里那个"被前置的接收者"有<b>三种</b>
	 * 字节码来源（见 {@link #LambdaAligner.RECEIVER_NAME}），{@code LocalVariableTable} 各不相同，所以归一
	 * 分两档：</p>
	 * <ul>
	 *   <li><b>结构证据优先</b>：{@code captureSlots[0] == 0} 且宿主是非静态方法，说明这一位
	 *       就是从宿主方法里 {@code aload 0} 压上来的 {@code this}。这是证明，不看名字。</li>
	 *   <li><b>没有结构证据时</b>（回溯失败）才退回名字：{@code null} / {@code "this"} /
	 *       {@code "this$0"}。</li>
	 * </ul>
	 *
	 * <p>反过来，结构证据<b>证伪</b>（首捕获的槽号不是 0）时，名字即使是 {@code "this$0"} 也
	 * <b>不得</b>归一：{@code this$0} 是合法的 Java 标识符，源码里真的可以写
	 * {@code void d(Foo this$0)}。把这种变量当成接收者，桥就会把接收者喂给一个本该是别的对象
	 * 的位置——静默错值。宁可这一位没名字（按名映射整体弃权，退回槽号/类型推断），也不猜。</p>
	 *
	 * @param captureSlots 每个捕获在<b>宿主方法</b>里的局部变量槽号；回溯失败时为 {@code null}
	 * @param hostIsStatic 宿主方法是否静态（静态宿主的槽 0 是形参，不可能是 {@code this}）
	 */
	private static List<String> captureNamesOf(String[] paramNames, List<String> captured,
	                                           boolean isStatic, String owner,
	                                           List<Integer> captureSlots, boolean hostIsStatic) {
		if (paramNames == null) return null;
		int offset = isStatic ? 0 : 1;
		int n = captured.size() - offset;
		if (n < 0 || paramNames.length < n) return null;
		List<String> out = new ArrayList<>(captured.size());
		if (!isStatic) out.add(LambdaAligner.RECEIVER_NAME);                     // 接收者没有源码名
		for (int i = 0; i < n; i++) out.add(paramNames[i]);
		if (!out.isEmpty() && isReceiverType(captured.get(0), owner)) {
			boolean structural = captureSlots != null && !captureSlots.isEmpty() && !hostIsStatic;
			boolean receiver = structural ? captureSlots.get(0) == 0 : isReceiverName(out.get(0));
			if (receiver) out.set(0, LambdaAligner.RECEIVER_NAME);
		}
		return out;
	}

	/**
	 * 各编译器给"捕获到的外层 {@code this}"写过的 {@code LocalVariableTable} 名字。
	 *
	 * <p>javac 写 {@code "this"}（且只在实例方法被改写成静态时保留下来），kotlinc 写
	 * {@code "this$0"}，{@code forceStaticLambdas} 前置的新槽干脆没有条目（{@code null}）。
	 * 三者指同一个东西。</p>
	 */
	private static boolean isReceiverName(String n) {
		return n == null || LambdaAligner.RECEIVER_NAME.equals(n) || "this".equals(n) || "this$0".equals(n);
	}

	/** 该描述符是不是"当前类自己的引用"（{@code forceStaticLambdas} 前置 receiver 的形态）。 */
	private static boolean isReceiverType(String desc, String owner) {
		return owner != null && desc != null && desc.equals("L" + owner + ";");
	}

	private static int getReceiverOffset(SyntheticInfo info, String owner) {
		if (info == null) return 0;
		if (!info.isStatic()) return 1;
		if (info.captureNames != null && !info.captureNames.isEmpty() && isReceiverName(info.captureNames.get(0))) {
			return 1;
		}
		if (info.captured != null && !info.captured.isEmpty() && isReceiverType(info.captured.get(0), owner)
		    && info.captureSlots != null && !info.captureSlots.isEmpty() && info.captureSlots.get(0) == 0) {
			return 1;
		}
		return 0;
	}

	static boolean hasCapturedVariables(SyntheticInfo info, String owner) {
		if (info == null || info.captured == null || info.captured.isEmpty()) return false;
		int receiverOffset = getReceiverOffset(info, owner);
		return info.captured.size() > receiverOffset;
	}

	static boolean hasCapturedLambdaWithoutLvt(SyntheticInfo info, String owner) {
		if (info == null || info.ghost || info.bridge || !info.renameable) return false;
		if (!hasCapturedVariables(info, owner)) return false;
		if (info.captureNames == null) return true;
		int receiverOffset = getReceiverOffset(info, owner);
		for (int i = receiverOffset; i < info.captureNames.size(); i++) {
			if (info.captureNames.get(i) == null) return true;
		}
		return false;
	}

	static List<String> findCapturedLambdasWithoutLvt(LongObjectMap<List<SyntheticInfo>> groups, String owner) {
		List<String> list = new ArrayList<>();
		if (groups == null) return list;
		for (int idx : LambdaAligner.groupOrder(groups)) {
			List<SyntheticInfo> g = groups.valueAt(idx);
			if (g == null) continue;
			for (SyntheticInfo ni : g) {
				if (hasCapturedLambdaWithoutLvt(ni, owner)) {
					list.add(ni.name + ni.desc);
				}
			}
		}
		return list;
	}

	/**
	 * 把 indy 调用点上的 lambda 形状（捕获表 / 功能接口 / SAM 签名）记到目标合成方法上。
	 *
	 * <p><b>必须再比一次描述符</b>：{@code nameIndex} 是按名字索引的，同名不同描述符的重载
	 * （JDK 8 的 {@code lambda$null$0(II)} 与 {@code lambda$null$0(I)}）会互相覆盖。
	 * 不比的话会把 A 的捕获表贴到 B 上，桥接的参数映射会直接错位——那是静默错值。</p>
	 *
	 * <p>只取<b>先见到的</b>那个调用点：同一个合成方法被多处 indy 引用时，各处的捕获表
	 * 必然一致（它就是方法签名的一部分），不一致只可能来自索引错位，此时保留第一份。</p>
	 */
	static void recordLambdaShape(SyntheticInfo info, InvokeDynamicInsnNode indy, Handle impl,
	                                      MethodNode host, Map<String, String[]> paramNames,
	                                      String owner) {
		if (info == null || info.captured != null) return;
		if (!info.desc.equals(impl.getDesc())) return;
		if (indy.bsmArgs.length < 3) return;
		if (!(indy.bsmArgs[0] instanceof Type sam) || !(indy.bsmArgs[2] instanceof Type inst)) return;
		Type[] caps = Type.getArgumentTypes(indy.desc);
		List<String> captured = new ArrayList<>(caps.length);
		for (Type t : caps) captured.add(t.getDescriptor());
		info.captured = captured;
		info.fiType = Type.getReturnType(indy.desc).getInternalName();
		info.samSig = sam.getDescriptor();
		info.instSig = inst.getDescriptor();
		info.captureSlots = captureSlotsOf(indy, caps);
		info.captureNames = captureNamesOf(paramNames.get(info.name + info.desc), captured,
			info.isStatic(), owner, info.captureSlots,
			host != null && (host.access & Opcodes.ACC_STATIC) != 0);
		info.hostParamEndSlot = paramEndSlot(host);
		info.hostMethod = host;
	}

	/** 宿主方法形参槽的结束位置（第一个局部变量所在的槽）。 */
	private static int paramEndSlot(MethodNode host) {
		int s = (host.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (Type t : Type.getArgumentTypes(host.desc)) s += t.getSize();
		return s;
	}

	/**
	 * 回溯每个捕获实参来自宿主方法的哪个局部变量槽。
	 *
	 * <p>javac 在 indy 之前按捕获顺序把实参压栈，所以从 indy 往前数 {@code captured.length}
	 * 条真实指令就是它们的来源。只认<b>直接的局部变量加载</b>：实参若是临时值
	 * （{@code () -> use(make(a))} 会先算出一个临时槽再捕获），槽号没有跨版本的身份，
	 * 回溯一律判失败——宁可退回类型推断，也不能拿一个随机槽号去"证明"映射。</p>
	 *
	 * @return 与 {@code captured} 对齐的槽号；无法回溯时 {@code null}
	 */
	private static List<Integer> captureSlotsOf(InvokeDynamicInsnNode indy, Type[] captured) {
		int n = captured.length;
		if (n == 0) return List.of();
		int[] slots = new int[n];
		AbstractInsnNode cur = indy.getPrevious();
		for (int k = n - 1; k >= 0; k--) {
			while (cur != null && cur.getOpcode() < 0) cur = cur.getPrevious();   // 跳过 Label/LineNumber
			if (!(cur instanceof VarInsnNode v)) return null;
			if (!loadMatches(v.getOpcode(), captured[k])) return null;
			slots[k] = v.var;
			cur = cur.getPrevious();
		}
		List<Integer> out = new ArrayList<>(n);
		for (int s : slots) out.add(s);
		return out;
	}

	/** 加载指令与类型是否匹配（用于校验回溯没有错位）。 */
	private static boolean loadMatches(int op, Type t) {
		switch (t.getSort()) {
			case Type.BOOLEAN: case Type.CHAR: case Type.BYTE: case Type.SHORT: case Type.INT:
				return op == Opcodes.ILOAD;
			case Type.LONG:   return op == Opcodes.LLOAD;
			case Type.FLOAT:  return op == Opcodes.FLOAD;
			case Type.DOUBLE: return op == Opcodes.DLOAD;
			case Type.OBJECT: case Type.ARRAY: return op == Opcodes.ALOAD;
			default: return false;
		}
	}

	/**
	 * 收集逻辑相关的 Label：只包含被跳转指令、switch、异常表引用的 Label。
	 *
	 * <p>排除纯调试标签（行号锚点、局部变量作用域边界），这些标签在不同
	 * {@code -g} 设置下数量与位置都会变，不应参与指纹。</p>
	 *
	 * <p>返回的 {@link Label} 实例与 {@link MethodNode#accept(MethodVisitor)}
	 * 回放时传入的是同一对象，可直接用作集合元素。</p>
	 */
	static Set<Label> collectValidLabels(MethodNode mn) {
		Set<Label> valid = new HashSet<>();
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof JumpInsnNode j) {
				valid.add(j.label.getLabel());
			} else if (insn instanceof TableSwitchInsnNode t) {
				valid.add(t.dflt.getLabel());
				for (LabelNode l : t.labels) valid.add(l.getLabel());
			} else if (insn instanceof LookupSwitchInsnNode l) {
				valid.add(l.dflt.getLabel());
				for (LabelNode ln : l.labels) valid.add(ln.getLabel());
			}
		}
		if (mn.tryCatchBlocks != null) {
			for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
				valid.add(tcb.start.getLabel());
				valid.add(tcb.end.getLabel());
				valid.add(tcb.handler.getLabel());
			}
		}
		return valid;
	}


	/**
	 * 统计类中所有通过 {@code invokedynamic} 引导参数直接引用的方法句柄目标出现频次。
	 * 键格式形如：{@code "com/example/Foo.save()V"}（不含 Handle tag，兼容跨 JDK 版本与私有调用模式差异）。
	 */
	static Map<String, Integer> countHandleTargets(ClassNode cn) {
		Map<String, Integer> counts = new HashMap<>();
		if (cn == null || cn.methods == null) return counts;
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode i : mn.instructions) {
				if (i instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (Object a : indy.bsmArgs) {
						if (a instanceof Handle h) {
							String key = h.getOwner() + "." + h.getName() + h.getDesc();
							counts.put(key, counts.getOrDefault(key, 0) + 1);
						}
					}
				}
			}
		}
		return counts;
	}
}
