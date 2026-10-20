package com.family.motherassistant.actions

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resolve an app she *named* to an installed package by its launcher label, instead of a
 * hardcoded package id — generalized from the original Hotstar-only resolver (Hotstar rebranded
 * to JioHotstar in 2025; matching the visible label self-corrects across renames). Needs the
 * LAUNCHER-category `<queries>` entry in the manifest, which grants visibility into every
 * launchable app on Android 11+. Every candidate is logged so the real resolved package can be
 * confirmed from logcat.
 *
 * Matching is on a normalized form (lowercase, letters/digits only, a trailing word "app" dropped):
 * an exact label match wins; otherwise the shortest label that contains what she said
 * ("hotstar" → "JioHotstar", "youtube" → "YouTube" over "YouTube Music").
 */
object AppResolver {

    data class App(val packageName: String, val label: String)

    fun resolve(context: Context, spokenName: String): App? {
        val query = normalize(spokenName)
        if (query.length < 2) return null
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            Log.e(TAG, "resolve: queryIntentActivities failed", e)
            emptyList()
        }
        val matches = activities.mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    null
                }
                label?.takeIf { normalize(it).contains(query) }?.let { App(pkg, it) }
            }
        Log.i(TAG, "resolve(\"$spokenName\") -> $matches")
        return matches.firstOrNull { normalize(it.label) == query }
            ?: matches.minByOrNull { it.label.length }
    }

    // Only a separate trailing word "app" is dropped ("Hotstar app"), never a suffix inside a
    // name — "WhatsApp" must stay "whatsapp", not become "whats".
    private fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+app$"), "").filter { it.isLetterOrDigit() }

    private const val TAG = "MotherAssistant"
}
