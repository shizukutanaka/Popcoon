package io.github.shizukutanaka.popcoon.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.shizukutanaka.popcoon.MainActivity

/**
 * Popcoon ホーム画面ウィジェット。
 *
 * 同種ソフト調査:
 *  - Yahoo!ショッピング: ウィジェットで日替わりクーポンや「5のつく日」を表示
 *    → 「毎日のようにアプリを開いては今日は何が得なのかなぁと見ている」ユーザー行動を支援
 *
 * Popcoon の実装:
 *  - ウォッチリストの最安値商品トップ3を表示
 *  - 今日セール中か「次のセール日」を表示
 *  - ワンタップでアプリ起動
 *
 * Glance API (Jetpack Compose ベース) 採用:
 *  - RemoteViews より型安全
 *  - Compose の UI 知識をそのまま流用
 */
class PopcoonWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // ウォッチリスト最新価格は WidgetUpdater が SharedPreferences (widget_cache) に
        // キャッシュ済み。ここではそれを読み出す。
        val items = loadWidgetItems(context)
        val todayInfo = loadTodayInfo(context)

        provideContent {
            // GlanceTheme は Android 12+ では動的カラー (Material You)、それ未満では
            // デフォルトの M3 配色を提供する。以前は 4 色 (背景・強調・文字・控えめ) を
            // 全て固定値でハードコードしており、ランチャーのライト/ダーク・動的カラーを
            // 一切無視していた (商用リリース監査で発見)。
            GlanceTheme {
                WidgetContent(items = items, todayInfo = todayInfo)
            }
        }
    }

    private fun loadWidgetItems(context: Context): List<WidgetItem> {
        // 実際は Room / DataStore から読み取る
        // SharedPreferences にキャッシュした最新価格を使用
        val prefs = context.getSharedPreferences("widget_cache", Context.MODE_PRIVATE)
        val count = prefs.getInt("count", 0)
        return (0 until minOf(count, 3)).mapNotNull { i ->
            val title = prefs.getString("title_$i", null) ?: return@mapNotNull null
            val price = prefs.getLong("price_$i", -1L)
            val verdict = prefs.getString("verdict_$i", "NEUTRAL") ?: "NEUTRAL"
            if (price < 0) null
            else WidgetItem(title = title.take(20), price = price, verdict = verdict)
        }
    }

    private fun loadTodayInfo(context: Context): TodayInfo {
        val today = java.time.LocalDate.now()
        val sale = PopcoonWidgetLogic.todayInfo(today.dayOfMonth, today.dayOfWeek)
        // i18n: ロジックは SaleKind を返し、ラベル文字列はここでロケール解決する。
        val label = when (sale.kind) {
            PopcoonWidgetLogic.SaleKind.YAHOO_5DAY ->
                context.getString(io.github.shizukutanaka.popcoon.R.string.widget_sale_yahoo_5day)
            PopcoonWidgetLogic.SaleKind.RAKUTEN_50DAY ->
                context.getString(io.github.shizukutanaka.popcoon.R.string.widget_sale_rakuten_50day)
            PopcoonWidgetLogic.SaleKind.YAHOO_SUNDAY ->
                context.getString(io.github.shizukutanaka.popcoon.R.string.widget_sale_yahoo_sunday)
            PopcoonWidgetLogic.SaleKind.NEXT ->
                context.getString(io.github.shizukutanaka.popcoon.R.string.widget_sale_next, sale.nextDay)
        }
        return TodayInfo(label = label, isActive = sale.isActive)
    }
}

/**
 * ウィジェットの「今日のセール情報」判定 (純関数、Context 非依存)。
 * 単体テストで網羅検証する。
 *
 * i18n: 表示文字列は持たず **どのセールか (SaleKind)** だけを返す。ラベルの
 * ロケール解決は [PopcoonWidget.loadTodayInfo] が string resource で行う。
 */
internal object PopcoonWidgetLogic {

