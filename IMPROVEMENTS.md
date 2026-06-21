# Popcoon 改善メモ (deep research)

コードベース全層 (build / data・network / feature・domain / Python TDD parity / UI・Compose /
CI) を調査した結果と、適用した改善・今後のバックログ。

## 製品改善ループ (Tier 70: Qiita/Zenn 外部知見の適用 — DataStore 破損/IO 例外でクラッシュする読み込みを防御 — 2026-06-21)

### きっかけ (Qiita/Zenn の DataStore・Context リーク記事)

- 「DataStore の `.data` はファイル読み取り失敗で `IOException`、破損で
  `CorruptionException` を投げる。`.catch { if (it is IOException) emit(emptyPreferences()) }`
  で既定値にフォールバックするのが定石」
  (qiita: DataStore の Read/Write 覚え書き / InvalidProtocolBufferException への対処)
- 「ViewModel が Activity Context を保持するとリーク。`@ApplicationContext` を使う」
  (qiita: Singleton を使う場合の注意 / zenn: Context の使い分け)

### Popcoon への監査結果

- **Context リーク**: 全 ViewModel が `@ApplicationContext` を注入し、Activity は
  メソッド引数 (`launchPurchase(activity)` / `requestReviewIfEligible(activity)`) で
  受け取りフィールド保持しない → **リークなし** ✓
- **DataStore 例外処理**: `UserPreferences` の全 read フローが
  `context.dataStore.data.map { … }` で、**`.catch` が無い**ことを発見 ❌

### 発見した問題

DataStore ファイルが破損 (書き込み途中でプロセス kill、ディスク障害等) または
読み取り不能になると、`.data` は collector に例外を伝播する。
`UserPreferences` の 12 個の read フロー全てが無防備で、特に **`onboarded` は起動直後の
`AppRootViewModel` で読まれる**ため、一度 DataStore が壊れると**アプリが起動時に
クラッシュし続け、ユーザーは再インストールするまで復帰できない**最悪シナリオがあった。

### 適用した変更

`UserPreferences` に **単一の `safeData` フロー**を導入し、全 read をそこ経由に統一:
```kotlin
private val safeData: Flow<Preferences> = context.dataStore.data
    .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
```
- 12 個の read フロー (`onboarded`/`crashReportOptin`/`rakutenSpu`/… 全て) を
  `context.dataStore.data` → `safeData` に変更。1 箇所で破損耐性を担保 (DRY)。
- 破損時は**空 Preferences = 全項目デフォルト値**で起動継続 (privacy-first の既定 OFF に
  自然にフォールバックするため、安全側に倒れる)。
- `IOException` 以外 (プログラミングエラー等) は握り潰さず再 throw。

### 一般教訓

DataStore/Room/ファイル等**外部永続化からの read は「失敗しうる Flow」**として扱う。
ハッピーパスの `.data.map` だけ書くと、破損という稀だが致命的な状態で
**起動ループクラッシュ**に至る。`onboarded` のような**起動経路で読む値**は特に、
`.catch` フォールバックの有無がアプリの復帰可能性を分ける。
リカバリ方針 (空=デフォルト) が privacy-first の既定値と一致していると、
フォールバックがそのまま安全な初期状態になり一石二鳥。

---

## 製品改善ループ (Tier 69: Qiita/Zenn 外部知見の適用 — ナビ二重 push 防止 + 検索 IME アクション — 2026-06-21)

### きっかけ (Qiita/Zenn の連打防止・IME 記事)

- 「ボタン/行を連打すると画面が二重に立ち上がる。Navigation は `launchSingleTop` か
  クリック抑制で防ぐ」 (qiita: ボタン連打で多重表示させない / zenn: 二度押しを避けるボタン)
- 「検索 `TextField` は `KeyboardOptions(imeAction = ImeAction.Search)` +
  `KeyboardActions(onSearch = { focusManager.clearFocus() })` でキーボードを閉じる」
  (zenn: TextField と Keyboard の問題と解決 / qiita: キーボードを閉じる方法)

### Popcoon への監査結果

- **タブ切替** (`navigateToTab`): `launchSingleTop = true` 済み ✓
- **詳細/二次画面への遷移**: `navigateToDetail`・`onSettings`・`onWatchlist`・`onBarcode`・
  `onSaleCalendar`・`onCustoms`・ウォッチリスト項目クリックが**いずれも
  `launchSingleTop` なし** → 連打で二重 push の余地 ❌
- **検索 TextField**: `keyboardOptions`/`keyboardActions` 未設定 → 結果表示後も
  キーボードが被さる ❌

### 発見した問題

1. **ナビゲーションの二重 push**: 商品行をすばやく 2 回タップすると、同じ商品の
   詳細画面がバックスタックに 2 つ積まれ、戻るを 2 回押す羽目になる。
   設定/ウォッチリスト/バーコード/カレンダー/関税ボタンも同様。
2. **検索後にキーボードが残る**: 検索は `onValueChange` の debounce で実行されるため
   IME に検索ボタンが無く、結果が出てもキーボードが画面下半分を覆ったまま。

### 適用した変更

1. **`launchSingleTop = true` を全二次遷移に付与**:
   - `navigateToDetail` (検索→詳細、バーコード→詳細の共通経路)
   - `PopcoonNavGraph` の `onSettings`/`onWatchlist`/`onBarcode`/`onSaleCalendar`/
     `onCustoms`/ウォッチリスト項目クリック (`detail/$key`)
   同一 route が既に先頭にあれば再 push せず先頭を再利用 → 連打で増殖しない。
   (通常の単発遷移には影響しない no-op、低リスク)
2. **検索 IME アクション**: `OutlinedTextField` に
   `KeyboardOptions(imeAction = ImeAction.Search)` +
   `KeyboardActions(onSearch = { showSuggestions = false; focusManager.clearFocus() })`
   を追加。検索ボタン押下でサジェストを閉じ、フォーカスを外してキーボードを下げる。

### 一般教訓

`launchSingleTop` は「同一 route が連続 push されるのを防ぐ」ための既定装備。
**遷移を起こす全ての navigate() に付けてよい** (単発時は no-op)。タブ以外の
画面遷移で付け忘れると、連打や遷移アニメ中の追加タップで簡単に二重化する。
また検索 UX では「入力 → 結果」の間でキーボードを能動的に下げる設計
(`ImeAction.Search` + `clearFocus`) が、限られた画面領域を結果表示に明け渡す定石。

---

## 製品改善ループ (Tier 68: Qiita/Zenn 外部知見の適用 — ロケール依存の数値書式 + Text 省略記号 — 2026-06-21)

### きっかけ (Qiita/Zenn の数値書式・Text overflow 記事)

- 「`String.format("%f", x)` を**ロケール無指定**で呼ぶと、独/仏/露などで小数点が
  『,』になる。機械可読出力や表記統一が要る箇所は `Locale.US` を明示せよ。
  数字自体もアラビア語等で別字形に置換されうる」
  (qiita: ロケールによって小数点がピリオドかコンマか変わる / String.format で死んだ話)
- 「長文 `Text` は `maxLines` + `overflow = TextOverflow.Ellipsis` を併用。
  `overflow` 省略時は `Clip` でハードに切れ『…』が出ない」
  (zenn/qiita: Compose Text の overflow)

### Popcoon への監査結果

ロケール安全性は概ね良好だったが 1 件の取りこぼし + UI 省略の漏れを発見:
- **`CurrencyFormatter`**: 全箇所 `String.format(Locale.US, "%,d", …)` ✓
- **`AwsSigV4Signer`**: AWS 署名日付は `SimpleDateFormat(…, Locale.US)` + UTC、
  hex は `%02x` (locale 非依存) ✓ — タイ仏暦/アラビア数字でも署名は壊れない
- **CSV エクスポート**: 価格は `Long.toString()` (locale 非依存の ASCII 数字) ✓
- **`PointSimulator`**: ❌ `"%.1f%%".format(rate)` が**ロケール無指定**。
- **Text overflow**: `ProductRow`/`WatchlistRow` のタイトルが `maxLines` のみで省略記号なし。

### 発見した問題

1. **`PointSimulator` のポイント率表示**: `"%.1f%%".format(rate)` は既定ロケール依存。
   端末の既定ロケールが独語等のユーザー (アプリ UI は EN でも) には「**1,5%**」と表示され、
   同画面の `CurrencyFormatter` (Locale.US, ピリオド) と**小数点表記が不一致**になる。
2. **商品タイトルのハードクリップ**: `ProductRow`(検索結果) と `WatchlistRow` の
   `Text(title, maxLines = 2)` は `overflow` 未指定 = `Clip`。長いタイトルが
   2 行目末尾で**文字の途中でブツ切れ**になり「…」が出ない (SearchSuggestions は既に
   `Ellipsis` 済みだった)。

### 適用した変更

1. **`PointSimulator`**: `String.format(Locale.US, "%.1f%%", rate)` に変更
   (`import java.util.Locale` 追加)。CurrencyFormatter と同じ Locale.US ポリシーに統一。
2. **`ProductRow` / `WatchlistRow`**: タイトル `Text` に
   `overflow = TextOverflow.Ellipsis` を追加 (`import …text.style.TextOverflow`)。
   長いタイトルが「…」で綺麗に省略される。

### 検証

- `%.Nf`/`%f` のロケール無指定書式を全 main から再 grep → 残り 0 件。
- `PointSimulatorTest` は `rateString` の小数点表記をアサートしていない (項目名のみ検証)
  ため、Locale.US 化で挙動は不変 (CI は元々ピリオドロケール)。

### 一般教訓

数値→文字列変換は **「人間向け表示」か「機械可読/表記統一」か**で扱いを変える:
- 機械可読 (CSV/JSON/署名/URL) や、アプリ内で表記を統一したい表示は `Locale.US` 明示。
- `Long.toString()` は locale 非依存なので安全 (ただし `%,d`/`%f` は要注意)。
本プロジェクトは CurrencyFormatter/署名で既に Locale.US を徹底できていたが、
**後から追加された 1 箇所 (PointSimulator) が方針から漏れていた** — 規約は
追加コードに自動適用されないので、定期 grep で逸脱を拾うのが有効。

---

## 製品改善ループ (Tier 67: Qiita/Zenn 外部知見の適用 — 複数形リソースで英語の「in 1 days」を修正 — 2026-06-21)

### きっかけ (Qiita/Zenn の plurals・状態保存記事)

- 「数を含む文言は `<plurals>` (quantity strings) を使う。`%d` を素の文字列に
  埋めると英語などで『1 days』のような非文法表現になる。`other` は必須、
  日本語/韓国語/中国語は CLDR 上 `other` のみ」
  (qiita: 複数形リソースを使う場合の注意(otherについて))
- 「構成変更は ViewModel、プロセス death は SavedStateHandle で状態復元」
  (qiita: ViewModel のデータを保存/復元するには SavedStateHandle を使う)

### Popcoon への監査結果

- **SavedStateHandle**: `SearchViewModel` が既に `SavedStateHandle` で barcode_query を
  受け取り済み。検索結果はメモリ保持で、プロセス death 後は再検索すればよい設計 ✓
  (1MB 制限のある Bundle に重い商品リストを載せない、という推奨にも合致)
- **複数形リソース**: `<plurals>` が **1 つも未使用**。`%d` を含む文言を全 EN 文字列で
  確認したところ、**1 件が英語で非文法**になることを発見。

### 発見した問題

`sale_calendar_days_until` = EN「in %1$d days」。`upcomingSales` は
`startDate > today` を満たすセールのみ返すため**最短で 1 日後 (翌日開催) が実際に到達する**。
その場合、英語ユーザーには **「in 1 days」** と表示され非文法だった。
(他の `%d` 文字列も点検: `product_cross_mall_*` は常に 2 件以上、`Pack of 1`/`(1 yr)` は
文法的に許容、のため対象外と判断。)

### 適用した変更

1. **4 ロケールの `sale_calendar_days_until` を `<string>` → `<plurals>` に移行**:
   - EN: `one`=「in %1$d day」/ `other`=「in %1$d days」
   - JA/KO/ZH: CLDR 上 `other` のみ (単複を区別しない言語) → 既存文言を `other` に格納
2. **`SaleCalendarScreen`**: `stringResource(R.string...)` を
   `pluralStringResource(R.plurals.sale_calendar_days_until, n, n)` に変更
   (第 2 引数 = 数量選択、第 3 引数 = `%d` への埋め込み)。

### 検証

- i18n パリティテスト **3 passed** を維持。
  parity test は `<string>` 要素のみ走査する実装なので、4 ロケール一斉に
  `<plurals>` 化してもキー集合の整合は崩れない (string から消え plurals は非対象)。
- 残存 `<string name="sale_calendar_days_until">` が無いこと、4 ロケール全てに
  `<plurals>` が在ることを grep で確認。

### 一般教訓

数を含む UI 文言は **`%d` 直接埋め込みではなく `<plurals>`** が原則。
日本語主体で開発すると「1 件」「2 件」が同形のため見落とすが、英語ロケールで
「1 days」「1 items」のような非文法表現が露出する。`plurals` の利点は
**翻訳者が言語ごとの単複ルール (CLDR) に従って quantity を増やせる**こと。
日本語のみ `other` で済むからといって他言語まで単数形を諦める必要はない。

---

## 製品改善ループ (Tier 66: Qiita/Zenn 外部知見の適用 — LazyColumn 重複キーによる潜在クラッシュを防御 — 2026-06-21)

### きっかけ (Qiita/Zenn の Room・LazyColumn 記事)

- 「`LazyColumn` の `key` は一意でなければならない。重複すると差分計算が壊れる
  (実際には `IllegalArgumentException` でクラッシュする)」
  (zenn/qiita: LazyColumn の key 指定とアニメーション)
- 「Room を `allowMainThreadQueries()` でメインスレッド実行するのは ANR の元。
  DAO は suspend/Flow で IO に逃がす」 (qiita: Room の基本と tips)

### Popcoon への監査結果

- **Room メインスレッド**: `grep 'allowMainThreadQueries'` → **0 件**。
  全 DAO が `suspend`/`Flow` で、`PriceSyncWorker`/ViewModel から coroutine 経由
  → メインスレッドブロックなし ✓
- **LazyColumn の key 一意性**: ここで**潜在クラッシュ**を発見。

### 発見した問題

`SearchScreen` の `LazyColumn` は `key = { it.product.key }` (`product.key = "platform:sku"`)。
結果リスト `rows` は `ProductMatcher.groupByIdentity(products)` の各グループから 1 行を作る。
`groupByIdentity` は **NFKC 正規化したタイトル類似性**で束ねるため、
**同一 `platform:sku` でもタイトルが異なると別グループに分かれ**、同じ `product.key` の
行が 2 つ生成されうる (EC API の重複レスポンス・ページ重複・同一 SKU の表記揺れ等)。
→ `LazyColumn` が重複 key を検出して **`IllegalArgumentException` でクラッシュ**する。
検索は最高頻度の操作なので、稀でも発生すればユーザー影響が大きい。

### 適用した変更

1. **`SearchViewModel`**: UI に出す直前で `rows.distinctBy { it.product.key }` を適用。
   key を構造的に一意化し、上流 (API / groupByIdentity) の挙動に依存せずクラッシュを防ぐ。
   グループは実質最安値順なので「先頭 (=最安値) を残す」`distinctBy` の挙動が妥当。
2. **`SearchViewModelTest`**: 同一 `platform:sku`・異タイトルの 2 件を流し、
   `Results.items` の key に重複がないことを検証する回帰テストを追加。

### 一般教訓

`LazyColumn` の `key` には「**安定**かつ**一意**」の二条件が必要 (Tier 59 で安定性、
本 Tier で一意性)。`key` を「ドメイン上一意なはずの値」(SKU 等) から導出するとき、
**「本当に表示リスト内で重複しないか?」を上流の結合・グループ化処理まで遡って**
確認する必要がある。防御的 `distinctBy` は 1 行で潜在クラッシュを構造的に消せるため、
外部データ由来のリストでは費用対効果が高い。

---

## 製品改善ループ (Tier 65: Qiita/Zenn 外部知見の適用 — edge-to-edge でメイン画面がステータスバー下に潜る — 2026-06-21)

### きっかけ (Qiita/Zenn の edge-to-edge・テーマ記事)

- 「targetSdk 35 では Android 15 で edge-to-edge が**強制適用**。
  `Modifier.safeDrawingPadding()` (= `windowInsetsPadding(WindowInsets.safeDrawing)`)
  でステータスバー/ナビゲーションバーとの重なりを防ぐ」
  (qiita: 対象 API レベル 35 で初めて edge-to-edge に対処する[Compose編])
- 「`isSystemInDarkTheme()` + `dynamic*ColorScheme()` でダーク/Material You 対応」
  (qiita: ダークモード完全対応ガイド)

### Popcoon への監査結果

- **ダークモード/動的カラー**: `PopcoonTheme` は `isSystemInDarkTheme()` で
  Light/Dark を自動切替、`dynamicLightColorScheme`/`dynamicDarkColorScheme` も実装済み
  (既定 OFF = ブランド一貫性優先)。Android 12+ で Material You 対応可能 ✓
- **edge-to-edge**: `MainActivity` が `enableEdgeToEdge()` を呼び、`build.gradle` は
  `targetSdk = 35`。→ **強制 edge-to-edge**。ここで insets 未処理の画面を発見。

### 発見した問題

`MainWithTabs` の `Scaffold` は `topBar = Column { OfflineBanner() }` (= **status bar
inset を処理しない素の Column**)。Material3 Scaffold は topBar が存在すると
「上端 inset は topBar が消費する」前提で innerPadding.top = topBar 高さとするため、
オンライン時 (OfflineBanner 高さ 0) は **innerPadding.top ≈ 0** となる。

その結果:
- **`SearchScreen`** (起動時メイン・最高頻度画面): 自前 TopAppBar を持たず
  `Column(fillMaxSize().padding)` で始まるため、**検索バーがステータスバー (時計/電池)
  の下に潜り込む**。
- **`OnboardingScreen`** (初回起動の第一印象): full-screen root で、
  **「スキップ」ボタンがステータスバーと重なる**。
- 一方 `Detail`/`Watchlist`/`Settings`/`Customs`/`SaleCalendar` は**各自 Scaffold +
  TopAppBar** を持ち、Material3 TopAppBar が `WindowInsets.statusBars` を自動処理する
  ため**正常** ✓ (これらに inset を足すと二重 padding になるので触らない)。

### 適用した変更

自前 TopAppBar を持たない 2 画面にだけ inset 処理を追加 (外側 Scaffold や
正常な画面には手を入れない、最小差分):
1. **`SearchScreen`**: root `Column` に `.statusBarsPadding()` を追加
   (下端のナビバーは外側 Scaffold の `NavigationBar`=bottomBar が処理済みなので上端のみ)。
2. **`OnboardingScreen`**: root `Column` に `.safeDrawingPadding()` を追加
   (full-screen root なので上下両方のシステムバーを回避)。背景 `Surface` は
   `fillMaxSize` のまま = 背景は端まで敷き、コンテンツだけ inset する正しい edge-to-edge。

### 一般教訓

**入れ子 Scaffold + edge-to-edge は inset の二重適用/未適用が起きやすい**。
判定基準: 「その画面は **自前の TopAppBar** を持つか?」
- 持つ → Material3 が status bar inset を自動処理。追加不要。
- 持たない (full-screen root / 素の Column) → `statusBarsPadding()` か
  `safeDrawingPadding()` を明示。
`enableEdgeToEdge()` + targetSdk 35 の組み合わせは「設定した瞬間に全画面へ影響する」
ため、TopAppBar を持たない画面を狙って点検するのが効率的。

---

## 製品改善ループ (Tier 64: Qiita/Zenn 外部知見の適用 — i18n 漏れと Modifier タップ領域の監査 — 2026-06-21)

### きっかけ (Qiita/Zenn の i18n・Modifier 順序記事)

- 「ハードコード文字列は多言語対応の最大の敵。**ユーザーに見える全ての文字列**を
  `strings.xml` に出すのが基本。ViewModel/サービス層からも `context.getString()` で
  参照する」 (qiita: Android アプリを多言語化する、zenn: 文字列をどこに定義するべきか)
- 「`Modifier.clickable().padding()` の順序はタップ領域を狭める。
  `Modifier.padding().clickable()` の順が広いタップ領域を確保する」
  (zenn: JetpackCompose の Modifier の順序について、qiita: 意外と知らない Modifier.clickable)

### Popcoon への監査結果

#### 1. ハードコード日本語文字列 (i18n 漏れ) — `grep '"[ぁ-んァ-ヶ一-龯]"'`

UI 全体を走査した結果、ハードコードはほぼゼロだが **1 件発見**:
- `SettingsViewModel.exportCsv()` の `Intent.createChooser(intent, "CSV を共有")` —
  ユーザー (EN/KO/ZH ロケール) には**「CSV を共有」が日本語のまま**表示されていた。
- `Text("⚠️")`/`Text("⭐")`/`Text("✓")` の絵文字は言語非依存なので問題なし ✓
- `Text("• $w")` のバレットも言語非依存 ✓
- 設定画面の `Text("• " + stringResource(...))` も同じ理由で OK ✓

#### 2. Modifier 順序 — `grep '\.clickable.*\.padding'`

→ **ヒット 0 件**。全クリック可能要素が `padding().clickable()` の正しい順序
(または `Surface` がタップ領域を内包) になっており、タップ領域問題なし ✓

#### 3. 最小タップサイズ (Material3 48dp)

`IconButton` は Material3 が自動で 48dp を確保。`Modifier.size()` 利用箇所も
`IconSize.md/sm` だが SearchSuggestions/OfflineBanner 内のアイコン**装飾**で
タップ対象ではない → 問題なし ✓

### 適用した変更

