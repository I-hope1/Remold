package remold.annotation;

import java.lang.annotation.*;

/**
 * @deprecated Use {@link RemoldReinit} instead.
 */
@Deprecated
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HotswapReinit {
    enum Mode {
        CONDITIONAL,
        OVERWRITE
    }
    Mode mode() default Mode.OVERWRITE;
}
