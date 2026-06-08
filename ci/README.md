# CI ワークフロー（手動セットアップが必要）

このディレクトリの [`android.yml`](./android.yml) は、本リポジトリ用に用意した
GitHub Actions CI ワークフローです。

## なぜ `.github/workflows/` に入っていないのか

このワークフローを生成した自動化エージェント（GitHub App）には
`workflows` 権限が無く、`.github/workflows/` 配下のファイルを push できません
（GitHub のセキュリティ制約）。そのため、ここにテンプレートとして配置しています。

## 有効化の手順

リポジトリ管理者が以下を実行してください（ローカルで 1 回だけ）:

```bash
mkdir -p .github/workflows
git mv ci/android.yml .github/workflows/android.yml   # または cp
git commit -m "ci: enable Android + Python CI workflow"
git push
```

`git mv` できる権限（通常の開発者の push 権限）があれば有効化できます。

## このワークフローが検証する内容

| ジョブ | 内容 |
|--------|------|
| **android** | JDK 17 + Android SDK + Gradle キャッシュ。`detekt`（静的解析）→ `lintDebug` → `testDebugUnitTest`（kotest 200+ テスト）→ `assembleDebug`。失敗時はテスト/lint/detekt レポートを artifact として保存。 |
| **python-oracle** | `popcoon-tdd` の pytest スイート（差分テストの正本、290 テスト）。ベンチマークは CI のノイズになるため無効化。 |

トリガー: `main` / `claude/**` への push、`main` への PR、手動実行（`workflow_dispatch`）。
同一ブランチで新しい push があれば古い実行を自動キャンセルします。

## 補足

ローカルに Android SDK が無い環境（`ANDROID_HOME` 未設定）では Kotlin を
コンパイルできないため、この CI が Kotlin のコンパイル・単体テストを検証する
唯一の経路になります。スキーマ変更（Room の version bump など）や移植
（PORTING_SPEC.md）のパリティは、まずこの CI を緑にしてからマージしてください。
