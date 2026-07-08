package io.github.shizukutanaka.popcoon.ui.screens.detail

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing
import androidx.compose.foundation.verticalScroll
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.UiText
import io.github.shizukutanaka.popcoon.feature.affiliate.AffiliateUrlBuilder
import io.github.shizukutanaka.popcoon.ui.util.HapticFeedback
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.ui.components.VerdictBadge
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    productKey: String,
    onBack: () -> Unit,
    viewModel: ProductDetailViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    LaunchedEffect(productKey) { viewModel.load(productKey) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val warningDesc = stringResource(R.string.a11y_warning)

    // 10-second dwell = documented success event → increments ReviewPrompter counter.
    // LaunchedEffect is cancelled automatically on back-navigation, so premature exits are safe.
    LaunchedEffect(productKey, "dwell") {
        val act = context as? Activity ?: return@LaunchedEffect
        delay(10_000L)
        viewModel.requestReviewIfEligible(act)
    }

    // Dark pattern detected → warning vibration. Documented in HapticFeedback.warning()
    // but was never triggered when warnings were displayed (promise-vs-reality gap).
    // Key on the product key (only when warnings exist), NOT the whole state object:
    // aiAdvice loads async (null→cached→text), so keying on `state` would re-fire the
    // vibration on every async update. This fires exactly once per product-with-warnings.
    val warningKey = (state as? DetailUiState.Loaded)
        ?.takeIf { it.warnings.isNotEmpty() }?.product?.key
    LaunchedEffect(warningKey) {
        if (warningKey != null) HapticFeedback.warning(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    // ウォッチリストに保存ボタン
                    val cur = state
                    if (cur is DetailUiState.Loaded) {
                        val activity = context as? Activity
                        IconButton(onClick = {
                            HapticFeedback.success(context)
                            val isAdding = !cur.isInWatchlist
                            viewModel.toggleWatchlist(cur.product)
                            if (isAdding && activity != null) {
                                viewModel.requestReviewIfEligible(activity)
                            }
                        }) {
                            Icon(
                                if (cur.isInWatchlist) AppIcons.Save else AppIcons.Unsave,
                                contentDescription = if (cur.isInWatchlist)
                                    stringResource(R.string.detail_saved)
                                else stringResource(R.string.detail_save),
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(Spacing.ml).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
        ) {
            when (val s = state) {
                DetailUiState.Loading ->
                    io.github.shizukutanaka.popcoon.ui.components.ProductDetailSkeleton()
                is DetailUiState.Loaded -> {
                    val act = context as? Activity
                    LoadedContent(
                        s = s,
                        affiliateOptin = s.affiliateOptin,
                        onAiHelpful = {
                            if (act != null) viewModel.requestReviewIfEligible(act)
                        },
                        onWaitChosen = {
                            if (act != null) viewModel.requestReviewIfEligible(act)
                        },
                    )
                }
                is DetailUiState.Error -> {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
                            modifier = Modifier.padding(Spacing.xxxl),
                        ) {
                            Text("⚠️", style = MaterialTheme.typography.displayMedium,
                                modifier = Modifier.semantics { contentDescription = warningDesc })
                            Text(
                                s.msg.asString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            FilledTonalButton(
                                onClick = { viewModel.load(productKey) },
                            ) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadedContent(
    s: DetailUiState.Loaded,
    affiliateOptin: Boolean,
    onAiHelpful: () -> Unit = {},
    onWaitChosen: () -> Unit = {},
) {
    // ─ タイトル
    // ─ 商品画像 + タイトル
    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        io.github.shizukutanaka.popcoon.ui.components.ProductImage(
            imageUrl = s.product.imageUrl,
            size = 80.dp,
        )
        Spacer(Modifier.width(Spacing.ml))
        Text(
            s.product.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }

    // ─ 買い時スコア (段階的開示: 要約→詳細)
    io.github.shizukutanaka.popcoon.ui.components.ScoreCard(
        score = s.score,
        verdict = s.verdict,
        confidence = s.confidence,
        signals = s.signals,
    )

    // ─ 価格詳細
    InfoCard(stringResource(R.string.detail_price), listOf(
        stringResource(R.string.detail_current_price) to io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(s.product.totalPrice),
        stringResource(R.string.detail_list_price) to io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(s.product.listPrice),
        stringResource(R.string.detail_shipping) to io.github.shizukutanaka.popcoon.core.CurrencyFormatter.yen(s.product.shippingFee),
    ))

    // ─ ポイント還元 実質価格 (ほぼやすねっと/最安値.com 対抗)
    io.github.shizukutanaka.popcoon.ui.components.PointSimulatorCard(product = s.product, userCtx = s.userCtx)

    // ─ 価格履歴チャート (Keepa/Pricey 標準機能)
    if (s.priceHistory.isNotEmpty()) {
        Text(stringResource(R.string.detail_price_history), style = MaterialTheme.typography.titleMedium)
        io.github.shizukutanaka.popcoon.ui.components.PriceChart(records = s.priceHistory)
    }

    // ─ 価格予測カード (Holt's linear + 信頼区間 — 競合非搭載の差別化機能)
    s.prediction?.let { pred ->
        Spacer(Modifier.height(Spacing.md))
        io.github.shizukutanaka.popcoon.ui.components.PricePredictionCard(prediction = pred)
    }

    // ─ 詳細情報 (セット単価/レビュー信頼度/TCO/エコ倫理) — Progressive Disclosure。
    // 主要な買い時判断材料 (ScoreCard/価格/予測/AI助言) と、あると便利だが必須ではない
    // 補足情報を分離し、既定で折りたたむ (機能過不足監査 C1: 商品詳細画面の
    // カード積層過多への対応。ScoreCard と同じ展開パターンを踏襲)。
    val hasSupplementalInfo = s.bundle != null ||
        (s.reviewTrust != null &&
            s.reviewTrust.trust != io.github.shizukutanaka.popcoon.feature.review.ReviewTrustScorer.Trust.UNKNOWN) ||
        s.tco != null || s.ethics != null
    if (hasSupplementalInfo) {
        var detailsExpanded by remember(s.product.key) { mutableStateOf(false) }
        Spacer(Modifier.height(Spacing.md))
        Card(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                detailsExpanded = !detailsExpanded
            },
            shape = RoundedCornerShape(CornerRadius.card),
        ) {
            Column(Modifier.padding(Spacing.lg)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.detail_more_info),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (detailsExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = detailsExpanded) {
                    Column(
                        Modifier.padding(top = Spacing.ml),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        s.bundle?.let { bundle ->
                            io.github.shizukutanaka.popcoon.ui.components.BundleCard(analysis = bundle)
                        }
                        s.reviewTrust?.let { rt ->
                            if (rt.trust != io.github.shizukutanaka.popcoon.feature.review.ReviewTrustScorer.Trust.UNKNOWN) {
                                io.github.shizukutanaka.popcoon.ui.components.ReviewTrustBadge(result = rt)
                            }
                        }
                        s.tco?.let { tco ->
                            io.github.shizukutanaka.popcoon.ui.components.TCOCard(result = tco)
                        }
                        s.ethics?.let { ethics ->
                            io.github.shizukutanaka.popcoon.ui.components.EthicsCard(
                                score = ethics,
                                origin = s.product.originCountry,
                            )
                        }
                    }
                }
            }
        }
    }

    // ─ 警告 (ダークパターン)
    if (s.warnings.isNotEmpty()) {
        var waitChosen by remember(s.product.key) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(CornerRadius.card)) {
            Column(Modifier.padding(Spacing.ml)) {
                Text(stringResource(R.string.detail_warnings_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.ml))
                s.warnings.forEach { w ->
                    Text("• $w", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Spacing.ml))
                }
                // ダークパターン警告に対して能動的な「様子を見る」選択肢を提示する。
                // 衝動買いを思いとどまる後押し + ReviewPrompter の成功イベント (4番目の文書化経路)。
                if (waitChosen) {
                    Text(
                        stringResource(R.string.detail_warning_wait_done),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    FilledTonalButton(onClick = { waitChosen = true; onWaitChosen() }) {
                        Text(stringResource(R.string.detail_warning_wait))
                    }
                }
            }
        }
    }

    // ─ AI買い物アドバイザー
    s.aiAdvice?.let { advice ->
        var feedbackGiven by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(CornerRadius.card)) {
            Column(Modifier.padding(Spacing.ml)) {
                Text(stringResource(R.string.detail_ai_advice), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.ml))
                Text(advice.asString(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.ml))
                if (feedbackGiven) {
                    Text(
                        stringResource(R.string.detail_ai_feedback_thanks),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TextButton(onClick = { feedbackGiven = true; onAiHelpful() }) {
                            Text(stringResource(R.string.detail_ai_helpful))
                        }
                        TextButton(onClick = { feedbackGiven = true }) {
                            Text(
                                stringResource(R.string.detail_ai_not_helpful),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // ─ 購入ページを開くボタン (アフィリエイト有効時は #ad 表示 — 景品表示法 8 条)
    if (s.product.url.isNotBlank()) {
        val context = LocalContext.current
        val url = remember(s.product.url, affiliateOptin) {
            AffiliateUrlBuilder.build(
                platform = s.product.platform,
                rawUrl = s.product.url,
                optOut = !affiliateOptin,
            )
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Button(
                onClick = {
                    HapticFeedback.success(context)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.detail_buy_button))
            }
            if (affiliateOptin) {
                Text(
                    stringResource(R.string.detail_buy_ad_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(CornerRadius.card)) {
        Column(Modifier.padding(Spacing.ml)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.ml))
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, style = MaterialTheme.typography.bodyMedium)
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(Spacing.ml))
            }
        }
    }
}
