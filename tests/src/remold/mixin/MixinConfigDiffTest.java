package remold.mixin;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MixinConfigDiff} 的 JSON 数组解析契约：不完整配置必须返回 null，不能当成“空列表”。 */
class MixinConfigDiffTest {

	@Test
	void parsesCommonMixinsArray() throws Exception {
		List<String> names = quotedArray("{\"mixins\": [\"A\", \"B\"]}");
		assertEquals(List.of("A", "B"), names);
	}

	@Test
	void missingKeyMeansEmptyArray() throws Exception {
		List<String> names = quotedArray("{\"client\": [\"C\"]}");
		assertTrue(names.isEmpty());
	}

	@Test
	void incompleteArrayIsRejectedInsteadOfTreatedAsEmpty() throws Exception {
		assertNull(quotedArray("{\"mixins\": [\"A\", \"B\""));
		assertNull(quotedArray("{\"mixins\": \"A\"}"));
	}

	private static List<String> quotedArray(String json) throws Exception {
		Method method = MixinConfigDiff.class.getDeclaredMethod("quotedArray", String.class, String.class);
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<String> result = (List<String>) method.invoke(null, json, "mixins");
		return result;
	}
}
