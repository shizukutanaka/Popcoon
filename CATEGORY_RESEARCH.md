# Popcoon カテゴリ別 改善調査（arXiv / GitHub）

Popcoon の機能ドメインを **10カテゴリ**に分け、各カテゴリで arXiv 論文・GitHub OSS を
~10件ずつ収集し、該当コードに紐づく改善点を洗い出す。`/loop` で分割実行（進捗は本表で管理）。

| # | カテゴリ | 該当コード | 状態 |
|---|----------|-----------|------|
| 1 | 価格予測・時系列 | `feature/prediction/PricePredictionEngine.kt` | ✅ 完了 |
| 2 | 買い時判定・割引タイミング | `feature/scorer/BuyTimingScorer.kt`, `feature/calendar/SaleCalendar.kt` | ✅ 完了 |
| 3 | 商品名寄せ・エンティティ解決 | `feature/matching/ProductMatcher.kt` | ✅ 完了 |
| 4 | レビュー信頼性・偽レビュー検出 | `feature/review/ReviewTrustScorer.kt` | ✅ 完了 |
| 5 | ダークパターン検出 | `feature/darkpattern/DarkPatternDetector.kt` | ✅ 完了 |
| 6 | ポイント/報酬・キャッシュバック最適化 | `feature/points/PointSimulator.kt` | ✅ 完了 |
| 7 | 越境EC・関税・為替 | `feature/crossborder/CustomsSimulator.kt` | ✅ 完了 |
| 8 | バーコード/JAN・商品認識 | `feature/barcode/*` | ✅ 完了 |
| 9 | 通知・リテンション | `worker/PriceSyncWorker.kt`, `feature/notification/*`, `feature/retention/*` | ✅ 完了 |
| 10 | プライバシー・オンデバイスML・セキュリティ | `data/repository/BackendClient.kt`, `feature/crash/*`, `data/network/AwsSigV4Signer.kt` | ✅ 完了 |

---

## カテゴリ1: 価格予測・時系列 — `PricePredictionEngine.kt`（現状: Holt 線形）

**arXiv:**
1. 線形モデル分析 (2403.14587) — DLinear/NLinear が強力なベースライン、Transformer 超えも多い
2. Super-Linear (2509.15105) — 線形エキスパート混合、24中19でMSE最小・軽量
3. XLinear (2601.09237) — 外生変数対応の軽量MLP、外部シグナル（在庫等）を取り込める
4. LightGTS (2506.06005) — 軽量汎用予測
5. 時系列予測サーベイ (2411.05793) — アーキ多様性
6. VMD+DNN 価格予測 (PMC11622893) — 分解でノイズ除去
7. 軽量オンライン Conformal (2505.08158) — 被覆保証付き区間
8. 時系列CP入門 (2511.13608)

**GitHub:**
9. product-price-tracker — Keepa API＋**LSTM で min/max 価格と発生時期を予測**、現在価格を履歴平均に対し格付け
10. price-tracker / amazon-price-tracker topics — Keepa の「変化時のみ記録」をギャップ補間する前処理パターン

**改善点:**
- Holt 線形 → **NLinear/DLinear 風の trend+seasonal 線形分解**（軽量・オンデバイス・ゼロ依存に合致）。
- **XLinear 的に外生変数**（在庫数 `stockCount`、`SaleCalendar` の特売フラグ）を予測に投入。
- product-price-tracker に倣い **min/max とその発生時期**を併せて予測し「○日後が底」を提示。
- Keepa 流の**履歴平均に対する現在価格の格付け（percentile）**を `BuyTimingScorer` シグナルに追加。
- `predictionMargin` を **Conformal 区間**へ（被覆保証）。

## カテゴリ2: 買い時判定・割引タイミング — `BuyTimingScorer.kt` / `SaleCalendar.kt`

**arXiv:**
1. マークダウン×価格弾力性 (2105.08313) — 反実仮想で最適値下げタイミング
2. Best/Worst time-to-buy 特許 (USPTO 8762219) — 月/曜日/日付別の想定割引
3. 購買意図予測 (2012.08777)
4. DQN 購買行動予測 (2506.17543)

