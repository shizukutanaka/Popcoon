# CI ワークフロー（手動セットアップが必要）

このディレクトリの [`android.yml`](./android.yml) は、本リポジトリ用に用意した
GitHub Actions CI ワークフローです。

## なぜ `.github/workflows/` に入っていないのか

このワークフローを生成した自動化エージェント（GitHub App）には
`workflows` 権限が無く、`.github/workflows/` 配下のファイルを push できません
（GitHub のセキュリティ制約）。そのため、ここにテンプレートとして配置しています。

**2026-08-18 に、独立した 3 経路すべてで再実証しました**（過去のセッションは
git push しか試していませんでした。以後のエージェントが同じ試行を繰り返さないよう
結果を残します）:

| 経路 | 結果 |
|---|---|
| `bash ci/enable.sh && git push`（HTTPS の git push） | `! [remote rejected] ... refusing to allow a GitHub App to create or update workflow \`.github/workflows/android.yml\` without \`workflows\` permission` |
| GitHub REST Contents API (`PUT /repos/.../contents/.github/workflows/android.yml`) | `403 Resource not accessible by integration` |
| GitHub Git Data API (`POST /repos/.../git/trees`) | `403 Resource not accessible by integration` |

つまり **CI の有効化はこのセッションからは到達不能**で、人間の push 権限が必要です。

## 有効化の手順（どちらか一方）

### 方法 A: ローカル（clone 済みなら最速）

```bash
bash ci/enable.sh && git push
```

`ci/enable.sh` は `ci/android.yml` を `.github/workflows/` へ `git mv` してコミットするだけです。
`git mv` できる権限（通常の開発者の push 権限）があれば有効化できます。

### 方法 B: GitHub の Web UI だけで（clone 不要・コピペ不要）

ファイルは既にリポジトリにあるので、**名前を変えるだけ**で有効化できます:

1. GitHub で `ci/android.yml` を開く
2. 鉛筆アイコン（Edit this file）をクリック
3. **ファイル名の入力欄**を `android.yml` から `../.github/workflows/android.yml` に書き換える
   （`../` を打つとパンくずが自動的に階層を上がります）
4. Commit changes

どちらの方法でも、対象ブランチは作業ブランチ（`claude/**`）でも `main` でも構いません。
ワークフローのトリガーは `push: [main, "claude/**"]` なので、有効化した時点の push で
すぐ初回実行が始まります。

## ⚠️ 未ビルド検証の変更 (CI 有効化後に最初に確認すべきもの)

2026-07 の改善セッションで、**Android ビルドを実行できない環境から**以下の
期限付き移行をコードレベルで適用済みです。CI を有効化した最初の実行 (または
Android Studio での手元ビルド) で必ずコンパイル・テストを確認してください:

