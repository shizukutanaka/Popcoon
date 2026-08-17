package io.github.shizukutanaka.popcoon.data.network

/**
 * JSON-LD (schema.org) のフラットな `"key":"value"` 文字列値を抽出する純関数。
 *
 * ktor 非依存に独立させ、Android SDK 無しでコンパイル・実行検証できる
 * (popcoon-tdd/kotlin_parity/run_jsonld.sh が**実関数**を直接呼ぶ)。
 * 以前は本関数が ktor 結合した FallbackScraper 内にあり、ハーネスは正規表現を**複製**して
 * 検証していた (複製がドリフトしても気付けない「検証の演劇」)。ここへ抽出し
 * FallbackScraper.extractJsonString は本関数へ委譲する。
 *
 * `(?:[^"\\]|\\.)*`: バックスラッシュエスケープ (\", \\) を含む値に対応。
 * `[^"\\]` (シングルクォートを除外しない) により "John's Store" のようなアポストロフィ入り値も抽出。
 * キーごとに Regex を 1 度だけコンパイルしてキャッシュする (固定キーの再コンパイル回避)。
 */
private val jsonLdKeyPatternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

internal fun extractJsonLdString(json: String, key: String): String? {
    val pattern = jsonLdKeyPatternCache.getOrPut(key) {
        Regex("""["']$key["']\s*:\s*["']((?:[^"\\]|\\.)*)["']""")
    }
    return pattern.find(json)?.groupValues?.get(1)
}

/**
 * JSON-LD の**引用符なし数値**値 (`"price": 1980` / `"price": 38.99`) を抽出する純関数。
 *
 * schema.org の `price`/`lowPrice`/`highPrice` は文字列 (`"1980"`) でも数値 (`1980`) でも
 * 提供され得る (Google の公式例は `"price": 38.99` という数値表記)。`extractJsonLdString` は
 * 引用符付き文字列しか拾えないため、数値表記の商品は price 抽出が null になり、
 * FallbackScraper が realPrice=0 の壊れた Product を生成していた (静かな値喪失バグ)。
 *
 * 本関数は colon の直後に引用符が**来ない**数値のみにマッチする (`-?\d+(\.\d+)?`)。
 * 引用符付き値 (`"price":"1980"`) は `\s*` の後に `"` が来てマッチしないため、
 * 文字列抽出との役割分担が崩れない。キーごとに Regex を 1 度だけコンパイルしてキャッシュする。
 */
private val jsonLdNumberPatternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

internal fun extractJsonLdNumber(json: String, key: String): String? {
    val pattern = jsonLdNumberPatternCache.getOrPut(key) {
        Regex("""["']$key["']\s*:\s*(-?\d+(?:\.\d+)?)""")
    }
    return pattern.find(json)?.groupValues?.get(1)
}

/**
 * JSON-LD で値が**文字列配列**になっているキーの先頭要素を抽出する純関数
 * (`"image":["https://a.jpg","https://b.jpg"]` → `https://a.jpg`)。
 *
 * schema.org の `image` は単一文字列・文字列配列・ImageObject のいずれでも提供され得る。
 * Amazon/楽天の商品ページは配列形式が多く、`extractJsonLdString` (colon 直後に引用符を要求) は
 * 配列 (`:[`) にマッチしないため、フォールバックスクレイプ商品の imageUrl が常に null になり
 * サムネイルが表示されなかった。本関数は colon → `[` → 先頭の引用符付き要素を拾う。
 *
 * キーごとに Regex を 1 度だけコンパイルしてキャッシュする。
 */
private val jsonLdArrayPatternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

internal fun extractJsonLdArrayFirst(json: String, key: String): String? {
    val pattern = jsonLdArrayPatternCache.getOrPut(key) {
        Regex("""["']$key["']\s*:\s*\[\s*["']((?:[^"\\]|\\.)*)["']""")
    }
    return pattern.find(json)?.groupValues?.get(1)
}

/**
 * JSON-LD で値が**ネストしたオブジェクト**になっているキーの内側フィールドを抽出する純関数
 * (`"brand":{"@type":"Brand","name":"Sony"}` を outerKey=brand/innerKey=name で → `Sony`)。
 *
 * schema.org の `brand` は文字列 (`"brand":"Sony"`) でも Brand オブジェクトでも提供され得る。
 * `extractJsonLdString(json,"brand")` は `:{` のオブジェクトにマッチせず brand が null になり、
 * ProductMatcher の横断名寄せ (brand を一致シグナルに使う) が弱まっていた。
 *
 * `[^{}]*?` で同一オブジェクト内 (ネスト波カッコ無し前提) に限定して innerKey を拾う。
 * outerKey/innerKey の組をキーに Regex を 1 度だけコンパイルしてキャッシュする。
 */
