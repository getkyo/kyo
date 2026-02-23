package kyo.http2.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe
import kyo.Maybe
import kyo.Present
import kyo.discard
import kyo.isNull
import scala.annotation.tailrec

/** Fully unsafe connection pool — no kyo effects or data structures.
  *
  * Manages per-host pools of idle connections with bounded capacity and health checks. Returns Maybe.empty when no connection is available;
  * the caller decides what to do.
  */
private[http2] class ConnectionPool[C](
    maxConnectionsPerHost: Int,
    pools: ConcurrentHashMap[ConnectionPool.HostKey, ConnectionPool.HostPool[C]],
    isAlive: C => Boolean,
    discardConn: C => Unit
):

    import ConnectionPool.*

    /** Try to get a live idle connection for the given host. */
    def poll(key: HostKey)(using AllowUnsafe): Maybe[C] =
        getPool(key).poll(isAlive, discardConn)

    /** Return a connection to the idle pool. If the queue is full, discard it. Does not touch inFlight. */
    def release(key: HostKey, conn: C)(using AllowUnsafe): Unit =
        getPool(key).release(conn, discardConn)

    /** Try to reserve an in-flight slot. Returns true if under the per-host limit. */
    def tryReserve(key: HostKey)(using AllowUnsafe): Boolean =
        getPool(key).tryReserve()

    /** Release an in-flight slot. Always call this after tryReserve, on both success and failure paths. */
    def unreserve(key: HostKey)(using AllowUnsafe): Unit =
        getPool(key).unreserve()

    /** Close all pools, discarding remaining idle connections. */
    def closeAll()(using AllowUnsafe): Unit =
        @tailrec def loop(iter: java.util.Iterator[HostPool[C]]): Unit =
            if iter.hasNext then
                iter.next().closeAll(discardConn)
                loop(iter)
        loop(pools.values().iterator())
        pools.clear()
    end closeAll

    private def getPool(key: HostKey)(using AllowUnsafe): HostPool[C] =
        pools.computeIfAbsent(key, _ => new HostPool[C](maxConnectionsPerHost))

end ConnectionPool

private[http2] object ConnectionPool:

    def init[C](
        maxConnectionsPerHost: Int,
        isAlive: C => Boolean,
        discard: C => Unit
    )(using AllowUnsafe): ConnectionPool[C] =
        new ConnectionPool(maxConnectionsPerHost, new ConcurrentHashMap(), isAlive, discard)

    private[http2] case class HostKey(host: String, port: Int) derives CanEqual

    private[internal] class HostPool[C](maxConnections: Int):
        private val idle     = new ConcurrentLinkedQueue[C]()
        private val inFlight = new AtomicInteger(0)

        @tailrec final def poll(isAlive: C => Boolean, discardConn: C => Unit)(using AllowUnsafe): Maybe[C] =
            val conn = idle.poll()
            if isNull(conn) then Maybe.empty
            else if isAlive(conn) then Present(conn)
            else
                discardConn(conn)
                poll(isAlive, discardConn)
            end if
        end poll

        def release(conn: C, discardConn: C => Unit)(using AllowUnsafe): Unit =
            if !idle.offer(conn) then
                discardConn(conn)

        @tailrec final def tryReserve()(using AllowUnsafe): Boolean =
            val current = inFlight.get()
            if current + idle.size() >= maxConnections then false
            else if inFlight.compareAndSet(current, current + 1) then true
            else tryReserve()
        end tryReserve

        def unreserve()(using AllowUnsafe): Unit =
            discard(inFlight.decrementAndGet())

        @tailrec final def closeAll(discardConn: C => Unit)(using AllowUnsafe): Unit =
            val conn = idle.poll()
            if conn.asInstanceOf[AnyRef] ne null then
                discardConn(conn)
                closeAll(discardConn)
            end if
        end closeAll

    end HostPool

end ConnectionPool
