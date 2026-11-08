package remold.mixin;

import java.util.Collection;
import java.util.List;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.reflect.mixin.LoadedTargetSuppressor;

/**
 * Fabric 侧薄适配：把 {@link IMixinTransformer} 与 ASM {@link ClassNode} 交给 core 的反射登记器。
 */
final class NewMixinRegistrar {

	private NewMixinRegistrar() {
	}

	static List<String> tryRegister(IMixinTransformer transformer, String dotClassName, ClassNode node) {
		return remold.reflect.mixin.NewMixinRegistrar.tryRegister(
			transformer, dotClassName, node, FabricSuppressor.INSTANCE);
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
