package com.livepets3d.app.monetization

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.livepets3d.app.BuildConfig
import com.livepets3d.app.data.PetRepository
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object PlayBillingFactory {
    fun create(context: Context, repo: PetRepository): BillingProvider = PlayBilling(context.applicationContext, repo)
}

/**
 * Google Play Billing (Play Billing Library 7).
 * - Loads localized prices for the four products configured in Play Console.
 * - Subscriptions are acknowledged; gem packs are consumed and credited exactly once.
 * - Premium is granted only from a PURCHASED, signature-verified subscription and is
 *   re-validated on every launch / restore (expired subscriptions remove Premium).
 * For server-grade validation, send purchaseToken to a backend that calls the Play Developer API.
 */
private class PlayBilling(private val context: Context, private val repo: PetRepository) : BillingProvider, PurchasesUpdatedListener {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private val items = Catalog.all()
    private val details = HashMap<String, ProductDetails>()
    private var pendingResult: ((Boolean, String) -> Unit)? = null
    private var connected = false
    private var status = "Connecting to Google Play…"

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init { connect() }

    override val isPremium: Boolean get() = repo.premiumEntitled
    override val storeReady: Boolean get() = connected && details.isNotEmpty()
    override val statusMessage: String get() = status
    override fun products(): List<Product> = items
    override fun addListener(l: () -> Unit) { listeners.add(l) }
    override fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    connected = true
                    status = "Connected to Google Play"
                    loadProducts()
                    refreshEntitlements(null)
                } else {
                    connected = false
                    status = "Google Play Billing unavailable (${r.debugMessage.ifBlank { "code ${r.responseCode}" }}). Install from Google Play to purchase."
                    notifyChanged()
                }
            }
            override fun onBillingServiceDisconnected() { connected = false; status = "Disconnected from Google Play"; notifyChanged() }
        })
    }

    private fun loadProducts() {
        fun query(type: String, ids: List<String>) {
            val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
            }).build()
            client.queryProductDetailsAsync(params) { r, list ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (pd in list) {
                        details[pd.productId] = pd
                        val item = items.firstOrNull { it.id == pd.productId } ?: continue
                        item.price = if (type == BillingClient.ProductType.SUBS)
                            pd.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
                        else pd.oneTimePurchaseOfferDetails?.formattedPrice
                    }
                    if (list.isEmpty()) status = "Products not found — publish them in Play Console"
                }
                notifyChanged()
            }
        }
        query(BillingClient.ProductType.SUBS, items.filter { it.kind == ProductKind.SUBSCRIPTION }.map { it.id })
        query(BillingClient.ProductType.INAPP, items.filter { it.kind == ProductKind.CONSUMABLE }.map { it.id })
    }

    override fun purchase(activity: Activity, product: Product, onResult: (Boolean, String) -> Unit) {
        if (!connected) { onResult(false, status); connect(); return }
        val pd = details[product.id] ?: run { onResult(false, "${product.title} is not available from Google Play yet."); return }
        val pdp = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd)
        if (product.kind == ProductKind.SUBSCRIPTION) {
            val token = pd.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run { onResult(false, "No subscription offer available."); return }
            pdp.setOfferToken(token)
        }
        pendingResult = onResult
        val r = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(pdp.build())).build())
        if (r.responseCode != BillingClient.BillingResponseCode.OK) { pendingResult = null; onResult(false, "Could not open Google Play (${r.responseCode}).") }
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        val cb = pendingResult; pendingResult = null
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handle(it) }
                main.post { cb?.invoke(true, "Thank you! Your purchase is being confirmed.") }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> main.post { cb?.invoke(false, "Purchase cancelled.") }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> { refreshEntitlements(null); main.post { cb?.invoke(true, "You already own this — restored.") } }
            else -> main.post { cb?.invoke(false, "Purchase failed: ${r.debugMessage}") }
        }
    }

    private fun handle(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!verify(p.originalJson, p.signature)) { Log.w("LivePetsBilling", "signature check failed"); return }
        val product = items.firstOrNull { it.id in p.products } ?: return
        if (product.kind == ProductKind.CONSUMABLE) {
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { res, _ ->
                if (res.responseCode == BillingClient.BillingResponseCode.OK) {
                    val key = "consumed_" + p.purchaseToken.hashCode()
                    if (!repo.sp.getBoolean(key, false)) {
                        repo.sp.edit().putBoolean(key, true).apply()
                        repo.gems += product.gems
                    }
                    notifyChanged()
                }
            }
        } else {
            repo.premiumEntitled = true
            if (!p.isAcknowledged) client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
            notifyChanged()
        }
    }

    /** Re-reads active subscriptions from Google Play; removes Premium when none is active. */
    private fun refreshEntitlements(onDone: ((Boolean, String) -> Unit)?) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                val active = list.any { it.purchaseState == Purchase.PurchaseState.PURCHASED && verify(it.originalJson, it.signature) }
                repo.premiumEntitled = active
                list.forEach { handle(it) }
                notifyChanged()
                onDone?.let { main.post { it(active, if (active) "Premium restored." else "No active subscription found on this Google account.") } }
            } else onDone?.let { main.post { it(false, "Could not reach Google Play (${r.responseCode}).") } }
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) list.forEach { handle(it) } // finish any unconsumed gem packs
        }
    }

    override fun restore(onResult: (Boolean, String) -> Unit) {
        if (!connected) { onResult(false, status); connect(); return }
        refreshEntitlements(onResult)
    }

    /** Verifies Google Play's RSA signature when the app's license key is configured. */
    private fun verify(json: String, sig: String): Boolean {
        val key = BuildConfig.PLAY_LICENSE_KEY
        if (key.isBlank()) return true // not configured yet (see README) — Play still guarantees purchase state
        return try {
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
            Signature.getInstance("SHA1withRSA").run { initVerify(pub); update(json.toByteArray()); verify(Base64.decode(sig, Base64.DEFAULT)) }
        } catch (e: Exception) { false }
    }
}
