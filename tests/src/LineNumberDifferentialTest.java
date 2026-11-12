import remold.LambdaAligner;
import remold.LambdaAligner.AlignmentEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于语句唯一身份（Statement ID）与行号表的随机序列差分属性测试（Property-Based Testing）。
 *
 * <p>核心契约与红线准则：
 * <ol>
 *   <li><b>多姿态多轮随机演进与细分场景硬配额</b>：
 *     覆盖 7 大场景分类（HOMOMORPHIC_PRUNED, TERNARY_NULL, LOCAL_CHAIN, CONSUMER_SHIFT_DELETE, HOST_SHIFT_DELETE, READD_CYCLIC, NESTED_TOPOLOGY），
 *     设立最低采样硬性指标，杜绝退化为平凡分布；</li>
 *   <li><b>四分类统计模型（拆分有证据与按约定）</b>：
 *     <ul>
 *       <li>【有证据的正确（Evidence-backed Correct）】：同名或同指纹由稳定证据（消费者/拓扑/指纹）正向锁定；</li>
 *       <li>【按约定的正确（Convention-fallback Correct）】：同构场景等按稳定约定保留旧名；</li>
 *       <li>【错配且有信号（Signaled Mismatch）】：信号按语句与方法名精确归因，杜绝同轮搭便车；</li>
 *       <li>【静默错配（Silent Mismatch）】：<b>红线 1：严格必须为 0</b>；</li>
 *       <li>【身份丢失（Identity Loss）】：无歧义留存语句，<b>红线 2：严格必须为 0（召回率 100%）</b>。</li>
 *     </ul>
 *   </li>
 *   <li><b>旧名字方向检视与幽灵防冲突不变量</b>：
 *     旧方法单向退休为幽灵，新分配的 fresh 名字绝不与任何历史幽灵方法重合；</li>
 *   <li><b>嵌套父子独立角色校验</b>：
 *     独立跟踪 ROLE_PARENT 与 ROLE_LEAF，防止父叶互换盲区；</li>
 *   <li><b>失败缩小（Delta Debugging）</b>：
 *     失败时提供动作序列与定位信息；</li>
 *   <li><b>系统化变异击杀率</b>：
 *     自动化回归验证 6 组设计变异（A, B, D, 1, 4, C）的击杀率。</li>
 * </ol>
 * </p>
 */
public class LineNumberDifferentialTest {

	/**
	 * 契约要求"全种子 100% 击杀"的<b>活动变异体</b>。
	 *
	 * <p>旧契约是 {@code {A, B, D}}；D 经证据（{@code ev-cf3a} 关门禁 5/5、{@code ev-1a5e} 开门禁 0/12）
	 * 判定为<b>门禁屏蔽变异体</b>后移入 {@link #GATE_MASKED_MUTANTS}，阈值与种子集<b>未</b>改动。</p>
	 */
	static final Set<String> ACTIVE_MUTANTS_FULL_KILL = Set.of("A", "B");

	/**
	 * <b>门禁屏蔽集（gate-masked mutants）</b>：被 {@code bodyFingerprint} 门禁短路、门禁开启下不可击杀的
	 * 变异体。必须<b>恰为</b> {@code {D}}——它是对"后人再塞一个杀不掉的变异体进屏蔽集"的结构性哨兵，
	 * 不是"允许 0 击杀"的免罪符（见 {@link #mutationKillRateRegressionTest} 的行为哨兵）。
	 */
	static final Set<String> GATE_MASKED_MUTANTS = Set.of("D");

