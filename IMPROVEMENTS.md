# Popcoon 改善メモ (deep research)

コードベース全層 (build / data・network / feature・domain / Python TDD parity / UI・Compose /
CI) を調査した結果と、適用した改善・今後のバックログ。

## 製品改善ループ (Tier 21: ConformalInterval の実行パリティ — 監査漏れの是正 — 2026-06-14)

未パリティ検査の純関数を洗い直し。Tier 10 で `predict` の margin を「Kotlin 拡張」と断じたが、
**誤り** — `proto_conformal_interval.py` という Python 参照が存在した。`ConformalInterval`
(本番 `PricePredictionEngine.predictionMargin` の置換候補) を実行パリティ検査に追加。
- 注目点: `k = ceil((n+1)*(1-alpha))` の**浮動小数点境界** (例 n=9,α=0.1 → 10*0.9 が
  9.0000…2 に丸まり ceil=10 で最大残差ブランチに切替) は、読みでは見落としやすく実行検証向き。
- ハーネス (run.sh) に CONFORMAL 8 ケース (境界・空・単一・負値含む) を追加し
  `conformal_margin` と照合 → **47/47 一致** (旧 39 + 8)。Kotlin/Python で同一の IEEE754 結果を確認。
- バグは無かったが、(1) 監査漏れの是正 (2) 数値敏感コードの実行検証 (3) CI 自動化、の 3 点で前進。

## 製品改善ループ (Tier 20: CI 有効化のターンキー化 + 検証の CI 配線 — 2026-06-13)

`RobotsTxt.isAllowed` を監査 (REP 19 ケースを実行) → **バグ無し・既存テストも網羅的**で、
ローカル検証可能な高価値の改善余地が概ね尽きたと判断。残る高価値作業は CI 依存
(app のコンパイルが前提)。そこで本セッション最大の制約 = CI 有効化に投資した。
- **`ci/enable.sh`**: 管理者が 1 コマンド (`bash ci/enable.sh && git push`) で
  `ci/android.yml` を `.github/workflows/` へ移動・有効化できる。
- **`ci/android.yml` にジョブ追加**: 本セッションの検証群を CI に配線。
  - `parity`: `kotlin_parity/run_all.sh` (SDK 不要で 6 純関数 + 3 マッパーを実行検証)。
  - `backend`: backend の vitest (これまで CI 経路ゼロだった — アラート/PII/ページ/検証テスト)。
- **`kotlin_parity/run_all.sh`**: 全ハーネス (parity 39 + Rakuten/Yahoo/JSON-LD) を集約実行、
  どれか失敗で非ゼロ終了。ローカルで全 green を確認。
- ねらい: 管理者が CI を入れた瞬間に、本セッションで積んだ検証 (Python 300 + Kotlin パリティ +
  backend) が**自動で回る**状態にしておく。CI 有効化の心理的・手順的コストを最小化。

## 製品改善ループ (Tier 19: FallbackScraper の JSON-LD 在庫抽出 — 2026-06-13)

まず「SortAndFilter の在庫切れ除外が実際に効くか」を実行検証しようとしたが、フィルタ本体は
`if (excludeOutOfStock && p.stockCount == 0)` で**自明に正しく** (null は除外されない)、
R/StringRes/SearchRow をスタブして実行するのは検証劇場と判断しスキップ (= 検証の費用対効果を
ソクラテス的に問うた)。代わりに在庫復元を別経路へ拡張:

`FallbackScraper` (API 失敗時に JSON-LD を読む) は schema.org の `offers.availability` を
取りこぼし、スクレイプ商品の `stockCount` も常に null だった。
- ktor 非依存の純粋関数 `stockFromAvailability(raw)` を新設し、`OutOfStock/SoldOut/Discontinued`
  (URL 形式含む) → 0、それ以外 → null に変換。parseProductSchema から呼ぶ (2 行追加)。
- **検証**: `run_jsonld.sh` で単体ビルド・実行。全 schema.org 値の写像に加え、nested
  `offers.availability` が flat キー正規表現で抽出され 0 に写ることを end-to-end でアサート green。

これで楽天/Yahoo/フォールバックの 3 経路で在庫が復元され、SortAndFilter の在庫切れ除外
(デフォルト ON) が実データで機能する。残るは Amazon PA-API (Offers.Availability、要 CI 検証)。

## 製品改善ループ (Tier 18: Yahoo の在庫データ復元 + マッパー抽出 — 2026-06-13)

Tier 17 の楽天と同じパターンを Yahoo に適用。`YahooClient` は V3 itemSearch の `inStock`
真偽を DTO で取りこぼし `stockCount` を常に null にしていた。
- 変換を純粋関数 `YahooMapper.kt` (ktor/BuildConfig 非依存) に抽出、`YahooClient` は IO のみに。
  `inStock==false → stockCount=0` を写す。listPrice(defaultPrice 優先)/shipping(code==2 無料) の
  既存ロジックも mapper に移設。
- **検証**: `run_yahoo.sh` で単体ビルド・実行。在庫/listPrice/shipping/既存フィールドを全アサート green。
- 正直な限界: `inStock` の正確なフィールド名は実 API で未確認。だが `ignoreUnknownKeys` +
  nullable 既定のため**名前が違っても無害** (従来同様 null)。回帰リスク無し・上振れのみ。

## 製品改善ループ (Tier 17: 楽天の在庫データ復元 — 幻フィールドの解消第一歩 — 2026-06-13)

Tier 8/9 で指摘した「データ抽出層が `stockCount` 等を埋めず、機能が死蔵」問題に着手。
`RakutenClient` は楽天 API が返す `availability` (1=在庫/0=在庫切れ) を DTO で取りこぼし、
`stockCount` を常に null にしていた → `SortAndFilter` の在庫切れ除外 (`stockCount==0`,
デフォルト ON) が不発だった。
- 変換ロジックを ktor/BuildConfig 非依存の純粋関数に切り出し (`RakutenMapper.kt`)、
  `RakutenClient` は IO のみに。DTO に `availability` を追加し、`availability==0 → stockCount=0` を写す。
- **検証**: `RakutenMapper.kt` + `Product.kt` を Gradle 同梱コンパイラで単体ビルド・実行
  (`popcoon-tdd/kotlin_parity/run_rakuten.sh`)。在庫/在庫切れ/既存フィールド保全を全アサート green。
  RakutenClient の IO 部 (ktor) のみ未コンパイル検証 (変更は DTO 削除 + `.toProduct()` 呼び出しの機械的差し替え)。
- `pointsBack` は `pointRate` から算出可能だが `product.totalPrice` を変え UI 全体に波及するため、
  CI でレンダリング検証できるまで保留 (TODO をコード/本書に明記)。これが「検証可能な分だけ進める」方針。

