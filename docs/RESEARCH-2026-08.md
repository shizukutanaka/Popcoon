# Popcoon 改善リサーチ (2026-08)

`docs/RESEARCH-2026-07.md` の続き。ユーザー指示「論文や技術情報などを参考に続けて」に基づく調査記録。
各項目は「本セッションで実装済み ✅ / 見送り (理由付き) ⏸️ / 承認待ち ⏳」を明記する。
**前回ログの記載を訂正した箇所も残す** (誤った出典を静かに消さない)。

本環境の制約: `arxiv.org` / `caa.go.jp` / `darkpatterns.jp` 等は egress プロキシで直接取得できず、
検索結果の要約経由で確認した。一次資料の URL は再検証できるよう全て記録する。

---

## 1. 時系列 Conformal Prediction (予測区間の較正)

**主な知見**
- **Conformal Prediction Algorithms for Time Series Forecasting: Methods and Benchmarking**
  (arXiv:2601.18509, 2026-01)。時系列 CP を 4 系統 (交換可能性の緩和 / データ単位の再定義 /
  残差ダイナミクスの明示モデル化 / 分布シフト追従のオンライン学習) に整理したうえでベンチマーク。
  **multi-step split conformal が 90% 被覆を満たしつつ最良の効率** を示す。
  多段先予測では AcMCP (多段先誤差の自己相関を取り込む) が区間を効率化。
  <https://arxiv.org/abs/2601.18509>
- ACI (Gibbs & Candès) / Conformal PID (Angelopoulos+ NeurIPS 2023) は既に P 項を実装済み
  (`ConformalInterval.adaptiveConformalMargin`、2026-07 リサーチ)。

**改善候補**
- ✅ **較正 horizon を予測 horizon に一致させる (multi-step split conformal)** — 実装済。
  上記文献が前提とする「キャリブレーション残差と本番の予測誤差が同分布」という条件を、
  従来実装は満たしていなかった: `predictionMargin` は **1 ステップ先** Holt 残差の分位点なのに、
  UI は predicted7d と predicted30d の **両方** に同じ ± 幅を表示していた。

  実測した被覆率 (ランダムウォーク 400 試行、目標 90%):

  | 予測先 | 1 ステップ較正 (旧) | horizon 一致 (新) |
  |---|---|---|
  | 7 日先 | **53.8%** (±195) | 91.8% (±476) |
  | 30 日先 | **20.5%** (±197) | 90.5% (±1729) |

  セール衝撃を含む系列でも 61.8% / 25.5% → 95.0% / 95.2%。「90% 被覆保証」の表示は誤りで、
  ユーザーには実際の 1/4 以下の不確実性しか提示されていなかった。
  oracle `holt_multistep_residuals` (+8 tests、horizon=1 の後方互換を固定) → Kotlin
  `holtResiduals(data, horizon)` → `Prediction.predictionMargin30d` 追加 → UI の 30 日行を分離。
  履歴が h ステップ先の実測を含まない場合は 0 (算出不能) を返し、短い方の margin を流用しない。
- ✅ **残差生成器そのものを parity 対象に追加** — 上記バグが両言語で検出できなかった真因は、
  `holtResiduals` がクロス言語照合の外にあり、parity が合成残差リストしか食わせていなかったこと。
  `HOLTRES` ケース (horizon 1/7/13/14/30 + 定数列・完全直線・3 点未満・空入力の境界) を追加。
- ✅ **予測アンサンブル (ASSESSMENT B1) — ただし h=7 のみ** — 実装済。predicted_7d を
  Holt 無減衰外挿から **Holt / damped-trend Holt / seasonal-naive の中央値** へ。
  単独最良はレジーム依存だが中央値は **どのレジームでも最悪にならない**。MAE 実測 (h=7、
  合成価格系列 300 試行):

  | レジーム | Holt (現行) | damped | snaive | **median (採用)** |
  |---|---|---|---|---|
  | ランダムウォーク | 160.3 | 144.3 | 139.7 | **143.9** |
  | トレンド転換 | 134.2 | 128.4 | 129.0 | **126.2** |
  | 週次季節性 | 123.0 | 123.0 | 44.7 | **120.7** |
  | セール衝撃 | 258.1 | 215.1 | 205.6 | **212.1** |

  φ=0.9 は fpp3 の実用域 [0.8, 0.98] の標準値で決定性のため推定せず固定、period=7 は
  SeasonalDecompForecast と同一。出典: Gardner & McKenzie (1985) の damped trend が
  M3/M4 で複雑手法に対し一貫して競争的。<https://www.bauer.uh.edu/gardner/docs/pdf/Why-the-damped-trend-works.pdf>
  / <https://otexts.com/fpp3/holt.html>
- ⏸️ **h=30 へのアンサンブル適用は見送り (実測に基づく判断)** — MAE の改善幅はむしろ
  h=30 の方が大きい (412.8→269.1 等、**-21〜-35%**) が、**予測区間を較正できない**。
  学習 90 点から得られる 30 ステップ先残差は約 60 本でも窓が重なるため実質独立ブロックは
  2 個ほどしかなく、アンサンブルの残差分位点が本番誤差を過小評価する。被覆率実測 (目標 90%)
  は適応追跡 78.0〜84.0% / 静的 split 79.8〜84.8% と **どちらも目標割れ** した
  (h=7 は 89.8〜91.5% で合格)。被覆保証は明示している契約なので較正できない予測器は
  採用しない。履歴が数百点得られるようになれば再検討する。
- ⏸️ **BuyTimingScorer のトレンド信号はアンサンブル化しない (負の実測結果)** —
  検討過程で 30 日先をアンサンブル化したところ、持続的トレンドの方向判定精度が
  **下降 98.8%→19.0% / 上昇 88.5%→2.8%** と激減した。減衰が変化幅を過小評価して「微」
  バケットへ寄せるためで、閾値 (±1% / ±5%) が無減衰スケール前提で調整されていることによる
  (逆に価格が下げ止まる系列ではアンサンブル 67.2% > 無減衰 36.0%)。
  h=30 を Holt 据え置きにしたため BuyTimingScorer は完全に不変。
  `parity_fixtures` の buy_timing 期待値が動いたことでこの退行を検知できた。
- ✅ **較正の整合維持** — 点予測を変えた horizon は残差も同じ予測器で取る
  (h=7 = アンサンブル残差 `ensemble_multistep_residuals` / `ensembleResiduals` を新設、
  h=30 = Holt 残差を据え置き)。出荷構成での被覆率実測: h=7 89.8〜90.8% / h=30 91.2〜93.5%。
  seasonal-naive の腕は原点までの観測値のみ参照し未来を覗かないことをテストで固定。
- ⏸️ **AcMCP (多段先誤差の自己相関モデル化)** — 区間の効率 (幅) は改善するが、被覆の是正は
  horizon 一致で達成済み。追加の状態とチューニングを要するため、被覆が実測で満たされている
  現状では費用対効果が見合わない。将来 30 日先の区間幅が広すぎるという実利用フィードバックが
  出た場合の第一候補として記録する。

## 2. ダークパターン / 日本の規制動向

**主な知見**
- **消費者庁「デジタル取引・特定商取引法等検討会」** (2026-01 設置)。第 3 回 (2026-04-13) で
  ダークパターン規制と誘導的広告勧誘表示、**第 4 回で「規制されるダークパターンの具体的な類型」と
  「契約・解約場面における規律の在り方」** を審議。中間とりまとめは **2026 年夏** 予定で、
  2027 年以降の特商法改正の方向を事実上決める。
  <https://www.caa.go.jp/policies/policy/consumer_transaction/meeting_materials/assets/consumer_transaction_cms101_260413_01.pdf>
- **消費者庁 2025-04 実態調査** (リサーチ・ディスカッション・ペーパー): **OECD (2022) の分類を主軸に
  Hidaka et al. (2023) の日本固有分類を追加**し、消費者相談・売上から抽出した **102 サイト** を調査。
  多数のサイトで見られたのは **Preselection (事前選択)** と **False Hierarchy (偽りの階層表示)**。
  評価軸は自律性の尊重 / 透明性 / 公正性 (契約は容易だが解約は困難、といった非対称の不在) /
  マーケティングと操作の区別。 <https://www.caa.go.jp/policies/future/icprc/research_010>
- **消費者庁 2026-06-18 消費者意識調査 (新規)**: 10〜70 代以上 1,200 人に **28 種類** の
  ダークパターンを提示。**見たことがある 76.2% / 過去 1 年に経験した 37.5%、経験は緊急性の強調が最多**。
  行動影響も計測され、隠れ定期購入群では高額プレミアムプラン選択率が 6.5% (対照群 1.0%)、
  アカウント管理費 300 円の正認識率は対照群 56% に対し隠れ定期購入群 46.2% / 隠れコスト群 34.8%。
  <https://www.caa.go.jp/policies/future/icprc/research_002/assets/caa_futurer_cms201_260618_02.pdf>
