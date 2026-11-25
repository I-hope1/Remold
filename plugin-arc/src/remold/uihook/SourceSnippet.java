package remold.uihook;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import remold.util.Names;

/**
 * 一个创建站点周围的只读源码片段，供 UI 元素调试视图使用。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>纯读：不修改追踪器，不参与任何热更判决。</li>
 *   <li>片段取自<b>当前</b>磁盘源码。目标行是元素创建时记录的栈帧行号；宿主类被重定义后
 *       磁盘源码可能已漂移，因此目标行可能过期，片段只保证是一个窗口。</li>
 *   <li>解析失败不抛异常：{@link #text()} 输出位置与一条原因。</li>
 * </ul>
 */
public final class SourceSnippet {

	/** 默认上下文行数：目标行前后各 4 行。 */
	public static final int DEFAULT_CONTEXT = 4;

	/** 源码根目录的系统属性（路径分隔符分隔）；{@code remold.agent.source_roots} 作为别名。 */
	private static final String SOURCE_ROOTS_PROPERTY = "remold.source_roots";
	private static final String SOURCE_ROOTS_PROPERTY_ALIAS = "remold.agent.source_roots";

	/** 片段中的一行源码。 */
	public record Line(int number, String text) {}

	private final String hostClass;
	private final String method;
	private final int targetLine;
	private final String sourcePath;
	private final List<Line> lines;
	private final String note;
	private final int context;

	private SourceSnippet(String hostClass, String method, int targetLine, String sourcePath,
	                      List<Line> lines, String note, int context) {
		this.hostClass = hostClass == null ? "" : hostClass;
		this.method = method == null ? "" : method;
		this.targetLine = targetLine;
		this.sourcePath = sourcePath;
		this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
		this.note = note;
		this.context = context;
	}

	public String hostClass() { return hostClass; }
	public String method() { return method; }
	public int targetLine() { return targetLine; }
	/** 源码文件的绝对路径；未解析到时为 {@code null}。 */
	public String sourcePath() { return sourcePath; }
	public List<Line> lines() { return lines; }
	/** 解析说明（未解析到源码、行号越界等）；片段完整时为 {@code null}。 */
	public String note() { return note; }
	public int context() { return context; }