private val jsonLdObjectPatternCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

internal fun extractJsonLdObjectField(json: String, outerKey: String, innerKey: String): String? {
    val pattern = jsonLdObjectPatternCache.getOrPut("$outerKey/$innerKey") {
        Regex("""["']$outerKey["']\s*:\s*\{[^{}]*?["']$innerKey["']\s*:\s*["']((?:[^"\\]|\\.)*)["']""")
    }
    return pattern.find(json)?.groupValues?.get(1)
}

/**
 * schema.org の Offer.availability (JSON-LD) を Product.stockCount に変換する純粋関数。
 *
 * ktor 非依存に独立させ、Android SDK 無しでコンパイル・実行検証できる
 * (popcoon-tdd/kotlin_parity/run_jsonld.sh)。FallbackScraper の parseProductSchema から使う。
 *
 * schema.org の在庫切れ系の値 (OutOfStock / SoldOut / Discontinued) を 0 とみなし、
 * SortAndFilter の在庫切れ除外を機能させる。InStock 等・不明は null (= 在庫あり扱い)。
 * 値は "OutOfStock" でも "https://schema.org/OutOfStock" のような URL 形式でも受け付ける。
 */
internal fun stockFromAvailability(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val token = raw.substringAfterLast('/').trim().lowercase()  // URL 形式なら末尾要素
    return when (token) {
        "outofstock", "soldout", "discontinued" -> 0
        else -> null
    }
}

/**
 * 原産国の表記ゆれ (ISO-2/ISO-3/英語名/日本語名) を EcoEthicsScorer が期待する ISO-2 キー
 * (JP/DE/US/CN/VN/BD/IN/KR) に正規化する。対応外・不明は null。
 *
 * 背景 (プロデューサ/コンシューマのキー不一致): EcoEthicsScorer は `CO2_BY_COUNTRY[origin]` の
 * 文字列キー検索だが、データ層は originCountry を **一切設定していなかった** (eco 機能が実データで死亡)。
 * JSON-LD の schema.org `countryOfOrigin` から拾い、ここで正規化して初めて eco スコアが動く。
 * 正規化が無いと "Japan"/"日本"/"JPN" がキー "JP" に一致せず、既定値 0.60 に黙って落ちる。
 */
internal fun normalizeOriginCountry(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (raw.substringAfterLast('/').trim().lowercase()) {
        "jp", "jpn", "japan", "日本", "にほん", "ニホン" -> "JP"
        "de", "deu", "ger", "germany", "ドイツ", "独", "独逸" -> "DE"
        "us", "usa", "united states", "united states of america", "america",
        "アメリカ", "米国", "米" -> "US"
        "cn", "chn", "china", "中国", "中華人民共和国", "中" -> "CN"
        "vn", "vnm", "vietnam", "viet nam", "ベトナム", "越南" -> "VN"
        "bd", "bgd", "bangladesh", "バングラデシュ" -> "BD"
        "in", "ind", "india", "インド", "印度" -> "IN"
        "kr", "kor", "korea", "south korea", "republic of korea",
        "韓国", "大韓民国", "韓" -> "KR"
        else -> null
    }
}

/**
 * schema.org `gtin13`/`gtin`/`gtin8` (= JAN/EAN/UPC バーコード) を Product.janCode 用に正規化する。
 * 数字のみ・長さ 8/12/13/14 桁 (JAN-8 / UPC-12 / JAN-13 / GTIN-14) なら採用、それ以外は null。
 *
 * 背景 (もう一つの死んだ継ぎ目): ProductMatcher は janCode 一致を**最優先の確実シグナル** (similarity=1.0)
 * として使うが、どのプロデューサも janCode を設定しておらず、その経路は常に死んでいた。
 * JSON-LD の gtin から復元して初めて横断名寄せの「バーコード完全一致」が機能する。
 */
internal fun normalizeGtin(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val digits = raw.trim().filter { it in '0'..'9' }  // ASCII 数字のみ (ハイフン等を除去)
    return when (digits.length) {
        8, 12, 13, 14 -> digits
        else -> null
    }
}

