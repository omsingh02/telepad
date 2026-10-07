package com.omsingh.telepad.core.wifi

import android.util.Log
import com.omsingh.telepad.core.clipboard.MAX_CLIPBOARD_UTF8_BYTES
import com.omsingh.telepad.core.connection.LivenessMonitor
import com.omsingh.telepad.core.connection.ReconnectPolicy
import com.omsingh.telepad.core.crypto.ClientIdentity
import com.omsingh.telepad.core.crypto.HandshakeResult
import com.omsingh.telepad.core.crypto.NoiseSession
import com.omsingh.telepad.core.host.HostInfo
import com.omsingh.telepad.core.host.HostInfoCodec
import com.omsingh.telepad.core.input.ConnectionState
import com.omsingh.telepad.core.input.ConnectionTarget
import com.omsingh.telepad.core.input.FailureReason
import com.omsingh.telepad.core.input.InputDispatcher
import com.omsingh.telepad.core.input.InputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Wi-Fi transport: Noise-encrypted UDP to the PC, kept alive and repaired.
 *
 * **Sending** is the hot path. Events are encoded on one dedicated, high-priority
 * thread into pre-allocated buffers, so a burst of touch events never contends with
 * the UI or allocates much.
 *
 * **Staying connected** is what UDP does not do by itself. Every couple of seconds
 * the PC is asked a tiny question (its host info); the answers prove it is still
 * there and measure the round trip ([LivenessMonitor]). When they stop, the link is
 * rebuilt in the background with growing pauses ([ReconnectPolicy]), and the UI shows
 * *Reconnecting* instead of a stale *Connected*. This also keeps the PC's session
 * alive: the server forgets a phone that has been quiet for a minute.
 *
 * Wire format of what is sent, once decrypted (little-endian):
 *
 *  - MouseMove    `[01][i16 dx][i16 dy]`
 *  - MouseButton  `[02][u8 button][u8 pressed]`
 *  - Scroll       `[03][i16 notches]`
 *  - KeyPress     `[04][u16 usage][u8 modifiers]`
 *  - KeyRelease   `[05][u16 usage][u8 modifiers]`
 *  - TextInput    `[06][u16 length][utf-8]`
 *  - MediaCmd     `[07][u8 action]`
 *  - VolumeCmd    `[08][u8 direction]`
 *  - LockScreen   `[09]`
 *  - ClipboardGet `[0E]`
 *  - ClipboardSet `[0F][u16 length][utf-8]`
 *  - LaunchAction `[10][u8 action]`
 *  - NowPlayingQ  `[11]`
 *  - HostInfoQ    `[12]`
 */
