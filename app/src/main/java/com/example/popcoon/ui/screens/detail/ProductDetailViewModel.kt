package com.example.popcoon.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.popcoon.data.model.Platform
import com.example.popcoon.data.model.PriceRecord
import com.example.popcoon.data.model.Product
import com.example.popcoon.data.repository.IProductRepository
import com.example.popcoon.feature.ai.BuyingAdvisor
import com.example.popcoon.feature.darkpattern.DarkPatternDetector
import com.example.popcoon.feature.darkpattern.DarkPatternTextDetector
import com.example.popcoon.feature.ethics.EcoEthicsScorer
import com.example.popcoon.feature.prediction.PricePredictionEngine
import com.example.popcoon.feature.bundle.BundlePackDetector
import com.example.popcoon.feature.review.ReviewTrustScorer
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.feature.points.PointSimulator
import com.example.popcoon.R
import com.example.popcoon.feature.retention.ReviewPrompter
import com.example.popcoon.feature.settings.UserPreferences
import com.example.popcoon.ui.UiText
import com.example.popcoon.feature.tco.TCOCalculator
import com.example.popcoon.core.PopcoonLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Error(val msg: UiText) : DetailUiState
    data class Loaded(
        val product: Product,
        val score: Int,
        val verdict: BuyTimingScorer.Verdict?,
        val confidence: String?,
        val signals: List<BuyTimingScorer.Signal>,
        val warnings: List<String>,
        val aiAdvice: UiText?,
        val priceHistory: List<PriceRecord> = emptyList(),
        val isInWatchlist: Boolean = false,
        val prediction: PricePredictionEngine.Prediction? = null,
        val tco: TCOCalculator.Result? = null,
        val reviewTrust: ReviewTrustScorer.Result? = null,
        val bundle: BundlePackDetector.Analysis? = null,
        val ethics: EcoEthicsScorer.Score? = null,
        val affiliateOptin: Boolean = false,
        /** EC 会員設定から構築。PointSimulatorCard に渡してポイント還元を個人化する。 */
        val userCtx: PointSimulator.UserContext = PointSimulator.UserContext(),
    ) : DetailUiState
}

/**
 * 商品詳細 ViewModel。
 *
 * Product 受け渡し方法:
 *  1. SearchScreen → navigateToDetail(product) が ProductNavCache に登録。
 *  2. load(productKey) が ProductNavCache.consume() で取得 (1回限り)。
 *  3. キャッシュミス時は productKey からフォールバック構築。
 *  4. backend 経由で価格履歴を非同期取得、scorer + darkpattern を適用。
 *  5. AI advice は最後に別 launch で背景取得 (UI をブロックしない)。
 *
 * ProductNavCache を使う理由:
 *  - Compose Navigation の savedStateHandle は遷移元/先で別インスタンスのため不可。
 *  - Parcelable 化は Compose Nav で非推奨、route 引数は URL が壊れやすい。
 */
