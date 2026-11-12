import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import remold.ClassDiffUtil;
import remold.RemoldAgent;

/**
 * 注解<b>默认值</b>（{@code AnnotationDefault}）变化探针（批次 J）。
 *
 * <p><b>它回答什么</b>：把注解类型的 {@code AnnotationDefault} 改掉（{@code default "v1"} →
 * {@code default "v2"}）再 {@code redefineClasses} 之后，运行期<b>实际读到</b>什么。三组读数按
 * 指挥部口径分开记：① 重定义前拿到的旧 {@code Method} 句柄（另分 warm/cold）；② 重定义后
 * 重新 {@code getDeclaredMethods()}；③ 重定义后首次解析。另加消费者侧终局读数：注解实例的
 * 成员值（{@code @Mark} 未显式赋值 → 只能取默认值），分"旧实例 / 重定义后新解析的类注解 /
 * 重定义后新解析的方法注解"三档。</p>
 *
 * <p><b>结论纪律</b>：本探针只打印读数与真实 {@code Throwable} 类名，不做任何"应该会抛"、
 * "应该读到旧值"的预设；批次 J 的机制描述在探针读数出来之前一律是 {@code [推断]}。</p>
 *
 * <p><b>灵敏度（阳性）对照</b>：{@code sensitivity-explicit-value-change} 改的是消费者注解里
 * <b>显式写死</b>的成员值（不是默认值），读数必须跟着变——它只证明"这条读数链路能看见注解值
 * 变化"，<b>不</b>构成默认值复现证据。</p>
 *
 * <p><b>跑法</b>（三格矩阵，见 {@code docs} 里的批次 J 报告）：
 * {@code java -javaagent:tests/build/p05/p05-agent.jar -cp tests/build/classes/java/main
 * AnnotationDefaultProbe}，用不同的 {@code java} 与是否附加
 * {@code -XX:+AllowEnhancedClassRedefinition} 区分格①/②/③；格子身份由本探针自己打印的
 * {@code structuralRedefine} 读数判定，不靠参数名推断。</p>
 */
public final class AnnotationDefaultProbe {

	/** 注解类型（它的 {@code AnnotationDefault} 是被改的对象）。 */
	private static final String ANNOTATION_CLASS = "probe.dflt.Mark";

	/** 消费者：用 {@code @Mark} 且<b>不</b>显式写成员值，因此取值只能来自默认值。 */
	private static final String CONSUMER_CLASS = "probe.fix.Consumer";

	public static void main(String[] args) throws Exception {
		System.out.println("=== annotation default (AnnotationDefault) drift probe — batch J ===");
		// 运行标识：同一探针在不同轮次/不同提交上的输出必须可区分（避免"同哈希"被读成缓存命中）。
		System.out.println("PROBE-RUN tag=" + (args.length == 0 ? "<none>" : args[args.length - 1])
			+ " proot=" + System.getProperty("user.dir"));
		if (args.length > 0 && "gate".equals(args[0])) {
			// 门禁模式：跑真实 RemoldAgent（-javaagent:core/build/libs/remold-agent.jar），
			// 验证"默认值变化→拒绝"与"无关变更→照常重定义"两条门禁行为。
			reportEnvironment();
			gateChecks();
			return;
		}
		reportEnvironment();
		System.out.println("PROBE-CAP capability=structuralRedefine value=" + structuralCapability());

		List<Scenario> scenarios = new ArrayList<>();
		scenarios.add(sensitivityControl());
		scenarios.add(runDefaultScenario("control-identical-default", "v1", "v1"));
		scenarios.add(runDefaultScenario("default-value-change", "v1", "v2"));
		scenarios.add(runDefaultScenario("default-removed", "v1", null));
		scenarios.add(runDefaultAddedScenario());

		for (Scenario scenario : scenarios) {
			System.out.println("--- scenario: " + scenario.label);
			System.out.println("PROBE-REDEFINE scenario=" + scenario.label + " outcome=" + scenario.redefineOutcome);
			for (Reading reading : scenario.readings) {
				System.out.println(reading);
			}
			System.out.println(scenario.summary());
		}
		comparatorChecks();
		System.out.println("=== probe finished: " + scenarios.size() + " scenario(s) ===");
	}

