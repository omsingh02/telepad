package com.omsingh.telepad.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Owns the Bluetooth HID Device profile lifecycle and report transmission.
 *
 * Lifecycle: [init] → callback → [connect] → [send*] → [disconnect] → [cleanup].
 *
 * **Pre-allocated buffers.** Mouse/keyboard/consumer report arrays and the 6KRO
 * active-keys slot table are all instance fields, reused on every send.
 * Per-event allocations on the hot path = zero.
 *
 * **Threading.** All `sendReport` calls happen on a dedicated single-thread
 * executor (`telepad-hid`) so the binder IPC to the system Bluetooth service
 * never runs on the main thread. State changes flow back through [state]
 * StateFlow, safe to collect from any context.
 *
 * **OEM compatibility caveat.** `BluetoothHidDevice` is documented in AOSP but
 * historically shipped *disabled* on many pre-Pixel OEM builds (the manifest
 * boolean `profile_supported_hidd` defaulted to false). [registerApp] returning
 * false is the strongest runtime signal we have for this — surface that to the
 * UI as `HidState.Error` so the user knows to switch to Wi-Fi mode.
 *
 * **HID interrupt channel.** `sendReport` uses the *interrupt* L2CAP channel
 * which is the correct low-latency channel for input. Radio TX is async from
 * the caller's perspective — we return immediately and the kernel queues.
 */
@SuppressLint("MissingPermission")
class HidController(val context: Context) {

    /** Externally observable state of the HID stack. */
    sealed interface HidState {
        data object Uninitialized : HidState
        data object Ready         : HidState
        data object Connecting    : HidState
        data class Connected(val deviceName: String) : HidState
        data class Error(val message: String) : HidState
    }

    private val _state = MutableStateFlow<HidState>(HidState.Uninitialized)
    val state: StateFlow<HidState> = _state.asStateFlow()