`SettingsViewModel` の `createChooser` タイトルを 4 ロケール文字列リソース化:
- `R.string.csv_share_chooser_title` を `values`/`values-en`/`values-ko`/`values-zh-rCN`
  に追加 (CSV を共有 / Share CSV / CSV 공유 / 分享CSV)
- `context.getString(R.string.csv_share_chooser_title)` で参照
- i18n パリティテスト 3 passed (4 ロケールのキー/プレースホルダー整合性を維持)

### 一般教訓

i18n 漏れは **ViewModel/サービス層**で発生しやすい:
- Composable では `stringResource()` が単一の経路なので漏れは少ない
- 一方 ViewModel は `context.getString()` を呼ばないとリテラル直書きしがち。
  特に `Intent.createChooser`/`Toast`/`Snackbar` のように Compose 外の API は要注意。

Modifier 順序問題は**「先にレイアウト、後にインタラクション」が原則**
(`size → padding → clickable → background` のような流れ)。
Popcoon はこの原則を全体で守れていた — Material3 の `Surface`/`Button`/`IconButton`
にデフォルト依存することで自然と回避されているのが大きい。

---

## 製品改善ループ (Tier 63: Qiita/Zenn 外部知見の適用 — 越境関税フォームが画面回転で消失 — 2026-06-21)

### きっかけ (Qiita/Zenn の状態保持・ライフサイクル記事)

- 「`rememberSaveable` は UI 一時状態の救世主。回転・プロセスキルでも TextField の値が
  消えないのは Saver が支えている。`remember` は Activity 再生成で状態を失う」
  (qiita: 実務で必須になる rememberSaveable を完全理解する)
- 「`collectAsStateWithLifecycle` は `repeatOnLifecycle(STARTED)` で背景購読を止める。
  `collectAsState` は背景でも collect し続ける」
  (qiita: collectAsStateWithLifecycle が追加されたぞ)

### Popcoon への監査結果

- **Flow 購読**: `grep 'collectAsState()'` → **0 件**。全画面が
  `collectAsStateWithLifecycle` を使用済み ✓ (既にベストプラクティス)
- **検索クエリ**: `SearchScreen` の `query` はローカル `remember` だが、
  `viewModel.currentQuery` (StateFlow) にバックされ、ViewModel が構成変更を生き残るため
  `LaunchedEffect(vmQuery)` で回転後に復元される → **既に回転安全** ✓
- **越境関税フォーム**: ここで回転消失バグを発見。

### 発見した問題

`CustomsSimulatorScreen` は設計上 ViewModel を持たない (「入力を `remember` で純計算する
だけ」)。しかし `foreign`/`shipping`/`japan` (外国価格・送料・国内最安値) と
`categoryIndex` (カテゴリ選択) が全て `remember` のため、**ユーザーが 4 項目を入力した
途中で画面回転すると全消失**する。越境購入の試算は入力項目が多く、回転事故の損失が大きい。

### 適用した変更

`CustomsSimulatorScreen` の入力 4 項目を `rememberSaveable` に変更:
- `foreign`/`shipping`/`japan` (String) と `categoryIndex` (Int) は Bundle に
  直接保存可能なため**カスタム Saver 不要**でそのまま置換。
- `categoryExpanded` (ドロップダウン開閉) は一過性 UI 状態なので `remember` のまま据え置き
  (回転時に閉じてよい)。

### 一般教訓

`remember` と `rememberSaveable` の使い分けは「**Activity 再生成 (回転/プロセスキル) を
越えて保持すべきユーザー入力か、一過性の UI 状態か**」で決まる。
ViewModel を持たない純計算画面 (SaleCalendar/Customs のような軽量画面) は
特に見落としやすい — ViewModel があれば状態がそこに退避されるが、無い画面では
`rememberSaveable` が唯一の防衛線になる。逆にダイアログ開閉・ドロップダウン展開は
`remember` でよい (回転でリセットされて困らない)。

---

## 製品改善ループ (Tier 62: ソクラテス式 + 外部知見 — Coil 最適化 ImageLoader が配線されず死蔵 — 2026-06-21)

### きっかけ (Qiita/Zenn の Coil/Ktor リソース記事)

Qiita/Zenn の画像読み込み・HTTP クライアント記事を調査:
- 「Coil の ImageLoader はメモリ/ディスクキャッシュを明示設定すべき。
  カスタム ImageLoader は Application で singleton 化するのが定石」
  (zenn: Coil を3系に上げたときのメモ、qiita: ImageLoaderとCompose)
- 「Ktor HttpClient を毎リクエスト生成するとリーク。singleton 化して使い回す」
  (qiita: ktor で httpclient を作ったら 2回目以降エラー)

### Popcoon への監査結果

- **Ktor HttpClient**: 7 クラス (BackendClient/BuyingAdvisor/PrivacyCrashReporter/
  FallbackScraper/AmazonPaApiClient/RakutenClient/YahooClient) が各 1 個を
  `private val client = HttpClient { }` として保持。**毎リクエスト生成ではなく
  クラス単位 singleton** なのでリークなし ✓ (プロセス寿命と一致、close 不要)。
- **Coil ImageLoader**: ここで **死蔵設定**を発見。

### 発見した問題 (ソクラテス式: 「この設定は本当に効いているか?」)

`CoilImageLoaderModule` は `@Singleton ImageLoader` を Hilt で提供し、docstring で
「デフォルト Coil の問題 (メモリ RAM 25% で OOM リスク / ディスクキャッシュ未設定で
毎回ネット取得 / クロスフェードなし)」を**修正する**と宣言している。
チューニング内容: メモリ 50MB + ディスク 200MB + crossfade 200ms + OkHttp timeout。

しかし「この ImageLoader を誰が使うのか?」を問うと:
- `grep 'ImageLoader' app/src/main` (module 除く) → **注入箇所 0 件**
- `PopcoonApp` は `coil3.SingletonImageLoader.Factory` を**未実装**
- `ProductImage` の `SubcomposeAsyncImage(model = ...)` は `imageLoader` 引数**なし**

→ Coil3 は `imageLoader` 未指定時、`SingletonImageLoader.get(context)` で**既定の
ImageLoader を生成**して使う。Hilt が作った最適化版は誰も要求しないため
**インスタンス化すらされない** (Hilt provider は遅延)。
結果、docstring が「修正する」と謳う問題が**そっくりそのまま残存**していた
(RAM 25% メモリキャッシュ・ディスクキャッシュなし・チカチカ)。

### 適用した変更

**`PopcoonApp`** に `coil3.SingletonImageLoader.Factory` を実装:
```kotlin
class PopcoonApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var imageLoader: ImageLoader
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
```
- `@Inject` で Hilt の最適化 ImageLoader を受け取り (これで初めて instantiate される)
- Coil3 のグローバル singleton として供給 → **全** `AsyncImage`/`SubcomposeAsyncImage`
  呼び出し (現在の `ProductImage` + 将来の追加分) が自動的に最適化版を使う。
- 各 call site に `imageLoader =` を渡す必要がない (1 箇所の配線で全画面に効く)。

### 一般教訓

Tier 57/58 の「呼び出し元がない死蔵メソッド」と同型だが、本件はより巧妙:
**設定オブジェクトは存在し・DI に登録され・詳細な docstring まである**のに、
フレームワーク (Coil) が要求する**配線フック (Factory) が欠けている**ため無効。
「`@Provides` した = 使われる」ではない。**「誰がこの provider を inject するのか?」**
を問わないと、DI コンテナ内で誰にも要求されないまま眠る。
ライブラリ固有の singleton 差し替え機構 (Coil の `SingletonImageLoader.Factory`、
WorkManager の `Configuration.Provider` 等) は「実装し忘れても無言で既定動作する」
ため、特に発見が遅れやすい。

---

## 製品改善ループ (Tier 61: Qiita/Zenn 外部知見の適用 — クリック可能カードの TalkBack merge — 2026-06-21)

### きっかけ (外部リサーチ第 3 ラウンド)

Qiita/Zenn のアクセシビリティ・ナビゲーション・null 安全記事を調査:
- 「クリック可能なコンテナは `mergeDescendants` で 1 フォーカスにまとめる」
  (qiita: Jetpack Compose における TalkBack 対応)
- 「Navigation Compose は 2.8.0 で型安全に。`@Serializable` route が推奨」
  (zenn: Jetpack Compose Navigation で実現する型安全なルーティング)
- 「`!!` ではなく `requireNotNull`/`checkNotNull`」
  (zenn: Kotlin での !! と requireNotNull の使い分け)

### Popcoon への監査結果

- **null 安全**: `grep '!!' app/src/main` → **ヒット 0 件** (Tier 51 で掃討済み) ✓
- **型安全ナビゲーション**: `PopcoonNavGraph` は文字列 route (`"detail/{productKey}"` 等)。
  2.8.0 の `@Serializable` route 化は **8 画面 + 全 Screen 引数の横断リファクタ**で、
  ローカルでコンパイル検証できない本環境ではリスクが高い → **バックログ**化 (後述)。
- **TalkBack merge**: `grep 'mergeDescendants'` → `PricePredictionCard` の 1 箇所のみ。
  一方、**LazyColumn の主要クリック対象である `ProductRow`・`WatchlistRow` が未 merge**。

### 発見した問題

`ProductRow` (検索結果) と `WatchlistRow` (ウォッチリスト) はいずれも
`combinedClickable`/`clickable` な `Surface` 1 つで「カード = 1 タップ対象」だが、
`mergeDescendants` が無いため TalkBack はカード内の各子要素
(商品画像・プラットフォームチップ・Verdict バッジ・価格・実質価格・タイトル・
名寄せバッジ・警告チップ) で**個別にフォーカス停止**する。
晴眼者には 1 タップのカードが、TalkBack ユーザーには 6〜8 回スワイプが必要になり、
アクセシビリティガイドライン (意味的にまとまった操作対象は 1 ノード) に反する。

### 適用した変更

1. **`ProductRow.kt`**: clickable `Surface` に `.semantics(mergeDescendants = true) {}` を追加。
   子の `contentDescription` (価格 a11y ラベル・警告 a11y) が順に連結され、
   「Amazon、買い時 85点、¥29,800、[タイトル]、…」と 1 回で読み上げられる。

2. **`WatchlistScreen.kt`**: `WatchlistRow` の clickable `Surface` に同様に追加。
   入れ子の `TargetPriceChip`/`StockAlertChip` は自前のクリックアクションを持つため
   Compose の merge 規則で**境界**となり、別フォーカスのまま残る (カード本体だけ merge)。
   → カード本体 1 スワイプ + 各チップ 1 操作、の理想的な TalkBack 構造になる。

### バックログ化した項目 (型安全ナビゲーション)

Navigation Compose 2.8.0+ の `@Serializable` route 移行は、ランタイムの route 文字列
パースミスを排除し IDE リファクタを効かせる正当な改善だが:
- `PopcoonNavGraph` の 8 route + `navigateToDetail` 拡張 + 各 Screen の引数を横断変更
- 本環境は Android SDK 不在でコンパイル検証不可、CI が唯一の検証経路
→ コンパイル不能な大規模変更は回帰リスクが高いため、CI 有効化後の専用 Tier に委ねる。

### 一般教訓

アクセシビリティは「`contentDescription` を付ける」で終わりではない。
**「TalkBack でカードを操作したとき、フォーカスが何回止まるか」**という
操作回数の観点が抜けやすい。クリック可能なコンテナは原則 merge、
ただし入れ子の操作要素 (チップ/ボタン) は境界として残す、が定石。

---

## 製品改善ループ (Tier 60: Qiita/Zenn 外部知見の適用 — 死蔵 API + 未使用 import の整理 — 2026-06-21)

### きっかけ (外部リサーチ第 2 ラウンド)

Qiita/Zenn のコルーチン・Room・セキュリティ・WorkManager 関連記事を調査:
- 「`try { async {} }` は罠 — `async` 内部に try を入れるべき」
  (qiita: Kotlin Coroutinesパターン＆アンチパターン)
- 「ProGuard/R8 の過剰 keep ルールは shrink を無効化する」
  (qiita: R8 設定、見直してますか？)
- 「`EncryptedSharedPreferences` より DataStore + Tink」
  (zenn: DataStore + tink で暗号化)
- 「`HiltWorker` + `@AssistedInject` の正しい連携」
  (qiita: WorkManager で Hilt を使う)

### Popcoon への監査結果

各項目を grep で検証:
- **コルーチン**: `ProductRepository.search()` は `coroutineScope { async { try {} catch {} } }`
  — try が async **内部**にあり、罠を回避済み ✓
- **R8 ルール**: `-keep class io.ktor.** { *; }` 等の過剰 keep が複数あるが、
  実際に削っても安全か CI でビルド検証する必要があり今回はスコープ外
  (将来の Tier で R8 metrics を取った上で個別に検討)
- **DataStore 暗号化**: `UserPreferences` が保存するのは boolean/int フラグのみで
  PII やシークレットを保存しない → 暗号化不要 ✓
- **HiltWorker**: `PriceSyncWorker` / `WeeklyDigestWorker` 共に `@HiltWorker` +
  `@AssistedInject` の正規パターン ✓

→ コルーチン・WorkManager 層は健全。しかし監査の副産物として **2 件の死蔵コード**
を発見した。

### 発見した死蔵コード

#### 1. `BackendClient.deleteAllData(deviceToken: String): Boolean`

Tier 56 で `SettingsViewModel.deleteAllData()` から「サーバー側削除」を撤回した際、
`BackendClient` 側のエンドポイントを呼び出すメソッドが**取り残されたまま**だった。
`grep -r 'backend\.deleteAllData\|BackendClient\(\)\.deleteAllData'` でヒット 0 件。
プライバシーファースト設計でデバイス識別子を持たない以上、`deviceToken` を渡せる
呼び出し側は永久に存在しない (= 不到達コード)。

#### 2. `ProductRow.kt` の 3 つの未使用 import

`androidx.compose.material.icons.Icons` / `Icons.filled.Share` / `Icons.filled.Star`
— grep で本文に 1 回も参照されないことを確認。
過去のリファクタリングで使われなくなったが import 文だけ残った典型例。

### 適用した変更

1. **`BackendClient.kt`**:
   - `deleteAllData(deviceToken)` メソッドを削除
   - 未使用になった `delete`/`header` import も削除
   - 設計意図を残すコメントブロックに置換 (Tier 56 への参照付き)

2. **`ProductRow.kt`**:
   - 3 つの未使用 icon import を削除

### 一般教訓

外部知見を**自プロジェクトに当てはめる監査**は、しばしば調査対象とは別の発見をもたらす。
今回はコルーチン/R8 パターンの検証中に、過去の Tier の修正で取り残された API を発見。
Socratic 監査 (Tier 56-58) と外部知見監査 (Tier 60) は**直交する**改善ベクトルで、
両方を交互に行うのが効率的。

---

## 製品改善ループ (Tier 59: Qiita/Zenn 外部知見の適用 — Compose 安定性設定で不要な再コンポーズを抑制 — 2026-06-21)

### きっかけ (外部リサーチ)

Qiita/Zenn の Jetpack Compose パフォーマンス記事を調査。繰り返し挙がる最重要論点:
- **「data class が `List`/`Map` を 1 つでも持つと unstable と推論され、その引数を取る
  Composable は skippable にならない」** (zenn.dev/yasi: LazyColumn パフォーマンス 3 箇条、
  zenn.dev/aokiti: derivedStateOf、Android 公式アーキテクチャガイド)。
- unstable 引数の Composable は、親が再コンポーズするたびに無条件再実行される。
  特に `LazyColumn` のアイテムでは、スクロールのたびに全行が再構築されうる。

### 監査結果 (Popcoon への当てはめ)

`grep -rn "@Immutable\|@Stable"` → **コードベース全体でヒット 0 件**。
一方、Composable に渡るドメインモデルの多くが `List`/`Map` を保持:
- `SearchRow` (`warnings: List<String>`, `alternatives: List<Product>`) — **LazyColumn のホットパス**
- `BuyTimingScorer.Score` (`signals: List<Signal>`)
- `SmartCartService.SmartCartResult` (`cartItems: List<CartItem>`)
- `CrossMallCartOptimizer.CartItem/Result` (`Map<String,Double>` 等)
- `PointSimulator.Result` (`breakdown: List<PointSource>`)
→ いずれも unstable 推論となり、対応する Composable が毎回再実行されていた。

### 選択肢と判断

- **A. 各 data class に `@Immutable` を付与** — 確実だが、`feature/`・`data/model` の
  「Android 非依存の純関数モデル」に `androidx.compose.runtime` を import させ、
  Compose への結合を生む (設計原則に反する)。
- **B. Compose コンパイラの安定性設定ファイル** ← **採用**
  `composeCompiler { stabilityConfigurationFile = ... }` (Kotlin 2.0.21 + compose plugin)。
  ソースを一切変更せず・ドメインモデルの純粋性を保ったまま安定性を宣言できる。
  Android 公式が推奨する「外部/自社の不変クラスを安定化する」正攻法。

### 適用した変更

1. **`compose_stability.conf`** 新設 — 全プロパティ `val`・深く不変であることを
   コードレビューで確認した 17 クラス + `java.time.LocalDate`/`Instant` を列挙。
   可変クラス (`AdviceCache`/`Trie`/`BillingManager` 等) は意図的に除外。

2. **`app/build.gradle.kts`** に `composeCompiler {}` ブロックを追加し設定ファイルを配線。

### 安全性

安定宣言は「このクラスのインスタンスは内容が変わらない」という対コンパイラの約束。
列挙クラスは全て pure function の出力で、構築後に変更されない (List/Map も再代入されない)
ため約束は成立する。**可変クラスを誤って宣言すると「変わったのに再コンポーズされない」
= 古い UI バグになる**ため、各クラスの定義を個別に確認し、確実に不変なものだけを列挙した。

### 検証

- ローカルは Android SDK 不在でコンパイル不可 → CI が唯一の検証経路。
- パフォーマンス効果は Compose コンパイラの metrics/reports
  (`-P plugin:androidx.compose.compiler...:metricsDestination`) で
  skippable 化を定量確認可能 (本変更には未同梱、必要時に有効化)。

### 一般教訓

外部コミュニティ知見 (Qiita/Zenn) の「List/Map で unstable」は、機能の死蔵とは別軸の
**「動いてはいるが非効率」**な改善余地。`grep "@Immutable"` が 0 件のプロジェクトは
ほぼ確実にこの最適化余地を持つ。設定ファイル方式はドメイン層の純粋性を犠牲にせず
適用できるため、レイヤ分離を重視するコードベースで特に有効。

---

## 製品改善ループ (Tier 58: ソクラテス式 — ReviewPrompter.requestIfEligible() の呼び出し元が存在しない — 2026-06-20)

### ソクラテス式問答 (「成功イベントは記録しているのに、レビューダイアログは誰が開く?」)

`ReviewPrompter` には 2 つの責務がある:
1. `recordSuccess()` — 成功イベントを DataStore に積算
2. `requestIfEligible(activity)` — 積算数が閾値 (5回) を超え 90日クールダウン外なら
   Google Play In-App Review フローを起動

`PriceSyncWorker` は値下がりがあるたびに `recordSuccess()` を呼んでいる ✓  
しかし `requestIfEligible()` を呼ぶ箇所が**コードベース全体に 1 行も存在しない** ✗

結果: ユーザーが何百件の価格通知を受け取っても、Play レビューダイアログは
一度も表示されない。`MIN_SUCCESS_COUNT = 5` / `COOLDOWN_MS = 90日` という
精密なロジックと 8 spec のテストが完全に死蔵されている。

さらに docstring は 4 つの成功イベントを列挙しているが、
`toggleWatchlist` (ウォッチリスト追加) では `recordSuccess()` 自体も呼ばれていない:
> 商品詳細を開いて10秒以上滞在 / **watchlist に追加** / dark pattern「待ち」/ AI advice「役立った」

### 選択肢と判断

- **A. `requestIfEligible()` を削除** — 機能を諦める。Google Play ランキング上の
  高評価レビューは有機的な ASO 向上手段として有効。諦めるのは機会損失。
- **B. `ProductDetailScreen` のウォッチリスト追加に配線** ← **採用**  
  「商品を保存する」はユーザーが Popcoon に価値を見出した最強の成功シグナル。
  Activity 参照が必要なため Screen から ViewModel メソッド経由で呼ぶパターンは
  `SettingsViewModel.launchPurchase(activity)` と同一。

### 適用した変更

1. **`ProductDetailViewModel`**:
   - `ReviewPrompter` を constructor injection に追加 (Hilt Singleton 既定)
   - `requestReviewIfEligible(activity: Activity)` を新設:
     `recordSuccess()` → `requestIfEligible(activity)` を順に呼ぶ

2. **`ProductDetailScreen`**:
   - `import android.app.Activity` を追加
   - ウォッチリストボタン `onClick` で `!cur.isInWatchlist` (= 追加操作) のとき
     `viewModel.requestReviewIfEligible(context as? Activity)` を呼ぶ
   - `null` チェックで Activity 以外のコンテキスト (Preview 等) では noop

### 補足: `requestIfEligible` の冪等性

`shouldRequestNow` のエッジトリガロジック (閾値 + クールダウン) により、
頻繁にウォッチリスト追加しても Play 側の quota (ユーザーあたり年数回の制限) を
超えない。`ReviewPrompterLogicTest` 8 spec が境界条件 (≤ 90日で false) を
識別テストとして保護済み。

### 一般教訓

「ロジックが complete で テストが green なら機能は動く」ではない。
**呼び出し元 (caller) が存在しない iff 機能は存在しない** (Halting Problem の逆)。
`requestXxx()` / `launchXxx()` / `sendXxx()` 命名のメソッドは
「誰が呼ぶか」のコードレビューが必須。

---

## 製品改善ループ (Tier 57: ソクラテス式 — 週次ダイジェスト通知チャンネルが存在するのに送信 Worker が存在しない — 2026-06-20)

