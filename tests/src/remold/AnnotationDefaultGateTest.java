package remold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 注解默认值门禁的接线测试（批次 J，不变量 C5）。
 *
 * <p>覆盖四件事，都是"静默按旧行为走"的失效面：</p>
 * <ol>
 *   <li>默认配置下检测到注解默认值变化必须<b>拒绝</b>（返回拒绝原因，且不冒充放行）；</li>
 *   <li>显式降级通道 {@code -Dremold.agent.annotation_default_change=allow} 放行时<b>必须打 WARN</b>，
 *       且警告里要点名<b>类与具体成员</b>——不得静默；</li>
 *   <li>批量路径的<b>整轮共退</b>记账：回滚对齐事务、清空本批待重定义集合/旧字节映射/重试候选，
 *       并在台账里留下点名判决；</li>
 *   <li>动态单类路径：真实的 {@link RemoldAgent#redefineDynamic} 在门禁拒绝时<b>不得调用</b>
 *       {@code redefineClasses}，显式放行时才调用一次。</li>
 * </ol>
 *
 * <p>断言全部落在可观测终局（返回值 / 集合状态 / 日志内容 / redefineClasses 调用），
 * 不用"调用了某个方法"当通过信号。</p>
 */
class AnnotationDefaultGateTest {

	private static final String MARK = "probe.dflt.Mark";

	/** 记录所有日志，供"不得静默"类断言使用；测试结束恢复原 logger。 */
	private static final class RecordingLogger implements RemoldAgent.Logger {
		final List<String> warns = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override
		public void log(String msg) {
		}

		@Override
		public void info(String msg) {
		}

		@Override
		public void warn(String msg) {
			this.warns.add(msg);
		}

		@Override
		public void error(String msg) {
			this.errors.add(msg);
		}

		@Override
		public void error(String msg, Throwable t) {
			this.errors.add(msg);
		}
	}

	private final RemoldAgent.Logger originalLogger = RemoldAgent.logger;
	private final RecordingLogger recorder = new RecordingLogger();

	private void installLogger() {
		RemoldAgent.logger = this.recorder;
	}

	@AfterEach
	void restore() {
		RemoldAgent.logger = this.originalLogger;
		System.clearProperty("remold.agent.annotation_default_change");
	}

	private static byte[] mark(String body) {
		return Fixtures.compile(Map.of(MARK,
			"package probe.dflt;\n"
				+ "import java.lang.annotation.*;\n"
				+ "@Retention(RetentionPolicy.RUNTIME)\n"
				+ "@Target({ElementType.TYPE, ElementType.METHOD})\n"
				+ "public @interface Mark { " + body + " }\n")).get(MARK);
	}

	// ------------------------------------------------------------ 判决

	@Test
	void defaultConfigurationRefusesAndDoesNotWarnAsIfAllowed() {
		installLogger();
		byte[] before = mark("String value() default \"v1\";");
		byte[] after = mark("String value() default \"v2\";");

		String rejection = RemoldAgent.annotationDefaultGate(MARK, before, after);

		assertNotNull(rejection, "a changed default must be refused with a reason, not silently allowed");
		assertTrue(rejection.contains("value()") && rejection.contains("\"v1\"") && rejection.contains("\"v2\""),
			"the refusal must name the member and both values: " + rejection);
		assertFalse(RemoldAgent.annotationDefaultChangeAllowed(),
			"precondition: the degradation channel is off by default");
		assertTrue(this.recorder.warns.isEmpty(),
			"a refusal must not masquerade as an allowed change (no [ANNOTATION-DEFAULT] permission warning): "
				+ this.recorder.warns);
	}

	@Test
	void explicitAllowWarnsWithClassAndMemberAndNeverStaysSilent() {
		installLogger();
		System.setProperty("remold.agent.annotation_default_change", "allow");
		byte[] before = mark("String value() default \"v1\";");
		byte[] after = mark("String value() default \"v2\";");

		String rejection = RemoldAgent.annotationDefaultGate(MARK, before, after);

		assertNull(rejection, "with the explicit switch the redefine is allowed (degradation channel)");
		assertTrue(RemoldAgent.annotationDefaultChangeAllowed(), "precondition: the switch is recognised");
		String warn = this.recorder.warns.stream()
			.filter(message -> message.contains("ANNOTATION-DEFAULT"))
			.findFirst().orElse(null);
		assertNotNull(warn, "allowing a default-value change must be loud, never silent: " + this.recorder.warns);
		assertTrue(warn.contains(MARK), "the warning must name the annotation class: " + warn);
		assertTrue(warn.contains("value()"), "the warning must name the changed member: " + warn);
		assertTrue(warn.contains("\"v1\"") && warn.contains("\"v2\""),
			"the warning must show the value change: " + warn);
	}

	@Test
	void identicalBytesAreNeitherRefusedNorWarned() {
		installLogger();
		byte[] bytes = mark("String value() default \"v1\";");

		assertNull(RemoldAgent.annotationDefaultGate(MARK, bytes, bytes),
			"redefining identical bytes is not a default-value change");
		assertTrue(this.recorder.warns.isEmpty(),
			"no warning for a non-change (the gate must not cry wolf): " + this.recorder.warns);
	}

	// ------------------------------------------------------------ 整轮共退

	@Test
	void batchRefusalRollsBackTheWholeBatchAndLeavesAVerdict() {
		installLogger();
		BatchState state = new BatchState();
		state.retryCandidates.put("probe.dflt.Mark", Path.of("Mark.java"));

		String aborted = RemoldAgent.handleAnnotationDefaultChangeInBatch("probe.dflt.Mark",
			mark("String value() default \"v1\";"), mark("String value() default \"v2\";"),
			state.transactions, state.definitions, state.oldEffectiveBytes, state.retryCandidates,
			state.skips);

		assertEquals("probe.dflt.Mark", aborted, "a default-value change must force a round abort");
		assertTrue(state.definitions.isEmpty(), "no class of the batch may be redefined after the abort");
		assertTrue(state.oldEffectiveBytes.isEmpty(), "the old-byte map belongs to the voided batch");
		assertTrue(state.retryCandidates.isEmpty(),
			"retrying cannot change the already-loaded classes' default, so nothing may be requeued");
		assertTrue(state.transactions.isEmpty(), "every alignment transaction of the batch must be rolled back");
		assertFalse(state.changed.committed, "the changed class' transaction must not be committed");
		assertFalse(state.changed.preRegistered, "the pre-registered aligned bytes must be withdrawn");
		assertEquals(1, state.skips.size(), "the abort must leave exactly one named verdict this round");
		String rejection = this.recorder.errors.stream()
			.filter(message -> message.contains("probe.dflt.Mark") && message.contains("value()"))
			.findFirst().orElse(null);
		assertNotNull(rejection, "the refusal must be loud and name the class + member: " + this.recorder.errors);
		state.skips.report();
		String verdictLine = this.recorder.warns.stream()
			.filter(message -> message.contains("ANNOTATION-DEFAULT-CHANGED"))
			.findFirst().orElse(null);
		assertNotNull(verdictLine, "the summary must name the annotation-default verdict: " + this.recorder.warns);
		assertTrue(verdictLine.contains("probe.dflt.Mark"),
			"the summary must name the class that forced the co-rollback: " + verdictLine);
	}

	@Test
	void batchControlKeepsTheBatchWhenTheDefaultDidNotChange() {
		installLogger();
		BatchState state = new BatchState();
		byte[] bytes = mark("String value() default \"v1\";");

		String aborted = RemoldAgent.handleAnnotationDefaultChangeInBatch("probe.dflt.Mark",
			bytes, bytes, state.transactions, state.definitions, state.oldEffectiveBytes,
			state.retryCandidates, state.skips);

		assertNull(aborted, "redefining identical bytes must not abort the round (negative control)");
		assertEquals(2, state.definitions.size(), "the untouched batch must keep its redefinition list");
		assertEquals(2, state.transactions.size(), "no alignment transaction may be rolled back");
		assertTrue(state.skips.isEmpty(), "no verdict may be recorded for a non-change");
	}

	/** 一轮批量重定义的本地状态（真实集合类型，不是替身）。 */
	private static final class BatchState {
		final RemoldAgent.AlignmentTransaction changed = new RemoldAgent.AlignmentTransaction("probe.dflt.Mark");
		final RemoldAgent.AlignmentTransaction sibling = new RemoldAgent.AlignmentTransaction("probe.fix.Sibling");
		final Map<String, RemoldAgent.AlignmentTransaction> transactions = new LinkedHashMap<>();
		final List<ClassDefinition> definitions = new ArrayList<>();
		final Map<String, byte[]> oldEffectiveBytes = new LinkedHashMap<>();
		final Map<String, Path> retryCandidates = new LinkedHashMap<>();
		final RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();

		BatchState() {
			this.changed.pendingAdds.put("probe.dflt.Mark$1", new byte[] {1});
			this.transactions.put("probe.dflt.Mark", this.changed);
			this.transactions.put("probe.fix.Sibling", this.sibling);
			this.definitions.add(new ClassDefinition(String.class, new byte[] {1}));
			this.definitions.add(new ClassDefinition(Object.class, new byte[] {2}));
			this.oldEffectiveBytes.put("probe.dflt.Mark", new byte[] {9});
		}
	}

	// ------------------------------------------------------------ 动态单类路径

	@Test
	void dynamicRedefineRefusesWithoutCallingRedefineClasses() throws Exception {
		installLogger();
		RecordingInstrumentation recorderProxy = new RecordingInstrumentation();
		installInstrumentation(recorderProxy.proxy);
		FixtureLoader fixtureLoader = new FixtureLoader();
		Class<?> annotationType = fixtureLoader.define(mark("String value() default \"v1\";"));
		RemoldAgent.bytecodeCache.put(annotationType.getName(), mark("String value() default \"v1\";"));

		boolean accepted = RemoldAgent.redefineDynamic(annotationType, mark("String value() default \"v2\";"));

		assertFalse(accepted, "the dynamic path must refuse an annotation default-value change");
		assertTrue(recorderProxy.redefinitions.isEmpty(),
			"a refused class must never reach Instrumentation.redefineClasses: " + recorderProxy.redefinitions);
	}

	// ------------------------------------------------------------ 辅助

	/** 只实现被测路径用到的三个方法；其余返回中性值。 */
	private static final class RecordingInstrumentation implements java.lang.reflect.InvocationHandler {
		final List<Object> redefinitions = new ArrayList<>();
		final Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(
			AnnotationDefaultGateTest.class.getClassLoader(),
			new Class<?>[] {Instrumentation.class}, this);

		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
			switch (method.getName()) {
				case "isRedefineClassesSupported":
				case "isRetransformClassesSupported":
					return true;
				case "isModifiableClass":
					return true;
				case "getAllLoadedClasses":
					return new Class<?>[0];
				case "getInitiatedClasses":
					return new Class<?>[0];
				case "redefineClasses":
					this.redefinitions.add(args);
					return null;
				case "addTransformer":
				case "removeTransformer":
					return true;
				case "isRetransformClassesSupportedByDefault":
					return true;
				case "getObjectSize":
					return 0L;
				default:
					return defaultFor(method.getReturnType());
			}
		}

		private static Object defaultFor(Class<?> type) {
			if (!type.isPrimitive()) return null;
			if (type == boolean.class) return false;
			if (type == long.class) return 0L;
			if (type == int.class) return 0;
			return 0;
		}
	}

	private static void installInstrumentation(Instrumentation instrumentation) throws Exception {
		Field field = RemoldAgent.class.getDeclaredField("inst");
		field.setAccessible(true);
		field.set(null, instrumentation);
	}

	/** 独立加载器：被测注解类型由内存字节定义，不落在测试 classpath 上。 */
	private static final class FixtureLoader extends ClassLoader {
		FixtureLoader() {
			super(AnnotationDefaultGateTest.class.getClassLoader());
		}

		Class<?> define(byte[] bytes) {
			return defineClass(MARK, bytes, 0, bytes.length);
		}
	}
}
