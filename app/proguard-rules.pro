# 保留通过反射调用的 Shizuku 桥接
-keep class rikka.shizuku.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
