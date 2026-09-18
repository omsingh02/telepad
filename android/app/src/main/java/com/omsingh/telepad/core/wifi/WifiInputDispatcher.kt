package com.omsingh.telepad.core.wifi

import android.util.Log
import com.omsingh.telepad.core.crypto.NoiseSession
import com.omsingh.telepad.core.crypto.PairingStore
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Zero-allocation, Noise-encrypted UDP dispatcher for the Wi-Fi transport.
 *
 * Design constraints (from the audit):
 *  - One pre-allocated TX buffer + DatagramPacket — no per-event allocation.
 *  - Dedicated single-thread, max-priority executor — no contention with
 *    Dispatchers.IO or main.
 *  - `socket.connect()` to pin the kernel route table — saves ~3 µs per send.
 *  - Noise IK transport encryption — confidentiality + integrity + replay.
 *
 * Wire format (matches server protocol.h):
 *  - `[u8 WIRE_TRANSPORT][NoiseCipherState.encrypt(plaintext) | 16B Poly1305 tag]`
 *
 * Plaintext layout per event type (little-endian):
 *  - MouseMove:   `[01][i16 dx][i16 dy]`                       = 5 bytes
 *  - MouseButton: `[02][u8 button][u8 pressed]`                 = 3 bytes
 *  - Scroll:      `[03][i16 delta]`                             = 3 bytes
 *  - KeyPress:    `[04][u16 keycode][u8 modifiers]`             = 4 bytes
 *  - KeyRelease:  `[05][u16 keycode][u8 modifiers]`             = 4 bytes
 *  - TextInput:   `[06][u16 len][bytes...]`                     = 3 + N bytes
 *  - MediaCmd:    `[07][u8 action]`                             = 2 bytes
 *  - VolumeCmd:   `[08][u8 direction]`                          = 2 bytes
 *  - LockScreen:  `[09]`                                        = 1 byte
 *  - ClipboardGet:`[0E]`                                        = 1 byte
 *  - ClipboardSet:`[0F][u16 len][utf8...]`                      = 3 + N bytes
 *  - LaunchAction:`[10][u8 action]`                             = 2 bytes
 *  - NowPlayingQ: `[11]`                                        = 1 byte
 *
 * The server's reply for Get / NowPlayingQ flows back through `rxLoop`.
 */
