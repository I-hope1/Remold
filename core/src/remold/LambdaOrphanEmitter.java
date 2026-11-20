package remold;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import remold.LambdaAligner.AlignmentEvent;
import remold.LambdaAligner.MatchContext;
import remold.LambdaAligner.OrphanPolicy;
import remold.LambdaAligner.SyntheticInfo;
import remold.util.Diag;
import remold.util.LongObjectMap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 孤儿 Lambda 复活、保留旧方法体注入与运行期熔断分流助手（从 {@link LambdaAligner} 拆出）。
 */
final class LambdaOrphanEmitter {

	private LambdaOrphanEmitter() {}

	private static volatile OrphanPolicy orphanPolicy = OrphanPolicy.SMART_ADAPTIVE;

	static void setOrphanPolicy(OrphanPolicy policy) {
		orphanPolicy = Objects.requireNonNull(policy);
	}

	static OrphanPolicy getOrphanPolicy() {
		return orphanPolicy;
	}

	private static volatile boolean retainOrphanBody = initRetainFlag("remold.agent.orphan_retain_body", true);
	private static volatile boolean retainOrphanBodyVoid = initRetainFlag("remold.agent.orphan_retain_void", false);

	private static boolean initRetainFlag(String property, boolean fallback) {
		String p = System.getProperty(property);
		if (p == null) {
			p = System.getenv(property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT));
		}
		if (p == null) return fallback;
		p = p.trim();
		return p.isEmpty() ? fallback : Boolean.parseBoolean(p);
	}

	static void setOrphanBodyRetention(boolean nonVoidEnabled, boolean voidEnabled) {
		retainOrphanBody = nonVoidEnabled;
		retainOrphanBodyVoid = voidEnabled;
	}

	static boolean isOrphanBodyRetainedForNonVoid() {
		return retainOrphanBody;
	}

	static boolean isOrphanBodyRetainedForVoid() {
		return retainOrphanBodyVoid;
	}

	static void clearLoggedOrphans() {
		LOGGED_ORPHANS.clear();
		LOGGED_FORWARDS.clear();
		LOGGED_RETAINED.clear();
	}

	static int getLoggedOrphanCount() {
		return LOGGED_ORPHANS.size();
	}

	static int getLoggedForwardCount() {
		return LOGGED_FORWARDS.size();
	}

	static boolean isOrphanLogged(String location) {
		return LOGGED_ORPHANS.contains(location);
	}

	static boolean isForwardLogged(String location) {
		return LOGGED_FORWARDS.contains(location);
	}

	/** 纯委托调用信息：包含被调用的目标方法节点、转发所需的主体指令序列以及上一轮是否已是转发桩。 */
	record Delegate(MethodInsnNode target, List<AbstractInsnNode> body, boolean wasForwarding) {}

	/** 包装类内部名集合（用于装箱/拆箱适配识别）。兼容 Java 8 编译。 */
	private static final Set<String> WRAPPERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte",
		"java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double")));

	private static boolean isPrimitive(Type t) {
		return t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE;
	}

	private static boolean isBoxing(MethodInsnNode m) {
		if (m.getOpcode() != Opcodes.INVOKESTATIC || !m.name.equals("valueOf")
		    || !WRAPPERS.contains(m.owner)) return false;
		Type[] args = Type.getArgumentTypes(m.desc);
		Type ret = Type.getReturnType(m.desc);
		return args.length == 1 && isPrimitive(args[0])
		       && ret.getSort() == Type.OBJECT && m.owner.equals(ret.getInternalName());
	}

	private static boolean isUnboxing(MethodInsnNode m) {
		return m.getOpcode() == Opcodes.INVOKEVIRTUAL && WRAPPERS.contains(m.owner)
		       && m.name.endsWith("Value") && m.desc.startsWith("()")
		       && isPrimitive(Type.getReturnType(m.desc));
	}

	private static boolean isWidening(int op) {
		return op == Opcodes.I2L || op == Opcodes.I2F || op == Opcodes.I2D
		       || op == Opcodes.L2F || op == Opcodes.L2D || op == Opcodes.F2D;
	}

	/**
	 * 判定方法节点是否为严格的“纯委托或标准适配委托”实现。
	 *
	 * <p>支持由 LambdaMetafactory 自动完成的无分支标准适配：装箱、拆箱、基本类型加宽、丢弃返回值 (POP/POP2)、CHECKCAST。</p>
	 * <p>若上一轮已为此方法注入过幽灵桩（前缀为 {@code LDC loc; ICONST_0/1; INVOKESTATIC onOrphanInvoked}），
	 * 会自动跳过该固定前缀，并仅提取后续的委托主体指令，避免多轮热更时前缀重复调用或退化为空壳。</p>
	 */
	static Delegate getPureDelegate(MethodNode mn) {
		if (mn.instructions == null || mn.instructions.size() == 0) return null;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) {
			if (i.getOpcode() >= 0) real.add(i);
		}

		// 先剥掉"生前指纹"标记：LDC <long>; POP2。它是 injectDummyBody 加的，不是桩体语义的一部分。
		// 不剥掉的话，下面的转发前缀检测会把它当成 LDC loc，整段判定失效（转发桩退化成空壳）。
		if (real.size() >= 2
		    && real.get(0) instanceof LdcInsnNode marker
		    && marker.cst instanceof Long
		    && real.get(1).getOpcode() == Opcodes.POP2) {
			real = new ArrayList<>(real.subList(2, real.size()));
		}

		// 跳过上一轮转发桩的固定前缀：LDC loc; ICONST_0/1; INVOKESTATIC onOrphanInvoked
		int start = 0;
		boolean wasForwarding = false;
		if (real.size() >= 3
		    && real.get(0) instanceof LdcInsnNode
		    && (real.get(1).getOpcode() == Opcodes.ICONST_0 || real.get(1).getOpcode() == Opcodes.ICONST_1)
		    && real.get(2) instanceof MethodInsnNode p
		    && p.owner.equals(Type.getInternalName(LambdaAligner.class))
		    && p.name.equals("onOrphanInvoked")) {
			start = 3;
			wasForwarding = (real.get(1).getOpcode() == Opcodes.ICONST_1);
		} else if (real.size() >= 2
		           && real.get(0) instanceof LdcInsnNode
		           && real.get(1) instanceof MethodInsnNode p
		           && p.owner.equals(Type.getInternalName(LambdaAligner.class))
		           && p.name.equals("onOrphanInvoked")) {
			start = 2;
		}
		List<AbstractInsnNode> body = real.subList(start, real.size());

		boolean voidRet = Type.getReturnType(mn.desc).getSort() == Type.VOID;
		int expectSlot = 0;
		int endSlot = Type.getArgumentsAndReturnSizes(mn.desc) >> 2;   // 参数槽总数（含 this 的 +1）
		if ((mn.access & Opcodes.ACC_STATIC) != 0) endSlot -= 1;

		MethodInsnNode core = null, onlyInvoke = null;
		int invokes = 0;

		for (AbstractInsnNode insn : body) {
			int op = insn.getOpcode();
			if (insn instanceof VarInsnNode v && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
				if (v.var != expectSlot) return null;                    // 必须按槽位顺序加载
				expectSlot += (op == Opcodes.LLOAD || op == Opcodes.DLOAD) ? 2 : 1;
			} else if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
				// ok
			} else if (isWidening(op) || (insn instanceof TypeInsnNode && op == Opcodes.CHECKCAST)) {
				// ok
			} else if ((op == Opcodes.POP || op == Opcodes.POP2) && voidRet) {
				// ok：Runnable r = this::a 丢弃返回值
			} else if (insn instanceof MethodInsnNode m) {
				invokes++;
				onlyInvoke = m;
				if (!isBoxing(m) && !isUnboxing(m)) {
					if (core != null) return null;
					core = m;
				}
			} else {
				return null;
			}
		}
		if (core == null && invokes == 1) core = onlyInvoke;             // 如 IntFunction<Integer> ← Integer::valueOf
		if (core == null || expectSlot != endSlot) return null;          // 每个形参恰好加载一次
		return new Delegate(core, body, wasForwarding);
	}

	/**
	 * 将被删除的 lambda 以“空方法体或透明转发桩”的形式复活注入到新字节码中，
	 * 防止被缓存的 {@code CallSite} 抛出 {@link NoSuchMethodError}。
	 *
	 * <p>复用调用方已经解析好的 {@code oldCn} 提取孤儿方法的签名头，
	 * 避免再次读取 {@code oldBytes}。</p>
	 *
	 * <p>{@code oldCn} 是用 {@code SKIP_DEBUG | SKIP_FRAMES} 读入的，
	 * 但注入空壳只用到 {@code access} / {@code name} / {@code desc} /
	 * {@code signature} / {@code exceptions} 这些方法头字段——它们不受
	 * {@code SKIP_*} 影响。方法体（Code 属性）由 {@link #injectDummyBody}
	 * 重新生成，不会拷贝 {@code oldCn} 中的原始指令。</p>
	 * @param oldCn         已解析的旧类节点（由 {@code align} 中的 {@code scan} 提供；{@code SKIP_FRAMES}）
	 * @param oldBytes      上一轮实际生效的字节码。仅在"保留旧方法体"候选存在时用
	 *                      {@code EXPAND_FRAMES} 重新解析一次，取方法体（含 StackMapTable）。
	 * @param newBytes      经过重命名处理后的新版本字节码
	 * @param presentKeys   新版本里实际存在的所有方法键（{@code name + desc}）
	 * @param oldMethodRefs 旧版本中所有 indy 句柄引用的目标方法出现频次
	 * @param newMethodRefs 新版本中所有 indy 句柄引用的目标方法出现频次
	 * @param ctx           当前匹配上下文
	 * @return 注入幽灵方法后的最终字节码；若无孤儿则原样返回 {@code newBytes}
	 */
	static byte[] resurrectOrphanedLambdas(ClassNode oldCn, byte[] oldBytes, byte[] newBytes,
	                                               Set<String> presentKeys,
	                                               Map<String, Integer> oldMethodRefs,
	                                               Map<String, Integer> newMethodRefs,
	                                               MatchContext ctx) {
		// 1. 旧类里的合成方法键集合，差集就是孤儿
		Set<String> orphanedKeys = new HashSet<>();
		var         oldGroups    = ctx.oldGroups;
		if (ctx.runR) {
			// 孤儿判定基准是**生效侧**方法表：幽灵桩 / 保留体 / 桥只存在于 E_prev，R_prev 里没有它们。
			// 判据与 scan 的 renameable 逐字对齐（lambda 系名字 && 非 access$），否则普通合成方法
			// （$default / $annotations 之类）会被误幽灵化。
			for (MethodNode mn : oldCn.methods) {
				if (mn.name.startsWith("<") || mn.name.startsWith("access$")) continue;
				if (!MethodFingerprinter.isSyntheticName(mn.name)) continue;
				String key = mn.name + mn.desc;
				if (!presentKeys.contains(key)) orphanedKeys.add(key);
			}
		} else {
			for (int idx = oldGroups.nextEntry(-1); idx != -1; idx = oldGroups.nextEntry(idx)) {
				List<SyntheticInfo> oldGroup = oldGroups.valueAt(idx);
				if (oldGroup == null) continue;
				for (SyntheticInfo oi : oldGroup) {
					if (!oi.renameable) continue; // 关键修复：只对真正可改名的 Lambda 系方法幽灵化，普通合成方法（如 access$/$default）让其自然缺失
					String key = oi.name + oi.desc;
					if (!presentKeys.contains(key)) orphanedKeys.add(key);
				}
			}
		}
		if (orphanedKeys.isEmpty()) return newBytes;

		// 2. 从已解析的 oldCn 中取遗弃方法的签名头（不再二次读 oldBytes）
		List<MethodNode> toInject = new ArrayList<>();
		for (MethodNode mn : oldCn.methods) {
			String key = mn.name + mn.desc;
			if (!orphanedKeys.contains(key)) continue;
			toInject.add(mn);
		}
		if (toInject.isEmpty()) return newBytes;

		// 可观测性：幽灵化是"静默"发生在对齐期的。把这行与
		// warnPositionalMismatch 放在一起看，就能认出"删除 + 改体同时发生"这一形态
		StringBuilder ghosted = new StringBuilder();
		int bridgeCount = 0;
		for (MethodNode mn : toInject) {
			if (ctx.bridges.containsKey(mn.name + mn.desc)) {
				bridgeCount++;
				continue;
			}
			if (ghosted.length() > 0) ghosted.append(", ");
			ghosted.append(mn.name);
		}
		Diag.info("[LambdaAligner] " + oldCn.name + " ghosted " + (toInject.size() - bridgeCount)
		          + " method(s) (old CallSites will fuse/degrade): " + ghosted
		          + (bridgeCount > 0 ? "; created " + bridgeCount + " capture bridge(s) (forwarding to new methods)" : ""));

		// 3. 以空壳或转发桩形式追加到新类末尾
		ClassReader cr = new ClassReader(newBytes);
		// 关键：不用 COMPUTE_FRAMES，避免 getCommonSuperClass 触发目标类加载
		ClassWriter  cw        = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		final String className = cr.getClassName();

		// 生前指纹必须按 name+desc 精确取：幽灵可能**同名不同描述符**
		// （JDK 8 的 lambda$null$0(II) 与 lambda$null$0(I) 就是这样），
		// 而 infoByName 只按名字索引，取到哪一个取决于索引写入顺序 —— 会让标记值在轮次间抖动，
		// 破坏"第二轮产物与第一轮逐字节相同"这类不变量。
		final Map<String, Long> ghostHashByKey = new HashMap<>();
		if (ctx.runR) {
			// Run R：幽灵集合由继承集定义，生前指纹以元数据为准（规格 §5：Meta 校正）。
			if (ctx.oldMeta != null) {
				for (Map.Entry<String, Long> en : ctx.oldMeta.ghostHashes.entrySet()) {
					ghostHashByKey.put(en.getKey(), en.getValue() == null ? 0L : en.getValue());
				}
			}
			// 元数据缺项时退回桩体标记（逐字与 Run E 同一口径）。
			for (MethodNode mn : oldCn.methods) {
				if (!isGhostMethod(mn)) continue;
				ghostHashByKey.putIfAbsent(mn.name + mn.desc, readGhostOriginalHash(mn));
			}
		}
		// 键必须与查表口径一致（查的是 toInject 的 Eff 键）：
		// Run E 下 oldGroups 就是 E_prev，oi.name 即 Eff 名；Run R 下 oldGroups 是 R_prev，
		// oi.name 只是 javac 槽位身份。这里若直接写 oi.name + oi.desc，被避障改名的 lambda
		// 会拿 Raw 键去撞 Eff 键：轻则取不到（原标记 0L），重则撞上"Raw 名恰好等于该 Eff 名"
		// 的同签名兄弟，把兄弟的生前指纹写进幽灵桩，下一次 stepGhostRevive 认不回原主。
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			for (SyntheticInfo oi : g) {
				String key = ctx.effKeyFor(oi);
				if (ctx.runR && ghostHashByKey.containsKey(key)) continue;   // 生效侧名字优先
				ghostHashByKey.put(key, oi.ghost ? oi.ghostOriginalHash : oi.hash);
			}
		}

		// 2.5 保留旧方法体（docs/lambda/02-orphan-body-retention.md）：
		//     非 void 孤儿默认把上一轮的真实方法体（含 StackMapTable）搬进幽灵方法，
		//     避免调用方拿到 null/0 后 NPE / 除零 / 数组越界。只在需要时用 EXPAND_FRAMES
		//     重新解析一次旧字节码（oldCn 是 SKIP_FRAMES 的），并解析新类结构做成员存活判定。
		boolean retentionOn = retainOrphanBody || retainOrphanBodyVoid;
		Map<String, MethodNode> oldBodies = Collections.emptyMap();
		ClassNode  oldBodyCn  = null;
		ClassNode  newStruct  = null;
		Set<String> plannedKeys = new HashSet<>();
		for (MethodNode mn : toInject) plannedKeys.add(mn.name + mn.desc);
		if (retentionOn) {
			try {
				oldBodyCn = readWithFrames(oldBytes);
				newStruct = readStructure(newBytes);
				Map<String, MethodNode> bodies = new HashMap<>();
				for (MethodNode m : oldBodyCn.methods) bodies.put(m.name + m.desc, m);
				oldBodies = bodies;
			} catch (Throwable t) {
				Diag.warn("[LambdaAligner] " + oldCn.name + ": cannot read the previous method bodies ("
				                 + t + "); orphan lambdas fall back to default-value stubs");
				retentionOn = false;
				oldBodies = Collections.emptyMap();
			}
		}
		final boolean                 retentionActive    = retentionOn;
		final Map<String, MethodNode> prevBodies         = oldBodies;
		final ClassNode               prevClassNode      = oldBodyCn;
		final ClassNode               nextClassNode      = newStruct;
		final int[]                   retainedBodies     = {0};
		final List<String>            retentionFallbacks = new ArrayList<>();

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public void visitEnd() {
				for (MethodNode mn : toInject) {
					// 空壳方法必须带 Code 属性，强制清掉 abstract / native
					int access = mn.access & ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
					MethodVisitor dummy = super.visitMethod(
					 access, mn.name, mn.desc, mn.signature,
					 mn.exceptions == null ? null : mn.exceptions.toArray(new String[0]));
					if (dummy != null) {
						String     key     = mn.name + mn.desc;
						MethodNode oldBody = prevBodies.get(key);
						// 上一轮已经是"保留体"幽灵：原样搬运（它的 3 参标记与旧体一起走），
						// 保证多轮产物稳定、旧实例拿到的一直是同一份旧逻辑——
						// 但**必须每轮重做成员存活校验**：上一轮还活着的 helper()/x 可能这一轮被删，
						// 直接搬运会保留调用已删成员的字节码，旧实例一执行就 NoSuchMethodError / NoSuchFieldError。
						if (retentionActive && oldBody != null && hasRetainedBodyMarker(oldBody)) {
							String carriedWhy = OrphanBodyRetention.rejectionReason(
							 prevClassNode, nextClassNode, oldBody, plannedKeys);
							if (carriedWhy == null) {
								injectWholeBody(dummy, access, mn, oldBody);
								retainedBodies[0]++;
								LambdaAligner.TEST_EVENTS.add(new AlignmentEvent("GHOST_KEEP_BODY", mn.name, null,
								 className + "#" + key + " (carried over)"));
								continue;
							}
							retentionFallbacks.add(key + ": carried-over body rejected (" + carriedWhy + ")");
						}
						// 捕获桥优先：这个老键不是"死了"，只是参数表缩水，转发给新方法即可。
						LambdaCaptureBridge.BridgeSpec bridge = ctx.bridges.get(mn.name + mn.desc);
						if (bridge != null) {
							LambdaAligner.TEST_EVENTS.add(new AlignmentEvent("BRIDGE_INJECT", mn.name, bridge.targetName,
							 className + "#" + mn.name + mn.desc));
							LambdaCaptureBridge.injectBridgeBody(dummy, className,
							 (oldCn.access & Opcodes.ACC_INTERFACE) != 0, bridge);
							continue;
						}
						Delegate delegate = getPureDelegate(mn);
						boolean canForward = false;
						if (delegate != null) {
							String targetKey = delegate.target.owner + "." + delegate.target.name + delegate.target.desc;
							int newCount = newMethodRefs.getOrDefault(targetKey, 0);
							int oldCount = oldMethodRefs.getOrDefault(targetKey, 0);
							// 门禁判定：若上一轮已是转发桩，则只要新版本仍有该方法引用即继承转发；
							// 若为首次退役为孤儿，则必须满足新版本对该方法引用的句柄数量净增（newCount > oldCount）。
							canForward = delegate.wasForwarding ? (newCount > 0) : (newCount > oldCount);
						}
						long originalHash = ghostHashByKey.getOrDefault(key, 0L);
						boolean keepPrevious = false;
						if (!canForward && !isGhostMethod(mn)) {
							if (retentionActive && oldBody != null) {
								boolean isVoid = Type.getReturnType(mn.desc).getSort() == Type.VOID;
								boolean want   = isVoid ? retainOrphanBodyVoid : retainOrphanBody;
								if (want) {
									String why = OrphanBodyRetention.rejectionReason(
									 prevClassNode, nextClassNode, oldBody, plannedKeys);
									keepPrevious = why == null;
									if (why != null) {
										retentionFallbacks.add(key + ": " + why);
									} else {
										// 旧实例语义优先：嵌套 lambda 被原位更新时仍保留旧体，
										// 但混跑（旧外层 + 新内层）必须可观测，不能静默。
										String mixed = OrphanBodyRetention.updatedNestedLambda(
										 prevClassNode, nextClassNode, oldBody, plannedKeys);
										if (mixed != null) {
											Diag.warn("[LambdaAligner] " + className + "#" + key
												+ ": retained body calls updated nested lambda " + mixed
												+ "; old instances may mix old outer with new inner semantics");
										}
									}
								} else {
									retentionFallbacks.add(key + ": " + (isVoid
									 ? "void orphan (remold.agent.orphan_retain_void=false)"
									 : "non-void orphan (remold.agent.orphan_retain_body=false)"));
								}
							} else if (retentionActive) {
								retentionFallbacks.add(key + ": the previous body is unavailable");
							}
						}
						if (keepPrevious) {
							injectRetainedBody(dummy, access, mn, className, oldBody, originalHash);
							retainedBodies[0]++;
							// 仍发 GHOST 事件：既有计数/哨兵按"这个老名字被幽灵化"记账；
							// GHOST_KEEP_BODY 是新增的可观测性明细（是否保留旧体）。
							LambdaAligner.TEST_EVENTS.add(new AlignmentEvent("GHOST", mn.name, null, className + "#" + key));
							LambdaAligner.TEST_EVENTS.add(new AlignmentEvent("GHOST_KEEP_BODY", mn.name, null,
							 className + "#" + key + " (previous body retained)"));
						} else {
							LambdaAligner.TEST_EVENTS.add(new AlignmentEvent("GHOST", mn.name, null, className + "#" + key));
							injectDummyBody(dummy, className, mn.name, mn.desc, canForward ? delegate : null,
							 originalHash);
						}
					}
				}
				super.visitEnd();
			}
		};
		cr.accept(cv, 0);
		if (retainedBodies[0] > 0) {
			Diag.info("[LambdaAligner] " + oldCn.name + " kept the previous body for "
			          + retainedBodies[0] + " orphan lambda(s)"
			          + " (existing CallSites keep a legal return value instead of null/0)");
		}
		if (!retentionFallbacks.isEmpty()) {
			Diag.warn("[LambdaAligner] " + oldCn.name + ": " + retentionFallbacks.size()
			          + " orphan lambda(s) fell back to the default-value stub: "
			          + describeFirst(retentionFallbacks, 3));
		}
		return cw.toByteArray();
	}

	/** 只列前 {@code n} 条，其余用 {@code +K more} 交代，保证一行可读。 */
	private static String describeFirst(List<String> items, int n) {
		StringBuilder sb = new StringBuilder();
		int limit = Math.min(n, items.size());
		for (int i = 0; i < limit; i++) {
			if (i > 0) sb.append("; ");
			sb.append(items.get(i));
		}
		if (items.size() > limit) sb.append("; +").append(items.size() - limit).append(" more");
		return sb.toString();
	}

	/** 用 {@code EXPAND_FRAMES} 读入：保留 StackMapTable（{@link FrameNode}）与标签，供方法体搬运。 */
	private static ClassNode readWithFrames(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
		return cn;
	}

	/** 只读结构（成员表/修饰符），不读方法体。 */
	private static ClassNode readStructure(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn,
		 ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return cn;
	}

	private static final String RETAINED_MARKER_DESC = "(Ljava/lang/String;ZZ)V";

	/**
	 * 上一轮注入的"保留体"幽灵：桩体里有 3 参
	 * {@code onOrphanInvoked(String, boolean, boolean)} 调用。
	 * <p>与默认值桩（2 参调用）区分，用于多轮搬运时保持旧体不被重新判定/覆盖。</p>
	 */
	private static boolean hasRetainedBodyMarker(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m
			    && m.owner.equals(Type.getInternalName(LambdaAligner.class))
			    && m.name.equals("onOrphanInvoked")
			    && m.desc.equals(RETAINED_MARKER_DESC)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 注入"保留上一轮方法体"的幽灵：前缀（指纹标记 + 3 参 onOrphanInvoked）+ 旧方法体（含帧）。
	 * <p>前缀不改变局部变量与栈，因此旧 StackMapTable 仍然有效；写入侧仍是 {@code COMPUTE_MAXS}。</p>
	 */
	private static void injectRetainedBody(MethodVisitor mv, int access, MethodNode header,
	                                       String className, MethodNode srcBody, long originalHash) {
		MethodNode out = new MethodNode(Opcodes.ASM9, access, header.name, header.desc,
		 header.signature, header.exceptions == null ? null : header.exceptions.toArray(new String[0]));
		if (originalHash != 0L) {
			out.instructions.add(new LdcInsnNode(originalHash));
			out.instructions.add(new InsnNode(Opcodes.POP2));
		}
		out.instructions.add(new LdcInsnNode(locationOf(className, header.name, header.desc)));
		out.instructions.add(new InsnNode(Opcodes.ICONST_0));   // willForward = false
		out.instructions.add(new InsnNode(Opcodes.ICONST_1));   // willRunPreviousBody = true
		out.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
		 Type.getInternalName(LambdaAligner.class), "onOrphanInvoked", RETAINED_MARKER_DESC, false));
		copyBody(srcBody, out);
		out.accept(mv);
	}

	/** 原样搬运上一轮的幽灵方法体（保留体或旧格式桩），不改一个字节。 */
	private static void injectWholeBody(MethodVisitor mv, int access, MethodNode header, MethodNode srcBody) {
		MethodNode out = new MethodNode(Opcodes.ASM9, access, header.name, header.desc,
		 header.signature, header.exceptions == null ? null : header.exceptions.toArray(new String[0]));
		copyBody(srcBody, out);
		out.accept(mv);
	}

	/**
	 * 搬运方法体：指令、标签、StackMapTable 帧、异常表一起克隆。
	 * <p>标签必须重建并映射，否则帧/异常表会指向旧 MethodNode 的标签对象，写入器算出的偏移会错。</p>
	 */
	private static void copyBody(MethodNode src, MethodNode dst) {
		Map<LabelNode, LabelNode> labels = new HashMap<>();
		for (AbstractInsnNode n : src.instructions) {
			if (n instanceof LabelNode l) labels.put(l, new LabelNode());
		}
		if (src.tryCatchBlocks != null) {
			for (TryCatchBlockNode t : src.tryCatchBlocks) {
				labels.computeIfAbsent(t.start, k -> new LabelNode());
				labels.computeIfAbsent(t.end, k -> new LabelNode());
				labels.computeIfAbsent(t.handler, k -> new LabelNode());
			}
		}
		for (AbstractInsnNode n : src.instructions) {
			dst.instructions.add(n instanceof LabelNode l ? labels.get(l) : n.clone(labels));
		}
		if (src.tryCatchBlocks != null) {
			for (TryCatchBlockNode t : src.tryCatchBlocks) {
				dst.tryCatchBlocks.add(new TryCatchBlockNode(
				 labels.get(t.start), labels.get(t.end), labels.get(t.handler), t.type));
			}
		}
	}

	/**
	 * 生成空壳 Lambda 方法体或方法引用转发桩的指令流。
	 * <p>
	 * <b>关键设计（默认值桩 100% 线性无跳转分支，彻底杜绝 VerifyError）</b>：<br>
	 * <p>注意：{@link #injectRetainedBody} 走的是另一条路径——它会搬运旧方法体（含
	 * {@code StackMapTable} 帧与异常表），因此不再是"无分支"形态；见
	 * {@code docs/lambda/02-orphan-body-retention.md} §3.1。</p>
	 * 字节码中完全不使用任何 {@code IFEQ} / {@code GOTO} 等分支跳转指令和 {@link Label}。
	 * 所有的策略判定、孤儿熔断栈探测、异常抛出与日志去重均下沉到纯 Java 静态方法
	 * {@link #onOrphanInvoked(String, boolean)} 中执行。<br>
	 * <ul>
	 *   <li>若为熔断器保护的调用：Java 静态方法直接抛出 {@link NoSuchMethodError} 展开栈中断执行；</li>
	 *   <li>若为普通业务调用：若命中方法引用委托则透明转发；若非委托则线性返回类型默认值（0 / null / false）。</li>
	 * </ul>
	 * 从而在各版本 JVM 下无需 StackMapTable 即可 100% 通过类验证。
	 * </p>
	 */
	private static void injectDummyBody(MethodVisitor mv, String className, String name, String desc,
	                                    Delegate delegate, long originalHash) {
		mv.visitCode();
		boolean willForward = delegate != null;

		// 0. 生前指纹标记：LDC <long>; POP2。
		//
		// 桩体一注入，这个方法的指纹就变成空壳的指纹，再也认不出"原封不动加回来"的同一个
		// lambda。把生前指纹钉在桩体里（跟着字节码走，不依赖任何外部内存），下一轮扫描时
		// 由 readGhostOriginalHash 读回来，供 stepGhostRevive 认领。
		// 放在最前面且紧跟 POP2，保证不影响后续指令的栈平衡；getPureDelegate 的转发前缀
		// 检测会跳过这 2 条指令。
		if (originalHash != 0L) {
			mv.visitLdcInsn(originalHash);
			mv.visitInsn(Opcodes.POP2);
		}

		// 1. 调用纯 Java 静态方法：统一处理策略分发、熔断栈探测及日志去重。
		//
		// 传入拼好的 location 常量与 willForward 标志（单个 LDC + ICONST）：
		// 运行时热路径上不分配任何 String，集合查找是 O(1) 哈希查找。
		mv.visitLdcInsn(locationOf(className, name, desc));
		mv.visitInsn(willForward ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC,
		 Type.getInternalName(LambdaAligner.class),
		 "onOrphanInvoked", "(Ljava/lang/String;Z)V", false);

		// 2. 若可透明转发，则回放委托方法体指令流；否则 100% 线性无分支返回类型默认值
		if (willForward) {
			for (AbstractInsnNode insn : delegate.body) {
				insn.accept(mv);
			}
		} else {
			injectDefaultReturnValue(mv, Type.getReturnType(desc), desc);
		}
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** location 文本：{@code com.example.Foo#lambda$build$0()V}。注入期算一次，写进字节码常量。 */
	static String locationOf(String className, String name, String desc) {
		if (className == null || className.isEmpty()) return name + desc;
		return className.replace('/', '.') + '#' + name + desc;
	}

	private static void injectDefaultReturnValue(MethodVisitor mv, Type returnType, String desc) {
		switch (returnType.getSort()) {
			case Type.VOID:
				mv.visitInsn(Opcodes.RETURN);
				break;
			case Type.BOOLEAN:
			case Type.CHAR:
			case Type.BYTE:
			case Type.SHORT:
			case Type.INT:
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitInsn(Opcodes.IRETURN);
				break;
			case Type.FLOAT:
				mv.visitInsn(Opcodes.FCONST_0);
				mv.visitInsn(Opcodes.FRETURN);
				break;
			case Type.LONG:
				mv.visitInsn(Opcodes.LCONST_0);
				mv.visitInsn(Opcodes.LRETURN);
				break;
			case Type.DOUBLE:
				mv.visitInsn(Opcodes.DCONST_0);
				mv.visitInsn(Opcodes.DRETURN);
				break;
			case Type.OBJECT:
			case Type.ARRAY:
				mv.visitInsn(Opcodes.ACONST_NULL);
				mv.visitInsn(Opcodes.ARETURN);
				break;
			default:
				throw new IllegalStateException("Unhandled return type parsing: " + desc);
		}
	}

	private static final List<OrphanBreakerPredicate>    ORPHAN_BREAKERS       = new CopyOnWriteArrayList<>();
	private static final List<TransparentFramePredicate> TRANSPARENT_PREDICATES = new CopyOnWriteArrayList<>();

	static void addOrphanBreaker(OrphanBreakerPredicate predicate) {
		if (predicate != null) ORPHAN_BREAKERS.add(predicate);
	}

	static void removeOrphanBreaker(OrphanBreakerPredicate predicate) {
		ORPHAN_BREAKERS.remove(predicate);
	}

	static void addTransparentFramePredicate(TransparentFramePredicate predicate) {
		if (predicate != null) TRANSPARENT_PREDICATES.add(predicate);
	}

	static void removeTransparentFramePredicate(TransparentFramePredicate predicate) {
		TRANSPARENT_PREDICATES.remove(predicate);
	}

	/**
	 * 已打印过日志的 location（并发安全）。
	 */
	private static final Set<String> LOGGED_ORPHANS =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static final Set<String> LOGGED_FORWARDS =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** 保留旧方法体的幽灵：首次被旧实例调用时告警一次（避免每帧刷屏）。 */
	private static final Set<String> LOGGED_RETAINED =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** 日志去重。{@code add} 返回 true 表示本次是首次，才打印。 */
	private static void logOrphanOnce(String location) {
		if (LOGGED_ORPHANS.add(location)) {
			Diag.warn("[LambdaAligner] orphaned lambda invoked: " + location
			          + " (subsequent invocations will be muted)");
		}
	}

	private static void logForwardOnce(String location) {
		if (LOGGED_FORWARDS.add(location)) {
			Diag.info("[LambdaAligner] orphaned lambda forwarded to method ref: " + location
			          + " (ghost, not reclaimed; subsequent invocations will be muted)");
		}
	}

	/**
	 * 保留旧方法体的幽灵首次被调用：按 location 去重告警。
	 *
	 * <p>为什么在这里而不是对齐期：对齐期不知道有没有存活实例在调它，给每个孤儿都发 WARN
	 * 只会制造噪音；运行期第一次真正走到这里时告警，既说明"哪个类哪个 lambda 在跑旧逻辑"，
	 * 又对没有存活实例的死孤儿保持安静。</p>
	 */
	private static void logRetainedOnce(String location) {
		if (LOGGED_RETAINED.add(location)) {
			Diag.warn("[LambdaAligner] orphaned lambda executed on existing instances: " + location
			                  + " - running the previous method body instead of returning a default value;"
			                  + " recreate the holder instance or restart to apply the new logic"
			                  + " (subsequent invocations will be muted)");
		}
	}

	/**
	 * 当热重载中被删除/孤立的空壳 Lambda 方法被调用时由生成的字节码调用。
	 * @param location 形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 */
	static void onOrphanInvoked(String location) {
		onOrphanInvoked(location, false, false);
	}

	/**
	 * 当热重载中被删除/孤立的 Lambda 方法被调用时由生成的字节码调用。
	 * @param location    形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 * @param willForward 是否将继续执行转发到方法引用的逻辑
	 */
	static void onOrphanInvoked(String location, boolean willForward) {
		onOrphanInvoked(location, willForward, false);
	}

	/**
	 * 当热重载中被删除/孤立的 Lambda 方法被调用时由生成的字节码调用
	 * （保留旧方法体的幽灵走这个三参入口）。
	 *
	 * @param location            形如 {@code com.example.Foo#lambda$build$0()V} 的常量
	 * @param willForward         是否将继续执行转发到方法引用的逻辑
	 * @param willRunPreviousBody 桩体随后会执行<b>上一轮的真实方法体</b>（保留体），
	 *                            而不是返回类型默认值 {@code 0/null/false}
	 */
	static void onOrphanInvoked(String location, boolean willForward, boolean willRunPreviousBody) {
		OrphanPolicy policy = orphanPolicy;

		if (policy == OrphanPolicy.SMART_ADAPTIVE) {
			if (isOrphanBreakerTriggered()) {
				throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
			}
			// 普通业务调用：去重后打印日志
			logOrphanDispatch(location, willForward, willRunPreviousBody);
			return;
		}

		if (policy == OrphanPolicy.THROW_NO_SUCH_METHOD) {
			throw new NoSuchMethodError("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.THROW) {
			throw new IllegalStateException("Lambda removed by hot swap: " + location);
		}

		if (policy == OrphanPolicy.LOG_AND_RETURN_DEFAULT) {
			logOrphanDispatch(location, willForward, willRunPreviousBody);
			return;
		}

		// OrphanPolicy.SILENT: 静默返回
	}

	private static void logOrphanDispatch(String location, boolean willForward, boolean willRunPreviousBody) {
		if (willForward) {
			logForwardOnce(location);
		} else if (willRunPreviousBody) {
			logRetainedOnce(location);
		} else {
			logOrphanOnce(location);
		}
	}

	/**
	 * 判断当前调用是否由已注册的孤儿熔断器（{@link OrphanBreakerPredicate}）发起（决定是否熔断）。
	 *
	 * <p><b>判据是"幽灵桩的直接调用者是否匹配注册的熔断器"</b>，而不是"调用栈上某处是否有
	 * 匹配项"：后者会被无关的同栈帧干扰（例如"业务代码 → 框架分发器 → 另一个 lambda"
	 * 这种情形，那个 lambda 其实不是受保护回调）。</p>
	 *
	 * <p><b>为什么不再用 {@code limit(16)} 扫描</b>：那是<b>深度相关</b>的判据 ——
	 * 回调链路上只要夹了较深的用户代码或框架帧（例如嵌套的
	 * {@code Time.run(10, () -> Time.run(5, () -> ...))}），熔断帧就会被截掉，
	 * 于是误判为"非熔断帧"，<b>熔断静默失效</b>。改为"定位幽灵桩入口后取 +2 帧"
	 * 与深度无关：栈有多深都能看到直接调用者。</p>
	 *
	 * <p>降级路径（无 StackWalker 的环境，如 Android/desugar）同样按"定位 {@code onOrphanInvoked}，
	 * 再跳过透明帧取调用者"处理，语义与 StackWalker 路径一致。
	 * 桌面 JVM 上该分支实际不可达（本类最低要求 Java 16+，而 StackWalker 自 Java 9 起可用），
	 * 保留它是为了 Android 等没有 StackWalker 的运行时。</p>
	 * @return 若直接调用者匹配已注册的熔断器则返回 true
	 */
	static boolean isOrphanBreakerTriggered() {
		try {
			if (StackWalkerHolder.IS_SUPPORTED) {
				return StackWalkerHolder.checkBreaker();
			}
		} catch (Throwable ignored) { }

		try {
			StackTraceElement[] t     = new Throwable().getStackTrace();
			int lastOrphan = -1;
			for (int i = 0; i < t.length; i++) {
				if ((SELF_ALIGNER.equals(t[i].getClassName()) || SELF_EMITTER.equals(t[i].getClassName())) && "onOrphanInvoked".equals(t[i].getMethodName())) {
					lastOrphan = i;
				} else if (lastOrphan >= 0) {
					break;
				}
			}
			if (lastOrphan < 0) return false;
			// 与 StackWalker 路径同构：桩之后跳过透明帧，取第一个真实调用者。
			int skip  = lastOrphan + 2;   // 跳过最外层 onOrphanInvoked 自身与桩
			int limit = Math.min(t.length, skip + MAX_TRANSPARENT_FRAMES + 1);
			while (skip < limit && isTransparentFrame(t[skip].getClassName())) skip++;
			if (skip >= t.length) return false;
			return shouldBreakOrphan(t[skip].getClassName(), t[skip].getMethodName());
		} catch (Throwable ignored) { }
		return false;
	}

	/** 类全限定名，用于在栈帧里定位幽灵桩入口。 */
	private static final String SELF_ALIGNER = LambdaAligner.class.getName();
	private static final String SELF_EMITTER = LambdaOrphanEmitter.class.getName();

	/**
	 * 透明帧扫描的深度上限。
	 *
	 * <p>穿透代理/隐藏帧时给一个上界，避免在"整条栈全是透明帧"的病态情况下无界扫描。
	 * 正常回调链路上透明帧屈指可数（代理 1 + handler 1 + invoke 1 + 少量 MethodHandle 帧），
	 * 64 远超实际需要，同时仍与栈的绝对深度无关。</p>
	 */
	private static final int MAX_TRANSPARENT_FRAMES = 64;

	/**
	 * 透明帧：桩与真实调用者之间由 {@code invokedynamic} 生成的代理类帧或注册的包装代理帧。
	 *
	 * <p>必须穿透，否则"固定 +2"在两种环境下会静默失效，而后果正是本机制要消灭的
	 * （熔断不触发）：</p>
	 * <ul>
	 *   <li>开了 {@code -XX:+ShowHiddenFrames} 时，{@code Foo$$Lambda/0x...} 会出现在栈里；</li>
	 *   <li>Android/desugar 之后的 {@code -$$Lambda$} 类不是隐藏类，在
	 *       {@code getStackTrace} 里本来就是可见帧。</li>
	 * </ul>
	 *
	 * <p>此外，UI 框架（如代理层）会用 {@link java.lang.reflect.Proxy}
	 * 把回调参数包成动态代理：幽灵桩与真正的调用者之间会夹着 <b>代理类帧</b>（JDK 9+ 的
	 * {@code jdk.proxyN.$ProxyNN}、JDK 8 的 {@code com.sun.proxy.$ProxyNN}）、
	 * <b>代理 handler 帧</b> 与 <b>方法句柄帧</b>（{@code java.lang.invoke.*}，仅 ShowHiddenFrames 下可见）。
	 * 这些同样通过已注册的 {@link TransparentFramePredicate} 穿透，否则熔断对"被代理的回调"永远不触发。</p>
	 */
	static boolean isTransparentFrame(String className) {
		if (className == null || className.isEmpty()) return false;
		// 刻意只用**类名**（不取 Class 对象），因此无需 RETAIN_CLASS_REFERENCE，
		// 也避免为判断而触发类加载。
		if (className.contains("$$Lambda")
		       || className.contains("$$$Lambda")
		       || className.startsWith("jdk.internal.reflect.")
		       || className.startsWith("jdk.proxy")           // JDK 9+ 动态代理 jdk.proxyN.$ProxyNN
		       || className.startsWith("com.sun.proxy.")      // JDK 8 动态代理
		       || className.startsWith("java.lang.reflect.")  // Proxy 调度
		       || className.startsWith("java.lang.invoke.")) { // MethodHandle 隐藏帧
			return true;
		}
		for (TransparentFramePredicate predicate : TRANSPARENT_PREDICATES) {
			try {
				if (predicate.isTransparent(className)) return true;
			} catch (Throwable ignored) { }
		}
		return false;
	}

	/**
	 * 该帧是否匹配已注册的孤儿熔断器。
	 */
	private static boolean shouldBreakOrphan(String className, String methodName) {
		if (className == null || methodName == null) return false;
		for (OrphanBreakerPredicate breaker : ORPHAN_BREAKERS) {
			try {
				if (breaker.shouldBreak(className, methodName)) return true;
			} catch (Throwable ignored) { }
		}
		return false;
	}

	/**
	 * 延迟初始化 Holder 类，避免在缺少 StackWalker 的低版本环境中触发 ClassNotFoundException / NoClassDefFoundError。
	 */
	private static class StackWalkerHolder {
		static final boolean     IS_SUPPORTED;
		static final StackWalker WALKER;

		static {
			boolean     supported = false;
			StackWalker walker    = null;
			try {
				walker = StackWalker.getInstance();
				supported = true;
			} catch (Throwable ignored) { }
			IS_SUPPORTED = supported;
			WALKER = walker;
		}

		static boolean checkBreaker() {
			if (!IS_SUPPORTED || WALKER == null) return false;
			return Boolean.TRUE.equals(WALKER.walk(s -> s
			 .dropWhile(f -> !isOrphanEntry(f))        // 定位幽灵桩入口
			 .dropWhile(f -> isOrphanEntry(f))         // 跳过所有 onOrphanInvoked 帧（兼顾 1 参和 2 参重载）
			 .skip(1)                                  // 跳过桩自身
			 .limit(MAX_TRANSPARENT_FRAMES + 1)        // 透明帧扫描深度上界
			 .filter(f -> !isTransparentFrame(f.getClassName()))  // 穿透代理/隐藏帧
			 .findFirst()
			 .map(f -> shouldBreakOrphan(f.getClassName(), f.getMethodName()))
			 .orElse(false)));
		}

		/** 该帧是否为幽灵桩入口（LambdaAligner 的 {@code onOrphanInvoked}）。 */
		private static boolean isOrphanEntry(StackWalker.StackFrame f) {
			String cls = f.getClassName();
			return (SELF_ALIGNER.equals(cls) || SELF_EMITTER.equals(cls)) && "onOrphanInvoked".equals(f.getMethodName());
		}
	}
	//endregion

	//region 辅助方法

	/**
	 * 识别"幽灵空壳"方法：上一轮为兜住被删除 lambda 的老 {@code CallSite} 而注入的空方法。
	 *
	 * <p>它调用 {@link LambdaAligner#onOrphanInvoked} 来完成策略分发，所以只凭这一个特征就能认出，
	 * 不需要自定义 attribute。识别出的方法会：
	 * <ul>
	 *   <li><b>保留</b>在 {@link MatchContext#oldGroups} 里 —— 孤儿计算要靠它继续复现注入，
	 *       否则下一轮它就从类里消失了，老 {@code CallSite} 会变成 {@link NoSuchMethodError} 之外的
	 *       更糟形态；</li>
	 *   <li><b>不参与任何匹配</b>（见 {@link SyntheticInfo#ghost}）—— 它的名字是死名字，
	 *       只能被兜住，不能被某个新 lambda "认领"，否则会把本该活着的 lambda 挤到别的名字上。</li>
	 * </ul>
	 *
	 * <p>实测动机（{@code scratch/hstest/del/}）：V1 两个 lambda、V2 删掉第一个（留下幽灵
	 * {@code $0}）、V3 改剩下那个的方法体。修复前 V3 会对齐成
	 * {@code $0=[新体]}、{@code $1=幽灵} —— 活着的 lambda 丢了它自己的名字 {@code $1}，
	 * 持有 {@code $1} 的调用点转而执行新代码。幽灵退出匹配后，它会稳定保持在
	 * {@code $1} 这个名字下继续当空壳。</p>
	 */
	static boolean isGhostMethod(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode mi
			    && mi.owner.equals(Type.getInternalName(LambdaAligner.class))
			    && mi.name.equals("onOrphanInvoked")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 读出幽灵桩体开头的"生前指纹"标记（{@code LDC <long>; POP2}）。
	 *
	 * <p>标记必须放在方法体最前面，且只认前 3 条指令内出现的 long 常量——避免把业务方法里
	 * 偶然出现的 long 常量误当成标记。返回 0 表示没有标记（旧版本注入的桩、或转发桩）。</p>
	 */
	static long readGhostOriginalHash(MethodNode mn) {
		AbstractInsnNode first = mn.instructions.getFirst();
		if (first instanceof LdcInsnNode ldc && ldc.cst instanceof Long v) {
			AbstractInsnNode next = first.getNext();
			// 必须紧跟 POP2：这是本对齐器写标记的固定形态。
			// 旧实现只看"前 3 条指令里有没有 long 常量"，保留旧方法体后方法体自身开头的
			// long 常量（LDC 123L）会被误读成生前指纹，导致 stepGhostRevive 拿错票据。
			if (next != null && next.getOpcode() == Opcodes.POP2) return v;
		}
		return 0L;
	}

	/**
	 * 扫描一份"实际进入 JVM 的字节码"里全部幽灵桩的生前指纹。
	 *
	 * <p>{@code name + desc} → 指纹；无标记的桩记 0。供旁路记账在<b>重定义成功后</b>调用：
	 * 对齐成功不等于 JVM 接受，所以记录不能绑在 {@code align} 里。</p>
	 */
	static Map<String, Long> scanGhostOriginHashes(byte[] effectiveBytes) {
		Map<String, Long> out = new HashMap<>();
		if (effectiveBytes == null || effectiveBytes.length == 0) return out;
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(effectiveBytes).accept(cn, 0);
			for (MethodNode mn : cn.methods) {
				if (isGhostMethod(mn)) out.put(mn.name + mn.desc, readGhostOriginalHash(mn));
			}
		} catch (Throwable ignored) {
			// 解析失败返回空表：读侧会退回桩体标记，不影响对齐。
		}
		return out;
	}
}
