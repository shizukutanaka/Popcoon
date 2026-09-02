# Popcoon 現状評価 — 長所 / 短所 / 改善案 (2026-07)

商用品質監査 (30 タスク) + リサーチ実装完了・`main` 公開時点の棚卸し。
数値は全て実測 (推測値なし)。改善案テーブルは将来の Claude (Opus / Sonnet) セッションが
そのままタスクとして拾える粒度で書く。**着手前に必ずリポジトリ直下の `CLAUDE.md` を読むこと。**

## 長所

1. **二重言語 TDD アーキテクチャ** — Python 仕様オラクル (549 tests) が真実の源、Kotlin 本番実装を
   `kotlin_parity/` の 18 ハーネスが**実コンパイル・実行**で照合 (run.sh 202 ケース 0 乖離、`run_compile_core.sh` が 58 ファイルを型検査)。
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
6. **i18n 規律** — 4 ロケール × 365 キー完全一致 + kind/label 分離パターンでオラクル結合と
   ローカライズを両立。TalkBack 対応 (チャート要約読み上げ・mergeDescendants) も監査済み
7. **backend の実ランタイムテスト** — vitest-pool-workers で本物の `src/index.ts` を miniflare 上で
   実行 (108 tests / 5 files)。レート制限はネイティブ binding + KV フォールバックの漸進移行、
   タイミングセーフ比較・ペイロード上限・KV TTL まで監査済み
8. **ドキュメントの誠実さ** — 「CI 4 本稼働」等の虚偽記載を全て実態に訂正済み。見送った
   改善案も理由・出典つきで `docs/RESEARCH-2026-07.md` に記録 (再検討可能性を保存)
9. **通知の抑制設計** — 誤検知対策の 1 サイクル遅延確認 (ユーザー承認済み設計)、
   1 同期あたり通知上限 3 件 + 優先度付け、いずれも純関数化されテスト済み
10. **kotest spec が本環境で実行できる (2026-08)** — Maven Central 遮断下でも、テストが使う
    42 シンボルだけを実装した `kotlin_parity/kotest_shim/` 経由で **45 spec / 648 アサーション**を
    実行 (`-Xfriend-paths` で internal も可視化)。テストファイルの変更は誤りの修正のみ。「CI が無いから kotest は動かせない」を
    要件ごと疑って解消した (`docs/RESEARCH-2026-08.md` §14)
11. **回復性** — 全 ViewModel mutating メソッドに CancellationException-aware try/catch、
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
   2026-08 に静的ゲートを 8 種へ拡張 (override / `R.*` 参照 / `when` 網羅 / テスト参照 /
   `realPrice` の ¥0 除外 / Room 移行チェーン / `take` の前の順序付け / ビルド構成の整合) し、
   実コンパイルも 34 → 58 ファイルへ広げた。いずれも欠陥注入で検出能力を実証済み。

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
   ユニットテスト 63 ファイルはロジック層に偏る (構造上やむを得ないが偏りは事実)。
   2026-08 に **45 spec は実行可能**になった (長所 10) が、残り 18 は production 側が
   Android/Hilt/ktor 依存でコンパイルできず、依然として **一度も実行されていない**

10. **テストコードの腐敗が測定されていなかった (2026-08 に判明)** — 初めて 31 spec を実行した
    ところ 409 アサーション中 **11 件 (2.7%) が偽**だった (その後 542 まで拡大しても追加の失敗は無し)。production のバグは 1 件のみで、
    残り 10 件はテスト側 — 実行日依存のフレーキー 2 件、外部仕様と食い違う定数 4 件、
    オラクルに対して一度も成立しない期待値 2 件、フィクスチャが不変条件を踏めないもの 1 件、
    そして**互いに矛盾する 2 テストの同居** 1 件。CI 有効化の初回実行は赤で始まるはずだった。
    さらに `DarkPatternTextDetectorTest` は演算子優先順位の誤り (`X in c shouldBe true` は
    `X in (c shouldBe true)` と解釈される) で **kotest があってもコンパイルできない**状態だった。
    残り 18 spec には同種の腐敗が同じ比率で眠っている可能性がある (未測定)
7. ~~**Yahoo 会員ランク未モデル化**~~ — 2026-08 に実装済 (B4)。UserContext.yahooRank +
   設定 UI + 4 ロケール文字列を追加し、感謝デー (毎月 11日・22日) を実計算するようにした
