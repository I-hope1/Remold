package remold;

/**
 * 字节码重织入桥接接口（用于 Mixin、AspectJ、自定义字节码增强器在热重载时重新织入）。
 *
 * <p>在类对齐完成后、进入布局安全门 (LayoutGate) 与重定义 (redefineClasses) 之前介入，
 * 将源码级编译产物转换为带有增强钩子（如 Mixin @Inject/@Unique）的运行时字节码。</p>
 */
@FunctionalInterface
public interface BytecodeTransformerBridge {

	/**
	 * 对新编译/对齐后的字节码执行重新织入。
	 *
	 * @param loader       目标类的 ClassLoader
	 * @param dotClassName 点分隔类名（如 "com.example.Foo"）
	 * @param targetClass  已加载的目标 Class 对象（如果是全新类则为 null）
	 * @param bytecode     进入此桥接器前的字节码
	 * @return 重新织入后的字节码；若未作修改可直接返回传入的 bytecode
	 * @throws Abort 本类<b>本轮必须整体放弃重定义</b>（事务一致性）。抛出后 Remold 不会调用
	 *               {@code redefineClasses}，也不会把任何"半成品"字节（既非新织入产物，也非
	 *               未织入的原始字节）送回 JVM：宿主与它关联的类一起停留在已加载旧版。
	 */
	byte[] transform(ClassLoader loader, String dotClassName, Class<?> targetClass, byte[] bytecode);

	/**
	 * 桥接器要求「放弃本类的本次重定义」的信号。
	 *
	 * <p><b>为什么需要它（而不是返回原字节）</b>：桥接产物一旦被织入了新的调用点（例如目标类开始
	 * 引用合成内部类的新构造器），而与之配套的类这一轮没能落地，两半字节码同时存在于 JVM 里就会
	 * 在下一个 tick 抛 {@code NoSuchMethodError}。反过来，把<b>未织入</b>的原始字节送进
	 * {@code redefineClasses} 会把 Mixin 钩子整片抹掉（washout）。两种"降级返回"都是错的，
	 * 唯一正确的动作是<b>不重定义</b>。返回 {@code null}/空数组表达不了这个语义——
	 * {@code applyTransformerBridges} 会把它读成"本桥没改动，沿用上一个字节"。</p>
	 *
	 * <p>调用方约定：{@code applyTransformerBridges} 必须原样向上抛出本异常（不得吞掉），
	 * 单类入口（{@code redefineDynamic}）捕获后返回失败、批量入口把该类移出本批。</p>
	 */
	final class Abort extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public Abort(String reason) {
			super(reason);
		}
	}
}
