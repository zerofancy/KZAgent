package com.kzagent.verification;

import java.awt.Toolkit;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the optimized jars: unit tests cannot detect a removed lifetime anchor. */
public final class NativeCallbackReleaseVerification {
    public static void main(String[] args) {
        try {
            verify();
            System.out.println("Release JNA lifetime and native callbacks verified across GC");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            // AWT starts its own event thread on macOS; also terminate it on failure.
            System.exit(1);
        }
    }

    private static void verify() throws Exception {
        Class.forName("com.sun.jna.Native");
        Method execute = null;
        Object foundation = null;
        AtomicInteger callbacks = new AtomicInteger();
        if (System.getProperty("os.name").startsWith("Mac")) {
            Toolkit.getDefaultToolkit();
            Class<?> type = Class.forName("io.github.vinceglb.filekit.dialogs.platform.mac.foundation.Foundation");
            foundation = type.getField("INSTANCE").get(null);
            execute = type.getMethod("executeOnMainThread", boolean.class, boolean.class, Runnable.class);
        }
        for (int round = 0; round < 5; round++) {
            if (execute != null) {
                execute.invoke(foundation, true, true, (Runnable) callbacks::incrementAndGet);
                if (callbacks.get() != round + 1) throw new AssertionError("Native callback did not complete");
            }
            System.gc();
            System.runFinalization();
            Thread.sleep(200);
            if (!"true".equals(System.getProperty("jna.loaded"))) {
                throw new AssertionError("GC disposed JNA while the application still uses it");
            }
        }
    }
}
