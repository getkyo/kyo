package kyo.net

import kyo.*

/** The platform-default network transport: the entry point for opening TCP and Unix-domain connections, accepting them through a listener, and
  * upgrading a plaintext connection to TLS.
  *
  * One implementation is selected per platform from the capability-probed backend registry: the posix transport over io_uring/epoll/kqueue on a
  * JVM or Native posix host, the pure-JDK NIO floor on a JVM with no usable posix syscall, and the Node.js net module on JS. The selected
  * implementation is reached through [[kyo.net.NetPlatform.transport]]; this abstract is the surface those implementations share, so a caller
  * writes against one API regardless of which floor the host runs.
  *
  *   - `connect` / `connectUnix`: open a client connection (optionally TLS) to a TCP host or a Unix socket path.
  *   - `listen` / `listenUnix`: bind a [[Listener]] and run a handler per accepted connection (optionally terminating TLS).
  *   - `upgradeToTls`: turn an already-open plaintext connection into a TLS one (STARTTLS-style), where the platform supports it.
  *
  * WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. Every operation returns a `Fiber.Unsafe` and
  * requires an `AllowUnsafe`; see AllowUnsafe for the safety contract. The caller owns the lifecycle of every connection and listener it opens.
  *
  * @see
  *   [[kyo.net.NetPlatform.transport]] for the platform-default instance.
  */
abstract class Transport:
    /** Connect to a remote TCP host. Returns a fiber that completes with an open connection. The caller owns the connection lifecycle. */
    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]]

    /** Connect to a remote TCP host with TLS. Returns a fiber that completes with an open encrypted connection. */
    def connect(host: String, port: Int, tls: NetTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]]

    /** Connect to a Unix domain socket. Returns a fiber that completes with an open connection. */
    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]]

    /** Open a connection over process stdin (fd 0, read) and stdout (fd 1, write). Returns a fiber that completes with the open connection,
      * or aborts [[NetStdioAlreadyOpenException]] when a stdio connection is already open (fds 0/1 are process-global).
      *
      * Every shipped backend supports stdio: the PosixHandle-backed transport, the JVM NIO floor, and the Node-stream transport.
      * [[NetStdioUnsupportedException]] remains the contract for a transport with no byte stream to fd 0/1 (e.g. an in-memory transport).
      */
    def stdio()(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]]

    /** Listen for incoming TCP connections.
      *
      * @param host
      *   Bind address (e.g., "0.0.0.0" for all interfaces)
      * @param port
      *   Port to listen on (0 for system-assigned)
      * @param backlog
      *   Maximum pending connections queue size
      * @param handler
      *   Called once per accepted connection, fire-and-forget. The handler must return Unit; any async work must be spawned via
      *   Fiber.Unsafe.init inside the handler body.
      * @return
      *   Fiber that completes with Listener once bound
      */
    def listen(host: String, port: Int, backlog: Int)(
        handler: Connection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[NetException]]

    /** Listen for incoming TLS connections. Each accepted connection completes a TLS handshake before the handler is invoked. */
    def listen(host: String, port: Int, backlog: Int, tls: NetTlsConfig)(
        handler: Connection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[NetException]]

    /** Listen on a Unix domain socket. */
    def listenUnix(path: String, backlog: Int)(
        handler: Connection => Unit
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[NetException]]

    /** Shutdown the transport. Closes the driver pool and all resources. Synchronous, idempotent. */
    def close()(using AllowUnsafe, Frame): Unit

    /** Upgrade a plaintext connection to TLS after pre-handshake bytes have been exchanged (STARTTLS-style).
      *
      * Implemented on every platform: JVM (NIO and the posix transport), Native (the posix transport), and JS (Node TLS). The returned fiber
      * completes with the new TLS connection, or aborts `NetException` when the connection is not upgradable (an in-memory connection) or the
      * handshake fails.
      */
    def upgradeToTls(
        conn: Connection,
        tls: NetTlsConfig,
        channelCapacity: Int
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Connection, Abort[NetException]]

    /** The TLS provider ids this transport can drive (e.g. "boringssl"/"jdk" for the posix transport, "jdk" for the NIO floor, "node" for JS).
      * A connection requesting a [[NetTlsConfig.tlsProvider]] outside this set fails closed (see [[upgradeToTls]] / `connect(tls)`). The set is
      * the transport's architectural capability, independent of host availability (whether a provider's library is staged is a separate probe).
      * A transport with no TLS support (such as an in-memory connection) returns the empty set and honors no provider id. The cross-backend test
      * matrix reads this to skip (backend x impl) cells the transport cannot serve, so the set of valid combinations lives here in production and
      * evolves with the transport rather than being duplicated as a fixed table in the tests.
      */
    private[net] def supportedTlsProviders: Set[String]
end Transport

/** A bound server socket returned by [[Transport.listen]] / [[Transport.listenUnix]], accepting connections and dispatching each to the handler
  * passed at `listen` time.
  *
  * The listener exposes the address it actually bound to ([[port]] / [[host]] / [[address]]), which matters when binding to port 0 for a
  * system-assigned port: the resolved port is readable here after `listen` returns. [[close]] stops accepting new connections and releases the
  * bound socket; it does NOT close connections already accepted (those are owned by their handler), so a graceful shutdown closes the listener
  * first and then drains the live connections.
  *
  * WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. [[close]] requires an `AllowUnsafe`; see AllowUnsafe
  * for the safety contract.
  */
abstract class Listener:
    /** Actual bound port. Returns -1 for Unix sockets. Useful when bound to port 0 for system assignment. */
    def port: Int

    /** Bound host address. Socket path for Unix sockets. */
    def host: String

    /** The address this listener is bound to (TCP or Unix). */
    def address: NetAddress

    /** Stop accepting new connections and close the listener. Synchronous, idempotent. Does not close already-accepted connections. */
    def close()(using AllowUnsafe, Frame): Unit
end Listener
