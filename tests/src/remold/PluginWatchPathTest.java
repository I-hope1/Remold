package remold;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-level guarantee for the loader-agnosticism seam.
 *
 * <p>Core must not carry any loader-specific directory convention. "Do not watch this directory"
 * is a <b>plugin vote</b> ({@link HotswapPlugin#isWatchPathIgnored}), so these tests lock three
 * properties that would each fail silently if the seam regressed:</p>
 * <ol>
 *   <li>with no plugin registered, nothing is excluded — a hardcoded {@code mods/} rule in core
 *       would immediately break the file-system driven ecosystems (Mindustry/Arc deploy into
 *       {@code mods/} and need those paths watched);</li>
 *   <li>a plugin vote is honored, including for paths core knows nothing about;</li>
 *   <li>a throwing plugin cannot take down the watcher / directory walk — the path is simply not
 *       excluded (fail-open on the exclusion decision; the guard's absence is a missing convenience,
 *       whereas an exception here would abort the whole scan).</li>
 * </ol>
 *
 * <p>Coverage boundary: the two call sites (watch events, startup directory walk) need a real
 * {@code WatchService} / filesystem and are not exercised here; they delegate to the same
 * {@link RemoldAgent#isWatchPathIgnored} asserted below.</p>
 */
class PluginWatchPathTest {

	@Test
	void engineExcludesNothingWithoutAPlugin() {
		Path underMods = Path.of("run", "mods", "example.jar");
		assertFalse(RemoldAgent.isWatchPathIgnored(underMods),
			"core must not hardcode a loader-specific exclusion: mods/ is a plugin decision");
	}

	@Test
	void pluginVoteIsHonoredAndRevertedOnUnregister() {
		Path underMods = Path.of("run", "mods", "example.jar");
		Path underClasses = Path.of("build", "classes", "java", "main", "Foo.class");

		HotswapPlugin ignoring = new HotswapPlugin() {
			@Override public String getName() { return "test-mods-guard"; }
			@Override public boolean isWatchPathIgnored(Path path) { return true; }
		};

		RemoldAgent.registerPlugin(ignoring);
		try {
			assertTrue(RemoldAgent.isWatchPathIgnored(underMods),
				"a plugin vote to ignore must be honored for the loader-specific path");
			assertTrue(RemoldAgent.isWatchPathIgnored(underClasses),
				"the stub ignores every path: the vote itself is the only input, core adds no rule of its own");
		} finally {
			RemoldAgent.unregisterPlugin(ignoring);
		}

		assertFalse(RemoldAgent.isWatchPathIgnored(underMods),
			"unregistering the plugin must restore the default (no exclusion)");
	}

	@Test
	void throwingPluginDoesNotAbortThePathCheck() {
		HotswapPlugin hostile = new HotswapPlugin() {
			@Override public String getName() { return "test-hostile-guard"; }
			@Override public boolean isWatchPathIgnored(Path path) { throw new IllegalStateException("boom"); }
		};

		RemoldAgent.registerPlugin(hostile);
		try {
			assertFalse(RemoldAgent.isWatchPathIgnored(Path.of("build", "classes", "Foo.class")),
				"a throwing plugin must not propagate: the path is simply not excluded");
		} finally {
			RemoldAgent.unregisterPlugin(hostile);
		}
	}
}
