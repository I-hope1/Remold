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
				byte[] bytes = serialize(fresh);
				// 回灌：合成内部类磁盘上没有 .class、也不经过 Remold 的 transformer，不回灌则
				// Nest 扫描 incomplete、构造器可见性判定失败（InitFix 的字段初始化 patch 被拒）。
				registerGenerated(conformedDotted, bytes);
				// 标成 Remold 自己发起的重定义：否则 AnnotationTransformer 会把这次 redefine 当成
				// 外部热更，在已经织入过的合成类上再跑一遍桥接。
				RemoldAgent.enterInternalRedefine(java.util.Collections.singleton(conformedDotted));
				try {
					inst.redefineClasses(new ClassDefinition(live, bytes));
				} finally {
					RemoldAgent.exitInternalRedefine();
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
				byte[] bytes = serialize(fresh);
				byte[] previous = generatedBytes(conformedDotted);
				registerGenerated(conformedDotted, bytes);
				registered++;
				// 结构变更窗口：目标类即将被重定义成「调用新 <init> 描述符」的版本，而合成类若
				// 仍是旧形态，两者之间的窗口内任何一次调用都会 NoSuchMethodError。这里在**目标类
				// redefine 之前**先把已加载的合成类重定义到新形态（redefineDynamic 的桥接阶段在
				// redefineClasses 之前，因此顺序天然正确）。
				if (inst != null) {
					Class<?> live = findLoadedClass(inst, conformedDotted);
					if (live != null) {
						// 不仅要看覆盖层字节是否变化，还要看**运行期已加载类**的成员 schema：
						// 上一次失败的重定义可能让覆盖层已是最新、而 JVM 里仍是旧构造器（陈旧 schema），
						// 这种情形下 overlay 比较会漏判。
						boolean bytesChanged = previous == null || !java.util.Arrays.equals(previous, bytes);
						boolean staleSchema = describeSchemaChange(live, fresh) != null;
						if (bytesChanged || staleSchema) {
							redefineSyntheticBeforeTarget(inst, live, conformedDotted, bytes);
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
	 * 在目标类重定义**之前**把已加载的合成内部类重定义到新形态，关闭「目标已换新 <init> 描述符、
	 * 合成类还是旧构造器」的窗口。
	 *
	 * <p>失败只 warn、不抛：目标类的重定义照常进行，随后 {@link #apply} 还会再试一次；最坏情况是
	 * 这一轮仍然存在窗口，必须由调用方看到明确诊断（不能静默）。</p>
	 */
	private static void redefineSyntheticBeforeTarget(Instrumentation inst, Class<?> live, String name, byte[] bytes) {
		try {
			RemoldAgent.enterInternalRedefine(java.util.Collections.singleton(name));
			try {
				inst.redefineClasses(new ClassDefinition(live, bytes));
			} finally {
				RemoldAgent.exitInternalRedefine();
			}
			RemoldAgent.info("[Mixin] synthetic inner class " + name
				+ " redefined BEFORE its target redefine (structural window closed)");
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] unable to redefine synthetic inner class " + name
				+ " before its target redefine (" + rootMessage(t) + "); the target may still call the old"
				+ " constructor until the follow-up inner-class hot update runs");
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
