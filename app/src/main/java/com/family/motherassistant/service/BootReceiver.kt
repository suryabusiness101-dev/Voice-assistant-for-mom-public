package com.family.motherassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-raises the floating bubble after a device reboot, without her ever having to open Maa
 * Assistant once. Before this, [AssistantService]'s persisted `bubble_enabled` pref only got
 * read again from [android.app.Activity.onResume] (`MainActivity.refreshBubbleState`) or a
 * bare service restart (`AssistantService.onStartCommand`'s `else` branch) — neither of which
 * happens on a cold boot until she opens the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AssistantService.isBubbleEnabled(context)) {
            Log.i(TAG, "boot: bubble_enabled=false, not raising")
            return
        }
        Log.i(TAG, "boot: re-raising bubble")
        try {
            AssistantService.showBubble(context)
        } catch (e: Exception) {
            // Some OEMs restrict background-started foreground services right after boot;
            // log rather than crash the receiver — she can still open the app to raise it.
            Log.e(TAG, "boot: could not re-raise bubble", e)
        }
    }

    companion object {
        private const val TAG = "MotherAssistant"
    }
}
