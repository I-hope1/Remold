package neprobe;

/**
 * Mixin target class.
 *
 * <p>It is <b>never</b> edited by the probe: only the Mixin templates change between stages, so
 * every observed difference comes from the re-weave path, not from InitFix field patching on the
 * target.</p>
 *
 * <p>All observation channels are {@code static volatile} fields: the probe reads them from the
 * same class (same class loader), so the measured value is the business end state the woven
 * handlers wrote — never a reflective-metadata echo.</p>
 */
public class ProbeTarget {
    /** Invocations of the baseline {@code @Inject} handler (exists in Mixin v1). */
    public static volatile int hits1;
    /** Invocations of the {@code @Inject} handler added by Mixin v2 (stage 1). */
    public static volatile int hits2;
    /** Invocations of the pre-existing handler of the second template (baseline of stage 2). */
    public static volatile int hits3;
    /** Value of the baseline {@code @Unique uniqueA} read by handler 1; 123 after any weave. */
    public static volatile int seen1 = -1;
    /** Value of the {@code @Unique ciAdded} field added by the stage 2 template (456). */
    public static volatile int seen3 = -1;
    /** Invocations of the handler added at runtime by the config-diff probe. */
    public static volatile int hits4;

    public ProbeTarget() {
    }

    /** Injected target method: all observable state is written by the Mixin handlers. */
    public String onReload() {
        return "T0";
    }
}
