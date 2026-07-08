package io.github.shizukutanaka.popcoon.feature.notification

private typealias Kind = PriceAlertEvaluator.Kind

/**
 * PriceAlertEvaluator の実行検証ハーネス (Android SDK 不要)。
 * run_alerts.sh から PriceAlertEvaluator.kt と一緒にコンパイル・実行する。
 *
 * 主眼: **エッジトリガ**意味論の永続的な回帰ガード。
 * Tier 53 (目標到達のエッジトリガ化) と Tier 54 (Worker テストの潜在失敗) はどちらも
 * 「Kotest が CI で未実行 → 潜在失敗が緑に見える」が原因だった。本ハーネスは Android SDK 無しの
 * `parity` ジョブ (実際に CI で走る) で評価器を実行検証し、エッジトリガが「上→下の跨ぎ」でのみ
 * 発火し、目標以下に留まる間は再通知しないことを恒久的に固定する。
 *
 * PriceAlertEvaluatorTest / PriceSyncWorkerLogicTest と同じ期待値を、SDK 非依存で実行する。
 */
fun main() {
    var fails = 0

    fun check(name: String, cond: Boolean) {
        if (!cond) { println("MISMATCH [$name]"); fails++ }
    }
    fun eval(prev: Long, latest: Long, target: Long?, minDrop: Int = 3) =
        PriceAlertEvaluator.evaluate(prev, latest, target, minDrop)

    // ── 目標到達: エッジトリガ (上→下の跨ぎのみ発火) ────────────────────────────
    check("跨ぎ: 5000→4000 target4000 = TARGET",
        eval(5000, 4000, 4000).kind == Kind.TARGET_REACHED)
    check("跨ぎ: 5000→3500 target4000 = TARGET",
        eval(5000, 3500, 4000).kind == Kind.TARGET_REACHED)
    check("跨ぎ: 5000→3800 target4000 = TARGET",
        eval(5000, 3800, 4000).kind == Kind.TARGET_REACHED)
    // 率が小さくても (1% floor 0) 跨ぎなら TARGET、dropPercent=0。
    run {
        val a = eval(4040, 4000, 4000)
        check("跨ぎ率0%: TARGET & drop=0", a.kind == Kind.TARGET_REACHED && a.dropPercent == 0)
    }

    // ── 再通知抑制: 既に目標以下のまま (跨いでいない) ──────────────────────────
    check("既に以下+小動き: 3000→3800 target4000 = NONE",
        eval(3000, 3800, 4000).kind == Kind.NONE)
    check("既に以下+不変: 3800→3800 target4000 = NONE (日次スパムの本丸)",
        eval(3800, 3800, 4000).kind == Kind.NONE)
    // prev == target は「跨ぎ」ではない (1..target は target を含む)。
    check("境界: prev==target 5000→4900 target5000 minDrop10 = NONE",
        eval(5000, 4900, 5000, 10).kind == Kind.NONE)

    // ── 既に目標以下で更に有意下落 → PRICE_DROP で拾う (情報を失わない) ─────────
    run {
        val a = eval(3800, 3000, 4000)
        check("既に以下+21%下落: PRICE_DROP & drop=21",
            a.kind == Kind.PRICE_DROP && a.dropPercent == 21)
    }

    // ── 真の跨ぎ (prev=target+1): Worker テストの修正後ケース ──────────────────
    run {
        val a = eval(5001, 4900, 5000, 10)  // 2% 下落 < minDrop10 でも跨ぎなので TARGET
        check("跨ぎ prev=5001 target5000 minDrop10 = TARGET",
            a.kind == Kind.TARGET_REACHED && a.shouldNotify)
    }

    // ── 初回観測 (prev<=0): 既に目標以下なら 1 回だけ発火 ──────────────────────
    run {
        val a = eval(0, 3000, 4000)
        check("初回 prev=0 目標以下: TARGET & drop=0", a.kind == Kind.TARGET_REACHED && a.dropPercent == 0)
    }
    check("初回 prev=0 目標なし: NONE", eval(0, 3000, null).kind == Kind.NONE)

    // ── 目標未達でも有意下落は PRICE_DROP にフォールスルー ─────────────────────
    run {
        val a = eval(5000, 4001, 4000)  // 目標未達 (4001>4000) だが 19% 下落
        check("目標未達+19%: PRICE_DROP & drop=19", a.kind == Kind.PRICE_DROP && a.dropPercent == 19)
    }
    // 境界単独: 目標を1円超過 + 下落ほぼ0 → NONE
    check("境界 4010→4001 target4000: NONE", eval(4010, 4001, 4000).kind == Kind.NONE)

    // ── 値下がり (目標未設定) の閾値 ───────────────────────────────────────────
    run {
        val a = eval(5000, 4000, null)
        check("目標なし20%下落: PRICE_DROP & drop=20", a.kind == Kind.PRICE_DROP && a.dropPercent == 20)
    }
    check("目標なし閾値ちょうど 100→97: PRICE_DROP (3%==minDrop)", eval(100, 97, null).kind == Kind.PRICE_DROP)
    check("目標なし閾値未満 100→98: NONE (2%<3%)", eval(100, 98, null).kind == Kind.NONE)
    check("目標なし値上がり: NONE", eval(4000, 4500, null).kind == Kind.NONE)
    check("目標なし横ばい: NONE", eval(4000, 4000, null).kind == Kind.NONE)

    // ── 異常値ガード ──────────────────────────────────────────────────────────
    check("latest<=0: NONE", eval(5000, 0, 4000).kind == Kind.NONE)
    check("target<=0 は無効→値下がり判定: PRICE_DROP", eval(5000, 4000, 0).kind == Kind.PRICE_DROP)

    // ── minDropPercent=0 (全下落通知) ─────────────────────────────────────────
    check("minDrop=0 で 1% 下落: PRICE_DROP",
        PriceAlertEvaluator.evaluate(1000, 990, null, 0).kind == Kind.PRICE_DROP)

    // ── shouldNotify ──────────────────────────────────────────────────────────
    check("shouldNotify: 跨ぎ=true", eval(5000, 4000, 4000).shouldNotify)
    check("shouldNotify: 微小値上がり=false", !eval(5000, 4900, null).shouldNotify)

    // ── プロパティ (双方向エッジ): 跨ぎは必ず TARGET、既に以下は決して TARGET でない ──
    var p = 0
    while (p < 2000) {
        val target = 1L + (p * 37L % 100_000L)
        val latest = 1L + (p * 53L % target)  // 1..target (必ず目標以下)
        // 前回が目標超 → 跨ぎ → 必ず TARGET_REACHED
        check("prop跨ぎ[$p]", eval(target + 1, latest, target).kind == Kind.TARGET_REACHED)
        // 前回も目標以下 → 跨いでいない → TARGET にはならない
        check("prop既に以下[$p]", eval(latest, latest, target).kind != Kind.TARGET_REACHED)
        p++
    }

    if (fails == 0) println("PRICE ALERT EVALUATOR: all assertions passed (edge-trigger + property)")
    else { println("PRICE ALERT EVALUATOR: $fails assertion(s) FAILED"); kotlin.system.exitProcess(1) }
}
