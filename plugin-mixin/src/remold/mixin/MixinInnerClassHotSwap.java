package remold.mixin;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import remold.BytecodeSources;
import remold.RemoldAgent;

/**
 * 把「Mixin 内部类的字节变化」落到运行期真实类上。
 *
 * <p><b>为什么不能只重定义目标类</b>：Mixin 织入时会把模板内部类 {@code Outer$1} 重映射成
 * 目标类的合成内部类，运行期名字由 {@code InnerClassGenerator.getUniqueReference} 生成
 * （{@code Target$Anonymous$<uuid>}）。只改 {@code Outer$1} 的方法体时，目标类的织入产物一个
 * 字节都没变，于是 {@code redefineDynamic} 正确地判 no-op——但真正承载新逻辑的那个合成内部类
 * 从来没有人重定义过，运行期继续跑旧版。表现为「改了匿名类体却仍输出 v1」。</p>
 *
 * <p><b>做法</b>：reload 成功后，从 {@code InnerClassGenerator} 的运行期登记表里找到本 Mixin 内部类
 * 对应的合成类名，用新的模板字节重新生成一份该合成类，再对**已加载**的那个类执行
 * {@code redefineClasses}。唯一名在 {@code registerInnerClass} 里按坐标缓存，跨 reload 稳定，
 * 所以目标类字节码里的引用不需要变。</p>
 *
 * <p><b>安全门</b>：只接受「成员 schema 未变」的重定义（等价于 JVM 自身对 redefine 的结构约束）。
 * 匿名类新增字段/方法属于结构变化，由 JVM 重定义规则或本类的 schema 检查拦下，并输出指向性
 * 诊断——不用 DCEVM 的结构化能力去绕过 Remold 的布局门。</p>
 *
 * <p><b>字节码回灌</b>：{@code Target$Anonymous$<uuid>} 这类合成内部类磁盘上没有 {@code .class}，
 * 也不经过 Remold 的 {@code ClassFileTransformer}，所以 Remold 自己的两条字节来源都读不到它——
 * Nest 成员扫描因此 {@code incomplete}、构造器/成员可见性判定只能给出"无法确定"，两者都
 * fail-closed（字段初始化 patch 被拒）。本类在成功生成合成类字节时把它登记进
 * {@link remold.BytecodeSources}（键 = 内部名），让 core 的离线元数据视图看得见这些类。</p>
 */
final class MixinInnerClassHotSwap {

	/**
	 * 本轮运行期重新生成的合成内部类字节（内部名 → 字节）。
	 *
	 * <p>登记发生在 {@code generate(...)} 成功之后、重定义之前：即使随后的 schema 检查或
	 * {@code redefineClasses} 失败，元数据也可用（那正是最需要诊断的时刻）。因此本表只承诺
	 * "读得到字节"，<b>不</b>作为"该字节已在 JVM 里生效"的凭证。</p>
	 *
	 * <p>键集合不会随 reload 次数增长：合成类的唯一名由 {@code InnerClassGenerator} 按坐标
	 * 缓存、跨 reload 稳定，同一 Mixin 内部类反复热更只会覆盖同一键。</p>
	 */
	private static final Map<String, byte[]> GENERATED = new ConcurrentHashMap<>();

	/**
	 * 已成功重定义的合成内部类字节：<b>键 = (加载器身份, 点分名)</b>，由
	 * {@link MixinClassBaseline#recordApplied}/{@link MixinClassBaseline#appliedOf} 持有。
	 *
	 * <p><b>为什么必须带加载器身份</b>（不变量 C2）：点分名一条索引无法区分"同名类被两个加载器各定义
	 * 一份"——那会让 A 加载器的成功字节被当成 B 加载器的基线（互相冒充），而闸 4 的判据正是
	 * "基线 = 本加载器实际加载的那份"。加载器键复用 premain 录口的弱键身份语义（同一套
	 * {@code ClassLoader} 实例身份，弱引用、不延长加载器寿命），两个来源因此同源可比。</p>
	 *
	 * <p>用途不变：缓存"已在 JVM 里生效"的字节（基线链 R1+），供「无实质变更短路」与闸 4 使用。
	 * R4 还原等场景下重新生成的合成类字节与运行期已加载的完全一致时不得再次 redefine ——
	 * JBR/DCEVM 的增强重定义会做堆实例重写（{@code ModifyInstances}），无谓的重复 redefine 会制造
	 * 额外的 safepoint/重写窗口，真实观测到过无关类字段瞬时为 null 的连带崩溃。</p>
	 */

	/** 规则 3（事务一致性）：本轮因合成类无法安全重定义而必须中止的宿主目标类。 */
	private static final java.util.Set<String> BLOCKED = ConcurrentHashMap.newKeySet();

	/** 分母计数（战报必须带）：实际执行的 redefine、其中字段表变化的次数、被闸拒绝的次数。 */
	private static final java.util.concurrent.atomic.AtomicInteger EXECUTED = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger FIELD_CHANGED = new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger GATE_REJECTED = new java.util.concurrent.atomic.AtomicInteger();

	/** 本轮分母（战报口径）。 */
	static String metricsSummary() {
		return "executed=" + EXECUTED.get() + " fieldTableChanged=" + FIELD_CHANGED.get()
			+ " gateRejected=" + GATE_REJECTED.get();
	}

	// ---------------------------------------------------------------- 存量实例（liveness）

