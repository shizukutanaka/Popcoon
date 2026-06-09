plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

detekt {
    config.setFrom("${rootProject.projectDir}/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
}

android {
    namespace = "com.example.popcoon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.popcoon"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "com.example.popcoon.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true

        // BuildConfig values — override via local.properties or env
        buildConfigField("String", "AMAZON_ACCESS_KEY", "\"${System.getenv("AMAZON_ACCESS_KEY") ?: ""}\"")
        buildConfigField("String", "AMAZON_SECRET_KEY", "\"${System.getenv("AMAZON_SECRET_KEY") ?: ""}\"")
        buildConfigField("String", "AMAZON_PARTNER_TAG", "\"${System.getenv("AMAZON_PARTNER_TAG") ?: ""}\"")
        buildConfigField("String", "RAKUTEN_APP_ID", "\"${System.getenv("RAKUTEN_APP_ID") ?: ""}\"")
        buildConfigField("String", "RAKUTEN_AFFILIATE_ID", "\"${System.getenv("RAKUTEN_AFFILIATE_ID") ?: ""}\"")
        buildConfigField("String", "YAHOO_APP_ID", "\"${System.getenv("YAHOO_APP_ID") ?: ""}\"")
        buildConfigField("String", "YAHOO_SID", "\"${System.getenv("YAHOO_SID") ?: ""}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${System.getenv("ANTHROPIC_API_KEY") ?: ""}\"")
        buildConfigField("String", "BACKEND_URL", "\"${System.getenv("BACKEND_URL") ?: "https://popcoon-backend.workers.dev"}\"")
        buildConfigField("String", "VERSION_NAME", "\"0.1.0\"")

    signingConfigs {
        create("release") {
            storeFile = System.getenv("KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore Preferences (UserPreferences で使用)
    implementation(libs.datastore.preferences)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // ML Kit — Google Code Scanner (CAMERA 権限不要)
    implementation(libs.mlkit.code.scanner)
    // フォールバック: bundled barcode scanning (オフライン環境)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX (将来の custom scanner 実装用に追加)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // WorkManager (バックグラウンド価格同期)
    implementation(libs.workmanager.ktx)
    implementation(libs.workmanager.hilt)

    // Play Billing 7.1+ (Premium ¥480/月 サブスク)
    implementation(libs.billing.ktx)

    // Coil3 (商品画像の非同期読み込み)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    // Glance ホーム画面ウィジェット
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// ── Kover テストカバレッジ設定 ────────────────────────────────────────────
kover {
    reports {
        total {
            xml { onCheck = true }
            html { onCheck = true }
        }
        filters {
            excludes {
                classes(
                    "*.*_Hilt*",
                    "*.*HiltModules*",
                    "*.BuildConfig",
                    "*.*Preview*",
                    "*.R",
                    "*.R\$*",
                )
                packages(
                    "com.example.popcoon.di",     // DI は実行時に検証
                    "com.example.popcoon.ui.theme",  // テーマは Preview で検証
                )
                annotatedBy("*Preview*", "*Generated*")
            }
        }
    }
}
