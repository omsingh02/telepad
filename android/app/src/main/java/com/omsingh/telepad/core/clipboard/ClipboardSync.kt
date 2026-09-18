package com.omsingh.telepad.core.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bidirectional clipboard sync between phone and PC.
 *
 * The flow:
 *
 *  1. **Phone → PC** ("Push clipboard to PC"): user explicitly taps a button.
 *     We read the phone clipboard via [ClipboardManager] and emit an
 *     [InputEvent.ClipboardSet] which the dispatcher carries to the server.
 *
 *  2. **PC → Phone** ("Pull clipboard from PC"): user explicitly taps a button.
 *     We emit [InputEvent.ClipboardGet]. The server's reply is a
 *     `MSG_TYPE_CLIPBOARD_DATA` packet handled by the active
 *     [InputDispatcher], which calls back into [onClipboardReceived] here.
 *
 * **Why explicit-only, no auto-sync?** Auto-syncing the clipboard is a
 * privacy disaster waiting to happen:
 *  - Phones constantly grab text from other apps onto the clipboard.
 *  - PCs do too — every Ctrl+C silently leaks across to the phone.
 *  - Many password managers paste credentials into the clipboard for ~10s.
 *
 * Making the user tap "send" or "fetch" turns clipboard sync from a footgun
 * into a tool. This matches what KDE Connect does, and it's the right default.
 *
 * **Size limit:** 32 KB. Above that we silently truncate. The server enforces
 * the same limit before applying. (UDP MTU is the real ceiling — even after
 * fragmentation, multi-MB clipboards over UDP are abuse, not feature.)
 *
 * **Thread safety:** [ClipboardManager] requires the main thread. All public
 * methods marshal to main via the system service's internal handler. The
 * [latestFromPc] flow is safe to collect from any context.
 */
class ClipboardSync(
    context: Context,
    private val dispatcher: () -> InputDispatcher?
) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
        as ClipboardManager

    private val _latestFromPc = MutableStateFlow<String?>(null)
    /**
     * Most recent clipboard text fetched from the PC, or null if none yet.
     * Surfaced by the keyboard screen's QuickActions row so the user can
     * paste it into a phone text field. Re-emitted on every fetch.
     */
    val latestFromPc: StateFlow<String?> = _latestFromPc.asStateFlow()

    /**
     * Send the phone's current clipboard text to the PC.
     * Silent no-op if the phone clipboard is empty or non-text.
     * @return true if a clipboard payload was sent.
     */
    fun pushToPc(): Boolean {
        val text = readPhoneClipboard() ?: return false
        val truncated = if (text.length > MAX_LEN) text.substring(0, MAX_LEN) else text
        dispatcher()?.dispatch(InputEvent.ClipboardSet(truncated))
        Log.d(TAG, "Pushed clipboard to PC (${truncated.length} chars)")
        return true
    }

    /**
     * Request the PC's clipboard. The response arrives asynchronously via
     * [onClipboardReceived] and is published on [latestFromPc].
     */
    fun pullFromPc() {
        dispatcher()?.dispatch(InputEvent.ClipboardGet)
    }

    /**
     * Called by the active dispatcher when a clipboard-data packet arrives
     * from the server. Updates [latestFromPc] for the UI to consume.
     *
     * Does *not* automatically write to the phone clipboard — the user must
     * explicitly paste from the "Pasted from PC" pill in the keyboard screen.
     * This prevents the PC's clipboard from silently overwriting whatever the
     * user had locally.
     */
    fun onClipboardReceived(text: String) {
        val truncated = if (text.length > MAX_LEN) text.substring(0, MAX_LEN) else text
        _latestFromPc.value = truncated
        Log.d(TAG, "Received clipboard from PC (${truncated.length} chars)")
    }

    /**
     * One-shot: copy the most recently received PC-clipboard text into the
     * phone's clipboard. Called when the user taps the "Pasted from PC" pill.
     */
    fun copyPcClipboardToPhone() {
        val text = _latestFromPc.value ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Telepad: from PC", text))
        Log.d(TAG, "Copied PC clipboard onto phone clipboard")
    }

    /** Forget the most recently received PC clipboard. UI scrubs the pill. */
    fun clearLatest() {
        _latestFromPc.value = null
    }

    private fun readPhoneClipboard(): String? {
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        return item.coerceToText(appContext)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "ClipboardSync"
        const val MAX_LEN = 32 * 1024  // 32 KB UTF-16 char ceiling.
    }
}
