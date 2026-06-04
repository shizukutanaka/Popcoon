# 貢献ガイド

Popcoon への貢献を歓迎する。品質基準は厳しめに設定している。

## セットアップ (clone 即ビルド)

```bash
git clone https://github.com/shizukutanaka/popcoon.git
cd popcoon
cp local.properties.example local.properties   # API キーを記入 (任意、未記入でもビルド可)
./gradlew assembleDebug                          # gradle-wrapper.jar 同梱済み
```

`./gradlew` に実行権限がない場合 (Windows clone 後など):

```bash
git update-index --chmod=+x gradlew
# または
chmod +x gradlew
```

## 開発原則

John Carmack (性能) / Robert C. Martin (clean architecture) / Rob Pike (シンプル) の思想を前提。

1. **読んでいないコードは変更するな** — 必ずファイル全体を読む
2. **TDD を強制** — Red → Green → Refactor サイクルなしのPRは不採用
3. **Mutation score 80%+** — テストの「質」を形式的に保証
4. **省力化・自動化** — 手動手順は CI に組み込む
5. **日本語優先** — 1,000 言語対応を目指す

## TDD サイクル (必須)

各機能追加は以下の順:

1. **Red** → テストを書く (実装なし) → 失敗
2. **Green** → 最小実装 → 通過
3. **Refactor** → 設計改善 (テスト維持) → 通過継続
4. **Mutation** → mutation test 実行 → 80%+ kill
5. **Golden** → snapshot で出力固定化 → CI 保護

## PR 受入れチェックリスト

- [ ] 新機能に対する Red テストが初回コミットに含まれる
- [ ] 全テスト通過 (Android `./gradlew test` + Python `pytest`)
- [ ] Coverage ≥ 97% (Python TDD 層)
- [ ] Mutation score ≥ 80% (該当する各モジュール)
- [ ] Lint clean (警告 0)
- [ ] 日本語コメントは敬語を避け、体言止めを好む
- [ ] CHANGELOG.md を更新
- [ ] テレメトリ追加禁止

## Differential testing の原則

Kotlin 本番実装と Python 参照実装は常に同じ出力を返す。
変更する場合は両側を同時修正し `test_differential.py` で検証する。

## ブランチ戦略

- `main` — 常にデプロイ可能
- `develop` — 開発統合
- `feat/*` / `fix/*` / `chore/*` — 作業ブランチ

## コミットメッセージ

Conventional commits 準拠:

- `feat: BundlePackDetector に楽天ケース対応追加`
- `fix(scorer): base_score 積算バグ修正`
- `refactor: Trie を deque ベースに置換 (14× 高速化)`
- `test: metamorphic test 追加`
- `docs: README の設置手順を更新`
