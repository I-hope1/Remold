package remold;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ForgeClassInfoSync} 的 Forge / NeoForge 契约：新增字段/方法必须在
 * {@code IMixinTransformer.reload} <b>之前</b>补进 Mixin 的 {@code ClassInfo}、reload 被拒绝时必须
 * 回滚本次补入项、同步失败时不得打断 reload。
 *
 * <p><b>为什么这些替身要放在独立 ClassLoader 里</b>：Forge / NeoForge 的 Mixin 与 ASM 都由
 * ModLauncher 的独立 ClassLoader 加载，插件侧只能拿到 {@code Object}。{@code ClassInfo.findField}
 * 的形参类型是<b>那个</b> ClassLoader 里的 {@code FieldNode}，所以同步逻辑必须按运行时对象的实际
 * 类型推导航；用 Agent 侧同名类型做反射签名会直接 {@code NoSuchMethodException}。本测试把
 * 「同形替身 + 一份独立加载的 ASM」放进子 ClassLoader 来复现这条边界：子加载器里编译出的
 * {@code probe.Fixture} 充当 Mixin 内部结构（{@code MixinTransformer}/{@code MixinProcessor}/
 * {@code MixinConfig}/{@code MixinInfo}/{@code ClassInfo}），父加载器（测试）只通过反射驱动。</p>
 *
 * <p><b>判据落在业务终局</b>：{@code ClassInfo} 的成员集合是否包含目标成员、以及 reload 被调用
 * <b>当时</b>这份元数据是否已经认识新成员——不是日志输出、不是调用次数。</p>
 */
public class ForgeClassInfoSyncTest {

	/** 与 {@code MixinInfo} 首次解析成员时相同的查询口径：INCLUDE_ALL | INCLUDE_INITIALISERS。 */
	private static final byte[] BASELINE_BYTES = mixinBytes(false);
	private static final byte[] EVOLVED_BYTES = mixinBytes(true);

	private static final FixtureAccess FIXTURE = FixtureAccess.create();

	@Test
	void syncRunsBeforeReloadSoNewMembersAreVisibleToTheConformCheck() throws Exception {
		Object transformer = FIXTURE.newTransformer(BASELINE_BYTES, false, new String[] { "probe.ClassInfoTarget" });
		// baseline precondition: the stale ClassInfo must NOT know the members we are about to add.
		assertFalse(FIXTURE.knowsField(transformer, "uniqueB"),
			"baseline: the reused ClassInfo must not know the newly declared @Unique field");
		assertFalse(FIXTURE.knowsMethod(transformer, "hookB"),
			"baseline: the reused ClassInfo must not know the newly declared @Inject handler");

		handleIfMixin(transformer, "probe.ClassInfoMixin", EVOLVED_BYTES);

		assertTrue(FIXTURE.sawNewFieldBeforeReload(transformer),
			"the new @Unique field must be resolvable from ClassInfo before reload runs its conform check");
		assertTrue(FIXTURE.sawNewMethodBeforeReload(transformer),
			"the new @Inject handler must be resolvable from ClassInfo before reload runs its conform check");
		assertEquals(1, FIXTURE.reloadCalls(transformer), "reload must be invoked exactly once for an accepted change");
		assertTrue(FIXTURE.knowsField(transformer, "uniqueB"),
			"an accepted reload must keep the field that was added to ClassInfo");
		assertTrue(FIXTURE.knowsMethod(transformer, "hookB"),
			"an accepted reload must keep the method that was added to ClassInfo");
		assertTrue(FIXTURE.knowsField(transformer, "uniqueA"), "pre-existing fields must stay in ClassInfo");
	}

	@Test
	void addedMembersAreRolledBackWhenReloadIsRejected() throws Exception {
		Object transformer = FIXTURE.newTransformer(BASELINE_BYTES, true, new String[0]);
		int baselineMembers = FIXTURE.memberCount(transformer);

		handleIfMixin(transformer, "probe.ClassInfoMixin", EVOLVED_BYTES);

		// The sync must still have happened first (otherwise the rollback assertion is meaningless).
		assertTrue(FIXTURE.sawNewMethodBeforeReload(transformer),
			"sanity: the rejected reload must have seen the synced member (sync runs before reload)");
		assertEquals(1, FIXTURE.reloadCalls(transformer), "the rejected reload is attempted once");
		assertFalse(FIXTURE.knowsField(transformer, "uniqueB"),
			"a rejected reload must remove the field added for it");
		assertFalse(FIXTURE.knowsMethod(transformer, "hookB"),
			"a rejected reload must remove the method added for it");
		assertEquals(baselineMembers, FIXTURE.memberCount(transformer),
			"rollback must restore the member count of the accepted (old) template");
		assertTrue(FIXTURE.knowsField(transformer, "uniqueA"), "rollback must keep pre-existing fields");
		assertTrue(FIXTURE.knowsMethod(transformer, "hookA"), "rollback must keep pre-existing methods");
	}

