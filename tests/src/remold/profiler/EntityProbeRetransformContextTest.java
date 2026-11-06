package remold.profiler;

import org.junit.jupiter.api.Test;
import remold.RemoldAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity-probe toggle retransforms already-loaded classes to install profiling probes.
 * That retransform is <b>not</b> a Remold hot-swap: the classfile buffer the JVM hands to
 * {@code AnnotationTransformer} is already woven (Mixin {@code @Unique} fields and handlers
 * included), so letting it run through the external-redefine channel re-weaves it and appends
 * hash-mangled duplicate {@code @Unique} fields to the baseline cache. The next real hot-swap
 * then fails LayoutGate with {@code field(s) removed}.
 *
 * <p>This test pins the fix at the seam: {@code toggleEntityProbe} must run its retransform
 * inside {@link RemoldAgent#enterInternalRetransform}, which makes the transformer pass the
 * class through untouched.</p>
 */
public class EntityProbeRetransformContextTest {

	static class Target {
		void probeMe() {
		}
	}

	@Test
	void entityProbeRetransformRunsAsInternalRetransform() {
		List<Boolean> contextWasInternalRetransform = new ArrayList<>();
		List<Class<?>[]> retransformed = new ArrayList<>();

		Instrumentation fake = (Instrumentation) Proxy.newProxyInstance(
			Instrumentation.class.getClassLoader(),
			new Class<?>[]{Instrumentation.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "retransformClasses": {
						RemoldAgent.InternalRedefineContext ctx = RemoldAgent.INTERNAL_CONTEXT.get();
						contextWasInternalRetransform.add(ctx != null && ctx.isRetransform);
						retransformed.add((Class<?>[]) args[0]);
						return null;
					}
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

		DynamicProfilerAPI.toggleEntityProbe(fake, Target.class, "probeMe");

		assertEquals(1, contextWasInternalRetransform.size(), "retransformClasses must be called exactly once");
		assertTrue(contextWasInternalRetransform.get(0),
			"entity-probe retransform must run inside an internal retransform context; otherwise"
				+ " AnnotationTransformer re-weaves the already-woven Mixin target");
		assertArrayEquals(new Class<?>[]{Target.class}, retransformed.get(0),
			"the retransform set must be the loaded subclasses of the base class");
		assertNull(RemoldAgent.INTERNAL_CONTEXT.get(), "the internal context must be cleared afterwards");
	}
}