	/**
	 * 比对器自检（{@link ClassDiffUtil#describeAnnotationDefaultChange} 的判定力）：
	 * 必须能看见默认值变化，对"同一份字节 / 非注解类"沉默，解析失败时 fail-closed 给出不可判定原因；
	 * 另打印降级通道的默认状态（必须 false = 默认拒绝）。
	 *
	 * <p>这一段需要 core 在 classpath 上（见类注释里的运行命令）。</p>
	 */
	private static void comparatorChecks() {
		byte[] v1 = compile(Map.of(ANNOTATION_CLASS, annotationSource("v1"))).get(ANNOTATION_CLASS);
		byte[] v2 = compile(Map.of(ANNOTATION_CLASS, annotationSource("v2"))).get(ANNOTATION_CLASS);
		byte[] noDefault = compile(Map.of(ANNOTATION_CLASS, annotationSource(null))).get(ANNOTATION_CLASS);
		Map<String, byte[]> support = compile(Map.of(
			ANNOTATION_CLASS, annotationSource("v1"),
			"probe.fix.Consumer", consumerSource("Consumer", "")));
		byte[] consumer = support.get("probe.fix.Consumer");
		byte[] truncated = java.util.Arrays.copyOf(v1, v1.length / 2);

		compare("control-identical", ClassDiffUtil.describeAnnotationDefaultChange(v1, v1), false);
		compare("default-changed", ClassDiffUtil.describeAnnotationDefaultChange(v1, v2), true);
		compare("default-removed", ClassDiffUtil.describeAnnotationDefaultChange(v1, noDefault), true);
		compare("default-added", ClassDiffUtil.describeAnnotationDefaultChange(noDefault, v1), true);
		compare("non-annotation-class", ClassDiffUtil.describeAnnotationDefaultChange(consumer, consumer), false);
		compare("unparseable-fail-closed", ClassDiffUtil.describeAnnotationDefaultChange(truncated, v2), true);
		System.out.println("PROBE-GATE annotation_default_change_allowed="
			+ RemoldAgent.annotationDefaultChangeAllowed() + " expect=false-by-default");
	}

	private static void compare(String label, String result, boolean expectChanged) {
		boolean changed = result != null;
		System.out.println("PROBE-COMPARE label=" + label + " changed=" + changed
			+ " expectChanged=" + expectChanged + " ok=" + (changed == expectChanged)
			+ " detail=" + (result == null ? "<none>" : result));
	}

	/**
	 * 门禁（C5）端到端读数：直接用真实 {@link RemoldAgent#redefineDynamic} 驱动，不模拟。
	 *
	 * <p>三组：① 注解默认值变化 → 必须被拒绝（且注解类型仍停留在旧默认值）；
	 * ② 无关变更（普通类方法体 v1→v2）→ 必须照常重定义（换无关输入对照，证明门不是全闭的）；
	 * ③ 显式降级通道（{@code -Dremold.agent.annotation_default_change=allow}）→ 放行，
	 * 且读数显示已加载消费者仍读旧默认值（这正是默认必须拒绝的原因）。</p>
	 */
	private static void gateChecks() throws Exception {
		if (RemoldAgent.getInstrumentation() == null) {
			System.out.println("PROBE-GATE end-to-end=SKIPPED(no instrumentation; run with"
				+ " -javaagent:core/build/libs/remold-agent.jar)");
			return;
		}
		System.out.println("PROBE-GATE annotation_default_change_allowed="
			+ RemoldAgent.annotationDefaultChangeAllowed());

		// ① 默认值变化：必须拒绝。
		byte[] v1 = compile(Map.of(ANNOTATION_CLASS, annotationSource("v1"))).get(ANNOTATION_CLASS);
		byte[] v2 = compile(Map.of(ANNOTATION_CLASS, annotationSource("v2"))).get(ANNOTATION_CLASS);
		ProbeLoader loader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(), Map.of());
		Class<?> mark = loader.define(ANNOTATION_CLASS, v1);
		RemoldAgent.bytecodeCache.put(ANNOTATION_CLASS, v1);
		boolean accepted = RemoldAgent.redefineDynamic(mark, v2);
		Object defaultAfter;
		try {
			defaultAfter = mark.getDeclaredMethod("value").getDefaultValue();
		} catch (Throwable t) {
			defaultAfter = "THROWN:" + t.getClass().getName();
		}
		System.out.println("PROBE-GATE case=annotation-default-change accepted=" + accepted
			+ " defaultAfter=" + defaultAfter
			+ (accepted ? "" : " (refused as expected — the class keeps its old default)"));

