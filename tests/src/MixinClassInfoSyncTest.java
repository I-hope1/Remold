package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code MixinClassInfoSync} 的反射契约：旧 {@code ClassInfo} 不认识新字节码里新增的
 * 字段/方法，同步后必须认识；reload 被拒绝时回滚必须把「本次补入」的成员摘掉、
 * 保留原有成员。
 *
 * <p><b>为什么方法同步与顺序有关</b>：{@code MixinInfo.reloadMixin} 的校验阶段会拿新
 * 字节码里的 {@code @Inject} handler 到这份（被 reload 复用的）ClassInfo 里查 Method
 * 句柄；查不到就抛 {@code Conform error ...}，把可热更的「新增/改名 handler」降级成
 * 「请重启客户端」。所以 {@code MixinHotSwap} 必须在 reload 之前同步元数据。</p>
 *
 * <p>使用与 Mixin {@code ClassInfo} 同形的测试替身，避免在纯 JUnit JVM 里初始化
 * {@code MixinService}/{@code MixinEnvironment}（那需要 Fabric/LaunchWrapper/ModLauncher 服务）。
 * 真实 Mixin {@code ClassInfo} 的构造签名与端到端行为由真实 Fabric 探针
 * （{@code tests/fabric-mixin-probe/evo/}）验收覆盖。</p>
 */
public class MixinClassInfoSyncTest {

	private static final Class<?> SYNC = syncClass();

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

		List<Object> addedOut = new ArrayList<Object>();
		Method addMissing = SYNC.getDeclaredMethod("addMissingFields", Object.class, ClassNode.class, List.class);
		addMissing.setAccessible(true);
		int count = (int) addMissing.invoke(null, classInfo, newBytes, addedOut);

		assertEquals(1, count, "exactly one newly declared field must be added");
		assertEquals(1, addedOut.size(), "the sync token must expose the field it added");
		assertNotNull(classInfo.findField(added), "after sync, the new field must be resolvable");
	}

	@Test
	void newInjectorMethodIsAddedToTheStaleClassInfo() throws Exception {
		FakeClassInfo classInfo = new FakeClassInfo();
		classInfo.seedMethod(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null));
		MethodNode added = new MethodNode(Opcodes.ACC_PRIVATE, "evoCHookTwo",
			"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		assertNull(classInfo.findMethod(added, 0), "baseline: stale ClassInfo must not know the new handler");

		ClassNode newBytes = new ClassNode();
		newBytes.name = "probe/SyncMixinFixture";
		newBytes.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null));
		newBytes.methods.add(added);

		List<Object> addedOut = new ArrayList<Object>();
		Method addMissing = SYNC.getDeclaredMethod("addMissingMethods", Object.class, ClassNode.class, List.class);
		addMissing.setAccessible(true);
		int count = (int) addMissing.invoke(null, classInfo, newBytes, addedOut);

		assertEquals(1, count, "exactly one newly declared injector method must be added");
		assertEquals(1, addedOut.size(), "the sync token must expose the method it added");
		assertNotNull(classInfo.findMethod(added, 0), "after sync, the new handler must be resolvable");
		assertNotNull(classInfo.findMethod(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null), 0),
			"pre-existing methods must be untouched");
	}

	@Test
	void addedMembersAreRolledBackWhenReloadIsRejected() throws Exception {
		FakeClassInfo classInfo = new FakeClassInfo();
		FieldNode existingField = new FieldNode(Opcodes.ACC_PRIVATE, "existing", "I", null, 0);
		FieldNode addedField = new FieldNode(Opcodes.ACC_PRIVATE, "remold$author", "Ljava/lang/String;", null, null);
		MethodNode addedMethod = new MethodNode(Opcodes.ACC_PRIVATE, "evoCHookTwo",
			"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		classInfo.seed(existingField);
		classInfo.seedMethod(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null));

		ClassNode newBytes = new ClassNode();
		newBytes.name = "probe/SyncMixinFixture";
		newBytes.fields.add(existingField);
		newBytes.fields.add(addedField);
		newBytes.methods.add(addedMethod);

		List<Object> addedFields = new ArrayList<Object>();
		List<Object> addedMethods = new ArrayList<Object>();
		Method addFields = SYNC.getDeclaredMethod("addMissingFields", Object.class, ClassNode.class, List.class);
		addFields.setAccessible(true);
		addFields.invoke(null, classInfo, newBytes, addedFields);
		Method addMethods = SYNC.getDeclaredMethod("addMissingMethods", Object.class, ClassNode.class, List.class);
		addMethods.setAccessible(true);
		addMethods.invoke(null, classInfo, newBytes, addedMethods);
		assertNotNull(classInfo.findField(addedField), "precondition: field was added");
		assertNotNull(classInfo.findMethod(addedMethod, 0), "precondition: method was added");

		Class<?> tokenType = Class.forName("remold.mixin.MixinClassInfoSync$SyncToken");
		Constructor<?> tokenCtor = tokenType.getDeclaredConstructor(Object.class, Object.class, List.class, List.class);
		tokenCtor.setAccessible(true);
		Object token = tokenCtor.newInstance(null, classInfo, addedFields, addedMethods);
		Method rollback = SYNC.getDeclaredMethod("rollback", tokenType);
		rollback.setAccessible(true);
		rollback.invoke(null, token);

		assertNull(classInfo.findField(addedField), "rollback must remove the field added for the rejected reload");
		assertNull(classInfo.findMethod(addedMethod, 0), "rollback must remove the method added for the rejected reload");
		assertNotNull(classInfo.findField(existingField), "rollback must keep pre-existing fields");
		assertNotNull(classInfo.findMethod(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null), 0),
			"rollback must keep pre-existing methods");
	}

	private static Class<?> syncClass() {
		try {
			return Class.forName("remold.mixin.MixinClassInfoSync");
		} catch (Exception e) {
			throw new IllegalStateException("MixinClassInfoSync is not on the test classpath", e);
		}
	}

	/** Shape-compatible stand-in for Mixin's ClassInfo: findField/$Field, findMethod/addMethod + member sets. */
	public static final class FakeClassInfo {
		private final Set<Field> fields = new HashSet<Field>();
		private final Set<MethodDef> methods = new HashSet<MethodDef>();
		private final Set<MethodDef> initialisers = new HashSet<MethodDef>();

		void seed(FieldNode node) {
			fields.add(new Field(this, node, true));
		}

		void seedMethod(MethodNode node) {
			methods.add(new MethodDef(node, true));
		}

		public Field findField(FieldNode node) {
			for (Field field : fields) {
				if (field.name.equals(node.name) && field.desc.equals(node.desc)) {
					return field;
				}
			}
			return null;
		}

		public MethodDef findMethod(MethodNode node, int flags) {
			for (MethodDef method : methods) {
				if (method.name.equals(node.name) && method.desc.equals(node.desc)) {
					return method;
				}
			}
			for (MethodDef method : initialisers) {
				if (method.name.equals(node.name) && method.desc.equals(node.desc)) {
					return method;
				}
			}
			return null;
		}

		void addMethod(MethodNode node) {
			if (node.name.startsWith("<")) {
				initialisers.add(new MethodDef(node, true));
			} else {
				methods.add(new MethodDef(node, true));
			}
		}

		public static final class Field {
			final String name;
			final String desc;

			public Field(FakeClassInfo owner, FieldNode node, boolean injected) {
				this.name = node.name;
				this.desc = node.desc;
			}
		}

		public static final class MethodDef {
			final String name;
			final String desc;

			public MethodDef(MethodNode node, boolean injected) {
				this.name = node.name;
				this.desc = node.desc;
			}
		}
	}
}
