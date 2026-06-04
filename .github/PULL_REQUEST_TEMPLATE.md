## 変更概要

<!-- 1-2 文で何を変えたか -->

## なぜ

<!-- 背景・解こうとしている問題 -->

## 変更内容

<!-- 具体的な変更のリスト -->

-
-

## TDD チェックリスト

- [ ] Red テストを先に書いた (初回コミット)
- [ ] Green 実装が Red を通した
- [ ] Refactor 後も全テスト通過
- [ ] Mutation score ≥ 80% (該当モジュール)
- [ ] Python 参照実装と Kotlin 実装が一致 (`test_differential.py`)

## 影響範囲

- [ ] 既存 API 互換性維持
- [ ] データモデル変更なし (あれば migration 同梱)
- [ ] プライバシーポリシー変更なし (あれば `PRIVACY.md` 更新)

## テスト

- [ ] Android `./gradlew test` 通過
- [ ] Python `pytest` 通過
- [ ] 手動動作確認 (スクリーンショット添付推奨)

## 関連 Issue

Closes #
