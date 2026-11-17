package remold.vcs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 逐代对齐元数据仓（旁路只读记录）。
 *
 * <p><b>定位</b>：把"上一轮重定义之后 JVM 里实际生效的样子"按代记下来，供后续轮次读取与离线回放。
 * 本类<b>只记录</b>，不参与任何放行/拒绝判定；读写两侧都不持有 {@code Class<?>} 或
 * {@code ClassLoader} 强引用。</p>
 *
 * <p><b>键 = (loader identity, 类内部名)</b>。loader 只参与身份判定：每个存活 loader 在
 * {@link WeakHashMap} 里对应一个 {@link LoaderSlot}，槽内是该 loader 名下的 head/history。
 * 因此 loader 被 GC 回收后，它名下的元数据随槽一起不可达——<b>不会成为永久泄漏</b>；
 * 而"head 永不驱逐"精确地指：<b>只要该 loader 还活着，其 head 条目不被历史预算淘汰</b>。</p>
 *
 * <p><b>两级存储</b>：</p>
 * <ul>
 *   <li><b>head</b>：每个键一条、不随历史预算淘汰，只随 loader 回收消失。</li>
 *   <li><b>history</b>：按类保留最近 {@link #HISTORY_PER_CLASS} 代，并受全局条数预算
 *       {@link #HISTORY_MAX_ENTRIES} 约束；超预算按全局 {@link Entry#seq} 淘汰最旧。</li>
 * </ul>
 *
 * <p><b>写入时机不在本类决定</b>：调用方（{@code RemoldAgent}）必须在该类<b>最终重定义成功</b>
 * 之后才调用 {@link #commit}；对齐期（{@code LambdaAligner.align}）只读不写，避免把"对齐算完了
 * 但 JVM 拒绝/回滚"的字节记成当前代。</p>
 */
public final class AlignmentMetaStore {

	/** 每个键最多保留多少代历史（不含永不驱逐的 head）。 */
	public static final int HISTORY_PER_CLASS = 8;

	/** 全局历史条数预算（不含 head）。 */
	public static final int HISTORY_MAX_ENTRIES = 4096;

	/** 进程内单例。PR1 只做内存记录；离线回放包见 {@link BytecodePack}。 */
	public static final AlignmentMetaStore INSTANCE = new AlignmentMetaStore();

	/** 一代对齐/重定义的不可变元数据快照（只含哈希与字符串）。 */
	public static final class Entry {
		/** 该键上的代序号，从 1 开始单调递增。 */
		public final int generation;
		/** 全局单调写入序，仅用于历史预算淘汰。 */
		public final long seq;
		/** 喂给对齐器的"新"字节（对齐前产物）指纹；0 表示未知。 */
		public final long preAlignHash;
		/** 对齐产出指纹；0 表示未知。 */
		public final long alignOutputHash;
		/** 实际送进 {@code redefineClasses} 的字节指纹；0 表示未知。 */
		public final long jvmEffHash;
		/** 本轮结束时仍留在产物里的自造名。 */
		public final Set<String> inventedNames;
		/** 幽灵方法 {@code name + desc} → 生前方法体指纹。 */
		public final Map<String, Long> ghostHashes;
		/** 旧键 {@code name:desc} → 新键 {@code name:desc}。 */
		public final Map<String, String> methodMapping;
		/** 本轮生成的捕获桥数量。 */
		public final int bridgeCount;

		Entry(int generation, long seq, long preAlignHash, long alignOutputHash, long jvmEffHash,
		      Set<String> inventedNames, Map<String, Long> ghostHashes,
		      Map<String, String> methodMapping, int bridgeCount) {
			this.generation = generation;
			this.seq = seq;
			this.preAlignHash = preAlignHash;
			this.alignOutputHash = alignOutputHash;
			this.jvmEffHash = jvmEffHash;
			this.inventedNames = inventedNames == null
				? Set.of() : Set.copyOf(new LinkedHashSet<>(inventedNames));
			this.ghostHashes = ghostHashes == null
				? Map.of() : Map.copyOf(new HashMap<>(ghostHashes));
			this.methodMapping = methodMapping == null
				? Map.of() : Map.copyOf(new HashMap<>(methodMapping));
			this.bridgeCount = bridgeCount;
		}

		@Override
		public String toString() {
			return "gen=" + generation + ", pre=" + Long.toHexString(preAlignHash)
				+ ", align=" + Long.toHexString(alignOutputHash)
				+ ", jvm=" + Long.toHexString(jvmEffHash)
				+ ", invented=" + inventedNames.size() + ", ghosts=" + ghostHashes.size();
		}
	}

	/** 一个 loader 名下的全部记录。随 loader 被回收而整体不可达。 */
	private static final class LoaderSlot {
		final long loaderId;
		final Map<String, Entry> head = new HashMap<>();
		final Map<String, Deque<Entry>> history = new HashMap<>();

		LoaderSlot(long loaderId) {
			this.loaderId = loaderId;
		}
	}

	/** 弱键：loader 回收后槽不可达，head/history 一并被收集。 */
	private final Map<ClassLoader, LoaderSlot> slots = new WeakHashMap<>();

	/** 无 loader 维度（引导类 / 直接调用）的固定槽，id 固定为 0。 */
	private final LoaderSlot bootstrapSlot = new LoaderSlot(0L);

	private long nextLoaderId = 1L;
	private long nextSeq = 1L;
	private int historyEntries;

	/** loader 回收后，其名下记录不再计入；这里给出当前仍可用的 loader 槽数（含 bootstrap）。 */
	public synchronized int liveLoaderCount() {
		return slots.size() + 1;
	}

	/** 当前 head 条目总数。 */
	public synchronized int headCount() {
		int n = bootstrapSlot.head.size();
		for (LoaderSlot s : slots.values()) n += s.head.size();
		return n;
	}

	/** 历史条目总数（不含 head）。 */
	public synchronized int historyCount() {
		return historyEntries;
	}

	/** 读取该键的当前代；不存在返回 {@code null}。 */
	public synchronized Entry head(ClassLoader loader, String className) {
		LoaderSlot slot = slot(loader);
		return slot == null ? null : slot.head.get(className);
	}

	/**
	 * 读取"当前代且实际生效指纹匹配"的条目。
	 *
	 * <p>下一轮对齐拿到的 {@code oldBytes} 必须正好是上一轮真正进 JVM 的字节，否则元数据不适用：
	 * 宁可返回 {@code null} 让读侧退回字节码标记，也不要拿错代指纹去配幽灵。</p>
	 */
	public synchronized Entry headMatching(ClassLoader loader, String className, long jvmEffHash) {
		LoaderSlot slot = slot(loader);
		if (slot == null) return null;
		Entry e = slot.head.get(className);
		return e != null && e.jvmEffHash == jvmEffHash ? e : null;
	}

	/** 读取该键的历史代（新→旧，只读副本）。 */
	public synchronized List<Entry> history(ClassLoader loader, String className) {
		LoaderSlot slot = slot(loader);
		Deque<Entry> d = slot == null ? null : slot.history.get(className);
		return d == null ? List.of() : new ArrayList<>(d);
	}

	/**
	 * 记录"该类的最终重定义已生效"这一代。
	 *
	 * <p><b>调用契约</b>：只能在该类的最终 {@code redefineClasses} 返回成功之后调用；
	 * 两阶段的阶段 A 不得调用，被回滚的批次不得调用。</p>
	 *
	 * @return 新写入的条目
	 */
	public synchronized Entry commit(ClassLoader loader, String className,
	                                 byte[] preAlignBytes, byte[] alignOutputBytes, byte[] jvmEffectiveBytes,
	                                 Set<String> inventedNames, Map<String, Long> ghostHashes,
	                                 Map<String, String> methodMapping, int bridgeCount) {
		LoaderSlot slot = slotFor(loader);
		Entry prev = slot.head.get(className);
		Entry e = new Entry(prev == null ? 1 : prev.generation + 1, nextSeq++,
			hash(preAlignBytes), hash(alignOutputBytes), hash(jvmEffectiveBytes),
			inventedNames, ghostHashes, methodMapping, bridgeCount);
		slot.head.put(className, e);

		Deque<Entry> d = slot.history.computeIfAbsent(className, x -> new ArrayDeque<>());
		d.addFirst(e);
		historyEntries++;
		if (d.size() > HISTORY_PER_CLASS) {
			d.removeLast();
			historyEntries--;
		}
		trimGlobalBudget();
		return e;
	}

	/** 清空仓（测试与 agent 重启口径）。 */
	public synchronized void reset() {
		slots.clear();
		bootstrapSlot.head.clear();
		bootstrapSlot.history.clear();
		historyEntries = 0;
		nextSeq = 1L;
		nextLoaderId = 1L;
	}

	/** 只读路径：缺失返回 {@code null}，不落表。 */
	private LoaderSlot slot(ClassLoader loader) {
		return loader == null ? bootstrapSlot : slots.get(loader);
	}

	/** 写入路径：缺失则建槽。 */
	private LoaderSlot slotFor(ClassLoader loader) {
		if (loader == null) return bootstrapSlot;
		return slots.computeIfAbsent(loader, k -> new LoaderSlot(nextLoaderId++));
	}

	private void trimGlobalBudget() {
		if (historyEntries <= HISTORY_MAX_ENTRIES) return;
		List<Object[]> all = new ArrayList<>();
		for (Map.Entry<String, Deque<Entry>> en : bootstrapSlot.history.entrySet()) {
			for (Entry e : en.getValue()) all.add(new Object[]{ bootstrapSlot, en.getKey(), e });
		}
		for (LoaderSlot s : slots.values()) {
			for (Map.Entry<String, Deque<Entry>> en : s.history.entrySet()) {
				for (Entry e : en.getValue()) all.add(new Object[]{ s, en.getKey(), e });
			}
		}
		all.sort(Comparator.comparingLong(o -> ((Entry) o[2]).seq));
		for (Object[] row : all) {
			if (historyEntries <= HISTORY_MAX_ENTRIES) break;
			@SuppressWarnings("unchecked")
			Map<String, Deque<Entry>> h = ((LoaderSlot) row[0]).history;
			Deque<Entry> d = h.get((String) row[1]);
			if (d != null && d.remove((Entry) row[2])) {
				historyEntries--;
				if (d.isEmpty()) h.remove((String) row[1]);
			}
		}
	}

	private static long hash(byte[] bytes) {
		return bytes == null ? 0L : java.util.Arrays.hashCode(bytes);
	}
}
