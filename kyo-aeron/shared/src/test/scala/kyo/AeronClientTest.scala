package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronRuntime
import kyo.internal.AeronTransport

class AeronClientTest extends Test:

    /** Keeps an external Aeron driver running at `dir` for the duration of `body`. Mirrors the helper in
      * TopicUniformInvariantsTest; duplicated to avoid depending on that class's private helper.
      */
    private def withExternalDriver[A](dir: Path)(body: => A < (Async & Abort[TopicException] & Scope))(using
        Frame
    ): A < (Async & Abort[TopicException] & Scope) =
        for
            release <- Latch.init(1)
            ready   <- Latch.init(1)
            driverFiber <- Fiber.initUnscoped {
                AeronPlatform.embedded(dir.unsafe.show).map { runtime =>
                    ready.release.andThen(release.await).andThen(Sync.Unsafe.defer(runtime.close()))
                }
            }
            _      <- ready.await
            result <- Sync.ensure(release.release)(body)
            _      <- driverFiber.get
        yield result

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
                    def transport: AeronTransport = realRuntime.transport
                    def close()(using AllowUnsafe): Unit =
                        closeCount.unsafe.incrementAndGet()
                        realRuntime.close()
                    end close
                Sync.Unsafe.defer(AeronClient.Unsafe.fromRuntime(countingRuntime).safe)
            }
        )(client => Sync.Unsafe.defer(client.unsafe.close()))

    "connect + single Topic.run(client) round-trip: received == Chunk(1L,2L)" in {
        Path.tempDir("kyo-aeron-client-l1").map { dir =>
            Scope.run {
                withExternalDriver(dir) {
                    AeronClient.connect(dir).map { client =>
                        Topic.run(client) {
                            for
                                started <- Latch.init(1)
                                fiber <- Fiber.initUnscoped(using Topic.isolate)(
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
        Path.tempDir("kyo-aeron-client-l2").map { dir =>
            Scope.run {
                withExternalDriver(dir) {
                    AeronClient.connect(dir).map { client =>
                        for
                            ready <- Latch.init(1)
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
        Path.tempDir("kyo-aeron-client-l3").map { dir =>
            AtomicInt.init(0).map { closeCount =>
                Scope.run {
                    withExternalDriver(dir) {
                        connectWithCloseCount(dir, closeCount).map { client =>
                            Topic.run(client) {
                                for
                                    started <- Latch.init(1)
                                    fiber <- Fiber.initUnscoped(using Topic.isolate)(
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
        Path.tempDir("kyo-aeron-client-l4").map { dir =>
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
        Path.tempDir("kyo-aeron-absent-client").map { absentDir =>
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

end AeronClientTest
