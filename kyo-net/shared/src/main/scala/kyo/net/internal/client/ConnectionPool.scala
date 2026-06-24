package kyo.net.internal.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import kyo.*
import kyo.net.NetAddress
import scala.annotation.tailrec

/** Per-host idle connection pool with bounded capacity, health checks, and idle eviction.
  *
  * Uses a lock-free Vyukov MPMC ring buffer (HostPool) per host for zero-allocation on the hot path. The ring is sized to
  * maxConnectionsPerHost and uses AtomicLong sequence numbers to distinguish empty from populated slots without locks.
  *
  * Capacity is enforced via an in-flight counter (tryReserve/unreserve): a slot is reserved before connecting and released regardless of
  * success, preventing connection storms when all idle slots are occupied.
  *
  * All public methods are direct (no Kyo `< S` wrappers) and require AllowUnsafe. Health checks (isAlive) and eviction (discardConn) are
  * supplied as constructor parameters so the pool remains generic over connection type C.
  */
final private[kyo] class ConnectionPool[C](
    maxConnectionsPerHost: Int,
    idleConnectionTimeoutNanos: Long,
    // Concurrent-collection audit: `pools` is a raw ConcurrentHashMap from host address to its per-host lock-free ring. kyo has no
    // concurrent-map type, and the pool's public methods are direct (no `< S` wrappers, AllowUnsafe-only) on the hot acquire/release path, so an
    // effect-based map cannot back it. Retained as a documented no-equivalent exception.
    pools: ConcurrentHashMap[NetAddress, ConnectionPool.HostPool],
    isAlive: C => Boolean,
    discardConn: C => Unit
):

    import ConnectionPool.*

    @volatile private var closed = false

    /** Try to get a live idle connection for the given host. */
    def poll(key: NetAddress)(using AllowUnsafe): Maybe[C] =
        if closed then Maybe.empty
        else getPool(key).poll(idleConnectionTimeoutNanos, isAlive, discardConn)

    /** Return a connection to the idle pool. If the ring is full, discard it. */
    def release(key: NetAddress, conn: C)(using AllowUnsafe): Unit =
        if closed then discardConn(conn)
        else getPool(key).release(conn, discardConn)

    /** Discard a connection without returning it to the pool. */
    def discard(conn: C)(using AllowUnsafe): Unit =
        discardConn(conn)

    /** Try to reserve an in-flight slot. Returns true if under the per-host limit. */
    def tryReserve(key: NetAddress)(using AllowUnsafe): Boolean =
        if closed then false
        else getPool(key).tryReserve()

    /** Release an in-flight slot. Always call this after tryReserve, on both success and failure paths. */
    def unreserve(key: NetAddress)(using AllowUnsafe): Unit =
        if !closed then getPool(key).unreserve()

    /** Close the pool. Returns idle connections for the caller to close. */
    def close()(using AllowUnsafe): Chunk[C] =
        if closed then Chunk.empty
        else
            closed = true
            val builder = ChunkBuilder.init[C]
            pools.forEach { (_, hostPool) =>
                hostPool.close(builder)
            }
            pools.clear()
            builder.result()

    private def getPool(key: NetAddress): HostPool =
        pools.computeIfAbsent(key, _ => new HostPool(maxConnectionsPerHost))

end ConnectionPool

private[kyo] object ConnectionPool:

    def init[C](
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        isAlive: C => Boolean,
        discard: C => Unit
    )(using AllowUnsafe): ConnectionPool[C] =
        require(maxConnectionsPerHost >= 2, s"maxConnectionsPerHost must be >= 2: $maxConnectionsPerHost")
        new ConnectionPool(
            maxConnectionsPerHost,
            idleConnectionTimeout.toNanos,
            new ConcurrentHashMap(),
            isAlive,
            discard
        )
    end init

    /** Lock-free MPMC ring buffer for idle connections, based on Dmitry Vyukov's MPMC queue.
      *
      * Each slot has a sequence number that trails the head/tail counters by one lap. A slot is readable when seq == head+1 and writable
      * when seq == tail. CAS on head/tail claims the slot; lazySet on seq publishes it to other threads after mutation completes.
      *
      * The inFlight counter tracks connections currently being established (not yet idle). tryReserve() only succeeds when idle + inFlight
      * < capacity, preventing thundering-herd reconnects when all connections are busy.
      */
    final private[internal] class HostPool(capacity: Int):
        require(capacity >= 2, s"maxConnectionsPerHost must be >= 2: $capacity")

        private val connections = Array.fill[Maybe[AnyRef]](capacity)(Absent)
        private val timestamps  = new Array[Long](capacity)
        // No kyo array-of-atomics type exists, so the per-slot sequence ring stays a raw AtomicLongArray: the Vyukov MPMC ring publishes each
        // slot independently via get/lazySet, which an array of boxed kyo.AtomicLong could not express without per-slot allocation.
        private val sequences = new AtomicLongArray(Array.tabulate[Long](capacity)(_.toLong))
        // Unsafe: the ring counters are created at HostPool construction, where there is no ambient AllowUnsafe; the single danger bridge here
        // builds them, and every get/compareAndSet below runs under the caller's AllowUnsafe (the public pool methods are AllowUnsafe-only).
        private val head     = AtomicLong.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        private val tail     = AtomicLong.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        private val inFlight = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

        /** Try to take an idle connection. Discards expired or dead connections and retries. */
        final def poll[C](
            idleTimeoutNanos: Long,
            isAlive: C => Boolean,
            discardConn: C => Unit
        )(using AllowUnsafe): Maybe[C] =
            val currentHead = head.get()
            val idx         = (currentHead % capacity).toInt
            val seq         = sequences.get(idx)
            if seq < currentHead + 1 then
                Maybe.empty
            else if !head.compareAndSet(currentHead, currentHead + 1) then
                poll(idleTimeoutNanos, isAlive, discardConn)
            else
                val conn = connections(idx).get.asInstanceOf[C]
                val ts   = timestamps(idx)
                connections(idx) = Absent
                sequences.lazySet(idx, currentHead + capacity)
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
        final def release[C](conn: C, discardConn: C => Unit)(using AllowUnsafe): Unit =
            val currentTail = tail.get()
            val idx         = (currentTail % capacity).toInt
            val seq         = sequences.get(idx)
            if seq < currentTail then
                discardConn(conn)
            else if !tail.compareAndSet(currentTail, currentTail + 1) then
                release(conn, discardConn)
            else
                connections(idx) = Present(conn.asInstanceOf[AnyRef])
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
            kyo.discard(inFlight.decrementAndGet())

        /** Close the pool. Drains idle connections for the caller to close. */
        def close[C](into: ChunkBuilder[C])(using AllowUnsafe): Unit =
            @tailrec def loop(): Unit =
                val currentHead = head.get()
                val idx         = (currentHead % capacity).toInt
                val seq         = sequences.get(idx)
                if seq < currentHead + 1 then ()
                else if head.compareAndSet(currentHead, currentHead + 1) then
                    connections(idx) match
                        case Present(conn) =>
                            connections(idx) = Absent
                            kyo.discard(into += conn.asInstanceOf[C])
                        case Absent =>
                            connections(idx) = Absent
                    end match
                    sequences.lazySet(idx, currentHead + capacity)
                    loop()
                else loop()
                end if
            end loop
            loop()
        end close

    end HostPool

end ConnectionPool
