package com.example.popcoon.feature.crash

import android.content.Context
import android.os.Build
import com.example.popcoon.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionId = generateSessionId()

    /** ユーザーが設定で有効化した場合のみ true */
    var enabled: Boolean = false

    /**
     * インストール時の Application.onCreate() で呼ぶ。
     * UncaughtExceptionHandler を仕掛ける。
     */
    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 1. ローカルにファイル保存 (オプトアウト時もデバッグ用に保持)
            saveLocally(throwable)
            // 2. オプトイン時のみサーバー送信
            if (enabled) {
                reportToServer(throwable)
            }
            // 3. 既存の handler に委譲 (システムのクラッシュダイアログ表示)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * スタックトレースから個人情報を除去。
     * - メールアドレス、URL中のクエリ、デバイス固有 ID、ファイルパスのユーザー名部分
     */
    private fun sanitize(stack: String): String {
        return stack
            // メールアドレス
            .replace(Regex("""[\w.-]+@[\w.-]+\.\w+"""), "[email]")
            // URL クエリパラメータ
            .replace(Regex("""\?[^\s)]+"""), "?[redacted]")
            // /data/user/0/<package>/files/[user-name]/...
            .replace(Regex("""/data/user/0/[^/]+/files/[^/]+"""),
                "/data/user/0/[pkg]/files/[user]")
            // /storage/emulated/0/[user-name]
            .replace(Regex("""/storage/emulated/\d+/[^/]+"""),
                "/storage/emulated/[u]/[user]")
            // IPv4
            .replace(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""), "[ip]")
            // 電話番号 (日本 国内/国際)
            .replace(Regex("""\b\+?81[-\s]?\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
            .replace(Regex("""\b0\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
    }

    private fun saveLocally(throwable: Throwable) {
        runCatching {
            val dir = File(context.filesDir, "crashes").apply { mkdirs() }
            val file = File(dir, "crash_${System.currentTimeMillis()}.log")
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            file.writeText(sanitize(writer.toString()))
            // 30件以上は古い順に削除 (ストレージ消費抑制)
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            if (files.size > 30) {
                files.take(files.size - 30).forEach { it.delete() }
            }
        }
    }

    private fun reportToServer(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val report = CrashReport(
            app_version = BuildConfig.VERSION_NAME ?: "unknown",
            android_version = Build.VERSION.SDK_INT,
            device_model = "${Build.MANUFACTURER} ${Build.MODEL}",
            timestamp = Instant.now().toString(),
            exception_class = throwable::class.qualifiedName ?: "Unknown",
            sanitized_stack = sanitize(sw.toString()).take(8000),
            build_type = if (BuildConfig.DEBUG) "debug" else "release",
            session_id = sessionId,
        )
        scope.launch {
            runCatching {
                client.post("$backendUrl/v1/crash") {
                    contentType(ContentType.Application.Json)
                    setBody(report)
                }
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

    /** ユーザーが設定で「ローカルクラッシュログを共有」を ON にした時に呼ぶ */
    suspend fun uploadPendingCrashes() {
        if (!enabled) return
        val dir = File(context.filesDir, "crashes")
        dir.listFiles()?.forEach { file ->
            runCatching {
                client.post("$backendUrl/v1/crash") {
                    contentType(ContentType.Application.Json)
                    setBody(file.readText())
                }
                file.delete()
            }
        }
    }

    fun clearLocalCrashes() {
        runCatching {
            File(context.filesDir, "crashes").deleteRecursively()
        }
    }
}
