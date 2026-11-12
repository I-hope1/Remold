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
 *       （驱逐范围扩展已整体移到 {@code parked/k-eviction-scope} 分支；主干只保留检测 + WARN 点名。）</li>
 * </ol>
 *
 * <p>夹具用 ASM 直接构造字节码而不是 javac：被测对象就是"两个版本的类文件"，构造字节能精确
 * 只改元数据一个维度，避免编译器的无关差异（行号、常量池顺序）污染判定。</p>
 */
public class ParameterAnnotationDriftTest {

	private static final String OWNER = "sample/Target";
	private static final String ANN_A = "Lsample/AnnA;";
	private static final String ANN_B = "Lsample/AnnB;";


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

	@Test
	@DisplayName("真实 javac 字节：参数注解值变化被点名（属性布局与运行时一致）")
	public void testRealJavacParameterAnnotationDrift() throws Exception {
		byte[] before = compileReal("alpha");
		byte[] after = compileReal("beta");

		List<String> drift = ReflectionCacheManager.detectMemberMetadataDrift(before, after);
		assertEquals(1, drift.size(), "Real javac output must be judged too, got: " + drift);
		assertTrue(drift.get(0).contains("resolve(java.lang.String)"), "Member must be named: " + drift);
		assertTrue(drift.get(0).contains("parameter annotations"), "Kind must be named: " + drift);
	}

	/**
	 * 用真实 javac 编一个带参数注解的类（注解为 {@code RUNTIME} 保留，落在
	 * {@code RuntimeVisibleParameterAnnotations} 属性里），返回类文件字节。
	 *
	 * <p>存在的意义：ASM 夹具证明的是"比对逻辑"，这里证明"javac 真实写出的属性布局"
	 * 也能被同一逻辑读出来（槽位对齐、属性存在性），避免只有自造字节能过。</p>
	 */
	private static byte[] compileReal(String kind) throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sigdrift");
		java.nio.file.Path src = dir.resolve("sample").resolve("Target.java");
		java.nio.file.Files.createDirectories(src.getParent());
		java.nio.file.Files.writeString(src,
			"package sample;\n"
			+ "public class Target {\n"
			+ "    public void resolve(@Doc(kind = \"" + kind + "\") String name) {}\n"
			+ "}\n");
		java.nio.file.Path ann = dir.resolve("sample").resolve("Doc.java");
		java.nio.file.Files.writeString(ann,
			"package sample;\n"
			+ "import java.lang.annotation.ElementType;\n"
			+ "import java.lang.annotation.Retention;\n"
			+ "import java.lang.annotation.RetentionPolicy;\n"
			+ "import java.lang.annotation.Target;\n"
			+ "@Retention(RetentionPolicy.RUNTIME)\n"
			+ "@Target(ElementType.PARAMETER)\n"
			+ "public @interface Doc { String kind(); }\n");
		javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "A JDK compiler is required for this fixture");
		java.nio.file.Path out = dir.resolve("out");
		int rc = javac.run(null, null, null, "-d", out.toString(), src.toString(), ann.toString());
		assertEquals(0, rc, "javac must compile the fixture");
		return java.nio.file.Files.readAllBytes(out.resolve("sample").resolve("Target.class"));
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

		assertTrue(drift, "Drift must be reported to the caller (the pipeline uses the boolean to decide"
			+ " whether to report at all; the scope-expansion consumer is parked, see parked/k-eviction-scope)");
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

	//region 辅助

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
