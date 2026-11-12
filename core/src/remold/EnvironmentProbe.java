package remold;

import java.lang.management.ManagementFactory;

/**
 * 增强类重定义（JBR / DCEVM）的能力探测与无能力时的降级策略。
 *
 * <p><b>为什么探测结果不能靠命令行参数判断</b>：{@code -XX:+AllowEnhancedClassRedefinition} 只存在于
 * DCEVM/JBR 系构建里。标准 HotSpot 单独加它会直接启动失败（{@code Unrecognized VM option}）；
 * 而一旦同时加了 {@code -XX:+IgnoreUnrecognizedVMOptions}，标准 HotSpot 会<b>接受并忽略</b>它，
 * 但 {@link java.lang.management.RuntimeMXBean#getInputArguments()} 仍会原样回显这个字符串。
 * 按参数文本判断就会把标准 JVM 误判成支持增强重定义，随后 {@code redefineClasses} 因
 * schema 变化抛 {@code UnsupportedOperationException}，并连带同批次其它类一起失败。</p>
 *
 * <p><b>降级策略只关"做了就会失败"的开关</b>，不关"做了也没用"的开关。降级而非报错的理由是：
 * 用户显式打开某个开关，说明他期望的是增强能力，而当前 JVM 给不了；与其让每一轮热更都失败一次，
 * 不如关掉做不了的部分，让仍然合法的方法体热更继续工作。</p>
 *
 * <p><b>为什么独立成类</b>：探测与降级是"当前运行时能做什么"的静态事实，与热更流水线的
 * 状态机无关。它原先与监听、对齐、布局门共享 {@link RemoldAgent} 的类体，任何一处改动都要
 * 重新读一遍这段探测注释。纯函数化之后，{@code tests/src/EnhancedHotswapGateTest} 可以只针对
 * 本类断言两个方向（有/无能力）。</p>
 */
final class EnvironmentProbe {

	private EnvironmentProbe() { }

	/** 运行时是否<b>真的</b>具备增强重定义能力，类初始化时探测一次。 */
	private static final boolean ENHANCED_HOTSWAP = probeEnhancedHotswap();

	/**
	 * 探测当前 JVM 是否<b>真的</b>具备增强重定义能力（新增字段/方法）。
	 *
	 * <p>厂商标识是可靠证据：标准 HotSpot（Oracle / Adoptium / Zulu…）不会自称
	 * JetBrains / DCEVM / Trava，而 DCEVM 系构建会。厂商对上了，再看 flag 是否真的开启 ——
	 * 参数文本单独不可信（标准 JVM 靠 {@code -XX:+IgnoreUnrecognizedVMOptions} 也会回显它），
	 * 但配合厂商标识就是可靠的。</p>
	 *
	 * <p>任何异常（MXBean 不可用、非 HotSpot 运行时）一律返回 {@code false}：能力探测必须
	 * fail-closed，把「不知道」当成「没有」。</p>
	 */
	private static boolean probeEnhancedHotswap() {
		String runtime = System.getProperty("java.vm.vendor", "")
		 + ' ' + System.getProperty("java.vm.name", "")
		 + ' ' + System.getProperty("java.runtime.version", "");
		if (!looksLikeEnhancedRuntime(runtime)) return false;
		return isFlagEnabled(ManagementFactory.getRuntimeMXBean().getInputArguments(), "AllowEnhancedClassRedefinition");
	}

	/**
	 * 解析 JVM 参数列表中某个布尔开关（{@code -XX:+Flag} / {@code -XX:-Flag}）的最终状态。
	 *
	 * <p>以命令行中<b>最后一次出现</b>的设置为准。若命令行中未指定，返回 {@code false}。</p>
	 */
	static boolean isFlagEnabled(java.util.List<String> inputArgs, String flagName) {
		if (inputArgs == null || flagName == null) return false;
		String plus = "-XX:+" + flagName;
		String minus = "-XX:-" + flagName;
		int lastPlus = -1;
		int lastMinus = -1;
		for (int i = 0; i < inputArgs.size(); i++) {
			String arg = inputArgs.get(i);
			if (arg.equals(plus)) lastPlus = i;
			else if (arg.equals(minus)) lastMinus = i;
		}
		return lastPlus > lastMinus;
	}