## 製品改善ループ (Tier 16: backend /v1/history の入力検証不足 — 2026-06-13)

`POST /v1/history` は `recorded_at` の**存在**しか見ず、`list_price` の型も未検証だった。
だが `appendPriceHistory` は `recorded_at` を `localeCompare` で**文字列ソート/dedup** し、
`evaluateAlerts` は `history[0]` を latest として扱う。→ 不正な timestamp ("today" 等) を
送られると履歴順序が時系列とズレ、**偽の latest** が予測/アラート全体を汚染する。
- 修正: `isValidIsoUtc()` で正準 ISO-8601 UTC ("...Z") のみ受理 (Kotlin の Instant.toString()
  形式に一致)。`list_price` も非負数を必須化。
- 検証: Node 再現 (10/10) — 正準値受理、garbage/ローカルオフセット/日付のみ/不能日付を拒否。
  かつ "today" が localeCompare で誤って先頭に来る (= 偽 latest) ことを実証。vitest 契約テスト追加。

## 製品改善ループ (Tier 15: backend クラッシュ受信の PII 漏れ — 2026-06-13)

`/v1/crash` の「個人情報チェック (二重チェック)」が **`body.sanitized_stack` だけ**を検査し、
保存は **body 全体** (`JSON.stringify(body)`) だった。→ `sanitized_stack` をクリーンにしつつ
他フィールド (例 `device_id:"user@email.com"`) に PII を入れれば**チェックをすり抜けて永続化**。
プライバシー (「個人情報なし」「テレメトリ未送信」) を中核差別化とする製品の致命的な穴。
- 修正: 純関数 `containsPotentialPii(payload)` を抽出し、**payload 全体**をメール/IPv4 正規表現で
  走査。ハンドラはこれで弾く。
- 検証: Node 再現で旧実装は他フィールドの PII を見逃し新実装は捕捉、正当なクラッシュは受理 (8/8)。
  vitest にも同契約テストを追加。

## 製品改善ループ (Tier 14: backend の KV 取りこぼし — GDPR 削除/アラート評価 — 2026-06-13)

backend 監査の続き。`KV.list` は 1 回で最大 1000 キーしか返さない (cursor で続きを取る) が、
2 箇所で cursor を辿らず**最初の1ページしか処理していなかった**:
- `/v1/device` 削除: アラート総数が 1000 件超のとき、対象デバイスのアラートを**消し残す** →
  「全データ削除 (GDPR Article 17)」「サーバー側も即時削除」という製品の明示的約束に違反。
- `evaluateAlerts` (`limit:100`): **101 件目以降のアラートが永遠に発火しない** → 中核機能が
  スケール時に黙って壊れる。
- 修正: `listAllKeys(ns, prefix)` ヘルパで cursor を list_complete まで辿り全キーを集約。両所で使用。
- 検証: 依存なし Node シミュレーション (2500 件・victim を 3 ページに分散) で実証 —
  旧単一ページ削除は victim の 2 件を残す (GDPR 違反を再現)、新実装は完全削除・他者無傷。
  vitest にも同契約のページネーションテストを追加 (CI で実行)。

## 製品改善ループ (Tier 13: backend のアラート誤発火バグ — 2026-06-13)

ユーザー指示で「製品の長所短所改善点」へ視点を戻し、未検証だった `backend/`
(TypeScript Cloudflare Worker) を監査。**実害のあるバグを検出・修正**:

### バグ: ATL (過去最安) アラートの誤発火
`evaluateCondition` の `atl` ケースが `history.slice(0, -1)` で**最古**の履歴を除外していた。
だが本番 (`evaluateAlerts`) では history は**新しい順**で `current === history[0]`。
正しくは current(=[0]) を除いた `slice(1)` の最小値と比較すべき。
- 影響: **最古の価格が真の最安だった場合、current が最安でないのに「過去最安」通知を誤発火**。
  Node 再現で実証 (history=[100,120,90], current=100 → 旧: true 誤発火 / 新: false 正)。
  信頼性を売りにする製品が偽の「最安」プッシュを送るのは致命的な信頼毀損。
- テストも同じ誤ロジックを複製し、かつ current を history 外に置く**本番と異なる契約**で
  書かれていたため見逃していた (customs と同型: テストがバグを承認)。
- 修正: 本番を `slice(1)` に。テストを本番契約 (current=history[0]) に揃え、
  「最古が真の最安」回帰ケースを追加。依存なしの Node 検証で全 8 アサーション green。

## ソクラテス監査 (Tier 12: 実行パリティを看板機能まで拡張 — 2026-06-13)

Tier 11 でスカラー関数 (customs/eco) の実行パリティを確立した。だがそれは「楽な 2 関数」。
最重要の `BuyTimingScorer` (プロダクトの存在理由) と数値リッチな `predict`/`darkpattern` は
依然「読み比べ」止まり — まさに customs バグを最初に見逃しかけた脆い方法。実行検証へ格上げした。

### 障壁の実測: 「serialization plugin が無いと PriceRecord はコンパイルできない」→ **偽**
履歴依存関数は `PriceRecord` (Product.kt, `@Serializable`) を要する。plugin はキャッシュに
無かったが、**実測すると plugin 無しで Product.kt はコンパイル成功** (exit 0)。理由: plugin 不在でも
`@Serializable` は無害なアノテーションに過ぎず、ロジックは `.serializer()` を呼ばないため
serialization ランタイム jar のみでビルドできる。→ スタブ不要、本物のソースで検証。

### 成果: 移植済み純関数 **6 種すべて**を実行パリティ化
`popcoon-tdd/kotlin_parity/` のハーネスを履歴依存関数まで拡張 (Product.kt + 予測/季節/カレンダー
等 10 ファイルを同梱コンパイラでビルド、31 classes)。結果 **39 ケース全一致**:
- scalar: customs 11 + eco 7
- 履歴 7 シナリオ × {darkpattern, predict, buytiming} = 21
- ダークパターン 4 種 (常設/参考価格/値上げ/端数) すべて発火し一致。buy-timing は
  67/72/55/62/67/42/null と多様な出力すべてが Python オラクルと一致。

→ Tier 10 で「読んで一致」とした BuyTimingScorer の判断が、**コンパイル&実行で裏付けられた**
(careful reading と executed check が一致 = 監査手法の相互検証)。看板機能のパリティは
もはや主張ではなく**実行可能な事実**。

### 限界 (正直に)
これは純粋ロジックの単体コンパイル/実行であり、**full app (Compose/Room/Hilt/DI) の
コンパイルは検証しない**。それは CI (`.github/workflows/`, 管理者の `git mv` 待ち) のみ。

