# Popcoon 現状評価 — 長所 / 短所 / 改善案 (2026-07)

商用品質監査 (30 タスク) + リサーチ実装完了・`main` 公開時点の棚卸し。
数値は全て実測 (推測値なし)。改善案テーブルは将来の Claude (Opus / Sonnet) セッションが
そのままタスクとして拾える粒度で書く。**着手前に必ずリポジトリ直下の `CLAUDE.md` を読むこと。**

## 長所

1. **二重言語 TDD アーキテクチャ** — Python 仕様オラクル (490 tests) が真実の源、Kotlin 本番実装を
   `kotlin_parity/` の 14 ハーネスが**実コンパイル・実行**で照合 (run.sh 164 ケース 0 乖離、`run_compile_core.sh` が 46 ファイルを型検査)。
   Android SDK 無しの環境でもロジックの実行検証ができる、この規模のアプリでは希少な体制
2. **テスト防御の深さ** — 11 階層 (unit/integration/golden/metamorphic/mutation/perf/fuzz/
   stateful/concurrency/differential/chaos)。mutation score 100% × 4 モジュール。
   kotest property-based + Room migration チェーンテスト (v1→v7)
3. **プライバシー第一の実装** — テレメトリゼロ、全推論オンデバイス、クラッシュレポートは
   opt-in + PII 自動除去 + 90 日 TTL、GDPR Article 17 完全実装 (画像キャッシュ・クラッシュ
   ローカル保存まで削除)。設計でなく実装として検証済み
4. **競合非搭載の差別化 6 機能** — ダークパターン検出 (テキスト 6 カテゴリ + 価格系)、
   TCO (代替製品比較つき)、越境関税、エコ倫理、ポイント個人化 (SPU 18 倍対応)、
   クロスモールカート最適化 (Prime 会員反映)
5. **名寄せ精度の多層防御** — JAN → 型番+容量 → タイトル (Jaccard + 文字 2-gram Dice ブレンド)
   → 属性ペナルティ (個数 / 色 27 名→16 正準 / 内容量 ml・mg 正規化)。各層に誤爆ガードと oracle 裏付け
6. **i18n 規律** — 4 ロケール × 405 キー完全一致 + kind/label 分離パターンでオラクル結合と
   ローカライズを両立。TalkBack 対応 (チャート要約読み上げ・mergeDescendants) も監査済み
7. **backend の実ランタイムテスト** — vitest-pool-workers で本物の `src/index.ts` を miniflare 上で
   実行 (80 tests)。レート制限はネイティブ binding + KV フォールバックの漸進移行、
   タイミングセーフ比較・ペイロード上限・KV TTL まで監査済み
8. **ドキュメントの誠実さ** — 「CI 4 本稼働」等の虚偽記載を全て実態に訂正済み。見送った
   改善案も理由・出典つきで `docs/RESEARCH-2026-07.md` に記録 (再検討可能性を保存)
9. **通知の抑制設計** — 誤検知対策の 1 サイクル遅延確認 (ユーザー承認済み設計)、
   1 同期あたり通知上限 3 件 + 優先度付け、いずれも純関数化されテスト済み
10. **回復性** — 全 ViewModel mutating メソッドに CancellationException-aware try/catch、
    StateFlow の例外終了に catch フォールバック、Worker は全滅時のみ指数バックオフ retry

## 短所 (それぞれ根本原因つき)

1. **CI 未稼働** — `ci/android.yml` は定義済みだがエージェントの GitHub App に `workflows`
   権限が無く `.github/workflows/` へ push 不能。人手で `bash ci/enable.sh && git push` が必要
