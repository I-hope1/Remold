import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 常驻 javac 服务端：一次启动，反复编译。
 *
 * <p><b>为什么需要它</b>：外部 {@code javac} 单次约 2.4s，其中只有 0.4s 是 JVM 启动，其余近 2s
 * 是 javac 自己的模块/符号表初始化。所以"把进程留着"本身没用，必须<b>复用同一个
 * {@link JavaCompiler} 与 {@link StandardJavaFileManager}</b>，让符号表缓存一直热着——
 * 实测同样一次编译从 2370ms 降到 33~44ms。</p>
 *
 * <p><b>为什么不用进程内编译</b>：{@code ToolProvider.getSystemJavaCompiler()} 给的是<b>当前
 * JVM</b> 的 javac。本项目的测试要跑 8/11/17/21 的版本矩阵（lambda 命名规则、class 文件版本
 * 都随版本变），进程内会把"javac21"偷偷换成"javac25"，等于把测试测的东西换掉。所以这里每个
 * JDK 起一个进程，用的是那个 JDK 自己的 javac，语义与外部调用完全一致。</p>
 *
 * <p><b>本类必须能编成 v52</b>：它要在 JDK 8 上运行，所以只用 Java 8 的 API，且由专门的
 * Gradle 任务以 {@code --release 8} 编译到 {@code build/javacdaemon/}。</p>
 *
 * <p><b>协议</b>（逐行，字段 base64 编码，避免路径里的空白与编码问题）：</p>
 * <pre>
 *   请求  C &lt;outDir&gt; &lt;opts&gt; &lt;src1&gt; [src2 ...]     opts 以 \0 分隔
 *   响应  OK
 *         ERR &lt;诊断文本&gt;
 *   退出  Q
 * </pre>
 */
public final class JavacDaemonServer {

	private JavacDaemonServer() { }

	public static void main(String[] args) throws Exception {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			out.println("ERR\t" + enc("this JDK has no system java compiler (JRE only?)"));
			return;
		}
		// 整个进程生命周期内只建一次：file manager 里缓存着平台符号表，这是提速的来源。
		StandardJavaFileManager files =
			compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);

		String line;
		while ((line = in.readLine()) != null) {
			if (line.equals("Q")) break;
			try {
				out.println(handle(compiler, files, line));
			} catch (Throwable t) {
				out.println("ERR\t" + enc(String.valueOf(t)));
			}
		}
	}

	private static String handle(JavaCompiler compiler, StandardJavaFileManager files, String line)
		throws java.io.IOException {
		String[] parts = line.split("\t", -1);
		if (parts.length < 4 || !"C".equals(parts[0])) return "ERR\t" + enc("bad request: " + line);

		String  outDir = dec(parts[1]);
		String  rawOpts = dec(parts[2]);
		List<File> sources = new ArrayList<File>();
		for (int i = 3; i < parts.length; i++) sources.add(new File(dec(parts[i])));

		String[] raw = rawOpts.length() == 0 ? new String[0] : rawOpts.split("\u0000", -1);
		List<String> options = new ArrayList<String>();
		String classpath = null;
		for (int i = 0; i < raw.length; i++) {
			String o = raw[i];
			if (("-cp".equals(o) || "-classpath".equals(o)) && i + 1 < raw.length) {
				classpath = raw[++i];
				continue;
			}
			options.add(o);
		}

		// 输出位置与类路径必须每轮显式设置，**不能**靠命令行里的 -d / -cp：
		// JDK 8 的 StandardJavaFileManager 会把第一次设的 CLASS_OUTPUT 缓存住，
		// 之后即使换 -d，产物仍然写进第一个目录（实测：两次编译都返回 OK，
		// 但第二个 class 落在了第一个 outDir 里）。这在"复用同一个 file manager"
		// 的前提下是必然要处理的，21 上恰好不发作。
		if (outDir.length() > 0) {
			files.setLocation(StandardLocation.CLASS_OUTPUT, java.util.Collections.singletonList(new File(outDir)));
		}
		files.setLocation(StandardLocation.CLASS_PATH,
			classpath == null ? null : splitPath(classpath));

		StringWriter diagnostics = new StringWriter();
		boolean ok = compiler.getTask(diagnostics, files, null, options, null,
			files.getJavaFileObjectsFromFiles(sources)).call();
		return ok ? "OK" : "ERR\t" + enc(diagnostics.toString());
	}

	/** {@code a;b} / {@code a:b} → 文件列表；空串视为"没有条目"。 */
	private static List<File> splitPath(String path) {
		List<File> out = new ArrayList<File>();
		for (String p : path.split(File.pathSeparator)) {
			if (p.length() > 0) out.add(new File(p));
		}
		return out;
	}

	private static String enc(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String dec(String s) {
		return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
	}
}
