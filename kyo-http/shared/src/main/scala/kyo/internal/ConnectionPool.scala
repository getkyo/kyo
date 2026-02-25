package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kyo.*
import kyo.discard
import scala.annotation.tailrec

/** Fully unsafe connection pool — no kyo effects or data structures.
  *
  * Manages per-host pools of idle connections with bounded capacity, health checks, and idle eviction. Uses a lock-free Vyukov MPMC ring
  * buffer per host for zero-allocation on the hot path.
  */
final private[kyo] class ConnectionPool[C](
    maxConnectionsPerHost: Int,
    idleConnectionTimeoutNanos: Long,
    pools: ConcurrentHashMap[ConnectionPool.HostKey, ConnectionPool.HostPool],
    isAlive: C => Boolean,
    discardConn: C => Unit
):

    import ConnectionPool.*

    /** Try to get a live idle connection for the given host. */
    def poll(key: HostKey)(using AllowUnsafe): Maybe[C] =
        getPool(key).poll(idleConnectionTimeoutNanos, isAlive, discardConn)

    /** Return a connection to the idle pool. If the ring is full, discard it. */
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
        pools.values().forEach(_.closeAll(discardConn))
        pools.clear()
    end closeAll

    private def getPool(key: HostKey)(using AllowUnsafe): HostPool =
        pools.computeIfAbsent(key, _ => new HostPool(maxConnectionsPerHost))

end ConnectionPool

private[kyo] object ConnectionPool:

    private val MaxConnectionsPerHost = 1024

    def init[C](
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        isAlive: C => Boolean,
        discard: C => Unit
    )(using AllowUnsafe): ConnectionPool[C] =
        require(
            maxConnectionsPerHost >= 2 && maxConnectionsPerHost <= MaxConnectionsPerHost,
            s"maxConnectionsPerHost must be >= 2 and <= $MaxConnectionsPerHost: $maxConnectionsPerHost"
        )
        new ConnectionPool(
            maxConnectionsPerHost,
            idleConnectionTimeout.toNanos,
            new ConcurrentHashMap(),
            isAlive,
            discard
        )
    end init

    private[kyo] case class HostKey(host: String, port: Int) derives CanEqual

    /** Lock-free MPMC ring buffer for idle connections, inspired by Dmitry Vyukov's MPMC queue (as implemented in Agrona's
      * ManyToManyConcurrentArrayQueue).
      *
      * Uses three parallel pre-allocated arrays (connections, timestamps, sequences) for zero allocation on the hot path. The sequence
      * array coordinates concurrent access: each slot's sequence number acts as a per-slot state machine that ensures writers have
      * exclusive ownership before writing and readers see completed writes before reading.
      *
      * Requires capacity >= 2 (same as Agrona) because with capacity == 1 the sequence values for "written, awaiting read" (seq = tail + 1)
      * and "read, ready for write" (seq = head + capacity) collide.
      */
    final private[internal] class HostPool(capacity: Int):
        require(capacity >= 2, s"maxConnectionsPerHost must be >= 2: $capacity")

        private val connections = new Array[AnyRef](capacity)
        private val timestamps  = new Array[Long](capacity)
        private val sequences   = new AtomicLongArray(capacity)
        private val head        = new AtomicLong(0)
        private val tail        = new AtomicLong(0)
        private val inFlight    = new AtomicInteger(0)

        // Initialize sequences: slot i is "ready for writing at tail position i"
        locally {
            @tailrec def loop(i: Int): Unit =
                if i < capacity then
                    sequences.lazySet(i, i.toLong)
                    loop(i + 1)
            loop(0)
        }

        /** Try to take an idle connection. Discards expired or dead connections and retries. */
        @tailrec final def poll[C](
            idleTimeoutNanos: Long,
            isAlive: C => Boolean,
            discardConn: C => Unit
        )(using AllowUnsafe): Maybe[C] =
            val currentHead = head.get()
            val idx         = (currentHead % capacity).toInt
            val seq         = sequences.get(idx)
            if seq < currentHead + 1 then
                // Ring is empty — no completed write at this position
                Maybe.empty
            else if !head.compareAndSet(currentHead, currentHead + 1) then
                // Another reader won, retry
                poll(idleTimeoutNanos, isAlive, discardConn)
            else
                // Claimed the slot — read data
                val conn = connections(idx).asInstanceOf[C]
                val ts   = timestamps(idx)
                connections(idx) = null
                // Release slot back to writers
                sequences.lazySet(idx, currentHead + capacity)
                // Check idle timeout and liveness
                val elapsed = java.lang.System.nanoTime() - ts
                if elapsed > idleTimeoutNanos then
                    discardConn(conn)
                    poll(idleTimeoutNanos, isAlive, discardConn)
                else if !isAlive(conn) then
                    discardConn(conn)
                    poll(idleTimeoutNanos, isAlive, discardConn)
                else
                    Present(conn)
                end if
            end if
        end poll

        /** Return a connection to the ring. If full, discard it. */
        @tailrec final def release[C](conn: C, discardConn: C => Unit)(using AllowUnsafe): Unit =
            val currentTail = tail.get()
            val idx         = (currentTail % capacity).toInt
            val seq         = sequences.get(idx)
            if seq < currentTail then
                // Ring is full — slot still holds an unread write
                discardConn(conn)
            else if !tail.compareAndSet(currentTail, currentTail + 1) then
                // Another writer won, retry
                release(conn, discardConn)
            else
                // Claimed the slot — write data
                connections(idx) = conn.asInstanceOf[AnyRef]
                timestamps(idx) = java.lang.System.nanoTime()
                sequences.lazySet(idx, currentTail + 1)
            end if
        end release

        /** Reserve an in-flight slot to prevent connection storms. */
        def tryReserve()(using AllowUnsafe): Boolean =
            @tailrec def loop(): Boolean =
                val current  = inFlight.get()
                val idleSize = (tail.get() - head.get()).toInt.max(0)
                if current + idleSize >= capacity then false
                else if inFlight.compareAndSet(current, current + 1) then true
                else loop()
            end loop
            loop()
        end tryReserve

        /** Release an in-flight slot. */
        def unreserve()(using AllowUnsafe): Unit =
            discard(inFlight.decrementAndGet())

        /** Drain and discard all idle connections. */
        def closeAll[C](discardConn: C => Unit)(using AllowUnsafe): Unit =
            @tailrec def loop(): Unit =
                val currentHead = head.get()
                val idx         = (currentHead % capacity).toInt
                val seq         = sequences.get(idx)
                if seq < currentHead + 1 then () // empty, done
                else if head.compareAndSet(currentHead, currentHead + 1) then
                    val conn = connections(idx)
                    connections(idx) = null
                    sequences.lazySet(idx, currentHead + capacity)
                    if conn ne null then discardConn(conn.asInstanceOf[C])
                    loop()
                else loop() // CAS failed, retry
                end if
            end loop
            loop()
        end closeAll

    end HostPool

end ConnectionPool
