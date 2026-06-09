package com.example.popcoon.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.popcoon.BuildConfig
import com.example.popcoon.data.db.PopcoonDatabase
import com.example.popcoon.data.repository.BackendClient
import com.example.popcoon.feature.billing.BillingManager
import com.example.popcoon.feature.settings.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val crashOptin: Boolean = false,
    val aiOptin: Boolean = false,
    val affiliateOptin: Boolean = true,
    val isPremium: Boolean = false,
    val billingStatus: BillingManager.PremiumStatus = BillingManager.PremiumStatus.UNKNOWN,
    val appVersion: String = "0.1.0",
    val isDeleting: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val database: PopcoonDatabase,
    private val backend: BackendClient,
    private val csvExporter: com.example.popcoon.feature.export.PriceHistoryCsvExporter,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(appVersion = BuildConfig.VERSION_NAME ?: "0.1.0")
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    // BillingManager は Activity 参照が必要なので遅延初期化
    private var billing: BillingManager? = null

    init {
        combine(
            prefs.crashReportOptin,
            prefs.aiAdvisorOptin,
            prefs.affiliateOptin,
            prefs.isPremium,
        ) { crash, ai, aff, prem ->
            _state.value.copy(
                crashOptin = crash,
                aiOptin = ai,
                affiliateOptin = aff,
                isPremium = prem,
            )
        }.onEach { _state.value = it }.launchIn(viewModelScope)
    }

    fun setCrashOptin(v: Boolean) { viewModelScope.launch { prefs.setCrashReportOptin(v) } }
    fun setAiOptin(v: Boolean) { viewModelScope.launch { prefs.setAiAdvisorOptin(v) } }
    fun setAffiliateOptin(v: Boolean) { viewModelScope.launch { prefs.setAffiliateOptin(v) } }

    /**
     * Activity からサブスク購入フローを起動する。
     * SettingsScreen で LocalContext から Activity を取得して呼ぶ。
     */
    fun launchPurchase(activity: Activity) {
        viewModelScope.launch {
            val b = getOrInitBilling(activity)
            val ok = b.initialize()
            if (!ok) {
                _state.value = _state.value.copy(
                    billingStatus = BillingManager.PremiumStatus.UNKNOWN,
                )
                return@launch
            }
            val offers = b.queryOffers()
            // 月額プランを優先
            val monthlyOffer = offers.firstOrNull {
                it.productId.contains("monthly")
            } ?: offers.firstOrNull()

            if (monthlyOffer != null) {
                b.launchPurchase(activity, monthlyOffer)
            }
        }
    }

    fun deleteAllData() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isDeleting = true)
            runCatching {
                database.clearAllTables()
                prefs.clearAll()
            }
            _state.value = _state.value.copy(isDeleting = false)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            runCatching { database.searchHistoryDao().deleteAll() }
        }
    }

    fun clearWatchlist() {
        viewModelScope.launch {
            runCatching { database.watchlistDao().deleteAll() }
        }
    }

    fun exportCsv() {
        viewModelScope.launch {
            runCatching {
                val intent = csvExporter.shareIntent(context)
                if (intent != null) {
                    context.startActivity(
                        android.content.Intent.createChooser(intent, "CSV を共有")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    fun openPrivacy() {
        val uri = Uri.parse("https://github.com/shizukutanaka/popcoon/blob/main/PRIVACY.md")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openLicenses() {
        // Google Play Services OSS ライセンス画面
        // 依存: com.google.android.gms:play-services-oss-licenses
        // build.gradle に追加済みの場合:
        // context.startActivity(
        //     Intent(context, OssLicensesMenuActivity::class.java)
        //         .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // )
        // 暫定: GitHub のライセンスページを開く
        val uri = Uri.parse("https://github.com/shizukutanaka/popcoon/blob/main/LICENSE")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun getOrInitBilling(activity: Activity): BillingManager {
        return billing ?: BillingManager(activity).also { billing = it }
    }

    override fun onCleared() {
        super.onCleared()
        billing?.dispose()
    }
}
