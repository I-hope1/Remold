import java.lang.instrument.Instrumentation;

/**
 * Minimal agent for {@link P05Probe}.
 *
 * <p>Deliberately installs no transformers and no Remold features: the P0.5 measurements are about
 * raw JVM/DCEVM capability, so Remold's own LayoutGate / InitFix / Mindustry hooks must not
 * interfere with the redefinitions under test.</p>
 */
public class P05Agent {

	private static volatile Instrumentation instrumentation;

	public static void premain(String args, Instrumentation inst) {
		instrumentation = inst;
	}

	public static Instrumentation instrumentation() {
		return instrumentation;
	}
}