    enum class SaleKind { YAHOO_5DAY, RAKUTEN_50DAY, YAHOO_SUNDAY, NEXT }

    /** @param nextDay NEXT のときのみ意味を持つ (次回ポイントアップ日)。 */
    data class SaleInfo(val kind: SaleKind, val isActive: Boolean, val nextDay: Int = 0)

    fun todayInfo(day: Int, dow: java.time.DayOfWeek): SaleInfo = when {
        // Yahoo! 5のつく日 (5/15/25) — 共有日は高還元の Yahoo を優先表示。
        day == 5 || day == 15 || day == 25 ->
            SaleInfo(SaleKind.YAHOO_5DAY, isActive = true)
        // 楽天 5と0のつく日のうち Yahoo と重ならない日 (10/20/30)。
        // (5/15/25 は上で Yahoo に振るので、ここに 5 を入れると到達不能=デッドになる)
        day == 10 || day == 20 || day == 30 ->
            SaleInfo(SaleKind.RAKUTEN_50DAY, isActive = true)
        dow == java.time.DayOfWeek.SUNDAY ->
            SaleInfo(SaleKind.YAHOO_SUNDAY, isActive = true)
        else ->
            SaleInfo(SaleKind.NEXT, isActive = false, nextDay = nextPointDay(day))
    }

    /** 今日以降で次にポイントアップする日 (5と0のつく日)。月末を越えると翌月の 5。 */
    fun nextPointDay(today: Int): Int {
        val candidates = listOf(5, 10, 15, 20, 25, 30)
        return candidates.firstOrNull { it > today } ?: 5
    }
}

@Composable
private fun WidgetContent(items: List<WidgetItem>, todayInfo: TodayInfo) {
    // GlanceTheme.colors はランチャーのライト/ダーク・動的カラーに追従する
    // (呼び出し元 provideGlance() の GlanceTheme { } ブロック内でのみ解決可能)。
    val bgColor = GlanceTheme.colors.background
    val primaryColor = GlanceTheme.colors.primary
    val textColor = GlanceTheme.colors.onBackground
    val subtleColor = GlanceTheme.colors.onSurfaceVariant

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .padding(Spacing.ml)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        // ヘッダー: アプリ名 + 今日のセール情報
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Horizontal.End,
        ) {
            Text(
                "Popcoon",
                style = TextStyle(
                    color = primaryColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (todayInfo.isActive) {
                Text(
                    "🎯",
                    style = TextStyle(fontSize = 12.sp),
                )
            }
        }

        // 今日のセール情報
        Text(
            todayInfo.label,
            style = TextStyle(
                color = if (todayInfo.isActive) primaryColor else subtleColor,
                fontSize = 10.sp,
            ),
            modifier = GlanceModifier.padding(bottom = Spacing.xs),
        )

        // ウォッチリスト商品 (最大3件)
        if (items.isEmpty()) {
            Text(
                LocalContext.current.getString(io.github.shizukutanaka.popcoon.R.string.widget_empty),
                style = TextStyle(color = subtleColor, fontSize = 10.sp),
            )
        } else {
            items.forEach { item ->
                WidgetItemRow(item = item, textColor = textColor, subtleColor = subtleColor)
            }
        }
    }
}

@Composable
private fun WidgetItemRow(
    item: WidgetItem,
    textColor: ColorProvider,
    subtleColor: ColorProvider,
) {
    val verdictColor = when (item.verdict) {
        "BUY_NOW" -> ColorProvider(Color(0xFF118A4E))
        "WAIT" -> ColorProvider(Color(0xFFC0392B))
        else -> subtleColor
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            item.title,
            style = TextStyle(color = textColor, fontSize = 11.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(item.price),
            style = TextStyle(
                color = verdictColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

data class WidgetItem(val title: String, val price: Long, val verdict: String)
data class TodayInfo(val label: String, val isActive: Boolean)

// ── Receiver — Manifest に登録 ───────────────────────────────────────────────
class PopcoonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PopcoonWidget()
}
