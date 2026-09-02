package io.github.shizukutanaka.popcoon.ui.components

import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import java.time.Instant

// PriceChart.kt (Compose Canvas) から切り出した純ロジック。同居していると Compose 依存に
// 巻き込まれて実コンパイルも kotest (PriceChartTest) も走らなかった。
// 同一パッケージなので呼び出し側は無変更。

/** 期間切替 (1週間/1ヶ月/全期間)。全 records は保持したまま表示範囲だけを絞る。 */
enum class PriceChartRange(val days: Int?) {
    WEEK(7), MONTH(30), ALL(null)
}

/** 選択期間で records を絞り込む (pure function、テスト容易)。ALL は絞り込まない。 */
internal fun filterByRange(records: List<PriceRecord>, chartRange: PriceChartRange): List<PriceRecord> {
    val days = chartRange.days ?: return records
    val cutoff = Instant.now().minusSeconds(days.toLong() * 86_400)
    return records.filter { it.recordedAt >= cutoff }
}

/**
 * 描画対象のレコードを時系列順で返す (pure function、テスト容易)。
 *
 * `realPrice <= 0` は取得失敗を 0 円として記録した汚染レコードであり、実際に成立した
 * 価格ではない。混ぜるとグラフの下端が常に ¥0 に張り付いて実際の変動幅が潰れ、
 * a11y の読み上げも「期間最安 0円」になり、先頭/末尾が ¥0 だと傾向 (上昇/下降) の
 * 判定まで反転する。`WidgetVerdict` / `WatchlistPriceDelta` は既に同じ規則で
 * 0 以下を除外しており、グラフだけが例外になっていた。
 */
internal fun plottableRecords(records: List<PriceRecord>): List<PriceRecord> =
    records.filter { it.realPrice > 0 }.sortedBy { it.recordedAt }