- **Play Billing Library 7.1.1 → 8.3.0** (`gradle/libs.versions.toml`,
  `BillingManager.kt`)。2026-08-31 以降は 8+ 必須。`queryProductDetailsAsync` の
  コールバック署名変更 (`QueryProductDetailsResult`) と `enableAutoServiceReconnection()`
  を適用済み。

  **2026-08-18 に公式 API リファレンスと突き合わせて静的監査済み**
  (コンパイルの代わりにはならないが、「未確認」より一段確度が上がる):

  | 確認項目 | 出典 | 結果 |
  |---|---|---|
  | `BillingClient.Builder.enableAutoServiceReconnection()` | [BillingClient.Builder](https://developer.android.com/reference/com/android/billingclient/api/BillingClient.Builder) | 存在する ✓ |
  | `BillingClient.Builder.enablePendingPurchases(PendingPurchasesParams)` | 同上 | 存在する ✓ (引数なし版は 8 で削除) |
  | `QueryProductDetailsResult.getProductDetailsList()` | [QueryProductDetailsResult](https://developer.android.com/reference/com/android/billingclient/api/QueryProductDetailsResult) | 存在する ✓ (Kotlin から `.productDetailsList`) |
  | PBL 8 で削除された API の参照 | [migrate-gpblv8](https://developer.android.com/google/play/billing/migrate-gpblv8) | `queryPurchaseHistoryAsync` / `querySkuDetailsAsync` / 引数なし `enablePendingPurchases` / `queryPurchasesAsync(String, ...)` / `enableAlternativeBilling` / `setReplaceProrationMode` — **いずれも BillingManager.kt は使用していない** ✓ |
  | 8.3.0 で期限を満たすか | [deprecation-faq](https://developer.android.com/google/play/billing/deprecation-faq) | 満たす。PBL 8 自身の期限は **2027-08-31** なので 1 年の余裕がある ✓ |

  残る未確認は「実際にコンパイル・実行が通るか」だけです。
- **compileSdk / targetSdk 35 → 36** (`app/build.gradle.kts`,
  `baselineprofile/build.gradle.kts`)。同じく 2026-08-31 期限 (延長申請で 11/01 まで)。
  [公式要件](https://developer.android.com/google/play/requirements/target-sdk) で
  新規/更新は API 36 以上と確認済み — この値で正しい。predictive back の
  opt-in (`enableOnBackInvokedCallback`) を Manifest に明示済み。AGP 8.10 は
  API 36 対応のため AGP バンプ不要。
- **16 KB ページサイズ対応: 期限は 2026-08-31 ではなく 2027-02-01**
  (2026-08-18 訂正。従来ここには「2025-11-01 以降 Play Console が強制」と書かれていたが、
  [公式ドキュメント](https://developer.android.com/guide/practices/page-sizes) の現行記載は
  *"Starting February 1, 2027, if your app updates don't support 16 KB memory page sizes,
  you won't be able to release these updates."* で、**リリース期限のクリティカルパスには
  乗っていない**)。
  純 Kotlin/Java のみのアプリは対応不要だが、本アプリは ML Kit
  (`mlkit-barcode-scanning` 17.3.0 / `play-services-mlkit-barcode-scanning` 16.1.0) と
  CameraX 1.4.1 の推移的依存にネイティブ `.so` を含むため対象。
  本環境では依存関係ツリー (`./gradlew :app:dependencies`) も APK 内 `.so` の
  実地確認もできないため未検証 — CI 稼働後に `zipalign -c -P 16` か Play Console の
  アップロード結果で確認すること (期限まで約 17 か月ある)。

## 初回実行で最初に見るもの

CI は一度も動いたことがなく、app モジュールの **84 ファイルはコンパイル検証すらされていない**
（ローカルの `run_compile_core.sh` は依存 jar が揃う 47 ファイルしか見られない）。
そのため初回実行で最も知りたいのは「コンパイルが通るか / kotest が通るか」の 2 点です。

これを確実に得られるよう、ジョブ構成を次のようにしてあります:

- `android` ジョブは **`compileDebugKotlin` → `testDebugUnitTest` → `assembleDebug`
  → `assembleRelease`** の順。GitHub Actions は最初に失敗したステップで打ち切るので、
  順序がそのまま「何が分かるか」になります。
- `detekt` / `lintDebug` は **`quality` という別ジョブ**に分離。detekt は
  `maxIssues: 0` かつ baseline 無しで、一度も実行されたことのない 131 ファイルに対して
  走ります。初回に指摘が出る可能性は高く、同一ジョブに置くとコンパイル結果を隠して
  しまうためです。両ジョブとも成功しないと全体は緑にならないので、ゲートとしての
  強度は落ちていません。

**detekt が大量に指摘してきた場合**は、既存分を凍結してから新規混入だけを止める運用に
切り替えてください:

```bash
./gradlew detektBaseline   # config/detekt/baseline.xml を生成
git add config/detekt/baseline.xml && git commit -m "ci: freeze existing detekt findings"
```

## このワークフローが検証する内容

| ジョブ | 内容 |
|--------|------|
| **android** | JDK 17 + Android SDK + Gradle キャッシュ。`compileDebugKotlin` → `testDebugUnitTest`（kotest 64 ファイル）→ `assembleDebug` → `assembleRelease`（使い捨てキーストアで署名、R8 圧縮・リソース shrink・kotlinx.serialization/Room/Hilt/Ktor の ProGuard ルールを実パイプラインで検証。生成 APK は誰も鍵を持たないため配布不可）。 |
| **quality** | `detekt` → `lintDebug`。**android ジョブとは別ジョブ**にしてある（下記「初回実行で最初に見るもの」参照）。 |
| **python-oracle** | `popcoon-tdd` の pytest スイート（差分テストの正本、300 テスト）。ベンチマークは CI のノイズになるため無効化。 |
| **parity** | Android SDK 不要。Gradle 同梱の kotlin-compiler-embeddable で純関数（customs/eco/dark-pattern/predict/buy-timing と各 EC マッパー）をコンパイル・実行し Python オラクルと照合（`popcoon-tdd/kotlin_parity/run_all.sh`）。 |
| **backend** | Cloudflare Worker の vitest（アラート評価・PII 検査・KV ページネーション・入力検証）。 |

トリガー: `main` / `claude/**` への push、`main` への PR、手動実行（`workflow_dispatch`）。
同一ブランチで新しい push があれば古い実行を自動キャンセルします。

## 補足

ローカルに Android SDK が無い環境（`ANDROID_HOME` 未設定）では Kotlin を
コンパイルできないため、この CI が Kotlin のコンパイル・単体テストを検証する
唯一の経路になります。スキーマ変更（Room の version bump など）や移植
（PORTING_SPEC.md）のパリティは、まずこの CI を緑にしてからマージしてください。
