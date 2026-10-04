# kotlinx.serialization 和 ML Kit 自带混淆规则，这里不需要额外配置

# 友盟+ 统计 SDK 没有自带混淆规则，按友盟文档保留
-keep class com.umeng.** { *; }
-keep class com.uyumao.** { *; }
-keep class org.repackage.** { *; }
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep public class com.yishulabs.qtranslator.R$* {
    public static final int *;
}
-dontwarn com.umeng.**
-dontwarn org.repackage.**
