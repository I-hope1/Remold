package remold;

import java.lang.instrument.ClassDefinition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 全仓唯一的 {@code Instrumentation.redefineClasses} 入口门禁。
 *
 * <p><b>为什么必须唯一</b>：JBR + {@code AllowEnhancedClassRedefinition} 收到畸形
 * {@link ClassDefinition} 不是抛 {@code LinkageError}，而是在增强重定义的拓扑排序里
 * {@code EXCEPTION_ACCESS_VIOLATION} 直接终止进程。没有异常可捕获、没有回滚可做，所以
 * "进 JVM 之前判"必须挂在<b>每一个</b>调用点上，而不是只挂在批次门面上。</p>
 *
 * <p>本类只做<b>策略</b>（逐类摘出 + {@code warn} + 进同一套跳过台账）；判据本身只有一份，
 * 在 {@link RemoldAgent#definitionProblem(Class, byte[])}（魔数 / 常量池逐项走查 / {@code major}
 * 上限 / {@code this_class} 名字核对）。任何入口都不得自带第二份校验。</p>
 *
 * <p>覆盖守卫：{@code tests/src/remold/RedefineEntryGateCoverageTest} 扫描 {@code core/src} 与全部
 * 插件源码里的 {@code redefineClasses} 调用点，逐个断言它所在的方法在白名单里——新增一个裸调用点
 * 该测试立刻变红。</p>
 */
public final class RedefineEntryGate {

	private RedefineEntryGate() {}

	/**
	 * 批次入口：把不安全的定义<b>逐个</b>摘出本批并 {@code warn}。
	 *
	 * <p>单个畸形类不得拖垮整批：与"单字段链接失败只隔离该字段"同一取向——其余类照常 redefine，
	 * 被摘掉的类进 {@link RemoldAgent.RoundSkips}，由同轮的 skip 摘要点名，用户不会看到一条静默的
	 * 成功行。</p>
	 *
	 * <p>放行下去的就是校验过的同一个 {@link ClassDefinition} 实例（同一个字节数组引用），
	 * 既不复制也不重读磁盘——这是"校验用的字节 == 送进 redefine 的字节"的唯一保证方式。</p>
	 *
	 * @param skips 本轮的跳过台账，可为 {@code null}（此时只 {@code warn}，不记账）
	 * @return 允许送进 JVM 的定义；入参为空时原样返回
	 */
	static List<ClassDefinition> gate(List<ClassDefinition> definitions, RemoldAgent.RoundSkips skips) {
		if (definitions == null || definitions.isEmpty()) return definitions;
		List<ClassDefinition> safe = new ArrayList<>(definitions.size());
		for (ClassDefinition def : definitions) {
			ClassDefinition admitted = gateOne(def, skips);
			if (admitted != null) safe.add(admitted);
		}
		return safe;
	}

	/**
	 * 单定义入口（动态单类 / 回退路径 / profiler 用）：拒绝时返回 {@code null}，已经 {@code warn}
	 * 并（在有台账时）记进台账。
	 *
	 * <p>没有 {@code skips} 的调用方拿到 {@code null} 就是"这一类不许 redefine"，<b>不得</b>换成
	 * 抛异常去拖垮整批，也不得退化成"照旧送进 JVM"。</p>
	 */
	static ClassDefinition gateOne(ClassDefinition definition, RemoldAgent.RoundSkips skips) {
		if (definition == null) return null;
		Class<?> type = definition.getDefinitionClass();
		String className = type == null ? "<unknown>" : type.getName();
		String problem = RemoldAgent.definitionProblem(type, definition.getDefinitionClassFile());
		if (problem == null) return definition;
		// 拒绝必须用户可见：受 DEBUG 门控的 log 在这里等于静默。
		RemoldAgent.warn("[BYTECODE-GATE] Skipping " + className + ": " + problem
		     + ". This class was NOT sent to redefineClasses — enhanced redefinition can terminate the VM"
		     + " on a malformed ClassDefinition. Recompile the class and retry.");
		if (skips != null) {
			skips.add(className, RemoldAgent.RoundSkips.verdict("BYTECODE-GATE", problem,
			 "recompile the class and retry"));
			skips.markBytecodeRejected(className);
		}
		return null;
	}

	/**
	 * 单定义入口的公开形态：给拿不到 {@link RemoldAgent.RoundSkips}（不同包）的入口用。
	 *
	 * <p>拒绝时就地打一条单类跳过摘要（{@code [HOTSWAP-SKIPPED]}），保证"被拦下的类"在日志里
	 * 与批次路径同一口径可见，而不是只有一行 per-class warn。</p>
	 */
	public static ClassDefinition gateOne(ClassDefinition definition) {
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();
		ClassDefinition admitted = gateOne(definition, skips);
		if (admitted == null) skips.report();
		return admitted;
	}

	/**
	 * 门禁之后裁剪<b>对齐事务</b>：只要有成员被本门禁拒掉，这个 {@link RemoldAgent.AlignmentTransaction}
	 * 就一步都不许提交。
	 *
	 * <p><b>为什么必须在提交之前裁</b>：{@code gate} 只过滤 {@code definitions}，事务集合原样传下去；
	 * 而 {@code AlignmentTransaction.commit()} / {@code preRegister()} 会做两件持久化的事——
	 * 往 {@link AnnotationTransformer#pendingAlignedClasses} 登记类字节、把 {@code cacheUpdates} 写进
	 * {@code bytecodeCache}。于是<b>没进 redefineClasses 的类，其未生效字节反而成了基线</b>：
	 * 下一轮对齐拿它当"已生效版本"，别处的 {@code no-op} 判定也会把它当成现状
	 * （{@code docs/status.md} §5 风险 15/16 要消灭的正是这个形态）。</p>
	 *
	 * <p><b>为什么是 rollback(false) 而不是 rollback(true)</b>：{@code pinOldToPending=true} 是给
	 * "宿主/匿名类半生效"准备的——那种情况下 JVM 里已经有新字节，必须用旧字节把未来的首次加载钉住。
	 * 被门禁拒掉的类<b>根本没进 JVM</b>，运行态与旧基线仍然一致，不需要（也不应该）往 pending 里写
	 * 任何条目：那里每写一条，就等于给"没生效的字节"再发一次基线身份。</p>
	 *
	 * <p>被拒类已由 {@link #gateOne} 记进 {@link RemoldAgent.RoundSkips}；这里只补一条宿主组级别的
	 * {@code warn}，让"整组不提交"在日志里也可见。</p>
	 *
	 * @param transactions 本轮对齐事务（可为 {@code null} / 空）
	 * @param skips        本轮台账，{@code null} 时不做任何裁剪（没有结构化拒绝名单可用）
	 * @return 可以继续提交的事务；无裁剪时原样返回入参
	 */
	static Collection<RemoldAgent.AlignmentTransaction> withoutRejectedGroups(
	 Collection<RemoldAgent.AlignmentTransaction> transactions, RemoldAgent.RoundSkips skips) {
		if (transactions == null || transactions.isEmpty() || skips == null) return transactions;
		Set<String> rejected = skips.bytecodeRejected();
		if (rejected.isEmpty()) return transactions;
		List<RemoldAgent.AlignmentTransaction> kept = new ArrayList<>(transactions.size());
		for (RemoldAgent.AlignmentTransaction tx : transactions) {
			String rejectedMember = firstRejectedMember(tx, rejected);
			if (rejectedMember == null) {
				kept.add(tx);
				continue;
			}
			tx.rollback(false);
			skips.add(tx.hostName, RemoldAgent.RoundSkips.verdict("BYTECODE-GATE",
			 "alignment group holds " + rejectedMember + ", which the bytecode gate refused",
			 "recompile the class and retry"));
			RemoldAgent.warn("[BYTECODE-GATE] Alignment group of " + tx.hostName + " is NOT committed: "
			 + rejectedMember + " was refused by the bytecode gate, so the aligned bytes for this group"
			 + " must not become the baseline. Nothing was sent to redefineClasses for the refused class.");
		}
		return kept;
	}

	/** 该事务组里第一个被门禁拒掉的成员（宿主或匿名类）；没有则 {@code null}。 */
	private static String firstRejectedMember(RemoldAgent.AlignmentTransaction tx, Set<String> rejected) {
		if (tx == null) return null;
		if (tx.hostName != null && rejected.contains(tx.hostName)) return "the host class " + tx.hostName;
		for (String target : tx.targetClasses) {
			if (rejected.contains(target)) return "anonymous class " + target;
		}
		return null;
	}
}