2. **Compose/Room/Hilt 層はコンパイル未検証** — 環境に Android SDK が無い。純 Kotlin は parity で
   実行検証済みだが、UI 層の変更は構文チェック止まり。CI 有効化までは残存リスク。
   **2026-08 に現実の欠陥として顕在化**: `UserPreferences` の 5 メンバーが `override` 欠落で
   約 1 か月コンパイル不能だった (e519e67 で修正)。再発防止に `run_compile_core.sh` を新設し
   46 ファイルを実コンパイルするようにしたが、**壊れた UserPreferences.kt 自体は
   datastore + dagger 依存でその対象外**だったため、全ソースを走査する `check_overrides.py`
   (interface 実装の override 欠落を構文検査) を追加して当該回帰クラスだけは塞いだ。
   それでも残り 85 ファイルの型検査は不能。CI 有効化 (A1) の優先度は最上位。
   2026-08-18 に静的ゲートを 4 種へ拡張 (override / `R.*` 参照 / `when` 網羅 / テスト参照) し、
   実コンパイルも 34 → 46 ファイルへ広げた。いずれも欠陥注入で検出能力を実証済み。

   **「スタブを作れば もっとコンパイルできるのでは」は 2026-08 に実測して見送った**
   (同じ検討を繰り返さないための記録):
   - 85 ファイルの内訳は Compose 44 / 非 Compose 41。非 Compose 側が要求する外部型は 87 種。
   - このうち **スタブが嘘をつけないのはアノテーションのみ** (`@Inject` `@Module` `@Dao` 等は
     プロセッサ不在では実物も不活性で意味的に同一)。それだけで足りるファイルは **8 件**、
     さらに 3 件は ktor 依存 (jar 不在) なので実質 **5 件** しか増えない。
   - 残りは `Context` (17 件) / Room の `RoomDatabase`・`Migration` / WorkManager の
     `CoroutineWorker` / DataStore の `Preferences`・`edit` など **実クラス**。これらを手書きで
     模すと、**実 API と食い違っても気付けない**「検証の演劇」になる (本リポジトリが
     `JsonLdStock` の docstring で戒めているのと同じ失敗)。既知のギャップを未知のリスクへ
     変えるだけなので採用しない。
3. **Amazon データソースが実質 FallbackScraper のみ** — PA-API 5.0 が 2026-05-15 廃止。
   後継 Creators API は OAuth2 資格情報 + 成果実績 (10 件/30 日) が必要で人手ゲート
4. **FCM push 経路がデッドコード** — backend 側は実装済みだが Android に Firebase 未組込
   (google-services.json 無し)。実通知は端末ローカルのみ。組み込むかは製品判断
5. **価格履歴 KV の lost-update** — read→merge→put の後勝ち。per-product Durable Objects への
   移行設計は `backend/README.md` に文書化済みだが、wrangler 実行検証不可のため未実装
6. **UI 自動テストが薄い** — Compose UI テスト 2 件 + androidTest 4 ファイルは本環境で実行不可。
   ユニットテスト 64 ファイルはロジック層に偏る (構造上やむを得ないが偏りは事実)
7. ~~**Yahoo 会員ランク未モデル化**~~ — 2026-08 に実装済 (B4)。UserContext.yahooRank +
   設定 UI + 4 ロケール文字列を追加し、感謝デー (毎月 11日・22日) を実計算するようにした
8. **名寄せの残課題** — groupByIdentity は JAN なし商品で O(m²) (粗ブロッキング B5 は未着手)。
   IDF-lite トークン重み付けは 2026-08 に実装済み。Model2Vec 等の埋め込みは効果不確実で見送り
9. **レビュー信頼度の入力が浅い** — rating + reviewCount のみ (星分布・レビュー履歴が
   API から取れない)。二峰性検出などの v2 はデータ源が増えるまで実装不能

## 改善案

### A. 人手ゲート (エージェントでは完了不能 — 着手せずユーザーへ案内)

| # | 項目 | 手順 |
|---|---|---|
| A1 | CI 有効化 | 人の push 権限で `bash ci/enable.sh && git push` (`ci/README.md`) |
| A2 | v0.1.0 Release 作成 | GitHub → Releases → Draft a new release (`main` からタグ v0.1.0) |
| A3 | default branch を `main` へ | GitHub → Settings → Branches |
| A4 | リポジトリ public 化 (必要なら) | GitHub → Settings → General → Danger Zone |
| A5 | Amazon Creators API 移行 | OAuth2 資格情報 + 成果実績の取得後、`AmazonPaApiClient.kt` の TODO 参照 |
| A6 | Firebase/FCM 組込み判断 | 製品判断。組込むなら backend の既存経路が生きる |

### B. Opus 向け (設計判断・横断変更・golden 移行を伴う — 計画提示→承認→実装)

| # | 項目 | 難易度 | 内容と注意 | 検証 |
|---|---|---|---|---|
| ~~B1~~ | ~~予測アンサンブル~~ | — | **h=7 のみ実装済 (2026-08)**。h=30 は予測区間を較正できない (被覆 78〜85%) ため意図的に見送り、BuyTimingScorer も Holt 据え置きで不変。詳細と実測は `docs/RESEARCH-2026-08.md` §1 | 完了 |
| B2 | Durable Objects 移行 | 高 | `backend/README.md` の設計どおり実装。**wrangler dev / デプロイ検証が可能な環境が前提** | `npx wrangler dev` + vitest + 段階ロールアウト |
| ~~B3~~ | ~~IDF-lite トークン重み~~ | — | **実装済 (2026-08)**。`tokenIdfWeights` + weighted Jaccard、weights=null は素の Jaccard へ委譲し後方互換。詳細は `docs/RESEARCH-2026-08.md` 3-1 | 完了 |
| ~~B4~~ | ~~Yahoo ランク次元~~ | — | **実装済 (2026-08)**。YahooRank enum + DataStore 保存 (不明値は NONE フォールバック) + 設定 UI 3択チップ。詳細は `docs/RESEARCH-2026-08.md` §4 | 完了 |
| B5 | groupByIdentity の粗ブロッキング | 低 (優先度低下) | 2026-08 の特徴量メモ化で 320 件 2.5s→112ms になり体感問題は解消。比較回数は O(m²) のままなので候補数が数百規模になったら再検討。**ブロッキングキーは文字 2-gram 側に取ること** (トークン一致だけだと 2-gram Dice の腕で救済されるペアを落とす) | run_matcher.sh + 大規模入力の perf 確認 |