- **解約妨害の実態**: 特商法の詐欺的定期購入規制 (2021 改正 / 2022-06-01 施行) で最終確認画面の
  表示義務が入った後も、「定期縛りなし」「いつでも解約可能」と表示しつつ実際は
  「次回発送の●●日前までに電話で連絡が必要」「その電話が繋がらない」という相談が継続している。
  <https://www.caa.go.jp/policies/policy/consumer_transaction/amendment/2021/assets/consumer_transaction_cms202_220322_01.pdf>

**改善候補**
- ✅ **OBSTRUCTION (解約妨害) を独立カテゴリとして追加** — 実装済。既存の HIDDEN_SUBSCRIPTION が
  「契約が継続すること自体を隠す」類型なのに対し、OECD 2022 の Obstruction (ローチモーテル) は
  「契約後に抜けにくくする」類型で、検討会でも「契約・解約場面」として独立論点になっている。
  深刻度 2 段階 — 解約手段の電話限定 = HIGH (相談事例の実害「電話が繋がらない」が最頻出)、
  次回発送日起点の事前連絡期限 = MEDIUM (実効的な解約可能期間を圧縮する条件)。
  誤爆ガード: 限定語 (のみ/だけ/に限) を必須にし複数手段の提示は拾わない / 解約と限定語の距離を
  12 文字に制限 / 期限側は後続に解約文脈語を要求。負ケースをテストと parity に固定。
- ✅ **URGENCY の recall 拡張** — 実装済。2026-06 意識調査で「経験した類型」の最多が緊急性の
  強調である一方、Popcoon のパターンは 9 本と薄く実際に取りこぼしていた (いずれも従来 0 件検出):
  「あと3時間で終了」「あと30分」「終了間近」「売り切れ次第終了」「本日最終日」
  「Last chance」「Offer ends」。特に **「あと」接頭辞は SCARCITY 側では対応済みなのに
  URGENCY 側が「残り」のみ** で、同義表現が片側だけ落ちる非対称になっていた。
  当初は「負ケースの収集が要る」として見送っていたが、**入れない語を負ケースとして固定する**
  方針で解決した — 「期間限定」(商品属性の用法が多い)、裸の「最終日」(配送文脈)、
  日単位カウンタ (納期であって煽りではない)、「終了しました」(事実表明) は意図的に非対象とし、
  それぞれ oracle と parity に非検出ケースとして記録した。
- ⏸️ **False Hierarchy (偽りの階層表示) / Preselection の視覚的検出** — 実態調査での最頻出だが、
  Popcoon の入力は商品ページの可視テキストのみで、ボタンの配色・サイズ・配置は取得できない。
  MISDIRECTION の「デフォルトで選択」等のテキスト痕跡を拾うのが現状の限界。

**前回ログの訂正**
- `docs/RESEARCH-2026-07.md` および `docs/ASSESSMENT-2026-07.md` は消費者庁 2025-04 実態調査を
  「**32 類型** + 事例集」と記載していたが、今回の再調査で裏付けが取れなかった。確認できたのは
  「OECD (2022) 分類 + Hidaka et al. (2023) の日本固有分類を用いて 102 サイトを調査」であり、
  類型数を 32 とする一次情報には到達できていない (2026-06 の意識調査で提示されたのは 28 種類)。
  出典が確認できない数値は残さない方針に従い、該当箇所を上記の確認済み記述へ差し替えた。

## 3. 名寄せ / エンティティ解決 (継続調査)

**主な知見**
- **Sparkly: A Simple yet Surprisingly Strong TF/IDF Blocker for Entity Matching**
  (Paulsen, Govind, Doan — PVLDB vol.16, 2023)。ブロッキングに tf/idf (BM25 変種) を素直に使うと
  **state-of-the-art な 8 手法を上回る**。重みは term frequency × idf = log(N/df(t))。
  <https://www.vldb.org/pvldb/vol16/p1507-paulsen.pdf> / <https://github.com/anhaidgroup/sparkly>
- **WDC Block** (ブロッキング専用ベンチマーク) が公開され、商品ドメインのブロッキング評価が可能に。
  <https://webdatacommons.org/largescaleproductcorpus/wdc-block/>
- 商品マッチングではブロッキングと hard negative の質が最終精度を左右する
  (Block-SCL, arXiv:2207.02008)。

**改善候補**
- ✅ **候補集合内 IDF-lite トークン重み付け (ASSESSMENT B3)** — 実装済。
  現行の `titleSimilarity` は素の Jaccard で、ブランド名やカテゴリ語が希少な識別トークンと
  等価に効いていた。**型番・容量・色・個数のいずれも取れない一般商品** (食品・日用品) では
  既存の属性ペナルティが全て中立になるため、共有語だけが多い別 SKU が閾値 0.6 ちょうどに
  達して統合され、味違い・メーカー違いに誤った「最安値」を提示していた:

  | ペア (corpus = はちみつ 4 件) | 素の Jaccard | IDF 重み付き | ブレンド後 titleSim |
  |---|---|---|---|
  | 山田養蜂場 アカシア vs れんげ | **0.600** (統合) | 0.452 | **0.500** (分離) |
  | 山田養蜂場 アカシア vs そば | **0.600** (統合) | 0.452 | **0.519** (分離) |
  | 山田養蜂場 vs 杉養蜂園 アカシア | **0.600** (統合) | 0.503 | **0.556** (分離) |
  | 山田養蜂場 れんげ vs そば | **0.600** (統合) | 0.410 | **0.540** (分離) |

  候補集合を corpus とみなし `idf(t) = ln(1 + N/df(t))` で重み付けした Jaccard
  (`Σ_{t∈A∩B} idf(t) / Σ_{t∈A∪B} idf(t)`) を採用。corpus 外トークンは df=1 相当の
  `ln(1+N)` (最も識別的)。**weights を渡さない経路は素の Jaccard へ委譲**するため
  `similarity(a, b)` の出力はビット単位で不変で、既存 parity/golden は無回帰
  (一様重みなら数学的には一致するが、浮動小数の丸めを避けるため委譲で厳密に保証した)。
  2-gram Dice の腕は不変なので、識別語が 1 つ違うだけの同一商品の表記ゆれは Dice 側で
  救済される (recall 非退行を parity と Kotest で固定)。
- ✅ **特徴量メモ化 (perf)** — B5 の前提として groupByIdentity の実測を取ったところ、支配項は
  O(m²) の比較回数ではなく **1 比較あたりの定数** だった。similarity() が 1 比較ごとに
  NFKC + 正規表現の派生を 6 系統 × 2 タイトル 実行しており (NFKC 約 12 回 / 正規表現
  約 20 回)、それが総当たりで O(m²) 回 走っていた。商品ごとに 1 回だけ導出して使い回す
  純粋なメモ化に変更 (出力は 1 ビットも不変、17×17 の全ペアで両経路一致を parity に固定):

  | 商品数 | 変更前 | 変更後 | 倍率 |
  |---|---|---|---|
  | 20 | 16.63 ms | **5.01 ms** | 3.3x |
  | 40 | 60.20 ms | **8.09 ms** | 7.4x |
  | 80 | 202.95 ms | **14.32 ms** | 14.2x |
  | 160 | 801.20 ms | **50.69 ms** | 15.8x |
  | 320 | 2522.12 ms | **112.14 ms** | 22.5x |

  あわせて docstring の「低レイテンシ (p99 < 1ms)」を訂正 (実測の裏付けが無く、変更前は
  20 件で既に 16.6ms だった)。
- ⏸️ **粗ブロッキング (ASSESSMENT B5)** — 上記メモ化で **優先度が大きく下がった**。
  3 モール横断の現実的な候補数 (数十〜百数十件) では 5〜50ms に収まり、体感を損なわない。
  比較回数自体は O(m²) のままなので、候補数が数百規模になる将来に再検討する。
  出典 (Blocking and Filtering Techniques for Entity Resolution: A Survey,
  Papadakis+, ACM Computing Surveys 53(2) 2020) では block building を転置索引
  (block id → entity id) として構成し、2 件の「最小共通ブロック」でのみ比較して
  重複比較を避ける手法が整理されている。<https://dl.acm.org/doi/abs/10.1145/3377455>
  なお Popcoon では **トークン一致だけでブロック化してはならない**: titleSimilarity は
  2-gram Dice の腕を持つため、共有トークンが 0 でも「明治おいしい牛乳900ml」と
  「明治 おいしい牛乳 900ml」は 0.75 でマッチする。ブロッキングキーは文字 2-gram 側に
  取る必要がある (共有 2-gram が 0 なら Jaccard も Dice も 0 になるため損失なし)。

## 3-2. 関連ソフトウェア調査 (競合・OSS)

**主な知見**
- **Keepa / CamelCamelCamel**: Keepa は 11 マーケットプレイスを追い Amazon.co.jp を含むが、
  **CamelCamelCamel は日本サイトを終了**しており日本の Amazon を追う手段は限られる。
  Keepa は Sales Rank 履歴 / Buy Box 挙動 / 出品者数を保持するが CamelCamelCamel は持たない。
  Keepa Pro は €29/月。 <https://revenuegeeks.com/compare/keepa-vs-camelcamelcamel>
- **PriceGhost** (自己ホスト型 OSS): **独立した抽出方式を複数並列に走らせて突き合わせる**
  多戦略抽出 (Price Voting)。<https://github.com/clucraft/PriceGhost>
