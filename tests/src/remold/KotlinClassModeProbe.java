package remold;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import remold.util.LibTool;

/**
 * End-to-end reproduction probe for the Kotlin hot-swap defects tracked by campaign
 * {@code battle-20261122151645-4c73}.
 *
 * <p><b>What it drives</b>: real {@code kotlinc} output (SAM conversions emitted as class files via
 * {@code -Xsam-conversions=class}, plus host-suffix anonymous objects), loaded from a scratch
 * directory that doubles as the classpath entry, then fed to the production batch body
 * {@code RemoldAgent.processChanges} — the method the file watcher calls — with a real
 * {@code Instrumentation} doing the redefinition. This probe never modifies core code.</p>
 *
 * <p><b>Invariants asserted</b> (see {@code campaign.json}):
 * <ul>
 *   <li><b>K1</b> — when Kotlin host-suffix closures/anonymous classes cannot be paired uniquely,
 *       the round must end as "whole host group out of this batch + warn": no runtime exception may
 *       be thrown (in particular {@code NullPointerException}), nothing unaligned or empty-named may
 *       be registered in a cache, and a later normal call must not fail with
 *       {@code NoClassDefFoundError}. A round may be entirely v1 (fail-closed) or entirely v2
 *       (aligned); a mixed v1/v2 end state is a partial application and a violation.</li>
 *   <li><b>K3</b> — a surviving anonymous instance must never answer another slot's semantics,
 *       including when v2 prepends one anonymous object and shifts the {@code $method$N} suffixes.</li>
 *   <li><b>K5</b> — the two controls stay green: an empty batch changes nothing, and the javac
 *       control group (real host names, structural evidence) really applies v2. Without them a
 *       "everything is skipped" reading would look like a pass.</li>
 * </ul>
 *
 * <p><b>Violation codes</b> ({@code KCM-VIOLATION=<code>}) for the fix batches:
 * {@code K1-crash} (exception thrown, or a runtime exception logged by the round), {@code K1-host}
 * (alignment/rejection names a host class that does not exist in the fixture set), {@code K1-partial},
 * {@code K1-cache}, {@code K1-ncdfe}, {@code K3-swap}, {@code K5-control}, {@code K5-no-trigger}.</p>
 *
 * <p>Run via {@code ./gradlew :tests:kotlinClassModeProbe
 * [-Dremold.kcm.scenario=class|classv3|prepend|control|all]}.</p>
 */
public class KotlinClassModeProbe {

	private static final Pattern MENTIONED_HOST = Pattern.compile(
		"(?:alignment failed in|ambiguity detected in|not aligned under host) ([A-Za-z0-9_$./]+)"
			+ "|(?:\\[ANON_ALIGN\\] Host) ([A-Za-z0-9_$./]+):");

	/** class scenario: SAM-off closures, two topology-equal candidates, labels-only v2 edit. */
	private static final ScenarioSpec CLASS_MODE = new ScenarioSpec(
		"class", "kcm.classmode.ClosureHost", new String[]{"slots"},
		"t1:v1-header:v1-body", "t2:v2-header:v2-body",
		"v1-header", "v2-header", "v1-body", "v2-body",
		"v1-nested-outer:v1-nested-inner", "v2-nested-outer:v2-nested-inner");

	/**
	 * classv3 scenario: same SAM-off host-suffix shape as {@code class}, but the new build grows the
	 * class SET by one closure. This is the shape where "host redefined, closures left behind" cannot
	 * even link: the fresh factory call references a closure class name the old build never defined,
	 * so the end state shows {@code NoClassDefFoundError} (violation {@code K1-ncdfe}).
	 */
	private static final ScenarioSpec CLASS_V3 = new ScenarioSpec(
		"classv3", "kcm.classmode.ClosureHost", new String[]{"slots"},
		"t1:v1-header:v1-body", "t3:v3-header:v3-body:v3-extra",
		"v1-header", "v3-header", "v1-body", "v3-body",
		"v1-nested-outer:v1-nested-inner", "v3-nested-outer:v3-nested-inner");

	/** prepend scenario: v2 prepends one anonymous object, shifting $1/$2 to $2/$3. */
	private static final ScenarioSpec PREPEND = new ScenarioSpec(
		"prepend", "kcm.prepend.SlotHost", new String[]{"slots"},
		"t1:v1-alpha:v1-beta", "t2:v2-header:v2-alpha:v2-beta",
		"v1-alpha", "v2-alpha", "v1-beta", "v2-beta",
		"v1-nested-outer:v1-nested-inner", "v2-nested-outer:v2-nested-inner");

