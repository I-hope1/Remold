package ciprobe;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Minimal Forge mod used only to host {@link ClassInfoProbe} inside a real dedicated server.
 *
 * <p>The probe is a Forge-side E2E fixture: it drives real Mixin template hot-swaps through the
 * real Remold agent ({@code -javaagent}) and reads the business end state on a live target
 * instance. It is gated by {@code -Dremold.probe.classinfo=true} so the mod is inert otherwise.</p>
 */
@Mod(CiMod.MODID)
public final class CiMod {
    public static final String MODID = "ciprobe";

    public CiMod() {
        MinecraftForge.EVENT_BUS.register(this);
        if (Boolean.getBoolean("remold.probe.classinfo")) {
            ClassInfoProbe.start();
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ClassInfoProbe.serverStarted();
    }
}
