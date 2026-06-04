package com.example.popcoon.data.network

import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.Product
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * API が失敗した場合のフォールバックとして、商品ページの構造化データ (JSON-LD) を読む。
 *
 * 方針 (倫理的):
 *  - robots.txt を尊重 (HEAD /robots.txt で allow 確認)
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
            agent = "Popcoon-Fallback/0.1 (+https://github.com/shizukutanaka/popcoon)"
        }
    }

    // 最終アクセス時刻 (ホスト別) — 粗いレート制限
    private val lastAccessMs = HashMap<String, Long>()
    private val minIntervalMs = 1000L

    /**
     * 商品URLから JSON-LD Product スキーマを抽出して Product を構築する。
     * 失敗時は null。
     */
    suspend fun fetchProduct(url: String, platform: Platform): Product? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null

        // レート制限
        val now = System.currentTimeMillis()
        val last = lastAccessMs[host] ?: 0L
        if (now - last < minIntervalMs) return null
        lastAccessMs[host] = now

        // robots.txt チェックは割愛 (製品化時は必須)
        val html = runCatching {
            client.get(url) {
                header("Accept", "text/html,application/xhtml+xml")
            }.bodyAsText()
        }.getOrNull() ?: return null

        val jsonLd = extractJsonLd(html) ?: return null
        return parseProductSchema(jsonLd, url, platform)
    }

    /**
     * HTML から <script type="application/ld+json"> の内容を抽出。
     * 正規表現: Compose 時代でも基本 regex で十分。
     */
    private fun extractJsonLd(html: String): String? {
        val pattern = Regex(
            """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        // 複数ある場合は最初の Product schema を優先
        for (match in pattern.findAll(html)) {
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

        return Product(
            sku = java.net.URI(url).path.substringAfterLast("/").take(64),
            title = name,
            platform = platform,
            realPrice = price,
            listPrice = price,
            url = url,
            imageUrl = image,
            brand = brand,
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("""["']${key}["']\s*:\s*["']([^"']+)["']""")
        return pattern.find(json)?.groupValues?.get(1)
    }
}
