package remold;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassDiffUtil#describeAnnotationDefaultChange} 的判定力测试。
 *
 * <p>该函数是注解默认值门禁的唯一判定点：它必须看见 {@code AnnotationDefault} 的真实变化，
 * 又必须对"常量池位移但值没变""成员声明顺序变化""非注解类"保持沉默——否则正常热更会被误拒；
 * 解析不了时必须 fail-closed（返回不可判定原因），不能当作"没有变化"放行。</p>
 *
 * <p>夹具全部由内存 {@code javac} 现编，测试的是真实 {@code AnnotationDefault} 属性。</p>
 */
class ClassDiffUtilTest {

	private static final String MARK = "probe.dflt.Mark";

	private static byte[] mark(String body) {
		return Fixtures.compile(Map.of(MARK,
			"package probe.dflt;\n"
				+ "import java.lang.annotation.*;\n"
				+ "@Retention(RetentionPolicy.RUNTIME)\n"
				+ "@Target({ElementType.TYPE, ElementType.METHOD})\n"
				+ "public @interface Mark { " + body + " }\n")).get(MARK);
	}

	// ------------------------------------------------------------ 必须报警的形态

	@Test
	void defaultValueChangeIsReported() {
		String change = ClassDiffUtil.describeAnnotationDefaultChange(
			mark("String value() default \"v1\";"), mark("String value() default \"v2\";"));

		assertNotNull(change, "a changed AnnotationDefault must be reported");
		assertTrue(change.contains("value()"), "the report must name the changed member: " + change);
		assertTrue(change.contains("\"v1\"") && change.contains("\"v2\""),
			"the report must show both sides of the change: " + change);
	}

	@Test
	void removedDefaultIsReported() {
		String change = ClassDiffUtil.describeAnnotationDefaultChange(
			mark("String value() default \"v1\";"), mark("String value();"));

		assertNotNull(change, "removing a default must be reported (already-loaded classes keep the old value)");
		assertTrue(change.contains("value()") && change.contains("<none>"),
			"the report must show the member and the missing default: " + change);
	}

	@Test
	void addedDefaultIsReported() {
		String change = ClassDiffUtil.describeAnnotationDefaultChange(
			mark("String value();"), mark("String value() default \"v1\";"));

		assertNotNull(change, "adding a default changes what already-parsed annotations resolve to");
		assertTrue(change.contains("value()") && change.contains("<none>"),
			"the report must show the member and the previous absence: " + change);
	}

	@Test
	void nestedAnnotationDefaultChangeIsReported() {
		String before = "package probe.dflt;\nimport java.lang.annotation.*;\n"
			+ "@Retention(RetentionPolicy.RUNTIME)\n"
			+ "public @interface Mark { Inner inner() default @Inner(x = 3);\n"
			+ "  @Retention(RetentionPolicy.RUNTIME) @interface Inner { int x(); } }\n";
		String after = before.replace("x = 3", "x = 4");

		String change = ClassDiffUtil.describeAnnotationDefaultChange(
			Fixtures.compile(Map.of(MARK, before)).get(MARK),
			Fixtures.compile(Map.of(MARK, after)).get(MARK));

		assertNotNull(change, "a nested annotation default change must be reported (deep compare)");
		assertTrue(change.contains("inner()"), "the report must name the member: " + change);
	}

	@Test
	void arrayClassAndEnumDefaultsAreComparedDeeply() {
		String base = "package probe.dflt;\nimport java.lang.annotation.*;\n"
			+ "@Retention(RetentionPolicy.RUNTIME)\n"
			+ "public @interface Mark {\n"
			+ "  int[] nums() default {1, 2};\n"
			+ "  Class<?> type() default Object.class;\n"
			+ "  Color color() default Color.RED;\n"
			+ "}\n";
		String sources = "package probe.dflt;\npublic enum Color { RED, BLUE }\n";

		byte[] baseBytes = compileMarkWithEnum(base, sources);
		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(baseBytes, baseBytes),
			"identical array/class/enum defaults must not be reported");

