package kyo.internal

import kyo.*
import kyo.net.Connection
import kyo.net.Listener
import kyo.net.NetException
import kyo.net.NetPlatform
import kyo.net.Transport

/** Test helper: bind a fake TCP server on 127.0.0.1 whose handler runs an async body per accepted connection.
  *
  * The transport's listen callback is synchronous; the async body is spawned as a fire-and-forget unsafe fiber so this
  * helper's return type is the safe `Listener` value, ready for the caller's chained test body. The listener is registered
  * with the enclosing `Scope` and closed on exit, so callers do not have to manage the FD lifecycle explicitly (and the
  * end-of-run FD leak check stays clean).
  */
private[kyo] object FakeServer:

    def listenPort(handler: Connection => Unit < Async)(using Frame): Listener < (Async & Scope & Abort[NetException]) =
        Sync.Unsafe.defer {
            NetPlatform.transport.listen("127.0.0.1", 0, 128) { conn =>
                val _ = Fiber.Unsafe.init(handler(conn))
            }
        }.map(_.safe).flatMap(_.use(identity)).flatMap { listener =>
            // Unsafe: Listener.close requires AllowUnsafe; the finalizer runs at Scope exit.
            Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(listener)
        }

end FakeServer
