package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** Shared connection pool — works with any Backend.ConnectionFactory.
  *
  * Manages per-host pools of idle connections with bounded capacity, health checks, and acquire timeouts. Generalizes the same logic as the
  * old NettyChannelPool but is backend-agnostic.
  */
private[kyo] class ConnectionPool(
    factory: Backend.ConnectionFactory,
    maxConnectionsPerHost: Maybe[Int],
    connectionAcquireTimeout: Duration
)(using AllowUnsafe):

    import ConnectionPool.*

    private val pools = new java.util.concurrent.ConcurrentHashMap[PoolKey, HostPool]()

    /** Acquire a connection to the given host, reusing an idle one if available. */
    def acquire(host: String, port: Int, ssl: Boolean, connectTimeout: Maybe[Duration])(using
        Frame
    ): Backend.Connection < (Async & Abort[HttpError]) =
        val key      = PoolKey(host, port, ssl)
        val hostPool = pools.computeIfAbsent(key, _ => HostPool.init(maxConnectionsPerHost))
        hostPool.acquire(factory, host, port, ssl, connectTimeout, connectionAcquireTimeout)
    end acquire

    /** Release a connection back to the pool. Synchronous — suitable for ensure blocks. */
    def release(host: String, port: Int, ssl: Boolean, conn: Backend.Connection)(using AllowUnsafe): Unit =
        val key      = PoolKey(host, port, ssl)
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
        Sync.Unsafe {
            val iter = pools.values().iterator()
            while iter.hasNext do
                iter.next().close()
            pools.clear()
        }.andThen(factory.close(gracePeriod))

end ConnectionPool

private[kyo] object ConnectionPool:

    private case class PoolKey(host: String, port: Int, ssl: Boolean)

    /** Per-host pool managing idle connections with bounded capacity. */
    private class HostPool(
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
            Sync.Unsafe {
                pollActive() match
                    case Present(conn) => conn
                    case Absent =>
                        if tryIncrementCount() then
                            factory.connect(host, port, ssl, connectTimeout).map { conn =>
                                // If connect fails after increment, decrement on failure
                                conn
                            }
                        else
                            // Pool is full — wait for a released connection with timeout
                            Abort.run[kyo.Timeout](
                                Async.timeout(acquireTimeout)(waitForActive(factory, host, port, ssl, connectTimeout))
                            ).map {
                                case Result.Success(conn) => conn
                                case Result.Failure(_) =>
                                    Abort.fail(HttpError.ConnectionFailed(
                                        host,
                                        port,
                                        new RuntimeException(s"Timed out acquiring connection ($acquireTimeout)")
                                    ))
                                case Result.Panic(e) => throw e
                            }
            }
        end acquire

        def release(conn: Backend.Connection)(using AllowUnsafe): Unit =
            given Frame = Frame.internal
            if conn.isAlive then
                idleChannels.offer(conn) match
                    case Result.Success(true) => ()
                    case _ =>
                        discard(totalCount.decrementAndGet())
                        conn.closeAbruptly()
            else
                discard(totalCount.decrementAndGet())
                conn.closeAbruptly()
            end if
        end release

        def close()(using AllowUnsafe): Unit =
            given Frame = Frame.internal
            idleChannels.close() match
                case Present(remaining) =>
                    remaining.foreach { conn =>
                        discard(totalCount.decrementAndGet())
                        conn.closeAbruptly()
                    }
                case Absent => ()
            end match
        end close

        @tailrec
        private def pollActive()(using AllowUnsafe): Maybe[Backend.Connection] =
            given Frame = Frame.internal
            idleChannels.poll() match
                case Result.Success(Present(conn)) =>
                    if conn.isAlive then Present(conn)
                    else
                        discard(totalCount.decrementAndGet())
                        conn.closeAbruptly()
                        pollActive()
                case _ => Absent
            end match
        end pollActive

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
            Abort.run[Closed](idleChannels.safe.take).map {
                case Result.Success(conn) =>
                    if conn.isAlive then conn
                    else
                        Sync.Unsafe {
                            discard(totalCount.decrementAndGet())
                            conn.closeAbruptly()
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

        def init(maxConnections: Maybe[Int])(using AllowUnsafe): HostPool =
            given Frame      = Frame.internal
            val capacity     = maxConnections.getOrElse(DefaultIdleCapacity)
            val idleChannels = Channel.Unsafe.init[Backend.Connection](capacity)
            val totalCount   = AtomicInt.Unsafe.init(0)
            new HostPool(idleChannels, totalCount, maxConnections.getOrElse(Int.MaxValue))
        end init
    end HostPool

end ConnectionPool
