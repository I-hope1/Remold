import java.lang.instrument.Instrumentation;

/** Minimal instrumentation carrier: exposes Instrumentation. Contains NO Remold code. */
public class Agent {
    public static volatile Instrumentation inst;

    public static void premain(String args, Instrumentation i) {
        inst = i;
    }

    public static void agentmain(String args, Instrumentation i) {
        inst = i;
    }
}
