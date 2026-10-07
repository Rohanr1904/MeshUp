# Gson reflection targets in the shared bitchat sources (persisted state payloads).
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.services.SeenMessageStore$* { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Kotlin metadata needed by reflection-based serialization.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Tink references JSR-305 annotations not present on Android.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

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
