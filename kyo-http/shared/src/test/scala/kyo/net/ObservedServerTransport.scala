package kyo.net

import kyo.*

/** Delegates every operation to `underlying` and records each connection a TCP listener accepts, so a test can watch what a server does to
  * its side of a connection: whether and when the server closed it, and the outbound channel the server writes to. The server is handed
  * the recording wrapper, whose delegation is total, so it behaves exactly as it would on the underlying transport. Lives in package
  * kyo.net because `Transport.capabilities` and `Connection.start` are `private[net]`.
  */
final class ObservedServerTransport(underlying: Transport) extends Transport:

    private val acceptedRef = new java.util.concurrent.atomic.AtomicReference[Chunk[ObservedConnection]](Chunk.empty)

    /** Every connection the listeners accepted so far, in accept order. */
    def accepted: Chunk[ObservedConnection] = acceptedRef.get()

    def connect(host: String, port: Int, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = underlying.connect(host, port, connectTimeout, config)

    def connectTls(host: String, port: Int, tls: NetTlsConfig, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = underlying.connectTls(host, port, tls, connectTimeout, config)

    def connectUnix(path: String, connectTimeout: Duration, config: NetConfig)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = underlying.connectUnix(path, connectTimeout, config)

    def stdio(channelCapacity: Int, readChunkSize: ByteSize)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = underlying.stdio(channelCapacity, readChunkSize)

    def listen(host: String, port: Int, backlog: Int, config: NetConfig)(handler: Connection => Unit)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Listener, Abort[NetException]] =
        underlying.listen(host, port, backlog, config) { conn =>
            val observed = new ObservedConnection(conn)
            discard(acceptedRef.updateAndGet(_.append(observed)))
            handler(observed)
        }

    def listenTls(host: String, port: Int, backlog: Int, tls: NetTlsConfig, config: NetConfig)(handler: Connection => Unit)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Listener, Abort[NetException]] = underlying.listenTls(host, port, backlog, tls, config)(handler)

    def listenUnix(path: String, backlog: Int, config: NetConfig)(handler: Connection => Unit)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Listener, Abort[NetException]] = underlying.listenUnix(path, backlog, config)(handler)

    def upgradeToTls(conn: Connection, tls: NetTlsConfig, channelCapacity: Int)(using
        AllowUnsafe,
        Frame
    ): Fiber.Unsafe[Connection, Abort[NetException]] = underlying.upgradeToTls(conn, tls, channelCapacity)

    private[net] def capabilities: TransportCapabilities = underlying.capabilities

end ObservedServerTransport

/** A connection that records the first `close()` called on it, as an event a test can await. */
final class ObservedConnection(underlying: Connection) extends Connection:
    private val closeCalled = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether the owner has called `close()`. */
    def wasClosed: Boolean = closeCalled.get()

    /** The write pump's state, when the underlying connection is a transport connection. */
    private[kyo] def writeState(using AllowUnsafe): Maybe[kyo.net.internal.transport.WriteState] =
        underlying match
            case conn: kyo.net.internal.transport.Connection[?] => Present(conn.writeState)
            case _                                              => Absent

    def inbound: Channel.Unsafe[Span[Byte]]  = underlying.inbound
    def outbound: Channel.Unsafe[Span[Byte]] = underlying.outbound

    def isOpen(using AllowUnsafe): Boolean = underlying.isOpen

    def close()(using AllowUnsafe, Frame): Unit =
        closeCalled.set(true)
        underlying.close()

    private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] = underlying.onClosing

    def detachForUpgrade()(using AllowUnsafe, Frame): Fiber.Unsafe[Maybe[Chunk[Span[Byte]]], Any] = underlying.detachForUpgrade()

    private[net] def start()(using AllowUnsafe, Frame): Boolean = underlying.start()

    def serverCertificateHash: Maybe[Span[Byte]] = underlying.serverCertificateHash

    def status: Connection.Status = underlying.status
end ObservedConnection
