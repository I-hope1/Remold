package remold.reflect.mixin;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MixinClassByteSources} 的查找链契约：已登记内存字节 → 监控目录磁盘 → 上次批次源码缓存。
 *
 * <p>用可注入的三档函数构造，不依赖真实 {@code activeWatchDirs} 或真实登记来源；
 * 顺序、空数组语义、以及"来源抛异常只跳过"都在这里钉死。</p>
 */
class MixinClassByteSourcesTest {

	private static final String DOT_NAME = "com.example.mixin.NewMixin";

	private static final byte[] REGISTERED = {1, 2, 3};

	private static final byte[] FROM_DISK = {4, 5, 6};

	private static final byte[] FROM_CACHE = {7, 8, 9};

	/** 通道①：已登记的内存字节优先于磁盘（同一批里 Mixin 内部类生成器刚登记过的最新字节）。 */
	@Test
	void registeredSourceWinsOverDiskAndCache() {
		byte[] bytes = MixinClassByteSources.lookup(DOT_NAME,
			name -> REGISTERED,
			name -> FROM_DISK,
			name -> FROM_CACHE);

		assertArrayEquals(REGISTERED, bytes, "the registered in-memory bytecode must win over disk");
	}

	/** 通道②：已登记来源未命中时由监控目录磁盘兜底（覆盖"资源回调先于 class 批次"的时序断层）。 */
	@Test
	void diskDirectoryFallsBackWhenNothingIsRegistered() {
		byte[] bytes = MixinClassByteSources.lookup(DOT_NAME,
			name -> null,
			name -> FROM_DISK,
			name -> FROM_CACHE);

		assertArrayEquals(FROM_DISK, bytes, "the watch-directory channel must answer when nothing is registered");
	}

	/** 通道③：磁盘也没有（文件已被删/在别的输出目录之外）时由上次批次源码缓存兜底。 */
	@Test
	void lastBatchCacheIsTheFinalFallback() {
		byte[] bytes = MixinClassByteSources.lookup(DOT_NAME,
			name -> new byte[0],
			name -> null,
			name -> FROM_CACHE);

		assertArrayEquals(FROM_CACHE, bytes, "an empty array means 'no bytes', so the source cache must answer");
	}

	/** 全部通道都没有：返回 null（调用方据此 fail-closed），且不抛异常。 */
	@Test
	void allChannelsEmptyMeansNull() {
		byte[] bytes = MixinClassByteSources.lookup(DOT_NAME,
			name -> null,
			name -> null,
			name -> new byte[0]);

		assertNull(bytes, "no channel has the bytes, so the lookup must report 'unknown' instead of guessing");
	}

	/** 单个来源抛异常只跳过该档，后续通道继续回答；异常不得打断调用方。 */
	@Test
	void throwingSourceIsSkippedAndTheNextChannelAnswers() {
		byte[] bytes = MixinClassByteSources.lookup(DOT_NAME,
			name -> {
				throw new IllegalStateException("registered source is broken");
			},
			name -> FROM_DISK,
			name -> FROM_CACHE);

		assertArrayEquals(FROM_DISK, bytes, "a broken source must not hide the channels behind it");
	}

	/** 空类名不触发任何来源（避免用 "" 去问磁盘/缓存）。 */
	@Test
	void blankClassNameIsRejectedWithoutAskingAnySource() {
		byte[] bytes = MixinClassByteSources.lookup("",
			name -> {
				throw new AssertionError("no source may be asked for an empty class name");
			},
			name -> {
				throw new AssertionError("no source may be asked for an empty class name");
			},
			name -> {
				throw new AssertionError("no source may be asked for an empty class name");
			});

		assertNull(bytes, "an empty class name has no bytes");
	}

	/** 内部名写法也能命中（宿主的 ClassInfo / InnerClassGenerator 传的是内部名）。 */
	@Test
	void internalNameSpellingAlsoMatches() {
		Function<String, byte[]> registered = name ->
			"com/example/mixin/NewMixin".equals(name) ? REGISTERED : null;

		byte[] bytes = MixinClassByteSources.lookup("com/example/mixin/NewMixin",
			registered,
			name -> FROM_DISK,
			name -> FROM_CACHE);

		assertArrayEquals(REGISTERED, bytes, "internal-name lookups must hit the same chain");
	}
}