### ソクラテス式問答 (「設備は整っているのに誰も動かしていない?」)

`LocalNotificationManager.sendWeeklyDigest()` / `PopcoonApp.CHANNEL_WEEKLY_DIGEST` / 4ロケールの
`channel_weekly_digest_*` & `notif_weekly_digest_title` が全て揃っている。
では「誰がこれを呼ぶのか?」→ **誰もいない**。

`grep -r "sendWeeklyDigest" app/src/main` で唯一の出力は定義元のみ。
`*Worker*.kt` を列挙すると `PriceSyncWorker` 1件しかなく、週次ダイジェストの
スケジューラーが存在しない。

つまり:
- 通知チャンネル「週刊まとめ / Weekly digest / 주간 요약 / 每周摘要」はユーザーの通知設定に表示されるが、
  **一度も通知が届くことはない** (チャンネルが存在するだけ)。
- `notif_weekly_digest_title` も 4ロケール全てで定義されているが参照されるのは
  `LocalNotificationManager.sendWeeklyDigest()` からのみで、そこ自身が呼ばれない。

### 選択肢と判断

- **A. チャンネルと文字列を削除** — 実装が存在しないのでクリーンにはなる。
  が、「通知設定に見えている機能を削除」はユーザーが混乱する可能性がある (既にインストール済みのデバイスで)。
  また機能そのものは有用 (週次で「何件値下がりした」を知らせる = engagementアップ)。
- **B. `WeeklyDigestWorker` を実装して配線** — 約束を履行する。
  Worker のロジックは端末内データのみ (`WatchlistItem.realPrice` vs `addedPrice`) で
  完結するためネットワーク不要・シンプル。← **採用**

### 適用した変更

1. **`WeeklyDigestWorker.kt`** 新設:
   - 7日ごとに実行 (ネットワーク不要、バッテリー低下時はスキップ)
   - `addedPrice > 0 && realPrice < addedPrice` を満たす商品を「値下がり中」として集計
   - `dropCountFrom(List<Pair<Long,Long>>): Int` を pure companion function として切り出し
     (テスト可能・Context 非依存)
   - `notificationManager.sendWeeklyDigest(context, summary)` を呼び出して通知を発行
   - `WORK_NAME = "weekly_digest"` を識別子として `enqueueUniquePeriodicWork`

2. **`MainActivity.kt`** に `WeeklyDigestWorker.schedule(applicationContext)` を追加
   (`PriceSyncWorker.schedule()` と並べてアプリ起動時に一度スケジュール)

3. **文字列 `notif_weekly_digest_body`** を 4ロケール全てに追加:
   - JA: 「ウォッチリスト%1$d件中%2$d件が値下がり中。アプリで最新価格を確認しましょう。」
   - EN: 「%2$d of your %1$d watchlist items dropped in price. Open the app for details.」
   - KO: 「관심 상품 %1$d개 중 %2$d개 가격이 내렸습니다. 앱에서 최신 가격을 확인하세요.」
   - ZH: 「关注清单%1$d件中有%2$d件价格下降。请打开应用查看最新价格。」

4. **`WeeklyDigestWorkerTest.kt`** 新設: `dropCountFrom` のケースを 7 spec で網羅
   (値下がり/値上がり/同値/基準なし/混在/空/WORK_NAME 固定)

### 一般教訓

「チャンネル登録 + 文字列定義 = 機能完成」ではない。
**配線 (caller) が存在しない delivery pipeline は、どれだけ内部実装が整っていても
ユーザーには届かない。** 通知機能は特に「チャンネルが通知設定に見えるのに通知が来ない」
という UX 違和感を生む。コードレビューで「この method/channel を呼ぶ経路はあるか?」
を問うことが有効。

---

## 製品改善ループ (Tier 56: ソクラテス式 — GDPR 全削除が UI の約束 [サーバー側削除] を果たしていなかった — 2026-06-20)

### ソクラテス式問答 (「注入された依存は実際に使われているか?」)

Tier 55 で backend 連携を追ったついでに問うた: **「`SettingsViewModel` は `BackendClient` を
何のために注入し、実際に呼んでいるか?」**

- 問: `deleteAllData()` は何を削除するか?
  → `database.clearAllTables()` + `prefs.clearAll()` = **端末内のみ**。`backend` は呼ばれない。
- 問: では `backend: BackendClient` を注入しているのはなぜか?
  → GDPR サーバー削除のためのはずだが、`deleteAllData` 内で**一度も使われていない死んだ注入**。
- 問: UI は何を約束しているか?
  → `settings_delete_desc` = 「端末内 + サーバー両方を即時削除」、確認本文 =「サーバー側の関連
    データを完全削除する」、ラベル =「GDPR Article 17」。**サーバー削除を明示的に約束**している。
- 問: そもそも削除すべきサーバー側の個人データは存在するか?
  → **存在しない。** アプリはデバイス識別子を一切持たない。backend に送るのは商品キー単位の
    匿名・共有価格履歴 (特定個人に紐づかない) と、PII 除去済み・デバイス非紐付けのクラッシュ
    レポート (90日 TTL) のみ。`BackendClient.deleteAllData(deviceToken)` は存在するが、渡すべき
    トークンが生成されたことがない。
- 結論: プライバシーを売りにするアプリ (「テレメトリ未送信」が差別化) が、果たせない
  サーバー削除を UI で約束していた = コンプライアンス上の齟齬かつユーザーへの虚偽表示。

### 製品判断 (ユーザー: おまかせ → privacy-first を最優先)

二つの正直な修正は**プライバシー上の含意が正反対**だったため確認した:
- 案 A: 削除のためだけにデバイストークンを導入し、文字通りサーバー削除を実装 →
  **per-user サーバー追跡の新規導入 = プライバシー後退**。製品の核 (zero telemetry) に反する。
- 案 B (採用): アプリは個人を特定するサーバーデータを持たない事実を UI に正直に反映する。

製品の核アイデンティティ (privacy-first) を守る案 B を採用。文言を正確にすることは
zero-telemetry の売りを**強化**もする。

### 適用した改善 (commit 97b5309)

- 4 ロケールの `settings_delete_desc` / `settings_delete_confirm_body` を
  「端末内の全データを完全削除（個人を特定するデータはサーバーに送信していません）」相当に修正。
- `SettingsViewModel` から死んだ `BackendClient` 注入を削除。`deleteAllData()` に
  「設計上サーバー側に削除対象の個人データが無い (デバイス識別子を持たない)」理由を docstring 化。
- **検証**: i18n parity 3 passed (キー/プレースホルダ不変)、4 ロケール XML well-formed。

### 一般教訓 (UI の約束と実装の齟齬)

「注入されているが使われていない依存」は、実装されなかった機能の化石であることが多い。
特に**UI 文言が約束する動作**と実装が乖離していないかを問う。privacy/GDPR のような
コンプライアンス文言は、誇大でも過小でもなく**実態と一致**していなければならない。
実態に合わせて文言を正す方が、文言に合わせて (プライバシーを損なう) 機能を足すより正しいことがある。

## 製品改善ループ (Tier 55: ソクラテス式 — PriceRecord の wire format 不一致 [価格履歴パイプラインが無音で死んでいた] — 2026-06-20)

### ソクラテス式問答 (「往復テストは wire format を検証しているか?」)

Tier 54 で `PriceSyncWorker` が `getPriceHistory` から `history.first()` を latest として
使うことを確認した。そこで次を問うた: **「`getPriceHistory` は実際に履歴を返せるのか?」**

- 問: `PriceRecord` は backend (Cloudflare Workers) と JSON でやり取りする。backend の契約は?
  → `interface PriceRecord { product_key; list_price; real_price; recorded_at }` = **snake_case**。
- 問: Kotlin の `PriceRecord` はどのキー名で直列化するか?
  → `@SerialName` は `recorded_at` にしか付いておらず、`productKey` / `listPrice` / `realPrice` は
    **camelCase のまま**直列化される。
- 問: ならば POST /v1/history は通るか?
  → **通らない。** backend は `body.product_key` が無いと `bad("invalid payload")` で 400。
    `postPricesAsync` の全 POST が拒否され、価格履歴が一度も蓄積されていなかった。
- 問: GET /v1/history のレスポンス (snake_case JSON) を Kotlin はデシリアライズできるか?
  → **できない。** `productKey` フィールドが JSON に無いため `MissingFieldException`。
    `BackendClient.getPriceHistory` の `runCatching` がこれを握り潰して `emptyList()` を返す。
- 結論: 価格履歴パイプライン全体が **wire 上で一度も機能していなかった**。
  `PriceSyncWorker` は常に空履歴を見る → 値下がり・目標到達アラートが**永遠に発火しない**。
  `BuyTimingScorer` / `PricePredictionEngine` も履歴ゼロで動いていた。backend の存在理由
  (「全ユーザー共有 → 予測精度向上」) が達成されていなかった。

### 発見の核 (往復テストの盲点)

`PriceRecordSerializationTest` は存在したが **Kotlin→JSON→Kotlin の往復**しか見ていなかった。
往復は encode と decode が同じ (誤った) キー名を使う限り**フォーマットが間違っていても成立する**。
これは Tier 45「検証の演劇性」の系: テストはあったが、このクラスが満たすべき契約
(backend との snake_case 一致) を**一度も検証していなかった**。

### 適用した改善 (commit 279d8f9)

- `PriceRecord` の `productKey` / `listPrice` / `realPrice` に `@SerialName("product_key")` 等を付与。
- `PriceRecordSerializationTest` に**識別力のある wire format テスト**を追加:
  - エンコード結果が `"product_key"` / `"list_price"` / `"real_price"` を含む (snake_case 契約)。
  - エンコード結果に `"productKey"` 等の camelCase が**出ない** (旧フォーマットでない)。
  - backend が返す snake_case JSON をデシリアライズして元の値に一致する (GET 経路)。
  - これらは `@SerialName` を差し戻すと**落ちる** (往復テストと違い識別的)。
- **検証**: Python で backend バリデーション (camelCase→400 / snake_case→OK) と snake_case GET
  レスポンスのパースを再現。Python オラクル 394 passed、parity harness 全緑。

### 一般教訓 (プロデューサ/コンシューマ契約テストの本質)

外部システムと JSON をやり取りする型は、**往復テストでなく wire format テスト**で守る。
往復 (encode→decode) は型の内部一貫性しか見ず、相手システムが期待するキー名・形式との
**界面の契約**を検証しない。「相手が送る生の JSON をデシリアライズできるか」「自分が送る生の
JSON が相手の要求キーを含むか」を、文字列リテラルで固定する。Tier 52 (JSON-LD の複数形式)
と同根: 界面の両側が同じ契約を共有しているかを、実際のフォーマットで照合する。

## 製品改善ループ (Tier 54: PriceSyncWorker 競合状態 + エッジトリガ後継テスト失敗 — 2026-06-20)

### ソクラテス式問答 (「修正後の Worker が修正を正しく使っているか?」)

Tier 53 でエッジトリガ化した `PriceAlertEvaluator` が正しく機能するには、
`PriceSyncWorker` が `previousPrice` と `targetPrice` を正確に渡すことが前提。
本 Tier はその配線を問う。

**問: `previousPrice = item.realPrice` が「前回同期価格」として正しいか?**
→ **正しい。** `item` は同期開始時の DB スナップショット。`updatePrice()` は後で呼ぶため、
  `previousPrice` は今回同期前の価格を確実に捕捉している。

**問: では `targetPrice = item.targetPrice` が正しく伝わるか?**
→ **設計上は正しいが、競合状態がある。** Worker は `observeAll().first()` でスナップショットを
  取得した後、`watchlistDao.upsert(item.copy(realPrice = ...))` で全フィールドを書き戻していた。
  もしユーザーが `setTargetPrice(key, 4000)` を Worker の取得〜書き戻しの間に呼ぶと、
  Room の `OnConflictStrategy.REPLACE` は旧スナップショット (`targetPrice = null`) で
  行を差し替え、**ユーザーの設定した目標価格を黙って消去する。**

### 適用した改善 (commit 4dee62f)

1. **競合状態修正**: `watchlistDao.upsert(item.copy(realPrice = latest.realPrice))` →
   `watchlistDao.updatePrice(item.productKey, latest.realPrice)` に変更。
   `updatePrice` は `UPDATE watchlist SET realPrice = :price WHERE productKey = :key` の
   単一カラム更新であり、`targetPrice` / `addedPrice` を一切触らない。
   DAO にはこの目的のために既に `updatePrice` が用意されており、docstring も
   「upsert の全フィールド書き換えを避け addedPrice を保全する」と明記していた。

2. **潜在テスト失敗の修正**: `PriceSyncWorkerLogicTest` に
   `"targetPrice 到達は dropPercent 未満でも TARGET_REACHED (優先)"` というテストが
   `previousPrice = 5000L, targetPrice = 5000L` (両者が等しい) で書かれていた。
   エッジトリガでは `wasAlreadyAtOrBelowTarget = (5000 in 1..5000) = true` となるため
   TARGET_REACHED に到達せず NONE を返す。テストは期待 TARGET_REACHED → 実際 NONE で
   **潜在失敗**状態だった (PriceSyncWorkerLogicTest も `useJUnitPlatform` 環境なので未実行)。
   修正: `previousPrice = 5001L` (目標を 1 円超える = 真の「跨ぎ」) に変更し、
   「`previousPrice == targetPrice` は跨ぎでない」という境界テストを追加。
   Python オラクルで全 6 ケース緑を確認。

### 一般教訓

「修正を適用したら、その修正を使う配線も正しいか」を問う。エッジトリガ化 (Tier 53) は
評価器が正しくなっても、Worker が評価器に正しい値を渡さなければ意味がない。修正は
依存チェーン全体に波及するため「修正の修正」が存在する。今回はたまたま既存配線が
評価器への入力としては正しかった (previousPrice の捕捉) が、DAO 書き戻しで別の問題
(競合状態) を抱えていた。また、Tier 53 の「実行されない潜在失敗」が今 Tier でも再現:
Worker 固有のテストも未実行で、Tier 53 の変更が同テストに与えた影響をチェックしていなかった。

### 恒久対策: エッジトリガの no-SDK 回帰ガードを追加 (commit 4f7d371)

Tier 53/54 の潜在失敗は「Kotest が Android SDK 必須 → CI で未実行 → 緑に見える」が共通の根因。
Tier 53 は一度きりの standalone コンパイルで検証したが**恒久的なガードを残さなかった**。
そこで `run_alerts.sh` + `alerts/PriceAlertEvaluatorCheck.kt` を追加 (既存の no-SDK ハーネス
`run_deeplinks.sh` 等と同じ方式)。本物の `PriceAlertEvaluator.kt` を単体コンパイルし、エッジ
トリガ契約 (跨ぎで TARGET 1 回 / 既に以下は再通知せず / prev==target は跨ぎでない / 更なる下落は
PRICE_DROP / 初回観測は 1 回 / 2000 件の双方向 property) を実行検証する。`run_all.sh` に組み込み、
**SDK 無しで実際に走る CI parity ジョブ**で恒久的に守られるようにした。

識別性を実証: 評価器をレベルトリガに差し戻すとハーネスは 4 件の明示 mismatch + property 失敗を
出して exit 非 0 になる (演劇でなく真の回帰ガード)。これで Tier 53/54 のクラスの潜在失敗は、
Kotest 未実行環境でも `bash popcoon-tdd/kotlin_parity/run_all.sh` で捕捉できる。

## 製品改善ループ (Tier 53: ソクラテス式 — 「テストは本当に実行されたのか?」+ robots クエリ遵守 — 2026-06-17)

### ソクラテス式問答 (検証の演劇性・第二幕: 実行されない緑)

Tier 45 は「アサーションが識別力を持つか」を問うた。本 Tier はさらに根源的な問いに進む:
**「このテストは一度でも実行されたことがあるのか?」** Kotest スイートは Android SDK 必須で、
`useJUnitPlatform` 追加も CI 専用 (本リポジトリの CI は SDK 不在で未稼働)。よって
Kotlin-only 純関数のテストは**一度も走っていない可能性**がある。緑でも赤でもなく「未実行」。

検証手法: Gradle 同梱の Kotlin コンパイラ (`kotlin-compiler-embeddable`、Android SDK 不要) で
本番純関数を単体コンパイルし、各テストの期待値を実コードに突き合わせて実行する
(JSON-LD パリティハーネスと同じ方式を任意の純関数へ一般化)。

### 発見 (コンパイル検証で露見した 2 件の「未実行の潜在失敗」)

1. **`PriceAlertEvaluatorTest` 境界テストが一度も成立していなかった** (commit f9dfb2c):
   `eval(prev=5000, latest=4001, target=4000)` を `NONE` と期待していたが、5000→4001 は約 20%
   下落なので実コードは `PRICE_DROP` を返す。「目標未達 = 無通知」と取り違えた誤期待。
   境界単独検証 (prev=4010 で下落 0%) と、フォールスルー検証 (prev=5000 → PRICE_DROP) に分割。
2. **`ReviewPrompter` の cooldown 境界 off-by-one** (commit 6e2d8f2):
   `ReviewPrompterLogicTest` は「ちょうど 90 日後は false (境界: < ではなく <=)」と**文書化された
   意図**を持つが、実コードは `elapsed < COOLDOWN` で、ちょうど 90 日では `true` を返していた
   (実装が文書化意図と逆)。`<= COOLDOWN` に修正 (保守側 = Google quota 厳守)。両者ともコンパイル
   実行で got/exp を確定させてから修正。

検証して**異常なし**だった純関数 (パリティ harness 非対象だが latent 失敗なし):
`WidgetVerdict` / `StockAlertEvaluator` / `WatchlistPriceDelta` / `ReviewTrustScorer`
(全テストケースをコンパイル実行し一致を確認)。

### robots.txt クエリ標的ルールの遵守 (commit ab5a6f5)

別のソクラテス問答: **「robots を、実際に GET する URL と同じものに対して照合しているか?」**
`FallbackScraper.fetchProduct` は query 付き URL を GET するのに、robots 照合には `uri.rawPath`
(query 除去) を渡していた。`Disallow: /*?` や `/*?replytocom` のようなクエリ標的ルールを取りこぼし、
サイトが明示的に禁止した URL を取得しうる倫理/仕様逸脱。`RobotsTxt.matches` は元々 query を扱えた
(渡されていなかっただけ)。照合パスを `rawPath + "?" + rawQuery` に修正。standalone コンパイルで
4 アサーション緑を確認。

### 一般教訓

「テストが緑」には 3 状態がある: ①実行されて通った ②実行されて落ちた ③**一度も実行されていない**。
SDK/CI 依存でローカル実行できないテストは ③ に陥りやすい。純関数は依存を切り離して
コンパイラだけで実行検証でき、③ を ① に変えられる。閾値・境界・符号を含むロジックは特に要検証。

### 適用: 目標到達通知をエッジトリガ化 (日次スパム解消) (commit ee05f7a)

`PriceAlertEvaluator` は `latestPrice <= targetPrice` で**毎同期** TARGET_REACHED を返していた
(レベルトリガ)。`PriceSyncWorker` は日次同期 + `setOnlyAlertOnce` 未設定 + 振動パターンのため、
価格が目標以下に留まる限り**毎日同じ通知が振動付きで再発火**する — CamelCamelCamel パリティを
謳う機能の目的と正反対。レベルトリガは明示テスト + docstring + property test で意図的に記述されて
いたため、製品判断を仰いだ上で**エッジトリガ化**を適用 (ユーザー選択: 推奨案)。

- TARGET_REACHED は目標を「上→下」に跨いだ同期のみ発火 (`previousPrice` が目標超、または
  `previousPrice <= 0` の初回観測)。目標以下に留まる間は再通知しない。
- 既に目標以下のまま更に有意下落した場合は PRICE_DROP として拾う (情報は失わない)。
- テストをレベル→エッジ意味論に更新 (跨ぎ / 既に以下 / 更に下落 / 初回観測 + 双方向 property)。
  評価器をコンパイルし全明示ケース + 3000 件のランダム property を実行して検証
  ("ALL EDGE-TRIGGER CASES + PROPERTY MATCH")。

## 製品改善ループ (Tier 52: JSON-LD price/image の形式網羅 — フォールバック商品の値喪失修正 — 2026-06-17)

### 発見 (schema.org の正規な多形式を regex が取りこぼしていた)

`FallbackScraper` は API 失敗時に商品ページの JSON-LD (schema.org) を読むが、
抽出器 `extractJsonLdString` は **引用符付き文字列値しか拾えなかった**。schema.org /
Google 公式仕様では:

- **price は数値表記が正規**: Google の構造化データ例は `"price": 38.99` (引用符なし数値)。
  引用符付き `"price":"1980"` しか拾えないため、数値表記の商品は price 抽出が null →
  `lowPrice` も同様 → `realPrice=0` の壊れた Product が静かに生成されていた。
- **image は配列が一般的**: Amazon/楽天は `"image":["https://a.jpg","https://b.jpg"]`。
  colon 直後に `[` が来る配列に regex がマッチせず、imageUrl が常に null → サムネイル非表示。
- **brand はオブジェクトが一般的**: `"brand":{"@type":"Brand","name":"Sony"}`。
  `:{` のオブジェクトにマッチせず brand が null → ProductMatcher の brand 一致シグナルが死ぬ。

### 適用した改善 (commit 9bb655d / 95449e7 / 1ca271b)

- `extractJsonLdNumber`: colon 直後に引用符が**来ない**数値 (`-?\d+(\.\d+)?`) のみマッチ。
  引用符付き値とは役割分担 (相互に非衝突)。price/lowPrice の数値フォールバックに配線。
