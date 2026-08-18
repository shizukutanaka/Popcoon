package io.github.shizukutanaka.popcoon.feature.export

import kotlinx.serialization.Serializable

/**
 * バックアップ専用のシリアライズ可能 DTO。`WatchlistItem` の Room アノテーションと分離する
 * (`PriceRecord`/`PriceCacheEntry` と同じ既存方針: Room スキーマとシリアライズ形式を分離し、
 * 将来の Room マイグレーションがバックアップ形式に影響しないようにする)。
 *
 * `previousInStock` / `pendingPrice` (どちらも同期の内部状態) は意図的に含めない —
 * 復元後は次回同期で自然に再構築される。
 *
 * **Room に依存しないファイルとして独立させている**のは、下の [normalizedOrNull] が
 * `run_compile_core.sh` の実コンパイル対象に入るようにするため。復元は外部から与えられた
 * ファイルを DB へ書き込む唯一の経路で、ここの検証ロジックが壊れると気付きにくい。
 */
@Serializable
internal data class WatchlistBackupEntry(
    val productKey: String,
    val sku: String,
    val title: String,
    val platform: String,
    val realPrice: Long,
    val listPrice: Long,
    val url: String,
    val imageUrl: String? = null,
    val addedAt: Long = 0,
    val targetPrice: Long? = null,
    val addedPrice: Long = 0,
    val stockAlertEnabled: Boolean = false,
    val tag: String? = null,
)

/**
 * 復元前の検証と正規化。復元できないエントリは null を返す。
 *
 * バックアップ JSON は**ユーザーの端末外を経由して戻ってくる**唯一の入力
 * (共有・クラウド保存・手編集がありうる) なので、DB へ入れる前にここで一度濾す。
 *
 * 方針 — **「壊れた行だけを落とし、それ以外は必ず復元する」**:
 *  - `productKey` が空白のみ: 主キーが壊れるので **除外**。これだけが除外理由。
 *  - 負の価格: 0 へ丸める。負の金額は表示・計算のどこでも意味を持たない。
 *  - `targetPrice` が 0 以下: 「未設定」(null) として扱う。`PriceAlertEvaluator` が
 *    `targetPrice > 0` を要求するため、0 のまま入れても永久に発火しない死んだ設定になる。
 *
 * `realPrice == 0` は **除外しない**。復元の目的はウォッチ対象そのものを取り戻すことで、
 * 価格は次回同期で上書きされる。読み出し側は 2026-08 の一斉修正で ¥0 を無視するよう
 * 揃えてあるので、0 のまま入っても判定を壊さない。ここで弾くとユーザーは
 * 「バックアップしたのに商品が消えた」という、より重い損失を被る。
 */
internal fun WatchlistBackupEntry.normalizedOrNull(): WatchlistBackupEntry? {
    if (productKey.isBlank()) return null
    return copy(
        realPrice = realPrice.coerceAtLeast(0),
        listPrice = listPrice.coerceAtLeast(0),
        addedPrice = addedPrice.coerceAtLeast(0),
        targetPrice = targetPrice?.takeIf { it > 0 },
    )
}
