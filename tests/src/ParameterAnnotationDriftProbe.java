import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
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

/**
 * 参数注解 / 泛型签名漂移探针（批次 K 的复用小框架，批次 J 复用）。
 *
 * <p><b>它回答什么</b>：修改一个类的 <b>属性</b>（{@code RuntimeVisibleParameterAnnotations}、
 * {@code Signature}）之后做 {@code Instrumentation.redefineClasses}，三种读取方式各自看到什么：
 * ① 重定义前拿到的旧 {@code Method} 句柄；② 重定义后重新 {@code getDeclaredMethods()}；
 * ③ 重定义后首次解析（从未被读过的句柄）。每一档都记录真实 {@code Throwable} 类名，
 * 不做任何"应该会抛"的预设——本探针的目标恰恰是<b>用读数替换推断</b>。</p>
 *
 * <p><b>为什么是可复用入口</b>：{@link #run(String, String, byte[], byte[])} 的入参就是
 * "变更前 / 变更后两份 class 字节 + 类名"，与具体变更类型无关；批次 J（注解默认值变化）只需
 * 传入注解类的两份字节即可复用同一套读数与日志格式。</p>
 *
 * <p><b>不做什么</b>：不构造人造异常、不模拟失败、不自行判定"复现成功/失败"。
 * 探针只打印读数，结论由读数的真实类名决定；若 {@code Instrumentation} 缺失或
 * {@code redefineClasses} 被标准 HotSpot 拒绝（结构性 schema 变化），如实记录为环境/能力缺口。</p>
 *
 * <p>跑法：{@code ./gradlew :tests:parameterAnnotationDriftProbe}（需要 {@code -javaagent} 提供
 * {@code Instrumentation}；属性变化是否在标准 HotSpot 上可 redefine 由本探针实测判定，不预设）。</p>
 */
public final class ParameterAnnotationDriftProbe {

	/** 夹具包名：注解类型与目标类分开，便于"只改用法不改注解声明"。 */
	private static final String ANNOTATION_CLASS = "probe.ann.Mark";

	private static final String TARGET_CLASS = "probe.fix.Target";

	/** 探针读取的四个反射 API（顺序即日志顺序）。 */
	private static final String[] METHOD_APIS = {
		"getParameterAnnotations",
		"getAnnotatedParameterTypes",
		"getGenericParameterTypes",
		"getAnnotations"
	};

	// ---------------------------------------------------------------- 结果模型

