package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redefine 入口的 class 文件合法性门禁（{@code docs/status.md} §5 风险 13）。
 *
 * <p>每条负向用例都落在<b>终局</b>上：拒绝原因非空、{@code redefineClasses} 调用次数为 0、
 * 该类进了 skip 台账、并且 warn 里点了名。正向对照（合法字节）证明门禁不是"一律拦"，
 * 同时钉住"送进 JVM 的字节就是校验过的那一份引用"。</p>
 */
class RedefineEntryGateTest {

	private static final String INTERNAL = "probe/GateTarget";
	private static final String DOT = "probe.GateTarget";
	private static final String OTHER_INTERNAL = "probe/OtherTarget";

	private final RemoldAgent.Logger originalLogger = RemoldAgent.logger;
	private final Instrumentation originalInstrumentation = RemoldAgent.getInstrumentation();
	private final RecordingLogger recorder = new RecordingLogger();
	private final RecordingInstrumentation instrumentation = new RecordingInstrumentation();

	@BeforeEach
	void install() {
		RemoldAgent.logger = this.recorder;
		RemoldAgent.swapInstrumentationForTest(this.instrumentation.proxy);
	}

	@AfterEach
	void restore() {
		RemoldAgent.swapInstrumentationForTest(this.originalInstrumentation);
		RemoldAgent.logger = this.originalLogger;
		RemoldAgent.bytecodeCache.remove(DOT);
		RemoldAgent.bytecodeCache.remove("probe.OtherTarget");
	}

	// ------------------------------------------------------------ 负向用例（四项各一条）

	/** 畸形魔数：{@code 0xDEADBEEF} 的字节绝不能进 redefine。 */
	@Test
	void badMagicIsRejectedBeforeRedefine() {
		byte[] bytes = validBytes(INTERNAL);
		bytes[0] = (byte) 0xDE;
		bytes[1] = (byte) 0xAD;
		bytes[2] = (byte) 0xBE;
		bytes[3] = (byte) 0xEF;
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertRejectedAndNeverRedefined(target, bytes, "magic");
	}

	/**
	 * 常量池被截断：魔数与 major 都合法、长度也远超 8 字节，只有逐项走常量池才能发现读到末端。
	 *
	 * <p>这条正是"不要只判 {@code length >= N}"的守卫：任何长度代理判据都会放它过去。</p>
	 */
	@Test
	void truncatedConstantPoolIsRejectedBeforeRedefine() {
		byte[] whole = validBytes(INTERNAL);
		byte[] truncated = Arrays.copyOf(whole, 16);
		Class<?> target = define(INTERNAL, whole);

		assertTrue(truncated.length >= 8,
		 "precondition: the sample is longer than the plain header, so a length check alone would pass it");
		assertRejectedAndNeverRedefined(target, truncated, "truncated");
	}

	/** 未知 class major：显式拒绝，而不是交给 JVM 去猜。 */
	@Test
	void unknownClassMajorIsRejectedBeforeRedefine() {
		byte[] bytes = validBytes(INTERNAL);
		bytes[6] = (byte) 0x03;
		bytes[7] = (byte) 0xE8; // 1000
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertRejectedAndNeverRedefined(target, bytes, "major");
	}

	/** 二进制名与目标类不一致：合法 class 文件也必须拒绝。 */
	@Test
	void binaryNameMismatchIsRejectedBeforeRedefine() {
		byte[] bytes = validBytes(OTHER_INTERNAL);
		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		assertRejectedAndNeverRedefined(target, bytes, "does not match");
	}

	// ------------------------------------------------------------ 正向对照

	/**
	 * 合法字节照常进 redefine：门禁不是"一律拦"。
	 *
	 * <p>同时断言送进 {@code redefineClasses} 的字节数组<b>就是</b>构造 {@link ClassDefinition} 时
	 * 那一份引用——校验与送 JVM 之间没有重读磁盘的窗口（风险 1 的 TOCTOU 半写）。</p>
	 */
	@Test
	void validDefinitionReachesRedefineWithTheSameBytesInstance() {
		byte[] bytes = validBytes(INTERNAL);
		Class<?> target = define(INTERNAL, bytes);
		ClassDefinition definition = new ClassDefinition(target, bytes);

		RemoldAgent.RedefineOutcome outcome = RemoldAgent.applyRedefinitions(
		 List.of(definition), List.of(), new RemoldAgent.RoundSkips());

		assertEquals(1, this.instrumentation.redefinitions.size(),
		 "a well-formed definition must reach redefineClasses (the gate cannot be a blanket block)");
		assertSame(definition, this.instrumentation.redefinitions.get(0)[0],
		 "the very same ClassDefinition instance must be handed to the JVM");
		assertSame(bytes, ((ClassDefinition) this.instrumentation.redefinitions.get(0)[0]).getDefinitionClassFile(),
		 "the bytes handed to the JVM must be the same array reference that was validated (no re-read)");
		assertTrue(outcome.successful().contains(DOT), "the accepted class is reported as redefined: " + outcome);
	}

	/** 门禁本身不复制、不替换字节：放行的就是刚才那一个对象。 */
	@Test
	void gateHandsOverTheSameInstance() {
		byte[] bytes = validBytes(INTERNAL);
		Class<?> target = define(INTERNAL, bytes);
		ClassDefinition definition = new ClassDefinition(target, bytes);

		List<ClassDefinition> safe = RemoldAgent.gateDefinitions(
		 List.of(definition), new RemoldAgent.RoundSkips());

		assertEquals(1, safe.size());
		assertSame(definition, safe.get(0), "the gate must not rebuild the definition");
		assertSame(bytes, safe.get(0).getDefinitionClassFile(), "the gate must not copy the bytes");
	}

