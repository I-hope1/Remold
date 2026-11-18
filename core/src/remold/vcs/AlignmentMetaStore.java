package remold.vcs;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

import remold.RemoldAgent;

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
 * <p><b>线程安全</b>：全部公开方法在同一个对象监视器上串行化。这条是必须的——{@code WeakHashMap}
 * 的 {@code get()} 会在内部触发 {@code expungeStaleEntries()} 改表结构，多线程只读也可能崩；
 * 因此内部所有对 {@code slots} 的访问（含 {@code slots.size()} / 遍历）都在锁内完成，
 * 调用方无需再包 {@code Collections.synchronizedMap(...)}。</p>
 *
 * <p><b>{@code null} loader</b>：走固定 {@code bootstrapSlot}（id 0），不经过 {@code WeakHashMap}，
 * 因此引导类加载器下的类不会 NPE，也不参与 loader 回收。</p>
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

	/** 全局 Raw_prev blob 字节预算；{@code -Dremold.vcs.meta_blob_bytes} 覆盖，默认 16 MiB。 */
	public static final long BLOB_BUDGET_BYTES =
	 Math.max(0L, Long.getLong("remold.vcs.meta_blob_bytes", 16L * 1024 * 1024));

	/** 单类 Raw_prev blob 上限；{@code -Dremold.vcs.meta_blob_per_class_bytes} 覆盖，默认 256 KiB。 */
	public static final int PER_CLASS_BLOB_BYTES =
	 Math.max(0, Integer.getInteger("remold.vcs.meta_blob_per_class_bytes", 256 * 1024));

	/** SHA-256 十六进制串长度，用于 {@link #UNKNOWN_SHA256} 的判定。 */
	private static final String UNKNOWN_SHA256 = "";

	/** 进程内单例。PR1 只做内存记录；离线回放包见 {@link BytecodePack}。 */
	public static final AlignmentMetaStore INSTANCE = new AlignmentMetaStore();

	/** 一代对齐/重定义的元数据快照（哈希、字符串表与可选的 Raw_prev blob）。 */
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
		/** {@code preAlign} 的 SHA-256（十六进制）；{@code ""} 表示未知。<b>树身份</b>用它，不用 32 位哈希。 */
		public final String preAlignSha256;
		/** 对齐产出字节的 SHA-256（十六进制）；{@code ""} 表示未知。 */
		public final String alignOutputSha256;
		/** 实际生效字节的 SHA-256（十六进制）；{@code ""} 表示未知。 */
		public final String jvmEffSha256;
		/** 本轮结束时仍留在产物里的自造名。 */
		public final Set<String> inventedNames;
		/** 幽灵方法 {@code name + desc} → 生前方法体指纹。 */
		public final Map<String, Long> ghostHashes;
		/** 旧键 {@code name:desc} → 新键 {@code name:desc}。 */
		public final Map<String, String> methodMapping;
		/**
		 * 本轮 {@code Raw} 方法键 → 本轮生效侧方法键（{@code name:desc}）。
		 *
		 * <p>它是下一轮"Raw ↔ Raw 配对 + 生效侧命名"的桥：Raw 侧的槽位身份决定配谁，
		 * 生效侧的名字决定产物叫什么。缺失（旧条目 / 预算丢弃）时空表，读侧据此整类退回兼容跑道。</p>
		 */
		public final Map<String, String> rawToEffKeys;
		/** 本轮生成的捕获桥数量。 */
		public final int bridgeCount;

		/**
		 * 上一轮进入对齐器之前的字节（{@code R_prev}），<b>best-effort</b>：受
		 * {@link #BLOB_BUDGET_BYTES} 与 {@link #PER_CLASS_BLOB_BYTES} 约束，预算压力下置空
		 * （哈希仍然保留），读侧据此降级。
		 */
		private byte[] rawPrevBytes;

		Entry(int generation, long seq, long preAlignHash, long alignOutputHash, long jvmEffHash,
		      String preAlignSha256, String alignOutputSha256, String jvmEffSha256,
		      Set<String> inventedNames, Map<String, Long> ghostHashes,
		      Map<String, String> methodMapping, Map<String, String> rawToEffKeys,
		      int bridgeCount, byte[] rawPrevBytes) {
			this.generation = generation;
			this.seq = seq;
			this.preAlignHash = preAlignHash;
			this.alignOutputHash = alignOutputHash;
			this.jvmEffHash = jvmEffHash;
			this.preAlignSha256 = preAlignSha256 == null ? UNKNOWN_SHA256 : preAlignSha256;
			this.alignOutputSha256 = alignOutputSha256 == null ? UNKNOWN_SHA256 : alignOutputSha256;
			this.jvmEffSha256 = jvmEffSha256 == null ? UNKNOWN_SHA256 : jvmEffSha256;
			this.inventedNames = inventedNames == null
				? Set.of() : Set.copyOf(new LinkedHashSet<>(inventedNames));
			this.ghostHashes = ghostHashes == null
				? Map.of() : Map.copyOf(new HashMap<>(ghostHashes));
			this.methodMapping = methodMapping == null
				? Map.of() : Map.copyOf(new HashMap<>(methodMapping));
			this.rawToEffKeys = rawToEffKeys == null
				? Map.of() : Map.copyOf(new HashMap<>(rawToEffKeys));
			this.bridgeCount = bridgeCount;
			this.rawPrevBytes = rawPrevBytes;
		}

		/** {@code R_prev} 字节；预算丢弃或本代未记录时为 {@code null}。 */
		public byte[] rawPrevBytes() {
			return rawPrevBytes;
		}

		/** 该条目当前持有的 blob 字节数（0 表示没有 blob）。 */
		int blobBytes() {
			return rawPrevBytes == null ? 0 : rawPrevBytes.length;
		}

		/** 预算回收：丢 blob 保哈希。返回释放的字节数。 */
		int dropBlob() {
			int n = blobBytes();
			rawPrevBytes = null;
			return n;
		}

		@Override
		public String toString() {
			return "gen=" + generation + ", pre=" + Long.toHexString(preAlignHash)
				+ ", align=" + Long.toHexString(alignOutputHash)
				+ ", jvm=" + Long.toHexString(jvmEffHash)
				+ ", invented=" + inventedNames.size() + ", ghosts=" + ghostHashes.size()
				+ ", rawPrev=" + blobBytes() + "B, map=" + rawToEffKeys.size();
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

	/** 没存下 blob 的代数（诊断读数）。 */
	private int droppedBlobCount;

	/** 每类只报一次 blob 丢弃（否则预算压力下会按代刷屏）。 */
	private static final Set<String> LOGGED_BLOB_DROPS =
	 java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * 兼容旧 9 参 {@code commit} 的派生映射查询口；见 {@link #setTranslationLookup}。
	 */
	private static volatile Function<String, Map<String, String>> translationLookup;

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

	/**
	 * 历史条目总数（不含 head），按<b>当前仍存活</b>的 loader 槽实时统计。
	 *
	 * <p>必须实时算：{@code WeakHashMap} 在 loader 被回收后只是悄悄摘槽，不会回调本类扣减计数器；
	 * 若返回缓存计数，死 loader 会永久占用全局预算配额（PR2 已修，见 {@code historyBudgetSpent}）。</p>
	 */
	public synchronized int historyCount() {
		return liveHistoryEntries();
	}

	/** 读取该键的当前代；不存在返回 {@code null}。 */
	public synchronized Entry head(ClassLoader loader, String className) {
		LoaderSlot slot = slot(loader);
		return slot == null ? null : slot.head.get(className);
	}

	/**
	 * 读取"当前代且实际生效指纹匹配"的条目（32 位预筛）。
	 *
	 * <p>下一轮对齐拿到的 {@code oldBytes} 必须正好是上一轮真正进 JVM 的字节，否则元数据不适用：
	 * 宁可返回 {@code null} 让读侧退回字节码标记，也不要拿错代指纹去配幽灵。</p>
	 *
	 * <p><b>32 位只是预筛</b>：命中后调用方必须再用 {@link #headMatching(ClassLoader, String, byte[])}
	 * 做 SHA-256 精判。<b>不得</b>把 32 位哈希当树身份（规格 §3.2 明令禁止）。</p>
	 */
	public synchronized Entry headMatching(ClassLoader loader, String className, long jvmEffHash) {
		LoaderSlot slot = slot(loader);
		if (slot == null) return null;
		Entry e = slot.head.get(className);
		return e != null && e.jvmEffHash == jvmEffHash ? e : null;
	}

	/**
	 * 读取"当前代且本轮旧侧字节逐字节匹配"的条目（SHA-256 精判）。
	 *
	 * <p>匹配口径是<b>本轮实际要用的旧侧字节</b>（{@code E_prev_used}）：调用方先比 32 位哈希做
	 * 快速预筛时也走这条，最终以 SHA-256 是否相等定案。任何一项不成立都返回 {@code null}
	 * ——近邻代不得顶替。</p>
	 *
	 * @param oldEffectiveBytes 本轮旧侧字节（{@code E_prev} 或调用方归一化后的等价字节）
	 */
	public synchronized Entry headMatching(ClassLoader loader, String className, byte[] oldEffectiveBytes) {
		if (oldEffectiveBytes == null || oldEffectiveBytes.length == 0) return null;
		LoaderSlot slot = slot(loader);
		if (slot == null) return null;
		Entry e = slot.head.get(className);
		if (e == null) return null;
		// 32 位只作快速预筛（两道口径都试）：不符就直接拒，省掉一次 SHA-256。
		long weak = hash(oldEffectiveBytes);
		if (e.jvmEffHash != weak && e.alignOutputHash != weak) return null;
		String sha = sha256Hex(oldEffectiveBytes);
		// 生效字节与对齐产出都可能被调用方当作"旧侧"送回来（例如前置归一化后的基线）：
		// 两者同代同源，任一相等即认这一代；跨代（32 位与 SHA 都对不上）一律不认。
		if (!e.jvmEffSha256.isEmpty() && e.jvmEffSha256.equals(sha)) return e;
		if (!e.alignOutputSha256.isEmpty() && e.alignOutputSha256.equals(sha)) return e;
		return null;
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
	 * <p><b>兼容重载</b>：不显式提供 {@code Raw → Eff} 键映射时，走
	 * {@link #setTranslationLookup} 注册的查询口取"该类最近一次对齐发布的映射"（只有
	 * "align 返回后立刻 commit"的调用方成立，例如测试与离线回放）。生产路径应使用下面的
	 * 显式重载，避免依赖时序。</p>
	 *
	 * @return 新写入的条目
	 */
	public synchronized Entry commit(ClassLoader loader, String className,
	                                 byte[] preAlignBytes, byte[] alignOutputBytes, byte[] jvmEffectiveBytes,
	                                 Set<String> inventedNames, Map<String, Long> ghostHashes,
	                                 Map<String, String> methodMapping, int bridgeCount) {
		Function<String, Map<String, String>> lookup = translationLookup;
		Map<String, String> derived = Map.of();
		if (lookup != null && className != null) {
			try {
				Map<String, String> m = lookup.apply(className);
				if (m != null) derived = m;
			} catch (Throwable ignored) {
				// 查询口失败只意味着"这一代没有翻译表"，读侧会整类退回兼容跑道。
			}
		}
		return commit(loader, className, preAlignBytes, alignOutputBytes, jvmEffectiveBytes,
		 inventedNames, ghostHashes, methodMapping, bridgeCount, derived);
	}

	/**
	 * 记录一代（显式带上 {@code Raw → Eff} 键映射）。
	 *
	 * @param rawToEffKeys 本轮 {@code Raw} 方法键 → 本轮生效侧方法键；{@code null}/空表表示不可用
	 */
	public synchronized Entry commit(ClassLoader loader, String className,
	                                 byte[] preAlignBytes, byte[] alignOutputBytes, byte[] jvmEffectiveBytes,
	                                 Set<String> inventedNames, Map<String, Long> ghostHashes,
	                                 Map<String, String> methodMapping, int bridgeCount,
	                                 Map<String, String> rawToEffKeys) {
		LoaderSlot slot = slotFor(loader);
		Entry prev = slot.head.get(className);
		byte[] rawPrev = retainBlob(className, preAlignBytes);
		Entry e = new Entry(prev == null ? 1 : prev.generation + 1, nextSeq++,
			hash(preAlignBytes), hash(alignOutputBytes), hash(jvmEffectiveBytes),
			sha256Hex(preAlignBytes), sha256Hex(alignOutputBytes), sha256Hex(jvmEffectiveBytes),
			inventedNames, ghostHashes, methodMapping, rawToEffKeys, bridgeCount, rawPrev);
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
		droppedBlobCount = 0;
		LOGGED_BLOB_DROPS.clear();
	}

	// ---------------------------------------------------------------------------------------------
	// Raw_prev blob 预算（丢 blob 保哈希：budget 压力下只影响"能否走 Raw ↔ Raw"，不影响哈希判据）
	// ---------------------------------------------------------------------------------------------

	/** 当前持有的 blob 总字节数（head + 未被预算回收的历史代）。 */
	public synchronized long blobBytes() {
		long n = 0;
		Set<Entry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Entry e : bootstrapSlot.head.values()) if (seen.add(e)) n += e.blobBytes();
		for (LoaderSlot s : slots.values()) {
			for (Entry e : s.head.values()) if (seen.add(e)) n += e.blobBytes();
			for (Deque<Entry> d : s.history.values()) {
				for (Entry e : d) if (seen.add(e)) n += e.blobBytes();
			}
		}
		return n;
	}

	/** 因预算/单类上限而没存下 blob 的代数（诊断读数）。 */
	public synchronized int droppedBlobCount() {
		return droppedBlobCount;
	}

	/**
	 * 决定这一代是否保存 {@code R_prev} blob。
	 *
	 * <p>顺序：单类上限 → 全局预算（先从最旧历史代回收 blob）。任一步不满足就<b>不存 blob</b>
	 * 并记一条 log-once 的 warn：下一轮该类会因"继承来源缺失"整类退回兼容跑道，
	 * 这条 warn 是那一轮降级的唯一可观测线索。</p>
	 */
	private byte[] retainBlob(String className, byte[] preAlignBytes) {
		if (preAlignBytes == null || preAlignBytes.length == 0) return null;
		int len = preAlignBytes.length;
		if (len > PER_CLASS_BLOB_BYTES) {
			noteBlobDrop(className, "entry is " + len + "B, per-class cap is " + PER_CLASS_BLOB_BYTES + "B");
			return null;
		}
		if (BLOB_BUDGET_BYTES <= 0) {
			noteBlobDrop(className, "Raw_prev blob budget is disabled (" + BLOB_BUDGET_BYTES + "B)");
			return null;
		}
		// 先给新代腾地方：从最旧的历史代开始丢 blob（head 的 blob 由"新代覆盖旧代"自然释放）。
		while (blobBytes() + len > BLOB_BUDGET_BYTES && dropOldestHistoryBlob() > 0) {
			// 继续回收
		}
		if (blobBytes() + len > BLOB_BUDGET_BYTES) {
			noteBlobDrop(className, "global budget exhausted (" + blobBytes() + "B held, "
				+ BLOB_BUDGET_BYTES + "B allowed, need " + len + "B)");
			return null;
		}
		byte[] copy = new byte[len];
		System.arraycopy(preAlignBytes, 0, copy, 0, len);
		return copy;
	}

	/** 从最旧的历史代回收一份 blob；返回释放字节数（0 表示没有可回收的）。 */
	private int dropOldestHistoryBlob() {
		Entry oldest = null;
		for (Deque<Entry> d : bootstrapSlot.history.values()) {
			for (Entry e : d) {
				if (e.blobBytes() == 0) continue;
				if (oldest == null || e.seq < oldest.seq) oldest = e;
			}
		}
		for (LoaderSlot s : slots.values()) {
			for (Deque<Entry> d : s.history.values()) {
				for (Entry e : d) {
					if (e.blobBytes() == 0) continue;
					if (oldest == null || e.seq < oldest.seq) oldest = e;
				}
			}
		}
		return oldest == null ? 0 : oldest.dropBlob();
	}

	private void noteBlobDrop(String className, String why) {
		droppedBlobCount++;
		if (LOGGED_BLOB_DROPS.add(className)) {
			RemoldAgent.warn("[VCS] Raw_prev blob not recorded for " + className + ": " + why
				+ ". Next round falls back to the effective-bytes baseline for this class"
				+ " (-Dremold.vcs.meta_blob_bytes / -Dremold.vcs.meta_blob_per_class_bytes).");
		}
	}

	/**
	 * 注册"该类最近一次对齐发布的 Raw → Eff 键映射"查询口。
	 *
	 * <p>只服务不显式传 {@code rawToEffKeys} 的旧 9 参 {@code commit} 调用方（测试 / 离线回放：
	 * align 之后立刻 commit）。生产路径由 {@code RemoldAgent} 显式传值，不依赖该口。</p>
	 */
	public static void setTranslationLookup(Function<String, Map<String, String>> lookup) {
		translationLookup = lookup;
	}

	/** 该字符串是否为可用的 SHA-256 十六进制串。 */
	public static boolean isKnownSha256(String sha) {
		return sha != null && !sha.isEmpty();
	}

	/** SHA-256 十六进制（小写）；{@code null}/空输入返回 {@code ""}。 */
	public static String sha256Hex(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return UNKNOWN_SHA256;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] dig = md.digest(bytes);
			StringBuilder sb = new StringBuilder(dig.length * 2);
			for (byte b : dig) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (Exception e) {
			return UNKNOWN_SHA256;
		}
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

	/** 只统计仍然可达（loader 未回收）的历史条目。 */
	private int liveHistoryEntries() {
		int n = 0;
		for (Deque<Entry> d : bootstrapSlot.history.values()) n += d.size();
		for (LoaderSlot s : slots.values()) {
			for (Deque<Entry> d : s.history.values()) n += d.size();
		}
		return n;
	}

	private void trimGlobalBudget() {
		// 先按存活槽重算：死 loader 的条目已随槽消失，缓存的 historyEntries 会偏高，
		// 若不重算会把活着的条目误淘汰。
		historyEntries = liveHistoryEntries();
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
