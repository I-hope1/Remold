package remold;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.jar.JarFile;

/**
 * Minimal {@code Premain-Class} / {@code Agent-Class} trampoline for the agent JAR.
 *
 * <p>{@code Boot-Class-Path} is intentionally omitted from the manifest: a static relative entry
 * such as {@code Boot-Class-Path: remold-agent.jar} makes the JVM pre-load a sibling
 * {@code remold-agent.jar} onto the bootstrap classpath when the running agent JAR has been
 * renamed, shadowing the renamed JAR's own classes. Loading the entrypoint on {@code app} and
 * appending the running JAR dynamically also handles {@code agentmain} attachment cleanly.</p>
 *
 * <p>If {@link RemoldAgent} itself were the entry class and then appended the JAR to the
 * bootstrap search path, {@link RemoldAgent} would remain defined by {@code app} while lazily
 * loaded package-private helpers (such as {@link EnvironmentProbe}) would be defined by
 * {@code bootstrap}, causing {@link IllegalAccessError} across runtime packages.</p>
 *
 * <p>This trampoline has no symbolic references to any {@code remold.*} class: it appends the
 * running agent JAR to the bootstrap class loader search first (when not already on bootstrap) and
 * then loads {@link RemoldAgent} through the bootstrap class loader ({@code null}), keeping all
 * agent classes in a single runtime package regardless of JAR filename.</p>
 */
public final class AgentBootstrap {

	private static final String AGENT_CLASS = "remold.RemoldAgent";

	private AgentBootstrap() { }

	public static void premain(String agentArgs, Instrumentation inst) throws Throwable {
		launch("premain", agentArgs, inst);
	}

	public static void agentmain(String agentArgs, Instrumentation inst) throws Throwable {
		launch("agentmain", agentArgs, inst);
	}

	private static void launch(String entryMethod, String agentArgs, Instrumentation inst) throws Throwable {
		if (inst != null && AgentBootstrap.class.getClassLoader() != null) {
			appendAgentJarToBootstrap(inst);
		}
		Class<?> agentClass = loadAgentClass();
		Method method = agentClass.getMethod(entryMethod, String.class, Instrumentation.class);
		try {
			method.invoke(null, agentArgs, inst);
		} catch (InvocationTargetException e) {
			throw e.getCause() != null ? e.getCause() : e;
		}
	}

	private static Class<?> loadAgentClass() throws ClassNotFoundException {
		try {
			return Class.forName(AGENT_CLASS, true, null);
		} catch (ClassNotFoundException notOnBootstrap) {
			// Fallback for unpacked classpath / test launches that have no JAR on bootstrap search.
			return Class.forName(AGENT_CLASS, true, AgentBootstrap.class.getClassLoader());
		}
	}

	private static void appendAgentJarToBootstrap(Instrumentation inst) {
		try {
			File agentJar = findSelfContainedAgentJar();
			if (agentJar != null) {
				inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
			}
		} catch (Throwable t) {
			System.err.println("[Remold] [WARN] Failed to append agent JAR to bootstrap classloader search: " + t);
		}
	}

	private static File findSelfContainedAgentJar() {
		CodeSource cs = AgentBootstrap.class.getProtectionDomain().getCodeSource();
		if (cs != null && cs.getLocation() != null) {
			try {
				File ownFile = new File(cs.getLocation().toURI());
				if (isSelfContainedAgentJar(ownFile)) {
					return ownFile;
				}
			} catch (Exception ignored) {
			}
		}
		// Multi-project classpaths (e.g. raw :core classes/JAR alongside :api) can shadow AgentBootstrap on app;
		// resolve the actual fat agent JAR from -javaagent arguments or classloader resources before appending.
		File fromArgs = findAgentJarFromJvmArgs();
		if (fromArgs != null) {
			return fromArgs;
		}
		return findAgentJarFromClassLoader();
	}

	private static File findAgentJarFromJvmArgs() {
		for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (!arg.startsWith("-javaagent:")) continue;
			String spec = arg.substring("-javaagent:".length());
			int eq = spec.indexOf('=');
			if (eq >= 0) {
				File beforeEq = new File(spec.substring(0, eq));
				if (isSelfContainedAgentJar(beforeEq)) return beforeEq;
			}
			File full = new File(spec);
			if (isSelfContainedAgentJar(full)) return full;
		}
		return null;
	}

	private static File findAgentJarFromClassLoader() {
		ClassLoader cl = AgentBootstrap.class.getClassLoader();
		if (cl == null) return null;
		try {
			Enumeration<URL> urls = cl.getResources("remold/AgentBootstrap.class");
			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();
				if (!"jar".equals(url.getProtocol())) continue;
				JarURLConnection conn = (JarURLConnection) url.openConnection();
				File jarFile = new File(conn.getJarFileURL().toURI());
				if (isSelfContainedAgentJar(jarFile)) {
					return jarFile;
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static boolean isSelfContainedAgentJar(File file) {
		if (!file.isFile() || !file.getName().endsWith(".jar")) {
			return false;
		}
		try (JarFile jar = new JarFile(file)) {
			// Distinguish the shaded agent JAR from the raw :core JAR (which lacks :api classes).
			return jar.getEntry("remold/RemoldAgent.class") != null
				&& jar.getEntry("remold/MainThreadDispatcher.class") != null;
		} catch (IOException e) {
			return false;
		}
	}
}
