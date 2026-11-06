package remold.profiler;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed entity-probe enable must not leave a half-registered target. JBR can throw
 * {@link ClassFormatError} (an {@link Error}, not an {@link Exception}) from
 * {@code retransformClasses}; the toggle must catch {@link Throwable}, report failure, and roll
 * back the {@link ProfilerTransformer#targetMethods} entry it just added.
 */
public class EntityProbeFailureRollbackTest {

	static class Target {
		void probeMe() {
		}
	}

	@Test
	void failedEnableRollsBackTargetRegistration() {
		Instrumentation fake = (Instrumentation) Proxy.newProxyInstance(
			Instrumentation.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "retransformClasses":
						throw new ClassFormatError("simulated invalid class file");
					case "getAllLoadedClasses":
						return new Class<?>[]{Target.class};
					case "isModifiableClass":
						return true;
					case "toString":
						return "fake-instrumentation";
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});

		boolean ok = DynamicProfilerAPI.toggleEntityProbe(fake, Target.class, "probeMe", true);

		assertFalse(ok, "a ClassFormatError from retransformClasses must be reported as failure");
		assertNull(ProfilerTransformer.targetMethods.get(Target.class),
			"a failed enable must remove the map entry entirely, not leave an empty set");
	}

	@Test
	void failedDisableKeepsRegistrationBecauseProbeBytesStayLive() {
		ProfilerTransformer.addTargetMethod(Target.class, "probeMe");
		try {
			Instrumentation fake = failingInstrumentation();
			boolean ok = DynamicProfilerAPI.toggleEntityProbe(fake, Target.class, "probeMe", false);
			assertFalse(ok, "a ClassFormatError from retransformClasses must be reported as failure");
			assertTrue(ProfilerTransformer.hasTargetMethod(Target.class, "probeMe"),
				"a failed disable must keep the registration: the probe bytecode is still live");
		} finally {
			ProfilerTransformer.clearTargetMethods(Target.class);
		}
	}

	private static Instrumentation failingInstrumentation() {
		return (Instrumentation) Proxy.newProxyInstance(
			Instrumentation.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "retransformClasses":
						throw new ClassFormatError("simulated invalid class file");
					case "getAllLoadedClasses":
						return new Class<?>[]{Target.class};
					case "isModifiableClass":
						return true;
					case "toString":
						return "fake-instrumentation";
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});
	}
}
