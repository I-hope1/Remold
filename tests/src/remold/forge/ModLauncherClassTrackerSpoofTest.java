package remold.forge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ModLauncher 类跟踪器摘除/恢复的 finally 契约。 */
class ModLauncherClassTrackerSpoofTest {

	@Test
	void suppressTemporarilyRemovesTargetsAndRestorePutsThemBack() {
		FakeTracker tracker = new FakeTracker();
		tracker.loadedClasses.add("net.minecraft.world.entity.Entity");
		tracker.loadedClasses.add("net.minecraft.server.MinecraftServer");
		ModLauncherClassTrackerSpoof spoof = new ModLauncherClassTrackerSpoof(null, tracker);

		Object token = spoof.suppress(List.of("net.minecraft.world.entity.Entity"));

		assertNotNull(token);
		assertFalse(tracker.loadedClasses.contains("net.minecraft.world.entity.Entity"));
		assertTrue(tracker.loadedClasses.contains("net.minecraft.server.MinecraftServer"));

		spoof.restore(token);
		assertTrue(tracker.loadedClasses.contains("net.minecraft.world.entity.Entity"));
		assertEquals(2, tracker.loadedClasses.size());
	}

	@Test
	void missingLoadedClassesFieldFailsClosedAndRestoreIsNoOp() {
		ModLauncherClassTrackerSpoof spoof = new ModLauncherClassTrackerSpoof(null, new Object());
		Object token = spoof.suppress(List.of("net.minecraft.world.entity.Entity"));
		assertNull(token);
		spoof.restore(token);
	}

	@Test
	void emptyTargetsDoNotTouchTheTracker() {
		FakeTracker tracker = new FakeTracker();
		tracker.loadedClasses.add("net.minecraft.world.entity.Entity");
		ModLauncherClassTrackerSpoof spoof = new ModLauncherClassTrackerSpoof(null, tracker);

		Object token = spoof.suppress(List.of());
		assertNotNull(token);
		assertTrue(tracker.loadedClasses.contains("net.minecraft.world.entity.Entity"));
	}

	private static final class FakeTracker {
		private final Set<String> loadedClasses = new LinkedHashSet<>();
	}
}