- **PriceBuddy** / **changedetection.io**: 任意サイトの価格・在庫追跡、複数ストア比較、
  Playwright による JS 重サイト対応。自己ホストの利点は「プライバシー」と「チェック頻度」。
  <https://github.com/jez500/pricebuddy>

**改善候補**
- ✅ **FallbackScraper の価格抽出を多戦略化 + ¥0 捏造の停止** — 実装済。
  Popcoon は JSON-LD **単独**で、PA-API 5.0 廃止 (2026-05-15) 後は Amazon 商品ページの
  実質唯一のデータ源がこれになっていた。さらに price が取れないとき `?: "0"` で
  **realPrice=0 の Product を捏造**しており、`ProductRepository.refresh` の KDoc が
  明文化する「失敗時は null」契約を破っていた。0 円商品は価格履歴に入ると常に史上最安値と
  なり、ATL 判定・price_below アラート・Holt/IQR 予測を汚染する。
  `parsePriceToLong` (0 以下は「無料」ではなく取得失敗として null) と
  `extractHtmlPrice` (microdata → OpenGraph ×2 → Twitter card の順で最初の正値を採用) を
  新設し、全滅なら null で失敗させるようにした。parity に 22 assertion を追加。
  **JSON-LD Product スキーマ自体の要求は据え置き** — 無いページは検索結果/エラーページの
  可能性が高く「商品ページである」ことの確証として機能するため。
- ⏸️ **抽出結果の投票 (PriceGhost の Price Voting 相当)** — 複数戦略が **異なる値** を返した
  ときに多数決で決める仕組み。現状は「最初に取れた戦略を採用」の優先順方式にとどめた。
  投票が効くのは戦略間の不一致が実データで頻発する場合だが、その頻度を測る実ページの
  収集手段が本環境に無い (egress 制限)。実運用のログが取れるようになったら再検討する。
- ⏸️ **Sales Rank / Buy Box 履歴 (Keepa 相当)** — PA-API 廃止で取得手段が無い。
  Creators API (A5、人手ゲート) 移行後に再検討。

## 4. 日本 EC のポイント制度 (更新確認)

**主な知見**
- **Yahoo!ショッピング / PayPay**: **2026-06-02 から PayPay ステップの付与基準が変更**され、
  ポイント利用分を差し引いた「お支払い金額 (残高・クレジット)」200 円ごとの付与になった。
  ただし決済回数・支払金額のカウント条件はポイント利用分を含めたまま。**ストアポイントや
  「5 のつく日」等のキャンペーンは従来どおりポイント利用分も付与対象**。
  また **2026-07-01 からヤフーショッピング商品券の利用分は付与対象外**。
  <https://topics.shopping.yahoo.co.jp/notice/archives/20260423paypay.html>
- **楽天 SPU**: 2026-07-01 の改定 (楽天ポイントカード+ファミリーマート +0.5 倍) が最新。
  通常ポイントと楽天カード通常分以外は全て月間上限つき。2026-08 時点で追加の改定は確認できず、
  実装済みの上限 18 倍モデルは有効。

**改善候補**
- ✅ **ヤフショ会員ランク次元 (ASSESSMENT B4)** — 実装済。「ヤフショ感謝デー」
  (毎月 11日・22日、シルバー +4% / ゴールド +5%) は SaleCalendar が日付を告知するだけで
  実質価格に反映されておらず、ランク保有者には最大 5% の還元が抜け落ちていた
  (UserContext にランク次元が無く「未実装」と明記されていた既知の穴)。
  2026-08 の再確認: 感謝デーは 2025-11-11 に「ゾロ目の日クーポン」を置き換えて開始し継続中。
  LYPプレミアムの +2% は 2026-06-22 の「スタンダードプラン」改称後も内容変更なし。
  <https://shopping.yahoo.co.jp/promotion/campaign/pointrank/>
  DataStore には enum 名を文字列で保存し、未設定・不明値は NONE へフォールバックする
  (将来ランクが増減しても読み出しで落ちない)。設定画面に 3 択チップ + 4 ロケール文字列。
  なお `SaleCalendar` の実装 (day == 11 || day == 22) は元から正しく、
  RESEARCH-2026-07 の「11/22」表記は日付ではなく「11日・22日」の意だった。
- ✅ **(副産物) app モジュールのコンパイルエラーを発見・修正** — B4 の配線中に
  `UserPreferences : IUserPreferences` の 5 メンバー (rakutenSpu / yahooPremium /
  paypaySoftbank / amazonPrime / ecPromptDismissed) が `override` 無しで宣言されており、
  **Kotlin ではコンパイルが通らない**状態だったことが判明した。最小再現を実際に
  コンパイルしてエラーを確認済み。IUserPreferences は 2026-07-13 に追加され、
  その後に足されたメンバーだけ override が付き、当初の 5 つが取り残されていた。
  Android SDK 不在 + CI 未稼働で約 1 か月検出されなかった —
  **短所 2「Compose/Room/Hilt 層はコンパイル未検証」が現実の欠陥として顕在化した事例**。
  他のインタフェース実装 (IProductRepository 3/3, IWidgetRefresher 1/1) は問題なし。
- ⏸️ **PayPay ステップの付与基準変更 (2026-06-02) の反映** — PointSimulator は「商品価格から
  付与額を計算する」モデルで、**ユーザーがポイントを使って支払う額を入力として持たない**。
  影響を受けるのは PayPay ステップ由来の付与のみで、シミュレーターが扱う基本 1% と
  キャンペーン群 (5 のつく日・日曜・プレミアム・SoftBank) は従来どおりポイント利用分も
  対象のため、現行の計算結果は変わらない。UserContext に「ポイント利用額」次元を足せば
  モデル化できるが、入力 UI の追加を伴うため単独の設計判断として切り出す。
- ⏸️ **商品券利用分の付与対象外 (2026-07-01)** — 同上。商品券使用額を入力に持たないため
  現行モデルでは表現できない。

---

## 5. ¥0 汚染の掃討 (2026-08、実測)

「取得失敗を 0 円として記録したレコード」がドメイン全体を汚染していた。書き込み側は
先に塞いだ (FallbackScraper の捏造停止 cdf61dc、backend の `real_price <= 0` 拒否 5c0ade0)
が、**既に蓄積した行を読む側は無防備**で、`realPrice` を読む全経路を洗い直した結果
6 か所で判定が壊れていた。実測した被害:

| 経路 | 症状 (実測) |
|---|---|
| `BuyTimingScorer` ATL 近接 / ボラティリティ | 正常 `95/BUY_NOW` が ¥0 1 件混入で `40/NEUTRAL` — シグナル消失 |
| `predict_price` / `PricePredictionEngine` | `historic_low` 3000→**0**、IQR の四分位が引きずられ**本物の高値**が外れ値として捨てられ `historic_high` 12000→8000、`predicted_7d` -22%。末尾 ¥0 では `current_price=0` かつ percentile=1.0 で `buy_now_probability` 0.167→**0.5** |
| 同上 (有効 13 件 + ¥0 17 件) | 「予測 ¥0 / 買い時確率 0.80 / MEDIUM」という完全に捏造された強い買い推奨 |
| `WeeklyDigestWorker.dropCountFrom` | `0 < addedPrice` が常に成立し ¥0 が必ず「値下がり」に数えられる |
| `PriceChartCanvas` | 下端が ¥0 に張り付き変動幅が潰れる / a11y が「期間最安 0円」/ 先頭・末尾が ¥0 だと傾向の読み上げが反転 |
| `TargetPriceChip` | ¥0 は常に target 以下 → 「目標達成」点灯。同じ画面の `WidgetVerdict` は NEUTRAL で矛盾 |

`WidgetVerdict.forItem` と `WatchlistPriceDelta.since` は**以前から**同じ規則で 0 以下を
除外していた。つまり規約自体は存在し、後から書かれた経路が守っていなかった。

**設計判断**: 下流に `> 0` を 6 個並べるのではなく、`List<PriceRecord>` がアプリ内で
生まれる唯一の場所 (`BackendClient.getPriceHistory`) を単一の関門にした
(「最良の部品は部品が無いこと」)。除外件数は `PopcoonLogger.w` に残す — 黙って捨てると
汚染がどれだけ残っているか診断できない。下流の個別ガードは多重防御として残す:
オラクル / parity ハーネスは `BackendClient` を通らないため、そこで検証できるのは
下流ガードの方だけ。

**検出力の実証**: Kotlin 側のフィルタだけを外す欠陥注入で parity が 3 件 MISMATCH に
なることを確認済み (差分にそのまま「¥0 予測 / 買い時確率 0.80」が現れる)。

---

## 6. 通知の上限が情報を捨てていた (2026-08)

`PriceSyncWorker` は 1 同期あたりの通知を 3 件に制限していたが、上限超過分を `take()` で
黙って捨てていた。価格フェーズは**選別より前に**全 Drop の確定価格を DB へ書き戻すため、
基準価格が下がった後はエッジトリガも下落率判定も再発火せず、4 件目以降の「目標価格到達」は
**二度と通知されない**。楽天スーパーセール等、同日に複数商品が値下がりする状況で現実に起きる。

