# Popcoon 改善点の洗い出し — 同種ソフト & arXiv 調査

同種ソフト（競合アプリ）と arXiv の知見を参照し、Popcoon の機能・アルゴリズム上の
改善点を洗い出した。各項目は該当コードにマッピングし、優先度を付す。
（先行の `IMPROVEMENTS.md` のバックログを、外部リサーチで裏付け・拡張する位置づけ）

## 調査した同種ソフト
- **Keepa** (Amazon価格追跡): 価格履歴/急落アラート、**将来価格予測 (AI forecasting)**、**Daily Drops (値下げフィード)**、**Sales Rank**、**在庫復活通知**、CSV、API、11マーケット横断。
- **CamelCamelCamel**: 無料、**Amazon ウィッシュリスト取込**、価格目標アラート。
- **Pricey (日本)**: 60+通販サイト横断、**送料込み比較**、ウォッチリスト自動追跡＋値下げ/再入荷通知。
- **価格.com**: スペック指定の**こだわり検索**、価格ランキング。
- **PLUG / Honey / Capital One Shopping**: **ブラウザ自動最安検索オーバーレイ**、**クーポン自動適用**、**Droplist（バリエーション色/サイズ単位の追跡＋最小値下げ幅のユーザー設定）**。

## 競合ギャップ（Popcoon に無い / 弱い機能）

| # | 改善点 | 競合根拠 | Popcoon 現状 / 該当コード | 優先 |
|---|--------|----------|---------------------------|------|
| G1 | **再入荷（在庫復活）通知** | Keepa, Pricey | `Product.stockCount` はあるが `PriceSyncWorker` は値下げ通知のみ | 高 |
| G2 | **値下げ閾値のユーザー設定** | Honey (min-drop), Pricey | `PriceSyncWorker.MIN_DROP_PERCENT=3%` 固定 → `UserPreferences` で可変化 | 高 |
| G3 | **クーポン自動検出/適用** | Honey, Capital One, PLUG | `Product.couponAmount/couponCode` フィールドのみ、発見ロジック無し | 中 |
| G4 | **値下げフィード / 注目ディール** | Keepa Daily Drops, CamelCamel Top Drops | 発見系サーフェス無し（検索起点のみ） | 中 |
| G5 | **ウォッチリストのバリエーション単位追跡** | Honey Droplist | `WatchlistDao` は SKU 単位だが色/サイズ束ね無し | 中 |
| G6 | **対応モール拡大**（メルカリ/PayPayモール/au PAY 等） | Keepa 11市場, Pricey 60+ | Amazon/楽天/Yahoo の3社 (`data/network/*`) | 中 |
| G7 | **スペック/属性での絞り込み検索** | 価格.com こだわり検索 | キーワード/バーコードのみ (`SearchViewModel`) | 低 |
| G8 | **共有→アプリ流入の強化**（ブラウザ連携の代替） | PLUG/Honey のオーバーレイ | `UrlClassifier` + deep link はあるが受動的 | 低 |

## arXiv 由来のアルゴリズム改善

### A1. 価格予測の高度化 — `feature/prediction/PricePredictionEngine.kt`
現状は Holt 線形平滑（季節性なし）。研究知見：
- 時系列予測サーベイ (arXiv:2411.05793) — 短系列では **DLinear/NLinear 等の単純線形＋分解が Transformer を上回る**ことが多い（軽量・オンデバイス向き）。
- VMD + DNN による EC 価格予測（PMC11622893）— **分解 (decomposition)** がノイズ除去に有効。
- → **改善**: (a) 季節成分を持つ Holt-Winters か、トレンド/季節を線形分解する DLinear 風へ。(b) 給料日・5と0のつく日・GW/年末等の**週次/月次季節性**を加味（`SaleCalendar` と連携）。(c) ゼロ依存制約に合致（重量級不要）。既存 `predictionMargin`（RMSE 区間）はキャリブレーション検証を追加。

### A2. ダークパターン検出の体系化 — `feature/darkpattern/DarkPatternDetector.kt`
現状: ALWAYS_ON_DISCOUNT / INFLATED_LIST_PRICE / PRE_SALE_MARKUP / CHARM_PRICING / Drip Pricing。研究知見：
- ダークパターン・データセット (arXiv:2211.06543) と検出 (arXiv:2406.01608) — **7類型の taxonomy**（Sneaking, Urgency, Misdirection, Social Proof, Scarcity, Obstruction, Forced Action）。BERT/RoBERTa で 0.97 精度。
- → **改善**: 既存検出を上記 taxonomy にマッピングし、**未カバー類型を追加** — 偽の緊急性（カウントダウン）、偽の希少性（「残り2点」演出）、ソーシャルプルーフ操作。オンデバイス制約上、テキストヒューリスティック＋数値ルールで近似（重量級モデルは送信プライバシーに反するため不採用、`ProductMatcher` と同方針）。

### A3. レビュー信頼度（サクラ）検出の強化 — `feature/review/ReviewTrustScorer.kt`
現状: 評価値＋レビュー数のヒューリスティック。研究知見：
- LLM 生成偽レビューは**人間にもモデルにも判別困難**になりつつある (arXiv:2506.13313)。
- FraudSquad (arXiv:2510.01801) — **言語モデル埋め込み＋グラフニューラルネット**で spam レビュー検出。
- → **改善**: GNN/LLM はオンデバイス不可だが、**分布シグナル**を追加可能 — 評価ヒストグラムの歪度（星5偏重）、レビュー**バースト（投稿速度）**、Verified-Purchase 比率、新規アカウント比率。「AI生成レビューは検出限界に達しつつある」旨の**正直な注意表示**を UI に出すと差別化。

### A4. 商品名寄せ — `feature/matching/ProductMatcher.kt`
既に arXiv:2512.07232 / 1907 を参照済み（Rough→Fine の2段階、JANコード優先）。研究と整合。**改善余地**: Fine 段階で属性（ブランド/型番/容量）の重み付き一致を追加し、Jaccard 単独より誤検出を低減。

## 推奨着手順
1. **G1 再入荷通知 + G2 閾値ユーザー設定**（`PriceSyncWorker` + `UserPreferences`、小規模・高需要・既存資産で実装可）。
2. **A1 価格予測の季節性**（買い時判断の核、`SaleCalendar` と相乗、Python TDD で先行検証可能）。
3. **A2 ダークパターン taxonomy 拡張**（既存検出器の自然な延長、テスト容易）。
4. **G4 値下げフィード / A3 レビュー分布シグナル**（差別化）。

## 出典
- Keepa: https://keepa.com/ ／ 比較: https://goaura.com/blog/camelcamelcamel-vs-keepa
- CamelCamelCamel: https://camelcamelcamel.com/
- Pricey: https://www.pricey.jp/ ／ 国内アプリ比較: https://app-liv.jp/shopping/all/0799/
- Honey (Droplist/クーポン): https://www.moneycrashers.com/honey-browser-extension-review/ ／ Capital One Shopping: https://www.capitalone.com/learn-grow/money-management/capital-one-shopping/
- 時系列予測サーベイ: https://arxiv.org/abs/2411.05793 ／ VMD+DNN 価格予測: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11622893/
- ダークパターン: https://arxiv.org/abs/2211.06543 ／ https://arxiv.org/abs/2406.01608
- 偽レビュー: https://arxiv.org/abs/2510.01801 ／ https://arxiv.org/html/2506.13313v1
