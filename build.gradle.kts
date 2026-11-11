plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Compose Compiler 插件由 Kotlin 官方发布，版本必须与 kotlin.android 完全一致；
    // 两者差一个版本号会拿到不匹配的编译器桥接，报错出现在编译期且信息晦涩。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