**GitHub:**
5. HelenGuohx/price-alert — 目標価格到達通知のフルスタック
6. BexTuychiev/automated-price-tracking — 複数ECを横断し閾値割れで通知
7. duyet/pricetrack — Firebase ベース、tiki/shopee 対応
8. AliAlboushama/Steam-Game-Price-Alert — **"Lowest Price Ever!" 検出**
9. omerhalid/Amazon-Price-Tracker — 目標価格メール
10. product-price-tracker の **price rating（履歴平均比の格付け）**

**改善点:**
- **曜日/日付別の平均割引**を履歴から集計し「今日は統計的に安い/高い曜日」シグナルを追加（特許 8762219、`SaleCalendar` と統合）。
- **"過去最安(ATL)更新"の明示バッジ**（Steam bot の "Lowest Price Ever" UX）。
- 価格弾力性の概念を簡易化し「値下げ余地が小さい＝今が買い」判定に反映。
- 目標価格アラート（競合の基本機能）を Popcoon の `WatchlistDao` に追加（ユーザー指定の目標価格）。

## カテゴリ3: 商品名寄せ・エンティティ解決 — `ProductMatcher.kt`（現状: JAN＋型番＋Jaccard）

**arXiv:**
1. Ditto (2004.00584) — 事前学習LM(BERT)で EM、SOTA比 +29% F1
2. 既存参照 (2512.07232) — Rough→Fine 2段階
3. LLM属性抽出 (2403.00863 / 2403.02130, F1 91%) — タイトルから属性抽出・正規化
4. 視覚ゼロショット属性抽出 (2502.15979) — 画像から属性
5. WDC product matching benchmark

**GitHub:**
6. megagonlabs/ditto — 事前学習LMベース EM 実装
7. dedupeio/dedupe — スケーラブルなファジー一致・record dedup
8. anhaidgroup/deepmatcher — 10行で深層 EM
9. Jinal17/Ecommerce-Product-Matching — DNN 商品名寄せ
10. product-matching GitHub topic 群

**改善点:**
- Fine 段階に **属性（ブランド/型番/容量/色）の重み付き一致**を追加（dedupe/deepmatcher の素性設計を参考、オンデバイス辞書ベースで近似）。
- LM ベース（Ditto）は重量級ゆえ送信不可だが、**ブロッキング→軽量スコアリング**の2段構成は踏襲済み。ブロッキング鍵を JAN だけでなく**正規化型番**にも拡張し再現率向上。
- 属性抽出は `BuyingAdvisor` の送信（タイトルのみ）経路で**任意опト的に**精緻化可能。

## カテゴリ4: レビュー信頼性・偽レビュー検出 — `ReviewTrustScorer.kt`（現状: 評価値＋件数ヒューリスティック）

**arXiv:**
1. FraudSquad (2510.01801) — LM埋め込み＋GNN で spam レビュー検出
2. LLM偽レビューは人間/機械とも判別困難 (2506.13313)
3. Ott et al. Deceptive Opinion Spam（gold-standard 1600件）

**GitHub:**
4. archchitha/Opinion-Spam-Detection — Ott corpus 利用
5. sghosh1991/Fake_Review_Detection — Yelp、SVM/NaiveBayes/LOF
6. kavya76/Spam-Reviews-Detector — DL アプローチ
7. PauDK/Deceptive-Review-Detection — Ott gold-standard
8. anubhavs11/Fake-Product-Review-Monitoring — Amazon/Flipkart
9. SayamAlt/Fake-Reviews-Detection — AI生成判定
10. ashishsalunkhe/DeepSpamReview — 深層アーキ比較

**改善点:**
- rating＋count に加え**分布シグナル**（星5偏重の歪度、レビューバースト＝投稿速度、LOF 的外れ値）をオンデバイスで追加。
- AI生成レビューは判別困難 (2506.13313) → 「サクラ度」は確率断定でなく**注意喚起**として提示（過信回避）。
- 取得可能なら Verified-Purchase 比率・新規アカウント比率を加味。

