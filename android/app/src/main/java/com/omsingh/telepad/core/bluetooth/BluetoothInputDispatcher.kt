package com.omsingh.telepad.core.bluetooth

import android.bluetooth.BluetoothAdapter
import android.util.Log
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_MUTE
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_NEXT_TRACK
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_PLAY_PAUSE
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_PREV_TRACK
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_STOP
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_VOLUME_DOWN
import com.omsingh.telepad.core.bluetooth.HidReportDescriptor.CONSUMER_VOLUME_UP
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.HidKeyCodes
import com.omsingh.telepad.core.input.HidModifierMask
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.core.input.asciiToHid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Routes [InputEvent]s to the Bluetooth HID controller.
 *
 * Translation strategy:
 *  - Mouse moves are accumulated with sub-pixel remainder so slow finger drags
 *    don't silently round to zero deltas.
 *  - Button state is tracked locally as a 3-bit bitmap, sent as part of every
 *    mouse report (HID semantics — there are no "button only" reports).
 *  - Keys go through the HidController's 6KRO slot tracker.
 *  - TextInput is typed character-by-character via [asciiToHid] with a small
 *    delay between presses to give the host's HID stack time to register.
 *    Non-ASCII characters are dropped (no `KEYEVENTF_UNICODE` equivalent in
 *    standard HID — use Wi-Fi for full Unicode).
 *  - Media commands are sent as Consumer Control usages with a brief release.
 *  - LockScreen sends Win+L (cross-platform default; macOS uses Ctrl+Cmd+Q
 *    but that requires an OS-specific code path on the host side).
 */