		// ② 换无关输入：普通类方法体 v1→v2 必须照常被接受，且新行为真的生效（终局断言）。
		String plainName = "probe.fix.Plain";
		byte[] plainV1 = compile(Map.of(plainName, "package probe.fix;\n"
			+ "public class Plain { public String who() { return \"v1\"; } }\n")).get(plainName);
		byte[] plainV2 = compile(Map.of(plainName, "package probe.fix;\n"
			+ "public class Plain { public String who() { return \"v2\"; } }\n")).get(plainName);
		ProbeLoader plainLoader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(), Map.of());
		Class<?> plain = plainLoader.define(plainName, plainV1);
		RemoldAgent.bytecodeCache.put(plainName, plainV1);
		boolean plainAccepted = RemoldAgent.redefineDynamic(plain, plainV2);
		Object who;
		try {
			who = plain.getDeclaredConstructor().newInstance().getClass().getMethod("who")
				.invoke(plain.getDeclaredConstructor().newInstance());
		} catch (Throwable t) {
			who = "THROWN:" + t.getClass().getName();
		}
		System.out.println("PROBE-GATE case=unrelated-body-change accepted=" + plainAccepted
			+ " who()=" + who);

		// ③ 显式降级通道：允许，但已加载消费者仍读旧默认值（这就是默认要拒绝的根因读数）。
		if (RemoldAgent.annotationDefaultChangeAllowed()) {
			byte[] v3 = compile(Map.of(ANNOTATION_CLASS, annotationSource("v3"))).get(ANNOTATION_CLASS);
			ProbeLoader allowLoader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(), Map.of());
			Class<?> mark2 = allowLoader.define(ANNOTATION_CLASS, v1);
			RemoldAgent.bytecodeCache.put(ANNOTATION_CLASS, v1);
			byte[] consumer = compile(Map.of(ANNOTATION_CLASS, annotationSource("v1"),
				"probe.fix.Consumer", consumerSource("Consumer", ""))).get("probe.fix.Consumer");
			Class<?> consumerType = allowLoader.define("probe.fix.Consumer", consumer);
			Annotation beforeAnnotation = consumerType.getAnnotation(
				annotationClassOf(allowLoader, ANNOTATION_CLASS));
			boolean allowAccepted = RemoldAgent.redefineDynamic(mark2, v3);
			Annotation afterAnnotation = consumerType.getAnnotation(
				annotationClassOf(allowLoader, ANNOTATION_CLASS));
			System.out.println("PROBE-GATE case=explicit-allow accepted=" + allowAccepted
				+ " consumerBefore=" + defaultValueOf(beforeAnnotation)
				+ " consumerAfter=" + defaultValueOf(afterAnnotation));
		}
	}

	private static String defaultValueOf(Annotation annotation) {
		if (annotation == null) return "null-annotation";
		try {
			Method member = annotation.annotationType().getDeclaredMethod("value");
			return String.valueOf(member.invoke(annotation));
		} catch (Throwable t) {
			return "THROWN:" + t.getClass().getName()
				+ (t.getCause() == null ? "" : " cause=" + t.getCause().getClass().getName());
		}
	}

	// ---------------------------------------------------------------- 环境与能力

	private static void reportEnvironment() {
		System.out.println("PROBE-ENV java.version=" + System.getProperty("java.version"));
		System.out.println("PROBE-ENV java.vm.name=" + System.getProperty("java.vm.name"));
		System.out.println("PROBE-ENV java.vm.version=" + System.getProperty("java.vm.version"));
		System.out.println("PROBE-ENV java.home=" + System.getProperty("java.home"));
		System.out.println("PROBE-ENV java.runtime.version=" + System.getProperty("java.runtime.version"));
		System.out.println("PROBE-ENV java.vm.info=" + System.getProperty("java.vm.info"));
		System.out.println("PROBE-ENV os.name=" + System.getProperty("os.name")
			+ " os.arch=" + System.getProperty("os.arch"));
		List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
		for (String argument : arguments) {
			System.out.println("PROBE-ENV inputArgument=" + argument);
		}
	}

	/**
	 * 真实能力判定："给已有类加一个字段再 redefine" 被接受 = 结构化重定义可用。
	 *
	 * <p>不读参数名、不猜 JVM 厂商：标准 HotSpot 会拒绝，JBR/DCEVM 在
	 * {@code -XX:+AllowEnhancedClassRedefinition} 下接受。</p>
	 */
	private static String structuralCapability() {
		Instrumentation instrumentation = P05Agent.instrumentation();
		if (instrumentation == null) {
			return "NO-INSTRUMENTATION";
		}
		try {
			Map<String, byte[]> one = compile(Map.of("probe.cap.Cap",
				"package probe.cap;\npublic class Cap { public int a; }\n"));
			Map<String, byte[]> two = compile(Map.of("probe.cap.Cap",
				"package probe.cap;\npublic class Cap { public int a; public int b; }\n"));
			ProbeLoader loader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(), Map.of());
			Class<?> target = loader.define("probe.cap.Cap", one.get("probe.cap.Cap"));
			instrumentation.redefineClasses(new ClassDefinition(target, two.get("probe.cap.Cap")));
			return "YES(field-added-accepted)";
		} catch (Throwable t) {
			return "NO(field-added-rejected:" + t.getClass().getName() + ")";
		}
	}

	// ---------------------------------------------------------------- 场景

	/**
	 * 灵敏度（阳性）对照：改消费者里<b>显式写死</b>的注解值（{@code @Mark("v1")} → {@code @Mark("v2")}），
	 * 重定义消费者类。读数必须从 v1 变 v2，否则说明读数链路本身就看不见注解值变化，后续默认值读数不可信。
	 */
	private static Scenario sensitivityControl() {
		Scenario scenario = new Scenario("sensitivity-explicit-value-change");
		Instrumentation instrumentation = P05Agent.instrumentation();
		Map<String, byte[]> before = compile(Map.of(
			ANNOTATION_CLASS, annotationSource("v1"),
			"probe.fix.Consumer", consumerSource("Consumer", "(\"v1\")")));
		Map<String, byte[]> after = compile(Map.of(
			ANNOTATION_CLASS, annotationSource("v1"),
			"probe.fix.Consumer", consumerSource("Consumer", "(\"v2\")")));
		if (instrumentation == null) {
			scenario.redefineOutcome = "NO-INSTRUMENTATION";
			return scenario;
		}
		ProbeLoader loader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(),
			Map.of(ANNOTATION_CLASS, before.get(ANNOTATION_CLASS)));
		Class<?> consumer;
		try {
			consumer = loader.define(CONSUMER_CLASS, before.get(CONSUMER_CLASS));
		} catch (Throwable t) {
			scenario.redefineOutcome = "LOAD-FAILED:" + t.getClass().getName();
			record(scenario, "load", "defineClass", CONSUMER_CLASS, t);
			return scenario;
		}
		Method tagged;
		try {
			tagged = consumer.getDeclaredMethod("tagged");
			readMemberValue(scenario, "pre-warm", "explicit-value", consumer.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS)));
			readMemberValue(scenario, "pre-warm", "explicit-value(method)", tagged.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS)));
		} catch (Throwable t) {
			record(scenario, "pre-warm", "getDeclaredMethod", "tagged", t);
			return scenario;
		}
		try {
			instrumentation.redefineClasses(new ClassDefinition(consumer, after.get(CONSUMER_CLASS)));
			scenario.redefineOutcome = "OK";
		} catch (Throwable t) {
			scenario.redefineOutcome = "THROWN:" + t.getClass().getName()
				+ (t.getMessage() == null ? "" : ": " + t.getMessage());
			record(scenario, "redefine", "redefineClasses", CONSUMER_CLASS, t);
			return scenario;
		}
		readMemberValue(scenario, "R2-fresh-array", "explicit-value", consumer.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS)));
		readMemberValue(scenario, "R2-fresh-array", "explicit-value(method)",
			tagged.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS)));
		return scenario;
	}

	/**
	 * 默认值场景：{@code beforeDefault == afterDefault} 是"去触发"对照（只重定义、不改默认值）；
	 * 否则是真正的默认值变化。{@code afterDefault == null} 表示变更后该成员<b>没有默认值</b>。
	 */
	private static Scenario runDefaultScenario(String label, String beforeDefault, String afterDefault) {
		Scenario scenario = new Scenario(label + "(" + beforeDefault + "->"
			+ (afterDefault == null ? "<no default>" : afterDefault) + ")");
		Instrumentation instrumentation = P05Agent.instrumentation();
		if (instrumentation == null) {
			scenario.redefineOutcome = "NO-INSTRUMENTATION";
			return scenario;
		}
		// 消费者字节在变更前后<b>完全一致</b>（不变），因此只需要重新编译注解类型：
		// "默认值被移除"的那一版消费者（@Mark 不带成员值）在新注解下本来就编不过，不能参与编译。
		Map<String, byte[]> before = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(beforeDefault),
			"probe.fix.Consumer", consumerSource("Consumer", ""),
			"probe.fix.Reader", consumerSource("Reader", ""),
			"probe.fix.Late", consumerSource("Late", "")));
		byte[] beforeAnnotation = before.get(ANNOTATION_CLASS);
		byte[] afterAnnotation = compile(Map.of(ANNOTATION_CLASS, annotationSource(afterDefault)))
			.get(ANNOTATION_CLASS);
		// 支撑表留空：注解类型随后显式 define 一次；若同时放进支撑表会被 define 第二遍 → LinkageError。
		ProbeLoader loader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(), Map.of());

		Class<?> annotationType;
		Class<?> consumer;
		Class<?> lateConsumer;
		try {
			annotationType = loader.define(ANNOTATION_CLASS, beforeAnnotation);
			consumer = loader.define(CONSUMER_CLASS, before.get(CONSUMER_CLASS));
			// Late：重定义之前就加载，但它的注解一次都不读（首次解析发生在重定义之后）。
			lateConsumer = loader.define("probe.fix.Late", before.get("probe.fix.Late"));
		} catch (Throwable t) {
			scenario.redefineOutcome = "LOAD-FAILED:" + t.getClass().getName();
			record(scenario, "load", "defineClass", CONSUMER_CLASS, t);
			return scenario;
		}
		System.out.println("PROBE-FIXTURE scenario=" + scenario.label
			+ " annotationBytes=" + beforeAnnotation.length + "->" + afterAnnotation.length
			+ " consumerBytes=" + before.get(CONSUMER_CLASS).length + "(unchanged)");

		// 重定义前取句柄：warm 组立刻读一次；cold 组一次都不读（它的"首次解析"发生在重定义之后）。
		Method warmDefaultHandle;
		Method coldDefaultHandle;
		Annotation oldClassAnnotation;
		Annotation oldMethodAnnotation;
		Method tagged;
		try {
			warmDefaultHandle = annotationType.getDeclaredMethod("value");
			Method[] declared = annotationType.getDeclaredMethods();
			coldDefaultHandle = null;
			for (Method candidate : declared) {
				if ("value".equals(candidate.getName())) {
					coldDefaultHandle = candidate;
				}
			}
			if (coldDefaultHandle == null) {
				throw new IllegalStateException("cold handle: annotation type has no value() member");
			}
			readDefaultValue(scenario, "pre-warm", "value", warmDefaultHandle);
			tagged = consumer.getDeclaredMethod("tagged");
			oldClassAnnotation = consumer.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS));
			oldMethodAnnotation = tagged.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS));
			readMemberValue(scenario, "pre-warm", "class-level", oldClassAnnotation);
			readMemberValue(scenario, "pre-warm", "method-level", oldMethodAnnotation);
		} catch (Throwable t) {
			record(scenario, "pre-warm", "getDeclaredMethod", "value/tagged", t);
			return scenario;
		}

		try {
			instrumentation.redefineClasses(new ClassDefinition(annotationType, afterAnnotation));
			scenario.redefineOutcome = "OK";
		} catch (Throwable t) {
			scenario.redefineOutcome = "THROWN:" + t.getClass().getName()
				+ (t.getMessage() == null ? "" : ": " + t.getMessage());
			record(scenario, "redefine", "redefineClasses", ANNOTATION_CLASS, t);
			return scenario;
		}

		// ① 旧句柄（重定义前读过默认值）
		readDefaultValue(scenario, "R1-stale-warm", "value", warmDefaultHandle);
		// ①b 旧句柄（重定义前一次都没读过 → 它的首次解析在重定义之后）
		readDefaultValue(scenario, "R1-stale-cold", "value", coldDefaultHandle);
		// ② 重新 getDeclaredMethods()
		try {
			for (Method method : annotationType.getDeclaredMethods()) {
				if ("value".equals(method.getName())) {
					readDefaultValue(scenario, "R2-fresh-array", "value", method);
				}
			}
		} catch (Throwable t) {
			record(scenario, "R2-fresh-array", "getDeclaredMethods", ANNOTATION_CLASS, t);
		}
		// ③ 首次解析：重定义之后才按名字取句柄，再读
		try {
			readDefaultValue(scenario, "R3-first-resolve", "value", annotationType.getDeclaredMethod("value"));
		} catch (Throwable t) {
			record(scenario, "R3-first-resolve", "getDeclaredMethod", "value", t);
		}

		// 消费者侧终局读数：默认值只有在"消费者没有显式赋值"时才可能被读到。
		Class<? extends Annotation> annotationClass = annotationClassOf(loader, ANNOTATION_CLASS);
		readMemberValue(scenario, "C1-old-instance", "class-level", oldClassAnnotation);
		readMemberValue(scenario, "C2-fresh-class-annotation", "class-level", consumer.getAnnotation(annotationClass));
		readMemberValue(scenario, "C3-fresh-method-annotation", "method-level", tagged.getAnnotation(annotationClass));
		readMemberValue(scenario, "C4-fresh-old-instance-method", "method-level", oldMethodAnnotation);
		// C5：重定义之后才定义的消费者类（同一个加载器、同一个注解类型 → 同一份注解类型缓存）。
		// 若它也读到 v1，说明旧默认值挂在"注解类型"上（类型级缓存），不是消费者类的反射数据快照。
		try {
			Class<?> reader = loader.define("probe.fix.Reader", before.get("probe.fix.Reader"));
			readMemberValue(scenario, "C5-post-redefine-new-consumer", "class-level", reader.getAnnotation(annotationClass));
		} catch (Throwable t) {
			record(scenario, "C5-post-redefine-new-consumer", "defineClass", "probe.fix.Reader", t);
		}
		// C6：加载在重定义之前、但注解**一次都没读过**的消费者类（首次解析在重定义之后）：
		// 它若读到 v2，说明旧值只黏在"重定义前解析过注解的类"上；若读到 v1，说明加载期就绑定了。
		readMemberValue(scenario, "C6-loaded-before-never-read", "class-level", lateConsumer.getAnnotation(annotationClass));
		return scenario;
	}

	/**
	 * 场景 {@code default added}（成员原本<b>没有</b>默认值，变更后有了默认值）。
	 *
	 * <p>指挥部裁定：默认拒绝（与 changed/removed 一致，fail-closed），机制描述标 {@code [推断]}，
	 * 放行与否等三格读数出来再议。消费者在两侧都必须显式赋值才能编过，所以"消费者终局"读的是
	 * 显式值（对照）；真正的默认值读数来自元数据路径，以及"重定义之后才编译并加载的裸 {@code @Mark}
	 * 消费者"（只有新注解类型下才编得过，它读到什么就是新默认值对外部世界的可见形态）。</p>
	 */
	private static Scenario runDefaultAddedScenario() {
		Scenario scenario = new Scenario("default-added(<no default>->v1)");
		Instrumentation instrumentation = P05Agent.instrumentation();
		if (instrumentation == null) {
			scenario.redefineOutcome = "NO-INSTRUMENTATION";
			return scenario;
		}
		Map<String, byte[]> before = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(null),
			"probe.fix.Consumer", consumerSource("Consumer", "(\"explicit\")"),
			"probe.fix.Reader", consumerSource("Reader", "(\"explicit\")"),
			"probe.fix.Late", consumerSource("Late", "(\"explicit\")")));
		Map<String, byte[]> after = compile(Map.of(
			ANNOTATION_CLASS, annotationSource("v1"),
			"probe.fix.Bare", consumerSource("Bare", "")));
		byte[] beforeAnnotation = before.get(ANNOTATION_CLASS);
		byte[] afterAnnotation = after.get(ANNOTATION_CLASS);
		ProbeLoader loader = new ProbeLoader(AnnotationDefaultProbe.class.getClassLoader(), Map.of());

		Class<?> annotationType;
		Class<?> consumer;
		Class<?> lateConsumer;
		try {
			annotationType = loader.define(ANNOTATION_CLASS, beforeAnnotation);
			consumer = loader.define(CONSUMER_CLASS, before.get(CONSUMER_CLASS));
			lateConsumer = loader.define("probe.fix.Late", before.get("probe.fix.Late"));
		} catch (Throwable t) {
			scenario.redefineOutcome = "LOAD-FAILED:" + t.getClass().getName();
			record(scenario, "load", "defineClass", CONSUMER_CLASS, t);
			return scenario;
		}
		System.out.println("PROBE-FIXTURE scenario=" + scenario.label
			+ " annotationBytes=" + beforeAnnotation.length + "->" + afterAnnotation.length
			+ " consumerBytes=" + before.get(CONSUMER_CLASS).length + "(unchanged)");

		Method warmDefaultHandle;
		Method coldDefaultHandle;
		Annotation oldClassAnnotation;
		Annotation oldMethodAnnotation;
		Method tagged;
		try {
			warmDefaultHandle = annotationType.getDeclaredMethod("value");
			coldDefaultHandle = null;
			for (Method candidate : annotationType.getDeclaredMethods()) {
				if ("value".equals(candidate.getName())) {
					coldDefaultHandle = candidate;
				}
			}
			if (coldDefaultHandle == null) {
				throw new IllegalStateException("cold handle: annotation type has no value() member");
			}
			readDefaultValue(scenario, "pre-warm", "value", warmDefaultHandle);
			tagged = consumer.getDeclaredMethod("tagged");
			oldClassAnnotation = consumer.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS));
			oldMethodAnnotation = tagged.getAnnotation(annotationClassOf(loader, ANNOTATION_CLASS));
			readMemberValue(scenario, "pre-warm", "class-level", oldClassAnnotation);
			readMemberValue(scenario, "pre-warm", "method-level", oldMethodAnnotation);
		} catch (Throwable t) {
			record(scenario, "pre-warm", "getDeclaredMethod", "value/tagged", t);
			return scenario;
		}

		try {
			instrumentation.redefineClasses(new ClassDefinition(annotationType, afterAnnotation));
			scenario.redefineOutcome = "OK";
		} catch (Throwable t) {
			scenario.redefineOutcome = "THROWN:" + t.getClass().getName()
				+ (t.getMessage() == null ? "" : ": " + t.getMessage());
			record(scenario, "redefine", "redefineClasses", ANNOTATION_CLASS, t);
			return scenario;
		}

		readDefaultValue(scenario, "R1-stale-warm", "value", warmDefaultHandle);
		readDefaultValue(scenario, "R1-stale-cold", "value", coldDefaultHandle);
		try {
			for (Method method : annotationType.getDeclaredMethods()) {
				if ("value".equals(method.getName())) {
					readDefaultValue(scenario, "R2-fresh-array", "value", method);
				}
			}
		} catch (Throwable t) {
			record(scenario, "R2-fresh-array", "getDeclaredMethods", ANNOTATION_CLASS, t);
		}
		try {
			readDefaultValue(scenario, "R3-first-resolve", "value",
				annotationType.getDeclaredMethod("value"));
		} catch (Throwable t) {
			record(scenario, "R3-first-resolve", "getDeclaredMethod", "value", t);
		}

		Class<? extends Annotation> annotationClass = annotationClassOf(loader, ANNOTATION_CLASS);
		readMemberValue(scenario, "C1-old-instance", "class-level", oldClassAnnotation);
		readMemberValue(scenario, "C2-fresh-class-annotation", "class-level", consumer.getAnnotation(annotationClass));
		readMemberValue(scenario, "C3-fresh-method-annotation", "method-level", tagged.getAnnotation(annotationClass));
		readMemberValue(scenario, "C4-fresh-old-instance-method", "method-level", oldMethodAnnotation);
		// C5：重定义之后才定义、且用"裸 @Mark"（不写成员值）——这条是 default added 的真实用法。
		try {
			Class<?> bare = loader.define("probe.fix.Bare", after.get("probe.fix.Bare"));
			readMemberValue(scenario, "C5-post-redefine-bare-consumer", "class-level",
				bare.getAnnotation(annotationClass));
		} catch (Throwable t) {
			record(scenario, "C5-post-redefine-bare-consumer", "defineClass", "probe.fix.Bare", t);
		}
		// C6：重定义前加载、但注解一次都没读过（显式值，作对照）。
		readMemberValue(scenario, "C6-loaded-before-never-read", "class-level",
			lateConsumer.getAnnotation(annotationClass));
		return scenario;
	}

	// ---------------------------------------------------------------- 读数实现

	private static void readDefaultValue(Scenario scenario, String stage, String subject, Method method) {
		read(scenario, stage, "getDefaultValue", subject, () -> {
			Object value = method.getDefaultValue();
			return value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
		});
	}

	/** 读注解实例的成员值 {@code value()}：默认值变化在业务侧唯一可观测的终局。 */
	private static void readMemberValue(Scenario scenario, String stage, String subject, Annotation annotation) {
		read(scenario, stage, "annotation.value()", subject, () -> {
			if (annotation == null) {
				return "null-annotation";
			}
			Method member = annotation.annotationType().getDeclaredMethod("value");
			Object value = member.invoke(annotation);
			return value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
		});
	}

	/**
	 * 取注解类型（同一 ProbeLoader 里已定义）：只做类型擦除，取值一律走反射，
	 * 探针不在编译期硬链接被测夹具类型。
	 */
	@SuppressWarnings("unchecked")
	private static Class<? extends Annotation> annotationClassOf(ClassLoader loader, String binaryName) {
		try {
			return (Class<? extends Annotation>) Class.forName(binaryName, false, loader);
		} catch (ClassNotFoundException notFound) {
			throw new IllegalStateException("annotation type is not visible to the probe loader: " + binaryName,
				notFound);
		}
	}

	private interface ValueSupplier {
		String get() throws Throwable;
	}

	private static void read(Scenario scenario, String stage, String api, String subject, ValueSupplier supplier) {
		try {
			scenario.readings.add(new Reading(stage, api, subject, "OK:" + supplier.get(), false, null));
		} catch (Throwable t) {
			record(scenario, stage, api, subject, t);
		}
	}

	private static void record(Scenario scenario, String stage, String api, String subject, Throwable t) {
		boolean errorLevel = t instanceof Error;
		String message = t.getMessage();
		String outcome = "THROWN:" + t.getClass().getName() + (message == null ? "" : ": " + message)
			+ causeChain(t);
		scenario.readings.add(new Reading(stage, api, subject, outcome, errorLevel, t.getClass().getName()));
		if (errorLevel && scenario.firstErrorClass == null) {
			scenario.firstErrorClass = t.getClass().getName();
			scenario.firstErrorStage = stage;
			scenario.firstErrorApi = api;
		}
	}

	/**
	 * 真实失败类名：反射会把注解成员抛出的异常包成 {@code InvocationTargetException}，
	 * 只记包装类会丢掉真正可 grep 的失败形态（{@code IncompleteAnnotationException} 等），
	 * 因此把 cause 链的类名一并打出来。
	 */
	private static String causeChain(Throwable t) {
		StringBuilder chain = new StringBuilder();
		Throwable cause = t.getCause();
		int depth = 0;
		while (cause != null && depth < 4) {
			chain.append(depth == 0 ? " cause=" : " <- ").append(cause.getClass().getName());
			cause = cause.getCause();
			depth++;
		}
		return chain.toString();
	}

	/** 一次读数：阶段、API、对象、结果（含真实 Throwable 类名）。 */
	static final class Reading {
		final String stage;
		final String api;
		final String subject;
		final String outcome;
		final boolean errorLevel;
		final String throwableClass;

		Reading(String stage, String api, String subject, String outcome, boolean errorLevel,
				String throwableClass) {
			this.stage = stage;
			this.api = api;
			this.subject = subject;
			this.outcome = outcome;
			this.errorLevel = errorLevel;
			this.throwableClass = throwableClass;
		}

		@Override
		public String toString() {
			return "PROBE-READ stage=" + this.stage + " api=" + this.api + " subject=" + this.subject
				+ " outcome=" + this.outcome + " errorLevel=" + this.errorLevel;
		}
	}

	/** 一个场景的全部读数。 */
	static final class Scenario {
		final String label;
		String redefineOutcome = "NOT-RUN";
		final List<Reading> readings = new ArrayList<>();
		String firstErrorClass;
		String firstErrorStage;
		String firstErrorApi;

		Scenario(String label) {
			this.label = label;
		}

		String summary() {
			return "PROBE-SUMMARY scenario=" + this.label + " redefine=" + this.redefineOutcome
				+ " escapedError=" + (this.firstErrorClass == null ? "none" : this.firstErrorClass)
				+ " at=" + (this.firstErrorClass == null ? "-" : this.firstErrorStage + "/" + this.firstErrorApi)
				+ " readings=" + this.readings.size();
		}
	}

	// ---------------------------------------------------------------- 夹具

	private static String annotationSource(String defaultValue) {
		String member = defaultValue == null ? "String value();" : "String value() default \"" + defaultValue + "\";";
		return "package probe.dflt;\n"
			+ "import java.lang.annotation.*;\n"
			+ "@Retention(RetentionPolicy.RUNTIME)\n"
			+ "@Target({ElementType.TYPE, ElementType.METHOD})\n"
			+ "public @interface Mark { " + member + " }\n";
	}

	/** 消费者：类与方法各一个 {@code @Mark}，成员值由 {@code arguments} 决定（空 = 用默认值）。 */
	private static String consumerSource(String simpleName, String arguments) {
		return "package probe.fix;\n"
			+ "import probe.dflt.Mark;\n"
			+ "@Mark" + arguments + "\n"
			+ "public class " + simpleName + " {\n"
			+ "  @Mark" + arguments + "\n"
			+ "  public String tagged() { return \"tagged\"; }\n"
			+ "  public String plain() { return \"plain\"; }\n"
			+ "}\n";
	}

	// ---------------------------------------------------------------- 内存 javac

	private static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("the probe JVM has no system Java compiler");
		}
		Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
					JavaFileObject.Kind kind, FileObject sibling) {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				buffers.put(className, buffer);
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return buffer;
					}
				};
			}
		};
		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> entry : sources.entrySet()) {
			URI uri = URI.create("string:///" + entry.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return entry.getValue();
				}
			});
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StringWriter out = new StringWriter();
		boolean ok = Boolean.TRUE.equals(compiler.getTask(out, memory, diagnostics,
			List.of("-proc:none", "-nowarn", "-g"), null, units).call());
		if (!ok) {
			StringBuilder message = new StringBuilder("fixture compilation failed:");
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				message.append("\n  ").append(diagnostic.getKind()).append(' ')
					.append(diagnostic.getMessage(null));
			}
			throw new IllegalStateException(message.toString());
		}
		Map<String, byte[]> output = new LinkedHashMap<>();
		for (Map.Entry<String, ByteArrayOutputStream> entry : buffers.entrySet()) {
			output.put(entry.getKey(), entry.getValue().toByteArray());
		}
		return output;
	}

	/** 每个场景一个干净 ClassLoader：先定义支撑类（注解类型），再定义被测类。 */
	private static final class ProbeLoader extends ClassLoader {
		private final Map<String, byte[]> supportClasses;

		ProbeLoader(ClassLoader parent, Map<String, byte[]> supportClasses) {
			super(parent);
			this.supportClasses = supportClasses == null ? Map.of() : supportClasses;
		}

		Class<?> define(String binaryName, byte[] bytes) {
			for (Map.Entry<String, byte[]> entry : this.supportClasses.entrySet()) {
				if (binaryName.equals(entry.getKey())) {
					continue;
				}
				defineClass(entry.getKey(), entry.getValue(), 0, entry.getValue().length);
			}
			return defineClass(binaryName, bytes, 0, bytes.length);
		}
	}

	private AnnotationDefaultProbe() {
	}
}
