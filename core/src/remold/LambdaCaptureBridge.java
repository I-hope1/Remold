package remold;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import remold.util.LongObjectMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lambda 捕获桥（单向：捕获缩水及可证明纯源新增捕获的兼容转发生成与跨轮认领）。
 */
final class LambdaCaptureBridge {
	private LambdaCaptureBridge() {}

	// ------------------------------------------------------------------------------------------------
	// 捕获桥（单向：只支持"删除捕获变量"）
	// ------------------------------------------------------------------------------------------------

	/**
	 * 桥接规格：老键（名字+描述符）→ 本轮目标方法 + 参数映射。
	 *
	 * <p>桥<b>不参与改名</b>：新方法保留自己的名字，桥占用老键。两者的描述符必然不同
	 * （捕获表缩水了），可以共存。这样老隐藏类常量池里那条 {@code (name, 旧desc)} 引用
	 * 仍然解析得到，而它指向的桥把参数丢给新方法。</p>
	 */
	static final class BridgeSpec {
		final String  oldDesc;
		final String  targetName;
		final String  targetDesc;
		/**
		 * 新捕获表每一位的取值来源，长度恒等于<b>新</b>捕获表长度（下标 {@code j} 对应新捕获 {@code j}）。
		 *
		 * <p>实例桥的接收者（捕获 0）也在里面，虽然它在桥体里是单独 {@code aload_0} 压的。</p>
		 */
		final ParamSource[] sources;
		/**
		 * 桥方法自身是不是静态的（由<b>老</b>方法决定）。
		 *
		 * <p>老隐藏类按老方法的形态调用它：老方法是实例方法，它就得是实例方法、槽 0 是接收者。
		 * 这一位只影响"桥怎么取自己的实参"，与"目标怎么被调"无关。</p>
		 */
		final boolean isStatic;
		/**
		 * <b>目标</b>方法是不是静态的（由<b>新</b>方法决定）。
		 *
		 * <p>与 {@link #isStatic} 必须分开：{@code forceStaticLambdas} 关闭时，"删掉 this 引用"
		 * 会让合成方法从实例变静态，而桥仍得是实例方法（老调用点按 {@code INVOKESPECIAL} 调它）。
		 * 把两者混成一位，就会写出"实例桥去 {@code INVOKESTATIC} 一个要接收者的目标"或
		 * 反之的非法字节码。</p>
		 */
		final boolean targetStatic;
		final String  location;

		BridgeSpec(String oldDesc, String targetName, String targetDesc,
		           ParamSource[] sources, boolean isStatic, boolean targetStatic, String location) {
			this.oldDesc = oldDesc;
			this.targetName = targetName;
			this.targetDesc = targetDesc;
			this.sources = sources;
			this.isStatic = isStatic;
			this.targetStatic = targetStatic;
			this.location = location;
		}
	}

	/**
	 * 桥体里"新捕获表第 j 项"的取值来源。
	 *
	 * <p>只有两种可能：要么从老实例已存下来的捕获里搬（{@link #OLD}），要么在桥里
	 * <b>现场重放</b>一个可证明的纯源（{@link #CONST}/{@link #GETSTATIC}/{@link #GETFIELD}）。
	 * 后者只用于"新增捕获"——老实例里根本没有那个值，而新方法体必然读它。</p>
	 *
	 * <p>"纯源"的判据是：这段表达式在桥执行时的求值结果，与它在 lambda 创建时被捕获的值
	 * <b>必然相同</b>。常量恒成立；字段读只在字段 {@code final} 时成立（读到的引用/值
	 * 之后不可能再变）。非 final 的字段读、方法调用、任何依赖局部变量的算式一律拒绝——
	 * 那等于编一个值，属于"静默写入假值"。</p>
	 */
	static final class ParamSource {
		static final int OLD = 0, CONST = 1, GETSTATIC = 2, GETFIELD = 3, RUNTIME_CALL = 4;

		final int    kind;
		/** {@link #OLD}：老捕获表下标。 */
		final int    oldIndex;
		/** {@link #CONST}：Integer / Long / Float / Double / String，或 null。 */
		final Object constant;
		/** {@link #GETSTATIC} / {@link #GETFIELD} 的字段三元组，或 {@link #RUNTIME_CALL} 的方法三元组。 */
		final String owner, name, desc;
		/** {@link #RUNTIME_CALL}：调用指令是否指向接口（决定常量池条目类型）。 */
		final boolean itf;
		/**
		 * {@link #RUNTIME_CALL} 的实参：全部是编译期折出来的常量，按形参顺序。
		 *
		 * <p>带参的"运行时常量"是<b>纯函数</b>（{@code Scl.scl(float) = v * Scl.scl()}）：
		 * 给定同样的实参，整个运行期返回同样的值。所以桥里重放时要把这几个常量原样压回去。</p>
		 */
		final Object[] args;

		private ParamSource(int kind, int oldIndex, Object constant,
		                    String owner, String name, String desc, boolean itf, Object[] args) {
			this.kind = kind;
			this.oldIndex = oldIndex;
			this.constant = constant;
			this.owner = owner;
			this.name = name;
			this.desc = desc;
			this.itf = itf;
			this.args = args;
		}

		static ParamSource old(int oldIndex) {
			return new ParamSource(OLD, oldIndex, null, null, null, null, false, null);
		}

		static ParamSource constant(Object value) {
			return new ParamSource(CONST, -1, value, null, null, null, false, null);
		}

		static ParamSource getStatic(String owner, String name, String desc) {
			return new ParamSource(GETSTATIC, -1, null, owner, name, desc, false, null);
		}

		static ParamSource getField(String owner, String name, String desc) {
			return new ParamSource(GETFIELD, -1, null, owner, name, desc, false, null);
		}

		/** 已登记的运行时常量访问器（静态调用）：桥里重放这次调用，实参是折出来的常量。 */
		static ParamSource runtimeCall(String owner, String name, String desc, boolean itf, Object[] args) {
			return new ParamSource(RUNTIME_CALL, -1, null, owner, name, desc, itf,
				args == null ? EMPTY_ARGS : args);
		}

		static final Object[] EMPTY_ARGS = new Object[0];

		/** 这个来源产出的值是不是正好是 {@code want} 类型（写错类型会在验证期直接失败）。 */
		boolean produces(Type want) {
			switch (kind) {
				case OLD:
					return true;                                  // 由按名匹配时的"同类型"检查保证
				case CONST:
					// null 可以喂给任意引用类型（ACONST_NULL 与目标类型无关）。
					if (constant == null) {
						return want.getSort() == Type.OBJECT || want.getSort() == Type.ARRAY;
					}
					return typeOf(constant).equals(want);
				case RUNTIME_CALL:
					// desc 是<b>方法</b>描述符，取返回值类型；GETSTATIC/GETFIELD 的 desc 是字段类型。
					return Type.getReturnType(desc).equals(want);
				default:
					return Type.getType(desc).equals(want);
			}
		}

		/** 一个已折出的常量对应的类型（用于逐位校验实参/返回值）。 */
		static Type typeOf(Object constant) {
			if (constant == null) return Type.getType("Ljava/lang/Object;");
			if (constant instanceof Integer) return Type.INT_TYPE;
			if (constant instanceof Long) return Type.LONG_TYPE;
			if (constant instanceof Float) return Type.FLOAT_TYPE;
			if (constant instanceof Double) return Type.DOUBLE_TYPE;
			if (constant instanceof String) return Type.getType("Ljava/lang/String;");
			return Type.VOID_TYPE;                                // 不可重放的常量
		}

		/** 供日志/事件使用：一眼看出这一位是"搬"还是"重放"。 */
		String describe() {
			switch (kind) {
				case OLD:        return "old[" + oldIndex + "]";
				case CONST:      return "const(" + constant + ")";
				case GETSTATIC:  return "static " + owner + "." + name;
				case RUNTIME_CALL:
					return "runtime-const " + owner + "." + name + "(" + args.length + " args)";
				default:         return "field " + owner + "." + name;
			}
		}
	}

	/** 上一轮注入的桥体解析结果：目标调用 + 每一位新捕获的来源。 */
	private record BridgeDelegate(MethodInsnNode target, ParamSource[] sources, boolean targetStatic) {}

	/**
	 * 捕获桥：把"这一轮删掉了捕获变量"的老 lambda 与它的新方法接起来。
	 *
	 * <p><b>为什么只做删除方向</b>：新描述符的形参表是老形参表的子序列，被删掉的那个槽位
	 * 在新方法体里<b>物理上不存在</b>（验证器保证读不到），所以桥做的是"丢弃一个新代码
	 * 永远不会读的值"——不是伪造值，也没有任何写入。反方向（新增捕获）必须替老实例编一个
	 * {@code 0}/{@code null} 填进去，而新方法体很可能真读它，那就是静默写入假值。</p>
	 *
	 * <p><b>三道门</b>（任一不成立就退回幽灵桩，不猜）：</p>
	 * <ol>
	 *   <li><b>SAM 不变</b>：功能接口、{@code samMethodType}、{@code instantiatedMethodType}
	 *       三者都必须相等。否则是"功能接口变了"（{@code BiFunction} → {@code Function}），
	 *       老实例对象本身还绑在旧接口上，桥接 impl 救不了。</li>
	 *   <li><b>宿主相同</b>：同一个 {@code hostKey}，否则"同一个 lambda"只是猜。</li>
	 *   <li><b>映射唯一</b>：新捕获表必须是老捕获表的<b>唯一</b>子序列嵌入。重复类型
	 *       （{@code [int,int] → [int]}）有歧义，直接拒绝。</li>
	 * </ol>
	 *
	 * <p>另有一条<b>布局自检</b>：{@code 捕获表 ++ 实例化 SAM 形参} 必须恰好等于 impl 描述符。
	 * 这条等式不成立就说明我们对这个调用点的参数布局理解有误，一律不建桥。</p>
	 *
	 * <p><b>第二轮的坑</b>：桥的键在新版本里天然没有对应物，若不在这里重新认领，它会被
	 * 当成"又被删了一次"重新幽灵化。所以先认桥（{@link #getBridgeDelegate}），把它上一轮
	 * 的目标顺着本轮的配对关系挪到新名字上，再重生成桥体——而不是"识别后沿用旧体"，
	 * 那样 {@code ctx.renameMap} 一改名桥就指向空气。</p>
	 */
	static void stepCaptureBridge(ClassNode oldCn, ClassNode newCn, LambdaAligner.MatchContext ctx,
	                                      Set<String> presentKeys) {
		List<LambdaAligner.SyntheticInfo> allOld = new ArrayList<>();
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<LambdaAligner.SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			allOld.addAll(g);
		}
		List<LambdaAligner.SyntheticInfo> unpairedNew = new ArrayList<>();
		for (int ni = ctx.newGroups.nextEntry(-1); ni != -1; ni = ctx.newGroups.nextEntry(ni)) {
			List<LambdaAligner.SyntheticInfo> g = ctx.newGroups.valueAt(ni);
			if (g == null) continue;
			for (LambdaAligner.SyntheticInfo n : g) {
				if (n.matched || n.ghost || !n.renameable) continue;
				if (n.captured == null) continue;
				unpairedNew.add(n);
			}
		}

