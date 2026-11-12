package remold.evict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import remold.RemoldAgent;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次 K：参数注解 / 泛型签名漂移的成员级点名与驱逐范围扩展。
 *
 * <p>覆盖两层，缺一不可：</p>
 * <ol>
 *   <li><b>字节级比对</b>：只有成员元数据（{@code Signature} 属性 / 参数注解）变了才点名；
 *       纯方法体变化与无关成员不得误报（对照组）。</li>
 *   <li><b>接线终局</b>：告警真的经 {@link RemoldAgent#logger} 输出，点出成员名，并显式登记
 *       "重定义不刷新已解析的反射句柄、驱逐只覆盖已注册缓存"这条能力边界（不变量 C4）；
 *       漂移时驱逐范围必须从声明类扩到<b>已加载派生类</b>，无关类不得进入（对照组）。</li>
 * </ol>
 *
 * <p>夹具用 ASM 直接构造字节码而不是 javac：被测对象就是"两个版本的类文件"，构造字节能精确
 * 只改元数据一个维度，避免编译器的无关差异（行号、常量池顺序）污染判定。</p>
 */
public class ParameterAnnotationDriftTest {

	private static final String OWNER = "sample/Target";
	private static final String ANN_A = "Lsample/AnnA;";
	private static final String ANN_B = "Lsample/AnnB;";

	/** 驱逐范围夹具层级：Root <- Mid <- Leaf；Api <- ApiImpl；Unrelated 与两者无关。 */
	static class Root {}
	static class Mid extends Root {}
	static class Leaf extends Mid {}
	interface Api {}
	static class ApiImpl implements Api {}
	static class Unrelated {}

	//region 夹具构造

	private static byte[] target(java.util.function.Consumer<ClassNode> mutator) {
		ClassNode cn = new ClassNode();
		cn.version = Opcodes.V17;
		cn.access = Opcodes.ACC_PUBLIC;
		cn.name = OWNER;
		cn.superName = "java/lang/Object";
		mutator.accept(cn);
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private static MethodNode method(String name, String desc) {
		MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
		mn.instructions.add(new InsnNode(Opcodes.RETURN));
		return mn;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] params(AnnotationNode... anns) {
		List<AnnotationNode>[] arr = new List[anns.length];
		for (int i = 0; i < anns.length; i++) {
			if (anns[i] != null) arr[i] = new ArrayList<>(List.of(anns[i]));
		}
		return arr;
	}

	private static AnnotationNode annotated(String desc) {
		return new AnnotationNode(desc);
	}

	private static AnnotationNode annotated(String desc, String key, Object value) {
		AnnotationNode an = new AnnotationNode(desc);
		an.values = new ArrayList<>();
		an.values.add(key);
		an.values.add(value);
		return an;
	}

	private static MethodNode resolveWith(AnnotationNode firstParamAnnotation) {
		MethodNode mn = method("resolve", "(Ljava/lang/String;I)V");
		mn.visibleParameterAnnotations = params(firstParamAnnotation, null);
		return mn;
	}

	/** 注解类型变化（{@code AnnA} -> {@code AnnB}）的最小类对。 */
	private static byte[][] annotationTypeChangedPair() {
		byte[] before = target(cn -> {
			cn.methods.add(resolveWith(annotated(ANN_A)));
			cn.methods.add(method("untouched", "()V"));
		});
		byte[] after = target(cn -> {
			cn.methods.add(resolveWith(annotated(ANN_B)));
			cn.methods.add(method("untouched", "()V"));
		});
		return new byte[][]{before, after};
	}

	//endregion

	//region 字节级比对

	@Test
	@DisplayName("参数注解类型变化：点名该成员，且不牵连无关成员")
	public void testParameterAnnotationTypeChangeIsNamed() {
		byte[][] pair = annotationTypeChangedPair();

		List<String> drift = ReflectionCacheManager.detectMemberMetadataDrift(pair[0], pair[1]);
		assertEquals(1, drift.size(), "Exactly one member must be reported, got: " + drift);
		assertTrue(drift.get(0).contains("resolve(java.lang.String, int)"),
			"Drift must name the member readably: " + drift);
		assertTrue(drift.get(0).contains("parameter annotations"),
			"Drift must name the metadata kind: " + drift);
		assertFalse(drift.get(0).contains("untouched"),
			"Members whose metadata is unchanged must not be named: " + drift);

		// 对照组（换无关输入）：同一份字节自比必须零漂移
		assertTrue(ReflectionCacheManager.detectMemberMetadataDrift(pair[0], pair[0]).isEmpty(),
			"Identical bytes must report no drift");
	}

	@Test
	@DisplayName("参数注解元素值变化：同样点名（元素值也是旧句柄里缓存的元数据）")
	public void testParameterAnnotationElementValueChangeIsNamed() {
		byte[] before = target(cn -> cn.methods.add(resolveWith(annotated(ANN_A, "value", "alpha"))));
		byte[] after = target(cn -> cn.methods.add(resolveWith(annotated(ANN_A, "value", "beta"))));

		List<String> drift = ReflectionCacheManager.detectMemberMetadataDrift(before, after);
		assertEquals(1, drift.size(), "Element value change must be reported, got: " + drift);
		assertTrue(drift.get(0).contains("parameter annotations"), "Kind must be named: " + drift);
	}

	@Test
	@DisplayName("泛型签名变化：方法签名与字段泛型都要点名")
	public void testGenericSignatureChangeIsNamed() {
		byte[] before = target(cn -> {
			MethodNode mn = method("map", "(Ljava/util/List;)V");
			mn.signature = "(Ljava/util/List<Ljava/lang/String;>;)V";
			cn.methods.add(mn);
			cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "items", "Ljava/util/List;",
				"Ljava/util/List<Ljava/lang/String;>;", null));
		});
		byte[] after = target(cn -> {
			MethodNode mn = method("map", "(Ljava/util/List;)V");
			mn.signature = "(Ljava/util/List<Ljava/lang/Integer;>;)V";
			cn.methods.add(mn);
			cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "items", "Ljava/util/List;",
				"Ljava/util/List<Ljava/lang/Integer;>;", null));
		});

		List<String> drift = ReflectionCacheManager.detectMemberMetadataDrift(before, after);
		assertEquals(2, drift.size(), "Both the method and the field must be named, got: " + drift);
		String joined = String.join("\n", drift);
		assertTrue(joined.contains("map(java.util.List)"), "Method must be named: " + joined);
		assertTrue(joined.contains("items: java.util.List"), "Field must be named: " + joined);
		assertTrue(joined.contains("generic signature"), "Kind must be named: " + joined);
	}

	@Test
	@DisplayName("对照组（去触发）：只有方法体变化时不得点名")
	public void testBodyOnlyChangeIsNotReported() {
		byte[] before = target(cn -> cn.methods.add(method("work", "()V")));
		byte[] after = target(cn -> {
			MethodNode mn = method("work", "()V");
			mn.instructions.clear();
			mn.instructions.add(new LdcInsnNode("changed-body"));
			mn.instructions.add(new InsnNode(Opcodes.RETURN));
			cn.methods.add(mn);
		});

		assertTrue(ReflectionCacheManager.detectMemberMetadataDrift(before, after).isEmpty(),
			"Body-only changes must not be reported as signature drift");
	}

	@Test
	@DisplayName("缺基线（oldBytes 为空）时不判定，也不伪装成无漂移")
	public void testMissingBaselineIsNotJudged() {
		byte[][] pair = annotationTypeChangedPair();
		assertTrue(ReflectionCacheManager.detectMemberMetadataDrift(null, pair[1]).isEmpty(),
			"No baseline => no verdict");
		assertTrue(ReflectionCacheManager.describeMemberMetadataDrift("sample.Target", null, pair[1]).isEmpty(),
			"No baseline => no warning lines");
	}

	//endregion

	//region 接线终局（告警 + 能力边界登记）

	@Test
	@DisplayName("接线终局：WARN 经 RemoldAgent.logger 点名成员并登记旧句柄不安全（C4）")
	public void testWarnNamesMemberAndRegistersUnsafeHandleBoundary() {
		byte[][] pair = annotationTypeChangedPair();

		CapturingLogger captured = new CapturingLogger();
		RemoldAgent.Logger previous = RemoldAgent.logger;
		RemoldAgent.logger = captured;
		boolean drift;
		try {
			drift = RemoldAgent.reportMemberMetadataDrift(
				Map.of("sample.Target", pair[1]), Map.of("sample.Target", pair[0]));
		} finally {
			RemoldAgent.logger = previous;
		}

		assertTrue(drift, "Drift must be reported to the caller (it drives the eviction-scope expansion)");
		assertFalse(captured.warnings.isEmpty(), "WARN must be emitted through RemoldAgent.logger");
		String all = String.join("\n", captured.warnings);
		assertTrue(all.contains("SIG-DRIFT"), "Warning must carry a searchable tag: " + all);
		assertTrue(all.contains("resolve(java.lang.String, int)"), "Warning must name the member: " + all);
		assertTrue(all.toUpperCase(Locale.ROOT).contains("UNSAFE"),
			"Warning must register that old reflection handles are unsafe: " + all);
		assertTrue(all.toUpperCase(Locale.ROOT).contains("REGISTERED CACHES"),
			"Warning must state that eviction only covers registered caches (C4, not 'solved'): " + all);
	}

	@Test
	@DisplayName("对照组（去触发）：字节未变或缺基线时不发 WARN")
	public void testNoWarnWithoutDrift() {
		byte[][] pair = annotationTypeChangedPair();

		CapturingLogger captured = new CapturingLogger();
		RemoldAgent.Logger previous = RemoldAgent.logger;
		RemoldAgent.logger = captured;
		boolean drift;
		boolean missingBaseline;
		try {
			drift = RemoldAgent.reportMemberMetadataDrift(
				Map.of("sample.Target", pair[0]), Map.of("sample.Target", pair[0]));
			missingBaseline = RemoldAgent.reportMemberMetadataDrift(
				Map.of("sample.Target", pair[1]), Map.of());
		} finally {
			RemoldAgent.logger = previous;
		}

		assertFalse(drift, "Identical bytes must not trigger drift");
		assertFalse(missingBaseline, "Missing baseline must not be treated as drift");
		assertTrue(captured.warnings.isEmpty(), "No warning may be emitted, got: " + captured.warnings);
	}

	//endregion

	//region 驱逐范围扩展

	@Test
	@DisplayName("驱逐范围：漂移时纳入已加载派生类，无关类不得进入")
	public void testEvictionScopeExpandsToLoadedDescendants() {
		ScopeRecorder recorder = new ScopeRecorder();
		Class<?>[] loaded = {Root.class, Mid.class, Leaf.class, Api.class, ApiImpl.class, Unrelated.class};
		ReflectionCacheManager.registerEvictor(recorder);
		try {
			// 对照组（去触发）：DECLARED 只给声明类，与旧行为一致
			recorder.received.clear();
			ReflectionCacheManager.evictAll(Set.of(Root.class),
				ReflectionCacheManager.EvictionScope.DECLARED, loaded);
			assertEquals(Set.of(Root.class), recorder.received,
				"DECLARED scope must only pass the declared class");

			// 触发：WITH_DESCENDANTS 必须包含全部已加载后代（含传递子类）
			recorder.received.clear();
			ReflectionCacheManager.evictAll(Set.of(Root.class),
				ReflectionCacheManager.EvictionScope.WITH_DESCENDANTS, loaded);
			assertEquals(Set.of(Root.class, Mid.class, Leaf.class), recorder.received,
				"WITH_DESCENDANTS must include loaded descendants: " + recorder.received);
			assertFalse(recorder.received.contains(Unrelated.class),
				"Unrelated classes must never enter the eviction scope");
		} finally {
			ReflectionCacheManager.unregisterEvictor(recorder);
		}
	}

	@Test
	@DisplayName("驱逐范围：接口作为声明类时纳入其已加载实现者")
	public void testEvictionScopeCoversInterfaceImplementors() {
		ScopeRecorder recorder = new ScopeRecorder();
		Class<?>[] loaded = {Root.class, Api.class, ApiImpl.class, Unrelated.class};
		ReflectionCacheManager.registerEvictor(recorder);
		try {
			ReflectionCacheManager.evictAll(Set.of(Api.class),
				ReflectionCacheManager.EvictionScope.WITH_DESCENDANTS, loaded);
			assertEquals(Set.of(Api.class, ApiImpl.class), recorder.received,
				"Implementors of the changed interface must be evicted too: " + recorder.received);
		} finally {
			ReflectionCacheManager.unregisterEvictor(recorder);
		}
	}

	@Test
	@DisplayName("驱逐范围：没有已加载快照时不猜测，退化为声明类")
	public void testEvictionScopeWithoutSnapshotFallsBackToDeclared() {
		ScopeRecorder recorder = new ScopeRecorder();
		ReflectionCacheManager.registerEvictor(recorder);
		try {
			ReflectionCacheManager.evictAll(Set.of(Root.class),
				ReflectionCacheManager.EvictionScope.WITH_DESCENDANTS, null);
			assertEquals(Set.of(Root.class), recorder.received,
				"Without a loaded-class snapshot the scope must not be guessed");
		} finally {
			ReflectionCacheManager.unregisterEvictor(recorder);
		}
	}

	//endregion

	//region 辅助

	private static final class ScopeRecorder implements ReflectionCacheEvictor {
		final Set<Class<?>> received = new LinkedHashSet<>();

		@Override
		public String getName() { return "ScopeRecorder"; }

		@Override
		public int evict(Set<Class<?>> redefinedClasses) {
			received.clear();
			received.addAll(redefinedClasses);
			return redefinedClasses.size();
		}
	}

	private static final class CapturingLogger implements RemoldAgent.Logger {
		final List<String> warnings = new ArrayList<>();

		@Override
		public void log(String msg) {}

		@Override
		public void info(String msg) {}

		@Override
		public void warn(String msg) { warnings.add(msg); }

		@Override
		public void error(String msg) {}

		@Override
		public void error(String msg, Throwable t) {}
	}

	//endregion
}
