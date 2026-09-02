package io.github.shizukutanaka.popcoon.feature.export

// PriceHistoryCsvExporter.kt (android.content / FileProvider) から切り出した純関数。
// 同居していると Android 依存に巻き込まれて実コンパイルも kotest (CsvEscapeTest) も
// 走らなかった。同一パッケージなので呼び出し側は無変更。

/** 表計算ソフトで数式として解釈され得る先頭文字。 */
internal const val CSV_FORMULA_TRIGGERS = "=+-@\t\r"

/**
 * CSV フィールドを RFC 4180 準拠でエスケープし、数式インジェクションも防ぐ。
 * internal にしてテストから直接呼べるようにする。
 */
internal fun String.csvEscape(): String {
    // CSV インジェクション対策 (1): 数式起動文字で始まるフィールドは ' を前置
    val guarded = if (isNotEmpty() && first() in CSV_FORMULA_TRIGGERS) "'$this" else this
    // CSV インジェクション対策 (2): ダブルクォートをエスケープしてフィールドをクォート
    val escaped = guarded.replace("\"", "\"\"")
    return "\"$escaped\""
}
