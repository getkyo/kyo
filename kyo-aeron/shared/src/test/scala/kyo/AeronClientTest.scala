package kyo

import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.internal.AeronAsyncPub
import kyo.internal.AeronAsyncSub
import kyo.internal.AeronBindings
import kyo.internal.AeronClientHandle
import kyo.internal.AeronDriverHandle
import kyo.internal.AeronPlatform
import kyo.internal.AeronPlatformTransport
import kyo.internal.AeronPublication
import kyo.internal.AeronRuntime
import kyo.internal.AeronSubscription
import kyo.internal.AeronTransport

class AeronClientTest extends Test:

    /** Builds an AeronClient from a connected AeronRuntime, managing its lifecycle with
      * Scope.acquireRelease. Bypasses AeronClient.connect so the runtime can be wrapped in a
      * close-counting proxy.
      */
    private def connectWithCloseCount(
        aeronDir: Path,
        closeCount: AtomicInt
    )(using Frame): AeronClient < (Scope & Async & Abort[TopicException]) =
        Scope.acquireRelease(
            AeronPlatform.external(aeronDir.unsafe.show).map { realRuntime =>
                // AeronRuntime is private[kyo], accessible from package kyo tests.
                val countingRuntime: AeronRuntime = new AeronRuntime:
                    def transport: AeronTransport        = realRuntime.transport
                    def close()(using AllowUnsafe): Unit =
                        closeCount.unsafe.incrementAndGet()
                        realRuntime.close()
                    end close
                Sync.Unsafe.defer(AeronClient.Unsafe.fromRuntime(countingRuntime).safe)
            }
        )(client => Sync.Unsafe.defer(client.unsafe.close()))

    // The client is a native handle with no Scala reference once abandoned, so the leaf
    // asserts what the driver can show: after rounds of connects stopped while in flight, with every client the rounds
    // did receive closed, the embedded driver still closes rather than waiting on a client that nobody closed. A driver
    // that does not close ends this leaf as its timeout.
    "connects stopped in flight leave a driver that still closes" in {
        Path.run(Path.tempDir("kyo-aeron-client-stops")).map { root =>
            val dir    = root / AeronDriver.mediaDirName
            val rounds = 40
            Scope.run {
                withExternalDriver(dir) {
                    Loop.indexed { i =>
                        if i >= rounds then Loop.done(rounds)
                        else
                            for
                                connecting <- Latch.init(1)
                                fiber      <- Fiber.initUnscoped(
                                    connecting.release.andThen(Abort.run[TopicException](AeronClient.connectUnscoped(dir)))
                                )
                                _ <- connecting.await
                                _ <- fiber.interrupt
                                r <- fiber.getResult
                                _ <- r match
                                    case Result.Success(Result.Success(client)) => Sync.Unsafe.defer(client.unsafe.close())
                                    case _                                      => Kyo.unit
                            yield Loop.continue
                            end for
                    }
                }
            }.map(stopped => assert(stopped == rounds))
        }
    }

    "connect + single Topic.run(client) round-trip: received == Chunk(1L,2L)" in {
        Path.run(Path.tempDir("kyo-aeron-client-l1")).map { root =>
            // The driver creates its own directory inside the temp one: Aeron deletes and recreates a driver
            // directory that already exists, and the temp directory stays what the scope removes.
            val dir = root / AeronDriver.mediaDirName
            Scope.run {
                withExternalDriver(dir) {
                    AeronClient.connect(dir).map { client =>
                        Topic.run(client) {
                            for
                                started <- Latch.init(1)
                                fiber   <- Fiber.initUnscoped(using Topic.isolate)(
                                    started.release.andThen(Topic.stream[Long]("aeron:ipc").take(2).run)
                                )
                                _        <- started.await
                                _        <- Fiber.initUnscoped(Topic.publish[Long]("aeron:ipc")(Stream.init(Seq(1L, 2L))))
                                received <- fiber.get
                            yield assert(received == Seq(1L, 2L), s"expected Seq(1L,2L) but got $received")
                        }
                    }
                }
            }
        }
    }

    // run(client) does NOT close the client, so one connect backs both scopes.
    "one shared client backs multiple run(client) scopes: Chunk(10L,20L)" in {
        Path.run(Path.tempDir("kyo-aeron-client-l2")).map { root =>
            val dir = root / AeronDriver.mediaDirName
            Scope.run {
                withExternalDriver(dir) {
                    AeronClient.connect(dir).map { client =>
                        for
                            ready         <- Latch.init(1)
                            consumerFiber <- Fiber.initUnscoped {
                                Topic.run(client) {
                                    ready.release.andThen(Topic.stream[Long]("aeron:ipc").take(2).run)
                                }
                            }
                            _        <- ready.await
                            _        <- Topic.run(client)(Topic.publish[Long]("aeron:ipc")(Stream.init(Seq(10L, 20L))))
                            received <- consumerFiber.get
                        yield assert(received == Seq(10L, 20L), s"expected Seq(10L,20L) but got $received")
                    }
                }
            }
        }
    }

    "client closes exactly once on normal Scope exit (close-count == 1)" in {
        Path.run(Path.tempDir("kyo-aeron-client-l3")).map { root =>
            val dir = root / AeronDriver.mediaDirName
            AtomicInt.init(0).map { closeCount =>
                Scope.run {
                    withExternalDriver(dir) {
                        connectWithCloseCount(dir, closeCount).map { client =>
                            Topic.run(client) {
                                for
                                    started <- Latch.init(1)
                                    fiber   <- Fiber.initUnscoped(using Topic.isolate)(
                                        started.release.andThen(Topic.stream[Int]("aeron:ipc").take(1).run)
                                    )
                                    _ <- started.await
                                    _ <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(42))))
                                    _ <- fiber.get
                                yield ()
                            }.andThen {
                                // After run(client) returns, the client must NOT be closed yet.
                                closeCount.get.map { countMidScope =>
                                    assert(countMidScope == 0, s"run(client) must NOT close the client; count=$countMidScope mid-scope")
                                }
                            }
                        }
                    }
                }.andThen {
                    closeCount.get.map { countAfterScope =>
                        assert(countAfterScope == 1, s"expected close-count == 1 after Scope exit but got $countAfterScope")
                    }
                }
            }
        }
    }

    // The timeout interrupts the stream; the Scope finalizer must still fire and close the client.
    "cancellation/timeout releases the client; close-count == 1" in {
        Path.run(Path.tempDir("kyo-aeron-client-l4")).map { root =>
            val dir = root / AeronDriver.mediaDirName
            AtomicInt.init(0).map { closeCount =>
                Scope.run {
                    withExternalDriver(dir) {
                        connectWithCloseCount(dir, closeCount).map { client =>
                            Async.timeout(200.millis) {
                                Topic.run(client) {
                                    Topic.stream[Int]("aeron:ipc").take(1000).run
                                }
                            }.map(Maybe(_)).handle(Abort.recover[Timeout](_ => Absent))
                        }
                    }
                }.andThen {
                    closeCount.get.map { count =>
                        assert(count == 1, s"expected close-count == 1 after scope+timeout but got $count")
                    }
                }
            }
        }
    }

    // A fresh empty tempDir has no driver, so the connect waits out the ~10 s driver-timeout before
    // failing. Same typed failure as Topic.run(absentDir): both go through the shared external primitive.
    "absent-driver connect aborts TopicTransportFailedException (JVM/JS/Native)" in {
        Path.run(Path.tempDir("kyo-aeron-absent-client")).map { absentDir =>
            Scope.run {
                Abort.run[TopicException] {
                    AeronClient.connect(absentDir).map { client =>
                        Topic.run(client)(())
                    }
                }
            }.map { result =>
                assert(
                    result.isFailure && result.failure.forall(_.isInstanceOf[TopicTransportFailedException]),
                    s"expected Result.Failure(_: TopicTransportFailedException) but got $result"
                )
            }
        }
    }

    // Scope.run discharges Scope but leaves Async and Abort in the row, so what escapes is a pending
    // computation, never a bare open client: the positive ascription must compile, the negative must not.
    "AeronClient cannot escape its Scope (type-level proof)" in {
        val _: AeronClient < (Async & Abort[TopicTransportFailedException]) =
            Scope.run(AeronClient.connect(Path("/dev/shm", "type-probe-only-never-runs")))
        typeCheckFailure("""
            import kyo.*
            val escaped: AeronClient = Scope.run(AeronClient.connect(Path("/dev/shm", "never-runs")))
        """)
        succeed
    }

    // Every other method is unused by this reproduction: the caller is interrupted at the connect join before any transport op.
    final private class FakeBindings(
        connectFiber: Promise.Unsafe[Ffi.Handle[AeronClientHandle], Any],
        onConnect: () => Unit,
        closed: java.util.concurrent.atomic.AtomicBoolean
    ) extends AeronBindings:
        def clientConnect(dir: String)(using AllowUnsafe): Fiber.Unsafe[Ffi.Handle[AeronClientHandle], Any] =
            onConnect()
            connectFiber
        def clientClose(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Unit = closed.set(true)
        def driverStart(dir: String, clientLivenessNs: Long, publicationUnblockNs: Long)(using
            AllowUnsafe
        ): Fiber.Unsafe[Ffi.Handle[AeronDriverHandle], Any]                             = ???
        def driverClose(driver: Ffi.Handle[AeronDriverHandle])(using AllowUnsafe): Unit = ???
        def asyncAddPublication(client: Ffi.Handle[AeronClientHandle], uri: String, streamId: Int)(using
            AllowUnsafe
        ): Maybe[Ffi.Handle[AeronAsyncPub]]                                                                                  = ???
        def asyncAddPublicationPoll(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Long                               = ???
        def asyncAddPublicationGet(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Maybe[Ffi.Handle[AeronPublication]] = ???
        def asyncAddPublicationFree(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Unit                               = ???
        def asyncAddPublicationErrCode(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Int                             = ???
        def asyncAddPublicationErrMsg(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Ffi.Borrowed[String]             = ???
        def publicationIsConnected(pub: Ffi.Handle[AeronPublication])(using AllowUnsafe): Int                                = ???
        def publicationOffer(pub: Ffi.Handle[AeronPublication], buffer: Buffer[Byte], length: Int)(using AllowUnsafe): Long  = ???
        def publicationMaxMessageLength(pub: Ffi.Handle[AeronPublication])(using AllowUnsafe): Int                           = ???
        def publicationClose(pub: Ffi.Handle[AeronPublication])(using AllowUnsafe): Unit                                     = ???
        def asyncAddSubscription(client: Ffi.Handle[AeronClientHandle], uri: String, streamId: Int)(using
            AllowUnsafe
        ): Maybe[Ffi.Handle[AeronAsyncSub]]                                                                                    = ???
        def asyncAddSubscriptionPoll(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Long                                = ???
        def asyncAddSubscriptionGet(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Maybe[Ffi.Handle[AeronSubscription]] = ???
        def asyncAddSubscriptionFree(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Unit                                = ???
        def asyncAddSubscriptionErrCode(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Int                              = ???
        def asyncAddSubscriptionErrMsg(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Ffi.Borrowed[String]              = ???
        def subscriptionIsConnected(sub: Ffi.Handle[AeronSubscription])(using AllowUnsafe): Int                                = ???
        def subscriptionPoll(sub: Ffi.Handle[AeronSubscription], dst: Buffer[Byte], dstCap: Int)(using AllowUnsafe): Long      = ???
        def subscriptionClose(sub: Ffi.Handle[AeronSubscription])(using AllowUnsafe): Unit                                     = ???
        def hasClientError(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Int                                      = ???
        def clientErrorMsg(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Ffi.Borrowed[String]                     = ???
        def clientErrorCode(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Int                                     = ???
        def testInjectError(client: Ffi.Handle[AeronClientHandle], errcode: Int, errmsg: String)(using AllowUnsafe): Unit      = ???
    end FakeBindings

    // Deterministic via the seam: a fake binding gates the connect fiber and the interrupt is registered on it via onComplete (LIFO before the resume).
    "an interrupt landing at the connect join closes the connected client" in {
        val closed        = new java.util.concurrent.atomic.AtomicBoolean(false)
        val connectCalled = new java.util.concurrent.atomic.AtomicBoolean(false)
        for
            connectFiber <- Sync.Unsafe.defer(Promise.Unsafe.init[Ffi.Handle[AeronClientHandle], Any]())
            fake = new FakeBindings(connectFiber, () => connectCalled.set(true), closed)
            fiber <- Fiber.initUnscoped(Scope.run(
                Scope.acquireRelease(AeronPlatformTransport.externalWith("/fake", fake))(rt =>
                    Sync.Unsafe.defer(rt.close())
                ).andThen(Async.never)
            ))
            _         <- assertEventually(Sync.defer(connectCalled.get()))
            _         <- assertEventually(connectFiber.safe.waiters.map(_ >= 1))
            _         <- connectFiber.safe.onComplete(_ => fiber.interrupt.unit)
            _         <- Sync.Unsafe.defer(connectFiber.completeDiscard(Result.succeed(Ffi.Handle.wrap[AeronClientHandle](new AnyRef))))
            _         <- fiber.getResult
            wasClosed <- Abort.run[Timeout](Async.timeout(1.second)(assertEventually(Sync.defer(closed.get())))).map(_.isSuccess)
        yield assert(wasClosed, "the connected client was never closed after the connect join was interrupted")
        end for
    }

end AeronClientTest
