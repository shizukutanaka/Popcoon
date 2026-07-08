package io.github.shizukutanaka.popcoon.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ユーザー設定の永続化。DataStore Preferences。
 * プライバシー優先: 全項目 opt-in、デフォルトは OFF。
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : IUserPreferences {
    private val Context.dataStore by preferencesDataStore("popcoon_prefs")

    /**
     * 全 read フローの単一の源。DataStore ファイルの読み取り失敗 (IOException) や
     * 破損時に **空の Preferences へフォールバック**する。
     *
     * これが無いと `.data` は例外を collector に伝播し、`onboarded` を起動時に読む
     * AppRootViewModel をはじめ全設定購読がクラッシュしうる (Android 公式の推奨パターン)。
     * IOException 以外 (プログラミングエラー等) はそのまま投げて握り潰さない。
     */
    private val safeData: Flow<Preferences> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    companion object {
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_CRASH_REPORT_OPTIN = booleanPreferencesKey("crash_optin")
        private val KEY_AI_ADVISOR_OPTIN = booleanPreferencesKey("ai_optin")
        private val KEY_AFFILIATE_OPTIN = booleanPreferencesKey("affiliate_optin")
        private val KEY_PREMIUM = booleanPreferencesKey("premium")
        private val KEY_SUCCESS_COUNT = intPreferencesKey("success_count")
        private val KEY_LAST_REVIEW_REQUEST = longPreferencesKey("last_review")
        private val KEY_WATCHLIST_SORT = intPreferencesKey("watchlist_sort_idx")

        // EC 会員設定 — PointSimulator.UserContext に供給し実質価格ランキングを個人化する
        private val KEY_RAKUTEN_SPU = intPreferencesKey("rakuten_spu")
        private val KEY_YAHOO_PREMIUM = booleanPreferencesKey("yahoo_premium")
        private val KEY_PAYPAY_SOFTBANK = booleanPreferencesKey("paypay_softbank")
        private val KEY_AMAZON_PRIME = booleanPreferencesKey("amazon_prime")

        // 通知感度 — PriceAlertEvaluator.minDropPercent に供給
        private val KEY_NOTIF_DROP_PCT = intPreferencesKey("notif_drop_pct")

        // EC 会員設定を一度でも案内済みか (SearchScreen のバナー用)
        private val KEY_EC_PROMPT_DISMISSED = booleanPreferencesKey("ec_prompt_dismissed")
    }

    val onboarded: Flow<Boolean> = safeData
        .map { it[KEY_ONBOARDED] ?: false }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDED] = value }
    }

    val crashReportOptin: Flow<Boolean> = safeData
        .map { it[KEY_CRASH_REPORT_OPTIN] ?: false }   // デフォルト OFF

    suspend fun setCrashReportOptin(value: Boolean) {
        context.dataStore.edit { it[KEY_CRASH_REPORT_OPTIN] = value }
    }

    val aiAdvisorOptin: Flow<Boolean> = safeData
        .map { it[KEY_AI_ADVISOR_OPTIN] ?: false }   // 個人 API key 必須なので OFF

    suspend fun setAiAdvisorOptin(value: Boolean) {
        context.dataStore.edit { it[KEY_AI_ADVISOR_OPTIN] = value }
    }

    val affiliateOptin: Flow<Boolean> = safeData
        .map { it[KEY_AFFILIATE_OPTIN] ?: false }   // デフォルト OFF (クラス方針: 全項目 opt-in)

    suspend fun setAffiliateOptin(value: Boolean) {
        context.dataStore.edit { it[KEY_AFFILIATE_OPTIN] = value }
    }

    val isPremium: Flow<Boolean> = safeData
        .map { it[KEY_PREMIUM] ?: false }

    suspend fun setPremium(value: Boolean) {
        context.dataStore.edit { it[KEY_PREMIUM] = value }
    }

    /** 成功イベント数 — In-App Review トリガーのカウンター */
    val successCount: Flow<Int> = safeData
        .map { it[KEY_SUCCESS_COUNT] ?: 0 }

    suspend fun incrementSuccessCount() {
        context.dataStore.edit {
            it[KEY_SUCCESS_COUNT] = (it[KEY_SUCCESS_COUNT] ?: 0) + 1
        }
    }

    val lastReviewRequest: Flow<Long> = safeData
        .map { it[KEY_LAST_REVIEW_REQUEST] ?: 0L }

    suspend fun markReviewRequested() {
        context.dataStore.edit {
            it[KEY_LAST_REVIEW_REQUEST] = System.currentTimeMillis()
        }
    }

    /** ウォッチリストの並べ替えモード（WatchlistSort.Mode の ordinal）。既定 0 = ADDED_DESC。 */
    val watchlistSortOrdinal: Flow<Int> = safeData
        .map { it[KEY_WATCHLIST_SORT] ?: 0 }

    suspend fun setWatchlistSort(ordinal: Int) {
        context.dataStore.edit { it[KEY_WATCHLIST_SORT] = ordinal }
    }

    /** 楽天 SPU 倍率 (1–15)。PointSimulator.UserContext.rakutenSpu に供給。 */
    val rakutenSpu: Flow<Int> = safeData.map { it[KEY_RAKUTEN_SPU] ?: 1 }

    suspend fun setRakutenSpu(v: Int) {
        context.dataStore.edit { it[KEY_RAKUTEN_SPU] = v.coerceIn(1, 15) }
    }

    /** Yahoo!プレミアム会員。PointSimulator.UserContext.yahooPremium に供給。 */
    val yahooPremium: Flow<Boolean> = safeData.map { it[KEY_YAHOO_PREMIUM] ?: false }

    suspend fun setYahooPremium(v: Boolean) {
        context.dataStore.edit { it[KEY_YAHOO_PREMIUM] = v }
    }

    /** SoftBank/Y!mobile 利用者。PointSimulator.UserContext.paypaySoftbank に供給。 */
    val paypaySoftbank: Flow<Boolean> = safeData.map { it[KEY_PAYPAY_SOFTBANK] ?: false }

    suspend fun setPaypaySoftbank(v: Boolean) {
        context.dataStore.edit { it[KEY_PAYPAY_SOFTBANK] = v }
    }

    /** Amazon Prime 会員。PointSimulator.UserContext.amazonPrime に供給。 */
    val amazonPrime: Flow<Boolean> = safeData.map { it[KEY_AMAZON_PRIME] ?: false }

    suspend fun setAmazonPrime(v: Boolean) {
        context.dataStore.edit { it[KEY_AMAZON_PRIME] = v }
    }

    /**
     * 価格アラートの最小値下がり率（%）。1/3/5/10 から選択。
     * PriceSyncWorker が PriceAlertEvaluator.evaluate() に渡す。デフォルト 3%。
     */
    val notifDropPercent: Flow<Int> = safeData.map { it[KEY_NOTIF_DROP_PCT] ?: 3 }

    suspend fun setNotifDropPercent(pct: Int) {
        context.dataStore.edit { it[KEY_NOTIF_DROP_PCT] = pct.coerceIn(1, 20) }
    }

    /**
     * EC 会員設定バナーを一度でも閉じた/設定画面に遷移したか。
     *
     * 実質価格ランキング (PointSimulator) は楽天SPU/Yahooプレミアム/PayPay/Amazonプライムの
     * 4設定に依存するが、全てデフォルト OFF かつ設定画面のみに存在するため、オンボーディングを
     * 終えたユーザーの大半が一度も気づかず「実質価格」が常に最低倍率で計算され続けていた
     * (アプリの差別化機能が事実上死蔵)。SearchScreen に一度だけ案内バナーを出し、
     * 閉じる/設定へ進むいずれかの操作で再表示しないようにする。
     */
    val ecPromptDismissed: Flow<Boolean> = safeData
        .map { it[KEY_EC_PROMPT_DISMISSED] ?: false }

    override suspend fun dismissEcPrompt() {
        context.dataStore.edit { it[KEY_EC_PROMPT_DISMISSED] = true }
    }

    /** GDPR Article 17 — 全データ削除 */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
