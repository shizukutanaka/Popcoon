package io.github.shizukutanaka.popcoon.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PopcoonDatabase の MIGRATION_1_2 〜 MIGRATION_5_6 を実データで検証する。
 *
 * これまで exportSchema = true でありながら room.schemaLocation が未設定で、
 * スキーマ JSON が生成されず、マイグレーション自体が一度も実行検証されていなかった
 * (商用リリース監査で発見)。各バージョンで実際にデータを INSERT し、マイグレーション後も
 * 消えずに読み出せることを確認する — 単に例外が出ないことだけでなく、
 * 「ユーザーのウォッチリストが実際に保全されるか」を検証する。
 *
 * 前提: app/build.gradle.kts の `ksp { arg("room.schemaLocation", ...) }` により
 * app/schemas/ にバージョンごとのスキーマ JSON が生成され、
 * `sourceSets { androidTest.assets.srcDirs("$projectDir/schemas") }` で
 * このテストから読めるようになっている必要がある。このリポジトリは Android SDK が無い
 * 環境で開発されたセッションを含むため、schemas/ の実体はまだ生成されていない —
 * Android SDK のある環境で最初にビルドした時点で生成され、以後このテストが有効になる。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PopcoonDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** v1 時点の watchlist テーブル DDL (WatchlistItem の v1 時点の列のみ)。 */
    private fun createV1Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watchlist` (
                `productKey` TEXT NOT NULL, `sku` TEXT NOT NULL, `title` TEXT NOT NULL,
                `platform` TEXT NOT NULL, `realPrice` INTEGER NOT NULL, `listPrice` INTEGER NOT NULL,
                `url` TEXT NOT NULL, `imageUrl` TEXT, `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`productKey`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `price_cache` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productKey` TEXT NOT NULL,
                `realPrice` INTEGER NOT NULL, `listPrice` INTEGER NOT NULL, `recordedAt` INTEGER NOT NULL)
            """.trimIndent(),
        )
    }

    @Test
    fun migrate1To2_targetPriceColumnAdded_existingRowsSurvive() {
        helper.createDatabase(testDbName, 1).apply {
            createV1Schema(this)
            execSQL(
                "INSERT INTO watchlist (productKey, sku, title, platform, realPrice, listPrice, url, imageUrl, addedAt) " +
                    "VALUES ('amazon:B1', 'B1', 'テスト商品', 'amazon', 1000, 1500, 'https://example.com', NULL, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDbName, 2, true, PopcoonDatabase.MIGRATION_1_2)
        val cursor = db.query("SELECT productKey, targetPrice FROM watchlist WHERE productKey = 'amazon:B1'")
        cursor.use {
            assert(it.moveToFirst()) { "既存行がマイグレーション後に消えている" }
            assert(it.isNull(it.getColumnIndexOrThrow("targetPrice"))) { "targetPrice は NULL (未設定) で追加されるべき" }
        }
    }

    @Test
    fun migrate2To3_addedPriceDefaultsToZero_existingRowsSurvive() {
        helper.createDatabase(testDbName, 1).apply {
            createV1Schema(this)
            execSQL(
                "INSERT INTO watchlist (productKey, sku, title, platform, realPrice, listPrice, url, imageUrl, addedAt) " +
                    "VALUES ('amazon:B2', 'B2', 'テスト商品2', 'amazon', 2000, 2500, 'https://example.com', NULL, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 3, true,
            PopcoonDatabase.MIGRATION_1_2, PopcoonDatabase.MIGRATION_2_3,
        )
        val cursor = db.query("SELECT addedPrice FROM watchlist WHERE productKey = 'amazon:B2'")
        cursor.use {
            assert(it.moveToFirst())
            assert(it.getLong(it.getColumnIndexOrThrow("addedPrice")) == 0L) {
                "v2以前に追加された行は addedPrice = 0 (基準なし) になるべき"
            }
        }
    }

    @Test
    fun migrate4To5_stockAlertDefaultsOff_previousInStockNull() {
        helper.createDatabase(testDbName, 1).apply {
            createV1Schema(this)
            execSQL(
                "INSERT INTO watchlist (productKey, sku, title, platform, realPrice, listPrice, url, imageUrl, addedAt) " +
                    "VALUES ('amazon:B3', 'B3', 'テスト商品3', 'amazon', 3000, 3500, 'https://example.com', NULL, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 5, true,
            PopcoonDatabase.MIGRATION_1_2, PopcoonDatabase.MIGRATION_2_3,
            PopcoonDatabase.MIGRATION_3_4, PopcoonDatabase.MIGRATION_4_5,
        )
        val cursor = db.query("SELECT stockAlertEnabled, previousInStock FROM watchlist WHERE productKey = 'amazon:B3'")
        cursor.use {
            assert(it.moveToFirst())
            assert(it.getInt(it.getColumnIndexOrThrow("stockAlertEnabled")) == 0) {
                "既存行の在庫アラートは既定で OFF になるべき"
            }
            assert(it.isNull(it.getColumnIndexOrThrow("previousInStock"))) {
                "既存行は初回同期扱い (基準なし) で previousInStock = NULL になるべき"
            }
        }
    }

    @Test
    fun migrate5To6_tagColumnAddedAsNull_existingRowsSurvive() {
        helper.createDatabase(testDbName, 1).apply {
            createV1Schema(this)
            execSQL(
                "INSERT INTO watchlist (productKey, sku, title, platform, realPrice, listPrice, url, imageUrl, addedAt) " +
                    "VALUES ('amazon:B4', 'B4', 'テスト商品4', 'amazon', 4000, 4500, 'https://example.com', NULL, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 6, true,
            PopcoonDatabase.MIGRATION_1_2, PopcoonDatabase.MIGRATION_2_3,
            PopcoonDatabase.MIGRATION_3_4, PopcoonDatabase.MIGRATION_4_5,
            PopcoonDatabase.MIGRATION_5_6,
        )
        val cursor = db.query("SELECT tag FROM watchlist WHERE productKey = 'amazon:B4'")
        cursor.use {
            assert(it.moveToFirst())
            assert(it.isNull(it.getColumnIndexOrThrow("tag"))) { "既存行は tag = NULL (未分類) になるべき" }
        }
    }

    /** v1 → v6 の全チェーンを一度に検証 (単体ステップだけでなく累積適用でも壊れないことを確認)。 */
    @Test
    fun migrateAll_1To6_fullChain() {
        helper.createDatabase(testDbName, 1).apply {
            createV1Schema(this)
            execSQL(
                "INSERT INTO watchlist (productKey, sku, title, platform, realPrice, listPrice, url, imageUrl, addedAt) " +
                    "VALUES ('amazon:FULL', 'FULL', '全チェーン検証商品', 'amazon', 5000, 6000, 'https://example.com', NULL, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 6, true,
            PopcoonDatabase.MIGRATION_1_2, PopcoonDatabase.MIGRATION_2_3,
            PopcoonDatabase.MIGRATION_3_4, PopcoonDatabase.MIGRATION_4_5,
            PopcoonDatabase.MIGRATION_5_6,
        )
        val cursor = db.query("SELECT * FROM watchlist WHERE productKey = 'amazon:FULL'")
        cursor.use {
            assert(it.moveToFirst()) { "v1で挿入した行が v1→v6 の全マイグレーション後も残っているべき" }
        }
    }
}