		byte[] arrayChanged = compileMarkWithEnum(base.replace("{1, 2}", "{1, 2, 3}"), sources);
		String arrayChange = ClassDiffUtil.describeAnnotationDefaultChange(baseBytes, arrayChanged);
		assertNotNull(arrayChange, "an array default change must be reported");
		assertTrue(arrayChange.contains("nums()"), "the report must name the array member: " + arrayChange);

		byte[] classChanged = compileMarkWithEnum(base.replace("Object.class", "String.class"), sources);
		String classChange = ClassDiffUtil.describeAnnotationDefaultChange(baseBytes, classChanged);
		assertNotNull(classChange, "a class-literal default change must be reported");
		assertTrue(classChange.contains("type()"), "the report must name the class member: " + classChange);

		byte[] enumChanged = compileMarkWithEnum(base.replace("Color.RED", "Color.BLUE"), sources);
		String enumChange = ClassDiffUtil.describeAnnotationDefaultChange(baseBytes, enumChanged);
		assertNotNull(enumChange, "an enum default change must be reported");
		assertTrue(enumChange.contains("color()"), "the report must name the enum member: " + enumChange);
	}

	// ------------------------------------------------------------ 必须沉默的形态

	@Test
	void constantPoolShiftWithTheSameValueIsNotReported() {
		// 追加一个带默认值的新成员：常量池索引整体位移，但 value() 的默认值一个字都没变。
		// 成员增删属于结构变化（由结构门负责），这里绝不能误报成"默认值变化"。
		String change = ClassDiffUtil.describeAnnotationDefaultChange(
			mark("String value() default \"v1\";"),
			mark("String value() default \"v1\"; int size() default 2;"));

		assertNull(change, "a member addition shifts the constant pool but must not be reported as a"
			+ " default-value change: " + change);
	}

	@Test
	void memberDeclarationOrderDoesNotMatter() {
		byte[] first = mark("String value() default \"v1\"; int size() default 2;");
		byte[] second = mark("int size() default 2; String value() default \"v1\";");

		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(first, second),
			"declaration order changes are not default-value changes");
		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(second, first),
			"the comparison must be symmetric");
	}

	@Test
	void nonAnnotationClassesAreNotJudged() {
		Map<String, String> one = new LinkedHashMap<>();
		one.put("probe.fix.Plain", "package probe.fix;\npublic class Plain { public String who() { return \"v1\"; } }\n");
		Map<String, String> two = new LinkedHashMap<>();
		two.put("probe.fix.Plain", "package probe.fix;\npublic class Plain { public String who() { return \"v2\"; } }\n");

		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(
				Fixtures.compile(one).get("probe.fix.Plain"),
				Fixtures.compile(two).get("probe.fix.Plain")),
			"plain classes have no AnnotationDefault; the comparator must stay silent");
	}

	@Test
	void missingBaselineIsNotJudged() {
		assertNull(ClassDiffUtil.describeAnnotationDefaultChange(null, mark("String value() default \"v1\";")),
			"without a baseline there is nothing to compare; the caller decides what to do");
	}

	// ------------------------------------------------------------ fail-closed

	@Test
	void unparseableBytesAreFailClosed() {
		byte[] truncated = Arrays.copyOf(mark("String value() default \"v1\";"), 40);

		String change = ClassDiffUtil.describeAnnotationDefaultChange(
			truncated, mark("String value() default \"v2\";"));

		assertNotNull(change, "unparseable bytes must NOT be treated as 'no change' (fail-closed)");
		assertTrue(change.contains("cannot verify"),
			"the report must say the comparison is impossible: " + change);
	}

	private static byte[] compileMarkWithEnum(String annotationSource, String enumSource) {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put(MARK, annotationSource);
		sources.put("probe.dflt.Color", enumSource);
		return Fixtures.compile(sources).get(MARK);
	}
}
