package moe.chenxy.oppopods.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.PodImagePrefs
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.config.imageUri

object PodImageLoader {
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"

    fun loadBitmap(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("OppoPods-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(
            moduleContext.resources,
            resolveFallbackId(moduleContext, resource, fallbackResId),
        )
    }

    fun loadBitmapWithCustomFallback(
        context: Context,
        prefs: SharedPreferences,
        address: String,
        resource: PodImageResource,
        customFallbackResource: PodImageResource,
        fallbackResId: Int,
    ): Bitmap? {
        val earphone = runCatching { PodImagePrefs.findOrLatest(prefs, address) }.getOrNull()
        val custom = runCatching {
            earphone?.imageUri(resource)?.let { uri -> decodeUri(context, uri) }
                ?: earphone?.imageUri(customFallbackResource)?.let { uri -> decodeUri(context, uri) }
        }.getOrNull()
        if (custom != null) {
            //android.util.Log.d("OppoPods-PodImage", "loaded custom $resource for ${earphone?.address}")
            return custom
        }

        val moduleContext = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: return null
        return BitmapFactory.decodeResource(
            moduleContext.resources,
            resolveFallbackId(moduleContext, resource, fallbackResId),
        )
    }

    private fun fallbackResName(resource: PodImageResource): String = when (resource) {
        PodImageResource.BOX -> "img_box"
        PodImageResource.LEFT -> "img_left"
        PodImageResource.RIGHT -> "img_right"
    }

    /**
     * Resolve the fallback drawable by name: the Bluetooth process may still run hook code from a
     * previous APK build while the installed resource ids were renumbered, so a compiled id can
     * point at an unrelated drawable.
     */
    private fun resolveFallbackId(moduleContext: Context, resource: PodImageResource, fallbackResId: Int): Int {
        val id = runCatching {
            moduleContext.resources.getIdentifier(fallbackResName(resource), "drawable", MODULE_PACKAGE)
        }.getOrDefault(0)
        return if (id != 0) id else fallbackResId
    }

    fun loadBoxBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmap(context, prefs, address, PodImageResource.BOX, R.drawable.img_box)
    }


    fun loadIslandLeftBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.LEFT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_left,
        )
    }

    fun loadIslandRightBitmap(context: Context, prefs: SharedPreferences, address: String): Bitmap? {
        return loadBitmapWithCustomFallback(
            context = context,
            prefs = prefs,
            address = address,
            resource = PodImageResource.RIGHT,
            customFallbackResource = PodImageResource.BOX,
            fallbackResId = R.drawable.img_right,
        )
    }

    private fun decodeUri(context: Context, uri: android.net.Uri): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                input?.let { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
}
