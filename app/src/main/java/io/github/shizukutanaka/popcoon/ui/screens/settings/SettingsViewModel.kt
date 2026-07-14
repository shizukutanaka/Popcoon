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
import io.github.shizukutanaka.popcoon.feature.crash.PrivacyCrashReporter
import io.github.shizukutanaka.popcoon.feature.export.WatchlistBackupManager
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences
import coil3.ImageLoader
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
import kotlinx.coroutines.async
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
    private val crashReporter: PrivacyCrashReporter,
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(appVersion = BuildConfig.VERSION_NAME ?: "0.1.0")
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    // BillingManager の Context 依存は BillingClient.newBuilder(context) のみで Activity 必須ではない
    // (launchBillingFlow だけが Activity を個別引数で要求する) ため、ApplicationContext で
    // ViewModel スコープに保持できる。以前は Activity ごとに launchPurchase() 内で遅延生成しており、
    // (1) 起動時に既存購入を復元するタイミングが無く、(2) status を誰も購読していなかったため、
    // 実際に課金してもアプリの isPremium が永遠に false のまま — という課金導線の断絶があった
    // (機能過不足監査で発見、収益に直結するバグ)。
    private val billing = BillingManager(context)

    // BillingClient.startConnection() は接続済みの状態で再度呼ぶと誤用になる (SDK 側で警告/エラー)。
    // アプリ起動時の自動接続と launchPurchase() が同じ接続試行を共有できるよう Deferred 化する
    // (launchPurchase() 側で initialize() を再度呼ばないようにするため)。
    private val billingReady = viewModelScope.async { billing.initialize() }

    init {
        // アプリ起動時に Billing 接続 → 既存購入をクエリ (再インストール後の復元、書き込み失敗からの回復)。
        // status の変化 (ACTIVE/INACTIVE) を DataStore へ反映する — これが無いと handlePurchase() が
        // ACTIVE に更新するのは BillingManager 内の StateFlow だけで、誰にも伝わらなかった。
        billing.status.onEach { status ->
            when (status) {
                BillingManager.PremiumStatus.ACTIVE -> prefs.setPremium(true)
                BillingManager.PremiumStatus.INACTIVE -> prefs.setPremium(false)
                // PENDING/UNKNOWN は判定保留 — 既存の DataStore 値 (前回確定した状態) を保持する。
                BillingManager.PremiumStatus.PENDING, BillingManager.PremiumStatus.UNKNOWN -> Unit
            }
            _state.value = _state.value.copy(billingStatus = status)
        }.launchIn(viewModelScope)
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
     *
     * billing.initialize() はアプリ起動時に一度だけ (billingReady) 実行済みのものを待つ —
     * ここで再度呼ぶと BillingClient の接続誤用になる。
     */
    fun launchPurchase(activity: Activity) {
        viewModelScope.launch {
            val ok = billingReady.await()
            if (!ok) {
                _state.value = _state.value.copy(
                    billingStatus = BillingManager.PremiumStatus.UNKNOWN,
                )
                return@launch
            }
            val offers = billing.queryOffers()
            // 月額プランを優先
            val monthlyOffer = offers.firstOrNull {
                it.productId.contains("monthly")
            } ?: offers.firstOrNull()

            if (monthlyOffer != null) {
                billing.launchPurchase(activity, monthlyOffer)
            }
        }
    }

    /**
     * GDPR Article 17 — 全データ削除。
     *
     * 端末内データを完全削除する: Room 全テーブル + DataStore 設定 + ローカル保存済み
     * クラッシュレポート (`PrivacyCrashReporter` が送信前に `filesDir/crashes/` へ永続化する
     * JSON、機種名・Androidバージョン等を含む) + Coil の画像ディスク/メモリキャッシュ
     * (`filesDir/cache/image_cache/`、商品画像)。
     * 以前はクラッシュレポートと画像キャッシュがこの削除対象から漏れており、「完全削除」の
     * 表示に反して実際には端末に個人利用履歴の痕跡 (閲覧した商品のサムネイル、クラッシュ発生
     * 時刻とセッションID) が残り続けていた (機能過不足監査で発見)。
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
                crashReporter.clearLocalCrashes()
                imageLoader.diskCache?.clear()
                imageLoader.memoryCache?.clear()
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
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // watchlistBackup.import() 内の DAO 書き込みループは例外を握り潰さない
                // (途中失敗時にどこまで復元されたか不明瞭になるのを避けるため意図的)。
                // ここで捕捉しないと Room 例外等でアプリがクラッシュし、「復元失敗」の
                // メッセージも表示されなかった (機能過不足監査で発見)。
                PopcoonLogger.w(this@SettingsViewModel, "restoreWatchlist failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    restoreResultMessage = UiText.StringResource(R.string.watchlist_restore_failed),
                )
            }
        }
    }

    fun clearRestoreResult() {
        _state.value = _state.value.copy(restoreResultMessage = null)
    }

    fun openPrivacy() {
        val uri = Uri.parse("https://github.com/shizukutanaka/popcoon/blob/main/PRIVACY.md")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // OSS ライセンス表記は ui/screens/licenses/LicensesScreen.kt (アプリ内画面) が担当する。
    // 以前はここで外部ブラウザに自プロジェクトの LICENSE を開くだけの暫定実装だったが、
    // 同梱 OSS 依存 (Compose/Ktor/Room/Hilt 等) の表記が欠落していた
    // (商用リリース監査で発見)。SettingsScreen から onLicenses コールバック経由で
    // 直接ナビゲートするため、ここに ViewModel メソッドは不要。

    override fun onCleared() {
        super.onCleared()
        billing.dispose()
    }
}