		// ---- 1. 第一轮：老 lambda 删了捕获，且新类里没有同键（本来会变成幽灵） ----
		Map<String, LambdaAligner.SyntheticInfo> claim = new HashMap<>();      // oldKey -> 唯一候选
		Map<String, Integer>      claims = new HashMap<>();      // newKey -> 被几个老键抢
		for (LambdaAligner.SyntheticInfo oi : allOld) {
			if (oi.matched || oi.ghost || oi.bridge || !oi.renameable) continue;
			// 桥的键是"老 CallSite 认的那个键"：Run E 就是旧侧原名，Run R 是上一代生效侧的名字。
			// 用 Raw 名当桥键会让老实例解析到一个 Raw 名字上（那个名字在本代可能属于别的 lambda）。
			if (presentKeys.contains(ctx.effKeyFor(oi))) continue;
			if (oi.captured == null) continue;
			LambdaAligner.SyntheticInfo only = null;
			int hits = 0;
			for (LambdaAligner.SyntheticInfo ni : unpairedNew) {
				if (planBridge(oi, ni, newCn) == null) continue;
				only = ni;
				if (++hits > 1) break;
			}
			// S29：唯一命中的目标是"零非接收者捕获"（空映射，无任何搬参证明）时，不能仅凭
			// hits == 1 建桥——同宿主同 SAM 可能还有因无 -g 捕获类型歧义而没通过 planBridge 的
			// 兄弟候选（它的映射算不出来，但同样是"同一个 lambda"的候选）。此时先数一遍
			// bridgeGatesHold 能过的候选：≥2 就必须交给宿主序号消歧，消歧不通过则放弃建桥、
			// 安全退回保留旧方法体，绝不把旧实例绑到空捕获死分支（n -> ""）。
			if (hits == 1 && only != null && !LambdaBytecodeScanner.hasCapturedVariables(only, newCn.name)) {
				int eligible = 0;
				for (LambdaAligner.SyntheticInfo other : unpairedNew) {
					if (bridgeGatesHold(oi, other)) eligible++;
				}
				if (eligible >= 2) {
					final int eligibleCount = eligible;
					LambdaAligner.SyntheticInfo byOrdinal = bridgeTargetByHostOrdinal(ctx, oi, unpairedNew, newCn);
					if (byOrdinal == null) {
						LambdaAligner.dbg(() -> "CAPTURE-BRIDGE-SKIPPED zero-capture ambiguous: " + oi.name + oi.desc
							+ " eligible=" + eligibleCount);
						continue;
					}
					only = byOrdinal;
				}
			}
			if (hits != 1) {
				// 多候选歧义：只在"同宿主新旧调用序列长度一致"时用序号消歧（长度一致 ⇒ 没有插入/删除）。
				// 长度不一致（S40 的中间插入）一律不用位置配对，避免把旧调用点绑到错误语义。
				final int candidates = hits;
				LambdaAligner.SyntheticInfo byOrdinal = bridgeTargetByHostOrdinal(ctx, oi, unpairedNew, newCn);
				if (byOrdinal == null) {
					LambdaAligner.dbg(() -> "CAPTURE-BRIDGE-SKIPPED " + ctx.currentClass + "#"
						+ oi.name + oi.desc + " matchCount=" + candidates + " host=" + oi.hostKey);
					continue;
				}
				LambdaAligner.dbg(() -> "capture bridge resolved by host ordinal: " + ctx.currentClass + "#"
					+ oi.name + oi.desc + " -> " + byOrdinal.name + byOrdinal.desc
					+ " matchCount=" + candidates + " host=" + oi.hostKey);
				only = byOrdinal;
			}
			String oldKey = ctx.effKeyFor(oi);
			String newKey = only.name + only.desc;
			claim.put(oldKey, only);
			claims.merge(newKey, 1, Integer::sum);
		}
		for (Map.Entry<String, LambdaAligner.SyntheticInfo> e : claim.entrySet()) {
			LambdaAligner.SyntheticInfo ni = e.getValue();
			if (claims.getOrDefault(ni.name + ni.desc, 0) != 1) continue;   // 一个目标被多抢：全部拒绝
			LambdaAligner.SyntheticInfo oi = findOldByKey(ctx, e.getKey());
			if (oi == null) continue;
			ParamSource[] plan = planBridge(oi, ni, newCn);
			if (plan == null) continue;
			String targetName = finalName(ctx, ni);
			String target = targetName + ni.desc;
			ctx.bridges.put(e.getKey(), new BridgeSpec(oi.desc, targetName, ni.desc, plan,
				oi.isStatic(), ni.isStatic(),
				LambdaOrphanEmitter.locationOf(ctx.currentClass, oi.name, oi.desc) + " -> " + target));
			// 有合成位就单独留痕：这是一次"桥替老实例重新造了一个值"，属于可观测的行为变化
			// （不是简单搬运），排查时最想知道的就是"哪个位置、从哪来"。
			StringBuilder synth = new StringBuilder();
			for (int j = 0; j < plan.length; j++) {
				if (plan[j].kind == ParamSource.OLD) continue;
				if (synth.length() > 0) synth.append(", ");
				synth.append("new[").append(j).append("]=").append(plan[j].describe());
			}
			if (synth.length() > 0) {
				LambdaAligner.TEST_EVENTS.add(new LambdaAligner.AlignmentEvent("BRIDGE_SYNTH", oi.name, ni.name,
					ctx.currentClass + "#" + e.getKey() + " " + synth));
				RemoldAgent.info("[LambdaAligner] Capture bridge replaying added capture: " + ctx.currentClass + "#"
				                 + e.getKey() + ": " + synth);
			}
			LambdaAligner.TEST_EVENTS.add(new LambdaAligner.AlignmentEvent("BRIDGE", oi.name, ni.name,
				ctx.currentClass + "#" + e.getKey() + " -> " + target));
			RemoldAgent.info("[LambdaAligner] Capture bridge " + ctx.currentClass + "#" + e.getKey()
			                 + " -> " + target + " (captures: " + oi.captured.size() + " -> "
			                 + ni.captured.size() + ", old instances remain active)");
		}

