import remold.HotswapBatchListener;
import remold.RemoldAgent;
import remold.ref.InitFix;
import remold.vcs.AlignmentMetaStore;
import remold.vcs.Changeset;
import remold.vcs.ChangesetLog;
import remold.vcs.GenerationalFieldLedger;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A14 runtime probe: post-revert conditional CAS fail-closed into the generational ledger, explicit
 * {@code @RemoldReinit(OVERWRITE)} recovery, and cross-generation {@code replayFacts} consumption with
 * the Eff byte budget at zero
 * ({@code docs/vcs/05-generational-field-ledger.md} §3.3 / §4, criteria A14, G6, G7, G9).
 *
 * <p><b>What it proves, in order (each item is a regression lock):</b></p>
 * <ol>
 *   <li><b>Round 2 replay really writes</b>: the {@code L1} channel applies the drifted initialisers to a
 *       live instance created <i>before</i> the batch — including {@code -0.0f} (sign bit checked through
 *       {@code floatToRawIntBits}) and {@code Z/B/S/C} (the narrow primitives that used to be dropped).</li>
 *   <li><b>A14 fail-closed</b>: after a forward revert to {@code seq 2}, a further drift of the same field
 *       must reconcile against the <b>target</b> generation's fact (expect {@code 1}, write {@code 2}).
 *       The instance still holds {@code 2} — the engine-written value of the target generation — so the
 *       conditional CAS <b>misses</b>: {@code PatchReport.replayFields()} says {@code skipped: ...}, a warn
 *       names the field, the heap value is <b>not</b> touched, and the debt lands in
 *       {@link GenerationalFieldLedger} (never in the transient {@code PENDING} slot). Taking the
 *       expectation from the round's {@code ClassDiff} instead would hit the CAS and silently report
 *       {@code replayed} — that is the mutation this case kills.</li>
 *   <li><b>G7 recovery</b>: an explicit {@code @RemoldReinit(mode = OVERWRITE)} on the declaring field
 *       writes it, closes the ledger debt and shows up as {@code recovered: overwrite} in the report.</li>
 *   <li><b>G9 / Eff eviction</b>: the whole reconciliation runs with {@code -Dremold.vcs.eff_bytes=0}, i.e.
 *       with the Eff bytes evicted from the changeset store; the facts (which live in
 *       {@code Changeset.replayFacts}) still resolve. The probe asserts the eviction really happened in the
 *       same run instead of assuming it.</li>
 * </ol>
 *
 * <p>Requires a DCEVM-capable JVM with {@code -XX:+AllowEnhancedClassRedefinition}, the native libtool, and
 * {@code -Dremold.vcs.enabled=true -Dremold.vcs.revert=true -Dremold.agent.init_drift.parents=probe/Base}.
 * Run through {@code ./gradlew :tests:vcsA14Probe -Pp05.jbr=<dir>}.</p>
 */
public final class VcsA14Probe {

	private static final String FIELD = "vf";
	private static final String BASE_INTERNAL = "probe/Base";

	private static Path classes;
	private static Path src;
	private static URLClassLoader loader;
	private static final AtomicInteger batches = new AtomicInteger();
	private static final List<String> warns = new ArrayList<>();
	private static volatile CountDownLatch latch = new CountDownLatch(0);

	private static Class<?> baseClass;
	private static Class<?> hostClass;
	/**
	 * The anonymous subclass is read off the live instance every time on purpose: the report is keyed by the
	 * class the redefine landed on, and the instance is the only handle the probe keeps across generations.
	 */
	private static Class<?> anonClass() {
		return survivor.getClass();
	}

	/** The declaring class is read off the live instance too (see {@link #anonClass()}). */
	private static Class<?> declaringClass() {
		return survivor.getClass().getSuperclass();
	}
	/** Created right after round 1 and kept alive across every generation: the heap is never rolled back. */
	private static Object survivor;
	private static Method vfValue;
	private static Method ffValue;
	private static Method zfValue;
	private static Method cfValue;
	private static Method bfValue;
	private static Method sfValue;

	/** Last {@code Base} generation compiled, so a round only rewrites it when it really changes. */
	private static int lastBaseMarker = 0;
	private static boolean lastBaseOverwrite;

	private static int passed;
	private static int failed;

