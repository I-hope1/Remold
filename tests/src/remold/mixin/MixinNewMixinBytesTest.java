package remold.mixin;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import remold.BytecodeSources;
import remold.reflect.mixin.MixinClassByteSources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MixinNewMixinBytes} 的回灌契约：新 Mixin 自身与它的 {@code $N} 内部类字节必须同时进入
 * Mixin 的字节码登记表（{@link MixinBytecodeOverlay}）与 core 的 {@link BytecodeSources}，
 * 否则 {@code prepare()} 构造 {@code MixinInfo} 时读不到类 / 读到上游负缓存。
 *
 * <p>真实 Mixin 服务在单元测试里不可用（{@code MixinService.getService()} 抛
 * {@code ServiceNotAvailableError}），因此覆盖层<b>安装</b>在测试里处于降级态；本测试断言的是
 * 登记表（回灌）本身，它必须与安装状态解耦——安装成功的那一次热更才会真正读到这些字节。</p>
 *
 * <p>对照：未登记的名字在两个通道都必须是 {@code null}（回灌不是"无条件放行"）。</p>
 */
class MixinNewMixinBytesTest {

	private static final String MIXIN_DOT = "com.example.mixin.PreRegisteredMixin";

	private static final String MIXIN_INTERNAL = "com/example/mixin/PreRegisteredMixin";

	private static final String INNER_INTERNAL = MIXIN_INTERNAL + "$1";

	/** 未登记的对照类名。 */
	private static final String UNKNOWN_DOT = "com.example.mixin.NeverRegisteredMixin";

	/** 内部类字节的来源替身（真实环境里是同一批次编译出的 {@code .class}，走监控目录通道）。 */
	private static final Function<String, byte[]> INNER_SOURCE = name -> {
		if (name == null) {
			return null;
		}
		return INNER_INTERNAL.equals(name.replace('.', '/')) ? innerBytes() : null;
	};

	@Test
	void registersTheMixinAndItsInnerClassOnBothChannels() {
		BytecodeSources.register(INNER_SOURCE);
		try {
			ClassNode node = nodeOf(mixinBytes());
			MixinNewMixinBytes.register(MIXIN_DOT, mixinBytes(), node);

			assertNotNull(MixinBytecodeOverlay.lookup(MIXIN_DOT),
				"the new mixin itself must be visible to Mixin's bytecode provider"
					+ " (otherwise prepare() throws 'mixin was not found')");
			assertNotNull(MixinBytecodeOverlay.lookup(MIXIN_DOT + "$1"),
				"the new mixin's $N inner class must be pre-registered too"
					+ " (otherwise ClassInfo reads null and MixinInfo.State blows up)");
			assertTrue(MixinBytecodeOverlay.registeredInternalNames().contains(INNER_INTERNAL),
				"the overlay must hold the inner-class internal name: "
					+ MixinBytecodeOverlay.registeredInternalNames());
			assertArrayEquals(mixinBytes(), MixinBytecodeOverlay.lookup(MIXIN_DOT),
				"the overlay must hold the same bytes that were read for this batch");

			assertNotNull(BytecodeSources.lookup(MIXIN_DOT),
				"core-side analysis (nest scan / member visibility) must see the new class as well");
			assertNotNull(BytecodeSources.lookup(MIXIN_INTERNAL),
				"internal-name spellings must resolve to the same registered bytes");
			assertNotNull(MixinClassByteSources.lookup(INNER_INTERNAL),
				"the inner-class bytes must stay reachable through the shared lookup chain");
		} finally {
			BytecodeSources.unregister(INNER_SOURCE);
			MixinClassByteSources.unregister(MIXIN_DOT);
			MixinClassByteSources.unregister(INNER_INTERNAL);
			MixinBytecodeOverlay.clear(List.of(MIXIN_DOT, MIXIN_DOT + "$1"));
		}
	}

	@Test
	void unregisteredNamesStayUnresolvedOnBothChannels() {
		assertNull(MixinBytecodeOverlay.lookup(UNKNOWN_DOT),
			"the overlay must not answer names that were never registered");
		assertNull(BytecodeSources.lookup(UNKNOWN_DOT),
			"the core channel must not answer names that were never registered");
	}

	/** 内部类字节在全部通道都取不到时：Mixin 自身照常回灌，内部类必须点名 warn（C5），不得静默。 */
	@Test
	void missingInnerClassBytesAreReportedByName() throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream original = System.out;
		PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(capture);
		try {
			MixinNewMixinBytes.register(MIXIN_DOT, mixinBytes(), nodeOf(mixinBytes()));
		} finally {
			System.setOut(original);
			capture.close();
		}
		String output = buffer.toString(StandardCharsets.UTF_8);

		assertNull(MixinBytecodeOverlay.lookup(MIXIN_DOT + "$1"),
			"no channel has the inner-class bytes, so nothing may be registered for it");
		assertTrue(output.contains(INNER_INTERNAL),
			"the diagnostic must name the inner class it could not find:\n" + output);
		assertTrue(output.contains("[WARN]"), "the refusal must be a warn, not a silent skip:\n" + output);

		MixinClassByteSources.unregister(MIXIN_DOT);
		MixinBytecodeOverlay.clear(List.of(MIXIN_DOT, MIXIN_DOT + "$1"));
	}

	// ------------------------------------------------------------------ fixtures

	/** 真实 class 文件：{@code PreRegisteredMixin} 声明一个非 static、非 synthetic 的 {@code $1}。 */
	private static byte[] mixinBytes() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
			MIXIN_INTERNAL, null, "java/lang/Object", null);
		writer.visitInnerClass(INNER_INTERNAL, MIXIN_INTERNAL, null, Opcodes.ACC_PUBLIC);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] innerBytes() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
			INNER_INTERNAL, null, "java/lang/Object", null);
		writer.visitInnerClass(INNER_INTERNAL, MIXIN_INTERNAL, null, Opcodes.ACC_PUBLIC);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static ClassNode nodeOf(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
