# 仕様書: Python 参照プロトタイプの Kotlin 移植（Porting Spec）

本セッションで検証した5つの Python 参照プロトタイプ（計49テスト）を、実アプリ（Kotlin）へ
移植するための仕様。**EcoEthics の教訓**（docstring が「一致」と偽り実装が乖離した事例）を踏まえ、
各移植には **Python 出力と完全一致するパリティテスト**（下記ゴールデンベクタ）の併設を必須とする。

共通方針:
- 純関数・ゼロ依存・決定的・オンデバイス（送信なし）を維持。
- 丸めは Python `round()` が**銀行丸め（round-half-to-even）**である点に注意。Kotlin で再現するか、
  ゴールデン値が半数境界を含まないことを確認（下記は全て非境界値）。
- ゴールデンベクタは `popcoon-tdd` の各 proto を実行して取得済み（再生成可能）。

---

## 1. A5 曜日の買い時シグナル → `feature/scorer/BuyTimingScorer.kt`
- 参照: `popcoon-tdd/proto_seasonal_signal.py::seasonal_buy_signal`
- **状態: 移植済み（加算的）** — `feature/scorer/SeasonalDowSignal.kt` ＋ `SeasonalDowSignalTest.kt`
  （ゴールデンベクタ）。`BuyTimingScorer` への加算配線は後続。丸めは `kotlin.math.round`（half-to-even）で Python 一致。
- Kotlin: `private fun seasonalDowSignal(history: List<PriceRecord>, today: LocalDate): Int`（±10）を
  追加し、既存スコアに加算。history から `(dow, price)` を導出（`recordedAt.atZone(Asia/Tokyo).dayOfWeek`,
  月=0..日=6）。`overall<=0` や履歴<14 やサンプル<2 は 0。
- **パリティ（履歴=4週 [1000×5,800×2] を (i%7, 値) で）**:
  | today_dow | 期待シグナル |
  |---|---|
  | 5 (土) | **10** |
  | 0 (月) | **-6** |
  | 3 (木) | **-6** |

## 2. A6 Conformal 予測区間 → `feature/prediction/PricePredictionEngine.kt`
- 参照: `popcoon-tdd/proto_conformal_interval.py::conformal_margin`
- **状態: 移植済み（加算的）** — `feature/prediction/ConformalInterval.kt`（`conformalMargin`/`predictInterval`）
  ＋ `ConformalIntervalTest.kt`（ゴールデンベクタ）。既存 `predictionMargin` への配線は後続。
- Kotlin: `predictionMargin` を RMSE から split-conformal へ置換。
  `fun conformalMargin(residuals: List<Double>, alpha: Double = 0.1): Double`
  = 絶対残差ソート, `k = ceil((n+1)*(1-alpha))`, `k>n→max` 否則 `sorted[k-1]`。
- **パリティ（residuals=[-30,-20,-10,-5,-2,0,2,5,10,20,30]）**:
  | alpha | margin |
  |---|---|
  | 0.1 | **30** |
  | 0.2 | **30** |
  | 0.3 | **20** |

## 3. A1 季節分解予測 → `feature/prediction/PricePredictionEngine.kt`（または新 `SeasonalForecaster`）
- 参照: `popcoon-tdd/proto_seasonal_decomp_forecast.py::seasonal_decompose_forecast`
- **状態: 移植済み（加算的）** — `feature/prediction/SeasonalDecompForecast.kt` ＋
  `SeasonalDecompForecastTest.kt`（ゴールデンベクタ）。PricePredictionEngine への統合は後続。
- Kotlin: 中心移動平均(窓=period)で季節成分を分離 → 季節除去系列に最小二乗線形 → `a*t+b+seasonal[t%period]`。
  履歴 < max(2*period,4) は直近値フラット、period<=1 は純線形。
- **パリティ（履歴=4週 [1000×5,800×2], horizon=7, period=7）**:
  `[1000, 1000, 1000, 1000, 1000, 800, 800]`（許容 1e-6）。

## 4. 横断カート最適化 → 新規 `feature/cart/CrossMallCartOptimizer.kt`
- 参照: `popcoon-tdd/proto_cross_mall_cart.py::optimize_basket` / `basket_savings`
- 仕様: `UNIVERSAL_CART_SPEC.md`。実質単価は `PointSimulator`、同一商品束ねは `ProductMatcher`。
  全探索（`brute_cap` 以下）で厳密最適、超で貪欲。タイブレーク=配送回数最小→決定的。
- **状態: 移植済み** — `feature/cart/CrossMallCartOptimizer.kt` ＋ `CrossMallCartOptimizerTest.kt`
  （ゴールデンベクタ）。UX 統合（カート画面、PointSimulator / ProductMatcher との配線）は後続。
- **パリティ**: items a{amazon:1000, rakuten:900}, b{amazon:1000, rakuten:1300}、
  malls amazon{ship800, free2000}, rakuten{ship800, free5000}:
  → assignment `{0:amazon, 1:amazon}`, total **2000.0**, num_malls **1**, shipping **0**, coupon **0**。
  （単品最安は両方 rakuten 寄りだが、amazon 集約で送料無料ラインに到達して総額最小）

## 5. ダークパターン UIテキスト検出 → `feature/darkpattern/DarkPatternTextDetector.kt`
- 参照: `popcoon-tdd/proto_darkpattern_signals.py::detect_dark_patterns`
- 仕様: `DARKPATTERN_EXPOSE_SPEC.md`。価格系（既存 DarkPatternDetector）とは独立した新クラスで
  テキスト系（URGENCY/SCARCITY/SOCIAL_PROOF/MISDIRECTION/FORCED_ACTION）を実装。
  出力は category 昇順・各カテゴリ最大1件。
- **状態: 移植済み** — `feature/darkpattern/DarkPatternTextDetector.kt` ＋
  `DarkPatternTextDetectorTest.kt`（ゴールデンベクタ）。
  既存 DarkPatternDetector への合流・a11y ラベル追加は後続。
- **パリティ**（入力 `"本日限り！残り3点。8人がカートに入れました"`）:
  ```
  [ {SCARCITY, "残り3点", HIGH}, {SOCIAL_PROOF, "8人がカートに入れました", MEDIUM}, {URGENCY, "本日限り", MEDIUM} ]
  ```

---

## 受け入れ条件（各移植）
1. 上記ゴールデンベクタに一致する Kotlin パリティテストを併設（kotest）。
2. Python 参照は変更しない（canonical-oracle を保持）。仕様変更時は両側を同時更新しゴールデン再生成。
3. プロジェクト CI（`testDebugUnitTest`）で green。丸め・タイムゾーン（JST）・浮動小数の差異に注意。

## 移植の優先度
A6（区間の被覆保証）→ A5（曜日シグナル）→ A1（季節予測）→ カート最適化 → ダークパターン。
いずれも既存クラスへの加算的変更で、リスクが小さく効果が見えやすい順。
