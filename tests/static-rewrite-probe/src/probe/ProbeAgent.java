package probe;

import java.lang.instrument.Instrumentation;

/** Minimal agent: exposes Instrumentation for redefineClasses. */
public final class ProbeAgent {
    public static volatile Instrumentation INST;

    private ProbeAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        INST = inst;
    }

    public static void agentmain(String args, Instrumentation inst) {
        INST = inst;
    }
}
