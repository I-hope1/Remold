import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verdict oracle for the NeoForge native-{@code [[mixins]]} Mixin reload probe.
 *
 * <p>Reads a probe evidence file and separates three questions that a raw log cannot:</p>
 * <ol>
 *   <li><b>Preconditions</b> — was the target really woven at baseline ({@code NP-BASE}) and was a
 *       Mixin template reload actually attempted for each stage ({@code [NP-DRIVER] phase=…})?
 *       If not, the run is {@code INVALID}: "nothing happened" must never be reported as a red
 *       (or green) result.</li>
 *   <li><b>Verdict</b> — {@code GREEN} iff every member check passed, else {@code RED} with the
 *       exact failing check ids.</li>
 *   <li><b>Degradation / shortcut</b> — {@code INVALID} also covers the run shapes that make a
 *       red or green result meaningless: a failed ClassInfo metadata sync, an empty member diff
 *       where a new member was expected, JPMS refusing the bridge's deep reflection
 *       ({@code InaccessibleObjectException}), the plugin not being loaded
 *       ({@code does not implement HotswapPlugin}), and — the one this probe exists to exclude —
 *       a Mixin config registered by hand ({@code MixinEnvironment.addConfiguration}) instead of
 *       through the NeoForge-native {@code META-INF/neoforge.mods.toml} {@code [[mixins]]} block.</li>
 * </ol>
 *
 * <p>Self-contradictory evidence is {@code INVALID} as well (a green check while the driver saw
 * the reload rejected, or vice versa), so a probe whose assertions were weakened into "always
 * pass" cannot produce a green run.</p>
 *
 * <p>Exit codes: {@code 0} = GREEN, {@code 1} = RED, {@code 2} = INVALID. The same oracle is run
 * against {@code fixtures/} by {@code verify-evidence.sh} to prove it still discriminates.</p>
 */
public final class Verdict {
    private static final String[] MEMBER_CHECKS = {"NP-M1", "NP-M2", "NP-PRESERVE-1", "NP-PRESERVE-2"};

    /**
     * Substrings that make a run unattributable even when the member checks look conclusive.
     * Each entry is {@code {marker, reason}}.
     */
    private static final String[][] DEGRADE_MARKERS = {
        {"Failed to sync Mixin class metadata", "DEGRADE=classinfo-sync-failed"},
        {"has no new members", "DEGRADE=no-new-members-where-one-was-expected"},
        {"InaccessibleObjectException", "DEGRADE=jpms-inaccessible-object"},
        {"does not implement HotswapPlugin", "DEGRADE=plugin-not-loaded"},
    };

    /**
     * Manual Mixin registration. The probe must rely on the NeoForge loader's
     * {@code [[mixins]]} declaration; a run that registers the config itself proves nothing about
     * that path, and Mixin prints a deprecation warning naming the call.
     */
    private static final String[] MANUAL_REGISTRATION_MARKERS = {
        "MixinEnvironment::addConfiguration",
        "Mixins::addConfiguration",
    };

