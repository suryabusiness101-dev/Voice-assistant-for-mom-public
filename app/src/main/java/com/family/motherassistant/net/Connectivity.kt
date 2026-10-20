package com.family.motherassistant.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * The single place that answers "can we actually reach the internet right now?".
 *
 * Every code path about to make a Gemini API call MUST gate on [hasInternet] first — a
 * hung or generically-failing HTTP call is a worse experience for her than an immediate
 * "internet नहीं है" spoken aloud and shown on screen.
 *
 * This trusts Android's own verdict: `NET_CAPABILITY_VALIDATED` is set only after the
 * platform's connectivity probe has actually reached the public internet, so the common
 * "Wi-Fi connected, no internet" / captive-portal case correctly reads as offline —
 * without us running a socket probe on the main thread.
 */
object Connectivity {

    fun hasInternet(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: run {
            Log.w(TAG, "offline: no active network")
            return false
        }
        val caps = cm.getNetworkCapabilities(network) ?: run {
            Log.w(TAG, "offline: no capabilities for active network")
            return false
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) Log.w(TAG, "offline: active network has no validated internet")
        return online
    }

    private const val TAG = "Connectivity"
}
