package kyo.net.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kyo.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Per-host idle connection pool with bounded capacity, health checks, and idle eviction.
  *
  * Uses a lock-free Vyukov MPMC ring buffer (HostPool) per host for zero-allocation on the hot path. The ring is sized to
  * maxConnectionsPerHost and uses AtomicLong sequence numbers to distinguish empty from populated slots without locks. It has at least two
  * slots, since a one-slot ring reads a published slot as free on the next lap and overwrites it; the limit itself is enforced by
  * tryReserve and release, never by the ring size, so a limit of one keeps one connection and a limit of zero or less admits none.
  *
  * Capacity is enforced via an in-flight counter (tryReserve/unreserve): a slot is reserved before connecting and released regardless of
  * success, preventing connection storms when all idle slots are occupied.
  *
  * An idle timeout of zero keeps nothing idle: a released connection is discarded at once, so none is reused. `Duration.Infinity` never
  * discards one for idleness.
  *
  * A host pool holding no idle connection and no reservation is evicted, by the reaper after each sweep and by an amortized scan when the
  * map grows, so a client that reaches many hosts retains at most about twice the pools of the hosts it still holds connections to.
  *
  * All public methods are direct (no Kyo `< S` wrappers) and require AllowUnsafe. Health checks (isAlive) and eviction (discardConn) are
  * supplied as constructor parameters so the pool remains generic over connection type C.
  */
