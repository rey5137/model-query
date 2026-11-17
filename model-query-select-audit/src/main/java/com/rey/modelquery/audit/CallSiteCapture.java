package com.rey.modelquery.audit;

import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.lang.reflect.InvocationHandler;
import java.util.List;

/** Captures the application call site and outermost executor method from the stack (R-INS-14, R-INS-18). */
final class CallSiteCapture {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final List<String> NOT_CALL_SITES = List.of(
            "com.rey.modelquery.",
            "org.springframework.",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "jdk.internal.reflect.",
            "jdk.proxy",
            "com.sun.proxy.",
            "java.util.stream."
    );

    private CallSiteCapture() {}

    record Site(String callSite, String method) {}

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
        for (int i = startSearch; i < frames.size(); i++) {
            StackWalker.StackFrame frame = frames.get(i);
            if (isCallSiteCandidate(frame)) {
                String className = frame.getClassName();
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                String callSite = simpleName + "." + frame.getMethodName()
                        + (frame.getLineNumber() >= 0 ? ":" + frame.getLineNumber() : "");
                return new Site(callSite, method);
            }
        }
        return null;
    }

    private static boolean isCallSiteCandidate(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        for (String prefix : NOT_CALL_SITES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        Class<?> declaring = frame.getDeclaringClass();
        if (ModelQueryExecutor.class.isAssignableFrom(declaring)
                || InvocationHandler.class.isAssignableFrom(declaring)) {
            return false;
        }
        if (isGeneratedQClass(declaring)) {
            return false;
        }
        return true;
    }

    private static boolean isGeneratedQClass(Class<?> type) {
        return ModelProperties.hasFieldsMethod(type) || ModelProperties.hasFieldsMethod(type.getNestHost());
    }
}
