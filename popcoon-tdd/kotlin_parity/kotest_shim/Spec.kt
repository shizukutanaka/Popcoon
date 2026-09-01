package io.kotest.core.spec.style

/**
 * kotest `StringSpec` の最小互換シム。
 *
 * **なぜ必要か**: この環境には Maven Central への egress が無く (repo1.maven.org /
 * repo.maven.apache.org は 403)、kotest の jar を取得できない。CI 有効化は人手ゲートなので、
 * `app/src/test` の 63 spec は **一度も実行されたことがない**。参照シンボルの実在は
 * `check_test_refs.py` が見ているが、アサーションが真かどうかは誰も確かめていなかった。
 *
 * kotest 本体を待つのではなく、テストが実際に使っている 42 シンボルだけを実装して
 * **テストファイルを 1 行も変えずに**コンパイル・実行する。
 *
 * 意図的な差異 (過大評価しないための明示):
 *  - プロパティテストの試行回数は kotest 既定の 1000 ではなく `POPCOON_PROPERTY_ITERATIONS`
 *    (既定 300)。シード固定で決定論的に走る。
 *  - shrinking は無し。反例はそのまま表示する。
 *  - 非対応の kotest 機能を使ったテストは **コンパイルエラー**になる (黙って通らない)。
 *  - コルーチン/Android/Room/Hilt/ktor に依存する spec は対象外 (run_kotest.sh が除外し件数を表示)。
 */
abstract class StringSpec(private val body: StringSpec.() -> Unit = {}) {

    /** 登録済みテスト (宣言順)。 */
    val registered = mutableListOf<Pair<String, () -> Unit>>()

    private var initialised = false

    /** 遅延初期化 — サブクラスのプロパティ初期化後に body を走らせる。 */
    fun collect(): List<Pair<String, () -> Unit>> {
        if (!initialised) {
            initialised = true
            body()
        }
        return registered
    }

    /** `"テスト名" { ... }` の登録。 */
    operator fun String.invoke(test: () -> Unit) {
        registered += this to test
    }
}
