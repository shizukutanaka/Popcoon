# Popcoon 改善リサーチ (2026-07)

ユーザー指示「関連最新論文・動画・情報を調べて改善点を洗い出す」に基づく5系統の並列Web調査の記録。
各項目は「本セッションで実装済み ✅ / 見送り (理由付き) ⏸️」を明記する。出典は調査時点 (2026-07) のもの。

---

## 1. 商品マッチング / エンティティ解決

**主な知見**
- WDC Products ベンチマーク: 近似的な負例 (別世代・別容量) で precision が最も落ちる。R-SupCon 79.99 F1 が上位。 <https://webdatacommons.org/largescaleproductcorpus/wdc-products/>
- LLM ベース EM (Peeters+ EDBT 2025): zero-shot GPT-4 が fine-tuned PLM を +40〜68 F1 上回る、未知商品に頑健。 <https://openproceedings.org/2025/conf/edbt/paper-81.pdf>
- AnyMatch (2024): fine-tuned GPT-2 (~124M) が GPT-4 の 4.4% 差、1/3899 コスト。 <https://arxiv.org/abs/2409.04073>
- 日本語名寄せ: Rakuten RIT の Cross/Bi-Encoder 商品マッチング、属性 NER (ブランド/色/サイズ) が有効。LY Corp は JAN 集約 + NGT 近似最近傍。 <https://rit.rakuten.com/research/language/>
- オンデバイス埋め込み: Model2Vec/potion (静的埋め込み、数MB、numpy のみ、多言語) が唯一 <20MB 制約に収まる候補。 <https://github.com/MinishLab/model2vec>

**改善候補**
- ✅ **文字 2-gram Dice 類似度の併用** — 実装済: `titleSim = max(tokenJaccard, 0.75 × bigramDice)`。分かち書き無しタイトルの Jaccard 退化を Dice が救済し (「明治おいしい牛乳900ml」 vs 「明治 おいしい牛乳 900ml」が 0.0→0.75 でマッチ)、0.75 減衰でブランド+カテゴリ語共有の別商品 (イヤホン vs ヘッドホン、0.545<0.6) は弾く。oracle (`proto_title_similarity.py` +12 tests) 先行 → Kotlin 移植 → parity 実行照合。
- ⏸️ **属性抽出+不一致ペナルティ拡張** — 容量 (実装済) に加え色・個数の矛盾を減点。WDC の corner-case precision 問題に直接対応。
- ⏸️ **候補集合内 IDF-lite トークン重み付け**。
- ⏸️ **Model2Vec 静的埋め込み re-ranker** — 効果不確実、優先度低。

## 2. 価格予測 / 買い時判定

**主な知見**
- 消費者向け「買い時」予測の 2024-26 の直接後継論文は薄い (Etzioni Hamlet 2003 の系譜)。Keepa/CamelCamelCamel データでの公開ベンチマークは不在 = Popcoon が評価を公開する余地。
- 単純統計手法が依然競争力: Nixtla ベンチで統計アンサンブル SCUM が Chronos を ~10% 上回り計算量 20%。短い疎な系列では foundation model が不利。 <https://github.com/Nixtla/nixtla>
- Conformal PID Control (Angelopoulos+ NeurIPS 2023): quantile tracking(P)+error integration(I)、交換可能性仮定なしで長期カバレッジ保証、ACI の無限区間問題を回避。~30-100行で実装可。 <https://arxiv.org/abs/2307.16895>
- オンデバイス予測モデル: TTM (IBM Granite, ~1M params, ~4MB) が唯一 <50MB 現実解だが非決定的でスパース系列での優位は不確実。

**改善候補**
- ✅ **split-conformal → Conformal PID (P項のみ)** — 実装済 (`ConformalInterval.adaptiveConformalMargin`、`PricePredictionEngine.predict()` に配線、Python oracle 7件追加 + parity 7件追加、404/104 green)。積分項 (I) は飽和関数のチューニングリスクがあるため見送り、P項単独で分布シフト追従の主要な利得を確認 (shift/shrink/shock の3ケースで順序依存の反応を実証)。
- ✅ **価格アラートのデバウンス** — 実装済 (`PriceAlertDebouncer`、ユーザーに設計判断を確認: 1サイクル遅延確認を選択)。当初案の「7日移動中央値」ではなく、次回同期で同じ観測値が再現した場合のみ発火するシンプルな1サイクル確認方式を採用 (Room v6→v7、実コンパイル検証 run_alerts.sh green)。
- ⏸️ **Holt + damped-trend ETS + seasonal-naive の中央値アンサンブル** — <30点系列で頑健。
- ⏸️ **TTM オンデバイス** — 見送り (非決定的・効果不確実)。

## 3. ダークパターン / 規制