	/** control: javac anonymous classes, real host names, unique structural evidence. */
	private static final ScenarioSpec CONTROL = new ScenarioSpec(
		"control", "kcm.control.JavaSlotHost", new String[]{"alpha", "beta"},
		"t1:j1-alpha|j1-beta", "t2:j2-alpha|j2-beta",
		"j1-alpha", "j2-alpha", "j1-beta", "j2-beta",
		null, null);

	private static final List<String> LOG_LINES = new ArrayList<>();
	private static final Map<String, Integer> VIOLATIONS = new java.util.LinkedHashMap<>();
	private static int scenariosRun;
	private static int scenariosFailed;
	private static int harnessErrors;

	public static void main(String[] args) throws Throwable {
		System.out.println("=== KotlinClassModeProbe: Kotlin class mode host-suffix alignment ===");
		Instrumentation instrumentation = RemoldAgent.getInstrumentation();
		if (instrumentation == null) {
			System.out.println("KCM-HARNESS-ERROR: instrumentation unavailable; run via ./gradlew :tests:kotlinClassModeProbe");
			System.exit(2);
		}
		RemoldAgent.INIT_FIX = true;
		RemoldAgent.ANON_ALIGN = true;
		RemoldAgent.LAMBDA_ALIGN = true;
		installLogCapture();
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.out.println("KCM-HARNESS-WARN: libtool unavailable (" + oneLine(t) + "); liveness probes fail conservatively");
		}

		Path work = Path.of(System.getProperty("remold.kcm.dir", "build/kotlinClassMode/live")).toAbsolutePath();
		String requested = System.getProperty("remold.kcm.scenario", "all").trim().toLowerCase(Locale.ROOT);
		System.out.println("KCM-CONFIG scenario=" + requested + " workDir=" + work);

		List<ScenarioSpec> order = List.of(CLASS_MODE, CLASS_V3, PREPEND, CONTROL);
		Set<String> wanted = "all".equals(requested)
			? new LinkedHashSet<>(List.of("class", "classv3", "prepend", "control"))
			: new LinkedHashSet<>(java.util.Arrays.asList(requested.split(",")));

		for (ScenarioSpec spec : order) {
			if (!wanted.contains(spec.tag())) continue;
			scenariosRun++;
			boolean ok;
			try {
				ok = runScenario(instrumentation, work, spec);
			} catch (Throwable t) {
				System.out.println("KCM-HARNESS-ERROR scenario=" + spec.tag() + " " + oneLine(t));
				harnessErrors++;
				ok = false;
			}
			int violations = violationsOf(spec.tag());
			System.out.println("KCM-SUMMARY scenario=" + spec.tag()
				+ " result=" + (ok && violations == 0 ? "PASS" : "FAIL") + " violations=" + violations);
			if (!ok || violations > 0) scenariosFailed++;
		}