	/** 渲染位置头、可选说明与带 {@code >} 标记的源码窗口。 */
	public String text() {
		StringBuilder sb = new StringBuilder(256);
		sb.append(Names.external(hostClass));
		if (!method.isEmpty()) sb.append('#').append(method);
		sb.append(':').append(targetLine);
		if (sourcePath != null) sb.append("  [").append(sourcePath).append(']');
		sb.append('\n');
		if (note != null) sb.append("  ").append(note).append('\n');

		int width = 1;
		for (Line l : lines) width = Math.max(width, Integer.toString(l.number()).length());
		for (Line l : lines) {
			sb.append(l.number() == targetLine ? "> " : "  ");
			String num = Integer.toString(l.number());
			for (int i = num.length(); i < width; i++) sb.append(' ');
			sb.append(num).append(" | ").append(l.text()).append('\n');
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return text();
	}

	/** 只带位置与原因、没有源码窗口的片段。 */
	public static SourceSnippet unresolved(String hostClass, String method, int targetLine, String note) {
		return new SourceSnippet(hostClass, method, targetLine, null, List.of(), note, DEFAULT_CONTEXT);
	}

	/** 从显式源码文件构建片段（测试与直接调用方的入口）。 */
	public static SourceSnippet fromFile(Path file, String hostClass, String method, int targetLine, int context) {
		int ctx = Math.max(0, context);
		if (file == null) {
			return new SourceSnippet(hostClass, method, targetLine, null, List.of(), "no source file given", ctx);
		}
		String path = file.toAbsolutePath().toString();
		try {
			List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
			if (targetLine < 1) {
				return new SourceSnippet(hostClass, method, targetLine, path, List.of(),
				 "target line " + targetLine + " is not a source line", ctx);
			}
			if (all.isEmpty()) {
				return new SourceSnippet(hostClass, method, targetLine, path, List.of(),
				 "source file is empty", ctx);
			}
			String note = null;
			if (targetLine > all.size()) {
				note = "target line " + targetLine + " is outside the file (" + all.size() + " lines)";
			}
			int start = Math.max(1, targetLine - ctx);
			int end = Math.min(all.size(), targetLine + ctx);
			List<Line> lines = new ArrayList<>(Math.max(0, end - start + 1));
			for (int n = start; n <= end; n++) lines.add(new Line(n, all.get(n - 1)));
			return new SourceSnippet(hostClass, method, targetLine, path, lines, note, ctx);
		} catch (IOException | RuntimeException e) {
			return new SourceSnippet(hostClass, method, targetLine, path, List.of(),
			 "cannot read source file: " + e.getClass().getSimpleName(), ctx);
		}
	}

	/** 用默认源码根解析源码文件并构建片段。 */
	public static SourceSnippet at(String hostClass, String method, int targetLine, String sourceFileName, int context) {
		return at(hostClass, method, targetLine, sourceFileName, context, defaultSourceRoots());
	}

	/** 用给定源码根解析源码文件并构建片段。 */
	public static SourceSnippet at(String hostClass, String method, int targetLine, String sourceFileName,
	                               int context, List<Path> roots) {
		int ctx = Math.max(0, context);
		Path file = resolveSourceFile(hostClass, sourceFileName, roots);
		if (file == null) {
			return new SourceSnippet(hostClass, method, targetLine, null, List.of(),
			 "source file not found under " + roots, ctx);
		}
		return fromFile(file, hostClass, method, targetLine, ctx);
	}

	/**
	 * 默认源码根：{@code -Dremold.source_roots}（或别名 {@code -Dremold.agent.source_roots}），
	 * 按主机路径分隔符切分；未设置时探测工作目录下的 {@code src/main/java} 与
	 * {@code src/main/kotlin}。
	 */
	public static List<Path> defaultSourceRoots() {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		String prop = System.getProperty(SOURCE_ROOTS_PROPERTY);
		if (prop == null || prop.isBlank()) prop = System.getProperty(SOURCE_ROOTS_PROPERTY_ALIAS);
		if (prop != null && !prop.isBlank()) {
			for (String part : prop.split(Pattern.quote(File.pathSeparator))) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) roots.add(Paths.get(trimmed).toAbsolutePath());
			}
		}
		if (roots.isEmpty()) {
			Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
			roots.add(cwd.resolve("src/main/java"));
			roots.add(cwd.resolve("src/main/kotlin"));
		}
		return new ArrayList<>(roots);
	}

	/**
	 * 在源码根下解析 {@code 包路径/源文件名}。源文件名缺省时按内部名推导：
	 * 去掉包路径与 {@code $} 之后的部分，依次尝试 {@code .java} 与 {@code .kt}。
	 */
	public static Path resolveSourceFile(String hostClass, String sourceFileName, List<Path> roots) {
		if (roots == null || roots.isEmpty()) return null;
		String internal = hostClass == null ? "" : hostClass;
		int slash = internal.lastIndexOf('/');
		String packageDir = slash < 0 ? "" : internal.substring(0, slash);

		List<String> names = new ArrayList<>(2);
		if (sourceFileName != null && !sourceFileName.isBlank()) {
			names.add(sourceFileName);
		} else {
			String simple = slash < 0 ? internal : internal.substring(slash + 1);
			int dollar = simple.indexOf('$');
			if (dollar >= 0) simple = simple.substring(0, dollar);
			if (!simple.isEmpty()) {
				names.add(simple + ".java");
				names.add(simple + ".kt");
			}
		}
		if (names.isEmpty()) return null;

		for (Path root : roots) {
			if (root == null) continue;
			for (String name : names) {
				Path candidate = packageDir.isEmpty() ? root.resolve(name) : root.resolve(packageDir).resolve(name);
				if (Files.isRegularFile(candidate)) return candidate;
			}
		}
		return null;
	}
}