	public static void main(String[] args) throws Throwable {
		System.out.println("=== VcsA14Probe: post-revert CAS fail-closed -> ledger debt -> OVERWRITE recovery ===");
		if (RemoldAgent.getInstrumentation() == null) {
			System.err.println("[FAIL] no instrumentation; run with -javaagent");
			System.exit(1);
		}
		if (!Boolean.getBoolean("remold.vcs.enabled") || !Boolean.getBoolean("remold.vcs.revert")) {
			System.err.println("[FAIL] remold.vcs.enabled and remold.vcs.revert must both be true");
			System.exit(1);
		}
		String driftParents = System.getProperty("remold.agent.init_drift.parents", "");
		if (!"probe/Base".equals(driftParents.trim())) {
			System.err.println("[FAIL] -Dremold.agent.init_drift.parents=probe/Base is required, got '"
			 + driftParents + "'");
			System.exit(1);
		}

		Path rootDir = Paths.get(System.getProperty("vcsA14Probe.dir",
		 Paths.get("build", "vcs-a14-probe").toString())).toAbsolutePath();
		classes = rootDir.resolve("classes");
		src = rootDir.resolve("src");
		deleteRecursively(rootDir);
		Files.createDirectories(classes);
		Files.createDirectories(src);
		AlignmentMetaStore.INSTANCE.reset();
		ChangesetLog.INSTANCE.reset();
		GenerationalFieldLedger.INSTANCE.reset();

		RemoldAgent.Logger baseLogger = RemoldAgent.logger;
		RemoldAgent.logger = new RemoldAgent.Logger() {
			@Override
			public void log(String msg) {
				baseLogger.log(msg);
			}

			@Override
			public void info(String msg) {
				baseLogger.info(msg);
			}

			@Override
			public void warn(String msg) {
				warns.add(msg);
				baseLogger.warn(msg);
			}

			@Override
			public void error(String msg) {
				baseLogger.error(msg);
			}

			@Override
			public void error(String msg, Throwable t) {
				baseLogger.error(msg, t);
			}
		};

		RemoldAgent.addBatchListener(new HotswapBatchListener() {
			@Override
			public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
				batches.incrementAndGet();
				latch.countDown();
			}
		});

