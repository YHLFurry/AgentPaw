# Keep AgentPaw core public API when the host app enables R8.
-keep class com.paw.agent.core.agent.** { *; }
-keep class com.paw.agent.core.llm.** { *; }
-keep class com.paw.agent.core.model.** { *; }
-keep class com.paw.agent.core.tool.** { *; }
-keep class com.paw.agent.core.skill.** { *; }
-keep class com.paw.agent.device.** { *; }

# kotlinx.serialization needs serializers kept for reflective lookup used by multimodal DTOs.
-keepclassmembers class com.paw.agent.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.paw.agent.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
