package com.example.popcoon.feature.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Popcoon Premium サブスクリプションマネージャ。
 *
 * Premium 特典:
 *  - アフィリエイト UI の非表示 (UX 向上)
 *  - 価格履歴のエクスポート (CSV)
 *  - アラート数 無制限 (無料は 5件まで)
 *  - 詳細 CO2 データ (IEA 電力源別)
 *  - 広告なし (将来広告を入れる場合)
 *
 * 価格帯 (研究結果に基づく): ¥480/月 または ¥3,800/年 (~33% 割引)
 * 業界標準の subscription ARPU = ad-only の 4.6 倍を狙う。
 */
class BillingManager(private val context: Context) {

    companion object {
        const val SKU_PREMIUM_MONTHLY = "popcoon_premium_monthly"
        const val SKU_PREMIUM_YEARLY = "popcoon_premium_yearly"
    }

    enum class PremiumStatus { ACTIVE, INACTIVE, PENDING, UNKNOWN }

    private val _status = MutableStateFlow(PremiumStatus.UNKNOWN)
    val status: StateFlow<PremiumStatus> = _status.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) handlePurchase(purchase)
        } else if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            _status.value = PremiumStatus.ACTIVE
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    suspend fun initialize(): Boolean = suspendCancellableCoroutine { cont ->
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                if (ok) queryPurchases()
                if (cont.isActive) cont.resume(ok)
            }
            override fun onBillingServiceDisconnected() {
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    suspend fun queryOffers(): List<ProductDetails> {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SKU_PREMIUM_MONTHLY)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SKU_PREMIUM_YEARLY)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
            )).build()

        return suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    cont.resume(details)
                } else {
                    cont.resume(emptyList())
                }
            }
        }
    }

    fun launchPurchase(activity: Activity, productDetails: ProductDetails): Boolean {
        val offer = productDetails.subscriptionOfferDetails?.firstOrNull() ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offer.offerToken)
                    .build()
            )).build()
        val result = billingClient.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build()
        billingClient.queryPurchasesAsync(params) { _, purchases ->
            val active = purchases.any {
                it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            _status.value = if (active) PremiumStatus.ACTIVE else PremiumStatus.INACTIVE
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                _status.value = PremiumStatus.PENDING
            }
            return
        }
        // acknowledge (48 時間以内に必須)
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken).build()
            billingClient.acknowledgePurchase(params) { _status.value = PremiumStatus.ACTIVE }
        } else {
            _status.value = PremiumStatus.ACTIVE
        }
    }

    fun dispose() {
        billingClient.endConnection()
    }
}