		// ---- Round 1: the baseline generation (no drift yet) ----
		// The pre-round compile is deliberately a *different* generation of Base/Other, otherwise the first
		// watched batch would see "identical bytes on disk" and never redefine anything.
		lastBaseMarker = 0;
		compile(new String[]{
			"probe/Base.java", baseSource(0, false),
			"probe/Host.java", generation(1, 0.0f, false, 'a', 1, 1),
			"probe/Other.java", otherSource(0)
		});
		loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, VcsA14Probe.class.getClassLoader());
		baseClass = Class.forName("probe.Base", true, loader);
		hostClass = Class.forName("probe.Host", true, loader);
		vfValue = baseClass.getMethod("vfValue");
		ffValue = baseClass.getMethod("ffValue");
		zfValue = baseClass.getMethod("zfValue");
		cfValue = baseClass.getMethod("cfValue");
		bfValue = baseClass.getMethod("bfValue");
		sfValue = baseClass.getMethod("sfValue");

		RemoldAgent.setWatchPaths("vcsA14Probe", List.of(classes), List.of());
		RemoldAgent.setFileShakeMs(250);
		Thread.sleep(400L);

		check(batch(generation(1, 0.0f, false, 'a', 1, 1), 1, false, otherSource(2)),
		 "R1: the batch fires for the first generation");
		long scope = AlignmentMetaStore.INSTANCE.loaderScopeId(loader);
		check(ChangesetLog.INSTANCE.head(scope).orElseThrow().seq() == 1, "R1: the scope is at seq 1");

		// The survivor is created against the round-1 initialisers and never re-created: every later
		// assertion about "the heap still holds X" depends on this object staying the same one.
		survivor = hostClass.getMethod("make").invoke(hostClass.getDeclaredConstructor().newInstance());
		check("probe.Host$1".equals(anonClass().getName()),
		 "R1: the replayed class is the anonymous subclass (" + anonClass().getName() + ")");
		checkEq(1, intOf(vfValue), "R1: the survivor starts on the round-1 initialiser (vf == 1)");

		// ---- Round 2: the drift the L1 channel replays (raw bits + narrow primitives) ----
		check(batch(generation(2, -0.0f, true, 'Z', -1, -2), 2, false, otherSource(3)),
		 "R2: the batch fires for the drifting generation (Base moves too, so the fact's declaring"
		  + " class has an entry in it)");
		checkEq(2, intOf(vfValue), "R2: the conditional replay wrote the survivor's int field (vf == 2)");
		checkEq(0x80000000, Float.floatToRawIntBits(floatOf(ffValue)),
		 "R2: -0.0f was replayed as its raw bit pattern (sign bit set, not +0.0)");
		check(Boolean.TRUE.equals(zfValue.invoke(survivor)), "R2: the boolean field was replayed (true)");
		check(Character.valueOf('Z').equals(charOf(cfValue)), "R2: the char field was replayed ('Z')");
		checkEq(-1, byteOf(bfValue), "R2: the byte field was replayed (-1)");
		checkEq(-2, shortOf(sfValue), "R2: the short field was replayed (-2)");
		check(generationFacts(scope, 2) > 0,
		 "R2: the drift produced replayFacts on the generation (" + generationFacts(scope, 2) + ")");
		check(GenerationalFieldLedger.INSTANCE.isUnclosed(loader, BASE_INTERNAL, FIELD) == false,
		 "R2: a written replay leaves no unclosed debt");

		// ---- Round 3: a different class moves the chain on, this class stays byte-identical ----
		check(batch(null, 2, false, otherSource(4)), "R3: the batch fires for the unrelated class");
		check(ChangesetLog.INSTANCE.head(scope).orElseThrow().seq() == 3,
		 "R3: the scope advances to seq 3 without touching the replay class");

		// ---- The forward revert to seq 2 (heap values are deliberately NOT rolled back) ----
		String summary = RemoldAgent.revertTo(2L);
		Changeset forward = ChangesetLog.INSTANCE.head(scope).orElseThrow();
		check(forward.seq() == 4 && forward.revertedToSeq() == 2L,
		 "R4: the revert lands as forward seq 4 with revertedToSeq=2 (summary=" + summary + ")");
		checkEq(2, intOf(vfValue),
		 "R4: the survivor still holds the target generation's engine-written value (vf == 2)");
		check(GenerationalFieldLedger.INSTANCE.latest(loader, BASE_INTERNAL, FIELD, 2L).isPresent(),
		 "R4: the target generation's fact is still in the ledger after the revert");

		// ---- Force the Eff budget to zero: the facts must not depend on the stored Eff bytes ----
		long effHeldBefore = ChangesetLog.INSTANCE.effBytesHeld();
		int effDroppedBefore = ChangesetLog.INSTANCE.effDroppedCount();
		String previousBudget = System.getProperty("remold.vcs.eff_bytes");
		System.setProperty("remold.vcs.eff_bytes", "0");

		// ---- Round 5: the same field drifts again; the CAS must be reconciled against seq 2 ----
		check(batch(generation(5, -0.0f, true, 'Z', -1, -2), 2, false, otherSource(5)),
		 "R5: the batch fires with the Eff budget at zero");
		long effHeldAfter = ChangesetLog.INSTANCE.effBytesHeld();
		check(ChangesetLog.INSTANCE.effDroppedCount() > effDroppedBefore,
		 "R5: the budget of 0 really evicts stored Eff blobs (dropped " + effDroppedBefore + " -> "
		  + ChangesetLog.INSTANCE.effDroppedCount() + ")");
		check(effHeldAfter <= effHeldBefore,
		 "R5: the Eff store stops growing past one blob under a budget of 0 (held "
		  + effHeldBefore + " -> " + effHeldAfter + " bytes)");

		InitFix.PatchReport report = InitFix.getLastReport(anonClass());
		check(report != null, "R5: the replayed class published a PatchReport");
		String vfResult = report == null ? null : report.replayFields().get(FIELD);
		check(vfResult != null && vfResult.startsWith("skipped: "),
		 "A14: a missed post-revert CAS is reported as skipped, not as applied (" + vfResult + ")");
		check(vfResult != null && vfResult.contains("expected initializer"),
		 "A14: the skip reason names the target generation's expectation (" + vfResult + ")");
		check(!report.replayGenerated(), "A14: the refused replay is not counted as generated");
		checkEq(2, intOf(vfValue),
		 "A14: the heap value is left untouched by the fail-closed skip (vf still 2)");
		check(GenerationalFieldLedger.INSTANCE.isUnclosed(loader, BASE_INTERNAL, FIELD),
		 "A14/G6: the un-closed debt lives in the generational ledger, keyed by the declaring class");
		check(hasWarnNaming(FIELD, "NOT replayed"),
		 "A14: a warn names the field that was not replayed");

		// ---- Round 6: explicit OVERWRITE recovery closes the debt ----
		warns.clear();
		check(batch(generation(2, -0.0f, true, 'Z', -1, -2, false), 3, true, otherSource(6)),
		 "R6: the batch fires for the explicit rebuild (the declaring class takes over the field and"
		  + " the subclass stops assigning it)");
		InitFix.PatchReport baseReport = InitFix.getLastReport(declaringClass());
		check(baseReport != null, "R6: the declaring class published a PatchReport");
		check(baseReport != null && InitFix.RECOVERED_BY_OVERWRITE.equals(
		 baseReport.recoveredFields().get(FIELD)),
		 "G7: the report shows the field as recovered by @RemoldReinit(OVERWRITE) ("
		  + (baseReport == null ? null : baseReport.recoveredFields()) + ")");
		check(!GenerationalFieldLedger.INSTANCE.isUnclosed(loader, BASE_INTERNAL, FIELD),
		 "G7: the ledger debt is closed by the explicit OVERWRITE");
		checkEq(0, intOf(vfValue),
		 "G7: the explicit OVERWRITE really wrote the field on the survivor (vf == 0, the declared"
		  + " initialiser, forced over the engine-written 2)");

		if (previousBudget == null) System.clearProperty("remold.vcs.eff_bytes");
		else System.setProperty("remold.vcs.eff_bytes", previousBudget);
		RemoldAgent.logger = baseLogger;

		System.out.println("=== VCS A14 PROBE SUMMARY: passed=" + passed + ", failed=" + failed + " ===");
		if (failed > 0) System.exit(1);
	}

	/** True when some warn names {@code field} and contains {@code needle}. */
	private static boolean hasWarnNaming(String field, String needle) {
		for (String warn : warns) {
			if (warn.contains(field) && warn.contains(needle)) return true;
		}
		return false;
	}

	/** How many replay facts the generation at {@code seq} carries. */
	private static int generationFacts(long scope, long seq) {
		Changeset cs = ChangesetLog.INSTANCE.bySeq(scope, seq).orElse(null);
		return cs == null ? -1 : cs.replayFacts().size();
	}

	/**
	 * Compile one round into the watched directory.
	 *
	 * @param hostSource    the {@code Host} source (carries the anonymous initialiser block), or
	 *                      {@code null} to leave {@code Host}/{@code Base} untouched this round
	 * @param baseMarker    {@code Base}'s own marker constant: changing it changes {@code Base}'s bytes,
	 *                      which is what gives the fact's declaring class an entry in that generation
	 * @param overwrite     annotate the replayed field on {@code Base} with
	 *                      {@code @RemoldReinit(mode = OVERWRITE)} (the explicit recovery path)
	 * @param otherSource   the unrelated class' source (moves the chain on without touching the field)
	 */
	private static boolean batch(String hostSource, int baseMarker, boolean overwrite, String otherSource)
	 throws Exception {
		latch = new CountDownLatch(1);
		int before = batches.get();
		List<String> pairs = new ArrayList<>();
		// Only touch Base when it really changes: rewriting an identical file still lands it in the
		// batch's candidate set, and the offline hierarchy then refuses to resolve it for that round
		// (fail-closed "candidate bytecode unavailable") — which would hide the case under test.
		if (baseMarker != lastBaseMarker || overwrite != lastBaseOverwrite) {
			pairs.add("probe/Base.java");
			pairs.add(baseSource(baseMarker, overwrite));
			lastBaseMarker = baseMarker;
			lastBaseOverwrite = overwrite;
		}
		if (hostSource != null) {
			pairs.add("probe/Host.java");
			pairs.add(hostSource);
		}
		if (otherSource != null) {
			pairs.add("probe/Other.java");
			pairs.add(otherSource);
		}
		compile(pairs.toArray(new String[0]));
		boolean fired = latch.await(25, TimeUnit.SECONDS);
		return fired && batches.get() > before;
	}

	/**
	 * The anonymous subclass writes inherited fields in an instance initialiser block. {@code L1} only
	 * replays <b>inherited</b> fields (a field the anonymous class declares itself is the ordinary
	 * own-field path), so the field lives on {@code Base} and the block drifts it.
	 */
	private static String generation(int vf, float ff, boolean zf, char cf, int bf, int sf) {
		return generation(vf, ff, zf, cf, bf, sf, true);
	}

	/**
	 * @param writeVf whether the anonymous initialiser still assigns the replayed field. The recovery
	 *                round drops that assignment on purpose: {@code @RemoldReinit(INCLUDE_SUBCLASSES)} on a
	 *                field that a subclass initialiser still assigns is refused by the layout gate (it would
	 *                overwrite the subclass-specific value), so the declaration must win — which is exactly
	 *                the "explicit rebuild" the contract names as the recovery path.
	 */
	private static String generation(int vf, float ff, boolean zf, char cf, int bf, int sf, boolean writeVf) {
		return "package probe;\n"
		 + "public class Host {\n"
		 + "  public Base make() { return new Base() {{ " + (writeVf ? "vf = " + vf + "; " : "")
		 + "ff = " + floatLiteral(ff)
		 + "; zf = " + zf + "; cf = '" + cf + "'; bf = " + bf + "; sf = " + sf + "; }}; }\n"
		 + "}\n";
	}

	/** {@code -0.0f} must reach the fixture source as such, not as {@code 0.0}. */
	private static String floatLiteral(float value) {
		return (Float.floatToRawIntBits(value) == 0x80000000) ? "-0.0f" : value + "f";
	}

	/** {@code marker} keeps {@code Base}'s bytes moving so the generation carries an entry for it. */
	private static String baseSource(int marker, boolean overwriteReinit) {
		String imports = overwriteReinit ? "import remold.annotation.RemoldReinit;\n" : "";
		String annotation = overwriteReinit ? "@RemoldReinit(mode = RemoldReinit.Mode.OVERWRITE) " : "";
		return "package probe;\n"
		 + imports
		 + "public class Base {\n"
		 + "  " + annotation + "public int vf = 0;\n"
		 + "  public float ff = 0.0f;\n"
		 + "  public boolean zf = false;\n"
		 + "  public char cf = 'a';\n"
		 + "  public byte bf = 0;\n"
		 + "  public short sf = 0;\n"
		 + "  public int marker() { return " + marker + "; }\n"
		 + "  public int vfValue() { return vf; }\n"
		 + "  public float ffValue() { return ff; }\n"
		 + "  public boolean zfValue() { return zf; }\n"
		 + "  public char cfValue() { return cf; }\n"
		 + "  public byte bfValue() { return bf; }\n"
		 + "  public short sfValue() { return sf; }\n"
		 + "}\n";
	}

	private static String otherSource(int value) {
		return "package probe;\n"
		 + "public class Other {\n"
		 + "  public int value() { return " + value + "; }\n"
		 + "}\n";
	}

	private static int intOf(Method accessor) throws Exception {
		Object value = accessor.invoke(survivor);
		return value instanceof Integer i ? i : -999;
	}

	private static float floatOf(Method accessor) throws Exception {
		Object value = accessor.invoke(survivor);
		return value instanceof Float f ? f : Float.NaN;
	}

	private static char charOf(Method accessor) throws Exception {
		Object value = accessor.invoke(survivor);
		return value instanceof Character c ? c : '?';
	}

	private static int byteOf(Method accessor) throws Exception {
		Object value = accessor.invoke(survivor);
		return value instanceof Byte b ? b : -999;
	}

	private static int shortOf(Method accessor) throws Exception {
		Object value = accessor.invoke(survivor);
		return value instanceof Short s ? s : -999;
	}

	private static void compile(String... pairs) throws Exception {
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("no system java compiler");
		for (int i = 0; i < pairs.length; i += 2) {
			Path file = src.resolve(pairs[i]);
			Files.createDirectories(file.getParent());
			Files.write(file, pairs[i + 1].getBytes(StandardCharsets.UTF_8));
		}
		String[] args = new String[pairs.length / 2 + 8];
		int n = 0;
		args[n++] = "-nowarn";
		args[n++] = "-proc:none";
		args[n++] = "-encoding";
		args[n++] = "UTF-8";
		args[n++] = "-d";
		args[n++] = classes.toString();
		// A round may compile a single file, so the previously compiled classes must be on the path; the
		// probe's own classpath carries the Remold annotation types the fixtures use.
		args[n++] = "-classpath";
		args[n++] = classes + java.io.File.pathSeparator + System.getProperty("java.class.path");
		for (int i = 0; i < pairs.length; i += 2) args[n++] = src.resolve(pairs[i]).toString();
		int rc = javac.run(null, null, null, args);
		if (rc != 0) throw new IllegalStateException("javac failed rc=" + rc);
	}

	private static void check(boolean condition, String what) {
		if (condition) {
			passed++;
			System.out.println("[PASS] " + what);
		} else {
			failed++;
			System.out.println("[FAIL] " + what);
		}
	}

	private static void checkEq(int expected, int actual, String what) {
		check(expected == actual, what + " (expected=" + expected + ", actual=" + actual + ")");
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// probe tear-down is best effort
				}
			});
		}
	}
}
