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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

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
 */
final class MixinInnerClassHotSwap {

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
				if (schemaDiff != null) {
					result.failures.add(conformedDotted + " (" + schemaDiff + ")");
					continue;
				}
				byte[] bytes = serialize(fresh);
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
		for (Class<?> candidate : inst.getAllLoadedClasses()) {
			if (candidate != null && dotClassName.equals(candidate.getName())) {
				return candidate;
			}
		}
		return null;
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
