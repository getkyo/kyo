package kyo.internal

import kyo.*

/** Pluggable byte-level I/O. Knows nothing about HTTP or WebSocket.
  *
  * All implementations are non-blocking. read()/write() suspend the calling fiber via Promise until the OS signals readiness or completion
  * — no OS thread is ever blocked. The only "blocking" in the system is protocol-level head-of-line (e.g. HTTP/1.1 serializes requests on
  * one connection).
  *
  * Implementations:
  *   - KqueueNativeTransport: kqueue (macOS/BSD, Native)
  *   - EpollNativeTransport: epoll (Linux, Native)
  *   - UringNativeTransport: io_uring (Linux 5.1+, Native)
  *   - NioTransport: java.nio.channels.Selector (JVM)
  *   - EpollJvmTransport: epoll via Panama FFI (Linux, JVM)
  *   - KqueueJvmTransport: kqueue via Panama FFI (macOS, JVM)
  *   - JsTransport: Node.js net/tls (JS)
  *   - QuicTransport: UDP/QUIC (all platforms)
  *
  * All implementations expose the same trait. Protocol code is identical regardless of transport.
  */
trait Transport:

    /** Opaque connection handle. ConnectionPool stores and retrieves these. */
    type Connection

    /** Open a TCP connection, optionally wrapping in TLS. */
    def connect(host: String, port: Int, tls: Boolean)(using Frame): Connection < (Async & Abort[HttpException])

    /** Check if a connection is still usable (for pool health checks). */
    def isAlive(connection: Connection)(using AllowUnsafe): Boolean

    /** Force-close a connection (for pool eviction). */
    def closeNowUnsafe(connection: Connection)(using AllowUnsafe): Unit

    /** Graceful close with timeout. */
    def close(connection: Connection, gracePeriod: Duration)(using Frame): Unit < Async

    /** Get a byte stream from a connection.
      *
      * HTTP/1.1: returns the connection itself (1:1 mapping). HTTP/2: allocates a new stream ID on the connection.
      */
    def stream(connection: Connection)(using Frame): TransportStream < Async

    /** Bind a server socket. Returns a TransportListener whose lifecycle is managed by the enclosing Scope.
      *
      * When the Scope exits:
      *   1. The accept loop fiber is interrupted
      *   2. The server socket is closed (via Scope.acquireRelease)
      *   3. All accepted-connection fibers are interrupted
      *
      * Each accepted connection gets its own fiber with Sync.ensure cleanup. The handler receives a TransportStream for the accepted
      * connection.
      *
      * HttpTransportServer.bind() wraps this in its own Scope.run block and exposes a Promise-based close/await API on HttpBackend.Binding.
      */
    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope)

end Transport

/** Bidirectional byte stream. Protocol code reads/writes through this.
  *
  * Read and write may be called concurrently (TCP has independent kernel buffers). Two reads or two writes concurrently is NOT safe —
  * enforced by fiber ownership.
  */
trait TransportStream:

    /** Read bytes into the buffer. Returns number of bytes read, or -1 on EOF. */
    def read(buf: Array[Byte])(using Frame): Int < Async

    /** Write bytes to the stream. */
    def write(data: Span[Byte])(using Frame): Unit < Async

end TransportStream

/** Bound server socket — immutable after creation. Lifecycle managed by Scope. */
trait TransportListener:
    def port: Int
    def host: String
end TransportListener