## ソクラテス監査 (Tier 11: 「検証できない/CIは管理者しか有効化できない」二つの前提を実測 — 2026-06-13)

3 ラウンド連続で「Kotlin はコンパイルできない」「CI は `workflows` 権限が無いので
管理者しか有効化できない」と主張してきた。ゴールに従い**両前提を実測**した。

### 前提1「CI は自分には有効化できない」→ 実測の結果 **真** (検証済み)
`ci/android.yml` を `.github/workflows/` へ `git mv` してコミットし push を試行 →
リモートが明示的に拒否:
`refusing to allow a GitHub App to create or update workflow ... without 'workflows' permission`。
→ 前ラウンドまで「継承した主張」だったものを、本セッションのトークンで**実証**。コミットは
reset で取り消し済み (push 不可なものでブランチを汚さない)。CI 有効化は管理者の `git mv` 待ちで確定。

### 前提2「Kotlin はローカルでコンパイル/実行できない」→ 実測の結果 **偽** (スカラー関数は可能)
パリティ最重要のスカラー関数は依存が極小だった:
`CustomsSimulator` (kotlin.math のみ) / `EcoEthicsScorer` (import ゼロ) → Android/Room/serialization 非依存。
Gradle 同梱の `kotlin-compiler-embeddable-2.0.20.jar` で**追加 classpath ゼロでコンパイル成功**。
→ 「Kotlin は CI でしか検証できない」は全 Kotlin には当てはまらない。

### 成果: パリティを「文書上の主張」から「実行可能な検証」へ (Tier 9/10 の核心に決着)
`popcoon-tdd/kotlin_parity/` に**実行可能なクロス言語パリティハーネス**を追加:
- `run.sh`: 同梱コンパイラで本物の Kotlin (CustomsSimulator/EcoEthicsScorer) + ハーネスを
  コンパイル → JVM 実行 → 出力を `compare_oracle.py` に渡し、同じ入力で Python オラクルを
  再計算して照合。**Android SDK 不要**。
- 結果: **18 ケース全一致 (customs 11 + eco 7)**。Tier 9 で修正した CustomsSimulator の
  verdict バグが、読み比べではなく**実際のコンパイル&実行で正しいと確認**された
  (食品免税の掘り出し物=CHEAPER / 中途半端=NOT_RECOMMENDED 等)。
- 嵌った点を README に記録: JVM の `stdout.encoding=ANSI_X3.4-1968` で日本語が `?` に化け、
  Python が壊れた入力で再計算して偽の mismatch を出していた → `-Dstdout.encoding=UTF-8` で解消。

### 残り (拡張)
- **履歴依存関数** (`BuyTimingScorer`/`PricePredictionEngine`/`DarkPatternDetector`) のハーネス化:
  `PriceRecord` (Product.kt, kotlinx-serialization plugin 依存) のコンパイルが必要。
  `-Xplugin` + serialization ランタイム追加、または最小 `PriceRecord` スタブで拡張可能。
- これで Tier 10 で「故意に未実装」とした Kotlin consumer の代替が**スカラー関数では実現**した
  (CI/SDK 無しで実行可能)。CI 有効化後は full app テストへ統合。

## ソクラテス監査 (Tier 10: 「監査」自体の網羅性への反問 — 2026-06-13)

前ラウンドの「パリティ監査」を反問した: 「最重要の関数を監査したか、それとも楽な関数だけか?」
答えは後者だった。customs/dark-pattern/eco/predict は見たが、**プロダクトの存在理由である
看板機能 `BuyTimingScorer` を監査していなかった**。また `eval_condition` を
「一致 (Kotlin 側は要追検証)」と言いつつ確認していなかった。

### BuyTimingScorer 監査 (buy_timing_scorer.py vs BuyTimingScorer.kt)
6 シグナルすべてを 1 行ずつ照合 — **コア経路は忠実なパリティ**:
ベース50 / ATL近接(≤0→30,≤0.1→22,≤0.3→12,≥0.9→-15) / トレンド(±) /
定価割引(40/25/10) / ボラティリティ(cv 0.02/0.05/0.25) / 履歴(90/30) /
ダークパターン罰則(-8) / verdict(≥70,≤35) / confidence(90/30) — 全一致。

検出した乖離 2 件 (いずれも記録):
1. **潜在**: Python のダークパターン罰則には `FAKE_SALE → -4` 分岐があるが、Kotlin に該当
   `WarningType` が無く未処理。現状は両言語とも `detect_dark_patterns` が FAKE_SALE を
   生成しないため**不活性**だが、その検出を将来追加すると乖離する。
2. **意図的拡張**: Kotlin は `today != null` のとき `signalUpcomingSale` / `signalSeasonalDow`
   を加算する (Python に無い)。ヘッダの「Python実装と同一式」は `today=null` 経路でのみ真。

### パリティを「文書上の主張」から「実行可能な契約」へ (Python 半分を実装)
Tier 9 で判明した核心 — *parity を強制する機構が無い* — に着手。検証済みオラクルから
**言語中立な JSON fixture を生成する仕組み**を追加した:
- `gen_parity_fixtures.py`: customs(8) / eco(6) / buy_timing(4) の固定入力→期待出力を
  `popcoon_core` から算出し `parity_fixtures.json` に書き出す (計 18 ケース)。
- `test_parity_fixtures.py`: その JSON を読み、オラクルと毎回突き合わせる**能動的セルフチェック**
  (fixture の陳腐化を防ぐ)。→ Python suite 296 → **300 passed**。
- 履歴構築規約 (`product_key='k'`, AMAZON, `recorded_at=2026-01-01+i 日 UTC`) を JSON に明記し、
  Kotlin 側が同一の決定論的構築で再現できるようにした。

### 残り (Kotlin consumer — 故意に未実装)
パリティの**真の強制**には Kotlin 側が同 `parity_fixtures.json` を読んで
`CustomsSimulator`/`EcoEthicsScorer`/`BuyTimingScorer` の出力を照合する必要がある。
だが**コンパイル検証できない Kotlin を投入しない**という本セッションの一貫した方針に従い、
今ラウンドでは書かない (customs バグの再来を避ける)。具体設計だけ確定:
- `parity_fixtures.json` を `app/src/test/resources/parity/` にコピー (生成時に同期、CI で diff チェック)。
- `ParityContractTest.kt` (kotest) が kotlinx.serialization で読み込み、scalar 関数
  (customs/eco) から照合開始。buy_timing は価格列から上記規約で `PriceRecord` を再構築。
- これは SDK/CI 有効化後の最初のタスク。fixture 側は既に検証済みなので Kotlin は照合を書くだけ。

## ソクラテス監査 (Tier 9: 「検証できない」という自分の前提への反問 — 2026-06-13)

