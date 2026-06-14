package com.example.popcoon.data.network

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.ktor.http.isSuccess
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * API が失敗した場合のフォールバックとして、商品ページの構造化データ (JSON-LD) を読む。
 *
 * 方針 (倫理的):
 *  - robots.txt を尊重 (GET /robots.txt を取得・キャッシュし allow 確認)
 *  - レート制限 (1 req/sec 以下)
 *  - User-Agent 明示 (Popcoon-Fallback/0.1 +https://github.com/shizukutanaka/popcoon)
 *  - 個別商品ページのみ (検索結果ページのスクレイプは禁止)
 *  - HTML DOM 解析は行わず、ページ内の <script type="application/ld+json"> のみを読む
 *
 * これは「公開された構造化メタデータの読み取り」であり、隠れた情報への
 * 無断アクセスではない。schema.org で EC サイト側が提供しているデータ。
 */
class FallbackScraper {

    private val client = HttpClient {
        install(UserAgent) {
            agent = USER_AGENT
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 10_000
        }
    }

    // 最終アクセス時刻 (ホスト別) — 粗いレート制限。複数コルーチンから触るので並行安全に。
    private val lastAccessMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val minIntervalMs = 1000L

    // robots.txt 本文のホスト別キャッシュ (取得不能時は "" を格納 = 全許可扱い)。
    private val robotsCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // キー別の抽出用 Regex キャッシュ (呼び出しごとの再コンパイルを回避)。
    private val keyPatternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    companion object {
        const val USER_AGENT =
            "Popcoon-Fallback/0.1 (+https://github.com/shizukutanaka/popcoon)"

        // JSON-LD 抽出パターンは定数なので 1 度だけコンパイルする。
        private val JSON_LD_PATTERN = Regex(
            """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    /**
     * 商品URLから JSON-LD Product スキーマを抽出して Product を構築する。
     * 失敗時は null。
     */
    suspend fun fetchProduct(url: String, platform: Platform): Product? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"

        // レート制限を先に適用 (robots.txt 取得もこのゲートの内側に収める)。
        // read-check-write を compute で atomic に行い、同一ホストへの同時アクセスが
        // 両方ゲートを通過してしまう競合を防ぐ。
        val now = System.currentTimeMillis()
        val updated = lastAccessMs.compute(host) { _, last ->
            if (last == null || now - last >= minIntervalMs) now else last
        }
        if (updated != now) return null  // 直近アクセスが近すぎる → スキップ

        // robots.txt を尊重 (取得不能時は許可、明示 Disallow は遵守)
        if (!isPathAllowedByRobots(uri, path)) return null

        val html = runCatching {
            val resp = client.get(url) {
                header("Accept", "text/html,application/xhtml+xml")
            }
            if (!resp.status.isSuccess()) return@runCatching null
            resp.bodyAsText()
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            null
        } ?: return null

        val jsonLd = extractJsonLd(html) ?: return null
        return parseProductSchema(jsonLd, url, platform)
    }

    /**
     * robots.txt を取得・キャッシュして対象パスの許可可否を返す。
     * ネットワーク失敗・robots.txt 不存在の場合は許可 (true)。
     */
    private suspend fun isPathAllowedByRobots(uri: java.net.URI, path: String): Boolean {
        val host = uri.host ?: return true
        // 取得失敗・不存在は "" を格納 (= 全許可)。ConcurrentHashMap は null 値不可のため空文字で表現。
        val robotsBody = robotsCache[host] ?: run {
            val scheme = uri.scheme ?: "https"
            val body = runCatching {
                client.get("$scheme://$host/robots.txt").bodyAsText()
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            } ?: ""
            robotsCache[host] = body
            body
        }
        return RobotsTxt.isAllowed(robotsBody, path, USER_AGENT)
    }

    /**
     * HTML から <script type="application/ld+json"> の内容を抽出。
     * 正規表現: Compose 時代でも基本 regex で十分。
     */
    private fun extractJsonLd(html: String): String? {
        // 複数ある場合は最初の Product schema を優先
        for (match in JSON_LD_PATTERN.findAll(html)) {
            val content = match.groupValues[1].trim()
            if (content.contains("\"@type\"") &&
                (content.contains("Product") || content.contains("IndividualProduct"))) {
                return content
            }
        }
        return null
    }

    /** 極小パーサ: kotlinx.serialization 不使用 (依存削減) */
    private fun parseProductSchema(
        json: String, url: String, platform: Platform,
    ): Product? {
        // キーだけを取り出す粗いパーサ
        val name = extractJsonString(json, "name") ?: return null
        val priceStr = extractJsonString(json, "price")
            ?: extractJsonString(json, "lowPrice") ?: "0"
        val price = priceStr.replace(",", "").toDoubleOrNull()?.toLong() ?: 0L
        val image = extractJsonString(json, "image")
        val brand = extractJsonString(json, "brand")
        // schema.org Offer.availability から在庫を復元 (在庫切れ系 → stockCount=0)。
        val availability = extractJsonString(json, "availability")
        // schema.org countryOfOrigin から原産国を復元 → EcoEthicsScorer 用に ISO-2 へ正規化。
        // これが無いと originCountry は常に null で eco スコアが表示されない (機能が死ぬ)。
        val origin = normalizeOriginCountry(extractJsonString(json, "countryOfOrigin"))
        // schema.org gtin13/gtin/gtin8 から JAN/バーコードを復元 → ProductMatcher の最優先一致用。
        // これが無いと janCode は常に null で「バーコード完全一致」の名寄せ経路が死ぬ。
        val jan = normalizeGtin(
            extractJsonString(json, "gtin13")
                ?: extractJsonString(json, "gtin")
                ?: extractJsonString(json, "gtin8"),
        )

        return Product(
            sku = java.net.URI(url).path.substringAfterLast("/").take(64),
            title = name,
            platform = platform,
            realPrice = price,
            listPrice = price,
            url = url,
            imageUrl = image,
            brand = brand,
            originCountry = origin,
            janCode = jan,
            stockCount = stockFromAvailability(availability),
        )
    }

    internal fun extractJsonString(json: String, key: String): String? {
        // キーごとに Regex を 1 度だけコンパイルしてキャッシュする (name/price/image 等の固定キー)。
        // (?:[^"\\]|\\.)*: バックスラッシュエスケープ (\", \\) を含む値に対応。
        // [^"\\] (シングルクォートを除外しない) により "John's Store" のようなアポストロフィ入り値も正しく抽出。
        val pattern = keyPatternCache.getOrPut(key) {
            Regex("""["']$key["']\s*:\s*["']((?:[^"\\]|\\.)*)["']""")
        }
        return pattern.find(json)?.groupValues?.get(1)
    }
}
