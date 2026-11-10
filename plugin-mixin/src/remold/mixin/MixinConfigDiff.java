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

import remold.RemoldAgent;
import remold.reflect.mixin.MixinClassByteSources;
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

	/**
	 * 读新列出的 Mixin 的 {@code ClassNode}。
	 *
	 * <p>字节来源两档：{@link MixinClassByteSources}（Remold 监控通道，覆盖"资源回调先于 class 批次"
	 * 的时序断层与 IDE 多输出目录的路径断层）→ {@link MixinService} 资源流（jar 内类兜底）。
	 * 两档都没有才返回 {@code null}，由 core 侧 fail-closed 并给出指向性诊断。</p>
	 *
	 * <p>解析出节点后立即把它与 {@code $N} 内部类的字节预登记进 Mixin 的字节码提供方
	 * （见 {@link MixinNewMixinBytes}），否则 {@code prepare()} 构造 {@code MixinInfo} 时读内部类会拿到
	 * 上游 {@code ClassInfo} 的负缓存。</p>
	 *
	 * <p>包内可见：测试直接调用本方法验证回退链。</p>
	 */
	static Object loadClassNode(String dotClassName) {
		if (dotClassName == null || dotClassName.isEmpty()) {
			return null;
		}
		byte[] bytes = MixinClassByteSources.lookup(dotClassName);
		if (bytes == null) {
			bytes = readServiceBytes(dotClassName);
		}
		if (bytes == null) {
			return null;
		}
		ClassNode node;
		try {
			node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Found " + bytes.length + " bytes for the newly listed mixin "
				+ dotClassName + " but they are not a readable class file (" + t
				+ "); the mixin is not registered until the class compiles again");
			return null;
		}
		MixinNewMixinBytes.register(dotClassName, bytes, node);
		return node;
	}

	/** Mixin 自己的资源流：jar 内的 Mixin 走这一档（监控目录里没有它的 .class 文件）。 */
	private static byte[] readServiceBytes(String dotClassName) {
		try {
			InputStream in = MixinService.getService().getResourceAsStream(
				dotClassName.replace('.', '/') + ".class");
			if (in == null) {
				return null;
			}
			try (InputStream resource = in) {
				return resource.readAllBytes();
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