過剰通知の上限そのものは文献どおり維持すべき (arXiv PMC8523513: 割り込み負荷)。
抑制すべきなのは**割り込みの回数**であって情報ではないので、超過分は 1 件のまとめ通知に
集約した (Android の通知グループ + サマリと同じ考え方)。併せて週次ダイジェストも
値下がり 0 件の週は送らないようにした — 「10件中0件が値下がり中」は情報量ゼロの
週次割り込みで、同クラスが空ウォッチリストについて掲げている方針と矛盾していた。

---

## 7. 「宣言と実装の食い違い」という単一の欠陥クラス (2026-08)

本セッションで見つけた欠陥は、分野が違っても **すべて同じ形**をしていた:
コメント・KDoc・README・テスト名が保証を宣言し、実装がそれを満たしていない。
機能の有無ではなく **宣言の検証** が抜けている、という構造的な問題。

| 宣言 | 実装 |
|---|---|
| `realPrice <= 0` は統計から除外する (`WidgetVerdict` / `WatchlistPriceDelta` が実践) | 8 経路が除外していなかった (§5) |
| 通知上限は割り込みの制御 | 超過分を `take()` で破棄し、基準価格は書き戻し済みなので**二度と再通知されない** (§6) |
| 「Opt-in only: 同意なしには 1 byte も送信しない」 | 同意前に取得したクラッシュを、後から ON にした瞬間に遡って送信 |
| 「サーバー側でも PII を二重に検査」 | クライアントが除去する 9 分類のうち **2 分類しか見ていない** |
| 「robots.txt を尊重」 | HTTP ステータスを見ず、503/429 のエラーページを「規則なし = 全許可」と解釈 |
| `evaluateCondition` の「fail-closed: 不発火扱い」 | 空 `and` / 子無し `not` / value 無し `price_above` が **必ず発火** |
| `CircuitBreaker` は「連続障害中の無駄なリクエストを止める」 | 3 クライアントが例外を `emptyList()` に潰すため **OPEN に遷移する経路が存在しない** |
| `searchWithBreaker` の「failed=true は例外、0 件は failed=false」 | 上記により常に `failed=false` で全滅判定が働かない |
| `MIGRATION_5_6` が v6 のスキーマへ移行する | 同時に追加された `price_cache` の CREATE TABLE が無く、更新時に起動不能 |
| `HALF_OPEN` は「1 件だけ試行を許可」 | 実装もテストも単一試行を強制していない |
| CSV エクスポートは価格履歴を出力する | 取得失敗を握り潰し、**欠けたことを伝えずに**共有まで進む |
| `inferCategory` の「該当しない商品では TCO 表示は無意味なため null」 | 部分一致だけで判定し、付属品・消耗品・別ジャンルを本体として拾う。「エアコン洗浄スプレー ¥980」に 5 年 277,018 円 (本体価格の 283 倍) を表示していた (§8) |
| `Tier` を MAJOR → MEDIUM → RECURRING の重要度順に宣言 | `sortedByDescending { it.tier.ordinal }` で並びが真逆。楽天スーパーセールが「5のつく日 +4%」の下に埋もれる (§9) |
| `TAX_EXEMPT_THRESHOLD = 16,666` の根拠コメント「商品代 ¥10,000 相当」 (= 個人輸入の 0.6 掛け) | 比較対象が `商品代 + 送料` で、個人輸入でも商業輸入でもない第三の体系になっている (§10) |
| `PopcoonLogger` の「PII フィルタ統合 (`PrivacyCrashReporter` と同じ regex)」 | 実際は 3 パターン古く、**本番の全ログが通る経路**で国内電話番号と Android のユーザーパスが素通り (§11) |
| ダークパターン警告は深刻度つきで検出される | 表示は `take(2)` なのに検出順のまま渡され、支払額が 3 割増える `DRIP_PRICING(HIGH)` が `CHARM_PRICING(LOW)` に押し出されていた (§12) |
| ウィジェットは verdict を出し BUY_NOW を緑で強調する (= 行動可能性を示す面) | `items.take(3)` が DAO の `addedAt DESC` のままで、目標到達品が「最近追加した 3 件」に押し出されていた (§13) |
| `extractUrl` の「上限 2048 文字で切り詰められる」 (テスト名・コメント) | 正規表現がスキーム以降しか制限せず **2056 文字**を返していた (§14) |
| `TCOCalculatorTest` の「ドラムも intensity に比例」 | 同一ファイルの「ドラムはスケールしない」と**同時に成立し得ない**。修正済み旧バグの化石が残っていた (§14) |
| `docs/ASSESSMENT-2026-07.md` 長所 #8「ドキュメントの誠実さ」 | 同じ文書に陳腐化した数値が **7 箇所** (i18n 405→365 キー等)。主張に対する自己反例 |

### テストが発見を遅らせていた事例

「テストがある」ことが安心材料にならなかったケースが 3 つあった。いずれも
**本番コードを 1 行も通っていない**か、**通っていても壊れていることを検知できない**:

- `alerts.test.ts` — `evaluateCondition` / `isValidCondition` を丸ごと再実装したコピーを
  検証。しかもコピーは fail-open のバグをそのまま写しており、34 件通っていることが
  欠陥の存在を隠していた。→ 本番から export して直接 import する形に変更。
- `AwsSigV4SignerTest` — 7 件すべて構造検査 (「64 文字の hex か」「必須ヘッダーが揃うか」)。
  署名鍵の 4 段 HMAC の順序を入れ替えても全て通る。誤署名は形だけ正しいので
  PA-API が 403 を返すまで気付けない。→ 時刻を注入可能にし、AWS 公開の鍵導出ベクタと
  Python 独立実装の 2 つの外部アンカーに固定 (実装自体にバグは無かった)。
- `CircuitBreakerTest` — 「1 回だけ許可」という名前で 2 回目が弾かれることを検証していない。
- `SaleCalendarTest`「活性セールリストは tier 降順」 — フィクスチャが **2026-07-17**
  (金曜・17 日) で、`monthlyRecurring` の 4 条件 (5のつく日 / 5と0のつく日 / 日曜 /
  11・22 日) を 1 つも満たさない。コメントは「プライムデー中 + 多数の繰り返し」と
  書いてあるが実際の活性セールはプライムデー 1 件だけで、`first().tier == MAJOR` は
  並び順と無関係に成立する。**不変条件を宣言しながら一度も踏んでいない**フィクスチャ。
  → 両 tier が同時に活性な日へ差し替え、「両 tier が含まれること」自体を先に
  アサートする形にした。さらに `run_calendar.sh` で 365 日を走査し、
  **両 tier 同時活性日が 20 日以上あること (実測 27 日)** を検査自身が表明する。
- `worker.test.ts` の PII 検査も同様に再実装コピーだったため、実ハンドラー越しに置き換えた。

**教訓**: 期待値が実装の出力そのものだったり、実装のコピーを検証していたりすると、
テスト件数は増えても検証力はゼロになる。期待値は **実装を疑える出所** (公開ベクタ /
独立実装 / 数式からの手導出) から取ること。

### 歯止め

同じ欠陥を 8 回別々に見つけた ¥0 汚染は `check_price_guard.py` として規則化した。
その他の静的ゲート (override / `R.*` 参照 / enum `when` 網羅 / テスト参照 / Room 移行) と
併せて 6 種。**全て欠陥注入で検出力を実証**しており、うち 2 件は自分の検査ツール側の
バグ (コメント内の文字列に正規表現が当たる、入れ子クラスの見落とし) を
ベースライン実行が捕まえたもの。

その後、同じ形の欠陥を 3 回見つけた「上限を掛ける前に優先順位を付けていない」も
`check_truncation.py` として規則化し **7 種**になった (§12 / §13)。
ここでも自ツールのバグを 2 件、クリーンなツリーでの誤検出として捕まえている
(`const val` 引数を文字列と判定できない / 複数行チェーンの受け側が `')'` になる)。

---

## 8. TCO のカテゴリ推定が付属品を本体として拾っていた (2026-08、修正済み)

`TCOCalculator.inferCategory()` はタイトルの部分一致だけでカテゴリを決めており、
**その本体の付属品・消耗品・工事**まで本体として拾っていた。TCO の電力・消耗品は
購入価格と独立した実額を積むモデルなので、安価な付属品に当てると表示が桁で壊れる。
`ProductDetailViewModel` が実際にこの結果で TCO パネルを出すため、そのままユーザーに見えていた。

| タイトル | 誤検出先 | 表示されていた 5 年 TCO |
|---|---|---|
| エアコン洗浄スプレー ¥980 | air_conditioner | 277,018 円 (電力 275,940 円 = 本体価格の 283 倍) |
| サプリメント カプセル ¥1,500 | coffee_capsule | 147,650 円 (カプセル代 146,000 円) |
| 3Dプリンター ¥45,000 | inkjet_printer | 無関係なインク/用紙代 106,000 円を加算 |
| プリンターインク 互換カートリッジ | inkjet_printer | 消耗品そのものに消耗品代 |
| iPhone ケース / スマホスタンド / 冷蔵庫マット / ノートPCスタンド | 各本体 | TCO パネルが出る |
| Android タブレット / スマートウォッチ Android対応 / カーエアコン ガス | smartphone / air_conditioner | 同上 |