	@Test
	void classInfoLookupsAreResolvedAgainstTheTransformerClassLoader() throws Exception {
		Object transformer = FIXTURE.newTransformer(BASELINE_BYTES, false, new String[0]);
		// A ClassNode built with the AGENT-side ASM: its FieldNode/MethodNode classes are NOT the ones
		// ClassInfo's (foreign-loader) signatures expect. This is exactly what would happen if the
		// implementation matched signatures with its own ASM types instead of the host's.
		ClassNode agentSideNode = new ClassNode();
		new ClassReader(EVOLVED_BYTES).accept(agentSideNode, 0);
		int baselineMembers = FIXTURE.memberCount(transformer);

		Object token = syncReflectively(transformer, "probe.ClassInfoMixin", agentSideNode);

		assertNotNull(token, "sync must always return a token (degradation, not an exception)");
		assertEquals(baselineMembers, FIXTURE.memberCount(transformer),
			"a signature mismatch must leave ClassInfo untouched instead of partially mutating it");
		assertFalse(FIXTURE.knowsMethod(transformer, "hookB"),
			"no member may be added when the host-side member types cannot be matched");
	}

	@Test
	void aMissingMixinInfoDoesNotBlockReload() throws Exception {
		// 给一个已加载目标，避免走「点分名查不到 → 内部名重试」这条额外调用路径把 reload 次数算成 2。
		Object transformer = FIXTURE.newTransformerWithoutMixinInfo(BASELINE_BYTES,
			new String[] { "probe.ClassInfoTarget" });

		handleIfMixin(transformer, "probe.NotConfiguredMixin", EVOLVED_BYTES);

		assertEquals(1, FIXTURE.reloadCalls(transformer),
			"metadata sync must never block the reload main path when the MixinInfo cannot be located");
		assertFalse(FIXTURE.knowsMethod(transformer, "hookB"),
			"nothing may be added when there is no MixinInfo to sync into");
	}

	// ---------------------------------------------------------------------------------------------
	// drivers
	// ---------------------------------------------------------------------------------------------

	/** 调用包私有的 {@code ForgeMixinHotSwap.handleIfMixin}，holder 用动态代理提供「没有已加载类」。 */
	private static void handleIfMixin(Object transformer, String dotClassName, byte[] bytes) throws Exception {
		Class<?> hotSwap = Class.forName("remold.forge.ForgeMixinHotSwap");
		Class<?> holderType = Class.forName("remold.forge.ForgeMixinHotSwap$InstrumentationHolder");
		InvocationHandler handler = (proxy, method, args) -> {
			if ("allLoadedClasses".equals(method.getName())) {
				return new Class<?>[0];
			}
			return null;
		};
		Object holder = Proxy.newProxyInstance(hotSwap.getClassLoader(), new Class<?>[] { holderType }, handler);
		Method handle = hotSwap.getDeclaredMethod("handleIfMixin", holderType, Object.class, String.class, byte[].class);
		handle.setAccessible(true);
		handle.invoke(null, holder, transformer, dotClassName, bytes);
	}

	private static Object syncReflectively(Object transformer, String mixinClassName, Object classNode) throws Exception {
		Class<?> sync = Class.forName("remold.forge.ForgeClassInfoSync");
		Method method = sync.getDeclaredMethod("sync", Object.class, String.class, Object.class);
		method.setAccessible(true);
		return method.invoke(null, transformer, mixinClassName, classNode);
	}

	// ---------------------------------------------------------------------------------------------
	// bytecode fixture: a Mixin template with one member added in the evolved version
	// ---------------------------------------------------------------------------------------------

