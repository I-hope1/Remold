package remold.offset.cases;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

final class Uns {
    static final Unsafe U;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (Exception e) { throw new Error(e); }
    }
}

class Foo { int x; int y; volatile int cnt; volatile String ref; }

/** helper：返回偏移量 / Field，自身不落点 */
class Util {
    static long offsetOf(Class<?> c, String n) throws Exception { return Uns.U.objectFieldOffset(c.getDeclaredField(n)); }
    static long offsetByName(String cls, String n) throws Exception {
        return Uns.U.objectFieldOffset(Class.forName(cls).getDeclaredField(n));
    }
    static Field field0() throws Exception { return Foo.class.getDeclaredField("cnt"); }
    static long viaField(Field f) { return Uns.U.objectFieldOffset(f); }
}

// 1. <clinit> 直接嵌套调用
class Direct {
    static final long OFF;
    static { try { OFF = Uns.U.objectFieldOffset(Foo.class.getDeclaredField("x")); } catch (Exception e) { throw new Error(e); } }
}

// 2. 局部变量中转（原文四行固定序列会漏）
class ViaLocal {
    static final long OFF;
    static { try { Field f = Foo.class.getDeclaredField("y"); OFF = Uns.U.objectFieldOffset(f); } catch (Exception e) { throw new Error(e); } }
}

// 3. 跨类 helper：本类不引用 Unsafe，靠 producer 轮次才会被扫到
class ViaHelper {
    static final long OFF;
    static { try { OFF = Util.offsetOf(Foo.class, "y"); } catch (Exception e) { throw new Error(e); } }
}

// 3b. helper 的参数是 String 类名
class ViaHelperName {
    static final long OFF;
    static { try { OFF = Util.offsetByName("remold.offset.cases.Foo", "x"); } catch (Exception e) { throw new Error(e); } }
}

// 3c. helper 接收 Field 参数
class ViaFieldParam {
    static final long OFF;
    static { try { OFF = Util.viaField(Foo.class.getDeclaredField("ref")); } catch (Exception e) { throw new Error(e); } }
}

// 3d. 零参 helper 返回 Field
class ViaField0 {
    static final long OFF;
    static { try { OFF = Uns.U.objectFieldOffset(Util.field0()); } catch (Exception e) { throw new Error(e); } }
}

// 4. doPrivileged + 匿名类（含 bridge 方法）
class DoPriv {
    static final long OFF;
    static {
        try {
            Field f = AccessController.doPrivileged(new PrivilegedExceptionAction<Field>() {
                public Field run() throws Exception { return Foo.class.getDeclaredField("ref"); }
            });
            OFF = Uns.U.objectFieldOffset(f);
        } catch (Exception e) { throw new Error(e); }
    }
}

// 4b. doPrivileged + lambda
class DoPrivLambda {
    static final long OFF;
    static {
        try {
            Field f = AccessController.doPrivileged((PrivilegedExceptionAction<Field>) () -> Foo.class.getDeclaredField("cnt"));
            OFF = Uns.U.objectFieldOffset(f);
        } catch (Exception e) { throw new Error(e); }
    }
}

// 5. 字段名拼接 → 通配
class Concat {
    final long o;
    Concat(int i) throws Exception { o = Uns.U.objectFieldOffset(Foo.class.getDeclaredField("f" + i)); }
}

// 6. 遍历 getDeclaredFields 存数组 → ESCAPED + 通配
class Loop {
    static final long[] OFFS = new long[8];
    static { int i = 0; for (Field f : Foo.class.getDeclaredFields()) OFFS[i++] = Uns.U.objectFieldOffset(f); }
}

// 7. 当场消费：不应产生绑定
class Immediate {
    static int get(Foo o) throws Exception { return Uns.U.getInt(o, Uns.U.objectFieldOffset(Foo.class.getDeclaredField("x"))); }
}

// 8. Updater
class Updaters {
    static final AtomicIntegerFieldUpdater<Foo> UP = AtomicIntegerFieldUpdater.newUpdater(Foo.class, "cnt");
    static final AtomicReferenceFieldUpdater<Foo, String> RUP =
            AtomicReferenceFieldUpdater.newUpdater(Foo.class, String.class, "ref");
}

// 9. VarHandle
class VH {
    static final VarHandle H;
    static { try { H = MethodHandles.lookup().findVarHandle(Foo.class, "x", int.class); } catch (ReflectiveOperationException e) { throw new Error(e); } }
}

// 10. 类来自参数 → UNRESOLVED_CLASS（动态持有者）
class DynHolder {
    final long o;
    DynHolder(Class<?> k) throws Exception { o = Uns.U.objectFieldOffset(k.getDeclaredField("x")); }
}

// 11. 分支合并 → 通配
class MergeF {
    final long o;
    MergeF(boolean flag) throws Exception {
        Field f = flag ? Foo.class.getDeclaredField("x") : Foo.class.getDeclaredField("y");
        o = Uns.U.objectFieldOffset(f);
    }
}

// 12. static final Field 常量 → 跨语句追踪
class StaticField {
    static final Field F;
    static final long OFF;
    static {
        try { F = Foo.class.getDeclaredField("x"); OFF = Uns.U.objectFieldOffset(F); } catch (Exception e) { throw new Error(e); }
    }
}

// 13. 装箱后放入 Map → ESCAPED
class Boxed {
    static final Map<String, Long> M = new HashMap<>();
    static { try { M.put("x", Uns.U.objectFieldOffset(Foo.class.getDeclaredField("x"))); } catch (Exception e) { throw new Error(e); } }
}

// 14. 偏移量加常量再存 → 派生仍绑定
class Derived {
    static final long OFF;
    static { try { OFF = Uns.U.objectFieldOffset(Foo.class.getDeclaredField("x")) + 4; } catch (Exception e) { throw new Error(e); } }
}

// 15. 日志拼接不应算逃逸，但 PUTSTATIC 仍记录
class Logged {
    static long OFF;
    static {
        try {
            long o = Uns.U.objectFieldOffset(Foo.class.getDeclaredField("y"));
            System.out.println("off=" + o);
            OFF = o;
        } catch (Exception e) { throw new Error(e); }
    }
}

// 16. 负向：无任何绑定
class Plain { int a; int f() { return a + 1; } }
