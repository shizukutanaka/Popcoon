# 機能案: Popcoon 横断スマートカート（Google Universal Cart を見越して）

## 背景 — Google Universal Cart（Google I/O 2026）
Google は複数店舗・複数面（Search / Gemini / YouTube / Gmail）をまたぐ **Universal Cart** を発表。
カートに入れた瞬間から背景エージェントが値下げ・価格履歴・再入荷を監視し、**Google Wallet 連携で
支払い特典・ロイヤルティ・モール offer を理解してポイント/隠れた割引を最適化**、Google Pay または
店舗サイトでチェックアウト。さらにオープン標準 **UCP（Universal Commerce Protocol）** と
**AP2（Agent Payments Protocol）** でエージェント決済を標準化（Shopify/Target/Walmart 等が参加）。

→ これは Popcoon の中核（横断比較・ポイント実質価格・価格履歴・買い時）と**正面から重なる脅威**。
   ただし Google は (a) Wallet＝**データ収集**前提、(b) **広告モデル**との利益相反、(c) 日本モール
   （楽天/Yahoo/PayPay）のポイント経済への最適化が浅い、という弱点を持つ。

## 先回り機能: 「Popcoon 横断スマートカート」
Amazon/楽天/Yahoo をまたぐ**一つのカート**。背景の**オンデバイス正直エージェント**が、カート全体に対して:
1. **実質価格バスケット最適化**（本提案の中核・実証済み）: 送料無料ライン・ポイント還元を考慮し、
   「商品Aは楽天(5と0の日)、商品Bは Amazon、合計¥X / ポイント¥Y」と**モール分割の最安**を提示。
2. **買い時**: A1（季節分解予測）/A5（曜日シグナル）/A6（被覆保証付き区間）で「今は高い、○日後が底」。
3. **正直フラグ**: `DarkPatternDetector`（偽の参考価格）、`ReviewTrustScorer`（サクラ）をカート内全商品に。
4. **再入荷・値下げ通知**（Google と同等のパリティ）。
5. **チェックアウト**: 決済は持たず、各モールへ**ディープリンク/アフィリエイト受け渡し**（`AffiliateUrlBuilder`）。

## Google に対する差別化
| 軸 | Google Universal Cart | Popcoon 横断カート |
|----|----------------------|--------------------|
| プライバシー | Wallet＝決済/行動データ収集 | **オンデバイス・アカウント不要・決済情報なし** |
| 中立性 | 広告モデルと不可分 | **利益相反なし**（横断で「他店が安い/今は待て」を言える） |
| 日本最適 | US 先行 | **楽天SPU/5と0/PayPay の実質価格**（`PointSimulator`） |
| 根拠 | 不透明AI | A1/A5/A6 の**透明・検証可能・被覆保証付き** |

## 中核アルゴリズムの実証（プロトタイプ）
`popcoon-tdd/proto_cross_mall_cart.py` ＋ `test_proto_cross_mall_cart.py`（8テスト pass、全体 270 passed）。
- `optimize_basket(items, malls)`: 送料無料ライン・ポイント込み実質単価から、カート総額を最小化する
  モール割り当てを返す。小規模は全探索で厳密最適、大規模は貪欲フォールバック。ゼロ依存・決定的。
- 検証済み挙動: 単品最安、**送料無料ラインへ寄せる集約が単品最安分割に勝つケース**、分割が最適なケース、
  単一モール強制、空カート、大規模カートの貪欲化。

## 相互運用・将来対応（標準を敵に回さない）
- UCP/AP2 はオープン標準。Popcoon は **意思決定/助言レイヤ**として、エージェント型チェックアウトの
  **手前に立つ**（「どこで・いつ買うのが実質最安か」を中立に決める）設計が有利。
- 将来 UCP 対応モールが増えれば、Popcoon の最適化結果を UCP カートへ引き渡す統合も可能。

## Popcoon 既存コードへのマッピング
- カート: `data/db`（`WatchlistDao` を拡張）／ UI 新規 `ui/screens/cart`。
- 実質単価: `feature/points/PointSimulator`（送料・ポイント・クーポン込み）。
- 同一商品の横断同定: `feature/matching/ProductMatcher`（モール跨ぎの名寄せ）。
- 最適化: 本プロトタイプ `proto_cross_mall_cart`（Kotlin 移植＋パリティテスト）。
- 買い時/正直フラグ: `feature/prediction`・`feature/scorer`・`feature/darkpattern`・`feature/review`。
- 受け渡し: `feature/affiliate/AffiliateUrlBuilder`。

## ポジショニング
> **Google のカートは「どの店でも買える」、Popcoon のカートは「どの店で・いつ買うのが本当に得か」を、
> データを取らずに正直に教える。**

## 出典
- Universal Cart 発表: https://blog.google/products-and-platforms/products/shopping/google-shopping-cart/ ／ https://www.retaildive.com/news/google-launches-cross-retailer-universal-cart/820957/
- UCP / AP2（オープン標準）: https://developers.google.com/merchant/ucp ／ https://www.emarketer.com/content/google-expands-push-agentic-shopping-with-universal-cart
- 日本語解説（広告モデル転換の指摘）: https://innovatopia.jp/ai/ai-news/104542/ ／ https://note.com/masa_cloud/n/n781291ee7d39
