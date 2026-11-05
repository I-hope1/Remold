package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code MixinClassInfoSync.addMissingFields} 的反射契约：旧 ClassInfo 不认识新字段，
 * 同步后必须认识。
 *
 * <p>使用与 Mixin {@code ClassInfo} 同形的测试替身，避免在纯 JUnit JVM 里初始化
 * {@code MixinService}/{@code MixinEnvironment}（那需要 Fabric/LaunchWrapper/ModLauncher 服务）。
 * 真实 Mixin {@code ClassInfo} 的构造签名与端到端行为由真实 Fabric 客户端验收覆盖。</p>
 */
public class MixinClassInfoSyncTest {

	@Test
	void newFieldIsAddedToTheStaleClassInfo() throws Exception {
		FakeClassInfo classInfo = new FakeClassInfo();
		classInfo.seed(new FieldNode(Opcodes.ACC_PRIVATE, "existing", "I", null, 0));
		FieldNode added = new FieldNode(Opcodes.ACC_PRIVATE, "remold$author", "Ljava/lang/String;", null, null);
		assertNull(classInfo.findField(added), "baseline: stale ClassInfo must not know the new field");

		ClassNode newBytes = new ClassNode();
		newBytes.name = "probe/SyncMixinFixture";
		newBytes.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "existing", "I", null, 0));
		newBytes.fields.add(added);

		Class<?> sync = Class.forName("remold.mixin.MixinClassInfoSync");
		Method addMissing = sync.getDeclaredMethod("addMissingFields", Object.class, ClassNode.class);
		addMissing.setAccessible(true);
		int count = (int) addMissing.invoke(null, classInfo, newBytes);

		assertEquals(1, count, "exactly one newly declared field must be added");
		assertNotNull(classInfo.findField(added), "after sync, the new field must be resolvable");
	}

	/** Shape-compatible stand-in for Mixin's ClassInfo: public findField + $Field(classInfo, node, injected). */
	public static final class FakeClassInfo {
		private final Set<Field> fields = new HashSet<>();

		void seed(FieldNode node) {
			fields.add(new Field(this, node, true));
		}

		public Field findField(FieldNode node) {
			for (Field field : fields) {
				if (field.name.equals(node.name) && field.desc.equals(node.desc)) {
					return field;
				}
			}
			return null;
		}

		public static final class Field {
			final String name;
			final String desc;

			public Field(FakeClassInfo owner, FieldNode node, boolean injected) {
				this.name = node.name;
				this.desc = node.desc;
			}
		}
	}
}