class WifiInputDispatcher(
    private val pairingStore: PairingStore,
    /** Receives plaintext server→client packets (clipboard data, now-playing, etc.). */
    private val onServerPacket: (ByteArray, Int) -> Unit = { _, _ -> }
) : InputDispatcher {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rxJob: Job? = null

    // Single-thread, max-priority TX executor.
    private val txExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telepad-wifi-tx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }

    private val socketRef = AtomicReference<DatagramSocket?>(null)
    private val sessionRef = AtomicReference<NoiseSession?>(null)
    private var connectedHost: String? = null

    // Pre-allocated TX buffers — reused on every event.
    private val plain  = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
    private val cipher = ByteArray(2048)
    private val txBuf  = ByteArray(2048)
    private val txPacket = DatagramPacket(txBuf, 0)

    override fun dispatch(event: InputEvent) {
        val sock = socketRef.get() ?: return
        val session = sessionRef.get() ?: return
        // All TX work happens on the dedicated thread.
        txExecutor.execute { sendOnTxThread(sock, session, event) }
    }

    private fun sendOnTxThread(sock: DatagramSocket, session: NoiseSession, event: InputEvent) {
        plain.clear()
        when (event) {
            is InputEvent.MouseMove -> {
                plain.put(MSG_MOUSE_MOVE)
                plain.putShort(event.dx.toInt().coerceIn(-32767, 32767).toShort())
                plain.putShort(event.dy.toInt().coerceIn(-32767, 32767).toShort())
            }
            is InputEvent.MouseButton -> {
                plain.put(MSG_MOUSE_BUTTON)
                plain.put(event.button.ordinal.toByte())
                plain.put(if (event.pressed) 1 else 0)
            }
            is InputEvent.Scroll -> {
                plain.put(MSG_SCROLL)
                plain.putShort(event.delta.toInt().coerceIn(-32767, 32767).toShort())
            }
            InputEvent.Click -> {
                // Decompose into press + release frames so the server's HID
                // path treats them uniformly with explicit DragStart/DragEnd.
                emitButton(plain, InputEvent.Button.LEFT, true)
                flushPlain(sock, session)
                plain.clear()
                emitButton(plain, InputEvent.Button.LEFT, false)
            }
            InputEvent.DoubleClick -> {
                repeat(2) {
                    emitButton(plain, InputEvent.Button.LEFT, true)
                    flushPlain(sock, session); plain.clear()
                    emitButton(plain, InputEvent.Button.LEFT, false)
                    flushPlain(sock, session); plain.clear()
                }
                return  // already flushed
            }
            InputEvent.RightClick -> {
                emitButton(plain, InputEvent.Button.RIGHT, true)
                flushPlain(sock, session); plain.clear()
                emitButton(plain, InputEvent.Button.RIGHT, false)
            }
            is InputEvent.DragStart -> emitButton(plain, event.button, true)
            is InputEvent.DragEnd   -> emitButton(plain, event.button, false)
            is InputEvent.KeyPress -> {
                plain.put(MSG_KEY_PRESS)
                plain.putShort(event.keyCode.toShort())
                plain.put(event.modifiers.toHidByte().toByte())
            }
            is InputEvent.KeyRelease -> {
                plain.put(MSG_KEY_RELEASE)
                plain.putShort(event.keyCode.toShort())
                plain.put(event.modifiers.toHidByte().toByte())
            }
            is InputEvent.TextInput -> {
                val bytes = event.text.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_TEXT_BYTES) return
                plain.put(MSG_TEXT_INPUT)
                plain.putShort(bytes.size.toShort())
                plain.put(bytes)
            }
            is InputEvent.MediaCommand -> {
                plain.put(MSG_MEDIA_CMD); plain.put(event.action.ordinal.toByte())
            }
            is InputEvent.VolumeCommand -> {
                plain.put(MSG_VOLUME_CMD); plain.put(event.direction.ordinal.toByte())
            }
            InputEvent.LockScreen -> plain.put(MSG_LOCK_SCREEN)
            InputEvent.ClipboardGet -> plain.put(MSG_CLIPBOARD_GET)
            is InputEvent.ClipboardSet -> {
                val bytes = event.text.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_CLIPBOARD_BYTES) return
                plain.put(MSG_CLIPBOARD_SET)
                plain.putShort(bytes.size.toShort())
                plain.put(bytes)
            }
            is InputEvent.LaunchAction -> {
                plain.put(MSG_LAUNCH_ACTION); plain.put(event.action.ordinal.toByte())
            }
        }
        flushPlain(sock, session)
    }

    private fun emitButton(buf: ByteBuffer, button: InputEvent.Button, pressed: Boolean) {
        buf.put(MSG_MOUSE_BUTTON)
        buf.put(button.ordinal.toByte())
        buf.put(if (pressed) 1 else 0)
    }

    private fun flushPlain(sock: DatagramSocket, session: NoiseSession) {
        val ptLen = plain.position()
        if (ptLen == 0) return
        val ctLen = session.encrypt(plain.array(), 0, ptLen, cipher, 0)
        if (ctLen <= 0) return
        txBuf[0] = NoiseSession.WIRE_TRANSPORT
        System.arraycopy(cipher, 0, txBuf, 1, ctLen)
        txPacket.setData(txBuf, 0, 1 + ctLen)
        try { sock.send(txPacket) } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.message}")
        }
    }

    /**
     * Establish a Noise IK session with the server.
     *
     * If we don't already have a trusted pubkey for the host, the connection
     * succeeds only at the "raw bytes flow" level — the caller (PairingViewModel)
     * is responsible for fingerprint verification before calling [persistTrustAndContinue].
     */
    override suspend fun connect(target: ConnectionTarget) {
        val w = target as? ConnectionTarget.Wifi ?: return
        _connectionState.value = ConnectionState.Connecting
        withContext(Dispatchers.IO) {
            try {
                val sock = DatagramSocket().apply {
                    connect(InetSocketAddress(w.host, w.port))
                    receiveBufferSize = 256 * 1024
                }
                val session = NoiseSession(pairingStore.localStaticPrivateKey())
                if (!session.runHandshake(sock, w.pubkeyBase64)) {
                    sock.close()
                    _connectionState.value = ConnectionState.Error("Handshake failed")
                    return@withContext
                }
                sessionRef.set(session)
                socketRef.set(sock)
                connectedHost = w.host
                startRxLoop(sock, session)
                _connectionState.value =
                    ConnectionState.Connected(w.host, ConnectionState.Transport.WIFI)
                Log.i(TAG, "Connected to ${w.host}:${w.port}")
            } catch (t: Throwable) {
                Log.w(TAG, "connect failed", t)
                _connectionState.value = ConnectionState.Error(t.message ?: "Connect failed")
            }
        }
    }

    private fun startRxLoop(sock: DatagramSocket, session: NoiseSession) {
        rxJob = scope.launch {
            val rxBuf = ByteArray(2048)
            val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
            val plainBuf = ByteArray(2048)
            while (isActive) {
                try {
                    sock.receive(rxPacket)
                    if (rxPacket.length < 2) continue
                    if (rxBuf[0] != NoiseSession.WIRE_TRANSPORT) continue
                    val n = session.decrypt(rxBuf, 1, rxPacket.length - 1, plainBuf, 0)
                    if (n < 1) continue  // auth failure — silently drop.
                    onServerPacket(plainBuf, n)
                } catch (_: Throwable) {
                    // Likely socket closed during disconnect — loop will exit naturally.
                    break
                }
            }
        }
    }

    override fun disconnect() {
        rxJob?.cancel(); rxJob = null
        sessionRef.getAndSet(null)?.reset()
        try { socketRef.getAndSet(null)?.close() } catch (_: Exception) {}
        connectedHost = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun shutdown() {
        disconnect()
        txExecutor.shutdownNow()
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val TAG = "WifiDispatcher"
        const val MAX_TEXT_BYTES = 512
        const val MAX_CLIPBOARD_BYTES = 32 * 1024

        const val MSG_MOUSE_MOVE: Byte    = 0x01
        const val MSG_MOUSE_BUTTON: Byte  = 0x02
        const val MSG_SCROLL: Byte        = 0x03
        const val MSG_KEY_PRESS: Byte     = 0x04
        const val MSG_KEY_RELEASE: Byte   = 0x05
        const val MSG_TEXT_INPUT: Byte    = 0x06
        const val MSG_MEDIA_CMD: Byte     = 0x07
        const val MSG_VOLUME_CMD: Byte    = 0x08
        const val MSG_LOCK_SCREEN: Byte   = 0x09
        const val MSG_CLIPBOARD_GET: Byte = 0x0E
        const val MSG_CLIPBOARD_SET: Byte = 0x0F
        const val MSG_LAUNCH_ACTION: Byte = 0x10
        const val MSG_NOW_PLAYING_Q: Byte = 0x11
    }

    /** Helper for callers to send a NowPlaying query without constructing an InputEvent. */
    fun queryNowPlaying() {
        val sock = socketRef.get() ?: return
        val session = sessionRef.get() ?: return
        txExecutor.execute {
            plain.clear()
            plain.put(MSG_NOW_PLAYING_Q)
            flushPlain(sock, session)
        }
    }
}
