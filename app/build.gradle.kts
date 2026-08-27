plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.purenote.local"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.purenote.local"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.kotlinx.coroutines.android)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)

  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
}

// 每次生成 Debug APK 后，按“纯记+版本号”归档到工作区的 APP 目录。
// 新版本发布前仍需同时递增 defaultConfig 中的 versionCode 与 versionName，
// 这样旧 APK 会保留，不会被下一版覆盖。
val archiveDebugApk by tasks.registering(Copy::class) {
    group = "build"
    description = "Copy the debug APK to ../APP using its PureNote version name."
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("../APP"))
    rename { "纯记+${android.defaultConfig.versionName}.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(archiveDebugApk)
}
