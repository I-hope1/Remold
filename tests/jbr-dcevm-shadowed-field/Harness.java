import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Pure JBR 21 + Instrumentation.redefineClasses control experiment.
 * No Remold code, no Remold agent. Reads p.Base/p.Mid/p.Leaf instances through
 * (1) Base's own method body (baseGetX: getfield p/Base.x),
 * (2) a never-redefined external getter (p.External.readBaseX: getfield p/Base.x),
 * (3) reflected Field resolved on p.Base,
 * (4) subclass own methods / declared fields.
 */
public class Harness {

    static Path root;
    static DirLoader loader;
    static Instrumentation inst;

    public static void main(String[] args) throws Exception {
        root = Paths.get(args[0]).toAbsolutePath().normalize();
        String mode = args[1];
        inst = Agent.inst;
        initUnsafe();
        System.out.println("ENV redefSupported=" + inst.isRedefineClassesSupported());

        loader = new DirLoader(root.resolve("classes/v1"), Harness.class.getClassLoader());
        Object[] v1 = make();
        read("boot(v1)", v1);
        readFields("boot(v1)", v1);

        if (mode.equals("v12") || mode.equals("v123") || mode.equals("repeat")) {
            loader.setDir(root.resolve("classes/v2"));
            redefine("v2", "p.Base", "p.Mid", "p.Leaf");
            read("v2-existing", v1);
            readFields("v2-existing", v1);
            Object[] v2 = make();
            read("v2-fresh", v2);
            readFields("v2-fresh", v2);

            if (mode.equals("v123")) {
                loader.setDir(root.resolve("classes/v3"));
                redefine("v3", "p.Mid", "p.Leaf");
                read("v3-existing", v1);
                readFields("v3-existing", v1);
                read("v3-fresh", v2);
                readFields("v3-fresh", v2);
                Object[] v3 = make();
                read("v3-fresh2", v3);
                readFields("v3-fresh2", v3);
            } else if (mode.equals("repeat")) {
                loader.setDir(root.resolve("classes/v3alt"));
                redefine("v3alt", "p.Mid", "p.Leaf");
                read("v3rep-existing", v1);
                readFields("v3rep-existing", v1);
                Object[] v3r = make();
                read("v3rep-fresh", v3r);
                readFields("v3rep-fresh", v3r);
            }
        }
    }

    static Object[] make() throws Exception {
        Class<?> f = loader.loadClass("p.Factory");
        return (Object[]) f.getMethod("make").invoke(null);
    }

    static void redefine(String version, String... names) throws Exception {
        ClassDefinition[] defs = new ClassDefinition[names.length];
        for (int i = 0; i < names.length; i++) {
            Class<?> c = loader.loadClass(names[i]);
            Path p = root.resolve("classes").resolve(version)
                    .resolve(names[i].replace('.', '/') + ".class");
            defs[i] = new ClassDefinition(c, Files.readAllBytes(p));
        }
        long t0 = System.nanoTime();
        inst.redefineClasses(defs);
        System.out.println("REDEFINE " + version + " [" + String.join(",", names) + "] ok opMs="
                + (System.nanoTime() - t0) / 1_000_000);
    }

    static void read(String tag, Object[] o) throws Exception {
        Class<?> bc = loader.loadClass("p.Base");
        Class<?> mc = loader.loadClass("p.Mid");
        Class<?> lc = loader.loadClass("p.Leaf");
        Method baseGetX = bc.getMethod("baseGetX");
        Method midGetX = mc.getMethod("midGetX");
        Method leafGetX = lc.getMethod("leafGetX");
        Field baseX = bc.getField("x"); // always resolved on Base
        Field midDeclX = decl(mc, "x");
        Field leafDeclX = decl(lc, "x");
        Method extBase = loader.loadClass("p.External").getMethod("readBaseX", bc);

        Object base = o[0], mid = o[1], leaf = o[2], anon = o[3];
        System.out.println(String.format(
                "PROBE-READING %-15s mid.x=%-4s leaf.x=%-4s anon.x=%-4s | base.method: mid=%-4s leaf=%-4s anon=%-4s base=%-4s"
                        + " | base.reflect: mid=%-4s leaf=%-4s anon=%-4s"
                        + " | ext.getfield: mid=%-4s leaf=%-4s"
                        + " | mid.method(leaf)=%-4s leaf.method(leaf)=%-4s base.reflect(base)=%-4s",
                tag,
                ownX(mid, baseX, midDeclX), ownX(leaf, baseX, leafDeclX), ownX(anon, baseX, leafDeclX),
                call(baseGetX, mid), call(baseGetX, leaf), call(baseGetX, anon), call(baseGetX, base),
                fv(baseX, mid), fv(baseX, leaf), fv(baseX, anon),
                ext(extBase, mid), ext(extBase, leaf),
                call(midGetX, leaf), call(leafGetX, leaf), fv(baseX, base)));
    }

