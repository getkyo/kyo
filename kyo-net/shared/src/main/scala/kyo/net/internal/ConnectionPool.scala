package kyo.net.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kyo.*
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
final private[kyo] class ConnectionPool[K, C](
    maxConnectionsPerHost: Int,
    idleConnectionTimeoutNanos: Long,
    pools: ConcurrentHashMap[K, ConnectionPool.HostPool],
    isAlive: C => Boolean,
    discardConn: C => Unit
):

    import ConnectionPool.*

    @volatile private var closed = false

    /** True once `close()` has run. For testing the client's close/release path only. */
    private[kyo] def isClosed(using AllowUnsafe): Boolean = closed

    // Test seam (default no-op): a deterministic interleaving point for the release-vs-close linearizability regression in
    // ConnectionPoolTest. It runs after release() has observed the pool open but before it publishes, so a test can drive
    // close() into exactly the window the shared-transport fd leak lives in. Never set outside that test.
    private[internal] var raceProbe: () => Unit = ConnectionPool.noRaceProbe

    /** Try to get a live idle connection for the given host. */
    def poll(key: K)(using AllowUnsafe): Maybe[C] =
        if closed then Maybe.empty
        else getPool(key).poll(idleConnectionTimeoutNanos, isAlive, discardConn)

    /** Return a connection to the idle pool. If the ring is full, discard it. */
    def release(key: K, conn: C)(using AllowUnsafe): Unit =
        if closed then discardConn(conn)
        else
            raceProbe()
            val hostPool = getPool(key)
            hostPool.release(conn, discardConn)
            // close() can race this release: it sets `closed`, drains every host pool, and clears the map, any of which may
            // fall between the `closed` read above and the publish just done. A connection published into a ring close()
            // already drained (or a fresh pool getPool re-created after pools.clear()) would otherwise never be drained again
            // and its socket never closed. Re-read `closed`. If it is now set, drain and discard this host pool ourselves. The
            // ring's head CAS makes disposal exactly-once against close()'s own drain.
            if closed then
                hostPool.drainDiscard(discardConn)
                // Drop the entry we may have re-created after close()'s pools.clear() so it does not linger. The two-arg remove
                // unmaps only this exact instance, so a fresh pool another releaser inserted for the same key is left alone.
                kyo.discard(pools.remove(key, hostPool))
            end if

    /** Discard a connection without returning it to the pool. */
    def discard(conn: C)(using AllowUnsafe): Unit =
        discardConn(conn)

    /** Try to reserve an in-flight slot. Returns true if under the per-host limit. */
    def tryReserve(key: K)(using AllowUnsafe): Boolean =
        if closed then false
        else getPool(key).tryReserve()

    /** Release an in-flight slot. Always call this after tryReserve, on both success and failure paths. */
    def unreserve(key: K)(using AllowUnsafe): Unit =
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

    // Cached mapping function: computeIfAbsent reuses this one instance instead of allocating a fresh lambda on every getPool call, and getPool
    // runs on the hot path (poll/release/tryReserve/unreserve each call it). maxConnectionsPerHost is a fixed constructor param, so one instance
    // per pool suffices.
    private val newHostPool: java.util.function.Function[K, HostPool] =
        _ => new HostPool(maxConnectionsPerHost)

    private def getPool(key: K): HostPool =
        pools.computeIfAbsent(key, newHostPool)

end ConnectionPool

private[kyo] object ConnectionPool:

    // The shared default for `raceProbe`: a single no-op instance so a production pool allocates no per-instance lambda.
    private[internal] val noRaceProbe: () => Unit = () => ()

    def init[K, C](
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        isAlive: C => Boolean,
        discard: C => Unit
    )(using AllowUnsafe): ConnectionPool[K, C] =
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
        private val sequences   = new AtomicLongArray(Array.tabulate[Long](capacity)(_.toLong))
        private val head        = new AtomicLong(0)
        private val tail        = new AtomicLong(0)
        private val inFlight    = new AtomicInteger(0)

        /** Try to take an idle connection. Discards expired or dead connections and retries. */
        final def poll[C](
            idleTimeoutNanos: Long,
            isAlive: C => Boolean,
            discardConn: C => Unit
        ): Maybe[C] =
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
        final def release[C](conn: C, discardConn: C => Unit): Unit =
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
        def tryReserve(): Boolean =
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
        def unreserve(): Unit =
            kyo.discard(inFlight.decrementAndGet())

        /** Drain every slot claimed for release, from `head` up to `tail`, applying `sink` to each connection.
          *
          * A concurrent `release` publishes in two steps: it CASes `tail` to claim a slot, then stores the connection and
          * `lazySet`s the slot's sequence to mark it readable. A drain that stopped at the first slot whose sequence is not yet
          * visible would treat a slot being published right now as the end of the ring and leave that connection behind, never
          * drained again. So while `head` is below `tail` a stale sequence means a claim is mid-publish: spin until its store
          * lands rather than terminate. A claimer between its CAS and its store is running on its own carrier (release never
          * suspends), so the wait is bounded. A single-threaded runtime has no release in flight while this runs, so the spin
          * is never taken. Ends when `head == tail`.
          */
        private def drainClaimed[C](sink: C => Unit): Unit =
            @tailrec def loop(): Unit =
                val currentHead = head.get()
                val currentTail = tail.get()
                if currentHead >= currentTail then ()
                else
                    val idx = (currentHead % capacity).toInt
                    val seq = sequences.get(idx)
                    if seq < currentHead + 1 then loop()
                    else if head.compareAndSet(currentHead, currentHead + 1) then
                        connections(idx) match
                            case Present(conn) =>
                                connections(idx) = Absent
                                sink(conn.asInstanceOf[C])
                            case Absent =>
                                connections(idx) = Absent
                        end match
                        sequences.lazySet(idx, currentHead + capacity)
                        loop()
                    else loop()
                    end if
                end if
            end loop
            loop()
        end drainClaimed

        /** Close the pool. Drains idle connections for the caller to close. */
        def close[C](into: ChunkBuilder[C]): Unit =
            drainClaimed[C](conn => kyo.discard(into += conn))

        /** Drain and discard every connection still in the ring, for a `release` that observed the pool closed after it had
          * already published. Shares [[drainClaimed]] with [[close]], so the same wait-for-a-mid-publish-claim rule applies.
          */
        def drainDiscard[C](discardConn: C => Unit): Unit =
            drainClaimed(discardConn)

    end HostPool

end ConnectionPool