8. **名寄せの残課題** — groupByIdentity は JAN なし商品で O(m²) (粗ブロッキング B5 は未着手)。
   IDF-lite トークン重み付けは 2026-08 に実装済み。Model2Vec 等の埋め込みは効果不確実で見送り
9. **レビュー信頼度の入力が浅い** — rating + reviewCount のみ (星分布・レビュー履歴が
   API から取れない)。二峰性検出などの v2 はデータ源が増えるまで実装不能

## 改善案

### A. 人手ゲート (エージェントでは完了不能 — 着手せずユーザーへ案内)

> **⏰ 期限は 2026-08-31 に到来済み (本日 2026-09-01 時点で 1 日経過)。**
> Play の target API 36 / Play Billing 8+ の要件は **コード側では満たしている**
> (`compileSdk`/`targetSdk` = 36、billing 8.3.0)。ただし
> **実ビルドが一度も通っていない** (Android SDK 不在) うえ **Release が 0 件**なので、
> 適合を実証できていない。延長申請の期限は 2026-11-01 (残り 61 日)。
> A1 (CI 有効化) → 実ビルド → A2 (Release) の順が唯一の検証経路。
> **2026-08 に再測定**: workflow ファイルを含む push は
> `refusing to allow a GitHub App to create or update workflow ... without 'workflows' permission`
> で拒否される (硬いゲート)。越えられないのは*実行*だけなので、初回実行が即死する類の
> 設定ミスは `check_build_config.py` (静的ゲート 8 種目) で先に潰した。


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
| C1 | ダークパターン regex 追加 | 消費者庁実態調査 (OECD 2022 分類 + Hidaka 2023) のうち未対応の文言パターンを 1 カテゴリずつ。誤爆ガードの負ケース必須 | proto+test → Kotlin → ParityHarness (現 202 ケース) |
| C2 | 属性 recall 追加 | 色 (現: カタカナ 27 色名 → 正準 16 色)・助数詞・単位の追加。「最寄り正準色へ保守的写像」原則を維持 | run_matcher.sh |
| C3 | parity ケース増強 | 既存ハーネスに境界ケース追加 (全角/半角・空文字・巨大値) | run_all.sh 全 green 維持 |
| C4 | backend テスト追加 | 未カバー経路を worker.test.ts に。2026-08 に DELETE /v1/alerts/{id} (ルート全体が未テストだった)・404 フォールスルー・PII の IPv4 分岐・共有コーパスによる sanitizePii 照合を追加済み | vitest 108+ / tsc |
| C5 | ドキュメント保守 | 実装変更時の README/ARCHITECTURE/RESEARCH ログ同期。数値は必ず実測 | 記載コマンドを実行して一致確認 |
| C6 | ViewModel テスト追加 | 未カバーの ViewModel に FakeDao/IUserPreferences パターンでテスト | brace バランス + 既存パターン照合 |

## 検証基準線 (2026-08 実測 — 記載コマンドを実際に流して確認)

- Python: **549 passed / 1 skipped** (`popcoon-tdd/`)
- Kotlin parity: **run_all.sh 全 18 ハーネス pass** (run.sh 202 matched / 0 mismatched、core compile 58 ファイル)
- **app の kotest spec: 45 specs / 648 passed / 0 failed** (`run_kotest.sh`、シム経由)
- backend: **tsc 0 errors / vitest 108 tests / 5 files pass**
- i18n: **4 ロケール × 365 strings** (+4 plurals) 完全一致
- ファイル数: Kotlin main 133 / unit test 63 / androidTest 4、Python 36

この基準線を下回る変更は原因を特定するまで push しない。
**基準線は `python3 ci/verify.py` が自動照合する** — 手で書き換えず `--update` で同期すること。

> 2026-08 の棚卸しで、この文書自身に陳腐化した数値が 7 箇所あった
> (490→549 / 405→365 キー / 80→108 / 14→18 ハーネス / 46→58 ファイル / 155→202 ケース /
> 静的ゲート 4→7 種)。長所 #8 が「ドキュメントの誠実さ」を掲げている以上、
> **数値の陳腐化はその主張に対する反例**になる。実測へ同期した。

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

## 見送り: 「未配線の Hilt プロバイダ」静的検査 (2026-08 に測定して不採用)

