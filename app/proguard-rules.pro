# Sage ProGuard Rules

# Keep the accessibility service (instantiated by Android framework via reflection)
-keep class com.mossgreen.sage.service.AssistantService { <init>(); }

# Keep enum values used in JSON serialization via CommandType.valueOf()
-keepclassmembers enum com.mossgreen.sage.model.CommandType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Rules for NewPipeExtractor
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rules for Rhino (used by NewPipeExtractor)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Rules for OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Preserve line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
