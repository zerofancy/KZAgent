-keep class * extends coil3.util.DecoderServiceLoaderTarget { *; }
-keep class * extends coil3.util.FetcherServiceLoaderTarget { *; }

# OkHttp probes these optional TLS providers and Android platform classes at runtime.
# They are not present or needed in the Compose Desktop distribution.
-dontwarn android.**
-dontwarn dalvik.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