本セッションで「配線されているように見えて到達不能」な実装を 3 件見つけた
(`CircuitBreaker` / `darkPatternA11yLabel` / `PriceCacheDao`)。4 件目を人手で探す
かわりに規則化できないか、`@Provides` / `@Binds` の戻り値型がどこにも注入されて
いないケースを検出する静的検査を試作したが、**採用しない**。

理由: 2 通りの実装を試して、どちらも現行ツリーで誤判定した。

| 試作 | 結果 |
|---|---|
| 注入側を `val x: T` 宣言だけで数える | `OkHttpClient` を「消費者ゼロ」と誤検出。実際は `provideImageLoader(okHttpClient: OkHttpClient)` の**引数**で消費されており、`val` を伴わない引数を数えていなかった |
| `:\s*T` の出現を全走査し、プロバイダ自身の戻り値位置を近傍テキストで除外 | 除外が効きすぎて `OkHttpClient` が 0 のまま、逆に `PriceCacheDao` (真に未配線) が 1 と出た |

Hilt の注入経路はコンストラクタ引数・`@Inject lateinit var`・`@Binds` の引数・
アセンブリ済みの `@AssistedInject` など形が多く、**正しく判定するには型解決が必要**で
正規表現では届かない。本リポジトリの静的ゲートは全て
「クリーンなツリーで誤検出ゼロ + 欠陥注入で検出を実証」を満たしてから配線しており、
この試作はその基準に届かない。誤検出するゲートは無視されるようになり、
既存 6 種の信頼まで損なう。

CI が有効化されれば `detekt` の `UnusedPrivateMember` 等や、Hilt/kapt の
未使用バインディング警告がこの領域を正攻法で扱える。それまでは
`PriceCacheDao` のように**発見したものを実態どおり注記する**運用で足りる。


## 判断待ち: TCO の係数テーブル 2 件 (2026-08 発見)

`inferCategory` の誤検出 (付属品を本体として拾う) は
`infer_tco_category` オラクル + `TCOCAT` パリティで修正済み。
一方、**`calculate_tco` の係数そのもの**に残る次の 2 点は、変更すると Python
オラクルとゴールデンベクタが動くため、CLAUDE.md の「設計判断は計画提示→承認」に従い
エージェント側では変更していない。

### (1) `ENERGY_DB` が推定可能 7 カテゴリのうち 5 つしか持たない

`infer_tco_category` が返し得るのは inkjet_printer / laser_printer / smartphone /
laptop / refrigerator / air_conditioner / coffee_capsule の 7 種。
このうち **smartphone と coffee_capsule は `ENERGY_DB` に項が無く電気代 0 円**として
計上される (`.get(category)` のフォールバック)。TCO の定義上は過小評価。

参考値 (追加するとしたらの試算、電力単価 27 円/kWh は既存実装の定数):

| カテゴリ | 想定 | (watts, h/day) | 年間電気代 |
|---|---|---|---|
| smartphone | 電池 ~15Wh を毎日充電 | (5, 3.0) | `5*3.0*365/1000*27` = 147 円 |
| coffee_capsule | 抽出時 ~1300W を 1 日 6 分 | (1300, 0.1) | `1300*0.1*365/1000*27` = 1,281 円 |

smartphone は 5 年で 735 円と誤差の範囲だが、coffee_capsule は 5 年 6,405 円で
本体価格 (8,000〜20,000 円) に対し無視できない。
**判断が要る点**: 出典をどう取るか (実測値・メーカー公称・省エネ性能カタログ)。
CLAUDE.md は外部仕様値を出典なしに変えることを禁じているため、値の根拠を
`docs/RESEARCH-*.md` に記録できる形でないと入れられない。

### (2) smartphone の残存価値式が既定年数 (5 年) でちょうど 0 になる

`RESIDUAL_RATE_DB["smartphone"] = max(0, 0.5 - y*0.12)` はゼロ交差が y≈4.17 年。
`calculate_tco` の既定 `years=5` は `ProductDetailViewModel` が使っている値なので、
**実アプリで smartphone の残存価値は常に 0 円**になる。

| 保有年数 | smartphone | laptop | その他 |
|---|---|---|---|
| 1 年 | 38.00% | 32.00% | 4.00% |
| 3 年 | 14.00% | 16.00% | 2.00% |
| 4 年 |  2.00% |  8.00% | 1.00% |
| 5 年 |  0.00% |  0.00% | 0.00% |

