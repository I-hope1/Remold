package remold.mixin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.reflect.mixin.LoadedTargetSuppressor;
import remold.reflect.mixin.MixinConfigContext;
import remold.reflect.mixin.MixinConfigDiffer;

/**
 * Fabric 侧的薄适配：把织入器、ClassNode 解析、{@link FabricLauncherSpoof} 与目标重织入回调
 * 交给 {@link MixinConfigDiffer}。Mixin 内部结构访问全部下沉到 core 的反射引擎。
 */
final class MixinConfigDiff {

	private MixinConfigDiff() {
	}

	static boolean isRemovedFromConfig(String dotClassName) {
		return MixinConfigDiffer.isRemovedFromConfig(dotClassName);
	}

	static void markReaddedToConfig(String dotClassName) {
		MixinConfigDiffer.markReaddedToConfig(dotClassName);
	}

	static void onAssetsChanged(Set<java.nio.file.Path> changedAssets) {
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer == null) {
			return;
		}
		MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
		String side = environment == null ? null : environment.getSide().name();
		MixinConfigDiffer.onAssetsChanged(changedAssets, new MixinConfigContext(
			transformer,
			side,
			MixinConfigDiff::loadClassNode,
			FabricSuppressor.INSTANCE,
			MixinHotSwap::scheduleTargets));
	}

	private static Object loadClassNode(String dotClassName) {
		try {
			InputStream in = MixinService.getService().getResourceAsStream(
				dotClassName.replace('.', '/') + ".class");
			if (in == null) {
				return null;
			}
			try (InputStream resource = in) {
				ClassNode node = new ClassNode();
				new ClassReader(resource).accept(node, 0);
				return node;
			}
		} catch (Throwable t) {
			return null;
		}
	}

	/** 保留测试使用的 JSON 数组解析入口。 */
	static List<String> quotedArray(String json, String key) {
		return MixinConfigDiffer.quotedArray(json, key);
	}

	/** 保留给 Fabric 侧诊断的声明解析入口。 */
	static Set<String> declaredMixinNames(String json) {
		MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
		String side = environment == null ? null : environment.getSide().name();
		return MixinConfigDiffer.declaredMixinNames(json, side);
	}

	private enum FabricSuppressor implements LoadedTargetSuppressor {
		INSTANCE;

		@Override
		public Object suppress(Collection<String> dottedTargets) {
			return FabricLauncherSpoof.suppress(dottedTargets);
		}

		@Override
		public void restore(Object token) {
			FabricLauncherSpoof.restore(token);
		}
	}
}
