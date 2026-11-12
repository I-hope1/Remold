package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code redefineDynamic} 的两阶段接线：新增带初值字段必须触发
 * <b>Stage A redefine → 补丁 → Stage C redefine</b> 的顺序契约。
 *
 * <p><b>为什么必须两阶段</b>：Mixin 热更给目标类新增 {@code @Unique} 字段并在 {@code @Inject}
 * 里立刻读取时走的就是 {@code redefineDynamic}。单次提交会让并发 tick 线程在补丁落盘前读到
 * 字段的零值（null/0）——与批次路径同一个读穿窗口。</p>
 *
 * <p><b>为什么可以在调用内部同步跑完</b>：调用方（MixinHotSwap / ForgeMixinHotSwap）在返回后
 * 立即做后续处理，所以 A → 补丁 → C 全部在 {@code redefineDynamic} 返回前完成；返回时 JVM
 * 已经是终态，公开 API 的返回语义不变。阶段 C 直接提交现成的 {@code runtimeBytes}，不重跑
 * transformer 桥接或 {@code InitFix.transform}。</p>
 *
 * <p><b>断言口径</b>：不依赖"某方法被调用"这种代理信号——用录制 {@link Instrumentation} 抓
 * {@code redefineClasses} 的<b>顺序与字节</b>：第一次必须是 Stage A（新字段结构 + 旧方法体），
 * 第二次必须是终态字节；补丁阶段用日志事件定位在两次 redefine 之间。</p>
 */
class DynamicTwoPhaseRedefineTest {

	private static final String DOTTED = "probe.dyn.Target";

	/** V1：一个静态字段 + 一个方法。 */
	private static final String V1 = """
		package probe.dyn;
		public class Target {
			public static int a = 1;
			public static String tag() { return "v1"; }
		}
		""";

	/** V2：新增带初值的静态字段 b（需要 InitFix 补丁），同时改方法体。 */
	private static final String V2 = """
		package probe.dyn;
		public class Target {
			public static int a = 1;
			public static int b = 42;
			public static String tag() { return "v2"; }
		}
		""";

	/** 纯方法体变化：不该触发两阶段。 */
	private static final String V1_BODY_ONLY = """
		package probe.dyn;
		public class Target {
			public static int a = 1;
			public static String tag() { return "v2"; }
		}
		""";

	private final RemoldAgent.Logger originalLogger = RemoldAgent.logger;
	private final List<String> events = new ArrayList<>();
	private final List<byte[]> redefined = new ArrayList<>();
	private Instrumentation previousInst;

