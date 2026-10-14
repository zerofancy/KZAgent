# BossTerm's kotlin-logging dependency contains optional Logback and GraalVM
# integrations. KZAgent uses the SLF4J facade, not Logback or a native-image build.
-dontwarn io.github.oshai.kotlinlogging.logback.**
-dontwarn com.oracle.svm.core.annotate.Substitute
-dontwarn com.oracle.svm.core.annotate.TargetElement
-dontwarn com.oracle.svm.core.annotate.TargetClass

# Ktor's OpenAPI module has optional JWT annotations. No BossTerm HTTP/MCP server
# or JWT authentication provider is instantiated by the local terminal adapter.
-dontwarn io.ktor.server.auth.jwt.JWTAuthenticationProvider

# PTY4J selects its OS implementation and loads its bundled JNI library at runtime.
# Keep that boundary explicit even when dependency-rule generation changes.
-keep class com.pty4j.** { *; }
-keep interface com.kzagent.kagent.desktop.terminal.TerminalUnixSession$LibC { *; }