これは `inferCategory` に smartphone 検出を足したときの根拠コメント
「5年で残存価値がほぼ0になる他カテゴリと異なり、中古スマホ市場は現実的な残存価値を持つ」
と食い違う。**式が意図どおりでないか、コメントが過大主張しているかのどちらか**で、
少なくとも現状は片方が誤り。中古スマホ相場 (5 年落ちで定価の 2〜3 割) を根拠に
係数を寝かせる (例 `max(0, 0.5 - y*0.06)`) 案が考えられるが、
これは製品挙動の変更 + ゴールデン再導出になるので承認が要る。

**エージェント側の対応**: 数値は変えていない。上記の食い違いを事実として記録するに留める。

## 判断待ち: 越境EC 試算の課税体系 (2026-08 発見)

`CustomsSimulator` / `popcoon_core.simulate_customs` の課税価格が
「商品代 + 送料」で、日本の 2 体系 (商業輸入 = CIF / 個人輸入 = 海外小売価格 × 0.6・
送料含まず) のどちらとも一致しない。免税ラインの 16,666 円は個人輸入の 0.6 掛けから
導いた値なので、閾値と課税ベースの体系が食い違っている。加えて、課税価格 1 万円以下でも
免税されない品目 (革製カバン・ハンドバッグ・手袋、編物製衣類、革靴・本底が革製の履物、
スキー靴) を区別できず無条件に免税と判定する。

誤差の実測と一次出典は `docs/RESEARCH-2026-08.md` §10 に記載
(布製衣類で +24.3% 過大、革靴で −19.1% 過小など、方向が両側にある)。

| 案 | 内容 | 影響 |
|---|---|---|
| A | 個人輸入体系へ統一 (`課税価格 = 商品代 × 0.6`、送料は課税価格に含めない、免税ラインを 10,000 に) | 最も正確。オラクル + ゴールデン + 差分/メタモルフィック/fuzz が動く。`verdict` が CHEAPER 寄りに変わる |
| B | A に加えてカテゴリへ素材次元を足し (例「衣類(編物)」「バッグ(革製)」「靴(革製)」)、少額免税の対象外品目を課税扱いにする | カテゴリ体系と i18n 文字列の追加を伴う。UI の選択肢が増える |
| C | 式は変えず、`customs_disclaimer` に「個人輸入の 0.6 掛けと免税対象外品目は未反映」を明記する | 4 ロケール同時のみ。数値の誤りは残る |

**製品挙動変更 + ゴールデン移行**のため、CLAUDE.md の方針に従いエージェント側では
A/B/C いずれも実施していない。到達不能だった `0.05` 既定値の除去 (挙動不変) のみ実施済み。


## 完了: Room エンティティの置き場所による巻き添え (2026-08 に解消)

`app/src/test` の 63 spec のうち残っていた詰まりの最多要因は Room エンティティ
`WatchlistItem` だったが、**本当の制約は Room ではなくファイルの置き場所**だった:

- `androidx.room` を import する production ファイルは 2 つだけ
  (`data/db/PopcoonDatabase.kt` / `di/DatabaseModule.kt`)
- `WatchlistItem` が要求するのは `@Entity` / `@Index` / `@PrimaryKey` の**アノテーション 3 つのみ**
  (本文書が既に「プロセッサ不在では実物も不活性」と判定している、嘘をつけない種類)
- しかしエンティティが `PopcoonDatabase.kt` に同居しているため `data.db` パッケージ全体が
  Android 依存として扱われ、純ロジックのファイルまで巻き添えになっていた

**production を一切変更せずに解消した。** `RStub.kt` と同じ方式で、
`run_compile_core.sh` が **実ファイルから毎回 `@Entity` ブロックを切り出して**
`EntityStub.kt` を生成する。コピーではなく実ソースの部分集合なので、本体を変えれば
次回の実行に自動反映される (ドリフト不可能)。DAO 等の未提供宣言を参照するファイルが
混ざれば実コンパイルが失敗して顕在化するので、黙ってカバレッジが減る方向には倒れない。

結果: 実コンパイル **48 → 51 ファイル** (`SmartCartService.kt` / `WatchlistSort.kt` が復帰)、
kotest **36 → 38 spec / 542 → 568 アサーション**、いずれも 0 failed。

## 完了: テスト可能な純ロジックを Android 依存ファイルから切り出した (2026-08)