	/**
	 * 生成 {@code probe.ClassInfoMixin} 的字节码。
	 *
	 * <p>演化版本比基线多两个成员：{@code @Inject} handler {@code hookB} 与 {@code @Unique}
	 * 字段 {@code uniqueB}——正是 reload 的 conform 校验会去 {@code ClassInfo} 里查的对象。</p>
	 */
	private static byte[] mixinBytes(boolean evolved) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/ClassInfoMixin", null, "java/lang/Object", null);
		// Constant-pool marker: Forge 侧按「常量池里是否出现 @Mixin 描述符」识别模板类。
		cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE, "uniqueA", "I", null, null).visitEnd();
		if (evolved) {
			cw.visitField(Opcodes.ACC_PRIVATE, "uniqueB", "I", null, null).visitEnd();
		}
		constructor(cw);
		handler(cw, "hookA");
		if (evolved) {
			handler(cw, "hookB");
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void constructor(ClassWriter cw) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
	}

	private static void handler(ClassWriter cw, String name) {
		String desc = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, name, desc, null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 2);
		mv.visitEnd();
	}

	// ---------------------------------------------------------------------------------------------
	// foreign-classloader fixture access
	// ---------------------------------------------------------------------------------------------

	/** 子 ClassLoader 中的 {@code probe.Fixture}：测试只能通过反射驱动它。 */
	private static final class FixtureAccess {

		private static final String SOURCE = """
			package probe;

			import java.util.ArrayList;
			import java.util.HashSet;
			import java.util.List;
			import java.util.Set;

			import org.objectweb.asm.ClassReader;
			import org.objectweb.asm.Opcodes;
			import org.objectweb.asm.tree.ClassNode;
			import org.objectweb.asm.tree.FieldNode;
			import org.objectweb.asm.tree.MethodNode;

			public final class Fixture {

				static final int FLAGS = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | 0x40000;

				private Fixture() {
				}

				public static Object newTransformer(byte[] baselineBytes, boolean reject, String[] targets) {
					ClassNode baseline = read(baselineBytes);
					ClassInfo info = new ClassInfo();
					for (FieldNode field : baseline.fields) {
						info.fields.add(new ClassInfo.Field(info, field, true));
					}
					for (MethodNode method : baseline.methods) {
						info.addMethod(method);
					}
					Processor processor = new Processor();
					MixinConfig config = new MixinConfig();
					config.mixins.add(new MixinInfo("probe.ClassInfoMixin", info));
					processor.configs.add(config);
					return new Transformer(processor, info, reject, targets);
				}

				public static Object newTransformerWithoutMixinInfo(byte[] baselineBytes, String[] targets) {
					ClassNode baseline = read(baselineBytes);
					ClassInfo info = new ClassInfo();
					for (FieldNode field : baseline.fields) {
						info.fields.add(new ClassInfo.Field(info, field, true));
					}
					for (MethodNode method : baseline.methods) {
						info.addMethod(method);
					}
					return new Transformer(new Processor(), info, false, targets);
				}

				public static boolean knowsField(Object transformer, String name) {
					for (ClassInfo.Field field : ((Transformer) transformer).classInfo.fields) {
						if (field.name.equals(name)) {
							return true;
						}
					}
					return false;
				}

				public static boolean knowsMethod(Object transformer, String name) {
					ClassInfo info = ((Transformer) transformer).classInfo;
					for (Member member : info.methods) {
						if (member.name.equals(name)) {
							return true;
						}
					}
					for (Member member : info.initialisers) {
						if (member.name.equals(name)) {
							return true;
						}
					}
					return false;
				}

				public static int memberCount(Object transformer) {
					ClassInfo info = ((Transformer) transformer).classInfo;
					return info.fields.size() + info.methods.size() + info.initialisers.size();
				}

				public static int reloadCalls(Object transformer) {
					return ((Transformer) transformer).reloadCalls;
				}

				public static boolean sawNewFieldBeforeReload(Object transformer) {
					return ((Transformer) transformer).sawNewField;
				}

				public static boolean sawNewMethodBeforeReload(Object transformer) {
					return ((Transformer) transformer).sawNewMethod;
				}

				static ClassNode read(byte[] bytes) {
					ClassNode node = new ClassNode();
					new ClassReader(bytes).accept(node, 0);
					return node;
				}

				public static final class Transformer {
					private final Processor processor;
					private final ClassInfo classInfo;
					private final boolean reject;
					private final List<String> targets;
					private int reloadCalls;
					private boolean sawNewField;
					private boolean sawNewMethod;

					Transformer(Processor processor, ClassInfo classInfo, boolean reject, String[] targets) {
						this.processor = processor;
						this.classInfo = classInfo;
						this.reject = reject;
						this.targets = new ArrayList<String>();
						for (String target : targets) {
							this.targets.add(target);
						}
					}

					/** Mixin 的 reload 入口；在这里观测「调用当时 ClassInfo 是否已认识新成员」。 */
					public List<String> reload(String className, ClassNode node) {
						this.reloadCalls++;
						for (FieldNode field : node.fields) {
							if ("uniqueB".equals(field.name)) {
								this.sawNewField = this.classInfo.findField(field) != null;
							}
						}
						for (MethodNode method : node.methods) {
							if ("hookB".equals(method.name)) {
								this.sawNewMethod = this.classInfo.findMethod(method, FLAGS) != null;
							}
						}
						if (this.reject) {
							throw new IllegalStateException("Conform error: injector hookB (probe fixture)");
						}
						return this.targets;
					}
				}

				static final class Processor {
					private final List<MixinConfig> configs = new ArrayList<MixinConfig>();
				}

				static final class MixinConfig {
					private final List<MixinInfo> mixins = new ArrayList<MixinInfo>();
				}

				static final class MixinInfo {
					private final String className;
					private final ClassInfo classInfo;

					MixinInfo(String className, ClassInfo classInfo) {
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

				static final class ClassInfo {
					private final Set<Field> fields = new HashSet<Field>();
					private final Set<Member> methods = new HashSet<Member>();
					private final Set<Member> initialisers = new HashSet<Member>();

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
						Member member = new Member(node.name, node.desc);
						if (node.name.startsWith("<")) {
							this.initialisers.add(member);
						} else {
							this.methods.add(member);
						}
					}

					public static final class Field {
						private final String name;
						private final String desc;

						public Field(ClassInfo owner, FieldNode node, boolean injected) {
							this.name = node.name;
							this.desc = node.desc;
						}
					}
				}

				static final class Member {
					private final String name;
					private final String desc;

					Member(String name, String desc) {
						this.name = name;
						this.desc = desc;
					}
				}
			}
			""";

		private final Class<?> fixture;

		private FixtureAccess(Class<?> fixture) {
			this.fixture = fixture;
		}

		static FixtureAccess create() {
			try {
				Path root = Files.createTempDirectory("remold-forge-classinfo-");
				Path source = root.resolve("probe").resolve("Fixture.java");
				Files.createDirectories(source.getParent());
				Files.write(source, SOURCE.getBytes(StandardCharsets.UTF_8));
				Path classes = Files.createDirectories(root.resolve("classes"));

				List<String> options = new ArrayList<>();
				options.add("-proc:none");
				options.add("-encoding");
				options.add("UTF-8");
				options.add("-classpath");
				options.add(asmClasspath());
				options.add("-d");
				options.add(classes.toString());
				options.add(source.toString());

				JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
				assertNotNull(compiler, "the test JVM must ship a Java compiler to build the fixture");
				ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
				int exit = compiler.run(null, diagnostics, diagnostics, options.toArray(new String[0]));
				assertEquals(0, exit, "fixture compilation failed:\n" + diagnostics.toString(StandardCharsets.UTF_8));

				List<URL> urls = new ArrayList<>();
				for (String entry : asmClasspath().split(java.io.File.pathSeparator)) {
					urls.add(Paths.get(entry).toUri().toURL());
				}
				urls.add(classes.toUri().toURL());
				// 父加载器刻意选 platform：让替身与它引用的 ASM 都落在子加载器里，复现宿主那一侧的类型隔离。
				URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
				return new FixtureAccess(Class.forName("probe.Fixture", true, loader));
			} catch (Exception e) {
				throw new IllegalStateException("unable to build the foreign-classloader fixture", e);
			}
		}

		/** 替身编译与运行都要用到的 ASM 位置（asm core + asm-tree）。 */
		private static String asmClasspath() {
			Set<String> entries = new LinkedHashSet<>();
			for (Class<?> type : new Class<?>[] { ClassReader.class, ClassNode.class }) {
				try {
					entries.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
				} catch (Exception e) {
					throw new IllegalStateException("unable to locate the ASM classes for " + type, e);
				}
			}
			return String.join(java.io.File.pathSeparator, entries);
		}

		Object newTransformer(byte[] baselineBytes, boolean reject, String[] targets) throws Exception {
			Method method = this.fixture.getMethod("newTransformer", byte[].class, boolean.class, String[].class);
			return method.invoke(null, baselineBytes, reject, targets);
		}

		Object newTransformerWithoutMixinInfo(byte[] baselineBytes, String[] targets) throws Exception {
			Method method = this.fixture.getMethod("newTransformerWithoutMixinInfo", byte[].class, String[].class);
			return method.invoke(null, baselineBytes, targets);
		}

		boolean knowsField(Object transformer, String name) throws Exception {
			return (boolean) invoke("knowsField", transformer, name);
		}

		boolean knowsMethod(Object transformer, String name) throws Exception {
			return (boolean) invoke("knowsMethod", transformer, name);
		}

		int memberCount(Object transformer) throws Exception {
			return (int) invoke("memberCount", transformer);
		}

		int reloadCalls(Object transformer) throws Exception {
			return (int) invoke("reloadCalls", transformer);
		}

		boolean sawNewFieldBeforeReload(Object transformer) throws Exception {
			return (boolean) invoke("sawNewFieldBeforeReload", transformer);
		}

		boolean sawNewMethodBeforeReload(Object transformer) throws Exception {
			return (boolean) invoke("sawNewMethodBeforeReload", transformer);
		}

		private Object invoke(String name, Object transformer, String... extra) throws Exception {
			Class<?>[] parameterTypes;
			Object[] arguments;
			if (extra.length == 0) {
				parameterTypes = new Class<?>[] { Object.class };
				arguments = new Object[] { transformer };
			} else {
				parameterTypes = new Class<?>[] { Object.class, String.class };
				arguments = new Object[] { transformer, extra[0] };
			}
			return this.fixture.getMethod(name, parameterTypes).invoke(null, arguments);
		}
	}
}
