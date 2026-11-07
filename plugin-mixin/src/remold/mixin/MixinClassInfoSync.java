package remold.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import remold.reflect.ClassInfoMemberSync;

/**
 * Fabric（Knot）侧的 {@code ClassInfo} 成员同步<b>薄适配</b>。
 *
 * <p>算法、类型解析规则、失败退化与回滚语义全部在 core 的 {@link ClassInfoMemberSync}；本类只把
 * 编译期类型（{@code IMixinTransformer} / ASM {@code ClassNode}）擦成 {@code Object} 后委托，并保留
 * {@code [Mixin]} 日志前缀。</p>
 *
 * <p><b>必须时序</b>：{@code MixinInfo.reloadMixin} 把新状态建在<b>旧</b> {@code ClassInfo} 上，因此
 * {@link #sync} 必须在 {@code transformer.reload(...)} <b>之前</b>完成——reload 自身的校验阶段就会
 * 查这份元数据。</p>
 *
 * <p><b>失败回滚</b>：{@link #sync} 返回本次补入的成员句柄；reload 被拒绝时调用 {@link #rollback}
 * 把补入项摘掉，避免一份未被接受的模板污染 {@code ClassInfo}、影响后续织入。</p>
 */
final class MixinClassInfoSync {

	/** 本侧日志前缀；引擎用它拼出与既有日志一致的措辞。 */
	static final String LOG_PREFIX = "[Mixin]";

	private MixinClassInfoSync() {
	}

	/** 本次同步补入的成员句柄，供 reload 失败时回滚。 */
	static final class SyncToken {
		static final SyncToken EMPTY = new SyncToken(ClassInfoMemberSync.emptyToken());

		private final ClassInfoMemberSync.Token delegate;

		private SyncToken(ClassInfoMemberSync.Token delegate) {
			this.delegate = delegate == null ? ClassInfoMemberSync.emptyToken() : delegate;
		}

		boolean isEmpty() {
			return this.delegate.isEmpty();
		}
	}

	/**
	 * 把 {@code newBytes} 中新增的字段/方法补进该 Mixin 的 {@code ClassInfo}。
	 *
	 * @param transformer    当前生效的 Mixin 织入器
	 * @param mixinClassName Mixin 的点分类名
	 * @param newBytes       最新的 Mixin 源码级字节码（完整解析的 ClassNode）
	 * @return 补入成员的回滚句柄；未找到 MixinInfo 或同步失败时返回空句柄
	 */
	static SyncToken sync(IMixinTransformer transformer, String mixinClassName, ClassNode newBytes) {
		ClassInfoMemberSync.Token inner =
			ClassInfoMemberSync.sync(transformer, mixinClassName, newBytes, LOG_PREFIX);
		return inner.isEmpty() ? SyncToken.EMPTY : new SyncToken(inner);
	}

	/**
	 * 撤销 {@link #sync} 补入的成员（仅用于 reload 被拒绝后恢复旧元数据）。
	 *
	 * @param token {@link #sync} 的返回值，可为 {@code null}
	 */
	static void rollback(SyncToken token) {
		if (token == null) {
			return;
		}
		ClassInfoMemberSync.rollback(token.delegate);
	}
}
