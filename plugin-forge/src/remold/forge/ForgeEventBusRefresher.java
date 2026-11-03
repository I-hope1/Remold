package remold.forge;

import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import remold.HotswapBatchListener;
import remold.RemoldAgent;

/**
 * Minecraft Forge 与 NeoForge 事件总线 (EventBus) 监听器动态刷新器。
 *
 * <p>在类完成热重定义后，自动检测重定义类中是否声明了 {@code @SubscribeEvent} 监听方法或
 * 标注了 {@code @EventBusSubscriber}。若存在，则向活动的 {@code IEventBus} 重新注册该类，
 * 使新增、修改的事件处理逻辑即时生效，而无需重启游戏。</p>
 */
public final class ForgeEventBusRefresher implements HotswapBatchListener {

	private final Instrumentation inst;

	public ForgeEventBusRefresher(Instrumentation inst) {
		this.inst = inst;
	}

	@Override
	public void afterInternalBatch(List<ClassDefinition> redefinedClasses) {
		if (redefinedClasses == null || redefinedClasses.isEmpty()) {
			return;
		}

		List<Class<?>> classes = new ArrayList<>(redefinedClasses.size());
		for (ClassDefinition cd : redefinedClasses) {
			if (cd != null && cd.getDefinitionClass() != null) {
				classes.add(cd.getDefinitionClass());
			}
		}

		refreshClasses(classes);
	}

	@Override
	public void onExternalClassRedefined(Class<?> clazz, byte[] wovenBytes) {
		if (clazz != null) {
			refreshClasses(Collections.singletonList(clazz));
		}
	}

	private void refreshClasses(List<Class<?>> classes) {
		if (classes == null || classes.isEmpty()) {
			return;
		}

		List<Class<?>> listenerClasses = new ArrayList<>();
		for (Class<?> clazz : classes) {
			if (hasEventListenerMethods(clazz)) {
				listenerClasses.add(clazz);
			}
		}

		if (listenerClasses.isEmpty()) {
			return;
		}

		// 获取所有活动的总线实例并刷新监听器
		List<Object> buses = findActiveEventBuses();
		if (buses.isEmpty()) {
			return;
		}

		for (Class<?> listenerClass : listenerClasses) {
			for (Object bus : buses) {
				tryRefreshBus(bus, listenerClass);
			}
		}
	}

	/** 探测类中是否含有 @SubscribeEvent 注解或 @EventBusSubscriber */
	private static boolean hasEventListenerMethods(Class<?> clazz) {
		if (clazz == null) return false;

		// 检查类注解
		for (Annotation ann : clazz.getAnnotations()) {
			String annName = ann.annotationType().getName();
			if (annName.endsWith(".EventBusSubscriber")) {
				return true;
			}
		}

		// 检查方法注解
		try {
			for (Method m : clazz.getDeclaredMethods()) {
				for (Annotation ann : m.getAnnotations()) {
					String annName = ann.annotationType().getName();
					if (annName.endsWith(".SubscribeEvent")) {
						return true;
					}
				}
			}
		} catch (Throwable ignored) {}

		return false;
	}

	/** 获取可用的事件总线对象列表（兼容 Forge 与 NeoForge） */
	private List<Object> findActiveEventBuses() {
		List<Object> buses = new ArrayList<>();

		// 1. 尝试 NeoForge.EVENT_BUS
		try {
			Class<?> neoForgeClass = ForgeRuntimeDetector.findClass("net.neoforged.neoforge.common.NeoForge", inst);
			if (neoForgeClass != null) {
				Field busField = neoForgeClass.getField("EVENT_BUS");
				Object bus = busField.get(null);
				if (bus != null) buses.add(bus);
			}
		} catch (Throwable ignored) {}

		// 2. 尝试 MinecraftForge.EVENT_BUS (Forge)
		try {
			Class<?> forgeClass = ForgeRuntimeDetector.findClass("net.minecraftforge.common.MinecraftForge", inst);
			if (forgeClass != null) {
				Field busField = forgeClass.getField("EVENT_BUS");
				Object bus = busField.get(null);
				if (bus != null) buses.add(bus);
			}
		} catch (Throwable ignored) {}

		return buses;
	}

	/** 在指定事件总线上注销并重新注册监听类 */
	private static void tryRefreshBus(Object bus, Class<?> listenerClass) {
		try {
			Method unregisterMethod = findMethod(bus.getClass(), "unregister", Object.class);
			if (unregisterMethod != null) {
				unregisterMethod.invoke(bus, listenerClass);
			}

			Method registerMethod = findMethod(bus.getClass(), "register", Object.class);
			if (registerMethod != null) {
				registerMethod.invoke(bus, listenerClass);
				RemoldAgent.info("[Forge/NeoForge] EventBus listeners successfully refreshed for class: " + listenerClass.getName());
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Forge/NeoForge] Failed to refresh EventBus listeners for " + listenerClass.getName() + ": " + t.getMessage());
		}
	}

	private static Method findMethod(Class<?> type, String name, Class<?>... paramTypes) {
		for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
			try {
				Method m = cur.getDeclaredMethod(name, paramTypes);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {}
		}
		for (Class<?> iface : type.getInterfaces()) {
			try {
				Method m = iface.getMethod(name, paramTypes);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {}
		}
		return null;
	}
}
