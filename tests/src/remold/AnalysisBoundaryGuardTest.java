package remold;

import org.junit.jupiter.api.Test;
import remold.util.Diag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architectural dependency-boundary guards locking in:
 * <ol>
 *   <li>All of {@code remold/util/} and all non-driver files under {@code remold/ref/} plus pure
 *       top-level analyzers never reference {@link RemoldAgent};</li>
 *   <li>No file under {@code core/src/} uses {@code import static remold.RemoldAgent.*;};</li>
 *   <li>{@code RemoldAgent.bytecodeCache} and {@code sourceBytecodeCache} are not accessed directly
 *       outside {@link RemoldAgent};</li>
 *   <li>{@link Diag} is completely silent by default when {@link RemoldAgent} is absent, and forwards
 *       dynamically to {@link RemoldAgent#logger} / flags once wired.</li>
 * </ol>
 */
class AnalysisBoundaryGuardTest {

	/** Runtime/driver classes under {@code core/src/remold/ref/} that legitimately interact with {@link RemoldAgent}. */
	private static final Set<String> REF_RUNTIME_EXEMPTIONS = Set.of(
		"core/src/remold/ref/InitFix.java",
		"core/src/remold/ref/InitFixRuntimeDriver.java",
		"core/src/remold/ref/HeapFieldWatch.java",
		"core/src/remold/ref/HierarchyTreeOracle.java",
		"core/src/remold/ref/InitializerDrift.java"
	);

	private static final List<String> PURE_TOP_LEVEL_FILES = List.of(
		"core/src/remold/LambdaBodyFingerprint.java",
		"core/src/remold/LambdaBytecodeScanner.java",
		"core/src/remold/LambdaCaptureBridge.java",
		"core/src/remold/LambdaOrphanEmitter.java",
		"core/src/remold/LayoutGate.java",
		"core/src/remold/LocalClassGuard.java",
		"core/src/remold/OrphanBodyRetention.java"
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
	void utilPackageAndNonDriverRefModulesNeverReferenceRemoldAgent() throws IOException {
		Path root = repoRoot();
		List<String> guardedFiles = new ArrayList<>(PURE_TOP_LEVEL_FILES);
		try (var utilStream = Files.walk(root.resolve("core/src/remold/util"))) {
			for (Path file : utilStream.filter(p -> p.toString().endsWith(".java")).toList()) {
				guardedFiles.add(root.relativize(file).toString().replace('\\', '/'));
			}
		}
		try (var refStream = Files.walk(root.resolve("core/src/remold/ref"))) {
			for (Path file : refStream.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = root.relativize(file).toString().replace('\\', '/');
				if (!REF_RUNTIME_EXEMPTIONS.contains(rel)) {
					guardedFiles.add(rel);
				}
			}
		}

		assertTrue(guardedFiles.contains("core/src/remold/util/Utils.java"), "Expected util directory scan");
		assertTrue(guardedFiles.contains("core/src/remold/ref/InitFixSafetyGate.java"), "Expected ref directory scan");

		List<String> violations = new ArrayList<>();
		for (String rel : guardedFiles) {
			Path file = root.resolve(rel);
			assertTrue(Files.isRegularFile(file), "Missing guarded file: " + rel);
			String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
			if (containsMatch(REMOLD_AGENT_ID, code)) {
				violations.add(rel);
			}
		}
		assertEquals(List.of(), violations,
			"remold/util/, non-driver remold/ref/, and pure top-level analyzers must not reference RemoldAgent");

		// Negative controls: coordinator and exempt driver files must trip the detector.
		String coordinatorCode = stripCommentsAndStrings(
			Files.readString(root.resolve("core/src/remold/RemoldAgentBootstrap.java"), StandardCharsets.UTF_8));
		String driverCode = stripCommentsAndStrings(
			Files.readString(root.resolve("core/src/remold/ref/InitFixRuntimeDriver.java"), StandardCharsets.UTF_8));
		assertTrue(containsMatch(REMOLD_AGENT_ID, coordinatorCode),
			"Negative control failed: scanner must detect RemoldAgent in coordinator sources");
		assertTrue(containsMatch(REMOLD_AGENT_ID, driverCode),
			"Negative control failed: scanner must detect RemoldAgent in exempt runtime driver");
	}

	@Test
	void coreSourcesDisallowWildcardStaticImportOfRemoldAgent() throws IOException {
		Path root = repoRoot();
		Path coreSrc = root.resolve("core/src");
		List<String> violations = new ArrayList<>();
		String forbidden = "import static remold.RemoldAgent.*;";
		try (var stream = Files.walk(coreSrc)) {
			for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = root.relativize(file).toString().replace('\\', '/');
				String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
				if (code.contains(forbidden)) {
					violations.add(rel);
				}
			}
		}
		assertEquals(List.of(), violations,
			"No file under core/src may use wildcard static import of RemoldAgent");

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
	void diagStandaloneDefaultSinkIsCompletelySilent() throws Exception {
		URL codeSource = Diag.class.getProtectionDomain().getCodeSource().getLocation();
		assertNotNull(codeSource, "Cannot locate Diag code source");

		PrintStream savedOut = System.out;
		PrintStream savedErr = System.err;
		ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
		ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
		try (URLClassLoader isolated = new URLClassLoader(new URL[]{codeSource}, ClassLoader.getPlatformClassLoader())) {
			Class<?> isolatedDiag = Class.forName("remold.util.Diag", true, isolated);
			Method isDebug = isolatedDiag.getMethod("isDebugEnabled");
			Method isPerf  = isolatedDiag.getMethod("isPerfEnabled");
			Method log     = isolatedDiag.getMethod("log", String.class);
			Method info    = isolatedDiag.getMethod("info", String.class);
			Method warn    = isolatedDiag.getMethod("warn", String.class);
			Method error   = isolatedDiag.getMethod("error", String.class, Throwable.class);

			assertFalse((Boolean) isDebug.invoke(null), "Standalone Diag default must report debug disabled");
			assertFalse((Boolean) isPerf.invoke(null), "Standalone Diag default must report perf disabled");

			System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
			System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
			log.invoke(null, "silent-log");
			info.invoke(null, "silent-info");
			warn.invoke(null, "silent-warn");
			error.invoke(null, "silent-error", new RuntimeException("boom"));
		} finally {
			System.setOut(savedOut);
			System.setErr(savedErr);
		}

		assertEquals("", capturedOut.toString(StandardCharsets.UTF_8),
			"Standalone Diag default sink must write nothing to stdout");
		assertEquals("", capturedErr.toString(StandardCharsets.UTF_8),
			"Standalone Diag default sink must write nothing to stderr");
	}

	@Test
	void diagForwardsDynamicallyToCurrentRemoldAgentLoggerAndFlags() {
		assertNotNull(RemoldAgent.logger, "Baseline: RemoldAgent.logger must be initialized");
		RemoldAgent.Logger savedLogger = RemoldAgent.logger;
		boolean savedDebug = RemoldAgent.DEBUG;
		boolean savedPerf  = RemoldAgent.PERF_LOG;
		List<String> captured = new ArrayList<>();
		try {
			RemoldAgent.DEBUG = true;
			RemoldAgent.PERF_LOG = true;
			assertTrue(Diag.isDebugEnabled(), "Diag.isDebugEnabled must reflect RemoldAgent.DEBUG");
			assertTrue(Diag.isPerfEnabled(), "Diag.isPerfEnabled must reflect RemoldAgent.PERF_LOG");

			RemoldAgent.DEBUG = false;
			RemoldAgent.PERF_LOG = false;
			assertFalse(Diag.isDebugEnabled(), "Diag.isDebugEnabled must turn false when RemoldAgent.DEBUG is false");
			assertFalse(Diag.isPerfEnabled(), "Diag.isPerfEnabled must turn false when RemoldAgent.PERF_LOG is false");

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
			RemoldAgent.logger = savedLogger;
			RemoldAgent.DEBUG = savedDebug;
			RemoldAgent.PERF_LOG = savedPerf;
		}

		// Control: once original logger is restored, subsequent Diag events must not enter captured list.
		int sizeAfterRestore = captured.size();
		Diag.info("diag-after-restore");
		assertEquals(sizeAfterRestore, captured.size(),
			"Restoring RemoldAgent.logger must immediately detach the test logger from Diag");
		assertFalse(captured.contains("info:diag-after-restore"));
	}
}
