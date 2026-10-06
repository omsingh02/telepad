package com.omsingh.telepad.core.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.omsingh.telepad.core.input.FailureReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Owns the Bluetooth HID Device profile: registering the phone as a keyboard and
 * mouse, connecting to a host, and delivering reports.
 *
 * **Threading.** One thread, [executor] ("telepad-hid"), does all HID work. The
 * framework delivers its callbacks there, [HidInputTranslator] keeps its state and sends
 * its reports there, and nothing blocks the main thread on the Bluetooth service.
 *
 * **Device support.** `BluetoothHidDevice` exists in AOSP but many manufacturers ship
 * it disabled, and only one app at a time can hold the HID Device role. Both show up
 * as a failure to obtain or register the profile, reported as a [FailureReason] the
 * UI can explain.
 */
@SuppressLint("MissingPermission")
class HidController(private val context: Context) : HidReportSink {

    /** Externally observable state of the HID stack. */
    sealed interface HidState {
        data object Uninitialized : HidState
        data object Ready : HidState
        data object Connecting : HidState
        data class Connected(val deviceName: String) : HidState
        data class Failed(val reason: FailureReason) : HidState
    }

    private val _state = MutableStateFlow<HidState>(HidState.Uninitialized)
    val state: StateFlow<HidState> = _state.asStateFlow()