- `extractJsonLdArrayFirst`: `key → [ → 先頭の引用符付き要素` を抽出。image の配列フォールバックに配線。
- `extractJsonLdObjectField(outer, inner)`: `outer → { → inner` を同一オブジェクト内 (`[^{}]*?`) で抽出。
  brand.name を拾う (先頭の商品 name と誤認しない)。brand のオブジェクトフォールバックに配線。
- 既存 `extractJsonLdString` は不変 (パリティ維持)。4 関数が排他的に役割分担。
- **検証**: `run_jsonld.sh` パリティハーネス (Android SDK 不要・実関数を直接実行) に
  JSON-LD NUMBER / ARRAY / OBJECT ブロックを追加し緑を確認。`run_all.sh` 全緑、Python 394 passed。

### 一般教訓 (プロデューサ/コンシューマのフォーマット契約)

死んだ継ぎ目シリーズ (Tier 37-38, 55) の系: データソースが**正規に複数フォーマットで**提供する
値 (price=文字列|数値、image=文字列|配列|オブジェクト) を、消費側が単一フォーマット前提で
パースすると、特定フォーマットの商品だけ静かに値が欠落する。schema.org の型ユニオンを
網羅したか常に確認する。

## 製品改善ループ (Tier 51: テスト演劇性の全面掃討 + 生産コードの !! 除去 — 2026-06-17)

### ソクラテス式問答 (Tier 45 の徹底適用: どのアサーションが「常に真」か)

Tier 45 で「検証の演劇性 (test theater)」を発見したが、当時は数件のみ修正していた。
本 Tier では全 54 テストファイルを走査し、**識別力ゼロのアサーション**を体系的に置換した。
演劇性パターンとその問題:

- `(a > b) shouldBe true` — 失敗時に `false != true` としか出ず、a/b の実値が分からない。
  Kotest の型付きマッチャ (`shouldBeGreaterThan` 等) は actual 値をエラーに含める。
- `collection.any { pred } shouldBe true` — 失敗しても「何が入っていたか」が出ない。
  `shouldExist { pred }` は要素一覧をダンプする。
- `collection.all { pred } shouldBe true` — **空コレクションで vacuously true** になる罠。
  `shouldContainOnly` / `shouldNotContain` は空でも正しく失敗する。
- `str.contains(x) shouldBe false` — `shouldNotContain x` に置換 (actual 文字列が出る)。
- `(x in 0..100) shouldBe true` — `shouldBeInRange 0..100` に置換。

### 適用した改善 (本セッション 14 commit)

- **テスト 18 ファイル**で演劇性アサーションを型付きマッチャに置換:
  BuyTimingScorer / EcoEthicsScorer / PricePredictionEngine / TCOCalculator /
  ConformalInterval / WatchlistPriceDelta / DatabaseIntegrity / PopcoonLogger /
  IntegrationTests / DarkPatternDetector(Text) / BundlePackDetector / CustomsSimulator /
  TrieSuggest / BillingManager / SaleCalendar / ProductMatcher / PointSimulator /
  ReviewTrustScorer / UrlClassifier / JanCodeQuery / ApiResult。
- **実バグ修正**: `EcoEthicsScorerTest` の `"非JP + 既知カテゴリ は代替案あり"` が DE
  (CO2 係数 0.30 < JP 0.45) を使い、79 行目の回帰テスト (`DE → null`) と矛盾していた。
  Python オラクルが `green=None` を返す国を「代替案あり」と誤検証していたため CN に修正。
- **Python オラクル値の固定**: BuyTimingScorer total=67 (ALWAYS_ON_DISCOUNT −8 含む)、
  EcoEthics co2=45/25・overall=77/46・エコマーク 45→55、co2Kg=70.0/400.0 等を具体値で固定。
- **生産コードの `!!` 除去**: `CrossMallCartOptimizer` の 3 箇所を `checkNotNull(...) { msg }`
  と `minBy` に置換 (require(isNotEmpty) で保証される不変条件を明示)。生産コードの `!!` ゼロを確認。
- **マジックナンバーの命名**: `EcoEthicsScorer` の `60`/`70` を `SUPPLY_CHAIN_SCORE_DEFAULT` /
  `CIRCULAR_ECONOMY_SCORE_DEFAULT` に抽出 (業界平均プレースホルダの意図をコメント化)。

### 一般教訓

`shouldBe true` は「ブール値を返す関数の直接検証」(`isValidJan13(x) shouldBe true`) にのみ使う。
**式の評価結果**を `shouldBe true` するのは演劇性のサインであり、ほぼ常に型付きマッチャに
置換できる。置換は失敗時の診断情報を増やし、空コレクションの vacuous-pass を塞ぐ。

## 製品改善ループ (Tier 50: BundleCard の unit price 表示バグ修正 + i18n 完全化 — 2026-06-16)

### 発見 (format 引数が無言で捨てられていた)

- `BundleCard.kt` で `stringResource(R.string.bundle_unit_price, CurrencyFormatter.yen(price))` を
  呼んでいたが、4 ロケール全ての `bundle_unit_price` にプレースホルダーが無かった (`"実質単価"` のみ)。
  `String.format()` は余分な引数を**黙って捨てる**ため、単価 (`¥800` 等) が画面に表示されず、
  「実質単価」という固定ラベルしか出ていなかった。
- `bundle_savings_discount / markup` (節約率 `−20%` / 上乗せ率 `+20%`) も 4 ロケール間で
  ハードコード (`"−${pct}%"`) されており、ロケール別文字列として管理されていなかった。

### 適用した改善 (commit 5018289)

- 4 ロケール全ての `bundle_unit_price` を format string に変更 (ja: `"1個 %1$s"`, en: `"%1$s / item"` 等)
- `bundle_savings_discount` (`−%1$d%%`) / `bundle_savings_markup` (`+%1$d%%`) を 4 ロケールに追加
- `BundleCard.kt` でこれらを使用、また `titleMedium` ヘッダーに `a11yHeading()` を追加

## 製品改善ループ (Tier 49: AccessibilityExt を生産コードに配線 — 2026-06-16)

### 発見 (Tier 46 の続き: 部分的に配線済みだったが 3 箇所が抜けていた)

- Tier 46 では「AccessibilityExt 6 関数すべて未配線」と診断。本ターンで実際に配線した。
- `VerdictBadge`: TalkBack は `contentDescription` 未設定時、**ラベル文字列** (「買い時」) を読む。
  一見問題ないが、スコアを含まない (「買い時」 vs 「買い時、85点」) → `verdictA11yLabel(verdict.name, score)` で補完。
  `ProductRow` 側で `score = row.score` を渡す signature 変更も実施。
- 価格 `Text`: `"¥29,800"` は TalkBack が「¥記号 2万9千800」と読みがちで不自然 →
  `priceA11yLabel(row.product.totalPrice)` = `"29,800円"` を `contentDescription` に設定。
- `SaleCalendarScreen.SectionHeader` + `BundleCard` タイトル: `a11yHeading()` で TalkBack が「見出し」として扱うよう設定。

## 製品改善ループ (Tier 48: 検索空状態・クロスモールチップの i18n 漏れ修正 — 2026-06-16)

### 発見 (4 ロケール対応しているはずの UI にハードコード和文が残存)

- `SearchHelpers.kt` 空状態本文: `"Amazon・楽天・Yahoo! の価格を\n一度に比較できます"` /
  `"別のキーワードや JAN コードで\n再度お試しください"` — 英語/韓国語/中国語環境で日本語が出ていた。
- `ProductRow.kt` クロスモールチップ: `"${count+1}モール最安 (-¥200)"` / `"${count}モールで比較"` —
  同上。カウント数が動的にもかかわらず完全にハードコード。

### 適用した改善

- `search_idle_body` / `search_no_results_body` / `product_cross_mall_cheapest` / `product_cross_mall_compare`
  を 4 ロケール全てに追加。`SearchHelpers.kt` と `ProductRow.kt` から `stringResource()` に切り替え。
- i18n parity test (test_i18n_parity.py): 3 tests 全て緑を確認。

## 製品改善ループ (Tier 47: CurrencyFormatter のロケール反転 guard を識別テストで固定 — 2026-06-15)

### 発見 (Tier 45 の系: guard はあるがテストが守っていない)
- `CurrencyFormatter` は docstring 通り「EU ロケール端末で桁区切りが反転するのを防ぐ」ため
  `String.format(Locale.US, "%,d", ...)` と**明示**している。これがこのクラスの存在意義。
- だが `CurrencyFormatterTest` は既定ロケール下でしか走らず、`Locale.US` 引数を削除しても緑のまま。
  → クラスの目的そのものが回帰テストで保護されていなかった (非識別テスト = 検証の演劇)。
- 実証: de_DE ロケール下で `"%,d".format(1234567)` は `1.234.567` (ピリオド区切り) になる。
  `Locale.US` guard 有りなら `1,234,567` を保つ。両者は異なる → de_DE 下のテストは識別的。

### 適用した改善
- **`run_currency.sh` (12 個目のハーネス)** + `CurrencyFormatterCheck.kt`: US / de_DE / ar の
  各ロケールに `Locale.setDefault` して全フォーマッタを実行し、どのロケールでも ASCII 数字+カンマを
  保つことをアサート (実行後 finally で既定ロケール復元)。guard 削除で de_DE ブロックが落ちる。
- **Kotest 側にも識別テストを追加** (CI 用): de_DE ロケール下で `yen(1_234_567) == "¥1,234,567"` 等。
- 教訓の一般化 (Tier 45 と同根): 「このクラスが防ぐと宣言しているバグを、実際に起こして
  防げることを示すテスト」が無ければ guard は保護されていない。

## 製品改善ループ (Tier 46: アクセシビリティ補助モジュールが全面的に未配線 [監査所見] — 2026-06-15)

### ソクラテス式問答 (どの層がテストの死角か)
- 問: `ui/a11y/AccessibilityExt.kt` の 6 関数 (a11yMinTouchTarget / a11yHeading / a11yDescription /
  verdictA11yLabel / priceA11yLabel / darkPatternA11yLabel) は production で何箇所から呼ばれる?
  → **0 箇所。** モジュール全体が死蔵。WCAG AAA 準拠を謳う docstring に反し、どの Composable も
  これらの集約ヘルパーを使っていない。
- 問: ではアプリにアクセシビリティは皆無か?
  → **否。** 各画面 (SearchScreen/WatchlistScreen/ProductDetailScreen 等) は `contentDescription` を
  **インラインで直接**付けている。つまり AccessibilityExt は「重複した未使用インフラ」であって、
  アプリが a11y 皆無というわけではない (verdict バッジ等の可視テキストは TalkBack が読む)。
- 問: verdictA11yLabel / darkPatternA11yLabel は和文ハードコードでもある。配線すべきか削除すべきか?
  → 判断保留。これは**意図的に作られた a11y インフラに見え**、私が書いたものでもないため、
  削除せず所見として surface する (CLAUDE 行動指針: 自分が作っていない・説明と矛盾する対象は
  削除前に報告)。

### 推奨 (将来 Android ビルド/CI が使える担当者向け、本セッションでは未実施)
- 集約ヘルパーに一本化するなら: 各画面のインライン `contentDescription` を `a11yDescription` に寄せ、
  クリック可能要素 (IconButton/チップ計 8 箇所) に `a11yMinTouchTarget` を適用。
- verdictA11yLabel/darkPatternA11yLabel を配線するなら**ローカライズが前提** (現状和文ハードコード)。
  `@StringRes` 化 + Composable 側 `stringResource` 解決が必要。score 文脈を読み上げるなら
  VerdictBadge に score を渡す signature 変更が要る。
- 本セッションで未実施の理由: いずれも Compose UI 変更で、当環境は Android SDK 不在のため
  コンパイル検証不能。未検証の UI コードは出さない方針 (検証できる純層のみ実装した)。

## 製品改善ループ (Tier 45: ソクラテス式 — 検証の演劇性 [非識別テストの暴露] — 2026-06-15)

### ソクラテス式問答 (手法そのものへの転回)
- 問: Tier 40-44 で挙動を変えた。**もし誰かがそれを差し戻したら、どのテストが落ちるか?**
  → 調べると `SortAndFilterTest` の `PRICE_ASC/DESC` テストは存在する。だが…
- 問: そのテストの `mkRow` は `effectivePrice` を指定しているか?
  → **していない。** デフォルト `effectivePrice = price (sticker)`。さらに Amazon・pointsBack=0 では
  `PointSimulator` 実質価格 == totalPrice。つまりテストデータでは両者が常に一致する。
- 問: ならば `SortAndFilter` が `totalPrice` でソートしても `effectivePrice` でソートしても、
  このテストは**同じ結果**になるのでは?
  → **その通り。** Tier 40 の修正 (totalPrice → effectivePrice) を差し戻してもテストは緑のまま。
  保護になっていない。「バグと修正の両方で通るテストは、保護ではなく演劇 (theater)」。
- 結論: 6 連続の正しさ修正を入れたが、その正しさを守るテストを 1 つも追加していなかった。
  これは「正しさを *主張* したが *保護* していない」状態。Socratic な自己批判: 私は
  「totalPrice を effectivePrice に変える」作業を機械的に繰り返し、検証ループを閉じていなかった。

### 適用した修正 (識別力のある回帰テスト)
- `SortAndFilterTest.mkRow` に `effectivePrice` パラメータを追加。
- **sticker 順と effectivePrice 順が *食い違う* データ**でテストを追加:
  - `PRICE_ASC/DESC`: sticker では [b,a,c] だが effectivePrice では [c,a,b] になる行集合を使い、
    sort が effectivePrice に従うことをアサート。totalPrice に差し戻すと**落ちる**。
  - 価格範囲フィルタ: sticker 枠外/effective 枠内 (3200→2900) と
    sticker 枠内/effective 枠外 (2900→3100) の商品を混在させ、effectivePrice 判定を強制。
- スタンドアロン Kotlin で期待値の算術と「識別性」(sticker パスが別結果を出すこと) を実行検証
  (Android SDK 不要、`/tmp/verify_sort.kt`)。CI では Kotest がこのテストを実行する。

### 一般教訓 (この発見は今後の全 Tier に適用)
- **テストの識別性チェック**: 新しい挙動を入れたら「旧挙動でこのテストは落ちるか?」を必ず問う。
  落ちないなら、それはカバレッジの錯覚。データを旧/新で *分岐する* 値に変える。
- 死蔵フィールド (`effectivePrice`) が「デフォルト値 == 旧フィールド」だと、テストが
  存在しても新コードパスを一度も踏まない。デフォルトが旧挙動に一致する設計は危険信号。

## 製品改善ループ (Tier 44: ソクラテス式 — TCO 計算の購入価格 [sticker → effectivePrice] — 2026-06-15)

### ソクラテス式問答
- 問: TCO (Total Cost of Ownership) の「購入価格」に何を渡しているか?
  → `product.totalPrice` = sticker + shipping、ポイント還元なし。
- 問: 例えば Rakuten SPU8x ユーザーが 10万円のプリンターを買うと、実質 8000円のポイントが返る。
  その ユーザーの真のプリンタ所有コストは?
  → `92000 + 年間消耗品` であるべき。だが TCO は `100000 + 消耗品` で計算されていた。
- 結論: TCO は「実質コスト」を示すための機能。ポイント還元前の価格では正確でない。

### 適用した修正
- `ProductDetailViewModel.load()` でのTCO計算:
  `purchasePrice = product.totalPrice` → `PointSimulator.simulate(product, userCtx).effectivePrice`
- `userCtx` は既に直前で構築済みなので追加 I/O なし。

## 製品改善ループ (Tier 43: ソクラテス式 — SmartCartService の自己言及 TODO [effectivePrice 修正] — 2026-06-15)

### ソクラテス式問答
- 問: `SmartCartService` のコメントに何と書いてあるか?
  → 「PointSimulator が前計算した実質単価を `options` に載せる想定（現在は totalPrice で代替）」
- 問: この TODO は誰かが修正したか?
  → **誰も修正していない。** 自己言及 TODO として放置され、実際は `totalPrice` (sticker+shipping) を
  CartOptimizer に渡していた。
- 問: `totalPrice` = `realPrice + shippingFee`。CartOptimizer はさらに MallConfig.shipping を加算する。
  送料が **二重計上** されるのではないか?
  → **その通り。** Rakuten 商品は `shippingFee=500` を含む totalPrice を `options` に渡し、
  さらに MallConfig.shipping=500 が加算される → 購入1件あたり 500円の誤差。
- 問: `naiveTotal` も同じ問題があるか?
  → **ある。** 「ウォッチ中プラットフォームでの単純購入合計」も totalPrice + mall shipping で
  二重計上されていた。比較基準が歪むため、最適化による節約額 `savingVsNaive` も不正確。
- 問: UserContext が DefaultContext のままだから、ポイント比較も無意味ではないか?
  → **そうだった。** WatchlistViewModel はすでに UserPreferences を @Inject していたが
  EC 会員設定 (rakutenSpu 等) は新規追加なので、UserContext を渡していなかった。

### 適用した修正
- **`SmartCartService.optimize()`**: `userCtx: PointSimulator.UserContext` パラメータを追加。
  `options` には `PointSimulator.simulate(p, userCtx).let { sticker - pointsBack }` を使用
  (shipping 除外 → optimizer が mall 送料を別途加算するため整合する)。
- **`SmartCartService.computeNaiveTotal()`**: 同じく effectivePrice (shipping なし) を使用。
- **`WatchlistViewModel.smartCart`**: `.map { }` を
  `combine(rawItems, prefs.rakutenSpu, prefs.yahooPremium, prefs.paypaySoftbank, prefs.amazonPrime) { ... }`
  に変更し、`UserContext` を構築して `SmartCartService.optimize(list, userCtx = userCtx)` に渡す。

## 製品改善ループ (Tier 42: ソクラテス式 — 名寄せ最安値の判定基準 [groupByIdentity 再ソート] — 2026-06-15)

### ソクラテス式問答
- 問: 検索結果で各グループの「代表商品 (最安値)」は何で選ばれているか?
  → `ProductMatcher.groupByIdentity` が `totalPrice` 順にソートし `group.first()` を代表にする。
- 問: `totalPrice` = `realPrice + shipping - 0 - 0` (points=0) — これはユーザーの実質価格か?
  → **否。** SPU8x の楽天会員は Amazon より 7% 安く買える場合でも、
  totalPrice が低い Amazon が「最安値代表」として選ばれる。
- 問: 代表選択を effectivePrice に変えると何が直るか?
  → 各グループで「そのユーザーにとって最も安い」プラットフォームの商品が代表になる。
  楽天ヘビーユーザーには楽天商品が、Amazon Prime ユーザーには Amazon 商品が前面に出る。

### 適用した修正
- `SearchViewModel.performSearch` で `groupByIdentity` 結果を `userCtx` 込みの
  `PointSimulator.simulate(product, userCtx).effectivePrice` で再ソート。
- `ProductMatcher.groupByIdentity` 自体は変更せず (totalPrice sort は userCtx 非依存の
  初期グルーピングに引き続き使える)。
- 再ソートは既に計算済みの `userCtx` を使うので追加 I/O なし (純粋関数)。

## 製品改善ループ (Tier 41: ソクラテス式 — ATL 近接比較の単位不整合 [BuyTimingScorer バイアス修正] — 2026-06-15)

### ソクラテス式問答 (ATL 比較の前提)
- 問: `signalAtlProximity(current, history)` は何を比較しているか?
  → `current` = `product.totalPrice` (sticker + shipping)、`history` = `PriceRecord.realPrice` (sticker のみ)。
- 問: 同じ「価格」を比較しているのか?
  → **否。** shipping 500円の商品は `current` が 500円分かさ上げされ、
  過去最安値1000円 / 最高値2000円の中で (1500+500=2000) ≈ 過去最高値圏と判定される。
  実際の sticker は (1500) = ちょうど中間なのに「高い」シグナルを出す。
- 問: Python オラクルは何を渡すと仮定しているか?
  → `test_buy_timing_scorer.py` の `_history` は `real_price = p`、`current` もそのまま `p` を
  単独で渡す (shipping なし)。つまり Python oracle は **sticker 同士**を比較する設計。
- 結論: Kotlin が `totalPrice` (sticker+shipping) を `current` に渡すのはオラクル仮定を破る。
  shipping が高い商品ほど ATL シグナルが「割高」方向にバイアスし、買い時スコアが低めに出る。

### 適用した修正
- `SearchViewModel.performSearch` の `BuyTimingScorer.score(current = ...)` を
  `product.totalPrice` → `product.realPrice` に変更。
- `DarkPatternDetector.detect(currentPrice = product.totalPrice)` は変更せず
  (ダークパターン検出は shipping 込みの「支払総額」で判定するのが正しい)。

## 製品改善ループ (Tier 40: ソクラテス式 — UserContext が空だったら? [UserContext Vacuum の修正] — 2026-06-15)

### ソクラテス式問答 (新視点: 単一真実源のインプットを誰が供給するのか)
- 問: PointSimulator を単一の真実源にした。では PointSimulator は「ユーザーの会員情報」をどこから得るのか?
- 答: `UserContext()` デフォルト = SPU=1、プレミアム全 false。日付のみ `LocalDate.now()` で自動設定。
- 問: `UserPreferences` (DataStore) に楽天 SPU・Yahoo Premium・SoftBank・Amazon Prime キーはあったか?
- 答: **なかった。** 設定に `isPremium` はあるが、これは Popcoon 自身のプレミアムフラグ (課金)。EC 会員情報はゼロ。
- 問: つまり Diamond+SPU8x のヘビーユーザーも SPU=1 のゲスト同様に並ぶのか?
- 答: **そうだった。** ポイント差が最大 8% 近くある Heavy user が最も利益を受けるはずの機能が、全員 base rate。
- 結論: 「単一の真実源を作る」だけでは不十分。その真実源が「誰のコンテキストで計算するか」も配線しなければ、
  精度の高い計算エンジンが全員に同じ (default) 結果を返すだけ — **UserContext Vacuum** と呼ぶ。

