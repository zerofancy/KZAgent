# Okio ProGuard rules to fix verification errors
# Keep all Okio classes and members to avoid type mismatches
-keep class okio.** { *; }
-dontwarn okio.**