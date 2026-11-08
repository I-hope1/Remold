package remold.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import remold.RemoldAgent;

/**
 * 临时让 Fabric 的 {@code isClassLoaded(target)} 返回 false 的窄窗口工具。
 *
 * <p>Mixin 的 {@code MixinInfo} 构造器在目标类已加载时会抛
 * {@code MixinTargetAlreadyLoadedException}。这是 Mixin 的设计前提（新 Mixin 必须在目标类
 * 首次加载前注册），但对开发期热加 Mixin 来说目标类通常已经加载。本类只在
 * {@code MixinConfig.prepare} 的极短窗口内，把 Fabric launcher 换成一个委托代理：
 * 对本次目标类回答“未加载”，其它调用全部原样委托，构造完立即恢复原 launcher。</p>
 *
 * <p><b>安全边界</b>：只在 Fabric 上可用（找不到 launcher 时返回 {@code null}，调用方
 * fail-closed 到重启提示）；代理窗口内如果出现非目标类加载查询，仍走原始 launcher。恢复
 * 放在 {@code finally} 中，异常路径不会留下假 launcher。</p>
 */
final class FabricLauncherSpoof {

	private static final Object LOCK = new Object();

	private FabricLauncherSpoof() {
	}

	/**
	 * 开始一个抑制窗口。
	 *
	 * @param targets 已加载、需要被临时报告为“未加载”的点分类名
	 * @return 恢复令牌；Fabric launcher 不可用时返回 {@code null}
	 */
	static Object suppress(Collection<String> targets) {
		if (targets == null || targets.isEmpty()) {
			return null;
		}
		try {
			Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false,
				FabricLauncherSpoof.class.getClassLoader());
			Class<?> launcherType = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher", false,
				FabricLauncherSpoof.class.getClassLoader());
			Field field = base.getDeclaredField("launcher");
			field.setAccessible(true);
			synchronized (LOCK) {
				Object current = field.get(null);
				if (current == null) {
					return null;
				}
				Set<String> names = new LinkedHashSet<>(targets);
				InvocationHandler handler = (proxy, method, args) -> {
					if (method.getName().equals("isClassLoaded") && args != null && args.length == 1
						&& args[0] instanceof String name && names.contains(name)) {
						return Boolean.FALSE;
					}
					try {
						return method.invoke(current, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				};
				Object proxy = Proxy.newProxyInstance(launcherType.getClassLoader(),
					new Class<?>[]{launcherType}, handler);
				field.set(null, proxy);
				return current;
			}
		} catch (Throwable t) {
			// 非 Fabric、JPMS 阻止或字段改名：调用方收不到令牌后会退化为“重启”提示。
			if (RemoldAgent.DEBUG) {
				RemoldAgent.log("[Mixin] Fabric launcher spoof unavailable: " + t);
			}
			return null;
		}
	}

	/** 恢复 {@link #suppress(Collection)} 返回的令牌对应的 launcher。 */
	static void restore(Object token) {
		if (token == null) {
			return;
		}
		try {
			Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false,
				FabricLauncherSpoof.class.getClassLoader());
			Field field = base.getDeclaredField("launcher");
			field.setAccessible(true);
			synchronized (LOCK) {
				field.set(null, token);
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to restore the Fabric launcher after new-mixin registration: " + t);
		}
	}
}