	/** 一次反射读数：哪个阶段、哪个 API、读到什么（或抛出什么）。 */
	public static final class Reading {
		public final String stage;
		public final String api;
		public final String subject;
		public final String outcome;
		public final boolean errorLevel;
		public final String throwableClass;

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
			return "PROBE-READ stage=" + stage + " api=" + api + " subject=" + subject
				+ " outcome=" + outcome;
		}
	}

	/** 一个变更场景的全部读数。 */
	public static final class Scenario {
		public final String label;
		public String redefineOutcome = "NOT-RUN";
		public final List<Reading> readings = new ArrayList<>();
		/** 第一个穿透 {@code catch (Exception)} 的 {@code Error} 类名（没有则 null）。 */
		public String firstErrorClass;
		public String firstErrorStage;
		public String firstErrorApi;

		Scenario(String label) {
			this.label = label;
		}

		public boolean escapedError() {
			return this.firstErrorClass != null;
		}

		/** 机器可读的一行结论。 */
		public String summary() {
			return "PROBE-SUMMARY scenario=" + this.label + " redefine=" + this.redefineOutcome
				+ " escapedError=" + (this.firstErrorClass == null ? "none" : this.firstErrorClass)
				+ " at=" + (this.firstErrorClass == null ? "-" : this.firstErrorStage + "/" + this.firstErrorApi)
				+ " readings=" + this.readings.size();
		}
	}

	// ---------------------------------------------------------------- 可复用入口

	/**
	 * 跑一个变更场景。
	 *
	 * @param label      场景名（日志用）
	 * @param binaryName 目标类的二进制名（例如 {@code probe.fix.Target}）
	 * @param before     变更前的 class 字节
	 * @param after      变更后的 class 字节
	 * @return 该场景的读数；任何异常都被记录而不是外抛（探针的首要产出是读数）
	 */
	public static Scenario run(String label, String binaryName, byte[] before, byte[] after) {
		return run(label, Map.of(), binaryName, before, after);
	}

	/**
	 * 跑一个变更场景，并先把 {@code supportClasses} 定义进同一个隔离 ClassLoader。
	 *
	 * <p>为什么需要它：读参数注解时必须能解析注解类型（否则得到的是
	 * {@code TypeNotPresentException}，与本探针要测的常量池错位无关）。被测类引用到的
	 * 自定义注解 / 辅助类型都由调用方通过本参数提供。</p>
	 *
	 * @param supportClasses 二进制名 → 字节，在被测类之前定义（不参与重定义）
	 */
	public static Scenario run(String label, Map<String, byte[]> supportClasses, String binaryName,
			byte[] before, byte[] after) {
		return run(label, supportClasses, binaryName, before, after, false);
	}

	/**
	 * @param perturbAfterRedefine 是否在读数之后追加"旧方法数据回收"扰动：多轮 GC + 常量池争用，
	 *                             再读旧句柄。旧 {@code Method} 的 {@code ConstMethod}/常量池只有在
	 *                             回收后才会被复用，若"旧快照对着新常量池"这个形态成立，只有这里才看得到。
	 *                             扰动本身不制造异常，只提供真实条件。
	 */
	public static Scenario run(String label, Map<String, byte[]> supportClasses, String binaryName,
			byte[] before, byte[] after, boolean perturbAfterRedefine) {
		Scenario scenario = new Scenario(label);
		Instrumentation instrumentation = P05Agent.instrumentation();
		if (instrumentation == null) {
			scenario.redefineOutcome = "NO-INSTRUMENTATION";
			return scenario;
		}
		IsolatedLoader loader = new IsolatedLoader(
			ParameterAnnotationDriftProbe.class.getClassLoader(), supportClasses);
		Class<?> target;
		try {
			target = loader.define(binaryName, before);
		} catch (Throwable t) {
			scenario.redefineOutcome = "LOAD-FAILED:" + t.getClass().getName();
			record(scenario, "load", "defineClass", binaryName, t);
			return scenario;
		}

		// 句柄在重定义前取得：warm 组立刻读一次（把可缓存的部分都填满），cold 组一次都不读。
		List<Method> warmHandles = new ArrayList<>();
		List<Method> coldHandles = new ArrayList<>();
		try {
			for (Method method : target.getDeclaredMethods()) {
				if (method.isSynthetic() || method.isBridge()) {
					continue;
				}
				warmHandles.add(method);
				coldHandles.add(method);
			}
			for (Method method : warmHandles) {
				readMethod(scenario, "pre-warm", method);
			}
			readClassLevel(scenario, "pre-warm", target);
		} catch (Throwable t) {
			record(scenario, "pre-warm", "getDeclaredMethods", binaryName, t);
			return scenario;
		}

		ConcurrentReader reader = null;
		if (perturbAfterRedefine) {
			reader = new ConcurrentReader(warmHandles);
			reader.start();
		}

		try {
			instrumentation.redefineClasses(new ClassDefinition(target, after));
			scenario.redefineOutcome = "OK";
		} catch (Throwable t) {
			scenario.redefineOutcome = "THROWN:" + t.getClass().getName()
				+ (t.getMessage() == null ? "" : ": " + t.getMessage());
			record(scenario, "redefine", "redefineClasses", binaryName, t);
			stopReader(scenario, reader);
			return scenario;
		}

		// ① 旧句柄（重定义前读过）
		for (Method method : warmHandles) {
			readMethod(scenario, "R1-stale-warm", method);
		}
		// ①b 旧句柄（重定义前一次都没读过）
		for (Method method : coldHandles) {
			readMethod(scenario, "R1-stale-cold", method);
		}
		// ①c 并发读：重定义期间另一线程持续读同一批旧句柄（真实用法：框架缓存被并发读）
		stopReader(scenario, reader);
		// ② 重新 getDeclaredMethods()
		try {
			for (Method method : target.getDeclaredMethods()) {
				if (method.isSynthetic() || method.isBridge()) {
					continue;
				}
				readMethod(scenario, "R2-fresh-array", method);
			}
		} catch (Throwable t) {
			record(scenario, "R2-fresh-array", "getDeclaredMethods", binaryName, t);
		}
		// ③ 首次解析：按名字与参数类型现取句柄，再读
		for (Method stale : warmHandles) {
			try {
				Method fresh = target.getDeclaredMethod(stale.getName(), stale.getParameterTypes());
				readMethod(scenario, "R3-first-resolve", fresh);
			} catch (Throwable t) {
				record(scenario, "R3-first-resolve", "getDeclaredMethod",
					stale.getName(), t);
			}
		}
		readClassLevel(scenario, "post", target);
		if (perturbAfterRedefine && !scenario.escapedError()) {
			perturbAndReread(scenario, warmHandles, loader);
		}
		return scenario;
	}

	// ---------------------------------------------------------------- 读数实现

	/**
	 * 并发读线程：重定义发生期间持续读同一批旧句柄。
	 *
	 * <p>为什么需要：框架（Jackson/Gson 的缓存填充）是多线程读反射元数据的。若"旧句柄 + 新常量池"
	 * 只在中途窗口里暴露，单线程顺序读看不到它。本线程<b>只记录真实抛出的 {@code Throwable}</b>，
	 * 停止条件由主线程控制（不是靠超时判定结果）。</p>
	 */
	private static final class ConcurrentReader extends Thread {

		private final List<Method> handles;
		private final List<Throwable> failures = new ArrayList<>();
		private volatile boolean running = true;
		private volatile int successfulReads;

		ConcurrentReader(List<Method> handles) {
			super("drift-probe-concurrent-reader");
			setDaemon(true);
			this.handles = handles;
		}

		@Override
		public void run() {
			while (this.running) {
				for (Method method : this.handles) {
					try {
						method.getParameterAnnotations();
						method.getGenericParameterTypes();
						method.getAnnotatedParameterTypes();
						this.successfulReads++;
					} catch (Throwable t) {
						synchronized (this.failures) {
							if (this.failures.size() < 32) {
								this.failures.add(t);
							}
						}
					}
				}
			}
		}
	}

	/** 停止并发读线程并把它的读数并入场景（成功次数作为前置基线，防止"一次都没读到"被当成通过）。 */
	private static void stopReader(Scenario scenario, ConcurrentReader reader) {
		if (reader == null) {
			return;
		}
		reader.running = false;
		try {
			reader.join(5000);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		List<Throwable> failures;
		synchronized (reader.failures) {
			failures = new ArrayList<>(reader.failures);
		}
		for (Throwable failure : failures) {
			record(scenario, "R5-concurrent-read", "concurrent-read-loop", "stale-handle", failure);
		}
		scenario.readings.add(new Reading("R5-concurrent-read", "concurrent-read-loop", "stale-handle",
			"OK:reads=" + reader.successfulReads + " failures=" + failures.size(), false, null));
	}

	/**
	 * 旧方法数据回收扰动：多轮"分配新类（争用常量池/元空间）+ GC"，每轮后重读旧句柄。
	 *
	 * <p>第一轮就命中 {@code Error} 即停止（后续同样条件只会重复同一形态）。每轮都记录读数，
	 * 不做任何"预期会抛"的断言——没有 {@code Error} 就如实打印 none。</p>
	 */
	private static void perturbAndReread(Scenario scenario, List<Method> staleHandles, IsolatedLoader loader) {
		for (int round = 1; round <= 4; round++) {
			for (int i = 0; i < 256; i++) {
				try {
					loader.defineChurn("probe.churn.R" + round + "C" + i);
				} catch (Throwable t) {
					record(scenario, "perturb-" + round, "defineChurn", "probe.churn.R" + round + "C" + i, t);
					return;
				}
			}
			System.gc();
			for (Method method : staleHandles) {
				readMethod(scenario, "R4-stale-after-gc-" + round, method);
			}
			if (scenario.escapedError()) {
				return;
			}
		}
	}

	private static void readClassLevel(Scenario scenario, String stage, Class<?> target) {
		read(scenario, stage, "getAnnotations", "class", () -> describeAnnotations(target.getAnnotations()));
		read(scenario, stage, "getGenericInterfaces", "class", () -> describeTypes(target.getGenericInterfaces()));
		read(scenario, stage, "getGenericSuperclass", "class", () -> describeType(target.getGenericSuperclass()));
	}

	private static void readMethod(Scenario scenario, String stage, Method method) {
		String subject = method.getName() + "/" + method.getParameterCount();
		read(scenario, stage, "getParameterAnnotations", subject,
			() -> describeParameterAnnotations(method.getParameterAnnotations()));
		read(scenario, stage, "getAnnotatedParameterTypes", subject,
			() -> describeAnnotatedTypes(method.getAnnotatedParameterTypes()));
		read(scenario, stage, "getGenericParameterTypes", subject,
			() -> describeTypes(method.getGenericParameterTypes()));
		read(scenario, stage, "getAnnotations", subject,
			() -> describeAnnotations(method.getAnnotations()));
		// Parameter 对象是另一条 JDK 路径（Executable.parameters -> Parameter 的注解快照），单独读一次：
		// 参数注解变化的旧句柄若只在某一条路径上错位，漏掉它就等于没测。
		read(scenario, stage, "getParameters", subject,
			() -> describeParameters(method.getParameters()));
	}

	private interface ValueSupplier {
		String get() throws Throwable;
	}

	private static void read(Scenario scenario, String stage, String api, String subject,
			ValueSupplier supplier) {
		try {
			String value = supplier.get();
			scenario.readings.add(new Reading(stage, api, subject, "OK:" + value, false, null));
		} catch (Throwable t) {
			record(scenario, stage, api, subject, t);
		}
	}

	/**
	 * 记录一个抛出的 {@code Throwable}：真实类名 + 消息，并标出它是否穿过了
	 * {@code catch (Exception)}（即 {@code Error} 级）。
	 */
	private static void record(Scenario scenario, String stage, String api, String subject, Throwable t) {
		boolean errorLevel = t instanceof Error;
		String message = t.getMessage();
		String outcome = "THROWN:" + t.getClass().getName() + (message == null ? "" : ": " + message);
		scenario.readings.add(new Reading(stage, api, subject, outcome, errorLevel, t.getClass().getName()));
		if (errorLevel && scenario.firstErrorClass == null) {
			scenario.firstErrorClass = t.getClass().getName();
			scenario.firstErrorStage = stage;
			scenario.firstErrorApi = api;
		}
	}

	private static String describeParameterAnnotations(Annotation[][] annotations) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < annotations.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append('#').append(i).append("={");
			Annotation[] declared = annotations[i];
			for (int j = 0; j < declared.length; j++) {
				if (j > 0) {
					sb.append(',');
				}
				sb.append(describeAnnotation(declared[j]));
			}
			sb.append('}');
		}
		return sb.append(']').toString();
	}

	private static String describeAnnotations(Annotation[] annotations) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < annotations.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(describeAnnotation(annotations[i]));
		}
		return sb.append(']').toString();
	}

	/** 强制解析注解成员值：常量池错位就是在这里暴露。 */
	private static String describeAnnotation(Annotation annotation) {
		Class<? extends Annotation> type = annotation.annotationType();
		StringBuilder sb = new StringBuilder("@").append(type.getName()).append('(');
		Method[] members = type.getDeclaredMethods();
		for (int i = 0; i < members.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			Method member = members[i];
			sb.append(member.getName()).append('=');
			try {
				member.setAccessible(true);
				sb.append(String.valueOf(member.invoke(annotation)));
			} catch (Throwable t) {
				Throwable cause = t.getCause() == null ? t : t.getCause();
				sb.append("<").append(cause.getClass().getName()).append('>');
			}
		}
		return sb.append(')').toString();
	}

	private static String describeAnnotatedTypes(AnnotatedType[] types) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < types.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			AnnotatedType type = types[i];
			sb.append(describeAnnotations(type.getAnnotations())).append(' ');
			sb.append(describeType(type.getType()));
		}
		return sb.append(']').toString();
	}

	/** {@code Parameter} 对象路径：名字 + 注解快照（与 {@code getParameterAnnotations()} 不是同一条 JDK 代码路径）。 */
	private static String describeParameters(java.lang.reflect.Parameter[] parameters) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < parameters.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			java.lang.reflect.Parameter parameter = parameters[i];
			sb.append('#').append(i).append(':').append(parameter.getName()).append('=')
				.append(describeAnnotations(parameter.getAnnotations()));
		}
		return sb.append(']').toString();
	}

	private static String describeTypes(Type[] types) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < types.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(describeType(types[i]));
		}
		return sb.append(']').toString();
	}

	/** 递归展开泛型实参：{@code Signature} 解析错误在这里必定被触发。 */
	private static String describeType(Type type) {
		if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
			StringBuilder sb = new StringBuilder(
				describeType(parameterized.getRawType())).append('<');
			Type[] arguments = parameterized.getActualTypeArguments();
			for (int i = 0; i < arguments.length; i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(describeType(arguments[i]));
			}
			return sb.append('>').toString();
		}
		if (type instanceof java.lang.reflect.GenericArrayType array) {
			return describeType(array.getGenericComponentType()) + "[]";
		}
		if (type instanceof java.lang.reflect.WildcardType wildcard) {
			return "?" + describeTypes(wildcard.getUpperBounds())
				+ (wildcard.getLowerBounds().length == 0 ? "" : " super " + describeTypes(wildcard.getLowerBounds()));
		}
		if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
			return variable.getName();
		}
		return type == null ? "null" : type.getTypeName();
	}

	// ---------------------------------------------------------------- 环境 / 灵敏度 / 能力

	/** 记录本格的 JVM 身份与完整启动参数：三格对照必须能逐字区分。 */
	private static void reportEnvironment() {
		System.out.println("PROBE-ENV java.version=" + System.getProperty("java.version")
			+ " java.vm.name=" + System.getProperty("java.vm.name")
			+ " java.vm.version=" + System.getProperty("java.vm.version")
			+ " java.home=" + System.getProperty("java.home"));
		Instrumentation instrumentation = P05Agent.instrumentation();
		System.out.println("PROBE-ENV inputArguments="
			+ java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
		System.out.println("PROBE-ENV instrumentation=" + (instrumentation == null ? "ABSENT" : "present")
			+ " canRedefineClasses="
			+ (instrumentation != null && instrumentation.isRedefineClassesSupported()));
	}

	/**
	 * 灵敏度对照（<b>不是复现证据</b>）：加载两份"属性被故意损坏"的类，让 JVM 自己的解析器
	 * 抛出真实异常，从而证明读数链路能看见并记录 {@code Error} 级 {@code Throwable} 的类名。
	 *
	 * <p>损坏形态：①参数注解的属性类型指向一个<b>不是注解类型</b>的类（{@code java.lang.String}）；
	 * ②方法 {@code Signature} 属性写成语法非法的字符串。两份字节都由 ASM 直接生成，
	 * 不经过 javac，也不含任何 {@code throw} 语句——异常全部由 JVM 解析器抛出。</p>
	 */
	private static void sensitivityControl() {
		System.out.println("--- SENSITIVITY-CONTROL (deliberately corrupted attributes; core purpose = does the"
			+ " read harness observe JVM-thrown Errors at all. This is NOT evidence of the defect.) ---");
		byte[] annotationTarget = asmClass("probe.bad.AnnotationTarget", "Ljava/lang/String;", null);
		runSensitivity("param-annotation-type-is-not-an-annotation", "probe.bad.AnnotationTarget",
			Map.of(), annotationTarget, "getParameterAnnotations");
		byte[] signatureTarget = asmClass("probe.bad.SignatureTarget", null, "(<");
		runSensitivity("malformed-generic-signature", "probe.bad.SignatureTarget",
			Map.of(), signatureTarget, "getGenericParameterTypes");

		// 第三种：结构合法但元素值重复（同一个注解成员被写两次）。JDK 的注解解析器对此有明确检查，
		// 是"注解路径能不能看见 Error"最直接的对照。
		Map<String, byte[]> support = compile(Map.of("probe.bad.Real",
			"package probe.bad;\npublic @interface Real { String value(); }\n"));
		runSensitivity("duplicate-annotation-member", "probe.bad.DuplicateTarget",
			support, asmDuplicateMemberClass(), "getParameterAnnotations");

		// 第四种：参数注解属性的 num_parameters 与实际参数个数不符（结构级损坏）。
		// 这一类最能代表"旧快照对着新常量池"的错位形态，因此必须证明它能被观测到。
		byte[] valid = compile(Map.of(
			"probe.bad.Counted",
			"package probe.bad;\n"
				+ "public class Counted { public void m(@Deprecated String s) { } }\n")).get("probe.bad.Counted");
		byte[] miscounted = patchParameterAnnotationCount(valid, 5);
		runSensitivity("param-annotation-count-mismatch", "probe.bad.Counted",
			Map.of(), miscounted, "getParameterAnnotations");
	}

	/**
	 * 把 {@code RuntimeVisibleParameterAnnotations} 属性的 {@code num_parameters} 改成 {@code newCount}。
	 *
	 * <p>只改一个字节：属性名索引与长度保持不变，因此类文件结构自洽，损坏只体现在属性内容里。
	 * 找到属性的办法是先定位常量池里该属性名的索引，再在字节里找 {@code [idx_hi idx_lo len..]}
	 * 这个唯一序列。</p>
	 */
	private static byte[] patchParameterAnnotationCount(byte[] classBytes, int newCount) {
		byte[] name = "RuntimeVisibleParameterAnnotations".getBytes(StandardCharsets.UTF_8);
		int nameIndex = findConstantPoolUtf8Index(classBytes, name);
		if (nameIndex < 0) {
			throw new IllegalStateException("attribute name not found in constant pool");
		}
		for (int i = 0; i + 6 < classBytes.length; i++) {
			if ((classBytes[i] & 0xFF) != ((nameIndex >> 8) & 0xFF)
				|| (classBytes[i + 1] & 0xFF) != (nameIndex & 0xFF)) {
				continue;
			}
			int length = ((classBytes[i + 2] & 0xFF) << 24) | ((classBytes[i + 3] & 0xFF) << 16)
				| ((classBytes[i + 4] & 0xFF) << 8) | (classBytes[i + 5] & 0xFF);
			if (length <= 0 || length > 64) {
				continue;
			}
			byte[] patched = classBytes.clone();
			patched[i + 6] = (byte) newCount;
			return patched;
		}
		throw new IllegalStateException("parameter-annotation attribute not found");
	}

	/** 在常量池里找 UTF8 常量，返回其索引；找不到返回 -1。 */
	private static int findConstantPoolUtf8Index(byte[] classBytes, byte[] name) {
		int count = ((classBytes[8] & 0xFF) << 8) | (classBytes[9] & 0xFF);
		int offset = 10;
		for (int index = 1; index < count; index++) {
			int tag = classBytes[offset] & 0xFF;
			switch (tag) {
				case 1 -> {
					int length = ((classBytes[offset + 1] & 0xFF) << 8) | (classBytes[offset + 2] & 0xFF);
					boolean match = length == name.length;
					for (int k = 0; match && k < length; k++) {
						match = classBytes[offset + 3 + k] == name[k];
					}
					if (match) {
						return index;
					}
					offset += 3 + length;
				}
				case 7, 8, 16, 19, 20 -> offset += 3;
				case 15 -> offset += 4;
				case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 5;
				case 5, 6 -> {
					offset += 9;
					index++;
				}
				default -> throw new IllegalStateException("unknown constant pool tag " + tag + " at " + index);
			}
		}
		return -1;
	}

	private static void runSensitivity(String label, String binaryName, Map<String, byte[]> supportClasses,
			byte[] bytes, String api) {
		IsolatedLoader loader = new IsolatedLoader(
			ParameterAnnotationDriftProbe.class.getClassLoader(), supportClasses);
		Class<?> target;
		try {
			target = loader.define(binaryName, bytes);
		} catch (Throwable t) {
			System.out.println("SENSITIVITY-CONTROL label=" + label + " stage=define"
				+ " throwable=" + t.getClass().getName()
				+ " message=" + String.valueOf(t.getMessage()));
			return;
		}
		Method method = target.getDeclaredMethods()[0];
		try {
			if ("getParameterAnnotations".equals(api)) {
				method.getParameterAnnotations();
			} else {
				method.getGenericParameterTypes();
			}
			System.out.println("SENSITIVITY-CONTROL label=" + label + " api=" + api
				+ " throwable=NONE (the harness cannot see this class of failure)");
		} catch (Throwable t) {
			System.out.println("SENSITIVITY-CONTROL label=" + label + " api=" + api
				+ " throwable=" + t.getClass().getName()
				+ " errorLevel=" + (t instanceof Error)
				+ " message=" + String.valueOf(t.getMessage()));
		}
	}

	/**
	 * 结构重定义能力实测：加一个实例字段再 redefine。
	 *
	 * <p>只用于区分"③ DCEVM 格"与"② 标准 HotSpot 格"——不靠 JVM 名字或启动参数推断，
	 * 靠这次 redefine 的真实结果（{@code OK} vs {@code THROWN:...}）说话。</p>
	 */
	private static void reportStructuralCapability() {
		Instrumentation instrumentation = P05Agent.instrumentation();
		if (instrumentation == null) {
			System.out.println("PROBE-ENV structuralRedefine=NO-INSTRUMENTATION");
			return;
		}
		byte[] one = asmStructuralClass(false);
		byte[] two = asmStructuralClass(true);
		IsolatedLoader loader = new IsolatedLoader(
			ParameterAnnotationDriftProbe.class.getClassLoader(), Map.of());
		try {
			Class<?> cls = loader.define("probe.cap.Structural", one);
			instrumentation.redefineClasses(new ClassDefinition(cls, two));
			System.out.println("PROBE-ENV structuralRedefine=OK (field added; enhanced redefinition is active)");
		} catch (Throwable t) {
			System.out.println("PROBE-ENV structuralRedefine=THROWN:" + t.getClass().getName()
				+ " message=" + String.valueOf(t.getMessage()));
		}
	}

	/** 生成带一个（或两个）实例字段的类：第二个字段是"结构变化"。 */
	private static byte[] asmStructuralClass(boolean withSecondField) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		String internal = "probe/cap/Structural";
		writer.visit(org.objectweb.asm.Opcodes.V1_8,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
			internal, null, "java/lang/Object", null);
		writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "a", "I", null, null).visitEnd();
		if (withSecondField) {
			writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "b", "I", null, null).visitEnd();
		}
		org.objectweb.asm.MethodVisitor init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,
			"<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>",
			"()V", false);
		init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * 生成"属性被故意损坏"的类：{@code annotationDescriptor} 非空则把参数注解指向该类型；
	 * {@code signature} 非空则把方法写成该（可能是非法）泛型签名。
	 */
	private static byte[] asmClass(String binaryName, String annotationDescriptor, String signature) {
		// COMPUTE_MAXS：这份字节的目的只是承载"损坏的属性"，栈帧算错会把失败提前到 defineClass 的
		// 校验阶段（ClassFormatError: Arguments can't fit into locals），那就测不到属性解析路径了。
		org.objectweb.asm.ClassWriter writer =
			new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		String internal = binaryName.replace('.', '/');
		writer.visit(org.objectweb.asm.Opcodes.V1_8,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
			internal, null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,
			"m", "(Ljava/lang/String;)V", signature, null);
		if (annotationDescriptor != null) {
			org.objectweb.asm.AnnotationVisitor annotation =
				method.visitParameterAnnotation(0, annotationDescriptor, true);
			annotation.visitEnd();
		}
		method.visitCode();
		method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}


	/** 参数注解里把同一个成员写两次：结构合法，内容非法（JDK 解析器对此有明确检查）。 */
	private static byte[] asmDuplicateMemberClass() {
		org.objectweb.asm.ClassWriter writer =
			new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		String internal = "probe/bad/DuplicateTarget";
		writer.visit(org.objectweb.asm.Opcodes.V1_8,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
			internal, null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,
			"m", "(Ljava/lang/String;)V", null, null);
		org.objectweb.asm.AnnotationVisitor annotation =
			method.visitParameterAnnotation(0, "Lprobe/bad/Real;", true);
		annotation.visit("value", "first");
		annotation.visit("value", "second");
		annotation.visitEnd();
		method.visitCode();
		method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] churnBytes(String internalName) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		writer.visit(org.objectweb.asm.Opcodes.V1_8,
			org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
			internalName, null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * 编译"同名单一变体"：v1 与各 v2 变体必须用<b>同一份注解字节</b>，只有目标类不同，
	 * 否则场景里混进了"注解类型也变了"这个额外变量。
	 */
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

	private static String annotationSource() {
		return "package probe.ann;\n"
			+ "import java.lang.annotation.*;\n"
			+ "@Retention(RetentionPolicy.RUNTIME)\n"
			+ "@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})\n"
			+ "public @interface Mark { String value(); int size() default 1; Class<?> type() default Object.class; }\n";
	}

	private static String targetSource(String paramAnnotation, String genericType) {
		return "package probe.fix;\n"
			+ "import java.util.List;\n"
			+ "public class Target {\n"
			+ "    public void paramOnly(" + paramAnnotation + " String s) { }\n"
			+ "    public " + genericType + " genericOnly(" + genericType + " in) { return in; }\n"
			+ "    public void both(" + paramAnnotation + " " + genericType + " xs) { }\n"
			+ "    public String plain(int x) { return \"plain\"; }\n"
			+ "}\n";
	}

	/** 注解在同一方法的两个参数之间移动：属性布局变化，参数个数与描述符不变。 */
	private static String movedAnnotationSource(boolean onSecond) {
		String params = onSecond
			? "String a, @probe.ann.Mark(\"v1\") String b"
			: "@probe.ann.Mark(\"v1\") String a, String b";
		return "package probe.fix;\n"
			+ "public class Target {\n"
			+ "    public void moved(" + params + ") { }\n"
			+ "}\n";
	}

	/** 参数上的注解类型变化（不是同类型换值）。 */
	private static String annotationTypeSource(String annotation) {
		return "package probe.fix;\n"
			+ "public class Target {\n"
			+ "    public void m(" + annotation + " String s) { }\n"
			+ "}\n";
	}

	/** 类级 {@code Signature}（父类泛型实参）变化：擦除后的父类不变。 */
	private static String classSignatureSource(String argument) {
		return "package probe.fix;\n"
			+ "public class Target extends java.util.ArrayList<" + argument + "> {\n"
			+ "    public void m(String s) { }\n"
			+ "}\n";
	}

	/**
	 * 注解值里带 class literal（{@code String.class} → {@code Integer.class}）。
	 *
	 * <p>class literal 在注解里以常量池 UTF8 描述符存储，索引位置随注解内容变化；
	 * 若"旧句柄按新常量池解析"成立，这一形态最容易读到错位值或抛出注解格式错误。</p>
	 */
	private static String classLiteralSource(String literal) {
		return "package probe.fix;\n"
			+ "public class Target {\n"
			+ "    public void m(@probe.ann.Mark(value = \"v1\", type = " + literal + ".class) String s) { }\n"
			+ "}\n";
	}

	// ---------------------------------------------------------------- main

	public static void main(String[] args) throws Exception {
		System.out.println("=== parameter annotation / generic signature drift probe ===");
		reportEnvironment();
		sensitivityControl();
		reportStructuralCapability();

		Map<String, byte[]> base = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(),
			TARGET_CLASS, targetSource("@probe.ann.Mark(\"v1\")", "List<String>")));
		Map<String, byte[]> longerValue = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(),
			TARGET_CLASS, targetSource("@probe.ann.Mark(\"v2-a-much-longer-value\")", "List<String>")));
		Map<String, byte[]> removedAnnotation = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(),
			TARGET_CLASS, targetSource("", "List<String>")));
		Map<String, byte[]> signatureChanged = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(),
			TARGET_CLASS, targetSource("@probe.ann.Mark(\"v1\")", "List<Integer>")));
		Map<String, byte[]> bothChanged = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(),
			TARGET_CLASS, targetSource("@probe.ann.Mark(\"v2\")", "List<Integer>")));

		Map<String, byte[]> movedBefore = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, movedAnnotationSource(false)));
		Map<String, byte[]> movedAfter = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, movedAnnotationSource(true)));
		Map<String, byte[]> typeBefore = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, annotationTypeSource("@probe.ann.Mark(\"v1\")")));
		Map<String, byte[]> typeAfter = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, annotationTypeSource("@java.lang.Deprecated")));
		Map<String, byte[]> classSigBefore = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, classSignatureSource("String")));
		Map<String, byte[]> classSigAfter = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, classSignatureSource("Integer")));

		Map<String, byte[]> literalBefore = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, classLiteralSource("java.lang.String")));
		Map<String, byte[]> literalAfter = compile(Map.of(
			ANNOTATION_CLASS, annotationSource(), TARGET_CLASS, classLiteralSource("java.lang.Integer")));

		byte[] annotationBytes = base.get(ANNOTATION_CLASS);
		byte[] before = base.get(TARGET_CLASS);
		System.out.println("   fixture = " + TARGET_CLASS + " before=" + before.length + " bytes"
			+ " annotation=" + ANNOTATION_CLASS + " " + annotationBytes.length + " bytes");

		List<Scenario> scenarios = new ArrayList<>();
		scenarios.add(runWithAnnotation("control-identical", annotationBytes, before, before));
		scenarios.add(runWithAnnotation("param-annotation-value-change", annotationBytes,
			before, longerValue.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("param-annotation-removed", annotationBytes,
			before, removedAnnotation.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("generic-signature-change", annotationBytes,
			before, signatureChanged.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("annotation-and-signature-change", annotationBytes,
			before, bothChanged.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("param-annotation-moved-between-parameters", annotationBytes,
			movedBefore.get(TARGET_CLASS), movedAfter.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("param-annotation-type-changed", annotationBytes,
			typeBefore.get(TARGET_CLASS), typeAfter.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("class-generic-signature-change", annotationBytes,
			classSigBefore.get(TARGET_CLASS), classSigAfter.get(TARGET_CLASS)));
		scenarios.add(runWithAnnotation("annotation-class-literal-value-change", annotationBytes,
			literalBefore.get(TARGET_CLASS), literalAfter.get(TARGET_CLASS)));

		for (Scenario scenario : scenarios) {
			System.out.println("--- scenario: " + scenario.label);
			System.out.println("PROBE-REDEFINE scenario=" + scenario.label + " outcome=" + scenario.redefineOutcome);
			for (Reading reading : scenario.readings) {
				System.out.println(reading);
			}
			System.out.println(scenario.summary());
		}
		System.out.println("=== probe finished: " + scenarios.size() + " scenario(s) ===");
	}

	/**
	 * 场景入口：把注解类型作为支撑类一起定义，再跑 {@link #run}。
	 *
	 * <p>注解类型读不到会污染读数（{@code TypeNotPresentException}），那不是本探针要测的东西，
	 * 所以它必须在同一个 ClassLoader 里可见。</p>
	 */
	private static Scenario runWithAnnotation(String label, byte[] annotationBytes, byte[] before,
			byte[] after) {
		return run(label, Map.of(ANNOTATION_CLASS, annotationBytes), TARGET_CLASS, before, after, true);
	}

	/**
	 * 每个场景一个干净 ClassLoader：先定义调用方给出的支撑类（如自定义注解），再定义被测类；
	 * 其余名字一律委托父加载器。每个场景独立，保证上一场景的重定义不影响下一场景。
	 */
	private static final class IsolatedLoader extends ClassLoader {

		private final Map<String, byte[]> supportClasses;

		IsolatedLoader(ClassLoader parent, Map<String, byte[]> supportClasses) {
			super(parent);
			this.supportClasses = supportClasses == null ? Map.of() : supportClasses;
		}

		/** 定义一个空的扰动类（只用于常量池/元空间分配，不参与任何被测读数）。 */
		Class<?> defineChurn(String binaryName) {
			byte[] bytes = churnBytes(binaryName.replace('.', '/'));
			return defineClass(binaryName, bytes, 0, bytes.length);
		}

		Class<?> define(String binaryName, byte[] bytes) {
			for (Map.Entry<String, byte[]> entry : this.supportClasses.entrySet()) {
				if (binaryName.equals(entry.getKey())) {
					continue;
				}
				byte[] support = entry.getValue();
				defineClass(entry.getKey(), support, 0, support.length);
			}
			return defineClass(binaryName, bytes, 0, bytes.length);
		}
	}
}
