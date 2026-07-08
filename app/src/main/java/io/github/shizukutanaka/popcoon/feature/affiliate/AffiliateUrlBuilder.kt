package io.github.shizukutanaka.popcoon.feature.affiliate

import android.net.Uri
import io.github.shizukutanaka.popcoon.BuildConfig
import io.github.shizukutanaka.popcoon.data.model.Platform

/**
 * 商品URLにアフィリエイトタグを注入。
 *
 * 景品表示法 8 条に基づく表示義務: アフィリエイトリンクであることを UI で明示する。
 * (「#ad」バッジ、設定画面での開示、プライバシーポリシーでの記述)
 *
 * 無効化:
 *  - ユーザーが Premium 購読したら injection を止める (UI で選択可能)
 *  - 設定で「アフィリエイトリンクを使わない」を ON にすると生 URL を返す
 */
object AffiliateUrlBuilder {

    /** @param optOut true なら素の URL を返す (injection なし) */
    fun build(platform: Platform, rawUrl: String, optOut: Boolean = false): String {
        if (optOut) return rawUrl
        return when (platform) {
            Platform.AMAZON -> buildAmazon(rawUrl)
            Platform.RAKUTEN -> buildRakuten(rawUrl)
            Platform.YAHOO -> buildYahoo(rawUrl)
        }
    }

    private fun buildAmazon(url: String): String {
        val tag = BuildConfig.AMAZON_PARTNER_TAG
        if (tag.isBlank()) return url
        val uri = Uri.parse(url)
        val builder = uri.buildUpon().clearQuery()
        // 既存 query を維持しつつ tag だけ入れ替え
        uri.queryParameterNames.filter { it != "tag" }.forEach { k ->
            uri.getQueryParameter(k)?.let { builder.appendQueryParameter(k, it) }
        }
        builder.appendQueryParameter("tag", tag)
        return builder.build().toString()
    }

    private fun buildRakuten(url: String): String {
        val raAffiliateId = BuildConfig.RAKUTEN_AFFILIATE_ID ?: return url
        if (raAffiliateId.isBlank()) return url
        // hb.afl.rakuten.co.jp 経由にラップ。
        // 商品 URL は pc= の値なので必ず percent-encode する。生のまま入れると
        // 商品 URL 内の ?/&/# がラッパー URL のクエリとして解釈され、リンクが壊れる。
        return "https://hb.afl.rakuten.co.jp/hgc/$raAffiliateId/?pc=${Uri.encode(url)}"
    }

    private fun buildYahoo(url: String): String {
        val sid = BuildConfig.YAHOO_SID
        if (sid.isNullOrBlank()) return url
        // Value Commerce 経由 (sid パラメータ)
        val sep = if (url.contains("?")) "&" else "?"
        return "${url}${sep}sc_e=sy_shp_web_search&sid=$sid"
    }
}
