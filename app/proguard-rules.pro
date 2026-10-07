# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.crypto.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep SecureIdentityStateManager from being obfuscated to prevent reflection issues
-keep class com.bitchat.android.identity.SecureIdentityStateManager {
    private android.content.SharedPreferences prefs;
    *;
}

# Keep all classes that might use reflection
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.nostr.** { *; }
-keep class com.bitchat.android.identity.** { *; }

# Room loads generated database implementations by name and invokes their no-argument
# constructors reflectively. R8 full-mode can otherwise optimize away WorkDatabase_Impl's
# constructor, causing AndroidX Startup to crash before Application.onCreate.
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# Keep Tor implementation (always included)
-keep class com.bitchat.android.net.RealTorProvider { *; }

# Arti (Custom Tor implementation in Rust) ProGuard rules
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.arti.** { *; }
-keepnames class org.torproject.arti.**
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.arti.**

# Fix for AbstractMethodError on API < 29 where LocationListener methods are abstract
-keepclassmembers class * implements android.location.LocationListener {
    public <methods>;
}

# R-5.5: release builds keep only Log.w / Log.e. Strip verbose/debug/info logging (and the
# isLoggable guards) so identifying d/i templates never ship. Log.w/e are redacted via util.Redact.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# R-5.5: Redact has no side effects; let R8 drop calls whose results are unused (i.e. inside the
# stripped Log.v/d/i calls above) so release builds do not compute HMACs for logs that never print.
-assumenosideeffects class com.bitchat.android.util.Redact {
    public *** id(...);
    public *** ids(...);
    public *** text(...);
}
