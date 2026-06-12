package kyo.net.internal.transport

import kyo.*
import kyo.net.Connection
import kyo.net.Listener
import kyo.net.NetAddress
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Transport

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
  *   - Native: `PosixTransport` using io_uring/epoll/kqueue
  *   - JVM: `PosixTransport` using io_uring/epoll/kqueue above the `NioTransport` (NIO Selector) floor
  *   - JS: `JsTransport` using Node.js net module
  */
abstract private[kyo] class TransportImpl[Handle] extends Transport:

    /** The driver pool powering this transport. The only member `TransportImpl` ADDS over the public `Transport` abstract; every other
      * operation (connect/listen/connectUnix/listenUnix/close/upgradeToTls) is already abstract on `Transport`, so the seam re-states nothing.
      */
    def pool: IoDriverPool[Handle]

end TransportImpl

/** Server listener returned by Transport.listen() or Transport.listenUnix(). Adds nothing over the public `Listener` abstract (port / host /
  * address / close are already declared there); the seam exists only to mirror `TransportImpl` as the internal listener base.
  */
abstract private[kyo] class ListenerImpl extends Listener
