package com.rey.modelquery.audit;

import com.rey.modelquery.annotations.Incubating;
import java.lang.instrument.Instrumentation;

/**
 * The select-audit Java agent (RFC 0026 Part D, D-230). Attached via {@code -javaagent} at startup, or by the JUnit
 * session when it opens without one (D-265); records its presence so the launcher session check passes.
 */
@Incubating
public final class AuditAgent {

    private static volatile boolean installed;
    private static volatile Instrumentation instrumentation;
    private static volatile boolean attachedAtRuntime;
    private static volatile boolean disabled;

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

    /** Whether the JUnit session attached the agent itself, not {@code -javaagent} (D-265). */
    static boolean attachedAtRuntime() {
        return attachedAtRuntime;
    }

    static void markAttachedAtRuntime() {
        attachedAtRuntime = true;
    }

    /** Whether {@code missingAgent} switched the audit off or to a warning for this JVM, so no further warning. */
    static boolean disabled() {
        return disabled;
    }

    static void disable() {
        disabled = true;
    }

    /** The {@link Instrumentation} instance handed to the agent, or {@code null}; package-private for tests. */
    static Instrumentation instrumentation() {
        return instrumentation;
    }
}
