package io.github.shizukutanaka.popcoon.feature.export

import io.github.shizukutanaka.popcoon.data.db.WatchlistItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * WatchlistBackupManager の純粋な変換ロジックのテスト。
 * Context/DAO/Uri を要する export()/import() 本体は Android 依存のため対象外とし、
 * 往復の正しさを左右する DTO 変換 + JSON シリアライズのみを検証する。
 */
class WatchlistBackupManagerTest : StringSpec({

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val sample = WatchlistItem(
        productKey = "amazon:B0TEST001",
        sku = "B0TEST001",
        title = "テスト商品",
        platform = "amazon",
        realPrice = 3980,
        listPrice = 4980,
        url = "https://amazon.co.jp/dp/B0TEST001",
        imageUrl = "https://example.com/img.jpg",
        addedAt = 1_700_000_000_000L,
        targetPrice = 3000,
        addedPrice = 4500,
        stockAlertEnabled = true,
        previousInStock = true,
        tag = "ガジェット",
    )

    "toBackupEntry → toWatchlistItem は主要フィールドを保持する (previousInStock を除く)" {
        val restored = sample.toBackupEntry().toWatchlistItem()
        restored shouldBe sample.copy(previousInStock = null)
    }

    "tag (フォルダ分類) もバックアップ・復元される" {
        sample.toBackupEntry().toWatchlistItem().tag shouldBe "ガジェット"
    }

    "旧バックアップ (tag 列が無い) は tag=null として復元される (前方互換性)" {
        val minimalJson = """
            {"productKey":"amazon:OLD","sku":"OLD","title":"旧商品","platform":"amazon",
             "realPrice":1000,"listPrice":1500,"url":"https://example.com"}
        """.trimIndent()
        val decoded = json.decodeFromString(WatchlistBackupEntry.serializer(), minimalJson)
        decoded.tag.shouldBeNull()
    }

    "previousInStock (内部同期状態) は意図的にバックアップに含まれない" {
        sample.toBackupEntry().toWatchlistItem().previousInStock.shouldBeNull()
    }

    "JSON エンコード→デコードで WatchlistBackupEntry は往復で等価" {
        val entry = sample.toBackupEntry()
        val encoded = json.encodeToString(WatchlistBackupEntry.serializer(), entry)
        val decoded = json.decodeFromString(WatchlistBackupEntry.serializer(), encoded)
        decoded shouldBe entry
    }

    "複数件のリストも往復で等価" {
        val entries = listOf(
            sample.toBackupEntry(),
            sample.copy(productKey = "rakuten:shop:item-2", sku = "item-2").toBackupEntry(),
        )
        val encoded = json.encodeToString(ListSerializer(WatchlistBackupEntry.serializer()), entries)
        val decoded = json.decodeFromString(ListSerializer(WatchlistBackupEntry.serializer()), encoded)
        decoded shouldBe entries
    }

    "targetPrice/imageUrl が null でもエンコード・デコードできる" {
        val entry = sample.copy(targetPrice = null, imageUrl = null).toBackupEntry()
        val encoded = json.encodeToString(WatchlistBackupEntry.serializer(), entry)
        val decoded = json.decodeFromString(WatchlistBackupEntry.serializer(), encoded)
        decoded shouldBe entry
    }

    "旧バックアップ (新フィールド欠落) を欠落フィールドのデフォルト値でデコードできる (前方互換性)" {
        // stockAlertEnabled 等が存在しなかった旧バージョンの最小限 JSON を模擬。
        val minimalJson = """
            {"productKey":"amazon:OLD","sku":"OLD","title":"旧商品","platform":"amazon",
             "realPrice":1000,"listPrice":1500,"url":"https://example.com"}
        """.trimIndent()
        val decoded = json.decodeFromString(WatchlistBackupEntry.serializer(), minimalJson)
        decoded.stockAlertEnabled shouldBe false
        decoded.targetPrice.shouldBeNull()
        decoded.addedPrice shouldBe 0
    }
})
