import remold.AnnotationTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class InterfaceLambdaMarkerTest {

	/**
	 * 被执行到的 {@code consume} 次数。public 是必需的：转换后的接口由子加载器定义，
	 * 与被测类不在同一运行期包内，package-private 访问会抛 {@code IllegalAccessError}。
	 */
	public static final java.util.concurrent.atomic.AtomicInteger consumed =
		new java.util.concurrent.atomic.AtomicInteger();

	public interface LambdaFixture {
		default Runnable capturing() {
			return () -> consume(this);
		}

		default Runnable nonCapturing() {
			return () -> consume(null);
		}

		static void consume(LambdaFixture value) {
			consumed.incrementAndGet();
		}
	}

	static final class ByteLoader extends ClassLoader {
		ByteLoader(ClassLoader parent) {
			super(parent);
		}

		Class<?> define(byte[] bytes) {
			return defineClass(null, bytes, 0, bytes.length);
		}
	}

	@Test
	void transformsAndLinksInterfaceLambdas() throws Exception {
		String slashName = LambdaFixture.class.getName().replace('.', '/');
		byte[] original;
		try (java.io.InputStream in = LambdaFixture.class.getResourceAsStream("/" + slashName + ".class")) {
			if (in == null) throw new AssertionError("fixture class bytes not found");
			original = in.readAllBytes();
		}

		byte[] transformed = AnnotationTransformer.forceStaticLambdas(
			original, slashName, InterfaceLambdaMarkerTest.class.getClassLoader());
		assertTrue(!Arrays.equals(original, transformed), "interface lambda fixture transformed");

		ClassNode node = new ClassNode();
		new ClassReader(transformed).accept(node, 0);
		int markerAccess = -1;
		for (FieldNode field : node.fields) {
			if ("$remold$lambdasForced".equals(field.name)) {
				markerAccess = field.access;
				break;
			}
		}
		int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		assertTrue(markerAccess == required, "interface marker flags are exactly 0x1019 (actual 0x"
			+ Integer.toHexString(markerAccess) + ")");

		boolean staticLambdaHasExplicitReceiver = false;
		for (MethodNode method : node.methods) {
			if (!method.name.startsWith("lambda$nonCapturing$") || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
			staticLambdaHasExplicitReceiver = arguments.length > 0
				&& arguments[0].getDescriptor().equals("L" + slashName + ";");
			if (staticLambdaHasExplicitReceiver) break;
		}
		assertTrue(staticLambdaHasExplicitReceiver,
			"non-capturing lambda was rewritten static with interface receiver parameter");

		byte[] transformedAgain = AnnotationTransformer.forceStaticLambdas(
			transformed, slashName, InterfaceLambdaMarkerTest.class.getClassLoader());
		assertTrue(Arrays.equals(transformed, transformedAgain), "transformation is byte-for-byte idempotent");

		ByteLoader loader = new ByteLoader(InterfaceLambdaMarkerTest.class.getClassLoader());
		Class<?> defined = loader.define(transformed);
		Class.forName(defined.getName(), true, loader);
		assertTrue(defined.isInterface(), "transformed interface verifies, links, and initializes");

		Object instance = Proxy.newProxyInstance(loader, new Class<?>[]{defined},
			(proxy, method, arguments) -> InvocationHandler.invokeDefault(proxy, method, arguments));
		int before = consumed.get();
		for (String methodName : new String[]{"capturing", "nonCapturing"}) {
			Runnable lambda = (Runnable) defined.getMethod(methodName).invoke(instance);
			lambda.run();
		}
		// 终局断言：两个 lambda 都必须真的执行到 consume()。只断言"没抛异常"会被
		// "链接成功但方法体被丢弃/换成空实现"这种退化骗过去。
		assertEquals(2, consumed.get() - before,
			"capturing 与 nonCapturing 两个 lambda 都必须真的执行到 consume()（实际增加 "
				+ (consumed.get() - before) + " 次）");
	}
}
