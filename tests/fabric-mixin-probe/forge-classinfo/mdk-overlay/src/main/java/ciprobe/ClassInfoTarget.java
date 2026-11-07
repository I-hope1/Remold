package ciprobe;

/**
 * Mixin target class. It is <b>never</b> edited by the probe: only the Mixin template changes
 * between stages, so every observed difference comes from the re-weave path, not from InitFix
 * field patching on the target.
 *
 * <p>All observation channels are static fields so the probe can read them without touching
 * reflective metadata (which is stale after a structural redefine on JBR — see
 * {@code docs/initfix/07-p05-probe-results.md}).</p>
 */
public class ClassInfoTarget {
    /** Invocations of the baseline {@code @Inject} handler (exists in Mixin v1). */
    public static volatile int hits1;
    /** Invocations of the {@code @Inject} handler added by Mixin v2. */
    public static volatile int hits2;
    /** Invocations of the pre-existing handler of the second template (baseline of stage 2). */
    public static volatile int hits3;
    /** Value of the baseline {@code @Unique} field seen by handler 1; 123 after any weave. */
    public static volatile int uniqueSeen1 = -1;
    /** Value of the {@code @Unique} field added by the stage 2 template (456). */
    public static volatile int uniqueSeen3 = -1;

    public ClassInfoTarget() {
    }

    /** Injected target method: all observable state is written by the Mixin handlers. */
    public void onReload() {
        // no-op body; behaviour comes entirely from the woven Mixin handlers
    }
}