### C. Sonnet 向け (機械的・検証容易 — CLAUDE.md の oracle 先行 TDD でそのまま着手可)

| # | 項目 | 内容 | 検証 |
|---|---|---|---|
| C1 | ダークパターン regex 追加 | 消費者庁実態調査 (OECD 2022 分類 + Hidaka 2023) のうち未対応の文言パターンを 1 カテゴリずつ。誤爆ガードの負ケース必須 | proto+test → Kotlin → ParityHarness (現 155 ケース) |
| C2 | 属性 recall 追加 | 色 (現: カタカナ 27 色名 → 正準 16 色)・助数詞・単位の追加。「最寄り正準色へ保守的写像」原則を維持 | run_matcher.sh |
| C3 | parity ケース増強 | 既存ハーネスに境界ケース追加 (全角/半角・空文字・巨大値) | run_all.sh 全 green 維持 |
| C4 | backend テスト追加 | 未カバー経路を worker.test.ts に。2026-08 に DELETE /v1/alerts/{id} (ルート全体が未テストだった)・404 フォールスルー・PII の IPv4 分岐を追加済み | vitest 80+ / tsc |
| C5 | ドキュメント保守 | 実装変更時の README/ARCHITECTURE/RESEARCH ログ同期。数値は必ず実測 | 記載コマンドを実行して一致確認 |
| C6 | ViewModel テスト追加 | 未カバーの ViewModel に FakeDao/IUserPreferences パターンでテスト | brace バランス + 既存パターン照合 |

## 検証基準線 (2026-07 実測)

- Python: **490 passed / 1 skipped** (`popcoon-tdd/`)
- Kotlin parity: **run_all.sh 全 14 ハーネス pass** (run.sh 164 matched / 0 mismatched、core compile 46 ファイル)
- backend: **tsc 0 errors / vitest 80 tests pass**
- i18n: **4 ロケール × 405 strings** (+3 plurals) 完全一致
- ファイル数: Kotlin main 131 / unit test 64 / androidTest 4、Python 38

この基準線を下回る変更は原因を特定するまで push しない。

## 判断待ち: `price_cache` テーブルの扱い (2026-08 発見)

`PriceCacheEntry` / `PriceCacheDao` / `price_cache` テーブル / Hilt の `@Provides` が
一式そろっているが、**読み書きするコードがアプリ内に一切存在しない**。
`PriceCacheDao` はどのクラスにも注入されていない (全ソース grep で確認)。
Entity のコメントは「オフライン閲覧用」とだけ書かれており、オフラインで価格履歴を
見られる機能があるかのように読めるが、そのような経路は無い —
`ProductRepository.getPriceHistory` は毎回 backend を叩く。

本セッションで見つけた「配線されているように見えて実際は到達不能」の 3 例目
(他: `CircuitBreaker` が例外の握り潰しで永久に OPEN にならなかった、
`darkPatternA11yLabel` が呼び出し元ゼロ)。CLAUDE.md の禁止事項
「配線されない機能・デッドコードの追加」に該当する。

**選択肢:**

| | 内容 | 影響 |
|---|---|---|
| A | 削除する | Room version 7 → 8 + `DROP TABLE price_cache` の移行が必要。リリース 0 件なので実データ影響なし。コードは Entity/DAO/Provides/移行内の CREATE を撤去 |
| B | 実際に配線する | `ProductRepository.getPriceHistory` にキャッシュ層を挟む。オフライン時に履歴を表示できるようになる。UI 側の「オフライン表示中」の扱いも要設計 |
| C | 現状維持 | 実態どおりの「未配線」注記だけ残す (2026-08 に実施済み) |

**スキーマ変更を伴う設計判断**のため、CLAUDE.md の方針に従いエージェント側では
A/B を実施していない。C として注記のみ入れてある。
なお `MIGRATION_5_6` に `price_cache` の CREATE TABLE が欠けていた件は
到達性と無関係な移行の欠陥なので別途修正済み。

