package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import kyo.*

/** Tracks every live connection a client or server holds so all of them can be closed on shutdown.
  *
  * A connection's close happens here at the kyo-http layer, not by relying on the transport: the registry closes each
  * tracked connection on shutdown, and the client's or server's own transport is closed only afterward. `HttpClientBackend`
  * records the connections it creates and `HttpServer` records the connections it accepts; both register them here and close
  * the lot when their own `close` runs.
  *
  * Thread-safety: a connection is closed exactly once even when a registration races `closeAll`. Both the registering
  * caller and `closeAll` claim a connection by `remove`-ing it from the set, and only the caller that wins the remove
  * closes it. `register` also closes the connection itself when it observes shutdown, so a caller that is interrupted
  * right after registering never leaks its connection, and `closeAll` never drops a still-open connection.
  */
final private[kyo] class ConnectionRegistry[C]:

    private val conns                 = ConcurrentHashMap.newKeySet[C]().nn
    @volatile private var closingFlag = false

    /** Register a live connection. Returns true if the caller may keep using it, or false if a shutdown is in progress,
      * in which case the connection has already been closed (through `close`) and the caller must not use it.
      */
    def register(conn: C)(close: C => Unit): Boolean =
        discard(conns.add(conn))
        if !closingFlag then true
        else
            // Shutdown raced this registration. Claim the connection back: if we remove it, closeAll has not closed it,
            // so close it here; if we cannot, closeAll already claimed and closed it. Either way it is closed exactly once.
            if conns.remove(conn) then runClose(conn, close)
            false
        end if
    end register

    /** Drop a connection that is being closed through another path (for example evicted from a pool). */
    def remove(conn: C): Unit = discard(conns.remove(conn))

    /** Drop entries that are no longer open, bounding the set without needing a per-connection close hook. */
    def pruneClosed(isOpen: C => Boolean): Unit = discard(conns.removeIf(c => !isOpen(c)))

    /** Begin shutdown without closing anything yet, so a connection registered after this closes itself in `register`
      * instead of being used. Used when there is work to do (a grace period, a pool drain) before closing connections.
      */
    def markClosing(): Unit = closingFlag = true

    /** Mark closing and close every registered connection, claiming each through `remove` so a connection a concurrent
      * `register` is adding is closed exactly once (whichever of the two removes it wins).
      */
    def closeAll(close: C => Unit): Unit =
        closingFlag = true
        conns.forEach { c =>
            if conns.remove(c) then runClose(c, close)
        }
    end closeAll

    private def runClose(conn: C, close: C => Unit): Unit =
        try close(conn)
        catch case _: Throwable => ()

end ConnectionRegistry
