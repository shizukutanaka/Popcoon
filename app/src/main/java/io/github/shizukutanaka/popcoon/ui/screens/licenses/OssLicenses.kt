package io.github.shizukutanaka.popcoon.ui.screens.licenses

/**
 * 同梱 OSS ライブラリのライセンス表記。
 *
 * gradle/libs.versions.toml の直接依存から手動で構成した一覧。この環境では
 * Gradle ビルドが実行できず自動生成ツール (Google の oss-licenses-plugin 等) を
 * 動かせないため、静的なリストとして手作業で維持する。推移的依存の完全な列挙
 * ではなく、実際に APK に同梱される主要な直接依存が対象。ビルドツール専用の
 * 依存 (AGP, Kotlin コンパイラプラグイン, KSP, detekt 等) は APK に含まれない
 * ため対象外。依存追加/更新時はこのリストも合わせて更新すること。
 */
enum class LicenseType(val displayName: String, val fullText: String) {
    APACHE_2_0("Apache License 2.0", ApacheLicenseText.FULL),
    EPL_1_0("Eclipse Public License 1.0", EplLicenseText.FULL),
    ANDROID_SDK(
        "Android Software Development Kit License Agreement",
        "Google の Android SDK 利用規約に基づき配布されています。全文は " +
            "https://developer.android.com/studio/terms を参照してください。",
    ),
}

data class LicenseEntry(
    val name: String,
    val version: String,
    val license: LicenseType,
)

object OssLicenses {
    val entries: List<LicenseEntry> = listOf(
        LicenseEntry("AndroidX Core KTX", "1.15.0", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX Lifecycle", "2.8.7", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX Activity Compose", "1.9.3", LicenseType.APACHE_2_0),
        LicenseEntry("Jetpack Compose (BOM)", "2026.04.00", LicenseType.APACHE_2_0),
        LicenseEntry("Material3", "1.3.1", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX Navigation Compose", "2.8.5", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX DataStore", "1.1.1", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX Room", "2.6.1", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX WorkManager", "2.10.0", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX CameraX", "1.4.1", LicenseType.APACHE_2_0),
        LicenseEntry("AndroidX Glance", "1.1.1", LicenseType.APACHE_2_0),
        LicenseEntry("Kotlin Coroutines", "1.9.0", LicenseType.APACHE_2_0),
        LicenseEntry("kotlinx.serialization", "1.7.3", LicenseType.APACHE_2_0),
        LicenseEntry("Ktor Client", "3.0.2", LicenseType.APACHE_2_0),
        LicenseEntry("Dagger Hilt", "2.52", LicenseType.APACHE_2_0),
        LicenseEntry("Coil3", "3.1.0", LicenseType.APACHE_2_0),
        LicenseEntry("Kotest", "5.9.1", LicenseType.APACHE_2_0),
        LicenseEntry("Turbine", "1.2.0", LicenseType.APACHE_2_0),
        LicenseEntry("JUnit4", "4.13.2", LicenseType.EPL_1_0),
        LicenseEntry("ML Kit Barcode Scanning", "17.3.0", LicenseType.ANDROID_SDK),
        LicenseEntry("Play Services (ML Kit code scanner)", "16.1.0", LicenseType.ANDROID_SDK),
        LicenseEntry("Play Billing Library", "7.1.1", LicenseType.ANDROID_SDK),
    ).sortedBy { it.name }
}
