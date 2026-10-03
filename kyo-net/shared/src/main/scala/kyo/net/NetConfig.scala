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
  *
  * The byte sizes are each a [[kyo.ByteSize]] and are narrowed where the transport uses them, as kyo-core's stream reads narrow theirs: zero
  * becomes one byte and a size beyond `Int.MaxValue` becomes `Int.MaxValue`, so no byte size is refused. `channelCapacity` is a
  * [[NetConfig.Size]], a count of one or more, and the grace is a [[NetConfig.Grace]] (positive or `Duration.Infinity`), so neither the
  * constructor nor `copy` can hold either value no connection could use. `Size(4)` checks a literal at compile time; a value known only at
  * runtime goes through `Size.init` or `Grace.init`, which fail with a [[NetConfigException]].
  */
case class NetConfig(
    channelCapacity: NetConfig.Size = NetConfig.DefaultChannelCapacity,
    readChunkSize: ByteSize = NetConfig.DefaultReadChunkSize,
    soRcvBuf: Maybe[ByteSize] = Absent,
    soSndBuf: Maybe[ByteSize] = Absent,
    peerCloseGrace: NetConfig.Grace = NetConfig.DefaultPeerCloseGrace
) derives CanEqual

object NetConfig:
    /** Default inbound/outbound pump channel depth. */
    val DefaultChannelCapacity: Size = Size(4)

    /** Default initial per-connection read buffer size. */
    val DefaultReadChunkSize: ByteSize = 8.kib

    /** `size` as the `Int` byte count a buffer or socket option takes: zero becomes one byte, and a size beyond `Int.MaxValue` becomes
      * `Int.MaxValue`, the rule kyo-core's stream reads apply to their own `ByteSize` buffers.
      */
    private[net] def bytesAtUse(size: ByteSize): Int = readBufferCapacity(size)

    /** Default peer-close grace window (see [[NetConfig.peerCloseGrace]]). */
    val DefaultPeerCloseGrace: Grace = 30.seconds

    /** The settings every operation applies when its caller passes none. */
    val default: NetConfig = NetConfig()

    /** A count of chunks a connection's pump channels hold: one or more. Zero would make every channel a rendezvous with nothing for the
      * pumps to stage into, so it is refused rather than held.
      */
    opaque type Size = Int

    object Size:
        /** The size for a literal `n`, checked at compile time: a literal below one, or an argument that is not a constant, does not
          * compile. A value known only at runtime goes through [[init]].
          */
        inline def apply(inline n: Int): Size =
            inline if n < 1 then compiletime.error("NetConfig.Size must be one or more")
            else n

        /** `n` as a size, or the [[NetConfigException]] refusing a value below one. */
        def init(n: Int)(using Frame): Result[NetConfigException, Size] = check("size", n)

        private[kyo] def check(setting: String, n: Int)(using Frame): Result[NetConfigException, Size] =
            if n >= 1 then Result.succeed(n)
            else Result.fail(NetConfigException(setting, n.toString, "one or more"))

        given CanEqual[Size, Size] = CanEqual.derived

        extension (self: Size) def value: Int = self
    end Size

    /** How long a connection may go without progress before it is released: positive, or `Duration.Infinity` for no limit. Zero would
      * release every such connection on the first check, so it is refused rather than held.
      */
    opaque type Grace = Duration

    object Grace:
        val unlimited: Grace = Duration.Infinity

        /** `d` as a grace window, or the [[NetConfigException]] refusing a zero duration. */
        def init(d: Duration)(using Frame): Result[NetConfigException, Grace] = check("grace", d)

        private[kyo] def check(setting: String, d: Duration)(using Frame): Result[NetConfigException, Grace] =
            if d > Duration.Zero then Result.succeed(d)
            else Result.fail(NetConfigException(setting, d.show, "positive, or Duration.Infinity for no limit"))

        given CanEqual[Grace, Grace] = CanEqual.derived

        extension (self: Grace) def duration: Duration = self
    end Grace
end NetConfig
