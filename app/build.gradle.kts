plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose Compiler 从编译器内部拆成独立 Gradle 插件，
    // 开启 buildFeatures.compose 却不应用它会在配置阶段直接失败。
    // 版本由根脚本的 apply false 统一管理，此处不写版本号。
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lanyue.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lanyue.reader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// Room schema 导出（T05）。
//
// `@Database(exportSchema = true)` 只是"同意导出"，真正决定 JSON 落到哪里的是这个
// KSP 参数。两者缺一：Room 会在每次构建时打一条警告说 schema 无处可写，而
// schema 一旦没被真正写出来，后续就永远没有基线可以 diff——加了字段只能靠
// `fallbackToDestructiveMigration()` 掩盖，代价是用户升级即丢数据。
//
// 路径用 `$projectDir/schemas`（即 app/schemas/），必须提交进版本库：
// 它是迁移测试（MigrationTestHelper）唯一可信的输入。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // 4.9 播放器
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // 4.12 密钥存储（Keystore + EncryptedSharedPreferences）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 4.6 网络层
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 4.6 / 真 DOM
    implementation("org.jsoup:jsoup:1.17.2")

    // 3.x 数据层
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// 4.10.7 / 8.2 性能验收：同屏 200 条
tasks.withType<Test> {
    maxParallelForks = 1
}
