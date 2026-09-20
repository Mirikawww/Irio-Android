# Irio release 混淆规则
# 默认规则 proguard-android-optimize.txt 已覆盖 AndroidX/Compose 大部分场景。

# --- 代码里按名字反射/构造的类，必须保留 ---

# 无（当前代码无 Class.forName / JSON 映射实体 / JNI）

# --- 第三方库自带的传递规则没被正确打包时需要的保底 ---

# JLatexMath 通过资源文件加载字体/符号表（assets 不在混淆范围），
# 但内部有按类名查找的工厂，保守保留：
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

# multiplatform-markdown-renderer 使用 kotlinx 的 expect/actual 与服务加载
-keep class com.mikepenz.markdown.** { *; }

# Coil 网络组件通过 ServiceLoader 注册
-keep class coil3.** { *; }

# --- Kotlin / Compose 常规保底 ---

# Compose 编译器生成的类
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin 元数据/反射（Kotlin serialization 未用，但反射调用需要）
-keepclassmembers class * {
    @kotlin.Metadata *;
}

# 保留泛型签名（Gson/Moshi 未用，但部分库运行时需要）
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# 调试信息：保留行号便于线上崩溃定位
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- 屏蔽警告 ---

# jlatexmath 引用了 AWT 类（Android 上用 stub 替代）
-dontwarn java.awt.**
-dontwarn org.scilab.forge.jlatexmath.awt.**

# OkHttp / Okio 的可选平台组件
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin 协程调试代理（发布包不需要）
-dontwarn kotlinx.coroutines.debug.**
