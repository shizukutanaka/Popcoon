import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.feature.cart.CrossMallCartOptimizer
import io.github.shizukutanaka.popcoon.feature.crossborder.CustomsSimulator
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternTextDetector
import io.github.shizukutanaka.popcoon.feature.ethics.EcoEthicsScorer
import io.github.shizukutanaka.popcoon.feature.prediction.ConformalInterval
import io.github.shizukutanaka.popcoon.feature.prediction.PricePredictionEngine
import io.github.shizukutanaka.popcoon.feature.prediction.SeasonalDecompForecast
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.feature.scorer.SeasonalDowSignal
import io.github.shizukutanaka.popcoon.feature.tco.TCOCalculator
import java.time.Instant

/**
 * クロス言語パリティ実行ハーネス。
 *
 * 目的: "Python parity" を文書上の主張ではなく**実行可能な検証**にする。
 * 本物の Kotlin 実装を Gradle 同梱の kotlin-compiler-embeddable でコンパイルし JVM で実行 →
 * 各入力に対する出力を TSV で印字。compare_oracle.py が同じ入力で検証済み Python オラクル
 * (popcoon_core / buy_timing_scorer) を再計算して照合する。入力を印字して Python が
 * 再計算するので fixture との drift が起き得ない。Android SDK 不要。run.sh から実行する。
 *
 * カバー: scalar (customs/eco) + 履歴依存 (dark-pattern/predict/buy-timing)。
 * buy-timing は today=null で呼ぶ (Kotlin 拡張の sale/dow シグナルを除いた Python 同等経路)。
 */

// 決定論的履歴: productKey="k", platform="amazon", recorded_at = 2026-01-01 + i 日 UTC。
// Python 側 compare_oracle.py と同一規約。
private val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
private fun hist(prices: List<Long>, listPrice: Long): List<PriceRecord> =
    prices.mapIndexed { i, p ->
        PriceRecord("k", "amazon", listPrice, p, BASE.plusSeconds(i.toLong() * 86_400))
    }

private fun csv(xs: List<Long>) = xs.joinToString(",")