class BluetoothInputDispatcher(
    private val hidController: HidController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : InputDispatcher {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Bitmap of currently-held mouse buttons. */
    private var currentButtons = 0
    private var dxRemainder = 0f
    private var dyRemainder = 0f

    init {
        scope.launch {
            hidController.state.collect { hidState -> updateState(hidState) }
        }
    }

    override fun dispatch(event: InputEvent) {
        when (event) {

            is InputEvent.MouseMove -> {
                // Carry sub-pixel remainder forward so slow drags accumulate.
                val totalDx = event.dx + dxRemainder
                val totalDy = event.dy + dyRemainder
                val ix = totalDx.toInt()
                val iy = totalDy.toInt()
                dxRemainder = totalDx - ix
                dyRemainder = totalDy - iy
                if (ix != 0 || iy != 0) {
                    hidController.sendMouseReport(currentButtons, ix, iy, 0)
                }
            }

            is InputEvent.MouseButton -> {
                val bit = buttonBit(event.button)
                currentButtons = if (event.pressed)
                    currentButtons or bit
                else
                    currentButtons and bit.inv()
                hidController.sendMouseReport(currentButtons, 0, 0, 0)
            }

            is InputEvent.Scroll -> {
                hidController.sendMouseReport(currentButtons, 0, 0, event.delta.toInt())
            }

            InputEvent.Click -> {
                hidController.sendMouseReport(currentButtons or 0x01, 0, 0, 0)
                hidController.sendMouseReport(currentButtons and 0x01.inv(), 0, 0, 0)
            }

            InputEvent.DoubleClick -> {
                repeat(2) {
                    hidController.sendMouseReport(currentButtons or 0x01, 0, 0, 0)
                    hidController.sendMouseReport(currentButtons and 0x01.inv(), 0, 0, 0)
                }
            }

            InputEvent.RightClick -> {
                hidController.sendMouseReport(currentButtons or 0x02, 0, 0, 0)
                hidController.sendMouseReport(currentButtons and 0x02.inv(), 0, 0, 0)
            }

            is InputEvent.DragStart -> {
                val bit = buttonBit(event.button)
                currentButtons = currentButtons or bit
                hidController.sendMouseReport(currentButtons, 0, 0, 0)
            }

            is InputEvent.DragEnd -> {
                val bit = buttonBit(event.button)
                currentButtons = currentButtons and bit.inv()
                hidController.sendMouseReport(currentButtons, 0, 0, 0)
            }

            is InputEvent.KeyPress -> {
                hidController.pressKey(event.keyCode, event.modifiers.toHidByte())
            }

            is InputEvent.KeyRelease -> {
                hidController.releaseKey(event.keyCode, event.modifiers.toHidByte())
            }

            is InputEvent.TextInput -> {
                // ASCII-only on Bluetooth. Unicode goes through Wi-Fi.
                scope.launch { typeAscii(event.text, event.modifiers.toHidByte()) }
            }

            is InputEvent.MediaCommand -> sendMomentaryConsumer(when (event.action) {
                InputEvent.MediaAction.PLAY_PAUSE -> CONSUMER_PLAY_PAUSE
                InputEvent.MediaAction.NEXT      -> CONSUMER_NEXT_TRACK
                InputEvent.MediaAction.PREV      -> CONSUMER_PREV_TRACK
                InputEvent.MediaAction.STOP      -> CONSUMER_STOP
            })

            is InputEvent.VolumeCommand -> sendMomentaryConsumer(when (event.direction) {
                InputEvent.VolumeDirection.UP   -> CONSUMER_VOLUME_UP
                InputEvent.VolumeDirection.DOWN -> CONSUMER_VOLUME_DOWN
                InputEvent.VolumeDirection.MUTE -> CONSUMER_MUTE
            })

            InputEvent.LockScreen -> {
                // Win+L works on Windows; macOS would need Ctrl+Cmd+Q. Wi-Fi
                // mode has a proper "lock" message; HID is best-effort.
                hidController.pressKey(HidKeyCodes.L, HidModifierMask.LEFT_META)
                scope.launch {
                    delay(40)
                    hidController.releaseKey(HidKeyCodes.L, HidModifierMask.LEFT_META)
                }
            }

            // Clipboard sync and quick launchers require structured packets;
            // standard HID cannot express them. Silently ignored on Bluetooth.
            InputEvent.ClipboardGet,
            is InputEvent.ClipboardSet,
            is InputEvent.LaunchAction -> {
                Log.d(TAG, "Event ${event::class.simpleName} unsupported on Bluetooth")
            }
        }
    }

    private suspend fun typeAscii(text: String, baseMods: Int) {
        for (c in text) {
            val (code, needsShift) = asciiToHid(c)
            if (code == 0) continue   // Non-ASCII — silently drop.
            val mods = baseMods or (if (needsShift) HidModifierMask.LEFT_SHIFT else 0)
            hidController.pressKey(code, mods)
            // ~1 BLE connection interval. Faster and some hosts miss the press.
            delay(8)
            hidController.releaseKey(code, mods)
            delay(4)
        }
    }

    private fun sendMomentaryConsumer(usage: Int) {
        hidController.sendConsumer(usage)
        scope.launch {
            delay(40)
            hidController.releaseConsumer()
        }
    }

    private fun buttonBit(button: InputEvent.Button): Int = when (button) {
        InputEvent.Button.LEFT   -> 0x01
        InputEvent.Button.RIGHT  -> 0x02
        InputEvent.Button.MIDDLE -> 0x04
    }

    override suspend fun connect(target: ConnectionTarget) {
        val bt = target as? ConnectionTarget.Bluetooth ?: return
        _connectionState.value = ConnectionState.Connecting
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            _connectionState.value = ConnectionState.Error("Bluetooth unavailable")
            return
        }
        val device = try {
            adapter.getRemoteDevice(bt.address)
        } catch (e: IllegalArgumentException) {
            _connectionState.value = ConnectionState.Error("Invalid BT address: ${bt.address}")
            return
        }
        hidController.connect(device)
    }

    override fun disconnect() {
        currentButtons = 0
        dxRemainder = 0f
        dyRemainder = 0f
        hidController.releaseAllKeys()
        hidController.releaseConsumer()
        hidController.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Project [HidController.HidState] onto the transport-agnostic [ConnectionState]. */
    private fun updateState(hidState: HidController.HidState) {
        _connectionState.value = when (hidState) {
            HidController.HidState.Uninitialized,
            HidController.HidState.Ready          -> ConnectionState.Disconnected
            HidController.HidState.Connecting     -> ConnectionState.Connecting
            is HidController.HidState.Connected   ->
                ConnectionState.Connected(hidState.deviceName, ConnectionState.Transport.BLUETOOTH)
            is HidController.HidState.Error       -> ConnectionState.Error(hidState.message)
        }
    }

    private companion object {
        const val TAG = "BtInputDispatcher"
    }
}
