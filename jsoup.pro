# jsoup optionally uses RE2/J when it is present on the runtime classpath and
# otherwise falls back to the JDK regex engine. KZAgent does not ship RE2/J.
-dontwarn com.google.re2j.**