    static void readFields(String tag, Object[] o) throws Exception {
        Class<?> bc = loader.loadClass("p.Base");
        Class<?> mc = loader.loadClass("p.Mid");
        Class<?> lc = loader.loadClass("p.Leaf");
        Field bx = decl(bc, "bx");
        Field mx = decl(mc, "mx");
        Field lx = decl(lc, "lx");
        Field v3m = decl(mc, "v3m");
        Field v3l = decl(lc, "v3l");
        Object base = o[0], mid = o[1], leaf = o[2], anon = o[3];
        System.out.println(String.format(
                "PROBE-FIELDS  %-15s bx: base=%-4s mid=%-4s leaf=%-4s | mx: mid=%-4s leaf=%-4s"
                        + " | lx: leaf=%-4s anon=%-4s | v3m: leaf=%-4s | v3l: leaf=%-4s",
                tag,
                fv(bx, base), fv(bx, mid), fv(bx, leaf),
                fv(mx, mid), fv(mx, leaf),
                fv(lx, leaf), fv(lx, anon),
                fv(v3m, leaf), fv(v3l, leaf)));
        System.out.println(String.format(
                "PROBE-RAW    %-15s midobj: Base.x=%-4s Mid.x=%-4s Mid.m=%-4s Mid.mx=%-4s"
                        + " | leafobj: Base.x=%-4s Mid.x=%-4s Leaf.x=%-4s Leaf.l=%-4s Leaf.lx=%-4s Leaf.v3l=%-4s",
                tag,
                raw(mid, bc.getField("x")), raw(mid, decl(mc, "x")), raw(mid, decl(mc, "m")),
                raw(mid, decl(mc, "mx")),
                raw(leaf, bc.getField("x")), raw(leaf, decl(mc, "x")), raw(leaf, decl(lc, "x")),
                raw(leaf, decl(lc, "l")), raw(leaf, decl(lc, "lx")), raw(leaf, decl(lc, "v3l"))));
        System.out.println(String.format(
                "PROBE-OFFSET %-15s Base.x@%-4s Mid.x@%-4s Leaf.x@%-4s | Base.bx@%-4s Mid.mx@%-4s Leaf.lx@%-4s",
                tag,
                off(bc.getField("x")), off(decl(mc, "x")), off(decl(lc, "x")),
                off(decl(bc, "bx")), off(decl(mc, "mx")), off(decl(lc, "lx"))));
    }

    /** Mid/Leaf own x if the class declares it, otherwise the inherited Base.x value. */
    static String ownX(Object inst, Field baseX, Field declX) throws Exception {
        if (inst == null) return "n/a";
        Field f = declX != null ? declX : baseX;
        return fv(f, inst);
    }

    static String fv(Field f, Object inst) throws Exception {
        if (f == null || inst == null || !f.getDeclaringClass().isInstance(inst)) return "-";
        f.setAccessible(true);
        Object v = f.get(inst);
        return v == null ? "null" : String.valueOf(v);
    }

    static String call(Method m, Object inst) throws Exception {
        if (inst == null) return "n/a";
        m.setAccessible(true);
        return String.valueOf(m.invoke(inst));
    }

    static String ext(Method m, Object inst) throws Exception {
        if (inst == null) return "n/a";
        m.setAccessible(true);
        return String.valueOf(m.invoke(null, inst));
    }

    static Field decl(Class<?> c, String name) {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    static Object unsafe;
    static Method objFieldOff;

    static void initUnsafe() throws Exception {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = f.get(null);
        objFieldOff = uc.getMethod("objectFieldOffset", Field.class);
        unsafeGetInt = uc.getMethod("getInt", Object.class, long.class);
    }

    static Method unsafeGetInt;

    static String raw(Object inst, Field f) throws Exception {
        if (inst == null || f == null) return "-";
        f.setAccessible(true);
        long o = (Long) objFieldOff.invoke(unsafe, f);
        return String.valueOf(unsafeGetInt.invoke(unsafe, inst, o));
    }

    static String off(Field f) throws Exception {
        if (f == null) return "-";
        return String.valueOf(objFieldOff.invoke(unsafe, f));
    }

    static class DirLoader extends ClassLoader {
        volatile Path dir;

        DirLoader(Path dir, ClassLoader parent) {
            super(parent);
            this.dir = dir;
        }

        void setDir(Path d) {
            dir = d;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Path f = dir.resolve(name.replace('.', '/') + ".class");
            try {
                byte[] b = Files.readAllBytes(f);
                return defineClass(name, b, 0, b.length);
            } catch (Exception e) {
                throw new ClassNotFoundException(name + " @ " + f, e);
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (name.startsWith("p.")) {
                        c = findClass(name);
                    } else {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
