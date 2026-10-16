package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link RemoldAgent#FILE_SHAKE_MS} 的可配置性：
 * 包括 getter/setter、System Property、以及配置解析恢复。
 */
class FileShakeConfigTest {

	private int originalShake;
	private String originalProp;

	@BeforeEach
	void setUp() {
		originalShake = RemoldAgent.getFileShakeMs();
		originalProp = System.getProperty("remold.agent.file_shake_ms");
	}

	@AfterEach
	void tearDown() {
		if (originalProp != null) {
			System.setProperty("remold.agent.file_shake_ms", originalProp);
		} else {
			System.clearProperty("remold.agent.file_shake_ms");
		}
		System.clearProperty("remold.fileShakeMs");
		RemoldAgent.setFileShakeMs(originalShake);
	}

	@Test
	void testFileShakeMsConfigurable() {
		// 1. 验证动态 setter 与 getter
		RemoldAgent.setFileShakeMs(800);
		assertEquals(800, RemoldAgent.getFileShakeMs());
		assertEquals(800, RemoldAgent.FILE_SHAKE_MS);

		// 负数应被忽略保护
		RemoldAgent.setFileShakeMs(-100);
		assertEquals(800, RemoldAgent.getFileShakeMs());

		// 2. 验证通过 System Property 与 initConfig 联动
		System.setProperty("remold.agent.file_shake_ms", "650");
		RemoldAgent.initConfig();
		assertEquals(650, RemoldAgent.getFileShakeMs());

		// 3. 验证通过 camelCase alias (remold.fileShakeMs)
		System.clearProperty("remold.agent.file_shake_ms");
		System.setProperty("remold.fileShakeMs", "450");
		RemoldAgent.initConfig();
		assertEquals(450, RemoldAgent.getFileShakeMs());
	}
}
