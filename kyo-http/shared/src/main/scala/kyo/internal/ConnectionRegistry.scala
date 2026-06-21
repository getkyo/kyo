package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import kyo.*

/** Tracks every live connection a client or server holds so all of them can be closed on shutdown.
  *
  * The transport's I/O driver is process-global and is never closed, so a connection's close happens here at the kyo-http
  * layer rather than through the driver. `HttpClientBackend` records the connections it creates and `HttpServer` records
  * the connections it accepts; both keep them here and close the lot when their own `close` runs.
  */
final private[kyo] class ConnectionRegistry[C]:

    private val conns                 = ConcurrentHashMap.newKeySet[C]().nn
    @volatile private var closingFlag = false

    /** True once shutdown has begun. A connection added after this should be closed by its caller rather than used,
      * because `closeAll` may already have swept the set.
      */
    def closing: Boolean = closingFlag

    /** Record a live connection. */
    def add(conn: C): Unit = discard(conns.add(conn))

    /** Drop a connection that is being closed through another path (for example evicted from a pool). */
    def remove(conn: C): Unit = discard(conns.remove(conn))

    /** Drop entries that are no longer open, bounding the set without needing a per-connection close hook. */
    def pruneClosed(isOpen: C => Boolean): Unit = discard(conns.removeIf(c => !isOpen(c)))

    /** Mark shutdown begun without closing anything, so a connection racing the close observes it and closes itself. */
    def markClosing(): Unit = closingFlag = true

    /** Mark closing and close every registered connection through `close`, containing any single close failure so it
      * cannot abort closing the rest.
      */
    def closeAll(close: C => Unit): Unit =
        closingFlag = true
        conns.forEach { c =>
            try close(c)
            catch case _: Throwable => ()
        }
        conns.clear()
    end closeAll

end ConnectionRegistry