CLAUDE.md のパターン 5「属性ペナルティは誤爆源を必ず考える」は `ProductMatcher` に
だけ適用されており、**同じ部分一致方式の `inferCategory` には適用されていなかった**。

対処は「誤検出より取りこぼしを選ぶ」設計への変更 (誤検出は桁で壊すが、取りこぼしは
TCO パネルが出ないだけ)。オラクル `popcoon_core.infer_tco_category` を新設し
(`calculate_tco` は未変更 = ゴールデン影響なし)、`kotlin_parity` に `TCOCAT` kind を
追加して 31 タイトルを実 Kotlin 実行で照合。付属品ガードを外すと 9 件 mismatch する
ことを欠陥注入で確認済み。

**係数テーブル側に残る 2 件** (`ENERGY_DB` が推定可能 7 カテゴリ中 5 つしか持たない /
smartphone の残存価値が既定 5 年でちょうど 0 になる) は `calculate_tco` = オラクル +
ゴールデンを動かすため未変更。`docs/ASSESSMENT-2026-07.md`「判断待ち: TCO の係数テーブル 2 件」に記録。

## 9. セールカレンダーの並びが重要度の逆順だった (2026-08、修正済み)

`SaleCalendar.activeSales()` が `sortedByDescending { it.tier.ordinal }`。
`Tier` は MAJOR, MEDIUM, RECURRING の**重要度順に宣言**されているので、ordinal 降順は
重要度の昇順 — 並びが真逆だった。`SaleBanner` / `SaleCalendarScreen` はこの順に
チップを出すため表示仕様そのもの。実行して確認した実害:

```
activeSales(2026-03-05) = [RECURRING, RECURRING, MAJOR]
  → 「Yahoo! 5のつく日 +4%」「楽天 5と0のつく日 +1%」が先で、
    楽天スーパーセール (春) が最後に埋もれる
```

2026 年 365 日のうち大型/中型と繰り返しが同時に活性な日は 27 日あり、そのすべてで逆順。
検出を遅らせたテストの構造は §7 の「テストが発見を遅らせていた事例」に記載。
歯止めとして `kotlin_parity/run_calendar.sh` を新設 (6,562 アサーション / 365 日走査)。
SaleCalendar は Python オラクルを持たない (日付固定の外部仕様データを二重管理する意味が
無い) が、順序・網羅性は純ロジックなので Android SDK 無しに実行検証できる。

## 10. 越境EC 試算が日本の課税ルールのどちらの体系にも一致していない (2026-08、要承認)

`CustomsSimulator.simulate()` / `popcoon_core.simulate_customs()` は

```
課税価格 = 商品代 + 送料
免税     = 課税価格 <= 16,666
関税     = 課税価格 × 税率
```

としている。しかし日本の課税価格には **2 つの体系**があり、この式はどちらでもない。

| 体系 | 課税価格 | 送料 | 免税ライン |
|---|---|---|---|
| 商業輸入 | CIF (商品代 + 運賃 + 保険料) | **含む** | 課税価格 1 万円以下 |
| **個人輸入** (本アプリの用途) | 海外小売価格 × **0.6** | **含まない** | 課税価格 1 万円以下 (= 小売価格 16,666 円以下) |
| 現行実装 | 商品代 + 送料 | 含む | 16,666 円以下 |

現行実装は **個人輸入の閾値 (16,666)** を **商業輸入の課税価格 (商品代+送料)** に
当てており、しかも課税ベースに 0.6 掛けを適用していない。
コード内のコメント「¥16,666 = 商品代 ¥10,000 相当」は 0.6 掛けを根拠にしているので、
**コメントの根拠と実装の式が食い違っている** (§7 の欠陥クラスそのもの)。

さらに、**課税価格 1 万円以下でも免税されない品目**がある:
革製のカバン・ハンドバッグ・手袋等、編物製衣類 (Tシャツ・セーター等)、
スキー靴・革靴・本底が革製の履物類等。本アプリのカテゴリ (衣類 / 靴 / バッグ) は
素材の次元を持たないため区別できず、現行は無条件に免税と判定する。

### 誤差の実測 (手計算を Python で検算、years/intensity 非依存)

| ケース | 現行 | 正しい個人輸入 | 差 |
|---|---|---|---|
| 衣類 (布製) 商品代 15,000 + 送料 3,000 | 22,376 (課税) | 18,000 (**免税**) | **+4,376 (+24.3%)** |
| 電子機器 30,000 + 送料 2,000 (ITA 無税) | 35,400 | 34,000 | +1,400 (+4.1%) |
| 靴 20,000 + 送料 4,000 | 34,520 | 29,360 | +5,160 (+17.6%) |
| 革靴 12,000 + 送料 2,000 (少額免税**対象外**品目) | 14,000 (免税と誤判定) | 17,296 (課税) | **−3,296 (−19.1%)** |

方向は 2 つある。0.6 掛け未適用 + 送料算入は **一貫して過大**で、
`verdict` を CHEAPER から遠ざける (= 越境が実際より不利に見える)。
逆に免税対象外品目の見落としは **過小**で、革製品・ニット衣類では
「免税」と表示した後に実際は課税される。

### 出典

- 税関 カスタムスアンサー 1006「課税価格の合計額が１万円以下の物品の免税適用について」
  <https://www.customs.go.jp/tetsuzuki/c-answer/imtsukan/1006_jr.htm>
  (免税対象外: 革製のカバン・ハンドバッグ・手袋等、編物製衣類、スキー靴・革靴・本底が革製の履物類等)
- 税関「少額輸入貨物の簡易税率」<https://www.customs.go.jp/tsukan/kanizeiritsu.htm>
- ジェトロ「輸入における消費税の課税：日本」<https://www.jetro.go.jp/world/qa/04A-000915.html>
- 財務省 関税・外国為替等審議会 関税分科会「急増する少額輸入貨物の課題と対応の検討」(令和7年10月17日)
  <https://www.mof.go.jp/about_mof/councils/syogakuyunyuwg/syogakuyunyuwg_gijihaihu/20251017/shiryo1.pdf>
- 解説記事 (0.6 掛けと送料の扱い): <https://hunade.com/yunyu-16666> / <https://boueki.standage.co.jp/import-duty-on-personal-imports/>

### エージェント側で実施していないこと

`simulate_customs` の式を変えるとオラクル + ゴールデンベクタ + 差分/メタモルフィック/
fuzz テストが動く **製品挙動変更**であり、CLAUDE.md の「設計判断は計画提示→承認」に
該当する。よって数値は変えず、記録に留めた。判断項目は
`docs/ASSESSMENT-2026-07.md`「判断待ち: 越境EC 試算の課税体系」を参照。
なお UI には既に `customs_disclaimer` (「関税率・手数料はカテゴリ別の概算です」) があるが、
上記 2 点の構造的なズレは開示していない。

## 11. PII サニタイザが 3 か所に複製され、本番ログ経路だけ古かった (2026-08、修正済み)

同じ規則が `PrivacyCrashReporter.sanitizeStack` (10 パターン) /
`PopcoonLogger.sanitize` (**7 パターン**) / backend `sanitizePii` (10 パターン) に
複製されていた。`PopcoonLogger` は **本番の全ログが通る経路**でありながら 3 パターン古く、
国内電話番号 (`090-…` / `03-…`)、`/data/user/0/<pkg>/files/<user>`、
`/storage/emulated/0/<user>` が素通りしていた。KDoc は
「`PrivacyCrashReporter` と同じ regex」と宣言していた。

さらに `api_key="secret"` → `api_key=[redacted]"` と開き引用符だけ消える非冪等な置換が
残っていた。backend は「サニタイズしても変わらない = PII を含まない」で二重チェックするので、
クライアントが冪等でないと **正当なクラッシュレポートが全て 400 で拒否される**。

対処: 純関数 `core/LogSanitizer.kt` へ一本化 (Android 非依存 → 実コンパイル対象)。
歯止めとして `kotlin_parity/sanitizer/corpus.tsv` (17 ケース) を作り、

- `run_sanitizer.sh` が実 Kotlin をコンパイル・実行して照合 + 全ケースの冪等性を検査
- `backend/test/sanitizer-corpus.test.ts` が **同じ corpus.tsv** を TypeScript の
  本番実装 (`sanitizePii` を export して直接 import) に対して回す

という形で **2 言語の一致を fixture drift 無しに**検証する。
期待値は正規表現から手導出したもので、どちらの実装の出力でもない。
各行に導出根拠と既知の限界を併記し、PII 無しの通常ログが無改変であること (誤爆ゼロ) も固定した。
第 3 の独立実装 (Python `re`) で 17 ケース全てを検算し 0 件乖離。

## 12. 警告の表示上限が「一番深刻なもの」を捨てていた (2026-08、修正済み)

`ProductRow` は行あたり `warnings.take(2)` しか出せないが、`SearchViewModel` は
`priceWarnings + textWarnings + drip` を **検出順のまま**渡していた。
`severity` は `UiText` へ変換する時点で捨てられるので、表示側では並べ替えようがない。

