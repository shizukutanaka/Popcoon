# CLAUDE.md — Popcoon エージェント運用指示書

Claude (Opus / Sonnet) が本リポジトリで作業する際の必読事項。**推測で書かれた数値は無い —
全て実測。作業開始時にまずここの検証コマンドを流し、基準線が崩れていないか確認せよ。**

## プロジェクト概要

Amazon / 楽天 / Yahoo!ショッピング横断の日本市場向け価格比較 Android アプリ。
差別化機能: ダークパターン検出・買い時スコア・TCO 計算・ポイント還元シミュレーション・
クロスモール名寄せ/カート最適化・価格アラート (1 サイクル遅延確認)。
プライバシー第一 (テレメトリゼロ、オンデバイス推論のみ、opt-in クラッシュレポートは PII 除去済み)。

```
app/          Android アプリ (Kotlin 131 ファイル / Compose / Hilt / Room)
  src/test/       plain JVM ユニットテスト (kotest, 64 ファイル)
  src/androidTest instrumentation テスト (4 ファイル — 本環境では実行不可)
popcoon-tdd/  Python 仕様オラクル (38 ファイル) + kotlin_parity/ 実行照合ハーネス
backend/      Cloudflare Workers (TypeScript, vitest-pool-workers で実ランタイムテスト)
docs/         RESEARCH-2026-07.md (実装済/見送り judgments), ASSESSMENT-2026-07.md (長所短所改善案)
ci/           android.yml (未稼働 — workflows 権限が無く人手で ci/enable.sh が必要)
```

## 検証コマンド (変更の種類ごとに必ず実行)

**まず `python3 ci/verify.py` を叩け。** 下表の全ゲートを実行し、**この表に書かれた基準線を
自分でパースして実測値と突き合わせ**、1 つでもズレていれば exit 1 する。意図的に数値を
変えた場合は `python3 ci/verify.py --update` で表を実測値へ同期する (手で書き換えるな)。
不足依存 (pytest 系 / backend の node_modules) は検出して自動導入し、何を入れたか表示する。
`--skip-backend` で npm 不可の環境にも対応。個別コマンドは下表のとおりで、デバッグ時に使う。

| 対象 | コマンド | 基準線 (2026-07 時点) |
|---|---|---|
| Python オラクル全体 | `cd popcoon-tdd && python3 -m pytest -q` | **500 passed, 1 skipped** |
| Kotlin 実行 parity 全体 | `bash popcoon-tdd/kotlin_parity/run_all.sh` | 全 14 ハーネス pass (`run.sh` は 164 matched / 0 mismatched、`run_compile_core.sh` は 47 ファイル実コンパイル) |
| 個別 parity | `bash popcoon-tdd/kotlin_parity/run_matcher.sh` 等 | "all assertions passed" |
| backend 型検査 | `cd backend && npx tsc --noEmit` | エラー 0 |
| backend テスト | `cd backend && npx vitest run` | **84 tests / 4 files pass** |
| i18n キー数一致 | `for f in values values-en values-ko values-zh-rCN; do grep -c '<string name=' app/src/main/res/$f/strings.xml; done` | **全ロケール 365** (plurals 3 は別) |
| Kotlin 構文 (ビルド不可の代替) | brace/paren カウント一致を Python ワンライナーで確認 | `{`=`}`, `(`=`)` |

## 環境制約 (重要 — 回避不能)

- **Android 実ビルド不可**: SDK が無く `./gradlew` はネットワーク制限で使えない。Compose/Room/Hilt/ktor 依存コード (85 ファイル) はコンパイル検証できない → UI 層の変更は brace バランス + 既存パターン踏襲 + コードレビュー精度で守る。**純 Kotlin ロジックは検証できる**: `/opt/gradle-*/lib/kotlin-compiler-embeddable-*.jar` で実コンパイル・実行 (parity ハーネスがこの方式。throwaway 検証は run_points.sh の invocation を流用)
- **依存 jar が手元にある 47 ファイルは `run_compile_core.sh` が一括実コンパイル**する (when 網羅漏れ・未解決参照・`R.string.*` 未定義・型不一致を検出)。同スクリプトは `check_overrides.py` / `check_resources.py` / `check_when_exhaustive.py` も呼び、**コンパイル不能な 85 ファイルも含む全ソース**で (a) インタフェース実装の `override` 欠落、(b) `R.*` 参照 (string/drawable/color/plurals/xml/style/mipmap/id) の実在、(c) enum に対する `when` の網羅漏れ、(d) テストが参照する本番シンボルの実在 (kotest はコンパイル不能) を静的検査する。**UI 層を触ったら必ず実行すること** — 2026-08 に `UserPreferences` の override 欠落で app が約 1 か月コンパイル不能だった実績がある
- **wrangler ランタイム実行不可**: KV/DO/ratelimit binding の実挙動は検証できない。vitest (miniflare) の既知の制限は `backend/README.md` の「テスト構成」参照
- git push は指定作業ブランチのみ許可 (タグ・他ブランチは 403)。`main` を動かすのは明示指示がある時だけ

