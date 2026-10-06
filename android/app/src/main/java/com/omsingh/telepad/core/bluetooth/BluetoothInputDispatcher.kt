package com.omsingh.telepad.core.bluetooth

import com.omsingh.telepad.core.host.HostProfile
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The Bluetooth transport: the phone as a Bluetooth keyboard and mouse.
 *
 * All the translating lives in [HidInputTranslator]; this class connects, and maps the
 * HID stack's state onto the [ConnectionState] the rest of the app understands.
 *
 * What Bluetooth cannot do, the UI is told about instead of failing silently: it
 * cannot carry the clipboard, and it cannot type anything outside ASCII (see
 * [untypeableCharacters]).
 */
class BluetoothInputDispatcher(
    private val hid: HidController,
    host: () -> HostProfile,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : InputDispatcher {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _untypeable = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** How many characters of some typed text could not be sent, each time that happens. */
    val untypeableCharacters: SharedFlow<Int> = _untypeable.asSharedFlow()

    private val translator = HidInputTranslator(
        sink = hid,
        executor = hid.executor,
        host = host,
        onUntypeable = { _untypeable.tryEmit(it) },
    )

    @Volatile private var targetName: String = ""

    init {
        scope.launch { hid.state.collect(::onHidState) }
    }

    override fun dispatch(event: InputEvent) = translator.handle(event)

    override suspend fun connect(target: ConnectionTarget) {
        val bt = target as? ConnectionTarget.Bluetooth ?: return
        targetName = bt.name
        val current = _connectionState.value
        if (current is ConnectionState.Connected && current.deviceName == bt.name) return

        hid.clearFailure()
        _connectionState.value = ConnectionState.Connecting(bt.name, ConnectionState.Transport.BLUETOOTH)

        val ready = suspendCancellableCoroutine<FailureReason?> { continuation ->
            hid.init(
                onReady = { if (continuation.isActive) continuation.resume(null) },
                onFailure = { reason -> if (continuation.isActive) continuation.resume(reason) },
            )
        }
        if (ready != null) {
            fail(ready)
            return
        }

        val device = hid.device(bt.address)
        if (device == null) {
            fail(FailureReason.BLUETOOTH_FAILED)
            return
        }
        hid.connect(device)
        // The outcome arrives through the HID state.
        withTimeoutOrNull(CONNECT_WAIT_MS) {
            hid.state.first { it is HidController.HidState.Connected || it is HidController.HidState.Failed }
        }
    }

    override fun disconnect() {
        translator.releaseAll()
        hid.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun fail(reason: FailureReason) {
        _connectionState.value = ConnectionState.Failed(
            targetName.ifEmpty { null }, ConnectionState.Transport.BLUETOOTH, reason,
        )
    }

    /** Projects [HidController.HidState] onto the transport-agnostic [ConnectionState]. */
    private fun onHidState(state: HidController.HidState) {
        val name = targetName
        _connectionState.value = when (state) {
            HidController.HidState.Uninitialized -> endedConnection()
            // Ready is also what registering reports on the way to connecting.
            HidController.HidState.Ready ->
                if (_connectionState.value is ConnectionState.Connecting) _connectionState.value else endedConnection()
            HidController.HidState.Connecting ->
                ConnectionState.Connecting(name.ifEmpty { "Bluetooth" }, ConnectionState.Transport.BLUETOOTH)
            is HidController.HidState.Connected ->
                ConnectionState.Connected(state.deviceName, ConnectionState.Transport.BLUETOOTH)
            is HidController.HidState.Failed ->
                ConnectionState.Failed(name.ifEmpty { null }, ConnectionState.Transport.BLUETOOTH, state.reason)
        }
    }

    private fun endedConnection(): ConnectionState {
        translator.releaseAll()
        return ConnectionState.Disconnected
    }

    private companion object {
        const val CONNECT_WAIT_MS = 25_000L
    }
}