fun main() {
    // ── CUSTOMS ──────────────────────────────────────────────────────────────
    data class C(val f: Long, val s: Long, val cat: String, val jp: Long?)
    listOf(
        C(10_000, 5_000, "衣類", null), C(20_000, 5_000, "靴", null),
        C(50_000, 5_000, "電子機器", null), C(10_000, 2_000, "食品", 50_000),
        C(20_000, 2_000, "食品", 40_000), C(40_000, 5_000, "食品", 30_000),
        C(20_000, 2_000, "衣類", 40_000), C(10_000, 6_666, "衣類", null),
        C(30_000, 3_000, "靴", null), C(0, 0, "電子機器", null),
        C(15_000, 5_000, "化粧品", 18_000),
    ).forEach { c ->
        val r = CustomsSimulator.simulate(c.f, c.s, c.cat, c.jp)
        println("CUSTOMS\t${c.f}\t${c.s}\t${c.cat}\t${c.jp ?: "null"}\t" +
            "${r.totalLandedCost}\t${r.customsDuty}\t${r.consumptionTax}\t${r.isTaxExempt}\t${r.verdict}")
    }

    // ── ECO ──────────────────────────────────────────────────────────────────
    data class E(val origin: String?, val cat: String, val certs: List<String>)
    listOf(
        E("CN", "smartphone", emptyList()), E("JP", "laptop", emptyList()),
        E("DE", "tv", listOf("green-cert")), E("VN", "tshirt", emptyList()),
        E(null, "smartphone", emptyList()), E("US", "unknown_category", emptyList()),
        E("CN", "tshirt", listOf("エコ認証")),
    ).forEach { e ->
        val s = EcoEthicsScorer.score(e.origin, e.cat, e.certs)
        println("ECO\t${e.origin ?: "null"}\t${e.cat}\t${e.certs.joinToString(";")}\t" +
            "${s.overall}\t${s.co2Score}\t${s.laborScore}\t${"%.6f".format(s.co2Kg)}\t${s.greenAlternative ?: "null"}")
    }

    // ── 履歴依存シナリオ (dark-pattern / predict / buy-timing 共通入力) ─────────
    data class H(val current: Long, val listPrice: Long, val prices: List<Long>)
    val flat90 = List(90) { 10_000L }
    val desc30 = (0 until 30).map { 15_000L - it * 100 }
    val asc14 = (0 until 14).map { 10_000L + it * 100 }
    val charm = List(40) { 9_980L }                       // 端数価格 (下二桁80-99)
    val inflated = List(20) { 5_000L }                    // list>max*1.5 で参考価格誇張
    val presale = List(7) { 8_000L } + List(7) { 9_200L } // 直近7日が前7日比+15%
    val scenarios = listOf(
        H(10_000, 12_000, flat90),
        H(desc30.last(), 20_000, desc30),
        H(asc14.last(), 14_000, asc14),
        H(9_980, 12_000, charm),
        H(5_000, 20_000, inflated),
        H(9_200, 12_000, presale),
        H(10_000, 12_000, List(13) { 10_000L }),          // 履歴不足 -> buy_timing null
    )
    for (h in scenarios) {
        val records = hist(h.prices, h.listPrice)

        // DARKPATTERN: sorted warning type names
        val warns = DarkPatternDetector.detect(h.current, if (h.listPrice <= 0) null else h.listPrice, records)
            .map { it.type.name }.sorted().joinToString(",")
        println("DARKPATTERN\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\t$warns")

        // PREDICT: Python と共有するフィールドのみ (margin/seasonal は Kotlin 拡張なので除外)
        val pred = PricePredictionEngine.predict(records)
        if (pred == null) {
            println("PREDICT\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\tnull")
        } else {
            println("PREDICT\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\t" +
                "${pred.predicted7d}\t${pred.predicted30d}\t${"%.4f".format(pred.buyNowProbability)}\t" +
                "${pred.historicLow}\t${pred.historicHigh}\t${pred.confidence}")
        }

        // BUYTIMING: today=null (Python 同等経路)
        val bt = BuyTimingScorer.score(h.current, h.listPrice, records, null)
        if (bt == null) {
            println("BUYTIMING\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\tnull")
        } else {
            println("BUYTIMING\t${h.current}\t${h.listPrice}\t${csv(h.prices)}\t${bt.total}\t${bt.verdict}\t${bt.confidence}")
        }
    }

    // ── CONFORMAL: split-conformal margin (浮動小数点境界を含む) ────────────────
    // residuals(';' 区切り Double) と alpha → margin。proto_conformal_interval と照合。
    data class CC(val residuals: List<Double>, val alpha: Double)
    val conformal = listOf(
        CC((1..10).map { it.toDouble() }, 0.1),
        CC((1..9).map { it.toDouble() }, 0.1),     // 10*0.9 の浮動小数点境界
        CC((1..9).map { it.toDouble() }, 0.05),
        CC(listOf(-5.0, 3.0, -1.0, 4.0, 2.0), 0.2),
        CC(listOf(7.5), 0.1),                       // 単一: k>n → max
        CC((1..100).map { it.toDouble() }, 0.05),
        CC(listOf(2.0, 2.0, 2.0), 0.33),
        CC(emptyList(), 0.1),                        // 空 → 0.0
    )
    for (c in conformal) {
        val m = ConformalInterval.conformalMargin(c.residuals, c.alpha)
        println("CONFORMAL\t${c.residuals.joinToString(";")}\t${c.alpha}\t${"%.10f".format(m)}")
    }

    // ── ADAPTIVE_CONFORMAL: quantile-tracking (Conformal PID P項) margin ────────
    // 静的分位点との違いを検査する順序依存ケースを含む。proto_conformal_interval と照合。
    val adaptiveConformal = listOf(
        CC((1..10).map { it.toDouble() }, 0.1),
        CC(listOf(7.5), 0.1),                                        // 単一残差
        CC(emptyList(), 0.1),                                        // 空 → 0.0
        CC(List(20) { 2.0 } + List(10) { 50.0 }, 0.1),               // shift: 直近が高ボラ化
        CC(List(20) { 50.0 } + List(10) { 2.0 }, 0.1),               // shrink: 直近が沈静化
        CC(List(29) { 1.0 } + listOf(1000.0), 0.1),                  // shock: 直近1件だけ急変動
        CC(listOf(-30.0, -20.0, -10.0, -5.0, -2.0, 0.0, 2.0, 5.0, 10.0, 20.0, 30.0), 0.3),
    )
    for (c in adaptiveConformal) {
        val m = ConformalInterval.adaptiveConformalMargin(c.residuals, c.alpha)
        println("ADAPTIVE_CONFORMAL\t${c.residuals.joinToString(";")}\t${c.alpha}\t${"%.10f".format(m)}")
    }

    // ── HOLTRES: Holt の horizon ステップ先残差列 ───────────────────────────────
    // conformal margin の入力そのもの。従来ここが未照合で、7/30 日先の区間を 1 ステップ先
    // 残差で較正していた過小被覆バグが両言語で検出できなかった (2026-08 リサーチ)。
    // proto_conformal_interval.holt_multistep_residuals と照合。
    data class HR(val data: List<Double>, val horizon: Int)
    val trending = listOf(1000.0, 1010.0, 990.0, 1030.0, 1005.0, 1040.0, 995.0,
                          1050.0, 1020.0, 1060.0, 1010.0, 1070.0, 1030.0, 1080.0)
    val holtRes = listOf(
        HR(trending, 1),                        // 後方互換: 従来の 1 期先残差
        HR(trending, 7),                        // 7 日先 (predicted7d の較正)
        HR(trending, 13),                       // 残差 1 件だけ残る境界
        HR(trending, 14),                       // 実測が取れない → 空
        HR(trending, 30),                       // 30 日先だが履歴不足 → 空
        HR(List(20) { 1000.0 }, 7),             // 定数列 → 全て 0
        HR(List(20) { 1000.0 + 5.0 * it }, 7),  // 完全直線 → 誤差ゼロで外挿
        HR(listOf(1000.0, 1100.0), 1),          // 3 点未満 → 空
        HR(emptyList(), 1),                     // 空入力 → 空
    )
    for (h in holtRes) {
        val r = PricePredictionEngine.holtResiduals(h.data, h.horizon)
        val enc = r.joinToString(";") { "%.10f".format(it) }
        println("HOLTRES\t${h.data.joinToString(";")}\t${h.horizon}\t$enc")
    }

    // ── SEASONAL: trend+seasonal 分解予測 (中心移動平均 + 最小二乗線形) ──────────
    // 価格列・horizon・period → 予測列。proto_seasonal_decomp_forecast と照合。
    data class SC(val prices: List<Double>, val horizon: Int, val period: Int)
    fun weekly(nDays: Int) = (0 until nDays).map { i ->
        1000.0 + 10 * i + when (i % 7) { 5 -> -50.0; 6 -> -30.0; 0 -> 20.0; else -> 0.0 }
    }
    val seasonal = listOf(
        SC(weekly(28), 7, 7),                                   // 4 週・週次季節性 + トレンド
        SC(weekly(21), 7, 7),                                   // 3 週
        SC(weekly(14), 7, 7),                                   // min_history 境界 (=14)
        SC(weekly(10), 7, 7),                                   // < min_history → フラット
        SC((0 until 20).map { 500.0 + 3.5 * it }, 5, 1),        // period=1 → 純線形
        SC((0 until 15).map { 800.0 - 2.0 * it + if (it % 5 == 0) 15.0 else 0.0 }, 5, 5),  // period=5
    )
    for (sc in seasonal) {
        val f = SeasonalDecompForecast.forecast(sc.prices, sc.horizon, sc.period)
        println("SEASONAL\t${sc.prices.joinToString(";")}\t${sc.horizon}\t${sc.period}\t" +
            f.joinToString(";") { "%.10f".format(it) })
    }

    // ── CART: cross-mall basket optimizer (組合せ最適化 + タイブレーク) ──────────
    // 入力 (items/malls) を # | , = で符号化して emit → Python が同じ入力で再計算し照合。
    // item= name#qty#mall=price,mall=price   mall= id#shipping#free#thr=disc,thr=disc
    fun encItems(items: List<CrossMallCartOptimizer.CartItem>) =
        items.joinToString("|") { ci ->
            "${ci.name}#${ci.qty}#" + ci.options.entries.joinToString(",") { e -> "${e.key}=${e.value}" }
        }
    fun encMalls(malls: Map<String, CrossMallCartOptimizer.MallConfig>) =
        malls.entries.joinToString("|") { (id, c) ->
            "$id#${c.shipping}#${c.freeThreshold}#" + c.coupons.joinToString(",") { cp -> "${cp.threshold}=${cp.discount}" }
        }
    fun item(name: String, qty: Int, vararg opts: Pair<String, Double>) =
        CrossMallCartOptimizer.CartItem(name, linkedMapOf(*opts), qty)
    fun mall(ship: Double, free: Double, vararg cps: Pair<Double, Double>) =
        CrossMallCartOptimizer.MallConfig(ship, free, cps.map { CrossMallCartOptimizer.Coupon(it.first, it.second) })

    data class Cart(val items: List<CrossMallCartOptimizer.CartItem>, val malls: Map<String, CrossMallCartOptimizer.MallConfig>)
    val carts = listOf(
        // 2 商品: 分割すると送料がかかるが、まとめると送料無料ライン超え
        Cart(listOf(item("A", 1, "amazon" to 1500.0, "rakuten" to 1600.0),
                    item("B", 1, "amazon" to 1800.0, "rakuten" to 1700.0)),
             mapOf("amazon" to mall(500.0, 3000.0), "rakuten" to mall(400.0, 3000.0))),
        // クーポンが効くケース
        Cart(listOf(item("A", 2, "rakuten" to 2000.0, "yahoo" to 2100.0),
                    item("B", 1, "rakuten" to 3000.0, "yahoo" to 2900.0)),
             mapOf("rakuten" to mall(400.0, 10000.0, 5000.0 to 500.0, 8000.0 to 1000.0),
                   "yahoo" to mall(350.0, 8000.0))),
        // 同額タイ → 配送回数が少ない (単一モール) を優先
        Cart(listOf(item("A", 1, "amazon" to 1000.0, "rakuten" to 1000.0),
                    item("B", 1, "amazon" to 1000.0, "rakuten" to 1000.0)),
             mapOf("amazon" to mall(0.0, 0.0), "rakuten" to mall(0.0, 0.0))),
        // 単一商品
        Cart(listOf(item("solo", 3, "amazon" to 800.0, "yahoo" to 790.0)),
             mapOf("amazon" to mall(500.0, 5000.0), "yahoo" to mall(600.0, 5000.0))),
        // 3 商品 3 モール
        Cart(listOf(item("A", 1, "amazon" to 1200.0, "rakuten" to 1250.0, "yahoo" to 1180.0),
                    item("B", 1, "amazon" to 900.0, "rakuten" to 880.0, "yahoo" to 950.0),
                    item("C", 2, "amazon" to 600.0, "rakuten" to 610.0, "yahoo" to 590.0)),
             mapOf("amazon" to mall(450.0, 3500.0), "rakuten" to mall(400.0, 3000.0, 2000.0 to 200.0),
                   "yahoo" to mall(500.0, 4000.0))),
    )
    // ── TEXT: UI テキスト系ダークパターン検出 (regex/Unicode) ───────────────────
    // text と stockCount → 警告 (category|severity|evidence)。proto_darkpattern_signals と照合。
    // 全角数字/空白 (３ / U+3000) で Python(Unicode \d\s) と Kotlin(ASCII) の乖離を検査。
    data class TX(val text: String, val stock: Int?)
    val texts = listOf(
        TX("本日限り！お見逃しなく", null),
        TX("残り3点", null),
        TX("残り３点", null),              // 全角数字 — 乖離検査 (n=3 HIGH)
        TX("残り５点", null),              // 全角数字 — parseUnicodeInt + severity (n=5 MEDIUM)
        TX("残り　3　点", null),           // 全角空白 U+3000 — 乖離検査
        TX("残り3個", null),               // 在庫助数詞 個 (点以外) — recall 拡張
        TX("あと2セット", null),            // 接頭辞 あと + 助数詞 セット
        TX("残り1台", null),               // 助数詞 台 (n=1 HIGH)
        TX("残り20個", null),              // 助数詞 個 (n=20 MEDIUM)
        TX("あと5日で発送", null),          // 日 は在庫助数詞でない → SCARCITY にしない (誤検出検査)
        TX("在庫わずか", null),
        TX("Only 2 left", null),
        TX("low in stock", null),
        TX("5人がカートに入れています", null),
        TX("3 people are viewing", null),
        TX("デフォルトでチェック", null),
        TX("本日限り 残り3点 5人が購入", null),   // 複数カテゴリ → ソート
        TX("定期購入コース", null),           // HIDDEN_SUBSCRIPTION (隠れ定期購入)
        TX("自動更新されます", null),          // HIDDEN_SUBSCRIPTION (自動継続)
        TX("Subscription automatically renews", null),  // HIDDEN_SUBSCRIPTION (英語)
        TX("本日限り 定期購入コース", null),   // URGENCY + HIDDEN_SUBSCRIPTION → ソート順検査
        TX("あと3時間で終了", null),                     // URGENCY あと接頭辞 (従来は非検出)
        TX("あと30分", null),                            // URGENCY 分
        TX("終了間近", null),                            // URGENCY 締切迫る系
        TX("売り切れ次第終了", null),                     // URGENCY 売り切れ次第
        TX("本日最終日", null),                          // URGENCY 限定つき最終日
        TX("Last chance! Offer ends tonight", null),     // URGENCY 英語
        TX("期間限定フレーバー いちご味", null),           // 商品属性 → 非検出 (誤爆ガード)
        TX("最終日までにお届けします", null),              // 配送文脈 → 非検出 (誤爆ガード)
        TX("残り3日で入荷", null),                        // 日単位 → 非検出 (誤爆ガード)
        TX("キャンペーンは終了しました", null),            // 終了済み → 非検出 (誤爆ガード)
        TX("解約はお電話のみで承ります", null),          // OBSTRUCTION 電話限定 (HIGH)
        TX("定期の停止はお電話のみ", null),              // OBSTRUCTION 定期停止の電話限定
        TX("お電話のみでの解約受付となります", null),      // OBSTRUCTION 語順逆 (電話→解約)
        TX("Call us to cancel your subscription", null), // OBSTRUCTION 英語
        TX("解約は次回お届け予定日の10日前までにご連絡ください", null),  // OBSTRUCTION 期限 (MEDIUM)
        TX("解約はマイページから可能ですがお問い合わせはお電話のみ", null),  // 12文字超 → 非検出
        TX("次回お届け日の変更は3日前まで可能です", null),  // 解約文脈なし → 非検出
        TX("本日限り 残り2点 デフォルトでチェック 解約はお電話のみ", null),  // 4カテゴリ → ソート順
        TX("", 2),                          // 空テキスト + stock=2
        TX("普通の商品説明です", 2),         // テキスト無 + stock=2 → SCARCITY
        TX("送料無料の良い商品", null),       // 何も無し
    )
    for (tx in texts) {
        val sigs = DarkPatternTextDetector.detect(tx.text, tx.stock)
        val enc = sigs.joinToString(";") { "${it.category}|${it.severity}|${it.evidence}" }
        println("TEXT\t${tx.text}\t${tx.stock ?: "null"}\t$enc")
    }

    // ── SDOW: 曜日季節性シグナル (round-half-to-even の境界含む) ────────────────
    // (dow:price;...) と todayDow → signal。proto_seasonal_signal と照合。
    // overall=100 を作り dowMean を 97.5/96.5/102.5 等にして rel*100 を .5 境界へ寄せる。
    data class SD(val hist: List<Pair<Int, Double>>, val today: Int)
    // 14 点: 月(0) が安く、他は overall=100 になるよう調整。
    fun mkHist(dowPrice: Map<Int, Double>, fill: Double, n: Int): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        var i = 0
        for ((d, p) in dowPrice) { out.add(d to p); i++ }
        while (out.size < n) { out.add((i % 7) to fill); i++ }
        return out
    }
    val sdows = listOf(
        // 月曜2サンプルが 97.5 → rel=0.025 → 2.5 → round-half-to-even → 2
        SD(listOf(0 to 97.5, 0 to 97.5, 1 to 101.0, 2 to 101.0, 3 to 100.0, 4 to 100.0,
                  5 to 100.0, 6 to 100.0, 1 to 100.5, 2 to 100.5, 3 to 100.5, 4 to 100.5,
                  5 to 100.5, 6 to 100.5), 0),
        // 月曜 96.5 → 3.5 → round-half-to-even → 4
        SD(listOf(0 to 96.5, 0 to 96.5, 1 to 101.0, 2 to 101.0, 3 to 100.0, 4 to 100.0,
                  5 to 100.0, 6 to 100.0, 1 to 100.5, 2 to 100.5, 3 to 101.0, 4 to 101.0,
                  5 to 100.5, 6 to 100.5), 0),
        // 月曜が高い → 負のシグナル
        SD(listOf(0 to 103.5, 0 to 103.5, 1 to 99.0, 2 to 99.0, 3 to 100.0, 4 to 100.0,
                  5 to 100.0, 6 to 100.0, 1 to 99.5, 2 to 99.5, 3 to 99.5, 4 to 99.5,
                  5 to 99.5, 6 to 99.5), 0),
        // クランプ +10 (月曜が極端に安い)
        SD(mkHist(mapOf(0 to 50.0), 100.0, 14), 0),
        // 履歴 < 14 → 0
        SD(mkHist(mapOf(0 to 90.0), 100.0, 10), 0),
        // 対象曜日サンプル < 2 → 0 (日曜=6 が1件のみ)
        SD(mkHist(mapOf(6 to 90.0), 100.0, 14), 6),
        // overall<=0 → 0
        SD(mkHist(mapOf(0 to 0.0), 0.0, 14), 0),
    )
    for (sd in sdows) {
        val sig = SeasonalDowSignal.signal(sd.hist, sd.today)
        val he = sd.hist.joinToString(";") { "${it.first}:${it.second}" }
        println("SDOW\t$he\t${sd.today}\t$sig")
    }

    // ── TCO: 総所有コスト (消耗品/電力/保守/残価, intensity・年数依存) ────────────
    // price/category/years/intensity → 7 フィールド。popcoon_core.calculate_tco と照合。
    data class TC(val price: Long, val category: String, val years: Int, val intensity: Double)
    val tcos = listOf(
        TC(15000, "inkjet_printer", 5, 1.0),
        TC(15000, "inkjet_printer", 5, 0.33),   // 浮動小数点の結合順検査
        TC(15000, "inkjet_printer", 7, 2.0),
        TC(40000, "laser_printer", 5, 1.0),
        TC(40000, "laser_printer", 5, 2.0),      // ドラムに intensity を掛けるか (乖離検査)
        TC(40000, "laser_printer", 5, 0.5),
        TC(8000, "coffee_capsule", 3, 1.0),
        TC(8000, "coffee_capsule", 5, 1.5),
        TC(120000, "laptop", 5, 1.0),
        TC(120000, "laptop", 1, 1.0),
        TC(80000, "smartphone", 5, 1.0),
        TC(50000, "refrigerator", 5, 1.0),
        TC(5000, "unknown_widget", 5, 1.0),      // generic フォールバック
    )
    for (tc in tcos) {
        val r = TCOCalculator.calculate(tc.price, tc.category, tc.years, tc.intensity)
        println("TCO\t${tc.price}\t${tc.category}\t${tc.years}\t${tc.intensity}\t" +
            "${r.consumablesTotal};${r.energyTotal};${r.maintenance};${r.residualValue};${r.totalTco};${r.tcoPerMonth}")
    }

    for (cart in carts) {
        val r: CrossMallCartOptimizer.Result = CrossMallCartOptimizer.optimize(cart.items, cart.malls)
        val assignParts = ArrayList<String>()
        for (i in 0 until cart.items.size) {
            assignParts.add(i.toString() + "=" + r.assignment[i])
        }
        val assign = assignParts.joinToString(",")
        println("CART\t${encItems(cart.items)}\t${encMalls(cart.malls)}\t" +
            "${"%.6f".format(r.total)}#${r.numMalls}#${"%.6f".format(r.shippingTotal)}#${"%.6f".format(r.couponTotal)}#$assign#${r.greedy}")
    }
}
