package remold.util;

/**
 * 极简诊断日志门面：切断纯分析/字节码模块对 {@code RemoldAgent} 的反向依赖。
 *
 * <p>独立调用方（例如离线字节码分析器或单测）无需加载 {@code RemoldAgent} 即可输出或静默日志；
 * {@code RemoldAgent} 在类初始化期把自身的动态 {@code Logger} 接入本接收器，从而保持测试期切换
 * {@code RemoldAgent.logger} 的捕获语义不变。</p>
 */
public final class Diag {

	/** 运行期日志接收器；由宿主代理在初始化时注册。 */
	public interface Sink {
		void log(String msg);
		void info(String msg);
		void warn(String msg);
		void error(String msg, Throwable t);
	}

	private static volatile Sink sink = new Sink() {
		@Override
		public void log(String msg) {
		}

		@Override
		public void info(String msg) {
			System.out.println("[Remold] " + msg);
		}

		@Override
		public void warn(String msg) {
			System.out.println("[Remold] [WARN] " + msg);
		}

		@Override
		public void error(String msg, Throwable t) {
			System.err.println("[Remold] " + msg);
			if (t != null) t.printStackTrace(System.err);
		}
	};

	private Diag() {}

	/** 注册动态日志接收器（线程安全发布）。 */
	public static void setSink(Sink newSink) {
		if (newSink != null) {
			sink = newSink;
		}
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