## 確立済みパターン (逸脱するな)

1. **oracle 先行 TDD**: 純ロジックの変更は (a) `popcoon-tdd/proto_*.py` + `test_proto_*.py` を先に書き pytest green → (b) Kotlin へ移植 (正規表現・定数・演算順序まで厳密一致) → (c) `kotlin_parity/` にケース追加し実 Kotlin 実行で照合。例: `proto_title_similarity.py` ↔ `ProductMatcher.titleSimilarity()`
2. **i18n の kind/label 分離**: `Signal.name` や `Warning.label` 等の日本語固定文字列はオラクル/ゴールデンテストが厳密比較する内部識別子 — **変更禁止**。UI 表示は enum kind + `ui/*Labels.kt` の `toLabelResource()` で `R.string` に変換。文字列追加は 4 ロケール同時 + キー数一致検証
3. **コルーチン例外**: catch 節では必ず `CancellationException` を先に再 throw。ログは `PopcoonLogger` (PII サニタイズ・リリース時無効) — `android.util.Log` 直接使用禁止
4. **有界並行**: 無制限 fan-out 禁止。`Semaphore(8).withPermit { }` (PriceSyncWorker / SearchViewModel / BackendClient が実例)
5. **属性ペナルティの保守方針** (ProductMatcher): 「両タイトルから一意に取れて、かつ食い違う」場合のみ減点。片方不明・曖昧 (複数値) は中立 null。誤爆源を必ず考える (例: 素の g は小文字限定 — 「5G」対策)
6. **ViewModel テスト可能性**: Context / 具象 DataStore を直接注入しない。`IUserPreferences` / `IWidgetRefresher` のようにインタフェース + Hilt `@Binds` で切る

## 禁止事項

- ゴールデンベクタ / 差分テストの期待値を「テストを通すため」に書き換える (数式から手計算で導出し、根拠をコミットメッセージに書くこと)
- CI が稼働しているかのような記載 (未稼働。歴史は README「CI について」)
- 配線されない機能・デッドコードの追加 (過去に EcoEthicsScorer で revert 実績あり)
- 楽天 SPU 等の外部仕様値を出典なしに変更 (docs/RESEARCH-2026-07.md に出典付きで記録)

## git 運用

- 項目単位でコミットし都度 push (`git push -u origin <作業ブランチ>`、失敗時 2/4/8/16s リトライ)
- コミットメッセージ: 種別プレフィックス (feat/fix/docs/test/i18n/perf/release) + 本文に「何が壊れていて何を検証したか」
- force-push・`--no-verify`・amend での歴史書換え禁止

## タスクの選び方

`docs/ASSESSMENT-2026-07.md` の改善案テーブルを見よ。**人手ゲート** (CI 有効化・Release 作成・
OAuth 資格情報等) はエージェントでは完了できない — 着手せずユーザーへ案内する。
設計判断を伴う項目 (スキーマ変更・golden 移行・API 変更) は着手前に計画を提示して承認を得る。
機械的で検証可能な項目 (regex/recall 追加・parity 増強・docs 保守) は oracle 先行 TDD で直接進めてよい。

タスクごとの実行手順書 (前提・触るファイル・手順・承認ゲート・模範コミット):
- **Opus** (設計判断 B1-B5): `docs/INSTRUCTIONS-OPUS.md`
- **Sonnet** (機械的 C1-C6): `docs/INSTRUCTIONS-SONNET.md`
