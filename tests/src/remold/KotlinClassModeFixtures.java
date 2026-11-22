package remold;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fixture plumbing for {@link KotlinClassModeProbe}.
 *
 * <p>Fixture layout (compiled by the {@code ktclass*} source sets in {@code tests/build.gradle}):
 * <ul>
 *   <li>{@code ktclassClsV1/V2} — Kotlin {@code -Xsam-conversions=class} closure host, so every SAM
 *       conversion is a real class file named {@code ClosureHost$metricTable$<hint>$N}.</li>
 *   <li>{@code ktclassPreV1/V2} — Kotlin host-suffix anonymous objects ({@code SlotHost$metricTable$N});
 *       v2 prepends one anonymous object, shifting the suffixes.</li>
 *   <li>{@code ktclassCtlV1/V2} — javac control group with the same shape and real host names.</li>
 * </ul>
 *
 * <p>Only the v1 output is ever on the probe classpath; v2 is read as bytes from the system
 * property directories and copied into a scratch directory that doubles as the class output dir of
 * the fixture class loader. That mirrors the real setup (the agent watches the compile output dir,
 * which is also on the classpath), so a class that v2 only introduces can still be loaded from disk
 * after the batch.
 *
 * <p>Gradle hands a source set's class directories over as a {@link File#pathSeparator}-joined list
 * (Kotlin source sets have an empty javac directory plus the Kotlin one), so every entry point here
 * takes the list and skips missing directories.
 */
final class KotlinClassModeFixtures {

	static final String PROP_CLASS_V1 = "remold.kcm.class.v1";
	static final String PROP_CLASS_V2 = "remold.kcm.class.v2";
	static final String PROP_PREPEND_V1 = "remold.kcm.prepend.v1";
	static final String PROP_PREPEND_V2 = "remold.kcm.prepend.v2";
	static final String PROP_CONTROL_V1 = "remold.kcm.control.v1";
	static final String PROP_CONTROL_V2 = "remold.kcm.control.v2";

	private KotlinClassModeFixtures() {
	}

	/**
	 * Resolve a fixture directory system property into the existing class-output directories.
	 *
	 * @throws IllegalStateException when the property is unset or none of its entries exists
	 */
	static List<Path> requiredDirs(String property) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("fixture directory property " + property + " is not set");
		}
		List<Path> dirs = new ArrayList<>();
		for (String entry : value.split(File.pathSeparator)) {
			if (entry.isBlank()) continue;
			Path path = Path.of(entry.trim()).toAbsolutePath().normalize();
			if (Files.isDirectory(path)) dirs.add(path);
		}
		if (dirs.isEmpty()) {
			throw new IllegalStateException("fixture directory does not exist: " + property + "=" + value);
		}
		return dirs;
	}

	/** Read every {@code .class} below the given roots as dotted class name to bytes. */
	static Map<String, byte[]> readClasses(List<Path> roots) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Path root : roots) {
			for (Path file : classFiles(root)) {
				out.put(dottedName(root, file), Files.readAllBytes(file));
			}
		}
		return out;
	}

	/**
	 * Replace every {@code .class} file of the given roots into {@code dst}, keeping the package layout.
	 *
	 * @return the copied files in deterministic order (the batch input)
	 */
	static List<Path> copyClasses(List<Path> roots, Path dst) throws IOException {
		Files.createDirectories(dst);
		Set<Path> copied = new LinkedHashSet<>();
		for (Path root : roots) {
			for (Path file : classFiles(root)) {
				Path target = dst.resolve(root.relativize(file).toString());
				Files.createDirectories(target.getParent());
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				copied.add(target);
			}
		}
		List<Path> ordered = new ArrayList<>(copied);
		ordered.sort(null);
		return ordered;
	}

	/** Recursively delete {@code root}; a missing directory is not an error. */
	static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
				Files.deleteIfExists(path);
			}
		}
	}

	/** Dotted class name of {@code classFile} relative to its class-output root. */
	static String dottedName(Path root, Path classFile) {
		String relative = root.relativize(classFile).toString();
		String noSuffix = relative.endsWith(".class") ? relative.substring(0, relative.length() - 6) : relative;
		return noSuffix.replace('/', '.').replace('\\', '.');
	}

	/** All fixture class names of the given versions (used to tell a real host name from a ghost one). */
	@SafeVarargs
	static Set<String> classNames(Map<String, byte[]>... versions) {
		Set<String> out = new LinkedHashSet<>();
		for (Map<String, byte[]> version : versions) out.addAll(version.keySet());
		return out;
	}

	/** True when {@code name} looks like a class that was synthesised with an empty/unstable name. */
	static boolean suspiciousClassName(String name) {
		if (name == null || name.isEmpty()) return true;
		return name.endsWith(".") || name.endsWith("$") || name.contains("$$") || name.contains("null");
	}

	private static List<Path> classFiles(Path root) throws IOException {
		if (!Files.isDirectory(root)) {
			throw new IllegalStateException("fixture directory does not exist: " + root);
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".class"))
				.sorted()
				.collect(Collectors.toList());
		}
	}
}