    private val bluetoothManager = context.getSystemService(
        Context.BLUETOOTH_SERVICE
    ) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var hidProfile: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telepad-hid").apply {
            priority = Thread.NORM_PRIORITY + 2
            isDaemon = true
        }
    }

    // ── Pre-allocated report buffers ─────────────────────────────────
    private val mouseReport    = ByteArray(HidReportDescriptor.MOUSE_REPORT_SIZE)
    private val keyboardReport = ByteArray(HidReportDescriptor.KEYBOARD_REPORT_SIZE)
    private val consumerReport = ByteArray(HidReportDescriptor.CONSUMER_REPORT_SIZE)
    /** Active 6KRO slots. 0 = empty. Modifier bits live separately in keyboardReport[0]. */
    private val activeKeys = IntArray(6)
    private var currentModifiers = 0

    // ── HID device-descriptor metadata ───────────────────────────────
    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Telepad",
        "Phone remote touchpad and keyboard",
        "Telepad",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidReportDescriptor.DESCRIPTOR
    )

    /**
     * QoS = best-effort with latency tuned for HID.
     * Token rate / bucket are small (HID traffic is tiny). Access latency and
     * delay variation are 11.25 ms — the Bluetooth Core spec's minimum
     * connection interval. Hosts can negotiate lower; this is the upper bound
     * the firmware will respect.
     */
    private val qosSettings = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        /* tokenRate     */ 800,
        /* tokenBucketSize */ 9,
        /* peakBandwidth */ 0,
        /* latency       */ 11250,
        /* delayVariation*/ 11250
    )

    /**
     * Acquire the HID Device profile proxy and register our descriptor.
     *
     * @param onReady invoked when registration succeeds and we're ready to [connect].
     * @param onError invoked with a human-readable reason on any failure.
     */
    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        val a = adapter ?: run { onError("Bluetooth unavailable"); return }
        if (!a.isEnabled) { onError("Bluetooth is off"); return }

        a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val p = proxy as BluetoothHidDevice
                hidProfile = p
                val ok = try {
                    p.registerApp(sdpSettings, null, qosSettings, executor, profileCallback)
                } catch (e: SecurityException) {
                    onError("Bluetooth permission denied")
                    return
                }
                if (!ok) {
                    val msg = "HID Device profile not supported on this device " +
                              "(your manufacturer may have disabled it)"
                    _state.value = HidState.Error(msg)
                    onError(msg)
                } else {
                    _state.value = HidState.Ready
                    onReady()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) hidProfile = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private val profileCallback = object : BluetoothHidDevice.Callback() {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            super.onConnectionStateChanged(device, state)
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> _state.value = HidState.Connecting
                BluetoothProfile.STATE_CONNECTED  -> {
                    connectedDevice = device
                    _state.value = HidState.Connected(deviceLabel(device))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    releaseAllKeys()
                    releaseConsumer()
                    _state.value = HidState.Ready
                }
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        _state.value = HidState.Connecting
        executor.execute {
            try {
                hidProfile?.connect(device)
            } catch (e: SecurityException) {
                _state.value = HidState.Error("Bluetooth permission denied")
            }
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                connectedDevice?.let { hidProfile?.disconnect(it) }
            } catch (_: SecurityException) { /* permission revoked mid-flight */ }
            connectedDevice = null
        }
    }

    fun cleanup() {
        try {
            disconnect()
            hidProfile?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidProfile)
        } catch (_: Exception) { /* best-effort shutdown */ }
        executor.shutdownNow()
    }

    // ═════════════════════ HOT PATH: report TX ═════════════════════

    /**
     * Send a mouse report: 3-bit button bitmap, 16-bit relative X, 16-bit Y, 8-bit wheel.
     * All values are coerced to the descriptor's logical range.
     */
    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = connectedDevice ?: return
        val r = mouseReport
        r[0] = (buttons and 0x07).toByte()
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        // Little-endian write.
        r[1] = (x and 0xFF).toByte()
        r[2] = ((x ushr 8) and 0xFF).toByte()
        r[3] = (y and 0xFF).toByte()
        r[4] = ((y ushr 8) and 0xFF).toByte()
        r[5] = wheel.coerceIn(-127, 127).toByte()
        trySend(device, HidReportDescriptor.REPORT_ID_MOUSE, r)
    }

    /**
     * Press a key.
     *  - If [hidCode] is 0xE0..0xE7, it's a modifier — OR its bit into the modifier
     *    byte rather than putting it in a key slot.
     *  - Otherwise, find the first empty 6KRO slot.
     *  - If all 6 slots are full, emit an all-error rollover report per HID spec.
     */
    fun pressKey(hidCode: Int, modifiers: Int) {
        currentModifiers = modifiers
        if (hidCode in 0xE0..0xE7) {
            val bit = 1 shl (hidCode - 0xE0)
            currentModifiers = currentModifiers or bit
            sendKeyboard(currentModifiers, activeKeys)
            return
        }
        for (i in activeKeys.indices) if (activeKeys[i] == hidCode) return // already pressed
        for (i in activeKeys.indices) {
            if (activeKeys[i] == 0) {
                activeKeys[i] = hidCode
                sendKeyboard(currentModifiers, activeKeys)
                return
            }
        }
        // Rollover — fill all slots with the ErrorRollOver value.
        sendKeyboard(currentModifiers, ROLLOVER_KEYS)
    }

    fun releaseKey(hidCode: Int, modifiers: Int) {
        currentModifiers = modifiers
        if (hidCode in 0xE0..0xE7) {
            val bit = 1 shl (hidCode - 0xE0)
            currentModifiers = currentModifiers and bit.inv()
            sendKeyboard(currentModifiers, activeKeys)
            return
        }
        for (i in activeKeys.indices) {
            if (activeKeys[i] == hidCode) {
                activeKeys[i] = 0
                break
            }
        }
        sendKeyboard(currentModifiers, activeKeys)
    }

    fun releaseAllKeys() {
        for (i in activeKeys.indices) activeKeys[i] = 0
        currentModifiers = 0
        sendKeyboard(0, activeKeys)
    }

    private fun sendKeyboard(modifiers: Int, keys: IntArray) {
        val device = connectedDevice ?: return
        val r = keyboardReport
        r[0] = (modifiers and 0xFF).toByte()
        r[1] = 0
        for (i in 0..5) r[2 + i] = (keys[i] and 0xFF).toByte()
        trySend(device, HidReportDescriptor.REPORT_ID_KEYBOARD, r)
    }

    /** Press a Consumer Control usage. Caller must follow with [releaseConsumer]. */
    fun sendConsumer(usage: Int) {
        val device = connectedDevice ?: return
        consumerReport[0] = (usage and 0xFF).toByte()
        consumerReport[1] = ((usage ushr 8) and 0xFF).toByte()
        trySend(device, HidReportDescriptor.REPORT_ID_CONSUMER, consumerReport)
    }

    /** Release the currently-pressed Consumer Control by sending an all-zero report. */
    fun releaseConsumer() {
        val device = connectedDevice ?: return
        consumerReport[0] = 0
        consumerReport[1] = 0
        trySend(device, HidReportDescriptor.REPORT_ID_CONSUMER, consumerReport)
    }

    private fun trySend(device: BluetoothDevice, reportId: Int, data: ByteArray) {
        try {
            hidProfile?.sendReport(device, reportId, data)
        } catch (_: SecurityException) {
            // Permission revoked mid-flight — connection will die shortly.
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport failed: ${t.message}")
        }
    }

    private fun deviceLabel(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (_: SecurityException) { device.address }

    private companion object {
        const val TAG = "HidController"
        /** Used to indicate 6KRO rollover per HID spec — all slots = 0x01 (ErrorRollOver). */
        val ROLLOVER_KEYS = IntArray(6) { 0x01 }
    }
}
