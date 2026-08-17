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

## 検証基準線 (2026-08 実測)

- Python: **490 passed / 1 skipped** (`popcoon-tdd/`)
- Kotlin parity: **run_all.sh 14 ハーネス全 pass** (run.sh 155 matched / 0 mismatched、core compile 34 ファイル)
- backend: **tsc 0 errors / vitest 80 tests pass**
- i18n: **4 ロケール × 405 strings** (+3 plurals) 完全一致
