package io.github.shizukutanaka.popcoon.data.network

import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.Product
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
        // robots.txt のマッチ対象は REP 仕様上 path + query。実際に GET する URL (query 付き) と
        // 一致させないと、Disallow: /*? のようなクエリ標的ルールを取りこぼし、禁止 URL を取得しうる。
        val rawPath = uri.rawPath?.ifEmpty { "/" } ?: "/"
        val path = uri.rawQuery?.let { "$rawPath?$it" } ?: rawPath

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
        // price は文字列 ("1980") と数値 (1980 / 38.99) の両形式があり得る (schema.org / Google 公式例)。
        // 文字列マッチが外れたら数値フォールバックを試す。これが無いと数値表記の商品が realPrice=0 になる。
        val priceStr = extractJsonString(json, "price")
            ?: extractJsonNumber(json, "price")
            ?: extractJsonString(json, "lowPrice")
            ?: extractJsonNumber(json, "lowPrice")
            ?: "0"
        val price = priceStr.replace(",", "").toDoubleOrNull()?.toLong() ?: 0L
        // image は単一文字列・文字列配列のいずれもあり得る (Amazon/楽天は配列が多い)。
        // 配列フォールバックが無いと配列形式の商品でサムネイルが表示されない。
        val image = extractJsonString(json, "image")
            ?: extractJsonArrayFirst(json, "image")
        // brand は文字列 ("Sony") でも Brand オブジェクト ({"name":"Sony"}) でもあり得る。
        // ネストオブジェクトのフォールバックが無いと ProductMatcher の brand 一致シグナルが死ぬ。
        val brand = extractJsonString(json, "brand")
            ?: extractJsonObjectField(json, "brand", "name")
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

    // 抽出ロジックは ktor 非依存の extractJsonLdString (JsonLdStock.kt) に集約。
    // パリティハーネスがその実関数を直接検証できるよう委譲する (正規表現の複製を排除)。
    internal fun extractJsonString(json: String, key: String): String? =
        extractJsonLdString(json, key)

    /** 引用符なし数値 (`"price": 1980`) の抽出。詳細は extractJsonLdNumber を参照。 */
    internal fun extractJsonNumber(json: String, key: String): String? =
        extractJsonLdNumber(json, key)

    /** 文字列配列の先頭要素 (`"image":["a","b"]` → `a`) の抽出。詳細は extractJsonLdArrayFirst を参照。 */
    internal fun extractJsonArrayFirst(json: String, key: String): String? =
        extractJsonLdArrayFirst(json, key)

    /** ネストオブジェクトの内側フィールド (`"brand":{"name":"Sony"}` → `Sony`)。詳細は extractJsonLdObjectField を参照。 */
    internal fun extractJsonObjectField(json: String, outerKey: String, innerKey: String): String? =
        extractJsonLdObjectField(json, outerKey, innerKey)
}
