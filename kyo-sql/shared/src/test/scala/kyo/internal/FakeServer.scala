package kyo.internal

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.net.Connection
import kyo.net.Listener
import kyo.net.NetException
import kyo.net.NetPlatform
import kyo.net.Transport

/** Test helper: bind a fake TCP server on 127.0.0.1 whose handler runs an async body per accepted connection.
  *
  * The transport's listen callback is synchronous; the async body is spawned as a fire-and-forget unsafe fiber so this
  * helper's return type is the safe `Listener` value, ready for the caller's chained test body. Both the listener and every
  * accepted connection are registered with the enclosing `Scope`: on exit the listener stops accepting and each accepted
  * connection is closed. Callers do not have to manage FD lifecycle, and the end-of-run FD leak check stays clean even when
  * a handler blocks its fiber indefinitely.
  */
private[kyo] object FakeServer:

    def listenPort(handler: Connection => Unit < Async)(using Frame): Listener < (Async & Scope & Abort[NetException]) =
        // Track every accepted connection so the Scope finalizer can close each one. The queue is
        // append-only across concurrent Fiber.Unsafe.init callbacks; ConcurrentLinkedQueue's
        // add / iterator are lock-free and safe to hit from the transport's listen callback.
        val accepted = new ConcurrentLinkedQueue[Connection]()
        Sync.Unsafe.defer {
            NetPlatform.transport.listen("127.0.0.1", 0, 128) { conn =>
                accepted.add(conn)
                val _ = Fiber.Unsafe.init(handler(conn))
            }
        }.map(_.safe).flatMap(_.use(identity)).flatMap { listener =>
            // Unsafe: Listener.close / Connection.close require AllowUnsafe; the finalizer runs at Scope exit.
            Scope.ensure(Sync.Unsafe.defer {
                listener.close()
                val it = accepted.iterator()
                while it.hasNext do
                    val conn = it.next()
                    if conn.isOpen then
                        try conn.close()
                        catch case scala.util.control.NonFatal(_) => ()
                end while
            }).andThen(listener)
        }
    end listenPort

end FakeServer
