package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fabric 侧适配器（{@code remold.mixin.MixinClassInfoSync}）的契约：它只是 core
 * {@code ClassInfoMemberSync} 的类型擦除壳子，因此这里只锁两件事——把宿主类型透传过去、以及
 * 「拿不到 processor 时降级为空句柄而不是抛异常」这条失败语义（算法 / 回滚语义由
 * {@code ClassInfoMemberSyncTest} 覆盖；编译期类型正确性由本测试的签名使用覆盖）。
 */
public class MixinClassInfoSyncTest {

	private static Class<?> sync() {
		try {
			return Class.forName("remold.mixin.MixinClassInfoSync");
		} catch (Exception e) {
			throw new IllegalStateException("MixinClassInfoSync is not on the test classpath", e);
		}
	}

	/** 没有 {@code processor} 字段的织入器替身：适配器必须降级而不是抛错。 */
	private static IMixinTransformer transformerWithoutProcessor() {
		InvocationHandler handler = (proxy, method, args) -> null;
		return (IMixinTransformer) Proxy.newProxyInstance(
			MixinClassInfoSyncTest.class.getClassLoader(), new Class<?>[] { IMixinTransformer.class }, handler);
	}

	@Test
	void degradesToAnEmptyTokenWhenTheTransformerHasNoProcessor() throws Exception {
		Class<?> adapter = sync();
		Method syncMethod = adapter.getDeclaredMethod("sync", IMixinTransformer.class, String.class, ClassNode.class);
		syncMethod.setAccessible(true);
		ClassNode newBytes = new ClassNode();
		newBytes.name = "probe/SyncMixinFixture";
		newBytes.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE, "added", "I", null, 0));

		Object token = assertDoesNotThrow(
			() -> syncMethod.invoke(null, transformerWithoutProcessor(), "probe.SyncMixinFixture", newBytes),
			"sync must never throw out of the adapter");

		assertNotNull(token, "sync must always return a token");
		Method isEmpty = token.getClass().getDeclaredMethod("isEmpty");
		isEmpty.setAccessible(true);
		assertTrue((boolean) isEmpty.invoke(token),
			"a transformer without a processor must degrade to an empty token");
	}

	@Test
	void rollbackOfAnEmptyTokenIsANoOp() throws Exception {
		Class<?> adapter = sync();
		java.lang.reflect.Field emptyField =
			Class.forName("remold.mixin.MixinClassInfoSync$SyncToken").getDeclaredField("EMPTY");
		emptyField.setAccessible(true);
		Object empty = emptyField.get(null);
		Method rollback = adapter.getDeclaredMethod("rollback",
			Class.forName("remold.mixin.MixinClassInfoSync$SyncToken"));
		rollback.setAccessible(true);
		assertDoesNotThrow(() -> rollback.invoke(null, empty), "rollback(EMPTY) must be a no-op");
		assertDoesNotThrow(() -> rollback.invoke(null, (Object) null), "rollback(null) must be a no-op");
	}
}
