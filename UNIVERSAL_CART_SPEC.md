# 仕様書: Popcoon 横断スマートカート（Cross-Mall Smart Cart）

Google Universal Cart を見越した先回り機能（`UNIVERSAL_CART_FEATURE.md` 参照）の実装仕様。
中核は「複数モールにまたがるカートを、送料・ポイント・クーポンを込みで**実質総額最小**に
割り当てる」最適化。本書は仕様を定義し、現行プロトタイプとのギャップを示し、不足分を実装する。

## 1. 用語・データモデル
- **CartItem**: `{name: str, qty: int=1, options: {mall_id: 実質単価}}`
  - 実質単価 = 税込価格 − ポイント − クーポン（**送料は含めない**。`PointSimulator` で前計算）。
  - `options` に存在するモールのみ購入可能（在庫切れ/非取扱はキー欠落で表現）。
- **Mall**: `{shipping: 送料, free_threshold: 送料無料ライン, coupons: [{threshold, discount}]}`
  - `coupons`: モール単位の「subtotal が threshold 以上で discount 円引き」（任意・複数可）。

## 2. 最適化
- **目的**: 実質総額を最小化する各 item のモール割り当てを求める。
- **総額** = Σ_mall [ subtotal_m − bestCoupon(subtotal_m) + (subtotal_m ≥ free_threshold_m ? 0 : shipping_m) ]
  - subtotal_m = Σ (実質単価 × qty)（モール m に割り当てた item）。
  - bestCoupon = しきい値を満たす coupons の中で最大の discount（単一適用）。
- **タイブレーク**（同額時）: ①配送回数が少ない（distinct モール数が小）→ 体験向上。②決定的順序。
- **規模**: 全探索が `brute_cap` 以下なら厳密最適、超なら item 単位の貪欲フォールバック。

## 3. 出力
`{assignment: {item_index: mall_id}, total, per_mall_subtotal, shipping_total, coupon_total, num_malls}`

## 4. 付随機能
- **節約額レポート**: 最適化総額 vs「全部を最安の単一モールで買った場合」を比較し、機能の価値を提示。

## 5. エッジケース
- 空カート → total 0。 / option 空の item → エラー。 / 単一 option → 強制割り当て。
- qty 指定なし → 1。 / coupons なし → 0。 / 送料無料ライン 0 → 常に送料無料。

## 6. 既存コードへの統合点
- 実質単価算出: `feature/points/PointSimulator`。/ 同一商品の横断同定: `feature/matching/ProductMatcher`。
- カート永続化: `data/db`（`WatchlistDao` 拡張）。/ 受け渡し: `feature/affiliate/AffiliateUrlBuilder`。
- 最適化中核: `popcoon-tdd/proto_cross_mall_cart`（Kotlin 移植＋パリティテスト）。

## 7. スコープ外（本仕様では扱わない）
決済（AP2/UCP 連携は将来）、リアルタイム在庫API、配送日数最適化、複数クーポン併用。

---

## 8. ギャップ分析（仕様 vs 現行プロトタイプ）
| 仕様要件 | 旧プロトタイプ | 状態 |
|----------|----------------|------|
| item 数量 `qty` | 未対応（数量1固定） | **不足 → 実装** |
| モール単位クーポン `coupons` | 未対応（送料無料のみ） | **不足 → 実装** |
| タイブレーク（配送回数最小） | 未対応（最初の最小） | **不足 → 実装** |
| 出力に `coupon_total` / `num_malls` | 未対応 | **不足 → 実装** |
| 節約額レポート | 未対応 | **不足 → 実装** |
| 厳密最適/貪欲フォールバック | 対応済み | OK |
| 送料無料ライン | 対応済み | OK |

→ 上記「不足」を `proto_cross_mall_cart.py` に**後方互換**で実装し、テストを追加（§9）。

## 9. 受け入れ条件
- 既存8テスト（単品最安・送料集約・分割・単一option・空・大規模貪欲・決定性）が引き続き green。
- 追加テスト: qty 反映、モールクーポン適用で最適が変わる、配送回数タイブレーク、節約額レポート。
- 全 Python スイートが green。
