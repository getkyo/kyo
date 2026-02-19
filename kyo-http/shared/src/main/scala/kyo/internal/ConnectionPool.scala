package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Shared connection pool — works with any Backend.ConnectionFactory.
  *
  * Manages per-host pools of idle connections with bounded capacity, health checks, and acquire timeouts. Generalizes the same logic as the
  * old NettyChannelPool but is backend-agnostic.
  */
final private[kyo] class ConnectionPool(
    factory: Backend.ConnectionFactory,
    maxConnectionsPerHost: Maybe[Int],
    connectionAcquireTimeout: Duration
)(using AllowUnsafe):

    import ConnectionPool.*

    private val pools = new java.util.concurrent.ConcurrentHashMap[PoolKey, HostPool]()

    /** Acquire a connection to the given host, reusing an idle one if available. */
    def acquire(key: PoolKey, connectTimeout: Maybe[Duration])(using
        Frame
    ): Backend.Connection < (Async & Abort[HttpError]) =
        val hostPool = pools.computeIfAbsent(key, _ => HostPool.init(maxConnectionsPerHost))
        hostPool.acquire(factory, key.host, key.port, HttpUrl.isSsl(key.port), connectTimeout, connectionAcquireTimeout)
    end acquire

    /** Release a connection back to the pool. Synchronous — suitable for ensure blocks. */
    def release(key: PoolKey, conn: Backend.Connection)(using AllowUnsafe, Frame): Unit =
        val hostPool = pools.get(key)
        if hostPool != null then hostPool.release(conn)
        else conn.closeAbruptly()
    end release

    /** Create a direct (non-pooled) connection for streaming. */
    def connectDirect(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
        Frame
    ): Backend.Connection < (Async & Abort[HttpError]) =
        factory.connect(host, port, ssl, connectTimeout)

    /** Shut down all pools and the underlying factory. */
    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            pools.values().forEach(_.close())
            pools.clear()
        }.andThen(factory.close(gracePeriod))

end ConnectionPool

private[kyo] object ConnectionPool:

    private[kyo] case class PoolKey(host: String, port: Int) derives CanEqual

    /** Per-host pool managing idle connections with bounded capacity. */
    final private class HostPool(
        idleChannels: Channel.Unsafe[Backend.Connection],
        totalCount: AtomicInt.Unsafe,
        maxConnections: Int
    ):

        def acquire(
            factory: Backend.ConnectionFactory,
            host: String,
            port: Int,
            ssl: Boolean,
            connectTimeout: Maybe[Duration],
            acquireTimeout: Duration
        )(using Frame): Backend.Connection < (Async & Abort[HttpError]) =
            Sync.Unsafe.defer {
                // First try to reuse an idle connection (fast path, no I/O)
                pollActive() match
                    case Present(conn) => conn
                    case Absent        =>
                        // Under the per-host limit — open a new connection
                        if tryIncrementCount() then
                            factory.connect(host, port, ssl, connectTimeout).map { conn =>
                                conn
                            }
                        else
                            // At capacity — block until a connection is released or timeout
                            Abort.recover[kyo.Timeout](_ =>
                                Abort.fail(HttpError.ConnectionFailed(
                                    host,
                                    port,
                                    new RuntimeException(s"Timed out acquiring connection ($acquireTimeout)")
                                ))
                            )(Async.timeout(acquireTimeout)(waitForActive(factory, host, port, ssl, connectTimeout)))
            }
        end acquire

        def release(conn: Backend.Connection)(using AllowUnsafe, Frame): Unit =
            // Always offer to idle channel — even dead connections — so that fibers
            // blocked in waitForActive are woken up and can create fresh connections.
            idleChannels.offer(conn) match
                case Result.Success(true) => ()
                case _                    => discardConnection(conn)
        end release

        def close()(using AllowUnsafe, Frame): Unit =
            idleChannels.close() match
                case Present(remaining) =>
                    remaining.foreach(discardConnection)
                case Absent => ()
            end match
        end close

        @tailrec
        private def pollActive()(using AllowUnsafe, Frame): Maybe[Backend.Connection] =
            idleChannels.poll() match
                case Result.Success(Present(conn)) =>
                    if conn.isAlive then Present(conn)
                    else
                        discardConnection(conn)
                        pollActive()
                case _ => Absent
            end match
        end pollActive

        private def discardConnection(conn: Backend.Connection)(using AllowUnsafe): Unit =
            discard(totalCount.decrementAndGet())
            conn.closeAbruptly()

        private def tryIncrementCount()(using AllowUnsafe): Boolean =
            @tailrec def loop(): Boolean =
                val current = totalCount.get()
                if current >= maxConnections then false
                else if totalCount.compareAndSet(current, current + 1) then true
                else loop()
            end loop
            loop()
        end tryIncrementCount

        private def waitForActive(
            factory: Backend.ConnectionFactory,
            host: String,
            port: Int,
            ssl: Boolean,
            connectTimeout: Maybe[Duration]
        )(using Frame, AllowUnsafe): Backend.Connection < (Async & Abort[HttpError]) =
            // Blocks until another fiber releases a connection back to idle
            Abort.run[Closed](idleChannels.safe.take).map {
                case Result.Success(conn) =>
                    // Connection may have gone stale while idle — discard and retry
                    if conn.isAlive then conn
                    else
                        Sync.Unsafe.defer {
                            discardConnection(conn)
                        }.andThen(acquire(factory, host, port, ssl, connectTimeout, Duration.Infinity))
                case Result.Failure(_) =>
                    Abort.fail(HttpError.ConnectionFailed(
                        host,
                        port,
                        new RuntimeException("Connection pool is closed")
                    ))
                case Result.Panic(e) =>
                    Abort.fail(HttpError.fromThrowable(e, host, port))
            }
        end waitForActive

    end HostPool

    private object HostPool:
        private inline def DefaultIdleCapacity = 256

        def init(maxConnections: Maybe[Int])(using AllowUnsafe, Frame): HostPool =
            val capacity     = maxConnections.getOrElse(DefaultIdleCapacity)
            val idleChannels = Channel.Unsafe.init[Backend.Connection](capacity)
            val totalCount   = AtomicInt.Unsafe.init(0)
            new HostPool(idleChannels, totalCount, maxConnections.getOrElse(Int.MaxValue))
        end init
    end HostPool

end ConnectionPool