実 Kotlin (実物の `DarkPatternDetector`) を実行して確認した実シナリオ:

```
¥9,980 の商品 + 送料 ¥3,000 (総額 12,980 = 本体比 +30.06%)、
定価 12,000 に対し 40 日間 9,980、タイトルに「在庫わずか」

検出順     : CHARM_PRICING(LOW), FAKE_SCARCITY(MEDIUM), DRIP_PRICING(HIGH)
take(2) 旧 : CHARM_PRICING(LOW), FAKE_SCARCITY(MEDIUM)      ← HIGH が落ちる
take(2) 新 : DRIP_PRICING(HIGH), FAKE_SCARCITY(MEDIUM)
```

「端数価格」と「在庫わずか」だけが出て、**実際に支払額が 3 割増える警告が消えていた**。
`a11yDescription` も `take(2)` の 2 件にしか付かないため、スクリーンリーダー利用者も同様。
`CHARM_PRICING` は末尾 80/98/99 の価格で広く発火し、`detect()` 内で必ず
価格系 HIGH より後・text/drip より前に積まれるので、この並びは日常的に起きる。

これは §6 の「通知の上限が情報を捨てていた」と**同じ形**の欠陥である
(上限を掛ける前に優先順位を付けていない)。**上限・truncate を書いたら、
その直前に順序付けがあるかを必ず確認する**こと。

### enum の向きは宣言順で決まる

同じセッションで逆向きの 2 件を修正した。取り違えると静かに壊れる:

| enum | 宣言順 | 正しいソート |
|---|---|---|
| `SaleCalendar.Tier` | MAJOR, MEDIUM, RECURRING (**重要度順**) | `sortedBy { ordinal }` |
| `DarkPatternDetector.Severity` | LOW, MEDIUM, HIGH (**昇順**) | `sortedByDescending { ordinal }` |

## 13. 同じ形が 3 回出たので規則化した — 「切る前に並べる」 (2026-08)

§6 (通知上限) と §12 (警告上限) に続き、3 件目が出た。

**ウィジェットが「最近追加した 3 件」を出していた。**
`WidgetUpdater` は `items.take(3)` で `WatchlistDao` の `ORDER BY addedAt DESC` のまま
先頭 3 件を書き出す。ウィジェットは項目ごとに verdict を出し `PopcoonWidget` が
BUY_NOW を緑で強調する — 存在理由は「今どれを買うべきか」の一目確認なのに、
4 件以上ウォッチしていると **目標価格に到達した項目がホーム画面から消えていた**。
通知側 (`PriceSyncPlanner.plan`) は既に「目標到達 → 下落率」で優先度を付けており、
**同じアプリの 2 つの通知面で方針が食い違っていた**。

### 歯止め: `check_truncation.py` (静的ゲート 7 種目)

コレクションへの `.take(n)` / `.takeLast(n)` は次のいずれかを要求する:

1. 同じ式の中に並べ替えがある (`sorted*` / `sortedWith` / `prioritize` 等)
2. 受け側 `val` の初期化式に並べ替えがある
3. `// truncate-order-ok: <理由>` を直前に書く

文字列の切り詰めは引数の大きさで除外する。本リポジトリの実測では
コレクション側 2〜7 / 文字列側 15〜8000 と綺麗に分かれており、`const val` も解決する。
複数行に折り返したチェーンを読めるよう、受け側の遡りは括弧の対応を取る。

クリーンなツリーで誤検出ゼロ。既存 7 箇所には**根拠付きの注記**を入れた
(時間窓 / 同一 severity 内 / 呼び出し側が並べる / DAO の `ORDER BY` / Trie の返り順)。
注記自体が「なぜ切ってよいか」の文書になる。

欠陥注入 4 種で検出力を実証:

| 注入 | 検出 |
|---|---|
| `topForWidget` の並べ替えを外す | `run_widget.sh`「目標到達品が先頭でない」 |
| `dropPercent` の ¥0 ガードを外す | `run_widget.sh`「¥0 の下落率が 0 でない」 |
| `truncate-order-ok` の注記を消す | `check_truncation.py` が該当行を報告 |
| 新規の未順序 `take` を足す | 同上 |

**自ツールのバグも 2 件、クリーンツリーでの誤検出として捕まえた** —
`const val` 引数を文字列と判定できず `PopcoonLogger` を誤報、
複数行チェーンの受け側が `')'` になり `PriceSyncPlanner` を誤報。
過去 (§7 の歯止め) と同じで、ゲートを足すときはまずベースライン実行が自分を検算する。

## 14. 「CI が要る」を疑う — kotest 無しで kotest spec を実行した (2026-08)

### 要件の読み替え

「kotest を動かすには CI が要る」を**要件そのものとして疑った** (マスク法①)。
実際に必要なのは CI ではなく「spec のアサーションが真だと確かめること」である。

制約の再測定: `repo1.maven.org` / `repo.maven.apache.org` / `dl.google.com` /
`services.gradle.org` / `plugins.gradle.org` はいずれも egress プロキシが CONNECT に 403。
kotest の jar は取得できない。しかし **kotest を取得する必要は無い** —
全 spec を走査すると、テストが実際に使う kotest シンボルは **42 個**、
spec スタイルは **63 ファイル全てが StringSpec** だった。表面が小さい。

そこで `kotlin_parity/kotest_shim/` に 42 シンボルだけを実装 (15 ファイル)。
`run_kotest.sh` が production の `core.jar` (対象選定は `run_compile_core.sh` に委譲) と
シムに対して **テストファイルを 1 行も変えずに**コンパイル・実行する。

### 結果: 初回 409 アサーション中 11 件が偽 (2.7%)

`app/src/test` の 63 spec は**一度も実行されたことがなかった**。
`check_test_refs.py` は参照シンボルの実在しか見ておらず、アサーションの真偽は未検証。
31 spec を初めて走らせた内訳:

| 種別 | 件数 | 代表例 |
|---|---|---|
| production の欠陥 | **1** | `extractUrl` が宣言の「上限 2048 文字」に反し 2056 文字を返す (スキーム 8 文字分) |
| フィクスチャが不変条件を踏めない | 1 | AdviceCache「真の LRU 回帰」が LRU と FIFO を区別できず、正しい LRU に対して落ちる |
| 外部仕様と食い違う定数 | 4 | ASIN は厳密に 10 文字なのにフィクスチャが 11〜12 文字 — 原理的に一致し得ない |
| 日付依存のフレーキー | 2 | `purchaseDate` 未固定で実行日が 5/0 のつく日 (月 6 日) だと落ちる |
| オラクルに対して一度も成立しない期待値 | 2 | TCO の保守費を無視した 50,000、消耗品の不等号が逆 |
| **互いに矛盾する 2 テストの同居** | 1 | 「ドラムも intensity に比例」と「ドラムはスケールしない」が同一ファイルに共存 |

**production のバグは 1 件だけ**だった。これは差別化ロジックが Python オラクル +
parity ハーネスで既に守られていることの裏付けでもある。
一方で、CI を有効化した初回実行は **10 件の赤で始まるはず**だった。

### 新種の欠陥クラス: 互いに矛盾する 2 テストの同居

`TCOCalculatorTest` に「ドラムも intensity に比例 (×2)」と
「intensity=2.0 でもドラムはスケールしない (121,200 を固定)」が共存していた。
**両者は同時に成立し得ない**。前者は差分パリティで検出・修正済みの旧バグ挙動を
固定した化石で、修正時に消し忘れたもの。テストが実行されていれば
その場で衝突が露見したが、実行できないので何年でも同居できた。

§7 の「テストが発見を遅らせていた事例」に対する新しい形:
これまでは「通るが検証力ゼロ」だったのに対し、これは「**そもそも通らないが、
誰も走らせないので気付かれない**」。前者は静的検査で拾いにくいが、後者は
**実行しさえすれば必ず落ちる** — 実行環境を用意することが唯一の対策だった。

### さらに 3 段階で対象を広げた (31 → 36 spec / 409 → 542 アサーション)

初回の除外理由を 1 件ずつ潰した。いずれも**依存の不足ではなくシム側の不備**だった:

| 対処 | 増分 | 内容 |
|---|---|---|
| `-Xfriend-paths=core.jar` | +3 spec / +89 | Gradle の test source set と同じ扱いにして `internal` を可視化。AwsSigV4Signer / ProductMatcher / PricePredictionEngine という**最も価値の高い 3 領域**が動くようになり、全て green |
| `beforeTest` をシムに追加 | +1 spec / +6 | 各テスト前フック。ランナーが `beforeHooks` を実行 |
| 演算子優先順位の誤りを修正 | +1 spec / +38 | 下記 |

**`DarkPatternTextDetectorTest` は kotest があってもコンパイルできなかった。**
`X in c shouldBe true` は Kotlin の優先順位規則 (infix 関数呼び出し > `in`) により
`X in (c shouldBe true)` と解釈される。括弧が必須。
`check_test_refs.py` は参照シンボルの実在しか見ないので、**コンパイル不能なテストファイルが
まるごと 1 つ**、誰にも気付かれずに存在していた。同種の誤りが他に無いか全テストを走査し、
残る 2 箇所 (`DatabaseIntegrityTest`) は正しく括弧が付いていることを確認した。

