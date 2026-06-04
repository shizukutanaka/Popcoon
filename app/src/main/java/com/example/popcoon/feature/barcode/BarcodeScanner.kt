package com.example.popcoon.feature.barcode

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * バーコード/JANコード/QRコードのスキャナ。
 *
 * 同種ソフト調査結果:
 *  - 最安値.com: 5秒で読み取り完了 (最大の差別化要素)
 *  - プライシー: バーコードスキャン対応
 *  - Keepa: built-in barcode scanner
 *  - 価格.com: バーコード検索機能
 *
 * Popcoon 実装方針 (Google Code Scanner 採用):
 *  - **CAMERA 権限不要** (Google Play Services が代行)
 *  - on-device 処理 (画像は Google に送られない)
 *  - UI 既製 (カスタム Camera/Preview 不要)
 *  - 自動ズーム機能あり (16.1.0+)
 *  - 日本の JAN コード (EAN_13/EAN_8/CODE_128 等) フル対応
 *
 * ML Kit Barcode Scanning API ではなく Code Scanner を選んだ理由:
 *  - 権限ゼロ → ユーザー摩擦最小
 *  - 既製 UI → 開発工数最小
 *  - on-device → プライバシー方針と一致
 */
class BarcodeScanner {

    /**
     * バーコード値 + フォーマット名 (検索クエリ生成用)
     */
    data class ScanResult(
        val rawValue: String,
        val formatName: String,
        val isJanLike: Boolean,    // JAN/EAN/UPC = 商品検索可能
        val isUrl: Boolean,        // URL = Share Intent 経由で classify 可能
    )

    private var scanner: GmsBarcodeScanner? = null
    private var activity: Activity? = null

    /**
     * Activity を渡して使用準備。
     * Compose から使う場合は LocalContext を Activity に cast。
     */
    fun bind(activity: Activity) {
        this.activity = activity
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX,
            )
            .enableAutoZoom()
            .build()
        scanner = GmsBarcodeScanning.getClient(activity, options)
    }

    /**
     * スキャナ起動。Google Code Scanner UI を表示し、
     * バーコード読み取り完了で Task が成功する。
     *
     * 失敗ケース:
     *  - ユーザーがキャンセル
     *  - Google Play Services が古い
     *  - スキャナモジュール未インストール (Play Services が自動 download)
     */
    fun startScan(): Task<Barcode>? {
        return scanner?.startScan()
    }

    companion object {
        /**
         * Barcode → ScanResult への変換 (pure function、テスト容易)。
         */
        fun fromBarcode(barcode: Barcode): ScanResult {
            val raw = barcode.rawValue ?: ""
            val format = formatName(barcode.format)
            val isJan = barcode.format in setOf(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
            )
            val isUrl = raw.startsWith("http://") || raw.startsWith("https://")
            return ScanResult(
                rawValue = raw,
                formatName = format,
                isJanLike = isJan,
                isUrl = isUrl,
            )
        }

        private fun formatName(format: Int): String = when (format) {
            Barcode.FORMAT_EAN_13 -> "EAN-13/JAN"
            Barcode.FORMAT_EAN_8 -> "EAN-8/JAN"
            Barcode.FORMAT_UPC_A -> "UPC-A"
            Barcode.FORMAT_UPC_E -> "UPC-E"
            Barcode.FORMAT_CODE_128 -> "Code 128"
            Barcode.FORMAT_CODE_39 -> "Code 39"
            Barcode.FORMAT_QR_CODE -> "QR"
            Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
            else -> "Unknown"
        }
    }
}
