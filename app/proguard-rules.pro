# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Google Drive API
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.common.**

# Apache HttpClient (transitive dep of google-api-client) references optional
# Kerberos/LDAP auth classes that don't exist on Android and are never used.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# Google Play Billing
-keep class com.android.vending.billing.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Strip verbose/debug/info logging from release builds. proguard-android-optimize.txt does not,
# and these lines carry Drive file ids and segment ids into user logcat and bug reports.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
