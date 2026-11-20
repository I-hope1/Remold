package remold.mixin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import remold.RemoldAgent;
import remold.reflect.mixin.MixinTargetIndex;

/**
 * 把宿主的 Mixin 配置快照推给 {@link MixinTargetIndex}。
 *
 * <p><b>为什么是"推"而不是"拉"</b>：出生点（{@code ClassFileLoadHook}）里绝不能反过来去拿
 * Mixin 的 processor 锁读配置——Mixin 自己可能在持锁状态下触发类加载，形成锁反转。
 * 因此快照只在这里、在宿主/批次侧的稳定时机读取并整表替换。</p>
 *
 * <p><b>为什么每次都要整表重推</b>：{@code processor.configs} 会随 {@code mixins.json} 热增删而变，
 * 增量合并会留下已删除目标的幽灵，而漏判真目标会让出生点补丁冲掉上游织入。</p>
 *
 * <p><b>时机</b>：{@code onAgentInit}/{@code onConfigReload}/资源变更之后各推一次；早期窗口里
 * processor 可能还没装配，此时 {@link #publishFrom} 只返回 {@code false}，索引保持
 * {@code UNKNOWN}——调用方不得把 UNKNOWN 当成"不是目标"。</p>
 */
final class MixinTargetPublisher {

	private MixinTargetPublisher() { }

	/**
	 * 用给定织入器的配置刷新索引。
	 *
	 * @return 是否真的发布了快照（拿到了非空配置列表）
	 */
	static boolean publishFrom(Object transformer) {
		if (transformer == null) return false;
		List<?> copy = copyConfigs(transformer);
		if (copy == null) return false;
		MixinTargetIndex.publish(copy);
		return true;
	}

	/**
	 * 在当前织入器上刷新一次（拿不到织入器时返回 {@code false}）。
	 *
	 * <p>这个方法必须从宿主侧/批次侧调用，<b>不得</b>放在类加载路径上。</p>
	 */
	static boolean publishNow() {
		return publishFrom(MixinReWeaver.activeTransformer());
	}

	/** 在 {@code synchronized(list)} 下做快照拷贝，避免与 Mixin 的配置增删并发。 */
	private static List<?> copyConfigs(Object transformer) {
		try {
			Object processor = readField(transformer, "processor");
			if (processor == null) return null;
			Object configs = readField(processor, "configs");
			if (!(configs instanceof List<?> list)) return null;
			synchronized (list) {
				return new ArrayList<>(list);
			}
		} catch (Throwable t) {
			// 读不到就是读不到：索引保持原样（早期窗口里这就是 UNKNOWN），不猜。
			return null;
		}
	}

	private static Object readField(Object target, String name) throws Exception {
		Field f = findField(target.getClass(), name);
		if (f == null) return null;
		f.setAccessible(true);
		return f.get(target);
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				// 继续查父类
			}
		}
		return null;
	}

	/** 诊断用：一次推送的读数（发布与否 + 收录目标数 + 索引版本）。 */
	static String describe(boolean published) {
		return "published=" + published + " targets=" + MixinTargetIndex.size()
			+ " available=" + MixinTargetIndex.available() + " stamp=" + MixinTargetIndex.stamp();
	}

	static void warnIfStillUnknown() {
		if (!MixinTargetIndex.available()) {
			RemoldAgent.warn("[Mixin] Target index still UNKNOWN after refresh; birth patches stay fail-closed "
				+ "for mixin-target detection");
		}
	}
}
