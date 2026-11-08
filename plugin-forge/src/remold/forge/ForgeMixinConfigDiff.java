package remold.forge;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import remold.RemoldAgent;
import remold.reflect.mixin.MixinConfigContext;
import remold.reflect.mixin.MixinConfigDiffer;

/**
 * Forge / NeoForge 侧的 Mixin 配置差异适配器：把跨 ClassLoader 的织入器、ASM ClassNode、
 * ModLauncher 类跟踪器抑制器与目标重织入回调交给 core 的反射引擎。
 */
final class ForgeMixinConfigDiff {

	private ForgeMixinConfigDiff() {
	}

	static void onAssetsChanged(Set<Path> changedAssets, Object transformer,
			ForgeMixinHotSwap.InstrumentationHolder inst) {
		if (transformer == null) {
			return;
		}
		try {
			MixinConfigDiffer.onAssetsChanged(changedAssets, new MixinConfigContext(
				transformer,
				currentSide(transformer),
				dotClassName -> loadClassNode(transformer, dotClassName),
				new ModLauncherClassTrackerSpoof(transformer),
				targets -> scheduleTargets(targets, inst)));
		} catch (Throwable t) {
			RemoldAgent.warn("[Forge/NeoForge] Failed to apply mixin config change: " + t);
		}
	}

	private static void scheduleTargets(List<String> targets, ForgeMixinHotSwap.InstrumentationHolder inst) {
		if (inst == null || targets == null || targets.isEmpty()) {
			return;
		}
		ForgeMixinHotSwap.scheduleTargets(targets, inst);
	}

	private static String currentSide(Object transformer) {
		try {
			ClassLoader loader = transformer.getClass().getClassLoader();
			Class<?> environmentClass = Class.forName(
				"org.spongepowered.asm.mixin.MixinEnvironment", true, loader);
			Object environment = environmentClass.getMethod("getCurrentEnvironment").invoke(null);
			Object side = environmentClass.getMethod("getSide").invoke(environment);
			return side == null ? null : side.toString();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * 用 Transformer 所在 ClassLoader 的 ASM 读取 ClassNode，避免把 Agent 侧的 ASM 类型
	 * 传给另一个 ClassLoader 里的 Mixin。
	 */
	private static Object loadClassNode(Object transformer, String dotClassName) {
		if (transformer == null || dotClassName == null) {
			return null;
		}
		try (InputStream resource = openResource(transformer, dotClassName.replace('.', '/') + ".class")) {
			if (resource == null) {
				return null;
			}
			byte[] bytes = resource.readAllBytes();
			ClassLoader loader = transformer.getClass().getClassLoader();
			Class<?> readerType = Class.forName("org.objectweb.asm.ClassReader", true, loader);
			Class<?> visitorType = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
			Class<?> nodeType = Class.forName("org.objectweb.asm.tree.ClassNode", true, loader);
			Object node = nodeType.getConstructor().newInstance();
			Object reader = readerType.getConstructor(byte[].class).newInstance((Object) bytes);
			readerType.getMethod("accept", visitorType, int.class).invoke(reader, node, 0);
			return node;
		} catch (Throwable t) {
			RemoldAgent.warn("[Forge/NeoForge] Unable to read ClassNode for " + dotClassName + ": " + t);
			return null;
		}
	}

	private static InputStream openResource(Object transformer, String resource) {
		try {
			ClassLoader loader = transformer.getClass().getClassLoader();
			Class<?> serviceClass = Class.forName("org.spongepowered.asm.service.MixinService", true, loader);
			Object service = serviceClass.getMethod("getService").invoke(null);
			Method getResource = service.getClass().getMethod("getResourceAsStream", String.class);
			return (InputStream) getResource.invoke(service, resource);
		} catch (Throwable ignored) {
			ClassLoader loader = transformer == null ? null : transformer.getClass().getClassLoader();
			return loader == null ? null : loader.getResourceAsStream(resource);
		}
	}
}
