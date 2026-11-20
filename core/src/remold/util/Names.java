package remold.util;

/**
 * 类名形态的唯一转换入口。
 *
 * <p><b>为什么必须收敛</b>：同一份代码里存在三种形态——JVM 的斜杠内部名（{@code a/b/C}）、
 * ASM/工具侧的点分名（{@code a.b.C}）、以及 Mixin 的 {@code getTargetClasses()} 返回值（斜杠）。
 * 曾经两次因为<b>手写转换方向写反</b>造成静默全量 miss：一次是出生点补丁的待决键（登记用点分、
 * 查找用斜杠），一次是动态重定义的提交键（{@code dot.replace('/', '.')} 把方向写反）。
 * 两次都是"编译通过、测试用同形态造数据时全绿、真机全 miss"。</p>
 *
 * <p>因此转换只在这里做，业务代码禁止直接书写 {@code replace('.', '/')} 之类的转换；
 * 守卫见 {@code tests/src/remold/util/NameConversionGuardTest.java}。</p>
 */
public final class Names {

	private Names() { }

	/** 点分名 → 斜杠内部名；已是内部名时原样返回。 */
	public static String internal(String name) {
		if (name == null) return null;
		return name.indexOf('.') < 0 ? name : name.replace('.', '/');
	}

	/** 斜杠内部名 → 点分名；已是点分名时原样返回。 */
	public static String external(String name) {
		if (name == null) return null;
		return name.indexOf('/') < 0 ? name : name.replace('/', '.');
	}
}
