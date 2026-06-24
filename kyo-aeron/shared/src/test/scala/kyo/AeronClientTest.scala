package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronRuntime
import kyo.internal.AeronTransport

// Focused tests for AeronClient: the Scope-managed, shareable Aeron client handle.
//
// Covers: connect + run(client) round-trip, shared-client multi-run, close-exactly-once
// (normal + cancellation), absent-driver typed failure, and the compile-time scope-escape anti-case.
//
// All leaves are cross-platform (JVM, JS, Native). The close-exactly-once probes wrap the real
// AeronRuntime (private[kyo], visible from package kyo tests) in an AtomicInt-counting proxy
// before passing to AeronClient.Unsafe.fromRuntime.
class AeronClientTest extends Test:

    // Keeps an external Aeron driver running at `dir` for the duration of `body`.
    // Mirrors the helper in TopicUniformInvariantsTest; duplicated here to avoid a cross-class
    // visibility dependency on that class's private helper.
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

    // Builds an AeronClient from a connected AeronRuntime, manually managing its lifecycle
    // with Scope.acquireRelease. This bypasses AeronClient.connect to allow wrapping the
    // AeronRuntime in a close-counting proxy. The acquire body connects via
    // AeronPlatform.external and wraps the result; the release closes via the proxy.
    private def connectWithCloseCount(
        aeronDir: Path,
        closeCount: AtomicInt
    )(using Frame): AeronClient < (Scope & Async & Abort[TopicException]) =
        Scope.acquireRelease(
            AeronPlatform.external(aeronDir.unsafe.show).map { realRuntime =>
                // Wrap the real runtime in an AtomicInt-counting proxy.
                // AeronRuntime is private[kyo], accessible from package kyo tests.
                // Inside close()(using AllowUnsafe) the AllowUnsafe is in scope for unsafe ops.
                val countingRuntime: AeronRuntime = new AeronRuntime:
                    def transport: AeronTransport = realRuntime.transport
                    def close()(using AllowUnsafe): Unit =
                        // AllowUnsafe is in scope from the close() signature parameter.
                        closeCount.unsafe.incrementAndGet()
                        realRuntime.close()
                    end close
                Sync.Unsafe.defer(AeronClient.Unsafe.fromRuntime(countingRuntime).safe)
            }
        )(client => Sync.Unsafe.defer(client.unsafe.close()))

    // ------------------------------------------------------------------
    // connect + one Topic.run(client) round-trip.
    //
    // An external driver is kept open by withExternalDriver; AeronClient.connect(dir) inside
    // Scope.run acquires a client; Topic.run(client) publishes Seq(1L,2L) over aeron:ipc and
    // streams 2 back. received == Chunk(1L,2L) proves the run(client) overload drives a live
    // round-trip funneled through runWith.
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // one shared client backs multiple Topic.run(client) scopes.
    //
    // AeronClient.connect(dir) once; producer Topic.run(client) publishes Seq(10L,20L) and
    // consumer Topic.run(client) streams 2, via a Latch-synchronized pattern. The consumer
    // receives Chunk(10L,20L). run(client) does NOT close the client; the same client is
    // reused for both scopes.
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // client closes exactly once when its Scope completes (normal).
    //
    // Uses connectWithCloseCount to intercept close() at the AeronRuntime level.
    // After Scope.run completes, closeCount == 1 proves the Scope.acquireRelease release
    // ran exactly once. Also verified: run(client) does NOT close (count stays 0 mid-scope).
    //
    // ------------------------------------------------------------------
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
                    // After Scope.run exits, the Scope.acquireRelease release ran exactly once.
                    closeCount.get.map { countAfterScope =>
                        assert(countAfterScope == 1, s"expected close-count == 1 after Scope exit but got $countAfterScope")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // cancellation/timeout releases the client.
    //
    // AeronClient.connect(dir) inside Scope.run; Async.timeout(200.millis) wrapping a
    // Topic.run(client) streaming take(1000). The timeout fires; outcome is Absent (recovered);
    // Scope finalizer fires on interruption, closing the counted client exactly once.
    //
    // ------------------------------------------------------------------
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
                    // After the scope (which the timeout triggers the finalizer for),
                    // the client must have been closed exactly once by the Scope.
                    closeCount.get.map { count =>
                        assert(count == 1, s"expected close-count == 1 after scope+timeout but got $count")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // connect to a dir with no driver aborts TopicTransportFailedException.
    //
    // A fresh empty Path.tempDir has no driver running. AeronClient.connect(dir) inside
    // Scope.run under Abort.run[TopicException] aborts Result.Failure(_: TopicTransportFailedException).
    // The same typed failure as Topic.run(absentDir): both consume the one
    // shared external primitive.
    //
    // Only the failure path is slow (~10 s driver-timeout). The framework's per-test timeout
    // covers it.
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // a still-open AeronClient cannot escape its Scope (compile-time anti-case).
    //
    // The type-level proof: `Scope.run(AeronClient.connect(dir))` has type
    // `AeronClient < (Async & Abort[TopicTransportFailedException])`, NOT `AeronClient`.
    // The Scope row is discharged but Async and Abort[TopicTransportFailedException] remain, so the escaping
    // value is NOT a bare open client; it is a pending computation that, when run, would yield
    // a CLOSED client (Scope.run fired the finalizer). Binding the open lifetime to Scope makes
    // the resource-escape unrepresentable as a bare value.
    // ------------------------------------------------------------------
    "AeronClient cannot escape its Scope (type-level proof)" in {
        // Compile-time proof: the type of Scope.run(AeronClient.connect(dir)) is
        // AeronClient < (Async & Abort[TopicTransportFailedException]), never a bare AeronClient.
        // We prove this by the ascription below (it must compile; the scope-escaped value is
        // still a pending computation, not an open client).
        val _: AeronClient < (Async & Abort[TopicTransportFailedException]) =
            Scope.run(AeronClient.connect(Path("/dev/shm", "type-probe-only-never-runs")))
        // The above type annotation is the proof: Scope.run discharges Scope but not Async/Abort.
        // There is no way to get a bare `AeronClient` (with no effects) from connect.
        //
        // Negative proof (the complement): binding the Scope-discharged value to a bare AeronClient
        // must NOT compile, because the Async & Abort effects remain in the row. typeCheckFailure
        // fails the test if the snippet ever compiles, pinning the resource-escape impossibility.
        typeCheckFailure("""
            import kyo.*
            val escaped: AeronClient = Scope.run(AeronClient.connect(Path("/dev/shm", "never-runs")))
        """)
        succeed
    }

end AeronClientTest
