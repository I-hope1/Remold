package remold.vcs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 离线回放包：把"一类一代"的输入/产出字节与契约字段落成独立文件。
 *
 * <p><b>为什么单独存在</b>：{@link AlignmentMetaStore} 只存哈希——它要在运行期常驻，不能把
 * 每代的字节都留在内存里。回放包是显式导出物，体积可控、可传阅、可直接喂给单测。</p>
 *
 * <p><b>启用</b>：系统属性 {@code -Dremold.vcs.pack_dir=<目录>}。未设置时 {@link #append} 是 no-op，
 * 主流程零额外开销（只多一次属性读取）。每个条目写一个 {@code %08d.pack} 文件，顺序即写入序。</p>
 *
 * <p><b>两种布局</b>：</p>
 * <ul>
 *   <li><b>单轮（兼容布局）</b>：{@link #append} 直接写目录根下的 {@code %08d.pack}，跨轮混在一个
 *       目录里。老探针与老调用方依赖这个布局，保持不变。</li>
 *   <li><b>多轮链式布局</b>：{@link #exportRound} 把<b>一轮已提交代</b>写成
 *       {@code seq-<n>/} 子目录（条目文件格式与单轮完全相同）+ {@code manifest.txt} 清单，清单记录
 *       {@code formatVersion}/{@code seq}/{@code changesetId}/{@code parentId}/{@code roundId}/
 *       {@code loaderScope}/{@code algoVersion}/{@code patchTiming}/{@code entryCount} 与每条的
 *       类名和三个哈希。{@link #chainRounds()} / {@link #round} 按 {@code seq} 读回。</li>
 * </ul>
 *
 * <p><b>两套布局互不干扰</b>：{@link #readAll} 只读目录根下的 {@code *.pack}，不递归进
 * {@code seq-<n>/}；链式读取只走子目录。因此单轮布局的现有验收不必改动。</p>
 *
 * <p><b>确定性</b>：一轮内的条目按类名升序落盘、清单字段顺序固定、清单只含十进制数字与哈希；
 * 同一 {@link Changeset} 与同一批条目重复导出必须逐字节相同（写入前先清掉该代目录的旧文件）。</p>
 *
 * <p><b>回放契约</b>：读出的每个条目满足
 * {@code LambdaAligner.align(oldEffective, preAlign) 逐字节等于 alignOutput}，
 * 且 {@code inventedNames}/{@code methodMapping}/{@code bridgeCount} 与当轮一致。离线重放不得依赖
 * 任何运行期全局状态（不读 store、不读 RemoldAgent 缓存）。</p>
 */
public final class BytecodePack {

	/** 导出目录属性名。 */
	public static final String DIR_PROPERTY = "remold.vcs.pack_dir";

	/** 导出包格式版本：清单字段集合或布局变化时递增（回放端按它判兼容）。 */
	public static final int FORMAT_VERSION = 1;

	/** 链式布局下每代子目录前缀，后接十进制 {@code seq}（从 1 开始）。 */
	public static final String ROUND_DIR_PREFIX = "seq-";

	/** 每代清单文件名。 */
	public static final String MANIFEST_FILE = "manifest.txt";

	private static final String PACK_SUFFIX = ".pack";
	private static final String TMP_SUFFIX = ".tmp";

	private static final AtomicLong SEQ = new AtomicLong();

	/** 一个"类 × 一代"的回放条目。 */
	public static final class Entry {
		public final String className;
		public final byte[] oldEffective;
		public final byte[] preAlign;
		public final byte[] alignOutput;
		public final byte[] jvmEffective;
		public final Set<String> inventedNames;
		public final Map<String, String> methodMapping;
		public final int bridgeCount;

		public Entry(String className, byte[] oldEffective, byte[] preAlign, byte[] alignOutput,
		             byte[] jvmEffective, Set<String> inventedNames,
		             Map<String, String> methodMapping, int bridgeCount) {
			this.className = className;
			this.oldEffective = oldEffective == null ? new byte[0] : oldEffective;
			this.preAlign = preAlign == null ? new byte[0] : preAlign;
			this.alignOutput = alignOutput == null ? new byte[0] : alignOutput;
			this.jvmEffective = jvmEffective == null ? new byte[0] : jvmEffective;
			this.inventedNames = inventedNames == null ? Set.of() : Set.copyOf(inventedNames);
			this.methodMapping = methodMapping == null ? Map.of() : Map.copyOf(methodMapping);
			this.bridgeCount = bridgeCount;
		}
	}

	/**
	 * 一轮已提交代的导出读数：清单里的契约字段 + 该代目录内的条目（按类名升序）。
	 *
	 * @param seq       已提交代序号（从 1 开始，只在链内唯一）
	 * @param id        该代内容寻址 id（{@link Changeset#id()}）
	 * @param parentId  上一代 id；首代为 {@code ""}
	 * @param patchTiming 提交时刻字段补丁状态（{@code BEFORE_COMMIT} / {@code AFTER_COMMIT}）
	 */
	public record Round(long seq, String id, String parentId, String roundId, long loaderScope,
	                    String algoVersion, String patchTiming, List<Entry> entries) {

		public Round {
			id = id == null ? "" : id;
			parentId = parentId == null ? "" : parentId;
			roundId = roundId == null ? "" : roundId;
			algoVersion = algoVersion == null ? "" : algoVersion;
			patchTiming = patchTiming == null ? "" : patchTiming;
			entries = entries == null ? List.of() : List.copyOf(entries);
		}

		/** 按类名取条目（内部名，如 {@code probe/Subject}）。 */
		public Optional<Entry> entry(String className) {
			for (Entry e : entries) {
				if (e.className.equals(className)) return Optional.of(e);
			}
			return Optional.empty();
		}
	}

	private BytecodePack() {
	}

	/** 是否启用导出；未启用时所有写入口是 no-op。 */
	public static boolean enabled() {
		String dir = System.getProperty(DIR_PROPERTY);
		return dir != null && !dir.isBlank();
	}

	/** 当前导出目录；未启用返回 {@code null}。 */
	public static Path dir() {
		String dir = System.getProperty(DIR_PROPERTY);
		return dir == null || dir.isBlank() ? null : Path.of(dir);
	}

	/** 追加一个条目到<b>单轮兼容布局</b>；未启用或写入失败只返回 {@code false}，绝不影响热更主流程。 */
	public static boolean append(Entry e) {
		Path root = dir();
		if (root == null || e == null) return false;
		try {
			Files.createDirectories(root);
			writeEntryAtomically(root, String.format("%08d%s", SEQ.incrementAndGet(), PACK_SUFFIX), e);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * 把一轮已提交代导出成 {@code seq-<n>/}（条目取自单轮布局里<b>内容匹配</b>的条目）。
	 *
	 * <p>这是提交刻度的写入口：调用方在 {@link ChangesetLog#commit} 返回之后调一次即可，
	 * 不需要另传字节。匹配条件是"类名相同 + {@code preAlign}/{@code jvmEffective} 的 SHA-256
	 * 与该代 {@link Changeset.Entry} 的 {@code rawHash}/{@code effHash} 相等"，因此与
	 * append 的先后顺序无关，也不会把别的代的字节认成这一代。</p>
	 *
	 * @return 目录与清单都写成返回 {@code true}；未启用/该代条目一个都没匹配上也返回 {@code true}
	 *         （清单会记 {@code entryCount=0}，由验收侧断言）
	 */
	public static boolean exportRound(Changeset cs) {
		return exportRound(dir(), cs);
	}

	/** {@link #exportRound(Changeset)} 的显式目录版本。 */
	public static boolean exportRound(Path root, Changeset cs) {
		if (root == null || cs == null) return false;
		List<Entry> available;
		try {
			available = Files.isDirectory(root) ? readAll(root) : List.of();
		} catch (Throwable t) {
			available = List.of();
		}
		return exportRound(root, cs, matchEntries(cs, available));
	}

	/** 把一轮已提交代按给定条目导出成 {@code seq-<n>/}；重复导出覆盖为同一内容。 */
	public static boolean exportRound(Changeset cs, Collection<Entry> entries) {
		return exportRound(dir(), cs, entries);
	}

	/** 显式目录 + 显式条目的导出入口。 */
	public static boolean exportRound(Path root, Changeset cs, Collection<Entry> entries) {
		if (root == null || cs == null) return false;
		try {
			Path roundDir = roundDir(root, cs.seq());
			Files.createDirectories(roundDir);
			clearDir(roundDir);
			Map<String, Entry> sorted = new TreeMap<>();
			if (entries != null) {
				for (Entry e : entries) {
					if (e != null && e.className != null) sorted.put(e.className, e);
				}
			}
			List<Entry> ordered = new ArrayList<>(sorted.values());
			int index = 0;
			for (Entry e : ordered) {
				writeEntryAtomically(roundDir,
				 String.format("%08d%s", ++index, PACK_SUFFIX), e);
			}
			writeManifestAtomically(roundDir.resolve(MANIFEST_FILE), cs, ordered);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** 已导出的 {@code seq} 升序列表（只认带清单的子目录）；未启用或目录不存在返回空表。 */
	public static List<Long> chainRounds() {
		return chainRounds(dir());
	}

	/** {@link #chainRounds()} 的显式目录版本。 */
	public static List<Long> chainRounds(Path root) {
		if (root == null || !Files.isDirectory(root)) return List.of();
		TreeSet<Long> seqs = new TreeSet<>();
		try (Stream<Path> s = Files.list(root)) {
			for (Path p : s.toList()) {
				if (!Files.isDirectory(p)) continue;
				long seq = roundSeqOf(p.getFileName().toString());
				if (seq > 0 && Files.isRegularFile(p.resolve(MANIFEST_FILE))) seqs.add(seq);
			}
		} catch (Throwable t) {
			// 目录读不了就如实返回已扫到的部分，不抛给热更主流程。
			return List.copyOf(seqs);
		}
		return List.copyOf(seqs);
	}

	/** 读回某一代；该代未导出或清单缺失/损坏时返回 {@link Optional#empty()}。 */
	public static Optional<Round> round(long seq) {
		return round(dir(), seq);
	}

	/** {@link #round(long)} 的显式目录版本。 */
	public static Optional<Round> round(Path root, long seq) {
		if (root == null || seq < 1) return Optional.empty();
		Path roundDir = roundDir(root, seq);
		Path manifest = roundDir.resolve(MANIFEST_FILE);
		if (!Files.isDirectory(roundDir) || !Files.isRegularFile(manifest)) return Optional.empty();
		try {
			Map<String, String> m = readManifest(manifest);
			List<Entry> entries = readAll(roundDir);
			return Optional.of(new Round(seq, m.getOrDefault("changesetId", ""),
			 m.getOrDefault("parentId", ""), m.getOrDefault("roundId", ""),
			 parseLong(m.get("loaderScope")), m.getOrDefault("algoVersion", ""),
			 m.getOrDefault("patchTiming", ""), entries));
		} catch (Throwable t) {
			return Optional.empty();
		}
	}

	/** 某代子目录路径 {@code <root>/seq-<n>}。 */
	public static Path roundDir(Path root, long seq) {
		return root.resolve(ROUND_DIR_PREFIX + seq);
	}

	/** 某代清单文件路径。 */
	public static Path manifestPath(Path root, long seq) {
		return roundDir(root, seq).resolve(MANIFEST_FILE);
	}

	/** 读出目录根下全部条目（单轮兼容布局），按写入序返回；不递归进 {@code seq-*} 子目录。 */
	public static List<Entry> readAll(Path root) throws IOException {
		List<Path> files;
		try (Stream<Path> s = Files.list(root)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(PACK_SUFFIX))
				.sorted(Comparator.comparing(p -> p.getFileName().toString()))
				.toList();
		}
		List<Entry> out = new ArrayList<>(files.size());
		for (Path p : files) {
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
				out.add(readEntry(in));
			}
		}
		return out;
	}

	/** 清空导出目录内本包格式的文件与全部 {@code seq-*} 子目录（测试复用目录时用）。 */
	public static void clear(Path root) throws IOException {
		if (root == null || !Files.isDirectory(root)) return;
		try (Stream<Path> s = Files.list(root)) {
			for (Path p : s.toList()) {
				String n = p.getFileName().toString();
				if (Files.isDirectory(p) && roundSeqOf(n) > 0) deleteTree(p);
				else if (n.endsWith(PACK_SUFFIX) || n.endsWith(TMP_SUFFIX)) Files.deleteIfExists(p);
			}
		}
		SEQ.set(0L);
	}

	// ---- 条目序列化：单轮布局与链式布局共用同一格式 ----

	private static void writeEntryAtomically(Path dir, String name, Entry e) throws IOException {
		Path tmp = dir.resolve(name + TMP_SUFFIX);
		Path dst = dir.resolve(name);
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
				Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
			out.writeUTF(e.className == null ? "" : e.className);
			out.writeInt(e.bridgeCount);
			writeBytes(out, e.oldEffective);
			writeBytes(out, e.preAlign);
			writeBytes(out, e.alignOutput);
			writeBytes(out, e.jvmEffective);
			out.writeInt(e.inventedNames.size());
			for (String n : e.inventedNames) out.writeUTF(n);
			out.writeInt(e.methodMapping.size());
			for (Map.Entry<String, String> en : e.methodMapping.entrySet()) {
				out.writeUTF(en.getKey());
				out.writeUTF(en.getValue());
			}
		}
		Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
	}

	private static Entry readEntry(DataInputStream in) throws IOException {
		String className = in.readUTF();
		int bridges = in.readInt();
		byte[] oldEff = readBytes(in);
		byte[] preAlign = readBytes(in);
		byte[] alignOut = readBytes(in);
		byte[] jvmEff = readBytes(in);
		int invN = in.readInt();
		Set<String> invented = new LinkedHashSet<>();
		for (int i = 0; i < invN; i++) invented.add(in.readUTF());
		int mapN = in.readInt();
		Map<String, String> mapping = new LinkedHashMap<>();
		for (int i = 0; i < mapN; i++) mapping.put(in.readUTF(), in.readUTF());
		return new Entry(className, oldEff, preAlign, alignOut, jvmEff, invented, mapping, bridges);
	}

	// ---- 清单（每代一份，字段顺序固定，值做最小转义） ----

	private static void writeManifestAtomically(Path dst, Changeset cs, List<Entry> entries)
	 throws IOException {
		StringBuilder sb = new StringBuilder(256 + entries.size() * 160);
		sb.append("formatVersion=").append(FORMAT_VERSION).append('\n');
		sb.append("seq=").append(cs.seq()).append('\n');
		sb.append("changesetId=").append(escape(cs.id())).append('\n');
		sb.append("parentId=").append(escape(cs.parentId() == null ? "" : cs.parentId())).append('\n');
		sb.append("roundId=").append(escape(cs.roundId())).append('\n');
		sb.append("loaderScope=").append(cs.loaderScope()).append('\n');
		sb.append("algoVersion=").append(escape(cs.algoVersion())).append('\n');
		sb.append("patchTiming=")
		 .append(cs.patchTiming() == null ? "" : cs.patchTiming().name()).append('\n');
		sb.append("buildId=").append(escape(cs.buildId())).append('\n');
		sb.append("entryCount=").append(entries.size()).append('\n');
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			sb.append("entry.").append(i).append(".name=").append(escape(e.className)).append('\n');
			sb.append("entry.").append(i).append(".preAlignSha256=")
			 .append(AlignmentMetaStore.sha256Hex(e.preAlign)).append('\n');
			sb.append("entry.").append(i).append(".alignOutputSha256=")
			 .append(AlignmentMetaStore.sha256Hex(e.alignOutput)).append('\n');
			sb.append("entry.").append(i).append(".jvmEffSha256=")
			 .append(AlignmentMetaStore.sha256Hex(e.jvmEffective)).append('\n');
			sb.append("entry.").append(i).append(".bridgeCount=").append(e.bridgeCount).append('\n');
		}
		Path tmp = dst.resolveSibling(dst.getFileName() + TMP_SUFFIX);
		Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
		 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
	}

	private static Map<String, String> readManifest(Path manifest) throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		for (String raw : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
			String line = raw;
			if (line.isEmpty() || line.charAt(0) == '#') continue;
			int eq = line.indexOf('=');
			if (eq <= 0) continue;
			out.put(line.substring(0, eq), unescape(line.substring(eq + 1)));
		}
		return out;
	}

	private static String escape(String value) {
		if (value == null) return "";
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String unescape(String value) {
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c != '\\' || i + 1 >= value.length()) {
				sb.append(c);
				continue;
			}
			char next = value.charAt(++i);
			switch (next) {
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				default -> sb.append(next);
			}
		}
		return sb.toString();
	}

	// ---- 内容匹配：只用哈希判定，不依赖 append 与 commit 的先后 ----

	private static List<Entry> matchEntries(Changeset cs, List<Entry> available) {
		if (cs.entries().isEmpty() || available.isEmpty()) return List.of();
		Map<String, List<Entry>> byClass = new LinkedHashMap<>();
		for (Entry e : available) {
			byClass.computeIfAbsent(e.className, ignored -> new ArrayList<>()).add(e);
		}
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<String, Changeset.Entry> want : new TreeMap<>(cs.entries()).entrySet()) {
			List<Entry> candidates = byClass.get(want.getKey());
			if (candidates == null) continue;
			String rawHash = want.getValue().rawHash();
			String effHash = want.getValue().effHash();
			for (Entry cand : candidates) {
				if (!AlignmentMetaStore.sha256Hex(cand.preAlign).equals(rawHash)) continue;
				if (!AlignmentMetaStore.sha256Hex(cand.jvmEffective).equals(effHash)) continue;
				out.add(cand);
				break;
			}
		}
		return out;
	}

	// ---- 小工具 ----

	private static long roundSeqOf(String name) {
		if (name == null || !name.startsWith(ROUND_DIR_PREFIX)) return -1L;
		String digits = name.substring(ROUND_DIR_PREFIX.length());
		if (digits.isEmpty()) return -1L;
		try {
			long seq = Long.parseLong(digits);
			return seq >= 1 ? seq : -1L;
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

	private static long parseLong(String value) {
		if (value == null || value.isBlank()) return -1L;
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

	private static void clearDir(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.toList()) {
				String n = p.getFileName().toString();
				if (n.endsWith(PACK_SUFFIX) || n.endsWith(TMP_SUFFIX)) Files.deleteIfExists(p);
			}
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		List<Path> all;
		try (Stream<Path> s = Files.walk(dir)) {
			all = s.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path p : all) Files.deleteIfExists(p);
	}

	private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
		out.writeInt(b.length);
		out.write(b);
	}

	private static byte[] readBytes(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < 0 || n > 64 * 1024 * 1024) throw new IOException("corrupt pack: byte length " + n);
		byte[] b = new byte[n];
		in.readFully(b);
		return b;
	}

	/** UTF-8 辅助：包内字符串一律 UTF（DataOutputStream#writeUTF 的 modified UTF-8）。 */
	static String utf8(byte[] bytes) {
		return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
	}
}
