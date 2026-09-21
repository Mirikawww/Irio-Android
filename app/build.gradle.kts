import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

android {
    namespace = "com.irio"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.irio"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 包名与 release 分离：可与正式版共存安装，互不影响数据。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Composables UI
    implementation("com.composables:ui:0.2.0")
    implementation("com.composables:composeunstyled-slider:2.7.0")

    // Markdown
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.45.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.45.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.0")
    implementation("com.squareup.okhttp3:okhttp:${libs.versions.okhttp.get()}")

    // 图片预览：双击/双指缩放（PhotoViewer）
    implementation("me.saket.telephoto:zoomable:0.19.0")

    // LaTeX — rikkahub fork of jlatexmath-android (JitPack)
    implementation("com.github.rikkahub.jlatexmath-android:jlatexmath:1.5")
    implementation("com.github.rikkahub.jlatexmath-android:jlatexmath-font-greek:1.5")
    implementation("com.github.rikkahub.jlatexmath-android:jlatexmath-font-cyrillic:1.5")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20241224")
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
