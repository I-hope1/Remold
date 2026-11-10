package remold.mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;

import remold.RemoldAgent;
import remold.reflect.mixin.MixinClassByteSources;

/**
 * 新列出 Mixin 的字节回灌：把 Mixin 自身与其 {@code $N} 内部类的字节提前接进
 * Mixin 自己的 {@code IClassBytecodeProvider} 与 core 的 {@link remold.BytecodeSources}，
 * 并清掉上游 {@code ClassInfo} 的负缓存。
 *
 * <p><b>为什么必须发生在 {@code prepare()} 之前</b>：{@code NewMixinRegistrar} 最终调用
 * {@code MixinConfig.prepare(Extensions)} 构造 {@code MixinInfo}。构造期间两处都要读字节：</p>
 * <ul>
 *   <li>{@code MixinInfo} 构造器自己走 {@code getBytecodeProvider().getClassNode(mixinClassName)}
 *       读 Mixin 类，读不到就抛 {@code InvalidMixinException: The specified mixin '...' was not found
 *       (ClassNotFoundException)}——纯 IDE 编译（只有 {@code out/production/classes}）时新类既没进
 *       jar 也没被 JVM 加载过，这条链必然失败；</li>
 *   <li>{@code MixinInfo.State.readInnerClasses()} 通过 {@code ClassInfo.forName(Outer$N)} 读内部类
 *       元数据，读失败会把 {@code null} 写进负缓存，随后
 *       {@code Cannot invoke "ClassInfo.isProbablyStatic()" because "innerClass" is null}。</li>
 * </ul>
 *
 * <p><b>字节从哪来</b>：{@link MixinClassByteSources}（已登记内存字节 → 全部监控目录磁盘最新 →
 * 上次批次源码缓存）。内部类与外部类分别查：IDE 增量编译常常只重编改了的那一个 {@code $N}。</p>
 *
 * <p><b>两条回灌通道的分工</b>：覆盖层解决 Mixin 侧（{@code ClassInfo} /
 * {@code InnerClassGenerator}）的可见性；{@link MixinClassByteSources#register} 解决 core 侧
 * （Nest 扫描、成员可见性判定）的可见性。两者都用同一份字节，登记表只回答本次显式登记过的名字。</p>
 *
 * <p><b>失败语义</b>：覆盖层装不上（非 Mixin 环境、未知 Mixin 版本）由
 * {@link MixinBytecodeOverlay#install()} 自己发一条指向性 warn 并返回 {@code false}；
 * 登记与安装解耦，core 侧登记与覆盖层登记表照样就位，下一次 reload 重试安装后即可生效。
 * 内部类字节取不到时逐个点名 warn（{@code C5}），绝不静默当作已可见。</p>
 */
final class MixinNewMixinBytes {

	private MixinNewMixinBytes() {
	}

	/**
	 * 回灌一个新增 Mixin 的字节。
	 *
	 * @param dotClassName 点分类名
	 * @param bytes        Mixin 自身的源码级字节；{@code null}/空数组时忽略
	 * @param node         由同一份 {@code bytes} 解析出的 {@code ClassNode}（读 {@code InnerClasses} 用）
	 */
	static void register(String dotClassName, byte[] bytes, ClassNode node) {
		if (dotClassName == null || bytes == null || bytes.length == 0) {
			return;
		}
		try {
			String mixinInternalName = MixinInnerClassSupport.internalName(dotClassName);

			// core 侧登记先做：它不依赖 Mixin 覆盖层，即使覆盖层不可用也能让 core 的分析看到新类。
			MixinClassByteSources.register(dotClassName, bytes);

			Map<String, byte[]> innerBytes = new LinkedHashMap<>();
			List<String> unresolved = new ArrayList<>();
			for (String innerName : MixinInnerClassSupport.ownInnerClassNames(mixinInternalName, node)) {
				byte[] found = MixinClassByteSources.lookup(innerName);
				if (found == null || found.length == 0) {
					unresolved.add(innerName);
					continue;
				}
				innerBytes.put(innerName, found);
				MixinClassByteSources.register(innerName, found);
			}

			// 先装覆盖层（幂等；装不上由它自己发一条指向性 warn 并降级），再登记字节：
			// 登记表与安装状态解耦——本次装不上时，下一次 reload 重试安装后这批字节立刻可用。
			MixinBytecodeOverlay.install();
			MixinBytecodeOverlay.register(dotClassName, bytes);
			Set<String> overlayNames = new LinkedHashSet<>();
			overlayNames.add(mixinInternalName);
			for (Map.Entry<String, byte[]> entry : innerBytes.entrySet()) {
				MixinBytecodeOverlay.register(entry.getKey(), entry.getValue());
				overlayNames.add(entry.getKey());
			}
			// 覆盖层只对"已登记"的名字生效，上游此前读失败写下的 null 缓存必须先清掉，否则永不重试。
			MixinBytecodeOverlay.evictClassInfoCache(overlayNames);

			if (!unresolved.isEmpty()) {
				RemoldAgent.warn("[Mixin] New mixin " + dotClassName + " declares inner classes whose bytecode"
					+ " could not be found on any channel (" + MixinClassByteSources.CHANNELS + "): "
					+ unresolved + ". Mixin's ClassInfo will read null for them, so registering this mixin will"
					+ " fail until those .class files are on a watched output directory"
					+ " (compiling the mixin's own source produces all of them).");
			}
		} catch (Throwable t) {
			RemoldAgent.warn("[Mixin] Failed to pre-register the bytecode of the new mixin " + dotClassName
				+ ": " + t + ". Registering it may fail because Mixin cannot read the class or its inner classes.");
		}
	}
}
