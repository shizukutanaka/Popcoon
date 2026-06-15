package com.example.popcoon.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing
import androidx.compose.foundation.verticalScroll
import com.example.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.popcoon.R
import com.example.popcoon.feature.affiliate.AffiliateUrlBuilder
import com.example.popcoon.ui.util.HapticFeedback
import androidx.compose.ui.unit.dp
import com.example.popcoon.feature.scorer.BuyTimingScorer
import com.example.popcoon.ui.components.VerdictBadge

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
                        IconButton(onClick = {
                            HapticFeedback.success(context)
                            viewModel.toggleWatchlist(cur.product)
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
                    com.example.popcoon.ui.components.ProductDetailSkeleton()
                is DetailUiState.Loaded -> LoadedContent(s, s.affiliateOptin)
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
                                s.msg,
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
private fun LoadedContent(s: DetailUiState.Loaded, affiliateOptin: Boolean) {
    // ─ タイトル
    // ─ 商品画像 + タイトル
    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
        com.example.popcoon.ui.components.ProductImage(
            imageUrl = s.product.imageUrl,
            platformEmoji = when (s.product.platform) {
                com.example.popcoon.data.model.Platform.AMAZON -> "📦"
                com.example.popcoon.data.model.Platform.RAKUTEN -> "🛒"
                com.example.popcoon.data.model.Platform.YAHOO -> "🟡"
            },
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
    com.example.popcoon.ui.components.ScoreCard(
        score = s.score,
        verdict = s.verdict,
        confidence = s.confidence,
        signals = s.signals,
    )

    // ─ 価格詳細
    InfoCard(stringResource(R.string.detail_price), listOf(
        stringResource(R.string.detail_current_price) to com.example.popcoon.core.CurrencyFormatter.yen(s.product.totalPrice),
        stringResource(R.string.detail_list_price) to com.example.popcoon.core.CurrencyFormatter.yen(s.product.listPrice),
        stringResource(R.string.detail_shipping) to com.example.popcoon.core.CurrencyFormatter.yen(s.product.shippingFee),
    ))

    // ─ ポイント還元 実質価格 (ほぼやすねっと/最安値.com 対抗)
    com.example.popcoon.ui.components.PointSimulatorCard(product = s.product, userCtx = s.userCtx)

    // ─ 価格履歴チャート (Keepa/Pricey 標準機能)
    if (s.priceHistory.isNotEmpty()) {
        Text(stringResource(R.string.detail_price_history), style = MaterialTheme.typography.titleMedium)
        com.example.popcoon.ui.components.PriceChart(records = s.priceHistory)
    }

    // ─ 価格予測カード (Holt's linear + 信頼区間 — 競合非搭載の差別化機能)
    s.prediction?.let { pred ->
        Spacer(Modifier.height(Spacing.md))
        com.example.popcoon.ui.components.PricePredictionCard(prediction = pred)
    }

    // ─ セット販売の実質単価 (Popcoon 独自 — まとめ買いの1個あたり価格)
    s.bundle?.let { bundle ->
        Spacer(Modifier.height(Spacing.md))
        com.example.popcoon.ui.components.BundleCard(analysis = bundle)
    }

    // ─ レビュー信頼度 (統計的サクラ検出 — レビュー本文を端末外に送らない)
    s.reviewTrust?.let { rt ->
        if (rt.trust != com.example.popcoon.feature.review.ReviewTrustScorer.Trust.UNKNOWN) {
            Spacer(Modifier.height(Spacing.md))
            com.example.popcoon.ui.components.ReviewTrustBadge(result = rt)
        }
    }

    // ─ TCO カード (総保有コスト — プリンター/PC 等の長期コスト可視化)
    s.tco?.let { tco ->
        Spacer(Modifier.height(Spacing.md))
        com.example.popcoon.ui.components.TCOCard(result = tco)
    }

    // ─ 環境・倫理スコア (原産国の CO2/労働指標 — 競合非搭載の差別化機能)
    s.ethics?.let { ethics ->
        Spacer(Modifier.height(Spacing.md))
        com.example.popcoon.ui.components.EthicsCard(
            score = ethics,
            origin = s.product.originCountry,
        )
    }

    // ─ 警告 (ダークパターン)
    if (s.warnings.isNotEmpty()) {
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
            }
        }
    }

    // ─ AI買い物アドバイザー
    s.aiAdvice?.let { advice ->
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(CornerRadius.card)) {
            Column(Modifier.padding(Spacing.ml)) {
                Text(stringResource(R.string.detail_ai_advice), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.ml))
                Text(advice, style = MaterialTheme.typography.bodyMedium)
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
