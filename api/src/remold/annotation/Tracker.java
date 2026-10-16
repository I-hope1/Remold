package remold.annotation;

import java.lang.annotation.*;

/**
 * 存活实例自动追踪注解。
 *
 * <p>标记在类上时，{@link remold.AnnotationTransformer} 会在类的构造器（{@code <init>}）出口处进行插桩，
 * 将新建实例注册到 {@link remold.InstanceTracker#register(Object)} 中（采用弱引用集合维护）。</p>
 *
 * <p>用于在 Native JVMTI 堆遍历（{@code LibTool.getInstances}）不可用时的保底方案，
 * 使得 {@link OnReload} 实例方法及存量状态修复能够正向检索并访问存活实例。</p>
 *
 * @see remold.InstanceTracker
 * @see remold.annotation.OnReload
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tracker {
}
