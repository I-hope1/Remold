package remold.startup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import remold.RemoldAgent;
import remold.fabric.FabricHotswapPlugin;
import remold.fabric.HostReadinessProbe;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code plugin-fabric} 启动就绪门闩的语义测试：<b>有明确信号才否决，取不到信号必须放行</b>。
 *
 * <p><b>守的是什么</b>：{@code isHostReady()} 的默认实现是 {@code true}，加载器不实现它
 * 等于没有启动期保护；但实现错了会走到另一个极端——门闩默认<b>没有超时放行</b>
 * （{@code -Dremold.agent.ready_timeout_ms=0}），一旦因为"映射对不上/类不在"就返回
 * {@code false}，热更会永久不工作。所以本类锁两条相反方向的错：</p>
 * <ul>
 *   <li><b>该否决的时候不否决</b>：服务端实例已建、tick 计数仍为 {@code 0} → 必须
 *       {@code false}（这正是启动风暴窗口）；</li>
 *   <li><b>不该否决的时候否决</b>：取不到任何信号（类不在、探测抛异常）→ 必须
 *       {@code true}，且只打一条 info 说明"按已就绪处理"。</li>
 * </ul>
 *
 * <p>探测口是 {@link HostReadinessProbe.ClassLocator} 注入的替身，因此不需要真实 Minecraft，
 * 也不需要 DCEVM。断言落在业务终局：{@code isHostReady()} 的返回值与就绪日志的条数。</p>
 */
class FabricHostReadinessTest {

	private static final String SERVER_CLASS = "net.minecraft.server.MinecraftServer";
	private static final String CLIENT_CLASS = "net.minecraft.client.MinecraftClient";

	private RecordingLogger logger;

	@BeforeEach
	void setUp() {
		logger = new RecordingLogger();
		logger.install();
	}

	@AfterEach
	void tearDown() {
		logger.restore();
		FakeServer.reset();
		FakeClient.reset();
	}

	@Test
	void noReadinessSignalFallsBackToReadyWithOneInfo() {
		// 没有任何 Minecraft 类（例如纯 Mindustry/Arc 或未加载宿主的开发环境）。
		FabricHotswapPlugin plugin = new FabricHotswapPlugin(new HostReadinessProbe(name -> null));

		assertTrue(plugin.isHostReady(),
		 "an unresolvable host must be treated as ready — the gate has no timeout release by default,"
		 + " so a veto here would starve hot swap forever");
		assertEquals(1, logger.count("[Fabric] 未取得就绪信号"),
		 "the fallback must be reported exactly once, saying it is treated as ready");

		// 就绪单向：再问一次不再打第二条日志。
		assertTrue(plugin.isHostReady());
		assertEquals(1, logger.count("[Fabric] 未取得就绪信号"),
		 "re-probing after the fallback must not log again");
		assertEquals(0, logger.count("[Fabric] host reported ready"),
		 "the fallback must not pretend the host really reported ready");
	}

	@Test
	void explicitNotReadySignalDefersBatchesUntilTheMainLoopTicks() {
		FakeServer.ticks = 0;
		FakeServer.instance = new FakeServer();
		FabricHotswapPlugin plugin = new FabricHotswapPlugin(probeFinding(SERVER_CLASS, FakeServer.class));

		// Negative control: the very same probe input that will later be ready must veto now.
		assertFalse(plugin.isHostReady(),
		 "a constructed MinecraftServer with tick count 0 is a definitive NOT_READY signal"
		 + " (the server exists but the main loop has not ticked yet)");
		assertEquals(0, logger.count("[Fabric]"),
		 "a still-booting host must not produce readiness log lines on every re-probe");

		// Flip only the tick counter: everything else stays identical.
		FakeServer.ticks = 1;
		assertTrue(plugin.isHostReady(), "the first tick opens the gate");
		assertEquals(1, logger.count("[Fabric] host reported ready"),
		 "the PENDING -> READY transition must be logged exactly once");

		// 就绪只前进不回退。
		FakeServer.ticks = 0;
		assertTrue(plugin.isHostReady(), "readiness must never regress once the main loop has ticked");
		assertEquals(1, logger.count("[Fabric] host reported ready"),
		 "a readiness that already happened must not be logged again");
	}

