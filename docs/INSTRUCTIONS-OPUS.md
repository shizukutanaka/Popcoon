# INSTRUCTIONS-OPUS.md — 設計判断タスクの手順書 (B1–B5)

対象: Claude Opus クラスのセッション。設計判断・横断変更・golden 移行を伴うタスク集。
**共通ルール: 着手前に `CLAUDE.md` の検証コマンドで基準線 (oracle 490 / parity 14 ハーネス /
backend 70 / 4×405 キー) を確認 → 実装計画を提示しユーザー承認を得てから実装 → 完了後に同じ
基準線+新規テストで検証。** 番号は `docs/ASSESSMENT-2026-07.md` の改善案テーブルに対応。

---

## B1. 予測アンサンブル (predicted7d の中央値化)

**目的**: `predicted7d` を Holt 単独から Holt / damped-trend ETS / seasonal-naive の
3 モデル中央値へ。30 点未満の短い系列で外れ値的トレンドに引きずられるのを抑える。

**これは製品挙動変更**: `predicted7d`/`predicted30d` の数値が変わり、
`BuyTimingScorer.kt:169` の `futureRatio` (predicted30d 依存、±1%/±5% 閾値で Signal 分岐)
を通じて買い時スコアまで波及する。**golden/差分テストの期待値書換えが必然的に発生する** —
CLAUDE.md 禁止事項のとおり、新期待値は必ず数式から手計算で導出し根拠をコミットに書く。

**触るファイル**:
- `popcoon-tdd/popcoon_core.py::predict_price` (99 行目〜) — オラクル先行
- `popcoon-tdd/test_popcoon_core.py` / `test_differential.py` / golden 系 — 期待値再導出
- `app/src/main/.../feature/prediction/PricePredictionEngine.kt` — Kotlin 移植
- `app/src/test/.../prediction/PricePredictionEngineTest.kt` — golden 更新
- `popcoon-tdd/kotlin_parity/ParityHarness.kt` (run.sh 経由) — 予測ケースの照合値更新

**手順**:
1. damped-trend ETS (φ=0.9 前後) と seasonal-naive (7 日周期) を `proto_` として oracle に追加、
   単体テストで各モデル単独の値を固定
2. `predict_price` に 3 モデル中央値を実装 — **既存の Holt 出力も Prediction に残す**
   (フィールド追加は後方互換) か置換 (golden 全滅) かを計画段階で提示し承認を取る
3. 影響を受ける全 golden の新値を手計算で導出 (導出過程をコミットメッセージへ)
4. Kotlin 移植 → PricePredictionEngineTest / parity 更新 → run_all.sh 全 green

**リスク**: BuyTimingScorer の Signal 分岐が変わる商品が出る。サンプル系列 2〜3 本で
before/after の Signal 差分を計画書に添えること。

---

## B2. 価格履歴の Durable Objects 移行

**前提 (必須)**: `wrangler dev` / デプロイ検証ができる環境。従来環境では wrangler ランタイム
実行不可のため**この前提を満たさないなら着手しない** (設計文書化まで完了済み)。

**設計は `backend/README.md`「価格履歴の lost-update 対策」節に 4 手順で文書化済み**。実装手順:
1. `PriceHistoryDO` クラス (SQLite backend) を `backend/src/` に追加、
   `idFromName(product_key)` で商品毎単一インスタンス化 (DO 内逐次実行がレースを構造的に排除)
2. `wrangler.toml` に `[[migrations]]` + `new_sqlite_classes` を宣言 (デプロイ設定と密結合 —
   ここが検証必須ポイント)
3. `POST /v1/history` を DO への fetch() 転送に置換、GET は「DO に無ければ KV」二段フォールバック
4. vitest: 既存 65 tests を green に保ちつつ DO 経路のテスト追加 (制限は
   `backend/README.md`「テスト構成」を先に読む)

**無料枠**: DO 100k req/日・SQLite 合計 5GB。書き込みレート制限 (5/分/IP) 下で十分。

---

## B3. IDF-lite トークン重み付け (ProductMatcher)

**目的**: 候補集合内で頻出するトークン (ブランド名・カテゴリ語) の Jaccard 寄与を減衰し、
「共有マーケティング語だけで閾値超え」の誤マッチをさらに抑える。

**API 設計判断が必要**: `titleSimilarity` (ProductMatcher.kt:204) は現在ペア単独で計算する。
IDF は corpus (検索結果セット) 文脈が要る。案: `groupByIdentity(products)` (同 114 行目) が
グループ化前に token→document frequency を 1 パス集計し、重み付き Jaccard を**グループ化にのみ**
適用 (ペア API `similarity`/`isMatch` は不変に保つ)。この設計を先に提示・承認。

**手順**: oracle (`proto_title_similarity.py` に weighted 版を追加) → Kotlin →
`kotlin_parity/matching/ProductMatcherCheck.kt` にケース追加 → `run_matcher.sh`。
**既存 40+ ケース (型番/属性/bigram/色/内容量) の無回帰が合格条件**。

---

## B4. Yahoo 会員ランク次元の追加

**目的**: 感謝デー (11/22 日、シルバー+4%/ゴールド+5%) を注記止まりから実計算へ。

**変更チェーン (この順で)**:
1. `feature/settings/UserPreferences.kt` + `IUserPreferences.kt` にランク設定 (enum ordinal) を
   追加 — `watchlistSortOrdinal` の実装パターンを踏襲
2. `SettingsViewModel.kt` / `SettingsScreen.kt` に設定 UI (既存 EC 会員設定ブロックに追従)
3. `feature/points/PointSimulator.kt::UserContext` にランク追加 → 感謝デー計算を実装
   (oracle 先行 → `run_points.sh` 照合)
4. 文字列は 4 ロケール同時追加 → キー数一致検証 (現 405)
5. **`SearchViewModelTest.kt` / `WatchlistViewModelTest.kt` の FakeUserPreferences に
   新フィールド実装を追加** (インタフェース拡張はテストの Fake を必ず壊す — 忘れがち)

---

## B5. groupByIdentity の粗ブロッキング

**目的**: JAN なし商品の O(m²) 照合を、型番プレフィクス/先頭トークンでバケット化して削減。

**合格条件**: (1) 結果のグループ構成が現行と完全一致 (ブロッキングは候補削減のみで判定は
変えない)、(2) グループ内・グループ間の順序安定性維持 (`sortedBy { it.totalPrice }` と挿入順)。
`run_matcher.sh` + 数百件の合成入力で before/after のグループ構成 diff ゼロを示す。
