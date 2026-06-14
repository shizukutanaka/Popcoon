# CI ワークフロー（手動セットアップが必要）

このディレクトリの [`android.yml`](./android.yml) は、本リポジトリ用に用意した
GitHub Actions CI ワークフローです。

## なぜ `.github/workflows/` に入っていないのか

このワークフローを生成した自動化エージェント（GitHub App）には
`workflows` 権限が無く、`.github/workflows/` 配下のファイルを push できません
（GitHub のセキュリティ制約）。そのため、ここにテンプレートとして配置しています。

## 有効化の手順（1 コマンド）

リポジトリ管理者が以下を実行してください（ローカルで 1 回だけ）:

```bash
bash ci/enable.sh && git push
```

`ci/enable.sh` は `ci/android.yml` を `.github/workflows/` へ `git mv` してコミットするだけです。
`git mv` できる権限（通常の開発者の push 権限）があれば有効化できます。
（自動化エージェントの GitHub App トークンには `workflows` 権限が無く、`.github/workflows/`
配下への push がリモートから拒否されることを実証済みです。人間の push 権限が必要です。）

## このワークフローが検証する内容

| ジョブ | 内容 |
|--------|------|
| **android** | JDK 17 + Android SDK + Gradle キャッシュ。`detekt`（静的解析）→ `lintDebug` → `testDebugUnitTest`（kotest 200+ テスト）→ `assembleDebug`。失敗時はテスト/lint/detekt レポートを artifact として保存。 |
| **python-oracle** | `popcoon-tdd` の pytest スイート（差分テストの正本、300 テスト）。ベンチマークは CI のノイズになるため無効化。 |
| **parity** | Android SDK 不要。Gradle 同梱の kotlin-compiler-embeddable で純関数（customs/eco/dark-pattern/predict/buy-timing と各 EC マッパー）をコンパイル・実行し Python オラクルと照合（`popcoon-tdd/kotlin_parity/run_all.sh`）。 |
| **backend** | Cloudflare Worker の vitest（アラート評価・PII 検査・KV ページネーション・入力検証）。 |

トリガー: `main` / `claude/**` への push、`main` への PR、手動実行（`workflow_dispatch`）。
同一ブランチで新しい push があれば古い実行を自動キャンセルします。

## 補足

ローカルに Android SDK が無い環境（`ANDROID_HOME` 未設定）では Kotlin を
コンパイルできないため、この CI が Kotlin のコンパイル・単体テストを検証する
唯一の経路になります。スキーマ変更（Room の version bump など）や移植
（PORTING_SPEC.md）のパリティは、まずこの CI を緑にしてからマージしてください。
