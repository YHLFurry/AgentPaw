# AgentPaw ProGuard rules

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.paw.agent.core.llm.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.paw.agent.core.llm.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
