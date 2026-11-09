package remold.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

/**
 * Mixin 内部类（匿名类 / 局部类 / 成员内部类）的纯函数支撑。
 *
 * <p>Mixin 模板里的 {@code Outer$1} 不是独立的 Mixin：它只是被 Mixin 当作素材重映射成
 * 目标类的合成内部类（运行期名字由 {@code InnerClassGenerator} 生成，见
 * {@code docs/topology/} 与上游 {@code InnerClassGenerator.getUniqueReference}）。
 * 因此「{@code Outer$N} 自己变了」既不能按普通类 redefine，也不能当成一个新 Mixin 去
 * reload。本类只做名字与归属判定，热更编排在 {@link MixinHotSwap}。</p>
 */
final class MixinInnerClassSupport {

	private MixinInnerClassSupport() {
	}

	/** 点分类名 → 内部名。 */
	static String internalName(String dotClassName) {
		return dotClassName == null ? null : dotClassName.replace('.', '/');
	}

	/** 内部名 → 点分类名。 */
	static String dotName(String internalName) {
		return internalName == null ? null : internalName.replace('/', '.');
	}

	/**
	 * 该 Mixin 自己的内部类内部名列表，按 {@code InnerClasses} 声明顺序去重。
	 *
	 * <p>归属判据与上游 {@code MixinInfo.State.readInnerClasses()} 同构：条目的
	 * {@code outerName} 指向本 Mixin，或条目自身名字以 {@code Mixin$} 开头。</p>
	 *
	 * <p>上游把「static + synthetic」的内部类归入 passthrough 集合（不重映射、不生成唯一副本），
	 * 这些类不会成为运行期合成内部类，不在本列表里。</p>
	 */
	static List<String> ownInnerClassNames(String mixinInternalName, ClassNode node) {
		if (mixinInternalName == null || node == null || node.innerClasses == null) {
			return Collections.emptyList();
		}
		String prefix = mixinInternalName + "$";
		Set<String> out = new LinkedHashSet<>();
		for (InnerClassNode inner : node.innerClasses) {
			if (inner == null || inner.name == null || inner.name.equals(mixinInternalName)) {
				continue;
			}
			boolean declaredByOuter = inner.outerName != null && inner.outerName.equals(mixinInternalName);
			boolean nameScoped = inner.name.startsWith(prefix);
			if (!declaredByOuter && !nameScoped) {
				continue;
			}
			if (isSyntheticPassthrough(inner)) {
				continue;
			}
			out.add(inner.name);
		}
		return new ArrayList<>(out);
	}

	/**
	 * 是否为 Mixin 的 passthrough 内部类：{@code static} 且 {@code synthetic}。
	 *
	 * <p>上游据此把它们放进 {@code syntheticInnerClasses}（例如 lambda 的隐藏类载体形态），
	 * 它们不参与唯一名重映射。</p>
	 */
	static boolean isSyntheticPassthrough(InnerClassNode inner) {
		if (inner == null) {
			return false;
		}
		boolean isStatic = (inner.access & Opcodes.ACC_STATIC) != 0;
		boolean isSynthetic = (inner.access & Opcodes.ACC_SYNTHETIC) != 0;
		return isStatic && isSynthetic;
	}

	/** 名字形如 {@code Outer$...}（含 {@code $}）即视为嵌套类。 */
	static boolean isNestedClassName(String internalName) {
		if (internalName == null) {
			return false;
		}
		int idx = internalName.lastIndexOf('$');
		return idx > 0 && idx < internalName.length() - 1;
	}

	/** {@code Outer$N} → {@code Outer}；不含 {@code $} 时返回 {@code null}。 */
	static String outerInternalName(String innerInternalName) {
		if (!isNestedClassName(innerInternalName)) {
			return null;
		}
		return innerInternalName.substring(0, innerInternalName.lastIndexOf('$'));
	}
}