	/**
	 * 运行时标识是否属于支持增强重定义的构建（DCEVM 系）。
	 *
	 * <p>抽成纯函数以便单测：{@code tests/src/EnhancedHotswapGateTest} 用真实的
	 * 厂商字符串断言标准 HotSpot 不会被判成增强运行时。</p>
	 */
	static boolean looksLikeEnhancedRuntime(String runtimeIdentity) {
		if (runtimeIdentity == null) return false;
		String s = runtimeIdentity.toLowerCase(java.util.Locale.ROOT);
		return s.contains("jetbrains") || s.contains("dcevm") || s.contains("trava");
	}

	/** 当前 JVM 是否支持增强型热重载（增加字段/方法等）。 */
	static boolean isEnhancedHotswapEnabled() {
		return ENHANCED_HOTSWAP;
	}

	/**
	 * 该类的结构变化是否必须因「当前 JVM 没有增强重定义」而被摘出本批重定义。
	 *
	 * <p>抽成纯函数是为了让 {@code tests/src/EnhancedHotswapGateTest} 能脱离 Instrumentation
	 * 锁住两个方向：有结构变化且无能力时必须摘出；<b>没有</b>结构变化时绝不摘出。
	 * 后者是真实缺陷的回归断言 —— 早期实现把判断写成了 {@code !structureChanged()}，
	 * 导致真结构变化静默放行、无结构变化反而误报。</p>
	 */
	static boolean mustSkipForMissingEnhancedRedefinition(boolean structureChanged, boolean enhanced) {
		return structureChanged && !enhanced;
	}

	/**
	 * 无增强重定义能力时的降级：把<b>会自己制造 schema 变化</b>的开关强制关掉。
	 *
	 * <p>只降级 {@code FORCE_STATIC_LAMBDA}：它把实例 lambda 改写成「静态 + 显式 {@code this} 首参数」，
	 * 方法描述符随之改变，这本身就是 schema 变化。在标准 HotSpot 上放行它，等于每一轮都主动制造
	 * 一次 {@code UnsupportedOperationException}。</p>
	 *
	 * <p><b>为什么不连 {@code INIT_FIX} 一起关</b>：{@code INIT_FIX} 不会失败，它只是<b>没机会执行</b>——
	 * 新增字段构成的 schema 变化已在 {@link #mustSkipForMissingEnhancedRedefinition} 那一层被整类摘出。
	 * 强制关闭它换不来任何收益，却会波及所有<b>直接</b>调用 {@link remold.ref.InitFix#transform} 的路径
	 * （回归套件 {@code InitFixOracle} 就在标准 JVM 上跑 48 个场景），让它们静默空转。
	 * 降级只关「做了就会失败」的东西，不关「做了也没用」的东西。</p>
	 *
	 * <p>{@code enhanced} 抽成参数而不是内部直接调用 {@link #isEnhancedHotswapEnabled()}，
	 * 是为了让 {@code tests/src/EnhancedHotswapGateTest} 能锁住两个方向（有/无能力）。
	 * 本方法会修改 {@link RemoldAgent#FORCE_STATIC_LAMBDA}。</p>
	 *
	 * @return 被降级关闭的开关名，顺序固定；空列表表示无需降级
	 */
	static java.util.List<String> degradeForMissingEnhancedRedefinition(boolean enhanced) {
		java.util.List<String> degraded = new java.util.ArrayList<>(1);
		if (enhanced) return degraded;
		if (RemoldAgent.FORCE_STATIC_LAMBDA) {
			RemoldAgent.FORCE_STATIC_LAMBDA = false;
			degraded.add("remold.agent.force_static_lambda");
		}
		return degraded;
	}

	/**
	 * 降级原因文案，与 {@link #degradeForMissingEnhancedRedefinition(boolean)} 产出的开关名一一对应。
	 *
	 * <p>抽出来是为了让「为什么关」写在唯一一处，日志与文档不必各自维护一份说法。</p>
	 */
	static String degradationReason(String switchName) {
		if ("remold.agent.force_static_lambda".equals(switchName)) {
			return "Rewriting instance lambdas into static ones changes their method descriptors,"
			 + " which is a schema change this JVM cannot apply.";
		}
		return "not supported by this JVM.";
	}
}
