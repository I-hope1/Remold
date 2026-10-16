import remold.RemoldAgent;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发入口加固的回归断言（先红后绿）。
 *
 * <p><b>守的是什么</b>：{@code processChanges} 全流程必须串行。{@code scheduler} 是单线程的，
 * 所以"监听线程 → 防抖 → 热更"这条路径本来就串行 —— 但存在绕过 scheduler 的入口
 * （UI refresh 按钮的 daemon 线程、{@code init} 的 else 分支等），它们会在调用方线程上
 * 直接跑热更，与 scheduler 上正在进行的那一轮重叠。</p>
 *
 * <p><b>为什么不是"两个 watcher 同时触发"</b>：那条路径本来就是串行的，断言它恒为 1
 * 永远绿，测不出任何东西。真正要复现的是
 * <b>"scheduler 正在处理时，另一个线程调用 {@code triggerHotswap()}"</b>。</p>
 *
 * <p><b>先红后绿的做法</b>：本断言读的是 agent 内的并发探针
 * （{@link RemoldAgent#peakConcurrentHotswaps()}）。把 {@code HOTSWAP_LOCK} 摘掉后，
 * 峰值应为 2、本断言变红；加锁后恒为 1、变绿。</p>
 *
 * <p><b>当前状态</b>：探针与锁已落地，但本入口尚未接入一个能在进程内 attach agent 的
 * 真实夹具 —— 它需要 {@code Instrumentation} 实例，而 {@code RemoldAgent.inst} 只能由
 * agent 入口注入。因此这里先断言**可确定性验证的三件事**，并显式标注尚未覆盖的部分，
 * 避免"测试写了但没测到东西"这种更坏的情况。</p>
 */
public class ConcurrencyAssert {

	static int failed = 0;
	static int passed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	public static void main(String[] args) throws Exception {
		System.out.println("== 并发入口加固断言 ==");

		// ---------- 1) 锁不变量：无锁调用必须在入口立即抛异常 ----------
		//
		// 这是本次加固最核心的保证：任何绕过 HOTSWAP_LOCK 的新入口都会在
		// triggerHotswapWith 的入口炸掉，而不是静默地与 scheduler 重叠。
		//
		// 这里通过反射调用私有 triggerHotswapWith 且**不持锁**，验证它确实拒绝执行。
		// 注意：若将来有人在锁内调用，这条断言会失效 —— 因此它检查的是"不持锁时必抛"，
		// 而不是"持锁时必不抛"。
		{
			System.out.println("-- 1) 锁不变量 --");
			boolean threw = false;
			String msg = null;
			try {
				var m = RemoldAgent.class.getDeclaredMethod("triggerHotswapWith", Class[].class);
				m.setAccessible(true);
				m.invoke(null, (Object) new Class<?>[0]);
			} catch (java.lang.reflect.InvocationTargetException e) {
				threw = true;
				msg = String.valueOf(e.getCause());
			} catch (Throwable t) {
				// NoSuchMethodException 等：说明方法名/签名变了，应视为失败而非通过
				msg = "非预期异常: " + t;
			}
			check(threw, "无锁调用 triggerHotswapWith 被拒绝（实际: " + msg + "）");
			check(msg != null && msg.contains("HOTSWAP_LOCK"),
				"拒绝原因是锁不变量（而非其它 NPE 之类）");
		}

		// ---------- 2) 探针初始态 ----------
		{
			System.out.println("-- 2) 并发探针 --");
			RemoldAgent.resetConcurrencyProbe();
			check(RemoldAgent.peakConcurrentHotswaps() == 0,
				"复位后峰值为 0（实际 " + RemoldAgent.peakConcurrentHotswaps() + "）");
		}

		// ---------- 3) 锁本身是可重入的 ----------
		//
		// init() 持锁后会经 triggerHotswap() 再次进入，若锁不可重入会自锁死。
		// 这里直接验证 ReentrantLock 字段的可重入性，避免那条路径在生产上才发现死锁。
		{
			System.out.println("-- 3) 锁可重入 --");
			var f = RemoldAgent.class.getDeclaredField("HOTSWAP_LOCK");
			f.setAccessible(true);
			var lock = (java.util.concurrent.locks.ReentrantLock) f.get(null);
			lock.lock();
			boolean reentered = false;
			try {
				reentered = lock.isHeldByCurrentThread();
				lock.lock();                       // 第二次获取：可重入则不死锁
				reentered = reentered && lock.getHoldCount() == 2;
			} finally {
				lock.unlock();
				lock.unlock();
			}
			check(reentered, "HOTSWAP_LOCK 可重入且 holdCount 正确");
			check(!lock.isLocked(), "异常路径后锁已完全释放");
		}

		// ---------- 4) 真实 Agent 挂载下并发重叠与峰值实测 (E2E) ----------
		{
			System.out.println("-- 4) 真实 Agent 挂载下并发重叠实测 --");
			var inst = RemoldAgent.getInstrumentation();
			check(inst != null, "Agent 挂载就绪：Instrumentation 实例可用");
			if (inst != null) {
				RemoldAgent.resetConcurrencyProbe();
				int threads = 4;
				var startLatch = new CountDownLatch(1);
				var doneLatch = new CountDownLatch(threads);
				var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();

				for (int i = 0; i < threads; i++) {
					new Thread(() -> {
						try {
							startLatch.await();
							RemoldAgent.triggerHotswap();
						} catch (Throwable t) {
							errors.add(t);
						} finally {
							doneLatch.countDown();
						}
					}, "ConcurrentTrigger-" + i).start();
				}

				startLatch.countDown();
				boolean done = doneLatch.await(5, TimeUnit.SECONDS);
				check(done, "多线程并发触发 triggerHotswap() 全部在超时内完成");
				check(errors.isEmpty(), "多线程并发触发无异常抛出: " + errors);
				int peak = RemoldAgent.peakConcurrentHotswaps();
				check(peak == 1, "全流程严格受 HOTSWAP_LOCK 保护，实测峰值并发数为 1 (实际: " + peak + ")");
			}
		}

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条");
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}
}