@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val repository: IProductRepository,
    private val advisor: BuyingAdvisor,
    private val adviceCache: com.example.popcoon.feature.ai.AdviceCache,
    private val watchlistDao: com.example.popcoon.data.db.WatchlistDao,
    private val prefs: UserPreferences,
    private val reviewPrompter: ReviewPrompter,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    fun load(productKey: String) {
        viewModelScope.launch {
            _state.value = DetailUiState.Loading
            if (!isValidProductKey(productKey)) {
                PopcoonLogger.w(this@ProductDetailViewModel, "Malformed productKey: $productKey")
                _state.value = DetailUiState.Error(UiText.StringResource(R.string.detail_error_invalid_key))
                return@launch
            }
            runCatching {
                // 1. 価格履歴を backend から取得 (非同期)
                val historyDeferred = async { repository.getPriceHistory(productKey) }

                // 2. Product: ProductNavCache → なければ productKey からフォールバック構築
                val history = historyDeferred.await()
                val product = ProductNavCache.consume(productKey)
                    ?: buildProductFromKey(productKey, history)

                // 3. EC 会員設定から UserContext 構築 (PointSimulatorCard 個人化に供給)
                val userCtx = PointSimulator.UserContext(
                    rakutenSpu = kotlinx.coroutines.flow.first(prefs.rakutenSpu),
                    yahooPremium = kotlinx.coroutines.flow.first(prefs.yahooPremium),
                    paypaySoftbank = kotlinx.coroutines.flow.first(prefs.paypaySoftbank),
                    amazonPrime = kotlinx.coroutines.flow.first(prefs.amazonPrime),
                    purchaseDate = java.time.LocalDate.now(),
                )

                // 4. スコア計算 (pure function — fast)
                // realPrice を渡す: PriceRecord.realPrice と単位が一致し ATL バイアスを避ける。
                val score = BuyTimingScorer.score(
                    current = product.realPrice,
                    listPrice = product.listPrice,
                    history = history,
                    today = java.time.LocalDate.now(),
                )

                // 4. ダークパターン検出 (pure function — fast)
                //    価格系 (履歴ベース) + テキスト系 5カテゴリ (PORTING_SPEC.md #5, arXiv 2411.07441)
                val priceWarnings = DarkPatternDetector.detect(
                    currentPrice = product.totalPrice,
                    listPrice = product.listPrice.takeIf { it > 0 },
                    history = history,
                )
                val dripWarning = DarkPatternDetector.detectDripPricing(
                    basePrice = product.realPrice,
                    totalPrice = product.totalPrice,
                )
                // テキスト系: 5カテゴリ検出器（URGENCY/SCARCITY/SOCIAL_PROOF/MISDIRECTION/FORCED_ACTION）
                val textSignals = DarkPatternTextDetector.detect(product.title)
                val warnings = (priceWarnings + listOfNotNull(dripWarning))
                    .map { "${it.label} (${it.severity.name})" }
                    .toMutableList()
                warnings += textSignals.map { "${it.evidence} (${it.severity.name})" }

                // レビュー信頼性: LOW なら警告に追加 (サクラ・サンプル不足の注意喚起)
                val reviewTrust = ReviewTrustScorer.evaluate(product.rating, product.reviewCount)
                if (reviewTrust.trust == ReviewTrustScorer.Trust.LOW &&
                    reviewTrust.reasonKey == "review_trust_too_perfect"
                ) {
                    warnings += context.getString(
                        com.example.popcoon.R.string.review_trust_too_perfect,
                    )
                }

                // 5. 即時表示 (ウォッチリスト状態・アフィリエイト設定も確認)
                val inWatchlist = watchlistDao.get(product.key) != null
                val affiliateOptin = kotlinx.coroutines.flow.first(prefs.affiliateOptin)
                val prediction = PricePredictionEngine.predict(history)
                // TCO: 対象カテゴリ (プリンター/PC等) のみ計算。
                // effectivePrice (ポイント還元後) を実質購入価格として供給。
                val tco = TCOCalculator.inferCategory(product.title)?.let { category ->
                    TCOCalculator.calculate(
                        purchasePrice = PointSimulator.simulate(product, userCtx).effectivePrice,
                        category = category,
                    )
                }
                // バンドル: タイトルから「N個セット」を抽出し実質単価を計算。
                // effectivePrice を使うことで「ポイント後の1個あたり実質単価」を示す。
                val effectivePriceForBundle = PointSimulator.simulate(product, userCtx).effectivePrice
                val bundle = BundlePackDetector.extractBundleInfo(product.title)?.let { info ->
                    if (info.packCount > 1) {
                        BundlePackDetector.detectValue(
                            bundlePrice = effectivePriceForBundle,
                            packCount = info.packCount,
                            singlePrice = null,  // 単品価格は将来 API 拡張で取得
                        )
                    } else {
                        null
                    }
                }
                // エコ倫理スコア: 原産国が判明している商品でのみ算出 (不明時は意味を持たない)
                val ethics = product.originCountry?.takeIf { it.isNotBlank() }?.let { origin ->
                    EcoEthicsScorer.score(
                        country = origin.uppercase(),
                        category = inferEthicsCategory(product.title),
                        certifications = extractCertifications(product.title),
                    )
                }
                _state.value = DetailUiState.Loaded(
                    product = product,
                    score = score?.total ?: 0,
                    verdict = score?.verdict,
                    confidence = score?.confidence,
                    signals = score?.signals.orEmpty(),
                    warnings = warnings,
                    aiAdvice = null,
                    priceHistory = history,
                    isInWatchlist = inWatchlist,
                    prediction = prediction,
                    tco = tco,
                    reviewTrust = reviewTrust,
                    bundle = bundle,
                    ethics = ethics,
                    affiliateOptin = affiliateOptin,
                    userCtx = userCtx,
                )

                // 6. AI advice をキャッシュ確認 → 必要なら背景取得
                if (score != null) {
                    val cached = adviceCache.get(product, score)
                    if (cached != null) {
                        // キャッシュヒット → 即時反映 (_state.update で productKey 一致確認)
                        _state.update { cur ->
                            if (cur is DetailUiState.Loaded && cur.product.key == product.key) {
                                cur.copy(aiAdvice = UiText.DynamicString(cached))
                            } else cur
                        }
                    } else {
                        // キャッシュミス → API call (UI ブロックなし)
                        viewModelScope.launch {
                            runCatching {
                                advisor.advise(product, score)
                            }.onSuccess { text ->
                                adviceCache.put(product, score, text)
                                _state.update { cur ->
                                    if (cur is DetailUiState.Loaded && cur.product.key == product.key) {
                                        cur.copy(aiAdvice = UiText.DynamicString(text))
                                    } else cur
                                }
                            }.onFailure { e ->
                                if (e is CancellationException) throw e
                                _state.update { cur ->
                                    if (cur is DetailUiState.Loaded && cur.product.key == product.key) {
                                        cur.copy(aiAdvice = UiText.StringResource(R.string.error_ai_advisor_failed))
                                    } else cur
                                }
                            }
                        }
                    }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.value = DetailUiState.Error(
                    e.message?.take(80)?.let { UiText.DynamicString(it) }
                        ?: UiText.StringResource(R.string.error_detail_load_failed)
                )
            }
        }
    }

    /** ウォッチリスト追加/削除トグル */
    fun toggleWatchlist(product: Product) {
        viewModelScope.launch {
            val cur = _state.value as? DetailUiState.Loaded ?: return@launch
            if (cur.isInWatchlist) {
                watchlistDao.delete(product.key)
                _state.value = cur.copy(isInWatchlist = false)
            } else {
                watchlistDao.upsert(
                    com.example.popcoon.data.db.WatchlistItem(
                        productKey = product.key,
                        sku = product.sku,
                        title = product.title,
                        platform = product.platform.id,
                        realPrice = product.realPrice,
                        listPrice = product.listPrice,
                        url = product.url,
                        imageUrl = product.imageUrl,
                        addedPrice = product.realPrice,  // 追加時価格を基準として固定
                    )
                )
                _state.value = cur.copy(isInWatchlist = true)
            }
            // ウィジェット更新 (ホーム画面の最安値リストを最新化)
            try {
                val items = kotlinx.coroutines.flow.first(watchlistDao.observeAll())
                com.example.popcoon.widget.WidgetUpdater.update(context, items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PopcoonLogger.w(this@ProductDetailViewModel, "Widget update failed: ${e.message}")
            }
        }
    }

    /**
     * ウォッチリスト追加をトリガーに Play In-App Review を起動する。
     * shouldRequest が偽 (5回未満 or 90日クールダウン中) のときは noop。
     * Activity 参照が必要なため Screen 側から呼ぶ。
     */
    fun requestReviewIfEligible(activity: android.app.Activity) {
        viewModelScope.launch {
            reviewPrompter.recordSuccess()
            reviewPrompter.requestIfEligible(activity)
        }
    }

    /**
     * タイトルから EcoEthicsScorer のカテゴリ (smartphone/laptop/tv/tshirt) を推定する。
     * 未知は "other" を返す (スコアラ側で既定 CO2 にフォールバック)。
     */
    private fun inferEthicsCategory(title: String): String {
        val t = title.lowercase()
        return when {
            ETHICS_SMARTPHONE.containsMatchIn(t) -> "smartphone"
            ETHICS_LAPTOP.containsMatchIn(t) -> "laptop"
            ETHICS_TV.containsMatchIn(t) -> "tv"
            ETHICS_TSHIRT.containsMatchIn(t) -> "tshirt"
            else -> "other"
        }
    }

    /** タイトルに含まれるエコ認証ワードを抽出 (CO2 スコアの加点判定に使う)。 */
    private fun extractCertifications(title: String): List<String> {
        val out = mutableListOf<String>()
        if (title.contains("エコ")) out += "エコマーク"
        if (title.lowercase().contains("green") || title.contains("オーガニック")) out += "green"
        return out
    }

    /** productKey の形式検証: "platform:sku" (スキップ不可、空文字列不可) */
    private fun isValidProductKey(key: String): Boolean {
        if (key.isBlank()) return false
        val parts = key.split(":", limit = 2)
        return parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()
    }

    /** productKey だけで最小 Product を構築するフォールバック */
    private fun buildProductFromKey(productKey: String, history: List<PriceRecord>): Product {
        val parts = productKey.split(":", limit = 2)
        val platform = Platform.fromId(parts.getOrNull(0))
        val sku = parts.getOrNull(1) ?: productKey
        val latest = history.firstOrNull()
        return Product(
            sku = sku,
            title = sku,   // タイトル不明時は SKU で代用
            platform = platform,
            realPrice = latest?.realPrice ?: 0L,
            listPrice = latest?.listPrice ?: 0L,
        )
    }
}

// エコ倫理カテゴリ推定用のキーワード (大文字小文字非依存・日英混在対応)
private val ETHICS_SMARTPHONE = Regex("スマホ|スマートフォン|iphone|android|smartphone|携帯電話")
private val ETHICS_LAPTOP = Regex("ノートpc|ノートパソコン|laptop|macbook|ノートブック|ウルトラブック")
private val ETHICS_TV = Regex("テレビ|液晶テレビ|有機el|television|\\btv\\b")
private val ETHICS_TSHIRT = Regex("tシャツ|ティーシャツ|tshirt|t-shirt|カットソー|アパレル|衣料")

/**
 * SearchScreen → ProductDetailScreen への Product 受け渡しヘルパー。
 *
 * savedStateHandle は遷移先に届かないため ProductNavCache 経由で渡す。
 */
fun androidx.navigation.NavController.navigateToDetail(product: Product) {
    ProductNavCache.put(product)
    // launchSingleTop: 同一商品の行を素早く 2 度タップしても詳細画面が二重に
    // push されない (同一 route なら先頭を再利用する)。
    navigate("detail/${product.platform.id}:${product.sku}") { launchSingleTop = true }
}
