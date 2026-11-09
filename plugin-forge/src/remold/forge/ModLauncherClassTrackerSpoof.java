package remold.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import remold.RemoldAgent;

/**
 * ModLauncher / NeoForge 的“目标类已加载”检查抑制器。
 *
 * <p>Forge / NeoForge 的 Mixin 服务是 {@code MixinServiceModLauncher}，其
 * {@code getClassTracker()} 返回 {@code ModLauncherClassTracker}；后者用 private 的
 * {@code loadedClasses} 集合记录已加载类。运行期新增 Mixin 的 {@code prepare()} 窗口内，
 * 把本次目标名从该集合临时摘除，结束后在 {@code finally} 中完整加回。</p>
 *
 * <p><b>线程安全</b>：所有读写都与 Mixin 自己一样以 {@code synchronized (loadedClasses)} 为界；
 * 摘除/恢复共用同一把锁，不会与类加载线程并发看到半截集合。</p>
 *
 * <p><b>失败策略</b>：字段不存在、类型不符或服务不可达时返回 {@code null}，调用方必须
 * fail-closed（提示重启），不得让 {@code prepare()} 在真实已加载目标上继续。</p>
 *
 * <p><b>为什么不是接口实现</b>：本类只把 {@link #suppress} / {@link #restore} 作为方法引用交给
 * core 的 {@code Function} / {@code Consumer} 参数，不 {@code implements} 任何 core 类型。插件 jar
 * 与 core jar 版本错位时，硬链接可选 core 类型会在类链接期抛 {@code NoClassDefFoundError}；
 * 类型擦除后同一场景只退化为“抑制不可用”，由 core 输出指向性诊断。</p>
 */
final class ModLauncherClassTrackerSpoof {

	private final Object transformer;
	private final Object trackerOverride;

	ModLauncherClassTrackerSpoof(Object transformer) {
		this(transformer, null);
	}

	/** 测试用：直接注入 tracker，绕过 MixinService 的 ClassLoader 解析。 */
	ModLauncherClassTrackerSpoof(Object transformer, Object trackerOverride) {
		this.transformer = transformer;
		this.trackerOverride = trackerOverride;
	}

	public Object suppress(Collection<String> dottedTargets) {
		if (dottedTargets == null || dottedTargets.isEmpty()) {
			return Token.EMPTY;
		}
		try {
			Object tracker = resolveClassTracker();
			if (tracker == null) {
				return null;
			}
			Field loadedField = findField(tracker.getClass(), "loadedClasses");
			if (loadedField == null) {
				return null;
			}
			loadedField.setAccessible(true);
			Object loadedObject = loadedField.get(tracker);
			if (!(loadedObject instanceof Set<?> loaded)) {
				return null;
			}
			@SuppressWarnings("unchecked")
			Set<String> loadedClasses = (Set<String>) loaded;
			List<String> removed = new ArrayList<>();
			synchronized (loadedClasses) {
				for (String target : dottedTargets) {
					if (target != null && loadedClasses.remove(target)) {
						removed.add(target);
					}
				}
			}
			return new Token(loadedClasses, removed);
		} catch (Throwable t) {
			RemoldAgent.warn("[Forge/NeoForge] Unable to suppress ModLauncher class tracker: "
				+ describe(t));
			return null;
		}
	}

	public void restore(Object token) {
		if (!(token instanceof Token state) || state.loadedClasses == null) {
			return;
		}
		synchronized (state.loadedClasses) {
			state.loadedClasses.addAll(state.removed);
		}
	}

	private Object resolveClassTracker() throws Exception {
		if (trackerOverride != null) {
			return trackerOverride;
		}
		// 从 Transformer 的 ClassLoader 找 MixinService（Forge/NeoForge 的 Mixin 位于
		// ModLauncher/ModuleLauncher 独立 ClassLoader，Agent 系统 ClassLoader 不可见）。
		if (transformer == null) {
			return null;
		}
		ClassLoader transformerLoader = transformer.getClass().getClassLoader();
		Class<?> serviceClass = Class.forName("org.spongepowered.asm.service.MixinService", true, transformerLoader);
		Object service = serviceClass.getMethod("getService").invoke(null);
		if (service == null) {
			return null;
		}
		Method getTracker = service.getClass().getMethod("getClassTracker");
		return getTracker.invoke(service);
	}

	private static Field findField(Class<?> clazz, String name) {
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder(t.getClass().getName());
		if (t.getMessage() != null) {
			sb.append(": ").append(t.getMessage());
		}
		return sb.toString();
	}

	private record Token(Set<String> loadedClasses, List<String> removed) {
		private static final Token EMPTY = new Token(null, List.of());
	}
}
