package remold;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

import remold.util.Names;

/**
 * 匿名类的**身份解析**：宿主、后缀路径、序号，一律以字节码为准，名字只作回退。
 *
 * <p>为什么不能靠名字：JVM 规范里匿名类名为 {@code Host$1}、{@code Host$1$2}，名字剥离尾部数字即可得到宿主。
 * kotlinc 不用这个形态——它把属性名、方法名、局部变量名拼进类名，形成
 * {@code Host$propAnonA$1}、{@code Host$makeUnnamedAnons$1..$3}、{@code Host$makeLocalNamedAnons$first$1}、
 * {@code Host$outerAnon$1$innerObj$1}。对这些名字做"剥离尾部数字"会得到一个**从不存在的类名**
 * （{@code Host$propAnonA}），把它当宿主会连带引发三类故障：宿主解析失败导致整族退出、
 * 宿主/匿名一致性校验误判不一致而回滚、以及按假宿主分组后编号冲突。</p>
 *
 * <p>权威证据是 {@code EnclosingMethod} 属性：kotlinc 与 javac 都给匿名类写这个属性，属性里点名的
 * 就是**真实的外层类**（实测 kotlinc 2.3.0：六种形态中五种指向 TLC，嵌套匿名对象指向它的外层匿名类；
 * {@code InnerClasses.outer_name_index} 在匿名类上是 0，取不到宿主，因此不能作为主判据）。</p>
 *
 * <p>本类只做解析，不做取舍：宿主取值、族成员判据、配对身份都由调用方决定，但**必须共用这一份**，
 * 否则会出现两套"谁属于这一族"的答案（那正是本缺陷的成因）。</p>
 */
public final class AnonHostResolver {

	private AnonHostResolver() {}

	/**
	 * 一次解析的完整结果。
	 *
	 * @param className  被解析的类（内部名）
	 * @param host       宿主内部名；无法判定时等于 {@code className}
	 * @param anonymous  是否具备匿名类标记（{@code EnclosingMethod}，或自身 {@code InnerClasses} 条目
	 *                   {@code outerName == null && innerName == null}）
	 * @param suffixPath 宿主之后、序号之前的部分（{@code Host$propAnonA$1} → {@code propAnonA}）；
	 *                   纯 Java 形态 {@code Host$1} 为空串
	 * @param index      尾部数字链代表的序号；没有数字尾巴时为 {@code -1}
	 * @param byAttribute 宿主是否取自字节码属性（{@code false} 表示走了名字回退）
	 */
	public record Identity(String className, String host, boolean anonymous,
	                       String suffixPath, int index, boolean byAttribute) {

		/** 配对身份：同一宿主下"后缀路径相同"的类视为同一个匿名类位置。 */
		public String pairKey() {
			return host + '#' + suffixPath;
		}
	}

	/**
	 * 解析一个类的匿名身份。
	 *
	 * @param className 内部名（{@code a/b/C}，点分名也行——内部会归一化）
	 * @param bytes     该类自身的字节码；为 {@code null} 或解析失败时退回名字判据
	 */
	public static Identity resolve(String className, byte[] bytes) {
		String cls = Names.internal(className);
		ClassNode cn = parse(bytes);
		boolean anonymous = cn != null
			? hasAnonymousMarker(cn)
			: isAnonymousName(cls, textHost(cls));
		String host;
		boolean byAttribute = false;
		if (cn != null && cn.outerClass != null && !cn.outerClass.isEmpty()) {
			host = cn.outerClass;
			byAttribute = true;
		} else {
			host = textHost(cls);
		}
		if (host == null || host.isEmpty() || host.equals(cls)) {
			return new Identity(cls, cls, anonymous, "", -1, byAttribute);
		}
		String suffix = cls.startsWith(host + "$") ? cls.substring(host.length() + 1) : cls;
		int index = trailingIndex(cls);
		String path = index >= 0 ? stripTrailingIndex(suffix) : suffix;
		return new Identity(cls, host, anonymous, path, index, byAttribute);
	}

	/** 便捷入口：宿主内部名；失败退化为入参自身。 */
	public static String hostOf(String className, byte[] bytes) {
		return resolve(className, bytes).host();
	}

