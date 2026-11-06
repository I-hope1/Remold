package remold.fabric;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FabricClassPathGroupsTest {

	@Test
	void parserAcceptsExplicitSeparatorOnAnyPlatform() {
		assertEquals(java.util.List.of("E:\\a", "E:\\b"),
			FabricClassPathGroups.parseAgentArgs("E:\\a;E:\\b", ";"));
		assertEquals(java.util.List.of("/a", "/b"),
			FabricClassPathGroups.parseAgentArgs("/a:/b", ":"));
	}

	@Test
	void agentArgsKeepDriveLetterOnWindowsStylePaths() throws Exception {
		Path root = Files.createTempDirectory("fabric-cpg");
		Path classes = Files.createDirectories(root.resolve("build").resolve("classes"));
		Path resources = Files.createDirectories(root.resolve("build").resolve("resources"));

		String oldGroups = System.getProperty("fabric.classPathGroups");
		String oldDev = System.getProperty("fabric.development");
		System.setProperty("fabric.development", "true");
		System.clearProperty("fabric.classPathGroups");
		try {
			String args = classes.toAbsolutePath() + File.pathSeparator + resources.toAbsolutePath();
			FabricClassPathGroups.apply(args);
			String groups = System.getProperty("fabric.classPathGroups");
			assertNotNull(groups, "classPathGroups must be configured for two sibling output dirs");
			assertTrue(groups.contains(classes.toAbsolutePath().toString()),
				"classes dir must survive agentArgs parsing: " + groups);
			assertTrue(groups.contains(resources.toAbsolutePath().toString()),
				"resources dir must survive agentArgs parsing: " + groups);
		} finally {
			if (oldGroups == null) System.clearProperty("fabric.classPathGroups");
			else System.setProperty("fabric.classPathGroups", oldGroups);
			if (oldDev == null) System.clearProperty("fabric.development");
			else System.setProperty("fabric.development", oldDev);
		}
	}
}
