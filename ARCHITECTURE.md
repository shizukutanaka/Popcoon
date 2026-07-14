# Popcoon アーキテクチャ

## 全体像

```
┌──────────────────────────────────────────────────────────────────┐
│                   Popcoon Android (Kotlin)                        │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  UI Layer  (Jetpack Compose + Material 3)                 │   │
│  │  ─ SearchScreen          ─ ProductDetailScreen             │   │
│  │  ─ VerdictBadge          ─ Theme (#00C4CC WCAG AAA)        │   │
│  └──────────────────┬────────────────────────────────────────┘   │
│                     │ State flow                                  │
│  ┌──────────────────▼────────────────────────────────────────┐   │
│  │  ViewModel Layer  (@HiltViewModel + kotlinx.coroutines)   │   │
│  │  ─ SearchViewModel       ─ ProductDetailViewModel          │   │
│  └──────────────────┬────────────────────────────────────────┘   │
│                     │                                              │
│  ┌──────────────────▼────────────────────────────────────────┐   │
│  │  Feature Layer  (Pure Kotlin business logic)              │   │
│  │  ─ PricePredictionEngine  ─ DarkPatternDetector           │   │
│  │  ─ TCOCalculator          ─ CustomsSimulator              │   │
│  │  ─ EcoEthicsScorer        ─ BundlePackDetector            │   │
│  │  ─ BuyTimingScorer (integrates 6 signals)                 │   │
│  │  ─ BuyingAdvisor (Claude API)                             │   │
│  │  ─ AffiliateUrlBuilder    ─ BillingManager (Premium)      │   │
│  └──────────────────┬────────────────────────────────────────┘   │
│                     │                                              │
│  ┌──────────────────▼────────────────────────────────────────┐   │
│  │  Repository  (ProductRepository, parallel 3EC fetch)      │   │
│  └───────┬──────────────┬──────────────┬────────┬────────────┘   │
│          │              │              │        │                 │
│  ┌───────▼──┐  ┌────────▼───┐  ┌──────▼─┐  ┌───▼────────┐        │
│  │ Amazon   │  │  Rakuten   │  │  Yahoo │  │  Fallback  │        │
│  │ PA-API*  │  │  Ichiba    │  │ Shop V3│  │  Scraper   │        │
│  └──────────┘  └────────────┘  └────────┘  └────────────┘        │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘

* Amazon PA-API 5.0 は 2026-05-15 に廃止済み (後継 Creators API / OAuth2)。
  現状 Amazon ソースは常時失敗し、サーキットブレーカー OPEN のまま Fallback
  Scraper (JSON-LD) が Amazon 商品の実質データ源になる。Creators API への
  移行は OAuth2 資格情報と成果実績 (10件/30日) を要するため未実施 (TODO)。
                                │
                                │ HTTPS
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│         Cloudflare Workers Backend (TypeScript)                   │
│                                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ PRICE_   │  │ DEVICE_  │  │ ALERTS   │  │ RATE_    │          │
│  │ HISTORY  │  │ TOKENS   │  │          │  │ LIMIT    │          │
│  │   KV     │  │   KV     │  │   KV     │  │   KV     │          │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘          │
│                                                                    │
│  Cron (hourly): evaluate alerts → FCM push                        │
│  Endpoints: GET/POST/DELETE /v1/history, /v1/alerts, /v1/device   │
└──────────────────────────────────────────────────────────────────┘
```

**現状の注意点**: 上記の FCM push 経路は backend 単体としては実装済みだが、Android
クライアントは Firebase SDK を組み込んでおらず (`google-services.json` 無し、
`FirebaseMessagingService` 未実装)、`/v1/device` にデバイストークンを登録すること
も一切ない。そのためこの経路は現状 backend 側だけで完結する死コードで、実際に届く
通知は端末ローカル (`LocalNotificationManager`、WorkManager の日次/週次ジョブが
起点) のみ。Firebase を組み込むまではこの節は将来計画として読むこと。

