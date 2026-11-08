package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 资源流防抖窗口与 Class 流解耦后的 setter/getter 契约。 */
class AssetShakeConfigTest {

	private int originalAsset;
	private int originalFile;

	@BeforeEach
	void setUp() {
		originalAsset = RemoldAgent.getAssetShakeMs();
		originalFile = RemoldAgent.getFileShakeMs();
	}

	@AfterEach
	void tearDown() {
		RemoldAgent.setAssetShakeMs(originalAsset);
		RemoldAgent.setFileShakeMs(originalFile);
	}

	@Test
	void assetWindowIsIndependentFromClassWindow() {
		RemoldAgent.setFileShakeMs(900);
		RemoldAgent.setAssetShakeMs(150);
		assertEquals(900, RemoldAgent.getFileShakeMs());
		assertEquals(150, RemoldAgent.getAssetShakeMs());

		RemoldAgent.setAssetShakeMs(-1);
		assertEquals(150, RemoldAgent.getAssetShakeMs());
	}
}