### 適用した修正 (Tier 40 = 単一真実源 + UserContext 個人化)
1. **`UserPreferences`** に EC 会員設定キーを追加:
   - `KEY_RAKUTEN_SPU: Int` (1–15, default 1) → `val rakutenSpu: Flow<Int>` + `setRakutenSpu(Int)`
   - `KEY_YAHOO_PREMIUM: Bool` → `val yahooPremium: Flow<Boolean>` + `setYahooPremium(Boolean)`
   - `KEY_PAYPAY_SOFTBANK: Bool` → `val paypaySoftbank: Flow<Boolean>` + `setPaypaySoftbank(Boolean)`
   - `KEY_AMAZON_PRIME: Bool` → `val amazonPrime: Flow<Boolean>` + `setAmazonPrime(Boolean)`
2. **`SearchViewModel`** に `UserPreferences` を @Inject で追加。`performSearch` 冒頭で `first()` 収集し
   `PointSimulator.UserContext` を組み立てる (検索中は変わらない → 毎 Row 再取得不要)。
3. **`SearchRow`** に `effectivePrice: Long` フィールドを追加。SearchViewModel が
   `PointSimulator.simulate(product, userCtx).effectivePrice` を明示的に渡す
   (デフォルトは `PointSimulator.simulate(product).effectivePrice` でテスト・プレビュー用に保全)。
4. **`SortAndFilter`**: `PRICE_ASC/PRICE_DESC` が `row.effectivePrice` でソート (従来 `product.totalPrice`)。
   `SearchFilter.apply` の価格範囲フィルタも `row.effectivePrice` に変更。
- 日付自動対応はそのまま: `LocalDate.now()` から 5と0のつく日/5のつく日/日曜日 ボーナスを反映。

### 日付のみ有効だった期間の正確な状況
- 6月15日現在: Rakuten の「5と0のつく日(15日)」+1%、Yahoo「5のつく日(15日)」+4% は
  デフォルト UserContext でも自動付与されていた。**日付感応ボーナスは元から機能していた**。
  会員ランク・プレミアム設定だけが空だった。

### 次の Tier (スコープ外)
- **SettingsScreen に EC 会員設定 UI を追加**: 楽天 SPU スライダー (1–15)、Yahoo Premium / SoftBank /
  Amazon Prime トグル。キーは既に UserPreferences に追加済み。
- `SettingsViewModel` に `setRakutenSpu / setYahooPremium / setPaypaySoftbank / setAmazonPrime` を
  expose して SettingsScreen の UI に束ねれば完成。
- BuyTimingScorer の `current = product.totalPrice` は歴史価格との相対比較なので
  `effectivePrice` 化すると非対称になる → 現状 totalPrice のまま維持が正しい。

## 製品改善ループ (Tier 39: ソクラテス式 — 中核の価値命題が成立しているか? [診断] — 2026-06-14)

### ソクラテス式問答 (最も根本的な前提)
- 問: このアプリは根本的に何のためにある? → 答: 商品を**実質価格 (ポイント還元後)** で並べ替えること。
- 問: 検索結果は実際どの数値で並ぶ? → 答: `SortAndFilter` は `product.totalPrice` で sort/filter。
- 問: 実データの `totalPrice` は? → 答: `realPrice + shipping - pointsBack - couponAmount`。だが
  **pointsBack/couponAmount は常に 0** (Rakuten/Yahoo マッパーが 0 固定、DTO は pointRate を parse すらしない)。
- 結論: 「安い順」は実質**定価+送料順**で、ポイントを完全に無視。中核の価値命題が成立していない。

### 診断 (実装は意図的に保留 — リスクが高く著者も TODO 化)
- **不整合**: 検索ランキング/価格フィルタ (`totalPrice`, points 無視) と 詳細画面の実質価格表示
  (`PointSimulator`, points 込み) が食い違う。安い順 #3 が実質 #1 でもユーザーに正しく並ばない。
- **保留の理由 (妥当)**: (1) `totalPrice` 変更は sort/filter/alert/比較 UI 全体に波及し、CI 無しでは
  描画検証不能。(2) `pointsBack` を API から素直に入れると、詳細の `PointSimulator` (プラットフォーム
  基本ポイントを別途加算) と**二重計上**する。真の修正は「実質価格の単一の真実源」を作り
  ランキングと詳細の両方がそれを使う設計変更で、CI 検証が前提。
- **本セッションの判断**: 中核機能の設計変更を未検証で強行するのは規律違反。正確な診断を文書化し、
  配線可否は製品判断としてユーザーに委ねる (この後 AskUserQuestion)。
- 関連の安全な前進 (済): originCountry/janCode は nullable・加算的で波及が無いため配線した (Tier 36-38)。
  pointsBack は totalPrice に波及するため別扱い。

## 製品改善ループ (Tier 38: janCode を主経路 (Yahoo API) でも復活 — 2026-06-14)

ソクラテス式の続き: Tier 37 の janCode 復活は **FallbackScraper (フォールバック経路) のみ**だった。
問: 大半の商品が通る**主経路 (Rakuten/Yahoo API)** では? → 答: 依然 null。半分しか直っていない。
- Yahoo Shopping V3 商品検索は応答に `janCode` を含むが、`YahooResponse.Hit` DTO が**モデル化して
  いなかった** (取りこぼし)。Yahoo は主要ソースのため影響大。
- 実装: `Hit.janCode: String? = null` を追加し、`toProduct()` で `normalizeGtin(janCode)` を設定。
  nullable 既定 + ignoreUnknownKeys のため、API がフィールドを返さなくても無害 (null のまま)。
  正規化は FallbackScraper の gtin と共通 (`normalizeGtin`) でキー整合を担保。
- 検証: run_yahoo.sh に janCode 4ケース (JAN-13/ハイフン正規化/不正→null/無し→null) → 全通過。
  マッパーのロジックは実行検証済み。API がフィールドを返すかは外部契約 (コメントで明示)。
- Rakuten Ichiba API は JAN を返さないため対象外 (DTO 確認済み)。

## 製品改善ループ (Tier 37: 死んだフィールドの体系的掃討 — janCode を復活 — 2026-06-14)

Tier 36 の視点 (消費されるが生成されないフィールド) を**体系化**。Product 全20フィールドを
「プロデューサ (mapper/scraper) が設定するか」×「コンシューマ (feature) が読むか」で照合:
- 死んでいた (consumed but never produced): `originCountry`(Tier36で対応)・**`janCode`**・
  `trustScore`・`subscribePrice`・`deliveryDays`・`couponAmount` 等。
- 最重要は **`janCode`**: `ProductMatcher` が JAN 一致を**最優先の確実シグナル** (similarity=1.0) と
  `groupByIdentity` で使うのに、どのプロデューサも設定しておらず**バーコード完全一致の名寄せが死亡**。
  横断比較が型番+ファジー一致 (全角対応で直した方) のみに依存していた。
- 実装: `normalizeGtin` (JsonLdStock.kt, 純粋) + `FallbackScraper` で schema.org `gtin13/gtin/gtin8`
  (= JAN/EAN/UPC) を抽出・正規化 → `Product(janCode=...)`。JSON-LD に GTIN を持つ商品で JAN 名寄せが復活。
  (EC API DTO は JAN を提供しないため Rakuten/Yahoo 経路は別途要対応 — 文書化のみ)。
- 検証: run_jsonld.sh に gtin 正規化 10ケース + end-to-end → 全通過。
- 残課題 (文書化): trustScore/subscribePrice/deliveryDays/couponAmount も同様に死んでいる
  (consumer が少数 or 0)。優先度順に今後対応。

## 製品改善ループ (Tier 36: ソクラテス式 — 「正しい関数」は「動く機能」か? — 2026-06-14)

### ソクラテス式問答 (さらに深い前提)
- 問: 今セッションは純関数の正しさを検証してきた。それは「機能が動く」ことを保証するか?
  → 答: しない。正しい関数も**誤った入力**で呼べば誤る。ユーザーは関数でなく画面を見る。
- 問: 入力が黙って誤るのはどこか? → 答: これらの関数は `DICT.get(key, default)` の**文字列キー検索**
  (`CO2_BY_COUNTRY[origin]` 等)。プロデューサがコンシューマと違うキーを渡すと既定値に黙って落ちる。
- 問: その**プロデューサ/コンシューマのキー整合性**を一度でも確認したか? → 答: 一度も。
  関数を単体でしか検証せず、関数**間の継ぎ目**を見ていなかった。

### 発見 — 機能が実データで死んでいた
- `originCountry` は Product に宣言され ProductDetailViewModel で読まれる (eco スコア表示) が、
  **コードベース全体でどのプロデューサも一切セットしていなかった** (scraper/mapper も)。
  → 実データでは常に null → `?.let` が発火せず eco スコアが**永遠に表示されない**。
  Tier 35 で green_alt バグを直した eco 機能は、そもそも実データで動いていなかった。

### 実装 — 死んだ機能を生き返らせる + キー整合性の解決
- `normalizeOriginCountry` (JsonLdStock.kt, 純粋): 表記ゆれ (ISO-2/ISO-3/英語/日本語、
  "Japan"/"日本"/"JPN" → "JP" 等) を EcoEthicsScorer の ISO-2 キーへ正規化。未対応は null。
- `FallbackScraper.parseProductSchema`: schema.org `countryOfOrigin` を抽出 → 正規化 →
  `Product(originCountry=...)`。これで JSON-LD に原産国を持つ商品で eco スコアが初めて動く。
- 検証: run_jsonld.sh に 25+ の正規化ケース + end-to-end (countryOfOrigin→正規化→キー) → 全通過。
- 教訓: 単体の正しさ (関数) と継ぎ目の正しさ (プロデューサ/コンシューマのキー整合) は別物。
  「正しいが呼ばれない/誤った引数で呼ばれる関数」は単体テストの死角。

## 製品改善ループ (Tier 35: ソクラテス式 — オラクル自体を疑い共有バグを発見 — 2026-06-14)

### ソクラテス式問答による新視点
- 問: 今セッションは何を検証してきたか? → 答: Kotlin == Python (差分パリティ)。
- 問: 「ミスマッチ=バグ」と判定する前提は? → 答: **Python (リファレンス) が正しい**。
- 問: その前提を検証したか? → 答: **一度もしていない** (9件のバグ修正すべてで暗黙)。
- 問: 差分パリティが構造的に見つけられないものは? → 答: **両実装に共通するバグ** (誤った共有式)。
- 結論: パリティは「一致」を証明するが「正しさ」は証明しない。リファレンス自体を疑う必要がある。

### 実装 — 共有バグを1件発見・修正
- **実バグ (共有)**: `score_eco_ethics` の「緑の代替案」が `saving_pct = int((1 - 0.45/co2_factor)*100)`。
  原産国が日本より低炭素 (DE 0.30 / US 0.38 < JP 0.45) だと saving_pct が**負**になり、
  「国産代替でCO2**-50%**削減可」という無意味な (むしろ逆効果の) 提案を表示していた。
  Python・Kotlin 両方に存在 → 差分パリティでは検出不能 (両方一致して間違っていた)。
  → 両実装で `saving_pct > 0` の時のみ提示するよう修正。
- **手法の追加**: `test_metamorphic.py` に `TestEcoMetamorphic` を追加。eco は既存の metamorphic
  スイート (customs/tco/予測/optimizer/scorer/trie をカバー) に**含まれていなかった**のが盲点だった。
  不変条件「緑の代替案は実際に削減する場合のみ提示」「スコアは [0,100]」を追加 → 110 passed。
  kotest 回帰 + パリティ harness (DE/tv ケース) でも両面固定。
- 教訓: リファレンス実装は「正本」だが「無謬」ではない。差分パリティ (実装間) と
  メタモルフィック/不変条件 (実装非依存) の**二本立て**で初めて共有バグに手が届く。

## 製品改善ループ (Tier 34: Trie オートコンプリートの子訪問順バグ — 差分パリティ — 2026-06-14)

### 長所短所改善点 (継続)
- **長所**: パリティ主張のある純関数が多く、差分検査で実バグが次々出る (今セッション計9件)。
- **短所**: **「Python と一致」と書きつつ未検証**の箇所が残っていた (Trie / TCO)。コメントの主張と
  実装の乖離が放置されやすい。また **コレクション型の選択 (HashMap vs LinkedHashMap)** が
  暗黙の順序契約を破る — 言語間移植で陥りやすい罠。
- **改善点**: パリティ主張を grep で全列挙し、ハーネス未収録を体系的に潰す (Trie が最後の1件)。

### 実装 (実バグ発見・修正)
- 全パリティ主張 Kotlin (13ファイル) を harness と突合 → 未収録は `core/Trie.kt` のみと判明。
- **実バグ**: `Trie.Node.children` が `HashMap<Char,Node>`。Python の dict は挿入順だが HashMap は
  ハッシュ順 → BFS の子訪問順が乖離し、`suggest()` の**サジェスト候補順・limit 打ち切り時の集合**が
  リファレンスと食い違っていた (例: "a" limit6 → Python[art,arc,ark,ant,and,any] vs
  Kotlin[art,arc,ark,act,ace,ant] — **集合ごと別物**)。ユーザーに見えるオートコンプリートの乖離。
  → `LinkedHashMap` に変更し挿入順を保持。
- `run_trie.sh` + `TrieCheck.kt` + `trie_oracle.py` で差分検査 (8クエリ+size) → 修正後 全一致。
  kotest 回帰1件 (順序アサート) 追加。既存テストは size/contains のみで順序未検査だった。

## 製品改善ループ (Tier 33: TCOCalculator で差分パリティ実バグ発見・修正 — 2026-06-14)

`TCOCalculator` が `popcoon_core.calculate_tco` との「同一式」を主張していたため**差分パリティ**を
本ハーネスに追加 (TCO 13 ケース) → **実バグ発見**:
- **レーザープリンターのドラム**: Python は `ConsumableItem("ドラム", 8000, 0.33)` で **intensity 非適用**
  (0.33回/年 固定) だが、Kotlin は `(8000 * 0.33 * intensity)` と intensity を掛けていた。
  → 使用強度 ≠ 1.0 のレーザープリンタで消耗品コスト＝TCO がずれる (i=2.0 で +13,200円 過大、
  i=0.5 で −6,600円 過少)。実害のある計算ミス。
- 副次: 各消耗品の結合順を Python の `int(price*(qty*intensity))` に合わせ (`price*(qty*intensity)`)、
  全 intensity で浮動小数点まで一致するよう統一。
- 修正後 **93/93 一致** (旧 80 + TCO 13)。kotest 回帰1件 (intensity=2.0 でドラム非スケール) 追加。
- 教訓: 「同一式」と書かれた箇所こそ差分検査の価値が高い (customs と同じ構図)。

## 製品改善ループ (Tier 32: JanCodeQuery のチェックデジット実行検証 — 2026-06-14)

Unicode の鉱脈から**独立検証可能なアルゴリズム**へ転換。`JanCodeQuery` (JAN/EAN バーコード検証) の
チェックデジットは標準アルゴリズムで、独立実装と照合すれば真の差分検証になる (重み付けの off-by-one
は valid を弾く/invalid を通す実害)。
- 独立オラクル: 標準 EAN-13 (奇数桁×1/偶数桁×3)・EAN-8 (奇数×3/偶数×1)・UPC-A を Python で別実装し
  test vector 生成。`run_jan.sh` + `JanCodeQueryCheck.kt` で照合: 有効 JAN-13/8、末尾改変の無効、
  桁数/非数字、UPC-12→JAN-13 変換 (0 前置)、国コード → 全通過。
- バグ無し。`foldIndexed` の重み付け・`(10 - sum%10)%10` は標準どおり正しいと実行確認。
- run_all.sh / CI parity job に組込み (ハーネス計9本)。

## 製品改善ループ (Tier 31: 自己誤認の訂正 — 冗長な全角パースヘルパー削除 — 2026-06-14)

`TargetPriceDialog` の全角入力を疑い検証した結果、**自分の過去の前提が誤りと判明**し訂正・簡素化。
- 検証 (bundled kotlinc で実行): `"３".toInt()` = 3、`"１０００".toLongOrNull()` = 1000。
  Kotlin の数値パースは `Character.digit` ベースで**全角数字を解釈する**。`toInt()` は例外を投げない。
- 帰結1: `TargetPriceDialog` (`text.filter{isDigit()}.toLongOrNull()`) は全角入力で**既に正常動作**。
  コメント「全角許容」は正確 → **バグではない**ので変更せず (誤った「修正」を未然に回避)。
- 帰結2: Tier 24/28 で追加した `parseUnicodeInt`/`parseUnicodeIntOrNull` は**冗長**だった。真の修正は
  regex の (?U) のみで、パースは素の `toInt()`/`toIntOrNull()` で足りる。両ヘルパーを削除し簡素化。
  → run_all.sh で 80/80 + 全ハーネス通過を確認 (挙動不変)。Tier 24/28 の該当記述も訂正済み。
- 教訓: 「Java/Kotlin は全角を弾く」という思い込みを実測せず一般化していた。**実行で確かめる**規律を
  二次的なパース層にも適用すべきだった。(?U) regex 修正自体は正しく必要だった。

## 製品改善ループ (Tier 30: ProductMatcher を NFKC 正規化に刷新 + 長所短所改善点 — 2026-06-14)

### 長所短所改善点の洗い出し (本セッションの知見ベース)
- **長所**: (1) ロジックが純関数中心で TDD/実行検証しやすい。(2) Python 参照プロトタイプ + パリティ
  という二重実装の規律があり、差分でバグが出る (customs/dark-pattern を実際に発見)。
  (3) 機能が豊富 (名寄せ・ポイント・予測・横断カート・ダークパターン暴露)。
- **短所**: (1) **全角/半角・Unicode 表記ゆれが構造的弱点** — ASCII `\d`/`\s`、手製の全角変換が
  各所に散在し、日本語 EC タイトルの実データで取りこぼし (本セッションで実バグ6件)。
  (2) コンパイルに Android SDK 必須で、CI 無効だとローカル検証経路が無い (kotlin_parity で代替中)。
  (3) 同じ正規化を各所で再実装し不整合 (normalizeTitle vs extractModelNumber)。
- **改善点 (本 Tier で着手)**: 表記ゆれ正規化を **NFKC に一本化**。手製の全角英数変換・全角ハイフン/
  スペースの個別 replace を `java.text.Normalizer` NFKC へ置換し、**半角カナ (ｿﾆｰ→ソニー)・
  濁点合成 (ﾊﾞ→バ)** まで一括対応。今後の表記ゆれ取りこぼしを構造的に断つ。

### 実装
- `ProductMatcher`: `toHalfWidth`(手製) → `nfkc()` に刷新。`extractModelNumber` から全角ハイフン/
  スペースの手動 replace を除去 (NFKC が一括処理)。`FULLWIDTH_REGEX` 削除。
- 検証: run_matcher.sh に半角カナの isMatch + 濁点合成トークン一致を追加 → 全通過 (既存も不変)。
  kotest 回帰1件追加。
- 効果: 半角カナ表記の同一商品 (Yahoo に多い) が名寄せされるように。表記ゆれ吸収が NFKC 標準準拠に。

## 製品改善ループ (Tier 29: 全角バグの体系的掃討 — ProductMatcher で2件発見・修正 — 2026-06-14)

Tier 28 の教訓「全角/Unicode は構造的弱点」を**バグパターン検索**で体系化。
`grep 'Regex(...\d...)'` で ASCII `\d` を使う全 regex を洗い出し → 残る1件 `ProductMatcher` を監査し
**実バグ2件発見・修正** (名寄せ/横断カートの基盤機能):
- **Bug 1**: `extractModelNumber` が `title.uppercase()` のみで全角半角化せず。全角型番
  「ＷＦ－１０００ＸＭ４」(販売者が全角でタイトルを書く場合) を取りこぼし → null。
  `normalizeTitle` は全角対応済みで**2経路の正規化が不整合**だった。
  → `toHalfWidth` ヘルパー抽出 + 全角ハイフン正規化を両経路で共用。
- **Bug 2**: `WHITESPACE_REGEX = "\s+"` が ASCII。全角スペース U+3000 で分割されず、全角タイトルが
  巨大1トークン化 → Jaccard 類似度が崩壊。全角表記の同一商品が別商品扱いになっていた。
  → `(?U)\s+` で全角スペースも分割 (Python `\s` の Unicode 既定と一致)。
- `run_matcher.sh` + `ProductMatcherCheck.kt`: ASCII/全角の型番抽出 + 全角タイトル同士の
  end-to-end `isMatch` を実行検証 (修正前3件 mismatch → 全通過)。kotest 回帰2件追加。

全角掃討完了: ASCII `\d` を使う regex は dark-pattern / bundle / matcher の3箇所、すべて修正済み。
本セッションの全角/Unicode 起因の実バグは計5件 (darkpattern×2 + bundle + matcher×2)。

## 製品改善ループ (Tier 28: BundlePackDetector で全角数字バグ発見・修正 — 2026-06-14)

「ネイティブ関数は cosmetic な発見のみ」という Tier 27 の総括は**早計**だった。バグの有無は
オラクルの有無より**関数の種別**(正規表現/文字列) に依る。`BundlePackDetector` (セット販売の
個数抽出 → 実質単価) を text/regex リスク種別として優先監査し、**実バグを発見・修正**:
- 正規表現が ASCII `\d`。日本語タイトルで頻出する全角数字「３本セット」「２４本ケース」を
  取りこぼし、全角表記のセット商品が「単品」(NOT_A_BUNDLE) 扱いになっていた (dark-pattern と同種)。
  → BUNDLE_PATTERNS 5本に `(?U)` 付与。(※当初「`toIntOrNull("３")` も null なので (?U) だけでは不十分」と
  記載し `parseUnicodeIntOrNull` を追加したが**誤り**: `toIntOrNull` は全角も解釈する。Tier 30 でヘルパー削除。)
