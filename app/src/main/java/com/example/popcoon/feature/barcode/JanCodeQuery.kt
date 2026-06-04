package com.example.popcoon.feature.barcode

/**
 * JAN/EAN コードの妥当性検証 + 検索クエリ生成。
 *
 * JAN コードの構造:
 *  - 13桁: 国コード(2-3) + メーカー(4-6) + 商品(3-5) + チェックデジット(1)
 *  - 8桁: 短縮版
 *  - 日本: 49xxxx... または 45xxxx...
 *
 * 各 EC API 別のクエリ:
 *  - Amazon PA-API: SearchItems の Keywords パラメータに JAN を渡す
 *  - 楽天 Ichiba: keyword パラメータ (JAN 直接対応)
 *  - Yahoo!ショッピング: query パラメータ (JAN 対応)
 */
object JanCodeQuery {

    /**
     * JAN-13 のチェックデジット計算。
     * 標準アルゴリズム (奇数桁を1倍、偶数桁を3倍して合計、10で割った余りを10から引く)。
     */
    fun isValidJan13(code: String): Boolean {
        if (code.length != 13) return false
        if (!code.all { it.isDigit() }) return false

        val digits = code.map { it.digitToInt() }
        val checkDigit = digits.last()
        val sum = digits.dropLast(1).foldIndexed(0) { i, acc, d ->
            acc + d * if (i % 2 == 0) 1 else 3
        }
        val computed = (10 - sum % 10) % 10
        return checkDigit == computed
    }

    /** JAN-8 のチェックデジット計算 (奇数桁3倍、偶数桁1倍) */
    fun isValidJan8(code: String): Boolean {
        if (code.length != 8) return false
        if (!code.all { it.isDigit() }) return false

        val digits = code.map { it.digitToInt() }
        val checkDigit = digits.last()
        val sum = digits.dropLast(1).foldIndexed(0) { i, acc, d ->
            acc + d * if (i % 2 == 0) 3 else 1
        }
        val computed = (10 - sum % 10) % 10
        return checkDigit == computed
    }

    /**
     * 国コード判定。
     * 日本: 45xxxx, 49xxxx
     * 米国/カナダ: 0xxxxx
     */
    fun countryFromJan13(code: String): String? {
        if (code.length != 13) return null
        return when {
            code.startsWith("45") || code.startsWith("49") -> "JP"
            code.startsWith("0") -> "US/CA"
            code.startsWith("4") -> "DE"
            code.startsWith("5") -> "UK"
            code.startsWith("6") -> "FR"
            code.startsWith("8") -> "PL/AT"
            else -> null
        }
    }

    /**
     * バーコード値から検索クエリ生成。
     * - 13桁/8桁の有効JAN: そのまま検索クエリに
     * - 無効: null (UIでエラー表示)
     */
    fun toSearchQuery(rawValue: String): String? {
        val trimmed = rawValue.trim()
        return when {
            isValidJan13(trimmed) -> trimmed
            isValidJan8(trimmed) -> trimmed
            // UPC は JAN-13 に変換可能 (先頭に 0 を追加)
            trimmed.length == 12 && trimmed.all { it.isDigit() } -> {
                val asJan = "0$trimmed"
                if (isValidJan13(asJan)) asJan else null
            }
            else -> null
        }
    }
}
