# Room スキーマ履歴

このディレクトリは `app/build.gradle.kts` の `ksp { arg("room.schemaLocation", ...) }` 設定により、
`PopcoonDatabase` の各バージョンのスキーマ JSON (`io.github.shizukutanaka.popcoon.data.db.PopcoonDatabase/<version>.json`)
が自動生成される場所です。

## なぜコミット対象か

Room のスキーマ JSON は Rails のマイグレーションファイルと同様、**過去バージョンの実スキーマの記録そのもの**です。
`androidTest` の `MigrationTest.kt` (`androidx.room.testing.MigrationTestHelper`) がこれらのファイルを参照して、
各 `MIGRATION_x_y` が実際に正しいスキーマ変換を行うかを検証します。ビルド成果物として `.gitignore` すべきものでは
ありません。

## 現状 (このコミット時点)

このリポジトリは Android SDK が利用できない環境で開発されたセッションを含むため、実際のビルドを実行して
スキーマ JSON を生成できていません。**Android Studio または CI (Android SDK あり) で最初に `./gradlew build`
(または `assembleDebug` 等) を実行した時点で、v1〜v6 の各スキーマ JSON がここに自動生成されます** — 生成された
JSON はコミットしてください。

現在の `PopcoonDatabase` バージョンは 6 (`MIGRATION_1_2` 〜 `MIGRATION_5_6` を実装済み)。
