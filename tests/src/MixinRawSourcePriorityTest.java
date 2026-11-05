package remold;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code MixinHotSwap.readOriginalBytes} 必须优先拿磁盘原始字节，而不是 sourceBytecodeCache。
 *
 * <p>首次加载时 {@code AnnotationTransformer} 可能把 Mixin 织入后的 {@code classfileBuffer}
 * 写进 sourceBytecodeCache；把它当源码再织一遍会造成重复注入。本用例故意把一份假字节塞进
 * sourceBytecodeCache，断言读回的是磁盘/资源里的真实 .class。</p>
 */
public class MixinRawSourcePriorityTest {

	@Test
	void diskBytesWinOverPollutedSourceCache() throws Exception {
		Class<?> fixture = RawSourceFixture.class;
		String name = fixture.getName();
		Path root = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI());
		RemoldAgent.activeWatchDirs.add(root);
		try {
			byte[] real = fixture.getResourceAsStream("/" + name.replace('.', '/') + ".class").readAllBytes();
			byte[] fake = new byte[] {1, 2, 3, 4, 5};
			RemoldAgent.sourceBytecodeCache.put(name, fake);

			Class<?> bridge = Class.forName("remold.mixin.MixinHotSwap");
			Method read = bridge.getDeclaredMethod("readOriginalBytes", Class.class);
			read.setAccessible(true);
			byte[] got = (byte[]) read.invoke(null, fixture);

			assertFalse(Arrays.equals(fake, got),
				"a polluted sourceBytecodeCache must not be treated as the raw baseline");
			assertArrayEquals(real, got, "the raw .class from disk/resource must win");
		} finally {
			RemoldAgent.activeWatchDirs.remove(root);
			RemoldAgent.sourceBytecodeCache.remove(name);
		}
	}
}
