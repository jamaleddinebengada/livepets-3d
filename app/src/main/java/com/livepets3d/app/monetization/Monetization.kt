package com.livepets3d.app.monetization

import android.app.Activity
import android.content.Context
import com.livepets3d.app.BuildConfig
import com.livepets3d.app.data.PetRepository
import com.livepets3d.app.data.PetSpecies

/**
 * Monetization seams.
 *  - Production builds use Google Play Billing ([PlayBilling]): real localized prices, purchases,
 *    acknowledgement, consumption of gem packs and restore.
 *  - TEST_MODE builds (the "tester" APK) use [TestBilling], clearly labelled, never shipped to Play.
 *  - Ads are NEVER shown inside the floating overlay.
 */
interface BillingProvider {
    val isPremium: Boolean
    /** True once the store is connected and product details are loaded. */
    val storeReady: Boolean
    val statusMessage: String
    fun products(): List<Product>
    fun purchase(activity: Activity, product: Product, onResult: (Boolean, String) -> Unit)
    fun restore(onResult: (Boolean, String) -> Unit)
    fun addListener(l: () -> Unit)
    fun removeListener(l: () -> Unit)
}

interface AdsProvider {
    fun canShowRewarded(): Boolean
    fun showRewarded(activity: Activity, onReward: (Boolean) -> Unit)
}

enum class ProductKind { SUBSCRIPTION, CONSUMABLE }

data class Product(
    val id: String, val title: String, val fallbackPrice: String, val subtitle: String,
    val kind: ProductKind, val gems: Int = 0, val highlight: Boolean = false, var price: String? = null,
) {
    val displayPrice: String get() = price ?: fallbackPrice
}

object Catalog {
    fun all() = listOf(
        Product("premium_monthly", "Premium Monthly", "\$2.99", "All animals · no ads · 3 pets on screen", ProductKind.SUBSCRIPTION),
        Product("premium_yearly", "Premium Yearly", "\$19.99", "Best value — save 44%", ProductKind.SUBSCRIPTION, highlight = true),
        Product("gems_120", "Handful of Gems", "\$0.99", "120 gems", ProductKind.CONSUMABLE, gems = 120),
        Product("gems_800", "Treasure Chest", "\$4.99", "800 gems", ProductKind.CONSUMABLE, gems = 800),
    )
}

/** Test-only provider: used exclusively when BuildConfig.TEST_MODE is true. */
private class TestBilling(private val repo: PetRepository) : BillingProvider {
    private val listeners = ArrayList<() -> Unit>()
    private val items = Catalog.all()
    override val isPremium: Boolean get() = repo.sp.getBoolean("test_premium", true)
    override val storeReady = true
    override val statusMessage = "TEST MODE — purchases are simulated and never charged"
    override fun products() = items
    override fun purchase(activity: Activity, product: Product, onResult: (Boolean, String) -> Unit) {
        if (product.kind == ProductKind.CONSUMABLE) repo.gems += product.gems
        else repo.sp.edit().putBoolean("test_premium", true).apply()
        listeners.forEach { it() }
        onResult(true, "Test purchase of ${product.title} completed (no money charged).")
    }
    override fun restore(onResult: (Boolean, String) -> Unit) = onResult(true, "Test mode: Premium restored.")
    override fun addListener(l: () -> Unit) { listeners.add(l) }
    override fun removeListener(l: () -> Unit) { listeners.remove(l) }
}

private class NoAds : AdsProvider {
    override fun canShowRewarded() = false
    override fun showRewarded(activity: Activity, onReward: (Boolean) -> Unit) = onReward(false)
}

object Monetization {
    lateinit var billing: BillingProvider
        private set
    lateinit var ads: AdsProvider
        private set
    val testMode: Boolean get() = BuildConfig.TEST_MODE

    fun init(context: Context, repo: PetRepository) {
        billing = if (BuildConfig.TEST_MODE) TestBilling(repo) else PlayBillingFactory.create(context, repo)
        ads = NoAds()
    }

    /** Production: free animals, gem-unlocked animals, or Premium. Tester build: TestBilling reports Premium. */
    fun isUnlocked(s: PetSpecies, repo: PetRepository): Boolean =
        s.available && (s.free || billing.isPremium || (!s.premium && repo.owns("pet_${s.name}")))
    val maxPetsOnScreen: Int get() = if (billing.isPremium) 3 else 1
}