    private Verdict() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java Verdict.java <evidence-log>");
            System.exit(2);
        }
        Result result = evaluate(Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8));
        System.out.println(result.line());
        System.exit(result.exitCode);
    }

    /** Strips ANSI escapes and Gradle console prefixes and keeps the line from the first marker. */
    private static String markerTail(String raw) {
        String line = raw.replaceAll("\u001B\\[[;0-9]*[A-Za-z]", "");
        for (String marker : new String[]{"[NP] CHECK ", "[NP-DRIVER] ", "[NP] "}) {
            int at = line.indexOf(marker);
            if (at >= 0) {
                return line.substring(at).strip();
            }
        }
        return line.strip();
    }

    static Result evaluate(List<String> lines) {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Map<String, String> reload = new LinkedHashMap<>();
        Set<String> degradation = new LinkedHashSet<>();
        for (String raw : lines) {
            String line = markerTail(raw);
            if (line.startsWith("[NP] CHECK ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4 && ("PASS".equals(parts[3]) || "FAIL".equals(parts[3]))) {
                    checks.put(parts[2], "PASS".equals(parts[3]));
                }
            } else if (line.startsWith("[NP-DRIVER] ")) {
                String phase = null;
                String state = null;
                for (String kv : line.substring("[NP-DRIVER] ".length()).split("\\s+")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String k = kv.substring(0, eq);
                    String v = kv.substring(eq + 1);
                    if ("phase".equals(k)) phase = v;
                    if ("reload".equals(k)) state = v;
                }
                if (phase != null && state != null) {
                    reload.put(phase, state);
                }
            }
            // Degradation / shortcut markers are matched on the raw line: they are printed by the
            // agent, Mixin or the JVM, not by the probe, so they never carry a [NP] prefix.
            for (String[] marker : DEGRADE_MARKERS) {
                if (raw.contains(marker[0])) {
                    degradation.add(marker[1]);
                }
            }
            for (String marker : MANUAL_REGISTRATION_MARKERS) {
                if (raw.contains(marker)) {
                    degradation.add("MANUAL-MIXIN-REGISTRATION=" + marker);
                }
            }
        }

        Set<String> invalid = new LinkedHashSet<>();
        Boolean baseline = checks.get("NP-BASE");
        if (!Boolean.TRUE.equals(baseline)) {
            invalid.add("PRECOND-NP-BASE=" + (baseline == null ? "missing" : "FAIL"));
        }
        for (String phase : new String[]{"stage1", "stage2"}) {
            String state = reload.get(phase);
            if (state == null) {
                invalid.add("PRECOND-RELOAD-" + phase + "=missing");
            } else if ("not-attempted".equals(state) || "unknown".equals(state)) {
                invalid.add("PRECOND-RELOAD-" + phase + "=" + state);
            }
        }
        invalid.addAll(degradation);

        Set<String> failing = new LinkedHashSet<>();
        for (String id : MEMBER_CHECKS) {
            Boolean value = checks.get(id);
            if (value == null) {
                failing.add(id + "(missing)");
            } else if (!value) {
                failing.add(id);
            }
        }

        boolean observedRejected = reload.containsValue("rejected") || reload.containsValue("weave-failed");
        boolean observedSuccess = reload.containsValue("success");
        if (observedRejected && failing.isEmpty()) {
            invalid.add("SELF-CONTRADICTION=green-checks-with-rejected-reload");
        }
        if (!observedRejected && observedSuccess && !failing.isEmpty()) {
            invalid.add("SELF-CONTRADICTION=failing-checks-with-successful-reload");
        }

        // Per-stage consistency: each stage's member check must agree with what the driver saw the
        // bridge do. This catches "reload reported success but the member is missing" (a re-weave
        // that quietly fell back to source bytes), the exact failure shape of a missing sync.
        for (String[] pair : new String[][]{{"stage1", "NP-M1"}, {"stage2", "NP-M2"}}) {
            String state = reload.get(pair[0]);
            Boolean value = checks.get(pair[1]);
            if (state == null || value == null) {
                continue;
            }
            if ("success".equals(state) && !value) {
                invalid.add("SELF-CONTRADICTION=" + pair[0] + "-success-but-" + pair[1] + "-FAIL");
            }
            if (("rejected".equals(state) || "weave-failed".equals(state)) && value) {
                invalid.add("SELF-CONTRADICTION=" + pair[0] + "-" + state + "-but-" + pair[1] + "-PASS");
            }
        }

        if (!invalid.isEmpty()) {
            return new Result(2, "INVALID", new LinkedHashSet<>(invalid), reload);
        }
        if (failing.isEmpty()) {
            return new Result(0, "GREEN", failing, reload);
        }
        return new Result(1, "RED", failing, reload);
    }

    static final class Result {
        final int exitCode;
        final String verdict;
        final Set<String> reasons;
        final Map<String, String> reload;

        Result(int exitCode, String verdict, Set<String> reasons, Map<String, String> reload) {
            this.exitCode = exitCode;
            this.verdict = verdict;
            this.reasons = reasons;
            this.reload = reload;
        }

        String line() {
            StringBuilder sb = new StringBuilder("[NP] VERDICT ").append(verdict);
            if (!reasons.isEmpty()) {
                sb.append(" reasons=").append(String.join(",", reasons));
            }
            sb.append(" reload=").append(reload);
            return sb.toString();
        }
    }
}
