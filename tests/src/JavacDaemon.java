import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 常驻 javac 的客户端：每个 javac 路径（也就是每个 JDK）一个服务进程，复用它的编译器实例。
 *
 * <p>实测：外部 {@code javac} 单次约 2370ms（其中 JVM 启动只占 410ms，其余是 javac 自己的
 * 符号表初始化），复用编译器实例后约 33~44ms。</p>
 *
 * <p><b>语义不变</b>：服务端用的是那个 JDK 自己的 javac，选项原样透传，产物与外部调用一致。
 * 这也是不用进程内编译的原因——{@code ToolProvider} 只会给你当前 JVM 的 javac。</p>
 *
 * <p><b>失败一律回退</b>：守护进程不可用（属性没设、进程起不来、协议异常、JDK 没有编译器）时
 * 抛 {@link Unavailable}，调用方照旧走外部进程。宁可变慢，不能让测试因为脚手架而红。</p>
 *
 * <p>关掉：{@code -Dhstest.javacDaemon=false}。</p>
 */
final class JavacDaemon {

	private JavacDaemon() { }

	/** 守护进程不可用，调用方应回退到外部 javac。 */
	static final class Unavailable extends RuntimeException {
		private static final long serialVersionUID = 1L;
		Unavailable(String msg) { super(msg); }
	}

	private static final Map<String, Session> SESSIONS = new HashMap<>();

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			synchronized (SESSIONS) {
				for (Session s : SESSIONS.values()) s.close();
				SESSIONS.clear();
			}
		}, "javac-daemon-shutdown"));
	}

	/** 是否配置了守护进程（只看开关，不代表一定能起来）。 */
	static boolean configured() {
		return !"false".equalsIgnoreCase(System.getProperty("hstest.javacDaemon"))
		       && System.getProperty("hstest.javacDaemon.classpath") != null;
	}

	/**
	 * 编译一组源文件。
	 *
	 * @return {@code null} 表示成功；否则是 javac 的诊断文本
	 * @throws Unavailable 守护进程不可用，调用方应回退到外部 javac
	 */
	static String compile(String javacPath, String outDir, List<String> options, List<File> sources) {
		if (!configured()) throw new Unavailable("javac daemon not configured");
		Session session = session(javacPath);
		return session.compile(outDir, options, sources);
	}

	private static Session session(String javacPath) {
		// 同一个 JDK 可能以两种写法出现（toolchain 给 F:\...，兜底给 F:/...），
		// 归一化之后只起一个进程。
		String key;
		try {
			key = new File(javacPath).getCanonicalPath();
		} catch (IOException e) {
			key = javacPath;
		}
		synchronized (SESSIONS) {
			Session s = SESSIONS.get(key);
			if (s != null && s.alive()) return s;
			if (s != null) SESSIONS.remove(key);
			s = Session.start(javacPath);
			SESSIONS.put(key, s);
			return s;
		}
	}

	/** 与 javac 同目录的 java，保证服务端跑在同一个 JDK 上。 */
	private static File javaFor(String javacPath) {
		File javac = new File(javacPath);
		File bin = javac.getParentFile();
		boolean win = System.getProperty("os.name").toLowerCase().contains("win");
		File java = new File(bin, win ? "java.exe" : "java");
		return java.isFile() ? java : new File(bin, "java");
	}

	private static final class Session {
		private final Process proc;
		private final BufferedWriter to;
		private final BufferedReader from;

		private Session(Process proc) {
			this.proc = proc;
			this.to = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
			this.from = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
		}

		static Session start(String javacPath) {
			String cp = System.getProperty("hstest.javacDaemon.classpath");
			File java = javaFor(javacPath);
			if (!java.isFile()) throw new Unavailable("no java next to " + javacPath);
			try {
				ProcessBuilder pb = new ProcessBuilder(java.getAbsolutePath(), "-cp", cp, "JavacDaemonServer");
				// 不合并 stderr：JVM 会把 "Picked up JAVA_TOOL_OPTIONS" 打到 stderr，
				// 合并后会让按行读的协议错位。
				pb.redirectError(ProcessBuilder.Redirect.DISCARD);
				Session s = new Session(pb.start());
				// 一行实证：确认这次真的走了常驻进程，而不是悄悄回退成外部 javac。
				System.out.println("[javac-daemon] started: " + javacPath);
				return s;
			} catch (IOException e) {
				throw new Unavailable("cannot start javac daemon for " + javacPath + ": " + e);
			}
		}

		boolean alive() {
			return proc.isAlive();
		}

		synchronized String compile(String outDir, List<String> options, List<File> sources) {
			StringBuilder line = new StringBuilder("C\t").append(enc(outDir)).append('\t');
			line.append(enc(String.join("\u0000", options)));
			for (File f : sources) line.append('\t').append(enc(f.getAbsolutePath()));
			try {
				to.write(line.toString());
				to.write('\n');
				to.flush();
				String resp = from.readLine();
				if (resp == null) throw new IOException("daemon closed the pipe");
				if (resp.equals("OK")) return null;
				if (resp.startsWith("ERR\t")) return dec(resp.substring(4));
				throw new IOException("unexpected response: " + resp);
			} catch (IOException e) {
				close();
				throw new Unavailable("javac daemon failed: " + e);
			}
		}

		void close() {
			try {
				to.write("Q\n");
				to.flush();
			} catch (IOException ignored) {
				// 进程可能已经死了
			}
			proc.destroy();
		}
	}

	private static String enc(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String dec(String s) {
		return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
	}

	// --------------------------------------------------------------------------------------------
	// 命令行适配：把 runCmd(javac, "-g", ..., "-d", out, src...) 这种形态转成守护进程调用。
	// 解析不出来就返回 false，调用方照旧走外部进程。
	// --------------------------------------------------------------------------------------------

	private static final List<String> WITH_VALUE =
		List.of("-d", "-cp", "-classpath", "-source", "-target", "--release", "-encoding",
		        "-processorpath", "-bootclasspath", "-s", "-h", "-Xmaxerrs");

	/**
	 * 尝试用守护进程执行一条 javac 命令行。
	 *
	 * @return {@code true} 表示已由守护进程完成（成功）；{@code false} 表示调用方应自己执行
	 * @throws RuntimeException 守护进程跑通但编译失败（与外部 javac 非零退出等价）
	 */
	static boolean tryRun(String[] cmd) {
		if (!configured() || cmd.length < 2) return false;
		if (!new File(cmd[0]).getName().toLowerCase().startsWith("javac")) return false;

		String outDir = null;
		List<String> options = new ArrayList<>();
		List<File> sources = new ArrayList<>();
		for (int i = 1; i < cmd.length; i++) {
			String a = cmd[i];
			if (WITH_VALUE.contains(a) && i + 1 < cmd.length) {
				if (a.equals("-d")) outDir = cmd[++i];
				else {
					options.add(a);
					options.add(cmd[++i]);
				}
			} else if (a.startsWith("-")) {
				options.add(a);
			} else {
				sources.add(new File(a));
			}
		}
		if (sources.isEmpty()) return false;

		String diagnostics;
		try {
			diagnostics = compile(cmd[0], outDir, options, sources);
		} catch (Unavailable e) {
			return false;
		}
		if (diagnostics != null) {
			throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\nOutput:\n" + diagnostics);
		}
		return true;
	}
}