		int totalViolations = VIOLATIONS.values().stream().mapToInt(Integer::intValue).sum();
		System.out.println("KCM-TOTAL scenarios=" + scenariosRun + " failed=" + scenariosFailed
			+ " violations=" + totalViolations + " harnessErrors=" + harnessErrors
			+ " violationsByCode=" + VIOLATIONS);
		if (harnessErrors > 0) System.exit(2);
		System.exit(scenariosFailed > 0 ? 1 : 0);
	}

	// ------------------------------------------------------------------ scenario driver

	/**
	 * One hot-swap round on one fixture pair: precondition on v1, no-trigger control, real round
	 * (scratch dir overwritten with v2 + production batch body), then the end-state reads.
	 */
	private static boolean runScenario(Instrumentation instrumentation, Path work, ScenarioSpec spec) throws Exception {
		String tag = spec.tag();
		Map<String, byte[]> v1 = KotlinClassModeFixtures.readClasses(
			KotlinClassModeFixtures.requiredDirs("remold.kcm." + tag + ".v1"));
		Map<String, byte[]> v2 = KotlinClassModeFixtures.readClasses(
			KotlinClassModeFixtures.requiredDirs("remold.kcm." + tag + ".v2"));
		Set<String> fixtureClasses = KotlinClassModeFixtures.classNames(v1, v2);

		FixtureHost fixture = FixtureHost.boot(tag, work, v1, spec.hostClass());
		String before = fixture.call("metricTable");
		List<Supplier<?>> held = fixture.instancesOfField(spec.instanceFields());
		String alphaBefore = labelOf(held, 0);
		String betaBefore = labelOf(held, 1);
		boolean ok = expect(tag, "precondition",
			spec.v1State().equals(before) && spec.v1Alpha().equals(alphaBefore) && spec.v1Beta().equals(betaBefore),
			"host and its anonymous instances run v1 before the round",
			"observed=" + before + " alpha=" + alphaBefore + " beta=" + betaBefore);
		if (spec.v1Nested() != null) {
			String nestedBefore = fixture.call("nestedTable");
			ok &= expect(tag, "precondition-nested", spec.v1Nested().equals(nestedBefore),
				"nested anonymous classes run v1 before the round", "observed=" + nestedBefore);
		}

		ok &= noTriggerControl(tag, instrumentation, fixture, spec.v1State());

		List<Path> changed = KotlinClassModeFixtures.copyClasses(
			KotlinClassModeFixtures.requiredDirs("remold.kcm." + tag + ".v2"), fixture.liveDir);
		Set<String> cacheBefore = cacheNames();
		RoundResult round = fixture.round(instrumentation, changed);
		reportRound(tag, round);

		if (round.escaped() != null) violation(tag, "K1-crash", oneLine(round.escaped()));
		for (String line : round.logLines()) {
			if (line.contains("NullPointerException")) {
				violation(tag, "K1-crash", "runtime exception logged by the round: " + truncate(line, 180));
			}
		}
		expectNoGhostHost(tag, round.logLines(), fixtureClasses);
		expectNoSuspiciousCacheNames(tag, cacheBefore);

		// Read the surviving instances *before* re-running any factory method: a factory call would
		// hide a silent replacement of the objects the old host code already holds.
		String alphaAfter = labelOf(held, 0);
		String betaAfter = labelOf(held, 1);
		boolean alphaIdentity = spec.v1Alpha().equals(alphaAfter) || spec.v2Alpha().equals(alphaAfter);
		boolean betaIdentity = spec.v1Beta().equals(betaAfter) || spec.v2Beta().equals(betaAfter);
		ok &= expect(tag, "K3-identity", alphaIdentity && betaIdentity,
			"surviving anonymous instances keep their own slot semantics",
			"alphaAfter=" + alphaAfter + " betaAfter=" + betaAfter);
		if (!alphaIdentity || !betaIdentity) {
			violation(tag, "K3-swap", "surviving instances: alpha=" + alphaAfter + " beta=" + betaAfter);
		}

		String after = null;
		try {
			after = fixture.call("metricTable");
			if (spec.v1Nested() != null) fixture.call("nestedTable");
		} catch (Throwable t) {
			classifyPostRoundCall(tag, t);
		}
		System.out.println("KCM-STATE scenario=" + tag + " before=" + before + " alphaBefore=" + alphaBefore
			+ " betaBefore=" + betaBefore + " after=" + after + " alphaAfter=" + alphaAfter
			+ " betaAfter=" + betaAfter);

		if (after != null) {
			boolean v1State = spec.v1State().equals(after)
				&& spec.v1Alpha().equals(alphaAfter) && spec.v1Beta().equals(betaAfter);
			boolean v2State = spec.v2State().equals(after)
				&& spec.v2Alpha().equals(alphaAfter) && spec.v2Beta().equals(betaAfter);
			boolean coherent = v1State || v2State;
			ok &= expect(tag, "coherent-state", coherent,
				"end state is entirely v1 (fail-closed) or entirely v2 (aligned)",
				"after=" + after + " alphaAfter=" + alphaAfter + " betaAfter=" + betaAfter);
			if (!coherent) {
				violation(tag, "K1-partial",
					"mixed end state: after=" + after + " alpha=" + alphaAfter + " beta=" + betaAfter);
			}
			if (CONTROL.tag().equals(tag)) {
				boolean applied = v2State;
				ok &= expect(tag, "K5-control", applied,
					"control group really applied v2 (proves the driver is not vacuous)",
					"after=" + after + " alphaAfter=" + alphaAfter + " betaAfter=" + betaAfter);
				if (!applied) {
					violation(tag, "K5-control", "control did not reach v2; K1/K3 readings would be meaningless");
				}
			}
		}
		return ok;
	}

	/** 去触发 control: an empty batch must neither throw nor change the live state. */
	private static boolean noTriggerControl(String tag, Instrumentation instrumentation,
	                                        FixtureHost fixture, String expectedState) {
		RoundResult noop = fixture.round(instrumentation, List.of());
		String state;
		try {
			state = fixture.call("metricTable");
		} catch (Throwable t) {
			violation(tag, "K5-no-trigger", "call after empty batch failed: " + oneLine(t));
			return false;
		}
		boolean ok = expect(tag, "K5-no-trigger",
			noop.escaped() == null && expectedState.equals(state),
			"an empty batch neither throws nor changes the live state",
			"escaped=" + oneLine(noop.escaped()) + " observed=" + state);
		if (noop.escaped() != null) {
			violation(tag, "K5-no-trigger", "empty batch threw " + oneLine(noop.escaped()));
		} else if (!expectedState.equals(state)) {
			violation(tag, "K5-no-trigger", "empty batch changed the live state to " + state);
		}
		return ok;
	}

	// ------------------------------------------------------------------ round driving

	/** Scenario shape: host class, instance fields, and the coherent v1/v2 end states. */
	record ScenarioSpec(String tag, String hostClass, String[] instanceFields,
	                    String v1State, String v2State, String v1Alpha, String v2Alpha,
	                    String v1Beta, String v2Beta, String v1Nested, String v2Nested) {
	}

	/** Round outcome: escaped throwable (null when the batch body returned normally), logs, timing. */
	record RoundResult(Throwable escaped, int files, long elapsedMs, List<String> logLines) {
	}

	/**
	 * Production batch body. Invoked reflectively: the agent classes live in the bootstrap loader
	 * (the agent jar is appended to the boot class path), so a package-private call from this probe's
	 * loader fails with {@code IllegalAccessError} even though the package names match.
	 */
	private static final Method PROCESS_CHANGES = resolveProcessChanges();

	private static Method resolveProcessChanges() {
		try {
			Method method = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot resolve RemoldAgent.processChanges", e);
		}
	}

	private static RoundResult runRound(Instrumentation instrumentation, Collection<Path> changedFiles) {
		LOG_LINES.clear();
		long start = System.nanoTime();
		Throwable escaped = null;
		try {
			PROCESS_CHANGES.invoke(null, new LinkedHashSet<>(changedFiles), instrumentation.getAllLoadedClasses());
		} catch (Throwable t) {
			escaped = unwrap(t);
		}
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
		return new RoundResult(escaped, changedFiles.size(), elapsedMs, List.copyOf(LOG_LINES));
	}

	// ------------------------------------------------------------------ fixture host

	/** Host class loaded from a scratch class-output directory, holding the live instance. */
	private static final class FixtureHost {

		final Path liveDir;
		private final Class<?> hostType;
		private final Object host;

		private FixtureHost(Path liveDir, Class<?> hostType, Object host) {
			this.liveDir = liveDir;
			this.hostType = hostType;
			this.host = host;
		}

		static FixtureHost boot(String tag, Path work, Map<String, byte[]> v1, String hostClassName) throws Exception {
			Path liveDir = work.resolve(tag);
			KotlinClassModeFixtures.deleteTree(liveDir);
			KotlinClassModeFixtures.copyClasses(
				KotlinClassModeFixtures.requiredDirs("remold.kcm." + tag + ".v1"), liveDir);
			URLClassLoader loader = new URLClassLoader(
				new URL[]{liveDir.toUri().toURL()}, KotlinClassModeProbe.class.getClassLoader());
			Class<?> hostType = Class.forName(hostClassName, true, loader);
			Object host = hostType.getConstructor().newInstance();
			// Seed the runtime baseline exactly like a first load would.
			for (Map.Entry<String, byte[]> entry : v1.entrySet()) {
				RemoldAgent.recordRuntimeBytecode(entry.getKey(), entry.getValue());
			}
			return new FixtureHost(liveDir, hostType, host);
		}

		String call(String methodName) {
			try {
				Method method = hostType.getMethod(methodName);
				return String.valueOf(method.invoke(host));
			} catch (Throwable t) {
				Throwable cause = unwrap(t);
				if (cause instanceof RuntimeException runtime) throw runtime;
				if (cause instanceof Error error) throw error;
				throw new IllegalStateException(cause);
			}
		}

		/** Live anonymous instances reachable through public fields (read *before* the round). */
		List<Supplier<?>> instancesOfField(String... fieldNames) throws Exception {
			List<Supplier<?>> out = new ArrayList<>();
			for (String fieldName : fieldNames) {
				Field field = hostType.getField(fieldName);
				Object value = field.get(host);
				if (value instanceof List<?> list) {
					for (Object element : list) out.add(asSupplier(element));
				} else {
					out.add(asSupplier(value));
				}
			}
			return out;
		}

		RoundResult round(Instrumentation instrumentation, Collection<Path> changedFiles) {
			return runRound(instrumentation, changedFiles);
		}
	}

	private static Supplier<?> asSupplier(Object candidate) {
		if (!(candidate instanceof Supplier<?> supplier)) {
			throw new IllegalStateException("fixture instance is not a Supplier: " + candidate);
		}
		return supplier;
	}

	private static String labelOf(List<Supplier<?>> instances, int index) {
		if (index >= instances.size()) return "<missing#" + index + ">";
		try {
			return String.valueOf(instances.get(index).get());
		} catch (Throwable t) {
			return "<threw " + oneLine(unwrap(t)) + ">";
		}
	}

	// ------------------------------------------------------------------ checks and reporting

	private static void reportRound(String tag, RoundResult round) {
		System.out.println("KCM-ROUND scenario=" + tag + " files=" + round.files()
			+ " escaped=" + (round.escaped() == null ? "none" : oneLine(round.escaped()))
			+ " elapsedMs=" + round.elapsedMs());
		for (String line : round.logLines()) {
			System.out.println("KCM-LOG scenario=" + tag + " " + truncate(line, 200));
		}
	}

	private static void expectNoGhostHost(String tag, List<String> logLines, Set<String> fixtureClasses) {
		for (String line : logLines) {
			Matcher matcher = MENTIONED_HOST.matcher(line);
			while (matcher.find()) {
				String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
				String host = normalizeHostName(raw);
				if (fixtureClasses.contains(host)) continue;
				violation(tag, "K1-host", "alignment/rejection names nonexistent host '" + host
					+ "' (fixture has " + fixtureClasses.size() + " classes)");
			}
		}
	}

	private static String normalizeHostName(String raw) {
		String host = raw.replace('/', '.');
		while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		return host;
	}

	private static void expectNoSuspiciousCacheNames(String tag, Set<String> cacheBefore) {
		for (String name : cacheNames()) {
			if (cacheBefore.contains(name)) continue;
			if (KotlinClassModeFixtures.suspiciousClassName(name)) {
				violation(tag, "K1-cache", "suspicious class name registered during the round: '" + name + "'");
			}
		}
	}

	private static Set<String> cacheNames() {
		return new LinkedHashSet<>(RemoldAgent.bytecodeCache.keySet());
	}

	private static void classifyPostRoundCall(String tag, Throwable t) {
		Throwable cause = unwrap(t);
		if (cause instanceof LinkageError) {
			violation(tag, "K1-ncdfe", "post-round call failed with " + oneLine(cause));
			return;
		}
		violation(tag, "K1-crash", "post-round call failed with " + oneLine(cause));
	}

	private static boolean expect(String tag, String what, boolean condition, String expectation, String detail) {
		if (condition) {
			System.out.println("KCM-PASS " + tag + " " + what + ": " + expectation);
		} else {
			System.out.println("KCM-FAIL " + tag + " " + what + ": " + expectation + " | " + detail);
		}
		return condition;
	}

	private static void violation(String tag, String code, String detail) {
		VIOLATIONS.merge(tag + "/" + code, 1, Integer::sum);
		System.out.println("KCM-VIOLATION=" + code + " scenario=" + tag + " " + truncate(detail, 240));
	}

	private static int violationsOf(String tag) {
		int total = 0;
		for (Map.Entry<String, Integer> entry : VIOLATIONS.entrySet()) {
			if (entry.getKey().startsWith(tag + "/")) total += entry.getValue();
		}
		return total;
	}

	private static void installLogCapture() {
		RemoldAgent.Logger base = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override
			public void log(String msg) {
			}

			@Override
			public void info(String msg) {
			}

			@Override
			public void warn(String msg) {
				LOG_LINES.add("[warn] " + msg);
				base.warn(msg);
			}

			@Override
			public void error(String msg) {
				LOG_LINES.add("[error] " + msg);
				base.error(msg);
			}

			@Override
			public void error(String msg, Throwable t) {
				LOG_LINES.add("[error] " + msg + " | " + oneLine(t));
				base.error(msg, t);
			}
		};
	}

	private static Throwable unwrap(Throwable t) {
		Throwable current = t;
		while ((current instanceof InvocationTargetException || current instanceof ExceptionInInitializerError)
			&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static String oneLine(Throwable t) {
		if (t == null) return "none";
		return t.getClass().getName() + ": " + String.valueOf(t.getMessage()).replace('\n', ' ');
	}

	private static String truncate(String value, int max) {
		if (value == null) return "null";
		String flat = value.replace('\n', ' ').replace('\r', ' ');
		return flat.length() <= max ? flat : flat.substring(0, max) + "...";
	}
}
