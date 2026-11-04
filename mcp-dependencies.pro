# MCP core also publishes servlet server transports; this application only creates clients.
-dontwarn jakarta.servlet.**
# Reactor probes optional monitoring, context-propagation and BlockHound integrations.
-dontwarn io.micrometer.**
-dontwarn reactor.blockhound.**
# Networknt uses the JDK regex backend unless optional Joni/GraalJS implementations are selected.
-dontwarn org.joni.**
-dontwarn org.jcodings.**
-dontwarn org.graalvm.polyglot.**
# Jackson's optional older-JDK fast paths and signature-polymorphic MethodHandle calls.
-dontwarn sun.misc.SharedSecrets
-dontwarn sun.misc.JavaLangAccess
-dontwarn tools.jackson.databind.deser.impl.MethodProperty
-dontwarn tools.jackson.databind.introspect.AnnotatedConstructor
-dontwarn tools.jackson.databind.introspect.AnnotatedMethod
-dontwarn tools.jackson.databind.ser.BeanPropertyWriter

# Jackson deserializes MCP records using annotations and canonical constructor metadata.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,Record,MethodParameters
-keep class io.modelcontextprotocol.spec.McpSchema$** { *; }
-keep class io.modelcontextprotocol.json.jackson3.** { *; }
# Jackson discovers feature flags with Class.getEnumConstants(); enum unboxing is incompatible.
-keep enum tools.jackson.** { *; }
-keep enum com.networknt.** { *; }
# The SDK resolves its schema validator through META-INF/services at client construction.
-keep class io.modelcontextprotocol.json.schema.jackson3.** { *; }
# Coroutine/reactor await resolves this implementation through its ContextInjector service.
-keep class kotlinx.coroutines.reactor.ReactorContextInjector { *; }
# TypeRef inspects the anonymous subclass's generic superclass at runtime.
-keep class io.modelcontextprotocol.json.TypeRef { *; }
-keep class * extends io.modelcontextprotocol.json.TypeRef { *; }
# Reactor updates subscriber/state fields reflectively through Atomic*FieldUpdater.
# Optimizing these fields to their initial null values breaks CAS loops.
-keepclassmembers class reactor.** {
    volatile <fields>;
}
# Jackson 3 still uses the com.fasterxml annotation artifact; annotation defaults refer to its enums.
-keep class com.fasterxml.jackson.annotation.** { *; }
