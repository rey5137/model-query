package com.rey.modelquery.audit;

import java.util.Arrays;
import java.util.List;

/** Classifies the first application or serializer frame of a model read. */
final class ReadSite {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final List<String> SERIALIZERS = List.of("com.fasterxml.jackson.", "tools.jackson.",
            "jakarta.json.", "javax.json.");
    private static volatile Rules cachedRules = new Rules("", List.of());

    private record Rules(String config, List<String> entries) {}

    record Site(String location, boolean serializer, boolean ignored) {}

    private ReadSite() {}

    static Site capture(Class<?> model) {
        String config = System.getProperty("modelquery.selectAudit.ignore", "");
        Rules rules = cachedRules;
        if (!rules.config().equals(config)) {
            rules = new Rules(config, Arrays.stream(config.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList());
            cachedRules = rules;
        }
        var frame = WALKER.walk(frames -> frames.filter(candidate -> {
            String name = candidate.getClassName();
            Class<?> declaring = candidate.getDeclaringClass();
            return declaring != ReadSite.class && declaring != ReadAccess.class && declaring != Tag.class
                    && !declaring.isAssignableFrom(model) && !declaring.isHidden() && !name.startsWith("java.")
                    && !name.startsWith("jdk.") && !name.startsWith("sun.") && !name.contains("$$")
                    && !name.contains("$Access4Jackson");
        }).findFirst().orElse(null));
        if (frame != null) {
            String name = frame.getClassName();
            if (rules.entries().stream().anyMatch(rule -> matches(rule, name, frame.getMethodName()))) {
                return new Site("", false, true);
            }
            boolean serializer = SERIALIZERS.stream().anyMatch(name::startsWith);
            String source = frame.getFileName();
            String location = name + "." + frame.getMethodName()
                    + (source == null ? "" : "(" + source + (frame.getLineNumber() < 0
                            ? "" : ":" + frame.getLineNumber()) + ")");
            return new Site(location, serializer, false);
        }
        return new Site("unknown reader", false, false);
    }

    private static boolean matches(String rule, String className, String method) {
        int separator = rule.indexOf('#');
        if (separator < 0) {
            return className.startsWith(rule);
        }
        if (!rule.substring(0, separator).equals(className)) {
            return false;
        }
        String namedMethod = rule.substring(separator + 1);
        String lambda = "lambda$" + namedMethod + "$";
        return method.equals(namedMethod) || (method.startsWith(lambda) && method.length() > lambda.length()
                && method.substring(lambda.length()).chars().allMatch(Character::isDigit));
    }
}
