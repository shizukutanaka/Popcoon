package io.github.shizukutanaka.popcoon.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shizukutanaka.popcoon.BuildConfig
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import io.github.shizukutanaka.popcoon.data.db.PopcoonDatabase
import io.github.shizukutanaka.popcoon.feature.billing.BillingManager
import io.github.shizukutanaka.popcoon.feature.export.WatchlistBackupManager
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences
import io.github.shizukutanaka.popcoon.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val crashOptin: Boolean = false,
    val aiOptin: Boolean = false,
    val affiliateOptin: Boolean = false,
    val isPremium: Boolean = false,
    val billingStatus: BillingManager.PremiumStatus = BillingManager.PremiumStatus.UNKNOWN,
    val appVersion: String = "0.1.0",
    val isDeleting: Boolean = false,
    // EC 会員設定 — PointSimulator.UserContext に供給
    val rakutenSpu: Int = 1,
    val yahooPremium: Boolean = false,
    val paypaySoftbank: Boolean = false,
    val amazonPrime: Boolean = false,
    // 通知感度 — 値下がり通知の最小変動率（%）
    val notifDropPercent: Int = 3,
    /** ウォッチリスト バックアップ復元の結果 (一時表示、null で非表示)。 */
    val restoreResultMessage: UiText? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val database: PopcoonDatabase,
    private val csvExporter: io.github.shizukutanaka.popcoon.feature.export.PriceHistoryCsvExporter,
    private val watchlistBackup: WatchlistBackupManager,
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

        combine(
            prefs.rakutenSpu,
            prefs.yahooPremium,
            prefs.paypaySoftbank,
            prefs.amazonPrime,
        ) { spu, yp, sb, ap ->
            _state.value.copy(
                rakutenSpu = spu,
                yahooPremium = yp,
                paypaySoftbank = sb,
                amazonPrime = ap,
            )
        }.onEach { _state.value = it }.launchIn(viewModelScope)

        prefs.notifDropPercent
            .onEach { _state.value = _state.value.copy(notifDropPercent = it) }
            .launchIn(viewModelScope)
    }

    fun setCrashOptin(v: Boolean) { viewModelScope.launch { prefs.setCrashReportOptin(v) } }
    fun setAiOptin(v: Boolean) { viewModelScope.launch { prefs.setAiAdvisorOptin(v) } }
    fun setAffiliateOptin(v: Boolean) { viewModelScope.launch { prefs.setAffiliateOptin(v) } }

    fun setRakutenSpu(v: Int) { viewModelScope.launch { prefs.setRakutenSpu(v) } }
    fun setYahooPremium(v: Boolean) { viewModelScope.launch { prefs.setYahooPremium(v) } }
    fun setPaypaySoftbank(v: Boolean) { viewModelScope.launch { prefs.setPaypaySoftbank(v) } }
    fun setAmazonPrime(v: Boolean) { viewModelScope.launch { prefs.setAmazonPrime(v) } }
    fun setNotifDropPercent(pct: Int) { viewModelScope.launch { prefs.setNotifDropPercent(pct) } }

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

    /**
     * GDPR Article 17 — 全データ削除。
     *
     * 端末内データ (Room 全テーブル + DataStore 設定) を完全削除する。
     * **サーバー側に削除すべき個人データは存在しない**: アプリはデバイス識別子を一切持たず、
     * backend に送るのは商品キー単位の匿名・共有価格履歴 (特定個人に紐づかない) と、
     * PII 除去済み・デバイス非紐付けのクラッシュレポート (90日 TTL で自動失効) のみ。
     * したがって「サーバー側の関連データ削除」は対象ゼロであり、ここでは端末内削除に専念する。
     * (privacy-first 設計を守るため、削除のためだけのデバイストークン導入はしない。)
     */
    fun deleteAllData() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isDeleting = true)
            try {
                database.clearAllTables()
                prefs.clearAll()
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isDeleting = false)
                throw e
            } catch (e: Exception) {
                // UI には握りつぶすが、デバッグのため WARN で記録 (PII は logger 側でサニタイズ)。
                PopcoonLogger.w(this@SettingsViewModel, "deleteAllData failed: ${e.message}", e)
            }
            _state.value = _state.value.copy(isDeleting = false)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            try {
                database.searchHistoryDao().deleteAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@SettingsViewModel, "clearSearchHistory failed: ${e.message}", e)
            }
        }
    }

    fun clearWatchlist() {
        viewModelScope.launch {
            try {
                database.watchlistDao().deleteAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@SettingsViewModel, "clearWatchlist failed: ${e.message}", e)
            }
        }
    }

    fun exportCsv() {
        viewModelScope.launch {
            try {
                val intent = csvExporter.shareIntent(context)
                if (intent != null) {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            intent,
                            context.getString(R.string.csv_share_chooser_title),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@SettingsViewModel, "exportCsv failed: ${e.message}", e)
            }
        }
    }

    /**
     * ウォッチリスト全体を JSON バックアップとして共有する (全ユーザー無料)。
     * `exportCsv()` (Premium 限定・価格履歴の分析用データ抽出) とは別機能: こちらは
     * `WatchlistItem` を過不足なく含み、機種変更・再インストール時の完全復元に使う。
     */
    fun backupWatchlist() {
        viewModelScope.launch {
            try {
                val intent = watchlistBackup.shareIntent(context)
                if (intent != null) {
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.watchlist_backup_share_title))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@SettingsViewModel, "backupWatchlist failed: ${e.message}", e)
            }
        }
    }

    /**
     * ファイルピッカーで選択されたバックアップ JSON からウォッチリストを復元する (upsert のみ、削除なし)。
     * @param uri [ActivityResultContracts.OpenDocument] 等で取得した URI。
     */
    fun restoreWatchlist(uri: Uri) {
        viewModelScope.launch {
            val result = watchlistBackup.import(context, uri)
            val message = when (result) {
                is WatchlistBackupManager.ImportResult.Success ->
                    // 単複区別が必要な英語等のため plurals を使う (UiText.StringResource は
                    // pluralStringResource に対応しないため、ここで resolve して DynamicString 化)。
                    UiText.DynamicString(
                        context.resources.getQuantityString(
                            R.plurals.watchlist_restore_success, result.count, result.count,
                        ),
                    )
                is WatchlistBackupManager.ImportResult.Failure ->
                    UiText.StringResource(R.string.watchlist_restore_failed)
            }
            _state.value = _state.value.copy(restoreResultMessage = message)
        }
    }

    fun clearRestoreResult() {
        _state.value = _state.value.copy(restoreResultMessage = null)
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