	/**
	 * 存量实例探针：返回该类的堆上实例（产品实现 = {@link remold.util.LibTool#getInstances} 的 JVMTI
	 * 堆遍历，单测可替换）。
	 */
	interface InstanceProbe {
		Object[] instancesOf(Class<?> type);
	}

	private static final InstanceProbe LIBTOOL_PROBE = remold.util.LibTool::getInstances;

	/** 当前探针（默认产品通道）。 */
	private static volatile InstanceProbe INSTANCE_PROBE = LIBTOOL_PROBE;

	/** 单测替换探针；传 {@code null} 恢复产品通道。 */
	static void setInstanceProbeForTest(InstanceProbe probe) {
		INSTANCE_PROBE = probe == null ? LIBTOOL_PROBE : probe;
	}

	/**
	 * 该合成类在堆上的存量实例数（纯诊断读数，<b>不参与判决</b>）。
	 *
	 * <p>{@code -1} = 探针不可用。语义是 {@code LibTool.getInstances} 的堆遍历口径：<b>含不可达但尚未被
	 * GC 物理回收</b>的对象。真机已裁定成员 schema 变化的污染与实例数无关（`raw=0` 时仍 `newlyNull=230`），
	 * 因此这里<b>不做 {@code System.gc()}</b>：既不需要"可达实例数"，也不该在游戏进程里制造一次 STW。</p>
	 */
	static int syntheticLive(Class<?> live) {
		if (live == null) {
			return -1;
		}
		return probeInstanceCount(live);
	}

