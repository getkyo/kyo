package kyo.internal.transport

import kyo.*

/** Transport abstraction for TCP and Unix domain socket connections.
  *
  * ## Lifecycle
  *
  * **Client:**
  *   1. `connect(host, port)` / `connectUnix(path)` returns fiber that completes with connection
  *   2. Connection ready for I/O when fiber completes
  *   3. Caller responsible for closing connection
  *
  * **Server:**
  *   1. `listen(host, port, handler)` / `listenUnix(path, handler)` returns fiber that completes with listener
  *   2. Each accepted connection passed to handler in its own fiber
  *   3. Close listener to stop accepting
  *
  * ## Platform Implementations
  *
  *   - Native: `NativeTransport` using epoll/kqueue
  *   - JVM: `NioTransport` using NIO Selector
  *   - JS: `JsTransport` using Node.js net module
  */
abstract private[kyo] class Transport[Handle]:

    /** The driver pool powering this transport. */
    def pool: IoDriverPool[Handle]

    /** Connect to a remote host. Returns fiber that completes with open connection. */
    def connect(host: String, port: Int)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[Handle], Abort[Closed]]

    /** Connect to a remote host with TLS. Returns fiber that completes with open connection. */
    def connect(host: String, port: Int, tls: HttpTlsConfig)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[Handle], Abort[Closed]]

    /** Listen for incoming connections.
      *
      * @param host
      *   Bind address (e.g., "0.0.0.0" for all interfaces)
      * @param port
      *   Port to listen on (0 for system-assigned)
      * @param backlog
      *   Maximum pending connections queue size
      * @param handler
      *   Called for each accepted connection (runs in separate fiber)
      * @return
      *   Fiber that completes with Listener once bound
      */
    def listen(host: String, port: Int, backlog: Int)(
        handler: Connection[Handle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]]

    /** Listen for incoming TLS connections.
      *
      * @param host
      *   Bind address (e.g., "0.0.0.0" for all interfaces)
      * @param port
      *   Port to listen on (0 for system-assigned)
      * @param backlog
      *   Maximum pending connections queue size
      * @param tls
      *   TLS configuration for the listener
      * @param handler
      *   Called for each accepted connection (runs in separate fiber)
      * @return
      *   Fiber that completes with Listener once bound
      */
    def listen(host: String, port: Int, backlog: Int, tls: HttpTlsConfig)(
        handler: Connection[Handle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]]

    /** Connect to a Unix domain socket. Returns fiber that completes with open connection. */
    def connectUnix(path: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Connection[Handle], Abort[Closed]]

    /** Listen on a Unix domain socket.
      *
      * @param path
      *   Socket file path (e.g., "/var/run/docker.sock")
      * @param backlog
      *   Maximum pending connections queue size
      * @param handler
      *   Called for each accepted connection (runs in separate fiber)
      * @return
      *   Fiber that completes with Listener once bound
      */
    def listenUnix(path: String, backlog: Int)(
        handler: Connection[Handle] => Unit < Async
    )(using AllowUnsafe, Frame): Fiber.Unsafe[Listener, Abort[Closed]]

    /** Shutdown the transport. Closes the driver pool and all resources. */
    def close()(using AllowUnsafe, Frame): Unit

end Transport

/** Server listener returned by Transport.listen() or Transport.listenUnix(). */
abstract private[kyo] class Listener:
    /** Actual port (useful when bound to port 0). Returns -1 for Unix sockets. */
    def port: Int

    /** Bound host address. Socket path for Unix sockets. */
    def host: String

    /** The address this listener is bound to (TCP or Unix). */
    def address: HttpAddress

    /** Stop accepting new connections and close the listener. */
    def close()(using AllowUnsafe, Frame): Unit
end Listener
