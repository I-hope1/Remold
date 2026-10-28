package remold;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 首次加载时的缓存填充（{@code docs/initfix/07-p05-probe-results.md} §14.7）。
 *
 * <p>删除字段的读者扫描要求"已加载类读内存里当前生效的字节码"。缓存如果只在热更过程中被动填充，
 * 首轮热更时几乎为空，扫描会判不完整并退回存活实例判据——判据等于没接线。这个 transformer 就是
 * 把"首次加载"这条最便宜的填充路径接上。</p>
 *
 * <p>它必须：只在首次加载时动作、只认工程自身的位置、对隐藏类/agent 自身不动作，并且
 * <b>永远返回 null</b>（不改字节）。最后一条是本类最要紧的断言——它一旦返回非 null 就会改写
 * 加载中的类，而它根本没有资格做这个决定。</p>
 */
class CacheWarmingTransformerTest {

	private static final byte[] FAKE_BYTES = "not-a-real-class".getBytes(StandardCharsets.UTF_8);

	@Test
	void cachesFirstLoadOfProjectClass() throws Exception {
		Path dir = Files.createTempDirectory("cache_warm");
		ProtectionDomain pd = domainOf(dir);

		byte[] result = new RemoldAgent.CacheWarmingTransformer()
			.transform(getClass().getClassLoader(), "cw/First", null, pd, FAKE_BYTES);

		assertNull(result, "the transformer must never change bytecode; it only observes");
		assertSame(FAKE_BYTES, RemoldAgent.bytecodeCache.get("cw.First"),
			"a first-load project class must be cached with the bytes the JVM is about to define");
	}

	/**
	 * 去触发对照：重定义通道（{@code classBeingRedefined != null}）不走这条路径——
	 * 那条路径上字节码由热更管线自己维护，这里插手会覆盖掉更准确的版本。
	 */
	@Test
	void doesNotCacheRedefinitionPath() throws Exception {
		Path dir = Files.createTempDirectory("cache_warm");
		ProtectionDomain pd = domainOf(dir);

		byte[] result = new RemoldAgent.CacheWarmingTransformer()
			.transform(getClass().getClassLoader(), "cw/Redefined", String.class, pd, FAKE_BYTES);

		assertNull(result);
		assertFalse(RemoldAgent.bytecodeCache.containsKey("cw.Redefined"),
			"the redefinition path must be left to the hotswap pipeline");
	}

	/** 隐藏类（lambda 等）不会进 transformer 的常规路径，也不可重定义，缓存它们只会污染读者集合。 */
	@Test
	void skipsHiddenClasses() throws Exception {
		Path dir = Files.createTempDirectory("cache_warm");
		ProtectionDomain pd = domainOf(dir);

		new RemoldAgent.CacheWarmingTransformer()
			.transform(getClass().getClassLoader(), "cw/Host$$Lambda/0x00000008000c0c40", null, pd, FAKE_BYTES);

		assertFalse(RemoldAgent.bytecodeCache.containsKey("cw.Host$$Lambda/0x00000008000c0c40"));
	}

	/** agent 自身不缓存，避免递归与自我污染。 */
	@Test
	void skipsAgentOwnClasses() throws Exception {
		Path dir = Files.createTempDirectory("cache_warm");
		ProtectionDomain pd = domainOf(dir);

		new RemoldAgent.CacheWarmingTransformer()
			.transform(getClass().getClassLoader(), "remold/RemoldAgent", null, pd, FAKE_BYTES);

		assertFalse(RemoldAgent.bytecodeCache.containsKey("remold.RemoldAgent"));
	}

	/**
	 * 换无关输入对照：位置不是工程目录（这里用一个 jar 文件而不是目录，且监听配置为空）→ 不缓存。
	 * 依赖 jar 里的类不是"工程自身"，把它们的字节抄进来只会让读者扫描多算。
	 */
	@Test
	void doesNotCacheNonProjectLocation() throws Exception {
		Path jar = Files.createTempFile("dep", ".jar");
		ProtectionDomain pd = domainOf(jar);

		new RemoldAgent.CacheWarmingTransformer()
			.transform(getClass().getClassLoader(), "cw/Dependency", null, pd, FAKE_BYTES);

		assertFalse(RemoldAgent.bytecodeCache.containsKey("cw.Dependency"));
	}

	/** 缓存值必须是原始引用（不复制、不改写），否则"内存里生效的那份"这个前提就不成立了。 */
	@Test
	void cachesTheExactBufferReference() throws Exception {
		Path dir = Files.createTempDirectory("cache_warm");
		ProtectionDomain pd = domainOf(dir);
		byte[] buffer = new byte[]{1, 2, 3, 4};

		new RemoldAgent.CacheWarmingTransformer()
			.transform(getClass().getClassLoader(), "cw/Exact", null, pd, buffer);

		assertArrayEquals(buffer, RemoldAgent.bytecodeCache.get("cw.Exact"));
	}

	private static ProtectionDomain domainOf(Path location) throws Exception {
		URL url = location.toUri().toURL();
		return new ProtectionDomain(new CodeSource(url, (Certificate[]) null), null, null, null);
	}
}
