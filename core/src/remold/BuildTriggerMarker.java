package remold;

import java.nio.charset.StandardCharsets;

/**
 * 构建完成 marker 的解析与格式化（纯函数，无 IO、无状态）。
 *
 * <p>marker 是构建工具（当前由 Gradle 插件写入）在编译任务结束后原子写出的普通文本文件。
 * agent 只依赖本类定义的契约，不依赖任何构建工具类型。格式见
 * {@code docs/runtime/01-build-trigger-mode.md} §2.3。</p>
 *
 * <p><b>时间域</b>：{@code build_end_millis} 是 {@link System#currentTimeMillis()} 的挂钟毫秒；
 * 与 {@link DebounceWindow} 的 {@link System#nanoTime()} 单调域不同，换算只发生在调用方。</p>
 */
final class BuildTriggerMarker {

	/** 首行固定标识。 */
	static final String MAGIC = "remold-build-trigger v1";
	/** 允许读取的最大字节数；超出即拒绝，避免被异常大文件拖住 watcher 线程。 */
	static final int MAX_BYTES = 8 * 1024;
	/** 挂钟偏差容忍窗口（毫秒）：陈旧/未来判定都使用它。 */
	static final long CLOCK_SKEW_MILLIS = 5000L;

	private BuildTriggerMarker() {}

	/** 解析结果：{@link #valid} 为 false 时 {@link #rejectReason} 必非空（英文，可直接进日志）。 */
	static final class Parsed {
		final boolean valid;
		final long    endMillis;
		final String  buildId;
		final String  task;
		final String  rejectReason;
		/** 与陈旧/未来边界的差值（毫秒）；仅这两类拒绝有意义，其余为 0。 */
		final long    deltaMillis;

		private Parsed(boolean valid, long endMillis, String buildId, String task,
		               String rejectReason, long deltaMillis) {
			this.valid        = valid;
			this.endMillis    = endMillis;
			this.buildId      = buildId;
			this.task         = task;
			this.rejectReason = rejectReason;
			this.deltaMillis  = deltaMillis;
		}

		static Parsed ok(long endMillis, String buildId, String task) {
			return new Parsed(true, endMillis, buildId, task, null, 0L);
		}

		static Parsed reject(String reason) {
			return new Parsed(false, 0L, null, null, reason, 0L);
		}

		static Parsed rejectSkew(String reason, long deltaMillis) {
			return new Parsed(false, 0L, null, null, reason, deltaMillis);
		}
	}

	/** 按契约格式化 marker 内容（供测试与文档示例共用；生产写入方在 gradle-plugin 侧）。 */
	static String format(long endMillis, String buildId, String task) {
		StringBuilder sb = new StringBuilder(128);
		sb.append(MAGIC).append('\n');
		sb.append("build_end_millis=").append(endMillis).append('\n');
		if (buildId != null && !buildId.isEmpty()) {
			sb.append("build_id=").append(buildId).append('\n');
		}
		if (task != null && !task.isEmpty()) {
			sb.append("task=").append(task).append('\n');
		}
		return sb.toString();
	}

	/**
	 * 解析 marker 内容。
	 *
	 * @param raw              文件原始字节（可为 {@code null}）
	 * @param nowMillis        当前挂钟毫秒（{@link System#currentTimeMillis()}）
	 * @param agentStartMillis agent 启动时的挂钟毫秒；早于 {@code agentStart - skew} 的 marker 视为陈旧
	 */
	static Parsed parse(byte[] raw, long nowMillis, long agentStartMillis) {
		if (raw == null || raw.length == 0) {
			return Parsed.reject("empty marker file");
		}
		if (raw.length > MAX_BYTES) {
			return Parsed.reject("marker exceeds " + MAX_BYTES + " bytes");
		}

		String text = new String(raw, StandardCharsets.UTF_8);
		String[] lines = text.split("\r?\n", -1);

		String magic = null;
		Long endMillis = null;
		String buildId = null;
		String task = null;

		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) continue;
			if (magic == null) {
				magic = trimmed;
				continue;
			}
			int eq = trimmed.indexOf('=');
			if (eq <= 0) continue;
			String key = trimmed.substring(0, eq).trim();
			String value = trimmed.substring(eq + 1).trim();
			switch (key) {
				case "build_end_millis":
					try {
						endMillis = Long.parseLong(value);
					} catch (NumberFormatException e) {
						return Parsed.reject("build_end_millis is not a number: " + value);
					}
					break;
				case "build_id":
					buildId = value.isEmpty() ? null : (value.length() > 128 ? value.substring(0, 128) : value);
					break;
				case "task":
					task = value.isEmpty() ? null : value;
					break;
				default:
					break; // 未知字段忽略，便于后续扩展
			}
		}

		if (magic == null || !MAGIC.equals(magic)) {
			return Parsed.reject("missing or unknown magic header");
		}
		if (endMillis == null) {
			return Parsed.reject("missing build_end_millis");
		}
		if (endMillis <= 0L) {
			return Parsed.reject("build_end_millis must be positive: " + endMillis);
		}

		long staleDelta = (agentStartMillis - CLOCK_SKEW_MILLIS) - endMillis;
		if (staleDelta > 0L) {
			return Parsed.rejectSkew("stale marker (delta=" + (-staleDelta) + "ms)", -staleDelta);
		}
		long futureDelta = endMillis - (nowMillis + CLOCK_SKEW_MILLIS);
		if (futureDelta > 0L) {
			return Parsed.rejectSkew("marker timestamp is in the future (delta=+" + futureDelta + "ms)", futureDelta);
		}

		return Parsed.ok(endMillis, buildId, task);
	}
}
