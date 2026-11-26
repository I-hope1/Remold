
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import remold.RemoldAgent;
import remold.util.LibTool;


/**
 * Real-machine probe for the Kotlin anonymous-class extreme sequence (campaign
 * {@code battle-20261126020100-9f9c}, batch F3).
 *
 * <p><b>Why this probe exists</b>: kotlinc splices the property / method / local-variable name into
 * the anonymous class name ({@code KtAnonTarget$propAnonA$1}, {@code ...$makeUnnamedAnons$1..$3},
 * {@code ...$makeLocalNamedAnons$first$1}, {@code ...$outerAnon$1$innerObj$1}). Stripping the
 * trailing digits — the javac rule — yields a host that never exists, and grouping / consistency
 * checks / Stage A target validation then act on that fake host. The authoritative outer class is
 * only recorded in the {@code EnclosingMethod} attribute.</p>
 *
 * <p><b>Fixtures</b>: real kotlinc 2.3.0 output (invariant C2). {@code tests/ktanon/v1..v4} are
 * compiled at probe runtime by the Kotlin compiler found in the Gradle cache; no ASM stand-in is
 * used anywhere. The probe asserts the compiled products really carry the kotlinc shape
 * ({@code EnclosingMethod} on every anonymous class, {@code kotlin.Metadata} on the host, the
 * expected name family), so a future change to the fixture pipeline cannot silently degrade to a
 * synthetic substitute.</p>
 *
 * <p><b>Round sequence</b> (each round is a full production batch through
 * {@code RemoldAgent.processChanges} with a real {@code Instrumentation} on JBR):
 * <ol>
 *   <li><b>R1</b> — the three unnamed anonymous objects passed to {@code listOf} are reordered and
 *       their bodies change; surviving instances must never answer another slot's label.</li>
 *   <li><b>R2</b> — the named anonymous objects {@code propAnonA} / {@code anonStruct} gain a
 *       field; the surviving {@code propAnonA} instance must read the new initializer (InitFix
 *       two-phase), i.e. {@code addedFlagValue() == 42}.</li>
 *   <li><b>R3</b> — local variable rename + nested renumbering + capture growth; no
 *       {@code NoClassDefFoundError} / {@code NullPointerException}, previously created instances
 *       stay usable and the renamed/nested new classes are loadable.</li>
 * </ol>
 *
 * <p>Every assertion lands on business-observable end state (the label values the surviving
 * instances actually return, and the patched field value read back through a method) — never on
 * logs, counters or "the call returned".</p>
 *
 * <p>Run via {@code ./gradlew :tests:kotlinAnonSequenceProbe -Pp05.jbr=/opt/idea/jbr}.</p>
 */
public class KotlinAnonSequenceProbe implements Opcodes {

	// ------------------------------------------------------------------ fixture expectations

	private static final String PKG = "ktanon";
	private static final String HOST = PKG + ".KtAnonTarget";
	private static final String MARKER = PKG + ".Marker";

	/** Business surface, slot by slot: what each surviving/new member reads back. */
	private static Map<String, String> v1() {
		return surface("propA:v1", "fnObj:v1", "outer:v1>inner:v1", "structA:v1",
			"u1:v1", "u2:v1", "u3:v1", "localFirst:v1");
	}

	/** R1 target: unnamed trio reordered/re-labelled; `propAnonA` proves the round took effect. */
	private static Map<String, String> r1() {
		return surface("propA:r1", "fnObj:v1", "outer:v1>inner:v1", "structA:v1",
			"u3:v2", "u1:v2", "u2:v2", "localFirst:v1");
	}

	/** R2 target: the named anonymous objects gained a field (InitFix) and new bodies. */
	private static Map<String, String> r2() {
		return surface("propA:v3", "fnObj:v1", "outer:v1>inner:v1", "structA:v3:77",
			"u3:v2", "u1:v2", "u2:v2", "localFirst:v1");
	}

	/** R3 target: renamed local object, renumbered nested objects, capture growth. */
	private static Map<String, String> r3() {
		return surface("propA:v3", "fnObj:v1", "outer:v4>extra:v4|inner:v4", "structA:v3:77",
			"u3:v2", "u1:v2", "u2:v4:cap:v4", "localFirst:v4");
	}

