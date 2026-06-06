# Popcoon が Amazon 公式買い物アプリを上回るために

目的: 「amazon公式の買い物アプリを上回る」ための戦略と、Popcoon 実コードへの落とし込み。
Amazon の物量（カタログ・物流）では勝てない。勝てるのは **Amazon が構造的に提供できない
「正直・横断・プライバシー優先の買い物アドバイザー」** という土俵。Amazon が 2025/2026 に
価格履歴/自動購入まで踏み込んだ今、差別化の核は「利益相反のない助言」と「日本ポイント実質価格」。

## 1. Amazon 公式アプリの現在地（2025/2026）— パリティ必須ライン
- **Alexa for Shopping（エージェント型AI、旧Rufus）**: 検索バーから質問、購入ガイド生成、動的比較、
  **最大1年の価格履歴**、**目標価格での自動購入**、**定期再入荷**、会話型再注文、アカウント記憶で個別化。
- AR 試着、配送追跡、レコメンド、Lightning Deals、Subscribe & Save、圧倒的カタログ/物流。
- → Popcoon は最低限ここに**機能パリティ**（価格履歴・予測・目標価格アラート・会話型助言・再入荷通知）が要る。

## 2. Amazon の構造的弱点（Popcoon が上回る源泉）
| 弱点 | 根拠 | Popcoon の優位 |
|------|------|----------------|
| **利益相反（自社しか売らない）** | Amazon は「楽天/Yahoo の方が安い」「今は買うな」と言えない | **横断比較＋正直な買い時判定**が本質的に可能 |
| **ダークパターン** | FTC $2.5B 制裁（Prime 解約 "Iliad Flow"、6クリック15選択）、偽の参考価格で割引を演出 | `DarkPatternDetector` が **Amazon 出品の偽セール/参考価格誇張を可視化** |
| **偽レビュー** | 2023年に2.5億件ブロック、38%の消費者が誤誘導 (FTC) | `ReviewTrustScorer` の**サクラ度・分布シグナル**（利益相反なく中立提示） |
| **データ収集前提** | Alexa for Shopping はアカウント記憶/個別化＝行動データ収集 | **ゼロテレメトリ・オンデバイス・アカウント不要**（I5方針） |
| **US偏重・日本ポイント弱い** | Alexa for Shopping は US 先行 | **楽天/Yahoo/PayPay の実質価格**（`PointSimulator`）で日本最適 |

## 3. 上回るための具体策（コードにマッピング・優先度）

### P0 — パリティ（Amazon に追いつく、未実装/弱い箇所）
1. **目標価格アラート**（Amazon: 目標価格で自動購入）→ `WatchlistDao` にユーザー指定の目標価格＋通知（自動購入はせず「今が買い」を提示＝正直さで差別化）。
2. **再入荷通知**（Amazon: restock）→ `PriceSyncWorker`＋`Product.stockCount`（cat9/G1）。
3. **会話型フォローアップ助言**（Amazon: Alexa 会話）→ `BuyingAdvisor` に追問対応＋**参照シグナル必須併記**（説明可能性で Amazon の不透明AIに勝つ、A10）。
4. **価格履歴の長期化**（Amazon: 1年）→ `PriceCacheDao`/`PriceChart` の保持期間と表示を1年へ。

### P1 — 上回る（Amazon が構造的にやらない/やれない）
5. **横断「正直比較」**: 同一商品の Amazon/楽天/Yahoo を `ProductMatcher` で名寄せし、**実質価格（送料・ポイント・クーポン込み）**で最安を提示。Amazon より安い他店があれば明示。
6. **Amazon 出品のダークパターン暴露**: `DarkPatternDetector` で偽の参考価格・常設セール・セール前値上げを Amazon 商品にこそ適用し、「この“割引”は本物か」を表示（FTC が問題視した手口を可視化）。
7. **正直な買い時判定**: A1（季節分解予測）＋A5（曜日シグナル）＋A6（**被覆保証付き**予測区間）で「今は高い、○日後が底」を**透明な根拠付き**で提示。Amazon の予測は自社購買誘導と不可分だが Popcoon は中立。
8. **サクラ・レビュー信頼度**: `ReviewTrustScorer`＋分布シグナル（A3: 星5偏重/投稿バースト）。中立な第三者だから言える。
9. **実質価格（日本ポイント）**: `PointSimulator` で楽天SPU/5と0のつく日/PayPay 還元後の実質最安を提示。

### P2 — 信頼・体験で差をつける
10. **プライバシーを売りに**: 「アカウント不要・データ送信ゼロ・オンデバイス計算」を前面に（Amazon の対極）。
11. **TCO/越境/エコ**: `TCOCalculator`/`CustomsSimulator`/`EcoEthicsScorer` で「総保有コスト」「越境の実質」「環境負荷」まで提示（Amazon の購買最適化とは別軸の価値）。

## 4. 既に実証済みの差別化技術（プロトタイプ）
Amazon の不透明AI予測に対し、Popcoon は**透明・検証可能・被覆保証付き**で対抗できることを Python で実証済み:
- A1 `proto_seasonal_decomp_forecast.py`（季節分解予測、9テスト）
- A5 `proto_seasonal_signal.py`（曜日の買い時シグナル、8テスト）
- A6 `proto_conformal_interval.py`（被覆保証付き予測区間、9テスト）
いずれもゼロ依存・決定的・オンデバイスで、Amazon が出せない「根拠の透明性」を担保。

## 5. 一言ポジショニング
> **「Amazon は“買わせる”アプリ、Popcoon は“賢く買う”アプリ。」**
> 横断・正直・プライバシー優先で、Amazon が利益相反ゆえに言えない「他店が安い」「今は待て」
> 「この割引は偽物」「このレビューは怪しい」を、検証可能な根拠付きで伝える。

## 出典
- Alexa for Shopping / 価格履歴・自動購入: https://www.aboutamazon.com/news/retail/alexa-for-shopping-ai-assistant ／ https://www.retaildive.com/news/amazon-ai-alexa-for-shopping/820218/ ／ https://www.cnbc.com/2026/05/13/amazon-ditches-rufus-ai-chatbot-in-favor-of-alexa-shopping-agent.html
- FTC ダークパターン $2.5B: https://www.npr.org/2025/09/23/nx-s1-5543497/the-dark-patterns-at-the-center-of-ftcs-lawsuit-against-amazon ／ 偽の参考価格: https://uxdesign.cc/amazons-roach-motel-4fc7a9d1fc31
- 偽レビュー: https://bettershopanalytics.com/amazon-cracks-down-on-fake-reviews-in-2025/ ／ https://easyparser.com/blog/detect-fake-amazon-reviews
