package io.github.shizukutanaka.popcoon.feature.ai

import io.github.shizukutanaka.popcoon.BuildConfig
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.core.CurrencyFormatter
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claude API と統合して「この商品を買うべきか」を自然言語で説明。
 *
 * キャッシュ統合 (AdviceCache):
 *  - 同一商品 + 同スコアバケット → キャッシュヒット → API 不要
 *  - 24時間 TTL / LRU 最大 100 件
 *  - キャッシュなし時のみ Claude API を呼ぶ
 *  - 1日 100ユーザー × 5閲覧 = 500 API call が
 *    キャッシュヒット率 60% で 200 call まで削減 (コスト 60% 減)
 *
 * プライバシー:
 *  - 送信するのは価格・スコア・商品タイトルのみ
 *  - 個人情報 / 端末識別子は一切送らない (I5 準拠)
 */
@Singleton
class BuyingAdvisor @Inject constructor(
    private val cache: AdviceCache,
    private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY,
) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /**
     * 商品の買い時アドバイスを取得する。成功時は助言テキストを返しキャッシュする。
     *
     * **失敗時は例外を投げる** (API キー未設定 / ネットワーク失敗 / 空応答)。
     * 以前は日本語のエラー文字列を return していたが、それでは
     *  - EN/KO/ZH ロケールに日本語が漏れる
     *  - 例外メッセージが UI に露出する
     *  - 呼び出し側 (ProductDetailViewModel) のローカライズ済みエラー処理が死にコード化
     *  - エラー文字列が「有効な助言」として再キャッシュされる
     * という問題があった。表示文言のロケール解決は UI 層の責務とする。
     */
    suspend fun advise(
        product: Product,
        score: BuyTimingScorer.Score,
        userContext: String = "",
    ): String {
        // 1. キャッシュチェック
        cache.get(product, score)?.let { return it }

        require(apiKey.isNotBlank()) { "Anthropic API key not configured" }

        val systemPrompt = """
            あなたは日本のショッピング・アシスタント「Popcoon」です。
            100文字以内、簡潔な日本語で「今買うべきか」を助言してください。
            - 明確な判断 (買い / 待ち / 様子見) を示す
            - 理由を1つ挙げる
            - 敬語は最小限
            - 絵文字・装飾なし
        """.trimIndent()

        val userPrompt = buildString {
            append("商品: ${product.title}\n")
            append("価格: ${CurrencyFormatter.yen(product.totalPrice)}\n")
            append("定価: ${CurrencyFormatter.yen(product.listPrice)}\n")
            append("買い時スコア: ${score.total}/100 (${score.verdict})\n")
            append("根拠:\n")
            score.signals.filter { it.contribution != 0 }
                .forEach { append("  ${if (it.contribution > 0) "+" else ""}${it.contribution}: ${it.name}\n") }
            if (userContext.isNotEmpty()) append("\n追加文脈: $userContext")
        }

        val request = ClaudeRequest(
            model = "claude-sonnet-5",
            maxTokens = 200,
            system = systemPrompt,
            messages = listOf(ClaudeMessage("user", userPrompt)),
        )

        val advice = try {
            val response = client.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val body = response.body<ClaudeResponse>()
            body.content.firstOrNull { it.type == "text" }?.text
                ?: error("Claude response had no text content")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 診断のため記録しつつ、ローカライズは呼び出し側に委ねるため再 throw する。
            PopcoonLogger.w("BuyingAdvisor", "API 呼び出し失敗", e)
            throw e
        }

        // 2. 成功時のみキャッシュ保存
        cache.put(product, score, advice)
        return advice
    }
}

@Serializable
private data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ClaudeMessage>,
)

@Serializable
private data class ClaudeMessage(val role: String, val content: String)

@Serializable
private data class ClaudeResponse(val content: List<Content>) {
    @Serializable
    data class Content(val type: String, val text: String? = null)
}
