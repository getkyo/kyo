package kyo.internal

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.handler.ssl.SslContextBuilder
import kyo.*

class NettyChannelPoolTest extends Test:

    private def initPool(
        port: Int,
        maxConnections: Maybe[Int] = Present(10),
        connectTimeout: Maybe[Duration] = Absent
    )(using AllowUnsafe, Frame): (NettyChannelPool, MultiThreadIoEventLoopGroup) =
        val workerGroup = new MultiThreadIoEventLoopGroup(
            0,
            new io.netty.util.concurrent.DefaultThreadFactory("test-pool", true),
            NettyTransport.ioHandlerFactory
        )
        val bootstrap = new Bootstrap()
            .group(workerGroup)
            .channel(NettyTransport.socketChannelClass)
            .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
            .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
        val sslContext = SslContextBuilder.forClient().build()
        val pool = NettyChannelPool.init(
            bootstrap,
            sslContext,
            "localhost",
            port,
            ssl = false,
            maxConnections,
            1048576,
            connectTimeout
        )
        (pool, workerGroup)
    end initPool

    private def cleanup(pool: NettyChannelPool, workerGroup: MultiThreadIoEventLoopGroup)(using AllowUnsafe, Frame): Unit =
        pool.close()
        discard(workerGroup.shutdownGracefully())

    private val pingHandler = HttpHandler.get("/ping") { (_, _) =>
        HttpResponse.ok("pong")
    }

    "acquire and release" - {

        "acquire returns an active channel" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port)
                    pool.acquire().map { ch =>
                        assert(ch.isActive())
                        pool.release(ch)
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }

        "release returns channel to pool for reuse" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(10))
                    pool.acquire().map { ch1 =>
                        val id1 = ch1.id()
                        pool.release(ch1)
                        pool.acquire().map { ch2 =>
                            assert(ch2.id().equals(id1), s"Expected reuse of channel $id1 but got ${ch2.id()}")
                            pool.release(ch2)
                            cleanup(pool, workerGroup)
                            succeed
                        }
                    }
                }
            }
        }

        "multiple acquire/release cycles" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    Kyo.foreach(1 to 10) { _ =>
                        pool.acquire().map { ch =>
                            assert(ch.isActive())
                            pool.release(ch)
                        }
                    }.andThen {
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }
    }

    "health check" - {

        "discards closed channels on acquire" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(10))
                    pool.acquire().map { ch =>
                        ch.close().sync()
                        pool.release(ch)
                        pool.acquire().map { ch2 =>
                            assert(ch2.isActive(), "Should get an active channel after discarding closed one")
                            pool.release(ch2)
                            cleanup(pool, workerGroup)
                            succeed
                        }
                    }
                }
            }
        }

        "discards multiple closed channels" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(10))
                    Async.fill(3, 3)(pool.acquire()).map { channels =>
                        channels.foreach { ch =>
                            ch.close().sync()
                            pool.release(ch)
                        }
                        pool.acquire().map { ch =>
                            assert(ch.isActive())
                            pool.release(ch)
                            cleanup(pool, workerGroup)
                            succeed
                        }
                    }
                }
            }
        }
    }

    "max connections" - {

        "limits total connections" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val maxConn             = 3
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(maxConn))
                    Async.fill(maxConn, maxConn)(pool.acquire()).map { channels =>
                        assert(channels.size == maxConn)
                        assert(channels.forall(_.isActive()))
                        channels.foreach(ch => pool.release(ch))
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }

        "waits when pool exhausted then returns released channel" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(1))
                    pool.acquire().map { ch1 =>
                        Fiber.initUnscoped(pool.acquire()).map { fiber =>
                            Async.delay(50.millis) {
                                pool.release(ch1)
                                fiber.get.map { ch2 =>
                                    assert(ch2.isActive())
                                    pool.release(ch2)
                                    cleanup(pool, workerGroup)
                                    succeed
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "unlimited pool" - {

        "allows many concurrent connections" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Absent)
                    Async.fill(20, 20)(pool.acquire()).map { channels =>
                        assert(channels.size == 20)
                        assert(channels.forall(_.isActive()))
                        channels.foreach(ch => pool.release(ch))
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }

        "reuses idle connections" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Absent)
                    pool.acquire().map { ch1 =>
                        val id1 = ch1.id()
                        pool.release(ch1)
                        pool.acquire().map { ch2 =>
                            assert(ch2.id().equals(id1))
                            pool.release(ch2)
                            cleanup(pool, workerGroup)
                            succeed
                        }
                    }
                }
            }
        }
    }

    "close" - {

        "closes all idle channels" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    Async.fill(3, 3)(pool.acquire()).map { channels =>
                        val channelRefs = channels.toSeq
                        channelRefs.foreach(ch => pool.release(ch))
                        pool.close()
                        Async.delay(50.millis) {
                            assert(channelRefs.forall(!_.isActive()), "All idle channels should be closed after pool close")
                            discard(workerGroup.shutdownGracefully())
                            succeed
                        }
                    }
                }
            }
        }

        "double close is safe" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    pool.close()
                    pool.close()
                    discard(workerGroup.shutdownGracefully())
                    succeed
                }
            }
        }

        "release to closed pool closes the channel" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    pool.acquire().map { ch =>
                        assert(ch.isActive())
                        pool.close()
                        pool.release(ch)
                        Async.delay(50.millis) {
                            assert(!ch.isActive(), "Channel should be closed after release to closed pool")
                            discard(workerGroup.shutdownGracefully())
                            succeed
                        }
                    }
                }
            }
        }
    }

    "connection failure" - {

        "acquire fails when host is unreachable" in run {
            Sync.Unsafe {
                val (pool, workerGroup) = initPool(port = 1, maxConnections = Present(5))
                Abort.run[HttpError](pool.acquire()).map {
                    case Result.Failure(_: HttpError.ConnectionFailed) =>
                        cleanup(pool, workerGroup)
                        succeed
                    case other =>
                        cleanup(pool, workerGroup)
                        fail(s"Expected ConnectionFailed but got $other")
                }
            }
        }
    }

    "connect timeout" - {

        "respects connect timeout configuration" in run {
            Sync.Unsafe {
                val (pool, workerGroup) = initPool(port = 80, maxConnections = Present(5), connectTimeout = Present(500.millis))
                Abort.run[HttpError](pool.acquire()).map {
                    case Result.Failure(_: HttpError.ConnectionFailed) =>
                        cleanup(pool, workerGroup)
                        succeed
                    case other =>
                        cleanup(pool, workerGroup)
                        fail(s"Expected ConnectionFailed but got $other")
                }
            }
        }
    }

    "concurrency" - {

        "concurrent acquire and release" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    Async.fill(50, 50) {
                        pool.acquire().map { ch =>
                            assert(ch.isActive())
                            pool.release(ch)
                        }
                    }.andThen {
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }

        "concurrent acquire with bounded pool" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val maxConn             = 3
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(maxConn))
                    Async.fill(20, 20) {
                        pool.acquire().map { ch =>
                            assert(ch.isActive())
                            Async.delay(5.millis) {
                                pool.release(ch)
                            }
                        }
                    }.andThen {
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }

        "concurrent acquire, release, and close" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    val latch               = Latch.Unsafe.init(1)
                    val acquireReleaseFiber = Fiber.initUnscoped(
                        latch.safe.await.andThen(
                            Async.fill(20, 20) {
                                Abort.run[HttpError](pool.acquire()).map {
                                    case Result.Success(ch) => pool.release(ch)
                                    case _                  => ()
                                }
                            }
                        )
                    )
                    val closeFiber = Fiber.initUnscoped(
                        latch.safe.await.andThen(
                            Async.delay(20.millis)(Sync.Unsafe(pool.close()))
                        )
                    )
                    latch.release()
                    acquireReleaseFiber.map(_.get).map { _ =>
                        closeFiber.map(_.get).andThen {
                            discard(workerGroup.shutdownGracefully())
                            succeed
                        }
                    }
                }
            }
        }

        "high contention acquire/release" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(2))
                    Async.fill(100, 100) {
                        pool.acquire().map { ch =>
                            assert(ch.isActive())
                            pool.release(ch)
                        }
                    }.andThen {
                        cleanup(pool, workerGroup)
                        succeed
                    }
                }
            }
        }
    }

    "edge cases" - {

        "acquire from closed pool completes without hanging" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(5))
                    pool.close()
                    // connectNew still works (bootstrap is independent of pool state)
                    // but the channel can't be returned to idle on release
                    Abort.run[HttpError](pool.acquire()).map {
                        case Result.Success(ch) =>
                            // Got a channel — verify it's valid, release discards it
                            assert(ch.isActive())
                            pool.release(ch)
                            discard(workerGroup.shutdownGracefully())
                            succeed
                        case Result.Failure(_: HttpError.ConnectionFailed) =>
                            discard(workerGroup.shutdownGracefully())
                            succeed
                        case other =>
                            discard(workerGroup.shutdownGracefully())
                            fail(s"Unexpected result: $other")
                    }
                }
            }
        }

        "fiber interruption during wait" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(1))
                    pool.acquire().map { ch1 =>
                        // Pool exhausted, next acquire will wait
                        Fiber.initUnscoped(pool.acquire()).map { fiber =>
                            Async.delay(50.millis) {
                                // Interrupt the waiting fiber instead of releasing
                                fiber.interrupt.andThen {
                                    fiber.getResult.map { result =>
                                        assert(result.isPanic, s"Expected interrupted (panic) but got $result")
                                        pool.release(ch1)
                                        cleanup(pool, workerGroup)
                                        succeed
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "multiple waiters unblocked sequentially" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(1))
                    pool.acquire().map { ch1 =>
                        // 3 fibers waiting for the single connection
                        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
                        Fiber.initUnscoped(
                            Async.fill(3, 3) {
                                pool.acquire().map { ch =>
                                    counter.incrementAndGet()
                                    pool.release(ch)
                                }
                            }
                        ).map { fiber =>
                            // Release ch1 to start unblocking waiters
                            Async.delay(50.millis) {
                                pool.release(ch1)
                                fiber.get.map { _ =>
                                    assert(counter.get() == 3, s"Expected 3 completions but got ${counter.get()}")
                                    cleanup(pool, workerGroup)
                                    succeed
                                }
                            }
                        }
                    }
                }
            }
        }

        "release of already-closed channel" in run {
            startTestServer(pingHandler).map { port =>
                Sync.Unsafe {
                    val (pool, workerGroup) = initPool(port, maxConnections = Present(2))
                    pool.acquire().map { ch =>
                        ch.close().sync()
                        assert(!ch.isActive())
                        // Release closed channel — pool accepts it into idle queue,
                        // but pollActive will discard it on next acquire
                        pool.release(ch)
                        pool.acquire().map { ch2 =>
                            assert(ch2.isActive(), "Should get a fresh active channel")
                            pool.release(ch2)
                            cleanup(pool, workerGroup)
                            succeed
                        }
                    }
                }
            }
        }
    }

end NettyChannelPoolTest
