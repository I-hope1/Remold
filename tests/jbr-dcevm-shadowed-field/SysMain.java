import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import p.Base;
import p.Leaf;
import p.Mid;

/**
 * Same control as Harness but on the SYSTEM classloader with compile-time typed references:
 * every read is the exact form "((Base) inst).x" or Base's own method, resolved against V1.
 * Removes the custom ClassLoader as a variable.
 *
 * Modes:
 *   v123   -> V1 -> V2 (Base,Mid,Leaf) -> V3 (Mid,Leaf); cohorts: v1-created, v2-created, v3-created
 *   v123an -> same rounds but the subclass shadow field is renamed sx (no "x" name collision)
 */
public class SysMain {

    static Path root;
    static Instrumentation inst;
    static String v2dir;
    static Object unsafe;
    static Method objFieldOff;

    static void initUnsafe() throws Exception {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = f.get(null);
        objFieldOff = uc.getMethod("objectFieldOffset", Field.class);
    }

    static String off(Field f) throws Exception {
        return f == null ? "-" : String.valueOf(objFieldOff.invoke(unsafe, f));
    }

    static Field decl(Class<?> c, String n) {
        try {
            return c.getDeclaredField(n);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    static String val(Field f, Object inst) throws Exception {
        if (f == null || inst == null) return "-";
        f.setAccessible(true);
        return String.valueOf(f.get(inst));
    }

    public static void main(String[] a) throws Exception {
        root = Paths.get(a[0]).toAbsolutePath().normalize();
        String mode = a[1];
        v2dir = mode.equals("v123an") ? "v2an" : "v2";
        inst = Agent.inst;
        initUnsafe();

        Base b1 = new Base();
        Mid m1 = new Mid();
        Leaf l1 = new Leaf();
        Leaf a1 = new Leaf() { };
        print("boot", b1, m1, l1, a1);

        redef(v2dir, "p.Base", "p.Mid", "p.Leaf");
        print("v2-c1", b1, m1, l1, a1);

        Base b2 = new Base();
        Mid m2 = new Mid();
        Leaf l2 = new Leaf();
        Leaf a2 = new Leaf() { };
        print("v2-c2", b2, m2, l2, a2);

        redef(mode.equals("v123an") ? "v3an" : "v3", "p.Mid", "p.Leaf");
        print("v3-c1", b1, m1, l1, a1);
        print("v3-c2", b2, m2, l2, a2);

        Base b3 = new Base();
        Mid m3 = new Mid();
        Leaf l3 = new Leaf();
        Leaf a3 = new Leaf() { };
        print("v3-c3", b3, m3, l3, a3);
    }

    static void redef(String ver, String... names) throws Exception {
        ClassDefinition[] d = new ClassDefinition[names.length];
        for (int i = 0; i < names.length; i++) {
            d[i] = new ClassDefinition(Class.forName(names[i]),
                    Files.readAllBytes(root.resolve("classes").resolve(ver)
                            .resolve(names[i].replace('.', '/') + ".class")));
        }
        inst.redefineClasses(d);
        System.out.println("REDEFINE " + ver + " [" + String.join(",", names) + "]");
    }

    /** c1 = made before V2, c2 = made after V2, c3 = made after V3. */
    static void print(String tag, Base b, Mid mid, Leaf leaf, Leaf anon) {
        System.out.println(String.format(
                "SYS-READING %-7s ((Base)mid).x=%-4d baseGetX(mid)=%-4d | ((Base)leaf).x=%-4d baseGetX(leaf)=%-4d"
                        + " | ((Base)anon).x=%-4d baseGetX(anon)=%-4d | base.x=%-4d"
                        + " | symbol(Mid).x: mid=%-4d leaf=%-4d | midGetX(leaf)=%-4d leafGetX(leaf)=%-4d",
                tag, ((Base) mid).x, mid.baseGetX(), ((Base) leaf).x, leaf.baseGetX(),
                ((Base) anon).x, anon.baseGetX(), b.x,
                mid.x, leaf.x, leaf.midGetX(), leaf.leafGetX()));
        try {
            Field bx = decl(Base.class, "x");
            Field m = decl(Mid.class, "m");
            Field l = decl(Leaf.class, "l");
            System.out.println(String.format(
                    "SYS-OFFSET %-7s Base.x@%-3s Mid.m@%-3s Leaf.l@%-3s Mid.x@%-3s Leaf.x@%-3s Mid.sx@%-3s"
                            + " | values: mid.m=%-4s leaf.l=%-4s",
                    tag, off(bx), off(m), off(l), off(decl(Mid.class, "x")), off(decl(Leaf.class, "x")),
                    off(decl(Mid.class, "sx")), val(m, mid), val(l, leaf)));
        } catch (Exception e) {
            System.out.println("SYS-OFFSET " + tag + " failed: " + e);
        }
    }
}
