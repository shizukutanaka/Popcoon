# Popcoon 現状評価 — 長所 / 短所 / 改善案 (2026-07)

商用品質監査 (30 タスク) + リサーチ実装完了・`main` 公開時点の棚卸し。
数値は全て実測 (推測値なし)。改善案テーブルは将来の Claude (Opus / Sonnet) セッションが
そのままタスクとして拾える粒度で書く。**着手前に必ずリポジトリ直下の `CLAUDE.md` を読むこと。**

## 長所

1. **二重言語 TDD アーキテクチャ** — Python 仕様オラクル (445 tests) が真実の源、Kotlin 本番実装を
   `kotlin_parity/` の 14 ハーネスが**実コンパイル・実行**で照合 (run.sh 109 ケース 0 乖離)。
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
6. **i18n 規律** — 4 ロケール × 398 キー完全一致 + kind/label 分離パターンでオラクル結合と
   ローカライズを両立。TalkBack 対応 (チャート要約読み上げ・mergeDescendants) も監査済み
7. **backend の実ランタイムテスト** — vitest-pool-workers で本物の `src/index.ts` を miniflare 上で
   実行 (70 tests)。レート制限はネイティブ binding + KV フォールバックの漸進移行、
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
   実行検証済みだが、UI 層の変更は構文チェック止まり。CI 有効化までは残存リスク
3. **Amazon データソースが実質 FallbackScraper のみ** — PA-API 5.0 が 2026-05-15 廃止。
   後継 Creators API は OAuth2 資格情報 + 成果実績 (10 件/30 日) が必要で人手ゲート
4. **FCM push 経路がデッドコード** — backend 側は実装済みだが Android に Firebase 未組込
   (google-services.json 無し)。実通知は端末ローカルのみ。組み込むかは製品判断
5. **価格履歴 KV の lost-update** — read→merge→put の後勝ち。per-product Durable Objects への
   移行設計は `backend/README.md` に文書化済みだが、wrangler 実行検証不可のため未実装
6. **UI 自動テストが薄い** — Compose UI テスト 2 件 + androidTest 4 ファイルは本環境で実行不可。
   ユニットテスト 63 ファイルはロジック層に偏る (構造上やむを得ないが偏りは事実)
7. **Yahoo 会員ランク未モデル化** — 感謝デー (+4〜5%) はランク条件つきで、UserContext に
   ランク次元が無いためシミュレーション対象外 (注記のみ)。設定 UI + スキーマ設計が必要
8. **名寄せの残課題** — groupByIdentity は JAN なし商品で O(m²)。IDF-lite トークン重み付けは
   corpus を渡す API 変更が必要で見送り中。Model2Vec 等の埋め込みは効果不確実で見送り
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
| B1 | 予測アンサンブル | 高 | predicted7d を Holt/damped-trend ETS/seasonal-naive の中央値に。**golden vector とBuyTimingScorer の数値が変わる製品挙動変更** — oracle 先行 + 全 golden の手計算再導出必須 | pytest + run.sh parity + golden 根拠をコミットに明記 |
| B2 | Durable Objects 移行 | 高 | `backend/README.md` の設計どおり実装。**wrangler dev / デプロイ検証が可能な環境が前提** | `npx wrangler dev` + vitest + 段階ロールアウト |
| B3 | IDF-lite トークン重み | 中 | groupByIdentity に corpus 文脈を渡す API 変更込み。頻出トークン (ブランド名等) の寄与を減衰 | 新 oracle + run_matcher.sh + 既存 27+ ケース無回帰 |
| B4 | Yahoo ランク次元 | 中 | UserPreferences + 設定 UI + PointSimulator.UserContext にランク追加、感謝デーを実計算へ。4 ロケール文字列同時 | run_points.sh + oracle + キー数一致 |
| B5 | groupByIdentity の粗ブロッキング | 中 | O(m²) を先頭トークン/型番プレフィクスでバケット化して削減。順序安定性を壊さないこと | run_matcher.sh + 大規模入力の perf 確認 |

### C. Sonnet 向け (機械的・検証容易 — CLAUDE.md の oracle 先行 TDD でそのまま着手可)

| # | 項目 | 内容 | 検証 |
|---|---|---|---|
| C1 | ダークパターン regex 追加 | 消費者庁 32 類型のうち未対応の文言パターンを 1 カテゴリずつ。誤爆ガードの負ケース必須 | proto+test → Kotlin → ParityHarness (現 109 ケース) |
| C2 | 属性 recall 追加 | 色 (現: カタカナ 27 色名 → 正準 16 色)・助数詞・単位の追加。「最寄り正準色へ保守的写像」原則を維持 | run_matcher.sh |
| C3 | parity ケース増強 | 既存ハーネスに境界ケース追加 (全角/半角・空文字・巨大値) | run_all.sh 全 green 維持 |
| C4 | backend テスト追加 | 未カバー経路 (異常系ヘッダ・巨大 payload 境界値・cron 評価等) を worker.test.ts に | vitest 70+ / tsc |
| C5 | ドキュメント保守 | 実装変更時の README/ARCHITECTURE/RESEARCH ログ同期。数値は必ず実測 | 記載コマンドを実行して一致確認 |
| C6 | ViewModel テスト追加 | 未カバーの ViewModel に FakeDao/IUserPreferences パターンでテスト | brace バランス + 既存パターン照合 |

## 検証基準線 (2026-07 実測)

- Python: **445 passed / 1 skipped** (`popcoon-tdd/`)
- Kotlin parity: **run_all.sh 全 14 ハーネス pass** (run.sh 109 matched / 0 mismatched)
- backend: **tsc 0 errors / vitest 70 tests pass**
- i18n: **4 ロケール × 398 strings** (+3 plurals) 完全一致
- ファイル数: Kotlin main 130 / unit test 63 / androidTest 4、Python 38

この基準線を下回る変更は原因を特定するまで push しない。