	// ------------------------------------------------------------ major 上限的推导

	/**
	 * 上限按运行期 {@code Runtime.version().feature() + 44} 推导，不是钉死的某个常量。
	 *
	 * <p>边界两侧各断言一次：{@code MAX} 放行、{@code MAX + 1} 拒绝。这样"改成恒通过"与
	 * "钉死成 65"两种退化都会在这里露出。</p>
	 */
	@Test
	void classMajorCeilingIsDerivedFromTheRunningJvm() {
		assertEquals(Runtime.version().feature() + 44, RemoldAgent.MAX_CLASS_MAJOR,
		 "the ceiling must be derived from the running JVM, not hard-coded");

		Class<?> target = define(INTERNAL, validBytes(INTERNAL));

		byte[] atCeiling = validBytes(INTERNAL);
		atCeiling[6] = (byte) ((RemoldAgent.MAX_CLASS_MAJOR >> 8) & 0xFF);
		atCeiling[7] = (byte) (RemoldAgent.MAX_CLASS_MAJOR & 0xFF);
		assertNull(RemoldAgent.definitionProblem(target, atCeiling),
		 "a class file at the running JVM's ceiling must be accepted");

		byte[] aboveCeiling = validBytes(INTERNAL);
		int tooNew = RemoldAgent.MAX_CLASS_MAJOR + 1;
		aboveCeiling[6] = (byte) ((tooNew >> 8) & 0xFF);
		aboveCeiling[7] = (byte) (tooNew & 0xFF);
		String problem = RemoldAgent.definitionProblem(target, aboveCeiling);
		assertNotNull(problem, "a class file newer than the running JVM must be refused");
		assertTrue(problem.contains("major"), "the refusal must name the version problem: " + problem);
	}

	// ------------------------------------------------------------ 辅助

	/** 走真实入口：被拒的类不进 redefine、进 skip 台账、并有用户可见 warn。 */
	private void assertRejectedAndNeverRedefined(Class<?> target, byte[] bytes, String expectedReasonPart) {
		ClassDefinition definition = new ClassDefinition(target, bytes);
		RemoldAgent.RoundSkips skips = new RemoldAgent.RoundSkips();

		RemoldAgent.RedefineOutcome outcome = RemoldAgent.applyRedefinitions(
		 List.of(definition), List.of(), skips);

		assertTrue(this.instrumentation.redefinitions.isEmpty(),
		 "a rejected definition must never reach redefineClasses: " + this.instrumentation.redefinitions.size()
				+ " call(s) happened");
		assertFalse(outcome.successful().contains(target.getName()),
		 "a rejected class must not be reported as redefined");
		assertTrue(skips.contains(target.getName()),
		 "the rejected class must be named in the round's skip ledger: " + skips.get(target.getName()));
		String verdict = skips.get(target.getName());
		assertNotNull(verdict);
		assertTrue(verdict.startsWith("BYTECODE-GATE"),
		 "the skip reason must point at this gate: " + verdict);
		String warn = this.recorder.warns.stream()
		 .filter(message -> message.contains("BYTECODE-GATE") && message.contains(target.getName()))
		 .findFirst().orElse(null);
		assertNotNull(warn, "the refusal must be user visible through warn (never DEBUG-gated): " + this.recorder.warns);
		assertTrue(warn.contains(expectedReasonPart), "the warning must carry the concrete reason: " + warn);
	}

	private static Class<?> define(String internalName, byte[] bytes) {
		return new FixtureLoader(RedefineEntryGateTest.class.getClassLoader())
		 .define(internalName.replace('/', '.'), bytes);
	}

	/** 自造一份合法 class 文件（含一个构造器，便于 {@code defineClass} 直接定义）。 */
	private static byte[] validBytes(String internalName) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static final class FixtureLoader extends ClassLoader {
		FixtureLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(String dotName, byte[] bytes) {
			return defineClass(dotName, bytes, 0, bytes.length);
		}
	}

	private static final class RecordingLogger implements RemoldAgent.Logger {
		final List<String> warns = new ArrayList<>();
		final List<String> errors = new ArrayList<>();

		@Override public void log(String msg) { }
		@Override public void info(String msg) { }
		@Override public void warn(String msg) { this.warns.add(msg); }
		@Override public void error(String msg) { this.errors.add(msg); }
		@Override public void error(String msg, Throwable t) { this.errors.add(msg + ": " + t); }
	}

	/** 只实现被测路径用到的少数方法；{@code redefineClasses} 只录制，不真的重定义。 */
	private static final class RecordingInstrumentation implements InvocationHandler {
		final List<ClassDefinition[]> redefinitions = new ArrayList<>();
		final Instrumentation proxy = (Instrumentation) Proxy.newProxyInstance(
		 RedefineEntryGateTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class}, this);

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "isRedefineClassesSupported":
				case "isRetransformClassesSupported":
				case "isModifiableClass":
				case "isRetransformClassesSupportedByDefault":
					return true;
				case "getAllLoadedClasses":
				case "getInitiatedClasses":
					return new Class<?>[0];
				case "redefineClasses":
					this.redefinitions.add((ClassDefinition[]) args[0]);
					return null;
				case "addTransformer":
				case "removeTransformer":
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
			return 0;
		}
	}
}
