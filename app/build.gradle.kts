import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Room 注解处理改用 KSP：比 kapt 更快，且原生面向 Kotlin 2。
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.rtcomm.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rtcomm.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 60
        versionName = "2.3.4"
        vectorDrawables { useSupportLibrary = true }
    }

    // ── 签名配置 ────────────────────────────────────────────────
    // debug：使用本机标准 debug keystore（可用 local.properties / 环境变量覆盖）。
    // release：正式分发签名，从 local.properties / 环境变量读取，**禁止回退 debug**。
    //   rtcomm.release.keystore / RTCOMM_RELEASE_KEYSTORE
    //   rtcomm.release.storePassword / RTCOMM_RELEASE_STORE_PASSWORD
    //   rtcomm.release.keyAlias / RTCOMM_RELEASE_KEY_ALIAS
    //   rtcomm.release.keyPassword / RTCOMM_RELEASE_KEY_PASSWORD
    signingConfigs {
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun prop(key: String, env: String, fallback: String): String =
            localProps.getProperty(key) ?: System.getenv(env) ?: fallback
        fun optProp(key: String, env: String): String? =
            localProps.getProperty(key) ?: System.getenv(env)
        create("rtcommShared") {
            storeFile = file(
                prop(
                    "rtcomm.keystore", "RTCOMM_KEYSTORE",
                    "${System.getProperty("user.home")}/.android/debug.keystore",
                ),
            )
            storePassword = prop("rtcomm.keystorePassword", "RTCOMM_KEYSTORE_PASSWORD", "android")
            keyAlias = prop("rtcomm.keyAlias", "RTCOMM_KEY_ALIAS", "androiddebugkey")
            keyPassword = prop("rtcomm.keyPassword", "RTCOMM_KEY_PASSWORD", "android")
            enableV1Signing = true
            enableV2Signing = true
        }
        // release 专用签名：未配置 keystore 时 assembleRelease 会失败（不再静默回退 debug 签名）。
        create("rtcommRelease") {
            val ks = optProp("rtcomm.release.keystore", "RTCOMM_RELEASE_KEYSTORE")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = optProp("rtcomm.release.storePassword", "RTCOMM_RELEASE_STORE_PASSWORD")
                keyAlias = optProp("rtcomm.release.keyAlias", "RTCOMM_RELEASE_KEY_ALIAS")
                keyPassword = optProp("rtcomm.release.keyPassword", "RTCOMM_RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rtcommShared")
        }
        release {
            // R8：压缩/混淆代码并裁剪未使用资源。反射相关路径（Gson/Room/OkHttp/Compose）
            // 依赖 consumer rules 与 app/proguard-rules.pro 保活；改动后务必回归一次 release 包。
            isMinifyEnabled = true
            isShrinkResources = true
            // 安全：release 必须用正式签名，禁止回退 debug（未配置时 assembleRelease 会失败）。
            signingConfig = signingConfigs.getByName("rtcommRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // Compose Compiler pinned to Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // 自分发项目：release 包不跑 lintVital（它会显著拖慢构建；lint 仍可单独 `gradle lint` 执行）。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions {
        unitTests {
            // 纯逻辑单测（不依赖设备）里，未使用的 android.* 桩方法返回默认值而非抛异常。
            isReturnDefaultValues = true
            // Robolectric 截图测试需要访问合并后的资源与清单。
            isIncludeAndroidResources = true
        }
    }
}

// 截图测试（Roborazzi）：
//   录制基准图（默认）：  gradle :app:testDebugUnitTest --tests "*ScreenshotRenderTest" --tests "*ScreenRenderTest"
//   校验（CI 视觉回归）：  -Proborazzi.record=false -Proborazzi.verify=true
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", providers.gradleProperty("roborazzi.record").getOrElse("true"))
    systemProperty("roborazzi.test.verify", providers.gradleProperty("roborazzi.verify").getOrElse("false"))
}

// Room 的非破坏性迁移需要导出可审查的 schema；KSP 构建时自动生成到 app/schemas。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Compose BOM: 2024.09.02 => material3 1.3.0 (PullToRefreshBox), foundation/ui 1.7.x
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)
    // EXIF 去敏使用 support 库实现（平台 android.media.ExifInterface 在旧系统有已知安全问题）
    implementation(libs.androidx.exifinterface)

    // ---- P1: 安全存储（EncryptedSharedPreferences）----
    implementation(libs.androidx.security.crypto)

    // ---- P1: Room 消息持久化 ----
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---- P2: 媒体（Coil 图片 + Media3 流播）----
    implementation(libs.coil.compose)
    // 动态头像（GIF/WebP）播放支持
    implementation(libs.coil.gif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // ---- FCM 推送（应用被杀/后台省电导致 WebSocket 断开时的系统通知兜底）----
    // 不使用 google-services 插件：改为运行时用字符串资源手动 FirebaseApp.initializeApp
    //（见 RtcommApp）。这样缺少 Firebase 配置时 App 仍可正常编译运行，推送自动降级为关闭。
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ---- JVM 单元测试（纯逻辑，不依赖设备）----
    testImplementation(libs.junit)

    // ---- 截图回归测试（Robolectric 真实框架 + Roborazzi 出图，无需设备/KVM）----
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // createComposeRule 需要一个可启动的宿主 Activity（并入 debug 清单）。
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Release 与 CI 使用同一份完整解析图，避免传递依赖在不知情的情况下漂移。
configurations.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
