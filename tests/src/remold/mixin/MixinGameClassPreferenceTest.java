package remold.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression for the real root cause of the live-client {@code InternalError}: the same class name can be
 * defined twice in one VM (host loader copy, e.g. Fabric Knot, and a bare Agent/System loader copy).
 * The bare copy has no Mixin weaving and no loader access transforms. Redefining that copy makes JBR see
 * "added interface" and verify subclasses against protected members, ending in
 * {@code VerifyError: Bad access to protected data in invokevirtual} -> bare {@code InternalError}.
 *
 * <p>{@code MixinHotSwap} must therefore prefer the host-loader copy, never the system/agent one.</p>
 */
public class MixinGameClassPreferenceTest {

    private static final class DefLoader extends ClassLoader {
        DefLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static Class<?> hostCopy() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/GameCopy", null, "java/lang/Object", null);
        cw.visitEnd();
        return new DefLoader(ClassLoader.getPlatformClassLoader())
            .define("fixture.GameCopy", cw.toByteArray());
    }

    @Test
    public void hostLoaderCopyWinsRegardlessOfIterationOrder() {
        Class<?> host = hostCopy();
        Class<?> system = MixinGameClassPreferenceTest.class; // loaded by the system/application loader
        assertSame(host, MixinHotSwap.preferGameClass(system, host),
            "host-loader (Knot/ModLauncher) copy must win over the system-loader copy");
        assertSame(host, MixinHotSwap.preferGameClass(host, system),
            "preference must be stable regardless of the getAllLoadedClasses() iteration order");
    }

    @Test
    public void loaderRanksReflectAuthority() {
        assertEquals(0, MixinHotSwap.loaderRank(String.class), "bootstrap classes have no loader");
        assertEquals(1, MixinHotSwap.loaderRank(MixinGameClassPreferenceTest.class),
            "system/application loader copy is a bare copy");
        assertEquals(2, MixinHotSwap.loaderRank(hostCopy()),
            "host loader (Knot/ModLauncher/...) copy is authoritative");
    }
}
