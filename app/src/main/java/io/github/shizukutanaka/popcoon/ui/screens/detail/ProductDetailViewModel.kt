package io.github.shizukutanaka.popcoon.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shizukutanaka.popcoon.data.model.Platform
import io.github.shizukutanaka.popcoon.data.model.PriceRecord
import io.github.shizukutanaka.popcoon.data.model.Product
import io.github.shizukutanaka.popcoon.data.repository.IProductRepository
import io.github.shizukutanaka.popcoon.feature.ai.BuyingAdvisor
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternDetector
import io.github.shizukutanaka.popcoon.feature.darkpattern.DarkPatternTextDetector
import io.github.shizukutanaka.popcoon.ui.toLabelResource
import io.github.shizukutanaka.popcoon.feature.ethics.EcoEthicsScorer
import io.github.shizukutanaka.popcoon.feature.prediction.PricePredictionEngine
import io.github.shizukutanaka.popcoon.feature.bundle.BundlePackDetector
import io.github.shizukutanaka.popcoon.feature.review.ReviewTrustScorer
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.feature.points.PointSimulator
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.feature.retention.ReviewPrompter
import io.github.shizukutanaka.popcoon.feature.settings.UserPreferences
import io.github.shizukutanaka.popcoon.ui.UiText
import io.github.shizukutanaka.popcoon.feature.tco.TCOCalculator
import io.github.shizukutanaka.popcoon.core.CurrencyFormatter
import io.github.shizukutanaka.popcoon.core.PopcoonLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val adviceCache: io.github.shizukutanaka.popcoon.feature.ai.AdviceCache,
    private val watchlistDao: io.github.shizukutanaka.popcoon.data.db.WatchlistDao,
    private val prefs: UserPreferences,
    private val reviewPrompter: ReviewPrompter,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    // toggleWatchlist() の read (isInWatchlist) → suspend DAO 呼び出し → write (_state 更新) の
    // 間に複数の中断点があり、★ ボタンの連打で2つの呼び出しが両方とも同じ古い isInWatchlist を
    // 読んでしまうレースがあった (両方が「追加」実行、片方の削除操作が失われる等)
    // (機能過不足監査で発見)。1トグル操作を完全に直列化して防ぐ。
    private val toggleWatchlistMutex = Mutex()

    /**
     * 進行中の読み込み。再入時に前回をキャンセルする。
     *
     * load() は `LaunchedEffect(productKey)` のほか **エラー画面の再試行ボタン**からも
     * 呼ばれる。ガードが無いと連打で複数の読み込みが並走し、遅れて終わった**古い方**が
     * 後から `_state` を上書きして、新しい試行が成功していてもエラー画面のままになる
     * (逆に古い成功で新しいエラーが隠れることもある)。SearchViewModel が
     * `searchJob?.cancel()` で既に解決している同じ問題なので、同じ形で揃える。
     */
    private var loadJob: Job? = null

    fun load(productKey: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = DetailUiState.Loading
            if (!isValidProductKey(productKey)) {
                PopcoonLogger.w(this@ProductDetailViewModel, "Malformed productKey: $productKey")
                _state.value = DetailUiState.Error(UiText.StringResource(R.string.detail_error_invalid_key))
                return@launch
            }
            runCatching {
                // 2. Product: ProductNavCache → なければ productKey からフォールバック構築
                val cachedProduct = ProductNavCache.consume(productKey)

                // 1. 価格履歴を backend から取得 (非同期)
                val historyDeferred = async { repository.getPriceHistory(productKey) }
                // 1b. 商品ページの JSON-LD (FallbackScraper) で原産国・JAN 等を補完 (非同期)。
                //     ProductNavCache 由来の product は検索 API (Amazon PA-API/楽天/Yahoo) の
                //     レスポンスそのままで、いずれも原産国を一切返さないため、これが無いと
                //     EthicsCard は永久に表示されなかった (機能過不足監査で発見: refresh() は
                //     このために実装済みだったが、呼び出し元がどこにも存在しなかった)。
                //     refresh() は失敗時 null を返す (以前は元の product に握りつぶしていたが、
                //     それが PriceSyncWorker の在庫アラート誤検知の原因だったため撤去済み —
                //     機能過不足監査で発見)。null 時はこの下の `?: cachedProduct` が拾う。
                val refreshDeferred = cachedProduct?.takeIf { it.url.isNotEmpty() }
                    ?.let { p -> async { repository.refresh(p) } }

                val history = historyDeferred.await()
                val product = refreshDeferred?.await()
                    ?: cachedProduct
                    ?: buildProductFromKey(productKey, history)

                // 3. EC 会員設定から UserContext 構築 (PointSimulatorCard 個人化に供給)
                val userCtx = PointSimulator.UserContext(
                    rakutenSpu = kotlinx.coroutines.flow.first(prefs.rakutenSpu),
                    yahooPremium = kotlinx.coroutines.flow.first(prefs.yahooPremium),
                    paypaySoftbank = kotlinx.coroutines.flow.first(prefs.paypaySoftbank),
                    yahooRank = kotlinx.coroutines.flow.first(prefs.yahooRank),
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
                // .label は日本語固定文字列 (BuyTimingScorer 内部識別用) なので UI 表示には使わない。
                // toLabelResource() で type/severity からロケール対応の文字列リソースへ変換する。
                // context.getString() を直接使う (このクラスは @ApplicationContext を保持済みで、
                // SearchViewModel と異なり UiText 経由の Composable 解決を要しない)。
                // textSignals (DarkPatternTextDetector) の evidence は商品タイトルから抽出した
                // 生テキストそのもの (翻訳不能・翻訳すべきでない) のため、そのまま残す。
                // 詳細画面は全件表示するので切り捨ては起きないが、検索結果
                // (SearchViewModel) と並び順を揃えるためここでも深刻度順にする。
                val warnings = DarkPatternDetector
                    .prioritize(priceWarnings + listOfNotNull(dripWarning))
                    .map { w ->
                        val (resId, args) = w.toLabelResource()
                        val label = context.getString(resId, *args.toTypedArray())
                        val severity = context.getString(w.severity.toLabelResource())
                        "$label ($severity)"
                    }
                    .toMutableList()
                // カテゴリ名も表示する (以前は検出根拠の生テキストと severity のみで、
                // どの種類のダークパターンかユーザーが判別できなかった — 機能過不足監査で発見)。
                warnings += textSignals.map {
                    val category = context.getString(it.category.toLabelResource())
                    val severity = context.getString(it.severity.toLabelResource())
                    "$category: ${it.evidence} ($severity)"
                }

                // レビュー信頼性: LOW なら警告に追加 (サクラ・サンプル不足の注意喚起)
                val reviewTrust = ReviewTrustScorer.evaluate(product.rating, product.reviewCount)
                if (reviewTrust.trust == ReviewTrustScorer.Trust.LOW &&
                    reviewTrust.reasonKey == "review_trust_too_perfect"
                ) {
                    warnings += context.getString(
                        io.github.shizukutanaka.popcoon.R.string.review_trust_too_perfect,
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
                                // prediction (Holt 線形予測) を userContext として渡す。
                                // 渡さないと、AI が「買い時スコア」だけを見て「今買うべき」と
                                // 助言する一方、同じ画面の PricePredictionCard は「30日後に
                                // 値下がり予測」を表示する、という自己矛盾が起こりうる
                                // (両者は独立に計算され、従来 AI 側には全く伝わっていなかった)。
                                advisor.advise(product, score, userContext = predictionContext(prediction))
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
                // 生の例外メッセージ (英語スタックトレース等) をユーザーに見せない
                // (SearchViewModel と同方針)。ネットワーク起因は専用の案内文、
                // それ以外は汎用エラーに丸める。
                PopcoonLogger.w(this@ProductDetailViewModel, "商品詳細の取得に失敗: ${e.message}")
                val isNetworkError = e is java.io.IOException
                _state.value = DetailUiState.Error(
                    UiText.StringResource(
                        if (isNetworkError) R.string.error_network_unavailable
                        else R.string.error_detail_load_failed,
                    ),
                )
            }
        }
    }

    /** ウォッチリスト追加/削除トグル */
    fun toggleWatchlist(product: Product) {
        viewModelScope.launch {
            toggleWatchlistMutex.withLock {
                val cur = _state.value as? DetailUiState.Loaded ?: return@withLock
                if (cur.isInWatchlist) {
                    watchlistDao.delete(product.key)
                    _state.value = cur.copy(isInWatchlist = false)
                } else {
                    watchlistDao.upsert(
                        io.github.shizukutanaka.popcoon.data.db.WatchlistItem(
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
                    io.github.shizukutanaka.popcoon.widget.WidgetUpdater.update(context, items)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PopcoonLogger.w(this@ProductDetailViewModel, "Widget update failed: ${e.message}")
                }
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

    // 純ロジック (エコ認証抽出・予測トレンド整形・productKey 検証) は ProductDetailLogic へ
    // 切り出し済み。ViewModel 本体は Context 依存で plain JVM テストが不可能なため、
    // 分岐が濃い部分をテスト可能な純関数に分離する (PriceSyncPlanner と同方針)。
    private fun extractCertifications(title: String): List<String> =
        ProductDetailLogic.extractCertifications(title)

    private fun predictionContext(prediction: PricePredictionEngine.Prediction?): String =
        ProductDetailLogic.predictionContext(prediction)

    private fun isValidProductKey(key: String): Boolean =
        ProductDetailLogic.isValidProductKey(key)

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
