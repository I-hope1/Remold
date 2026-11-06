package remold.fabric;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static remold.RemoldAgent.info;
import static remold.RemoldAgent.warn;

/**
 * Fabric 开发运行期的类路径分组注入（{@code fabric.classPathGroups}）。
 *
 * <p><b>为什么必须由 Agent 在 premain 里做</b>：Fabric Loader 在开发模式下用 {@code Knot} 按
 * <i>class path group</i> 模拟模组间的类隔离；组未声明时，同一工程的散装输出目录会被当成互相
 * 不可见的独立来源，热更后的字节码对已加载的宿主不可见，表现为<b>热更静默不生效</b>。
 * 这个属性必须在 Knot 初始化之前设置，因此只能落在 premain，且必须早于插件体系加载
 * （见 {@link remold.RemoldAgent#premain}）。</p>
 *
 * <p><b>分组规则</b>：把传入的监控目录按"父目录"归并，父目录是 {@code build} / {@code main} /
 * {@code java} / {@code production} 这类构建输出层级时上提到祖父目录 —— 目的是让
 * {@code classes} 与 {@code resources} 落进同一个组，而不是两个互斥的组。已有配置逐条保留，
 * 只在缺少整组时追加，避免覆盖用户手写的值。</p>
 *
 * <p><b>为什么独立成包</b>：这是加载器专有知识，不是热更引擎语义。集中到
 * {@code remold.integration} 后，core 的其余部分不出现 {@code fabric.*} 字样，
 * 需要下线或替换某个生态支持时只删这一个包。</p>
 */
public final class FabricClassPathGroups {

	private FabricClassPathGroups() { }

	/** 包级可见：按给定分隔符切 agentArgs；测试可显式传 ";" / ":"，不依赖宿主平台。 */
	static java.util.List<String> parseAgentArgs(String agentArgs, String pathSeparator) {
		java.util.List<String> out = new java.util.ArrayList<>();
		if (agentArgs == null || agentArgs.trim().isEmpty()) return out;
		for (String token : agentArgs.split(Pattern.quote(pathSeparator))) {
			token = token.trim();
			if (!token.isEmpty()) out.add(token);
		}
		return out;
	}

	/** 若当前运行时是 Fabric 开发环境，则按监控目录补齐 {@code fabric.classPathGroups}；否则什么都不做。 */
	public static void apply(String agentArgs) {
		try {
			boolean isFabric = System.getProperty("fabric.dli.config") != null
					|| System.getProperty("fabric.dli.main") != null
					|| "true".equalsIgnoreCase(System.getProperty("fabric.development"))
					|| (System.getProperty("sun.java.command") != null && System.getProperty("sun.java.command").contains("net.fabricmc."));
			if (!isFabric) return;

			String existing = System.getProperty("fabric.classPathGroups");
			Set<String> existingEntries = new HashSet<>();
			if (existing != null && !existing.trim().isEmpty()) {
				for (String g : existing.split(Pattern.quote(File.pathSeparator + File.pathSeparator))) {
					for (String p : g.split(Pattern.quote(File.pathSeparator))) {
						if (!p.trim().isEmpty()) {
							existingEntries.add(new File(p.trim()).getAbsolutePath());
						}
					}
				}
			}

			List<File> candidates = new ArrayList<>();
			for (String token : parseAgentArgs(agentArgs, File.pathSeparator)) {
				File f = new File(token);
				if (f.isDirectory() && !candidates.contains(f)) {
					candidates.add(f);
				}
			}

			Map<String, List<File>> groups = new LinkedHashMap<>();
			for (File dir : candidates) {
				File parent = dir.getParentFile();
				if (parent == null) continue;
				String parentKey = parent.getAbsolutePath();
				if (parent.getName().equalsIgnoreCase("production")
						|| parent.getName().equalsIgnoreCase("main")
						|| parent.getName().equalsIgnoreCase("java")
						|| parent.getName().equalsIgnoreCase("build")) {
					File grandParent = parent.getParentFile();
					if (grandParent != null) {
						parentKey = grandParent.getAbsolutePath();
					}
				}
				groups.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(dir);
			}

			StringBuilder sb = new StringBuilder();
			if (existing != null && !existing.trim().isEmpty()) {
				sb.append(existing.trim());
			}

			String sep = File.pathSeparator;
			String groupSep = sep + sep;

			for (List<File> grp : groups.values()) {
				if (grp.size() < 2) continue;
				boolean alreadyContained = true;
				for (File f : grp) {
					if (!existingEntries.contains(f.getAbsolutePath())) {
						alreadyContained = false;
						break;
					}
				}
				if (alreadyContained) continue;

				if (sb.length() > 0) {
					sb.append(groupSep);
				}
				for (int i = 0; i < grp.size(); i++) {
					if (i > 0) sb.append(sep);
					sb.append(grp.get(i).getAbsolutePath());
				}
			}

			if (sb.length() > 0 && !sb.toString().equals(existing)) {
				System.setProperty("fabric.classPathGroups", sb.toString());
				info("[Fabric] Configured fabric.classPathGroups=" + sb.toString());
			}
		} catch (Throwable t) {
			warn("[Fabric] Failed to auto-configure fabric.classPathGroups: " + t);
		}
	}
}