	/** 探针调用；任何失败都返回 {@code -1}（= 读数不可用），绝不抛出打断闸门。 */
	private static int probeInstanceCount(Class<?> live) {
		try {
			Object[] instances = INSTANCE_PROBE.instancesOf(live);
			return instances == null ? 0 : instances.length;
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] live-instance probe failed for " + live.getName()
				+ "; structural synthetic redefines will be refused until it works (fail-closed): " + t);
			return -1;
		}
	}

	/** 宿主目标类本轮是否必须中止重定义（合成类无法安全落地时不允许半残状态）。 */
	static boolean isBlocked(String targetDotName) {
		return targetDotName != null && BLOCKED.contains(targetDotName);
	}

	static void clearBlocked(String targetDotName) {
		if (targetDotName != null) {
			BLOCKED.remove(targetDotName);
		}
	}

	/** 登记口只装一次：来源函数恒定，内容由 {@link #GENERATED} 演进。 */
	private static final AtomicBoolean SOURCE_REGISTERED = new AtomicBoolean();

	/** 回灌给 core 的字节码来源；内部名与点分名都接受（core 两种形态都会问）。 */
	private static final Function<String, byte[]> SOURCE = MixinInnerClassHotSwap::generatedBytes;

	private MixinInnerClassHotSwap() {
	}

	/** 一次内部类热更的结果。 */
	static final class Result {
		final List<String> redefined = new ArrayList<>();
		final List<String> notLoaded = new ArrayList<>();
		final List<String> unchanged = new ArrayList<>();
		final List<String> failures = new ArrayList<>();

		boolean changed() {
			return !this.redefined.isEmpty();
		}

		boolean hasFailure() {
			return !this.failures.isEmpty();
		}

		String describeConformed() {
			return String.join(", ", this.redefined);
		}

		String describeFailures() {
			return String.join("; ", this.failures);
		}
	}

	/**
	 * 对本次 reload 的 Mixin 做一次内部类热更新。
	 *
	 * @param transformer             当前生效的 Mixin 织入器（不透明对象）
	 * @param mixinDotName            Mixin 的点分类名（仅用于日志）
	 * @param originalInnerInternalNames 本次涉及的原模板内部类内部名（{@code Outer$1} 形态）
	 * @return 热更结果；任何情况下都不抛异常
	 */
	static Result apply(Object transformer, String mixinDotName, Collection<String> originalInnerInternalNames) {
		Result result = new Result();
		if (transformer == null || originalInnerInternalNames == null || originalInnerInternalNames.isEmpty()) {
			return result;
		}
		Instrumentation inst = RemoldAgent.getInstrumentation();
		if (inst == null) {
			result.failures.add("Instrumentation unavailable");
			return result;
		}
		Map<?, ?> innerClasses;
		Object generator;
		try {
			generator = findInnerClassGenerator(transformer);
			if (generator == null) {
				result.failures.add("InnerClassGenerator not found on this Mixin version");
				return result;
			}
			// 注意：findField 返回 Field 句柄，必须显式取值；这里曾经把句柄当值用，
			// 于是每个版本都报 "innerClasses is unavailable"（真实 KnotServer 探针抓到的）。
			java.lang.reflect.Field innerClassesField = findField(generator.getClass(), "innerClasses");
			Object raw = innerClassesField == null ? null : innerClassesField.get(generator);
			innerClasses = raw instanceof Map ? (Map<?, ?>) raw : null;
			if (innerClasses == null) {
				result.failures.add("InnerClassGenerator.innerClasses is unavailable on this Mixin version");
				return result;
			}
		} catch (Throwable t) {
			result.failures.add("cannot inspect InnerClassGenerator: " + t);
			return result;
		}

		Set<String> wanted = new LinkedHashSet<>(originalInnerInternalNames);
		List<Object> snapshot;
		synchronized (innerClasses) {
			snapshot = new ArrayList<>(innerClasses.values());
		}
		Method generate = findMethod(generator.getClass(), "generate", String.class, ClassNode.class);
		if (generate == null) {
			result.failures.add("InnerClassGenerator.generate(String, ClassNode) is unavailable");
			return result;
		}

		for (Object info : snapshot) {
			try {
				String originalName = (String) invoke(info, "getOriginalName");
				if (originalName == null || !wanted.contains(originalName)) {
					continue;
				}
				String conformedDotted = (String) invoke(info, "getClassName");
				if (conformedDotted == null) {
					continue;
				}
				Class<?> live = findLoadedClass(inst, conformedDotted);
				if (live == null) {
					// 合成内部类还没被加载：下次首次加载会用新的模板字节生成，无需热更。
					result.notLoaded.add(conformedDotted);
					continue;
				}
				ClassNode fresh = new ClassNode();
				Object generated = generate.invoke(generator, conformedDotted, fresh);
				if (!Boolean.TRUE.equals(generated)) {
					result.failures.add(conformedDotted + " (InnerClassGenerator.generate returned false)");
					continue;
				}
				java.util.List<String> loadedFields = MixinSyntheticSchema.loadedFields(live);
				// 字段表相对**已加载类实时反射表**（不变量 A）的差异：只做诊断，判决在单一出口里。
				RemoldAgent.info("[Mixin] synthetic inner class " + conformedDotted + " field table vs loaded: loaded="
					+ loadedFields + " fresh=" + MixinSyntheticSchema.nodeFields(fresh)
					+ " (schemaHash=" + MixinSyntheticSchema.schemaHash(fresh) + ").");
				byte[] bytes = serialize(fresh);
				if (isUnchanged(live.getClassLoader(), conformedDotted, bytes)) {
					result.unchanged.add(conformedDotted);
					continue;
				}
				String schemaDiff = describeSchemaChange(live, fresh);
				if (schemaDiff != null && !RemoldAgent.isEnhancedHotswapEnabled()) {
					result.failures.add(conformedDotted + " (" + schemaDiff + ")");
					continue;
				}
				if (schemaDiff != null) {
					// 增强重定义（JBR/DCEVM）允许改成员表：合成内部类的构造器描述符会随
					// Mixin→Target 映射变化，这正是结构热更需要放行的形态。
					RemoldAgent.info("[Mixin] synthetic inner class " + conformedDotted
						+ " changes member schema on a JBR/DCEVM host (" + schemaDiff + "); redefining it.");
				}
				// 回灌：合成内部类磁盘上没有 .class、也不经过 Remold 的 transformer，不回灌则
				// Nest 扫描 incomplete、构造器可见性判定失败（InitFix 的字段初始化 patch 被拒）。
				registerGenerated(conformedDotted, bytes);
				// 标成 Remold 自己发起的重定义：否则 AnnotationTransformer 会把这次 redefine 当成
				// 外部热更，在已经织入过的合成类上再跑一遍桥接。
				// BLOCKED 的键必须是**宿主目标类**（与 reweave/isBlocked 同源），不能是 Mixin 模板名：
				// 用模板名会让"中止宿主重定义"永远命中不到宿主，等于假接线。
				if (!redefineSyntheticSafely(inst, live, conformedDotted, bytes, hostDotNameOf(info, mixinDotName))) {
					result.failures.add(conformedDotted + " (rejected by the single-exit gate)");
					continue;
				}
				result.redefined.add(conformedDotted);
			} catch (Throwable t) {
				result.failures.add(info + " (" + rootMessage(t) + ")");
			}
		}
		if (RemoldAgent.DEBUG && !result.notLoaded.isEmpty()) {
			RemoldAgent.log("[Mixin] inner class(es) not loaded yet, new bytes apply on first load: "
				+ String.join(", ", result.notLoaded));
		}
		return result;
	}

	/**
	 * 判断「重新生成的合成类字节」与运行期已加载字节是否完全一致（无实质变更）。
	 *
	 * <p>先查本加载器名下已生效的字节（{@link MixinClassBaseline#appliedOf}，键含加载器身份），
	 * 再回落到 {@link RemoldAgent#bytecodeCache}（点名索引、无加载器维度——只作<b>启发式</b>兜底：
	 * 它只能让"疑似未变"更保守地短路，不能证明基线身份）。两者都没有 → 视为有变更（必须重定义，
	 * 宁可多一次 redefine 也不能漏掉真实变更）。</p>
	 */
	static boolean isUnchanged(ClassLoader loader, String dottedName, byte[] fresh) {
		if (dottedName == null || fresh == null) {
			return true;
		}
		byte[] current = MixinClassBaseline.appliedOf(loader, dottedName);
		if (current == null) {
			current = RemoldAgent.bytecodeCache.get(dottedName);
		}
		if (current == null) {
			return false;
		}
		return java.util.Arrays.equals(current, fresh);
	}

	/** 成功 redefine 之后落账"已在 JVM 里生效"的字节；键含加载器身份（见 {@link #isUnchanged}）。 */
	private static void recordLoaded(Class<?> live, String dottedName, byte[] bytes) {
		if (live != null && dottedName != null && bytes != null) {
			MixinClassBaseline.recordApplied(live.getClassLoader(), dottedName, bytes);
		}
	}

	// ---------------------------------------------------------------- 字节码回灌

	/**
	 * 把本轮生成的合成内部类字节登记进 Remold 的字节来源登记口。
	 *
	 * <p>幂等：来源函数只登记一次，之后每次热更只是覆盖 {@link #GENERATED} 里的同一键。
	 * 永不抛异常（登记口自身也不会），失败最多让 core 侧继续 fail-closed，不会更坏。</p>
	 *
	 * @param dotClassName 合成类名（点分名或内部名均可）
	 * @param bytes        本轮生成的字节码
	 */
	static void registerGenerated(String dotClassName, byte[] bytes) {
		if (dotClassName == null || bytes == null || bytes.length == 0) {
			return;
		}
		if (SOURCE_REGISTERED.compareAndSet(false, true)) {
			BytecodeSources.register(SOURCE);
		}
		GENERATED.put(MixinInnerClassSupport.internalName(dotClassName), bytes);
	}

	/** 当前回灌的合成类内部名（诊断用）。 */
	static Set<String> generatedInternalNames() {
		return new LinkedHashSet<>(GENERATED.keySet());
	}

	private static byte[] generatedBytes(String className) {
		if (className == null) {
			return null;
		}
		return GENERATED.get(MixinInnerClassSupport.internalName(className));
	}

	/** 单测读数：本进程回灌给 core 的合成类字节（{@code null} = 这一轮没生成过）。 */
	static byte[] generatedBytesForTest(String className) {
		return generatedBytes(className);
	}

	/**
	 * 合成类所属的<b>宿主目标类</b>点分名：{@code BLOCKED} 的键，也是 {@code MixinReWeaver} 询问
	 * "宿主本轮要不要中止"时用的名字。
	 *
	 * <p>必须取 {@code InnerClassInfo.getTargetName()}（内部名 → 点分名），不能直接用调用方传进来的
	 * Mixin 模板名：模板名与目标名是两个不同的类，用模板名标记会让 {@code isBlocked(target)} 永远为假，
	 * 事务中止就退化成一条日志。</p>
	 */
	private static String hostDotNameOf(Object info, String fallback) {
		try {
			Object targetInternal = invoke(info, "getTargetName");
			if (targetInternal instanceof String name && !name.isEmpty()) {
				return MixinInnerClassSupport.dotName(name);
			}
		} catch (Throwable ignored) {
			// 老版本没有 getTargetName：退回调用方给的名字（只影响 BLOCKED 的键与日志，不影响闸判据）。
		}
		return fallback;
	}

	/**
	 * 把「目标类本次织入新登记的合成内部类」的字节提前生成出来并回灌。
	 *
	 * <p><b>为什么必须有这一步（S7 时序缺口）</b>：Mixin 织入目标类时只执行
	 * {@code InnerClassGenerator.registerInnerClass}（分配稳定唯一名），真正生成字节要等到
	 * 该合成类首次被加载。而 {@code InitFix} 在**同一次目标类 redefine 的内部**就要读
	 * {@code <Target>$Anonymous$<hash>.<init>} 的 protected 状态，才能判断新建实例的字段初始化
	 * 表达式是否可用 —— 此时字节还不存在，于是 fail-closed 拒绝（真实 KnotServer S7 红）。
	 * 把生成提前到「目标类织入完成之后、InitFix 扫描之前」，元数据视图即可见，
	 * 且**不放宽任何安全门**（读不到时仍照旧拒绝）。</p>
	 *
	 * <p><b>为什么不产生 UUID/哈希分裂</b>：唯一名来自 {@code registerInnerClass} 的坐标缓存
	 * （跨 reload 稳定），生成是对模板字节的确定性重映射；这里生成的字节与合成类首次加载时
	 * 由 {@code InnerClassGenerator} 生成的字节同源同参。为避免副作用，生成后会把
	 * {@code InnerClassInfo.loadCounter} 还原成生成前的值 —— 否则该合成类会被误判为"已加载"，
	 * 可能让平台侧跳过真正的 defineClass。</p>
	 *
	 * @param transformer   当前生效的 Mixin 织入器
	 * @param targetDotName 刚织入完成的目标类点分名
	 * @return 本次新回灌的合成类个数（0 表示该目标没有合成内部类，或没有新增项）
	 */
	static int refeedForTarget(Object transformer, String targetDotName) {
		if (transformer == null || targetDotName == null) {
			return 0;
		}
		String targetInternal = MixinInnerClassSupport.internalName(targetDotName);
		int registered = 0;
		try {
			Object generator = findInnerClassGenerator(transformer);
			if (generator == null) {
				return 0;
			}
			java.lang.reflect.Field innerClassesField = findField(generator.getClass(), "innerClasses");
			Object raw = innerClassesField == null ? null : innerClassesField.get(generator);
			if (!(raw instanceof Map)) {
				return 0;
			}
			Method generate = findMethod(generator.getClass(), "generate", String.class, ClassNode.class);
			if (generate == null) {
				return 0;
			}
			Map<?, ?> innerClasses = (Map<?, ?>) raw;
			List<Object> snapshot;
			synchronized (innerClasses) {
				snapshot = new ArrayList<>(innerClasses.values());
			}
			Instrumentation inst = RemoldAgent.getInstrumentation();
			for (Object info : snapshot) {
				String targetName = (String) invoke(info, "getTargetName");
				if (targetName == null || !targetName.equals(targetInternal)) {
					continue;
				}
				String conformedDotted = (String) invoke(info, "getClassName");
				if (conformedDotted == null) {
					continue;
				}
				// 每次都按**当前**模板重新生成：早先的 "已回灌就跳过" 会让上一次（可能是在
				// ClassInfo 失配窗口里生成的）旧字节一直遮蔽新映射，retarget 往返后合成类的
				// 构造器描述符便停留在旧形态（真机 NoSuchMethodError）。生成是确定性的。
				java.lang.reflect.Field counter = findField(info.getClass(), "loadCounter");
				int loadCounterBefore = counter == null ? 0 : counter.getInt(info);
				ClassNode fresh = new ClassNode();
				boolean generated;
				try {
					generated = Boolean.TRUE.equals(generate.invoke(generator, conformedDotted, fresh));
				} finally {
					// 还原"未加载"语义：只有在合成类真正被 defineClass 时才应该递增。
					if (counter != null) {
						try {
							counter.setInt(info, loadCounterBefore);
						} catch (Throwable ignored) {
							// 老版本无该字段：忽略，最多退化成本次批次里 isLoaded() 偏真。
						}
					}
				}
				if (!generated) {
					continue;
				}
				// Schema 锚定：已加载合成类的字段表为准补齐墓碑字段（禁止字段缩容 → 不触发
				// DCEVM ModifyInstances）。类型漂移无法用墓碑表达 → fail-closed 拒绝重定义。
				Class<?> live = inst == null ? null : findLoadedClass(inst, conformedDotted);
				java.util.List<String> loadedFields = live == null ? java.util.List.of()
					: MixinSyntheticSchema.loadedFields(live);
				// 基线字节 = 本加载器 JVM 实际加载的那份（premain 录口 / 已生效字节），
				// 不是本进程上一轮生成到覆盖层里的字节：R0 启动期加载的合成类在 R1 首次热更时
				// 覆盖层是空的，读覆盖层会让 retainConstructors 拿不到任何旧 <init>。
				// 字段表相对**已加载类实时反射表**（不变量 A）的差异：只做诊断，判决在单一出口里。
				RemoldAgent.info("[Mixin] synthetic inner class " + conformedDotted + " field table vs loaded: loaded="
					+ loadedFields + " fresh=" + MixinSyntheticSchema.nodeFields(fresh)
					+ " (schemaHash=" + MixinSyntheticSchema.schemaHash(fresh) + ").");
				byte[] bytes = serialize(fresh);
				// 这里刻意读**覆盖层**（上一轮生成了什么），不是基线（JVM 里是什么）：它只用来判断
				// "本次生成的字节相对上一次生成有没有变"，从而决定是否值得多一次 redefine。
				byte[] previous = generatedBytes(conformedDotted);
				registerGenerated(conformedDotted, bytes);
				registered++;
				// 结构变更窗口：目标类即将被重定义成「调用新 <init> 描述符」的版本，而合成类若
				// 仍是旧形态，两者之间的窗口内任何一次调用都会 NoSuchMethodError。这里在**目标类
				// redefine 之前**先把已加载的合成类重定义到新形态（redefineDynamic 的桥接阶段在
				// redefineClasses 之前，因此顺序天然正确）。
				if (inst != null) {
					if (live != null) {
						// 无实质变更短路（对齐 AnonClassAligner 的 no-op 语义）：运行期已加载字节与本次
						// 重生成字节完全一致时直接跳过，不做无谓 redefine（避免额外的堆重写窗口）。
						if (isUnchanged(live.getClassLoader(), conformedDotted, bytes)) {
							if (RemoldAgent.DEBUG) {
								RemoldAgent.log("[Mixin] synthetic inner class " + conformedDotted
									+ " is byte-identical to the loaded class; skipping redefine");
							}
						} else {
							// 不仅要看覆盖层字节是否变化，还要看**运行期已加载类**的成员 schema：
							// 上一次失败的重定义可能让覆盖层已是最新、而 JVM 里仍是旧构造器（陈旧 schema），
							// 这种情形下 overlay 比较会漏判。
							boolean bytesChanged = previous == null || !java.util.Arrays.equals(previous, bytes);
							boolean staleSchema = describeSchemaChange(live, fresh) != null;
							if (bytesChanged || staleSchema) {
								redefineSyntheticBeforeTarget(inst, live, conformedDotted, bytes, targetDotName);
							}
						}
					}
				}
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] unable to pre-generate synthetic inner classes for " + targetDotName
				+ "; InitFix metadata may stay unresolved for this batch (fail-closed preserved): "
				+ rootMessage(t));
			return registered;
		}
		if (registered > 0) {
			RemoldAgent.info("[Mixin] pre-generated " + registered + " synthetic inner class(es) for "
				+ targetDotName + " before the InitFix metadata scan");
		}
		return registered;
	}

	/**
	 * **唯一**的合成类 redefine 出口：所有路径只经这里，闸与计数都在出口内，避免下一条路径漏网。
	 *
	 * <p>前置闸（全部只靠反射/字节解析，不需要旧字节）：</p>
	 * <ol>
	 *   <li>宿主已被标 BLOCKED → 直接拒绝（规则 3）；</li>
	 *   <li>已加载构造器描述符集合必须是新字节 `<init>` 集合的子集（否则旧调用点必 NSME）；</li>
	 *   <li>已加载字段表必须是新字节字段表的子集（否则触发实例迁移）；</li>
	 *   <li>没有基线字节（类在 agent 注册前就已加载）→ 拒绝并**要求重启**，不静默降级。
	 *       判据见 {@link #baselineMissingReason(ClassLoader, String)}：只认 premain 录口
	 *       （键 = 加载器标识 + 类名，见 {@link MixinClassBaseline}）与本源已成功 redefine 过的字节。</li>
	 * </ol>
	 *
	 * @return true = 已执行 redefine；false = 被闸拒绝（调用方必须中止宿主）
	 */
	static boolean redefineSyntheticSafely(Instrumentation inst, Class<?> live, String name, byte[] bytes,
			String hostDotName) {
		if (inst == null || live == null || bytes == null) {
			return false;
		}
		if (hostDotName != null && BLOCKED.contains(hostDotName)) {
			GATE_REJECTED.incrementAndGet();
			return false;
		}
		ClassNode fresh = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(fresh, 0);
		java.util.Set<String> freshCtors = new java.util.HashSet<>();
		for (org.objectweb.asm.tree.MethodNode m : fresh.methods) {
			if ("<init>".equals(m.name)) {
				freshCtors.add(m.desc);
			}
		}
		for (java.lang.reflect.Constructor<?> c : live.getDeclaredConstructors()) {
			if (!freshCtors.contains(Type.getConstructorDescriptor(c))) {
				GATE_REJECTED.incrementAndGet();
				blockHost(hostDotName, name, "missing loaded <init>" + Type.getConstructorDescriptor(c));
				return false;
			}
		}
		java.util.List<String> loadedFields = MixinSyntheticSchema.loadedFields(live);
		java.util.List<String> freshFields = MixinSyntheticSchema.nodeFields(fresh);
		for (String field : loadedFields) {
			if (!freshFields.contains(field)) {
				GATE_REJECTED.incrementAndGet();
				blockHost(hostDotName, name, "field table would shrink: " + field);
				return false;
			}
		}
		String baselineMissing = baselineMissingReason(live.getClassLoader(), name);
		if (baselineMissing != null) {
			GATE_REJECTED.incrementAndGet();
			blockHost(hostDotName, name, baselineMissing);
			return false;
		}
		// 结构闸门（真机已裁定，不变量 C4）：只要**字段表相对已加载版本发生变化**就一律拒绝，与堆上有
		// 多少实例无关——
		//   • fieldTableChanged == false：只改方法体，存量与新建实例都原地生效 → 放行；
		//   • fieldTableChanged == true：一律拒绝 + 中止宿主。真机两分支实测（HEAD ac20e60b）已闭合
		//     事实③：即便 `raw=0 reachable=0`（堆上 0 个实例），对合成匿名类调用 redefineClasses 仍必现
		//     `newlyNull=230`（230 个具名 Record `LootCondition` 引用被抹）。即 JBR-21 DCEVM 的
		//     ModifyInstances 污染与实例数**无关**，只与"成员 schema 与已加载版本不一致"有关。
		//   • 旧的「变化 + 无存活实例 → 放行」分支已删除；`live{raw,reachable}` 仅保留为诊断读数。
		// 口径：flags 也参与比较（isStatic / isSynthetic），同名同描述符但 static 翻转同样算变化。
		boolean fieldTableChanged = !loadedFields.equals(freshFields)
			|| !MixinSyntheticSchema.loadedFieldSignatures(live)
				.equals(MixinSyntheticSchema.nodeFieldSignatures(fresh));
		int liveCount = syntheticLive(live);
		RemoldAgent.info("[Mixin] synthetic inner class " + name + " live{raw="
			+ (liveCount < 0 ? "unavailable" : String.valueOf(liveCount)) + "} fieldTableChanged="
			+ fieldTableChanged + " loaded=" + loadedFields + " fresh=" + freshFields
			+ " loadedFlags=" + MixinSyntheticSchema.loadedFieldSignatures(live)
			+ " freshFlags=" + MixinSyntheticSchema.nodeFieldSignatures(fresh) + ".");
		// 候选签名的锚点 = **这次要送进 JVM 的候选字节**（不可变、随源码变化），不是宿主目标类的
		// originalBytes（它恒定不变，会把"用户已改回一致捕获列表"也误判成同一份被拒候选而永久锁死）。
		long candidateSignature = java.util.Arrays.hashCode(bytes) & 0xffffffffL;
		if (fieldTableChanged) {
			FIELD_CHANGED.incrementAndGet();
			GATE_REJECTED.incrementAndGet();
			if (SyntheticAbortLedger.isSameCandidateAborted(name, candidateSignature)) {
				blockHost(hostDotName, name, "stays aborted: the identical candidate bytes (signature="
					+ candidateSignature + ") were already refused in this session; not retrying them — change the"
					+ " capture list or the anonymous-class source to start a new candidate");
				return false;
			}
			SyntheticAbortLedger.record(name, candidateSignature);
			blockHost(hostDotName, name, "field table changed (" + loadedFields + " -> " + freshFields + "; flags "
				+ MixinSyntheticSchema.loadedFieldSignatures(live) + " -> "
				+ MixinSyntheticSchema.nodeFieldSignatures(fresh) + ") with live{raw="
				+ (liveCount < 0 ? "unavailable" : String.valueOf(liveCount)) + "}: a member-schema change on a"
				+ " loaded synthetic class makes JBR/DCEVM rewrite unrelated heaps (real machine: newlyNull=230 even"
				+ " with zero instances), so it is refused regardless of the instance count — restart, or keep the"
				+ " capture list stable (@RemoldReinit / lambda)");
			return false;
		}
		// 放行路径：清掉该合成类名下任何陈旧记录（"被拒 → 改回一致 → 放行"必须在同一会话内自愈）。
		SyntheticAbortLedger.clear(name);
		remold.ref.HeapFieldWatch.snapshot("before-synthetic-redefine", name);
		RemoldAgent.enterInternalRedefine(java.util.Collections.singleton(name));
		try {
			inst.redefineClasses(new ClassDefinition(live, bytes));
		} catch (Throwable t) {
			GATE_REJECTED.incrementAndGet();
			blockHost(hostDotName, name, "redefineClasses failed: " + t);
			return false;
		} finally {
			RemoldAgent.exitInternalRedefine();
		}
		remold.ref.HeapFieldWatch.snapshot("after-synthetic-redefine", name);
		recordLoaded(live, name, bytes);
		EXECUTED.incrementAndGet();
		return true;
	}

	/**
	 * 闸 4 判据：该 (加载器, 类名) 是否有可用的基线字节。
	 *
	 * <p><b>基线 = JVM 实际加载的那份（不变量 C2）</b>。判定只认两个来源，顺序即基线链：</p>
	 * <ol>
	 *   <li>本源已生效字节 {@link MixinClassBaseline#appliedOf}：本加载器名下<b>已成功</b> redefine
	 *       过的字节（失败的重定义不落账，见 {@link #redefineSyntheticSafely} 的落账点），它确实在
	 *       JVM 里生效过——所以是基线链 R0→R1→R2 上成功那一步的产物；键 = (加载器身份, 点分名)；</li>
	 *   <li>premain 录口 {@link MixinClassBaseline}：键 = 加载器实例身份 + 类名，值是 JVM 最终定义字节
	 *       （R0）。这是唯一能证明"基线属于这个加载器"的来源。</li>
	 * </ol>
	 *
	 * <p><b>legacy 点分名缓存不参与判定</b>：{@link RemoldAgent#bytecodeCache} 只有点分名一条索引，
	 * 同名不同加载器的两份类会互相冒充。把它当基线来源，等于给"同名互相冒充"留一条静默后门，
	 * 而且它无法保证"这份字节是本加载器 JVM 实际加载的那份"。它只保留在 {@link #isUnchanged}
	 * 的"无实质变更短路"里当启发式（宁可多一次 redefine，也不拿它证明身份）。</p>
	 *
	 * @param loader  定义该类的加载器（合成类通常来自 Knot 的加载器）
	 * @param dotName 合成类点分名
	 * @return {@code null} = 基线可用；否则为拒绝原因（含 restart 要求）
	 */
	static String baselineMissingReason(ClassLoader loader, String dotName) {
		if (dotName == null) {
			return "no baseline bytes (unknown class name); restart required";
		}
		if (baselineBytes(loader, dotName) != null) {
			return null;
		}
		return "no baseline bytes (class loaded before the agent registered); baseline "
			+ MixinClassBaseline.metricsSummary() + "; last miss: " + MixinClassBaseline.lastMissSummary()
			+ "; restart required";
	}

	/**
	 * 闸 4 的唯一基线解析口：本加载器名下<b>已生效</b>的字节（{@link MixinClassBaseline#appliedOf}，
	 * 已成功 redefine、确实在 JVM 里生效过）优先，其次 premain 录口（(加载器, 类名) 键的 defineClass 最终字节）。
	 *
	 * <p>顺序有意义：成功 redefine 之后 JVM 里的那份是 R1+，录口里仍是 R0——基线链
	 * R0→R1→R2 必须取"成功后的字节"，否则第二次热更会拿自己的第一版字节当基线。</p>
	 *
	 * <p>两个来源都以<b>加载器实例身份</b>为键（同一套弱键语义）：同名类在两个加载器下各有一份基线，
	 * 互不冒充。</p>
	 *
	 * <p>未命中时 {@link MixinClassBaseline#baselineOf} 会计一次 {@code missed}（一次拒绝计一次），
	 * 并把类全称 + 加载器标签 + 原因记进点名清单。</p>
	 *
	 * @return 基线字节（内部副本，调用方不得修改）；{@code null} = 没有基线
	 */
	static byte[] baselineBytes(ClassLoader loader, String dotName) {
		return baselineBytes(loader, dotName, true);
	}

	/**
	 * 基线解析口（可关闭未命中计数）。
	 *
	 * @param countMiss 是否把未命中记进 {@code missed} 分母。<b>只有闸 4 该计</b>：分母口径是
	 *                  "一次拒绝计一次"，非闸判据的读取点读到/读不到都不改变拒绝计数。
	 */
	static byte[] baselineBytes(ClassLoader loader, String dotName, boolean countMiss) {
		if (dotName == null) {
			return null;
		}
		byte[] loaded = MixinClassBaseline.appliedOf(loader, dotName);
		if (loaded != null) {
			return loaded;
		}
		return MixinClassBaseline.baselineOf(loader, dotName, countMiss);
	}

	private static void blockHost(String hostDotName, String syntheticName, String why) {
		if (hostDotName != null) {
			BLOCKED.add(hostDotName);
		}
		RemoldAgent.warn("[Mixin] synthetic inner class " + syntheticName + ": " + why
			+ "; refusing the redefine and aborting the host target redefine"
			+ " (restart required if the class was loaded before the agent registered).");
	}

	/**
	 * 在目标类重定义**之前**把已加载的合成内部类重定义到新形态，关闭「目标已换新 <init> 描述符、
	 * 合成类还是旧构造器」的窗口。
	 *
	 * <p>失败只 warn、不抛：目标类的重定义照常进行，随后 {@link #apply} 还会再试一次；最坏情况是
	 * 这一轮仍然存在窗口，必须由调用方看到明确诊断（不能静默）。</p>
	 */
	private static void redefineSyntheticBeforeTarget(Instrumentation inst, Class<?> live, String name, byte[] bytes,
			String hostDotName) {
		if (redefineSyntheticSafely(inst, live, name, bytes, hostDotName)) {
			RemoldAgent.info("[Mixin] synthetic inner class " + name
				+ " redefined BEFORE its target redefine (structural window closed); metrics: "
				+ metricsSummary());
		}
	}

	// ---------------------------------------------------------------- 结构契约

	/**
	 * 比较「运行期已加载的合成内部类」与「本次重新生成的字节」的成员 schema。
	 *
	 * <p>字段按「名字 + 描述符」、方法按「名字 + 描述符」比较，顺序无关。返回 {@code null}
	 * 表示 schema 一致（可以安全 redefine）；否则返回人类可读的差异摘要。</p>
	 */
	static String describeSchemaChange(Class<?> live, ClassNode fresh) {
		Set<String> liveFields = new LinkedHashSet<>();
		for (Field f : live.getDeclaredFields()) {
			liveFields.add(f.getName() + ":" + Type.getDescriptor(f.getType()));
		}
		Set<String> liveMethods = new LinkedHashSet<>();
		for (Method m : live.getDeclaredMethods()) {
			liveMethods.add(m.getName() + Type.getMethodDescriptor(m));
		}
		// 构造器在反射侧不是"方法"，在 ClassNode 侧是 <init>：两侧必须按同一口径归一。
		Set<String> liveCtors = new LinkedHashSet<>();
		for (java.lang.reflect.Constructor<?> c : live.getDeclaredConstructors()) {
			liveCtors.add("<init>" + Type.getConstructorDescriptor(c));
		}
		Set<String> newFields = new LinkedHashSet<>();
		if (fresh.fields != null) {
			for (FieldNode f : fresh.fields) {
				newFields.add(f.name + ":" + f.desc);
			}
		}
		Set<String> newMethods = new LinkedHashSet<>();
		Set<String> newCtors = new LinkedHashSet<>();
		if (fresh.methods != null) {
			for (MethodNode m : fresh.methods) {
				if ("<init>".equals(m.name)) {
					newCtors.add(m.name + m.desc);
				} else if ("<clinit>".equals(m.name)) {
					// 反射侧看不到静态初始化块，无法比对；若真的新增了 <clinit>，
					// JVM 的 redefine 会以 UnsupportedOperationException 拒绝，由调用方上报。
					continue;
				} else {
					newMethods.add(m.name + m.desc);
				}
			}
		}
		Set<String> missingFields = minus(liveFields, newFields);
		Set<String> addedFields = minus(newFields, liveFields);
		Set<String> missingMethods = minus(liveMethods, newMethods);
		Set<String> addedMethods = minus(newMethods, liveMethods);
		Set<String> missingCtors = minus(liveCtors, newCtors);
		Set<String> addedCtors = minus(newCtors, liveCtors);
		if (missingFields.isEmpty() && addedFields.isEmpty()
			&& missingMethods.isEmpty() && addedMethods.isEmpty()
			&& missingCtors.isEmpty() && addedCtors.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder("member schema changed:");
		appendDiff(sb, "fields removed", missingFields);
		appendDiff(sb, "fields added", addedFields);
		appendDiff(sb, "methods removed", missingMethods);
		appendDiff(sb, "methods added", addedMethods);
		appendDiff(sb, "constructors removed", missingCtors);
		appendDiff(sb, "constructors added", addedCtors);
		sb.append(" — the JVM cannot redefine a class whose members changed; restart the client"
			+ " (or keep the anonymous class's member set stable while iterating on its body)");
		return sb.toString();
	}

	private static void appendDiff(StringBuilder sb, String label, Set<String> values) {
		if (values.isEmpty()) {
			return;
		}
		sb.append(' ').append(label).append('=').append(values);
	}

	private static Set<String> minus(Set<String> from, Set<String> other) {
		Set<String> out = new LinkedHashSet<>(from);
		out.removeAll(other);
		return out;
	}

	private static byte[] serialize(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	// ---------------------------------------------------------------- 定位

	private static Object findInnerClassGenerator(Object transformer) throws Exception {
		Object processor = readField(transformer, "processor");
		if (processor == null) {
			return null;
		}
		Object extensions = readField(processor, "extensions");
		if (extensions == null) {
			return null;
		}
		Method getGenerators = extensions.getClass().getMethod("getGenerators");
		getGenerators.setAccessible(true);
		Object list = getGenerators.invoke(extensions);
		if (!(list instanceof List)) {
			return null;
		}
		synchronized (list) {
			for (Object generator : (List<?>) list) {
				if (generator != null && "InnerClassGenerator".equals(generator.getClass().getSimpleName())) {
					return generator;
				}
			}
		}
		return null;
	}

	private static Class<?> findLoadedClass(Instrumentation inst, String dotClassName) {
		// 同名多副本时选宿主加载器权威副本，拒绝 AppClassLoader 裸副本（见 ClassCopySelector）
		return remold.ClassCopySelector.pickNamed(inst.getAllLoadedClasses(), dotClassName);
	}

	private static Object readField(Object target, String name) throws Exception {
		Field f = findField(target.getClass(), name);
		if (f == null) {
			return null;
		}
		return f.get(target);
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> type, String name, Class<?>... params) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				Method m = c.getDeclaredMethod(name, params);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	private static Object invoke(Object target, String name) throws Exception {
		Method m = findMethod(target.getClass(), name);
		if (m == null) {
			return null;
		}
		return m.invoke(target);
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		String message = cur.getMessage();
		return cur.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