## データフロー

1. ユーザーが検索キーワード入力
2. `SearchViewModel.onQueryChange` → debounce 300ms
3. `ProductRepository.search()` が 3 EC に **並列** async/await
4. 各 EC が 5秒以内に応答しなければ個別 timeout
5. 結果を統合し `totalPrice` 昇順でソート
6. fire-and-forget で backend に `POST /v1/history`
7. Compose が `StateFlow<SearchUiState>` を collect して UI 更新

## 原則

### 単方向データフロー
UI → ViewModel → Repository → Network. 逆流なし。StateFlow のみ。

### 純粋関数層
`feature/*` は Android 依存なし。JVM で単体テスト可能。
**Kotlin Multiplatform 化の準備**: 将来 iOS 対応で core ロジックを共通化可能。

### Fail-soft
API 失敗でもアプリは動く。3 EC のうち1つでも成功すれば結果を返す。

### テレメトリゼロ
分析 SDK なし。クラッシュレポート ([Google Play Vitals](https://developer.android.com/distribute/best-practices/develop/monitor-app-quality)) のみ (OS 標準、ユーザー同意済み)。

## Kotlin と Python の二重構造

```
    [仕様]
       │
       ├──> Python (popcoon-tdd/)         仕様オラクル
       │     ├─ 273 tests, 98% coverage
       │     ├─ 100% mutation × 4 modules
       │     └─ 11 階層防御
       │
       └──> Kotlin (app/src/main/)        本番実装
             ├─ 同じロジック、同じ出力
             ├─ Differential test で一致保証
             └─ Android で動く
```

Python は本番にデプロイされない。
**Kotlin 実装の正しさを守るオラクル**として機能する
(手動実行 `python3 -m pytest -q`。CI での自動実行は `ci/android.yml` が
`.github/workflows/` へ未移動のため現状未稼働 — README.md の「CI について」参照)。

## セキュリティ方針

- API key は BuildConfig (リポジトリ非コミット)
- HTTPS 強制 (`usesCleartextTraffic=false`)
- Android Keystore で端末内データ保護
- ProGuard/R8 full mode でコード難読化
- Dependabot で週次依存更新 (`.github/dependabot.yml`、稼働中)
- ※ CodeQL 週次セキュリティスキャンは過去の記載だが未実装 (2026-07 監査で訂正)

## 収益化

- **無料**: 全機能使用可能、アフィリエイト URL 経由で収益発生
- **Premium ¥480/月 or ¥3,800/年**:
  - アフィリエイト UI 非表示
  - アラート無制限 (無料 5件)
  - 価格履歴 CSV エクスポート
  - 詳細 CO2 データ
- Google Play Billing Library 7.1 (`billing-ktx`)
- 業界統計: subscription ARPU = ad-only の 4.6 倍

## スケール予測 (無料枠内)

Cloudflare Workers 無料枠: 100k requests/day。
1 ユーザー平均 20 requests/日と仮定 → **5,000 DAU まで完全無料運用可能**。

それを超えた時点で Workers Paid ($5/月) に移行。
¥480/月 × 2 Premium 購読で backend コスト回収。

## テスト戦略の11階層

1. **Unit tests** — 個別関数
2. **Integration tests** — モジュール連携
3. **Golden snapshots** — 出力ハッシュ固定
4. **Metamorphic** — 線形性 / 単調性 / 反転
5. **Mutation testing** — テストの穴を検出 (100%)
6. **Performance regression** — μs 単位閾値
7. **Fuzzing** — property-based + boundary
8. **Stateful** — 状態機械の組合せ爆発
9. **Concurrency** — 並行アクセス
10. **Differential** — optimized vs naive で実装整合性
11. **Chaos** — 時刻改竄 / メモリ圧迫 / GC 圧力
12. **Targeted PBT** — `hypothesis.target()` でワーストケース誘導

Popcoon の核心ロジックは 2026 年時点の業界ベストプラクティス以上で守られている。
