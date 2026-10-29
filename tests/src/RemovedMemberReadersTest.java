import org.junit.jupiter.api.Test;
import remold.ref.RemovedMemberReaders;
import remold.ref.RemovedMemberReaders.RemovedMember;
import remold.ref.RemovedMemberReaders.Result;
import remold.ref.RemovedMemberReaders.TypeInfo;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「批次外读者」判据的断言（{@code docs/status.md} 风险 11 / 风险 12，
 * 判据推导见 {@code docs/initfix/07-p05-probe-results.md} §13.6）。
 *
 * <p>每条用例都编译两轮：v1 是改动前的世界（读者能编译），v2 只重编真正变化的类；读者的字节码沿用 v1
 * ——这正是"陈旧读者"的形态。断言同时看两件事：改动前引用是否真的指向被删成员（归属），改动后是否悬空
 * （后果）。只判其中一半都会出错，所以反向对照不可省。</p>
 */
class RemovedMemberReadersTest {

	// ------------------------------------------------------------------------------------------------
	// 字段：owner 是静态类型
	// ------------------------------------------------------------------------------------------------

	/**
	 * {@code Fieldref} 的 owner 是编译期静态类型，不是声明类。读者用子类静态类型读父类字段时，
	 * 引用写成 {@code Field Sub.x}——只按声明类匹配会漏报。
	 */
	@Test
	void readerThroughSubclassOwnerIsFound() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int x = 1; }",
			       "p/Sub", "package p; public class Sub extends A { }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return s.x; } }"),
			Map.of("p/A", "package p; public class A { }"));

		Result r = find(w, removedField("p/A", "x", "I", false));

		assertTrue(r.hasReaders(), "a reader through a subclass-typed receiver must be reported");
		assertEquals("p/Reader", r.hits().get(0).reader());
		assertEquals("p/Sub", r.hits().get(0).refOwner(),
			"the reference owner is the static type (Sub), not the declaring class (A)");
	}

	/** 去触发对照：读者被重编成不再读该字段，同一批字节码里就不该再报出读者。 */
	@Test
	void readerThatNoLongerReadsIsNotReported() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int x = 1; }",
			       "p/Sub", "package p; public class Sub extends A { }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return s.x; } }"),
			Map.of("p/A", "package p; public class A { }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return 0; } }"));

		assertFalse(find(w, removedField("p/A", "x", "I", false)).hasReaders(),
			"a reader whose new bytecode dropped the read must not be reported");
	}

	/** 换无关输入对照：删的是另一个类的同名字段，读者不该被算到它头上。 */
	@Test
	void sameNameInAnotherClassIsNotReported() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int x = 1; }",
			       "p/B", "package p; public class B { public int x = 2; }",
			       "p/Reader", "package p; public class Reader { static int read(B b) { return b.x; } }"),
			Map.of("p/A", "package p; public class A { }"));

		assertFalse(find(w, removedField("p/A", "x", "I", false)).hasReaders(),
			"removing A.x must not be blamed on a reader of B.x");
	}

	/** 子类自己声明了同名字段时，{@code Sub.x} 指向 {@code Sub.x}，不是被删的 {@code A.x}。 */
	@Test
	void hidingSubclassFieldIsNotAReader() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int x = 1; }",
			       "p/Sub", "package p; public class Sub extends A { public int x = 2; }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return s.x; } }"),
			Map.of("p/A", "package p; public class A { }"));

		assertFalse(find(w, removedField("p/A", "x", "I", false)).hasReaders(),
			"a hiding subclass field resolves to Sub.x, not the removed A.x");
	}

	/**
	 * 同一次热更里两个类都删掉同名同描述的字段时，引用必须归属到<b>它本来指向的那个</b>。
	 * 只判"改动后悬空"而不做改动前的归属，会把 B 的读者同时算到 A 头上。
	 */
	@Test
	void attributionPicksTheRightOwnerWhenTwoClassesLoseTheSameField() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int x = 1; }",
			       "p/B", "package p; public class B { public int x = 2; }",
			       "p/Reader", "package p; public class Reader { static int read(B b) { return b.x; } }"),
			Map.of("p/A", "package p; public class A { }",
			       "p/B", "package p; public class B { }"));

		Result r = RemovedMemberReaders.find(RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()), w.post(),
			List.of(removedField("p/A", "x", "I", false), removedField("p/B", "x", "I", false)));

		assertEquals(1, r.hits().size(), "only the member the reference actually pointed at may be blamed");
		assertEquals("p/B", r.hits().get(0).member().owner());
	}

	/**
	 * 接口字段截胡：改动前 {@code Sub.x} 指向 {@code A.x}，改动后接口 {@code I} 多了一个同名字段，
	 * 解析改落到 {@code I.x}。
	 *
	 * <p>接口字段必然是 {@code public static final}，所以这次改指向是<b>静态性翻转</b>：
	 * {@code GETFIELD} 落到静态字段上抛 {@code IncompatibleClassChangeError}——这是真错误，
	 * 必须阻断。与"同静态性的回退"（只记录不阻断）区分开。</p>
	 *
	 * <p>这个夹具只能用两轮编译造出来：v1 里 {@code I} 没有 {@code x}（否则 {@code s.x} 在 javac 阶段
	 * 就是"引用不明确"），v2 里给 {@code I} 加上。</p>
	 */
	@Test
	void interfaceFieldHijackIsIncompatibleAccess() throws Exception {
		Worlds w = worlds(
			Map.of("p/I", "package p; public interface I { }",
			       "p/A", "package p; public class A { public int x = 2; }",
			       "p/Sub", "package p; public class Sub extends A implements I { }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return s.x; } }"),
			Map.of("p/I", "package p; public interface I { int x = 1; }",
			       "p/A", "package p; public class A { }"));

		assertTrue(hasRef(w.post().get("p/Reader"), "field", "p/Sub", "x", "I"),
			"precondition: the fixture must contain Fieldref p/Sub.x:I");

		Result r = find(w, removedField("p/A", "x", "I", false));
		assertTrue(r.hasReaders(), "an ICCE blocks the redefine");
		assertTrue(r.hits().isEmpty(), "nothing dangles — resolution lands on I.x");
		assertFalse(r.incompatibleAccess().isEmpty(),
			"the target is static while the reference is a GETFIELD, which is IncompatibleClassChangeError");
		assertTrue(r.retargeted().isEmpty(),
			"a staticness flip is not a benign fallback, so it must not be filed under retargeted");
	}

	/**
	 * private 字段的 {@code Fieldref} owner 恒为声明类：读者必须强转成 {@code A} 才能编译，
	 * 强转后静态类型就是 {@code A}。这类成员按 owner 直判，不依赖解析。
	 */
	@Test
	void privateFieldIsMatchedByOwnerDirectly() throws Exception {
		Worlds w = worlds(
			Map.of("p/Outer", """
				package p;
				public class Outer {
				    public static class A { private int x = 1; }
				    public static class Sub extends A { }
				    public static class Reader { static int read(Sub s) { return ((A) s).x; } }
				}
				"""),
			// v2 必须重编整个 Outer 才能产出 Outer$A，但本轮真正变化的只有 A 自己，
			// 所以只把 Outer$A 的字节码换进来，Reader 保持 v1 —— 这才是"陈旧读者"的形态。
			Map.of("p/Outer", """
				package p;
				public class Outer {
				    public static class A { }
				    public static class Sub extends A { }
				    public static class Reader { static int read(Sub s) { return 0; } }
				}
				"""),
			Set.of("p/Outer$A"));

		Result r = find(w, removedField("p/Outer$A", "x", "I", true));
		assertTrue(r.hasReaders(), "the nestmate reader of a private field must be reported");
		assertEquals("p/Outer$Reader", r.hits().get(0).reader());
		assertEquals("p/Outer$A", r.hits().get(0).refOwner(),
			"a private field's Fieldref owner is always the declaring class");
	}

	// ------------------------------------------------------------------------------------------------
	// 方法
	// ------------------------------------------------------------------------------------------------

	/** 继承来的方法经子类静态类型调用时，引用 owner 是子类。 */
	@Test
	void inheritedMethodCallIsFound() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int foo() { return 1; } }",
			       "p/Sub", "package p; public class Sub extends A { }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return s.foo(); } }"),
			Map.of("p/A", "package p; public class A { }"));

		Result r = find(w, removedMethod("p/A", "foo", "()I"));
		assertTrue(r.hasReaders(), "an inherited method call must be reported");
		assertEquals("p/Sub", r.hits().get(0).refOwner());
	}

	/** 接口 default 方法走 {@code InterfaceMethodref}，与 {@code Methodref} 是两个常量池 tag。 */
	@Test
	void interfaceDefaultMethodIsFound() throws Exception {
		Worlds w = worlds(
			Map.of("p/I", "package p; public interface I { default int d() { return 1; } }",
			       "p/Reader", "package p; public class Reader { static int read(I i) { return i.d(); } }"),
			Map.of("p/I", "package p; public interface I { }"));

		assertTrue(hasRef(w.post().get("p/Reader"), "interfaceMethod", "p/I", "d", "()I"),
			"precondition: a default-method call is an InterfaceMethodref, not a Methodref");

		Result r = find(w, removedMethod("p/I", "d", "()I"));
		assertTrue(r.hasReaders(), "a default-method caller must be reported");
		assertEquals("interfaceMethod", r.hits().get(0).kind());
	}

	/** 方法引用（{@code A::foo}）以 MethodHandle 存在，不是 invoke 指令。 */
	@Test
	void methodReferenceIsFound() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public static void foo() { } }",
			       "p/Reader", "package p; public class Reader { static Runnable r = A::foo; }"),
			Map.of("p/A", "package p; public class A { }"));

		Result r = find(w, removedMethod("p/A", "foo", "()V"));
		assertTrue(r.hasReaders(), "a method reference (MethodHandle in the constant pool) must be reported");
	}

	/**
	 * 删掉<b>重写</b>方法时，上转型读者的常量池里根本没有 {@code A.m}。
	 *
	 * <p>这是方法与字段最本质的差异：字段引用向上解析，owner 必须是被删类的后代；实例方法还有一层
	 * 向下分派（JVMS 5.4.6），receiver 的实际类型决定执行谁，所以引用 owner 可以是 {@code A} 的
	 * <b>任何祖先</b>。只按 {@code declaring == A} 归属会把这些读者<b>全部漏掉</b>。</p>
	 *
	 * <p>实测（{@code P05Probe} M8a）：{@code viaI} / {@code viaSuper} / {@code viaA} 三条路径在删除后
	 * 都变成 {@code Super.m}，<b>一个异常都不抛</b>。</p>
	 *
	 * <p>正因为零异常，这属于<b>合法回退</b>：开发者删掉 Override 的本意就是回到父类实现，
	 * 调用方源码不需要改，全量重编后字节码也仍然能正确解析。所以只记录、不阻断。</p>
	 */
	@Test
	void overriddenMethodRemovalFallsBackToSuperclass() throws Exception {
		Worlds w = worlds(
			Map.of("p/I", "package p; public interface I { int m(); }",
			       "p/Super", "package p; public class Super { public int m() { return 1; } }",
			       "p/A", "package p; public class A extends Super implements I { public int m() { return 2; } }",
			       "p/Reader", """
			       	package p;
			       	public class Reader {
			       	    static int viaI(I i) { return i.m(); }
			       	    static int viaSuper(Super s) { return s.m(); }
			       	}
			       	"""),
			Map.of("p/A", "package p; public class A extends Super implements I { }"));

		// 前置：读者的常量池里写的是 I.m / Super.m，从不出现 A.m —— 漏判的根源就在这里。
		assertTrue(hasRef(w.post().get("p/Reader"), "interfaceMethod", "p/I", "m", "()I"),
			"precondition: the upcast reader names I.m");
		assertTrue(hasRef(w.post().get("p/Reader"), "method", "p/Super", "m", "()I"),
			"precondition: the other upcast reader names Super.m");
		assertFalse(hasRef(w.post().get("p/Reader"), "method", "p/A", "m", "()I"),
			"precondition: nothing in the reader mentions A.m, so owner matching alone cannot find it");

		Result r = find(w, removedMethod("p/A", "m", "()I"));
		assertFalse(r.hasReaders(),
			"falling back to Super.m is what deleting the override was meant to do — no exception,"
			+ " and the reader needs no source change");
		assertFalse(r.dispatchShifted().isEmpty(),
			"but the fallback must be surfaced: those references now run a different body");
		assertTrue(r.hits().isEmpty(), "nothing throws NoSuchMethodError here");
	}

	/**
	 * 删掉接口方法的<b>唯一实现</b>：具体类不再有方法体，调用抛 {@code AbstractMethodError}。
	 *
	 * <p>这与上一条的差别是"祖先有没有实现"——有实现就静默转移，没有实现就炸。
	 * 实测（{@code P05Probe} M8b）：{@code AbstractMethodError: Receiver class rem3.Only does not
	 * define or inherit an implementation of the resolved method 'abstract java.lang.String n()' of
	 * interface rem3.J}。</p>
	 */
	@Test
	void soleInterfaceImplementationRemovalLeavesAbstractMethod() throws Exception {
		Worlds w = worlds(
			Map.of("p/J", "package p; public interface J { int n(); }",
			       "p/Only", "package p; public class Only implements J { public int n() { return 3; } }",
			       "p/Reader", "package p; public class Reader { static int viaJ(J j) { return j.n(); } }"),
			// post 写成 abstract 只是为了让 javac 通过（具体类不覆盖抽象方法编译不过）；
			// 判据不看 abstract 标志——它只关心"有没有方法体"。
			Map.of("p/Only", "package p; public abstract class Only implements J { }"));

		Result r = find(w, removedMethod("p/Only", "n", "()I"));
		assertTrue(r.hasReaders(), "a concrete class losing its only implementation must be reported");
		assertFalse(r.abstractMissing().isEmpty(), "the failure mode here is AbstractMethodError");
		assertTrue(r.dispatchShifted().isEmpty(), "there is no implementation left to shift to");
	}

	/** 回退到接口的 default 实现：不抛错，且这正是删掉 Override 的本意——记录但不阻断。 */
	@Test
	void fallingBackToInterfaceDefaultIsNotBlocking() throws Exception {
		Worlds w = worlds(
			Map.of("p/I", "package p; public interface I { default int d() { return 1; } }",
			       "p/A", "package p; public class A implements I { public int d() { return 2; } }",
			       "p/Reader", "package p; public class Reader { static int r(A a) { return a.d(); } }"),
			Map.of("p/A", "package p; public class A implements I { }"));

		Result r = find(w, removedMethod("p/A", "d", "()I"));
		assertFalse(r.hasReaders(), "the interface default is a real implementation; nothing throws");
		assertFalse(r.dispatchShifted().isEmpty(), "the fallback must still be reported");
		assertTrue(r.abstractMissing().isEmpty(), "a default body means no AbstractMethodError");
	}

	/**
	 * 子接口把父接口的 {@code default} <b>重新声明为 abstract</b>：更具体的声明覆盖父接口的实现，
	 * 所以删掉类里的实现之后<b>没有</b>可执行体。
	 *
	 * <p>这条守的是方法选择里的 maximally-specific 规则。只按"先遇到 default 就返回"会把这里误判成
	 * "回退到 default 实现"（静默转移），而实际是 {@code AbstractMethodError}——两者都是拒绝，
	 * 但报告给用户的原因完全不同。</p>
	 */
	@Test
	void subInterfaceRedeclaringDefaultAsAbstractLeavesNoBody() throws Exception {
		Worlds w = worlds(
			Map.of("p/Base", "package p; public interface Base { default int d() { return 1; } }",
			       "p/Sub", "package p; public interface Sub extends Base { int d(); }",
			       "p/A", "package p; public class A implements Sub { public int d() { return 2; } }",
			       "p/Reader", "package p; public class Reader { static int r(A a) { return a.d(); } }"),
			// abstract 只是为了让 javac 通过；判据只看"有没有方法体"。
			Map.of("p/A", "package p; public abstract class A implements Sub { }"));

		Result r = find(w, removedMethod("p/A", "d", "()I"));
		assertTrue(r.hasReaders(), "the more specific abstract redeclaration wins over the inherited default");
		assertFalse(r.abstractMissing().isEmpty(),
			"Sub re-declares d as abstract, so Base's default is shadowed and nothing is executable");
		assertTrue(r.dispatchShifted().isEmpty(), "there is no default left to fall back to");
	}

	/**
	 * 编译期常量（{@code ConstantValue}，JLS §13.4.9）的读者常量池里<b>没有 Fieldref</b>。
	 *
	 * <p>这是"读者扫描对它完全失明"的直接证据，也是"必须强拒绝"的依据：空的读者集合在这里
	 * 不是安全证明，而是<b>没有信号</b>。对照组证明边界划在哪——运行期初始化的
	 * {@code static final} 与普通 {@code static} 都有 Fieldref。</p>
	 */
	@Test
	void compileTimeConstantsLeaveNoFieldrefInReaders() throws Exception {
		Worlds w = worlds(
			Map.of("p/Holder", """
				package p;
				public class Holder {
				    public static final int CONST = 42;
				    public static final String TEXT = "hi";
				    public static final Object REF = new Object();
				    public static int plain = 5;
				}
				""",
			       "p/Reader", """
				package p;
				public class Reader {
				    static int c() { return Holder.CONST; }
				    static String t() { return Holder.TEXT; }
				    static Object r() { return Holder.REF; }
				    static int p() { return Holder.plain; }
				}
				"""),
			Map.of());

		byte[] reader = w.post().get("p/Reader");
		assertFalse(hasRef(reader, "field", "p/Holder", "CONST", "I"),
			"a compile-time int constant is inlined as bipush — no Fieldref exists to scan for");
		assertFalse(hasRef(reader, "field", "p/Holder", "TEXT", "Ljava/lang/String;"),
			"a compile-time String constant is inlined as ldc — no Fieldref exists either");
		assertTrue(hasRef(reader, "field", "p/Holder", "REF", "Ljava/lang/Object;"),
			"control: a static final *reference* is not a compile-time constant, it still uses getstatic");
		assertTrue(hasRef(reader, "field", "p/Holder", "plain", "I"),
			"control: a plain static field is not inlined either");
	}

	/**
	 * 反方向：删的是<b>实例</b>字段，父类同名的是<b>静态</b>字段。{@code GETFIELD} 解析到静态字段上
	 * 同样是 {@code IncompatibleClassChangeError}——HotSpot 的 `resolve_field` 里那句
	 * {@code is_static != fd.is_static()} 是<b>双向</b>检查的。
	 */
	@Test
	void instanceFieldLandingOnStaticFieldReportsIncompatibleChange() throws Exception {
		Worlds w = worlds(
			Map.of("p/Super", "package p; public class Super { public static int x = 1; }",
			       "p/A", "package p; public class A extends Super { public int x = 2; }",
			       "p/Reader", "package p; public class Reader { static int read(A a) { return a.x; } }"),
			Map.of("p/A", "package p; public class A extends Super { }"));

		Result r = find(w, removedField("p/A", "x", "I", false));
		assertTrue(r.hasReaders(), "the reference now lands on a static field, which GETFIELD cannot read");
		assertFalse(r.incompatibleAccess().isEmpty(), "staticness flip: ICCE");
		assertTrue(r.incompatibleAccess().get(0).detail().contains("IncompatibleClassChangeError"),
			"the report must name the actual error: " + r.incompatibleAccess().get(0).detail());
	}

	/**
	 * 跨分支继承实现：{@code A} 自己<b>不</b>实现 {@code I}，是子类 {@code Sub extends A implements I}
	 * 靠<b>继承</b> {@code A.m} 来满足 {@code I.m}。此时 {@code A} 与 {@code I} 没有直接子类型关系，
	 * 但删掉 {@code A.m} 会让 {@code Sub} 失去实现。
	 *
	 * <p>只判 {@code isSubtype(A, refOwner)} 的写法会漏掉这一类——它必须再看
	 * "有没有 {@code A} 的后代同时是 {@code refOwner} 的子类型"。</p>
	 */
	@Test
	void crossBranchInheritanceStillReachesTheDeletedImplementation() throws Exception {
		Worlds w = worlds(
			Map.of("p/I", "package p; public interface I { int m(); }",
			       "p/A", "package p; public class A { public int m() { return 1; } }",
			       "p/Sub", "package p; public class Sub extends A implements I { }",
			       "p/Reader", "package p; public class Reader { static int r(I i) { return i.m(); } }"),
			Map.of("p/A", "package p; public class A { }"));

		assertTrue(hasRef(w.post().get("p/Reader"), "interfaceMethod", "p/I", "m", "()I"),
			"precondition: the reader names I.m, and A never mentions I");
		assertFalse(RemovedMemberReaders.isSubtype(RemovedMemberReaders.universe(w.pre()),
				"p/A", "p/I"),
			"precondition: A is not a subtype of I — the direct check cannot see this path");

		Result r = find(w, removedMethod("p/A", "m", "()I"));
		assertTrue(r.hasReaders(),
			"Sub implements I.m by inheriting A.m, so deleting A.m leaves Sub without a body");
		assertFalse(r.abstractMissing().isEmpty(), "the failure mode is AbstractMethodError");
	}

	/**
	 * 删静态字段、父类有同名字段：引用不悬空，落到父类的<b>同静态性</b>字段上——这是合法回退
	 * （删掉子类的遮蔽字段，本意就是回到父类的那个），旧字节码本来就能正确解析，运行期零异常。
	 * 实测（`P05Probe` M9b）确认 `GETSTATIC A.s` 会向上解析到 `Super.s`。
	 */
	@Test
	void staticFieldAbsorbedBySuperclassIsABenignFallback() throws Exception {
		Worlds w = worlds(
			Map.of("p/Super", "package p; public class Super { public static int s = 22; }",
			       "p/A", "package p; public class A extends Super { public static int s = 11; }",
			       "p/Reader", "package p; public class Reader { static int read() { return A.s; } }"),
			Map.of("p/A", "package p; public class A extends Super { }"));

		Result r = find(w, removedStaticField("p/A", "s", "I"));
		assertFalse(r.hasReaders(), "same-kind fallback throws nothing and needs no source change");
		assertTrue(r.hits().isEmpty(), "resolution walks up and finds Super.s, so nothing dangles");
		assertTrue(r.incompatibleAccess().isEmpty(), "both sides are static, so no ICCE");
		assertFalse(r.retargeted().isEmpty(), "the fallback must still be reported");
	}

	/**
	 * 删静态字段、父类只有<b>实例</b>字段：`GETSTATIC` 落到实例字段上是
	 * {@code IncompatibleClassChangeError}（实测 `P05Probe` M9d）。
	 */
	@Test
	void staticFieldLandingOnInstanceFieldReportsIncompatibleChange() throws Exception {
		Worlds w = worlds(
			Map.of("p/Super", "package p; public class Super { public int s = 22; }",
			       "p/A", "package p; public class A extends Super { public static int s = 11; }",
			       "p/Reader", "package p; public class Reader { static int read() { return A.s; } }"),
			Map.of("p/A", "package p; public class A extends Super { }"));

		Result r = find(w, removedStaticField("p/A", "s", "I"));
		assertTrue(r.hasReaders(), "a GETSTATIC landing on an instance field blows up at run time");
		assertFalse(r.incompatibleAccess().isEmpty(), "staticness flip: ICCE");
		assertTrue(r.incompatibleAccess().get(0).detail().contains("IncompatibleClassChangeError"),
			"the report must name the actual error: " + r.incompatibleAccess().get(0).detail());
	}

	/**
	 * 对照组：静态字段删除但没人读 → 干净。没有这条，"报了"可能只是"只要删静态字段就报"。
	 */
	@Test
	void staticFieldRemovalWithNoReaderIsClean() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public static int s = 11; }",
			       "p/Reader", "package p; public class Reader { static int read() { return 1; } }"),
			Map.of("p/A", "package p; public class A { }"));

		Result r = find(w, removedStaticField("p/A", "s", "I"));
		assertFalse(r.hasReaders(), "nobody references A.s, so removing it is safe");
	}


	@Test
	void nonOverriddenMethodRemovalIsStillALinkageError() throws Exception {
		Worlds w = worlds(
			Map.of("p/P", "package p; public class P { public int q() { return 1; } }",
			       "p/Reader", "package p; public class Reader { static int call(P p) { return p.q(); } }"),
			Map.of("p/P", "package p; public class P { }"));

		Result r = find(w, removedMethod("p/P", "q", "()I"));
		assertTrue(r.hasReaders(), "no ancestor declares q(), so the reference dangles");
		assertFalse(r.hits().isEmpty(), "the failure mode is NoSuchMethodError, i.e. a hit");
		assertTrue(r.dispatchShifted().isEmpty(), "there is nothing to dispatch to");
	}

	/**
	 * 去触发对照：同一条继承链上删掉一个<b>没有任何引用</b>的方法，不能报出读者。
	 *
	 * <p>没有这条，上面几个用例的"报出来了"可能只是"只要删方法就报"。</p>
	 */
	@Test
	void unrelatedMethodRemovalOnTheSameHierarchyIsNotReported() throws Exception {
		Worlds w = worlds(
			Map.of("p/I", "package p; public interface I { int m(); }",
			       "p/Super", "package p; public class Super { public int m() { return 1; } }",
			       "p/A", "package p; public class A extends Super implements I {"
			              + " public int m() { return 2; } public void unused() { } }",
			       "p/Reader", "package p; public class Reader { static int viaI(I i) { return i.m(); } }"),
			Map.of("p/A", "package p; public class A extends Super implements I { public int m() { return 2; } }"));

		Result r = find(w, removedMethod("p/A", "unused", "()V"));
		assertFalse(r.hasReaders(), "nobody calls A.unused(), so removing it is safe");
	}

	// ------------------------------------------------------------------------------------------------
	// 判不了 ≠ 没有
	// ------------------------------------------------------------------------------------------------

	@Test
	void missingHierarchyIsReportedAsInconclusive() throws Exception {
		Worlds w = worlds(
			Map.of("p/A", "package p; public class A { public int x = 1; }",
			       "p/Sub", "package p; public class Sub extends A { }",
			       "p/Reader", "package p; public class Reader { static int read(Sub s) { return s.x; } }"),
			Map.of("p/A", "package p; public class A { }"));

		// 改动后的世界故意缺 Sub：悬空判定在第一步就断掉
		Map<String, TypeInfo> pruned = new LinkedHashMap<>(RemovedMemberReaders.universe(w.post()));
		pruned.remove("p/Sub");

		Result r = RemovedMemberReaders.find(RemovedMemberReaders.universe(w.pre()), pruned, w.post(),
			List.of(removedField("p/A", "x", "I", false)));

		assertFalse(r.hasReaders(), "an unresolvable reference must not be silently reported as a reader");
		assertFalse(r.inconclusive().isEmpty(),
			"but it must be surfaced as inconclusive — treating 'cannot tell' as 'no reader' is exactly"
			+ " the failure mode this bucket exists to prevent");
	}

	// ------------------------------------------------------------------------------------------------
	// fixtures
	// ------------------------------------------------------------------------------------------------

	/** 改动前后的两套世界。{@code post} 里未重编的类沿用 {@code pre} 的字节码。 */
	private record Worlds(Map<String, byte[]> pre, Map<String, byte[]> post) { }

	private static RemovedMember removedField(String owner, String name, String desc, boolean isPrivate) {
		return new RemovedMember(owner, name, desc, true, isPrivate);
	}

	private static RemovedMember removedMethod(String owner, String name, String desc) {
		return new RemovedMember(owner, name, desc, false, false);
	}

	private static RemovedMember removedStaticField(String owner, String name, String desc) {
		return new RemovedMember(owner, name, desc, true, false, true, false);
	}

	private static Result find(Worlds w, RemovedMember member) {
		return RemovedMemberReaders.find(RemovedMemberReaders.universe(w.pre()),
			RemovedMemberReaders.universe(w.post()), w.post(), List.of(member));
	}

	private static boolean hasRef(byte[] classBytes, String kind, String owner, String name, String desc) {
		return RemovedMemberReaders.refs(classBytes).stream().anyMatch(
			r -> r.kind().equals(kind) && r.owner().equals(owner) && r.name().equals(name)
			     && r.desc().equals(desc));
	}

	/**
	 * 编译两轮：v1 是改动前的完整世界，v2 只包含真正重编的类（以 v1 为 classpath）。
	 * v2 里没出现的类沿用 v1 的字节码——这就是"陈旧读者"的形态。
	 */
	private static Worlds worlds(Map<String, String> v1, Map<String, String> v2) throws Exception {
		return worlds(v1, v2, v2.keySet());
	}

	/**
	 * @param replaced 只把这些类的字节码换成 v2 的；其余全部沿用 v1。
	 *                 用于"本轮只重编了其中几个类"的形态——注意它和 v2 的源码 key 可以不同
	 *                 （重编 {@code Outer} 会顺带产出 {@code Outer$A}，但本轮真正变的只有后者）。
	 */
	private static Worlds worlds(Map<String, String> v1, Map<String, String> v2,
	                             Set<String> replaced) throws Exception {
		Path d1 = Files.createTempDirectory("readers_v1");
		compileInto(d1, v1, null);
		Map<String, byte[]> pre = readClasses(d1);

		Map<String, byte[]> post = new LinkedHashMap<>(pre);
		if (!v2.isEmpty()) {
			Path d2 = Files.createTempDirectory("readers_v2");
			compileInto(d2, v2, d1.toString());
			Map<String, byte[]> v2Classes = readClasses(d2);
			for (String name : replaced) {
				byte[] bytes = v2Classes.get(name);
				if (bytes == null) throw new IllegalStateException("v2 did not produce " + name);
				post.put(name, bytes);
			}
		}
		return new Worlds(pre, post);
	}

	private static void compileInto(Path dir, Map<String, String> sources, String extraClasspath) throws Exception {
		List<File> files = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			Path src = dir.resolve(e.getKey() + ".java");
			Files.createDirectories(src.getParent());
			Files.writeString(src, e.getValue(), StandardCharsets.UTF_8);
			files.add(src.toFile());
		}

		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		if (javac == null) throw new IllegalStateException("a JDK (not a JRE) is required to compile fixtures");
		List<String> options = new ArrayList<>(List.of("-d", dir.toString(), "-g", "-nowarn"));
		if (extraClasspath != null) {
			options.add("-cp");
			options.add(extraClasspath);
		}
		try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
			Boolean ok = javac.getTask(null, fm, null, options, null, fm.getJavaFileObjectsFromFiles(files)).call();
			if (!Boolean.TRUE.equals(ok)) {
				throw new IllegalStateException("fixture compilation failed: " + sources.keySet());
			}
		}
	}

	private static Map<String, byte[]> readClasses(Path dir) throws Exception {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (var walk = Files.walk(dir)) {
			for (Path p : walk.filter(Files::isRegularFile).toList()) {
				String rel = dir.relativize(p).toString().replace('\\', '/');
				if (!rel.endsWith(".class")) continue;
				out.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
			}
		}
		return out;
	}
}
