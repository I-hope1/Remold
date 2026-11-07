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
 * Verdict oracle for the Forge ClassInfo member-sync probe.
 *
 * <p>Reads a probe evidence file and separates three questions that a raw log cannot:</p>
 * <ol>
 *   <li><b>Preconditions</b> — was the target really woven at baseline ({@code CI-BASE}) and was a
 *       Mixin template reload actually attempted for each stage ({@code [CI-DRIVER] phase=…})?
 *       If not, the run is {@code INVALID}: "nothing happened" must never be reported as a red
 *       (or green) result.</li>
 *   <li><b>Verdict</b> — {@code GREEN} iff every member check passed, else {@code RED} with the
 *       exact failing check ids.</li>
 *   <li><b>Attribution</b> — {@code INVALID} also covers self-contradictory evidence (a green
 *       check while the driver observed a rejected reload, or vice versa), so the evidence cannot
 *       claim a mechanism that the same log denies.</li>
 * </ol>
 *
 * <p>Exit codes: {@code 0} = GREEN, {@code 1} = RED, {@code 2} = INVALID. The same oracle is run
 * against {@code fixtures/} by {@code verify-evidence.sh} to prove it still discriminates.</p>
 */
public final class Verdict {
    private static final String[] MEMBER_CHECKS = {"CI-M1", "CI-M2", "CI-PRESERVE-1", "CI-PRESERVE-2"};

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
        int ci = line.indexOf("[CI] CHECK ");
        if (ci >= 0) {
            return line.substring(ci);
        }
        int drv = line.indexOf("[CI-DRIVER] ");
        if (drv >= 0) {
            return line.substring(drv);
        }
        return line.strip();
    }

    static Result evaluate(List<String> lines) {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        Map<String, String> reload = new LinkedHashMap<>();
        for (String raw : lines) {
            // Server console output is colourised and Gradle prefixes task output, so locate the
            // marker anywhere in the line instead of requiring a clean prefix.
            String line = markerTail(raw);
            if (line.startsWith("[CI] CHECK ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 4 && ("PASS".equals(parts[3]) || "FAIL".equals(parts[3]))) {
                    checks.put(parts[2], "PASS".equals(parts[3]));
                }
            } else if (line.startsWith("[CI-DRIVER] ")) {
                String phase = null;
                String state = null;
                for (String kv : line.substring("[CI-DRIVER] ".length()).split("\\s+")) {
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
        }

        Set<String> invalid = new LinkedHashSet<>();
        Boolean baseline = checks.get("CI-BASE");
        if (!Boolean.TRUE.equals(baseline)) {
            invalid.add("PRECOND-CI-BASE=" + (baseline == null ? "missing" : "FAIL"));
        }
        for (String phase : new String[]{"stage1", "stage2"}) {
            String state = reload.get(phase);
            if (state == null) {
                invalid.add("PRECOND-RELOAD-" + phase + "=missing");
            } else if ("not-attempted".equals(state) || "unknown".equals(state)) {
                invalid.add("PRECOND-RELOAD-" + phase + "=" + state);
            }
        }

        Set<String> failing = new LinkedHashSet<>();
        for (String id : MEMBER_CHECKS) {
            Boolean value = checks.get(id);
            if (value == null) {
                failing.add(id + "(missing)");
            } else if (!value) {
                failing.add(id);
            }
        }

        boolean observedRejected = reload.containsValue("rejected");
        boolean observedSuccess = reload.containsValue("success");
        if (observedRejected && failing.isEmpty()) {
            invalid.add("SELF-CONTRADICTION=green-checks-with-rejected-reload");
        }
        if (!observedRejected && observedSuccess && !failing.isEmpty()) {
            invalid.add("SELF-CONTRADICTION=failing-checks-with-successful-reload");
        }

        // Per-stage consistency: each stage's member check must agree with what the driver saw the
        // bridge do. This catches the "reload reported success but the member is missing" case,
        // which is the exact failure shape of a re-weave that quietly fell back to source bytes.
        for (String[] pair : new String[][]{{"stage1", "CI-M1"}, {"stage2", "CI-M2"}}) {
            String state = reload.get(pair[0]);
            Boolean value = checks.get(pair[1]);
            if (state == null || value == null) {
                continue;
            }
            boolean success = "success".equals(state);
            boolean attemptedAndRejected = "rejected".equals(state) || "weave-failed".equals(state);
            if (success && !value) {
                invalid.add("SELF-CONTRADICTION=" + pair[0] + "-success-but-" + pair[1] + "-FAIL");
            }
            if (attemptedAndRejected && value) {
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
            StringBuilder sb = new StringBuilder("[CI] VERDICT ").append(verdict);
            if (!reasons.isEmpty()) {
                sb.append(" reasons=").append(String.join(",", reasons));
            }
            sb.append(" reload=").append(reload);
            return sb.toString();
        }
    }
}