**主な知見**
- arXiv 2411.07441 以降: AppRay (2024, モバイルUI 2185件/18類型)、DECEPTICON (2025)、LLM 監査エージェント (2026)。
- 日本: 消費者庁 2025-04 実態調査が **32類型** + 事例集を公開 (最頻出=事前選択・偽りの階層表示)。 <https://www.caa.go.jp/policies/future/icprc/research_010>
- ダークパターン対策協会 (NDD): 認証制度 2025-10-15 開始、Bronze/Silver/Gold。 <https://www.ndda.net/>
- 特商法: 2026-01 検討会設置、解約妨害の明文禁止を審議、~2027 施行見込み。
- FTC: Amazon Prime ダークパターン **$2.5B 和解 (2025-09)**。
- データセット: **yamanalab/ec-darkpattern (Apache-2.0, 2356件)** 利用可。 <https://github.com/yamanalab/ec-darkpattern>

**改善候補**
- ✅ **HIDDEN_SUBSCRIPTION (隠れ定期購入) カテゴリ追加** — 実装済 (Kotlin+Python+parity+両テスト、97 parity / 397 oracle green)。特商法 2027 改正・FTC 和解の中核類型に先回り。
- ✅ **警告のカテゴリ名・深刻度のローカライズ表示** — 実装済 (`ui/DarkPatternTextLabels.kt`)。カテゴリ名が一切表示されず severity は英語 enum 生値だった実バグを修正。CAA 32類型の正式名は調査で全量を確認できなかったため、不正確な規制引用を避け一般的な日本語記述に留めた。
- ⏸️ **ec-darkpattern (Apache-2.0) からの日本語ルール拡充** — 実地確認の結果、データセットは Mathur et al. 2019 (英語圏ECサイト調査) 由来の英語テキストで日本語コンテンツが無いことが判明。直接移植ではなく大規模な翻訳・日本市場への適応が必要で費用対効果が見合わないため見送り。
- ✅→部分実装 **ReviewTrustScorer**: 当初想定の二峰性係数・レビュー急増検知は星別内訳・投稿日時が Amazon PA-API/楽天/Yahoo のいずれからも取得不可と判明し実装不可 (データモデル変更+3マッパー改修+API側対応未確認という前提の上に成立せず見送り)。代わりに既存データ (平均評価+件数) だけで直せる実バグを発見・修正: 「完璧すぎる」判定が reviewCount>=1000 の単一しきい値のみで、999件はどんな高評価でも無条件で素通りしていた。300〜999件の中量域に4.95以上という厳しめの中間しきい値を追加し抜け穴を解消。

## 4. Android / Google Play 2026 要件

**主な知見 (期限つき)**
- **target API 36 (Android 16)**: 新規/更新は **2026-08-31** から必須 (延長申請で 11/1)。 <https://developer.android.com/google/play/requirements/target-sdk>
- **Play Billing Library 8+**: 同じく **2026-08-31** 期限。現状 7.1.1。 <https://developer.android.com/google/play/billing/deprecation-faq>
- **16 KB ページサイズ**: 2025-11-01 から新規/更新で必須。純 Kotlin は既定で適合、native .so (ML Kit 依存) は要確認。
- Android 16 挙動変更: predictive back 既定 ON、edge-to-edge 強制、sw≥600dp で画面回転制限無視、WorkManager クォータ厳格化。
- Compose: 安定 BOM 2026.06.01、material3 1.4.0 (M3 Expressive 初安定)、Navigation 3 安定、strong skipping 既定。Kotlin 2.3.x + KSP2、AGP 8.x 最新 (AGP 9 は要移行計画)。

**改善候補**
- ✅ **targetSdk/compileSdk 35→36 + 挙動変更対応** — コード移行済 (predictive back opt-in 明示、edge-to-edge/回転制限は監査済み問題なし、AGP 8.10 は API 36 対応でバンプ不要)。**CI/Android Studio でのビルド検証が必須** (ci/README.md に明記)。
- ✅ **Play Billing 7.1.1 → 8.3.0 移行** — コード移行済 (QueryProductDetailsResult 署名変更、enableAutoServiceReconnection 採用)。同上、ビルド検証必須。
- ⏸️ Compose BOM / Kotlin / material3 Expressive のバンプ — ビルド検証不能環境での大規模バンプはリスク過大、CI 有効化後に別途。
- ✅ (関連) Amazon PA-API 5.0 廃止をドキュメントに反映済み (下記5参照)。

## 5. 日本EC API / Cloudflare / Anthropic

