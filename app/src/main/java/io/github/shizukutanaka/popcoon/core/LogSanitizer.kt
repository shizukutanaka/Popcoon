package io.github.shizukutanaka.popcoon.core

/**
 * ログ・スタックトレースから PII / 秘密情報を除去する純関数。
 *
 * **単一の実装**であることが要件。以前は同じ規則が 3 か所に複製されていた:
 *  - `PrivacyCrashReporter.sanitizeStack` (10 パターン、最新)
 *  - `PopcoonLogger.sanitize` (**7 パターン、古い**)
 *  - `backend/src/index.ts` の `sanitizePii` (10 パターン、最新)
 *
 * `PopcoonLogger` の KDoc は「PII フィルタ統合 (`PrivacyCrashReporter` と同じ regex)」と
 * 宣言していたが実際には同じではなく、**本番の全ログが通る経路**で次が素通りしていた:
 *  - 国内電話番号 (`090-1234-5678` / `03-1234-5678`) — `+81` 形式しか見ていなかった
 *  - `/data/user/0/<pkg>/files/<user>` / `/storage/emulated/0/<user>` のパス
 *  - `api_key="secret"` の開き引用符が消える非冪等な置換 (backend は
 *    「サニタイズしても変わらない = PII 無し」で二重チェックするため、
 *    冪等でないと正当なレポートが 400 で全拒否される)
 *
 * backend (TypeScript) の `sanitizePii` と **同一規則**であること自体を
 * `popcoon-tdd/kotlin_parity/run_sanitizer.sh` が共有コーパスで実行検証する
 * (期待値は正規表現から手導出したもので、どちらの実装の出力でもない)。
 * 規則を変えるときは Kotlin / TypeScript / コーパスを必ず同時に更新すること。
 *
 * Android に依存しないので `run_compile_core.sh` の実コンパイル対象に入る。
 */
object LogSanitizer {

    fun sanitize(text: String): String = text
        // メールアドレス
        .replace(Regex("""[\w.-]+@[\w.-]+\.\w+"""), "[email]")
        // URL クエリパラメータ (マルチパラメータ対応: ?k=v&k2=v2)
        .replace(Regex("""([?&][^=\s&#]+=)[^\s&#"')]+"""), "$1[redacted]")
        // AWS アクセスキー ID
        .replace(Regex("""AKIA[0-9A-Z]{16}"""), "[aws-key]")
        // Authorization ヘッダ (任意スキーム)
        .replace(Regex("""(?i)(authorization\s*[:=]\s*)(?:\w+\s+)?[^\s"',;]+"""), "$1[redacted]")
        // api_key / secret / token / password / credential の値。
        // 開き引用符は **キャプチャ側に含める**: capture の外に置くと
        // `api_key="secret"` → `api_key=[redacted]"` と開き引用符だけ消え、
        // 出力が入力として安定しない (冪等でない)。
        .replace(
            Regex(
                """(?i)("?\w*(?:api[_-]?key|secret|token|password|credential)\w*"?""" +
                    """\s*[:=]\s*["']?)[^\s"',&}]+""",
            ),
            "$1[redacted]",
        )
        // IPv4
        .replace(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""), "[ip]")
        // 電話番号 (日本 国際/国内)
        .replace(Regex("""\b\+?81[-\s]?\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
        .replace(Regex("""\b0\d{1,4}[-\s]?\d{1,4}[-\s]?\d{4}\b"""), "[tel]")
        // Android ファイルパスのユーザー名部分
        .replace(Regex("""/data/user/0/[^/]+/files/[^/\s]+"""), "/data/user/0/[pkg]/files/[user]")
        .replace(Regex("""/storage/emulated/\d+/[^/\s]+"""), "/storage/emulated/[u]/[user]")
}
