# Native's finalizer is a lifetime anchor, although Java code never reads it.
# Removing the field lets GC dispose all callbacks while the app still uses JNA.
-keepclassmembers class com.sun.jna.Native {
    private static final java.lang.Object finalizer;
}