class WifiInputDispatcher(
    private val identity: ClientIdentity,
    /** Called on every new socket, e.g. to bind it to the Wi-Fi network. */
    private val prepareSocket: (DatagramSocket) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val timing: Timing = Timing(),
    private val newMonitor: () -> LivenessMonitor = { LivenessMonitor() },
    /** Receives everything the PC sends except host info, which is handled here. */
    private val onServerMessage: (ServerMessage) -> Unit = {},
) : InputDispatcher {

    /** How long to wait at each step. Tests use much shorter ones. */
    data class Timing(
        val firstAttemptMs: Int = 1_200,
        val secondAttemptMs: Int = 1_500,
        val reconnectAttemptMs: Int = 2_000,
        val tickMs: Long = 500,
        val diagnosisAttempts: Int = 2,
        val diagnosisTimeoutMs: Int = 400,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _hostInfo = MutableStateFlow(HostInfo.UNKNOWN)

    /** What the connected PC says about itself; [HostInfo.UNKNOWN] until it answers. */
    val hostInfo: StateFlow<HostInfo> = _hostInfo.asStateFlow()

    /** One established, encrypted conversation with the PC. */
    private class Link(
        val socket: DatagramSocket,
        val session: NoiseSession,
        val target: ConnectionTarget.Wifi,
        val monitor: LivenessMonitor,
    ) {
        @Volatile var closed = false

        fun close() {
            closed = true
            try { socket.close() } catch (_: Exception) {}
            session.reset()
        }
    }

    private val lock = Any()
    @Volatile private var link: Link? = null
    private var target: ConnectionTarget.Wifi? = null
    private var linkJobs: List<Job> = emptyList()
    private var reconnectJob: Job? = null

    /** Bumped by every connect and disconnect, so work for an older request knows to stop. */
    private val generation = AtomicInteger()

    // Single-thread, max-priority TX executor.
    private val txExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "telepad-wifi-tx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }

    // Pre-allocated TX buffers, only ever touched on the TX thread.
    private val plain = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
    private val cipher = ByteArray(2048)
    private val txBuf = ByteArray(2048)
    private val txPacket = DatagramPacket(txBuf, 0)

    // ── Sending ──────────────────────────────────────────────────────

    override fun dispatch(event: InputEvent) {
        val current = link ?: return
        // An exception escaping a task on the TX thread would reach the default
        // handler and kill the app, so a bad event is dropped instead. (Only the
        // event type is logged: events can carry typed text and clipboard contents.)
        txExecutor.execute {
            try {
                sendOnTxThread(current, event)
            } catch (t: Throwable) {
                Log.w(TAG, "dropped ${event::class.simpleName}: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun sendOnTxThread(link: Link, event: InputEvent) {
        if (link.closed) return
        plain.clear()
        when (event) {
            is InputEvent.MouseMove -> {
                plain.put(MSG_MOUSE_MOVE)
                plain.putShort(event.dx.toInt().coerceIn(-32767, 32767).toShort())
                plain.putShort(event.dy.toInt().coerceIn(-32767, 32767).toShort())
            }
            is InputEvent.MouseButton -> emitButton(event.button, event.pressed)
            is InputEvent.Scroll -> {
                plain.put(MSG_SCROLL)
                plain.putShort(event.delta.toInt().coerceIn(-32767, 32767).toShort())
            }
            InputEvent.Click -> {
                // Press and release are separate frames so the PC treats them just
                // like the explicit presses of a drag.
                emitButton(InputEvent.Button.LEFT, true)
                flushPlain(link)
                plain.clear()
                emitButton(InputEvent.Button.LEFT, false)
            }
            InputEvent.RightClick -> {
                emitButton(InputEvent.Button.RIGHT, true)
                flushPlain(link)
                plain.clear()
                emitButton(InputEvent.Button.RIGHT, false)
            }
            is InputEvent.DragStart -> emitButton(event.button, true)
            is InputEvent.DragEnd -> emitButton(event.button, false)
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
                if (!putText(MSG_TEXT_INPUT, event.text, MAX_TEXT_BYTES)) return
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
                if (!putText(MSG_CLIPBOARD_SET, event.text, MAX_CLIPBOARD_BYTES)) return
            }
            is InputEvent.LaunchAction -> {
                plain.put(MSG_LAUNCH_ACTION); plain.put(event.action.ordinal.toByte())
            }
        }
        flushPlain(link)
    }

    /** Writes `[tag][u16 length][utf-8]`, or returns false if the text is too long to send. */
    private fun putText(tag: Byte, text: String, maxBytes: Int): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) return false
        plain.put(tag)
        plain.putShort(bytes.size.toShort())
        plain.put(bytes)
        return true
    }

    private fun emitButton(button: InputEvent.Button, pressed: Boolean) {
        plain.put(MSG_MOUSE_BUTTON)
        plain.put(button.ordinal.toByte())
        plain.put(if (pressed) 1 else 0)
    }

    private fun flushPlain(link: Link) {
        val ptLen = plain.position()
        if (ptLen == 0) return
        val nonce = link.session.encrypt(plain.array(), 0, ptLen, cipher, 0)
        if (nonce < 0L) return
        val ctLen = ptLen + 16
        txBuf[0] = NoiseSession.WIRE_TRANSPORT
        for (i in 0 until 8) {
            txBuf[1 + i] = ((nonce ushr (i * 8)) and 0xFFL).toByte()
        }
        System.arraycopy(cipher, 0, txBuf, 9, ctLen)
        txPacket.setData(txBuf, 0, 9 + ctLen)
        try {
            link.socket.send(txPacket)
        } catch (t: Throwable) {
            Log.w(TAG, "send failed: ${t.javaClass.simpleName}")
        }
    }

    private fun sendSingleByte(link: Link, type: Byte) {
        txExecutor.execute {
            try {
                if (link.closed) return@execute
                plain.clear()
                plain.put(type)
                flushPlain(link)
            } catch (t: Throwable) {
                Log.w(TAG, "query failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Asks the PC what is playing. The answer arrives through [onServerMessage]. */
    fun queryNowPlaying() {
        link?.let { sendSingleByte(it, MSG_NOW_PLAYING_Q) }
    }

    // ── Connecting ───────────────────────────────────────────────────

    private sealed interface Attempt {
        class Established(val link: Link) : Attempt
        data object NotPaired : Attempt
        data object Silent : Attempt
        data object Broken : Attempt
    }

    private fun attemptOnce(w: ConnectionTarget.Wifi, timeoutMs: Int, pairingToken: ByteArray? = null): Attempt {
        // A fresh socket for every attempt, so a late reply to one cannot be mistaken
        // for the answer to the next.
        val socket = DatagramSocket()
        try {
            prepareSocket(socket)
            socket.connect(InetSocketAddress(w.host, w.port))
            socket.receiveBufferSize = 256 * 1024
            val session = NoiseSession(identity.localStaticPrivateKey())
            return when (session.runHandshake(socket, w.pubkeyBase64, timeoutMs, pairingToken)) {
                HandshakeResult.ESTABLISHED ->
                    Attempt.Established(Link(socket, session, w, newMonitor()))
                HandshakeResult.NOT_PAIRED -> { socket.close(); Attempt.NotPaired }
                HandshakeResult.NO_REPLY -> { socket.close(); Attempt.Silent }
                HandshakeResult.FAILED -> { socket.close(); Attempt.Broken }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "attempt failed: ${t.javaClass.simpleName}: ${t.message}")
            try { socket.close() } catch (_: Exception) {}
            return Attempt.Broken
        }
    }

    override suspend fun connect(target: ConnectionTarget) {
        (target as? ConnectionTarget.Wifi)?.let { connectTo(it) }
    }

    /** How a [connectTo] request ended. */
    sealed interface ConnectOutcome {
        data object Connected : ConnectOutcome
        data class Failed(val reason: FailureReason) : ConnectOutcome

        /** A newer connect or a disconnect replaced this request before it finished. */
        data object Superseded : ConnectOutcome
    }

    /**
     * Connects to [w] and reports how it went. Callers that need to act on the result
     * (save the PC as paired, show guidance) use this; [connect] is the same without it.
     *
     * [pairingToken] is the one-time token from a QR code. It is used for this connect only and never kept
     * with the target: once the PC has paired the phone, it is not needed again.
     */
    suspend fun connectTo(w: ConnectionTarget.Wifi, pairingToken: ByteArray? = null): ConnectOutcome {
        if (this.target == w && _connectionState.value is ConnectionState.Connected) return ConnectOutcome.Connected

        val mine = begin(w)
        _connectionState.value = ConnectionState.Connecting(w.name, ConnectionState.Transport.WIFI)

        // Not cancellable: the attempt owns a socket that must end up either live or closed,
        // and the bookkeeping below must run even if the caller has given up waiting.
        val result = withContext(Dispatchers.IO + NonCancellable) {
            var attempt = attemptOnce(w, timing.firstAttemptMs, pairingToken)
            if (attempt is Attempt.Silent && stillWanted(mine)) {
                attempt = attemptOnce(w, timing.secondAttemptMs, pairingToken)
            }
            if (attempt is Attempt.Silent && stillWanted(mine)) {
                ConnectResult(attempt, diagnose(w))
            } else {
                ConnectResult(attempt, null)
            }
        }

        if (!stillWanted(mine)) {
            // The user connected elsewhere, or disconnected, while this was in flight.
            (result.attempt as? Attempt.Established)?.link?.close()
            return ConnectOutcome.Superseded
        }
        return when (val attempt = result.attempt) {
            is Attempt.Established -> {
                onEstablished(attempt.link, mine)
                ConnectOutcome.Connected
            }
            Attempt.NotPaired -> failed(w, FailureReason.NOT_PAIRED)
            Attempt.Silent, Attempt.Broken -> failed(w, result.reason ?: FailureReason.UNREACHABLE)
        }
    }

    private fun failed(w: ConnectionTarget.Wifi, reason: FailureReason): ConnectOutcome {
        fail(w, reason)
        return ConnectOutcome.Failed(reason)
    }

    private class ConnectResult(val attempt: Attempt, val reason: FailureReason?)

    /**
     * After a handshake gets no answer: does the PC answer anything at all? A PC that
     * hands over a *different* public key than the one we hold has been reinstalled or
     * replaced, so our handshake to the old key was rightly ignored. A PC that answers
     * with our key but not the handshake is a connection problem. No answer is simply
     * unreachable.
     */
    private suspend fun diagnose(w: ConnectionTarget.Wifi): FailureReason {
        val key = PairingIntro.fetchPublicKey(
            w.host, w.port,
            attempts = timing.diagnosisAttempts,
            timeoutPerAttemptMs = timing.diagnosisTimeoutMs,
            prepare = prepareSocket,
        )
        return when {
            key == null -> FailureReason.UNREACHABLE
            Base64.getEncoder().encodeToString(key) == w.pubkeyBase64 -> FailureReason.HANDSHAKE_FAILED
            else -> FailureReason.KEY_CHANGED
        }
    }

    private fun begin(w: ConnectionTarget.Wifi): Int {
        synchronized(lock) {
            val mine = generation.incrementAndGet()
            teardownLocked()
            target = w
            _hostInfo.value = HostInfo.UNKNOWN
            return mine
        }
    }

    private fun stillWanted(mine: Int) = generation.get() == mine

    private fun fail(w: ConnectionTarget.Wifi, reason: FailureReason) {
        _connectionState.value = ConnectionState.Failed(w.name, ConnectionState.Transport.WIFI, reason)
    }

    private fun onEstablished(established: Link, mine: Int) {
        synchronized(lock) {
            if (!stillWanted(mine)) {
                established.close()
                return
            }
            established.monitor.reset(nowMs())
            link = established
            linkJobs = listOf(scope.launch { receiveLoop(established) }, scope.launch { heartbeatLoop(established, mine) })
        }
        publish(established)
        Log.i(TAG, "Connected to ${established.target.host}:${established.target.port}")
    }

    // ── Receiving ────────────────────────────────────────────────────

    private suspend fun receiveLoop(link: Link) = withContext(Dispatchers.IO) {
        val rxBuf = ByteArray(2048)
        val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
        val plainBuf = ByteArray(2048)
        var consecutiveErrors = 0
        while (isActive && !link.closed) {
            try {
                // Offer the whole buffer every time, so a short datagram can never limit
                // what the next receive accepts.
                rxPacket.setLength(rxBuf.size)
                link.socket.receive(rxPacket)
                consecutiveErrors = 0
                if (rxPacket.length < MIN_TRANSPORT_LENGTH || rxBuf[0] != NoiseSession.WIRE_TRANSPORT) continue
                var nonce = 0L
                for (i in 0 until 8) {
                    nonce = nonce or ((rxBuf[1 + i].toLong() and 0xFFL) shl (i * 8))
                }
                val n = link.session.decrypt(nonce, rxBuf, 9, rxPacket.length - 9, plainBuf, 0)
                if (n < 1) continue // authentication failure or replay: drop silently
                handleDecrypted(link, plainBuf, n)
            } catch (_: SocketTimeoutException) {
                // No timeout is set; harmless if a platform imposes one.
            } catch (_: PortUnreachableException) {
                // The PC's port answered with "nobody home" (ICMP): it may be restarting.
                // The heartbeat decides whether the link is truly gone.
            } catch (t: Throwable) {
                if (link.closed || link.socket.isClosed) break
                if (++consecutiveErrors > MAX_RX_ERRORS) break
                delay(RX_ERROR_PAUSE_MS)
            }
        }
    }

    private fun handleDecrypted(link: Link, buf: ByteArray, len: Int) {
        val now = nowMs()
        val message = ServerMessages.parse(buf, len, System.currentTimeMillis())
        when {
            message is ServerMessage.Host -> {
                link.monitor.onPong(now)
                _hostInfo.value = message.info
                publish(link)
            }
            message != null -> {
                link.monitor.onPacket(now)
                onServerMessage(message)
            }
            // Authenticated, just not something this version understands: still proof of life.
            else -> link.monitor.onPacket(now)
        }
    }

    // ── Staying connected ────────────────────────────────────────────

    private suspend fun heartbeatLoop(link: Link, mine: Int) {
        while (!link.closed && stillWanted(mine)) {
            val now = nowMs()
            if (link.monitor.shouldPing(now)) {
                link.monitor.onPingSent(now)
                sendSingleByte(link, HostInfoCodec.MSG_HOST_INFO_QUERY)
            }
            publish(link)
            if (link.monitor.health(now) == LivenessMonitor.Health.LOST) {
                onLost(link, mine)
                return
            }
            delay(timing.tickMs)
        }
    }

    /** Shows the current latency and link quality, if this is still the live link. */
    private fun publish(link: Link) {
        if (this.link !== link || link.closed) return
        val now = nowMs()
        val state = ConnectionState.Connected(
            deviceName = link.target.name,
            transport = ConnectionState.Transport.WIFI,
            latencyMs = link.monitor.latencyMs,
            unstable = link.monitor.health(now) != LivenessMonitor.Health.HEALTHY,
        )
        if (_connectionState.value != state) _connectionState.value = state
    }

    private fun onLost(lost: Link, mine: Int) {
        val w: ConnectionTarget.Wifi
        synchronized(lock) {
            if (link !== lost || !stillWanted(mine)) return
            link = null
            w = lost.target
            lost.close()
            reconnectJob = scope.launch { reconnectLoop(w, mine) }
        }
        Log.w(TAG, "Lost the PC; reconnecting")
    }

    private suspend fun reconnectLoop(w: ConnectionTarget.Wifi, mine: Int) {
        val lostAt = nowMs()
        var attempt = 1
        while (stillWanted(mine)) {
            if (reconnectPolicy.shouldGiveUp(nowMs() - lostAt)) {
                fail(w, FailureReason.CONNECTION_LOST)
                return
            }
            _connectionState.value = ConnectionState.Reconnecting(w.name, ConnectionState.Transport.WIFI, attempt)
            delay(reconnectPolicy.delayBeforeAttempt(attempt))
            if (!stillWanted(mine)) return

            when (val outcome = withContext(Dispatchers.IO) { attemptOnce(w, timing.reconnectAttemptMs) }) {
                is Attempt.Established -> {
                    if (!stillWanted(mine)) {
                        outcome.link.close()
                        return
                    }
                    onEstablished(outcome.link, mine)
                    return
                }
                Attempt.NotPaired -> {
                    fail(w, FailureReason.NOT_PAIRED)
                    return
                }
                Attempt.Silent, Attempt.Broken -> attempt++
            }
        }
    }

    /**
     * Checks right now that the PC still answers, instead of waiting for the next
     * heartbeat to notice. Call when the app returns to the foreground or the phone
     * changes network: if there is no answer within [graceMs] the link is rebuilt.
     */
    fun verifyNow(graceMs: Long = 1_500) {
        val current = link ?: return
        val mine = generation.get()
        scope.launch {
            val asked = nowMs()
            current.monitor.onPingSent(asked)
            sendSingleByte(current, HostInfoCodec.MSG_HOST_INFO_QUERY)
            delay(graceMs)
            // Anything heard since the question was asked means the PC is there.
            if (link === current && !current.monitor.heardSince(asked)) onLost(current, mine)
        }
    }

    // ── Ending ───────────────────────────────────────────────────────

    override fun disconnect() {
        synchronized(lock) {
            generation.incrementAndGet()
            teardownLocked()
            target = null
        }
        _hostInfo.value = HostInfo.UNKNOWN
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun teardownLocked() {
        reconnectJob?.cancel(); reconnectJob = null
        linkJobs.forEach { it.cancel() }; linkJobs = emptyList()
        link?.close()
        link = null
    }

    fun shutdown() {
        disconnect()
        txExecutor.shutdownNow()
        scope.cancel()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val TAG = "WifiDispatcher"
        const val MAX_TEXT_BYTES = 512

        /** Largest clipboard payload sent to the PC; see [MAX_CLIPBOARD_UTF8_BYTES]. */
        const val MAX_CLIPBOARD_BYTES = MAX_CLIPBOARD_UTF8_BYTES

        /** Wire tag, 8 byte nonce, and at least a 1 byte message plus the 16 byte tag. */
        const val MIN_TRANSPORT_LENGTH = 1 + 8 + 1 + 16

        const val MAX_RX_ERRORS = 20
        const val RX_ERROR_PAUSE_MS = 50L

        const val MSG_MOUSE_MOVE: Byte = 0x01
        const val MSG_MOUSE_BUTTON: Byte = 0x02
        const val MSG_SCROLL: Byte = 0x03
        const val MSG_KEY_PRESS: Byte = 0x04
        const val MSG_KEY_RELEASE: Byte = 0x05
        const val MSG_TEXT_INPUT: Byte = 0x06
        const val MSG_MEDIA_CMD: Byte = 0x07
        const val MSG_VOLUME_CMD: Byte = 0x08
        const val MSG_LOCK_SCREEN: Byte = 0x09
        const val MSG_CLIPBOARD_GET: Byte = 0x0E
        const val MSG_CLIPBOARD_SET: Byte = 0x0F
        const val MSG_LAUNCH_ACTION: Byte = 0x10
        const val MSG_NOW_PLAYING_Q: Byte = 0x11
    }
}
