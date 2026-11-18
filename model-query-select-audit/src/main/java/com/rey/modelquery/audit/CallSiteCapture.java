package com.rey.modelquery.audit;

import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.lang.reflect.InvocationHandler;
import java.util.List;

/**
 * Captures the application call site and outermost executor method from the stack (R-INS-14, R-INS-18). The test is
 * the one the JUnit listener recorded for the thread, else the outermost application frame.
 */
final class CallSiteCapture {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final String LIBRARY = "com.rey.modelquery.";

    private static final List<String> NOT_CALL_SITES = List.of(
            LIBRARY,
            "org.springframework.",
            "com.sun.proxy.",
            "java.",
            "jdk.",
            "sun.",
            "org.junit.",
            "org.testng.",
            "org.apache.maven.surefire.",
            "org.gradle.",
            "worker.org.gradle.",
            "com.intellij.rt."
    );

    private CallSiteCapture() {}

    record Site(String callSite, String method, String test) {
        Site(String callSite, String method) {
            this(callSite, method, "");
        }
    }

    /**
     * Captures the call site and outermost executor method. Returns {@code null} if the stack contains
     * {@code com.rey.modelquery.portability} or no valid call site could be found.
     */
    static Site capture() {
        List<StackWalker.StackFrame> frames = WALKER.walk(s -> s.toList());
        for (StackWalker.StackFrame frame : frames) {
            if (frame.getClassName().startsWith("com.rey.modelquery.portability.")) {
                return null;
            }
        }

        int outermostExecutorIndex = -1;
        for (int i = frames.size() - 1; i >= 0; i--) {
            Class<?> declaring = frames.get(i).getDeclaringClass();
            if (ModelQueryExecutor.class.isAssignableFrom(declaring)) {
                outermostExecutorIndex = i;
                break;
            }
        }

        String method = outermostExecutorIndex >= 0 ? frames.get(outermostExecutorIndex).getMethodName() : "query";

        int startSearch = outermostExecutorIndex >= 0 ? outermostExecutorIndex + 1 : 0;
        // a caller inside com.rey.modelquery.* (this project's own tests, samples) is only used when no other
        // application frame exists; then the outermost such frame, nearest the test runner, is taken so an
        // engine frame between the read and the test is never reported
        for (boolean library : new boolean[] {false, true}) {
            Site site = findCallSite(frames, startSearch, method, library);
            if (site != null) {
                return site;
            }
        }
        return null;
    }

    private static Site findCallSite(List<StackWalker.StackFrame> frames, int startSearch, String method,
            boolean allowLibraryPackage) {
        for (int n = startSearch; n < frames.size(); n++) {
            int i = allowLibraryPackage ? frames.size() - 1 - (n - startSearch) : n;
            StackWalker.StackFrame frame = frames.get(i);
            if (isCallSiteCandidate(frame, allowLibraryPackage)) {
                String className = frame.getClassName();
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                String callSite = simpleName + "." + frame.getMethodName()
                        + (frame.getLineNumber() >= 0 ? ":" + frame.getLineNumber() : "");
                String test = CurrentTest.get();
                return new Site(callSite, method, test != null ? test : findTestFrame(frames, i));
            }
        }
        return null;
    }

    private static String findTestFrame(List<StackWalker.StackFrame> frames, int fromIndex) {
        StackWalker.StackFrame outerCandidate = frames.get(fromIndex);
        for (int i = frames.size() - 1; i >= fromIndex; i--) {
            StackWalker.StackFrame frame = frames.get(i);
            if (isCallSiteCandidate(frame, true)) {
                outerCandidate = frame;
                break;
            }
        }
        return outerCandidate.getClassName() + "#" + outerCandidate.getMethodName();
    }

    private static boolean isCallSiteCandidate(StackWalker.StackFrame frame, boolean allowLibraryPackage) {
        String className = frame.getClassName();
        for (String prefix : NOT_CALL_SITES) {
            if (allowLibraryPackage && prefix.equals(LIBRARY)) {
                continue;
            }
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        Class<?> declaring = frame.getDeclaringClass();
        if (ModelQueryExecutor.class.isAssignableFrom(declaring)
                || InvocationHandler.class.isAssignableFrom(declaring)) {
            return false;
        }
        return !isGeneratedQClass(declaring);
    }

    private static boolean isGeneratedQClass(Class<?> type) {
        return ModelProperties.hasFieldsMethod(type) || ModelProperties.hasFieldsMethod(type.getNestHost());
    }
}
