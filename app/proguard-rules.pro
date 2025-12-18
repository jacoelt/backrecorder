############################################
# Jetpack Compose – REQUIRED
############################################
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material.** { *; }
-keep class androidx.compose.material3.** { *; }

-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

############################################
# Kotlin metadata (CRITICAL)
############################################
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @kotlin.Metadata *;
}

############################################
# Kotlin Coroutines
############################################
-keep class kotlinx.coroutines.** { *; }

############################################
# kotlinx.serialization (if used)
############################################
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

############################################
# App UI (Compose)
############################################
-keep class com.backrecorderapp.** { *; }

############################################
# AndroidX DataStore (Preferences)
############################################
-keep class androidx.datastore.** { *; }

############################################
# Google Play Billing & Licensing
############################################
-keep class com.android.billingclient.api.** { *; }
-keep class com.android.vending.licensing.** { *; }

############################################
# wheel-picker-compose
############################################
-keep class com.commandiron.wheel_picker_compose.** { *; }

############################################
# Keep foreground service for MediaRecorder
############################################
-keep class com.backrecorderapp.services.AudioRecordingService { *; }

############################################
# Tink (AES, AEAD, Keyset, etc.)
############################################
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.aead.** { *; }
-keep class com.google.crypto.tink.integration.android.** { *; }
-keep class com.google.crypto.tink.integration.android.AndroidKeysetManager { *; }

############################################
# Kotlin reflection
############################################
-keepclassmembers class kotlin.reflect.** { *; }
-keepclassmembers class kotlin.jvm.internal.** { *; }

############################################
# Keep generated classes that use reflection (common pattern in obfuscated libs)
############################################
-keep class r1.** { *; }
-keep class t1.** { *; }
-keep class s1.** { *; }
-keep class p1.** { *; }
-keep class j4.** { *; }
-keep class q4.** { *; }
-keep class F1.** { *; }

############################################
# Protobuf – REQUIRED
############################################
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

############################################
# General note: adjust above keep rules if you know the actual package of the lib causing reflection issues
############################################
