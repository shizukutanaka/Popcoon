package io.github.shizukutanaka.popcoon.feature.crash

import android.content.Context
import android.os.Build
import io.github.shizukutanaka.popcoon.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * プライバシー優先のクラッシュ報告。
 *
 * 業界標準ツール (Crashlytics 等) と異なる方針:
 *  - **Opt-in only**: ユーザー明示同意なしには 1 byte も送信しない
 *  - **PII 自動除去**: スタックトレースから個人情報パターンを削除
 *  - **集約 only**: ユーザー識別子を持たない (1 セッション = 1 匿名 ID、再起動で破棄)
 *  - **自前 backend**: Cloudflare Workers (third-party SDK 不要)
 *  - **オフラインキュー**: ネット復旧時に再送
 *
 * 業界目標: 99% crash-free sessions
 */
class PrivacyCrashReporter(
    private val context: Context,
    private val backendUrl: String = BuildConfig.BACKEND_URL,
) {
    @Serializable
    private data class CrashReport(
        val app_version: String,
        val android_version: Int,
        val device_model: String,
        val timestamp: String,
        val exception_class: String,
        val sanitized_stack: String,
        val build_type: String,
        val session_id: String,    // ランダム、ユーザー紐付けなし
    )

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 10_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val sessionId = generateSessionId()

    /** ユーザーが設定で有効化した場合のみ true */
    var enabled: Boolean = false

    /**
     * インストール時の Application.onCreate() で呼ぶ。
     * UncaughtExceptionHandler を仕掛ける。
     *
     * 設計: クラッシュ時点ではプロセスがほぼ即座に終了するため、ネットワーク送信を
     * fire-and-forget しても完了しない。代わりに構造化レポート (CrashReport JSON) を
     * ローカルに永続化し、次回起動時の `uploadPendingCrashes()` で確実に送る
     * (業界標準の永続化→次回起動送信パターン)。
     */
    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // クラッシュレポートをローカルに永続化 (オプトアウト時もデバッグ用に保持)。
            // 送信は次回起動時の uploadPendingCrashes() に委ねる。
            saveLocally(throwable)
            // 既存の handler に委譲 (システムのクラッシュダイアログ表示)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** throwable から送信用の構造化レポートを組み立てる (PII はサニタイズ済み)。 */
    private fun buildReport(throwable: Throwable): CrashReport {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return CrashReport(
            app_version = BuildConfig.VERSION_NAME ?: "unknown",
            android_version = Build.VERSION.SDK_INT,
            device_model = "${Build.MANUFACTURER} ${Build.MODEL}",
            timestamp = Instant.now().toString(),
            exception_class = throwable::class.qualifiedName ?: "Unknown",
            sanitized_stack = sanitize(sw.toString()).take(8000),
            build_type = if (BuildConfig.DEBUG) "debug" else "release",
            session_id = sessionId,
        )
    }

    private fun sanitize(stack: String): String = sanitizeStack(stack)

    companion object {
        /**
         * スタックトレース・ログから個人情報を除去する純関数。
         * PopcoonLogger の共通パターン + クラッシュログ固有のファイルパスパターン。
         * `internal` 可視性はテスト用。
         */
        internal fun sanitizeStack(text: String): String = text
            // メールアドレス
            .replace(Regex("""[\w.-]+@[\w.-]+\.\w+"""), "[email]")
            // URL クエリパラメータ（マルチパラメータ対応: ?k=v&k2=v2）
            .replace(Regex("""([?&][^=\s&#]+=)[^\s&#"')]+"""), "$1[redacted]")
            // AWS アクセスキー ID
            .replace(Regex("""AKIA[0-9A-Z]{16}"""), "[aws-key]")
            // Authorization ヘッダ (任意スキーム)
            .replace(Regex("""(?i)(authorization\s*[:=]\s*)(?:\w+\s+)?[^\s"',;]+"""), "$1[redacted]")
            // api_key / secret / token / password / credential の値
            .replace(
                Regex("""(?i)("?\w*(?:api[_-]?key|secret|token|password|credential)\w*"?\s*[:=]\s*)["']?[^\s"',&}]+"""),
                "$1[redacted]",
            )
            // IPv4
            .replace(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""), "[ip]")
            // 電話番号 (日本 国内/国際)
            .replace(Regex("""\b\+?81[-\s]?\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
            .replace(Regex("""\b0\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
            // Android ファイルパスのユーザー名部分
            .replace(Regex("""/data/user/0/[^/]+/files/[^/\s]+"""), "/data/user/0/[pkg]/files/[user]")
            .replace(Regex("""/storage/emulated/\d+/[^/\s]+"""), "/storage/emulated/[u]/[user]")
    }

    private fun saveLocally(throwable: Throwable) {
        runCatching {
            val dir = File(context.filesDir, "crashes").apply { mkdirs() }
            // 構造化レポート (CrashReport JSON) として保存。次回起動時にそのまま送信できる。
            val file = File(dir, "crash_${System.currentTimeMillis()}.json")
            file.writeText(json.encodeToString(CrashReport.serializer(), buildReport(throwable)))
            // 30件以上は古い順に削除 (ストレージ消費抑制)
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            if (files.size > 30) {
                files.take(files.size - 30).forEach { it.delete() }
            }
        }
    }

    /**
     * セッション ID: 起動ごとにランダム、永続化しない。
     * ユーザー紐付け不可能。
     */
    private fun generateSessionId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    /**
     * 永続化済みのクラッシュレポートを送信する。
     * オプトイン時、Application 起動時に呼ぶ (前回セッションのクラッシュを確実に送る)。
     * 各ファイルは CrashReport JSON。デコードに失敗したファイル (旧形式等) は破棄する。
     */
    suspend fun uploadPendingCrashes() {
        if (!enabled) return
        val dir = File(context.filesDir, "crashes")
        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val report = runCatching {
                json.decodeFromString(CrashReport.serializer(), file.readText())
            }.getOrNull()
            if (report == null) {
                file.delete() // 壊れた/旧形式のファイルは再送し続けないよう破棄
                return@forEach
            }
            runCatching {
                client.post("$backendUrl/v1/crash") {
                    contentType(ContentType.Application.Json)
                    setBody(report)
                }
            }.onSuccess { file.delete() }
             .onFailure { e ->
                 if (e is kotlinx.coroutines.CancellationException) throw e
             }
        }
    }

    fun clearLocalCrashes() {
        runCatching {
            File(context.filesDir, "crashes").deleteRecursively()
        }
    }
}
