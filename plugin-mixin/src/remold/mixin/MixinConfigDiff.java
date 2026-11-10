package remold.mixin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.reflect.mixin.MixinConfigContext;
import remold.reflect.mixin.MixinConfigDiffer;

/**
 * Fabric 侧的薄适配：把织入器、ClassNode 解析、{@link FabricLauncherSpoof} 与目标重织入回调
 * 交给 {@link MixinConfigDiffer}。Mixin 内部结构访问全部下沉到 core 的反射引擎。
 *
 * <p>抑制器只以方法引用（{@code Function} / {@code Consumer}）传入，本类不 {@code implements}
 * 任何 core 类型：core jar 版本错位时最坏退化为“抑制不可用”的可诊断降级，而不是类链接期的
 * {@code NoClassDefFoundError}。</p>
 */
final class MixinConfigDiff {

	/** 诊断用：本加载器的“目标类已加载”检查点。 */
	static final String LOADER_HOOK = "Fabric FabricLauncherBase.launcher.isClassLoaded";

	private MixinConfigDiff() {
	}

	static boolean isRemovedFromConfig(String dotClassName) {
		return MixinConfigDiffer.isRemovedFromConfig(dotClassName);
	}

	static void markReaddedToConfig(String dotClassName) {
		MixinConfigDiffer.markReaddedToConfig(dotClassName);
	}

	static void onAssetsChanged(Set<java.nio.file.Path> changedAssets) {
		MixinConfigContext context = context();
		if (context == null) {
			return;
		}
		MixinConfigDiffer.onAssetsChanged(changedAssets, context);
	}

	/**
	 * 接力登记「配置先到、字节后到」的新 Mixin。
	 *
	 * <p>IDE 里先保存 {@code mixins.json}、几秒后才编译出 {@code .class} 是常态。资源事件那一刻
	 * 读不到字节的条目被记进待决集；每次有 Mixin 类变更进入热更流程时顺带重试一次，字节一落地
	 * 就自动完成登记与目标重织入——不需要用户重启，也不需要再存一次配置。</p>
	 */
	static void retryPendingMixins() {
		MixinConfigContext context = context();
		if (context == null) {
			return;
		}
		MixinConfigDiffer.retryPending(context);
	}

	/** 尚未拿到字节码的新列出 Mixin（诊断与测试用）。 */
	static Set<String> pendingUnresolvedMixins() {
		return MixinConfigDiffer.pendingUnresolvedMixins();
	}

	private static MixinConfigContext context() {
		IMixinTransformer transformer = MixinReWeaver.activeTransformer();
		if (transformer == null) {
			return null;
		}
		MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();
		String side = environment == null ? null : environment.getSide().name();
		return new MixinConfigContext(
			transformer,
			side,
			MixinConfigDiff::loadClassNode,
			FabricLauncherSpoof::suppress,
			FabricLauncherSpoof::restore,
			LOADER_HOOK,
			MixinHotSwap::scheduleTargets);
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
}