final private[kyo] class ConnectionPool[K, C](
    maxConnectionsPerHost: Int,
    idleConnectionTimeoutNanos: Long,
    pools: ConcurrentHashMap[K, ConnectionPool.HostPool],
    isAlive: C => Boolean,
    discardConn: C => Unit,
    clock: Clock,
    frame: Frame
):

    import ConnectionPool.*

    @volatile private var closed = false

    // Background fiber that closes connections idle past the timeout, so a socket is released even when the pool is never
    // polled again and its client never closed. Absent for an infinite timeout; interrupted by close().
    @volatile private var reaper: Maybe[Fiber.Unsafe[Unit, Any]] = Absent

    /** True once `close()` has run. For testing the client's close/release path only. */
    private[kyo] def isClosed(using AllowUnsafe): Boolean = closed

    // Test seam (default no-op): a deterministic interleaving point for the release-vs-close linearizability regression in
    // ConnectionPoolTest. It runs after release() has observed the pool open but before it publishes, so a test can drive
    // close() into exactly the window the shared-transport fd leak lives in. Never set outside that test.
    private[internal] var raceProbe: () => Unit = ConnectionPool.noRaceProbe

    /** Try to get a live idle connection for the given host. */
    def poll(key: K)(using AllowUnsafe): Maybe[C] =
        if closed then Maybe.empty
        else getPool(key).poll(clock.unsafe.nowMonotonic().toNanos, idleConnectionTimeoutNanos, isAlive, discardConn)

    /** Return a connection to the idle pool. If the ring is full, discard it. */
    def release(key: K, conn: C)(using AllowUnsafe): Unit =
        if closed || idleConnectionTimeoutNanos == 0L then discardConn(conn)
        else
            raceProbe()
            publish(key, conn, clock.unsafe.nowMonotonic().toNanos)

    @tailrec private def publish(key: K, conn: C, now: Long)(using AllowUnsafe): Unit =
        val hostPool = getPool(key)
        // A pool retired by eviction refuses the publish; its key maps to a fresh pool once the evictor has unmapped it.
        if !hostPool.release(now, conn, discardConn) then publish(key, conn, now)
        // close() can race this release: it sets `closed`, drains every host pool, and clears the map, any of which may
        // fall between the `closed` read in release and the publish just done. A connection published into a ring close()
        // already drained (or a fresh pool getPool re-created after pools.clear()) would otherwise never be drained again
        // and its socket never closed. Re-read `closed`. If it is now set, drain and discard this host pool ourselves. The
        // ring's head CAS makes disposal exactly-once against close()'s own drain.
        else if closed then
            hostPool.drainDiscard(discardConn)
            // Drop the entry we may have re-created after close()'s pools.clear() so it does not linger. The two-arg remove
            // unmaps only this exact instance, so a fresh pool another releaser inserted for the same key is left alone.
            kyo.discard(pools.remove(key, hostPool))
        end if
    end publish

    /** Discard a connection without returning it to the pool. */
    def discard(conn: C)(using AllowUnsafe): Unit =
        discardConn(conn)

    /** Try to reserve an in-flight slot. Returns true if under the per-host limit. */
    @tailrec def tryReserve(key: K)(using AllowUnsafe): Boolean =
        if closed then false
        else
            getPool(key).tryReserve() match
                case HostPool.Reserved => true
                case HostPool.Full     => false
                case _                 => tryReserve(key) // retired by eviction: the key maps to a fresh pool once it is unmapped
        end if
    end tryReserve

    /** Release an in-flight slot. Always call this after tryReserve, on both success and failure paths. */
    def unreserve(key: K)(using AllowUnsafe): Unit =
        if !closed then getPool(key).unreserve()

    /** Close the pool. Returns idle connections for the caller to close. */
    def close()(using AllowUnsafe): Chunk[C] =
        if closed then Chunk.empty
        else
            closed = true
            reaper match
                case Present(r) =>
                    given Frame = frame
                    kyo.discard(r.interrupt())
                case Absent => ()
            end match
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

    // The map size at which creating a host pool next scans for vacant ones to evict. Doubling it from the size each scan leaves keeps the
    // scans amortized O(1) per created pool however many hosts are live. A lost update between racing creators only moves a scan.
    @volatile private var evictAtSize = EvictScanMinSize

    private def getPool(key: K): HostPool =
        val existing = pools.get(key)
        if existing ne null then existing
        else
            // Creating pools is the only way the map grows, so scanning here bounds it for every idle timeout, including the infinite one
            // that runs no reaper. The scan runs before the creation so it cannot retire the pool this call is about to hand out.
            if pools.size() >= evictAtSize then
                evictVacant()
                evictAtSize = math.max(EvictScanMinSize, pools.size() * 2)
            pools.computeIfAbsent(key, newHostPool)
        end if
    end getPool

    // Unmap every host pool holding no idle connection and no reservation. A pool is retired before it is unmapped, so a holder of the old
    // reference can neither publish into it nor reserve on it (see HostPool.tryRetire).
    private def evictVacant(): Unit =
        pools.forEach { (key, hostPool) =>
            if hostPool.tryRetire() then kyo.discard(pools.remove(key, hostPool))
        }

    // Launch the idle-expiry reaper (init calls this only for a finite timeout). One scheduler fiber that parks on
    // Clock.sleep between passes: no thread blocking, no per-request cost. close() interrupts it.
    private def startReaper(interval: Duration)(using AllowUnsafe): Unit =
        given Frame = frame
        reaper =
            Present(
                Sync.Unsafe.evalOrThrow(
                    // Bind the pool's own clock, not the ambient one: the pool may be initialized under a controlled clock
                    // that the ambient clock would leave parked forever, and a test's clock drives cadence and idle-age reads.
                    Clock.let(clock)(Clock.repeatWithDelay(interval, interval)(Sync.Unsafe.defer(sweepExpiredHosts())))
                ).unsafe
            )
    end startReaper

    // One reaper pass: close every connection idle past the timeout, across all host pools, then drop the pools that left vacant.
    private def sweepExpiredHosts()(using AllowUnsafe): Unit =
        given Frame = frame
        val now     = clock.unsafe.nowMonotonic().toNanos
        pools.forEach((_, hostPool) => hostPool.sweepExpired(now, idleConnectionTimeoutNanos, discardConn))
        evictVacant()
    end sweepExpiredHosts

end ConnectionPool