### 残り 27 spec の詰まりを測った (実装は見送り、判断項目として記録)

最多の詰まりは Room エンティティ `WatchlistItem`。ただし本当の制約は Room ではなく
**ファイルの置き場所**だった:

- `androidx.room` を import する production ファイルは **2 つだけ**
  (`data/db/PopcoonDatabase.kt` と `di/DatabaseModule.kt`)
- `WatchlistItem` が要求する Room シンボルは `@Entity` / `@Index` / `@PrimaryKey` の
  **アノテーション 3 つのみ**。ASSESSMENT が既に「プロセッサ不在では実物も不活性なので
  スタブが嘘をつけない」と判定している種類
- しかしエンティティが `PopcoonDatabase.kt` に同居しているため、`data.db` パッケージ全体が
  Android 依存として扱われ、それを import する**純ロジックのファイルまで巻き添え**になる

巻き添えの実測: **自身は Android 非依存なのに `data.db` の import だけで落ちているのは 2 件** —
`feature/cart/SmartCartService.kt` と `feature/watchlist/WatchlistSort.kt`。
どちらも差別化機能かつ ¥0 汚染監査の対象だった重要ファイル。
解消すれば実コンパイル +2 ファイル、kotest +2 spec (`SmartCartServiceTest` / `WatchlistSortTest`)。

**採った手段**: 当初「設計判断だから承認を待つ」と判断したが、これは自分への問いが
足りていなかった。案 (a) エンティティを別ファイルへ切り出す = production の構成変更で、
`PopcoonDatabase.kt` 自体はコンパイル検証できないため移動の正しさを実行で確かめられない。
一方 案 (b) は **`RStub.kt` と完全に同じ方式** — ハーネスが実ファイルから毎回切り出して
生成する — で、production を 1 行も触らない。既にこのリポジトリが採用している
テスト基盤の作法そのものであり、設計判断ではなく機械的作業だった。(b) を実施した。

`run_compile_core.sh` が `PopcoonDatabase.kt` から `@Entity` ブロックを行単位で切り出して
`EntityStub.kt` を生成し、`androidx.room` のアノテーションスタブと共にコンパイルする。
**コピーではなく実ソースの部分集合**なので、本体を変えれば次回の実行に自動反映される。
生成したスタブは「Android 非依存で `data.db` を提供するファイル」として対象選定に合成登録し、
DAO 等の未提供宣言を参照するファイルが混ざれば実コンパイルが失敗して顕在化するようにした。

結果: 実コンパイル **48 → 51 ファイル**、kotest **36 → 38 spec / 542 → 568 アサーション**、
いずれも 0 failed。予測 (+2 ファイル / +2 spec) と一致した。

### さらに残る 25 spec — 詰まりの正体は「純ロジックの同居」

残りを見ると、**依存が本当に必要なわけではなく、純粋な関数が Compose/Android のファイルに
同居している**ために動かせないものが複数ある: `watchlistBuyVerdict` (WatchlistScreen.kt)、
`SearchRow` (SearchScreen.kt)、`filterByRange` (PriceChart)、`csvEscape`、`PopcoonWidgetLogic`。
本リポジトリは既に `WidgetVerdict` / `PriceSyncPlanner` / `BundlePackDetector` で
「純ロジックを別ファイルへ切り出す」パターンを確立しており、同じ処方が効く。
**4 件を切り出した** (同一パッケージ内の分割なので呼び出し側は無変更): `watchlistBuyVerdict` →
`WatchlistBuyVerdict.kt`、`PriceChartRange`/`filterByRange`/`plottableRecords` → `PriceChartData.kt`、
`csvEscape` → `CsvEscape.kt`、`PopcoonWidgetLogic` → `PopcoonWidgetLogic.kt`。
結果: 実コンパイル 51 → **55**、kotest 38 → **42 spec / 602 アサーション**、0 failed。
`csvEscape` の数式ガードを外す欠陥注入で `CsvEscapeTest` が落ちることを確認。
`SearchRow` だけは `List<UiText>` (Compose 束縛) を持つため見送り (`SortAndFilterTest` は CI 待ち)。

### 明示した限界 (過大評価しない)

実行のたびに件数を表示する。63 spec 中:

- **36 実行**、3 は Android/coroutines import で除外、
  24 は production 側が Room/Hilt/ktor 依存でコンパイル不能
  (最多の詰まりは Room エンティティ `WatchlistItem` の 4 spec)
- プロパティテストは kotest 既定 1000 回ではなく **300 回**をシード固定 (shrinking なし)
- 非対応の kotest 機能を使った spec は**コンパイルエラー**で落ちる (黙って通らない)

欠陥注入で検出力を実証: `TCOCalculator` の保守費を `purchasePrice/10` → `/20` に改変すると
2 spec (未知カテゴリの maintenance / vsAlternative のゴールデン値) が失敗する。

### 教訓

**「テストがある」と「テストが検証している」の間には、「テストが実行されている」がある。**
本リポジトリは §7 で前者 2 つのギャップ (検証の演劇) を潰してきたが、
3 つ目のギャップ — 実行そのものの不在 — は測っていなかった。
実測値は 409 中 11 = 2.7%。テストコードもコードであり、書きっぱなしは腐る。
しかも 1 ファイルは**コンパイルすら通らないまま**存在していた。

## 15. 「通っている」と「検証している」は別 — Kotlin 突然変異テスト (2026-08)

### 問い

43 spec / 618 アサーションが緑になった。だが本セッションだけで
**フィクスチャが不変条件を一度も踏んでいない** spec を 2 件見つけている
(SaleCalendarTest §7 / AdviceCacheTest §14)。ならば当然の問いは
**「この 43 spec は本当に検証しているのか」**。

Python 側は `mutation_test.py` で検出能力を測っていたが、Kotlin 側は
そもそも実行できなかったので未測定だった。実行できるようになった今、同じ問いを向けられる。

### `mutation_kotlin.py`

意味のあるバグ 15 種を本番コードへ注入し、spec が落ちるかを測る。
等価変異 (挙動が変わらない書き換え) は測定を汚すので入れない。

**高速化**: 素朴に毎回 `run_kotest.sh` を回すと 1 注入 60〜90 秒。
変異させた **1 ファイルだけ**を再コンパイルし、生成 jar を `core.jar` より前に置く
(JVM のクラスパスは先勝ち) ことで数秒に短縮した。

**その高速化が自分を騙した**: 初回測定は MU06 (robots.txt の全許可化) を「生存」と報告した。
しかし直前に手動注入で kill されることを確認済みだった。原因は Kotlin の `const val` が
呼び出し側のバイトコードへ**インライン展開**されること — 定数の宣言ファイルだけ
再コンパイルしても、コンパイル済みの spec には反映されない。
`const val` を含む変異は自動判別してフルパイプラインへフォールバックするようにした。
**測定ツールが嘘をつくと「検証していない箇所」の地図そのものが嘘になる**ので、
ここは速度より正しさを取った (§13 で自ツールのバグを 2 件捕まえたのと同じ構図)。

### 結果: 初回 73% → 最終 93%

| 生存した変異 | 正体 | 対処 |
|---|---|---|
| MU02 目標価格ちょうど到達を買い時にしない | **境界を踏むフィクスチャが 1 つも無かった**。ユーザーが明示した条件なので、ちょうど一致で買い時にならないのは仕様違反 | `WatchlistBuyVerdictTest` に境界の両側 (== と +1 円) を追加 |
| MU15 通知の優先度を逆転 | 本セッションで直した `PriceSyncPlanner` の並びが、**どのゲートでも検証されていなかった** — `PriceSyncWorkerLogicTest` が `PriceSyncWorker.WORK_NAME` (WorkManager 依存クラスの定数) のせいで実行できなかったため | `WorkNames.kt` を新設して両 Worker の一意名を集約。`dropCountFrom` も `WeeklyDigestLogic.kt` へ切り出し。**2 spec 解錠** |
| MU14 国内電話番号の除去を削除 | **誤読しやすい生存**。kotest では守られていないが `run_sanitizer.sh` の共有コーパスが守っている (注入して 2 件 fail することを実証) | 測定範囲の注記として残す。二重に持たせない |

最終: **14 killed / 1 survived (93%)**。生存 1 件は上記のとおり別ハーネスが守っている。

### WorkNames を 1 箇所に集めたのは検証のためだけではない

`enqueueUniquePeriodicWork` は名前が衝突すると既存のスケジュールを**黙って置き換える**。
名前が各 Worker の companion に散っていると、コピー & ペーストでの衝突に気付けない。
1 箇所に並べれば重複が目で見えるし、Android 非依存なので実コンパイルと kotest の対象にも入る。

### 教訓

§14 で「テストがある / 検証している / 実行されている」の 3 段を区別した。
突然変異テストはその **2 段目を初めて数値で測った**もので、
実行できるようにした直後に測ったからこそ「実行はされているが検証していない」箇所
(MU02) と「そもそも実行経路が無い」箇所 (MU15) を切り分けられた。

