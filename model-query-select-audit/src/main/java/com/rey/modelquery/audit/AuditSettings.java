package com.rey.modelquery.audit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * The agent settings read when the JUnit session opens (R-INS-22, D-265): a system property wins over the same key in
 * {@code junit-platform.properties} on the class path.
 */
final class AuditSettings {

    /** How the agent is attached: {@code auto} (the default) attaches it at session open when no agent was given. */
    static final String ATTACH = "modelquery.selectAudit.attach";

    /** What a session does with no agent: {@code fail} (the default), {@code warn} or {@code off}. */
    static final String MISSING_AGENT = "modelquery.selectAudit.missingAgent";

    enum Attach { AUTO, STARTUP }

    enum MissingAgent { FAIL, WARN, OFF }

    private AuditSettings() {}

    static Attach attach(ClassLoader loader) {
        return parse(ATTACH, read(ATTACH, loader), Attach.class, Attach.AUTO);
    }

    static MissingAgent missingAgent(ClassLoader loader) {
        return parse(MISSING_AGENT, read(MISSING_AGENT, loader), MissingAgent.class, MissingAgent.FAIL);
    }

    static String read(String key, ClassLoader loader) {
        String value = System.getProperty(key);
        // a blank property (an unset Maven placeholder) leaves the file's value in force
        if ((value != null && !value.isBlank()) || loader == null) {
            return value;
        }
        try (InputStream in = loader.getResourceAsStream("junit-platform.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty(key);
        } catch (IOException e) {
            return null;
        }
    }

    static <E extends Enum<E>> E parse(String key, String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            StringBuilder allowed = new StringBuilder();
            for (E constant : type.getEnumConstants()) {
                allowed.append(allowed.isEmpty() ? "" : "|").append(constant.name().toLowerCase(Locale.ROOT));
            }
            throw new IllegalStateException(key + "=" + value + " is not one of " + allowed);
        }
    }
}
