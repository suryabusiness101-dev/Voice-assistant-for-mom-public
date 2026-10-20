package com.family.motherassistant.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.motherassistant.R
import com.family.motherassistant.service.AssistantAccessibilityService

/**
 * Tier 2 actions — driven through the [AssistantAccessibilityService] node tree, not a
 * direct Android API. Phase 3 feature 1: share the current screen into WhatsApp. Features A/B:
 * search for and open content, and switch away from the Kids profile, inside Hotstar (only —
 * for now) — both are main-app-trigger only, enforced upstream in
 * [com.family.motherassistant.core.AssistantController], not here.
 *
 * Same contract as [SystemActions]: returns a deterministic [SystemActions.Result] whose
 * spoken line reflects what actually happened, and a [SystemActions.FollowUp] /
 * [SystemActions.Pending] when the Activity must do something first.
 */
class AccessibilityActions(private val context: Context) {

    /**
     * @param targetApp    the app to share into — currently always treated as WhatsApp.
     * @param targetContact the WhatsApp contact / group name she said, or null if she didn't.
     * @param sourceOverride the source app package locked in when the share was first recognized
     *        (before any clarify round-trip). Preferred over the service's live `lastForegroundApp`
     *        so a multi-turn clarify — or a transient foreground surface during the app switch —
     *        can't drift the source out from under us. Null → fall back to the live value.
     */
    fun shareOnScreen(
        targetApp: String,
        targetContact: String?,
        sourceOverride: String? = null
    ): SystemActions.Result {
        val svc = AssistantAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "share: accessibility service not enabled — sending her to Settings")
            return SystemActions.Result(
                str(R.string.act_share_needs_a11y),
                followUp = SystemActions.FollowUp.OpenAccessibilitySettings
            )
        }

        // Gemini sometimes fills `target` with the app name, "unknown", or leaves it empty when
        // she didn't actually name a recipient — never guess who a share goes to, ask instead.
        // Checked with the same matchCore the WA_PICK_ROW scan itself uses, so a target that
        // reduces to nothing but a generic word ("whatsapp group" with no group named) is caught
        // here too — before the scan loop ever starts, not after it times out.
        val contact = targetContact?.trim().orEmpty()
        if (contact.isBlank() || BOGUS_TARGETS.any { contact.equals(it, true) } ||
            contact.equals(targetApp.trim(), true) ||
            AssistantAccessibilityService.isUnusableShareTarget(contact)
        ) {
            Log.i(TAG, "share: no usable recipient (\"$contact\") — asking")
            return SystemActions.Result(
                str(R.string.act_share_who),
                pending = SystemActions.Pending(
                    "share_onscreen",
                    "she wants to share what is currently on her screen to WhatsApp but has not " +
                        "said which group or person; her next words name the recipient"
                )
            )
        }

        val source = sourceOverride?.takeIf { it.isNotBlank() } ?: svc.lastForegroundApp
        Log.i(
            TAG,
            "share: source app = $source (override=$sourceOverride live=${svc.lastForegroundApp})"
        )
        if (source == null) {
            Log.w(TAG, "share: no foreground app seen yet — cannot tell what to share")
            return SystemActions.Result(str(R.string.act_share_no_source))
        }
        if (WHATSAPP_PKGS.any { source == it }) {
            // The locked source is WhatsApp itself (e.g. she retried without going back to the
            // Reel) — there is nothing on WhatsApp's own screen to "share on screen".
            Log.w(TAG, "share: source app is WhatsApp itself — nothing on-screen to share")
            return SystemActions.Result(str(R.string.act_share_no_source))
        }

        // Bring the source app forward. When this runs from a foreground Activity that's
        // unrestricted; when it runs from the bubble / a11y-button (a background context) it
        // relies on the background-activity-launch exemption that SYSTEM_ALERT_WINDOW grants.
        // If the launch is blocked anyway, the source app is often already in front (she never
        // left it) — so log and press on; AWAIT_SRC in the flow will fail cleanly if not.
        val launch = context.packageManager.getLaunchIntentForPackage(source)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            Log.e(TAG, "share: no launch intent for source app $source (package visibility?)")
            return SystemActions.Result(str(R.string.act_share_source_not_open))
        }

        val app = targetApp.ifBlank { "WhatsApp" }
        Log.i(TAG, "share: launching $source, handing off to a11y service (app=$app target=\"$contact\")")
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "share: could not launch $source (background-activity-launch blocked?) — " +
                "continuing in case it is already foreground", e)
        }
        svc.beginShareFlow(app, contact, source)
        return SystemActions.Result(str(R.string.act_share_starting, contact), asyncShareStarted = true)
    }

    // ---- feature A: search_and_open (Hotstar only for now, main-app-trigger only) --------

    /**
     * @param appName the app she named — checked against [isHotstar]; only Hotstar is wired up.
     * @param searchQuery what to search for, as she said it.
     */
    fun searchAndOpen(appName: String, searchQuery: String): SystemActions.Result {
        val svc = AssistantAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "search: accessibility service not enabled — sending her to Settings")
            return SystemActions.Result(
                str(R.string.act_a11y_needs_permission),
                followUp = SystemActions.FollowUp.OpenAccessibilitySettings
            )
        }
        if (!isHotstar(appName)) {
            Log.i(TAG, "search: app \"$appName\" not supported yet (Hotstar-only)")
            return SystemActions.Result(str(R.string.act_hotstar_only))
        }
        val query = searchQuery.trim()
        if (query.isBlank()) {
            Log.i(TAG, "search: no search query given — asking")
            return SystemActions.Result(
                str(R.string.act_search_what),
                pending = SystemActions.Pending(
                    "search_and_open",
                    "she wants to search for something on Hotstar but has not said what; her next " +
                        "words name the show or movie to search for"
                )
            )
        }
        val pkg = resolveHotstarPackage()
        if (pkg == null) {
            Log.w(TAG, "search: Hotstar not found among installed apps")
            return SystemActions.Result(str(R.string.act_hotstar_not_installed))
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            Log.e(TAG, "search: no launch intent for $pkg")
            return SystemActions.Result(str(R.string.act_hotstar_not_open))
        }
        Log.i(TAG, "search: launching $pkg, handing off to a11y service (query=\"$query\")")
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "search: could not launch $pkg — continuing in case it is already foreground", e)
        }
        svc.beginSearchAndOpenFlow(pkg, query)
        return SystemActions.Result(str(R.string.act_search_starting, query))
    }

    // ---- feature B: switch_profile (Hotstar only for now, main-app-trigger only) ---------

    /** @param appName the app she named — checked against [isHotstar]; only Hotstar is wired up. */
    fun switchProfile(appName: String): SystemActions.Result {
        val svc = AssistantAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "profile: accessibility service not enabled — sending her to Settings")
            return SystemActions.Result(
                str(R.string.act_a11y_needs_permission),
                followUp = SystemActions.FollowUp.OpenAccessibilitySettings
            )
        }
        if (!isHotstar(appName)) {
            Log.i(TAG, "profile: app \"$appName\" not supported yet (Hotstar-only)")
            return SystemActions.Result(str(R.string.act_hotstar_only))
        }
        val pkg = resolveHotstarPackage()
        if (pkg == null) {
            Log.w(TAG, "profile: Hotstar not found among installed apps")
            return SystemActions.Result(str(R.string.act_hotstar_not_installed))
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            Log.e(TAG, "profile: no launch intent for $pkg")
            return SystemActions.Result(str(R.string.act_hotstar_not_open))
        }
        Log.i(TAG, "profile: launching $pkg, handing off to a11y service")
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w(TAG, "profile: could not launch $pkg — continuing in case it is already foreground", e)
        }
        svc.beginSwitchProfileFlow(pkg)
        return SystemActions.Result(str(R.string.act_profile_starting))
    }

    // ---- Hotstar app resolution (shared by features A and B) ------------------------------

    private fun isHotstar(appName: String): Boolean = appName.contains("hotstar", ignoreCase = true)

    /** Label-based, not a hardcoded package id — see [AppResolver] (shared with troubleshoot_app). */
    private fun resolveHotstarPackage(): String? = AppResolver.resolve(context, "hotstar")?.packageName

    private fun str(resId: Int): String = context.getString(resId)
    private fun str(resId: Int, arg: String): String = context.getString(resId, arg)

    companion object {
        private const val TAG = "MotherAssistant"
        private val BOGUS_TARGETS = listOf("unknown", "whatsapp", "whatsapp status", "none", "null")
        private val WHATSAPP_PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
