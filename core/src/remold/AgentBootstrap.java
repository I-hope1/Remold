package remold;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.jar.JarFile;

/**
 * Minimal {@code Premain-Class} / {@code Agent-Class} trampoline for the agent JAR.
 *
 * <p>When {@code remold-agent.jar} is renamed (or attached dynamically via {@code agentmain}),
 * the static {@code Boot-Class-Path: remold-agent.jar} manifest entry does not match the JAR file,
 * so the JVM loads the entry class on the application class loader instead of the bootstrap class
 * loader. If {@link RemoldAgent} itself were the entry class and then appended the JAR to the
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
			CodeSource cs = AgentBootstrap.class.getProtectionDomain().getCodeSource();
			if (cs == null || cs.getLocation() == null) return;
			File file = new File(cs.getLocation().toURI());
			if (file.isFile() && file.getName().endsWith(".jar")) {
				inst.appendToBootstrapClassLoaderSearch(new JarFile(file));
			}
		} catch (Throwable t) {
			System.err.println("[Remold] [WARN] Failed to append agent JAR to bootstrap classloader search: " + t);
		}
	}
}