## カテゴリ5: ダークパターン検出 — `DarkPatternDetector.kt`（現状: 5類型ルールベース）

**arXiv:**
1. EC darkpattern dataset (2211.06543) — RoBERTa 0.975
2. Dark Patterns at Scale (Mathur, CSCW2019) — 11K サイトの taxonomy
3. AidUI (ICSE'23) — UIダークパターンの統一 taxonomy＋検出
4. YOLO 視覚検出データセット (2512.18269)
5. LLM 検出 (2406.01608)

**GitHub:**
6. **yamanalab/ec-darkpattern**（日本発, IEEE BigData 2022）— BERT/RoBERTa baseline＋データセット
7. SageSELab/AidUI — UIダークパターン taxonomy＋検出コード
8. aruneshmathur/dark-patterns — 11K crawl、商品ページ分類器
9. Venkateeshh/DarkSurfer-Extension — LLM 拡張
10. XRegiGigaSX/EthiGuard — BERT 分類器＋拡張

**改善点:**
- 現状5類型を **Mathur/AidUI の統一 taxonomy**（Sneaking/Urgency/Misdirection/Social Proof/Scarcity/Obstruction/Forced Action）にマッピングし網羅性を点検。
- **yamanalab/ec-darkpattern（日本語データ）**で語彙ヒューリスティックを拡充（オンデバイス・送信なし方針を維持）。
- 偽の緊急性（カウントダウン）・偽の希少性（残りN点）・confirmshaming を text ルールで追加。

## カテゴリ6: ポイント/報酬・キャッシュバック最適化 — `PointSimulator.kt`（現状: 楽天/Yahoo/Amazon ルール）

**arXiv:**
1. 公平な points 報酬設計 (2506.03911) — devaluation-free 学習
2. 個別化プロモーションの動的配分 (2512.23781)

**GitHub:**
3. starkarthikr/credit-card-optimizer — カテゴリ別最適ポートフォリオ、月上限/年会費考慮
4. tianhaoz95/iwfp — 5%還元最大化（Android/iOS/web）
5. kampofo6/Point-Pilot — 還元追跡＋**取りこぼし検出**＋実質価値
6. aashishvanand/ccreward-web — MCC 別最適カード
7. riddhibajaj/CardGenie — 購入ごと最適カード推薦
8. credit-card-points / reward-points GitHub topics 群

**改善点:**
- 現状はモール側ポイントのみ → **クレカ/MCC 別還元**を加味し「どの支払い手段が最安実質か」を提示（ccreward/CardGenie 流）。
- **取りこぼし検出**（Point-Pilot）: 「5と0のつく日に買えば+X円」等の機会損失アラート。
- 月間上限(cap)・年会費を考慮した実質還元計算（credit-card-optimizer）。

## 出典（カテゴリ4-6）
- GitHub: https://github.com/archchitha/Opinion-Spam-Detection ／ https://github.com/sghosh1991/Fake_Review_Detection ／ https://github.com/kavya76/Spam-Reviews-Detector ／ https://github.com/anubhavs11/Fake-Product-Review-Monitoring ／ https://github.com/yamanalab/ec-darkpattern ／ https://github.com/SageSELab/AidUI ／ https://github.com/aruneshmathur/dark-patterns ／ https://github.com/Venkateeshh/DarkSurfer-Extension ／ https://github.com/starkarthikr/credit-card-optimizer ／ https://github.com/tianhaoz95/iwfp ／ https://github.com/kampofo6/Point-Pilot ／ https://github.com/aashishvanand/ccreward-web ／ https://github.com/riddhibajaj/CardGenie
- arXiv: https://arxiv.org/abs/2510.01801 ／ https://arxiv.org/html/2506.13313v1 ／ https://arxiv.org/abs/2211.06543 ／ https://arxiv.org/html/2512.18269v1 ／ https://arxiv.org/abs/2406.01608 ／ https://arxiv.org/pdf/2506.03911 ／ https://arxiv.org/html/2512.23781

## カテゴリ7: 越境EC・関税・為替 — `CustomsSimulator.kt`（現状: 簡易関税率＋16,666円閾値）

**arXiv/参考:**
1. HSコード分類は LLM 属性抽出 (2403.00863) と接続可能（品目→税率マッピング）
2. landed cost の概念（送料＋関税＋消費税＋手数料の総額）

**GitHub/ツール:**
3. AccioWork/import-duty-calculator — HTS データ、Section 301、landed cost、MPF/HMF
4. avadev/Avalara-AvaTax — クロスボーダー duty/import tax をチェックアウトで一括
5. Flexport Tariff Simulator ／ SimplyDuty ／ Freightos ／ tariffdutycalculator

**改善点:**
- 現状の国×簡易率を **HSコード分類**で品目別税率に精緻化（HS分類は属性抽出 2403.00863 と接続、オンデバイス辞書で近似）。
- **landed cost の一括提示**（送料＋関税＋消費税＋手数料、AccioWork/Avalara 流）。
- **多通貨・FXレート対応**（現状JPY前提）で越境購入の実質比較。
- 日本の少額免税（課税価格1万円以下）・個人輸入の課税価格60%ルールを明示。

## カテゴリ8: バーコード/JAN・商品認識 — `feature/barcode/*`（現状: JANスキャン＋`JanCodeQuery`）

**arXiv:**
1. barcodeless product classifier（self-checkout、The Visual Computer）
2. 2段階 Faster-RCNN＋ResNet-18 軽量パイプライン（edge 向け）
3. MGL-YOLO 軽量バーコード検出 (PMC11644706)
4. ラック商品認識 (2202.13081)

**GitHub:**
5. EventideSystems/brocade.io — オープン GTIN/商品DB API（認証不要 read）
6. erikraft/EAN — バーコード生成/検索/スキャン
7. evscott/barcodelookup — Node の EAN/ISBN ルックアップ
8. eansearch/UPCBarcodeLookup — Swift パッケージ
9. upcdatabase.org / barcodelookup.com API

**改善点:**
- JAN 無し/破損時の **画像ベース商品認識**（軽量 ResNet-18/YOLO、オンデバイス）をフォールバックに。
- **オープン GTIN DB（brocade.io）**を `JanCodeQuery` の商品名解決フォールバックに追加。
- MGL-YOLO 流の軽量検出でスキャン成功率/速度向上（暗所・歪み対応）。

## カテゴリ9: 通知・リテンション — `PriceSyncWorker` / `LocalNotificationManager` / `feature/retention/ReviewPrompter`

**arXiv:**
1. "Should I send this notification?" (2202.08812) — RL で通知数減＋開封率増
2. TIM (2406.07067) — 時間帯別 CTR で送信時刻制御

**GitHub:**
3. HelenGuohx/price-alert — 目標価格通知
4. BexTuychiev/automated-price-tracking — **Discord 通知**
5. duyet/pricetrack — Firebase、複数ECチャネル
6. App Store discounts tracker — **RSS/Telegram/DingTalk** 多チャネル通知

**改善点:**
- 固定ルール（`MAX_NOTIFICATIONS=3` / `MIN_DROP_PERCENT=3%`）→ **送信判断・送信時刻のオンデバイス最適化**（2202.08812/TIM）。
- **通知チャネル多様化**（競合は Discord/Telegram/メール/RSS）。現状はローカル通知＋widget のみ。
- `ReviewPrompter`: レビュー依頼を**肯定的瞬間**（買い時的中後など）に出す習慣形成タイミング最適化。

## カテゴリ10: プライバシー・オンデバイスML・セキュリティ — `BackendClient` / `PrivacyCrashReporter` / `AwsSigV4Signer`

**arXiv:**
1. Aero (2312.10789) — 非信頼サーバ前提の DP＋低オーバーヘッド連合学習
2. DP機構評価 (2510.09691)、グラフ連合推薦 (2508.06208)

**GitHub:**
3. tensorflow/privacy — DP-SGD による学習
4. TFLite on-device model personalization — 転送学習で端末内パーソナライズ
5. 量子化/プルーニングで軽量化（TFLite topics）

**改善点:**
- 価格共有プールへの**差分プライバシーノイズ付与**（送信前、tensorflow/privacy の DP-SGD 思想）。
- 買い時の**個人化を TFLite＋転送学習**でオンデバイス完結（データ送信なし、I5 方針に合致）。
- セキュリティ: `AwsSigV4Signer` の署名検証テスト拡充、証明書ピン留めの段階導入（既出バックログ）。

## 出典（カテゴリ7-10）
- GitHub: https://github.com/AccioWork/import-duty-calculator ／ https://github.com/avadev/Avalara-AvaTax-for-Magento2 ／ https://github.com/EventideSystems/brocade.io ／ https://github.com/erikraft/EAN ／ https://github.com/evscott/barcodelookup ／ https://github.com/eansearch/UPCBarcodeLookup ／ https://github.com/HelenGuohx/price-alert ／ https://github.com/BexTuychiev/automated-price-tracking ／ https://github.com/duyet/pricetrack ／ https://github.com/tensorflow/privacy
- arXiv/参考: https://arxiv.org/abs/2403.00863 ／ https://ar5iv.labs.arxiv.org/html/2202.13081 ／ https://pmc.ncbi.nlm.nih.gov/articles/PMC11644706/ ／ https://arxiv.org/abs/2202.08812 ／ https://arxiv.org/abs/2406.07067 ／ https://arxiv.org/abs/2312.10789 ／ https://arxiv.org/abs/2510.09691 ／ https://arxiv.org/html/2508.06208v1

---

## まとめ（全10カテゴリ完了）
各カテゴリで arXiv 論文・GitHub OSS を ~10件収集し、Popcoon の該当コードに紐づく改善点を洗い出した。
横断的に最も効果が高いのは **(A) 軽量線形/分解＋Conformal 区間による価格予測強化（cat1）**、
**(B) 曜日/日付の買い時シグナル（cat2）**、**(C) 統一 taxonomy でのダークパターン網羅（cat5）**、
**(D) オンデバイス DP/個人化（cat10）** — いずれも Popcoon のゼロ依存・オンデバイス・
プライバシー方針と整合し、Python TDD で先行検証可能。詳細・優先度は `IMPROVEMENTS.md` /
`RESEARCH_IMPROVEMENTS.md` のバックログと統合済み。

---

## 出典（カテゴリ1-3）
- GitHub: https://github.com/product-price-tracker/product-price-tracker ／ https://github.com/topics/price-tracker ／ https://github.com/HelenGuohx/price-alert ／ https://github.com/BexTuychiev/automated-price-tracking ／ https://github.com/duyet/pricetrack ／ https://github.com/AliAlboushama/Steam-Game-Price-Alert ／ https://github.com/megagonlabs/ditto ／ https://github.com/dedupeio/dedupe ／ https://github.com/anhaidgroup/deepmatcher ／ https://github.com/Jinal17/Ecommerce-Product-Matching
- arXiv: https://arxiv.org/abs/2403.14587 ／ https://arxiv.org/html/2509.15105v1 ／ https://arxiv.org/pdf/2601.09237 ／ https://arxiv.org/html/2506.06005v1 ／ https://arxiv.org/abs/2411.05793 ／ https://arxiv.org/abs/2505.08158 ／ https://arxiv.org/abs/2105.08313 ／ https://arxiv.org/abs/2012.08777 ／ https://arxiv.org/abs/2506.17543 ／ https://arxiv.org/pdf/2004.00584 ／ https://arxiv.org/abs/2403.00863 ／ https://arxiv.org/abs/2403.02130 ／ https://arxiv.org/html/2502.15979v1