前ラウンドで「Android SDK も CI も無いから何も検証できない」と繰り返し主張していた。
ゴール『ソクラテス問答を行い改善する』に従い、**その前提自体を反問**した:
「本当に環境を確認したのか? それとも確認せず思い込んでいるだけか?」

### 反問で判明したこと (前提は誤りだった)
環境を実測すると、検証可能な層が存在した:
- **`popcoon-tdd/` は Python リファレンス実装** (mutation/chaos/differential/metamorphic/fuzzing
  /golden 等の網羅テスト)。**Python は動く** → `pytest` で **290 passed, 1 skipped** を即時取得。
  本セッションが「持っていない」と言い続けた "real signal" は、実は最初から取得可能だった。
- `backend/` は TypeScript Cloudflare Worker (node22 はあるが node_modules 不在 → offline 不可)。
- Gradle は offline 動作可 (8.11.1, kotlin-compiler-embeddable 同梱)。`:app` のみ Android SDK 必須。

### さらなる反問: 「Python parity」は誰が保証しているのか?
プロジェクトの看板は "Python parity" (Kotlin 移植が Python と一致)。だが **検証機構が無い**:
`test_differential.py` は **Python 最適化版 vs Python naive 版**を比較するだけで、コメント自身が
『「Kotlin本体との整合性」の predecessor』と告白している。golden snapshot も Python 内部の
ハッシュ固定のみで、Kotlin はそのfixtureを一切消費していない。→ **Kotlin は黙って乖離し得る。**

### 検証済みオラクルで Kotlin↔Python パリティ監査 (5 関数を読み比べ)
| 関数 | 結果 |
|---|---|
| `predict_price` / `PricePredictionEngine` | ✅ 一致 (Holt α=0.3/β=0.1, IQR, buy_prob, confidence)。Conformal/季節分解は Kotlin 拡張 |
| `detect_dark_patterns` / `DarkPatternDetector` | ✅ 一致 (常設90%/参考1.5x/値上げ1.1x/端数80-99)。drip/text は Kotlin 拡張 |
| `score_eco_ethics` / `EcoEthicsScorer` | ✅ 一致 (国別CO2・労働定数, 式, 丸めすべて一致) |
| `eval_condition` | ✅ 一致 (Kotlin 側は別途要追検証だが式は同型) |
| `simulate_customs` / `CustomsSimulator` | ❌ **乖離 (バグ検出)** |

### 検出バグ: CustomsSimulator の verdict 分岐順 (修正済み)
Kotlin は「`完全一致`」とコメントしつつ、食品/化粧品の `NOT_RECOMMENDED` 判定を
**最優先に繰り上げて**いた。結果、国内価格が設定された食品/化粧品は **価格に関係なく常に
NOT_RECOMMENDED**。検証済み Python は逆で、免税級の掘り出し物 (国内の70%未満) は CHEAPER が勝つ。
- **しかも Kotlin テストがこのバグを固定していた**: `simulate(10k,2k,食品,国内50k)` を
  `NOT_RECOMMENDED` と断言。Python オラクルでは同入力は `CHEAPER` (total=12,000・免税)。
  → 「緑のチェックは結果ではなく仮説」の実例 (テストがバグを承認していた)。
- **対応**: ①Kotlin の `when` を Python の分岐順に修正 ②バグを固定していた Kotlin テストを
  オラクル準拠 (CHEAPER) に訂正し、正しい NOT_RECOMMENDED 経路 (中途半端な節約帯) の
  ケースを追加 ③**Python オラクル側に verdict 分岐順のクロス言語コントラクトを追加**
  (`test_customs_verdict_branch_order`, 6 ケース) → ローカルで実行・検証可能。期待値は
  すべて `popcoon_core` で算出。Python suite: 290 → **296 passed**。

### 残課題 (今後)
- **クロス言語 golden ブリッジ (parity の真の強制)**: Python から言語中立な JSON fixture
  (固定入力→期待出力) を書き出し、Kotlin テストが同 fixture を消費して一致検証する仕組み。
  これで "parity" が文書上の主張から実行可能な契約になる。Python 側生成器は即検証可、
  Kotlin consumer は CI/SDK 有効化後に配線 (今は未コンパイル surface を増やさない)。
- **最重要は依然 CI/SDK の有効化**: `:app` の Kotlin/Compose 層は今も未コンパイル。
  Python 層が緑でも Android 本体の検証は別問題。`git mv ci/android.yml .github/workflows/` が前提。

## 製品分析 (Tier 7: 長所・短所・不足機能の洗い出しと実装)

プロダクトとしての強み・弱み・不足機能を棚卸しし、価値が高く自己完結する
ギャップを実装した。

### 長所 (差別化の核心)
- **買い時判定の独自性**: `BuyTimingScorer` (ATL近接・トレンド・変動率・季節性) +
  `PricePredictionEngine` (Holt線形 + Conformal 区間 + 季節分解) は競合14アプリ非搭載。
- **ダークパターン暴露**: 価格系 + テキスト5カテゴリ検出 + `ReviewTrustScorer` (統計的サクラ検出)。
- **長期コスト可視化**: `TCOCalculator` (消耗品・電力)、`BundlePackDetector` (実質単価)、
  `PointSimulator` (ポイント還元後実質価格)。
- **プライバシー優先**: オンデバイス完結、レビュー本文を端末外に送らない。
- **Python TDD オラクル**: アルゴリズムは Python 正本と差分テストでパリティ保証 (290 tests)。
- **4ロケール対応** (ja/en/ko/zh-rCN)、ウォッチリスト + 目標価格アラート + ウィジェット + バーコード。

### 短所・不足 (今回修正)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 47 | **不足機能** | `EcoEthicsScorer` (CO2・労働権利スコア、Python パリティ・テスト済み) が UI から一切参照されず **死蔵** | 商品詳細に `EthicsCard` を新設し配線。原産国判明時のみ算出 (不明時は無意味な定数のため非表示)。スコアロジックは不変、表示のみ追加 |
| 48 | **不足機能** | ホーム画面ウィジェットの判定が全件 `"NEUTRAL"` 固定で無意味 | `WidgetVerdict` 純関数を新設 (目標到達/追加時比±5% で BUY_NOW・WAIT・NEUTRAL を導出) + 単体テスト。完全な履歴を要する詳細画面スコアラとは別の軽量判定として明示 |
| 49 | **テスト基盤** | `testOptions` に `useJUnitPlatform()` 不在 → Kotest spec は JUnit4 ランナーで **1件も発見されず**、Kotlin 単体テスト群 (200+) が実質未実行だった | `unitTests.all { it.useJUnitPlatform() }` を追加。これで `ci/android.yml` の単体テストジョブが初めて意味を持つ |
| 50 | **CI** | `gradlew` の実行ビットが欠落 (100644) | `100755` に修正。`./gradlew` がフレッシュチェックアウトで動作 |

