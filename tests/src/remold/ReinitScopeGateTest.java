package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import remold.LayoutGate.Verdict;
import remold.RemoldAgent.LayoutAction;
import remold.RemoldAgent.LayoutDecision;
import remold.ref.InitFix;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守卫：{@code @RemoldReinit(scope = INCLUDE_SUBCLASSES)} 不得把声明类初值写进"自己在
 * {@code <init>} 里写继承字段"的子类实例（{@code docs/initfix/03-runtime-driver.md} §4）。
 *
 * <p>【因果链】</p>
 * <ol>
 *   <li>前置基线：夹具子类字节码里确实存在对父类字段的 {@code PUTFIELD}（否则用例是空转）；</li>
 *   <li>终局断言：检测门必须给出"该字段不安全 + 点名子类"的结论，布局门必须 REJECT 并移出宿主；</li>
 *   <li>对照组：显式 {@code scope = SELF}、子类不写该字段、子类只读该字段都不得误伤；</li>
 *   <li>fail-closed：子类已登记但字节读不到时必须拒绝，不得当成"没有子类写它"。</li>
 * </ol>
 *
 * <p>依赖的完备性事实：子类要产生存活实例必先被加载，加载必经 transform 并登记进
 * {@code HierarchyTree}；因此"已登记子类"就是"可能有实例的子类"的完备集合
 * （见 {@code AnnotationTransformer.HierarchyTree#knownSubtypes}）。本用例手工登记夹具类，
 * 模拟该注册表在真实运行时的状态。</p>
 */
class ReinitScopeGateTest {

	private static final String FIELD = "health";

	@Test
	void subclassInitializerWritingInheritedFieldIsRejected() throws Exception {
		String pkg = "reinitgate1";
		Map<String, byte[]> classes = compile(pkg, annotatedParent(pkg, ""), holder(pkg, "return new Parent() {{ health = 100; }};"));
		byte[] parent = classes.get(pkg + "/Parent");
		byte[] anon = classes.get(pkg + "/Holder$1");
		assertNotNull(parent, "precondition: Parent compiled");
		assertNotNull(anon, "precondition: anonymous subclass compiled");

		// 前置基线：子类 <init> 里确实写父类的 health 字段（不是读，也不是写别的字段）。
		assertTrue(initializerWrites(anon, FIELD),
			"precondition: the anonymous subclass initializer must assign the inherited field");
		assertTrue(declaresField(anon, FIELD) == false,
			"precondition: the subclass does not shadow the field; it writes the inherited one");

		register(pkg + "/Parent", parent);
		register(pkg + "/Holder$1", anon);
		putCache(pkg + ".Holder$1", anon);
		try {
			Map<String, String> unsafe = InitFix.unsafeInheritedReinitFields(null, node(parent), Set.of(FIELD));
			assertFalse(unsafe.isEmpty(),
				"a subclass initializer writing the inherited field must be detected");
			assertTrue(unsafe.get(FIELD).contains(pkg + ".Holder$1"),
				"the reason must name the offending subclass: " + unsafe.get(FIELD));
			assertTrue(unsafe.get(FIELD).contains("INCLUDE_SUBCLASSES"),
				"the reason must explain the scope hazard: " + unsafe.get(FIELD));

			// 终局：布局判决必须拒绝并移出宿主（而不是只 warn 后继续覆盖子类值）。
			LayoutDecision d = LayoutDecisionEngine.rejectUnsafeReinitScope(
				pkg + ".Parent", Set.of(pkg + ".Parent", pkg + ".Holder$1"), unsafe);
			assertEquals(LayoutAction.REJECT, d.action());
			assertTrue(d.dropped().contains(pkg + ".Parent"), "the host must be dropped from the batch");
			assertTrue(d.reason().contains(FIELD) && d.reason().contains(pkg + ".Holder$1"),
				"reject reason must name the field and subclass: " + d.reason());
			String summary = LayoutDecisionEngine.skipVerdictForLayout(d, null);
			assertTrue(summary.contains("unsafe @RemoldReinit scope"),
				"the one-line summary must be self-explanatory: " + summary);
			assertTrue(summary.contains("scope = SELF"),
				"the summary must tell the operator about the explicit escape hatch: " + summary);
		} finally {
			clearCache(pkg + ".Holder$1");
		}
	}

	@Test
	void explicitSelfScopeIsTheEscapeHatch() throws Exception {
		String pkg = "reinitgate2";
		Map<String, byte[]> classes = compile(pkg,
			annotatedParent(pkg, "(scope = remold.annotation.RemoldReinit.Scope.SELF)"),
			holder(pkg, "return new Parent() {{ health = 100; }};"));
		byte[] parent = classes.get(pkg + "/Parent");
		byte[] anon = classes.get(pkg + "/Holder$1");
		assertTrue(initializerWrites(anon, FIELD), "precondition: the subclass still writes the field");

		register(pkg + "/Parent", parent);
		register(pkg + "/Holder$1", anon);
		putCache(pkg + ".Holder$1", anon);
		try {
			Map<String, String> unsafe = InitFix.unsafeInheritedReinitFields(null, node(parent), Set.of(FIELD));
			assertTrue(unsafe.isEmpty(),
				"scope = SELF is the explicit safe escape hatch and must not be blocked: " + unsafe);
		} finally {
			clearCache(pkg + ".Holder$1");
		}
	}

	@Test
	void subclassThatDoesNotTouchTheFieldIsFine() throws Exception {
		String pkg = "reinitgate3";
		Map<String, byte[]> classes = compile(pkg,
			annotatedParent(pkg, ""),
			"package " + pkg + ";\npublic class Plain extends Parent { }\n");
		byte[] parent = classes.get(pkg + "/Parent");
		byte[] plain = classes.get(pkg + "/Plain");
		assertNotNull(plain, "precondition: the named subclass compiled");
		assertFalse(initializerWrites(plain, FIELD),
			"precondition: this subclass does not write the inherited field");

		register(pkg + "/Parent", parent);
		putCache(pkg + ".Plain", plain);
		try {
			Map<String, String> unsafe = InitFix.unsafeInheritedReinitFields(null, node(parent), Set.of(FIELD));
			assertTrue(unsafe.isEmpty(),
				"a subclass that does not write the inherited field must not trip the gate: " + unsafe);
		} finally {
			clearCache(pkg + ".Plain");
		}
	}

	@Test
	void registeredSubclassWithUnreadableBytesIsFailClosed() throws Exception {
		String pkg = "reinitgate4";
		Map<String, byte[]> classes = compile(pkg,
			annotatedParent(pkg, ""),
			"package " + pkg + ";\npublic class Plain extends Parent { }\n");
		byte[] parent = classes.get(pkg + "/Parent");
		byte[] plain = classes.get(pkg + "/Plain");
		register(pkg + "/Parent", parent);
		register(pkg + "/Plain", plain);            // 登记了子类（=可能有实例）
		clearCache(pkg + ".Plain");                 // 但字节读不到
		try {
			Map<String, String> unsafe = InitFix.unsafeInheritedReinitFields(null, node(parent), Set.of(FIELD));
			assertFalse(unsafe.isEmpty(),
				"an uninspectable registered subclass must fail closed, not be read as 'no subclass writes'");
			assertTrue(unsafe.get(FIELD).contains("cannot inspect"), unsafe.get(FIELD));
		} finally {
			clearCache(pkg + ".Plain");
		}
	}

	@Test
	void staticFieldScopeGateDoesNotApply() throws Exception {
		String pkg = "reinitgate5";
		String parentSrc =
			"package " + pkg + ";\n"
			+ "public class Parent {\n"
			+ "    @remold.annotation.RemoldReinit protected static int counter = 1;\n"
			+ "}\n";
		Map<String, byte[]> classes = compile(pkg, parentSrc,
			holder(pkg, "return new Parent() {{ counter = 5; }};"));
		byte[] parent = classes.get(pkg + "/Parent");
		byte[] anon = classes.get(pkg + "/Holder$1");
		assertNotNull(anon, "precondition: the anonymous subclass compiled");
		register(pkg + "/Parent", parent);
		register(pkg + "/Holder$1", anon);
		putCache(pkg + ".Holder$1", anon);
		try {
			Map<String, String> unsafe = InitFix.unsafeInheritedReinitFields(null, node(parent), Set.of("counter"));
			assertTrue(unsafe.isEmpty(),
				"static fields have a single slot; scope is meaningless and the gate must ignore them: " + unsafe);
		} finally {
			clearCache(pkg + ".Holder$1");
		}
	}

	// ------------------------------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------------------------------

	private static String annotatedParent(String pkg, String annotationArgs) {
		return "package " + pkg + ";\n"
			+ "public class Parent {\n"
			+ "    @remold.annotation.RemoldReinit" + annotationArgs + "\n"
			+ "    protected int " + FIELD + " = 1;\n"
			+ "    public int " + FIELD + "() { return " + FIELD + "; }\n"
			+ "}\n";
	}

	private static String holder(String pkg, String body) {
		return "package " + pkg + ";\n"
			+ "public class Holder {\n"
			+ "    public static Parent make() { " + body + " }\n"
			+ "}\n";
	}

	private static void register(String internalName, byte[] bytes) {
		AnnotationTransformer.HierarchyTree.register(bytes);
	}

	private static void putCache(String dotName, byte[] bytes) {
		RemoldAgent.bytecodeCache.put(dotName, bytes);
	}

	private static void clearCache(String dotName) {
		RemoldAgent.bytecodeCache.remove(dotName);
		RemoldAgent.sourceBytecodeCache.remove(dotName);
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	private static boolean declaresField(byte[] bytes, String field) {
		ClassNode cn = node(bytes);
		for (FieldNode f : cn.fields) {
			if (f.name.equals(field)) return true;
		}
		return false;
	}

	private static boolean initializerWrites(byte[] bytes, String field) {
		ClassNode cn = node(bytes);
		for (MethodNode m : cn.methods) {
			if (!"<init>".equals(m.name)) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode fin && fin.getOpcode() == Opcodes.PUTFIELD
				    && fin.name.equals(field)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Map<String, byte[]> compile(String pkg, String... sources) throws Exception {
		Path dir = Files.createTempDirectory("reinit_scope");
		List<File> files = new ArrayList<>();
		for (int i = 0; i < sources.length; i++) {
			String source = sources[i];
			java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("(?:public\\s+)?(?:final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+(\\w+)")
				.matcher(source);
			if (!m.find()) throw new IllegalStateException("cannot find the top-level type name in source " + i);
			Path src = dir.resolve(m.group(1) + ".java");
			Files.writeString(src, source, StandardCharsets.UTF_8);
			files.add(src.toFile());
		}
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("JDK required");
		List<String> options = new ArrayList<>(List.of("-d", dir.toString(), "-g", "-nowarn",
			"-classpath", System.getProperty("java.class.path")));
		java.io.StringWriter diag = new java.io.StringWriter();
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			Boolean ok = javac.getTask(diag, fm, null, options, null,
				fm.getJavaFileObjectsFromFiles(files)).call();
			if (!Boolean.TRUE.equals(ok)) {
				throw new IllegalStateException("compilation failed: " + pkg + "\n" + diag);
			}
		}
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				String rel = dir.relativize(p).toString().replace('\\', '/');
				if (!rel.endsWith(".class")) continue;
				out.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
			}
		}
		return out;
	}
}
