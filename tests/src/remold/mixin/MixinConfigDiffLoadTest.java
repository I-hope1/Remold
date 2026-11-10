package remold.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import remold.BytecodeSources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MixinConfigDiff#loadClassNode} 的字节通道契约。
 *
 * <p>真实 Mixin 服务在单元测试里不可用（{@code MixinService.getService()} 直接抛
 * {@code ServiceNotAvailableError}），这正是被测的失效形态：只走 Mixin 资源流时读不到新 Mixin 的
 * {@code ClassNode}。这里把字节登记进 {@link BytecodeSources}（Remold 监控通道的第一个来源），
 * 断言回退链仍能解析出节点。</p>
 *
 * <p>对照：查一个未登记的名字必须返回 {@code null}（不得凭空造出节点）。</p>
 */
class MixinConfigDiffLoadTest {

	private static final String MIXIN_DOT = "com.example.mixin.NewMixin";

	private static final String MIXIN_INTERNAL = "com/example/mixin/NewMixin";

	private static final String TARGET_INTERNAL = "com/example/target/TargetClass";

	/** 必须是同一个实例才能被 {@link BytecodeSources#unregister} 撤销。 */
	private static final Function<String, byte[]> SOURCE =
		name -> isMixinName(name) ? mixinBytes() : null;

	@Test
	void readsNewMixinNodeFromRemoldChannelsWhenMixinServiceIsUnavailable() throws Exception {
		BytecodeSources.register(SOURCE);
		try {
			Object node = loadClassNode(MIXIN_DOT);

			assertNotNull(node, "the fallback chain must provide the ClassNode when Mixin's own resource"
				+ " stream cannot (same-batch timing gap / IDE multi-output-directory)");
			assertEquals(MIXIN_INTERNAL, classNameOf(node), "the parsed node must be the newly listed mixin");
		} finally {
			BytecodeSources.unregister(SOURCE);
		}
	}

	@Test
	void unregisteredClassStillYieldsNull() throws Exception {
		BytecodeSources.register(SOURCE);
		try {
			assertNull(loadClassNode("com.example.mixin.UnknownMixin"),
				"a class that no channel has must stay unresolved instead of being fabricated");
		} finally {
			BytecodeSources.unregister(SOURCE);
		}
	}

	private static boolean isMixinName(String name) {
		return name != null
			&& (name.equals(MIXIN_DOT) || name.equals(MIXIN_INTERNAL));
	}

	/** 生成一份真实 class 文件：带 {@code @Mixin} 注解 + 一个 {@code $1} 内部类声明。 */
	private static byte[] mixinBytes() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT,
			MIXIN_INTERNAL, null, "java/lang/Object", null);
		AnnotationVisitor annotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor values = annotation.visitArray("value");
		values.visit(null, Type.getObjectType(TARGET_INTERNAL));
		values.visitEnd();
		annotation.visitEnd();
		writer.visitInnerClass(MIXIN_INTERNAL + "$1", MIXIN_INTERNAL, null, Opcodes.ACC_STATIC);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static Object loadClassNode(String dotClassName) throws Exception {
		Method method = MixinConfigDiff.class.getDeclaredMethod("loadClassNode", String.class);
		method.setAccessible(true);
		return method.invoke(null, dotClassName);
	}

	private static String classNameOf(Object node) throws Exception {
		Field name = node.getClass().getDeclaredField("name");
		name.setAccessible(true);
		return (String) name.get(node);
	}
}
