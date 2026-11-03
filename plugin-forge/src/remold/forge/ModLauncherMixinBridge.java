package remold.forge;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import remold.BytecodeTransformerBridge;
import remold.RemoldAgent;

/**
 * 穿透 ModLauncher (Forge) 与 ModuleLauncher (NeoForge) 类加载器层级的 Mixin 重织入桥。
 *
 * <p>在 Forge / NeoForge 中，SpongePowered Mixin 运行于专属的下层类加载器中（如 TransformingClassLoader）。
 * 本桥接器通过探测目标 ClassLoader 与 JVM 加载的类，动态穿透类加载器壁垒定位 {@code IMixinTransformer}，
 * 在热重定义前将源码级字节码重新交给 Mixin 织入，保全 {@code @Inject} 钩子与 {@code @Unique} 注入字段。</p>
 */
public final class ModLauncherMixinBridge implements BytecodeTransformerBridge {

	private static final byte[] MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;".getBytes(StandardCharsets.UTF_8);

	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private final Instrumentation inst;

	private volatile Object activeTransformer;
	private volatile Method transformMethod;
	private volatile boolean resolverFailed;

	public ModLauncherMixinBridge(Instrumentation inst) {
		this.inst = inst;
	}

	@Override
	public byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode) {
		if (dotClassName == null || bytecode == null) {
			return bytecode;
		}
		if (looksLikeMixin(bytecode)) {
			return bytecode;
		}

		Object transformer = resolveTransformer(loader);
		if (transformer == null || transformMethod == null) {
			return bytecode;
		}

		try {
			byte[] woven = (byte[]) transformMethod.invoke(transformer, dotClassName, dotClassName, bytecode);
			return woven != null ? woven : bytecode;
		} catch (Throwable t) {
			reportOnce(dotClassName, "[Forge/NeoForge] Mixin re-weaving failed; falling back to source bytecode", t);
			return bytecode;
		}
	}

	/** 动态穿透类加载器定位活动的 IMixinTransformer 实例及 transformClassBytes 方法 */
	private Object resolveTransformer(ClassLoader preferredLoader) {
		if (activeTransformer != null) {
			return activeTransformer;
		}
		if (resolverFailed) {
			return null;
		}

		synchronized (this) {
			if (activeTransformer != null) return activeTransformer;
			if (resolverFailed) return null;

			try {
				Class<?> envClass = null;
				if (preferredLoader != null) {
					try {
						envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", false, preferredLoader);
					} catch (Throwable ignored) {}
				}
				if (envClass == null) {
					envClass = ForgeRuntimeDetector.findClass("org.spongepowered.asm.mixin.MixinEnvironment", inst);
				}
				if (envClass == null) {
					return null;
				}

				Method getCurrentEnv = envClass.getMethod("getCurrentEnvironment");
				Object env = getCurrentEnv.invoke(null);
				Method getActiveTransformer = envClass.getMethod("getActiveTransformer");
				Object trans = getActiveTransformer.invoke(env);

				if (trans == null) {
					Method getDefaultEnv = envClass.getMethod("getDefaultEnvironment");
					Object defaultEnv = getDefaultEnv.invoke(null);
					trans = getActiveTransformer.invoke(defaultEnv);
				}

				if (trans != null) {
					Method method = trans.getClass().getMethod("transformClassBytes", String.class, String.class, byte[].class);
					method.setAccessible(true);
					this.transformMethod = method;
					this.activeTransformer = trans;
					RemoldAgent.info("[Forge/NeoForge] Successfully acquired Mixin transformer across ClassLoader: " + trans.getClass().getName());
					return activeTransformer;
				}
			} catch (Throwable t) {
				resolverFailed = true;
				reportOnce("<resolver>", "[Forge/NeoForge] Unable to resolve Mixin transformer across ClassLoader", t);
			}
		}

		return null;
	}

	private static boolean looksLikeMixin(byte[] bytes) {
		int last = bytes.length - MIXIN_DESC.length;
		outer:
		for (int i = 0; i <= last; i++) {
			for (int j = 0; j < MIXIN_DESC.length; j++) {
				if (bytes[i + j] != MIXIN_DESC[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static void reportOnce(String key, String message, Throwable t) {
		if (REPORTED.add(key)) {
			RemoldAgent.warn(message + ": " + key + " (" + t + ")");
		}
	}
}