		// ---- 2. 第二轮：上一轮建好的桥，把目标挪到本轮的名字上并重生成 ----
		// Run E：桥在旧侧方法表里（旧侧就是 E_prev），直接扫 oldGroups。
		// Run R：桥不在 R_prev 里（它是上一轮为兜住老实例造出来的），只能从 E_prev 枚举，
		//        再用翻译表把"桥的目标（生效侧名字）"反查回 R_prev 的槽位，才能找到本轮配对物。
		if (ctx.runR) {
			stepInheritedBridges(ctx, newCn, presentKeys);
		} else {
			for (LambdaAligner.SyntheticInfo oi : allOld) {
				if (!oi.bridge) continue;
				String oldKey = oi.name + oi.desc;
				if (presentKeys.contains(oldKey)) continue;
				MethodNode mn = findMethod(oldCn, oi.name, oi.desc);
				if (mn == null) continue;
				BridgeDelegate bd = getBridgeDelegate(mn);
				if (bd == null) {
					LambdaAligner.dbg(() -> "BRIDGE unrecognized body: " + oldKey);
					continue;                                          // 认不出：让它按幽灵处理
				}
				LambdaAligner.SyntheticInfo prevTarget = findOldByKey(ctx, bd.target.name + bd.target.desc);
				if (prevTarget == null) {
					LambdaAligner.dbg(() -> "BRIDGE target vanished: " + oldKey + " -> " + bd.target.name + bd.target.desc);
					continue;
				}
				repointBridge(ctx, oldKey, oi.desc, oi.isStatic(), oi.name, bd, prevTarget, presentKeys);
			}
		}
	}

	/**
	 * Run R 的桥继承：继承集来自 {@code E_prev}，目标重指向落在本轮最终名字上。
	 *
	 * <p>顺序约束（规格 §4.2）：本步必须晚于阶段二（避障名已定案），早于孤儿注入
	 * （桥体由 {@code resurrectOrphanedLambdas} 按 {@code ctx.bridges} 生成）。</p>
	 */
	private static void stepInheritedBridges(LambdaAligner.MatchContext ctx, ClassNode newCn, Set<String> presentKeys) {
		ClassNode effCn = ctx.effPrevCn;
		if (effCn == null) return;
		// 生效侧方法键 → R_prev 槽位（用于把"上一轮桥指向的生效侧方法"反查回本轮配对物）。
		Map<String, LambdaAligner.SyntheticInfo> rawByEffKey = new HashMap<>();
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<LambdaAligner.SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			for (LambdaAligner.SyntheticInfo oi : g) {
				rawByEffKey.putIfAbsent(ctx.effKeyFor(oi), oi);
			}
		}
		for (MethodNode mn : effCn.methods) {
			if (!isBridgeMethod(mn)) continue;
			String oldKey = mn.name + mn.desc;
			if (presentKeys.contains(oldKey)) continue;
			BridgeDelegate bd = getBridgeDelegate(mn);
			if (bd == null) {
				LambdaAligner.dbg(() -> "BRIDGE unrecognized body: " + oldKey);
				continue;
			}
			LambdaAligner.SyntheticInfo prevTarget = rawByEffKey.get(bd.target.name + bd.target.desc);
			if (prevTarget == null) {
				LambdaAligner.dbg(() -> "BRIDGE target vanished: " + oldKey + " -> " + bd.target.name + bd.target.desc);
				continue;
			}
			repointBridge(ctx, oldKey, mn.desc, (mn.access & Opcodes.ACC_STATIC) != 0, mn.name, bd,
				prevTarget, presentKeys);
		}
	}

	/**
	 * 把一条已存在的捕获桥重新指向本轮产物里的目标方法（Run E / Run R 共用）。
	 *
	 * @param oldKey      桥的键（老 CallSite 认的 {@code name+desc}）
	 * @param bridgeDesc  桥的描述符
	 * @param bridgeStatic 桥是否静态
	 * @param bridgeName  桥的方法名（仅用于日志定位）
	 * @param prevTarget  桥目标在"配对旧侧"里的对应方法
	 */
	private static void repointBridge(LambdaAligner.MatchContext ctx, String oldKey, String bridgeDesc, boolean bridgeStatic,
	                                  String bridgeName, BridgeDelegate bd, LambdaAligner.SyntheticInfo prevTarget,
	                                  Set<String> presentKeys) {
		// 配对关系只记在新侧（pair 里 ni.matchedWith = oi），反向要自己找。
		LambdaAligner.SyntheticInfo now = findNewForOld(ctx, prevTarget);
		if (now == null) {
			LambdaAligner.dbg(() -> "BRIDGE target unpaired: " + oldKey + " -> " + bd.target.name + bd.target.desc);
			return;                                            // 目标真没了
		}
		if (now.ghost || !now.renameable) return;
		// captured 只在 recordLambdaShape 遇到 indy 时赋值；目标若变成仅被直接调用/不可达，
		// 这里会是 null。第一轮已判过，第二轮同样不能直接解引用，否则抛 NPE 拖垮整类对齐。
		if (now.captured == null) return;
		if (presentKeys.contains(oldKey)) return;
		// 目标的静态位若与上一轮不同，上一轮那份"搬哪些实参"的记录就不再成立
		// （比如它又用回了 this，目标从静态变回实例，而桥当时把接收者丢掉了）。
		// 这种情况不猜，退回幽灵桩。
		if (bd.targetStatic() != now.isStatic()) {
			LambdaAligner.dbg(() -> "BRIDGE target staticness changed: " + oldKey);
			return;
		}
		// 上一轮记下的"每一位从哪来"只覆盖捕获表；解析出的 sources 还带着桥体尾部压的
		// SAM 实参（它属于目标形参，不属于捕获表）。先按本轮目标描述符算出尾部个数，
		// 校验总长与尾参类型，再把捕获那一段交给新一轮的 BridgeSpec —— 尾部由
		// injectBridgeBody 按本轮描述符重新压，不能在轮次间复用。
		int newFirst   = now.isStatic() ? 0 : 1;
		int newCapArgs = now.captured.size() - newFirst;
		int samArgs    = Type.getArgumentTypes(now.desc).length - newCapArgs;
		if (samArgs < 0 || bd.sources().length != now.captured.size() + samArgs
		    || !samTailMatches(bridgeDesc, now.desc, samArgs)) {
			LambdaAligner.dbg(() -> "BRIDGE source plan size mismatch: " + oldKey
				+ " plan=" + bd.sources().length + " want=" + (now.captured.size() + samArgs));
			return;
		}
		String targetName = finalName(ctx, now);
		String target = targetName + now.desc;
		ParamSource[] caps = Arrays.copyOf(bd.sources(), now.captured.size());
		ctx.bridges.put(oldKey, new BridgeSpec(bridgeDesc, targetName, now.desc, caps,
			bridgeStatic, now.isStatic(),
			LambdaOrphanEmitter.locationOf(ctx.currentClass, bridgeName, bridgeDesc) + " -> " + target));
		LambdaAligner.TEST_EVENTS.add(new LambdaAligner.AlignmentEvent("BRIDGE_REPOINT", bridgeName, now.name,
			ctx.currentClass + "#" + oldKey + " -> " + target));
	}

	/**
	 * 该方法在本轮产物里的<b>最终</b>名字。
	 *
	 * <p>阶段二可能给未配对的新方法分配避障名，而改名只记在 {@code ctx.renameMap} 里、
	 * {@link LambdaAligner.SyntheticInfo#name} 保持编译期原名。桥的目标必须用最终名字——否则会指回自己
	 * （键相同就是无限递归）。</p>
	 */
	static String finalName(LambdaAligner.MatchContext ctx, LambdaAligner.SyntheticInfo info) {
		String renamed = ctx.renameMap.get(info.name + info.desc);
		return renamed != null ? renamed : info.name;
	}

	/**
	 * 判定能否建桥，并给出"新形参 → 老形参"的映射。
	 *
	 * <p>返回 {@code null} 表示不建桥。映射按可靠性分三级：</p>
	 * <ol>
	 *   <li><b>形参名</b>（最硬）：两侧都带 {@code -g} 时，源码里的变量名直接就是身份。
	 *       名字不随宿主方法增删局部变量而变，所以宿主签名变了也照样能定映射；</li>
	 *   <li><b>宿主形参槽号</b>：只对"捕获来自宿主形参"成立——形参槽在宿主描述符不变时稳定，
	 *       而<b>局部变量槽会随前序局部增删整体位移</b>，槽号相等不构成证明；</li>
	 *   <li><b>类型唯一性</b>（兜底）：每个新形参在旧捕获表里恰好一个同类型位置。</li>
	 * </ol>
	 *
	 * <p>三级都必须满足"每个新形参恰好一个来源 + 整体内射"。内射是硬要求：两个新形参
	 * 共用一个旧值意味着它们是<b>两个不同的变量</b>，值不一定相同，喂同一个值是静默错值。</p>
	 */
	/**
	 * 多候选歧义时的位置消歧：只在**同宿主的新旧调用序列长度一致**时成立（长度一致 ⇒ 没有插入/删除，
	 * 序号就是可靠证据）。长度不一致（S40 的"中间插入"）一律不使用位置配对，退回幽灵/保留旧体。
	 *
	 * <p>只接受普通方法宿主（{@code M:}）：lambda 宿主的键是链式 {@code L:hash@up}，哈希屏蔽字面量，
	 * 跨宿主同形时并不可靠。</p>
	 */
	private static LambdaAligner.SyntheticInfo bridgeTargetByHostOrdinal(LambdaAligner.MatchContext ctx, LambdaAligner.SyntheticInfo oi,
	                                                       List<LambdaAligner.SyntheticInfo> unpairedNew, ClassNode newCn) {
		if (oi.hostKey == null || !oi.hostKey.startsWith("M:")) return null;
		List<LambdaAligner.SyntheticInfo> oldSeq = ctx.oldHostSeq.get(oi.hostKey);
		List<LambdaAligner.SyntheticInfo> newSeq = ctx.newHostSeq.get(oi.hostKey);
		if (oldSeq == null || newSeq == null || oldSeq.size() != newSeq.size()) return null;
		int idx = oldSeq.indexOf(oi);
		if (idx < 0 || idx >= newSeq.size()) return null;
		LambdaAligner.SyntheticInfo target = newSeq.get(idx);
		if (target == null || !unpairedNew.contains(target)) return null;
		return planBridge(oi, target, newCn) == null ? null : target;
	}

	private static ParamSource[] planBridge(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni, ClassNode newCn) {
		if (!bridgeGatesHold(oi, ni)) return null;
		int[] legacy = legacyMapping(oi, ni);
		if (legacy != null) {
			ParamSource[] plan = new ParamSource[legacy.length];
			for (int j = 0; j < legacy.length; j++) plan[j] = ParamSource.old(legacy[j]);
			return plan;
		}
		return planWithSynthesis(oi, ni, newCn);
	}

	/**
	 * 建桥的硬前提。任一不成立就退回幽灵桩（行为与引入本机制前完全一致）。
	 */
	private static boolean bridgeGatesHold(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni) {
		if (oi.captured == null || ni.captured == null) return false;
		// 方向限制：
		//  - 实例 → 静态：允许。这正是"删掉 this 引用"的形态（新方法体物理上读不到接收者），
		//    桥丢弃接收者转发即可，没有伪造值。
		//  - 静态 → 实例：拒绝。那等于"新增一个接收者捕获"，老实例里没有这个值，
		//    桥只能编 null 塞进去，而新体必然读它（否则 javac 不会把它写成实例方法）。
		if (oi.isStatic() && !ni.isStatic()) return false;
		if (!sameHost(oi, ni)) return false;
		if (!Objects.equals(oi.fiType, ni.fiType)) return false;
		if (!Objects.equals(oi.samSig, ni.samSig)) return false;
		if (!Objects.equals(oi.instSig, ni.instSig)) return false;
		if (!layoutHolds(oi) || !layoutHolds(ni)) return false;
		// 空捕获表下任何"证明"都是空洞的：两侧都空，映射算出来是个空数组，证明通过却什么也没证。
		// 这类 lambda 没有实参可搬，建桥没有任何意义，一律不建（必须放在按名字映射之前——
		// 名字映射对 0 个捕获会返回一个合法的空数组，正好绕过等长闸）。
		//
		// 这条也挡住了"零捕获 → 新增捕获"：老实例完全没有状态，谈"同一个 lambda"没有任何依据
		// （LambdaAlignTortureTest 的"删掉中间层"就是靠这条不被误接成桥）。
		return !oi.captured.isEmpty();
	}

	/**
	 * 纯"搬值"的映射：新捕获表里的每一项都来自一个老捕获。
	 *
	 * <p>覆盖删除 / 重排 / 子序列，<b>不允许</b>新增。返回 {@code null} 表示这一路走不通，
	 * 由 {@link #planWithSynthesis} 尝试对多出来的那几位做纯源重放。</p>
	 */
	private static int[] legacyMapping(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni) {
		if (ni.captured.size() > oi.captured.size()) return null;

		int[] byName = mappingByNames(oi, ni);
		if (byName != null) return byName;

		// 槽号可比的前提是宿主描述符完全一致，否则槽号整体位移。
		int[] bySlot = null;
		if (oi.hostKey != null && oi.hostKey.equals(ni.hostKey)) {
			bySlot = mappingByHostSlots(oi, ni);
		}
		// 捕获数"不变"只可能是重排，而重排本身不构成"这两个是同一个 lambda"的证据：
		// 同一个宿主里换个体、换个返回值的 lambda 也会落在同一 SAM 上。所以等长时
		// 必须有证明（名字或形参槽），且不能是空捕获表（空表下证明是空洞的）。
		// 变少则不同：少了一个捕获本身就是要桥接的那类编辑，允许退回类型推断。
		if (ni.captured.size() == oi.captured.size()) {
			if (ni.captured.isEmpty()) return null;
			return bySlot;
		}
		return bySlot != null ? bySlot : mappingByType(oi, ni);
	}

	/**
	 * 新增捕获的<b>纯源重放</b>：把宿主方法里"这个变量从哪来"回溯出来，在桥里重放一遍。
	 *
	 * <p>只在<b>两侧名字齐全</b>时尝试。没有名字就无法区分"这一位是新增的"和"老变量换了名字"，
	 * 合成会退化成猜值。名字齐全时先把仍然存在的老捕获按名字对上（沿用老实例存下来的值），
	 * 剩下的新捕获才允许重放——所以"删一个 + 加一个"这种混合编辑也能处理。</p>
	 *
	 * <p>重放的三类纯源见 {@link ParamSource}：常量、{@code final} 静态字段、{@code final}
	 * 实例字段（后者要求桥本身持有 {@code this}）。其余一律拒绝：非 final 字段会在创建之后
	 * 被改写，读它等于编一个"看起来像"的值。</p>
	 */
	private static ParamSource[] planWithSynthesis(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni, ClassNode newCn) {
		List<String> on = oi.captureNames, nn = ni.captureNames;
		if (on == null || nn == null) return null;
		int n = oi.captured.size(), m = ni.captured.size();
		if (on.size() != n || nn.size() != m) return null;
		if (ni.hostMethod == null || ni.captureSlots == null) return null;
		if (ni.captureSlots.size() != m) return null;
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (m <= nf) return null;

		ParamSource[] plan = new ParamSource[m];
		boolean[] used = new boolean[n];
		if (of == 1 && nf == 1) {
			plan[0] = ParamSource.old(0);                          // 接收者 ↔ 接收者
			used[0] = true;
		}

		boolean synthesized = false;
		for (int j = nf; j < m; j++) {
			String nm = nn.get(j);
			if (nm == null) return null;
			int only = -1;
			for (int i = of; i < n; i++) {
				if (used[i] || !nm.equals(on.get(i))) continue;
				if (!oi.captured.get(i).equals(ni.captured.get(j))) return null;   // 同名换型：矛盾
				if (only >= 0) return null;                                       // 同名多处：歧义
				only = i;
			}
			if (only >= 0) {
				plan[j] = ParamSource.old(only);
				used[only] = true;
				continue;
			}
			ParamSource src = synthesize(ni.hostMethod, ni.captureSlots.get(j),
				ni.hostParamEndSlot, !oi.isStatic(), newCn, ni.captured.get(j));
			if (src == null) {
				int jj = j;
				LambdaAligner.dbg(() -> "BRIDGE synth refused: " + nn.get(jj) + " slot=" + ni.captureSlots.get(jj));
				return null;
			}
			plan[j] = src;
			synthesized = true;
		}
		// 一位都没合成，说明 legacy 路径失败是别的原因（矛盾/歧义），不要在这里兜。
		if (!synthesized) return null;

		// 每一位的产出类型必须与目标形参逐位相等：写错类型在验证期直接失败，宁可退回幽灵桩。
		Type[] want = Type.getArgumentTypes(ni.desc);
		for (int j = nf; j < m; j++) {
			if (!plan[j].produces(want[j - nf])) return null;
		}
		return plan;
	}

	/**
	 * 回溯"宿主方法里槽 {@code slot} 的值从哪来"，把它变成一个可在桥里重放的纯源。
	 *
	 * <p>依据 JLS §15.27.2：被捕获的局部变量是 effectively final，所以在宿主方法里对该槽
	 * 只有<b>唯一一处</b> {@code xSTORE}。多于一处说明槽被别的变量复用了，唯一性不成立，拒绝。</p>
	 *
	 * <p>只认"紧邻 store 的一到两条指令"：常量、{@code GETSTATIC}、{@code ALOAD 0; GETFIELD}。
	 * 任何算式、方法调用、读别的局部变量都不认——那些值依赖创建时的栈帧，桥执行时已经不存在。</p>
	 *
	 * @param bridgeHasThis 桥自身是不是实例方法（{@code this.field} 需要槽 0 的接收者）
	 */
	private static ParamSource synthesize(MethodNode host, int slot, int hostParamEndSlot,
	                                      boolean bridgeHasThis, ClassNode newCn, String wantDesc) {
		if (host == null || slot < 0) return null;
		List<AbstractInsnNode> real = realInsns(host);
		if (real == null) return null;

		// 宿主形参：值由调用方给，方法体里没有"它从哪来"——唯一例外是构造器把它存进了
		// 不可变字段（`this.cfg = cfg`），那样桥可以靠 `ALOAD 0; GETFIELD` 把它取回来。
		if (hostParamEndSlot >= 0 && slot < hostParamEndSlot) {
			return paramViaField(host, real, slot, wantDesc, bridgeHasThis, newCn);
		}

		int storeAt = storeOf(real, slot);
		if (storeAt < 1) return null;

		AbstractInsnNode prev = real.get(storeAt - 1);
		// 1) 常量：值恒定，重放恒等价（`int margin = 10` 这类）。
		if (isConstPush(prev)) {
			Object c = constValue(prev);
			if (c == NOT_A_CONSTANT) return null;
			return ParamSource.constant(c);
		}
		// 2) 静态字段：只有 final 才保证"桥读到的还是创建时那个值"。
		if (prev instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
			return isFinalField(newCn, f) ? ParamSource.getStatic(f.owner, f.name, f.desc) : null;
		}
		// 3) 当前实例的字段：ALOAD 0; GETFIELD。桥必须自己持有 this，否则无从取接收者。
		if (bridgeHasThis && storeAt >= 2
		    && real.get(storeAt - 2) instanceof VarInsnNode a
		    && a.getOpcode() == Opcodes.ALOAD && a.var == 0
		    && prev instanceof FieldInsnNode g && g.getOpcode() == Opcodes.GETFIELD) {
			return isFinalField(newCn, g) ? ParamSource.getField(g.owner, g.name, g.desc) : null;
		}
		// 4) 已登记的"运行时常量"访问器：静态调用，返回值在整个运行期不变。
		//    为什么它不是"编一个值"：桥里重放的就是同一次调用，语义上等价于把当年那次调用
		//    的结果再取一遍——前提由登记方承诺（见 RuntimeConstants）。
		//    带参形态（Scl.scl(float) = v * Scl.scl()）是<b>纯函数</b>：实参必须是可折的常量，
		//    桥里把折出来的实参原样压回去再调用。
		if (prev instanceof MethodInsnNode m && RuntimeConstants.isRuntimeConstant(m)) {
			Type[] argTypes = Type.getArgumentTypes(m.desc);
			if (argTypes.length == 0) {
				return ParamSource.runtimeCall(m.owner, m.name, m.desc, m.itf, ParamSource.EMPTY_ARGS);
			}
			Object[] args = foldValues(host, real, storeAt - 1, argTypes.length, 0, new HashSet<>());
			if (args == null) return null;                        // 实参不是常量：无从重放
			for (int i = 0; i < argTypes.length; i++) {
				if (!ParamSource.typeOf(args[i]).equals(argTypes[i])) return null;
			}
			return ParamSource.runtimeCall(m.owner, m.name, m.desc, m.itf, args);
		}
		// 4) 纯算式：整段在编译期算成一个常量（`float scaled = pad * 2;` 这类）。
		//    只认算术/位运算/类型转换，且叶子必须都能折成常量；任何一条不满足就整体放弃。
		Object folded = foldSlot(host, real, slot, 0, new HashSet<>());
		return folded == NOT_A_CONSTANT ? null : ParamSource.constant(folded);
	}

	/**
	 * 构造器参数 → 不可变字段：{@code this.f = cfg} 之后，桥用 {@code ALOAD 0; GETFIELD f}
	 * 就能把"当年那个构造器参数"的值取回来。
	 *
	 * <p>为什么只需要证明字段 {@code final}：JVM 保证 {@code final} 字段只在构造器里被写一次，
	 * 所以"构造完成后读它"与"构造时参数的值"必然相同。这也正是 {@code InitFix.scanParamFields}
	 * 的条件 A——比它的条件 B（非 final 但 private + 全 Nest 单处 PUTFIELD）更严，但不需要
	 * 扫 Nest。</p>
	 *
	 * <p><b>前提是桥自己持有 {@code this}</b>：静态桥没有接收者，拿不到那个实例。此时不猜。</p>
	 */
	private static ParamSource paramViaField(MethodNode host, List<AbstractInsnNode> real, int slot,
	                                         String wantDesc, boolean bridgeHasThis, ClassNode newCn) {
		if (!"<init>".equals(host.name) || !bridgeHasThis) return null;
		String paramDesc = paramDescAt(host, slot);
		if (paramDesc == null || !paramDesc.equals(wantDesc)) return null;

		FieldInsnNode only = null;
		for (int i = 0; i + 2 < real.size(); i++) {
			AbstractInsnNode a = real.get(i), b = real.get(i + 1), c = real.get(i + 2);
			if (!(a instanceof VarInsnNode va) || va.getOpcode() != Opcodes.ALOAD || va.var != 0) continue;
			if (!(b instanceof VarInsnNode vb) || vb.var != slot || !isLoadOpcode(vb.getOpcode())) continue;
			if (!(c instanceof FieldInsnNode fc) || fc.getOpcode() != Opcodes.PUTFIELD) continue;
			if (!fc.desc.equals(paramDesc)) continue;
			// 同一个参数存进了多个字段：哪个才是"那个值"不再唯一，不猜。
			if (only != null && !(only.name.equals(fc.name) && only.desc.equals(fc.desc))) return null;
			only = fc;
		}
		if (only == null || !isFinalField(newCn, only)) return null;
		return ParamSource.getField(only.owner, only.name, only.desc);
	}

	/** 宿主方法第 {@code slot} 个形参的描述符；不是形参则 {@code null}。 */
	private static String paramDescAt(MethodNode host, int slot) {
		int s = (host.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (Type t : Type.getArgumentTypes(host.desc)) {
			if (s == slot) return t.getDescriptor();
			s += t.getSize();
		}
		return null;
	}

	/** 该槽在方法体里唯一一处 {@code xSTORE} 的下标；不是唯一一处则 {@code -1}。 */
	private static int storeOf(List<AbstractInsnNode> real, int slot) {
		int at = -1, n = 0;
		for (int i = 0; i < real.size(); i++) {
			AbstractInsnNode x = real.get(i);
			if (x instanceof VarInsnNode v && isStoreOpcode(v.getOpcode()) && v.var == slot) {
				n++;
				at = i;
			}
		}
		return n == 1 ? at : -1;
	}

	/** 折叠深度上限：链式局部变量最多跟四层，再深就放弃（宁可漏补）。 */
	private static final int FOLD_DEPTH = 4;

	/**
	 * 把"槽 {@code slot} 那唯一一处 store 的生产者"在编译期算成一个常量。
	 *
	 * <p>为什么是"算成常量"而不是"把原指令序列搬进桥"：搬序列要保证栈形状、类型、操作码
	 * 全部一致，任何一处错位都是验证期失败或静默错值；而算成一个常量只有一种失败模式——
	 * 算不出来就返回 {@link #NOT_A_CONSTANT}，调用方退回幽灵桩。这也正是 javac 自己会对
	 * 常量表达式做的事。</p>
	 *
	 * <p>求值语义必须与 JVM 一致：`int`/`long` 的环绕、`float`/`double` 的 IEEE 754（Java 17
	 * 起一律 strictfp）都与字节码逐位相同，所以"用 Java 算一遍"是等价的，不是近似。</p>
	 */
	private static Object foldSlot(MethodNode host, List<AbstractInsnNode> real, int slot,
	                               int depth, Set<Integer> seen) {
		if (depth > FOLD_DEPTH || !seen.add(slot)) return NOT_A_CONSTANT;
		int storeAt = storeOf(real, slot);
		if (storeAt < 1) return NOT_A_CONSTANT;
		return foldRange(host, real, storeAt, depth, seen);
	}

	/**
	 * 折叠"紧邻 {@code end} 之前、恰好产出 {@code count} 个值"的那段表达式。
	 *
	 * <p>先用反向栈高度求出表达式起点（{@code need = need - pushed + consumed}），再正向
	 * 在一条小栈上求值。反向定界保证只碰这一段，不会把前面的语句卷进来。</p>
	 *
	 * @return 按<b>压栈顺序</b>（栈底 → 栈顶）排列的常量数组；折不出来返回 {@code null}
	 */
	private static Object[] foldValues(MethodNode host, List<AbstractInsnNode> real, int end,
	                                   int count, int depth, Set<Integer> seen) {
		int need = count, start = end - 1;
		while (need > 0) {
			if (start < 0) return null;
			int[] eff = stackEffect(real.get(start));
			if (eff == null) return null;                        // 不认识的指令：整段放弃
			need = need - eff[0] + eff[1];
			start--;
		}
		start++;
		ArrayDeque<Object> st = new ArrayDeque<>();
		for (int i = start; i < end; i++) {
			if (!evalOne(host, real, real.get(i), st, depth, seen)) return null;
		}
		if (st.size() != count) return null;
		Object[] out = new Object[count];
		Iterator<Object> it = st.descendingIterator();  // 栈底 → 栈顶
		for (int i = 0; i < count; i++) out[i] = it.next();
		return out;
	}

	/** 单值版本：{@code storeAt} 之前那段表达式折成一个常量。 */
	private static Object foldRange(MethodNode host, List<AbstractInsnNode> real, int storeAt,
	                                int depth, Set<Integer> seen) {
		Object[] v = foldValues(host, real, storeAt, 1, depth, seen);
		return v == null ? NOT_A_CONSTANT : v[0];
	}

	private static boolean evalOne(MethodNode host, List<AbstractInsnNode> real, AbstractInsnNode n,
	                               ArrayDeque<Object> st, int depth, Set<Integer> seen) {
		int op = n.getOpcode();
		if (isConstPush(n)) {
			Object c = constValue(n);
			if (c == NOT_A_CONSTANT) return false;
			st.push(c);
			return true;
		}
		if (n instanceof VarInsnNode v && isLoadOpcode(op)) {
			if (op == Opcodes.ALOAD) return false;               // 引用型局部变量：不在折叠范围
			Object c = foldSlot(host, real, v.var, depth + 1, seen);
			if (c == NOT_A_CONSTANT) return false;
			st.push(c);
			return true;
		}
		try {
			return evalOp(op, st);
		} catch (ArithmeticException e) {
			return false;                                        // 除零：原代码创建时就会抛，不重放
		}
	}

	/** 一段指令的栈效应 {@code {pushed, consumed}}；不在白名单内返回 {@code null}。 */
	private static int[] stackEffect(AbstractInsnNode n) {
		int op = n.getOpcode();
		if (isConstPush(n)) return new int[]{1, 0};
		if (n instanceof VarInsnNode) {
			if (isLoadOpcode(op)) return new int[]{1, 0};
			if (isStoreOpcode(op)) return new int[]{0, 1};
			return null;
		}
		if (isBinaryOp(op)) return new int[]{1, 2};
		if (isUnaryOp(op)) return new int[]{1, 1};
		return null;
	}

	private static boolean isLoadOpcode(int op) {
		return op >= Opcodes.ILOAD && op <= Opcodes.ALOAD;
	}

	/**
	 * 二元算术/位运算。
	 *
	 * <p><b>必须按类型逐个列，不能用 opcode 区间</b>：JVM 的算术指令是<b>按类型交错</b>编号的
	 * （{@code iadd=96, ladd=97, fadd=98, dadd=99}），区间判断会把别的类型一起圈进来，
	 * 再拿 {@code popInt} 去弹 {@code Long} → 静默失效。</p>
	 */
	private static boolean isBinaryOp(int op) {
		return isIntBinary(op) || isLongBinary(op) || isFloatBinary(op) || isDoubleBinary(op);
	}

	private static boolean isIntBinary(int op) {
		switch (op) {
			case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV:
			case Opcodes.IREM: case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
			case Opcodes.IAND: case Opcodes.IOR:  case Opcodes.IXOR:
				return true;
			default:
				return false;
		}
	}

	private static boolean isLongBinary(int op) {
		switch (op) {
			case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
			case Opcodes.LREM: case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
			case Opcodes.LAND: case Opcodes.LOR:  case Opcodes.LXOR:
				return true;
			default:
				return false;
		}
	}

	private static boolean isFloatBinary(int op) {
		return op == Opcodes.FADD || op == Opcodes.FSUB || op == Opcodes.FMUL
		    || op == Opcodes.FDIV || op == Opcodes.FREM;
	}

	private static boolean isDoubleBinary(int op) {
		return op == Opcodes.DADD || op == Opcodes.DSUB || op == Opcodes.DMUL
		    || op == Opcodes.DDIV || op == Opcodes.DREM;
	}

	private static boolean isUnaryOp(int op) {
		switch (op) {
			case Opcodes.INEG: case Opcodes.LNEG: case Opcodes.FNEG: case Opcodes.DNEG:
			case Opcodes.I2L: case Opcodes.I2F: case Opcodes.I2D:
			case Opcodes.L2I: case Opcodes.L2F: case Opcodes.L2D:
			case Opcodes.F2I: case Opcodes.F2L: case Opcodes.F2D:
			case Opcodes.D2I: case Opcodes.D2L: case Opcodes.D2F:
			case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
				return true;
			default:
				return false;
		}
	}

	/** 在 {@code st} 上执行一条算术/转换指令；类型不匹配或栈不够时返回 {@code false}。 */
	private static boolean evalOp(int op, ArrayDeque<Object> st) {
		// ---- int 二元 ----
		if (isIntBinary(op)) {
			Integer b = popInt(st), a = popInt(st);
			if (a == null || b == null) return false;
			int x = a, y = b;
			switch (op) {
				case Opcodes.IADD: st.push(x + y); return true;
				case Opcodes.ISUB: st.push(x - y); return true;
				case Opcodes.IMUL: st.push(x * y); return true;
				case Opcodes.IDIV: st.push(x / y); return true;
				case Opcodes.IREM: st.push(x % y); return true;
				case Opcodes.ISHL: st.push(x << y); return true;
				case Opcodes.ISHR: st.push(x >> y); return true;
				case Opcodes.IUSHR: st.push(x >>> y); return true;
				case Opcodes.IAND: st.push(x & y); return true;
				case Opcodes.IOR: st.push(x | y); return true;
				case Opcodes.IXOR: st.push(x ^ y); return true;
				default: return false;
			}
		}
		// ---- long 二元（移位量是 int，必须单独弹） ----
		if (isLongBinary(op)) {
			boolean shift = op == Opcodes.LSHL || op == Opcodes.LSHR || op == Opcodes.LUSHR;
			Object bo = st.poll();
			Long a = popLong(st);
			if (a == null || bo == null) return false;
			long x = a;
			if (shift) {
				if (!(bo instanceof Integer)) return false;
				int n = (Integer) bo;
				switch (op) {
					case Opcodes.LSHL: st.push(x << n); return true;
					case Opcodes.LSHR: st.push(x >> n); return true;
					default: st.push(x >>> n); return true;
				}
			}
			if (!(bo instanceof Long)) return false;
			long y = (Long) bo;
			switch (op) {
				case Opcodes.LADD: st.push(x + y); return true;
				case Opcodes.LSUB: st.push(x - y); return true;
				case Opcodes.LMUL: st.push(x * y); return true;
				case Opcodes.LDIV: st.push(x / y); return true;
				case Opcodes.LREM: st.push(x % y); return true;
				case Opcodes.LAND: st.push(x & y); return true;
				case Opcodes.LOR: st.push(x | y); return true;
				case Opcodes.LXOR: st.push(x ^ y); return true;
				default: return false;
			}
		}
		// ---- float 二元 ----
		if (isFloatBinary(op)) {
			Float b = popFloat(st), a = popFloat(st);
			if (a == null || b == null) return false;
			float x = a, y = b;
			switch (op) {
				case Opcodes.FADD: st.push(x + y); return true;
				case Opcodes.FSUB: st.push(x - y); return true;
				case Opcodes.FMUL: st.push(x * y); return true;
				case Opcodes.FDIV: st.push(x / y); return true;
				case Opcodes.FREM: st.push(x % y); return true;
				default: return false;
			}
		}
		// ---- double 二元 ----
		if (isDoubleBinary(op)) {
			Double b = popDouble(st), a = popDouble(st);
			if (a == null || b == null) return false;
			double x = a, y = b;
			switch (op) {
				case Opcodes.DADD: st.push(x + y); return true;
				case Opcodes.DSUB: st.push(x - y); return true;
				case Opcodes.DMUL: st.push(x * y); return true;
				case Opcodes.DDIV: st.push(x / y); return true;
				case Opcodes.DREM: st.push(x % y); return true;
				default: return false;
			}
		}
		// ---- 一元：取负与类型转换 ----
		switch (op) {
			case Opcodes.INEG: { Integer a = popInt(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.LNEG: { Long a = popLong(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.FNEG: { Float a = popFloat(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.DNEG: { Double a = popDouble(st); if (a == null) return false; st.push(-a); return true; }
			case Opcodes.I2L: { Integer a = popInt(st); if (a == null) return false; st.push((long) a); return true; }
			case Opcodes.I2F: { Integer a = popInt(st); if (a == null) return false; st.push((float) a); return true; }
			case Opcodes.I2D: { Integer a = popInt(st); if (a == null) return false; st.push((double) a); return true; }
			case Opcodes.L2I: { Long a = popLong(st); if (a == null) return false; st.push((int) (long) a); return true; }
			case Opcodes.L2F: { Long a = popLong(st); if (a == null) return false; st.push((float) (long) a); return true; }
			case Opcodes.L2D: { Long a = popLong(st); if (a == null) return false; st.push((double) (long) a); return true; }
			case Opcodes.F2I: { Float a = popFloat(st); if (a == null) return false; st.push((int) (float) a); return true; }
			case Opcodes.F2L: { Float a = popFloat(st); if (a == null) return false; st.push((long) (float) a); return true; }
			case Opcodes.F2D: { Float a = popFloat(st); if (a == null) return false; st.push((double) (float) a); return true; }
			case Opcodes.D2I: { Double a = popDouble(st); if (a == null) return false; st.push((int) (double) a); return true; }
			case Opcodes.D2L: { Double a = popDouble(st); if (a == null) return false; st.push((long) (double) a); return true; }
			case Opcodes.D2F: { Double a = popDouble(st); if (a == null) return false; st.push((float) (double) a); return true; }
			case Opcodes.I2B: { Integer a = popInt(st); if (a == null) return false; st.push((int) (byte) (int) a); return true; }
			case Opcodes.I2C: { Integer a = popInt(st); if (a == null) return false; st.push((int) (char) (int) a); return true; }
			case Opcodes.I2S: { Integer a = popInt(st); if (a == null) return false; st.push((int) (short) (int) a); return true; }
			default: return false;
		}
	}

	private static Integer popInt(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Integer ? (Integer) o : null;
	}

	private static Long popLong(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Long ? (Long) o : null;
	}

	private static Float popFloat(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Float ? (Float) o : null;
	}

	private static Double popDouble(ArrayDeque<Object> st) {
		Object o = st.poll();
		return o instanceof Double ? (Double) o : null;
	}

	/** 字段必须在本类里声明且带 {@code ACC_FINAL}；继承来的字段看不到定义，不猜。 */
	private static boolean isFinalField(ClassNode cn, FieldInsnNode f) {
		if (cn == null || f == null || !cn.name.equals(f.owner)) return false;
		for (FieldNode fn : cn.fields) {
			if (fn.name.equals(f.name) && fn.desc.equals(f.desc)) {
				return (fn.access & Opcodes.ACC_FINAL) != 0;
			}
		}
		return false;
	}

	private static boolean isStoreOpcode(int op) {
		return op == Opcodes.ISTORE || op == Opcodes.LSTORE || op == Opcodes.FSTORE
		    || op == Opcodes.DSTORE || op == Opcodes.ASTORE;
	}

	/** 单条指令就能把值压栈的"常量"形态（不含 {@code GETSTATIC} 等读状态的）。 */
	private static boolean isConstPush(AbstractInsnNode n) {
		int op = n.getOpcode();
		return op == Opcodes.ACONST_NULL
		    || (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
		    || op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1
		    || (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2)
		    || op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1
		    || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
		    || n instanceof LdcInsnNode;
	}

	/** "不是常量"的哨兵——{@code ACONST_NULL} 的合法取值是 {@code null}，不能拿它当失败信号。 */
	private static final Object NOT_A_CONSTANT = new Object();

	private static Object constValue(AbstractInsnNode n) {
		int op = n.getOpcode();
		if (op == Opcodes.ACONST_NULL) return null;
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return (long) (op - Opcodes.LCONST_0);
		if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (float) (op - Opcodes.FCONST_0);
		if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return (double) (op - Opcodes.DCONST_0);
		if (n instanceof IntInsnNode i) return i.operand;
		if (n instanceof LdcInsnNode l) {
			Object c = l.cst;
			// Type / Handle / ConstantDynamic 不是"值"，重放它们没有意义。
			if (c instanceof Integer || c instanceof Long || c instanceof Float
			    || c instanceof Double || c instanceof String) return c;
		}
		return NOT_A_CONSTANT;
	}

	/**
	 * 按源码变量名建立映射（两侧都必须带 {@code -g}）。
	 *
	 * <p>名字是"同一个变量"最硬的证据：它不随宿主方法里增删局部变量而位移，也不随宿主签名
	 * 变化而失效——这正是槽号做不到的两种情形。</p>
	 *
	 * <p>要求严格：只要有一个捕获位缺名字就整体放弃（不能"有的按名、有的按猜"）；
	 * 同名换型视为矛盾直接拒绝。实例 lambda 的第 0 位是接收者，两侧固定对齐。</p>
	 */
	private static int[] mappingByNames(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni) {
		List<String> on = oi.captureNames, nn = ni.captureNames;
		if (on == null || nn == null) return null;
		int n = oi.captured.size(), m = ni.captured.size();
		if (on.size() != n || nn.size() != m) return null;
		if (m > n) return null;
		int[] map = new int[m];
		boolean[] used = new boolean[n];
		// 两侧各自跳过接收者：静态位可以不同（实例 → 静态是"删掉 this"），此时新侧没有接收者。
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (of == 1 && nf == 1) {
			map[0] = 0;                                            // 接收者 ↔ 接收者
			used[0] = true;
		}
		for (int j = nf; j < m; j++) {
			String name = nn.get(j);
			if (name == null) return null;
			int only = -1;
			for (int i = of; i < n; i++) {
				if (!name.equals(on.get(i))) continue;
				if (!oi.captured.get(i).equals(ni.captured.get(j))) return null;   // 同名换型：矛盾
				if (only >= 0) return null;
				only = i;
			}
			if (only < 0 || used[only]) return null;
			used[only] = true;
			map[j] = only;
		}
		return map;
	}

	/**
	 * 按<b>宿主形参</b>槽号证明映射（宿主描述符必须完全一致）。
	 *
	 * <p>只认宿主形参：局部变量槽会随前序局部变量的增删整体位移，槽号相等说明不了是同一个
	 * 变量。任一捕获来自宿主局部变量时整体放弃，退回类型唯一性。</p>
	 *
	 * @return {@code 新形参下标 → 老形参下标}；不可用或无解时 {@code null}
	 */
	static int[] mappingByHostSlots(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni) {
		if (oi.captureSlots == null || ni.captureSlots == null) return null;
		if (oi.captureSlots.size() != oi.captured.size()) return null;
		if (ni.captureSlots.size() != ni.captured.size()) return null;
		if (oi.hostParamEndSlot < 0 || ni.hostParamEndSlot < 0) return null;
		// 只认宿主形参：局部变量槽会随前序局部变量的增删整体位移，槽号相等说明不了是同一个变量。
		for (int s : oi.captureSlots) if (s >= oi.hostParamEndSlot) return null;
		for (int s : ni.captureSlots) if (s >= ni.hostParamEndSlot) return null;
		int n = oi.captured.size(), m = ni.captured.size();
		if (m > n) return null;
		int[] map = new int[m];
		boolean[] used = new boolean[n];
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (of == 1 && nf == 1) {
			// 接收者恒在槽 0（javac 把 aload_0 放在捕获序列最前）。回溯不到槽 0 说明
			// 我们对这个调用点的布局理解有误，不能拿"槽号 0 恰好相同"糊过去。
			if (oi.captureSlots.get(0) != 0 || ni.captureSlots.get(0) != 0) return null;
			map[0] = 0;
			used[0] = true;
		}
		for (int j = nf; j < m; j++) {
			int only = -1;
			for (int i = of; i < n; i++) {
				if (!oi.captureSlots.get(i).equals(ni.captureSlots.get(j))) continue;
				if (!oi.captured.get(i).equals(ni.captured.get(j))) return null;   // 同槽换型：矛盾
				if (only >= 0) return null;
				only = i;
			}
			if (only < 0 || used[only]) return null;
			used[only] = true;
			map[j] = only;
		}
		return map;
	}

	/**
	 * 按类型唯一性推断映射（退路）。每个新形参必须在旧捕获表里<b>恰好</b>命中一个同类型位置。
	 *
	 * @return {@code 新形参下标 → 老形参下标}；有歧义或无解时 {@code null}
	 */
	private static int[] mappingByType(LambdaAligner.SyntheticInfo oi, LambdaAligner.SyntheticInfo ni) {
		List<String> oldL = oi.captured, newL = ni.captured;
		int n = oldL.size(), m = newL.size();
		if (m > n) return null;
		int[] map = new int[m];
		boolean[] used = new boolean[n];
		int of = oi.firstCapture(), nf = ni.firstCapture();
		if (of == 1 && nf == 1) {
			map[0] = 0;                                            // 接收者 ↔ 接收者
			used[0] = true;
		}
		for (int j = nf; j < m; j++) {
			int only = -1;
			for (int i = of; i < n; i++) {
				if (!oldL.get(i).equals(newL.get(j))) continue;
				if (only >= 0) return null;               // 同类型多个候选：歧义
				only = i;
			}
			if (only < 0 || used[only]) return null;
			used[only] = true;
			map[j] = only;
		}
		return map;
	}

	/**
	 * 布局自检：{@code 捕获表 ++ 实例化 SAM 形参} 必须恰好等于 impl 描述符。
	 *
	 * <p>成立就说明"捕获值前置、SAM 形参在后"不是假设而是每例现验的；不成立说明这个
	 * 调用点的布局另有蹊跷（编译器版本差异、或根本不是 LambdaMetafactory 产生的），
	 * 此时任何参数映射都是猜的。</p>
	 *
	 * <p><b>实例 lambda 的捕获表含接收者</b>：{@code () -> use(this.x)} 编译成
	 * {@code aload_0; indy (LC;)LRunnable;}，而 impl 是 {@code private void lambda$f$0()}
	 * ——接收者不在描述符里。所以非 static 时必须先剥掉第一个捕获再拼。</p>
	 */
	private static boolean layoutHolds(LambdaAligner.SyntheticInfo info) {
		if (info.captured == null || info.instSig == null) return false;
		List<String> caps = info.captured;
		if (!info.isStatic()) {
			if (caps.isEmpty()) return false;
			caps = caps.subList(1, caps.size());
		}
		StringBuilder sb = new StringBuilder("(");
		for (String c : caps) sb.append(c);
		for (Type t : Type.getArgumentTypes(info.instSig)) sb.append(t.getDescriptor());
		sb.append(')').append(Type.getReturnType(info.instSig).getDescriptor());
		return sb.toString().equals(info.desc);
	}

	/**
	 * 宿主门：是不是"在同一个地方创建的那个 lambda"。
	 *
	 * <p>{@code M:} 键只比<b>方法名</b>。原因是<b>被删掉的捕获变量往往就是宿主方法的形参</b>
	 * ——{@code void f(String s, int k)} 改成 {@code void f(int k)} 时，宿主描述符必然跟着变。
	 * 若要求整个 {@code hostKey} 相等，最常见的那个场景反而永远建不出桥。</p>
	 *
	 * <p>{@code L:} 键（宿主本身是个 lambda）不放松：父 lambda 的票据哈希变了，说明父自己
	 * 也没配对成功，此时谈"同一个宿主"只是猜。</p>
	 *
	 * <p><b>代价</b>：同名重载的宿主（{@code f(I)} 与 {@code f(II)} 同时存在）会共用宿主门，
	 * 只能靠 SAM 门、子序列门与"1:1 唯一"三道约束去消歧；仍有歧义时两侧都退回幽灵桩。</p>
	 */
	static boolean sameHost(LambdaAligner.SyntheticInfo a, LambdaAligner.SyntheticInfo b) {
		if (a.hostKey == null || b.hostKey == null) return false;
		if (a.hostKey.equals(b.hostKey)) return true;
		if (!a.hostKey.startsWith("M:") || !b.hostKey.startsWith("M:")) return false;
		return hostName(a.hostKey).equals(hostName(b.hostKey));
	}

	/** {@code "M:name+desc"} → {@code "M:name"}。 */
	private static String hostName(String hostKey) {
		int paren = hostKey.indexOf('(');
		return paren < 0 ? hostKey : hostKey.substring(0, paren);
	}

	/** 桥体：{@code [标记] [aload_0] 加载保留下来的老形参 → 调目标 → 返回}。 */
	static void injectBridgeBody(MethodVisitor mv, String className, boolean itf, BridgeSpec spec) {
		mv.visitCode();
		// 标记 + 日志。刻意走独立入口而不是 onOrphanInvoked：桥不是死孤儿，见 onBridgeInvoked。
		mv.visitLdcInsn(spec.location);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(LambdaAligner.class),
		                   "onBridgeInvoked", "(Ljava/lang/String;)V", false);
		// 目标要接收者时先把它压到栈底。此时桥必为实例方法（"静态 → 实例"已被
		// bridgeMapping 拒绝，那等于凭空造一个接收者值），槽 0 就是接收者。
		if (!spec.targetStatic) mv.visitVarInsn(Opcodes.ALOAD, 0);

		Type[] oldArgs = Type.getArgumentTypes(spec.oldDesc);
		int[]  slotOf  = new int[oldArgs.length];
		int    slot    = spec.isStatic ? 0 : 1;
		for (int i = 0; i < oldArgs.length; i++) {
			slotOf[i] = slot;
			slot += oldArgs[i].getSize();
		}
		// 捕获表下标 → 描述符形参下标。两侧的"第一个非接收者捕获"下标可以不同：
		// 实例 → 静态时老侧有接收者（oldFirst = 1）、新侧没有（newFirst = 0），
		// 老描述符的形参也从老侧的接收者之后开始数。
		int oldFirst = spec.isStatic ? 0 : 1;
		int newFirst = spec.targetStatic ? 0 : 1;
		for (int j = newFirst; j < spec.sources.length; j++) {
			ParamSource s = spec.sources[j];
			switch (s.kind) {
				case ParamSource.OLD: {
					int argIdx = s.oldIndex - oldFirst;
					if (argIdx < 0 || argIdx >= oldArgs.length) {
						throw new IllegalStateException("bridge param mapping out of range: cap=" + s.oldIndex
							+ " desc=" + spec.oldDesc);
					}
					mv.visitVarInsn(oldArgs[argIdx].getOpcode(Opcodes.ILOAD), slotOf[argIdx]);
					break;
				}
				case ParamSource.CONST:
					if (s.constant == null) mv.visitInsn(Opcodes.ACONST_NULL);
					else mv.visitLdcInsn(s.constant);
					break;
				case ParamSource.GETSTATIC:
					mv.visitFieldInsn(Opcodes.GETSTATIC, s.owner, s.name, s.desc);
					break;
				case ParamSource.RUNTIME_CALL:
					for (Object a : s.args) {
						if (a == null) mv.visitInsn(Opcodes.ACONST_NULL);
						else mv.visitLdcInsn(a);
					}
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, s.owner, s.name, s.desc, s.itf);
					break;
				default:                                          // GETFIELD：接收者就是桥自己的 this
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitFieldInsn(Opcodes.GETFIELD, s.owner, s.name, s.desc);
					break;
			}
		}
		// itf 必须跟着宿主走：接口里的合成方法在常量池里是 InterfaceMethodref，
		// 写成 false 会得到一条指向 Methodref 的调用指令，校验/解析直接失败。
		// 目标若是实例方法，只能走 INVOKESPECIAL：它一定是本类里的 private 合成方法，
		// 而 INVOKEVIRTUAL 对 private 方法在 nestmate 之前是非法/不可解析的。
		//
		// 尾部 SAM 实参：桥自己就是老 lambda 方法，形参 = [老捕获...] + [SAM 形参...]。
		// 老捕获按 sources 搬或现场重放即可，但 SAM 形参是<b>调用方传进来的实参</b>，
		// 必须原样压给目标。漏压时重定义不跑字节码校验器，不会先抛 VerifyError：调用方
		// 读到的缺失实参可能是 null（探针实测 NPE），也可能是 JIT 下的垃圾引用
		// （真机报告形态 EXCEPTION_ACCESS_VIOLATION）。两者同根因，都按"桥体非法"处理。
		Type[] newArgs    = Type.getArgumentTypes(spec.targetDesc);
		int    newCapArgs = spec.sources.length - newFirst;   // 新捕获表里进描述符的那一段
		int    samArgs    = newArgs.length - newCapArgs;
		int    oldCapArgs = oldArgs.length - samArgs;
		if (samArgs < 0 || oldCapArgs < 0 || !samTailMatches(spec.oldDesc, spec.targetDesc, samArgs)) {
			throw new IllegalStateException("bridge SAM tail mismatch: old=" + spec.oldDesc
				+ " target=" + spec.targetDesc + " sources=" + spec.sources.length);
		}
		for (int k = 0; k < samArgs; k++) {
			Type oldT = oldArgs[oldCapArgs + k];
			mv.visitVarInsn(oldT.getOpcode(Opcodes.ILOAD), slotOf[oldCapArgs + k]);
		}
		mv.visitMethodInsn(spec.targetStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
		                   className, spec.targetName, spec.targetDesc, itf);
		mv.visitInsn(Type.getReturnType(spec.targetDesc).getOpcode(Opcodes.IRETURN));
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** 老描述符与目标描述符的尾部 SAM 形参类型是否逐个相等。 */
	private static boolean samTailMatches(String oldDesc, String newDesc, int samArgs) {
		Type[] a = Type.getArgumentTypes(oldDesc);
		Type[] b = Type.getArgumentTypes(newDesc);
		if (samArgs < 0 || a.length < samArgs || b.length < samArgs) return false;
		for (int k = 0; k < samArgs; k++) {
			if (!a[a.length - samArgs + k].equals(b[b.length - samArgs + k])) return false;
		}
		return true;
	}

	/** 该方法的开头是不是桥体标记（{@code LDC loc; INVOKESTATIC onBridgeInvoked}）。 */
	static boolean isBridgeMethod(MethodNode mn) {
		List<AbstractInsnNode> real = realInsns(mn);
		if (real == null) return false;
		real = stripGhostHash(real);
		return real.size() >= 2
		       && real.get(0) instanceof LdcInsnNode
		       && real.get(1) instanceof MethodInsnNode p
		       && p.owner.equals(Type.getInternalName(LambdaAligner.class))
		       && p.name.equals("onBridgeInvoked");
	}

	/**
	 * 解析上一轮注入的桥体：跳过标记前缀，按顺序收集被加载的老形参，最后那条调用就是目标。
	 *
	 * <p>与 {@link #getPureDelegate} 的关键区别：<b>允许跳过形参槽位</b>。桥本来就是
	 * "少加载几个形参"的，若沿用那条"每个形参必须按槽位顺序恰好加载一次"的严格判据，
	 * 跳槽位的桥在下一轮会判不出 delegate，直接退化成死幽灵——第一轮的成果当场作废。</p>
	 *
	 * <p>这条放宽只作用于带桥标记的方法，不影响 {@code getPureDelegate} 对普通 lambda
	 * 的严格性（那份严格性是 {@code wasForwarding} 可信的前提）。</p>
	 */
	private static BridgeDelegate getBridgeDelegate(MethodNode mn) {
		List<AbstractInsnNode> real = realInsns(mn);
		if (real == null) return null;
		real = stripGhostHash(real);
		if (real.size() < 4) return null;
		if (!(real.get(0) instanceof LdcInsnNode)
		    || !(real.get(1) instanceof MethodInsnNode p)
		    || !p.owner.equals(Type.getInternalName(LambdaAligner.class))
		    || !p.name.equals("onBridgeInvoked")) {
			return null;
		}
		int i = 2;
		boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
		// 接收者压栈是可选的：目标若是静态方法（"删掉 this" 的桥），桥体不会压它。
		// 不能像旧版那样无条件要求 aload_0 —— 那会让这类桥在第二轮认不出来，
		// 白白退化成死幽灵。判据放在最后与目标调用指令一起交叉验证。
		boolean receiverPushed = false;
		if (!isStatic && i < real.size()
		    && real.get(i) instanceof VarInsnNode v0
		    && v0.getOpcode() == Opcodes.ALOAD && v0.var == 0
		    // 紧跟 GETFIELD 的 ALOAD 0 是"读 this 字段"的合成位，不是接收者压栈
		    && !(i + 1 < real.size()
		         && real.get(i + 1) instanceof FieldInsnNode gf0 && gf0.getOpcode() == Opcodes.GETFIELD)) {
			receiverPushed = true;
			i++;
		}
		// 形参槽位 -> 形参下标
		Type[] args = Type.getArgumentTypes(mn.desc);
		Map<Integer, Integer> paramAtSlot = new HashMap<>();
		int slot = isStatic ? 0 : 1;
		for (int k = 0; k < args.length; k++) {
			paramAtSlot.put(slot, k);
			slot += args[k].getSize();
		}

		List<ParamSource> loaded = new ArrayList<>();
		MethodInsnNode core = null;
		// 桥体把"捕获表下标"记下来（不是描述符形参下标）：实例方法的捕获表第 0 项是接收者，
		// 两种下标差 1，混用会让第二轮按错位的下标搬实参。
		int firstCap = isStatic ? 0 : 1;
		for (; i < real.size(); i++) {
			AbstractInsnNode insn = real.get(i);
			int op = insn.getOpcode();
			if (insn instanceof VarInsnNode v && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
				if (op == Opcodes.ALOAD && v.var == 0 && i + 1 < real.size()
				    && real.get(i + 1) instanceof FieldInsnNode gf && gf.getOpcode() == Opcodes.GETFIELD) {
					loaded.add(ParamSource.getField(gf.owner, gf.name, gf.desc));
					i++;
					continue;
				}
				Integer pi = paramAtSlot.get(v.var);
				if (pi == null) return null;                      // 加载了非形参槽位：不是我们的桥
				int capIdx = pi + firstCap;
				// 只查重、不要求槽号递增：换序桥就是<b>故意</b>倒着压栈的（b 然后 a），
				// 旧的"必须递增"判据会把它判成不认识，第二轮直接幽灵化。
				for (ParamSource s : loaded) {
					if (s.kind == ParamSource.OLD && s.oldIndex == capIdx) return null;
				}
				loaded.add(ParamSource.old(capIdx));
			} else if (isConstPush(insn)) {
				Object c = constValue(insn);
				if (c == NOT_A_CONSTANT) return null;
				loaded.add(ParamSource.constant(c));
			} else if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
				loaded.add(ParamSource.getStatic(f.owner, f.name, f.desc));
			} else if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
				// ok
			} else if (insn instanceof MethodInsnNode m) {
				// 已登记的运行时常量访问器是"压值"，不是核心调用。判据用登记表而不是
				// "位置"：核心调用也是 INVOKESTATIC，只能靠身份区分。
				if (RuntimeConstants.isRuntimeConstant(m)) {
					// 带参形态：紧邻它的前 N 个"压值"就是实参，必须全是常量。
					int argc = Type.getArgumentTypes(m.desc).length;
					if (argc > loaded.size()) return null;
					Object[] callArgs = new Object[argc];
					for (int k = argc - 1; k >= 0; k--) {
						ParamSource a = loaded.remove(loaded.size() - 1);
						if (a.kind != ParamSource.CONST) return null;
						callArgs[k] = a.constant;
					}
					loaded.add(ParamSource.runtimeCall(m.owner, m.name, m.desc, m.itf, callArgs));
					continue;
				}
				if (core != null) return null;                    // 只能有一条核心调用
				core = m;
			} else {
				return null;
			}
		}
		if (core == null) return null;
		boolean targetStatic = core.getOpcode() == Opcodes.INVOKESTATIC;
		// 交叉验证：实例桥必压接收者、且只在目标也是实例方法时才压；静态桥反之。
		if (receiverPushed == targetStatic) return null;
		// 对齐 {@link BridgeSpec#sources} 的契约（长度 == 新捕获表长度）：
		// 接收者被单独压栈、没进 loaded，这里补回捕获下标 0（实例方法的接收者恒为捕获 0）。
		if (receiverPushed) loaded.add(0, ParamSource.old(0));
		return new BridgeDelegate(core, loaded.toArray(new ParamSource[0]), targetStatic);
	}

	/** 全部真实指令（跳过 {@code opcode < 0} 的伪指令）；空方法返回 {@code null}。 */
	private static List<AbstractInsnNode> realInsns(MethodNode mn) {
		if (mn.instructions == null || mn.instructions.size() == 0) return null;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode i : mn.instructions) {
			if (i.getOpcode() >= 0) real.add(i);
		}
		return real;
	}

	/** 剥掉"生前指纹"标记 {@code LDC <long>; POP2}。 */
	private static List<AbstractInsnNode> stripGhostHash(List<AbstractInsnNode> real) {
		if (real.size() >= 2
		    && real.get(0) instanceof LdcInsnNode marker
		    && marker.cst instanceof Long
		    && real.get(1).getOpcode() == Opcodes.POP2) {
			return new ArrayList<>(real.subList(2, real.size()));
		}
		return real;
	}

	private static LambdaAligner.SyntheticInfo findOldByKey(LambdaAligner.MatchContext ctx, String key) {
		for (int gi = ctx.oldGroups.nextEntry(-1); gi != -1; gi = ctx.oldGroups.nextEntry(gi)) {
			List<LambdaAligner.SyntheticInfo> g = ctx.oldGroups.valueAt(gi);
			if (g == null) continue;
			for (LambdaAligner.SyntheticInfo oi : g) {
				if ((oi.name + oi.desc).equals(key)) return oi;
			}
		}
		return null;
	}

	/**
	 * 反查：这个旧方法在本轮被配给了哪个新方法。
	 *
	 * <p>{@link #pair} 只写 {@code ni.matchedWith = oi}（新侧单向指针），旧侧的
	 * {@code matchedWith} 始终是 {@code null}，所以从旧找新必须扫新侧。</p>
	 */
	private static LambdaAligner.SyntheticInfo findNewForOld(LambdaAligner.MatchContext ctx, LambdaAligner.SyntheticInfo oi) {
		if (oi == null || !oi.matched) return null;
		for (int gi = ctx.newGroups.nextEntry(-1); gi != -1; gi = ctx.newGroups.nextEntry(gi)) {
			List<LambdaAligner.SyntheticInfo> g = ctx.newGroups.valueAt(gi);
			if (g == null) continue;
			for (LambdaAligner.SyntheticInfo ni : g) {
				if (ni.matchedWith == oi) return ni;
			}
		}
		return null;
	}

	/** 按 {@code name + desc} 精确查（{@code nameIndex} 按名字索引，同名异描述符时会取错）。 */
	static LambdaAligner.SyntheticInfo findByKey(LongObjectMap<List<LambdaAligner.SyntheticInfo>> groups, String name, String desc) {		for (int gi = groups.nextEntry(-1); gi != -1; gi = groups.nextEntry(gi)) {
			List<LambdaAligner.SyntheticInfo> g = groups.valueAt(gi);
			if (g == null) continue;
			for (LambdaAligner.SyntheticInfo i : g) {
				if (i.name.equals(name) && i.desc.equals(desc)) return i;
			}
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode cn, String name, String desc) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
		}
		return null;
	}

	/**
	 * 捕获桥被调用时进入（由注入的桥体字节码调用）。
	 *
	 * <p><b>刻意不查熔断器</b>：{@link #onOrphanInvoked} 在 {@code SMART_ADAPTIVE} 下先判
	 * {@code isOrphanBreakerTriggered()} 再抛 {@link NoSuchMethodError}，而桥按构造就
	 * <b>不是</b>死孤儿——它的目标方法存在、且被三道门证明过兼容。若复用那个入口，
	 * 挂在 UI / 定时器熔断器上的回调会在转发之前被直接炸断，桥等于白建。</p>
	 */
	public static void onBridgeInvoked(String location) {
		if (LOGGED_BRIDGES.add(location)) {
			RemoldAgent.info("[LambdaAligner] bridged lambda invoked: " + location
			                 + " (capture list shrank; forwarding to the new body)");
		}
	}

	private static final Set<String> LOGGED_BRIDGES =
	 Collections.newSetFromMap(new ConcurrentHashMap<>());

}
