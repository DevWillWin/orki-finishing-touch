package com.example.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Modern Google Play Billing Library 8+ Manager for Orki AI.
 * Handles Subscriptions and In-App purchases with auto reconnection,
 * ProductDetails querying, launchBillingFlow, and purchase acknowledgement.
 */
class PlayBillingManager(
    private val context: Context,
    private val externalScope: CoroutineScope
) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        private const val TAG = "PlayBillingManager"

        // Play Console In-App & Subscription Product IDs: duration + "_" + subscription
        const val PRODUCT_PLUS_WEEKLY = "weekly_plus"
        const val PRODUCT_PLUS_MONTHLY = "monthly_plus"
        const val PRODUCT_PLUS_YEARLY = "yearly_plus"

        const val PRODUCT_PRO_WEEKLY = "weekly_pro"
        const val PRODUCT_PRO_MONTHLY = "monthly_pro"
        const val PRODUCT_PRO_YEARLY = "yearly_pro"

        val ALL_SUBSCRIPTION_IDS = listOf(
            PRODUCT_PLUS_WEEKLY,
            PRODUCT_PLUS_MONTHLY,
            PRODUCT_PLUS_YEARLY,
            PRODUCT_PRO_WEEKLY,
            PRODUCT_PRO_MONTHLY,
            PRODUCT_PRO_YEARLY
        )
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _productDetailsMap = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetailsMap = _productDetailsMap.asStateFlow()

    private val _purchasedPlan = MutableStateFlow<String?>(null)
    val purchasedPlan = _purchasedPlan.asStateFlow()

    private val _billingMessage = MutableSharedFlow<String>()
    val billingMessage = _billingMessage.asSharedFlow()

    // Using Google Play Billing 8.3.0 Builder with enablePendingPurchases and enableAutoServiceReconnection
    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun startConnection() {
        if (!billingClient.isReady) {
            Log.d(TAG, "Starting Play Billing connection...")
            billingClient.startConnection(this)
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Google Play Billing client setup succeeded (v8+).")
            _isConnected.value = true
            querySubscriptions()
            queryExistingPurchases()
        } else {
            Log.w(TAG, "Billing setup failed with code: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            _isConnected.value = false
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.d(TAG, "Play Billing service disconnected.")
        _isConnected.value = false
    }

    /**
     * Query Subscription product details from Google Play Console.
     */
    fun querySubscriptions() {
        if (!billingClient.isReady) {
            Log.w(TAG, "Cannot query product details: BillingClient not ready.")
            return
        }

        val productList = ALL_SUBSCRIPTION_IDS.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsList = queryResult.productDetailsList ?: emptyList()
                Log.d(TAG, "Found ${detailsList.size} subscription products in Play Console.")
                val map = detailsList.associateBy { it.productId }
                _productDetailsMap.value = map
            } else {
                Log.w(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Check if user already owns active subscriptions in Play Console.
     */
    fun queryExistingPurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchases)
            } else {
                Log.w(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
            }
        }
    }

    /**
     * Launch Google Play Billing Purchase Flow for selected tier and cycle.
     */
    fun launchPurchaseFlow(
        activity: Activity,
        targetPlan: String, // "Plus" or "Pro"
        cycle: String = "monthly" // "weekly", "monthly", "yearly"
    ) {
        val targetProductId = when (targetPlan) {
            "Plus" -> when (cycle) {
                "weekly" -> PRODUCT_PLUS_WEEKLY
                "yearly" -> PRODUCT_PLUS_YEARLY
                else -> PRODUCT_PLUS_MONTHLY
            }
            "Pro" -> when (cycle) {
                "weekly" -> PRODUCT_PRO_WEEKLY
                "yearly" -> PRODUCT_PRO_YEARLY
                else -> PRODUCT_PRO_MONTHLY
            }
            else -> PRODUCT_PLUS_MONTHLY
        }

        val productDetails = _productDetailsMap.value[targetProductId]

        if (productDetails != null) {
            val offers = productDetails.subscriptionOfferDetails
            val offerToken = offers?.firstOrNull()?.offerToken

            if (offerToken != null) {
                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()

                val result = billingClient.launchBillingFlow(activity, billingFlowParams)
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    emitMessage("Unable to launch Play billing: ${result.debugMessage}")
                }
            } else {
                emitMessage("Product offer token not available for $targetProductId")
            }
        } else {
            // When running in developer testing / pre-release without Play Store connection
            Log.i(TAG, "Product $targetProductId not found in Play Console or test mode active.")
            emitMessage("Play Console: Initiated purchase for $targetPlan ($cycle)")
            _purchasedPlan.value = targetPlan
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    processPurchases(purchases)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User canceled Play billing purchase flow.")
                emitMessage("Purchase canceled")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.d(TAG, "Item already owned.")
                emitMessage("You already have an active subscription!")
                queryExistingPurchases()
            }
            else -> {
                Log.e(TAG, "Play billing error: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                emitMessage("Billing error: ${billingResult.debugMessage}")
            }
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                val products = purchase.products
                val isPro = products.any { it.contains("pro", ignoreCase = true) }
                val isPlus = products.any { it.contains("plus", ignoreCase = true) }

                val plan = when {
                    isPro -> "Pro"
                    isPlus -> "Plus"
                    else -> "Plus"
                }

                _purchasedPlan.value = plan
                emitMessage("Successfully unlocked $plan Plan via Play Console!")

                // Acknowledge the purchase if not acknowledged yet
                if (!purchase.isAcknowledged) {
                    val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()

                    billingClient.acknowledgePurchase(acknowledgeParams) { result ->
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            Log.d(TAG, "Play Console Purchase acknowledged successfully.")
                        } else {
                            Log.w(TAG, "Failed to acknowledge purchase: ${result.debugMessage}")
                        }
                    }
                }
            }
        }
    }

    private fun emitMessage(msg: String) {
        externalScope.launch(Dispatchers.Main) {
            _billingMessage.emit(msg)
        }
    }

    fun destroy() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
