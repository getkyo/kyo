package kyo.net

import kyo.*

/** Test transport whose `connect` hands out pre-built connections in order (see
  * `kyo.net.internal.transport.Connection.inMemoryPair`), so a client backend runs its full pool/connect path against channel-backed
  * connections with no sockets. Every other operation panics. Lives in package kyo.net because `Transport.capabilities` is `private[net]`.
  */
final class TestChannelTransport(conns: Seq[Connection]) extends Transport:

    private val next = new java.util.concurrent.atomic.AtomicInteger(0)

    /** How many connections `connect` has handed out. */
    def connectCount: Int = next.get()

    def connect(host: String, port: Int, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] =
        val i = next.getAndIncrement()
        if i < conns.size then Fiber.Unsafe.fromResult(Result.succeed(conns(i)))
        else
            Fiber.Unsafe.fromResult(Result.panic(new IllegalStateException(
                s"TestChannelTransport exhausted: connect #${i + 1} requested but only ${conns.size} connections were prepared"
            )))
        end if
    end connect

    private def unsupported[A](op: String)(using AllowUnsafe): Fiber.Unsafe[A, Abort[NetException]] =
        Fiber.Unsafe.fromResult(Result.panic(new UnsupportedOperationException(s"TestChannelTransport: $op not supported")))

    def connectTls(host: String, port: Int, tls: NetTlsConfig, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = unsupported("connectTls")

    def connectUnix(path: String, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = unsupported("connectUnix")

    def stdio(channelCapacity: Int, readChunkSize: Int)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = unsupported("stdio")

    def listen(host: String, port: Int, backlog: Int, config: NetConfig)(handler: Connection => Unit)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Listener, Abort[NetException]] = unsupported("listen")

    def listenTls(host: String, port: Int, backlog: Int, tls: NetTlsConfig, config: NetConfig)(handler: Connection => Unit)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Listener, Abort[NetException]] = unsupported("listenTls")

    def listenUnix(path: String, backlog: Int, config: NetConfig)(handler: Connection => Unit)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Listener, Abort[NetException]] = unsupported("listenUnix")

    def upgradeToTls(conn: Connection, tls: NetTlsConfig, channelCapacity: Int)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = unsupported("upgradeToTls")

    private[net] def capabilities: TransportCapabilities = TransportCapabilities(Set.empty, unixSockets = false)

end TestChannelTransport
