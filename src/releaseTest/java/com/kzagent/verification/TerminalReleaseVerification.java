package com.kzagent.verification;

/** Runs against optimized jars: ordinary unit tests only load the original dependency. */
public final class TerminalReleaseVerification {
    public static void main(String[] args) throws Exception {
        // Resolving every method forces JVM verification of the large pointer handler,
        // even without a display or a configured provider/session.
        Class<?> renderer = Class.forName("ai.rever.bossterm.compose.ui.ProperTerminalKt", false,
                TerminalReleaseVerification.class.getClassLoader());
        if (renderer.getDeclaredMethods().length == 0) {
            throw new AssertionError("Terminal renderer methods are missing from Release");
        }
        System.out.println("Release terminal renderer bytecode verified");
    }
}