	@BeforeEach
	void installRecorder() {
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override public void log(String msg) { }
			@Override public void info(String msg) {
				// 补丁阶段的定位信号：applyPatch 在跑字段任务前必打这一行。
				if (msg != null && msg.contains("Applying static field init patch")) events.add("patch-phase");
			}
			@Override public void warn(String msg) { }
			@Override public void error(String msg) { }
			@Override public void error(String msg, Throwable t) { }
		};
		previousInst = RemoldAgent.swapInstrumentationForTest(recorder());
	}

	@AfterEach
	void restore() {
		RemoldAgent.swapInstrumentationForTest(previousInst);
		RemoldAgent.logger = originalLogger;
		RemoldAgent.bytecodeCache.remove(DOTTED);
	}

	// ------------------------------------------------------------ 用例

	@Test
	void addedInitializedFieldRunsStageAThenPatchThenStageC() throws Exception {
		Map<String, byte[]> v1 = Fixtures.compile(Map.of(DOTTED, V1));
		Map<String, byte[]> v2 = Fixtures.compile(Map.of(DOTTED, V2));
		Class<?> target = loadAndInitialize(v1.get(DOTTED));

		assertTrue(RemoldAgent.redefineDynamic(target, v2.get(DOTTED)),
			"a two-phase dynamic redefine must report success");

		assertEquals(2, redefined.size(),
			"new field with initializer must be committed twice (stage A then stage C), actual=" + redefined.size());

		byte[] stageA = redefined.get(0);
		byte[] stageC = redefined.get(1);

		// 1) Stage A = 新字段结构 + 旧方法体；Stage C = 终态字节。
		assertFalse(Arrays.equals(stageA, stageC),
			"stage A and stage C must be different bytecode (old bodies vs new bodies)");
		assertTrue(hasField(stageA, "b"), "stage A must carry the new field declaration");
		assertEquals("v1", tagBody(stageA), "stage A must keep the OLD method body (tag returns v1)");
		assertEquals("v2", tagBody(stageC), "stage C must carry the new method body (tag returns v2)");

		// 2) 顺序契约：redefine(stageA) → 补丁 → redefine(stageC)。
		int first = events.indexOf("redefine");
		int patch = events.indexOf("patch-phase");
		int second = indexOfFrom(events, "redefine", first + 1);
		assertTrue(first >= 0 && second > first,
			"exactly two redefineClasses calls are expected; events=" + events);
		assertTrue(patch > first && patch < second,
			"the field-init patch must run between stage A and stage C; events=" + events);

		// 3) 终态：返回时缓存指向 C，且返回 true 不是"停在阶段 A"。
		assertArrayEquals(stageC, RemoldAgent.bytecodeCache.get(DOTTED),
			"after the call the live bytecode baseline must be stage C");
	}

	@Test
	void methodBodyOnlyChangeStaysSinglePhase() throws Exception {
		Map<String, byte[]> v1 = Fixtures.compile(Map.of(DOTTED, V1));
		Map<String, byte[]> v2 = Fixtures.compile(Map.of(DOTTED, V1_BODY_ONLY));
		Class<?> target = loadAndInitialize(v1.get(DOTTED));

		assertTrue(RemoldAgent.redefineDynamic(target, v2.get(DOTTED)),
			"a pure method-body change must still succeed");

		assertEquals(1, redefined.size(),
			"pure method-body change must NOT pay a second redefine; actual=" + redefined.size());
		assertEquals("v2", tagBody(redefined.get(0)), "the single redefine must carry the new body");
		assertFalse(events.contains("patch-phase"),
			"no field init patch should run when no field was added; events=" + events);
	}

	// ------------------------------------------------------------ 工具

	private Class<?> loadAndInitialize(byte[] bytes) throws Exception {
		ClassLoader loader = new ClassLoader(DynamicTwoPhaseRedefineTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (name.equals(DOTTED)) return defineClass(name, bytes, 0, bytes.length);
				throw new ClassNotFoundException(name);
			}
		};
		Class<?> target = Class.forName(DOTTED, true, loader);
		target.getDeclaredMethod("tag").invoke(null);      // 触发 <clinit>，补丁前置守卫要求类已初始化
		RemoldAgent.bytecodeCache.put(DOTTED, bytes);
		return target;
	}

	/** 录制 {@code redefineClasses} 的字节与次数；其余 Instrumentation 方法给中性返回。 */
	private Instrumentation recorder() {
		InvocationHandler handler = (proxy, method, args) -> {
			switch (method.getName()) {
				case "isRedefineClassesSupported":
					return true;
				case "isModifiableClass":
					return true;
				case "redefineClasses": {
					for (ClassDefinition definition : (ClassDefinition[]) args[0]) {
						redefined.add(definition.getDefinitionClassFile());
						events.add("redefine");
					}
					return null;
				}
				case "getAllLoadedClasses":
				case "getInitiatedClasses":
					return new Class<?>[0];
				case "getObjectSize":
					return 0L;
				case "toString":
					return "RecordingInstrumentation";
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				default:
					return defaultValue(method.getReturnType());
			}
		};
		return (Instrumentation) Proxy.newProxyInstance(
			Instrumentation.class.getClassLoader(), new Class<?>[] { Instrumentation.class }, handler);
	}

	private static Object defaultValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == void.class) return null;
		if (type == long.class) return 0L;
		if (type == float.class) return 0f;
		if (type == double.class) return 0d;
		if (type == char.class) return (char) 0;
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		return 0;
	}

	private static int indexOfFrom(List<String> list, String value, int from) {
		for (int i = from; i < list.size(); i++) {
			if (value.equals(list.get(i))) return i;
		}
		return -1;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	private static boolean hasField(byte[] bytes, String name) {
		for (FieldNode field : parse(bytes).fields) {
			if (field.name.equals(name)) return true;
		}
		return false;
	}

	/** 取 {@code tag()} 方法体里的 LDC 字符串常量（夹具用它区分旧/新执行体）。 */
	private static String tagBody(byte[] bytes) {
		for (MethodNode mn : parse(bytes).methods) {
			if (!mn.name.equals("tag")) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) return s;
			}
		}
		return null;
	}
}
