import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import remold.LambdaAligner;
import remold.uihook.CellPropertyRef;
import remold.uihook.CellPropertyRef.PropertyCall;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch-scoped lambda mapping must be addressable per class.
 *
 * <p>Hotswap runs {@code LambdaAligner.align} once per class while the batch is being transformed,
 * then fires {@code CellPropertyRef.afterRedefineBatch} once after <b>all</b> classes were
 * redefined. The legacy {@code LambdaAligner.LAST_RESULT} is a single global slot, so by the time
 * the batch callback runs it only describes the <b>last</b> aligned class. Earlier classes in the
 * same batch therefore compare their own name against another class's snapshot, miss the
 * authoritative mapping and silently fall back to the 0.6 similarity guess.</p>
 *
 * <p>This test builds the exact shape of that sequence: align class A, align class B, then ask
 * {@code CellPropertyRef.matchMethods} for class A. The similarity fallback is made to score 0
 * (different property methods), so the only way A can be paired is A's own alignment snapshot.</p>
 */
public class CellLambdaBatchAlignTest {

	/** Compiles {@code perclass/HostA} and {@code perclass/HostB} in a v1 and a v2 variant. */
	private static Map<String, byte[]> compileHosts(Path tmp) throws Exception {
		String javac = HstestFixtures.javac(17);
		Map<String, byte[]> out = new HashMap<>();
		compileVariant(javac, tmp, out, "v1", new String[]{"a0", "a1"}, new String[]{"b0", "b1"});
		compileVariant(javac, tmp, out, "v2", new String[]{"a9", "a0", "a1"}, new String[]{"b9", "b0", "b1"});
		return out;
	}

	private static void compileVariant(String javac, Path tmp, Map<String, byte[]> out, String version,
	                                   String[] aLabels, String[] bLabels) throws Exception {
		Path srcDir = tmp.resolve("src-" + version).resolve("perclass");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("HostA.java"), hostSource("HostA", aLabels), StandardCharsets.UTF_8);
		Files.writeString(srcDir.resolve("HostB.java"), hostSource("HostB", bLabels), StandardCharsets.UTF_8);

		Path classes = tmp.resolve("classes-" + version);
		HstestFixtures.compile(javac, classes,
		 srcDir.resolve("HostA.java").toFile(), srcDir.resolve("HostB.java").toFile());

