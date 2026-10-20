package com.family.motherassistant.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.family.motherassistant.R
import com.family.motherassistant.core.AssistantController

/**
 * Phase 3 accessibility-driven features, one per named flow:
 *  - **feature 1** ([ShareFlow]) — share whatever is on screen (validated sources: an Instagram
 *    Reel, a YouTube Short) into a WhatsApp contact or group she names.
 *  - **feature A** ([SearchAndOpenFlow]) — search for and open content inside a named app
 *    (Hotstar only for now). Main-app-trigger only (see [AssistantController]).
 *  - **feature B** ([SwitchProfileFlow]) — navigate to a named app's profile switcher and pick
 *    whichever profile is not "Kids" (Hotstar only for now). Also main-app-trigger only.
 *
 * This is the least generalizable code in the app: every app's UI differs and shifts with
 * updates. Each feature is deliberately its own flat, heavily logged state machine (via the
 * shared [Flow] base) — a miss at any step ends that flow with a specific spoken line, never
 * blind-tapping past a step it can't verify — so the matchers can be widened later from real
 * logcat evidence rather than guesswork. Every line is logged under tag [TAG], prefixed by the
 * flow's own name (`share:`, `search:`, `profile:`) — separate code paths, not entangled; only
 * one flow runs at a time.
 */
class AssistantAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** Last real foreground app seen (not us, not systemui/launcher/IME), confirmed stable. */
    @Volatile
    private var lastForegroundPackage: String? = null

    /**
     * A candidate app seen on the most recent real-app window-state-changed event that has not
     * yet repeated on a second, consecutive one — see [onAccessibilityEvent].
     */
    @Volatile
    private var pendingForegroundPackage: String? = null

    val lastForegroundApp: String? get() = lastForegroundPackage

    /**
     * At most one accessibility-driven flow runs at a time — [ShareFlow] (feature: share on
     * screen), [SearchAndOpenFlow] and [SwitchProfileFlow] (Hotstar features A/B) all extend this
     * so the watchdog, timeout handling, and finish/fail plumbing is shared, while each flow's own
     * states and matchers stay in its own class — separate code paths, not entangled.
     */
    private abstract inner class Flow(val logTag: String) {
        var stepStartedAt: Long = SystemClock.uptimeMillis()
        var currentStateLabel: String = "start"
        open fun budgetMs(): Long = STEP_TIMEOUT_MS
        abstract fun pump()
        abstract fun timeoutMessage(): String
        abstract val interruptedMessage: String
    }

    private var flow: Flow? = null

    private enum class S { AWAIT_SRC, AWAIT_SHEET, AWAIT_WA, WA_PICK_ROW, WA_SEND }

    private inner class ShareFlow(
        val targetApp: String,
        val targetContact: String,
        val sourcePackage: String
    ) : Flow("share") {
        var state: S = S.AWAIT_SRC
        var triedChooser = false
        var triedExpandMiniPlayer = false
        var rowClicked = false
        var sendClicks = 0
        var lastSendClickAt = 0L

        // WA_PICK_ROW search-retry bookkeeping
        var pickScans = 0
        var searchOpened = false   // tapped WhatsApp's Search affordance
        var searchTyped = false    // ACTION_SET_TEXT'd the name into the search box

        override fun budgetMs() = if (state == S.WA_PICK_ROW) WA_PICK_TIMEOUT_MS else STEP_TIMEOUT_MS
        override fun timeoutMessage(): String = timeoutMessageFor(this)
        override val interruptedMessage: String get() = getString(R.string.act_share_timeout)

        override fun pump() {
            when (state) {
                S.AWAIT_SRC -> if (currentPackage() == sourcePackage) findAndClickShare(this)
                S.AWAIT_SHEET -> findWhatsAppInSheet(this)
                S.AWAIT_WA -> if (currentPackage() == WHATSAPP_PKG) enterWhatsAppQuery(this)
                // WA_PICK_ROW / WA_SEND used to scan+click unconditionally, with no check that
                // WhatsApp was still the foreground app. If WhatsApp fails to actually come up
                // (e.g. blocked by its own "Update WhatsApp" wall) or focus otherwise drifts away
                // mid-flow, this blindly kept tapping whatever was on screen instead — seen on
                // 2026-09-10 clicking through YouTube's own search bar and the home launcher for
                // 13+ seconds before the timeout. Same don't-act-on-what-you-can't-verify
                // principle as every other state already follows.
                S.WA_PICK_ROW -> if (currentPackage() == WHATSAPP_PKG) pickWhatsAppRow(this)
                    else Log.v(TAG, "share: WA_PICK_ROW waiting — foreground is ${currentPackage()}, not WhatsApp")
                S.WA_SEND -> if (currentPackage() == WHATSAPP_PKG) tapSend(this)
                    else Log.v(TAG, "share: WA_SEND waiting — foreground is ${currentPackage()}, not WhatsApp")
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (flow == null) return
            pumpFlow()
            if (flow != null) handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ---- lifecycle -----------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AssistantAccessibilityService connected")
        // Re-run a share that was parked waiting for this service to be enabled.
        AssistantController.get(this).onAccessibilityConnected()
        registerAccessibilityButton()
    }

    /**
     * Wire the system Accessibility Button as a second, no-permission way to start a voice turn
     * (free once this service exists). Availability is OEM/nav-mode dependent — on gesture-nav
     * devices it lives in the accessibility shortcut menu rather than an on-screen button — so
     * this is best-effort and just logged if unavailable.
     */
    private fun registerAccessibilityButton() {
        try {
            val c = accessibilityButtonController
            c.registerAccessibilityButtonCallback(object :
                AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.i(TAG, "accessibility button pressed — starting a voice turn")
                    AssistantService.triggerFromButton(this@AssistantAccessibilityService)
                }

                override fun onAvailabilityChanged(
                    controller: AccessibilityButtonController,
                    available: Boolean
                ) {
                    Log.i(TAG, "accessibility button available=$available")
                }
            })
            Log.i(TAG, "accessibility button registered (available=${c.isAccessibilityButtonAvailable})")
        } catch (e: Exception) {
            Log.w(TAG, "could not register the accessibility button", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        endFlow()
        Log.i(TAG, "AssistantAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        endFlow()
        super.onDestroy()
    }

    override fun onInterrupt() {
        val f = flow ?: return
        Log.w(TAG, "${f.logTag}: onInterrupt while a flow was active")
        failFlow(f.interruptedMessage)
    }

    /**
     * A single window-state-changed event is not trusted on its own — a transient
     * launcher/gesture-nav/system-overlay flash mid-switch can briefly report as the "foreground
     * app" (see the `googlequicksearchbox` case documented on [NON_APP_HINTS], and another,
     * unblocklisted transient surface that has been seen latching as the source and blocking a
     * share that should have worked). Instead, a candidate package must
     * repeat on two *consecutive* real-app events before it's locked in as [lastForegroundPackage]
     * — a one-off flash becomes a [pendingForegroundPackage] that never gets confirmed and is
     * simply superseded by whatever comes next, leaving the previously-locked source untouched.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (isForegroundApp(pkg)) {
                when {
                    pkg == lastForegroundPackage -> pendingForegroundPackage = null
                    pkg == pendingForegroundPackage -> {
                        Log.v(TAG, "foreground app -> $pkg (confirmed across 2 consecutive events)")
                        lastForegroundPackage = pkg
                        pendingForegroundPackage = null
                    }
                    else -> {
                        Log.v(TAG, "foreground app candidate -> $pkg (awaiting confirmation)")
                        pendingForegroundPackage = pkg
                    }
                }
            }
        }
        if (flow != null) pumpFlow()
    }

    // ---- public entry point (called by AccessibilityActions) ----------------

    /**
     * Begin driving the share. The caller has already brought [sourcePackage] to the
     * foreground (from an Activity context, to dodge background-activity-launch limits);
     * this only watches and acts on the node tree.
     */
    fun beginShareFlow(targetApp: String, targetContact: String, sourcePackage: String) {
        if (flow != null) {
            Log.w(TAG, "share: a flow is already running — ignoring new request")
            return
        }
        Log.i(
            TAG,
            "share: begin — app=$targetApp contact=\"$targetContact\" source=$sourcePackage"
        )
        if (sourcePackage !in VALIDATED_SOURCE_PKGS) {
            Log.w(
                TAG,
                "share: source $sourcePackage is not a validated path (validated: " +
                    "$VALIDATED_SOURCE_PKGS) — attempting the generic share-affordance search"
            )
        }
        val f = ShareFlow(targetApp, targetContact, sourcePackage)
        flow = f
        transition(f, S.AWAIT_SRC)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /**
     * Begin feature A: search [query] inside [targetPackage] (Hotstar only) and open the top
     * result. The caller has already launched [targetPackage]; this only watches and acts on
     * the node tree. Main-app-trigger only — enforced by [AssistantController], not here.
     */
    fun beginSearchAndOpenFlow(targetPackage: String, query: String) {
        if (flow != null) {
            Log.w(TAG, "search: a flow is already running — ignoring new request")
            return
        }
        Log.i(TAG, "search: begin — package=$targetPackage query=\"$query\"")
        val f = SearchAndOpenFlow(targetPackage, query)
        flow = f
        transitionSO(f, SO.AWAIT_APP)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /**
     * Begin feature B: navigate to [targetPackage]'s profile switcher (Hotstar only) and pick
     * whichever tile is not labelled "Kids" — structural, not name-based. The
     * caller has already launched [targetPackage]. Main-app-trigger only, enforced by
     * [AssistantController].
     */
    fun beginSwitchProfileFlow(targetPackage: String) {
        if (flow != null) {
            Log.w(TAG, "profile: a flow is already running — ignoring new request")
            return
        }
        Log.i(TAG, "profile: begin — package=$targetPackage")
        val f = SwitchProfileFlow(targetPackage)
        flow = f
        transitionSP(f, SP.AWAIT_APP)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    // ---- feature A: search_and_open (Hotstar only, generic node matchers) ------------------

    private enum class SO { AWAIT_APP, AWAIT_SEARCH, AWAIT_RESULTS }

    private inner class SearchAndOpenFlow(val targetPackage: String, val query: String) : Flow("search") {
        var state: SO = SO.AWAIT_APP
        var searchFieldBottom: Int = 0
        var lastEntryClickAt = 0L
        var scans = 0
        /** Poster-tagged labels visible the instant the query was typed — see [pickFirstResult]. */
        var staleResultLabels: Set<String> = emptySet()
        var resultsStartedAt = 0L
        /** True once the search field has been tapped to give it real input focus — see [findOrOpenSearch]. */
        var fieldFocused = false
        var fieldFocusedAt = 0L

        override fun budgetMs() = if (state == SO.AWAIT_RESULTS) SEARCH_RESULTS_TIMEOUT_MS else STEP_TIMEOUT_MS
        override fun timeoutMessage(): String = when (state) {
            SO.AWAIT_APP -> getString(R.string.act_hotstar_not_open)
            SO.AWAIT_SEARCH -> getString(R.string.act_search_no_search)
            SO.AWAIT_RESULTS -> getString(R.string.act_search_no_results, query)
        }
        override val interruptedMessage: String get() = getString(R.string.act_search_timeout)

        override fun pump() {
            when (state) {
                SO.AWAIT_APP -> if (currentPackage() == targetPackage) {
                    transitionSO(this, SO.AWAIT_SEARCH)
                    findOrOpenSearch(this)
                }
                SO.AWAIT_SEARCH -> if (currentPackage() == targetPackage) findOrOpenSearch(this)
                SO.AWAIT_RESULTS -> if (currentPackage() == targetPackage) pickFirstResult(this)
            }
        }
    }

    private fun transitionSO(f: SearchAndOpenFlow, s: SO) {
        f.state = s
        f.currentStateLabel = s.name
        f.stepStartedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "search: state -> $s")
    }

    /**
     * Fast path: if a search field is already visible, type the query into it and move on.
     * Otherwise find a "Search" entry point (a bottom-nav tab, a toolbar icon) and click it,
     * re-checking every tick (debounced) until the field appears.
     */
    private fun findOrOpenSearch(f: SearchAndOpenFlow) {
        val root = rootInActiveWindow ?: return
        val field = findNode(root) { it.isVisibleToUser && it.className?.contains("EditText") == true }
        if (field != null) {
            val bounds = Rect().also { field.getBoundsInScreen(it) }
            f.searchFieldBottom = bounds.bottom
            // Real on-device evidence: setting text directly via
            // ACTION_SET_TEXT — without ever actually tapping the field — visibly puts the query
            // into the field (confirmed via a node dump) but Hotstar's search never runs: no real
            // input focus/IME session ever gets established, so ACTION_IME_ENTER submits into a
            // void and returns true without doing anything. A real user always taps the field
            // first. Do that for real (clickSmart, not just ACTION_FOCUS, since the earlier
            // "Search" tab click needed the gesture-tap fallback too) and give it a tick to land
            // before typing — typing in the same call as the tap risks racing the tap's own
            // touch-down/up.
            if (!f.fieldFocused) {
                val tapped = clickSmart(field)
                Log.i(TAG, "search: tapping search field to focus it -> $tapped")
                f.fieldFocused = true
                f.fieldFocusedAt = SystemClock.uptimeMillis()
                handler.postDelayed({ pumpFlow() }, 400L)
                return
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f.query)
            }
            // Snapshot whatever poster-tagged tiles are already on screen *before* the query
            // takes effect (a leftover "Recent Searches"/trending carousel, or a previous
            // query's results) — pickFirstResult() diffs against this so it can't mistake that
            // stale content for a real match to the new query while results are still loading.
            f.staleResultLabels = findPosterTaggedNodes(root, f.searchFieldBottom).map { nodeLabel(it) }.toSet()
            f.resultsStartedAt = SystemClock.uptimeMillis()
            val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            // Hotstar's search only runs once the query is *submitted*, same as pressing the
            // keyboard's search/enter key — ACTION_IME_ENTER triggers that submit without needing
            // the soft keyboard actually on screen, now that the field has real input focus.
            val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
            Log.i(
                TAG,
                "search: found search field, set text \"${f.query}\" ok=$ok submitted=$submitted " +
                    "isFocused=${field.isFocused} (stale labels snapshot: ${f.staleResultLabels})"
            )
            transitionSO(f, SO.AWAIT_RESULTS)
            handler.postDelayed({ pumpFlow() }, 1200L)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - f.lastEntryClickAt < 1500L) return
        // Don't require isClickableChain here — real evidence from Hotstar
        // shows its bottom-nav "Search" tab, and every ancestor up to the root, all
        // report clickable=false despite being a real custom-rendered tappable tab. Requiring a
        // clickable ancestor meant this never even became a candidate, so clickSmart()'s
        // gesture-tap fallback (already proven against WhatsApp's identically non-clickable rows)
        // never got a chance to run. Same fallback pattern as findAndClickShare: prefer a
        // clickable-chain match, but fall back to any labelled match.
        val candidates = findNodes(root) { n ->
            n.isVisibleToUser &&
                (SEARCH_ENTRY_LABELS.any { nodeLabel(n).equals(it, true) } ||
                    n.viewIdResourceName?.substringAfterLast('/')?.contains("search", true) == true)
        }
        val entry = candidates.firstOrNull { isClickableChain(it) } ?: candidates.firstOrNull()
        if (entry != null) {
            Log.i(TAG, "search: clicking search entry \"${nodeLabel(entry)}\" (id=${entry.viewIdResourceName})")
            clickSmart(entry)
            f.lastEntryClickAt = now
        } else {
            Log.v(TAG, "search: no search entry point visible yet")
        }
    }

    /**
     * Not gated on isClickableChain — see the note in findOrOpenSearch. A content tile with
     * custom touch handling and no clickable ancestor still needs to reach clickSmart()'s
     * gesture-tap fallback to be openable at all.
     */
    private fun findPosterTaggedNodes(root: AccessibilityNodeInfo, fieldBottom: Int): List<AccessibilityNodeInfo> {
        val all = findNodes(root) { n ->
            n.isVisibleToUser && n.className?.contains("EditText") != true &&
                nodeLabel(n).isNotBlank() &&
                RESULT_BLACKLIST.none { nodeLabel(n).equals(it, true) } &&
                RESULT_TILE_ID_EXCLUDE.none { n.viewIdResourceName?.contains(it, true) == true } &&
                run {
                    val b = Rect().also { n.getBoundsInScreen(it) }
                    fieldBottom == 0 || b.top >= fieldBottom - 20
                }
        }
        return all.filter { n -> RESULT_TILE_ID_HINTS.any { n.viewIdResourceName?.contains(it, true) == true } }
    }

    /**
     * Pick the top matching result. A live on-device dump proved a
     * plain "first labelled node below the search field" is not safe on Hotstar: before she
     * finishes typing, that zone can show a leftover "Recent Searches" chip row from an earlier
     * query, and right after typing (before real results load) an "auto suggestion" chip row
     * (`tag_search_auto_suggestion_item`) renders first too — both real, populated UI, not a
     * loading state, so a position-only filter can't tell them apart from a genuine result. One
     * reliable signal from that dump: a real "Top Results" tile carries a resource-id containing
     * `content_poster`; suggestion/recent-search chips never do — so this only looks at
     * poster-tagged nodes.
     *
     * That alone wasn't enough (2026-09-11/13 on-device: asked for "Daredevil", got "Bigg Boss"
     * instead) — a *stale* poster-tagged tile (a trending carousel row, or a previous query's
     * results) can still be on screen when the results zone first re-renders, before Hotstar
     * swaps in the real results for the new query. [SearchAndOpenFlow.staleResultLabels] is a
     * snapshot of exactly those labels taken the instant the query was typed, so a poster-tagged
     * node is only accepted here once its label is *not* in that stale set — i.e. it's
     * demonstrably new content, not a leftover. After [STALE_RESULT_GRACE_MS] with nothing but
     * stale-looking candidates, that filter is dropped (in case she searched for a title that
     * genuinely was already on screen) rather than waiting out the full flow timeout.
     */
    private fun pickFirstResult(f: SearchAndOpenFlow) {
        val root = rootInActiveWindow ?: return
        f.scans++
        val posterTagged = findPosterTaggedNodes(root, f.searchFieldBottom)
        val fresh = posterTagged.filterNot { nodeLabel(it) in f.staleResultLabels }
        val graceElapsed = SystemClock.uptimeMillis() - f.resultsStartedAt >= STALE_RESULT_GRACE_MS
        val pool = fresh.ifEmpty { if (graceElapsed) posterTagged else emptyList() }
        Log.i(
            TAG,
            "search: result scan #${f.scans} -> ${posterTagged.size} poster-tagged, " +
                "${fresh.size} fresh (grace elapsed=$graceElapsed): " +
                posterTagged.take(8).joinToString { "\"${nodeLabel(it)}\"[id=${it.viewIdResourceName}]" }
        )
        val candidates = pool.filter { isClickableChain(it) }.ifEmpty { pool }
        val top = candidates.firstOrNull()
        if (top == null) {
            if (f.scans >= 3) dumpClickables(root, "no fresh poster-tagged search result yet")
            return
        }
        val label = nodeLabel(top)
        Log.i(TAG, "search: opening top result \"$label\"")
        if (clickSmart(top)) {
            finishFlow(getString(R.string.act_search_opened, label))
        } else {
            Log.w(TAG, "search: result tap did not register — will retry")
        }
    }

    // ---- feature B: switch_profile (Hotstar only, generic node matchers) -------------------

    private enum class SP { AWAIT_APP, AWAIT_ENTRY, AWAIT_MENU, AWAIT_TILES }

    private inner class SwitchProfileFlow(val targetPackage: String) : Flow("profile") {
        var state: SP = SP.AWAIT_APP
        var lastClickAt = 0L
        var scans = 0

        override fun budgetMs() = if (state == SP.AWAIT_TILES) PROFILE_TILES_TIMEOUT_MS else STEP_TIMEOUT_MS
        override fun timeoutMessage(): String = when (state) {
            SP.AWAIT_APP -> getString(R.string.act_hotstar_not_open)
            SP.AWAIT_ENTRY -> getString(R.string.act_profile_no_entry)
            SP.AWAIT_MENU -> getString(R.string.act_profile_no_switch_option)
            SP.AWAIT_TILES -> getString(R.string.act_profile_no_tiles)
        }
        override val interruptedMessage: String get() = getString(R.string.act_profile_timeout)

        override fun pump() {
            when (state) {
                SP.AWAIT_APP -> if (currentPackage() == targetPackage) {
                    transitionSP(this, SP.AWAIT_ENTRY)
                    findProfileEntry(this)
                }
                SP.AWAIT_ENTRY -> if (currentPackage() == targetPackage) findProfileEntry(this)
                SP.AWAIT_MENU -> if (currentPackage() == targetPackage) findSwitchOption(this)
                SP.AWAIT_TILES -> if (currentPackage() == targetPackage) pickNonKidsTile(this)
            }
        }
    }

    private fun transitionSP(f: SwitchProfileFlow, s: SP) {
        f.state = s
        f.currentStateLabel = s.name
        f.stepStartedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "profile: state -> $s")
    }

    /**
     * Look for a way to reach the profile switcher. A node that already reads like "Switch
     * Profile" / "Who's Watching" jumps straight to the tile screen; otherwise a generic
     * "Profile" / "Account" entry is clicked first, landing on an account menu that should then
     * contain the switch option (see [findSwitchOption]).
     */
    private fun findProfileEntry(f: SwitchProfileFlow) {
        val root = rootInActiveWindow ?: return
        val now = SystemClock.uptimeMillis()
        if (now - f.lastClickAt < 1500L) return
        // Not gated on isClickableChain — see the note in findOrOpenSearch: real evidence from
        // Hotstar shows even its bottom-nav tabs report clickable=false all the way up, so a
        // clickable-ancestor requirement would silently exclude a real, tappable entry point.
        val direct = pickBest(root) { n -> DIRECT_PROFILE_LABELS.any { nodeLabel(n).contains(it, true) } }
        if (direct != null) {
            Log.i(TAG, "profile: found direct entry \"${nodeLabel(direct)}\" — clicking")
            clickSmart(direct)
            f.lastClickAt = now
            transitionSP(f, SP.AWAIT_TILES)
            return
        }
        val entry = pickBest(root) { n -> PROFILE_ENTRY_LABELS.any { nodeLabel(n).contains(it, true) } }
        if (entry != null) {
            Log.i(TAG, "profile: found account entry \"${nodeLabel(entry)}\" — clicking")
            clickSmart(entry)
            f.lastClickAt = now
            transitionSP(f, SP.AWAIT_MENU)
        } else {
            Log.v(TAG, "profile: no profile/account entry visible yet")
        }
    }

    private fun findSwitchOption(f: SwitchProfileFlow) {
        val root = rootInActiveWindow ?: return
        val now = SystemClock.uptimeMillis()
        if (now - f.lastClickAt < 1500L) return
        val opt = pickBest(root) { n -> DIRECT_PROFILE_LABELS.any { nodeLabel(n).contains(it, true) } }
        if (opt != null) {
            Log.i(TAG, "profile: found switch option \"${nodeLabel(opt)}\" — clicking")
            clickSmart(opt)
            f.lastClickAt = now
            transitionSP(f, SP.AWAIT_TILES)
        } else {
            f.scans++
            Log.v(TAG, "profile: no switch-profile option visible yet in this menu")
            if (f.scans >= 6) dumpClickables(root, "no switch-profile option found in account menu")
        }
    }

    /**
     * Find the best visible node matching [match]: prefer one with a clickable ancestor, but fall
     * back to any visible match — needed because Hotstar's custom-rendered UI reports
     * clickable=false on real tappable elements; clickSmart()'s
     * gesture-tap fallback can still activate such a node once it becomes a candidate at all.
     */
    private fun pickBest(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val candidates = findNodes(root) { n -> n.isVisibleToUser && match(n) }
        return candidates.firstOrNull { isClickableChain(it) } ?: candidates.firstOrNull()
    }

    /**
     * Select whichever profile tile is not labelled "Kids" — structural, not name-based, by
     * design: never try to detect which profile is currently active, just always
     * land here and pick the non-Kids tile. Never blind-taps — if no non-Kids tile is found, it
     * keeps scanning until the timeout rather than guessing.
     */
    private fun pickNonKidsTile(f: SwitchProfileFlow) {
        val root = rootInActiveWindow ?: return
        f.scans++
        // Not gated on isClickableChain — see the note in findOrOpenSearch / pickBest.
        val allTiles = findNodes(root) { n ->
            n.isVisibleToUser && nodeLabel(n).isNotBlank() &&
                PROFILE_TILE_BLACKLIST.none { nodeLabel(n).equals(it, true) }
        }
        val tiles = allTiles.filter { isClickableChain(it) }.ifEmpty { allTiles }
        val nonKids = tiles.filter { !nodeLabel(it).contains("kids", true) }
        Log.i(
            TAG,
            "profile: tile scan #${f.scans} -> ${tiles.size} tile(s), ${nonKids.size} non-Kids: " +
                tiles.joinToString { "\"${nodeLabel(it)}\"" }
        )
        val pick = nonKids.firstOrNull()
        if (pick == null) {
            if (f.scans >= 3) dumpClickables(root, "no non-Kids profile tile found")
            return
        }
        Log.i(TAG, "profile: selecting profile \"${nodeLabel(pick)}\"")
        if (clickSmart(pick)) {
            finishFlow(getString(R.string.act_profile_switched))
        } else {
            Log.w(TAG, "profile: tile tap did not register — will retry")
        }
    }

    // ---- state machine -----------------------------------------------------

    private fun pumpFlow() {
        val f = flow ?: return
        if (SystemClock.uptimeMillis() - f.stepStartedAt > f.budgetMs()) {
            failFlow(f.timeoutMessage())
            return
        }
        f.pump()
    }

    private fun findAndClickShare(f: ShareFlow) {
        val root = rootInActiveWindow ?: return
        Log.i(TAG, "share: source app foreground pkg=${root.packageName} — looking for a Share affordance")
        val candidates = findNodes(root) { n ->
            n.isVisibleToUser && SHARE_LABELS.any { nodeLabel(n).contains(it, true) }
        }
        Log.i(
            TAG,
            "share: ${candidates.size} share-like node(s): " +
                candidates.joinToString { "\"${nodeLabel(it)}\"[click=${it.isClickable}]" }
        )
        val target = candidates.firstOrNull { isClickableChain(it) } ?: candidates.firstOrNull()
        if (target == null) {
            if (!f.triedExpandMiniPlayer) {
                // YouTube's minimised mini-player exposes no Share affordance in its accessibility
                // tree at all — confirmed via a real uiautomator dump (2026-09-17): the only
                // clickable nodes over the player are Play/Pause, Close, and this "Expand Mini
                // Player" region. Leave the minimised state once before giving up, same
                // widen-a-step-from-real-evidence pattern as findWhatsAppInSheet's chooser
                // fallback below.
                val expand = findNode(root) { n ->
                    n.isVisibleToUser && n.isClickable &&
                        EXPAND_MINI_PLAYER_LABELS.any { nodeLabel(n).contains(it, true) }
                }
                if (expand != null) {
                    Log.i(
                        TAG,
                        "share: no share affordance found — looks like a minimised player, " +
                            "expanding via \"${nodeLabel(expand)}\""
                    )
                    f.triedExpandMiniPlayer = true
                    clickSmart(expand)
                    f.stepStartedAt = SystemClock.uptimeMillis()
                    handler.postDelayed({ pumpFlow() }, 500L)
                    return
                }
            }
            dumpClickables(root, "no share affordance")
            failFlow(getString(R.string.act_share_no_button))
            return
        }
        Log.i(TAG, "share: clicking share node \"${nodeLabel(target)}\"")
        clickSmart(target)
        transition(f, S.AWAIT_SHEET)
    }

    private fun findWhatsAppInSheet(f: ShareFlow) {
        val root = rootInActiveWindow ?: return
        val cands = findNodes(root) { n ->
            n.isVisibleToUser && nodeLabel(n).contains("whatsapp", true)
        }
        if (cands.isNotEmpty()) {
            Log.i(
                TAG,
                "share: ${cands.size} whatsapp-like sheet node(s): " +
                    cands.joinToString { "\"${nodeLabel(it)}\"[rank=${waSheetRank(nodeLabel(it))}]" }
            )
            // Prefer a plain "WhatsApp" (share to a chat) over "WhatsApp Status" (a story) or
            // "WhatsApp Business" (not handled). If only those exist, skip to the chooser instead
            // of silently sharing to Status.
            val wa = cands.minByOrNull { waSheetRank(nodeLabel(it)) }
            if (wa != null && waSheetRank(nodeLabel(wa)) <= WA_SHEET_RANK_ACCEPT) {
                Log.i(TAG, "share: choosing WhatsApp option \"${nodeLabel(wa)}\" — clicking")
                clickSmart(wa)
                transition(f, S.AWAIT_WA)
                return
            }
            Log.w(TAG, "share: only Status/Business WhatsApp entries in the sheet — widening to chooser")
        }
        if (!f.triedChooser) {
            val more = findNode(root) { n ->
                n.isVisibleToUser && CHOOSER_LABELS.any { nodeLabel(n).contains(it, true) }
            }
            if (more != null) {
                Log.i(TAG, "share: WhatsApp not directly listed — widening via \"${nodeLabel(more)}\"")
                f.triedChooser = true
                clickSmart(more)
                f.stepStartedAt = SystemClock.uptimeMillis()
                return
            }
        }
        // otherwise keep waiting until the watchdog times this step out
    }

    private fun enterWhatsAppQuery(f: ShareFlow) {
        val root = rootInActiveWindow ?: return
        if (looksLikeAppLock(root)) {
            failFlow(getString(R.string.act_share_whatsapp_locked))
            return
        }
        // Fast path: if the picker already shows a search box, type the core name now. Otherwise
        // just move on — WA_PICK_ROW scans the visible list first and opens search itself if the
        // group isn't there (e.g. not in the "recent" list).
        val field = findNode(root) { n ->
            n.isVisibleToUser && n.className?.contains("EditText") == true
        }
        if (field != null) {
            val term = searchTerm(f)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term)
            }
            val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "share: set WhatsApp search text \"$term\" ok=$ok")
            f.searchTyped = true
        }
        transition(f, S.WA_PICK_ROW)
        handler.postDelayed({ pumpFlow() }, 1000L)
    }

    /**
     * Find the target chat row. Row labels are matched on their **text core** — emoji, symbols,
     * ZWJ / variation selectors and punctuation stripped, a trailing generic word ("group",
     * "chat", …) dropped — so a spoken "family" matches a group whose name is
     * "Family" followed by a run of emoji. Matching is whole-token: an exact token match wins; failing that, a
     * single unambiguous leading-token prefix ("family" → the only "Family …" row). More than one
     * distinct name in reach → stop and ask for the full name (never guess who it goes to). If
     * nothing matches, WhatsApp's search is opened and the core name typed, then the scan retries.
     */
    private fun pickWhatsAppRow(f: ShareFlow) {
        if (f.rowClicked) return
        val root = rootInActiveWindow ?: return
        if (looksLikeAppLock(root)) {
            failFlow(getString(R.string.act_share_whatsapp_locked))
            return
        }
        f.pickScans++

        val qCore = matchCore(f.targetContact)
        val qToks = qCore.split(' ').filter { it.isNotBlank() }

        val exact = ArrayList<AccessibilityNodeInfo>()
        val prefix = ArrayList<AccessibilityNodeInfo>()
        val labelled = findNodes(root) { n ->
            n.isVisibleToUser && n.className?.contains("EditText") != true && nodeLabel(n).isNotBlank()
        }
        for (n in labelled) {
            val rToks = matchCore(nodeLabel(n)).split(' ').filter { it.isNotBlank() }
            if (rToks.isEmpty() || qToks.isEmpty()) continue
            when {
                rToks == qToks -> exact.add(n)
                rToks.size > qToks.size && rToks.subList(0, qToks.size) == qToks -> prefix.add(n)
            }
        }

        val pool = if (exact.isNotEmpty()) exact else prefix
        val distinctNames = pool.map { matchCore(nodeLabel(it)) }.toSet()
        Log.i(
            TAG,
            "share: WA row scan #${f.pickScans} q=\"$qCore\" exact=${exact.size} prefix=${prefix.size} " +
                "names=$distinctNames"
        )

        if (distinctNames.size > 1) {
            // More than one distinct name in reach — never click. Wait one scan in case the first
            // frame after a search was half-rendered; if it's still ambiguous, ask for the full name.
            if (f.pickScans >= 2) {
                Log.w(TAG, "share: ambiguous WhatsApp target for \"$qCore\" -> $distinctNames")
                failFlow(getString(R.string.act_share_contact_ambiguous))
            }
            return
        }

        if (pool.isNotEmpty()) {
            val row = pool.firstOrNull { isClickableChain(it) } ?: pool.first()
            Log.i(TAG, "share: selecting WhatsApp target \"${nodeLabel(row)}\" core=\"${matchCore(nodeLabel(row))}\"")
            if (clickSmart(row)) {
                f.rowClicked = true
                transition(f, S.WA_SEND)
                handler.postDelayed({ pumpFlow() }, 1200L)
            } else {
                Log.w(TAG, "share: row tap did not register — will retry")
            }
            return
        }

        if (maybeStartWhatsAppSearch(f, root)) return
        dumpClickables(root, "WhatsApp target row not found (yet)")
        // keep retrying until the watchdog times out -> act_share_contact_not_found
    }

    /**
     * The group may not be in the picker's default list. After a couple of empty scans, open
     * WhatsApp's Search and type the core name so it surfaces. Returns true if it acted this tick
     * (caller should wait for the next pump).
     */
    private fun maybeStartWhatsAppSearch(f: ShareFlow, root: AccessibilityNodeInfo): Boolean {
        val field = findNode(root) { n ->
            n.isVisibleToUser && n.className?.contains("EditText") == true
        }
        if (field != null && !f.searchTyped) {
            val term = searchTerm(f)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term)
            }
            val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "share: typed WhatsApp search \"$term\" ok=$ok")
            f.searchTyped = true
            f.stepStartedAt = SystemClock.uptimeMillis()   // fresh budget for results to load
            handler.postDelayed({ pumpFlow() }, 900L)
            return true
        }
        if (field == null && !f.searchOpened && f.pickScans >= 3) {
            val icon = findNode(root) { n ->
                n.isVisibleToUser && n.className?.contains("EditText") != true &&
                    (nodeLabel(n).equals("search", true) ||
                        n.viewIdResourceName?.substringAfterLast('/')?.contains("search", true) == true)
            }
            if (icon != null) {
                Log.i(TAG, "share: opening WhatsApp search")
                f.searchOpened = true
                clickSmart(icon)
                f.stepStartedAt = SystemClock.uptimeMillis()
                handler.postDelayed({ pumpFlow() }, 700L)
                return true
            }
        }
        return false
    }

    /** The text to type into WhatsApp search — the core name only ("family"), never the emoji. */
    private fun searchTerm(f: ShareFlow): String =
        matchCore(f.targetContact).ifBlank { f.targetContact.trim() }

    /**
     * Rank a share-sheet entry whose label mentions "whatsapp" — lower is better. A plain
     * "WhatsApp" (share into a chat) must win over "WhatsApp Status" (posts a story, not what she
     * asked) and "WhatsApp Business" (a different app, unhandled).
     */
    private fun waSheetRank(label: String): Int {
        val l = label.lowercase().trim()
        return when {
            l == "whatsapp" -> 0
            l.contains("status") -> 8
            l.contains("business") -> 7
            l.startsWith("whatsapp") -> 2   // "WhatsApp" + minor trailing text — still the chat entry
            l.contains("whatsapp") -> 4
            else -> 9
        }
    }

    /** Conservative check for WhatsApp's App Lock / biometric screen — only very specific text. */
    private fun looksLikeAppLock(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val hit = findNode(root) { n ->
            n.isVisibleToUser && LOCK_HINTS.any { nodeLabel(n).contains(it, true) }
        }
        if (hit != null) Log.w(TAG, "share: WhatsApp App Lock screen detected (\"${nodeLabel(hit)}\")")
        return hit != null
    }

    private fun tapSend(f: ShareFlow) {
        val now = SystemClock.uptimeMillis()
        if (f.sendClicks > 0 && now - f.lastSendClickAt < 1400) return
        val root = rootInActiveWindow ?: return
        val send = findNode(root) { n -> isSendControl(n) }
        if (send == null) {
            if (f.sendClicks == 0) {
                dumpClickables(root, "send button not found (yet)")
                return   // keep retrying; WA_SEND timeout -> act_share_no_send (never a false DONE)
            }
            // a send tap already landed and no further send screen appeared -> it went through
            Log.i(TAG, "share: shared to WhatsApp \"${f.targetContact}\" after ${f.sendClicks} send tap(s)")
            finishFlow(getString(R.string.act_share_done, f.targetContact))
            return
        }
        Log.i(TAG, "share: tapping send \"${nodeLabel(send)}\" id=${send.viewIdResourceName} (tap #${f.sendClicks + 1})")
        val clicked = clickSmart(send)
        if (!clicked) {
            Log.w(TAG, "share: send tap did not register — will retry")
            return   // don't count it; retry until the step times out
        }
        f.sendClicks++
        f.lastSendClickAt = now
        f.stepStartedAt = now
        if (f.sendClicks >= 2) {
            Log.i(TAG, "share: shared to WhatsApp \"${f.targetContact}\" after ${f.sendClicks} send tap(s)")
            finishFlow(getString(R.string.act_share_done, f.targetContact))
        } else {
            // a second send screen (media caption preview) may follow
            handler.postDelayed({ pumpFlow() }, 1600L)
        }
    }

    /**
     * A genuine send control — not WhatsApp's "Send to…" picker *title* (which merely contains
     * "send"). Accept an exact "send" label / contentDescription, a send-ish viewId, or a Button /
     * ImageButton whose label is send-ish.
     */
    private fun isSendControl(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser || !n.isEnabled) return false
        val lbl = nodeLabel(n).lowercase().trim()
        if (lbl.startsWith("send to") || lbl.contains("send to ")) return false   // picker title
        val vid = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
        val cls = n.className?.toString().orEmpty()
        return lbl == "send" || lbl == "send message" ||
            vid == "send" || vid == "btn_send" || vid.endsWith("_send") || vid.endsWith("send_btn") ||
            ((cls.contains("Button") || cls.contains("ImageView")) && lbl.contains("send") && lbl.length <= 12)
    }

    // ---- flow end --------------------------------------------------------

    private fun finishFlow(msg: String) {
        val f = flow ?: return
        Log.i(TAG, "${f.logTag}: DONE — $msg")
        endFlow()
        notifyResult(msg)
    }

    private fun failFlow(msg: String) {
        val f = flow ?: return
        Log.e(TAG, "${f.logTag}: FAILED at state=${f.currentStateLabel} — $msg")
        endFlow()
        notifyResult(msg)
    }

    private fun endFlow() {
        flow = null
        handler.removeCallbacks(watchdog)
    }

    private fun notifyResult(msg: String) {
        val listener = shareResultListener
        if (listener != null) listener(msg)
        else Log.w(TAG, "no result listener registered; outcome was: $msg")
    }

    private fun transition(f: ShareFlow, s: S) {
        f.state = s
        f.currentStateLabel = s.name
        f.stepStartedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "share: state -> $s")
    }

    private fun timeoutMessageFor(f: ShareFlow): String = when (f.state) {
        S.AWAIT_SRC -> getString(R.string.act_share_source_not_open)
        S.AWAIT_SHEET -> getString(R.string.act_share_no_whatsapp)
        S.AWAIT_WA -> getString(R.string.act_share_whatsapp_not_open)
        S.WA_PICK_ROW -> getString(R.string.act_share_contact_not_found, f.targetContact)
        S.WA_SEND -> getString(R.string.act_share_no_send)
    }

    // ---- node-tree helpers ---------------------------------------------------

    private fun currentPackage(): String? =
        rootInActiveWindow?.packageName?.toString() ?: lastForegroundPackage

    private fun isForegroundApp(pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg == packageName) return false
        return NON_APP_HINTS.none { pkg.contains(it, true) }
    }

    private fun nodeLabel(n: AccessibilityNodeInfo): String =
        ((n.contentDescription ?: n.text)?.toString() ?: "").trim()

    private fun isClickableChain(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 7) {
            if (n.isClickable) return true
            n = n.parent
            hops++
        }
        return false
    }

    /**
     * Activate [node]: try `ACTION_CLICK` on the nearest clickable+enabled ancestor (up to 12
     * hops), then on the node itself, then — the reliable path for WhatsApp / Instagram list rows
     * and send controls, which use custom touch handling and are not node-clickable — a
     * synthesized tap at the node's on-screen centre via `dispatchGesture`.
     */
    private fun clickSmart(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 12) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "share: click <${n.className}> \"${nodeLabel(n)}\" -> true")
                return true
            }
            n = n.parent
            hops++
        }
        if (node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "share: click node <${node.className}> -> true")
            return true
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() > 0 && r.height() > 0) {
            val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            val ok = dispatchGesture(gesture, null, null)
            Log.i(TAG, "share: gesture tap <${node.className}> @(${r.centerX()},${r.centerY()}) -> $ok")
            return ok
        }
        Log.w(TAG, "share: clickSmart exhausted for <${node.className}> (no bounds)")
        return false
    }

    private fun dumpClickables(root: AccessibilityNodeInfo?, why: String) {
        val labels = findNodes(root) { it.isVisibleToUser && it.isClickable }
            .map { nodeLabel(it) }
            .filter { it.isNotBlank() }
            .take(30)
        Log.w(TAG, "share: [$why] visible clickable labels: $labels")
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        limit: Int = NODE_SCAN_LIMIT,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < limit) {
            val n = queue.removeFirst()
            seen++
            try {
                if (match(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "share: node traversal hiccup", e)
            }
        }
        return null
    }

    private fun findNodes(
        root: AccessibilityNodeInfo?,
        limit: Int = NODE_SCAN_LIMIT,
        match: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (root == null) return out
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < limit) {
            val n = queue.removeFirst()
            seen++
            try {
                if (match(n)) out.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "share: node traversal hiccup", e)
            }
        }
        return out
    }

    companion object {
        private const val TAG = "MotherAssistant"

        private const val INSTAGRAM_PKG = "com.instagram.android"
        private const val YOUTUBE_PKG = "com.google.android.youtube"
        private const val WHATSAPP_PKG = "com.whatsapp"

        /**
         * Source apps this flow is proven against on-device — Instagram Reels, and YouTube
         * Shorts (a regular non-Shorts video isn't separately
         * confirmed yet, though nothing here is Shorts-specific). Anything else still runs the
         * same generic matchers, just logs a heads-up that it's untested.
         */
        private val VALIDATED_SOURCE_PKGS = setOf(INSTAGRAM_PKG, YOUTUBE_PKG)

        private const val STEP_TIMEOUT_MS = 8_000L
        /** WA_PICK_ROW needs longer: it may open search, type, and wait for results to load. */
        private const val WA_PICK_TIMEOUT_MS = 14_000L
        /** AWAIT_RESULTS (feature A) needs longer: a content grid may take a moment to load. */
        private const val SEARCH_RESULTS_TIMEOUT_MS = 12_000L
        /**
         * Feature A — how long [pickFirstResult] holds out for a poster-tagged tile that's
         * demonstrably new (not in the pre-search stale-labels snapshot) before giving up on
         * that check and accepting a stale-looking match anyway. See [pickFirstResult].
         */
        private const val STALE_RESULT_GRACE_MS = 3_000L
        /** AWAIT_TILES (feature B) needs longer: the profile screen may take a moment to load. */
        private const val PROFILE_TILES_TIMEOUT_MS = 10_000L
        /** Max acceptable share-sheet rank (see [waSheetRank]) — above this, skip to the chooser. */
        private const val WA_SHEET_RANK_ACCEPT = 2
        private const val WATCHDOG_INTERVAL_MS = 700L
        private const val NODE_SCAN_LIMIT = 4_000

        private val SHARE_LABELS = listOf("share", "reshare", "send post")
        private val CHOOSER_LABELS = listOf("share to", "other apps", "share via", "more options")

        /**
         * YouTube's minimised mini-player (confirmed via a real `uiautomator dump`, 2026-09-17):
         * its accessibility tree exposes Play/Pause, Close, and this "Expand Mini Player" region
         * over the player — no Share affordance at all until it's expanded back to the full
         * watch view. See [findAndClickShare].
         */
        private val EXPAND_MINI_PLAYER_LABELS = listOf("expand mini player", "expand miniplayer")

        /** Feature A (search_and_open) — a bottom-nav tab / toolbar icon that opens search. */
        private val SEARCH_ENTRY_LABELS = listOf("search")

        /** Feature A — generic search-screen chrome to never mistake for a real content result. */
        private val RESULT_BLACKLIST = listOf(
            "search", "cancel", "clear", "back", "filter", "home", "subscribe", "my space",
            "recent searches", "trending", "clear all", "voice search", "mic", "menu", "profile", "settings",
            "top results", "more results", "popular searches in", "popular searches"
        )

        /**
         * Feature A — resource-id substring a genuine "Top Results" content tile carries on
         * Hotstar (confirmed via a live on-device dump). Suggestion
         * chips and recent-search chips never carry this id even though they render with a real,
         * non-blank label before real results load — this is the one signal that reliably tells
         * them apart, so [pickFirstResult] requires it rather than accepting any labelled node.
         */
        private val RESULT_TILE_ID_HINTS = listOf("content_poster")

        /** Feature A — belt-and-suspenders exclusion for the as-you-type suggestion chip row. */
        private val RESULT_TILE_ID_EXCLUDE = listOf("search_auto_suggestion")

        /** Feature B (switch_profile) — a generic entry point into the account/profile area. */
        private val PROFILE_ENTRY_LABELS = listOf("profile", "account", "my account")

        /** Feature B — labels that jump straight to (or, in a menu, trigger) the profile switcher. */
        private val DIRECT_PROFILE_LABELS = listOf("switch profile", "who's watching", "manage profiles", "switch account")

        /** Feature B — chrome on the profile-tiles screen that is never itself a profile. */
        private val PROFILE_TILE_BLACKLIST = listOf("back", "add profile", "manage profiles", "edit", "done", "add", "cancel")

        /** Trailing filler words dropped from a name core before matching ("family group" -> "family"). */
        private val TRAILING_GENERIC = setOf("group", "grup", "gruph", "chat", "wala", "wale", "wali")

        /** Leading app-name tokens dropped from a name core ("whatsapp family group" -> "family"). */
        private val LEADING_GENERIC = setOf("whatsapp", "wa")

        /** Very specific WhatsApp App Lock / biometric-screen text — kept narrow to avoid false hits. */
        private val LOCK_HINTS = listOf(
            "unlock whatsapp", "unlock with", "use fingerprint", "confirm fingerprint", "app lock"
        )

        /**
         * Substrings of packages that are never a real "source app" to share from — system UI,
         * the launcher, the IME, permission dialogs, and the Google feed / Assistant surface
         * (`googlequicksearchbox`) that Pixel/Moto route through *during the app-switch gesture
         * itself*. Without `googlequicksearchbox` here, simply swiping from Instagram to this app
         * latched the search surface as the source and the share failed with "वो app नहीं खुल पाया"
         */
        private val NON_APP_HINTS = listOf(
            "systemui", "launcher", "trebuchet", "quickstep", "inputmethod", "packageinstaller",
            "permissioncontroller", "com.google.android.googlequicksearchbox", "com.android.settings",
            // OEM security/anti-phishing interstitials that briefly foreground on a link tap
            "securityhub", "securitycenter"
        )

        /**
         * Reduce a display name to a comparable core: drop everything that isn't a letter or digit
         * (emoji, ZWJ, variation selectors, skin-tone modifiers, punctuation, "·" separators), collapse
         * whitespace, lowercase, then drop a leading app-name token ("whatsapp") and a trailing generic
         * word ("group", "chat", …). Keeps Devanagari / Odia letters, so a group named in her script
         * still compares. The leading strip is a backstop for when Gemini leaves the app name in
         * `target` ("WhatsApp family group" -> "family") despite the SystemPrompt rule.
         *
         * `internal` (not `private`) so [com.family.motherassistant.actions.AccessibilityActions]
         * can reuse the exact same normalization to guard against a target that reduces to nothing
         * usable (e.g. "whatsapp group" with no group actually named) before starting a share flow.
         */
        internal fun matchCore(raw: String): String {
            val toks = raw
                .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
                .lowercase()
                .split(' ')
                .filter { it.isNotBlank() }
                .toMutableList()
            while (toks.size > 1 && toks.first() in LEADING_GENERIC) toks.removeAt(0)
            while (toks.size > 1 && toks.last() in TRAILING_GENERIC) toks.removeAt(toks.lastIndex)
            return toks.joinToString(" ")
        }

        /**
         * True when [raw] can never resolve to a real WhatsApp contact/group — its [matchCore]
         * is blank, or is nothing but a leftover generic word ("group", "chat", "whatsapp", …)
         * with no actual name in it. `matchCore` only strips a leading/trailing generic token
         * down to one remaining token, so a target that was *only* generic words ("whatsapp
         * group") ends up as exactly that one generic word, not blank — this catches that case.
         * Lets [com.family.motherassistant.actions.AccessibilityActions] fail fast / ask instead
         * of running the full `WA_PICK_ROW` scan loop against a target that cannot possibly match.
         */
        internal fun isUnusableShareTarget(raw: String): Boolean {
            val core = matchCore(raw)
            return core.isBlank() || core in TRAILING_GENERIC || core in LEADING_GENERIC
        }

        /** Set while the service is connected, so the app can tell whether it is enabled. */
        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set

        /**
         * MainActivity registers this to speak / show the async share outcome. Needs the
         * Activity still alive (backgrounded is fine); null → the outcome is log-only.
         */
        @Volatile
        var shareResultListener: ((String) -> Unit)? = null
    }
}
