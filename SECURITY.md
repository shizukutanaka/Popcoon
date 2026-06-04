# セキュリティポリシー

## 報告

脆弱性を発見した場合、**公開 Issue ではなく** 非公開チャネルで報告する。

- GitHub Security Advisory (推奨): https://github.com/shizukutanaka/popcoon/security/advisories/new
- Email は GitHub プロフィール経由

## サポートバージョン

| Version | Supported |
|---|---|
| 0.1.x (current) | ✅ |
| < 0.1 | ❌ |

## 対応フロー

1. 報告受付から 72 時間以内に確認返信
2. 再現確認後 7 日以内に影響範囲を評価
3. 深刻度に応じ:
   - **Critical**: 72時間以内にパッチ配信
   - **High**: 1週間以内
   - **Medium/Low**: 次回リリース
4. 修正後に CVE 取得 (必要に応じて)
5. 報告者のクレジット表示 (希望時)

## スコープ

対象:
- Popcoon Android app 本体
- Cloudflare Workers backend
- CI/CD pipeline

対象外:
- 上流依存 (Ktor, Compose 等) のバグ — 上流にレポート願う
- ユーザーが自己管理する API キーの扱い
- ソーシャルエンジニアリング

## API キー管理の方針

### 現状
- EC API キー (Amazon PA-API / 楽天 / Yahoo) は `BuildConfig` 経由でビルド時注入。
- `local.properties` (gitignore 済) または CI シークレットで管理。
- ネットワークは `network_security_config.xml` で HTTPS/TLS のみ許可、cleartext 禁止。

### 既知の制約と推奨
`BuildConfig` のキーは APK のリバースエンジニアリングで抽出可能 (業界共通の制約)。
リスク低減策:
- **EC アフィリエイトキー**: 流出してもアフィリエイト収益の誤帰属程度で被害は限定的。
  各 EC のダッシュボードで使用量を監視。
- **Anthropic API キー (課金)**: クライアント直叩きは課金乱用リスクがあるため、
  本番では **backend (Cloudflare Workers) 経由のプロキシ**に移行することを推奨。
  Worker 側でレート制限・利用上限を強制し、キーをサーバーにのみ保持する。
  (現在は MVP のため BuildConfig 経由。`BACKEND_URL` 設定時はプロキシ優先。)
- R8/ProGuard による難読化を release ビルドで有効化済み。
- 機微キーは 90 日ごとにローテーション。