/**
 * Amazon PA-API 5.0 `Offers.Listings.Availability` (Type + Message) を Product.stockCount に変換する。
 *
 * 背景 (三つ目の死んだ継ぎ目): stockCount は Rakuten/Yahoo では availability/inStock から復元済みだが、
 * **最大プラットフォームの Amazon 経路では常に null** で、在庫切れ除外フィルタ・在庫アラートが
 * Amazon 商品に対して死蔵していた。PA-API は Availability を返すのに従来 DTO で取りこぼしていた。
 *
 * 在庫切れ (Type=OutOfStock/SoldOut、または Message に明確な在庫切れ語) のみ 0。
 * 「通常 N 日以内に発送」等のバックオーダー表記は在庫あり扱いで null (Rakuten/Yahoo と同じ保守方針)。
 * amazon.co.jp は Message を日本語で返すため和英両方の語を判定する。
 */
internal fun stockFromAmazonAvailability(type: String?, message: String?): Int? {
    val t = type?.trim()?.lowercase().orEmpty()
    if (t == "outofstock" || t == "soldout") return 0
    val m = message?.trim()?.lowercase().orEmpty()  // 日本語は lowercase 無影響
    if (m.isEmpty()) return null
    val outOfStockMarkers = listOf(
        "在庫切れ", "在庫なし", "入荷未定", "お取り扱いできません", "取り扱いを終了",
        "unavailable", "out of stock",
    )
    return if (outOfStockMarkers.any { m.contains(it) }) 0 else null
}

/**
 * 価格文字列 → 円 (Long)。抽出できない・0 以下なら null。
 *
 * `"1,980"` / `"¥1,980"` / `"1980円"` / 全角 `"１９８０"` / `"38.99"` を受ける。
 * **0 以下は null を返す** — 価格 0 は「無料商品」ではなく「取得失敗」であり、
 * 呼び出し側が失敗として扱えるようにするため (下記 extractHtmlPrice / FallbackScraper 参照)。
 */
internal fun parsePriceToLong(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
    val m = Regex("""-?\d[\d,]*(?:\.\d+)?""").find(normalized) ?: return null
    val v = m.value.replace(",", "").toDoubleOrNull() ?: return null
    val cents = v.toLong()
    return if (cents > 0L) cents else null
}

// microdata / OpenGraph の価格マーカー。属性順は実サイトでまちまちなので
// 「同一タグ内にマーカーと content 属性がある」ことだけを条件にする。
private val HTML_PRICE_MARKERS = listOf(
    "itemprop=[\"']price[\"']",
    "property=[\"']product:price:amount[\"']",
    "property=[\"']og:price:amount[\"']",
    "name=[\"']twitter:data1[\"']",
)

private val htmlPriceTagCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

/**
 * JSON-LD 以外の経路から価格を拾う **フォールバック戦略群** (2026-08 リサーチ)。
 *
 * 関連ソフトウェア調査で、自己ホスト型価格追跡 PriceGhost が「独立した抽出方式を
 * 複数並列に走らせて突き合わせる」設計を採っていることを確認した。Popcoon の
 * FallbackScraper は **JSON-LD 単独** で、Amazon PA-API 5.0 廃止 (2026-05-15) 後は
 * これが Amazon 商品ページの実質的な唯一のデータ源になっている。JSON-LD が無い/
 * price を含まないページでは価格が取れず、しかも従来は 0 円の Product を捏造していた。
 *
 * 戦略の優先順:
 *  1. microdata  `<span itemprop="price" content="1980">`
 *  2. OpenGraph  `<meta property="product:price:amount" content="1980">`
 *  3. OpenGraph  `<meta property="og:price:amount" content="1980">`
 *  4. Twitter card `<meta name="twitter:data1" content="¥1,980">`
 * 最初に **正の値** が取れた戦略を採用する (0 や解析不能はスキップして次へ)。
 * 全滅なら null — 呼び出し側は「取得失敗」として扱うこと。
 */
internal fun extractHtmlPrice(html: String): Long? {
    for (marker in HTML_PRICE_MARKERS) {
        val tagPattern = htmlPriceTagCache.getOrPut(marker) {
            Regex("""<[^>]*$marker[^>]*>""", RegexOption.IGNORE_CASE)
        }
        for (tag in tagPattern.findAll(html)) {
            val content = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(tag.value)?.groupValues?.get(1)
            parsePriceToLong(content)?.let { return it }
        }
    }
    return null
}