	/**
	 * 族成员判据：{@code cls} 是否属于 {@code host} 名下的匿名类族。
	 *
	 * <p>同时接受两种形态：字节码带匿名标记（可跨命名风格），或名字上属于
	 * {@code host$...$<数字>}（老版本/无字节码时唯一的判据）。{@code Host$Inner$Deep} 这类具名内部类
	 * 没有数字尾巴、也没有匿名标记，不算族成员。</p>
	 */
	public static boolean isFamilyMember(String host, String className, byte[] bytes) {
		if (host == null || className == null) return false;
		String h = Names.internal(host);
		String c = Names.internal(className);
		if (h.equals(c) || !c.startsWith(h + "$")) return false;
		ClassNode cn = parse(bytes);
		if (cn != null && hasAnonymousMarker(cn)) {
			// 有匿名标记时以属性为准：宿主必须与属性一致（嵌套内层的宿主是外层匿名类）。
			if (cn.outerClass != null && !cn.outerClass.isEmpty()) {
				return cn.outerClass.equals(h);
			}
			return isAnonymousName(c, h);
		}
		return isAnonymousName(c, h);
	}

	/**
	 * 名字判据：{@code host$...$<数字>}，末尾是数字段即算匿名类名（{@code Host$1}、{@code Host$prop$1}、
	 * {@code Host$1$2} 都成立；{@code Host$Helper}、{@code Host$Helper$Deep} 不成立）。
	 *
	 * <p>与 {@link #isDigitChainAnon} 的差别：后者要求数字段前还有内容（它服务"后缀族"通道，纯 Java
	 * 形态由 {@link AnonClassAligner#isAnonymousClassName} 覆盖）。本判据是**匿名类名**的统一口径。</p>
	 */
	public static boolean isAnonymousName(String className, String host) {
		if (className == null || host == null) return false;
		String c = Names.internal(className);
		String h = Names.internal(host);
		if (!c.startsWith(h + "$")) return false;
		String suffix = c.substring(h.length() + 1);
		if (suffix.isEmpty() || suffix.endsWith("$") || suffix.contains("$$")) return false;
		int i = suffix.length();
		while (i > 0 && Character.isDigit(suffix.charAt(i - 1))) i--;
		return i < suffix.length();
	}

	/**
	 * 名字回退：与历史判据同义（尾部是数字链），仅供拿不到字节码时使用。
	 *
	 * <p>与 {@link AnonClassAligner#batchAnonHost} 的差别是**不把中间段当宿主**：它只用于"该名字是否是
	 * 某个宿主的族成员"，宿主由调用方给出。</p>
	 */
	public static boolean isDigitChainAnon(String className, String host) {
		String c = Names.internal(className);
		String h = Names.internal(host);
		if (!c.startsWith(h + "$")) return false;
		String suffix = c.substring(h.length() + 1);
		int tail = suffix.length();
		while (tail > 0 && Character.isDigit(suffix.charAt(tail - 1))) tail--;
		return tail > 0 && tail < suffix.length();
	}

	/**
	 * 纯名字宿主回退：从右往左剥掉 {@code $<数字>} 段。
	 *
	 * <p>Java 形态下这就是宿主；kotlinc 形态下会得到不存在的名字，调用方必须用
	 * {@link #hostOf} 的字节码结果覆盖它，并且对"解析不出来"保持 fail-closed。</p>
	 */
	public static String textHost(String className) {
		return AnonClassAligner.batchAnonHost(className);
	}

	//region internals

	private static ClassNode parse(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return null;
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(bytes).accept(cn,
				ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return cn;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 匿名类标记：{@code EnclosingMethod}（{@code outerClass != null}），或自身 {@code InnerClasses}
	 * 条目里 {@code outerName == null && innerName == null}。两者都缺时不是匿名类——
	 * 具名内部类、lambda 合成方法类都不该被当成匿名类对待。
	 */
	private static boolean hasAnonymousMarker(ClassNode cn) {
		if (cn.outerClass != null && !cn.outerClass.isEmpty()) return true;
		if (cn.innerClasses == null) return false;
		for (InnerClassNode icn : cn.innerClasses) {
			if (!cn.name.equals(icn.name)) continue;
			return icn.outerName == null && icn.innerName == null
				&& (icn.access & Opcodes.ACC_SYNTHETIC) != 0;
		}
		return false;
	}

	private static int trailingIndex(String cls) {
		int cut = cls.length();
		int i = cut;
		while (i > 0 && Character.isDigit(cls.charAt(i - 1))) i--;
		if (i == cut) return -1;
		String digits = cls.substring(i, cut);
		if (i > 0 && cls.charAt(i - 1) != '$') return -1;
		try {
			return Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String stripTrailingIndex(String suffix) {
		int i = suffix.length();
		while (i > 0 && Character.isDigit(suffix.charAt(i - 1))) i--;
		if (i == suffix.length()) return suffix;
		if (i > 0 && suffix.charAt(i - 1) == '$') i--;
		return suffix.substring(0, i);
	}

	//endregion
}
