# ============================================================
# PotatoSack - ProGuard shrink-only 配置
# ============================================================

-dontoptimize
-dontobfuscate


# ============================================================
# PotatoSack 插件代码
#
# 保留插件自身的所有类和成员，避免 Bukkit/Paper 加载入口、
# Gson 反射以及其他运行时访问受到影响。
# ============================================================

-keep class indi.somebottle.potatosack.** {
    *;
}


# ============================================================
# AWS SDK - ServiceLoader
#
# AWS SDK 会通过 Java ServiceLoader 动态加载 HTTP 实现
# 和内部 JSON 工厂，因此需要显式保留对应实现类及构造方法。
# ============================================================

-keep class software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService {
    public <init>();
}

-keep class software.amazon.awssdk.thirdparty.jackson.core.JsonFactory {
    public <init>();
}


# ============================================================
# AWS SDK - 反射访问
#
# AWS SDK 部分内部逻辑会动态访问 clone() 方法。
# 保留这些成员，避免裁剪后出现运行时错误。
# ============================================================

-keepclassmembers class software.amazon.awssdk.** {
    *** clone();
}


# ============================================================
# 类文件元数据
#
# 保留泛型签名、异常声明、内部类信息和注解。
# 这些信息可能会被反射、序列化框架或第三方库使用。
# ============================================================

-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*


# ============================================================
# 枚举
#
# 保留所有枚举类型的标准 values() 和 valueOf() 方法。
# 避免依赖反射、序列化或配置解析时出现问题。
# ============================================================

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ============================================================
# Java 序列化
#
# 保留 Serializable 类型中的 serialVersionUID。
# ============================================================

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
}


# ============================================================
# 可选平台与可选依赖
#
# 以下依赖中的部分代码只针对特定平台或可选功能。
# PotatoSack 当前不会使用这些实现，因此允许 ProGuard
# 忽略对应的缺失类警告。
# ============================================================


# ------------------------------------------------------------
# OkHttp 可选平台支持
#
# OkHttp 同时包含 Android、Conscrypt、BouncyCastle、
# OpenJSSE 等可选平台实现。在普通 Java Minecraft
# 服务器环境中并不需要这些类。
# ------------------------------------------------------------

-dontwarn android.**
-dontwarn dalvik.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**


# ------------------------------------------------------------
# AWS CRT 可选实现
#
# PotatoSack 使用 AWS SDK 的 URLConnection HTTP 客户端，
# 不使用基于 AWS CRT 的原生实现。
# ------------------------------------------------------------

-dontwarn software.amazon.awssdk.crt.**


# ------------------------------------------------------------
# cron-utils 可选 Bean Validation 支持
#
# cron-utils 包含 javax.validation 集成，
# 但当前插件不依赖这部分功能。
# ------------------------------------------------------------

-dontwarn javax.validation.**


# ------------------------------------------------------------
# aircompressor 可选 Hadoop 适配层
#
# aircompressor 内置 Hadoop Codec 适配代码，
# Minecraft 服务器环境中不需要 Hadoop 相关 API。
# ------------------------------------------------------------

-dontwarn org.apache.hadoop.**


# ------------------------------------------------------------
# hash4j / VarHandle
#
# hash4j 使用 VarHandle 的 signature-polymorphic 调用。
# ProGuard 无法完全按照普通方法签名解析这些调用，
# 因此会产生实际上无害的缺失成员警告。
# ------------------------------------------------------------

-dontwarn com.dynatrace.hash4j.hashing.ByteBufferUtil
-dontwarn com.dynatrace.hash4j.internal.ByteArrayUtil


# ============================================================
# ProGuard 诊断输出
#
# usage：记录被 ProGuard 删除的类和成员。
# seeds：记录由 keep 规则直接保留的类和成员。
# ============================================================

-printusage target/proguard-usage.txt
-printseeds target/proguard-seeds.txt
