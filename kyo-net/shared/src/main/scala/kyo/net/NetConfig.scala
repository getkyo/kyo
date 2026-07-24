package kyo.net

import kyo.*

/** The shape of the connection and socket a [[Transport]] operation produces, passed to each operation alongside the address it acts on.
  *
  * Nothing here configures a transport. Every field applies to a single connection or a single socket, which is why it travels with the call
  * rather than being fixed when the transport is built: that is what lets one process-wide transport serve callers wanting different buffer
  * sizes. The two deadlines live where they apply rather than here, since a bag whose fields are inert for half its call sites is a bag that
  * quietly drops settings: a connect deadline is the `connectTimeout` parameter of the connect operations, and a TLS handshake deadline is
  * [[NetTlsConfig.handshakeTimeout]], carried by the config every handshaking operation already takes. The one genuinely process-wide setting,
  * the driver count, is the `kyo.net.ioPoolSize` flag.
  *
  * All parameters have production-ready defaults. Override only when profiling reveals a bottleneck or when deploying on hardware with
  * unusual memory or CPU characteristics.
  *
  *   - `channelCapacity`: number of in-flight chunks that the inbound/outbound pump channels can buffer before backpressure kicks in.
  *     Increasing this trades memory for throughput on high-latency connections.
  *   - `readChunkSize`: size of the read buffer a connection starts with (bytes). Larger values reduce system-call overhead on bulk transfers
  *     at the cost of higher per-connection memory usage. It seeds a buffer that then adapts to the traffic it sees. Applies where the
  *     transport owns its read buffer, which is the posix and NIO backends; the Node backend has no read buffer of its own, since Node
  *     delivers chunks it sizes itself, so the value has nothing to act on there.
  *   - `soRcvBuf`: when `Present(n)`, sets `SO_RCVBUF` to `n` bytes on the connect socket, the listen socket, and each accepted socket.
  *     `Absent` (the default) leaves the kernel's default unchanged. Node exposes no socket-buffer API, so on JS a `Present` value fails the
  *     operation with [[NetSocketOptionUnsupportedException]] rather than being silently ignored.
  *   - `soSndBuf`: same as `soRcvBuf` for `SO_SNDBUF` (the send buffer). It shapes the sockets that actually send, the connect socket and each
  *     accepted socket. The posix backends also set it on the listen socket before `bind`, because an accepted socket inherits its buffer sizes
  *     from the listener it came from; NIO instead skips it there, a `ServerSocketChannel` rejecting the option outright.
  *   - `peerCloseGrace`: the window a connection whose peer has closed (FIN) is given to make read progress before it is reclaimed, when its
  *     inbound side is backpressured (no read is armed, so the peer FIN is otherwise unobservable). Any drained span resets the window; only a full
  *     window with zero progress reclaims the descriptor. `Duration.Infinity` disables reclamation (the pre-guard behavior).
  */
case class NetConfig(
    channelCapacity: Int = NetConfig.DefaultChannelCapacity,
    readChunkSize: Int = NetConfig.DefaultReadChunkSize,
    soRcvBuf: Maybe[Int] = Absent,
    soSndBuf: Maybe[Int] = Absent,
    peerCloseGrace: Duration = NetConfig.DefaultPeerCloseGrace
) derives CanEqual:
    require(channelCapacity > 0, s"channelCapacity must be positive: $channelCapacity")
    require(readChunkSize > 0, s"readChunkSize must be positive: $readChunkSize")
    soRcvBuf.foreach(n => require(n > 0, s"soRcvBuf must be positive: $n"))
    soSndBuf.foreach(n => require(n > 0, s"soSndBuf must be positive: $n"))
    require(peerCloseGrace > Duration.Zero, s"peerCloseGrace must be positive (or Duration.Infinity to disable): $peerCloseGrace")
end NetConfig

object NetConfig:
    /** Default inbound/outbound pump channel depth. */
    val DefaultChannelCapacity: Int = 4

    /** Default initial per-connection read buffer size, in bytes. */
    val DefaultReadChunkSize: Int = 8192

    /** Default peer-close grace window (see [[NetConfig.peerCloseGrace]]). */
    val DefaultPeerCloseGrace: Duration = 30.seconds

    /** The settings every operation applies when its caller passes none. */
    val default: NetConfig = NetConfig()
end NetConfig