## 16. 初回 CI 実行を守る — ビルド構成の静的検査 (2026-08、静的ゲート 8 種目)

### 前提の再測定

「CI 有効化は人手ゲート」を記憶ではなく**実測で確認し直した**。
`.github/workflows/android.yml` を含むブランチを push すると:

```
! [remote rejected] (refusing to allow a GitHub App to create or update workflow
  `.github/workflows/android.yml` without `workflows` permission)
```

GitHub App に `workflows` 権限が無い。これは硬いゲートで、エージェント側からは越えられない。

### 「ビルド検証は実行するか諦めるかの二択」を疑う

越えられないのは *実行* だけである。**初回 CI 実行が即死する類の設定ミスは静的に決まる**。
`run_compile_core.sh` の型検査がカバーするのはソースだけで、
ビルド構成 — version catalog / Manifest / リソース XML / ワークフロー YAML — は
**誰も見ていなかった**。初回実行はこの環境で唯一の実ビルドになるので、
そこで初めて分かる必要のない失敗を先に潰す価値が高い。

`check_build_config.py` (静的ゲート 8 種目) の検査:

| 検査 | 落ちたときに起きること |
|---|---|
| `libs.*` が `gradle/libs.versions.toml` に実在するか | `Unresolved reference: libs` で構成フェーズ即死 |
| `AndroidManifest.xml` の `android:name` のクラスが実在するか | **ビルドは通り、実行時に ClassNotFoundException** |
| `res/` の XML が整形式か | aapt が即座に落ちる |
| `ci/android.yml` が整形式 YAML で `name`/`on`/`jobs`/`runs-on` を持つか | 1 ステップも走らない |

`on:` が YAML 1.1 で真偽値 `True` に解釈される GitHub Actions の既知の罠にも対応した。

### ここでも自ツールが先に嘘をついた

初回実行は 16 件の「未定義」を報告した — `libs.plugins.hilt` など。しかし実際には
version catalog の `[plugins]` セクションに全て存在する。原因は
**セクションごとの名前空間** (`[libraries]` → `libs.X` / `[plugins]` → `libs.plugins.X` /
`[bundles]` → `libs.bundles.X`) を落としていたこと。
§13・§15 に続き 3 回目の「ゲートを足すときはまずベースライン実行が自分を検算する」。

欠陥注入 4 種で全ての検査経路を実証:
catalog から hilt を削除 / Manifest のクラス名をタイポ / strings.xml を壊す /
job から `runs-on` を削除 — いずれも検出して復旧を確認した。

---

## 本セッションの実装サマリ (このブランチ)

| 項目 | コミット種別 | 検証 |
|---|---|---|
| OBSTRUCTION (解約妨害) ダークパターン検出 | feat | oracle +8 (負ケース 3) → run.sh parity 109 → 117 matched / 4 ロケール 399 キー一致 |
| conformal margin の較正 horizon 一致 (7d/30d 分離) | fix | oracle +8 → run.sh parity 117 → 126 matched / 被覆 53.8%→91.8%, 20.5%→90.5% を実測 |
| 残差生成器 (HOLTRES) を parity 対象に追加 | test | 境界 9 ケース、実 Kotlin コンパイル・実行で 0 乖離 |
| 基準線数値と 32 類型記載の訂正 | docs | 記載コマンドを実行して一致確認 |
| ProductMatcher IDF-lite トークン重み付け (B3) | feat | oracle +9 → run_matcher 全 assertion pass / 別SKU 4 ペアを 0.600→0.5 台へ分離 |
| ProductMatcher 特徴量メモ化 | perf | 出力不変 (17×17 全ペア等価性を parity 固定) / 320 件 2.5s → 112ms (22.5x) |
| URGENCY recall 拡張 (あと/締切迫る/英語) | feat | oracle +6 → run.sh parity 126 → 136 matched / 誤爆ガード 4 ケース |
| 7日先の予測アンサンブル (B1) | feat | oracle +14 → run.sh parity 136 → 155 matched / MAE -2〜-18% / 被覆 89.8〜90.8% / golden 手導出更新 |
| UserPreferences の override 欠落 (コンパイルエラー) 修正 | fix | 最小再現を実コンパイルで確認 + スタブで契約充足を検証 |
| ヤフショ会員ランク次元 / 感謝デー (B4) | feat | run_points parity +7 ケース / 4 ロケール 405 キー一致 |
| `run_compile_core.sh` 新設 (Android 非依存 34 ファイルを実コンパイル) | test | 欠陥注入 3 種で検出能力を実証 / run_all 13 → 14 ハーネス |
| `check_overrides.py` 新設 (コンパイル不能な 85 ファイルも含む override 欠落検査) | test | 実バグ再現で検出を実証 / 初版の偽陽性 4 件を depth 判定で解消 |
| backend の未テスト経路を補完 (C4) | test | vitest 70 → 80 / DELETE /v1/alerts/{id} はルート全体が未テストだった |
| FallbackScraper の多戦略価格抽出 + ¥0 捏造の停止 | fix | run_jsonld parity +22 assertion / refresh の「失敗時 null」契約を回復 |
| backend の npm install 復旧 (workers-types v5) | fix | tsc 0 errors / vitest 70 tests pass |
| 通知上限による価格アラートの永久喪失 | fix | plan() の分割を実コンパイル・実行で 13 assertion / Kotest 回帰 6 件 / 4 ロケール 365 キー |
| 週次ダイジェストの ¥0 計上と 0 件週の通知 | fix | dropCountFrom を実コンパイル・実行で 8 ケース / Kotest 回帰 3 件 |
| 予測エンジンの ¥0 除外 (oracle 先行) | fix | oracle 494 → 500 / parity 155 → 164 matched / 欠陥注入で 3 件 MISMATCH を実証 |
| 価格グラフ・目標達成チップの ¥0 除外 | fix | plottableRecords を実コンパイル・実行で 6 ケース / Kotest 回帰 5 件 |
| 価格履歴の入口 (`BackendClient`) に単一の ¥0 関門 | fix | run_compile_core OK / 除外件数を PopcoonLogger に記録 |
| Amazon PA-API の ¥0 Product 捏造を停止 | fix | Offers 無し商品は null / Rakuten・Yahoo も入口で 0 以下を除外 |
| `check_resources.py` 新設 (`R.*` 参照 388 件の実在検査) | test | 全 131 ファイル対象 / Compose ファイルへの注入 2 件で検出を実証 |
| `check_when_exhaustive.py` 新設 (enum `when` の網羅漏れ) | test | 29 enum-when / skip 0 / Glance と実際の回帰再現の 2 件で実証 / 初版の偽陽性 1 件 (多行ラベル) を修正 |
| `check_test_refs.py` 新設 (テスト→本番シンボル 1,139 件) | test | kotest はコンパイル不能 / 改名の追随漏れ注入で実証 / 初版の偽陽性 1 件 (入れ子クラス) を修正 |
| 実コンパイルを 34 → 46 ファイルへ拡張 | test | KDoc の型名が「参照」と誤判定され対象が縮んでいた + coroutines/javax.inject の実 jar が手元にあった |
| クラッシュ同意の遡及送信を停止 | fix | 取得時点の同意で保存先を分離 / PRIVACY.md に記載を追加 |
| サーバー PII 検査をクライアントと同じ 9 分類へ | fix | backend 84 → 96 tests / 冪等性の回帰ガードを追加 |
| robots.txt の 429・5xx を全面禁止として扱う (RFC 9309) | fix | 本番 RobotsTxt に合成規則を通して 10 assertion |
| Room `MIGRATION_5_6` の `price_cache` 作成漏れ | fix | 到達性を list_releases で評価 / check_migrations.py を新設 |
| CSV エクスポートの失敗握り潰しと逐次 HTTP | fix | 失敗件数を UI へ / Semaphore(8) / 4 ロケール plurals |
| アラート条件の fail-open 3 経路 | fix | backend 96 → 105 tests / 評価側と検証側の両方で塞ぐ |
| 検索クライアントの例外握り潰し (ブレーカー不達) | fix | 3 クライアントで伝播へ / 全滅判定も回復 |
| SigV4 テストを AWS 公開ベクタ + 独立実装に固定 | test | 時刻注入で既知応答テストが可能に (実装のバグは無し) |
| 商品詳細の再試行連打による stale 上書き | fix | loadJob のキャンセル (SearchViewModel と同形) |
| 静的ゲートを 6 種へ拡張 | test | 全て欠陥注入で検出力を実証 / 自ツールのバグ 2 件も検出 |

## 検証基準線 (2026-08 実測)

すべて `python3 ci/verify.py` で一括実行・自動照合できる (CLAUDE.md の表が基準線の単一の源)。

- Python: **507 passed / 1 skipped** (`popcoon-tdd/`)
- Kotlin parity: **run_all.sh 14 ハーネス全 pass** (run.sh 164 matched / 0 mismatched、core compile 47 ファイル)
- backend: **tsc 0 errors / vitest 105 tests pass**
- i18n: **4 ロケール × 365 strings** (+4 plurals) 完全一致
- 静的ゲート: **6 種**すべて OK (`run_compile_core.sh` が一括実行)
