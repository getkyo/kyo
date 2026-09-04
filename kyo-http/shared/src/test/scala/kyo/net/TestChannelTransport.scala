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

/** Wraps a connection and records whether anything closed it. Delegation is total, so the wrapped connection behaves exactly as it would
  * unwrapped; the only added behaviour is the flag. Lives here rather than in a test file because `Connection.start` is `private[net]`.
  */
final class RecordingConnection(underlying: Connection) extends Connection:
    private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether `close()` has been called on this connection. */
    def wasClosed: Boolean = closed.get()

    def inbound: Channel.Unsafe[Span[Byte]]  = underlying.inbound
    def outbound: Channel.Unsafe[Span[Byte]] = underlying.outbound

    def isOpen(using AllowUnsafe): Boolean = underlying.isOpen

    def close()(using AllowUnsafe, Frame): Unit =
        closed.set(true)
        underlying.close()

    private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] = underlying.onClosing

    def detachForUpgrade()(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[Chunk[Span[Byte]]], Any] = underlying.detachForUpgrade()

    private[net] def start()(using AllowUnsafe, Frame): Boolean = underlying.start()

    def serverCertificateHash: Maybe[Span[Byte]] = underlying.serverCertificateHash

    def status: Connection.Status = underlying.status
end RecordingConnection

/** Test transport whose `connect` parks until the test releases it, so a test can settle the caller's own promise FIRST and then let the
  * connect succeed. That ordering is what an interrupted caller produces in production, and it cannot be staged with a transport that
  * hands back an already-completed fiber.
  */
final class DeferredConnectTransport(conn: Connection)(using AllowUnsafe) extends Transport:
    private val gate = Promise.Unsafe.init[Connection, Abort[NetException]]()

    /** Let the pending connect succeed with the prepared connection. */
    def release()(using AllowUnsafe, Frame): Unit = discard(gate.complete(Result.succeed(conn)))

    def connect(host: String, port: Int, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = gate

    private def unsupported[A](op: String)(using AllowUnsafe): Fiber.Unsafe[A, Abort[NetException]] =
        Fiber.Unsafe.fromResult(Result.panic(new UnsupportedOperationException(s"DeferredConnectTransport: $op not supported")))

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
end DeferredConnectTransport
