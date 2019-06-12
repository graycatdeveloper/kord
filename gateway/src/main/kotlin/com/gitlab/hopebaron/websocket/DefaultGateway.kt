package com.gitlab.hopebaron.websocket

import com.gitlab.hopebaron.websocket.handler.*
import com.gitlab.hopebaron.websocket.ratelimit.RateLimiter
import com.gitlab.hopebaron.websocket.retry.Retry
import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.features.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readText
import io.ktor.util.error
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val defaultGatewayLogger = KotlinLogging.logger { }

@UnstableDefault
@ObsoleteCoroutinesApi
@ExperimentalCoroutinesApi
class DefaultGateway(
        private val url: String,
        private val client: HttpClient,
        private val retry: Retry,
        private val rateLimiter: RateLimiter
) : Gateway {

    private val channel = BroadcastChannel<Event>(Channel.CONFLATED)

    override val events: Flow<Event> = channel.asFlow()

    private lateinit var socket: DefaultClientWebSocketSession

    private val restart = atomic(true)

    private val handshakeHandler: HandshakeHandler

    init {
        val sequence = Sequence()
        SequenceHandler(events, sequence)
        handshakeHandler = HandshakeHandler(events, ::send, sequence)
        HeartbeatHandler(events, ::send, { restart() }, sequence)
        ReconnectHandler(events) { restart() }
        InvalidSessionHandler(events) { restart(it) }
    }

    override suspend fun start(configuration: GatewayConfiguration) {
        handshakeHandler.configuration = configuration
        retry.reset()
        while (retry.hasNext && restart.value) {
            var error: Throwable? = null

            try {
                socket = webSocket(url)
                retry.reset() //successfully connected, reset retry token
                readSocket()
                if (socket.closeReason.isCompleted) { //did Discord close the socket?
                    val reason = socket.closeReason.await()

                    val webSocketReason = reason?.knownReason
                    if (webSocketReason != null) { //discord didn't throw an error, we'll just restart
                        defaultGatewayLogger.info { "Gateway closed: ${webSocketReason.code} ${webSocketReason.name}, retrying connection" }
                        restart.update { true }
                    } else { //discord did throw an error

                        //find out if it's our fault or the user's
                        val discordReason = GatewayCloseCode.values().firstOrNull { it.code == reason?.code }
                        if (discordReason?.resetSession == true) channel.send(SessionClose)
                        if (discordReason?.retry == true) restart.update { true }
                        else {
                            restart.update { false }
                            error = IllegalStateException("Gateway closed: ${reason?.code} ${reason?.message}")
                        }
                    }
                }
            } catch (exception: Exception) {
                defaultGatewayLogger.error(exception)
            }

            if (error != null) throw error

            //only suspend when we're retrying
            //TODO don't want to retry when we're manually restarting, figure out a way to know the difference
            if (restart.value) retry.retry()
        }

        if (!retry.hasNext) {
            defaultGatewayLogger.warn { "retry limit exceeded, gateway closing" }
        }
    }

    private suspend fun readSocket() {
        socket.incoming.asFlow().filterIsInstance<Frame.Text>().collect { frame ->

            val json = frame.readText()
            defaultGatewayLogger.trace { "Gateway <<< $json" }

            Json.nonstrict.parse(Event.Companion, json)?.let { channel.send(it) }
        }
    }

    private fun <T> ReceiveChannel<T>.asFlow() = flow {
        val iterator = iterator()
        try {
            while (iterator.hasNext()) emit(iterator.next())
        } catch (ignore: CancellationException) {
            //reading was stopped from somewhere else, ignore
        }
    }

    private suspend fun webSocket(url: String) = client.webSocketSession(HttpMethod.Get, host = url) {
        this.url.protocol = URLProtocol.WSS
        this.url.port = 443
    }

    override suspend fun close() {
        channel.send(SessionClose)
        if (socketOpen) socket.close(CloseReason(1000, "leaving"))
        restart.update { false }
    }

    internal suspend fun restart(code: Close = CloseForReconnect) {
        restart.update { true }
        if (socketOpen) {
            channel.send(code)
            socket.close(CloseReason(1000, "reconnecting"))
        }
    }

    override suspend fun send(command: Command) {
        if (!socketOpen) error("call 'start' before sending messages")
        rateLimiter.consume()
        val json = Json.stringify(Command.Companion, command)
        if (command is Identify) defaultGatewayLogger.trace { "Gateway >>> Identify" }
        else defaultGatewayLogger.trace { "Gateway >>> $json" }
        socket.send(Frame.Text(json))
    }

    private val socketOpen get() = ::socket.isInitialized && !socket.outgoing.isClosedForSend && !socket.incoming.isClosedForReceive
}

internal val GatewayConfiguration.identify get() = Identify(token, IdentifyProperties(os, name, name), false, 50, shard, presence)

internal val os: String get() = System.getProperty("os.name")

/**
 * Enum representation of https://discordapp.com/developers/docs/topics/opcodes-and-status-codes#gateway-gateway-close-event-codes
 *
 * @param retry Whether the error is caused by the user or by Kord.
 * If we caused it, we should consider restarting the gateway.
 */
internal enum class GatewayCloseCode(val code: Short, val retry: Boolean = true, val resetSession: Boolean = false) {
    /**
     * ¯\_(ツ)_/¯
     */
    Unknown(4000),

    /**
     * We're sending the wrong opCode, this shouldn't happen unless we seriously broke something.
     */
    UnknownOpCode(4001),

    /**
     * We're sending malformed data, this shouldn't happen unless we seriously broke something.
     */
    DecodeError(4002),

    /**
     * We're sending data without starting a session, this shouldn't happen unless we seriously broke something.
     */
    NotAuthenticated(4003),

    /**
     * User send wrong token.
     */
    AuthenticationFailed(4004, false),

    /**
     * We're identifying more than once, this shouldn't happen unless we seriously broke something.
     */
    AlreadyAuthenticated(4005),

    /**
     * Send wrong sequence, restart and reset sequence number.
     */
    InvalidSeq(4007, true, true),

    /**
     * We're sending too fast, this means the user passed a wrongly configured rate limiter, we'll just ignore that though.
     */
    RateLimited(4008),

    /**
     * Timeout, Heartbeat handling is probably at fault, restart and reset sequence number.
     */
    SessionTimeout(4009, true, resetSession = true),

    /**
     * User supplied the wrong sharding info, we can't fix this on our end so we'll just stop.
     */
    InvalidShard(4010),

    /**
     * User didn't supply sharding info when it was required, we can't fix this on our end so we'll just stop.
     */
    ShardingRequired(4011)
}