### 確認した非ギャップ (誤検知防止メモ)
- **目標価格設定 UI**: `TargetPriceDialog` / `TargetPriceChip` / VM 配線が既に存在 — 不足ではない。
- **CI ワークフロー本体**: `ci/android.yml` テンプレートが既に存在 (GitHub App の `workflows` 権限欠如で
  `.github/workflows/` へ push 不可、`ci/README.md` 参照)。リポジトリ管理者が `git mv` で有効化する。
- **`PointSimulator` / `PricePredictionEngine` の整数除算疑い**: いずれも Double 演算 (Kotlin の型昇格) で
  精度損失なし — バグではない。

### 追加実装 (Tier 7 後続)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 51 | **不足機能** | `SaleCalendar` (テスト済み) が検索の当日バナーでしか露出せず、今後のセールを一覧できない (docstring が「Pricey 相当の主要差別化」と明記) | `SaleCalendar.upcomingSales` 純関数+テストを追加し、`SaleCalendarScreen` (開催中/今後の大型セール) を新設。検索トップバーのカレンダーアイコン+当日バナータップで遷移。4ロケール対応 |
| 52 | **並行性バグ** | `SearchViewModel.performSearch` の `async{}` 内 `runCatching { getPriceHistory }.getOrDefault` が `CancellationException` を握り潰し、キャンセル済みの子コルーチンが空履歴でスコア計算を継続 (構造化並行性を破壊) | `.onFailure { if (it is CancellationException) throw it }` を挿入。他全 7 箇所 (各 API クライアント / `BackendClient` / `FallbackScraper` / `CsvExporter` / `PriceSyncWorker`) と挙動を統一 |
| 53 | **不足機能** | `CustomsSimulator` (テスト済み・Python パリティの越境関税/消費税計算) が UI 未配線で死蔵 | 設定「ツール」セクションから開く `CustomsSimulatorScreen` を新設。現地価格/送料/カテゴリ/(任意)国内最安値を入力し、課税価格・関税・消費税・手数料・着払い合計の内訳と判定チップ (国内比較入力時) を表示。ViewModel 不要の純 `remember` 計算。4ロケール対応 (各 240 文字列) |
| 54 | **不足機能 (コアUX)** | `AffiliateUrlBuilder` (Amazon/楽天/Yahoo 三社対応のアフィリエイトタグ注入) が完全実装済みだが、商品詳細画面に購入ボタンが存在せず `product.url` が一切使われていない。ユーザーは買い時スコアを見ても購入に進めない | `ProductDetailScreen` の末尾に「購入ページを開く」`Button` を追加。`AffiliateUrlBuilder.build()` で設定の `affiliateOptin` に応じてURL変換、`Intent.ACTION_VIEW` で開く。アフィリエイト有効時は `#ad` 開示ラベル表示 (景品表示法 8 条)。`UserPreferences` を VM に注入し `DetailUiState.Loaded` に `affiliateOptin` フィールド追加。4ロケール対応 (各 242 文字列) |
## ソクラテス監査 (Tier 8: 自作機能への反問 — 2026-06-13)

「在庫アラート」を実装した直後に、ゴール『ソクラテス問答を行い改善する』に従って
**自分の成果物そのものを反問**した。問い: 「この機能は発火する信号を持っているか?」

### 反問で判明したこと
- `Product.stockCount` は **本番のどのデータ経路でも代入されない幻のフィールド**だった。
  `AmazonPaApiClient` / `RakutenClient` / `YahooClient` / `FallbackScraper` のいずれも
  代入せず、`grep` で確認すると代入は **テストコード内のみ**。backend の `PriceRecord`
  にも在庫フィールドが無い。よって `Product.isInStock` は本番では `realPrice > 0` に縮退する。
- 実装した `PriceSyncWorker` の `nowInStock = latest.realPrice > 0` 代理は **ほぼ常に true** →
  `BACK_IN_STOCK` / `OUT_OF_STOCK` は実データ上ほぼ発火し得ない。
- 同じ幻フィールドに依存する `SortAndFilter` の「在庫切れ除外」(`stockCount == 0`) も
  **以前から不発の死蔵コントロール**だった (今回の反問で副次的に発見)。
- これは本セッションが繰り返し是正してきた死蔵パターン (widget の "NEUTRAL" 固定、
  未表示の EcoEthics スコア) の **再演**。検証 (CI/SDK) が無い中での機能配線が、
  動くソフトではなく「もっともらしい表面積」を生む危険の実例。

### 対応 (#55 改め)
| # | 分類 | 対応 |
|---|------|------|
| 55 | **誠実な撤回** | ユーザー向けの偽の約束 (発火しない「在庫アラート ON」トグル — 自前のダークパターン)、スキーマ変更 (Room v5/MIGRATION_4_5 → v4 に戻す)、`realPrice > 0` の誤った代理を **撤回**。検証済みの純粋ロジック `StockAlertEvaluator` (7 テスト) **のみ残置**し、docstring に「実在庫信号が供給されるまで休眠」「有効化方法」を明記。i18n は 246→242 に復帰 (4ロケール パリティ維持)。撤回の過程で下記「幻フィールド監査」を実施 |

### 幻フィールド監査 (反問から派生した全 sweep)
`stockCount` が氷山の一角ではないか、と疑って `Product` の全フィールドを監査した。
本番の Product は **4 つの生成元** (`AmazonPaApiClient.toProduct` / `RakutenClient` /
`YahooClient` / `FallbackScraper.fetchProduct`) が **手動コンストラクタ呼び出し** で作る
(プラットフォーム固有 DTO を deserialize → `Product(...)` に詰め替え)。よって、
そのコンストラクタ呼び出しで渡されないフィールドは **既定値のまま=幻**になる。

