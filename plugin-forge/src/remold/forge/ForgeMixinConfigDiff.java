package remold.forge;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import remold.RemoldAgent;
import remold.reflect.mixin.MixinClassByteSources;
import remold.reflect.mixin.MixinConfigContext;
import remold.reflect.mixin.MixinConfigDiffer;

/**
 * Forge / NeoForge 侧的 Mixin 配置差异适配器：把跨 ClassLoader 的织入器、ASM ClassNode、
 * ModLauncher 类跟踪器抑制器与目标重织入回调交给 core 的反射引擎。
 *
 * <p>抑制器只以方法引用（{@code Function} / {@code Consumer}）传入，本类不 {@code implements}
 * 任何 core 类型：core jar 版本错位时退化为“抑制不可用”的可诊断降级，而不是类链接期的
 * {@code NoClassDefFoundError}。</p>
 */
final class ForgeMixinConfigDiff {

	/** 诊断用：本加载器的“目标类已加载”检查点。 */
	static final String LOADER_HOOK = "ModLauncher MixinServiceModLauncher ModLauncherClassTracker.loadedClasses";

	private ForgeMixinConfigDiff() {
	}

	static void onAssetsChanged(Set<Path> changedAssets, Object transformer,
			ForgeMixinHotSwap.InstrumentationHolder inst) {
		if (transformer == null) {
			return;
		}
		try {
			ModLauncherClassTrackerSpoof spoof = new ModLauncherClassTrackerSpoof(transformer);
			MixinConfigDiffer.onAssetsChanged(changedAssets, new MixinConfigContext(
				transformer,
				currentSide(transformer),
				dotClassName -> loadClassNode(transformer, dotClassName),
				spoof::suppress,
				spoof::restore,
				LOADER_HOOK,
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
	 *
	 * <p>字节来源两档：{@link MixinClassByteSources}（Remold 监控通道，覆盖"资源回调先于 class 批次"
	 * 的时序断层与 IDE 多输出目录的路径断层）→ Mixin 自己的资源流（jar 内类兜底）。
	 * 两档都没有才返回 {@code null}，由 core 侧 fail-closed 并给出指向性诊断。</p>
	 *
	 * <p><b>已知边界（不假装覆盖）</b>：Forge / NeoForge 侧没有 {@code MixinBytecodeOverlay} 那层
	 * {@code IClassBytecodeProvider} 覆盖（ModLauncher 的 Mixin 服务实例在另一个 ClassLoader 里，
	 * 代理替换需要按加载器分别安装，且插件 jar 与那边的 Mixin 服务类型不可硬链接），因此这里
	 * <b>只做</b> {@link MixinClassByteSources#register} 的 core 侧登记：core 的 Nest 扫描与成员
	 * 可见性判定能看到新类，但 Mixin 自己的 {@code ClassInfo}/{@code InnerClassGenerator} 仍看不到
	 * 新增的 {@code $N} 内部类。带内部类的新 Mixin 在 Forge 上因此可能仍需一次重启；
	 * 失败形态由 core 侧登记失败诊断点名，不会静默。</p>
	 */
	private static Object loadClassNode(Object transformer, String dotClassName) {
		if (transformer == null || dotClassName == null) {
			return null;
		}
		try {
			byte[] bytes = MixinClassByteSources.lookup(dotClassName);
			if (bytes == null) {
				bytes = readServiceBytes(transformer, dotClassName.replace('.', '/') + ".class");
			}
			if (bytes == null) {
				return null;
			}
			MixinClassByteSources.register(dotClassName, bytes);
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

	private static byte[] readServiceBytes(Object transformer, String resource) throws Exception {
		try (InputStream stream = openResource(transformer, resource)) {
			return stream == null ? null : stream.readAllBytes();
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