- 派生発見: doc コメントが実在しない `bundle_pack_detector.py` との 100% 等価を主張 → 修正
  (実行検証は run_bundle.sh に置換)。
- `run_bundle.sh` + `BundlePackDetectorCheck.kt`: ASCII/全角の抽出 + verdict 独立手計算で実行検証
  → 修正前 4 件 mismatch (全角)、修正後 全通過。kotest 回帰3件追加。run_all.sh / CI 組込み。

教訓: 残る native 純関数も **text/regex 系を優先**すべき (ReviewTrustScorer 等の数値系より
バグ潜在性が高い)。Unicode/全角は日本語 EC アプリの構造的弱点。

## 製品改善ループ (Tier 27: PointSimulator の実質価格表示バグ修正 + 実行回帰 — 2026-06-14)

中核機能「実質価格」(ポイント還元後価格、アプリの差別化要素) の `PointSimulator` を監査。
**透明性を損なう表示バグを1件発見・修正**:
- 楽天SPU の付与額は `coerceIn(1,15)` だが、表示率は生の `rakutenSpu` を使用 → 不一致。
  spu=0 → 1% 付与なのに "0.0%" 表示、spu=20 → 15% 付与なのに "20.0%" 表示。
  「計算ロジックを公開 (透明性)」が売りの機能で表示と実態が食い違う問題。
  → 表示率を coerce 後の値に統一。
- `run_points.sh` + `PointSimulatorCheck.kt`: 期待値を**ルールから手計算 (独立オラクル)** して照合。
  楽天 SPU/5と0の日/ダイヤ重ね掛け、各ソースの切り捨て、Yahoo PayPay/5の日/日曜/プレミアム/SB、
  Amazon 商品別還元、実質価格の 0 フロアを実行検証 → 全 assert 通過 (修正後の表示含む)。
- run_all.sh / CI parity job に組込み。

総括: 独立 Python オラクルとの差分検査 (customs/dark-pattern で実バグ発見) の鉱脈は掘り尽くした。
ネイティブ純関数はオラクル不在で差分が出にくく、発見は表示系の小バグに留まる。
残る高価値作業は CI 依存 (ConformalInterval/SeasonalDecompForecast の本番配線等)。

## 製品改善ループ (Tier 26: UrlClassifier の実行検証 — 共有フロー回帰 — 2026-06-14)

パリティ網羅完了後、Python オラクルを持たないネイティブ Kotlin 純関数のうち**正規表現/文字列
パース系**(dark-pattern と同じ高リスク種別) を監査。`UrlClassifier` (Share Intent の URL →
Platform+SKU、中核の「2タップ」フロー) を standalone 実行ハーネスで検証。
- 精読では bug 無し: Amazon 2段パターン順序、capture 前の query 除去、.html 再正規化は妥当。
- `run_url.sh` + `UrlClassifierCheck.kt` で実 URL 形式 (Amazon /dp//gp/product//SEO/言語パス/
  クエリ付き、楽天、Yahoo .html有無、埋め込み URL 救出、非マッチ) を実行検証 → 全 assert 通過。
- オラクル不在のため bug 発見力は低い (差分が出ない) が、CI 不可な中核機能に実行回帰を追加。
- run_all.sh / CI parity job に組込み。AffiliateUrlBuilder は android.net.Uri 依存で standalone 不可。

## 製品改善ループ (Tier 25: SeasonalDowSignal の実行パリティ — パリティ網羅完了 — 2026-06-14)

最後の未直接検査の純関数 `SeasonalDowSignal` (曜日季節性の買い時シグナル) を実行パリティ検査に追加。
注目点は **round-half-to-even** (Python `round()` = Kotlin `kotlin.math.round`)。素朴な `Math.round`
(round-half-up) なら乖離する箇所。`rel*100` を 2.5/3.5/-3.5 の .5 境界に寄せたケースで検証。
- SDOW 7 ケース (正/負シグナル、+10 クランプ、履歴不足、曜日サンプル不足、overall<=0) → **80/80 一致**。
- 丸めは銀行丸めで一致。ガード分岐も全て一致。バグ無し。

**パリティ網羅完了**: 移植純関数 (customs / eco / dark-pattern数値 / dark-pattern テキスト /
predict / buy-timing / conformal / seasonal-decomp / cart / seasonal-dow) + EC マッパー3種
(Rakuten/Yahoo/JSON-LD) を全て実行パリティ検査済み (run_all.sh, CI parity job)。
発見した実バグ: customs (Holt 初期化) と DarkPatternText (Unicode \d\s ×2 + 早期return)。

## 製品改善ループ (Tier 24: DarkPatternTextDetector で実バグ2件発見・修正 — 2026-06-14)

`DarkPatternTextDetector` (UIテキスト系ダークパターン検出) を実行パリティ検査に追加し、
`proto_darkpattern_signals.py` と照合 → **実バグ2件を発見・修正**（customs 以来の本物の発見）。
- **Bug A (Unicode 乖離)**: Python3 の `\d`/`\s` は str 既定で Unicode だが、Java/Kotlin の
  `\d`/`\s` は ASCII 専用。「残り３点」(全角数字)・「残り　3　点」(全角空白 U+3000) を
  Kotlin が取りこぼし、Python と乖離。日本語 EC では全角が頻出するため実用上の検出漏れ。
  → 該当 regex に `(?U)` (UNICODE_CHARACTER_CLASS) を付与し Python と一致。
- **Bug A' (当初の想定 → ※Tier 30 で訂正)**: (?U) で全角数字「３」がマッチした後、`"３".toInt()` が
  例外を投げると想定し `parseUnicodeInt` を追加した。**この前提は誤り**: Kotlin の `toInt()` は
  `Character.digit` ベースで全角数字も解釈する (`"３".toInt()` = 3、例外なし)。真の修正は (?U) のみで十分で、
  ヘルパーは冗長 → Tier 30 で削除。取りこぼしの真因は regex の `\d` が ASCII 専用だった点のみ。
- **Bug B (早期 return)**: `if (text.isBlank()) return emptyList()` が、空テキスト+低在庫
  (stockCount<=3) の SCARCITY 検査を丸ごとスキップ。Python は早期 return せず stockCount を見る。
  → 早期 return を削除し Python と一致。可視テキスト無し+低在庫の商品で警告が出るように。
- TEXT 14 ケースを run.sh に追加 → **73/73 一致**。kotest 回帰テスト4件も追加 (CI android job)。

これで主要移植純関数 (customs/eco/dark-pattern×2/predict/buy-timing/conformal/seasonal/cart) を
実行パリティで全数検証。発見した実バグは customs (Holt 初期化) と本件 (Unicode×2 + 早期return)。

## 製品改善ループ (Tier 23: CrossMallCartOptimizer の実行パリティ — 2026-06-14)

最も複雑な純関数 `CrossMallCartOptimizer` (横断スマートカート: 送料無料ライン・クーポン・
配送回数を考慮した組合せ最適化) を実行パリティ検査に追加。`proto_cross_mall_cart.py` と照合。
- 入力 (items/malls) を `# | , =` で符号化して emit → Python が同じ入力で再計算 (drift 防止)。
- 5 シナリオ: 送料無料ラインでの集約、クーポン適用、同額タイ→配送回数最小、単一商品、3商品3モール。
  → **58/58 一致** (旧 53 + 5)。全探索の列挙順・(総額,配送回数,combo) 辞書順タイブレーク・
  貪欲フォールバック判定すべて Python と一致。バグ無し。
- 副次: `run.sh` のコンパイル対象に `CrossMallCartOptimizer.kt` を追加 (漏れていた)。

これで主要な移植純関数 (customs/eco/dark-pattern/predict/buy-timing/conformal/seasonal/cart) は
すべて実行パリティ検査済み。残りは小物 (SeasonalDowSignal / DarkPatternTextDetector)。

## 製品改善ループ (Tier 22: SeasonalDecompForecast の実行パリティ — 2026-06-14)

`SeasonalDecompForecast` (本番 `PricePredictionEngine.seasonalForecast7d` の基盤) を実行
パリティ検査に追加。中心移動平均 + 季節成分中心化 + 最小二乗線形 + 外挿という、浮動小数点
累積が多段で**乖離リスクの高い**数値ロジック。
- ハーネス (run.sh) に SEASONAL 6 ケース (週次季節性 4/3/2週、min_history 境界、フラット
  フォールバック、period=1 純線形、period=5) を追加し `seasonal_decompose_forecast` と
  10 桁精度で照合 → **53/53 一致** (旧 47 + 6)。Python/Kotlin で全予測値が完全一致。
- バグ無し。これで Holt/IQR/conformal/seasonal の予測パイプライン全数値要素が実行検証済み。

## 製品改善ループ (Tier 21: ConformalInterval の実行パリティ — 監査漏れの是正 — 2026-06-14)

未パリティ検査の純関数を洗い直し。Tier 10 で `predict` の margin を「Kotlin 拡張」と断じたが、
**誤り** — `proto_conformal_interval.py` という Python 参照が存在した。`ConformalInterval`
(本番 `PricePredictionEngine.predictionMargin` の置換候補) を実行パリティ検査に追加。
- 注目点: `k = ceil((n+1)*(1-alpha))` の**浮動小数点境界** (例 n=9,α=0.1 → 10*0.9 が
  9.0000…2 に丸まり ceil=10 で最大残差ブランチに切替) は、読みでは見落としやすく実行検証向き。
- ハーネス (run.sh) に CONFORMAL 8 ケース (境界・空・単一・負値含む) を追加し
  `conformal_margin` と照合 → **47/47 一致** (旧 39 + 8)。Kotlin/Python で同一の IEEE754 結果を確認。
- バグは無かったが、(1) 監査漏れの是正 (2) 数値敏感コードの実行検証 (3) CI 自動化、の 3 点で前進。

## 製品改善ループ (Tier 20: CI 有効化のターンキー化 + 検証の CI 配線 — 2026-06-13)

`RobotsTxt.isAllowed` を監査 (REP 19 ケースを実行) → **バグ無し・既存テストも網羅的**で、
ローカル検証可能な高価値の改善余地が概ね尽きたと判断。残る高価値作業は CI 依存
(app のコンパイルが前提)。そこで本セッション最大の制約 = CI 有効化に投資した。
- **`ci/enable.sh`**: 管理者が 1 コマンド (`bash ci/enable.sh && git push`) で
  `ci/android.yml` を `.github/workflows/` へ移動・有効化できる。
- **`ci/android.yml` にジョブ追加**: 本セッションの検証群を CI に配線。
  - `parity`: `kotlin_parity/run_all.sh` (SDK 不要で 6 純関数 + 3 マッパーを実行検証)。
  - `backend`: backend の vitest (これまで CI 経路ゼロだった — アラート/PII/ページ/検証テスト)。
- **`kotlin_parity/run_all.sh`**: 全ハーネス (parity 39 + Rakuten/Yahoo/JSON-LD) を集約実行、
  どれか失敗で非ゼロ終了。ローカルで全 green を確認。
- ねらい: 管理者が CI を入れた瞬間に、本セッションで積んだ検証 (Python 300 + Kotlin パリティ +
  backend) が**自動で回る**状態にしておく。CI 有効化の心理的・手順的コストを最小化。

## 製品改善ループ (Tier 19: FallbackScraper の JSON-LD 在庫抽出 — 2026-06-13)

まず「SortAndFilter の在庫切れ除外が実際に効くか」を実行検証しようとしたが、フィルタ本体は
`if (excludeOutOfStock && p.stockCount == 0)` で**自明に正しく** (null は除外されない)、
R/StringRes/SearchRow をスタブして実行するのは検証劇場と判断しスキップ (= 検証の費用対効果を
ソクラテス的に問うた)。代わりに在庫復元を別経路へ拡張:

`FallbackScraper` (API 失敗時に JSON-LD を読む) は schema.org の `offers.availability` を
取りこぼし、スクレイプ商品の `stockCount` も常に null だった。
- ktor 非依存の純粋関数 `stockFromAvailability(raw)` を新設し、`OutOfStock/SoldOut/Discontinued`
  (URL 形式含む) → 0、それ以外 → null に変換。parseProductSchema から呼ぶ (2 行追加)。
- **検証**: `run_jsonld.sh` で単体ビルド・実行。全 schema.org 値の写像に加え、nested
  `offers.availability` が flat キー正規表現で抽出され 0 に写ることを end-to-end でアサート green。

これで楽天/Yahoo/フォールバックの 3 経路で在庫が復元され、SortAndFilter の在庫切れ除外
(デフォルト ON) が実データで機能する。残るは Amazon PA-API (Offers.Availability、要 CI 検証)。

## 製品改善ループ (Tier 18: Yahoo の在庫データ復元 + マッパー抽出 — 2026-06-13)

Tier 17 の楽天と同じパターンを Yahoo に適用。`YahooClient` は V3 itemSearch の `inStock`
真偽を DTO で取りこぼし `stockCount` を常に null にしていた。
- 変換を純粋関数 `YahooMapper.kt` (ktor/BuildConfig 非依存) に抽出、`YahooClient` は IO のみに。
  `inStock==false → stockCount=0` を写す。listPrice(defaultPrice 優先)/shipping(code==2 無料) の
  既存ロジックも mapper に移設。
- **検証**: `run_yahoo.sh` で単体ビルド・実行。在庫/listPrice/shipping/既存フィールドを全アサート green。
- 正直な限界: `inStock` の正確なフィールド名は実 API で未確認。だが `ignoreUnknownKeys` +
  nullable 既定のため**名前が違っても無害** (従来同様 null)。回帰リスク無し・上振れのみ。

## 製品改善ループ (Tier 17: 楽天の在庫データ復元 — 幻フィールドの解消第一歩 — 2026-06-13)

Tier 8/9 で指摘した「データ抽出層が `stockCount` 等を埋めず、機能が死蔵」問題に着手。
`RakutenClient` は楽天 API が返す `availability` (1=在庫/0=在庫切れ) を DTO で取りこぼし、
`stockCount` を常に null にしていた → `SortAndFilter` の在庫切れ除外 (`stockCount==0`,
デフォルト ON) が不発だった。
- 変換ロジックを ktor/BuildConfig 非依存の純粋関数に切り出し (`RakutenMapper.kt`)、
  `RakutenClient` は IO のみに。DTO に `availability` を追加し、`availability==0 → stockCount=0` を写す。
- **検証**: `RakutenMapper.kt` + `Product.kt` を Gradle 同梱コンパイラで単体ビルド・実行
  (`popcoon-tdd/kotlin_parity/run_rakuten.sh`)。在庫/在庫切れ/既存フィールド保全を全アサート green。
  RakutenClient の IO 部 (ktor) のみ未コンパイル検証 (変更は DTO 削除 + `.toProduct()` 呼び出しの機械的差し替え)。
- `pointsBack` は `pointRate` から算出可能だが `product.totalPrice` を変え UI 全体に波及するため、
  CI でレンダリング検証できるまで保留 (TODO をコード/本書に明記)。これが「検証可能な分だけ進める」方針。

## 製品改善ループ (Tier 16: backend /v1/history の入力検証不足 — 2026-06-13)

`POST /v1/history` は `recorded_at` の**存在**しか見ず、`list_price` の型も未検証だった。
だが `appendPriceHistory` は `recorded_at` を `localeCompare` で**文字列ソート/dedup** し、
`evaluateAlerts` は `history[0]` を latest として扱う。→ 不正な timestamp ("today" 等) を
送られると履歴順序が時系列とズレ、**偽の latest** が予測/アラート全体を汚染する。
- 修正: `isValidIsoUtc()` で正準 ISO-8601 UTC ("...Z") のみ受理 (Kotlin の Instant.toString()
  形式に一致)。`list_price` も非負数を必須化。
- 検証: Node 再現 (10/10) — 正準値受理、garbage/ローカルオフセット/日付のみ/不能日付を拒否。
  かつ "today" が localeCompare で誤って先頭に来る (= 偽 latest) ことを実証。vitest 契約テスト追加。

## 製品改善ループ (Tier 15: backend クラッシュ受信の PII 漏れ — 2026-06-13)

`/v1/crash` の「個人情報チェック (二重チェック)」が **`body.sanitized_stack` だけ**を検査し、
保存は **body 全体** (`JSON.stringify(body)`) だった。→ `sanitized_stack` をクリーンにしつつ
他フィールド (例 `device_id:"user@email.com"`) に PII を入れれば**チェックをすり抜けて永続化**。
プライバシー (「個人情報なし」「テレメトリ未送信」) を中核差別化とする製品の致命的な穴。
- 修正: 純関数 `containsPotentialPii(payload)` を抽出し、**payload 全体**をメール/IPv4 正規表現で
  走査。ハンドラはこれで弾く。
- 検証: Node 再現で旧実装は他フィールドの PII を見逃し新実装は捕捉、正当なクラッシュは受理 (8/8)。
  vitest にも同契約テストを追加。

## 製品改善ループ (Tier 14: backend の KV 取りこぼし — GDPR 削除/アラート評価 — 2026-06-13)

backend 監査の続き。`KV.list` は 1 回で最大 1000 キーしか返さない (cursor で続きを取る) が、
2 箇所で cursor を辿らず**最初の1ページしか処理していなかった**:
- `/v1/device` 削除: アラート総数が 1000 件超のとき、対象デバイスのアラートを**消し残す** →
  「全データ削除 (GDPR Article 17)」「サーバー側も即時削除」という製品の明示的約束に違反。
- `evaluateAlerts` (`limit:100`): **101 件目以降のアラートが永遠に発火しない** → 中核機能が
  スケール時に黙って壊れる。
- 修正: `listAllKeys(ns, prefix)` ヘルパで cursor を list_complete まで辿り全キーを集約。両所で使用。
- 検証: 依存なし Node シミュレーション (2500 件・victim を 3 ページに分散) で実証 —
  旧単一ページ削除は victim の 2 件を残す (GDPR 違反を再現)、新実装は完全削除・他者無傷。
  vitest にも同契約のページネーションテストを追加 (CI で実行)。

## 製品改善ループ (Tier 13: backend のアラート誤発火バグ — 2026-06-13)

ユーザー指示で「製品の長所短所改善点」へ視点を戻し、未検証だった `backend/`
(TypeScript Cloudflare Worker) を監査。**実害のあるバグを検出・修正**:

### バグ: ATL (過去最安) アラートの誤発火
`evaluateCondition` の `atl` ケースが `history.slice(0, -1)` で**最古**の履歴を除外していた。
だが本番 (`evaluateAlerts`) では history は**新しい順**で `current === history[0]`。
正しくは current(=[0]) を除いた `slice(1)` の最小値と比較すべき。
- 影響: **最古の価格が真の最安だった場合、current が最安でないのに「過去最安」通知を誤発火**。
  Node 再現で実証 (history=[100,120,90], current=100 → 旧: true 誤発火 / 新: false 正)。
  信頼性を売りにする製品が偽の「最安」プッシュを送るのは致命的な信頼毀損。
- テストも同じ誤ロジックを複製し、かつ current を history 外に置く**本番と異なる契約**で
  書かれていたため見逃していた (customs と同型: テストがバグを承認)。
- 修正: 本番を `slice(1)` に。テストを本番契約 (current=history[0]) に揃え、
  「最古が真の最安」回帰ケースを追加。依存なしの Node 検証で全 8 アサーション green。

## ソクラテス監査 (Tier 12: 実行パリティを看板機能まで拡張 — 2026-06-13)

Tier 11 でスカラー関数 (customs/eco) の実行パリティを確立した。だがそれは「楽な 2 関数」。
最重要の `BuyTimingScorer` (プロダクトの存在理由) と数値リッチな `predict`/`darkpattern` は
依然「読み比べ」止まり — まさに customs バグを最初に見逃しかけた脆い方法。実行検証へ格上げした。

### 障壁の実測: 「serialization plugin が無いと PriceRecord はコンパイルできない」→ **偽**
履歴依存関数は `PriceRecord` (Product.kt, `@Serializable`) を要する。plugin はキャッシュに
無かったが、**実測すると plugin 無しで Product.kt はコンパイル成功** (exit 0)。理由: plugin 不在でも
`@Serializable` は無害なアノテーションに過ぎず、ロジックは `.serializer()` を呼ばないため
serialization ランタイム jar のみでビルドできる。→ スタブ不要、本物のソースで検証。

### 成果: 移植済み純関数 **6 種すべて**を実行パリティ化
`popcoon-tdd/kotlin_parity/` のハーネスを履歴依存関数まで拡張 (Product.kt + 予測/季節/カレンダー
等 10 ファイルを同梱コンパイラでビルド、31 classes)。結果 **39 ケース全一致**:
- scalar: customs 11 + eco 7
- 履歴 7 シナリオ × {darkpattern, predict, buytiming} = 21
- ダークパターン 4 種 (常設/参考価格/値上げ/端数) すべて発火し一致。buy-timing は
  67/72/55/62/67/42/null と多様な出力すべてが Python オラクルと一致。

→ Tier 10 で「読んで一致」とした BuyTimingScorer の判断が、**コンパイル&実行で裏付けられた**
(careful reading と executed check が一致 = 監査手法の相互検証)。看板機能のパリティは
もはや主張ではなく**実行可能な事実**。

### 限界 (正直に)
これは純粋ロジックの単体コンパイル/実行であり、**full app (Compose/Room/Hilt/DI) の
コンパイルは検証しない**。それは CI (`.github/workflows/`, 管理者の `git mv` 待ち) のみ。

## ソクラテス監査 (Tier 11: 「検証できない/CIは管理者しか有効化できない」二つの前提を実測 — 2026-06-13)

3 ラウンド連続で「Kotlin はコンパイルできない」「CI は `workflows` 権限が無いので
管理者しか有効化できない」と主張してきた。ゴールに従い**両前提を実測**した。

