import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 探针的**公共前置断言**：实际加载的 agent 类必须与刚刚构建出的 class 文件逐字节一致，并打印当前 git SHA 与
 * dirty 标志。
 *
 * <p>为什么必须集中在一处：这条断言的意义是"探针读到的是我这一轮改的代码"。它一旦缺失，产出的每个"绿"都可能是
 * 上一版代码的读数——本仓库已经为此付过代价（变异不转红被误判成"断言不承载因果"，实际是工作区切了分支）。
 * 逐个探针各加一份必然会有漏项，所以只留这一个入口，所有 JBR 探针在 {@code main} 开头调用它。</p>
 *
 * <p>比较对象是 {@code core/build/classes/java/main} 下的产物，而不是 jar 时间戳：时间戳受文件系统精度与复制
 * 影响，字节哈希才是"同一份代码"的直接证据。</p>
 */
public final class AgentFreshness {

	private AgentFreshness() { }

	/** 参与比对的 agent 类（相对 classpath 与构建产物的同一条相对路径）。 */
	private static final String[] AGENT_CLASSES = {
		"remold/RemoldAgent.class",
		"remold/ref/InitFix.class",
		"remold/ClassDiffUtil.class",
	};

	/**
	 * 校验并汇报。
	 *
	 * @param check 断言出口：{@code (ok, message)}
	 * @param note  信息出口：{@code (message)}
	 * @return 全部被比较的类都一致时返回 {@code true}
	 */
	public static boolean verify(BiConsumer<Boolean, String> check, Consumer<String> note) {
		note.accept("freshness: workspace " + gitState());
		boolean allOk = true;
		int compared = 0;
		for (String resource : AGENT_CLASSES) {
			byte[] loaded;
			try {
				loaded = readResource(resource);
			} catch (Throwable t) {
				// 资源缺失不等于代码陈旧（有的探针不带全量 agent），只记录，不判失败。
				note.accept("freshness: " + resource + " is not on the probe classpath, skipped");
				continue;
			}
			Path built = findBuiltClass(resource);
			if (built == null) {
				note.accept("freshness: no build output for " + resource + ", skipped");
				continue;
			}
			String loadedHash = sha256(loaded);
			String builtHash;
			try {
				builtHash = sha256(Files.readAllBytes(built));
			} catch (Throwable t) {
				check.accept(false, "freshness: cannot read build output " + built + ": " + t);
				allOk = false;
				continue;
			}
			boolean same = loadedHash.equals(builtHash);
			check.accept(same, "freshness: loaded " + resource + " matches the build output ("
				+ loadedHash.substring(0, 12) + ")");
			allOk &= same;
			compared++;
		}
		if (compared == 0) {
			check.accept(false, "freshness: no agent class could be compared - the probe may be running stale code");
			return false;
		}
		return allOk;
	}

	/** 当前 git 状态：只读 HEAD 与分支名，脏标记由是否存在未提交改动决定。 */
	public static String gitState() {
		String head = run("git", "rev-parse", "--short", "HEAD");
		String branch = run("git", "rev-parse", "--abbrev-ref", "HEAD");
		String dirty = run("git", "status", "--porcelain").isEmpty() ? "clean" : "DIRTY";
		return "sha=" + head + " branch=" + branch + " tree=" + dirty;
	}

	private static String run(String... command) {
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes()).trim();
			p.waitFor();
			return out;
		} catch (Throwable t) {
			return "unavailable";
		}
	}

	private static byte[] readResource(String resource) throws Throwable {
		try (java.io.InputStream in = AgentFreshness.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) throw new IllegalStateException("not on classpath: " + resource);
			return in.readAllBytes();
		}
	}

	private static Path findBuiltClass(String resource) {
		Path cwd = Paths.get("").toAbsolutePath();
		for (Path base : new Path[] { cwd, cwd.resolve(".."), cwd.resolve("../..") }) {
			Path p = base.resolve("core/build/classes/java/main").resolve(resource);
			if (Files.isRegularFile(p)) return p;
		}
		return null;
	}

	private static String sha256(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Throwable t) {
			return "unavailable";
		}
	}
}
