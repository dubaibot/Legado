# ============================================================
# 蓝阅 ProGuard / R8 keep 规则
# T51：release 包各功能与 debug 一致；无 ClassNotFoundException、
# 无字段被混淆导致的反序列化失败。
# ============================================================

# ============================================================
# 1. Room 实体与 DAO
# Room 通过反射读写实体字段，混淆后字段名变了会导致数据库读写失败。
# ============================================================
-keep class com.lanyue.reader.data.db.** { *; }
-keep class com.lanyue.reader.data.db.entity.** { *; }
-keep class com.lanyue.reader.data.db.dao.** { *; }

# Room 生成的实现类
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ============================================================
# 2. 书源模型（规则解析依赖字段名）
# 规则引擎通过 Gson/反射读写字源 JSON 的字段名，
# 混淆后 Legado 格式的书源 JSON 导入会失败。
# ============================================================
-keep class com.lanyue.reader.source.model.** { *; }

# ============================================================
# 3. 备份 DTO（BackupCodec 手写字段读写，但 Gson 工具链可能反射）
# 备份格式是对外契约，字段名混淆会让旧版备份读不出来。
# ============================================================
-keep class com.lanyue.reader.data.backup.** { *; }

# ============================================================
# 4. JS 引擎反射入口
# core/engine 通过 Rhino/QuickJS 反射调用 JsExtensions 的方法名，
# 混淆方法名会导致书源规则里的 JS 调用全部失效。
# ============================================================
-keep class com.lanyue.reader.core.engine.JsExtensions { *; }
-keep class com.lanyue.reader.core.engine.JsRuntime { *; }
-keep class com.lanyue.reader.core.engine.JsRuntimePool { *; }
-keep class com.lanyue.reader.core.engine.JsRunner { *; }
-keep class com.lanyue.reader.core.engine.RunnerInstance { *; }
-keep class com.lanyue.reader.core.engine.Engine { *; }
-keep class com.lanyue.reader.core.engine.DomParser { *; }
-keep class com.lanyue.reader.core.engine.Sniffer { *; }
-keep class com.lanyue.reader.core.engine.LanyueBrowserEngine { *; }

# ============================================================
# 5. 弹幕实体与配置
# 弹幕源配置通过数据库持久化，字段名混淆会导致配置丢失。
# ============================================================
-keep class com.lanyue.reader.danmaku.DanmakuSourceEntity { *; }
-keep class com.lanyue.reader.danmaku.DanmakuSourceRegistry { *; }
-keep class com.lanyue.reader.danmaku.DanmakuSourceValidator { *; }
-keep class com.lanyue.reader.danmaku.DanmakuSourceFormModel { *; }
-keep class com.lanyue.reader.danmaku.DanmakuMatchEngine { *; }
-keep class com.lanyue.reader.danmaku.render.** { *; }

# ============================================================
# 6. 排版与阅读核心（含枚举值）
# Paginator、ReadingSession 等被 Compose 反射调用枚举值。
# ============================================================
-keep class com.lanyue.reader.core.render.** { *; }
-keep class com.lanyue.reader.core.tts.** { *; }

# ============================================================
# 7. Kotlinx Serialization
# 当前全仓 0 处 @Serializable 引用，但依赖已声明。
# 保留序列化 companion 与 Serializer，将来加注解时不用回来补。
# ============================================================
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(**);
}

# ============================================================
# 8. Gson（备份编解码与书源 JSON 解析用到 Gson）
# Gson 通过反射读写字段，必须保留字段名。
# ============================================================
-keepattributes Signature
-keepattributes EnclosingMethod
# 防止 Gson 把 data class 的字段名混淆
-keep class com.google.gson.** { *; }
# 所有带 @SerializedName 的字段
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================================
# 9. OkHttp / Okio
# OkHttp 用反射加载 TLS/Platform 特定类。
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================================
# 10. Media3 / ExoPlayer
# ExoPlayer 通过反射加载 DataSource 工厂和渲染器。
# ============================================================
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ============================================================
# 11. Compose
# Compose 编译器插件已处理大部分混淆问题，
# 但 Composable 函数签名被反射调用（预览/工具）时需要保留。
# ============================================================
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ============================================================
# 12. 枚举值（通用）
# 枚举的 values() 和 valueOf() 被多处反射调用。
# ============================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
# 13. Kotlin Metadata
# 保留 Kotlin 反射元数据，部分库依赖它做反射。
# ============================================================
-keepattributes KotlinMetadata
-keep class kotlin.Metadata { *; }