    /** The thread everything HID runs on. Scheduled so that timed releases can be queued. */
    val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "telepad-hid").apply {
            priority = Thread.NORM_PRIORITY + 2
            isDaemon = true
        }
    }.apply { removeOnCancelPolicy = true }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var hidProfile: BluetoothHidDevice? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var registered = false
    private var pendingReady: (() -> Unit)? = null
    private var pendingFailure: ((FailureReason) -> Unit)? = null
    private var connectTimeout: ScheduledFuture<*>? = null
    private var registerTimeout: ScheduledFuture<*>? = null

    override val isConnected: Boolean get() = connectedDevice != null && hidProfile != null

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Telepad",
        "Phone remote touchpad and keyboard",
        "Telepad",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidReportDescriptor.DESCRIPTOR
    )

    /**
     * Best effort with the lowest latency the Bluetooth core spec allows (11.25 ms).
     * HID traffic is tiny, so the token bucket is too.
     */
    private val qosSettings = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        /* tokenRate */ 800,
        /* tokenBucketSize */ 9,
        /* peakBandwidth */ 0,
        /* latency */ 11250,
        /* delayVariation */ 11250
    )

    // ── Preconditions ────────────────────────────────────────────────

    /** Whether the runtime permission needed to talk to paired devices is held. */
    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    val hasAdapter: Boolean get() = adapter != null

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Phones this one has been paired with in Android's Bluetooth settings. */
    fun bondedDevices(): List<BluetoothDevice> =
        try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

    fun device(address: String): BluetoothDevice? =
        try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Gets the HID Device profile and registers this phone's descriptor.
     * [onReady] runs once the framework confirms the registration; [onFailure] says why not.
     */
    fun init(onReady: () -> Unit, onFailure: (FailureReason) -> Unit) {
        val a = adapter ?: return onFailure(FailureReason.BLUETOOTH_UNSUPPORTED)
        if (!hasConnectPermission()) return onFailure(FailureReason.BLUETOOTH_PERMISSION)
        if (!a.isEnabled) return onFailure(FailureReason.BLUETOOTH_DISABLED)
        if (registered && hidProfile != null) return onReady()

        pendingReady = onReady
        pendingFailure = onFailure

        val obtained = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val p = proxy as BluetoothHidDevice
                hidProfile = p
                val accepted = try {
                    p.registerApp(sdpSettings, null, qosSettings, executor, profileCallback)
                } catch (e: SecurityException) {
                    failInit(FailureReason.BLUETOOTH_PERMISSION)
                    return
                }
                if (!accepted) {
                    failInit(FailureReason.BLUETOOTH_FAILED)
                    return
                }
                // `registerApp` only means the request was taken; wait for the confirmation.
                registerTimeout = executor.schedule({
                    if (!registered) failInit(FailureReason.BLUETOOTH_FAILED)
                }, REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidProfile = null
                    registered = false
                    connectedDevice = null
                    _state.value = HidState.Uninitialized
                }
            }
        }, BluetoothProfile.HID_DEVICE)

        if (!obtained) failInit(FailureReason.BLUETOOTH_UNSUPPORTED)
    }

    private fun failInit(reason: FailureReason) {
        registerTimeout?.cancel(false)
        val callback = pendingFailure
        pendingReady = null
        pendingFailure = null
        _state.value = HidState.Failed(reason)
        callback?.invoke(reason)
    }

    private val profileCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidController.registered = registered
            if (registered) {
                registerTimeout?.cancel(false)
                _state.value = HidState.Ready
                val callback = pendingReady
                pendingReady = null
                pendingFailure = null
                callback?.invoke()
            } else {
                connectedDevice = null
                if (_state.value !is HidState.Failed) _state.value = HidState.Uninitialized
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> _state.value = HidState.Connecting
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeout?.cancel(false)
                    connectedDevice = device
                    _state.value = HidState.Connected(labelOf(device))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectTimeout?.cancel(false)
                    val wasConnected = connectedDevice != null
                    connectedDevice = null
                    // A connection that never came up is a failure; one that ended is just over.
                    _state.value = if (wasConnected || _state.value !is HidState.Connecting) {
                        HidState.Ready
                    } else {
                        HidState.Failed(FailureReason.BLUETOOTH_FAILED)
                    }
                }
            }
        }

        // The host asks for a report we never keep: say so, rather than leave it waiting.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            try {
                hidProfile?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            } catch (_: SecurityException) {
            }
        }

        // The host sets keyboard LEDs (Caps Lock and the like) and expects an answer.
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            try {
                hidProfile?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            } catch (_: SecurityException) {
            }
        }
    }

    /** Asks the host to connect. The result arrives through [state]. */
    fun connect(device: BluetoothDevice) {
        _state.value = HidState.Connecting
        connectTimeout?.cancel(false)
        connectTimeout = executor.schedule({
            if (_state.value is HidState.Connecting) {
                _state.value = HidState.Failed(FailureReason.BLUETOOTH_FAILED)
            }
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        executor.execute {
            try {
                val asked = hidProfile?.connect(device) ?: false
                if (!asked && _state.value is HidState.Connecting) {
                    _state.value = HidState.Failed(FailureReason.BLUETOOTH_FAILED)
                }
            } catch (e: SecurityException) {
                _state.value = HidState.Failed(FailureReason.BLUETOOTH_PERMISSION)
            }
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                connectedDevice?.let { hidProfile?.disconnect(it) }
            } catch (_: SecurityException) {
                // Permission revoked mid-flight.
            }
            connectedDevice = null
            if (_state.value !is HidState.Failed && registered) _state.value = HidState.Ready
        }
    }

    /** Forgets a failure so the next attempt starts clean. */
    fun clearFailure() {
        if (_state.value is HidState.Failed) {
            _state.value = if (registered) HidState.Ready else HidState.Uninitialized
        }
    }

    fun cleanup() {
        try {
            connectedDevice?.let { hidProfile?.disconnect(it) }
            hidProfile?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidProfile)
        } catch (_: Exception) {
            // Best-effort shutdown.
        }
        hidProfile = null
        registered = false
        connectedDevice = null
        executor.shutdownNow()
    }

    // ── Report transmission (executor thread) ────────────────────────

    override fun send(reportId: Int, data: ByteArray) {
        val device = connectedDevice ?: return
        try {
            hidProfile?.sendReport(device, reportId, data)
        } catch (_: SecurityException) {
            // Permission revoked mid-flight: the connection will end shortly.
        } catch (t: Throwable) {
            Log.w(TAG, "sendReport failed: ${t.javaClass.simpleName}")
        }
    }

    private fun labelOf(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

    private companion object {
        const val TAG = "HidController"
        const val REGISTER_TIMEOUT_MS = 6_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
