package remold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import remold.offset.LayoutPredictor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the cache half of the AppClassLoader bare-copy incident: a second, lower-authority
 * copy of a class name must never replace the host-loader baseline in {@code bytecodeCache} /
 * {@code sourceBytecodeCache}. Previously the loader was the first one to be cached
 * ({@code putIfAbsent}), so a bare AppClassLoader copy could become the redefine baseline and produce
 * the {@code VerifyError: Bad access to protected data} chain.
 */
public class CacheLoaderPriorityTest {

    private static final String NAME = "fixture.cacheprobe.PriorityProbe";

    private static final class HostLoader extends ClassLoader {
        HostLoader() {
            super(ClassLoader.getPlatformClassLoader());
        }
    }

    @AfterEach
    public void cleanup() {
        RemoldAgent.bytecodeCache.remove(NAME);
        RemoldAgent.sourceBytecodeCache.remove(NAME);
    }

    private static void write(byte[] runtime, byte[] source, ClassLoader loader) {
        RemoldAgent.updateInitialBaseline(NAME, runtime, source, loader, false);
    }

    @Test
    public void bareCopyNeverOverwritesHostBaseline() {
        byte[] host = {1, 2, 3};
        byte[] bare = {9, 9, 9};
        write(host, host, new HostLoader());
        assertArrayEquals(host, RemoldAgent.bytecodeCache.get(NAME), "precondition: host copy cached");

        boolean accepted = RemoldAgent.updateInitialBaseline(
            NAME, bare, bare, ClassLoader.getSystemClassLoader(), false);

        assertFalse(accepted, "a lower-authority (AppClassLoader) copy must be rejected");
        assertArrayEquals(host, RemoldAgent.bytecodeCache.get(NAME),
            "bytecodeCache must keep the host-loader bytes");
        assertArrayEquals(host, RemoldAgent.sourceBytecodeCache.get(NAME),
            "sourceBytecodeCache must keep the host-loader bytes");
    }

    @Test
    public void hostCopyReplacesBareCopyEvenIfLoadedLater() {
        byte[] bare = {9, 9, 9};
        byte[] host = {1, 2, 3};
        write(bare, bare, ClassLoader.getSystemClassLoader());
        assertArrayEquals(bare, RemoldAgent.bytecodeCache.get(NAME), "precondition: bare copy cached first");

        boolean accepted = RemoldAgent.updateInitialBaseline(NAME, host, host, new HostLoader(), false);

        assertTrue(accepted, "a host-loader copy must replace the bare one regardless of load order");
        assertArrayEquals(host, RemoldAgent.bytecodeCache.get(NAME), "host bytes must win in the cache");
    }

    @Test
    public void ephemeralPredictionLoaderNeverEntersBaseline() {
        LayoutPredictor.EphemeralClassLoader ephemeral = new LayoutPredictor.EphemeralClassLoader(
            Map.of(), null, ClassLoader.getPlatformClassLoader());
        byte[] bytes = {7};
        boolean accepted = RemoldAgent.updateInitialBaseline(NAME, bytes, bytes, ephemeral, false);
        assertFalse(accepted, "throwaway layout-prediction copies must never become the redefine baseline");
        assertNull(RemoldAgent.bytecodeCache.get(NAME), "cache must stay empty for ephemeral copies");
    }

    @Test
    public void sameAuthoritySecondLoadKeepsFirstBaseline() {
        byte[] first = {1};
        byte[] second = {2};
        write(first, first, ClassLoader.getSystemClassLoader());
        boolean accepted = RemoldAgent.updateInitialBaseline(
            NAME, second, second, ClassLoader.getSystemClassLoader(), false);
        assertFalse(accepted, "same-authority duplicate loads keep the first baseline");
        assertArrayEquals(first, RemoldAgent.bytecodeCache.get(NAME), "first baseline must survive");
    }
}
