package remold.util;

import remold.RemoldAgent;

/**
 * JBR/DCEVM {@code redefineClasses} rewrites {@code Class} references held in <b>static fields</b>
 * to an old-version "shadow" {@code Class} object (same name and loader, old field metadata;
 * {@code LibTool.getInstances(shadow)} returns 0 and {@code retransformClasses(shadow)} silently
 * does nothing). Heap instance fields, array elements and map/set keys are not rewritten.
 *
 * <p>Any external {@code Class} argument that may have been held in a static field across a
 * redefine must be normalized before it is used as a JVMTI class, a retransform target, or a
 * profiler target-table key.</p>
 */
public final class ClassIdentity {
	private ClassIdentity() {}

	/**
	 * Returns the canonical {@code Class} currently registered for this name/loader.
	 * Primitive, array and hidden classes are returned unchanged (hidden class names contain
	 * {@code '/'} and cannot be looked up by name).
	 */
	public static Class<?> canonicalize(Class<?> clazz) {
		if (clazz == null) return null;
		if (clazz.isPrimitive() || clazz.isArray()) return clazz;
		String name = clazz.getName();
		if (name.indexOf('/') >= 0) return clazz;
		try {
			Class<?> canonical = Class.forName(name, false, clazz.getClassLoader());
			if (canonical != clazz) {
				RemoldAgent.warn("[SHADOW-CLASS] normalized stale Class ref for " + name
					+ " (shadow=" + System.identityHashCode(clazz)
					+ ", canonical=" + System.identityHashCode(canonical) + ")");
			}
			return canonical;
		} catch (Throwable t) {
			return clazz;
		}
	}
}
