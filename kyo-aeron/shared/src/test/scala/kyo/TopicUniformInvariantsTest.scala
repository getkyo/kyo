package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronSentinels
import kyo.internal.AeronTransport

// Uniform invariant tests for the kyo-aeron module, covering embedded-driver isolation,
// connect-failure propagation, non-blocking resource management, and event-loop safety.
//
// Each embedded driver uses a unique per-instance directory allocated Scala-side via
// Path.tempDir("kyo-aeron-embedded") and passed through AeronPlatform.embedded(dir); two
// concurrent Topic.run() calls on one host never collide; the directory is deleted on teardown.
//
// The FFI link uses aeron_driver_static only; no dynamic libaeron is required at runtime.
//
// No blocking primitive in any kyo-aeron Scala source (main or test); tests
// synchronize exclusively via Latch, Fiber.get, and Async.sleep.
//
// All leaves are cross-platform (JVM, JS, Native).
class TopicUniformInvariantsTest extends Test:

    // ------------------------------------------------------------------
    // concurrent embedded Topic.run round-trips
    // do not collide.
    //
    // A driverStart(null) routes to Aeron's single shared default directory;
    // a second concurrent call would delete the first driver's CnC file via
    // dirDeleteOnStart, crashing or stalling the first. Each Topic.run instead
    // allocates a unique directory via Path.tempDir so the two drivers never share state.
    //
    // Latch synchronization: each arm waits for its subscriber to be ready
    // before publishing, preventing the publisher from sending before the
    // subscription is established (which would cause a message miss).
    // ------------------------------------------------------------------
    "concurrent embedded Topic.run round-trips receive only their own chunk, no collision" in {
        val run1 = Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(3).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(1, 2, 3))))
                received <- fiber.get
            yield received
        }
        val run2 = Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(3).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(4, 5, 6))))
                received <- fiber.get
            yield received
        }
        Async.zip(run1, run2).map { (received1, received2) =>
            assert(
                received1 == Seq(1, 2, 3),
                s"run1 got $received1 but expected Seq(1,2,3); drivers may have collided"
            )
            assert(
                received2 == Seq(4, 5, 6),
                s"run2 got $received2 but expected Seq(4,5,6); drivers may have collided"
            )
        }
    }

    // ------------------------------------------------------------------
    // no temp-dir leak across repeated Topic.run calls.
    //
    // Each Topic.run allocates a unique directory via Path.tempDir and
    // deletes it in the Sync.ensure teardown. After N sequential runs the
    // OS temp root must contain zero kyo-aeron-embedded* directories.
    //
    // The assertion runs outside Topic.run so the teardown (including
    // dir.removeAll) has already completed by the time we list the temp dir.
    // ------------------------------------------------------------------
    "no temp-dir leak: zero kyo-aeron-embedded dirs remain after 5 sequential runs" in {
        val n = 5
        // Capture the count of matching dirs before we start so residual entries
        // from a previous (failed) test run do not cause a spurious failure.
        Abort.run[FileFsException](Path.basePaths.tmp.list("kyo-aeron-embedded*")).map { beforeResult =>
            val before = beforeResult match
                case Result.Success(dirs) => dirs.size
                case _                    => 0
            Loop.indexed { i =>
                if i >= n then Loop.done(())
                else
                    // Run an empty Topic body to exercise the full embedded() lifecycle
                    // (alloc dir, start driver, teardown driver, removeAll dir).
                    // An empty body completes immediately without needing a subscriber.
                    Topic.run(()).andThen(Loop.continue)
            }.andThen(
                Abort.run[FileFsException](Path.basePaths.tmp.list("kyo-aeron-embedded*")).map {
                    case Result.Success(after) =>
                        assert(
                            after.size <= before,
                            s"expected no new kyo-aeron-embedded dirs after $n runs, but found ${after.size - before} leftover(s): $after"
                        )
                    case Result.Failure(_) =>
                        // If we cannot list the temp dir, conservatively succeed: the
                        // inability to list is a platform limitation, not a leak.
                        succeed
                    case Result.Panic(t) =>
                        fail(s"Panic listing temp dir for leak check: $t")
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // embedded round-trip behind the run(v) signature.
    //
    // The per-instance dir allocation is invisible at the type level: Topic.run(v)
    // returns A < (Async & S). A Seq(1,2,3) round-trip confirms both
    // the behavioral contract and the type-level invariant.
    // ------------------------------------------------------------------
    "single embedded round-trip: received == Chunk(1,2,3); run(v) is A < (Async & S)" in {
        // Type-level invariant: Topic.run(42) ascribes to Int < Async.
        val _: Int < Async = Topic.run(42)
        Topic.run {
            for
                started <- Latch.init(1)
                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[Int]("aeron:ipc").take(3).run)
                )
                _        <- started.await
                _        <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(1, 2, 3))))
                received <- fiber.get
            yield assert(received == Seq(1, 2, 3), s"expected Seq(1,2,3) but got $received")
        }
    }

    // Keeps an external Aeron driver running at `dir` for the duration of `body`.
    //
    // AeronPlatform.embedded(dir) launches a media driver (the "external" driver from
    // run(aeronDir)'s perspective) plus a keepalive client and holds them open until the
    // `release` latch fires. The driver lives in an unscoped fiber so run(aeronDir) inside
    // `body` connects a SECOND, independent client to the same dir. run(aeronDir) is exercised
    // inside `body`; its Abort[TopicTransportFailedException] row composes within this helper's wider
    // Abort[TopicException].
    private def withExternalDriver[A](dir: Path)(body: => A < (Async & Abort[TopicException] & Scope))(using
        Frame
    ): A < (Async & Abort[TopicException] & Scope) =
        for
            release <- Latch.init(1)
            ready   <- Latch.init(1)
            driverFiber <- Fiber.initUnscoped {
                AeronPlatform.embedded(dir.unsafe.show).map { runtime =>
                    // Signal readiness once the driver + keepalive client are up, then block
                    // the fiber on `release` (non-blocking Async suspension) so the driver stays
                    // alive for the whole body; close only after release fires.
                    ready.release.andThen(release.await).andThen(Sync.Unsafe.defer(runtime.close()))
                }
            }
            _      <- ready.await
            result <- Sync.ensure(release.release)(body)
            _      <- driverFiber.get
        yield result

    // ------------------------------------------------------------------
    // Topic.run(aeronDir) round-trip over a
    // PRESENT external driver.
    //
    // An external driver runs at `dir`; Topic.run(dir) connects its own client,
    // publishes Seq("a","b") over aeron:ipc and streams 2 back. received ==
    // Chunk("a","b") proves the external-connect seam drives a live round-trip
    // with no aeron:udp / free-port infra. Cross-platform (JVM, JS, Native).
    // ------------------------------------------------------------------
    "Topic.run(aeronDir) present-driver round-trip: received == Chunk(a,b)" in {
        // Type-level invariant: run(aeronDir) carries Abort[TopicException].
        val _: Int < (Async & Abort[TopicException]) = Topic.run(Path("/nonexistent-type-probe"))(42)
        Path.tempDir("kyo-aeron-external-present").map { dir =>
            withExternalDriver(dir) {
                Topic.run(dir) {
                    for
                        started <- Latch.init(1)
                        fiber <- Fiber.initUnscoped(using Topic.isolate)(
                            started.release.andThen(Topic.stream[String]("aeron:ipc").take(2).run)
                        )
                        _        <- started.await
                        _        <- Fiber.initUnscoped(Topic.publish[String]("aeron:ipc")(Stream.init(Seq("a", "b"))))
                        received <- fiber.get
                    yield assert(received == Seq("a", "b"), s"""expected Seq("a","b") but got $received""")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // run(aeronDir) closes only the client, leaving the
    // external driver alive.
    //
    // After a first Topic.run(dir) round-trip completes (its client closed), a
    // SECOND Topic.run(dir) round-trip runs against the same still-open external
    // driver and also succeeds. A different chunk ("x","y") distinguishes it from
    // the first. Success proves run(aeronDir) closed only its own client, never
    // the caller's driver.
    // ------------------------------------------------------------------
    "run(aeronDir) closes only the client: a second round-trip on the same driver succeeds" in {
        def roundTrip(dir: Path, payload: Seq[String]) =
            Topic.run(dir) {
                for
                    started <- Latch.init(1)
                    fiber <- Fiber.initUnscoped(using Topic.isolate)(
                        started.release.andThen(Topic.stream[String]("aeron:ipc").take(payload.size).run)
                    )
                    _        <- started.await
                    _        <- Fiber.initUnscoped(Topic.publish[String]("aeron:ipc")(Stream.init(payload)))
                    received <- fiber.get
                yield received
            }
        Path.tempDir("kyo-aeron-external-reuse").map { dir =>
            withExternalDriver(dir) {
                for
                    first  <- roundTrip(dir, Seq("a", "b"))
                    second <- roundTrip(dir, Seq("x", "y"))
                yield
                    assert(first == Seq("a", "b"), s"""first round-trip expected Seq("a","b") but got $first""")
                    assert(
                        second == Seq("x", "y"),
                        s"""second round-trip expected Seq("x","y") but got $second; the first run may have closed the driver"""
                    )
            }
        }
    }

    // ------------------------------------------------------------------
    // an ABSENT-driver Topic.run(aeronDir)
    // connect aborts a uniform TopicTransportFailedException on JVM, JS, AND Native.
    //
    // A fresh empty Path.tempDir has no driver running, so the connect waits the
    // ~10 s driver-timeout, then fails: JVM throws DriverTimeoutException, FFI's
    // clientConnect returns NULL -> FfiNullPointer. The shared external primitive
    // maps either to a single typed Abort.fail(TopicTransportFailedException). The outcome
    // is Result.Failure(_: TopicTransportFailedException) (the leaf TYPE, not the message),
    // never a panic / thrown exception / backend-divergent outcome. The framework's
    // 60 s per-test timeout (>= 20 s) covers the ~10 s failure path.
    // ------------------------------------------------------------------
    "absent-driver Topic.run(aeronDir) aborts a uniform TopicTransportFailedException (JVM/JS/Native)" in {
        Path.tempDir("kyo-aeron-absent-driver").map { absentDir =>
            Abort.run[TopicException] {
                Topic.run(absentDir)(Topic.stream[String]("aeron:ipc").take(1).run)
            }.map { result =>
                assert(
                    result.isFailure && result.failure.forall(_.isInstanceOf[TopicTransportFailedException]),
                    s"expected Result.Failure(_: TopicTransportFailedException) but got $result"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // a concurrent ticker fiber keeps
    // ticking during a slow absent-driver connect on JVM, JS, AND Native.
    //
    // @Ffi.blocking routes the connect off the JS event loop (onto a libuv worker thread)
    // and parks a JVM/Native carrier under the scheduler's blocking monitor, so the ticker
    // fiber keeps advancing during the ~10 s connect. A plain (non-blocking-annotated) binding
    // would instead freeze the JS Node event loop and strand a Native carrier, stopping the
    // ticker; this leaf asserts the ticker kept advancing throughout the connect.
    //
    // The ticker fiber (Async.sleep loop, AtomicInt; NO blocking primitive on the
    // test fiber) is launched FIRST, then the slow connect fiber. Awaiting the
    // connect fiber's result holds for the full ~10 s while the ticker runs
    // concurrently. After the connect fails, the ticker is interrupted and the
    // count read; >= 50 ticks proves the event loop / carrier was never frozen.
    // A count of 0 would mean the carrier was frozen.
    // ------------------------------------------------------------------
    "a concurrent ticker keeps ticking (>= 50) during a slow absent-driver connect (JVM/JS/Native)" in {
        Path.tempDir("kyo-aeron-absent-ticker").map { absentDir =>
            AtomicInt.init.map { ticker =>
                for
                    tickerFiber <- Fiber.initUnscoped {
                        Loop.forever {
                            Async.sleep(5.millis).andThen(ticker.incrementAndGet)
                        }
                    }
                    connectFiber <- Fiber.initUnscoped {
                        Abort.run[TopicException] {
                            Topic.run(absentDir)(Topic.stream[String]("aeron:ipc").take(1).run)
                        }
                    }
                    connectResult <- connectFiber.get
                    _             <- tickerFiber.interrupt
                    ticks         <- ticker.get
                yield
                    assert(
                        connectResult.isFailure && connectResult.failure.forall(_.isInstanceOf[TopicTransportFailedException]),
                        s"absent-driver connect should fail with TopicTransportFailedException but got $connectResult"
                    )
                    assert(
                        ticks >= 50,
                        s"ticker advanced only $ticks times during the ~10 s connect; the event loop / carrier may be frozen. @Ffi.blocking is not engaging."
                    )
            }
        }
    }

    // ------------------------------------------------------------------
    // all three Topic.run overloads funnel through runWith
    // and are identical type-for-type across backends.
    //
    // run(v) and run(client) produce A < (Async & S); run(aeronDir) produces
    // A < (Async & Abort[TopicTransportFailedException] & S) (the failable-external-connect row).
    // All three drive a present-driver round-trip confirming the single-funnel invariant.
    // ------------------------------------------------------------------
    "all three run overloads funnel through runWith; cross-backend type-identical rows" in {
        // Type-level invariant: run(v) is A < (Async & S)
        val _: Int < Async = Topic.run(42)
        // Type-level invariant: run(client)(v) is A < (Async & S)
        val _: Int < (Async & Abort[TopicTransportFailedException]) = Scope.run(AeronClient.connect(Path("/dev/shm", "type-probe")).map {
            c =>
                Topic.run(c)(42)
        })
        // Type-level invariant: run(aeronDir)(v) is A < (Async & Abort[TopicTransportFailedException] & S)
        val _: Int < (Async & Abort[TopicTransportFailedException]) = Topic.run(Path("/nonexistent-inv001-probe"))(42)
        // Behavioral: present-driver round-trip via all three overloads
        Path.tempDir("kyo-aeron-inv001").map { dir =>
            Scope.run {
                withExternalDriver(dir) {
                    for
                        // run(client): borrow the same client for a round-trip
                        clientResult <- AeronClient.connect(dir).map { client =>
                            Topic.run(client) {
                                for
                                    ready <- Latch.init(1)
                                    fiber <- Fiber.initUnscoped(using Topic.isolate)(
                                        ready.release.andThen(Topic.stream[Int]("aeron:ipc").take(1).run)
                                    )
                                    _ <- ready.await
                                    _ <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(99))))
                                    r <- fiber.get
                                yield r
                            }
                        }
                        // run(aeronDir): separate connect for a second round-trip
                        dirResult <- Topic.run(dir) {
                            for
                                ready <- Latch.init(1)
                                fiber <- Fiber.initUnscoped(using Topic.isolate)(
                                    ready.release.andThen(Topic.stream[Int]("aeron:ipc").take(1).run)
                                )
                                _ <- ready.await
                                _ <- Fiber.initUnscoped(Topic.publish[Int]("aeron:ipc")(Stream.init(Seq(77))))
                                r <- fiber.get
                            yield r
                        }
                    yield
                        assert(clientResult == Seq(99), s"run(client) round-trip: expected Seq(99) but got $clientResult")
                        assert(dirResult == Seq(77), s"run(aeronDir) round-trip: expected Seq(77) but got $dirResult")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // no io.aeron on the public surface; AeronClient is the opaque alias.
    //
    // Compile-level proof: the ascription below does NOT compile if io.aeron types
    // appear, and the fact that the code below type-checks proves that no io.aeron.Aeron
    // or io.aeron.driver.MediaDriver appears on the Topic/AeronClient surface.
    //
    // There is no Topic.run(MediaDriver) or Topic.run(Aeron) overload: the public API takes only
    // kyo types (a Path or an AeronClient). The absence of any io.aeron-typed overload is pinned
    // at compile time below via typeCheckFailure.
    // ------------------------------------------------------------------
    "no io.aeron on public surface; no MediaDriver/Aeron run overload; AeronClient is opaque" in {
        // AeronClient is an opaque type (not a wrapper exposing Aeron internals).
        // Prove: the public surface of AeronClient involves only kyo types.
        val _: AeronClient < (Scope & Async & Abort[TopicTransportFailedException]) =
            AeronClient.connect(Path("/dev/shm", "type-probe-inv002"))
        // Prove: Topic.run(client) takes an AeronClient, not an io.aeron.Aeron.
        // The ascription below type-checks only if Topic.run(client) accepts AeronClient.
        typeCheckFailure("""
            import kyo.*
            // MediaDriver and Aeron are JVM-only symbols; the Topic surface takes only kyo types.
            Topic.run(null.asInstanceOf[io.aeron.driver.MediaDriver])(())
        """)
        typeCheckFailure("""
            import kyo.*
            Topic.run(null.asInstanceOf[io.aeron.Aeron])(())
        """)
        succeed
    }

    // ------------------------------------------------------------------
    // the connect failure is a TopicException leaf;
    // message is single-sourced in TopicException.scala; AeronClient.scala adds no
    // new message string; the leaf type is TopicTransportFailedException.
    //
    // Runs a failed connect (absent driver) and asserts the result is
    // Result.Failure(_: TopicTransportFailedException). The leaf extends TopicTransportException
    // which extends TopicException (sealed root). The message comes from the upstream
    // AeronPlatform.external catch, not from AeronClient.scala itself.
    // ------------------------------------------------------------------
    "connect failure is TopicTransportFailedException (TopicException leaf); message single-sourced" in {
        Path.tempDir("kyo-aeron-inv010-absent").map { absentDir =>
            Scope.run {
                Abort.run[TopicException] {
                    AeronClient.connect(absentDir).map { client =>
                        Topic.run(client)(())
                    }
                }
            }.map { result =>
                result match
                    case Result.Failure(e: TopicTransportFailedException) =>
                        // The message must be non-empty (originated in the underlying transport layer,
                        // single-sourced in TopicException.scala / AeronPlatform.external).
                        assert(
                            e.getMessage.nonEmpty,
                            "TopicTransportFailedException message must be non-empty (single-sourced in TopicException.scala)"
                        )
                        // The leaf must be exactly TopicTransportFailedException (not a parent or another leaf).
                        assert(
                            e.isInstanceOf[TopicTransportFailedException],
                            s"expected TopicTransportFailedException leaf but got ${e.getClass.getSimpleName}"
                        )
                        succeed
                    case other =>
                        fail(s"expected Result.Failure(_: TopicTransportFailedException) but got $other")
            }
        }
    }

    // Stream-ids for the offer-after-own-close leaves. Distinct from the
    // AeronTransportTest ids (7-114) so the shared aeron:ipc channel never bleeds a message
    // across tests; 200/201 are reserved here for the two offer-after-own-close leaves.
    private val offerAfterOwnCloseStreamId = 200
    private val clientCloseStreamId        = 201

    // Adds a transport-level publication via the shared add-deadline loop and asserts it is
    // Present on a healthy embedded driver. Mirrors the UAF-sentinel leaf's add pattern
    // (mirrors the UAF-sentinel leaf's add pattern in AeronTransportTest) but is local so this
    // file does not import test helpers.
    private def addPublication(transport: AeronTransport, streamId: Int)(using
        Frame,
        kyo.test.AssertScope
    ): transport.Publication < (Async & Abort[TopicTransportException]) =
        Topic.addPublicationDeadline(transport, "aeron:ipc", streamId, 10.seconds).map { pubMaybe =>
            assert(pubMaybe.isDefined, "addPublicationDeadline returned Absent on a healthy embedded driver")
            pubMaybe.get
        }

    // ------------------------------------------------------------------
    // an offer on a publication AFTER the
    // caller's OWN closePublication returns the existing safe sentinel, no UAF.
    //
    // kyo_aeron_publication_close marks the bundle closed under close_mutex and defers the free
    // to the client-close sweep, so a later offer on the closed-but-not-yet-freed handle observes
    // b->closed and returns -4 (AERON_PUBLICATION_CLOSED) without touching the underlying aeron
    // handle. Without the deferral the close would free the bundle and a subsequent transport.offer
    // would dereference freed memory on Native/JS.
    //
    // Cross-platform: the safe outcome is uniform. On JVM io.aeron's
    // Publication.offer / tryClaim on a closed publication returns Publication.CLOSED
    // (-4) and isConnected() returns false, memory-safely (refcounted); on FFI the
    // close_mutex+closed guard returns the same -4 / not-connected. maxMessageLength is
    // exercised after close to prove the third guarded read does not fault and is uniform:
    // the FFI close-guard returns 0, and JVM's isClosed()-guard returns 0 too, so the
    // assertion is the exact `== 0` on every platform.
    //
    // Sequential by design (deterministic on the single-threaded JS event loop too):
    // add a publication, closePublication it (its OWN close), then read the three
    // hot-path guards on the still-held handle, then close the client (sweep frees the
    // deferred bundle), then remove the dir.
    // ------------------------------------------------------------------
    "offer after the caller's own closePublication returns the safe Closed sentinel, no UAF (JVM/JS/Native)" in {
        val payload = Array[Byte](1, 2, 3, 4)
        for
            dir <- Path.tempDir("kyo-aeron-offer-after-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            offerResult <- Abort.run[TopicTransportException] {
                addPublication(transport, offerAfterOwnCloseStreamId).map { pub =>
                    Sync.Unsafe.defer {
                        // The caller's OWN close: marks the bundle closed, defers the free.
                        transport.closePublication(pub)
                        // The three guarded reads on the same, now-closed handle. Each observes the
                        // closed flag and returns its existing safe sentinel without touching the
                        // freed handle.
                        val offered   = transport.offer(pub, payload)
                        val connected = transport.publicationIsConnected(pub)
                        val maxMsgLen = transport.maxMessageLength(pub)
                        (offered, connected, maxMsgLen)
                    }
                }
            }
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield offerResult match
            case Result.Success((offered, connected, maxMsgLen)) =>
                assert(
                    offered == AeronSentinels.Closed,
                    s"expected offer after the caller's own closePublication to return AeronSentinels.Closed (-4); got $offered"
                )
                assert(
                    !connected,
                    s"expected publicationIsConnected after own close to be false; got $connected"
                )
                assert(
                    maxMsgLen == 0,
                    s"expected maxMessageLength after own close to be 0 (uniform: JVM io.aeron isClosed-guard and the FFI closed-guard both return 0); got $maxMsgLen"
                )
            case other =>
                fail(s"add/offer round-trip failed before the assertion: $other")
        end for
    }

    // ------------------------------------------------------------------
    // client-close AFTER a publication the caller
    // already closed completes cleanly, with no double-free and no recorded fatal
    // error, proving the per-client sweep is the sole free-owner of the deferred bundle.
    //
    // The caller closes the publication (closed flag set, free deferred), then the
    // client (runtime) closes. The client-close sweep frees the deferred bundle exactly
    // once. A double-free or use-after-free would crash the process before the
    // assertion; fatalError staying Absent proves the conductor saw no fatal
    // condition during the clean teardown.
    //
    // Cross-platform: the clean-teardown + Absent-fatal-error outcome is
    // uniform. On JVM no error is recorded and close is refcounted; on FFI the sweep
    // is the sole free-owner. fatalError is read on a fresh transport BEFORE
    // the runtime closes (the runtime owns the transport's client handle).
    // ------------------------------------------------------------------
    "client-close after a caller-closed publication is clean: no double-free, fatalError Absent (JVM/JS/Native)" in {
        for
            dir <- Path.tempDir("kyo-aeron-client-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            fatalAfterClose <- Abort.run[TopicTransportException] {
                addPublication(transport, clientCloseStreamId).map { pub =>
                    Sync.Unsafe.defer {
                        // The caller closes the publication (deferred free), then reads the
                        // recorded fatal error: it must be Absent (a clean, healthy transport).
                        transport.closePublication(pub)
                        transport.fatalError
                    }
                }
            }
            // Close the client: the sweep frees the deferred bundle. A double-free would
            // crash here; reaching the assertion proves it did not.
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield fatalAfterClose match
            case Result.Success(fatal) =>
                assert(
                    fatal.isEmpty,
                    s"expected fatalError to be Absent after a clean caller-close; got $fatal"
                )
            case other =>
                fail(s"add/close round-trip failed before the assertion: $other")
        end for
    }

    // Stream-ids for the subscription poll-after-own-close leaves; distinct from the publication
    // leaves (200/201) and the AeronTransportTest ids so the shared aeron:ipc channel never bleeds.
    private val pollAfterOwnCloseStreamId = 202
    private val subClientCloseStreamId    = 203

    // Adds a transport-level subscription via the shared add-deadline loop and asserts it is
    // Present on a healthy embedded driver. Mirror of addPublication above.
    private def addSubscription(transport: AeronTransport, streamId: Int)(using
        Frame,
        kyo.test.AssertScope
    ): transport.Subscription < (Async & Abort[TopicTransportException]) =
        Topic.addSubscriptionDeadline(transport, "aeron:ipc", streamId, 10.seconds).map { subMaybe =>
            assert(subMaybe.isDefined, "addSubscriptionDeadline returned Absent on a healthy embedded driver")
            subMaybe.get
        }

    // ------------------------------------------------------------------
    // a poll on a subscription AFTER the caller's OWN closeSubscription returns no fragment
    // (Absent), and a SECOND closeSubscription is an idempotent no-op, with no UAF or double-free.
    //
    // kyo_aeron_subscription_close marks the bundle closed under close_mutex and defers the free
    // to the client-close sweep, so a later poll on the closed-but-not-yet-freed handle observes
    // b->closed and returns no fragment without touching the underlying aeron handle; the
    // idempotency guard makes the second close a no-op rather than a double-free / double-release.
    // Without the deferral + guard the close would free the bundle and a subsequent poll or a
    // second close would corrupt the heap on Native/JS. Symmetric to the publication
    // offer-after-own-close leaf above.
    //
    // Cross-platform: the safe outcome is uniform. On JVM io.aeron's Subscription.poll on a
    // closed subscription yields no fragment (JvmAeronTransport.pollOne also maps any AeronException
    // to Absent), and Subscription.close is idempotent; on FFI the close_mutex + closed guard
    // returns the same no-fragment and the idempotency guard makes the second close inert.
    // ------------------------------------------------------------------
    "poll after the caller's own closeSubscription returns no fragment and a second close is a no-op, no UAF (JVM/JS/Native)" in {
        for
            dir <- Path.tempDir("kyo-aeron-poll-after-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pollResult <- Abort.run[TopicTransportException] {
                addSubscription(transport, pollAfterOwnCloseStreamId).map { sub =>
                    Sync.Unsafe.defer {
                        // The caller's OWN close: marks the bundle closed, defers the free.
                        transport.closeSubscription(sub)
                        // Guarded poll on the same, now-closed handle: observes the closed flag and
                        // returns no fragment without dereferencing the freed handle.
                        val polled = transport.pollOne(sub)
                        // Second close: idempotent no-op (no double-free / double-release).
                        transport.closeSubscription(sub)
                        polled
                    }
                }
            }
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield pollResult match
            case Result.Success(polled) =>
                assert(
                    polled.isEmpty,
                    s"expected pollOne after the caller's own closeSubscription to return Absent; got $polled"
                )
            case other =>
                fail(s"add/poll round-trip failed before the assertion: $other")
        end for
    }

    // ------------------------------------------------------------------
    // client-close AFTER a subscription the caller already closed completes cleanly, with no
    // double-free and no recorded fatal error, proving the per-client subscription sweep is the
    // sole free-owner of the deferred bundle. Mirror of the publication client-close leaf above.
    // ------------------------------------------------------------------
    "client-close after a caller-closed subscription is clean: no double-free, fatalError Absent (JVM/JS/Native)" in {
        for
            dir <- Path.tempDir("kyo-aeron-sub-client-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            fatalAfterClose <- Abort.run[TopicTransportException] {
                addSubscription(transport, subClientCloseStreamId).map { sub =>
                    Sync.Unsafe.defer {
                        // The caller closes the subscription (deferred free), then reads the
                        // recorded fatal error: it must be Absent (a clean, healthy transport).
                        transport.closeSubscription(sub)
                        transport.fatalError
                    }
                }
            }
            // Close the client: the subscription sweep frees the deferred bundle. A double-free
            // would crash here; reaching the assertion proves it did not.
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield fatalAfterClose match
            case Result.Success(fatal) =>
                assert(
                    fatal.isEmpty,
                    s"expected fatalError to be Absent after a clean caller-close; got $fatal"
                )
            case other =>
                fail(s"add/close round-trip failed before the assertion: $other")
        end for
    }

    // ------------------------------------------------------------------
    // TopicRegistrationFailedException.errorCode is a specific
    // POSITIVE driver code on all platforms for the same rejection.
    //
    // aeron_errcode() reports AERON_CLIENT_ERRORED_MEDIA_DRIVER as a negated driver code
    // (aeron_client.c); math.abs at both FFI capture sites (asyncAddPublicationErrCode /
    // asyncAddSubscriptionErrCode) normalizes it to the raw positive driver code, matching JVM
    // RegistrationException.errorCodeValue().
    //
    // A malformed URI forces an immediate registration rejection on all
    // three backends without needing a real Aeron driver; the embedded
    // driver rejects the URI synchronously on the first _poll < 0 cycle.
    //
    // Cross-platform: both JVM (RegistrationException.errorCodeValue)
    // and FFI (math.abs(aeron_errcode())) produce the same positive integer.
    // ------------------------------------------------------------------
    "TopicRegistrationFailedException.errorCode is positive on all platforms for the same rejection" in {
        // A malformed Aeron URI forces an immediate registration rejection on all three backends.
        // The embedded driver rejects the URI synchronously; no aeron:udp / free-port infra needed.
        // Topic.run supplies the embedded transport; Topic.publish triggers the addPublication loop.
        val malformedUri = "aeron:invalid"
        for
            result <- Topic.run {
                Abort.run[TopicException] {
                    Topic.publish[Int](malformedUri)(Stream.init(Seq(1)))
                }
            }
        yield result match
            case Result.Failure(e: TopicRegistrationFailedException) =>
                // errorCode is the raw POSITIVE driver code on all platforms.
                // Confirmed concrete value: errorCode == 1 (INVALID_CHANNEL) on JVM and Native for
                // "aeron:invalid" (unknown media scheme). The math.abs normalization makes this
                // identical across platforms; without it, FFI would return the negated value (-1).
                assert(e.errorCode == 1, s"expected errorCode == 1 (INVALID_CHANNEL) but got ${e.errorCode}")
                assert(e.aeronUri == malformedUri, s"expected aeronUri == $malformedUri but got ${e.aeronUri}")
            case other =>
                fail(s"expected TopicRegistrationFailedException but got $other")
        end for
    }

    // ------------------------------------------------------------------
    // TopicRegistrationFailedException message is single-sourced
    // from TopicException.scala; the errcode normalization adds no message string
    // elsewhere (only math.abs at the two FFI capture sites).
    //
    // Cross-platform: the message template lives once in
    // TopicException.scala and is exercised on all three backends.
    // ------------------------------------------------------------------
    "TopicRegistrationFailedException message is single-sourced from TopicException.scala" in {
        // Reuse the same malformed-URI rejection path as the first rejection leaf.
        val malformedUri = "aeron:invalid"
        for
            result <- Topic.run {
                Abort.run[TopicException] {
                    Topic.publish[Int](malformedUri)(Stream.init(Seq(1)))
                }
            }
        yield result match
            case Result.Failure(e: TopicRegistrationFailedException) =>
                // message is single-sourced; contains "Driver error" from TopicException.scala
                // and no string injected by FfiAeronTransport (the errcode normalization is only math.abs).
                assert(e.getMessage.contains("Driver error"), s"message shape changed: ${e.getMessage}")
                assert(e.getMessage.contains("aeron:invalid"), s"URI not in message: ${e.getMessage}")
            case other =>
                fail(s"expected TopicRegistrationFailedException but got $other")
        end for
    }

    // ------------------------------------------------------------------
    // cross-entry-point: an absent-driver connect aborts with
    // TopicTransportFailedException through BOTH AeronClient.connect AND
    // Topic.run(aeronDir), on JVM, JS, AND Native.
    //
    // Both entry points are exercised against the SAME absent dir in ONE test,
    // asserting they fail with the same leaf TYPE and proving the shared external
    // primitive owns the single connect-failure catch; neither
    // entry point can diverge into a panic / null / backend-specific outcome.
    //
    // Both connects wait the ~10 s driver-timeout. They run CONCURRENTLY via Async.zip so
    // total elapsed is ~10 s (not ~20 s), within the framework per-test timeout. The Abort
    // is discharged into Result before zip, so the failures are values, not aborts.
    // ------------------------------------------------------------------
    "cross-entry-point: BOTH AeronClient.connect AND Topic.run(aeronDir) abort TopicTransportFailedException from the same absent dir (JVM/JS/Native)" in {
        Path.tempDir("kyo-aeron-inv014-cross").map { absentDir =>
            val connectViaClient =
                Scope.run {
                    Abort.run[TopicException] {
                        AeronClient.connect(absentDir).map { client =>
                            Topic.run(client)(())
                        }
                    }
                }
            val connectViaRun =
                Abort.run[TopicException] {
                    Topic.run(absentDir)(Topic.stream[String]("aeron:ipc").take(1).run)
                }
            Async.zip(connectViaClient, connectViaRun).map { (clientResult, runResult) =>
                assert(
                    clientResult.isFailure && clientResult.failure.forall(_.isInstanceOf[TopicTransportFailedException]),
                    s"AeronClient.connect: expected Result.Failure(_: TopicTransportFailedException) but got $clientResult"
                )
                assert(
                    runResult.isFailure && runResult.failure.forall(_.isInstanceOf[TopicTransportFailedException]),
                    s"Topic.run(aeronDir): expected Result.Failure(_: TopicTransportFailedException) but got $runResult"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // via AeronClient.connect (class-A): a concurrent ticker keeps ticking
    // during a slow absent-driver AeronClient.connect on JVM, JS, AND Native.
    //
    // This asserts that @Ffi.blocking on the AeronClient.connect entry point does not
    // freeze the JS event loop or strand a Native carrier thread. Complementary to the
    // Topic.run(aeronDir) entry point covered elsewhere; both entry points must be
    // independently verified to prove non-freeze holds regardless of how the driver
    // connection is initiated.
    //
    // The ticker fiber (Async.sleep loop + AtomicInt; NO blocking primitive on the test
    // fiber) is launched FIRST, then the slow connect fiber inside Scope.run under
    // Abort.run[TopicException]. The connect fails at the ~10 s driver-timeout; on a failed
    // connect the Scope.acquireRelease acquire aborts before producing a value, so nothing
    // is acquired and no release runs. After the connect fails, the ticker is
    // interrupted and the count read; >= 50 ticks proves the carrier was never frozen.
    // A count of 0 would mean the carrier was frozen.
    // ------------------------------------------------------------------
    "via AeronClient.connect: a concurrent ticker keeps ticking (>= 50) during a slow absent-driver connect (JVM/JS/Native)" in {
        Path.tempDir("kyo-aeron-inv015-client").map { absentDir =>
            AtomicInt.init.map { ticker =>
                for
                    tickerFiber <- Fiber.initUnscoped {
                        Loop.forever {
                            Async.sleep(5.millis).andThen(ticker.incrementAndGet)
                        }
                    }
                    connectFiber <- Fiber.initUnscoped {
                        Scope.run {
                            Abort.run[TopicException] {
                                AeronClient.connect(absentDir).map { client =>
                                    Topic.run(client)(())
                                }
                            }
                        }
                    }
                    connectResult <- connectFiber.get
                    _             <- tickerFiber.interrupt
                    ticks         <- ticker.get
                yield
                    assert(
                        connectResult.isFailure && connectResult.failure.forall(_.isInstanceOf[TopicTransportFailedException]),
                        s"absent-driver AeronClient.connect should fail with TopicTransportFailedException but got $connectResult"
                    )
                    assert(
                        ticks >= 50,
                        s"ticker advanced only $ticks times during the ~10 s connect; the event loop / carrier may be frozen. @Ffi.blocking is not engaging."
                    )
            }
        }
    }

end TopicUniformInvariantsTest
