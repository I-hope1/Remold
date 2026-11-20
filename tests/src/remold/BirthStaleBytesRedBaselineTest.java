package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红基线 B（{@code docs/vcs/07-load-frontier-and-birth-watermark.md} 的前置证据）：
 * <b>"修改早于加载"时，出生点会把陈旧字节原样放行并记账</b>。
 *
 * <p><b>本用例断言的是缺陷现状，不是期望行为。</b>它存在的意义是：在实现出生点补丁（{@code PendingLoadPatch}）
 * 之前先证明缺口真实存在（仓库 §8.3：未验证的测量不得用来改产品代码）；实现落地后本类应当整体反转
 * ——那时 {@code transform} 必须返回替换后的新字节，且 {@code bytecodeCache} 记录的必须是生效字节。</p>
 *
 * <p><b>缺口链条</b>：类 X 在磁盘/构建输出里已被改成 v2，但 JVM 还没加载它，因此当轮 {@code redefineClasses}
 * 无事可做；稍后 X 首次被加载时，classfileBuffer 是加载器给出的 v1，而
 * {@code CacheWarmingTransformer.transform} 永远 {@code return null}（不改字节），
 * 于是 JVM 加载 v1、缓存也记 v1 —— 那次编辑静默失效，且后续 diff 的基线也被污染成 v1。</p>
 */
class BirthStaleBytesRedBaselineTest {

	private static final String CLASS_NAME = "probe/StaleBirth";
	private static final String DOT_NAME = "probe.StaleBirth";

	private Path projectDir;

	@AfterEach
	void cleanUp() {
		if (projectDir != null) RemoldAgent.activeWatchDirs.remove(projectDir);
		RemoldAgent.bytecodeCache.remove(DOT_NAME);
	}

	/** 造一个"工作区"目录并登记为监控路径，使 {@code isProjectLocation} 认它。 */
	private ProtectionDomain projectDomain() throws IOException {
		projectDir = Files.createTempDirectory("remold-birth-probe");
		RemoldAgent.activeWatchDirs.add(projectDir);
		CodeSource cs = new CodeSource(URI.create(projectDir.toUri().toString()).toURL(),
		 (Certificate[]) null);
		return new ProtectionDomain(cs, null);
	}

	/**
	 * 现状：出生点不改字节，且缓存记录的是<b>加载器送来的陈旧字节</b>。
	 *
	 * <p>三个断言分别锁住三件事：①transformer 返回 null（JVM 会用 classfileBuffer，即 v1）；
	 * ②缓存里存的是 v1 而不是构建输出里的 v2；③因此后续所有基于 {@code bytecodeCache} 的判断
	 * （diff 基线、对齐输入、读者扫描）拿到的都是陈旧字节。</p>
	 */
	@Test
	void birthServesAndCachesTheStaleBuffer() throws Exception {
		ProtectionDomain pd = projectDomain();
		byte[] staleBytes = "STALE-V1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] editedBytes = "EDITED-V2".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		// 编辑已经发生：构建输出（也就是会被 PendingLoadPatch 用作替换源的那份）是 v2。
		Files.write(projectDir.resolve("StaleBirth.class"), editedBytes);

		byte[] returned = RemoldAgent.CacheWarmingTransformer.class
		 .getDeclaredConstructor().newInstance()
		 .transform(getClass().getClassLoader(), CLASS_NAME, null, pd, staleBytes);

		assertNull(returned,
		 "红基线：出生点当前不替换字节（返回 null 表示 JVM 采用 classfileBuffer）");
		byte[] cached = RemoldAgent.getRuntimeBytecode(DOT_NAME);
		assertTrue(cached != null, "红基线：出生点应当把加载进来的字节记账");
		assertArrayEquals(staleBytes, cached,
		 "红基线：缓存记录的是加载器送来的陈旧字节，不是构建输出里的新字节");
		assertArrayEquals(editedBytes,
		 Files.readAllBytes(projectDir.resolve("StaleBirth.class")),
		 "对照组：构建输出确实已经是新字节——缺口不在\"编辑没发生\"，而在\"出生点没用它\"");
	}

	/**
	 * 对照组：非工作区（例如第三方 jar）的类不进缓存，说明"记不记账"本身是有判定的，
	 * 缺口只在"记的是哪一份字节"。
	 */
	@Test
	void nonProjectLocationIsNotCachedAtAll() throws Exception {
		CodeSource cs = new CodeSource(new URI("file:/tmp/remold-outside.jar").toURL(), (Certificate[]) null);
		ProtectionDomain pd = new ProtectionDomain(cs, null);
		byte[] staleBytes = "STALE".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		byte[] returned = RemoldAgent.CacheWarmingTransformer.class
		 .getDeclaredConstructor().newInstance()
		 .transform(getClass().getClassLoader(), "probe/Outside", null, pd, staleBytes);

		assertNull(returned, "非工作区类同样不替换字节");
		assertNull(RemoldAgent.getRuntimeBytecode("probe.Outside"),
		 "对照组：非工作区类不进运行时基线缓存（判定生效）");
		assertEquals(Set.of(), Set.of(),
		 "占位断言：本用例只记录观测结果，不具备对\"是否应当缓存外部类\"的归因能力");
	}
}
