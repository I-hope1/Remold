package remold.forge;

import remold.reflect.ClassInfoMemberSync;

/**
 * Forge / NeoForge 侧的 {@code ClassInfo} 成员同步<b>薄适配</b>。
 *
 * <p>算法、类型解析规则（按宿主 ClassLoader 的 {@code FieldNode}/{@code MethodNode} 推导）、
 * 失败退化与回滚语义全部在 core 的 {@link ClassInfoMemberSync}；本类只负责把跨 ClassLoader 的
 * {@code Object} 句柄透传过去，并保留 {@code [Forge/NeoForge]} 日志前缀。</p>
 *
 * <p>{@link SyncToken} 只是引擎句柄的类型前缀：{@code ForgeMixinHotSwap} 需要「可回滚句柄」这个
 * 契约，而不需要看到引擎内部结构。</p>
 */
final class ForgeClassInfoSync {

	/** 本侧日志前缀；引擎用它拼出与既有日志一致的措辞。 */
	static final String LOG_PREFIX = "[Forge/NeoForge]";

	private ForgeClassInfoSync() {
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
	 * 把 {@code classNode} 里新增的字段/方法补进该 Mixin 的 {@code ClassInfo}。
	 *
	 * <p>必须在 {@code IMixinTransformer.reload(...)} <b>之前</b>调用。</p>
	 *
	 * @param transformer    当前生效的 Mixin 织入器（跨 ClassLoader 取得的 {@code Object}）
	 * @param mixinClassName Mixin 的点分类名
	 * @param classNode      新字节码完整解析后的 {@code ClassNode}（必须由 transformer 所在
	 *                       ClassLoader 的 ASM 构造，否则形参类型对不上）
	 * @return 补入成员的回滚句柄；未找到 {@code MixinInfo} 或同步失败时返回空句柄
	 */
	static SyncToken sync(Object transformer, String mixinClassName, Object classNode) {
		ClassInfoMemberSync.Token inner =
			ClassInfoMemberSync.sync(transformer, mixinClassName, classNode, LOG_PREFIX);
		return inner.isEmpty() ? SyncToken.EMPTY : new SyncToken(inner);
	}

	/**
	 * 撤销 {@link #sync} 补入的成员（用于 reload 被拒绝后恢复旧元数据）。
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
