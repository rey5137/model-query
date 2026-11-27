package com.rey.modelquery.audit;

import net.bytebuddy.agent.ByteBuddyAgent;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.Set;
import java.util.TreeSet;

/**
 * Attaches this jar as an agent to the running JVM when the JUnit session opens without {@code -javaagent}
 * (R-INS-22, D-265), through Byte Buddy's attacher: in process when the JVM allows it, else an external attach process.
 */
final class SelfAttach {

    private SelfAttach() {}

    /** Attaches the agent; returns {@code null} when it is installed afterwards, else why not. */
    static String attach() {
        File jar;
        try {
            CodeSource source = AuditAgent.class.getProtectionDomain().getCodeSource();
            jar = source == null ? null : new File(source.getLocation().toURI());
        } catch (URISyntaxException | SecurityException | IllegalArgumentException e) {
            jar = null;
        }
        if (jar == null || !jar.isFile() || !jar.getName().endsWith(".jar")) {
            return "the audit classes are not loaded from a jar" + (jar == null ? "" : " (" + jar + ")");
        }
        // The attached agent starts from the system class loader; checked first, so no unusable transformer stays.
        if (!visibleToSystemLoader()) {
            return "the audit classes are not loaded by the system class loader, so an attached agent would not see "
                    + "them (e.g. Surefire useSystemClassLoader=false)";
        }
        // The JDK prints its dynamic-agent warning (JEP 451) to System.err on the attach thread while the agent
        // starts; a test runner's captured System.err can fail there, which aborts the agent. Print it to stderr.
        PrintStream captured = System.err;
        PrintStream stderr = new PrintStream(new FileOutputStream(FileDescriptor.err), true);
        try {
            System.setErr(stderr);
            ByteBuddyAgent.attach(jar, String.valueOf(ProcessHandle.current().pid()));
        } catch (RuntimeException | LinkageError e) {
            return "attaching to the running JVM failed: " + e;
        } finally {
            if (System.err == stderr) {
                System.setErr(captured);
            }
        }
        if (!AuditAgent.isInstalled()) {
            return "the attached agent did not start";
        }
        String early = seamsLoadedBefore(AuditAgent.instrumentation());
        if (early != null) {
            return "engine classes were loaded before the session opened (" + early + "), so their hooks are missing; "
                    + "a launcher session listener or extension ran a query first";
        }
        AuditAgent.markAttachedAtRuntime();
        return null;
    }

    private static boolean visibleToSystemLoader() {
        try {
            return Class.forName(AuditAgent.class.getName(), false, ClassLoader.getSystemClassLoader())
                    == AuditAgent.class;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String seamsLoadedBefore(Instrumentation inst) {
        Set<String> seams = ReadTransformer.seamTypeNames();
        Set<String> loaded = new TreeSet<>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (seams.contains(type.getName()) && !ReadTransformer.seamTypesSeen().contains(type.getName())) {
                loaded.add(type.getName().substring(type.getName().lastIndexOf('.') + 1));
            }
        }
        return loaded.isEmpty() ? null : String.join(", ", loaded);
    }
}
