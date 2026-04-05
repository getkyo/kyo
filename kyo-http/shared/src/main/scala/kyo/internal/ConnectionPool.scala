package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kyo.*
import scala.annotation.tailrec

/** Manages per-host pools of idle connections with bounded capacity, health checks, and idle eviction. Uses a lock-free Vyukov MPMC ring
  * buffer per host for zero-allocation on the hot path. All methods return suspended computations.
  */
final private[kyo] class ConnectionPool[C](
    maxConnectionsPerHost: Int,
    idleConnectionTimeoutNanos: Long,
    pools: ConcurrentHashMap[TransportAddress, ConnectionPool.HostPool],
    isAlive: C => Boolean < Sync,
    discardConn: C => Unit < Async
):

    import ConnectionPool.*

    @volatile private var closed = false

    def poll(key: TransportAddress)(using Frame): Maybe[C] < Async =
        if closed then Sync.defer(Maybe.empty)
        else getPool(key).poll(idleConnectionTimeoutNanos, isAlive, discardConn)

    def release(key: TransportAddress, conn: C)(using Frame): Unit < Async =
        if closed then discardConn(conn)
        else getPool(key).release(conn, discardConn)

    def track(key: TransportAddress, conn: C)(using Frame): Unit < Sync =
        Sync.defer { if !closed then getPool(key).track(conn) }

    def untrack(key: TransportAddress, conn: C)(using Frame): Unit < Sync =
        Sync.defer { if !closed then getPool(key).untrack(conn) }

    def discard(conn: C)(using Frame): Unit < Async =
        discardConn(conn)

    def tryReserve(key: TransportAddress)(using Frame): Boolean < Sync =
        Sync.defer {
            if closed then false
            else getPool(key).tryReserve()
        }

    def unreserve(key: TransportAddress)(using Frame): Unit < Sync =
        Sync.defer { if !closed then getPool(key).unreserve() }

    def close()(using Frame): Chunk[C] < Sync =
        Sync.defer {
            if closed then Chunk.empty
            else
                closed = true
                val builder = ChunkBuilder.init[C]
                pools.forEach { (_, hostPool) =>
                    hostPool.close(builder)
                }
                pools.clear()
                builder.result()
        }

    private def getPool(key: TransportAddress): HostPool =
        pools.computeIfAbsent(key, _ => new HostPool(maxConnectionsPerHost))

end ConnectionPool

private[kyo] object ConnectionPool:

    def init[C](
        maxConnectionsPerHost: Int,
        idleConnectionTimeout: Duration,
        isAlive: C => Boolean < Sync,
        discard: C => Unit < Async
    )(using Frame): ConnectionPool[C] < Sync =
        Sync.defer {
            require(maxConnectionsPerHost >= 2, s"maxConnectionsPerHost must be >= 2: $maxConnectionsPerHost")
            new ConnectionPool(
                maxConnectionsPerHost,
                idleConnectionTimeout.toNanos,
                new ConcurrentHashMap(),
                isAlive,
                discard
            )
        }

    final private[internal] class HostPool(capacity: Int):
        require(capacity >= 2, s"maxConnectionsPerHost must be >= 2: $capacity")

        private val connections = Array.fill[Maybe[AnyRef]](capacity)(Absent)
        private val timestamps  = new Array[Long](capacity)
        private val sequences   = new AtomicLongArray(Array.tabulate[Long](capacity)(_.toLong))
        private val head        = new AtomicLong(0)
        private val tail        = new AtomicLong(0)
        private val inFlight    = new AtomicInteger(0)
        private val tracked     = new ConcurrentHashMap[AnyRef, Unit]()

        final def poll[C](
            idleTimeoutNanos: Long,
            isAlive: C => Boolean < Sync,
            discardConn: C => Unit < Async
        )(using Frame): Maybe[C] < Async =
            Sync.defer {
                val currentHead = head.get()
                val idx         = (currentHead % capacity).toInt
                val seq         = sequences.get(idx)
                if seq < currentHead + 1 then
                    Sync.defer(Maybe.empty)
                else if !head.compareAndSet(currentHead, currentHead + 1) then
                    poll(idleTimeoutNanos, isAlive, discardConn)
                else
                    val conn = connections(idx).get.asInstanceOf[C]
                    val ts   = timestamps(idx)
                    connections(idx) = Absent
                    sequences.lazySet(idx, currentHead + capacity)
                    val elapsed = java.lang.System.nanoTime() - ts
                    if elapsed > idleTimeoutNanos then
                        discardConn(conn).andThen(poll(idleTimeoutNanos, isAlive, discardConn))
                    else
                        isAlive(conn).map { alive =>
                            if !alive then
                                discardConn(conn).andThen(poll(idleTimeoutNanos, isAlive, discardConn))
                            else
                                Sync.defer(Present(conn))
                        }
                    end if
                end if
            }

        final def release[C](conn: C, discardConn: C => Unit < Async)(using Frame): Unit < Async =
            Sync.defer {
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
                    Kyo.unit
                end if
            }

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

        def unreserve(): Unit =
            kyo.discard(inFlight.decrementAndGet())

        def track[C](conn: C): Unit =
            kyo.discard(tracked.put(conn.asInstanceOf[AnyRef], ()))

        def untrack[C](conn: C): Unit =
            kyo.discard(tracked.remove(conn.asInstanceOf[AnyRef]))

        def close[C](into: ChunkBuilder[C]): Unit =
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
            tracked.forEach { (conn, _) =>
                kyo.discard(into += conn.asInstanceOf[C])
            }
            tracked.clear()
        end close

    end HostPool

end ConnectionPool
