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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>回放契约</b>：读出的每个条目满足
 * {@code LambdaAligner.align(oldEffective, preAlign) 逐字节等于 alignOutput}，
 * 且 {@code inventedNames}/{@code methodMapping}/{@code bridgeCount} 与当轮一致。离线重放不得依赖
 * 任何运行期全局状态（不读 store、不读 RemoldAgent 缓存）。</p>
 */
public final class BytecodePack {

	/** 导出目录属性名。 */
	public static final String DIR_PROPERTY = "remold.vcs.pack_dir";

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

	/** 追加一个条目；未启用或写入失败只返回 {@code false}，绝不影响热更主流程。 */
	public static boolean append(Entry e) {
		Path root = dir();
		if (root == null || e == null) return false;
		try {
			Files.createDirectories(root);
			String name = String.format("%08d.pack", SEQ.incrementAndGet());
			Path tmp = root.resolve(name + ".tmp");
			Path dst = root.resolve(name);
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
					Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
				out.writeUTF(e.className);
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
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** 读出目录内全部条目，按写入序返回。 */
	public static List<Entry> readAll(Path root) throws IOException {
		List<Path> files;
		try (Stream<Path> s = Files.list(root)) {
			files = s.filter(p -> p.getFileName().toString().endsWith(".pack"))
				.sorted(Comparator.comparing(p -> p.getFileName().toString()))
				.toList();
		}
		List<Entry> out = new ArrayList<>(files.size());
		for (Path p : files) {
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
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
				out.add(new Entry(className, oldEff, preAlign, alignOut, jvmEff, invented, mapping, bridges));
			}
		}
		return out;
	}

	/** 清空导出目录内本包格式的文件（测试复用目录时用）。 */
	public static void clear(Path root) throws IOException {
		if (root == null || !Files.isDirectory(root)) return;
		try (Stream<Path> s = Files.list(root)) {
			for (Path p : s.toList()) {
				String n = p.getFileName().toString();
				if (n.endsWith(".pack") || n.endsWith(".tmp")) Files.deleteIfExists(p);
			}
		}
		SEQ.set(0L);
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
