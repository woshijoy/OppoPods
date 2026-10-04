package moe.chenxy.oppopods.pods

import android.content.Context

/**
 * Recognises earbuds from the BBK family (OPPO / OnePlus / realme, including Enco models).
 *
 * They all pair with the HeyMelody (欢律) app and speak the same AT protocol this module
 * implements, but their Bluetooth names do not necessarily contain "oppo" - a OnePlus Buds 4
 * shows up as "OnePlus Buds 4". The hooks used to test `name.contains("oppo")`, so those
 * models were silently ignored; this matcher is the single place that decides support.
 */
object PodDeviceMatcher {
    private val BRAND_KEYWORDS = listOf("oppo", "oneplus", "enco", "realme", "一加")

    /** Brand-only check, safe to use without a Context. */
    fun matchesName(deviceName: String?): Boolean {
        val name = deviceName?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return BRAND_KEYWORDS.any { name.contains(it) }
    }

    /**
     * Brand check plus the bundled official model table. The table wins when it knows the
     * exact name, so speakers that share a brand (e.g. realme Cobble Speaker) stay excluded.
     */
    fun matches(context: Context?, deviceName: String?): Boolean {
        val name = deviceName?.takeIf { it.isNotBlank() } ?: return false
        if (context != null) {
            when (DeviceModelRegistry.modelKind(context, name)) {
                DeviceModelRegistry.ModelKind.POD -> return true
                DeviceModelRegistry.ModelKind.NON_POD -> return false
                DeviceModelRegistry.ModelKind.UNKNOWN -> Unit
            }
        }
        return matchesName(name)
    }
}
