package remold.mixin;

import java.util.List;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Fabric 侧薄适配：把 {@link IMixinTransformer} 与 ASM {@link ClassNode} 交给 core 的反射登记器。
 *
 * <p>抑制器以方法引用传入（{@code Function} / {@code Consumer}），本类不 {@code implements}
 * 任何 core 类型：core jar 版本错位时退化为“抑制不可用”的可诊断降级，而不是类链接期的
 * {@code NoClassDefFoundError}。</p>
 */
final class NewMixinRegistrar {

	private NewMixinRegistrar() {
	}

	static List<String> tryRegister(IMixinTransformer transformer, String dotClassName, ClassNode node) {
		return remold.reflect.mixin.NewMixinRegistrar.tryRegister(
			transformer, dotClassName, node,
			FabricLauncherSpoof::suppress, FabricLauncherSpoof::restore,
			MixinConfigDiff.LOADER_HOOK);
	}
}