4 生成元が **一つも設定しない** フィールド (常に既定値):
| フィールド | 既定 | 影響を受ける機能 | 実害 |
|---|---|---|---|
| `originCountry` | null | `EcoEthicsCard` (#47) | **本番で常に非表示**。`ProductDetailViewModel:152` が `originCountry?.takeIf{...}` で gate するため、今セッションで「死蔵を蘇生」と記録した #47 は **実際には蘇生していない** |
| `stockCount` | null | 在庫アラート(#55) / `SortAndFilter` 在庫切れ除外 | 既述。発火しない |
| `pointsBack` | 0 | `PointSimulator` の Amazon 経路 | Amazon のポイント還元は常に 0 表示。※ 楽天/Yahoo は固定レートモデル (`PointSimulator:74`) で算出するため **機能する** — 全滅ではない |
| `couponAmount`/`couponCode` | 0/"" | `Product.hasCoupon` / クーポン表示 | クーポン UI は常に非表示 |
| `janCode` | null | 名寄せ/重複統合 (3モール最安) | JAN ベースの名寄せが効かない |
| `subscribePrice` | null | 定期おトク便比較 | 常に非表示 |
| `deliveryDays` | null | 配送日数表示 | 常に非表示 |

**結論**: 在庫アラートは単発のミスではなく、**「リッチ商品インテリジェンス層が、
データ抽出層の出力しないフィールドに依存している」という systemic な乖離**の一症状。
スコアリング/純関数 (Python パリティ済み) は健全だが、その入力が production で枯れている。

### 残課題 (未着手 — 検証優先の方針で「実装せず記録」)
- **データ抽出層の拡充 (最優先・全機能の前提)**: 各クライアントの `toProduct` に
  `originCountry` / `pointsBack`(Amazon) / `couponAmount` / `janCode` / `subscribePrice` /
  `stockCount` の抽出を追加する。これらが入って初めて EcoEthicsCard・在庫アラート・
  クーポン表示・JAN 名寄せ・定期便比較が **本番で生きる**。スコープ大 + ローカル検証不能 (SDK不在)
  のため、CI 有効化後に着手すべき。
- **暫定の正直化**: 上記が入るまで、幻フィールドに依存する UI は「データなし時は非表示」
  ガードを持つこと (EcoEthicsCard は既に gate 済みで害なし。クーポン/在庫も同様)。
  ユーザーに「在りそうで無い」機能を見せない方針を維持。
- **CI 緑化の確認**: 上記 #49 で初めて Kotest が走るため、未実行だった spec に潜在失敗が無いか CI 有効化後に要確認
  (ローカルは Android SDK 不在で検証不可)。

## 適用済み (Tier 6: 並行性・セキュリティ・バグの第4回監査)

データ/キャッシュ・ViewModel・Share・課金 の 4 カテゴリを徹底監査。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 40 | バグ/コンパイル | `SettingsViewModel.launchBillingFlow(activity, offer)` は存在しないメソッド (コンパイルエラー) → `launchPurchase(activity, offer)` に修正 | HIGH |
| 41 | 並行性 | `BillingManager.queryOffers()` の `suspendCancellableCoroutine` で `isActive` ガード不在 → コルーチンキャンセル後に `resume()` が呼ばれ `IllegalStateException` → ガード追加 | HIGH |
| 42 | UX/性能 | `SearchViewModel` が進行中の検索をキャンセルせず新クエリを最大 2 秒待たせる (後行クエリがキュー待ち) → `searchJob?.cancel()` + 新 `Job` で即時切替 | MED |
| 43 | セキュリティ | `UrlClassifier.extractUrl` の `[^\s]+` が 2048 文字超の URL を制限なく返す → `\S{1,2048}` で上限化 | MED |
| 44 | 正当性 | `MainActivity.handleIntent` が `extractUrl` の null 時に raw テキストを `classify` に渡す (URL なし文字列で不定動作) → `?: return` に修正 | MED |
| 45 | 並行性 | `ProductNavCache.put()` が `ConcurrentHashMap` + check-then-act でスレッド非安全 (2スレッドが同時に上限チェックして不整合) → `LinkedHashMap` + `@Synchronized` で原子化、挿入順 FIFO を保証 | MED |
| 46 | 並行性 | `AdviceCache.put()` が `@Synchronized` でない `evictIfNeeded()` を外から呼ぶ不整合パターン → `put()`/`get()` を `@Synchronized` 化、`evictIfNeeded` をインライン化、`ConcurrentHashMap` → `HashMap` に統一 | LOW |

### 監査で確認した非バグ (誤検知防止メモ)
- `PricePredictionEngine.percentile = cleaned.count { it >= current }`: `>= current` は正しい。現価格を下回る件数が多いほど "安い" を意味するが、`>= current` は "現価格以上の履歴件数 / 総数" = 買い時確率 (高 = 安い) と同義 — **反転ではなく正しい計算**。
- `RobotsTxt.endAnchored = anchored && !core.endsWith("*")`: `*$` パターンの場合 `endAnchored=false` になるが、`*` が可変長マッチするので末尾アンカーと同値 — **バグではない**。
- `Trie`: `ReentrantReadWriteLock` で insert (write) / suggest (read) を保護済み — **スレッドセーフ**。

## 適用済み (Tier 5: ライフサイクル・テスト品質・UI一貫性・性能の第3回監査)

ライフサイクル/購読・テスト品質・UI アイコン一貫性・再描画性能の 4 観点で再監査。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 33 | ライフサイクル | `collectAsState` → `collectAsStateWithLifecycle` (6画面・12箇所): バックグラウンド時の購読停止。`lifecycle-runtime-compose 2.8.7` 依存追加 + 全6ファイル移行 | MED |
| 34 | 並行性/correctness | `ProductDetailViewModel` AI 助言上書きが check-then-set 競合 → `_state.update { cur -> if (cur is Loaded && cur.product.key == product.key) ... }` でアトミック化 | MED |
| 35 | 性能/Compose | `PriceChart` の `sortedBy`/`min`/`max` がリコンポジション毎に再計算 → `remember(records)` / `remember(sorted)` で key 変化時のみ再計算 | MED |
| 36 | テスト品質 | `ReviewPrompterLogicTest` がテスト内でロジックを再実装 (回帰検出不能) → `ReviewPrompter.shouldRequestNow()` companion 純関数を抽出してテストが本番呼び出しに | MED |
| 37 | テスト品質 | `NotificationLogicTest` が通知 ID・テキスト・URI をテスト内で再実装 → `LocalNotificationManager.{notificationId, priceAlertText, deepLinkUri}()` companion 純関数を抽出 | MED |
| 38 | テスト品質 | `PriceSyncWorkerLogicTest` が値下がり率を直接計算してテスト — `PriceAlertEvaluator.evaluate()` を直接呼ぶテストに書き換え + `WORK_NAME` を `internal` 公開 | LOW |
| 39 | UI一貫性 | `SearchSuggestions`/`OfflineBanner`/`ProductDetailScreen`/`SearchScreen`/`SettingsScreen` が `Icons.Default.*` を直参照 (AppIcons 方針違反) → 全5ファイルを `AppIcons` 経由に統一 | LOW |

### 今後のバックログ (round 3 で確認、未適用)
- `HapticFeedbackTest` / `BillingManagerTest`: 定数のみ検証でロジック保護なし。
  `HapticFeedback` の vibration効果定数は Android API 由来で変更困難。
  `BillingManager` のSKU/価格はサービス仕様変更時の意図的変更のため現状維持で可。
- `AccessibilityExt.kt` の `verdictA11yLabel` / `darkPatternA11yLabel`: 現状は
  Kotlin 文字列定数で多言語非対応。非 Composable 関数のため `Context.getString()` を
  呼ぶ設計変更が必要 (シグネチャ破壊あり) — 要設計検討。

## 適用済み (Tier 4: 並行性・性能・テスト品質の徹底監査)

ビルド/マニフェスト/セキュリティ設定・並行性/ライフサイクル/性能・テスト網羅の
3 観点で再監査。設定 (network_security_config / backup / FileProvider / manifest) は
全て健全。並行性・性能で確認した問題を修正。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 25 | 性能 | `WatchlistViewModel.smartCart` が総当たり最適化 (最大 200k) をメイン/即時 dispatcher で実行 → `flowOn(Dispatchers.Default)` | HIGH |
| 26 | 並行性 | `PriceSyncWorker` が逐次フェッチ + 常に `success` (バックオフ死蔵)。Semaphore(8) 並列化 + 全件失敗時のみ `retry` | HIGH |
| 27 | 並行性 | `WidgetUpdater.pendingJob` が Main/Worker から無同期 check-then-act 競合 → lock で atomic 化 | HIGH |
| 28 | 並行性/leak | `BackendClient.postPriceAsync` が検索結果ごとに無制限 launch (≈90 並行 POST、応答未消費) → `postPricesAsync(List)` で 1 コルーチン順次送信 + `bodyAsText` で接続解放 | HIGH |
| 29 | 性能 | `SearchViewModel` がグループごとに価格履歴を逐次取得 → `async`/`awaitAll` で並列化 | MED |
| 30 | 性能 | `FallbackScraper` が Regex を呼び出しごとに再コンパイル → companion 定数 + キー別キャッシュ。レート制限ゲートを `compute` で atomic 化 | MED |
| 31 | バグ/テスト | `PopcoonWidget` の楽天セール分岐に到達不能な day=5。純関数 `PopcoonWidgetLogic` に抽出して修正、テストを本番呼び出しに | LOW |
| 32 | テスト | `Product`/`Platform` の派生プロパティと `fromId` フォールバック契約 (未知→AMAZON) を `ProductTest` で固定 | — |

### 今後のバックログ (round 2 で確認、未適用)
- `collectAsState` → `collectAsStateWithLifecycle` (6 画面 12 箇所): バックグラウンド時の
  購読停止。要 `androidx.lifecycle:lifecycle-runtime-compose` 依存追加 (CI で要検証)。
- 弱いテスト (本番ロジックをテスト内に再実装し回帰検出不能): `PriceSyncWorkerLogicTest`,
  `NotificationLogicTest`, `ReviewPrompterLogicTest`, `HapticFeedbackTest`, `BillingManagerTest`
  → 純関数を本番側に抽出して本番呼び出しに (Widget は #31 で対応済み)。
- AppIcons 集約方針の徹底 (画面の直 `Icons.Default.*` を `AppIcons` 経由に)。
- `ProductDetailViewModel` の AI 助言上書きを `_state.update{}` + productKey 一致確認に。

## 適用済み (Tier 3: カテゴリ別徹底監査)

プロダクトを 5 カテゴリ (データ&永続化 / 価格アルゴリズム / 消費者保護 / UI・Compose /
ウォッチリスト・カート・課金・バックグラウンド) に分割し、各層を実コードまで精査。
CI も Android SDK も無く Kotlin が一度もコンパイルされていないため、**コンパイル不能
バグが多数潜伏**していた。確認済みのものを全て修正 (各々テスト付きまたは inspection 検証)。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 13 | データ | `AmazonPaApiClient.SearchItemsRequest` の primary/secondary コンストラクタが同一 JVM シグネチャ → conflicting overloads (コンパイル不能) | HIGH |
| 14 | データ | `PriceRecord` が `Instant` を `@Serializable` するもシリアライザ不在 → コンパイル不能。`InstantIso8601Serializer` 追加 + 往復テスト | HIGH |
| 15 | DI | Hilt 二重バインディング **7 件** (`@Inject` + `@Provides`): AdviceCache, BuyingAdvisor, BackendClient, PriceHistoryCsvExporter, StartupTracker, ReviewPrompter, ConnectivityObserver → 全て Dagger コンパイル不能。冗長 `@Provides` を削除 | HIGH |
| 16 | データ | `YahooClient` が `premiumPrice` (会員割引価格) を list price に使い割引表示が反転 → `defaultPrice` に修正 | MED |
| 17 | アルゴリズム | `SaleCalendar.nextMajorSale` が当年のみ生成 → 12/7–31 に null。翌年分を併合 + 年境界回帰テスト | MED |
| 18 | 課金 | `AffiliateUrlBuilder` 楽天リンクが商品 URL を未エンコードで `pc=` に連結 → リンク破損 (収益逸失)。`Uri.encode` | MED |
| 19 | クラッシュ | `PrivacyCrashReporter` が ① クラッシュ時 fire-and-forget で送信未達 ② 保存形式と送信形式が不一致。永続化→次回起動送信パターンに修正 | MED |
| 20 | UI/i18n | ProductDetail 価格カード・Watchlist 空状態・Barcode エラー 4 箇所の日本語直書きをリソース化 (en/ko/zh 対応) | MED |
| 21 | UI/compose | `SearchSuggestions` の LazyColumn に安定キー付与 | MED |
| 22 | セキュリティ | CSV エクスポートの数式インジェクション対策 (`=+-@` 始まりに `'` 前置) + テスト | LOW |
| 23 | プライバシー | `PopcoonLogger` が Throwable を未サニタイズで Logcat 出力 → サニタイズ連結に修正 | LOW |
| 24 | UI/a11y | 検索画面のお気に入りボタンが「保存」と誤読み上げ → `nav_watchlist` | LOW |

消費者保護カテゴリ (darkpattern/review/ethics) は Python オラクルとのパリティ含め
**全て CLEAN** (監査で確認、修正不要)。ロケール 4 言語のキー集合も一致を確認。

### 監査で確認した非バグ (誤検知防止メモ)
`PointSimulator` 0除算 / `ProductMatcher` janCode 欠落 / `BundlePackDetector` 0除算 /
`TCOCalculator` 負値 tcoPerMonth — いずれも**存在しない** (ガード済み or 到達不能)。

## 適用済み (Tier 1: build + correctness + safety)

| # | 内容 | 主な変更 |
|---|------|----------|
| 1 | **コンパイルブロッカー修正**: `Pop_TealDark` がどこにも定義されず参照されていた (現状ビルド不能) | `ui/theme/Theme.kt` |
| 2 | **全 Ktor クライアントに HttpTimeout 追加** (デフォルト 100s ハングを防止) | network/* , repository/BackendClient, ai/BuyingAdvisor, crash/PrivacyCrashReporter |
| 3 | **DB 破壊的マイグレーションを debug 限定化** (release でのユーザーデータ消失防止) | `di/DatabaseModule.kt` |
| 4 | **robots.txt 遵守を実装** (doc は「尊重」と書きつつ未実装だった) + 純関数パーサと単体テスト | `network/FallbackScraper.kt`, `network/RobotsTxt.kt` |
| 5 | **EcoEthicsScorer の Kotlin↔Python 乖離を解消** (docstring は「完全一致」と主張も別式だった)。Python oracle に一致させ、絶対値パリティテストで固定 | `feature/ethics/EcoEthicsScorer.kt` + test |
| 6 | **PopcoonLogger の秘密情報リダクション強化** (旧 regex はマルチパラメータ URL で API キーを伏せ損ねる) | `core/PopcoonLogger.kt` + test |
| 7 | **ハードコード日本語 UI 文字列をリソース化** (en/ko/zh で日本語露出) | `ui/components/*`, `res/values*/strings.xml` |

## 適用済み (Tier 2: 競合調査ベースの機能・基盤)

GitHub 上の同種 OSS 価格追跡アプリ (CamelCamelCamel, Keepa, ShopSense, Pricewise,
jeevandhakal/price_comparison, edent/Amazon-Wishlist-Pricedrop-Alert 等) を調査し、
Popcoon に欠けていた最も普遍的な機能と、その検証基盤を実装した。

| # | 内容 | 主な変更 |
|---|------|----------|
| 8 | **希望価格 (target price) アラート**: 競合が普遍的に持つ「指定価格まで下がったら通知」。従来は相対値下がり率のみで、予算までの緩やかな下落を取りこぼしていた。目標到達は率の閾値を無視し最優先で通知。純関数 `PriceAlertEvaluator` (18 テスト) + Room v1→v2 マイグレーション + ウォッチリスト UI (チップ/ダイアログ) | `feature/notification/PriceAlertEvaluator.kt`, `data/db/PopcoonDatabase.kt`, `di/DatabaseModule.kt`, `worker/PriceSyncWorker.kt`, `ui/components/TargetPriceDialog.kt`, `ui/screens/watchlist/*`, `res/values*/strings.xml` |
| 9 | **CI ワークフロー新設**: TDD 重視の設計にも関わらず CI が存在せず、ローカル Android SDK も無いため Kotlin が一度もコンパイル検証されていなかった。detekt/lint/単体テスト/assemble + Python オラクルを実行。App の `workflows` 権限制約のため `ci/` にテンプレートとして配置 (管理者が 1 行で有効化) | `ci/android.yml`, `ci/README.md` |
| 10 | **潜在コンパイルエラー修正**: `PriceSyncWorker` が `CurrencyFormatter` を import せず参照していた (HEAD でも壊れていた) | `worker/PriceSyncWorker.kt` |

## 検証
- Python TDD: `cd popcoon-tdd && python3 -m pytest -q` (290 passed, 1 skipped)。
- Kotlin: ローカルに Android SDK が無いため inspection で担保。`ci/android.yml` を
  `.github/workflows/` に配置すると lint / detekt / `testDebugUnitTest` / assemble が
  自動検証する (有効化手順は `ci/README.md`)。

## 今後のバックログ (未適用)

### 信頼性・品質 (Tier 2)
- ProductRepository に API レート制限 / 指数バックオフ / サーキットブレーカ。
- FallbackScraper の JSON-LD 抽出を regex から正規 JSON パーサへ (数値 price・エスケープ対応)。
- 検索の in-flight キャンセル (古いクエリ結果の上書き防止) と SearchScreen のリトライ UI。
- AdviceCache の TTL / 退避テスト、`put` の同期化 (上限超過の競合)。
- ProductDetailViewModel / WatchlistViewModel / SettingsViewModel の単体テスト追加。
- CI: ~~ワークフロー新設~~ (#9 で実装、要有効化) → 次は detekt / Kover カバレッジを
  マージゲート化、baseline profile 検証。
- a11y 文字列: ~~`VerdictBadge.kt` の i18n~~ (実装済み) → 残りは `AccessibilityExt.kt`
  (Context 引数が必要な非 Composable のため要設計)。

### 競合調査バックログ (同種 OSS 価格追跡アプリ由来)
GitHub 調査で確認した、競合にあり Popcoon に未実装だった機能。インパクト順:
- ~~**ウォッチリストの整理**: ソート・並べ替え~~ → 実装済み (#11 `WatchlistSort`:
  追加/価格/割引率/名前/目標到達順、永続化 + 並べ替えメニュー)。タグ付け/
  カテゴリ分けは未着手。
- ~~**URL 貼り付けで追加**: 共有インテント (`ACTION_SEND`)~~ → 既存 (`feature/share/
  UrlClassifier`, MainActivity で配線済み)。
- **クーポン/プロモコード集約**と決済前の自動適用 (Honey, Karma の中核機能)。
- **在庫アラート**: 再入荷/在庫切れ通知。純関数 `StockAlertEvaluator` は検証済みで用意済みだが、
  `Product.stockCount` が production で代入されない幻フィールドのため **配線は前提待ち** (Tier 8 #55 参照)。
  scraper/backend が実在庫信号を返すのが前提。
- ~~**「追加時からの変動」表示**: ウォッチ追加時価格を基準に変動を可視化~~ → 実装済み
  (#12 `WatchlistPriceDelta` + Room v2→v3 `addedPrice` カラム + 行内表示)。
- **値下がりフィード**: ウォッチ外の急落商品を一覧する発見導線 (要 backend)。
- **多通貨対応**: 越境購入 (CustomsSimulator) と整合する通貨換算表示。

### 配線中に発見・修正した潜在バグ (CI 不在で未検出だったコンパイルエラー)
- `PriceSyncWorker` が `CurrencyFormatter` を import せず参照 (#10 で修正)。
- `UserPreferences` が `@Inject constructor` と `SettingsModule.@Provides` の二重
  バインディング (Hilt コンパイルエラー)。冗長な module provider を削除 (#11)。

### アルゴリズム (Tier 3)
- 価格予測を Holt 線形から Holt-Winters (季節性) へ。
- Kotlin↔Python 差分/契約テスト基盤 (CustomsSimulator / TCOCalculator / PricePredictionEngine /
  BuyTimingScorer の出力一致を CI で保証)。
- Kotlin 側ミューテーションテスト (Python は 100% kill 達成済み)。

### 設計判断 (要検討)
- TCOCalculator の残価が長期で 0% になる (現実は 5-15%) — 下限を設けるか要件確認。
- CustomsSimulator の関税丸めは truncate (日本の実務は切り上げが一般的) — 仕様確認。