### 前提1「CI は自分には有効化できない」→ 実測の結果 **真** (検証済み)
`ci/android.yml` を `.github/workflows/` へ `git mv` してコミットし push を試行 →
リモートが明示的に拒否:
`refusing to allow a GitHub App to create or update workflow ... without 'workflows' permission`。
→ 前ラウンドまで「継承した主張」だったものを、本セッションのトークンで**実証**。コミットは
reset で取り消し済み (push 不可なものでブランチを汚さない)。CI 有効化は管理者の `git mv` 待ちで確定。

### 前提2「Kotlin はローカルでコンパイル/実行できない」→ 実測の結果 **偽** (スカラー関数は可能)
パリティ最重要のスカラー関数は依存が極小だった:
`CustomsSimulator` (kotlin.math のみ) / `EcoEthicsScorer` (import ゼロ) → Android/Room/serialization 非依存。
Gradle 同梱の `kotlin-compiler-embeddable-2.0.20.jar` で**追加 classpath ゼロでコンパイル成功**。
→ 「Kotlin は CI でしか検証できない」は全 Kotlin には当てはまらない。

### 成果: パリティを「文書上の主張」から「実行可能な検証」へ (Tier 9/10 の核心に決着)
`popcoon-tdd/kotlin_parity/` に**実行可能なクロス言語パリティハーネス**を追加:
- `run.sh`: 同梱コンパイラで本物の Kotlin (CustomsSimulator/EcoEthicsScorer) + ハーネスを
  コンパイル → JVM 実行 → 出力を `compare_oracle.py` に渡し、同じ入力で Python オラクルを
  再計算して照合。**Android SDK 不要**。
- 結果: **18 ケース全一致 (customs 11 + eco 7)**。Tier 9 で修正した CustomsSimulator の
  verdict バグが、読み比べではなく**実際のコンパイル&実行で正しいと確認**された
  (食品免税の掘り出し物=CHEAPER / 中途半端=NOT_RECOMMENDED 等)。
- 嵌った点を README に記録: JVM の `stdout.encoding=ANSI_X3.4-1968` で日本語が `?` に化け、
  Python が壊れた入力で再計算して偽の mismatch を出していた → `-Dstdout.encoding=UTF-8` で解消。

### 残り (拡張)
- **履歴依存関数** (`BuyTimingScorer`/`PricePredictionEngine`/`DarkPatternDetector`) のハーネス化:
  `PriceRecord` (Product.kt, kotlinx-serialization plugin 依存) のコンパイルが必要。
  `-Xplugin` + serialization ランタイム追加、または最小 `PriceRecord` スタブで拡張可能。
- これで Tier 10 で「故意に未実装」とした Kotlin consumer の代替が**スカラー関数では実現**した
  (CI/SDK 無しで実行可能)。CI 有効化後は full app テストへ統合。

## ソクラテス監査 (Tier 10: 「監査」自体の網羅性への反問 — 2026-06-13)

前ラウンドの「パリティ監査」を反問した: 「最重要の関数を監査したか、それとも楽な関数だけか?」
答えは後者だった。customs/dark-pattern/eco/predict は見たが、**プロダクトの存在理由である
看板機能 `BuyTimingScorer` を監査していなかった**。また `eval_condition` を
「一致 (Kotlin 側は要追検証)」と言いつつ確認していなかった。

### BuyTimingScorer 監査 (buy_timing_scorer.py vs BuyTimingScorer.kt)
6 シグナルすべてを 1 行ずつ照合 — **コア経路は忠実なパリティ**:
ベース50 / ATL近接(≤0→30,≤0.1→22,≤0.3→12,≥0.9→-15) / トレンド(±) /
定価割引(40/25/10) / ボラティリティ(cv 0.02/0.05/0.25) / 履歴(90/30) /
ダークパターン罰則(-8) / verdict(≥70,≤35) / confidence(90/30) — 全一致。

検出した乖離 2 件 (いずれも記録):
1. **潜在**: Python のダークパターン罰則には `FAKE_SALE → -4` 分岐があるが、Kotlin に該当
   `WarningType` が無く未処理。現状は両言語とも `detect_dark_patterns` が FAKE_SALE を
   生成しないため**不活性**だが、その検出を将来追加すると乖離する。
2. **意図的拡張**: Kotlin は `today != null` のとき `signalUpcomingSale` / `signalSeasonalDow`
   を加算する (Python に無い)。ヘッダの「Python実装と同一式」は `today=null` 経路でのみ真。

### パリティを「文書上の主張」から「実行可能な契約」へ (Python 半分を実装)
Tier 9 で判明した核心 — *parity を強制する機構が無い* — に着手。検証済みオラクルから
**言語中立な JSON fixture を生成する仕組み**を追加した:
- `gen_parity_fixtures.py`: customs(8) / eco(6) / buy_timing(4) の固定入力→期待出力を
  `popcoon_core` から算出し `parity_fixtures.json` に書き出す (計 18 ケース)。
- `test_parity_fixtures.py`: その JSON を読み、オラクルと毎回突き合わせる**能動的セルフチェック**
  (fixture の陳腐化を防ぐ)。→ Python suite 296 → **300 passed**。
- 履歴構築規約 (`product_key='k'`, AMAZON, `recorded_at=2026-01-01+i 日 UTC`) を JSON に明記し、
  Kotlin 側が同一の決定論的構築で再現できるようにした。

### 残り (Kotlin consumer — 故意に未実装)
パリティの**真の強制**には Kotlin 側が同 `parity_fixtures.json` を読んで
`CustomsSimulator`/`EcoEthicsScorer`/`BuyTimingScorer` の出力を照合する必要がある。
だが**コンパイル検証できない Kotlin を投入しない**という本セッションの一貫した方針に従い、
今ラウンドでは書かない (customs バグの再来を避ける)。具体設計だけ確定:
- `parity_fixtures.json` を `app/src/test/resources/parity/` にコピー (生成時に同期、CI で diff チェック)。
- `ParityContractTest.kt` (kotest) が kotlinx.serialization で読み込み、scalar 関数
  (customs/eco) から照合開始。buy_timing は価格列から上記規約で `PriceRecord` を再構築。
- これは SDK/CI 有効化後の最初のタスク。fixture 側は既に検証済みなので Kotlin は照合を書くだけ。

## ソクラテス監査 (Tier 9: 「検証できない」という自分の前提への反問 — 2026-06-13)

前ラウンドで「Android SDK も CI も無いから何も検証できない」と繰り返し主張していた。
ゴール『ソクラテス問答を行い改善する』に従い、**その前提自体を反問**した:
「本当に環境を確認したのか? それとも確認せず思い込んでいるだけか?」

### 反問で判明したこと (前提は誤りだった)
環境を実測すると、検証可能な層が存在した:
- **`popcoon-tdd/` は Python リファレンス実装** (mutation/chaos/differential/metamorphic/fuzzing
  /golden 等の網羅テスト)。**Python は動く** → `pytest` で **290 passed, 1 skipped** を即時取得。
  本セッションが「持っていない」と言い続けた "real signal" は、実は最初から取得可能だった。
- `backend/` は TypeScript Cloudflare Worker (node22 はあるが node_modules 不在 → offline 不可)。
- Gradle は offline 動作可 (8.11.1, kotlin-compiler-embeddable 同梱)。`:app` のみ Android SDK 必須。

### さらなる反問: 「Python parity」は誰が保証しているのか?
プロジェクトの看板は "Python parity" (Kotlin 移植が Python と一致)。だが **検証機構が無い**:
`test_differential.py` は **Python 最適化版 vs Python naive 版**を比較するだけで、コメント自身が
『「Kotlin本体との整合性」の predecessor』と告白している。golden snapshot も Python 内部の
ハッシュ固定のみで、Kotlin はそのfixtureを一切消費していない。→ **Kotlin は黙って乖離し得る。**

### 検証済みオラクルで Kotlin↔Python パリティ監査 (5 関数を読み比べ)
| 関数 | 結果 |
|---|---|
| `predict_price` / `PricePredictionEngine` | ✅ 一致 (Holt α=0.3/β=0.1, IQR, buy_prob, confidence)。Conformal/季節分解は Kotlin 拡張 |
| `detect_dark_patterns` / `DarkPatternDetector` | ✅ 一致 (常設90%/参考1.5x/値上げ1.1x/端数80-99)。drip/text は Kotlin 拡張 |
| `score_eco_ethics` / `EcoEthicsScorer` | ✅ 一致 (国別CO2・労働定数, 式, 丸めすべて一致) |
| `eval_condition` | ✅ 一致 (Kotlin 側は別途要追検証だが式は同型) |
| `simulate_customs` / `CustomsSimulator` | ❌ **乖離 (バグ検出)** |

### 検出バグ: CustomsSimulator の verdict 分岐順 (修正済み)
Kotlin は「`完全一致`」とコメントしつつ、食品/化粧品の `NOT_RECOMMENDED` 判定を
**最優先に繰り上げて**いた。結果、国内価格が設定された食品/化粧品は **価格に関係なく常に
NOT_RECOMMENDED**。検証済み Python は逆で、免税級の掘り出し物 (国内の70%未満) は CHEAPER が勝つ。
- **しかも Kotlin テストがこのバグを固定していた**: `simulate(10k,2k,食品,国内50k)` を
  `NOT_RECOMMENDED` と断言。Python オラクルでは同入力は `CHEAPER` (total=12,000・免税)。
  → 「緑のチェックは結果ではなく仮説」の実例 (テストがバグを承認していた)。
- **対応**: ①Kotlin の `when` を Python の分岐順に修正 ②バグを固定していた Kotlin テストを
  オラクル準拠 (CHEAPER) に訂正し、正しい NOT_RECOMMENDED 経路 (中途半端な節約帯) の
  ケースを追加 ③**Python オラクル側に verdict 分岐順のクロス言語コントラクトを追加**
  (`test_customs_verdict_branch_order`, 6 ケース) → ローカルで実行・検証可能。期待値は
  すべて `popcoon_core` で算出。Python suite: 290 → **296 passed**。

### 残課題 (今後)
- **クロス言語 golden ブリッジ (parity の真の強制)**: Python から言語中立な JSON fixture
  (固定入力→期待出力) を書き出し、Kotlin テストが同 fixture を消費して一致検証する仕組み。
  これで "parity" が文書上の主張から実行可能な契約になる。Python 側生成器は即検証可、
  Kotlin consumer は CI/SDK 有効化後に配線 (今は未コンパイル surface を増やさない)。
- **最重要は依然 CI/SDK の有効化**: `:app` の Kotlin/Compose 層は今も未コンパイル。
  Python 層が緑でも Android 本体の検証は別問題。`git mv ci/android.yml .github/workflows/` が前提。

## ソクラテス監査 (Tier 11: 多言語/アクセシビリティ一貫性への反問 — 2026-06-16)

反問: 「4ロケール対応」を長所として掲げるが、**ロジック層に埋め込まれた表示文字列**は
本当にロケールに追従しているか? data/feature 層の定数は `stringResource` を経由しないため、
端末を英語/韓国語/中国語にしても日本語のまま漏れ出ているのではないか?

### 実装 (今回修正)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 56 | **i18n 漏れ** | `Platform.displayName` は data 層の定数 (`"楽天"`/`"Yahoo!"`) で日本語固定。英語/韓国語/中国語 UI でも商品バッジ・カート集計・セールバナー・カレンダーに「楽天」が出ていた (UI 5箇所) | UI 層に `@Composable Platform.localizedName()` を新設し端末ロケールの `platform_*` を引く。`displayName` はロジック/シリアライズ専用に限定。全4ロケールに `platform_amazon/rakuten/yahoo` を追加。`?.let` ラムダは Composable スコープ外のため `localizedName()` を先に解決する形へ修正 |
| 57 | **i18n 漏れ + 重複** | `verdictA11yLabel` が verdict→語の対応 (買い時/様子見/待ち推奨) を `VerdictBadge` の可視ラベル (`R.string.verdict_*`) と**二重持ち**し、しかも日本語固定。TalkBack が全ロケールで日本語を読み上げていた | 可視ラベルを再利用し `a11y_verdict_score` テンプレートでスコア文を組む方式へ。重複した `verdictA11yLabel` と旧テスト3件を削除 (翻訳が分岐するリスクも消去) |

### 確認した非ギャップ (誤検知防止メモ)
- **検索の in-flight キャンセル**: `SearchViewModel` は `searchJob?.cancel()` + `debounce(300)` +
  `distinctUntilChanged()` + `CancellationException` 再送出で**既に正しく**古い結果の上書きを防いでいる。バグではない。
- **`normalizeOriginCountry` ↔ `EcoEthicsScorer` のキー整合**: 正規化の出力キー (JP/DE/US/CN/VN/BD/IN/KR) と
  `CO2_BY_COUNTRY` の対応国が**完全一致**。到達不能な対応国は無い。バグではない。

### 未着手 (リスク/スコープで今回見送り — 要 CI/SDK 有効化後の検証)
- **ダークパターン警告ラベルの i18n**: `DarkPatternDetector.Warning.label` も日本語固定 (`"常設セール"` 等) で
  可視表示・a11y 両方に漏れている。ただし label は静的 (type ベース) と動的 (`"送料込みで割高 (実質+15%)"`,
  テキスト検出の evidence) が混在し、2つの ViewModel が `List<String>` へ畳んで type を捨てている。
  正しい修正は `WarningType` を UI まで通し `@Composable` で解決する大規模リファクタで、Android 未コンパイル環境では高リスク。
  死蔵中の `darkPatternA11yLabel` (本番呼び出し 0、テスト9件) はこの配線が前提。
- **`CurrencyFormatter.yenAccessible` の `"円"` 固定**: 非 Composable の純関数で Context/locale を持たないため、
  英語 TalkBack でも "1,234円" を読む。価格は常に JPY だが読み上げ語の locale 化は設計変更が要る。
- **`FallbackScraper` の JSON-LD 抽出**: 「最初のキー一致」方式は `offers` 内の `availability`/`price` を
  拾うために**意図的に**ネストを跨ぐが、その副作用で `"brand":{"name":...}` がトップレベル `name` より前に
  来ると商品名を誤抽出し得る。トップレベル限定にすると nested 抽出が壊れるため、正しい修正は実 JSON パーサ
  (kotlinx.serialization) への置換。ktor 結合面の未コンパイルリスクが高く今回見送り。

## 製品分析 (Tier 7: 長所・短所・不足機能の洗い出しと実装)

プロダクトとしての強み・弱み・不足機能を棚卸しし、価値が高く自己完結する
ギャップを実装した。

### 長所 (差別化の核心)
- **買い時判定の独自性**: `BuyTimingScorer` (ATL近接・トレンド・変動率・季節性) +
  `PricePredictionEngine` (Holt線形 + Conformal 区間 + 季節分解) は競合14アプリ非搭載。
- **ダークパターン暴露**: 価格系 + テキスト5カテゴリ検出 + `ReviewTrustScorer` (統計的サクラ検出)。
- **長期コスト可視化**: `TCOCalculator` (消耗品・電力)、`BundlePackDetector` (実質単価)、
  `PointSimulator` (ポイント還元後実質価格)。
- **プライバシー優先**: オンデバイス完結、レビュー本文を端末外に送らない。
- **Python TDD オラクル**: アルゴリズムは Python 正本と差分テストでパリティ保証 (290 tests)。
- **4ロケール対応** (ja/en/ko/zh-rCN)、ウォッチリスト + 目標価格アラート + ウィジェット + バーコード。

### 短所・不足 (今回修正)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 47 | **不足機能** | `EcoEthicsScorer` (CO2・労働権利スコア、Python パリティ・テスト済み) が UI から一切参照されず **死蔵** | 商品詳細に `EthicsCard` を新設し配線。原産国判明時のみ算出 (不明時は無意味な定数のため非表示)。スコアロジックは不変、表示のみ追加 |
| 48 | **不足機能** | ホーム画面ウィジェットの判定が全件 `"NEUTRAL"` 固定で無意味 | `WidgetVerdict` 純関数を新設 (目標到達/追加時比±5% で BUY_NOW・WAIT・NEUTRAL を導出) + 単体テスト。完全な履歴を要する詳細画面スコアラとは別の軽量判定として明示 |
| 49 | **テスト基盤** | `testOptions` に `useJUnitPlatform()` 不在 → Kotest spec は JUnit4 ランナーで **1件も発見されず**、Kotlin 単体テスト群 (200+) が実質未実行だった | `unitTests.all { it.useJUnitPlatform() }` を追加。これで `ci/android.yml` の単体テストジョブが初めて意味を持つ |
| 50 | **CI** | `gradlew` の実行ビットが欠落 (100644) | `100755` に修正。`./gradlew` がフレッシュチェックアウトで動作 |

### 確認した非ギャップ (誤検知防止メモ)
- **目標価格設定 UI**: `TargetPriceDialog` / `TargetPriceChip` / VM 配線が既に存在 — 不足ではない。
- **CI ワークフロー本体**: `ci/android.yml` テンプレートが既に存在 (GitHub App の `workflows` 権限欠如で
  `.github/workflows/` へ push 不可、`ci/README.md` 参照)。リポジトリ管理者が `git mv` で有効化する。
- **`PointSimulator` / `PricePredictionEngine` の整数除算疑い**: いずれも Double 演算 (Kotlin の型昇格) で
  精度損失なし — バグではない。

### 追加実装 (Tier 7 後続)
| # | 分類 | 内容 | 対応 |
|---|------|------|------|
| 51 | **不足機能** | `SaleCalendar` (テスト済み) が検索の当日バナーでしか露出せず、今後のセールを一覧できない (docstring が「Pricey 相当の主要差別化」と明記) | `SaleCalendar.upcomingSales` 純関数+テストを追加し、`SaleCalendarScreen` (開催中/今後の大型セール) を新設。検索トップバーのカレンダーアイコン+当日バナータップで遷移。4ロケール対応 |
| 52 | **並行性バグ** | `SearchViewModel.performSearch` の `async{}` 内 `runCatching { getPriceHistory }.getOrDefault` が `CancellationException` を握り潰し、キャンセル済みの子コルーチンが空履歴でスコア計算を継続 (構造化並行性を破壊) | `.onFailure { if (it is CancellationException) throw it }` を挿入。他全 7 箇所 (各 API クライアント / `BackendClient` / `FallbackScraper` / `CsvExporter` / `PriceSyncWorker`) と挙動を統一 |
| 53 | **不足機能** | `CustomsSimulator` (テスト済み・Python パリティの越境関税/消費税計算) が UI 未配線で死蔵 | 設定「ツール」セクションから開く `CustomsSimulatorScreen` を新設。現地価格/送料/カテゴリ/(任意)国内最安値を入力し、課税価格・関税・消費税・手数料・着払い合計の内訳と判定チップ (国内比較入力時) を表示。ViewModel 不要の純 `remember` 計算。4ロケール対応 (各 240 文字列) |
| 54 | **不足機能 (コアUX)** | `AffiliateUrlBuilder` (Amazon/楽天/Yahoo 三社対応のアフィリエイトタグ注入) が完全実装済みだが、商品詳細画面に購入ボタンが存在せず `product.url` が一切使われていない。ユーザーは買い時スコアを見ても購入に進めない | `ProductDetailScreen` の末尾に「購入ページを開く」`Button` を追加。`AffiliateUrlBuilder.build()` で設定の `affiliateOptin` に応じてURL変換、`Intent.ACTION_VIEW` で開く。アフィリエイト有効時は `#ad` 開示ラベル表示 (景品表示法 8 条)。`UserPreferences` を VM に注入し `DetailUiState.Loaded` に `affiliateOptin` フィールド追加。4ロケール対応 (各 242 文字列) |
## ソクラテス監査 (Tier 8: 自作機能への反問 — 2026-06-13)

「在庫アラート」を実装した直後に、ゴール『ソクラテス問答を行い改善する』に従って
**自分の成果物そのものを反問**した。問い: 「この機能は発火する信号を持っているか?」

### 反問で判明したこと
- `Product.stockCount` は **本番のどのデータ経路でも代入されない幻のフィールド**だった。
  `AmazonPaApiClient` / `RakutenClient` / `YahooClient` / `FallbackScraper` のいずれも
  代入せず、`grep` で確認すると代入は **テストコード内のみ**。backend の `PriceRecord`
  にも在庫フィールドが無い。よって `Product.isInStock` は本番では `realPrice > 0` に縮退する。
- 実装した `PriceSyncWorker` の `nowInStock = latest.realPrice > 0` 代理は **ほぼ常に true** →
  `BACK_IN_STOCK` / `OUT_OF_STOCK` は実データ上ほぼ発火し得ない。
- 同じ幻フィールドに依存する `SortAndFilter` の「在庫切れ除外」(`stockCount == 0`) も
  **以前から不発の死蔵コントロール**だった (今回の反問で副次的に発見)。
- これは本セッションが繰り返し是正してきた死蔵パターン (widget の "NEUTRAL" 固定、
  未表示の EcoEthics スコア) の **再演**。検証 (CI/SDK) が無い中での機能配線が、
  動くソフトではなく「もっともらしい表面積」を生む危険の実例。

### 対応 (#55 改め)
| # | 分類 | 対応 |
|---|------|------|
| 55 | **誠実な撤回** | ユーザー向けの偽の約束 (発火しない「在庫アラート ON」トグル — 自前のダークパターン)、スキーマ変更 (Room v5/MIGRATION_4_5 → v4 に戻す)、`realPrice > 0` の誤った代理を **撤回**。検証済みの純粋ロジック `StockAlertEvaluator` (7 テスト) **のみ残置**し、docstring に「実在庫信号が供給されるまで休眠」「有効化方法」を明記。i18n は 246→242 に復帰 (4ロケール パリティ維持)。撤回の過程で下記「幻フィールド監査」を実施 |

