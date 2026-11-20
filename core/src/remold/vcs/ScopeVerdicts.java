package remold.vcs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 继承作用域判决的时间戳存储（S1/S3）。
 *
 * <p><b>为什么需要它</b>：{@code @RemoldReinit(INCLUDE_SUBCLASSES)} 与读者判定里有若干"因为没看到
 * 子类/读者 ⇒ 放行"的否定性结论。这类结论只在"当时可见的世界"内成立：之后懒加载进来的子类可能让
 * 它变错。当前实现每轮重算并不缓存，所以问题不是"缓存过期"，而是<b>没有任何记录能证明某次放行是
 * 基于哪个加载前沿做出的</b>——需要复盘或重判时无从下手。</p>
 *
 * <p><b>为什么落在 {@code remold.vcs}</b>：①按 {@code WeakHashMap<ClassLoader, Slot>} 隔离 loader
 * （含 bootstrap 槽），与 {@link GenerationalFieldLedger} 一致；②裁剪/遗忘语义直接复用窗口口径；
 * ③依赖方向不变——{@code ref} 层向下依赖 {@code vcs}，不必反过来认识宿主代理层的继承树。</p>
 *
 * <p><b>失效规则</b>（任一成立即 {@link Availability#STALE}，调用方必须重判或降级为
 * {@code OPAQUE_GUARDED}）：加载前沿超过盖章水位线、世界已进入保守模式、或该条目被窗口裁剪。</p>
 */
public final class ScopeVerdicts {

	public static final ScopeVerdicts INSTANCE = new ScopeVerdicts();

	/** 一条判决：在 {@code watermark} 这个加载前沿上，{@code subject} 的作用域被判为安全。 */
	public record Verdict(String subject, long watermark, boolean conservativeAtStamp) { }

	/** 判决可用性。 */
	public enum Availability {
		/** 有判决且仍有效。 */
		FRESH,
		/** 有判决但已过期：必须重判，不得直接沿用。 */
		STALE,
		/** 该主体从没做过判决。 */
		UNKNOWN
	}

	private static final class Slot {
		final Map<String, Verdict> verdicts = new HashMap<>();
		final Map<String, Integer> forgotten = new HashMap<>();
	}

	private final Map<ClassLoader, Slot> slots = new WeakHashMap<>();
	private Slot bootstrapSlot = new Slot();
	private long forgottenTotal;
	private long recordedTotal;
	private long staleChecks;

	private ScopeVerdicts() { }

	private synchronized Slot slotFor(ClassLoader loader) {
		if (loader == null) return bootstrapSlot;
		return slots.computeIfAbsent(loader, ignored -> new Slot());
	}

	/**
	 * 记录一条判决：{@code subject}（通常是类的内部名）在当前加载前沿上被判为安全。
	 *
	 * @param subject   判定主体（内部名）
	 * @param safe      本次判决结论是否"安全"；只有 {@code true} 才值得留档（{@code false} 已经是拒绝）
	 */
	public synchronized void record(ClassLoader loader, String subject, boolean safe) {
		if (subject == null || !safe) return;
		slotFor(loader).verdicts.put(subject,
		 new Verdict(subject, LoadFrontier.watermark(), LoadFrontier.conservativeOnly()));
		recordedTotal++;
	}

	/**
	 * 查询某条判决是否仍然有效。
	 *
	 * <p>{@link Availability#STALE} 的含义是"这条判决是在更早的世界上做的，现在不能用它放行"。</p>
	 */
	public synchronized Availability check(ClassLoader loader, String subject) {
		if (subject == null) return Availability.UNKNOWN;
		Slot slot = slotFor(loader);
		Verdict verdict = slot.verdicts.get(subject);
		if (verdict == null) {
			// 判决被窗口裁剪过：同样不能当作"从来没有判过"。
			if (slot.forgotten.getOrDefault(subject, 0) > 0) {
				staleChecks++;
				return Availability.STALE;
			}
			return Availability.UNKNOWN;
		}
		boolean advanced = LoadFrontier.watermark() > verdict.watermark();
		boolean conservativeNow = LoadFrontier.conservativeOnly() && !verdict.conservativeAtStamp();
		if (advanced || conservativeNow) {
			staleChecks++;
			return Availability.STALE;
		}
		return Availability.FRESH;
	}

	/**
	 * 按窗口裁剪判决记录；被裁掉的条目计入遗忘（{@link #forgottenVerdicts}）。
	 *
	 * @param keepWatermark 仍然可信的最小加载前沿
	 * @return 被裁掉的判决条数
	 */
	public synchronized int pruneToWindow(long keepWatermark) {
		int dropped = 0;
		for (Slot slot : allSlots()) {
			var it = slot.verdicts.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, Verdict> e = it.next();
				if (e.getValue().watermark() < keepWatermark) {
					slot.forgotten.merge(e.getKey(), 1, Integer::sum);
					it.remove();
					dropped++;
				}
			}
		}
		forgottenTotal += dropped;
		return dropped;
	}

	private List<Slot> allSlots() {
		List<Slot> out = new ArrayList<>(slots.values());
		out.add(bootstrapSlot);
		return out;
	}

	/** 被裁剪掉的判决累计条数（遗忘可观测）。 */
	public synchronized long forgottenVerdicts() {
		return forgottenTotal;
	}

	/** 已记录的判决总数。 */
	public synchronized long recordedVerdicts() {
		return recordedTotal;
	}

	/** 被判为 STALE 的查询次数（"旧结论被拒绝沿用"的次数）。 */
	public synchronized long staleChecks() {
		return staleChecks;
	}

	/** 诊断读数。 */
	public synchronized Map<String, Long> counters() {
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("recorded", recordedTotal);
		out.put("forgotten", forgottenTotal);
		out.put("staleChecks", staleChecks);
		return Collections.unmodifiableMap(out);
	}

	/** 清空（测试与 agent 重启口径）。 */
	public synchronized void reset() {
		slots.clear();
		bootstrapSlot = new Slot();
		forgottenTotal = 0L;
		recordedTotal = 0L;
		staleChecks = 0L;
	}
}
