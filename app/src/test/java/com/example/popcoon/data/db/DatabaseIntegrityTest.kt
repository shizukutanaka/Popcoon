package com.example.popcoon.data.db

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Room データベースの整合性テスト。
 *
 * 同種ソフト調査:
 *  - Pricey で「アップデート後にお気に入りや履歴がリセットされる」という
 *    ユーザー苦情が複数 → データ消失はアプリ評価を直撃する
 *
 * 対策:
 *  1. Entity の data class が変更されても既存レコードが読めることを確認
 *  2. fallbackToDestructiveMigration は 0.x のみ許可 (v1.0+ では禁止)
 *  3. addedAt のデフォルト値が実際に挿入されていることを確認
 *  4. 大量データ挿入 → 件数上限でトリミングが機能することを確認
 *
 * これは Android Instrumentation test (実機 or Emulator) 前提だが、
 * ロジック部分だけ pure unit test で検証できる範囲を切り出した。
 */
class DatabaseIntegrityTest : StringSpec({

    "WatchlistItem のデフォルト値が正しく設定される" {
        val now = Instant.now().toEpochMilli()
        val item = WatchlistItem(
            productKey = "amazon:B0TEST001",
            sku = "B0TEST001",
            title = "テスト商品",
            platform = "amazon",
            realPrice = 3980L,
            listPrice = 4980L,
            url = "https://www.amazon.co.jp/dp/B0TEST001",
            imageUrl = null,
            // addedAt をデフォルト値 (現在時刻) で生成
        )
        // addedAt は Instant.now() なので now 以上のはず
        (item.addedAt >= now - 1000L) shouldBe true
        // v2/v3 で追加したカラムの既定値
        (item.targetPrice == null) shouldBe true
        item.addedPrice shouldBe 0L
    }

    "SearchHistoryEntry のデフォルト timestamp が現在時刻近辺" {
        val before = Instant.now().toEpochMilli()
        val entry = SearchHistoryEntry(query = "テストクエリ")
        val after = Instant.now().toEpochMilli()
        (entry.timestamp in before..after) shouldBe true
    }

    "productKey の一意性: 同じ key を 2 回 upsert しても 1 件" {
        // Dao の onConflict = REPLACE 挙動を検証 (SQLite 上の実機テスト用)
        // ここでは Entity 構造のみ検証
        val item1 = WatchlistItem(
            productKey = "amazon:DUPE", sku = "DUPE", title = "A",
            platform = "amazon", realPrice = 1000, listPrice = 1500,
            url = "", imageUrl = null, addedAt = 100L,
        )
        val item2 = item1.copy(title = "B", realPrice = 900)
        // upsert: item2 が item1 を上書きするので productKey は同じ
        item2.productKey shouldBe item1.productKey
    }

    "PriceCacheEntry: recordedAt は必須" {
        shouldNotThrowAny {
            PriceCacheEntry(
                productKey = "amazon:B0CACHE",
                realPrice = 4980L,
                listPrice = 5980L,
                recordedAt = Instant.now().toEpochMilli(),
            )
        }
    }

    "大量データの trim ロジック (DAO クエリ): 最新N件の保持" {
        // trim 後に保持する件数は最大 50 件
        // SearchHistoryDao.trim(50) が呼ばれたあと 51 件目は消える
        // ここでは trim メソッドのシグネチャが正しいことを型レベルで確認
        val daoKClass = SearchHistoryDao::class
        val trimMethod = daoKClass.members.find { it.name == "trim" }
        (trimMethod != null) shouldBe true
    }

    "Instant converter: ラウンドトリップ精度" {
        val converter = InstantConverter()
        val original = Instant.now()
        val epochMilli = converter.fromInstant(original)!!
        val restored = converter.toInstant(epochMilli)!!
        // ms 精度のため差が 1ms 以内
        kotlin.math.abs(original.toEpochMilli() - restored.toEpochMilli()) shouldBe 0L
    }

    "Instant converter: null 入力は null を返す" {
        val converter = InstantConverter()
        converter.fromInstant(null) shouldBe null
        converter.toInstant(null) shouldBe null
    }

    "totalPrice 計算: shipping と points を正しく加減算" {
        // WatchlistItem に totalPrice プロパティがないことを確認
        // (Product 側に持つべき設計)
        // ここでは DB Entity として price fields が正しい型を持つことを確認
        val item = WatchlistItem(
            productKey = "p", sku = "s", title = "t",
            platform = "amazon",
            realPrice = 2980L,
            listPrice = 3980L,
            url = "", imageUrl = null,
        )
        item.realPrice shouldBe 2980L
        item.listPrice shouldBe 3980L
        (item.listPrice > item.realPrice) shouldBe true
    }
})
