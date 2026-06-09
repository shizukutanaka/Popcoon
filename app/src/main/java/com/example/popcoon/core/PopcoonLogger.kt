package com.example.popcoon.core

import android.util.Log
import com.example.popcoon.BuildConfig

/**
 * 構造化ロガー。
 *
 * デフォルトの `Log.d` / `println` の問題:
 *  - リリースビルドでも出力される (情報漏洩リスク)
 *  - フィルタリング困難
 *  - PII を意図せず含む可能性
 *
 * このロガー:
 *  - リリースビルド時は INFO 以上のみ Logcat に出力
 *  - `tag` を Class クラス名から自動取得
 *  - PII フィルタ統合 (`PrivacyCrashReporter` と同じ regex)
 *  - 実装は薄い (依存追加せず標準 Log にラップ)
 */
object PopcoonLogger {

    private const val MAX_TAG_LENGTH = 23

    enum class Level(val priority: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
    }

    /** リリースビルドでは INFO 以上のみ出力 */
    private val minLevel: Level
        get() = if (BuildConfig.DEBUG) Level.VERBOSE else Level.INFO

    fun v(tag: Any, message: String) = log(Level.VERBOSE, resolveTag(tag), message, null)
    fun d(tag: Any, message: String) = log(Level.DEBUG, resolveTag(tag), message, null)
    fun i(tag: Any, message: String) = log(Level.INFO, resolveTag(tag), message, null)
    fun w(tag: Any, message: String, throwable: Throwable? = null) =
        log(Level.WARN, resolveTag(tag), message, throwable)
    fun e(tag: Any, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, resolveTag(tag), message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level.priority < minLevel.priority) return
        // throwable のスタックトレース/例外メッセージにも PII/秘密が混入し得るため、
        // 生の Throwable を渡さず、サニタイズしたテキストとして連結する。
        val sanitized = if (throwable != null) {
            sanitize(message) + "\n" + sanitize(throwable.stackTraceToString())
        } else {
            sanitize(message)
        }
        when (level) {
            Level.VERBOSE -> Log.v(tag, sanitized)
            Level.DEBUG -> Log.d(tag, sanitized)
            Level.INFO -> Log.i(tag, sanitized)
            Level.WARN -> Log.w(tag, sanitized)
            Level.ERROR -> Log.e(tag, sanitized)
        }
    }

    private fun resolveTag(tag: Any): String {
        val raw = when (tag) {
            is String -> tag
            else -> tag::class.java.simpleName
        }
        return if (raw.length > MAX_TAG_LENGTH) raw.take(MAX_TAG_LENGTH) else raw
    }

    /**
     * PII / 秘密情報フィルタ。
     * `internal` 可視性はテストからの直接検証用 (本来は実装詳細)。
     */
    internal fun sanitize(message: String): String {
        return message
            .replace(Regex("""[\w.-]+@[\w.-]+\.\w+"""), "[email]")
            // URL クエリパラメータの「値」を全て伏せる (?k=v&k2=v2 のマルチパラメータ対応)
            .replace(Regex("""([?&][^=\s&#]+=)[^\s&#"')]+"""), "$1[redacted]")
            // AWS アクセスキー ID
            .replace(Regex("""AKIA[0-9A-Z]{16}"""), "[aws-key]")
            // Authorization ヘッダ (任意スキーム Bearer/Basic/Digest の資格情報まで伏せる)
            .replace(
                Regex("""(?i)(authorization\s*[:=]\s*)(?:\w+\s+)?[^\s"',;]+"""),
                "$1[redacted]",
            )
            // api_key / secret / token / password / credential の値。
            // key=value / key: value / JSON "key":"value" / 接頭辞付き MY_SECRET_KEY=... に対応
            .replace(
                Regex(
                    """(?i)("?\w*(?:api[_-]?key|secret|token|password|credential)\w*"?""" +
                        """\s*[:=]\s*)["']?[^\s"',&}]+""",
                ),
                "$1[redacted]",
            )
            .replace(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""), "[ip]")
            .replace(Regex("""\b\+?81[-\s]?\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
    }
}
