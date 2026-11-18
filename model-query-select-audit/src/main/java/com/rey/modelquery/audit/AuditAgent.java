package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import java.lang.instrument.Instrumentation;

/**
 * The select-audit Java agent (RFC 0026 Part D, D-230). Attachable via {@code -javaagent} at startup; records its
 * presence so the JUnit launcher session check passes.
 */
@Incubating
public final class AuditAgent {

    private static volatile boolean installed;
    private static volatile Instrumentation instrumentation;

    private AuditAgent() {}

    /** Premain hook called when attached via {@code -javaagent}. */
    @Incubating
    public static synchronized void premain(String arguments, Instrumentation inst) {
        if (!installed) {
            instrumentation = inst;
            ReadTransformer.install(inst);
            installed = true;
        }
    }

    /** Agentmain hook called when attached dynamically. */
    @Incubating
    public static void agentmain(String arguments, Instrumentation inst) {
        premain(arguments, inst);
    }

    /** Whether the audit agent is currently attached and active. */
    @Incubating
    public static boolean isInstalled() {
        return installed;
    }

    /** The {@link Instrumentation} instance handed to the agent, or {@code null} when not attached. */
    @Incubating
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /** Looks up the audit tag for {@code model}, or {@code null} if untagged. */
    @Incubating
    public static Tag tagOf(Object model) {
        return AuditObserver.tagOf(model);
    }
}