	/** 只打印读数、不参与判定力的观测项（旧契约亦未对其设断言，本轮不新增也不下调）。 */
	static final Set<String> OBSERVED_ONLY_MUTANTS = Set.of("1", "4", "C");

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.ACTIVE_MUTATION = "";
		LambdaAligner.clearLoggedWarnings();
	}

	enum StmtKind {
		SIMPLE,            // consumeA(() -> leafA())
		HOMOMORPHIC,       // consumeB(() -> leafShared()) 与其它语句同体
		TERNARY_NULL,      // consumeA(flag ? () -> leafA() : null) [消费者分析 Fail-Closed 置空]
		LOCAL_CHAIN,       // Runnable r = () -> leafA(); consumeA(r); [局部变量中转]
		NESTED_PARENT,     // consumeA(() -> sub(() -> leafA()))
		CONSUMER_DIFF      // consumeC(() -> leafA())
	}

	enum ScenarioCategory {
		HOMOMORPHIC_PRUNED,     // 同构缩减
		TERNARY_NULL,           // 三元 null 消费者
		LOCAL_CHAIN,            // 局部变量中转
		CONSUMER_SHIFT_DELETE,  // 改消费者加删前序
		HOST_SHIFT_DELETE,      // 改父体加删前序
		READD_CYCLIC,           // 反复删加
		NESTED_TOPOLOGY         // 嵌套拓扑
	}

	static class StmtSpec {
		final int id;
		final StmtKind kind;
		final int bodyId;     // 决定方法体内容，bodyId 相同则字节码体相同
		int consumerId;       // 决定调用者，0=consumeA, 1=consumeB, 2=consumeC

		StmtSpec(int id, StmtKind kind, int bodyId, int consumerId) {
			this.id = id;
			this.kind = kind;
			this.bodyId = bodyId;
			this.consumerId = consumerId;
		}

		StmtSpec copy() {
			return new StmtSpec(id, kind, bodyId, consumerId);
		}

		String toJavaLine() {
			String c = consumerId == 0 ? "consumeA" : (consumerId == 1 ? "consumeB" : "consumeC");
			switch (kind) {
				case SIMPLE:
					return "        " + c + "(() -> leaf" + bodyId + "()); // ID_" + id;
				case HOMOMORPHIC:
					return "        " + c + "(() -> leafShared()); // ID_" + id;
				case TERNARY_NULL:
					return "        " + c + "(flag ? () -> leaf" + bodyId + "() : null); // ID_" + id;
				case LOCAL_CHAIN:
					return "        { Runnable r" + id + " = () -> leaf" + bodyId + "(); " + c + "(r" + id + "); } // ID_" + id;
				case NESTED_PARENT:
					return "        " + c + "(() -> sub(() -> leaf" + bodyId + "())); // ID_" + id;
				case CONSUMER_DIFF:
					return "        " + c + "(() -> leaf" + bodyId + "()); // ID_" + id;
				default:
					throw new IllegalStateException();
			}
		}
	}

	/** 编译产物与其外部真值元数据。 */
	static class CompiledUnit {
		final byte[] bytes;
		final String source;
		final Map<Integer, StmtSpec> lineToStmt;
		final Map<String, Integer> methodToStmtId;
		final Map<Integer, String> stmtIdToLeafMethod;
		final Map<Integer, String> stmtIdToParentMethod;
		final Map<String, String> methodToRole; // "ROLE_LEAF" or "ROLE_PARENT"

		CompiledUnit(byte[] bytes, String source, Map<Integer, StmtSpec> lineToStmt,
		             Map<String, Integer> methodToStmtId,
		             Map<Integer, String> stmtIdToLeafMethod,
		             Map<Integer, String> stmtIdToParentMethod,
		             Map<String, String> methodToRole) {
			this.bytes = bytes;
			this.source = source;
			this.lineToStmt = lineToStmt;
			this.methodToStmtId = methodToStmtId;
			this.stmtIdToLeafMethod = stmtIdToLeafMethod;
			this.stmtIdToParentMethod = stmtIdToParentMethod;
			this.methodToRole = methodToRole;
		}
	}

	static class ScenarioStats {
		final Map<ScenarioCategory, Integer> categorySamples = new EnumMap<>(ScenarioCategory.class);
		int evidenceCorrect = 0;
		int conventionCorrect = 0;
		int totalSignaled = 0;
		int totalSilent = 0;
		int totalIdentityLoss = 0;
		int totalUnambiguousTested = 0;

		void recordCategory(ScenarioCategory cat) {
			categorySamples.merge(cat, 1, Integer::sum);
		}
	}

	@Test
	void propertyBasedMultiRoundDifferentialTest(@TempDir Path tmp) throws Exception {
		String javac = javac21();

		String seedsProp = System.getProperty("property.seeds");
		long[] seeds;
		if (seedsProp != null && !seedsProp.isEmpty()) {
			String[] parts = seedsProp.split(",");
			seeds = new long[parts.length];
			for (int i = 0; i < parts.length; i++) seeds[i] = Long.parseLong(parts[i].trim());
		} else {
			seeds = new long[]{42L, 101L, 2024L, 9999L, 12345L};
		}

		int roundsPerSeed = Integer.getInteger("property.rounds", 8);
		ScenarioStats stats = new ScenarioStats();

		for (long seed : seeds) {
			runPropertyTestForSeed(tmp, javac, seed, roundsPerSeed, stats);
		}

		System.out.println("[PropertyTest] 完成多种子多轮次属性验证:\n"
			+ "  有证据正确=" + stats.evidenceCorrect + ", 约定正确=" + stats.conventionCorrect
			+ ", 显式告警/熔断=" + stats.totalSignaled + ", 身份丢失=" + stats.totalIdentityLoss
			+ ", 静默错配=" + stats.totalSilent + ", 无歧义留存样本=" + stats.totalUnambiguousTested + "\n"
			+ "  细分场景分布=" + stats.categorySamples);

		// 硬性最低配额断言（Hard Quota）
		for (ScenarioCategory cat : ScenarioCategory.values()) {
			int count = stats.categorySamples.getOrDefault(cat, 0);
			assertTrue(count >= 5, "场景 [" + cat + "] 样本数 (" + count + ") 低于硬性指标 5，生成器存在分布漂移！");
		}

		assertEquals(0, stats.totalSilent, "红线 1：静默错配数必须严格为 0！");
		assertEquals(0, stats.totalIdentityLoss, "红线 2：无歧义留存语句身份丢失数必须严格为 0（召回率 100%）！");
		assertTrue(stats.evidenceCorrect > 0, "必须存在有证据的正确配对");
		assertTrue(stats.totalUnambiguousTested > 0, "必须有效测试到无歧义留存样本");
	}

	private static void runPropertyTestForSeed(Path tmp, String javac, long seed, int roundsPerSeed, ScenarioStats stats) throws Exception {
		Random rng = new Random(seed);
		List<String> actionHistory = new ArrayList<>();
		List<StmtSpec> deletedPool = new ArrayList<>();
		Set<String> accumulatedGhosts = new HashSet<>();

		// 初始语句集 V0: 包含各类样本
		List<StmtSpec> currentStmts = new ArrayList<>();
		int nextId = 1;
		currentStmts.add(new StmtSpec(nextId++, StmtKind.SIMPLE, 1, 0));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.HOMOMORPHIC, 99, 0));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.HOMOMORPHIC, 99, 1));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.TERNARY_NULL, 2, 0));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.LOCAL_CHAIN, 3, 1));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.NESTED_PARENT, 4, 2));
		currentStmts.add(new StmtSpec(nextId++, StmtKind.CONSUMER_DIFF, 5, 1));

		boolean hostFlag = true;
		CompiledUnit prevUnit = compileUnit(tmp, javac, "s" + seed + "_v0", currentStmts, hostFlag);

		for (int round = 1; round <= roundsPerSeed; round++) {
			List<StmtSpec> nextStmts = new ArrayList<>();
			for (StmtSpec s : currentStmts) {
				nextStmts.add(s.copy());
			}

			List<String> roundActions = new ArrayList<>();

			// 调度保证：前几轮针对性注入典型难场景，防止完全随机退化
			if (round == 1) {
				// 轮次 1：同构缩减（删除一个 HOMOMORPHIC）
				int homIdx = findFirstStmtIndex(nextStmts, StmtKind.HOMOMORPHIC);
				if (homIdx != -1) {
					StmtSpec removed = nextStmts.remove(homIdx);
					deletedPool.add(removed);
					roundActions.add("FORCE_HOMOMORPHIC_PRUNE ID_" + removed.id);
					stats.recordCategory(ScenarioCategory.HOMOMORPHIC_PRUNED);
				}
			} else if (round == 2) {
				// 轮次 2：改消费者加删前序（针对消费者锚定）
				if (nextStmts.size() > 2) {
					StmtSpec removed = nextStmts.remove(0); // 删前序
					deletedPool.add(removed);
					StmtSpec target = nextStmts.get(0);
					target.consumerId = (target.consumerId + 1) % 3;
					roundActions.add("CONSUMER_SHIFT_DELETE: DEL ID_" + removed.id + ", EDIT_CONSUMER ID_" + target.id);
					stats.recordCategory(ScenarioCategory.CONSUMER_SHIFT_DELETE);
				}
			} else if (round == 3) {
				// 轮次 3：改父体加删前序
				if (nextStmts.size() > 2) {
					StmtSpec removed = nextStmts.remove(0);
					deletedPool.add(removed);
					hostFlag = !hostFlag;
					roundActions.add("HOST_SHIFT_DELETE: DEL ID_" + removed.id + ", TOGGLE_HOST_FLAG=" + hostFlag);
					stats.recordCategory(ScenarioCategory.HOST_SHIFT_DELETE);
				}
			} else if (round == 4 && !deletedPool.isEmpty()) {
				// 轮次 4：反复删加
				StmtSpec readded = deletedPool.remove(0).copy();
				nextStmts.add(0, readded);
				roundActions.add("READD_CYCLIC ID_" + readded.id);
				stats.recordCategory(ScenarioCategory.READD_CYCLIC);
			}

			// 统计各类型采样
			for (StmtSpec s : nextStmts) {
				if (s.kind == StmtKind.TERNARY_NULL) stats.recordCategory(ScenarioCategory.TERNARY_NULL);
				if (s.kind == StmtKind.LOCAL_CHAIN) stats.recordCategory(ScenarioCategory.LOCAL_CHAIN);
				if (s.kind == StmtKind.NESTED_PARENT) stats.recordCategory(ScenarioCategory.NESTED_TOPOLOGY);
			}

			// 随机变异（仅在第 4 轮后执行，保证前 4 轮严格按照针对性注入场景执行，防止随机扰动覆盖变异靶点）
			if (round > 4) {
				if (!deletedPool.isEmpty() && rng.nextDouble() < 0.35) {
					int readdIdx = rng.nextInt(deletedPool.size());
					StmtSpec readdStmt = deletedPool.remove(readdIdx).copy();
					int insertPos = rng.nextInt(nextStmts.size() + 1);
					nextStmts.add(insertPos, readdStmt);
					roundActions.add("READD ID_" + readdStmt.id + " at pos " + insertPos);
					stats.recordCategory(ScenarioCategory.READD_CYCLIC);
				}

				if (nextStmts.size() > 4 && rng.nextDouble() < 0.40) {
					int removeIdx = rng.nextInt(nextStmts.size());
					StmtSpec removed = nextStmts.remove(removeIdx);
					deletedPool.add(removed);
					roundActions.add("DELETE ID_" + removed.id);
					if (removed.kind == StmtKind.HOMOMORPHIC) {
						stats.recordCategory(ScenarioCategory.HOMOMORPHIC_PRUNED);
					}
				}

				if (rng.nextDouble() < 0.25) {
					hostFlag = !hostFlag;
					roundActions.add("TOGGLE_HOST_FLAG=" + hostFlag);
					stats.recordCategory(ScenarioCategory.HOST_SHIFT_DELETE);
				}

				if (!nextStmts.isEmpty() && rng.nextDouble() < 0.35) {
					StmtSpec target = nextStmts.get(rng.nextInt(nextStmts.size()));
					int oldC = target.consumerId;
					target.consumerId = (target.consumerId + 1) % 3;
					roundActions.add("EDIT_CONSUMER ID_" + target.id + " (" + oldC + "->" + target.consumerId + ")");
					stats.recordCategory(ScenarioCategory.CONSUMER_SHIFT_DELETE);
				}

				if (rng.nextDouble() < 0.30) {
					Collections.shuffle(nextStmts, rng);
					roundActions.add("SHUFFLE");
				}

				if (nextStmts.size() < 12 && rng.nextDouble() < 0.45) {
					StmtKind k = StmtKind.values()[rng.nextInt(StmtKind.values().length)];
					int bId = (k == StmtKind.HOMOMORPHIC) ? 99 : (rng.nextInt(15) + 10);
					StmtSpec added = new StmtSpec(nextId++, k, bId, rng.nextInt(3));
					nextStmts.add(added);
					roundActions.add("ADD ID_" + added.id + " (" + k + " body=" + bId + " c=" + added.consumerId + ")");
				}
			}

			actionHistory.add("Round " + round + ": " + roundActions);

			CompiledUnit nextUnit = compileUnit(tmp, javac, "s" + seed + "_v" + round, nextStmts, hostFlag);

			LambdaAligner.clearLoggedWarnings();

			byte[] aligned;
			try {
				aligned = LambdaAligner.align(prevUnit.bytes, nextUnit.bytes);
			} catch (Throwable t) {
				fail("[PropertyTest FAIL] Seed=" + seed + ", Round=" + round + " 对齐抛出异常: " + t.getMessage()
					+ "\nAction History:\n" + String.join("\n", actionHistory), t);
				return;
			}
			assertNotNull(aligned, "Round " + round + " 对齐产物不能为 null");

			ClassNode cnAligned = readClass(aligned);
			CompiledUnit alignedUnit = extractCompiledUnit(aligned, nextUnit.source, nextUnit.lineToStmt);

			Map<Integer, StmtSpec> prevStmtsById = toIdMap(currentStmts);
			Map<Integer, StmtSpec> nextStmtsById = toIdMap(nextStmts);

			// 收集事件（防御性拷贝）
			List<AlignmentEvent> roundEvents = new ArrayList<>(LambdaAligner.TEST_EVENTS);

			// === 核心检验 1：存活语句的身份与四分类判定 ===
			for (StmtSpec curr : nextStmts) {
				StmtSpec prevSpec = prevStmtsById.get(curr.id);
				if (prevSpec == null) continue; // 本轮新增，不参与旧身份留存检验

				String oldLeafName = prevUnit.stmtIdToLeafMethod.get(curr.id);
				String activeLeafName = alignedUnit.stmtIdToLeafMethod.get(curr.id);

				MethodNode activeMn = findMethod(cnAligned, activeLeafName);
				MethodNode oldMn = findMethod(cnAligned, oldLeafName);

				boolean activeIsGhost = callsMethod(activeMn, "onOrphanInvoked");
				boolean oldIsGhost = callsMethod(oldMn, "onOrphanInvoked");
				boolean bodyMatches = callsExpectedLeaf(activeMn, curr);
				boolean retainedOldName = oldLeafName != null && oldLeafName.equals(activeLeafName);

				// 嵌套拓扑的角色完整性校验（防父叶互换盲区）
				if (curr.kind == StmtKind.NESTED_PARENT) {
					String oldParentName = prevUnit.stmtIdToParentMethod.get(curr.id);
					String activeParentName = alignedUnit.stmtIdToParentMethod.get(curr.id);
					String oldParentRole = prevUnit.methodToRole.get(oldParentName);
					String activeParentRole = alignedUnit.methodToRole.get(activeParentName);
					String oldLeafRole = prevUnit.methodToRole.get(oldLeafName);
					String activeLeafRole = alignedUnit.methodToRole.get(activeLeafName);
					assertEquals("ROLE_PARENT", oldParentRole);
					assertEquals("ROLE_PARENT", activeParentRole);
					assertEquals("ROLE_LEAF", oldLeafRole);
					assertEquals("ROLE_LEAF", activeLeafRole);
				}

				// 判定无歧义留存子集（召回率硬性下限：方法体全局互不相同、消费者未变且全局唯一、非三元与同构）：
				boolean isUnambiguous = isUnambiguous(currentStmts, nextStmts, curr, prevSpec);

				boolean oldMatchesBody = callsExpectedLeaf(oldMn, curr);
				boolean homomorphicTwinExchange = !isUnambiguous && !retainedOldName && bodyMatches && oldMatchesBody
					&& !activeIsGhost && !oldIsGhost
					&& (hasHomomorphicBody(currentStmts, curr) || hasHomomorphicBody(nextStmts, curr));

				if ((retainedOldName && bodyMatches && !activeIsGhost) || homomorphicTwinExchange) {
					if (isUnambiguous) {
						stats.evidenceCorrect++;
						stats.totalUnambiguousTested++;
					} else {
						stats.conventionCorrect++;
					}
				} else {
					// 未能正确保留旧名字身份
					if (isUnambiguous) {
						stats.totalIdentityLoss++;
						fail("[PropertyTest FAIL] 身份丢失（Identity Loss）！\n"
							+ "Seed=" + seed + ", Round=" + round + ", 语句 ID=" + curr.id + "\n"
							+ "旧叶子方法=" + oldLeafName + ", 新映射方法=" + activeLeafName + "\n"
							+ "oldIsGhost=" + oldIsGhost + ", activeIsGhost=" + activeIsGhost + "\n"
							+ "Action History:\n" + String.join("\n", actionHistory));
					} else {
						// 精确信号归因：检查是否存在涉及该方法的事件或幽灵熔断
						boolean methodSignaled = roundEvents.stream().anyMatch(ev -> ev.involves(oldLeafName) || ev.involves(activeLeafName))
							|| activeIsGhost || oldIsGhost
							|| (roundEvents.stream().anyMatch(ev -> "HOMOMORPHIC_PRUNING".equals(ev.kind)) && curr.kind == StmtKind.HOMOMORPHIC);

						if (methodSignaled) {
							stats.totalSignaled++;
						} else {
							stats.totalSilent++;
							fail("[PropertyTest FAIL] 静默错配（Silent Mismatch）！\n"
								+ "Seed=" + seed + ", Round=" + round + ", 语句 ID=" + curr.id + "\n"
								+ "旧叶子方法=" + oldLeafName + ", 新映射方法=" + activeLeafName + "\n"
								+ "既未正确对齐，又无针对该方法的告警或熔断事件！\n"
								+ "Action History:\n" + String.join("\n", actionHistory));
						}
					}
				}
			}

			// === 核心检验 2：旧名字方向检验（遍历旧类所有旧方法，检视新类去向） ===
			for (Map.Entry<String, Integer> entry : prevUnit.methodToStmtId.entrySet()) {
				String oldMethodName = entry.getKey();
				int oldStmtId = entry.getValue();
				MethodNode mnInAligned = findMethod(cnAligned, oldMethodName);
				assertNotNull(mnInAligned, "旧方法 " + oldMethodName + " 必须在新产物中存在（活跃或幽灵）");

				boolean isGhost = callsMethod(mnInAligned, "onOrphanInvoked");

				// 幽灵单向退休不变量：上一轮已是幽灵的方法，新产物中必须永远保持为幽灵，绝不许被复活
				ClassNode cnPrev = readClass(prevUnit.bytes);
				MethodNode prevMn = findMethod(cnPrev, oldMethodName);
				boolean wasGhostInPrev = prevMn != null && callsMethod(prevMn, "onOrphanInvoked");
				if (wasGhostInPrev) {
					assertTrue(isGhost, "[PropertyTest FAIL] 幽灵单向退休不变量被破坏！旧幽灵方法 " + oldMethodName
						+ " 在本轮产物中被违规复活！\nAction History:\n" + String.join("\n", actionHistory));
				}

				if (!nextStmtsById.containsKey(oldStmtId)) {
					// 该语句在本轮被删除：必须安全退役为幽灵桩，或被带有针对性信号的对齐复用
					if (!isGhost) {
						Integer claimerId = alignedUnit.methodToStmtId.get(oldMethodName);
						boolean signaled = roundEvents.stream().anyMatch(ev -> ev.involves(oldMethodName))
							|| (roundEvents.stream().anyMatch(ev -> "HOMOMORPHIC_PRUNING".equals(ev.kind)));
						if (!signaled) {
							stats.totalSilent++;
							fail("[PropertyTest FAIL] 被删除语句旧名被静默篡夺！\n"
								+ "Seed=" + seed + ", Round=" + round + ", 被删语句 ID=" + oldStmtId + ", 旧名=" + oldMethodName + "\n"
								+ "被语句 ID=" + claimerId + " 认领但无任何针对该方法的告警信号！\n"
								+ "Action History:\n" + String.join("\n", actionHistory));
						}
					}
				}
			}

			// === 核心检验 3：幽灵防冲突不变量（新生成的 fresh name 绝不得与历史幽灵名字重合） ===
			for (String gName : accumulatedGhosts) {
				MethodNode mnGhost = findMethod(cnAligned, gName);
				assertNotNull(mnGhost, "Round " + round + " 幽灵方法 " + gName + " 必须在新产物中保留");
				assertTrue(callsMethod(mnGhost, "onOrphanInvoked"),
					"[PropertyTest FAIL] 幽灵单向退休不变量被破坏！已退役幽灵方法 " + gName + " 在 Round " + round + " 被违规复活！\n"
					+ "Action History:\n" + String.join("\n", actionHistory));
				assertFalse(alignedUnit.methodToStmtId.containsKey(gName),
					"[PropertyTest FAIL] 历史幽灵方法 " + gName + " 被活语句认领（fresh name 冲突）！\n"
					+ "Action History:\n" + String.join("\n", actionHistory));
			}

			for (MethodNode mn : cnAligned.methods) {
				if (callsMethod(mn, "onOrphanInvoked")) {
					accumulatedGhosts.add(mn.name);
				}
			}

			// 推进基准
			prevUnit = alignedUnit;
			currentStmts = nextStmts;
		}
	}

	/**
	 * 系统化变异击杀率（<b>契约（批次 I 第二轮之后）：活动集 100%，门禁屏蔽集为 {@code {D}} + 哨兵</b>）。
	 *
	 * <p><b>为什么 D 不再要求 100%</b>：{@code D} 是"允许幽灵参与后续匹配"的变异
	 * （{@code LambdaAligner} 里的 {@code !isMutationActive("D") && oi.ghost}）。批次 I 把兜底配对改成
	 * fail-closed（{@link LambdaAligner#fallbackPairingAllowed}）之后，Pass B 在配对<b>之前</b>就拒绝
	 * 方法体不可证明等价的一对，{@code D} 想触发的"幽灵被错误认领"永远走不到。实测：门禁关闭
	 * （{@code ev-cf3a}，两处 Pass B 调用点短路）时 {@code D} 立刻回到 5/5 击杀，说明它<b>不是语义等价</b>；
	 * 门禁开启时 5 种子 {@code 0/5}（{@code ev-ba57}）、扩到 12 种子仍 {@code 0/12}（{@code ev-1a5e}）。
	 * 因此它是<b>门禁屏蔽变异体（gate-masked mutant）</b>，不是"不可达/等价变异体"。
	 * </p>
	 *
	 * <p>本用例对此给出<b>两侧契约</b>，任何一侧变化都会变红：</p>
	 * <ol>
	 *   <li><b>活动集 100%</b>：{@link #ACTIVE_MUTANTS_FULL_KILL}（{@code A}、{@code B}）必须在<b>全部种子</b>
	 *       下被击杀。种子集与阈值均未改动（5 种子 × 4 轮，旧契约就是这 5 个种子）。</li>
	 *   <li><b>屏蔽集恰为 {@code {D}}</b>：{@link #GATE_MASKED_MUTANTS} 必须与字面量 {@code {D}} 相等，
	 *       且与活动集不相交。后人要往屏蔽集里"偷偷加一个杀不掉的变异体"，这里直接红。</li>
	 *   <li><b>行为哨兵</b>：{@code D} 仍在门禁开启下<b>实跑</b>，击杀数必须 {@code == 0}。
	 *       失败信息写明"D 已可被击杀，说明门禁已变化，请将 D 移回击杀集"——
	 *       这不是把 {@code 0/N} 当合格，而是"门禁形态变了"的第二道报警。</li>
	 * </ol>
	 *
	 * <p><b>恢复条件</b>：只要 {@code BODY_MISMATCH} 门禁被移除 / 放宽 / 改回"按位置配对"
	 * （任何让 {@code fallbackPairingAllowed} 不再拒绝"体不可证明等价"的改动），哨兵立刻变红 ——
	 * 此时把 {@code "D"} 从 {@link #GATE_MASKED_MUTANTS} 移回 {@link #ACTIVE_MUTANTS_FULL_KILL}，
	 * 并删掉下面那条 {@code == 0} 断言即可恢复旧契约。</p>
	 *
	 * <p>观测项（{@code 1}、{@code 4}、{@code C}）保持旧契约的判定力不变：它们此前也没有 100% 断言，
	 * 只打印读数，本轮不新增也不下调。</p>
	 */
	@Test
	void mutationKillRateRegressionTest(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		long[] testSeeds = new long[]{42L, 101L, 2024L, 9999L, 12345L};
		int roundsPerSeed = 4;
		String[] mutations = new String[]{"A", "B", "D", "1", "4", "C"};
		Map<String, Integer> killCounts = new LinkedHashMap<>();

		for (String m : mutations) {
			int kills = 0;
			for (long seed : testSeeds) {
				LambdaAligner.ACTIVE_MUTATION = m;
				try {
					ScenarioStats stats = new ScenarioStats();
					runPropertyTestForSeed(tmp, javac, seed, roundsPerSeed, stats);
					// 若无异常，但静默错配或身份丢失发生（虽通常会在内部 fail，此兜底）
					if (stats.totalSilent > 0 || stats.totalIdentityLoss > 0) {
						kills++;
					}
				} catch (Throwable t) {
					// 变异被属性测试红线击杀！
					kills++;
				} finally {
					LambdaAligner.ACTIVE_MUTATION = "";
				}
			}
			killCounts.put(m, kills);
		}

		System.out.println("[MutationReport] 变异击杀率（" + testSeeds.length + " 种子测试）:\n"
			+ "  Mutation A (屏蔽 alignByHostSequence): " + killCounts.get("A") + "/" + testSeeds.length + "\n"
			+ "  Mutation B (去掉 canPair 消费者否决): " + killCounts.get("B") + "/" + testSeeds.length + "\n"
			+ "  Mutation D (允许幽灵参与后续匹配) [门禁屏蔽集，期望 0]: " + killCounts.get("D") + "/" + testSeeds.length + "\n"
			+ "  Mutation 1 (hostKey 剔除消费者特征): " + killCounts.get("1") + "/" + testSeeds.length + " [观测项]\n"
			+ "  Mutation 4 (findConsumers 恒空): " + killCounts.get("4") + "/" + testSeeds.length + " [观测项]\n"
			+ "  Mutation C (决胜键改回 methodIndex): " + killCounts.get("C") + "/" + testSeeds.length + " [观测项]");

		// 1) 活动集：全种子 100%（阈值与种子集均未改动）。
		for (String m : ACTIVE_MUTANTS_FULL_KILL) {
			assertTrue(killCounts.containsKey(m), "活动变异体 " + m + " 必须真的被跑过（不允许静默跳过）");
			assertEquals(testSeeds.length, (int) killCounts.get(m),
				"Mutation " + m + " 必须在所有测试种子下被击杀！");
		}

		// 2) 结构哨兵：屏蔽集恰为 {D}，且与活动集不相交、每个变异体都被显式分类。
		assertEquals(Set.of("D"), GATE_MASKED_MUTANTS,
			"门禁屏蔽集必须恰为 {D}：只有 D 被 bodyFingerprint 门禁证明为门禁屏蔽变异体。"
				+ "往里加新成员需要先给出'关禁可击杀 / 开禁 0/N'两条证据。");
		assertTrue(Collections.disjoint(GATE_MASKED_MUTANTS, ACTIVE_MUTANTS_FULL_KILL),
			"同一变异体不能既在活动集又在屏蔽集: masked=" + GATE_MASKED_MUTANTS
				+ ", active=" + ACTIVE_MUTANTS_FULL_KILL);
		Set<String> classified = new HashSet<>(ACTIVE_MUTANTS_FULL_KILL);
		classified.addAll(GATE_MASKED_MUTANTS);
		classified.addAll(OBSERVED_ONLY_MUTANTS);
		assertEquals(new HashSet<>(Arrays.asList(mutations)), classified,
			"每个变异体都必须被显式分类（活动集 / 屏蔽集 / 观测项），不允许漏判: "
				+ "classified=" + classified + ", mutations=" + Arrays.toString(mutations));

		// 3) 行为哨兵：D 在门禁开启下继续实跑，击杀数必须 == 0（门禁形态变了就报警，不是默默接受 0/N）。
		int dKills = killCounts.get("D");
		assertEquals(0, dKills,
			"D 已可被击杀，说明门禁已变化，请将 D 移回击杀集（实际击杀 " + dKills + "/" + testSeeds.length
				+ "；证据见 ev-cf3a 关门禁 5/5、ev-1a5e 开门禁 0/12）");
	}

	//region 门禁精确防线：旧侧 hostKey == null（hostKind "?"）一票否决

	/**
	 * <b>门禁的宿主形态一票否决（精确防线）</b>：旧侧 lambda 取不到宿主
	 * （{@code hostKey == null} ⇒ {@code hostKind} 返回 {@code "?"}）时，<b>"同名"不得成为放行证据</b>。
	 *
	 * <p><b>形态</b>（合成夹具，专为钉住这条防线）：v1 里 {@code lambda$execute$0} 的方法体是
	 * {@code act1()}，但把宿主方法 {@code execute()} 的方法体清空后，它的 callsite
	 * （{@code invokedynamic}）就不在了 ⇒ 它成了"无任何引用"的孤立合成方法，扫描器不会给它
	 * 分配宿主 ⇒ {@code hostKey == null}。v2 里<b>同名</b>的 {@code lambda$execute$0} 挂在普通方法
	 * {@code execute()} 下（{@code hostKey = "M:execute()V"}），方法体是 {@code act2()}。
	 * 名字相同、嵌套层级相同、方法体不等价。</p>
	 *
	 * <p><b>契约</b>：宿主形态一变（{@code ?} vs {@code M}）就必须在"同名放行"之前一票否决——
	 * 记 {@code BODY_MISMATCH}、旧名幽灵化（老 callsite 熔断）、绝不让 {@code act2} 的体顶替旧名。</p>
	 *
	 * <p><b>为什么需要这条用例</b>：门禁里"同名 ⇒ 放行"（{@code ni.name.equals(oi.name)}）是刻意保留的
	 * 原地更新捷径，它会把"取不到宿主"的候选一并放行，除非宿主形态检查先否决。变异"只在旧侧
	 * {@code hostKey} 非 null 时才否决"（即只拿掉这条一票否决）在既有全量用例下<b>存活</b>
	 * （观测 {@code ev-5c1c}：60 次 null-host 命中全落在属性测试里，断言区分不出），故补此具名用例。</p>
	 */
	@Test
	void nullOldHostVetoedBeforeSameNameShortCircuit(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		String head = "package test;\npublic class NullHostCase {\n"
			+ "    static void consumeA(Runnable r) { if (r != null) r.run(); }\n"
			+ "    static void act1() { }\n"
			+ "    static void act2() { }\n";
		String v1 = head + "    public void execute() { consumeA(() -> act1()); }\n}\n";
		String v2 = head + "    public void execute() { consumeA(() -> act2()); }\n}\n";

		byte[] v1Bytes = compileSource(tmp, javac, "nh_v1", v1);
		byte[] v2Bytes = compileSource(tmp, javac, "nh_v2", v2);

		// 夹具前提 1：v1 有 lambda$execute$0，且体内调 act1。
		ClassNode cnV1 = readClass(v1Bytes);
		MethodNode oldLambda = findMethod(cnV1, "lambda$execute$0");
		assertNotNull(oldLambda, "夹具前提：v1 必须有合成方法 lambda$execute$0（实际方法表: " + names(cnV1) + "）");
		assertTrue(callsMethod(oldLambda, "act1"), "夹具前提 1：v1 的旧 lambda 体必须调 act1");

		// 夹具前提 2：抹掉 callsite 后，旧 lambda 仍是孤立合成方法（类内无人引用它 ⇒ 无宿主）。
		byte[] oldBytes = stripMethodBody(v1Bytes, "execute");
		ClassNode cnOld = readClass(oldBytes);
		assertTrue(callsMethod(findMethod(cnOld, "lambda$execute$0"), "act1"),
			"夹具前提 2：抹 callsite 不得连带删掉旧 lambda 本体");
		assertFalse(hasAnyReferenceTo(cnOld, "lambda$execute$0"),
			"夹具前提 2：旧 lambda 必须成为'无任何引用'的孤立方法（否则 hostKey 不会是 null，本用例就测不到这条防线）");

		// 夹具前提 3：v2 的同名 lambda 体调 act2（与旧体不同）。
		ClassNode cnV2 = readClass(v2Bytes);
		MethodNode newLambda = findMethod(cnV2, "lambda$execute$0");
		assertNotNull(newLambda, "夹具前提 3：v2 必须有同名合成方法 lambda$execute$0（实际方法表: " + names(cnV2) + "）");
		assertTrue(callsMethod(newLambda, "act2"), "夹具前提 3：v2 的同名 lambda 体必须调 act2（体确实不同）");

		LambdaAligner.LAST_RESULT = null;
		LambdaAligner.TEST_EVENTS.clear();
		LambdaAligner.clearLoggedWarnings();
		byte[] product = LambdaAligner.align(oldBytes, v2Bytes);
		assertNotNull(product, "对齐产物非空");
		ClassNode out = readClass(product);

		// 1) 终局：旧名必须幽灵化（老 callsite 熔断），而不是被 act2 的体认领。
		MethodNode slot = findMethod(out, "lambda$execute$0");
		assertNotNull(slot, "旧名 lambda$execute$0 必须留在产物里（活跃或幽灵，实际方法表: " + names(out) + "）");
		assertTrue(callsMethod(slot, "onOrphanInvoked"),
			"旧侧 hostKey==null 且方法体不等价时必须一票否决：旧名要幽灵化，实际 " + describe(slot));
		assertFalse(callsMethod(slot, "act2"),
			"绝不能让 act2 的方法体顶替旧名（同名短路必须被宿主形态否决拦住）");

		// 2) 留痕：必须点名旧名打 BODY_MISMATCH。
		List<String> mismatched = new ArrayList<>();
		for (AlignmentEvent ev : LambdaAligner.TEST_EVENTS) {
			if ("BODY_MISMATCH".equals(ev.kind)) mismatched.add(ev.oldName + "<-" + ev.newName);
		}
		assertTrue(mismatched.contains("lambda$execute$0<-lambda$execute$0"),
			"拒绝配对必须点名 lambda$execute$0（实际 BODY_MISMATCH: " + mismatched + "）");
		assertTrue(LambdaAligner.LAST_RESULT.stats.bodyMismatchRefusals > 0,
			"拒绝必须计数到 bodyMismatchRefusals: " + LambdaAligner.LAST_RESULT.stats);

		// 3) 本形态不该从 Pass A "按位置"漏过去（旧侧无宿主 ⇒ 深度未知）。
		assertEquals(0, LambdaAligner.LAST_RESULT.stats.passAPairs,
			"本形态不该走 Pass A: " + LambdaAligner.LAST_RESULT.stats);
	}

	/** 写单源文件、编到独立输出目录，返回 {@code test/NullHostCase.class} 字节。 */
	private static byte[] compileSource(Path tmp, String javac, String tag, String src) throws Exception {
		Path srcDir = Files.createDirectories(tmp.resolve("src_" + tag));
		Path outDir = Files.createDirectories(tmp.resolve("out_" + tag));
		Path srcFile = srcDir.resolve("NullHostCase.java");
		Files.writeString(srcFile, src, StandardCharsets.UTF_8);
		runCmd(javac, "-g", "-nowarn", "-encoding", "UTF-8", "-d", outDir.toString(), srcFile.toString());
		return Files.readAllBytes(outDir.resolve("test/NullHostCase.class"));
	}

	/** 把指定方法的方法体替换成单条 {@code RETURN}（连带清空 frames / LVT），其余方法原样保留。 */
	private static byte[] stripMethodBody(byte[] bytes, String methodName) {
		ClassNode cn = readClass(bytes);
		boolean found = false;
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals(methodName)) continue;
			InsnList body = new InsnList();
			body.add(new InsnNode(Opcodes.RETURN));
			mn.instructions = body;
			mn.tryCatchBlocks = new ArrayList<>();
			mn.localVariables = null;
			mn.maxStack = 0;
			mn.maxLocals = 1;
			found = true;
		}
		assertTrue(found, "夹具前提：" + methodName + " 必须存在于夹具类里");
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 类内是否还有任何指令（{@code MethodInsn} / {@code invokedynamic} 的 implMethod 句柄）引用该方法名。 */
	private static boolean hasAnyReferenceTo(ClassNode cn, String methodName) {
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode m && m.name.equals(methodName)) return true;
				if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle h && h.getName().equals(methodName)) return true;
					}
				}
			}
		}
		return false;
	}

	private static String names(ClassNode cn) {
		StringBuilder sb = new StringBuilder("[");
		for (MethodNode mn : cn.methods) sb.append(mn.name).append(mn.desc).append(' ');
		return sb.append(']').toString();
	}

	private static String describe(MethodNode mn) {
		StringBuilder sb = new StringBuilder(mn.name + mn.desc + " ops=[");
		for (AbstractInsnNode insn : mn.instructions) {
			int op = insn.getOpcode();
			if (op >= 0) sb.append(org.objectweb.asm.util.Printer.OPCODES[op]).append(' ');
		}
		return sb.append(']').toString();
	}
	//endregion

	private static int findFirstStmtIndex(List<StmtSpec> list, StmtKind kind) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).kind == kind) return i;
		}
		return -1;
	}

	private static boolean isUnambiguous(List<StmtSpec> prevList, List<StmtSpec> nextList, StmtSpec curr, StmtSpec prevSpec) {
		if (curr.kind == StmtKind.TERNARY_NULL) return false; // 三元表达式 Fail-Closed 置空，无消费者特征
		if (curr.bodyId != prevSpec.bodyId || curr.kind != prevSpec.kind || curr.consumerId != prevSpec.consumerId) return false;
		int prevCount = 0;
		for (StmtSpec s : prevList) {
			if (s.consumerId == curr.consumerId && s.bodyId == curr.bodyId) prevCount++;
		}
		if (prevCount != 1) return false;
		int nextCount = 0;
		for (StmtSpec s : nextList) {
			if (s.consumerId == curr.consumerId && s.bodyId == curr.bodyId) nextCount++;
		}
		return nextCount == 1;
	}

	private static boolean hasHomomorphicBody(List<StmtSpec> list, StmtSpec target) {
		for (StmtSpec s : list) {
			if (s.id != target.id && (s.bodyId == target.bodyId || (s.kind == StmtKind.HOMOMORPHIC && target.kind == StmtKind.HOMOMORPHIC))) {
				return true;
			}
		}
		return false;
	}

	private static Map<Integer, StmtSpec> toIdMap(List<StmtSpec> list) {
		Map<Integer, StmtSpec> map = new HashMap<>();
		for (StmtSpec s : list) map.put(s.id, s);
		return map;
	}

	private static boolean callsExpectedLeaf(MethodNode mn, StmtSpec stmt) {
		if (mn == null || mn.instructions == null) return false;
		if (stmt.kind == StmtKind.HOMOMORPHIC) {
			return callsMethod(mn, "leafShared");
		} else {
			return callsMethod(mn, "leaf" + stmt.bodyId);
		}
	}

	private static CompiledUnit compileUnit(Path tmp, String javac, String tag, List<StmtSpec> stmts, boolean flag) throws Exception {
		Path srcDir = Files.createDirectories(tmp.resolve("src_" + tag));
		Path outDir = Files.createDirectories(tmp.resolve("out_" + tag));

		StringBuilder sb = new StringBuilder();
		sb.append("package test;\n");
		sb.append("public class PropCase {\n");
		sb.append("    public static boolean flag = ").append(flag).append(";\n");
		sb.append("    static void consumeA(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void consumeB(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void consumeC(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void sub(Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    static void leafShared() { }\n");
		for (int i = 0; i <= 30; i++) {
			sb.append("    static void leaf").append(i).append("() { }\n");
		}
		sb.append("    public void execute() {\n");

		Map<Integer, StmtSpec> lineToStmt = new HashMap<>();
		String[] headLines = sb.toString().split("\n", -1);
		int curLine = headLines.length;

		for (StmtSpec s : stmts) {
			sb.append(s.toJavaLine()).append("\n");
			lineToStmt.put(curLine++, s);
		}
		sb.append("    }\n");
		sb.append("}\n");

		String src = sb.toString();
		Path srcFile = srcDir.resolve("PropCase.java");
		Files.writeString(srcFile, src, StandardCharsets.UTF_8);

		runCmd(javac, "-g", "-nowarn", "-encoding", "UTF-8", "-d", outDir.toString(), srcFile.toString());
		byte[] bytes = Files.readAllBytes(outDir.resolve("test/PropCase.class"));

		return extractCompiledUnit(bytes, src, lineToStmt);
	}

	private static CompiledUnit extractCompiledUnit(byte[] bytes, String source, Map<Integer, StmtSpec> lineToStmt) {
		ClassNode cn = readClass(bytes);
		Map<String, Integer> methodToStmtId = new HashMap<>();
		Map<Integer, String> stmtIdToLeafMethod = new HashMap<>();
		Map<Integer, String> stmtIdToParentMethod = new HashMap<>();
		Map<String, String> methodToRole = new HashMap<>();

		MethodNode execNode = findMethod(cn, "execute");
		if (execNode != null) {
			int curLine = -1;
			for (AbstractInsnNode insn : execNode.instructions) {
				if (insn instanceof LineNumberNode lnn) {
					curLine = lnn.line;
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					if (indy.bsmArgs != null && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
						String mName = h.getName();
						StmtSpec spec = lineToStmt.get(curLine);
						if (spec != null) {
							methodToStmtId.put(mName, spec.id);
							if (spec.kind == StmtKind.NESTED_PARENT) {
								stmtIdToParentMethod.put(spec.id, mName);
								methodToRole.put(mName, "ROLE_PARENT");
							} else {
								stmtIdToLeafMethod.put(spec.id, mName);
								methodToRole.put(mName, "ROLE_LEAF");
							}
						}
					}
				}
			}
		}

		for (Map.Entry<Integer, String> parentEntry : stmtIdToParentMethod.entrySet()) {
			int sId = parentEntry.getKey();
			String parentName = parentEntry.getValue();
			MethodNode pNode = findMethod(cn, parentName);
			if (pNode != null) {
				for (AbstractInsnNode insn : pNode.instructions) {
					if (insn instanceof InvokeDynamicInsnNode indy) {
						if (indy.bsmArgs != null && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h) {
							String leafName = h.getName();
							methodToStmtId.put(leafName, sId);
							stmtIdToLeafMethod.put(sId, leafName);
							methodToRole.put(leafName, "ROLE_LEAF");
						}
					}
				}
			}
		}

		return new CompiledUnit(bytes, source, lineToStmt, methodToStmtId, stmtIdToLeafMethod, stmtIdToParentMethod, methodToRole);
	}

	private static ClassNode readClass(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);
		return cn;
	}

	private static MethodNode findMethod(ClassNode cn, String name) {
		if (name == null) return null;
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) return mn;
		}
		return null;
	}

	private static boolean callsMethod(MethodNode mn, String targetMethodName) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof MethodInsnNode minsn) {
				if (minsn.name.equals(targetMethodName)) return true;
			}
		}
		return false;
	}

	static String javac21() {
		String j = System.getProperty("hstest.javac21");
		if (j != null && new File(j).exists()) return j;
		j = System.getenv("HSTEST_JAVAC21");
		if (j != null && new File(j).exists()) return j;
		j = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		return new File(j).exists() ? j : "javac";
	}

	static void runCmd(String... cmd) throws Exception {
		// javac 走常驻服务（同版本、同选项，产物一致）；不可用时照旧起外部进程。
		if (JavacDaemon.tryRun(cmd)) return;
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder out = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				out.append(line).append('\n');
			}
		}
		int rc = p.waitFor();
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc + ": " + Arrays.toString(cmd) + "\nOutput:\n" + out);
	}
}