**主な知見**
- **楽天 SPU 上限 15→18.5倍 (2026-07-01 改定)**。2025-02 以降モバイル等は毎月エントリー必須、サービス毎ポイント上限。旧 IchibaItem/Search (〜2017版) は 2026-02-09 廃止、最新は 2026-07-01 版。
- **Yahoo!/LYP**: 2025-02 以降キャンペーンポイントは期間限定PayPay (〜30日失効)。5のつく日 +4% 継続。**ゾロ目の日は 2025-10 終了 → 感謝デー (11/22, ランク条件付き) に置換**。itemSearch V3 は継続。
- **Amazon PA-API 5.0 は 2026-05-15 廃止 → Creators API (OAuth2, 成果 10件/30日)**。
- **税制**: ¥16,666 少額輸入免税は 2028-04 廃止決定 (2026中は有効)。食品8%→1% 案 (2027-04) は未成立。
- **Cloudflare**: 無料プランで Durable Objects (SQLite) 利用可 (2025-04)、Rate Limiting binding GA (2025-09)。KV は高速化したが依然結果整合 (read-modify-write レース未解消)。Analytics Engine 無料。
- **Anthropic**: 低コストは claude-haiku-4-5 ($1/$5 per MTok)。Haiku 4.5 の最小キャッシュ長 4096 トークン。

**改善候補**
- ✅ **Amazon PA-API 5.0 廃止をドキュメント反映** — 実装済 (docstring/ARCHITECTURE/CHANGELOG/local.properties)。Creators API 移行は OAuth2 資格情報が必要で本環境では不可、TODO 明記。
- ✅ **楽天 SPU 上限 15→18** — 実装済 (PointSimulator + parity + 設定UI + 4ロケール注記、parity green)。
- ✅ **`/v1/advice` を Sonnet → Haiku 4.5** — 実装済 (tsc+vitest green)。
- ✅ **Yahoo 2026 ルール反映** — 実装済: 日曜+5% を「プレミアムな日曜日」条件 (LYPプレミアム/SoftBank 会員 + 5,000円以上、いずれも既存 UserContext フィールド) でゲート、感謝デー (11/22) をカレンダーに追加、期間限定PayPay 失効を注記。感謝デーのシミュレーションはランク次元が必要なため意図的に見送り (docstring 記載)。
- ✅ **KV rate-limit → ネイティブ ratelimit binding** — 実装済: binding があれば使用・無ければ KV フォールバックの漸進移行 (tsc + vitest 50/50)。
- ✅ **価格履歴 lost-update の DO 移行設計を文書化** — backend/README.md に per-product Durable Object 移行手順を記録 (実装は wrangler 検証不能のため見送り、理由も記録)。

---

## 本セッションの実装サマリ (このブランチ)

| 項目 | コミット種別 | 検証 |
|---|---|---|
| `/v1/advice` Haiku 化 | perf | tsc + vitest 46/46 |
| Amazon PA-API 5.0 廃止ドキュメント | docs | — (docs) |
| parity ハーネスの system Gradle フォールバック | test | run_all.sh 13/13 実コンパイル green |
| 楽天 SPU 上限 15→18 | fix | run_points parity + oracle 394 |
| 税制の将来変更注記 | docs | run.sh parity 93 |
| HIDDEN_SUBSCRIPTION ダークパターン検出 | feat | run.sh parity 97 + oracle 397 |
| Conformal PID (適応予測区間) | feat | run.sh parity 104 + oracle 404 |
| ダークパターン警告のカテゴリ/深刻度ローカライズ | fix | 4ロケール390キー一致 + parity 104 |
| Play Billing 8.3.0 + target API 36 移行 | feat | コード移行のみ — CI ビルド検証必須 |
| Yahoo 2026 ルール (プレミアムな日曜日/感謝デー) | fix | run_points + run.sh parity + oracle 404 |
| ネイティブ rate-limit binding (KV フォールバック付き) | feat | tsc + vitest 50/50 |
| ProductMatcher 属性不一致ペナルティ (個数/色) | feat | run_matcher 8件追加 全green |
| 価格アラートのデバウンス (1サイクル遅延確認) | feat | run_alerts parity + Room v6→v7 migration test |
| ReviewTrustScorer 中量域しきい値バグ修正 | fix | 手動検証12件全一致 (Kotest 実行不可のため throwaway harness) |
| ProductMatcher 文字 2-gram Dice 併用 (分かち書き無し救済) | feat | oracle `proto_title_similarity` +12 tests → run_matcher parity 全green (oracle 416) |
| Yahoo 期間限定PayPay 失効を設定画面に注記 | i18n | 4ロケール 398キー一致 + XML well-formed |

## 恒久的な環境制約

- **Android 実ビルド不可** (SDK 無し、Gradle wrapper が services.gradle.org へ到達不可)。Tier 0 の SDK/Billing バンプは「コード移行 + CI 検証必須」までが本環境のゴール。
- ただし **純 Kotlin ビジネスロジックは `/opt/gradle-8.14.3/lib` の kotlin-compiler-embeddable で実コンパイル・実行検証が可能** (parity ハーネス経由)。
- backend は `tsc --noEmit` + vitest で検証可能。wrangler ランタイム依存の機能 (KV/DO/ratelimit binding) は実行検証不可。
