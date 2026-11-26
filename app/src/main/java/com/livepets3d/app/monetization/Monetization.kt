package com.livepets3d.app.monetization

import android.app.Activity
import android.content.Context
import com.livepets3d.app.data.PetRepository
import com.livepets3d.app.data.PetSpecies

/**
 * Monetization seams. This beta ships with no-op providers; the real Google Play Billing and
 * AdMob implementations plug in here without touching UI or pet logic.
 *
 * Rules already encoded for later:
 *  - Ads are NEVER shown inside the floating overlay — only in the main app (e.g. rewarded ads in the shop).
 *  - Premium unlocks premium animals, removes ads and enables up to 3 animals on screen.
 */
interface BillingProvider {
    val isPremium: Boolean
    fun products(): List<Product>
    fun purchase(activity: Activity, product: Product, onResult: (Boolean, String) -> Unit)
}

interface AdsProvider {
    fun canShowRewarded(): Boolean
    fun showRewarded(activity: Activity, onReward: (Boolean) -> Unit)
}

data class Product(val id: String, val title: String, val price: String, val subtitle: String, val highlight: Boolean = false)

private class BetaBilling(private val repo: PetRepository) : BillingProvider {
    override val isPremium: Boolean get() = repo.sp.getBoolean("beta_premium", true)
    override fun products() = listOf(
        Product("premium_monthly", "Premium Monthly", "\$2.99", "All animals · no ads · 3 pets on screen"),
        Product("premium_yearly", "Premium Yearly", "\$19.99", "Best value — save 44%", highlight = true),
        Product("gems_small", "Handful of Gems", "\$0.99", "120 gems"),
        Product("gems_big", "Treasure Chest", "\$4.99", "800 gems + bonus outfit"),
    )
    override fun purchase(activity: Activity, product: Product, onResult: (Boolean, String) -> Unit) {
        onResult(false, "Store payments are not connected in this beta build. All premium content is unlocked for testing.")
    }
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

    fun init(context: Context, repo: PetRepository) {
        billing = BetaBilling(repo)
        ads = NoAds()
    }

    fun isUnlocked(s: PetSpecies): Boolean = s.available && (!s.premium || billing.isPremium)
    val maxPetsOnScreen: Int get() = if (billing.isPremium) 3 else 1
}
