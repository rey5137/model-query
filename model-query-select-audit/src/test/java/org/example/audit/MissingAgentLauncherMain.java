package org.example.audit;

public final class MissingAgentLauncherMain {
    private MissingAgentLauncherMain() {}

    public static void main(String[] args) {
        try (var session = org.junit.platform.launcher.core.LauncherFactory.openSession()) {
            throw new AssertionError("missing agent did not fail the launcher session");
        }
    }
}
