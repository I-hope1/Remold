package remold;

import org.junit.jupiter.api.Test;
import remold.util.Diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural dependency-boundary guards locking in:
 * <ol>
 *   <li>Pure bytecode analysis and emitter modules never reference {@link RemoldAgent};</li>
 *   <li>Extracted {@link RemoldAgent} coordinator modules never use wildcard static imports;</li>
 *   <li>{@code RemoldAgent.bytecodeCache} and {@code sourceBytecodeCache} are not accessed directly
 *       outside {@link RemoldAgent};</li>
 *   <li>{@link Diag} dynamically forwards to the active {@link RemoldAgent#logger}.</li>
 * </ol>
 */
class AnalysisBoundaryGuardTest {

	private static final List<String> PURE_ANALYSIS_AND_EMITTER_FILES = List.of(
		"core/src/remold/util/Diag.java",
		"core/src/remold/LambdaBodyFingerprint.java",
		"core/src/remold/LambdaBytecodeScanner.java",
		"core/src/remold/LambdaCaptureBridge.java",
		"core/src/remold/LambdaOrphanEmitter.java",
		"core/src/remold/LayoutGate.java",
		"core/src/remold/LocalClassGuard.java",
		"core/src/remold/OrphanBodyRetention.java",
		"core/src/remold/ref/InitFixSafetyGate.java",
		"core/src/remold/ref/InitFixSliceExtractor.java",
		"core/src/remold/ref/InitFixPatchEmitter.java",
		"core/src/remold/ref/RemovedMemberReaders.java",
		"core/src/remold/ref/InitializerReplay.java",
		"core/src/remold/ref/ApplyInitializerReplay.java",
		"core/src/remold/ref/HashSemantics.java",
		"core/src/remold/ref/StageASkeleton.java",
		"core/src/remold/ref/ConstructorTailInjector.java"
	);

	private static final List<String> AGENT_COORDINATOR_FILES = List.of(
		"core/src/remold/VcsAgentCoordinator.java",
		"core/src/remold/TwoPhaseCoordinator.java",
		"core/src/remold/RemoldAgentBootstrap.java",
		"core/src/remold/AgentWatchCoordinator.java",
		"core/src/remold/BatchHotswapExecutor.java",
		"core/src/remold/BatchLayoutCoordinator.java",
		"core/src/remold/DynamicRedefineExecutor.java"
	);

	private static final Pattern BLOCK_COMMENTS  = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern LINE_COMMENTS   = Pattern.compile("//[^\\r\\n]*");
	private static final Pattern STRING_LITERALS = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
	private static final Pattern REMOLD_AGENT_ID = Pattern.compile("\\bRemoldAgent\\b");
	private static final Pattern CACHE_FIELD_ID  = Pattern.compile("\\b(?:bytecodeCache|sourceBytecodeCache)\\b");

	private static Path repoRoot() {
		Path dir = Paths.get("").toAbsolutePath();
		while (dir != null) {
			if (Files.isDirectory(dir.resolve("core/src")) && Files.isDirectory(dir.resolve("tests/src"))) {
				return dir;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("Cannot locate repo root from " + Paths.get("").toAbsolutePath());
	}

	private static String stripCommentsAndStrings(String source) {
		String s = BLOCK_COMMENTS.matcher(source).replaceAll("");
		s = LINE_COMMENTS.matcher(s).replaceAll("");
		return STRING_LITERALS.matcher(s).replaceAll("\"\"");
	}

	private static boolean containsMatch(Pattern pattern, String code) {
		Matcher m = pattern.matcher(code);
		return m.find();
	}

	@Test
	void pureAnalysisAndEmitterModulesHaveZeroRemoldAgentReferences() throws IOException {
		Path root = repoRoot();
		List<String> violations = new ArrayList<>();
		for (String rel : PURE_ANALYSIS_AND_EMITTER_FILES) {
			Path file = root.resolve(rel);
			assertTrue(Files.isRegularFile(file), "Missing guarded file: " + rel);
			String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
			if (containsMatch(REMOLD_AGENT_ID, code)) {
				violations.add(rel);
			}
		}
		assertEquals(List.of(), violations,
			"Pure analysis/emitter modules must not reference RemoldAgent (use Diag for logging)");

		// Negative control: the same detector must catch an actual RemoldAgent coordinator file.
		String coordinatorCode = stripCommentsAndStrings(
			Files.readString(root.resolve("core/src/remold/RemoldAgentBootstrap.java"), StandardCharsets.UTF_8));
		assertTrue(containsMatch(REMOLD_AGENT_ID, coordinatorCode),
			"Negative control failed: scanner must detect RemoldAgent references in coordinator sources");
	}

	@Test
	void coordinatorModulesDisallowWildcardStaticImportOfRemoldAgent() throws IOException {
		Path root = repoRoot();
		List<String> violations = new ArrayList<>();
		String forbidden = "import static remold.RemoldAgent.*;";
		for (String rel : AGENT_COORDINATOR_FILES) {
			Path file = root.resolve(rel);
			assertTrue(Files.isRegularFile(file), "Missing coordinator file: " + rel);
			String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
			if (code.contains(forbidden)) {
				violations.add(rel);
			}
		}
		assertEquals(List.of(), violations,
			"Coordinator modules must use explicit imports instead of wildcard static import");

		// Negative control: exact detector matches a wildcard import snippet.
		assertTrue(stripCommentsAndStrings("package p;\n" + forbidden + "\nclass C {}").contains(forbidden),
			"Negative control failed: wildcard import detector must match forbidden import");
	}

	@Test
	void runtimeAndSourceCachesAreNotAccessedDirectlyOutsideRemoldAgent() throws IOException {
		Path root = repoRoot();
		Path coreSrc = root.resolve("core/src");
		List<String> violations = new ArrayList<>();
		try (var stream = Files.walk(coreSrc)) {
			for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = root.relativize(file).toString().replace('\\', '/');
				if ("core/src/remold/RemoldAgent.java".equals(rel)) continue;
				String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
				if (containsMatch(CACHE_FIELD_ID, code)) {
					violations.add(rel);
				}
			}
		}
		assertEquals(List.of(), violations,
			"Only RemoldAgent may directly read or mutate bytecodeCache/sourceBytecodeCache");

		// Negative control: RemoldAgent.java itself holds and accesses those fields.
		String agentCode = stripCommentsAndStrings(
			Files.readString(root.resolve("core/src/remold/RemoldAgent.java"), StandardCharsets.UTF_8));
		assertTrue(containsMatch(CACHE_FIELD_ID, agentCode),
			"Negative control failed: cache detector must trigger on RemoldAgent.java");
	}

	@Test
	void diagForwardsDynamicallyToCurrentRemoldAgentLogger() {
		assertNotNull(RemoldAgent.logger, "Baseline: RemoldAgent.logger must be initialized");
		RemoldAgent.Logger saved = RemoldAgent.logger;
		List<String> captured = new ArrayList<>();
		try {
			RemoldAgent.logger = new RemoldAgent.Logger() {
				@Override public void log(String msg) { captured.add("log:" + msg); }
				@Override public void info(String msg) { captured.add("info:" + msg); }
				@Override public void warn(String msg) { captured.add("warn:" + msg); }
				@Override public void error(String msg) { captured.add("error:" + msg); }
				@Override public void error(String msg, Throwable t) { captured.add("errorT:" + msg); }
			};

			Diag.info("diag-info-probe");
			Diag.warn("diag-warn-probe");
			Diag.log("diag-log-probe");
			Diag.error("diag-error-probe");

			assertEquals(
				List.of(
					"info:diag-info-probe",
					"warn:diag-warn-probe",
					"log:diag-log-probe",
					"error:diag-error-probe"
				),
				captured,
				"Diag must dynamically dispatch to the active RemoldAgent.logger"
			);
		} finally {
			RemoldAgent.logger = saved;
		}

		// Control: once original logger is restored, subsequent Diag events must not enter captured list.
		int sizeAfterRestore = captured.size();
		Diag.info("diag-after-restore");
		assertEquals(sizeAfterRestore, captured.size(),
			"Restoring RemoldAgent.logger must immediately detach the test logger from Diag");
		assertFalse(captured.contains("info:diag-after-restore"));
	}
}
