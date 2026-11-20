package remold.util;

/**
 * 极简诊断日志门面：切断纯分析/字节码模块对 {@code RemoldAgent} 的反向依赖。
 *
 * <p>未接入宿主代理前默认全级别静默（no-op）；{@code RemoldAgent} 在类初始化期把自身的动态
 * {@code Logger} 接入本接收器，从而保持测试期切换 {@code RemoldAgent.logger} 的捕获语义不变。</p>
 */
public final class Diag {

	/** 运行期日志接收器；由宿主代理在初始化时注册。 */
	public interface Sink {
		void log(String msg);
		void info(String msg);
		void warn(String msg);
		void error(String msg, Throwable t);
		default boolean isDebugEnabled() { return false; }
		default boolean isPerfEnabled() { return false; }
	}

	private static volatile Sink sink = new Sink() {
		@Override public void log(String msg) {}
		@Override public void info(String msg) {}
		@Override public void warn(String msg) {}
		@Override public void error(String msg, Throwable t) {}
	};

	private Diag() {}

	/** 注册动态日志接收器（线程安全发布）。 */
	public static void setSink(Sink newSink) {
		if (newSink != null) {
			sink = newSink;
		}
	}

	public static boolean isDebugEnabled() {
		return sink.isDebugEnabled();
	}

	public static boolean isPerfEnabled() {
		return sink.isPerfEnabled();
	}

	public static void log(String msg) {
		sink.log(msg);
	}

	public static void info(String msg) {
		sink.info(msg);
	}

	public static void warn(String msg) {
		sink.warn(msg);
	}

	public static void error(String msg) {
		sink.error(msg, null);
	}

	public static void error(String msg, Throwable t) {
		sink.error(msg, t);
	}
}
