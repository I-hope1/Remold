import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import remold.AnonClassAligner;

/**
 * S22 根因回归：枚举常量体 {@code Op$1} 与匿名类同形，但它是独立类（{@code ACC_ENUM}、
 * 由 {@code Op.<clinit>} 构造），不得被批次路由成"宿主类下属匿名类"——否则对齐器不消费它，
 * 批次会把它当"已位移的旧名"静默移出本批，旧版本永远热不掉。
 */
class EnumConstantBodyRoutingTest {

	private static final String ENUM_WITH_BODY =
		"public enum Op {\n"
		+ "  ADD { @Override public String f() { return \"x\"; } };\n"
		+ "  public abstract String f();\n"
		+ "}\n";

	private static final String ANON_HOST =
		"public class H {\n"
		+ "  public static Runnable r = new Runnable() { public void run() { } };\n"
		+ "}\n";

	@Test
	void enumConstantBodyIsNotRoutedAsAnAnonymousClass() {
		Map<String, byte[]> classes = LayoutGateAssert.compile(Map.of("Op", ENUM_WITH_BODY));
		byte[] body = classes.get("Op$1");
		assertNotNull(body, "javac must emit Op$1 for an enum constant body");

		// 名字判据与字节判据必然分歧——分歧本身正是 S22 的根因（名字说"匿名"，字节说"独立类"）。
		assertTrue(AnonClassAligner.isAnonymousClassName("Op", "Op$1"),
			"name-level predicate still says anonymous (legacy heuristic)");
		assertFalse(AnonClassAligner.isAnonymousClass(body, "Op"),
			"enum constant body carries ACC_ENUM and must be excluded from anonymous admission");
		assertFalse(AnonClassAligner.isBatchAnonClass("Op$1", body),
			"enum constant body must be redefined as an independent class, not routed into a host alignment group");
	}

	@Test
	void realAnonymousClassIsStillRoutedForAlignment() {
		Map<String, byte[]> classes = LayoutGateAssert.compile(Map.of("H", ANON_HOST));
		byte[] anon = classes.get("H$1");
		assertNotNull(anon, "javac must emit H$1 for the anonymous inner class");
		assertTrue(AnonClassAligner.isBatchAnonClass("H$1", anon),
			"a real anonymous class must still be admitted into the host alignment group");
	}
}
