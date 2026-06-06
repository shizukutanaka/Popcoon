# 仕様書: ダークパターン暴露の拡張（Dark Pattern Exposure）

`BEAT_AMAZON_APP.md` P1-6「Amazon 出品のダークパターン暴露」の実装仕様。
FTC が問題視した手口（偽の参考価格・偽の緊急性/希少性・操作的なソーシャルプルーフ等）を、
中立な第三者として可視化する。学術 taxonomy（Mathur CSCW2019 / AidUI ICSE'23 / arXiv:2211.06543）
に既存検出をマッピングし、不足カテゴリを補う。

## 1. taxonomy と現行 `DarkPatternDetector` のマッピング
| Mathur/AidUI カテゴリ | 現行検出（価格・数値ベース） | 状態 |
|----------------------|------------------------------|------|
| Misdirection（誤誘導） | `INFLATED_LIST_PRICE`（参考価格誇張） | 一部あり |
| Sneaking（こっそり） | `PRE_SALE_MARKUP` / `DRIP_PRICING`（隠れコスト） | 一部あり |
| （価格演出） | `ALWAYS_ON_DISCOUNT` / `CHARM_PRICING` | あり |
| **Urgency（偽の緊急性）** | — | **不足** |
| **Scarcity（偽の希少性）** | — | **不足** |
| **Social Proof（操作的社会的証明）** | — | **不足** |
| **Misdirection（プリチェック/初期選択）** | — | **不足** |
| **Forced Action（confirmshaming）** | — | **不足** |

→ 不足は主に **UI テキスト系**。価格・数値系は既存 Kotlin が担当、テキスト系を本実装で補完。

## 2. 入力 / 出力
- 入力: `text`（商品ページの可視テキスト連結）, 任意 `stock_count`（数値在庫）。
  - プライバシー: 商品ページのテキストのみ。送信なし・オンデバイス（I5 方針）。
- 出力: 警告リスト `[{"category", "evidence", "severity"}]`（category は taxonomy 名）。

## 3. 検出ルール（テキスト系）
- **URGENCY**: `残り\d+(時間|分|秒)`、`本日限り`、`まもなく終了`、`ending soon`、`limited time`、`hurry`。
- **SCARCITY**: `残り(\d+)点`、`在庫わずか`、`only (\d+) left`、`low stock`。
  - severity: 数 n≤3 または「わずか」→ HIGH、それ以外 MEDIUM。`stock_count` 0<n≤3 でも HIGH。
- **SOCIAL_PROOF**: `(\d+)人が(見て|閲覧|カートに|購入)`、`(\d+) people are viewing`、`in (\d+) carts`。
- **MISDIRECTION**: `(デフォルト|既定|初期設定)で(チェック|選択|追加)`、`pre-?(checked|selected)`。
- **FORCED_ACTION (confirmshaming)**: `いいえ.*(節約|お得|割引).*(したくない|不要|結構)`、`no, i (don't|do not) want to save`。

## 4. severity
HIGH = 強い操作性（極小在庫・confirmshaming）。MEDIUM = それ以外。決定的に出力（category 昇順）。

## 5. 既存コードへの統合点
- `feature/darkpattern/DarkPatternDetector.kt`（価格系）に**テキスト系検出を合流**。
- `ui/a11y/AccessibilityExt.kt` の警告ラベル taxonomy に新カテゴリを追加。
- 検出中核: `popcoon-tdd/proto_darkpattern_signals`（Kotlin 移植＋パリティテスト）。

## 6. スコープ外
画像/レイアウト系ダークパターン（YOLO 検出 2512.18269）、DOM 解析、多言語の網羅（ja/en に限定）。

## 7. 受け入れ条件
- 各カテゴリの検出・未検出・severity 判定のテストが green。
- クリーンなテキストで誤検知ゼロ。複数同時検出・決定性。全 Python スイート green。