		out.put(version + "/HostA", Files.readAllBytes(classes.resolve("perclass").resolve("HostA.class")));
		out.put(version + "/HostB", Files.readAllBytes(classes.resolve("perclass").resolve("HostB.class")));
	}

	private static String hostSource(String cls, String[] labels) {
		StringBuilder sb = new StringBuilder("package perclass;\npublic class ").append(cls).append(" {\n");
		for (String label : labels) {
			sb.append("  public static final Runnable ").append(label.toUpperCase())
			  .append(" = () -> System.out.print(\"").append(label).append("\");\n");
		}
		return sb.append("}\n").toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> invokeMatchMethods(String slashName,
	                                                      Map<String, List<List<PropertyCall>>> oldM,
	                                                      Map<String, List<List<PropertyCall>>> newM) throws Exception {
		Method m = CellPropertyRef.class.getDeclaredMethod("matchMethods", String.class, Map.class, Map.class);
		m.setAccessible(true);
		return (Map<String, String>) m.invoke(null, slashName, oldM, newM);
	}

	private static PropertyCall call(String method, String arg) {
		return new PropertyCall("arc/scene/ui/layout/Cell", method,
		 "(Ljava/lang/String;)Larc/scene/ui/layout/Cell;", new Object[]{arg}, 12);
	}

	private static Map<String, List<List<PropertyCall>>> model(String key, PropertyCall call) {
		Map<String, List<List<PropertyCall>>> m = new HashMap<>();
		m.put(key, List.of(List.of(call)));
		return m;
	}

	@Test
	void earlierClassInBatchKeepsItsAuthoritativeMapping(@TempDir Path tmp) throws Exception {
		Map<String, byte[]> classes = compileHosts(tmp);

		LambdaAligner.align(classes.get("v1/HostA"), classes.get("v2/HostA"));
		LambdaAligner.AlignmentResult aResult = LambdaAligner.LAST_RESULT;
		assertNotNull(aResult, "baseline: align(A) must publish a snapshot");
		assertEquals("perclass/HostA", aResult.alignedClass);
		assertNotNull(aResult.methodMapping, "baseline: class A must yield a mapping");
		assertFalse(aResult.methodMapping.isEmpty(), "baseline: class A mapping must not be empty");

		// The second class of the same batch overwrites the single global slot.
		LambdaAligner.align(classes.get("v1/HostB"), classes.get("v2/HostB"));
		assertNotNull(LambdaAligner.LAST_RESULT);
		assertEquals("perclass/HostB", LambdaAligner.LAST_RESULT.alignedClass,
		 "baseline: the single global slot now describes class B");

		Map.Entry<String, String> pair = aResult.methodMapping.entrySet().iterator().next();
		Map<String, List<List<PropertyCall>>> oldM = model(pair.getKey(), call("text", "old"));
		Map<String, List<List<PropertyCall>>> newM = model(pair.getValue(), call("image", "new"));

		Map<String, String> mapped = invokeMatchMethods("perclass/HostA", oldM, newM);

		assertEquals(pair.getValue(), mapped.get(pair.getKey()),
		 "class A must use A's own alignment snapshot, not the global slot (which now holds B); "
		 + "methodMapping=" + aResult.methodMapping);
	}

	@Test
	void unknownClassFallsBackInsteadOfBorrowing(@TempDir Path tmp) throws Exception {
		Map<String, byte[]> classes = compileHosts(tmp);
		LambdaAligner.align(classes.get("v1/HostA"), classes.get("v2/HostA"));

		Map<String, List<List<PropertyCall>>> oldM = model("lambda$static$0:()V", call("text", "old"));
		Map<String, List<List<PropertyCall>>> newM = model("lambda$static$1:()V", call("image", "new"));

		Map<String, String> mapped = invokeMatchMethods("perclass/NotAligned", oldM, newM);
		assertTrue(mapped.isEmpty(), "a class without its own snapshot must not borrow another class's mapping");
	}

	@Test
	void perClassLookupSurvivesLaterAlignmentOfAnotherClass(@TempDir Path tmp) throws Exception {
		Map<String, byte[]> classes = compileHosts(tmp);

		LambdaAligner.align(classes.get("v1/HostA"), classes.get("v2/HostA"));
		LambdaAligner.AlignmentResult aResult = LambdaAligner.LAST_RESULT;
		assertNotNull(aResult);

		LambdaAligner.align(classes.get("v1/HostB"), classes.get("v2/HostB"));
		assertEquals("perclass/HostB", LambdaAligner.LAST_RESULT.alignedClass);

		LambdaAligner.AlignmentResult storedA = LambdaAligner.lastResultFor("perclass/HostA");
		assertNotNull(storedA, "class A's snapshot must still be addressable after B was aligned");
		assertEquals("perclass/HostA", storedA.alignedClass);
		assertEquals(aResult.methodMapping, storedA.methodMapping,
		 "class A's stored snapshot must be the exact immutable object A published, not a re-read of the global slot");

		assertNull(LambdaAligner.lastResultFor("perclass/NeverAligned"),
		 "a class that was never aligned must not resolve to any snapshot");
	}

	@Test
	void emptyResultOverwritesStalePerClassSnapshot(@TempDir Path tmp) throws Exception {
		Map<String, byte[]> classes = compileHosts(tmp);

		LambdaAligner.align(classes.get("v1/HostA"), classes.get("v2/HostA"));
		assertNotNull(LambdaAligner.lastResultFor("perclass/HostA").methodMapping);

		// Early-return path: no old bytes to align against. The class name is still known from the
		// new bytes, so the stale mapping must be replaced by "no conclusion this round".
		LambdaAligner.align(null, classes.get("v2/HostA"));

		LambdaAligner.AlignmentResult stale = LambdaAligner.lastResultFor("perclass/HostA");
		assertNotNull(stale, "an early return must still publish an empty snapshot for the class");
		assertNull(stale.methodMapping,
		 "a round without a conclusion must not leave the previous round's mapping addressable");
	}
}
