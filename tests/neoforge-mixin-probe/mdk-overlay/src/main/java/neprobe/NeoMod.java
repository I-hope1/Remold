package neprobe;

import net.neoforged.fml.common.Mod;

/**
 * Minimal NeoForge mod that only hosts {@link NeoProbe} inside a real dedicated-server dev run.
 *
 * <p>It deliberately imports nothing from Minecraft: the probe exercises the Mixin / ClassInfo
 * path, and a mod that does not touch game classes keeps the fixture small and its failure modes
 * attributable. Gated by {@code -Dremold.probe.neoforge=true} so the mod is inert otherwise.</p>
 */
@Mod(NeoMod.MODID)
public final class NeoMod {
    public static final String MODID = "neprobe";

    public NeoMod() {
        if (Boolean.getBoolean("remold.probe.neoforge")) {
            NeoProbe.start();
        }
    }
}
