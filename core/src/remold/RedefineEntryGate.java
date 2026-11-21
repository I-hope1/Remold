package remold;

import java.lang.instrument.ClassDefinition;
import java.util.ArrayList;
import java.util.List;

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
}