残り spec の一部は、**依存が本当に必要なのではなく、純粋な関数が Compose/Android の
ファイルに同居している**ために動かせなかった。`WidgetVerdict` / `PriceSyncPlanner` で
確立済みの「純ロジックを別ファイルへ切り出す」パターンを 4 件に適用した。
同一パッケージ内の分割なので**呼び出し側の import すら変わらず**、移した関数は
実コンパイル対象に入るため検証は厚くなる方向にしか倒れない。

| 宣言 | 元 | 移動先 | 解錠された spec |
|---|---|---|---|
| `watchlistBuyVerdict` | `WatchlistScreen.kt` (Compose) | `ui/screens/watchlist/WatchlistBuyVerdict.kt` | `WatchlistBuyVerdictTest` |
| `PriceChartRange` / `filterByRange` / `plottableRecords` | `PriceChart.kt` (Compose Canvas) | `ui/components/PriceChartData.kt` | `PriceChartTest` |
| `CSV_FORMULA_TRIGGERS` / `csvEscape` | `PriceHistoryCsvExporter.kt` (android.content) | `feature/export/CsvEscape.kt` | `CsvEscapeTest` |
| `PopcoonWidgetLogic` | `PopcoonWidget.kt` (Glance) | `widget/PopcoonWidgetLogic.kt` | `WidgetSaleLogicTest` |

結果: 実コンパイル **51 → 55 ファイル**、kotest **38 → 42 spec / 568 → 602 アサーション**、0 failed。

**追加 1 件 — 定数の置き場所**: `RobotsTxt.kt` は import ゼロの純ロジックで実コンパイル対象
だったが、`RobotsTxtTest` は `USER_AGENT` / `DENY_ALL_ROBOTS` が ktor 依存の
`FallbackScraper` の companion にあるせいで実行できず、**RFC 9309 準拠の判定が
どのハーネスでも検証されていなかった** (Amazon の唯一のデータ源が FallbackScraper で
あることを踏まえると重要な空白)。両定数は robots.txt プロトコル側の概念なので
`RobotsTxt` へ移し、production 3 箇所とテスト 3 箇所の参照を更新。
kotest 42 → **43 spec / 618 アサーション**。欠陥注入 (`DENY_ALL_ROBOTS` を空 Disallow =
全許可に) で `RobotsTxtTest` が落ちることを確認済み。

**追加 2 件目 — バックアップ往復マッパー**: `toBackupEntry` / `toWatchlistItem` を
`WatchlistBackupManager.kt` (android.content / Hilt) から `WatchlistBackupMapper.kt` へ切り出し、
実コンパイル 55 → **56 ファイル**。往復マッピングはデータ欠落の直撃点で、
`WatchlistItem` と `WatchlistBackupEntry` のフィールド不整合が型検査で captured されるようになった。
ただし `WatchlistBackupManagerTest` 自体は解錠できていない — 後述の制約による。

### 残る硬い制約: kotlinx.serialization コンパイラプラグインが無い

`WatchlistBackupManagerTest` と `PriceRecordSerializationTest` は
`WatchlistBackupEntry.serializer()` / `PriceRecord.serializer()` を呼ぶ。これらは
**コンパイラプラグインが生成する**メンバーで、`@Serializable` アノテーションだけでは存在しない。
`kotlin-serialization-compiler-plugin` の jar は Gradle 同梱ライブラリにも
`kotlin-compiler-embeddable` 内部にも無く (実際に検索・jar 内走査で確認)、
Maven Central が遮断されているため取得もできない。**アノテーションスタブでは代替できない
種類の依存**なので、この 2 spec は CI 有効化まで実行不能。
欠陥注入 (`csvEscape` の数式ガードを外す → `CsvEscapeTest` が落ちる) で、移した関数が
本当に spec に守られていることを確認した。
`check_price_guard.py` が `PriceChart.kt` を報告するようになった (¥0 除外が
`plottableRecords` と一緒に別ファイルへ移ったため) — 全読み出しが `plottableRecords`
経由であることを根拠に ALLOWLIST へ登録。

**見送り 1 件**: `SearchRow` (`SearchScreen.kt`) は `warnings: List<UiText>` を持ち、
`UiText` が `@Composable fun asString()` を持つ Compose 束縛の型なので、切り出しても
`SortAndFilterTest` は解錠できない。CI 有効化後の領分。
