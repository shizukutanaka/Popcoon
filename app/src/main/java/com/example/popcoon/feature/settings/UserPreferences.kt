package com.example.popcoon.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ユーザー設定の永続化。DataStore Preferences。
 * プライバシー優先: 全項目 opt-in、デフォルトは OFF。
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val Context.dataStore by preferencesDataStore("popcoon_prefs")

    companion object {
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_CRASH_REPORT_OPTIN = booleanPreferencesKey("crash_optin")
        private val KEY_AI_ADVISOR_OPTIN = booleanPreferencesKey("ai_optin")
        private val KEY_AFFILIATE_OPTIN = booleanPreferencesKey("affiliate_optin")
        private val KEY_PREMIUM = booleanPreferencesKey("premium")
        private val KEY_SUCCESS_COUNT = intPreferencesKey("success_count")
        private val KEY_LAST_REVIEW_REQUEST = longPreferencesKey("last_review")
        private val KEY_LANGUAGE = intPreferencesKey("language_idx")
        private val KEY_WATCHLIST_SORT = intPreferencesKey("watchlist_sort_idx")
    }

    val onboarded: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ONBOARDED] ?: false }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDED] = value }
    }

    val crashReportOptin: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_CRASH_REPORT_OPTIN] ?: false }   // デフォルト OFF

    suspend fun setCrashReportOptin(value: Boolean) {
        context.dataStore.edit { it[KEY_CRASH_REPORT_OPTIN] = value }
    }

    val aiAdvisorOptin: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_AI_ADVISOR_OPTIN] ?: false }   // 個人 API key 必須なので OFF

    suspend fun setAiAdvisorOptin(value: Boolean) {
        context.dataStore.edit { it[KEY_AI_ADVISOR_OPTIN] = value }
    }

    val affiliateOptin: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_AFFILIATE_OPTIN] ?: false }   // デフォルト OFF (クラス方針: 全項目 opt-in)

    suspend fun setAffiliateOptin(value: Boolean) {
        context.dataStore.edit { it[KEY_AFFILIATE_OPTIN] = value }
    }

    val isPremium: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_PREMIUM] ?: false }

    suspend fun setPremium(value: Boolean) {
        context.dataStore.edit { it[KEY_PREMIUM] = value }
    }

    /** 成功イベント数 — In-App Review トリガーのカウンター */
    val successCount: Flow<Int> = context.dataStore.data
        .map { it[KEY_SUCCESS_COUNT] ?: 0 }

    suspend fun incrementSuccessCount() {
        context.dataStore.edit {
            it[KEY_SUCCESS_COUNT] = (it[KEY_SUCCESS_COUNT] ?: 0) + 1
        }
    }

    val lastReviewRequest: Flow<Long> = context.dataStore.data
        .map { it[KEY_LAST_REVIEW_REQUEST] ?: 0L }

    suspend fun markReviewRequested() {
        context.dataStore.edit {
            it[KEY_LAST_REVIEW_REQUEST] = System.currentTimeMillis()
        }
    }

    /** ウォッチリストの並べ替えモード（WatchlistSort.Mode の ordinal）。既定 0 = ADDED_DESC。 */
    val watchlistSortOrdinal: Flow<Int> = context.dataStore.data
        .map { it[KEY_WATCHLIST_SORT] ?: 0 }

    suspend fun setWatchlistSort(ordinal: Int) {
        context.dataStore.edit { it[KEY_WATCHLIST_SORT] = ordinal }
    }

    /** GDPR Article 17 — 全データ削除 */
    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
