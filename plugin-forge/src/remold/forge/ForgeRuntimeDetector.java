package remold.forge;

import java.lang.instrument.Instrumentation;

/**
 * Minecraft Forge 与 NeoForge 运行时环境探测工具。
 */
public final class ForgeRuntimeDetector {

	private ForgeRuntimeDetector() {}

	/** 判断当前 JVM 是否运行在 NeoForge 环境中 */
	public static boolean isNeoForge(Instrumentation inst) {
		return findClass("net.neoforged.bus.api.IEventBus", inst) != null
				|| findClass("net.neoforged.fml.ModList", inst) != null;
	}

	/** 判断当前 JVM 是否运行在 Minecraft Forge (旧版/标准) 环境中 */
	public static boolean isForge(Instrumentation inst) {
		return findClass("net.minecraftforge.eventbus.api.IEventBus", inst) != null
				|| findClass("net.minecraftforge.fml.ModList", inst) != null;
	}

	/**
	 * 在多层 ClassLoader（如 ModLauncher TransformingClassLoader / NeoForge ModularURLClassLoader）中
	 * 查找指定类。
	 *
	 * @param dotName 类全限定名（如 {@code org.spongepowered.asm.mixin.MixinEnvironment}）
	 * @param inst JVM Instrumentation（可为 null）
	 * @return 找到的 Class 对象，找不到则返回 null
	 */
	public static Class<?> findClass(String dotName, Instrumentation inst) {
		if (dotName == null) return null;

		// 1. 尝试当前线程上下文类加载器（游戏主线程通常设为游戏类加载器）
		ClassLoader tccl = Thread.currentThread().getContextClassLoader();
		if (tccl != null) {
			try {
				return Class.forName(dotName, false, tccl);
			} catch (Throwable ignored) {}
		}

		// 2. 尝试系统类加载器
		try {
			return Class.forName(dotName, false, ClassLoader.getSystemClassLoader());
		} catch (Throwable ignored) {}

		// 3. 遍历已被 JVM 加载的所有类（穿透所有 ClassLoader）
		if (inst != null) {
			try {
				for (Class<?> loaded : inst.getAllLoadedClasses()) {
					if (dotName.equals(loaded.getName())) {
						return loaded;
					}
				}
			} catch (Throwable ignored) {}
		}

		return null;
	}
}
