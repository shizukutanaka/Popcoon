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
- ⏸️ **文字 2-gram Dice 類似度の併用** — 日本語タイトルは分かち書きが無くトークン Jaccard が弱い。効果高・~50行。次スプリント候補 (要 oracle + parity 追加)。
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
- ⏸️ **split-conformal → Conformal PID** — セール期の分布シフトに対するカバレッジ改善。最有力だが oracle 新規作成 (proto_conformal_pid.py) + Kotlin 移植 + parity が必要。次スプリント最優先。
- ⏸️ **価格アラートのデバウンス** — 「7日移動中央値から X% 以上下」かつ「N記録継続」で誤検知削減。中程度・要 oracle。
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
- ⏸️ **警告文を消費者庁32類型・景表法/特商法の用語に対応付け** — 低労力・規制準拠言語。次候補。
- ⏸️ **ec-darkpattern (Apache-2.0) からの日本語ルール拡充** — ライセンス表記を OssLicenses に追加要。
- ⏸️ **ReviewTrustScorer v2** (二峰性係数・レビュー急増検知) — pure Kotlin、要 oracle。

## 4. Android / Google Play 2026 要件

**主な知見 (期限つき)**
- **target API 36 (Android 16)**: 新規/更新は **2026-08-31** から必須 (延長申請で 11/1)。 <https://developer.android.com/google/play/requirements/target-sdk>
- **Play Billing Library 8+**: 同じく **2026-08-31** 期限。現状 7.1.1。 <https://developer.android.com/google/play/billing/deprecation-faq>
- **16 KB ページサイズ**: 2025-11-01 から新規/更新で必須。純 Kotlin は既定で適合、native .so (ML Kit 依存) は要確認。
- Android 16 挙動変更: predictive back 既定 ON、edge-to-edge 強制、sw≥600dp で画面回転制限無視、WorkManager クォータ厳格化。
- Compose: 安定 BOM 2026.06.01、material3 1.4.0 (M3 Expressive 初安定)、Navigation 3 安定、strong skipping 既定。Kotlin 2.3.x + KSP2、AGP 8.x 最新 (AGP 9 は要移行計画)。

**改善候補**
- ⏸️ **targetSdk/compileSdk 35→36 + 挙動変更対応** — 期限あり最優先だが本環境で Android ビルド不可。コード移行 + CI 検証必須の形で別途。
- ⏸️ **Play Billing 7.1.1 → 8 移行** — 同上、破壊的変更あり。
- ⏸️ Compose BOM / Kotlin / material3 Expressive のバンプ。
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
- ⏸️ **Yahoo 感謝デー (11/22) 追加・期間限定PayPay 失効注記** — ランク条件のため新設定次元が必要。UserContext にランクを足す設計判断を要し次スプリント。
- ⏸️ **KV rate-limit → ネイティブ ratelimit binding** — backend、wrangler ランタイム無しでは実行検証不可のため慎重に。
- ⏸️ **価格履歴 append を per-product Durable Object 化** — レース根絶の正攻法だが変更大・デプロイ検証不可。設計文書化のみ推奨。

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

## 恒久的な環境制約

- **Android 実ビルド不可** (SDK 無し、Gradle wrapper が services.gradle.org へ到達不可)。Tier 0 の SDK/Billing バンプは「コード移行 + CI 検証必須」までが本環境のゴール。
- ただし **純 Kotlin ビジネスロジックは `/opt/gradle-8.14.3/lib` の kotlin-compiler-embeddable で実コンパイル・実行検証が可能** (parity ハーネス経由)。
- backend は `tsc --noEmit` + vitest で検証可能。wrangler ランタイム依存の機能 (KV/DO/ratelimit binding) は実行検証不可。