private[kyo] object ConnectionPool:

    // The shared default for `raceProbe`: a single no-op instance so a production pool allocates no per-instance lambda.
    private[internal] val noRaceProbe: () => Unit = () => ()

    // The smallest map size at which creating a host pool scans for vacant pools, so a client with a handful of hosts never scans.
    private val EvictScanMinSize = 16

    def init[K, C](
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        isAlive: C => Boolean,
        discard: C => Unit
    )(using Frame): ConnectionPool[K, C] < Sync =
        init(maxConnectionsPerHost, idleConnectionTimeout, isAlive, discard, new ConcurrentHashMap[K, HostPool]())

    private[internal] def init[K, C](
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        isAlive: C => Boolean,
        discard: C => Unit,
        pools: ConcurrentHashMap[K, HostPool]
    )(using frame: Frame): ConnectionPool[K, C] < Sync =
        // Capture the ambient clock (Clock.live, or a test's clock under Clock.withTimeControl). The pool stamps
        // idle-start instants and runs its reaper against it, so eviction is exercisable under virtual time.
        Clock.use { clock =>
            Sync.Unsafe.defer {
                val pool: ConnectionPool[K, C] = new ConnectionPool(
                    maxConnectionsPerHost,
                    idleConnectionTimeout.toNanos,
                    pools,
                    isAlive,
                    discard,
                    clock,
                    frame
                )
                // Finite positive timeout only: an infinite-timeout pool expires nothing, and a zero-timeout pool keeps nothing idle.
                // Sweep cadence is half the idle timeout, floored at 50ms, so an idle connection closes within about 1.5x the idle
                // timeout.
                if idleConnectionTimeout != Duration.Infinity && idleConnectionTimeout != Duration.Zero then
                    val intervalNanos = math.max(idleConnectionTimeout.toNanos / 2, 50L * 1000000L)
                    pool.startReaper(intervalNanos.nanos)
                pool
            }
        }
    end init

    /** Lock-free MPMC ring buffer for idle connections, based on Dmitry Vyukov's MPMC queue.
      *
      * Each slot has a sequence number that trails the head/tail counters by one lap. A slot is readable when seq == head+1 and writable
      * when seq == tail. CAS on head/tail claims the slot; lazySet on seq publishes it to other threads after mutation completes.
      *
      * The inFlight counter tracks connections currently being established (not yet idle). tryReserve() only succeeds when idle + inFlight
      * < limit, preventing thundering-herd reconnects when all connections are busy.
      *
      * The ring has `limit` slots but never fewer than two: one slot reads its own published sequence as writable on the next lap, so a
      * second release would overwrite the first. Below two slots, `release` therefore enforces `limit` itself rather than through the ring.
      */
    final private[internal] class HostPool(limit: Int):
        private val capacity = math.max(limit, 2)

        import HostPool.*

        private val connections = Array.fill[Maybe[AnyRef]](capacity)(Absent)
        private val timestamps  = new Array[Long](capacity)
        private val sequences   = new AtomicLongArray(Array.tabulate[Long](capacity)(_.toLong))
        private val head        = new AtomicLong(0)
        private val tail        = new AtomicLong(0)
        private val inFlight    = new AtomicInteger(0)

        /** Try to take an idle connection. Discards expired or dead connections and retries. `now` is one monotonic
          * reading for the whole poll, so retries compare idle age against a stable instant.
          */
        final def poll[C](
            now: Long,
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
                poll(now, idleTimeoutNanos, isAlive, discardConn)
            else
                val conn = connections(idx).get.asInstanceOf[C]
                val ts   = timestamps(idx)
                connections(idx) = Absent
                sequences.lazySet(idx, currentHead + capacity)
                val elapsed = now - ts
                if elapsed > idleTimeoutNanos then
                    discardConn(conn)
                    poll(now, idleTimeoutNanos, isAlive, discardConn)
                else if !isAlive(conn) then
                    discardConn(conn)
                    poll(now, idleTimeoutNanos, isAlive, discardConn)
                else
                    Present(conn)
                end if
            end if
        end poll

        /** Close every connection idle past the timeout, scanning from the head.
          *
          * The ring is ordered by idle age head to tail, so the scan stops at the first non-stale head (a rare out-of-order
          * `release` self-heals next sweep, never a leak). A stale head is claimed with the same `head` CAS `poll` uses, so
          * the reaper is race-free and exactly-once against a concurrent `poll`/`close`; an unreadable sequence is
          * mid-publish, so stop. A close failure is logged so one bad connection can't stall the rest.
          */
        final def sweepExpired[C](now: Long, idleTimeoutNanos: Long, discardConn: C => Unit)(using AllowUnsafe, Frame): Unit =
            @tailrec def loop(): Unit =
                val currentHead = head.get()
                val currentTail = tail.get()
                if currentHead >= currentTail then ()
                else
                    val idx = (currentHead % capacity).toInt
                    val seq = sequences.get(idx)
                    if seq < currentHead + 1 then ()                          // head mid-publish (fresh): stop
                    else if now - timestamps(idx) <= idleTimeoutNanos then () // head still fresh => all behind fresher => done
                    else if head.compareAndSet(currentHead, currentHead + 1) then
                        val conn = connections(idx).get.asInstanceOf[C]
                        connections(idx) = Absent
                        sequences.lazySet(idx, currentHead + capacity)
                        try discardConn(conn)
                        catch
                            case ex: Throwable if NonFatal(ex) =>
                                Log.live.unsafe.error(
                                    s"kyo.net: ConnectionPool reaper failed to close an idle connection: ${ex.getMessage}"
                                )
                        end try
                        loop()
                    else loop() // lost the CAS to a concurrent poll/close/sweep: re-read
                    end if
                end if
            end loop
            loop()
        end sweepExpired

        /** Return a connection to the ring, or discard it if full. `now` is the idle-start instant stamped on the
          * connection, read once from the pool's clock. False, with the connection untouched, when the pool is retired.
          */
        final def release[C](now: Long, conn: C, discardConn: C => Unit): Boolean =
            val currentTail = tail.get()
            if currentTail == RetiredTail then false
            else if limit < capacity && currentTail - head.get() >= limit then
                discardConn(conn)
                true
            else releaseToRing(now, conn, discardConn)
            end if
        end release

        @tailrec private def releaseToRing[C](now: Long, conn: C, discardConn: C => Unit): Boolean =
            val currentTail = tail.get()
            if currentTail == RetiredTail then false
            else
                val idx = (currentTail % capacity).toInt
                val seq = sequences.get(idx)
                if seq < currentTail then
                    discardConn(conn)
                    true
                else if !tail.compareAndSet(currentTail, currentTail + 1) then
                    releaseToRing(now, conn, discardConn)
                else
                    connections(idx) = Present(conn.asInstanceOf[AnyRef])
                    timestamps(idx) = now
                    sequences.lazySet(idx, currentTail + 1)
                    true
                end if
            end if
        end releaseToRing

        /** Reserve an in-flight slot to prevent connection storms: [[Reserved]], [[Full]], or [[Retired]] when eviction retired this pool. */
        def tryReserve(): Int =
            @tailrec def loop(): Int =
                val current     = inFlight.get()
                val currentTail = tail.get()
                if currentTail == RetiredTail then Retired
                else if current + (currentTail - head.get()).toInt.max(0) >= limit then Full
                else if !inFlight.compareAndSet(current, current + 1) then loop()
                // The reservation is published before this read, and tryRetire publishes its retirement before reading inFlight, so at
                // least one of the two sees the other: the retirement is undone, or this reservation is given back here.
                else if tail.get() == RetiredTail then
                    kyo.discard(inFlight.decrementAndGet())
                    Retired
                else Reserved
                end if
            end loop
            loop()
        end tryReserve

        /** Retire the pool when it holds no idle connection and no reservation, after which `release` and `tryReserve` refuse it, so the
          * caller can unmap it without stranding a connection or a reservation. Retiring swaps `tail` for a sentinel, so it excludes a
          * concurrent `release` through that same CAS; a reservation that slipped in is seen through `inFlight` and the retirement undone.
          * `poll`, `sweepExpired` and the drains treat the sentinel as an empty ring.
          */
        def tryRetire(): Boolean =
            val currentTail = tail.get()
            if currentTail == RetiredTail || head.get() != currentTail || inFlight.get() != 0 then false
            else if !tail.compareAndSet(currentTail, RetiredTail) then false
            else if inFlight.get() != 0 then
                tail.set(currentTail)
                false
            else true
            end if
        end tryRetire

        /** Release an in-flight slot. Never reaches a retired pool: a pool with a reservation outstanding is not retired. */
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

    private[internal] object HostPool:
        val Reserved = 1
        val Full     = 0
        val Retired  = -1

        // Below every real tail (which starts at 0 and only grows), so the `head >= tail` empty checks treat a retired ring as empty.
        private[internal] val RetiredTail = -1L
    end HostPool

end ConnectionPool
