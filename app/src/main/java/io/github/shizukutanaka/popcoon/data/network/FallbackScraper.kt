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

        /**
         * robots.txt が「取得不能」(429 / 5xx) のときにキャッシュする合成 robots.txt。
         *
         * 専用の状態型を増やさず、**全面禁止を意味する robots.txt そのもの**を入れることで
         * 既存の [RobotsTxt.isAllowed] をそのまま通す。判定経路が 1 本のままなので、
         * 「禁止状態だけ別扱いにして片方の分岐を直し忘れる」余地が無い。
         */
        internal const val DENY_ALL_ROBOTS = "User-agent: *\nDisallow: /"

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

        // JSON-LD の Product スキーマが無いページはそもそも商品ページでない可能性が高い
        // (検索結果・エラーページ等) ため、ここは要求したままにする。多戦略化するのは
        // 「商品ページだと確認できたうえで price フィールドだけ取れない」ケース
        // (parseProductSchema 内の HTML フォールバック)。
        val jsonLd = extractJsonLd(html) ?: return null
        return parseProductSchema(jsonLd, html, url, platform)
    }

    /**
     * robots.txt を取得・キャッシュして対象パスの許可可否を返す。
     *
     * **HTTP ステータスで扱いを変える** (RFC 9309 §2.3.1)。以前はステータスを一切見ずに
     * `bodyAsText()` をそのまま robots.txt として解釈していたため、サイトが
     * 503 / 429 を返して「今は来ないでくれ」と明示している状況でも、
     * エラーページ本文が「規則なし」とパースされて **全許可** になり、そのまま
     * スクレイピングを続けていた。相手が最も負荷を受けている瞬間に叩き続ける挙動で、
     * robots.txt を尊重すると謳っている以上いちばん避けるべきケースだった。
     *
     *  - 2xx        → 本文をそのまま規則として解釈
     *  - 429 / 5xx  → **全面禁止**として扱う (§2.3.1.4「unavailable」)
     *  - その他 4xx → robots.txt 無し = 全許可 (§2.3.1.3)
     *  - ネットワーク失敗 → 全許可 (従来どおり。この場合は続く本文 GET も失敗するため
     *    実質スクレイピングは進まない)
     *
     * 禁止状態はプロセス生存中キャッシュされる。次回起動で再取得されるので、
     * 一時的な 5xx で恒久的に諦めることはない (保守的側に倒している)。
     */
    private suspend fun isPathAllowedByRobots(uri: java.net.URI, path: String): Boolean {
        val host = uri.host ?: return true
        // 取得失敗・不存在は "" を格納 (= 全許可)。ConcurrentHashMap は null 値不可のため空文字で表現。
        val robotsBody = robotsCache[host] ?: run {
            val scheme = uri.scheme ?: "https"
            val body = runCatching {
                val resp = client.get("$scheme://$host/robots.txt")
                when {
                    resp.status.isSuccess() -> resp.bodyAsText()
                    resp.status.value == 429 || resp.status.value >= 500 -> DENY_ALL_ROBOTS
                    else -> ""
                }
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
        json: String, html: String, url: String, platform: Platform,
    ): Product? {
        // キーだけを取り出す粗いパーサ
        val name = extractJsonString(json, "name") ?: return null
        // price は文字列 ("1980") と数値 (1980 / 38.99) の両形式があり得る (schema.org / Google 公式例)。
        // 文字列マッチが外れたら数値フォールバックを試す。
        //
        // JSON-LD で取れない場合は **HTML 側の別戦略** (microdata / OpenGraph / Twitter card) へ
        // フォールバックする (2026-08 リサーチ: PriceGhost の多戦略抽出に対応)。
        // PA-API 5.0 廃止後、Amazon 商品ページはこの経路が実質唯一のデータ源のため、
        // JSON-LD 単独では取りこぼしがそのまま機能停止になる。
        //
        // **どの戦略でも取れなければ null を返して失敗にする**。以前は `?: "0"` で
        // realPrice=0 の Product を捏造しており、refresh() の「失敗時は null」という
        // 明文化された契約 (ProductRepository.refresh の KDoc) を破っていた。0 円の商品は
        // 価格履歴に記録されると常に「史上最安値」となり、price_below 系アラートを
        // 無条件で発火させ、ATL 判定と予測パイプラインを汚染する。
        val price = parsePriceToLong(
            extractJsonString(json, "price")
                ?: extractJsonNumber(json, "price")
                ?: extractJsonString(json, "lowPrice")
                ?: extractJsonNumber(json, "lowPrice"),
        ) ?: extractHtmlPrice(html) ?: return null
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
            sku = skuFromUrl(url),
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

    /**
     * 商品 URL の最終パスセグメントを sku として抽出する。
     *
     * trimEnd('/'): 楽天の正規 URL は "https://item.rakuten.co.jp/$shop/$item/" と
     * 末尾スラッシュ付きで構築される (UrlClassifier.kt)。trim せずに substringAfterLast("/")
     * すると末尾スラッシュの直後 (=空文字列) を拾ってしまい、楽天商品の sku が常に "" になって
     * 全ての楽天商品が同じ productKey ("rakuten:") に潰れ、ウォッチリストの別商品が互いを
     * 上書きしていた (機能過不足監査で発見)。
     */
    internal fun skuFromUrl(url: String): String =
        java.net.URI(url).path.trimEnd('/').substringAfterLast("/").take(64)

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