### 幻フィールド監査 (反問から派生した全 sweep)
`stockCount` が氷山の一角ではないか、と疑って `Product` の全フィールドを監査した。
本番の Product は **4 つの生成元** (`AmazonPaApiClient.toProduct` / `RakutenClient` /
`YahooClient` / `FallbackScraper.fetchProduct`) が **手動コンストラクタ呼び出し** で作る
(プラットフォーム固有 DTO を deserialize → `Product(...)` に詰め替え)。よって、
そのコンストラクタ呼び出しで渡されないフィールドは **既定値のまま=幻**になる。

4 生成元が **一つも設定しない** フィールド (常に既定値):
| フィールド | 既定 | 影響を受ける機能 | 実害 |
|---|---|---|---|
| `originCountry` | null | `EcoEthicsCard` (#47) | **本番で常に非表示**。`ProductDetailViewModel:152` が `originCountry?.takeIf{...}` で gate するため、今セッションで「死蔵を蘇生」と記録した #47 は **実際には蘇生していない** |
| `stockCount` | null | 在庫アラート(#55) / `SortAndFilter` 在庫切れ除外 | 既述。発火しない |
| `pointsBack` | 0 | `PointSimulator` の Amazon 経路 | Amazon のポイント還元は常に 0 表示。※ 楽天/Yahoo は固定レートモデル (`PointSimulator:74`) で算出するため **機能する** — 全滅ではない |
| `couponAmount`/`couponCode` | 0/"" | `Product.hasCoupon` / クーポン表示 | クーポン UI は常に非表示 |
| `janCode` | null | 名寄せ/重複統合 (3モール最安) | JAN ベースの名寄せが効かない |
| `subscribePrice` | null | 定期おトク便比較 | 常に非表示 |
| `deliveryDays` | null | 配送日数表示 | 常に非表示 |

**結論**: 在庫アラートは単発のミスではなく、**「リッチ商品インテリジェンス層が、
データ抽出層の出力しないフィールドに依存している」という systemic な乖離**の一症状。
スコアリング/純関数 (Python パリティ済み) は健全だが、その入力が production で枯れている。

### 残課題 (未着手 — 検証優先の方針で「実装せず記録」)
- **データ抽出層の拡充 (最優先・全機能の前提)**: 各クライアントの `toProduct` に
  `originCountry` / `pointsBack`(Amazon) / `couponAmount` / `janCode` / `subscribePrice` /
  `stockCount` の抽出を追加する。これらが入って初めて EcoEthicsCard・在庫アラート・
  クーポン表示・JAN 名寄せ・定期便比較が **本番で生きる**。スコープ大 + ローカル検証不能 (SDK不在)
  のため、CI 有効化後に着手すべき。
- **暫定の正直化**: 上記が入るまで、幻フィールドに依存する UI は「データなし時は非表示」
  ガードを持つこと (EcoEthicsCard は既に gate 済みで害なし。クーポン/在庫も同様)。
  ユーザーに「在りそうで無い」機能を見せない方針を維持。
- **CI 緑化の確認**: 上記 #49 で初めて Kotest が走るため、未実行だった spec に潜在失敗が無いか CI 有効化後に要確認
  (ローカルは Android SDK 不在で検証不可)。

## 適用済み (Tier 6: 並行性・セキュリティ・バグの第4回監査)

データ/キャッシュ・ViewModel・Share・課金 の 4 カテゴリを徹底監査。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 40 | バグ/コンパイル | `SettingsViewModel.launchBillingFlow(activity, offer)` は存在しないメソッド (コンパイルエラー) → `launchPurchase(activity, offer)` に修正 | HIGH |
| 41 | 並行性 | `BillingManager.queryOffers()` の `suspendCancellableCoroutine` で `isActive` ガード不在 → コルーチンキャンセル後に `resume()` が呼ばれ `IllegalStateException` → ガード追加 | HIGH |
| 42 | UX/性能 | `SearchViewModel` が進行中の検索をキャンセルせず新クエリを最大 2 秒待たせる (後行クエリがキュー待ち) → `searchJob?.cancel()` + 新 `Job` で即時切替 | MED |
| 43 | セキュリティ | `UrlClassifier.extractUrl` の `[^\s]+` が 2048 文字超の URL を制限なく返す → `\S{1,2048}` で上限化 | MED |
| 44 | 正当性 | `MainActivity.handleIntent` が `extractUrl` の null 時に raw テキストを `classify` に渡す (URL なし文字列で不定動作) → `?: return` に修正 | MED |
| 45 | 並行性 | `ProductNavCache.put()` が `ConcurrentHashMap` + check-then-act でスレッド非安全 (2スレッドが同時に上限チェックして不整合) → `LinkedHashMap` + `@Synchronized` で原子化、挿入順 FIFO を保証 | MED |
| 46 | 並行性 | `AdviceCache.put()` が `@Synchronized` でない `evictIfNeeded()` を外から呼ぶ不整合パターン → `put()`/`get()` を `@Synchronized` 化、`evictIfNeeded` をインライン化、`ConcurrentHashMap` → `HashMap` に統一 | LOW |

### 監査で確認した非バグ (誤検知防止メモ)
- `PricePredictionEngine.percentile = cleaned.count { it >= current }`: `>= current` は正しい。現価格を下回る件数が多いほど "安い" を意味するが、`>= current` は "現価格以上の履歴件数 / 総数" = 買い時確率 (高 = 安い) と同義 — **反転ではなく正しい計算**。
- `RobotsTxt.endAnchored = anchored && !core.endsWith("*")`: `*$` パターンの場合 `endAnchored=false` になるが、`*` が可変長マッチするので末尾アンカーと同値 — **バグではない**。
- `Trie`: `ReentrantReadWriteLock` で insert (write) / suggest (read) を保護済み — **スレッドセーフ**。

## 適用済み (Tier 5: ライフサイクル・テスト品質・UI一貫性・性能の第3回監査)

ライフサイクル/購読・テスト品質・UI アイコン一貫性・再描画性能の 4 観点で再監査。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 33 | ライフサイクル | `collectAsState` → `collectAsStateWithLifecycle` (6画面・12箇所): バックグラウンド時の購読停止。`lifecycle-runtime-compose 2.8.7` 依存追加 + 全6ファイル移行 | MED |
| 34 | 並行性/correctness | `ProductDetailViewModel` AI 助言上書きが check-then-set 競合 → `_state.update { cur -> if (cur is Loaded && cur.product.key == product.key) ... }` でアトミック化 | MED |
| 35 | 性能/Compose | `PriceChart` の `sortedBy`/`min`/`max` がリコンポジション毎に再計算 → `remember(records)` / `remember(sorted)` で key 変化時のみ再計算 | MED |
| 36 | テスト品質 | `ReviewPrompterLogicTest` がテスト内でロジックを再実装 (回帰検出不能) → `ReviewPrompter.shouldRequestNow()` companion 純関数を抽出してテストが本番呼び出しに | MED |
| 37 | テスト品質 | `NotificationLogicTest` が通知 ID・テキスト・URI をテスト内で再実装 → `LocalNotificationManager.{notificationId, priceAlertText, deepLinkUri}()` companion 純関数を抽出 | MED |
| 38 | テスト品質 | `PriceSyncWorkerLogicTest` が値下がり率を直接計算してテスト — `PriceAlertEvaluator.evaluate()` を直接呼ぶテストに書き換え + `WORK_NAME` を `internal` 公開 | LOW |
| 39 | UI一貫性 | `SearchSuggestions`/`OfflineBanner`/`ProductDetailScreen`/`SearchScreen`/`SettingsScreen` が `Icons.Default.*` を直参照 (AppIcons 方針違反) → 全5ファイルを `AppIcons` 経由に統一 | LOW |

### 今後のバックログ (round 3 で確認、未適用)
- `HapticFeedbackTest` / `BillingManagerTest`: 定数のみ検証でロジック保護なし。
  `HapticFeedback` の vibration効果定数は Android API 由来で変更困難。
  `BillingManager` のSKU/価格はサービス仕様変更時の意図的変更のため現状維持で可。
- `AccessibilityExt.kt` の `verdictA11yLabel` / `darkPatternA11yLabel`: 現状は
  Kotlin 文字列定数で多言語非対応。非 Composable 関数のため `Context.getString()` を
  呼ぶ設計変更が必要 (シグネチャ破壊あり) — 要設計検討。

## 適用済み (Tier 4: 並行性・性能・テスト品質の徹底監査)

ビルド/マニフェスト/セキュリティ設定・並行性/ライフサイクル/性能・テスト網羅の
3 観点で再監査。設定 (network_security_config / backup / FileProvider / manifest) は
全て健全。並行性・性能で確認した問題を修正。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 25 | 性能 | `WatchlistViewModel.smartCart` が総当たり最適化 (最大 200k) をメイン/即時 dispatcher で実行 → `flowOn(Dispatchers.Default)` | HIGH |
| 26 | 並行性 | `PriceSyncWorker` が逐次フェッチ + 常に `success` (バックオフ死蔵)。Semaphore(8) 並列化 + 全件失敗時のみ `retry` | HIGH |
| 27 | 並行性 | `WidgetUpdater.pendingJob` が Main/Worker から無同期 check-then-act 競合 → lock で atomic 化 | HIGH |
| 28 | 並行性/leak | `BackendClient.postPriceAsync` が検索結果ごとに無制限 launch (≈90 並行 POST、応答未消費) → `postPricesAsync(List)` で 1 コルーチン順次送信 + `bodyAsText` で接続解放 | HIGH |
| 29 | 性能 | `SearchViewModel` がグループごとに価格履歴を逐次取得 → `async`/`awaitAll` で並列化 | MED |
| 30 | 性能 | `FallbackScraper` が Regex を呼び出しごとに再コンパイル → companion 定数 + キー別キャッシュ。レート制限ゲートを `compute` で atomic 化 | MED |
| 31 | バグ/テスト | `PopcoonWidget` の楽天セール分岐に到達不能な day=5。純関数 `PopcoonWidgetLogic` に抽出して修正、テストを本番呼び出しに | LOW |
| 32 | テスト | `Product`/`Platform` の派生プロパティと `fromId` フォールバック契約 (未知→AMAZON) を `ProductTest` で固定 | — |

### 今後のバックログ (round 2 で確認、未適用)
- `collectAsState` → `collectAsStateWithLifecycle` (6 画面 12 箇所): バックグラウンド時の
  購読停止。要 `androidx.lifecycle:lifecycle-runtime-compose` 依存追加 (CI で要検証)。
- 弱いテスト (本番ロジックをテスト内に再実装し回帰検出不能): `PriceSyncWorkerLogicTest`,
  `NotificationLogicTest`, `ReviewPrompterLogicTest`, `HapticFeedbackTest`, `BillingManagerTest`
  → 純関数を本番側に抽出して本番呼び出しに (Widget は #31 で対応済み)。
- AppIcons 集約方針の徹底 (画面の直 `Icons.Default.*` を `AppIcons` 経由に)。
- `ProductDetailViewModel` の AI 助言上書きを `_state.update{}` + productKey 一致確認に。

## 適用済み (Tier 3: カテゴリ別徹底監査)

プロダクトを 5 カテゴリ (データ&永続化 / 価格アルゴリズム / 消費者保護 / UI・Compose /
ウォッチリスト・カート・課金・バックグラウンド) に分割し、各層を実コードまで精査。
CI も Android SDK も無く Kotlin が一度もコンパイルされていないため、**コンパイル不能
バグが多数潜伏**していた。確認済みのものを全て修正 (各々テスト付きまたは inspection 検証)。

| # | カテゴリ | 内容 | 重大度 |
|---|---------|------|--------|
| 13 | データ | `AmazonPaApiClient.SearchItemsRequest` の primary/secondary コンストラクタが同一 JVM シグネチャ → conflicting overloads (コンパイル不能) | HIGH |
| 14 | データ | `PriceRecord` が `Instant` を `@Serializable` するもシリアライザ不在 → コンパイル不能。`InstantIso8601Serializer` 追加 + 往復テスト | HIGH |
| 15 | DI | Hilt 二重バインディング **7 件** (`@Inject` + `@Provides`): AdviceCache, BuyingAdvisor, BackendClient, PriceHistoryCsvExporter, StartupTracker, ReviewPrompter, ConnectivityObserver → 全て Dagger コンパイル不能。冗長 `@Provides` を削除 | HIGH |
| 16 | データ | `YahooClient` が `premiumPrice` (会員割引価格) を list price に使い割引表示が反転 → `defaultPrice` に修正 | MED |
| 17 | アルゴリズム | `SaleCalendar.nextMajorSale` が当年のみ生成 → 12/7–31 に null。翌年分を併合 + 年境界回帰テスト | MED |
| 18 | 課金 | `AffiliateUrlBuilder` 楽天リンクが商品 URL を未エンコードで `pc=` に連結 → リンク破損 (収益逸失)。`Uri.encode` | MED |
| 19 | クラッシュ | `PrivacyCrashReporter` が ① クラッシュ時 fire-and-forget で送信未達 ② 保存形式と送信形式が不一致。永続化→次回起動送信パターンに修正 | MED |
| 20 | UI/i18n | ProductDetail 価格カード・Watchlist 空状態・Barcode エラー 4 箇所の日本語直書きをリソース化 (en/ko/zh 対応) | MED |
| 21 | UI/compose | `SearchSuggestions` の LazyColumn に安定キー付与 | MED |
| 22 | セキュリティ | CSV エクスポートの数式インジェクション対策 (`=+-@` 始まりに `'` 前置) + テスト | LOW |
| 23 | プライバシー | `PopcoonLogger` が Throwable を未サニタイズで Logcat 出力 → サニタイズ連結に修正 | LOW |
| 24 | UI/a11y | 検索画面のお気に入りボタンが「保存」と誤読み上げ → `nav_watchlist` | LOW |

消費者保護カテゴリ (darkpattern/review/ethics) は Python オラクルとのパリティ含め
**全て CLEAN** (監査で確認、修正不要)。ロケール 4 言語のキー集合も一致を確認。

### 監査で確認した非バグ (誤検知防止メモ)
`PointSimulator` 0除算 / `ProductMatcher` janCode 欠落 / `BundlePackDetector` 0除算 /
`TCOCalculator` 負値 tcoPerMonth — いずれも**存在しない** (ガード済み or 到達不能)。

## 適用済み (Tier 1: build + correctness + safety)

| # | 内容 | 主な変更 |
|---|------|----------|
| 1 | **コンパイルブロッカー修正**: `Pop_TealDark` がどこにも定義されず参照されていた (現状ビルド不能) | `ui/theme/Theme.kt` |
| 2 | **全 Ktor クライアントに HttpTimeout 追加** (デフォルト 100s ハングを防止) | network/* , repository/BackendClient, ai/BuyingAdvisor, crash/PrivacyCrashReporter |
| 3 | **DB 破壊的マイグレーションを debug 限定化** (release でのユーザーデータ消失防止) | `di/DatabaseModule.kt` |
| 4 | **robots.txt 遵守を実装** (doc は「尊重」と書きつつ未実装だった) + 純関数パーサと単体テスト | `network/FallbackScraper.kt`, `network/RobotsTxt.kt` |
| 5 | **EcoEthicsScorer の Kotlin↔Python 乖離を解消** (docstring は「完全一致」と主張も別式だった)。Python oracle に一致させ、絶対値パリティテストで固定 | `feature/ethics/EcoEthicsScorer.kt` + test |
| 6 | **PopcoonLogger の秘密情報リダクション強化** (旧 regex はマルチパラメータ URL で API キーを伏せ損ねる) | `core/PopcoonLogger.kt` + test |
| 7 | **ハードコード日本語 UI 文字列をリソース化** (en/ko/zh で日本語露出) | `ui/components/*`, `res/values*/strings.xml` |

## 適用済み (Tier 2: 競合調査ベースの機能・基盤)

GitHub 上の同種 OSS 価格追跡アプリ (CamelCamelCamel, Keepa, ShopSense, Pricewise,
jeevandhakal/price_comparison, edent/Amazon-Wishlist-Pricedrop-Alert 等) を調査し、
Popcoon に欠けていた最も普遍的な機能と、その検証基盤を実装した。

| # | 内容 | 主な変更 |
|---|------|----------|
| 8 | **希望価格 (target price) アラート**: 競合が普遍的に持つ「指定価格まで下がったら通知」。従来は相対値下がり率のみで、予算までの緩やかな下落を取りこぼしていた。目標到達は率の閾値を無視し最優先で通知。純関数 `PriceAlertEvaluator` (18 テスト) + Room v1→v2 マイグレーション + ウォッチリスト UI (チップ/ダイアログ) | `feature/notification/PriceAlertEvaluator.kt`, `data/db/PopcoonDatabase.kt`, `di/DatabaseModule.kt`, `worker/PriceSyncWorker.kt`, `ui/components/TargetPriceDialog.kt`, `ui/screens/watchlist/*`, `res/values*/strings.xml` |
| 9 | **CI ワークフロー新設**: TDD 重視の設計にも関わらず CI が存在せず、ローカル Android SDK も無いため Kotlin が一度もコンパイル検証されていなかった。detekt/lint/単体テスト/assemble + Python オラクルを実行。App の `workflows` 権限制約のため `ci/` にテンプレートとして配置 (管理者が 1 行で有効化) | `ci/android.yml`, `ci/README.md` |
| 10 | **潜在コンパイルエラー修正**: `PriceSyncWorker` が `CurrencyFormatter` を import せず参照していた (HEAD でも壊れていた) | `worker/PriceSyncWorker.kt` |

## 検証
- Python TDD: `cd popcoon-tdd && python3 -m pytest -q` (290 passed, 1 skipped)。
- Kotlin: ローカルに Android SDK が無いため inspection で担保。`ci/android.yml` を
  `.github/workflows/` に配置すると lint / detekt / `testDebugUnitTest` / assemble が
  自動検証する (有効化手順は `ci/README.md`)。

## 今後のバックログ (未適用)

### 信頼性・品質 (Tier 2)
- ProductRepository に API レート制限 / 指数バックオフ / サーキットブレーカ。
- FallbackScraper の JSON-LD 抽出を regex から正規 JSON パーサへ (数値 price・エスケープ対応)。
- 検索の in-flight キャンセル (古いクエリ結果の上書き防止) と SearchScreen のリトライ UI。
- AdviceCache の TTL / 退避テスト、`put` の同期化 (上限超過の競合)。
- ProductDetailViewModel / WatchlistViewModel / SettingsViewModel の単体テスト追加。
- CI: ~~ワークフロー新設~~ (#9 で実装、要有効化) → 次は detekt / Kover カバレッジを
  マージゲート化、baseline profile 検証。
- a11y 文字列: ~~`VerdictBadge.kt` の i18n~~ (Tier 11 #57 で実装、`verdictA11yLabel` 廃止) →
  残りは ① `darkPatternA11yLabel` (死蔵・日本語固定、警告 type を UI まで通す配線が前提) と
  ② `CurrencyFormatter.yenAccessible` の `"円"` 固定 (非 Composable のため locale 化に設計変更が要る)。
- Platform 表示名: ~~data 層 `displayName` 固定の i18n 漏れ~~ → Tier 11 #56 で `Platform.localizedName()` 実装済み。
- ダークパターン警告ラベルの i18n: `Warning.label` 日本語固定。`WarningType` を UI へ通す大規模リファクタが前提 (Tier 11 参照)。

### 競合調査バックログ (同種 OSS 価格追跡アプリ由来)
GitHub 調査で確認した、競合にあり Popcoon に未実装だった機能。インパクト順:
- ~~**ウォッチリストの整理**: ソート・並べ替え~~ → 実装済み (#11 `WatchlistSort`:
  追加/価格/割引率/名前/目標到達順、永続化 + 並べ替えメニュー)。タグ付け/
  カテゴリ分けは未着手。
- ~~**URL 貼り付けで追加**: 共有インテント (`ACTION_SEND`)~~ → 既存 (`feature/share/
  UrlClassifier`, MainActivity で配線済み)。
- **クーポン/プロモコード集約**と決済前の自動適用 (Honey, Karma の中核機能)。
- **在庫アラート**: 再入荷/在庫切れ通知。純関数 `StockAlertEvaluator` は検証済みで用意済みだが、
  `Product.stockCount` が production で代入されない幻フィールドのため **配線は前提待ち** (Tier 8 #55 参照)。
  scraper/backend が実在庫信号を返すのが前提。
- ~~**「追加時からの変動」表示**: ウォッチ追加時価格を基準に変動を可視化~~ → 実装済み
  (#12 `WatchlistPriceDelta` + Room v2→v3 `addedPrice` カラム + 行内表示)。
- **値下がりフィード**: ウォッチ外の急落商品を一覧する発見導線 (要 backend)。
- **多通貨対応**: 越境購入 (CustomsSimulator) と整合する通貨換算表示。

### 配線中に発見・修正した潜在バグ (CI 不在で未検出だったコンパイルエラー)
- `PriceSyncWorker` が `CurrencyFormatter` を import せず参照 (#10 で修正)。
- `UserPreferences` が `@Inject constructor` と `SettingsModule.@Provides` の二重
  バインディング (Hilt コンパイルエラー)。冗長な module provider を削除 (#11)。

### アルゴリズム (Tier 3)
- 価格予測を Holt 線形から Holt-Winters (季節性) へ。
- Kotlin↔Python 差分/契約テスト基盤 (CustomsSimulator / TCOCalculator / PricePredictionEngine /
  BuyTimingScorer の出力一致を CI で保証)。
- Kotlin 側ミューテーションテスト (Python は 100% kill 達成済み)。

### 設計判断 (要検討)
- TCOCalculator の残価が長期で 0% になる (現実は 5-15%) — 下限を設けるか要件確認。
- CustomsSimulator の関税丸めは truncate (日本の実務は切り上げが一般的) — 仕様確認。