	/** Slots that must reach the round's target version (the named anonymous objects). */
	private static final List<String> NAMED_SLOTS =
		List.of("propAnonA", "fnObjAnon", "outerAnon", "anonStruct", "localFirst");

	/** The unnamed trio: only identity is asserted (their positions may legitimately be re-routed). */
	private static final List<String> UNNAMED_SLOTS = List.of("unnamed1", "unnamed2", "unnamed3");

	private static Map<String, String> surface(String propAnonA, String fnObjAnon, String outerAnon, String structA,
	                                           String unnamed1, String unnamed2, String unnamed3, String localFirst) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put("propAnonA", propAnonA);
		out.put("fnObjAnon", fnObjAnon);
		out.put("outerAnon", outerAnon);
		out.put("anonStruct", structA);
		out.put("unnamed1", unnamed1);
		out.put("unnamed2", unnamed2);
		out.put("unnamed3", unnamed3);
		out.put("localFirst", localFirst);
		return out;
	}

	/** kotlinc 2.3.0 name family of the baseline fixture (C2: names only kotlinc produces). */
	private static final List<String> EXPECTED_V1_CLASSES = List.of(
		"HOST", "MARKER",
		"HOST$propAnonA$1", "HOST$fnObjAnon$1", "HOST$outerAnon$1", "HOST$outerAnon$1$innerObj$1",
		"HOST$anonStruct$1",
		"HOST$makeUnnamedAnons$1", "HOST$makeUnnamedAnons$2", "HOST$makeUnnamedAnons$3",
		"HOST$makeLocalNamedAnons$first$1");

	/** Compiler jars the offline kotlinc needs (names only; resolved from the Gradle cache). */
	private static final List<String> COMPILER_JARS = List.of(
		"kotlin-compiler-embeddable-2.3.0.jar",
		"kotlin-stdlib-2.3.0.jar",
		"kotlin-reflect-1.6.10.jar",
		"kotlinx-coroutines-core-jvm-1.8.0.jar",
		"kotlin-daemon-embeddable-2.3.0.jar",
		"annotations-26.0.2.jar");

	// ------------------------------------------------------------------ state

	private static int passed;
	private static final List<String> VIOLATIONS = new ArrayList<>();
	private static final List<String> LOGS = new ArrayList<>();
	private static Path srcRoot;
	private static Path buildRoot;
	private static Path liveDir;
	private static Instrumentation instrumentation;

	public static void main(String[] args) throws Throwable {
		System.out.println("=== KotlinAnonSequenceProbe (kotlinc 2.3.0 fixtures, real batch) ===");
		String srcProp = System.getProperty("remold.ktanon.src");
		String dirProp = System.getProperty("remold.ktanon.dir");
		if (srcProp == null || dirProp == null) {
			System.out.println("KTANON-HARNESS-ERROR: -Dremold.ktanon.src / -Dremold.ktanon.dir are required "
				+ "(run via ./gradlew :tests:kotlinAnonSequenceProbe)");
			System.exit(2);
		}
		srcRoot = Path.of(srcProp).toAbsolutePath().normalize();
		buildRoot = Path.of(dirProp).toAbsolutePath().normalize();
		liveDir = buildRoot.resolve("live");

		instrumentation = RemoldAgent.getInstrumentation();
		if (instrumentation == null) {
			System.out.println("KTANON-HARNESS-ERROR: no Instrumentation; run with -javaagent remold-agent.jar");
			System.exit(2);
		}
		RemoldAgent.logger = new CaptureLogger();
		try {
			if (!LibTool.initialized()) LibTool.init();
		} catch (Throwable t) {
			System.out.println("KTANON-HARNESS-WARN: libtool unavailable (" + t + ")");
		}
		if (!RemoldAgent.isEnhancedHotswapEnabled()) {
			System.out.println("KTANON-HARNESS-ERROR: enhanced class redefinition is off; pass -Pp05.jbr=/opt/idea/jbr");
			System.exit(2);
		}

		Map<String, Path> versions = new LinkedHashMap<>();
		try {
			for (String version : List.of("v1", "v2", "v3", "v4")) {
				versions.put(version, compile(version));
			}
		} catch (Throwable t) {
			System.out.println("KTANON-HARNESS-ERROR: kotlinc failed: " + t);
			System.exit(2);
		}
		checkKotlincShape(versions.get("v1"));
		selfCheck();

		try {
			runSequence(versions);
		} catch (Throwable t) {
			violation("HARNESS", "probe aborted: " + t);
			t.printStackTrace();
		}
		System.out.println("KTANON-SUMMARY rounds=R0..R3 violations=" + VIOLATIONS.size()
			+ " result=" + (VIOLATIONS.isEmpty() ? "PASS" : "FAIL"));
		for (String v : VIOLATIONS) System.out.println("KTANON-VIOLATION " + v);
		System.exit(VIOLATIONS.isEmpty() ? 0 : 1);
	}

	// ------------------------------------------------------------------ sequence

	private static void runSequence(Map<String, Path> versions) throws Exception {
		sync(versions.get("v1"));
		Class<?> host = Class.forName(HOST, true, KotlinAnonSequenceProbe.class.getClassLoader());

		Object target = host.getConstructor().newInstance();
		Object propAnonA = readProperty(host, target, "propAnonA");
		Object fnObjAnon = readProperty(host, target, "fnObjAnon");
		Object outerAnon = readProperty(host, target, "outerAnon");
		Object localFirst = host.getMethod("makeLocalNamedAnons").invoke(target);
		List<Object> unnamed = asList(host.getMethod("makeUnnamedAnons").invoke(target));

		Map<String, String> baseline = readSurface(host, target, propAnonA, fnObjAnon, outerAnon, unnamed, localFirst);
		System.out.println("KTANON-BASELINE " + describe(baseline));
		if (!baseline.equals(v1())) {
			violation("C1-baseline", "v1 surface " + describe(baseline) + " != " + describe(v1()));
			return;
		}

		// R0 control (no trigger): re-run the batch with byte-identical v1 classes. The detectors must
		// stay silent — a probe that reports "stale" without a fixture change is measuring nothing.
		List<String> sinkBefore = sink;
		sink = new ArrayList<>();
		RoundResult r0 = round("R0", "v1", versions.get("v1"));
		Map<String, String> afterR0 = readSurface(host, target, propAnonA, fnObjAnon, outerAnon, unnamed, localFirst);
		readings("R0", afterR0);
		expectApplied("R0", afterR0, v1());
		expectUnnamedIdentity("R0", afterR0, 2);
		List<String> controlProblems = sink;
		sink = sinkBefore;
		checkRound("R0", r0);
		if (!controlProblems.isEmpty()) {
			for (String problem : controlProblems) violation("C1-control", "R0 no-op round was flagged: " + problem);
		} else {
			pass("R0: a byte-identical round produces no finding (the detectors need a real change)");
		}

		// R1: unnamed anonymous objects reordered + bodies changed (host must still take the round).
		RoundResult r1 = round("R1", "v1", versions.get("v2"));
		Map<String, String> afterR1 = readSurface(host, target, propAnonA, fnObjAnon, outerAnon, unnamed, localFirst);
		readings("R1", afterR1);
		checkRound("R1", r1);
		expectApplied("R1", afterR1, r1());
		expectUnnamedIdentity("R1", afterR1, 2);

		// R2: named anonymous objects gain a field -> InitFix must patch the surviving instance.
		RoundResult r2 = round("R2", "v2", versions.get("v3"));
		Map<String, String> afterR2 = readSurface(host, target, propAnonA, fnObjAnon, outerAnon, unnamed, localFirst);
		readings("R2", afterR2);
		checkRound("R2", r2);
		expectApplied("R2", afterR2, r2());
		expectAddedField("R2", propAnonA);
		expectUnnamedIdentity("R2", afterR2, 2);

		// R3: local rename + nested renumber + capture growth on top of everything before.
		RoundResult r3 = round("R3", "v3", versions.get("v4"));
		Map<String, String> afterR3 = readSurface(host, target, propAnonA, fnObjAnon, outerAnon, unnamed, localFirst);
		readings("R3", afterR3);
		checkRound("R3", r3);
		expectApplied("R3", afterR3, r3());
		expectUnnamedIdentity("R3", afterR3, 2);
		expectLoadable("R3", HOST + "$makeLocalNamedAnons$renamedFirst$1");
		expectLoadable("R3", HOST + "$outerAnon$1$innerAnons$1");
		expectLoadable("R3", HOST + "$outerAnon$1$innerAnons$2");
	}

	/** Read every business-observable slot from the *surviving* instances plus fresh calls. */
	private static Map<String, String> readSurface(Class<?> host, Object target, Object propAnonA, Object fnObjAnon,
	                                               Object outerAnon, List<Object> unnamed, Object localFirst) {
		return surface(
			labelOf(propAnonA),
			labelOf(fnObjAnon),
			labelOf(outerAnon),
			String.valueOf(safeCall(() -> host.getMethod("anonStruct").invoke(host.getConstructor().newInstance()))),
			labelOf(unnamed.isEmpty() ? null : unnamed.get(0)),
			labelOf(unnamed.size() < 2 ? null : unnamed.get(1)),
			labelOf(unnamed.size() < 3 ? null : unnamed.get(2)),
			labelOf(localFirst));
	}

	private record RoundResult(Throwable escaped, int files) {
	}

	/** One production batch: sync the class-output dir to {@code targetVersion}, then redefine. */
	private static RoundResult round(String name, String fromVersion, Path targetVersion) throws Exception {
		List<Path> changed = sync(targetVersion);
		seedRuntimeBaseline(buildRoot.resolve("out").resolve(fromVersion));
		Throwable escaped = null;
		try {
			processChanges(changed);
		} catch (Throwable t) {
			escaped = unwrap(t);
		}
		System.out.println("KTANON-ROUND round=" + name + " files=" + changed.size()
			+ " escaped=" + (escaped == null ? "none" : escaped.getClass().getSimpleName() + ": " + escaped.getMessage()));
		return new RoundResult(escaped, changed.size());
	}

	private static void checkRound(String name, RoundResult round) {
		if (round.escaped() != null) {
			violation("C1-crash", name + " batch threw " + round.escaped().getClass().getName()
				+ ": " + round.escaped().getMessage());
		}
		for (String line : snapshotLogs()) {
			if (line.contains("NullPointerException")) {
				violation("C1-npe", name + " round logged " + truncate(line));
			}
		}
	}

	// ------------------------------------------------------------------ business surface reads

	/** Read a Kotlin property through its getter (kotlinc emits {@code getXxx} for {@code val xxx}). */
	private static Object readProperty(Class<?> host, Object target, String name) throws Exception {
		return host.getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)).invoke(target);
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asList(Object value) {
		if (value instanceof List<?> list) return (List<Object>) list;
		return List.of();
	}

	private static Object safeCall(ThrowingSupplier supplier) {
		try {
			return supplier.get();
		} catch (Throwable t) {
			Throwable cause = unwrap(t);
			violation("C1-ncdfe", "host call threw " + cause.getClass().getName() + ": " + cause.getMessage());
			return "<threw " + cause.getClass().getSimpleName() + ">";
		}
	}

	private interface ThrowingSupplier {
		Object get() throws Throwable;
	}

	/** Call {@code label()} and classify a throw: linkage failures and NPEs are the two names in the brief. */
	private static String labelOf(Object instance) {
		if (instance == null) return "<null-instance>";
		try {
			return String.valueOf(instance.getClass().getMethod("label").invoke(instance));
		} catch (Throwable t) {
			Throwable cause = unwrap(t);
			String code = cause instanceof LinkageError ? "C1-ncdfe" : "C1-npe";
			violation(code, "label() on " + instance.getClass().getName() + " threw "
				+ cause.getClass().getName() + ": " + cause.getMessage());
			return "<threw " + cause.getClass().getSimpleName() + ">";
		}
	}

	/** Every named member must reach the round's target version (the host was not rejected as a whole). */
	private static void expectApplied(String round, Map<String, String> actual, Map<String, String> target) {
		for (String slot : NAMED_SLOTS) {
			String want = target.get(slot);
			String got = actual.get(slot);
			if (want.equals(got)) {
				pass(round + ": " + slot + " reached " + got);
			} else {
				violation("C1-stale", round + ": " + slot + " is '" + got + "' but must be '" + want
					+ "' (the round did not take effect for this member)");
			}
		}
	}

	/**
	 * The unnamed trio may be re-routed or kept, but each surviving instance must answer its own
	 * slot's semantics: an instance created as {@code uN} may only ever read back {@code uN:*}.
	 */
	private static void expectUnnamedIdentity(String round, Map<String, String> actual, int versions) {
		for (int i = 1; i <= UNNAMED_SLOTS.size(); i++) {
			String slot = UNNAMED_SLOTS.get(i - 1);
			String value = actual.get(slot);
			String own = "u" + i + ":";
			if (value != null && value.startsWith(own)) {
				pass(round + ": " + slot + " kept its own semantics (" + value + ")");
			} else {
				violation("C1-swap", round + ": " + slot + " answered '" + value
					+ "' but an instance created as u" + i + " may only answer '" + own + "*'");
			}
		}
	}

	/** R2: the field added to the surviving anonymous object must be patched by InitFix. */
	private static void expectAddedField(String round, Object instance) {
		if (instance == null) {
			violation("C1-initfix", round + ": no surviving propAnonA instance to read");
			return;
		}
		try {
			Method method = instance.getClass().getMethod("addedFlagValue");
			Object value = method.invoke(instance);
			if (Integer.valueOf(42).equals(value)) {
				pass(round + ": surviving propAnonA instance read the added field initializer (42)");
			} else {
				violation("C1-initfix", round + ": surviving propAnonA addedFlagValue()=" + value + " (expected 42)");
			}
		} catch (NoSuchMethodException e) {
			violation("C1-initfix", round + ": surviving propAnonA class never received addedFlagValue()"
				+ " (class=" + instance.getClass().getName() + ")");
		} catch (Throwable t) {
			Throwable cause = unwrap(t);
			violation("C1-initfix", round + ": addedFlagValue() threw " + cause.getClass().getName() + ": " + cause.getMessage());
		}
	}

	/** R3: a class the redefined host references must be loadable (B1). */
	private static void expectLoadable(String round, String className) {
		try {
			Class<?> loaded = Class.forName(className, false, KotlinAnonSequenceProbe.class.getClassLoader());
			pass(round + ": " + className + " is loadable (" + loaded.getName() + ")");
		} catch (Throwable t) {
			Throwable cause = unwrap(t);
			violation("C1-load", round + ": " + className + " is not loadable: "
				+ cause.getClass().getName() + ": " + cause.getMessage());
		}
	}

	// ------------------------------------------------------------------ detector self-check

	/**
	 * The detectors are the probe's own instrument, so they are checked against fabricated readings
	 * before any real batch runs: they must stay silent on a healthy surface and must flag a silent
	 * swap, a stale member and a missing InitFix patch. Without this, "the probe is red" could just
	 * mean the detector screams at everything.
	 */
	private static void selfCheck() {
		List<String> saved = sink;
		List<String> scratch = new ArrayList<>();
		sink = scratch;
		try {
			Map<String, String> healthy = surface("propA:r1", "fnObj:v1", "outer:v1>inner:v1", "structA:v1",
				"u1:v2", "u2:v2", "u3:v2", "localFirst:v1");
			expectApplied("SELF-healthy", healthy, r1());
			expectUnnamedIdentity("SELF-healthy", healthy, 2);
			boolean silent = scratch.isEmpty();

			scratch.clear();
			expectApplied("SELF-stale", v1(), r1());
			boolean caughtStale = scratch.stream().anyMatch(line -> line.startsWith("C1-stale"));

			scratch.clear();
			expectUnnamedIdentity("SELF-swap", surface("propA:r1", "fnObj:v1", "outer:v1>inner:v1", "structA:v1",
				"u3:v2", "u2:v2", "u1:v2", "localFirst:v1"), 2);
			boolean caughtSwap = scratch.stream().anyMatch(line -> line.startsWith("C1-swap"));

			scratch.clear();
			expectAddedField("SELF-initfix", null);
			boolean caughtInitfix = scratch.stream().anyMatch(line -> line.startsWith("C1-initfix"));
			sink = saved;

			check(silent, "SELF: detectors stay silent on a healthy surface");
			check(caughtStale, "SELF: a stale member (round never reached the JVM) is flagged");
			check(caughtSwap, "SELF: a silent semantic swap between unnamed objects is flagged");
			check(caughtInitfix, "SELF: a missing InitFix patch on a surviving instance is flagged");
		} finally {
			sink = saved;
		}
	}

	// ------------------------------------------------------------------ C2: real kotlinc product

	private static void checkKotlincShape(Path v1) throws IOException {
		Map<String, byte[]> classes = readClasses(v1);
		for (String expected : EXPECTED_V1_CLASSES) {
			String name = expected.replace("HOST", PKG + "/KtAnonTarget").replace("MARKER", PKG + "/Marker");
			if (!classes.containsKey(name)) {
				violation("C2-kotlinc", "kotlinc output is missing " + name + " (names present: " + classes.keySet() + ")");
			}
		}
		byte[] hostBytes = classes.get(PKG + "/KtAnonTarget");
		if (hostBytes == null) return;
		ClassNode hostNode = parse(hostBytes);
		boolean hasMetadata = false;
		if (hostNode.visibleAnnotations != null) {
			for (AnnotationNode annotation : hostNode.visibleAnnotations) {
				if ("Lkotlin/Metadata;".equals(annotation.desc)) hasMetadata = true;
			}
		}
		if (!hasMetadata) violation("C2-kotlinc", "host has no kotlin.Metadata annotation; not a real kotlinc product");
		for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
			if (!entry.getKey().startsWith(PKG + "/KtAnonTarget$")) continue;
			ClassNode node = parse(entry.getValue());
			if (node.outerClass == null) {
				violation("C2-kotlinc", entry.getKey() + " has no EnclosingMethod attribute");
			}
			if (node.innerClasses != null) {
				for (var inner : node.innerClasses) {
					if (node.name.equals(inner.name) && (inner.outerName != null || inner.innerName != null)) {
						violation("C2-kotlinc", entry.getKey() + " carries a named InnerClasses self entry: "
							+ inner.outerName + "/" + inner.innerName);
					}
				}
			}
		}
		System.out.println("KTANON-CLASSES v1=" + classes.keySet().stream().sorted().toList());
	}

	// ------------------------------------------------------------------ kotlinc invocation

	/** Compile {@code tests/ktanon/<version>} into {@code <build>/ktanon/out/<version>} with kotlinc 2.3.0. */
	private static Path compile(String version) throws Exception {
		Path out = buildRoot.resolve("out").resolve(version);
		Files.createDirectories(out);
		List<Path> jars = locateCompilerJars();
		String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String stdlib = jars.stream().filter(p -> p.getFileName().toString().startsWith("kotlin-stdlib-"))
			.findFirst().orElseThrow(() -> new IllegalStateException("kotlin-stdlib jar missing")).toString();
		List<String> command = new ArrayList<>();
		command.add(javaBin);
		command.add("-cp");
		command.add(jars.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)));
		command.add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
		command.add("-no-reflect");
		command.add("-no-stdlib");
		command.add("-nowarn");
		command.add("-cp");
		command.add(stdlib);
		command.add("-d");
		command.add(out.toString());
		command.add(srcRoot.resolve(version).toString());
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		int exit = process.waitFor();
		if (exit != 0) {
			throw new IllegalStateException("kotlinc " + version + " exit=" + exit + ": "
				+ output.substring(0, Math.min(2000, output.length())));
		}
		System.out.println("KTANON-COMPILED version=" + version + " classes=" + readClasses(out).size()
			+ " compiler=" + jars.get(0).getFileName());
		return out;
	}

	/** Locate the offline kotlinc jars in the Gradle cache (no network, no dependency resolution). */
	private static List<Path> locateCompilerJars() throws IOException {
		String home = System.getProperty("remold.ktanon.gradleUserHome",
			System.getenv().getOrDefault("GRADLE_USER_HOME", Path.of(System.getProperty("user.home"), ".gradle").toString()));
		Path caches = Path.of(home, "caches");
		if (!Files.isDirectory(caches)) throw new IllegalStateException("Gradle cache not found under " + home);
		List<Path> found = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(caches)) {
			List<Path> all = walk.filter(Files::isRegularFile).collect(Collectors.toList());
			for (String jar : COMPILER_JARS) {
				Path hit = all.stream()
					.filter(p -> p.getFileName().toString().equals(jar))
					.max(Comparator.comparing(Path::toString))
					.orElseThrow(() -> new IllegalStateException("missing compiler jar " + jar + " under " + caches));
				found.add(hit);
			}
		}
		return found;
	}

	// ------------------------------------------------------------------ batch / class-output plumbing

	/**
	 * Replace the class-output directory (which is on the probe classpath, like a real build dir)
	 * with {@code version}, returning the files of the new version (the batch input).
	 */
	private static List<Path> sync(Path version) throws IOException {
		if (!Files.isDirectory(liveDir)) Files.createDirectories(liveDir);
		try (Stream<Path> walk = Files.walk(liveDir)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
				if (!path.equals(liveDir)) Files.deleteIfExists(path);
			}
		}
		List<Path> copied = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(version)) {
			for (Path source : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
				Path target = liveDir.resolve(version.relativize(source).toString());
				Files.createDirectories(target.getParent());
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				copied.add(target);
			}
		}
		return copied;
	}

	/** Seed the runtime baseline exactly like the transformer does on first load. */
	private static void seedRuntimeBaseline(Path version) throws IOException {
		for (Map.Entry<String, byte[]> entry : readClasses(version).entrySet()) {
			RemoldAgent.recordRuntimeBytecode(entry.getKey().replace('/', '.'), entry.getValue());
		}
	}

	private static void processChanges(List<Path> changed) throws Exception {
		Method method = RemoldAgent.class.getDeclaredMethod("processChanges", Set.class, Class[].class);
		method.setAccessible(true);
		method.invoke(null, new LinkedHashSet<>(changed), instrumentation.getAllLoadedClasses());
	}

	private static Map<String, byte[]> readClasses(Path root) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		if (!Files.isDirectory(root)) return out;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path file : walk.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".class")).sorted().collect(Collectors.toList())) {
				String relative = root.relativize(file).toString();
				out.put(relative.substring(0, relative.length() - ".class".length()).replace('\\', '/'),
					Files.readAllBytes(file));
			}
		}
		return out;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	// ------------------------------------------------------------------ reporting

	private static void pass(String message) {
		passed++;
		if (sink == VIOLATIONS) System.out.println("KTANON-PASS " + message);
	}

	/** Detectable-fault ledger; the self-check temporarily swaps in a scratch sink. */
	private static List<String> sink = VIOLATIONS;

	private static void violation(String code, String message) {
		String line = code + ": " + truncate(message);
		sink.add(line);
		if (sink == VIOLATIONS) System.out.println("KTANON-FAIL " + line);
	}

	private static void check(boolean ok, String label) {
		if (ok) {
			pass(label);
		} else {
			violation("SELF", label);
		}
	}

	private static void readings(String round, Map<String, String> surface) {
		System.out.println("KTANON-READING round=" + round + " surface=" + describe(surface));
	}

	private static String describe(Map<String, String> surface) {
		return surface.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" | "));
	}

	private static String truncate(String value) {
		if (value == null) return "null";
		String flat = value.replace('\n', ' ');
		return flat.length() <= 220 ? flat : flat.substring(0, 220) + "...";
	}

	private static List<String> snapshotLogs() {
		synchronized (LOGS) {
			return List.copyOf(LOGS);
		}
	}

	private static Throwable unwrap(Throwable t) {
		Throwable current = t;
		while (current instanceof InvocationTargetException && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	/** Records agent warn/error lines (used only to detect a logged NPE; never as a pass signal). */
	static final class CaptureLogger implements RemoldAgent.Logger {
		private static final boolean VERBOSE = Boolean.getBoolean("remold.ktanon.verbose");

		@Override public void log(String msg) {
			if (VERBOSE) record("LOG", msg);
		}

		@Override public void info(String msg) {
			if (VERBOSE) record("INFO", msg);
		}

		@Override public void warn(String msg) { record("WARN", msg); }

		@Override public void error(String msg) { record("ERROR", msg); }

		@Override public void error(String msg, Throwable t) {
			record("ERROR", msg + (t == null ? "" : " | " + t.getClass().getName() + ": " + t.getMessage()));
		}

		private void record(String level, String msg) {
			String line = "[" + level + "] " + msg;
			synchronized (LOGS) { LOGS.add(line); }
			System.out.println("KTANON-AGENT " + truncate(line));
		}
	}
}