	@Test
	void clientThatJoinedAWorldIsAReadySignal() {
		FakeClient.instance = new FakeClient();
		FakeClient.instance.world = new Object();
		FabricHotswapPlugin plugin = new FabricHotswapPlugin(probeFinding(CLIENT_CLASS, FakeClient.class));

		assertTrue(plugin.isHostReady(),
		 "a client that has entered a world is far past the startup storm");
		assertEquals(1, logger.count("[Fabric] host reported ready"));
	}

	@Test
	void clientSittingAtTheMainMenuIsNotAVeto() {
		FakeClient.instance = new FakeClient(); // 没有世界
		FabricHotswapPlugin plugin = new FabricHotswapPlugin(probeFinding(CLIENT_CLASS, FakeClient.class));

		assertTrue(plugin.isHostReady(),
		 "a client parked at the main menu may never join a world; vetoing it would starve hot swap forever");
		assertEquals(1, logger.count("[Fabric] 未取得就绪信号"),
		 "no world means 'no signal', and 'no signal' must be reported as the ready fallback");
	}

	@Test
	void throwingProbeIsTreatedAsUnknownNotAsAVeto() {
		// 门闩把 isHostReady() 抛异常按未就绪处理（fail-closed），所以探测异常必须被本插件吞掉。
		FabricHotswapPlugin plugin = new FabricHotswapPlugin(new HostReadinessProbe(name -> {
			throw new IllegalStateException("reflection blocked by the host");
		}));

		assertTrue(plugin.isHostReady(),
		 "a broken probe must not be turned into a veto: the gate would treat the throw as not-ready"
		 + " and starve hot swap");
		assertEquals(1, logger.count("[Fabric] 未取得就绪信号"));
	}

	private static HostReadinessProbe probeFinding(String className, Class<?> found) {
		return new HostReadinessProbe(name -> className.equals(name) ? found : null);
	}

	// region fixtures

	/** 替身服务端：{@code getServer()} 静态访问器 + {@code getTicks()} tick 计数（Yarn 名字）。 */
	public static final class FakeServer {
		static volatile int    ticks;
		static volatile Object instance;

		static void reset() {
			ticks = 0;
			instance = null;
		}

		public static Object getServer() {
			return instance;
		}

		public int getTicks() {
			return ticks;
		}
	}

	/** 替身客户端：{@code getInstance()} + 私有 {@code world} 字段（已进入世界的判据）。 */
	public static final class FakeClient {
		static volatile FakeClient instance;

		private Object world;

		static void reset() {
			instance = null;
		}

		public static Object getInstance() {
			return instance;
		}
	}

	/** 只记录、不落盘；断言 [Fabric] 就绪日志的条数与归因文本。 */
	private static final class RecordingLogger implements RemoldAgent.Logger {
		private final List<String> lines = new ArrayList<>();
		private RemoldAgent.Logger previous;

		void install() {
			previous = RemoldAgent.logger;
			RemoldAgent.logger = this;
			assertNotNull(previous, "there must be a default logger to restore");
		}

		void restore() {
			if (previous != null) RemoldAgent.logger = previous;
		}

		synchronized int count(String prefix) {
			int n = 0;
			for (String line : lines) {
				if (line.startsWith(prefix)) n++;
			}
			return n;
		}

		private synchronized void record(String level, String msg) {
			lines.add(msg == null ? "" : msg);
		}

		@Override public void log(String msg) { record("DEBUG", msg); }
		@Override public void info(String msg) { record("INFO", msg); }
		@Override public void warn(String msg) { record("WARN", msg); }
		@Override public void error(String msg) { record("ERROR", msg); }
		@Override public void error(String msg, Throwable t) { record("ERROR", msg); }
	}
	// endregion
}
