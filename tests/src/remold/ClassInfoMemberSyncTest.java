package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import remold.reflect.ClassInfoMemberSync;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassInfoMemberSync}（core 共享引擎）的契约：旧 {@code ClassInfo} 不认识新字节码里新增的
 * 字段/方法，同步后必须认识；reload 被拒绝时回滚必须只摘掉「本次补入」的成员；**半程失败**
 * （字段已补、补方法时抛错）同样必须回滚干净——这是 Fabric 旧实现缺失、Forge 实现已有的语义，
 * 收敛后两侧共用。
 *
 * <p>使用与 Mixin {@code ClassInfo} 同形的测试替身，避免在纯 JUnit JVM 里初始化
 * {@code MixinService}/{@code MixinEnvironment}。真实 Mixin {@code ClassInfo} 的构造签名与端到端
 * 行为由真实 Forge / NeoForge 探针覆盖；跨 ClassLoader 的类型解析由
 * {@code ForgeClassInfoSyncTest} 覆盖。</p>
 */
public class ClassInfoMemberSyncTest {

	@Test
	void newFieldIsAddedToTheStaleClassInfo() throws Exception {
		FakeClassInfo classInfo = new FakeClassInfo();
		classInfo.seed(new FieldNode(Opcodes.ACC_PRIVATE, "existing", "I", null, 0));
		FieldNode added = new FieldNode(Opcodes.ACC_PRIVATE, "remold$author", "Ljava/lang/String;", null, null);
		assertNull(classInfo.findField(added), "baseline: stale ClassInfo must not know the new field");

		ClassNode newBytes = new ClassNode();
		newBytes.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "existing", "I", null, 0));
		newBytes.fields.add(added);

		List<Object> addedOut = new ArrayList<Object>();
		Method addMissing = engine().getDeclaredMethod("addMissingFields", Object.class, Object.class, List.class);
		addMissing.setAccessible(true);
		addMissing.invoke(null, classInfo, newBytes, addedOut);

		assertEquals(1, addedOut.size(), "the engine must expose exactly the newly added field handle");
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
		newBytes.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null));
		newBytes.methods.add(added);

		List<Object> addedOut = new ArrayList<Object>();
		Method addMissing = engine().getDeclaredMethod("addMissingMethods", Object.class, Object.class, List.class);
		addMissing.setAccessible(true);
		addMissing.invoke(null, classInfo, newBytes, addedOut);

		assertEquals(1, addedOut.size(), "the engine must expose exactly the newly added method handle");
		assertNotNull(classInfo.findMethod(added, 0), "after sync, the new handler must be resolvable");
		assertNotNull(classInfo.findMethod(new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null), 0),
			"pre-existing methods must be untouched");
	}

	@Test
	void addedMembersAreRolledBackWhenReloadIsRejected() {
		Fixture fixture = Fixture.create(false);
		fixture.classInfo.seed(fixture.existingField);
		fixture.classInfo.seedMethod(fixture.existingMethod);

		ClassInfoMemberSync.Token token = ClassInfoMemberSync.sync(
			fixture.transformer, "probe.SyncMixinFixture", fixture.evolvedBytes, "[test]");

		assertTrue(token.addedFieldCount() + token.addedMethodCount() > 0,
			"precondition: the evolved bytes must add members");
		assertNotNull(fixture.classInfo.findField(fixture.addedField), "precondition: field was added");
		assertNotNull(fixture.classInfo.findMethod(fixture.addedMethod, 0), "precondition: method was added");

		ClassInfoMemberSync.rollback(token);

		assertNull(fixture.classInfo.findField(fixture.addedField),
			"rollback must remove the field added for the rejected reload");
		assertNull(fixture.classInfo.findMethod(fixture.addedMethod, 0),
			"rollback must remove the method added for the rejected reload");
		assertNotNull(fixture.classInfo.findField(fixture.existingField), "rollback must keep pre-existing fields");
		assertNotNull(fixture.classInfo.findMethod(fixture.existingMethod, 0),
			"rollback must keep pre-existing methods");
	}

	/**
	 * 半程失败（字段已补、补方法时抛错）必须回滚已补入的字段：引擎在第一次改写成员集合<b>之前</b>
	 * 就把可变列表挂到句柄上，因此 catch 分支不是空操作。旧 Fabric 实现（两次补入都成功后才建句柄）
	 * 在这条用例下会残留成员。
	 */
	@Test
	void halfWayFailureRollsBackWhatWasAlreadyAdded() {
		Fixture fixture = Fixture.create(true);
		fixture.classInfo.seed(fixture.existingField);

		ClassInfoMemberSync.Token token = ClassInfoMemberSync.sync(
			fixture.transformer, "probe.SyncMixinFixture", fixture.evolvedBytes, "[test]");

		assertTrue(token.isEmpty(), "a failed sync must degrade to an empty token");
		assertNull(fixture.classInfo.findField(fixture.addedField),
			"the field added before the method phase blew up must be rolled back");
		assertNotNull(fixture.classInfo.findField(fixture.existingField),
			"pre-existing fields must survive a half-way failure");
	}

	@Test
	void syncWithoutMixinInfoIsANoOp() {
		Fixture fixture = Fixture.create(false);
		fixture.processor.configs.clear();

		ClassInfoMemberSync.Token token = ClassInfoMemberSync.sync(
			fixture.transformer, "probe.SyncMixinFixture", fixture.evolvedBytes, "[test]");

		assertTrue(token.isEmpty(), "no MixinInfo must degrade to an empty token");
		assertNull(fixture.classInfo.findField(fixture.addedField), "nothing may be added without a MixinInfo");
	}

	private static Class<?> engine() {
		try {
			return Class.forName("remold.reflect.ClassInfoMemberSync");
		} catch (Exception e) {
			throw new IllegalStateException("ClassInfoMemberSync is not on the test classpath", e);
		}
	}

	/** Transformer → Processor → Config → MixinInfo → ClassInfo 的同形替身链。 */
	private static final class Fixture {
		final FakeTransformer transformer;
		final FakeProcessor processor;
		final FakeClassInfo classInfo;
		final ClassNode evolvedBytes;
		final FieldNode addedField;
		final MethodNode addedMethod;
		final FieldNode existingField;
		final MethodNode existingMethod;

		private Fixture(FakeTransformer transformer, FakeProcessor processor, FakeClassInfo classInfo,
			ClassNode evolvedBytes, FieldNode addedField, MethodNode addedMethod,
			FieldNode existingField, MethodNode existingMethod) {
			this.transformer = transformer;
			this.processor = processor;
			this.classInfo = classInfo;
			this.evolvedBytes = evolvedBytes;
			this.addedField = addedField;
			this.addedMethod = addedMethod;
			this.existingField = existingField;
			this.existingMethod = existingMethod;
		}

		static Fixture create(boolean failOnMethodPhase) {
			ClassNode evolved = new ClassNode();
			evolved.name = "probe/SyncMixinFixture";
			FieldNode existingField = new FieldNode(Opcodes.ACC_PRIVATE, "existing", "I", null, 0);
			FieldNode addedField = new FieldNode(Opcodes.ACC_PRIVATE, "remold$author", "Ljava/lang/String;", null, null);
			evolved.fields.add(existingField);
			evolved.fields.add(addedField);
			MethodNode existingMethod = new MethodNode(Opcodes.ACC_PRIVATE, "existing", "()V", null, null);
			MethodNode addedMethod = new MethodNode(Opcodes.ACC_PRIVATE, "evoCHookTwo",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
			evolved.methods.add(existingMethod);
			evolved.methods.add(addedMethod);

			FakeClassInfo classInfo = new FakeClassInfo();
			if (failOnMethodPhase) {
				classInfo.failOnMethod("evoCHookTwo");
			}
			FakeMixinInfo mixinInfo = new FakeMixinInfo("probe.SyncMixinFixture", classInfo);
			FakeConfig config = new FakeConfig();
			config.mixins.add(mixinInfo);
			FakeProcessor processor = new FakeProcessor();
			processor.configs.add(config);
			return new Fixture(new FakeTransformer(processor), processor, classInfo, evolved,
				addedField, addedMethod, existingField, existingMethod);
		}
	}

	private static final class FakeTransformer {
		private final FakeProcessor processor;

		FakeTransformer(FakeProcessor processor) {
			this.processor = processor;
		}
	}

	private static final class FakeProcessor {
		private final List<Object> configs = new ArrayList<Object>();
	}

	private static final class FakeConfig {
		private final List<Object> mixins = new ArrayList<Object>();
	}

	private static final class FakeMixinInfo {
		private final String className;
		private final Object classInfo;

		FakeMixinInfo(String className, Object classInfo) {
			this.className = className;
			this.classInfo = classInfo;
		}

		public String getClassName() {
			return this.className;
		}

		Object getClassInfo() {
			return this.classInfo;
		}
	}

	/** 与 Mixin {@code ClassInfo} 同形：findField/$Field、findMethod/addMethod + 成员集合。 */
	public static final class FakeClassInfo {
		private final Set<Field> fields = new HashSet<Field>();
		private final Set<Member> methods = new HashSet<Member>();
		private final Set<Member> initialisers = new HashSet<Member>();
		private String failOnMethodName;

		void failOnMethod(String name) {
			this.failOnMethodName = name;
		}

		void seed(FieldNode node) {
			this.fields.add(new Field(this, node, true));
		}

		void seedMethod(MethodNode node) {
			this.methods.add(new Member(node.name, node.desc));
		}

		public Field findField(FieldNode node) {
			for (Field field : this.fields) {
				if (field.name.equals(node.name) && field.desc.equals(node.desc)) {
					return field;
				}
			}
			return null;
		}

		public Member findMethod(MethodNode node, int flags) {
			for (Member method : this.methods) {
				if (method.name.equals(node.name) && method.desc.equals(node.desc)) {
					return method;
				}
			}
			for (Member method : this.initialisers) {
				if (method.name.equals(node.name) && method.desc.equals(node.desc)) {
					return method;
				}
			}
			return null;
		}

		void addMethod(MethodNode node) {
			if (this.failOnMethodName != null && this.failOnMethodName.equals(node.name)) {
				throw new IllegalStateException("simulated failure while adding " + node.name);
			}
			Member member = new Member(node.name, node.desc);
			if (node.name.startsWith("<")) {
				this.initialisers.add(member);
			} else {
				this.methods.add(member);
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
	}

	static final class Member {
		final String name;
		final String desc;

		Member(String name, String desc) {
			this.name = name;
			this.desc = desc;
		}
	}
}
