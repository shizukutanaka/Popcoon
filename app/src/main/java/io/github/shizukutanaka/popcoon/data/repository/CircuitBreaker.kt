package io.github.shizukutanaka.popcoon.data.repository

/**
 * プラットフォーム別の軽量サーキットブレーカー。
 *
 * 課題: [ProductRepository.search] は Amazon/楽天/Yahoo に毎回並列問い合わせる。
 * 1 プラットフォームの API が連続障害中でも、検索のたびに (タイムアウトいっぱいの)
 * 5 秒待って失敗するだけの無駄なリクエストを送り続けていた。他 2 件の結果は
 * 返せているため気づきにくいが、障害中の platform 分だけ検索全体の体感速度が
 * 常に悪化し続ける。
 *
 * 状態遷移:
 *  CLOSED (通常) → 連続失敗が閾値到達 → OPEN (openDurationMs 秒間リクエストをスキップ)
 *  → 経過後 HALF_OPEN (試行を通す) → 成功で CLOSED / 失敗で OPEN に戻る
 *
 * **HALF_OPEN は「同時に 1 件だけ」を強制しない** — 結果が記録される (recordSuccess /
 * recordFailure) までは通る。以前ここには「1件だけ試行を許可」と書いてあったが
 * 実装はそうなっておらず、テスト名も同じ主張をしながら 2 回目が弾かれることを
 * 検証していなかった。実装に合わせて記述を正した。
 *
 * 単一試行の強制を入れていない理由: このブレーカーは **端末ごと・プラットフォームごと**
 * に独立しており、サーバ側で多数のクライアントが共有する構成のような
 * 「復旧時のなだれ込み」は起きない。加えて呼び出し側 (ProductRepository.search) は
 * 1 回の検索でプラットフォームごとに 1 コールしか出さず、失敗すれば即 OPEN に戻るため、
 * 実運用の試行数は事実上 1 件になる。in-flight フラグを足すと、
 * CancellationException で結果が記録されなかった場合に枠が返らず
 * **恒久的に閉じたまま**になる危険の方が大きい。
 *
 * Context 非依存の純粋な状態機械。呼び出し側 (ProductRepository) が
 * 実行前に [allowRequest]、結果に応じて [recordSuccess]/[recordFailure] を呼ぶ。
 */
class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000L,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var consecutiveFailures = 0
    private var openedAtMs: Long = 0
    private var state = State.CLOSED

    /** 現在の状態 (テスト・診断用)。 */
    @Synchronized
    fun currentState(): State = state

    /** このリクエストを実行してよいか。false ならスキップして即座にフォールバック値を使う。 */
    @Synchronized
    fun allowRequest(nowMs: Long): Boolean {
        if (state == State.OPEN && nowMs - openedAtMs >= openDurationMs) {
            state = State.HALF_OPEN
        }
        return state != State.OPEN
    }

    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
        state = State.CLOSED
    }

    /** HALF_OPEN 中の失敗は即座に OPEN に戻す (試行の権利を使い切ったため)。 */
    @Synchronized
    fun recordFailure(nowMs: Long) {
        consecutiveFailures++
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN
            openedAtMs = nowMs
        }
    }
}
