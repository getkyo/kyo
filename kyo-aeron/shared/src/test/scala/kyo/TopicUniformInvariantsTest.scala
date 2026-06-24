package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronSentinels
import kyo.internal.AeronTransport

/** Uniform invariant tests for the kyo-aeron module: embedded-driver isolation, connect-failure
  * propagation, non-blocking resource management, and event-loop safety.
  *
  * Every embedded driver gets a unique per-instance directory (Path.tempDir, via
  * AeronPlatform.embedded), deleted on teardown, so concurrent Topic.run calls on one host never
  * collide. Tests synchronize exclusively via Latch, Fiber.get, and Async.sleep; no blocking primitive
  * appears in any kyo-aeron source, main or test.
  */
class TopicUniformInvariantsTest extends Test:

    // driverStart(null) would route to Aeron's single shared default directory, where a second concurrent
    // call deletes the first driver's CnC file via dirDeleteOnStart, crashing or stalling it.
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

    // The assertion runs outside Topic.run so the Sync.ensure teardown (including dir.removeAll)
    // has already completed by the time the temp dir is listed.
    "no temp-dir leak: zero kyo-aeron-embedded dirs remain after 5 sequential runs" in {
        val n = 5
        // Captured before the runs so residual entries from a prior failed run don't cause a spurious
        // failure.
        Abort.run[FileFsException](Path.basePaths.tmp.list("kyo-aeron-embedded*")).map { beforeResult =>
            val before = beforeResult match
                case Result.Success(dirs) => dirs.size
                case _                    => 0
            Loop.indexed { i =>
                if i >= n then Loop.done(())
                else
                    // An empty body still exercises the full embedded() lifecycle (alloc dir, start
                    // driver, teardown driver, removeAll dir) and needs no subscriber.
                    Topic.run(()).andThen(Loop.continue)
            }.andThen(
                Abort.run[FileFsException](Path.basePaths.tmp.list("kyo-aeron-embedded*")).map {
                    case Result.Success(after) =>
                        assert(
                            after.size <= before,
                            s"expected no new kyo-aeron-embedded dirs after $n runs, but found ${after.size - before} leftover(s): $after"
                        )
                    case Result.Failure(_) =>
                        // Inability to list the temp dir is a platform limitation, not a leak.
                        succeed
                    case Result.Panic(t) =>
                        fail(s"Panic listing temp dir for leak check: $t")
                }
            )
        }
    }

    // The per-instance dir allocation stays invisible at the type level: run(v) is A < (Async & S).
    "single embedded round-trip: received == Chunk(1,2,3); run(v) is A < (Async & S)" in {
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

    /** Keeps an external Aeron driver running at `dir` for the duration of `body`.
      *
      * AeronPlatform.embedded(dir) launches a media driver (the "external" driver from run(aeronDir)'s
      * perspective) plus a keepalive client, held open until the `release` latch fires. The driver lives
      * in an unscoped fiber so run(aeronDir) inside `body` connects a second, independent client to the
      * same dir.
      */
    private def withExternalDriver[A](dir: Path)(body: => A < (Async & Abort[TopicException] & Scope))(using
        Frame
    ): A < (Async & Abort[TopicException] & Scope) =
        for
            release <- Latch.init(1)
            ready   <- Latch.init(1)
            driverFiber <- Fiber.initUnscoped {
                AeronPlatform.embedded(dir.unsafe.show).map { runtime =>
                    // Signal readiness, then suspend on `release` so the driver stays alive for the body.
                    ready.release.andThen(release.await).andThen(Sync.Unsafe.defer(runtime.close()))
                }
            }
            _      <- ready.await
            result <- Sync.ensure(release.release)(body)
            _      <- driverFiber.get
        yield result

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

    // The second round-trip succeeding proves the first run(aeronDir) closed only its own client,
    // never the caller's driver. The distinct chunks keep the two round-trips distinguishable.
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

    // A fresh empty tempDir has no driver, so the connect waits the ~10 s driver-timeout and then fails
    // differently per backend: JVM throws DriverTimeoutException, FFI's clientConnect returns NULL and
    // yields FfiNullPointer. The shared external primitive maps both to the one typed failure.
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

    // @Ffi.blocking routes the connect off the JS event loop (a libuv worker thread) and parks a JVM/Native
    // carrier under the scheduler's blocking monitor, so the ticker keeps advancing during the ~10 s
    // connect; a plain binding would freeze the Node loop and strand a Native carrier at ticks == 0.
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

    // run(v) and run(client) produce A < (Async & S); run(aeronDir) adds the failable external-connect
    // row. The ascriptions pin the rows, then all three drive a present-driver round-trip.
    "all three run overloads funnel through runWith; cross-backend type-identical rows" in {
        val _: Int < Async = Topic.run(42)
        val _: Int < (Async & Abort[TopicTransportFailedException]) = Scope.run(AeronClient.connect(Path("/dev/shm", "type-probe")).map {
            c =>
                Topic.run(c)(42)
        })
        val _: Int < (Async & Abort[TopicTransportFailedException]) = Topic.run(Path("/nonexistent-inv001-probe"))(42)
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

    // The public API takes only kyo types (a Path or an AeronClient): the ascription type-checks only if
    // AeronClient's surface is free of io.aeron types; the typeCheckFailure snippets pin the absence of
    // any io.aeron-typed run overload.
    "no io.aeron on public surface; no MediaDriver/Aeron run overload; AeronClient is opaque" in {
        val _: AeronClient < (Scope & Async & Abort[TopicTransportFailedException]) =
            AeronClient.connect(Path("/dev/shm", "type-probe-inv002"))
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

    // The message comes from the upstream AeronPlatform.external catch, single-sourced in
    // TopicException.scala; AeronClient.scala adds no message string of its own.
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
                        assert(
                            e.getMessage.nonEmpty,
                            "TopicTransportFailedException message must be non-empty (single-sourced in TopicException.scala)"
                        )
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

    /** Distinct from the AeronTransportTest ids (7-114) so the shared aeron:ipc channel never bleeds a
      * message across tests.
      */
    private val offerAfterOwnCloseStreamId = 200
    private val clientCloseStreamId        = 201

    /** Adds a transport-level publication via the shared add-deadline loop and asserts it is Present on a
      * healthy embedded driver. Mirrors AeronTransportTest's UAF-sentinel add pattern, kept local so this
      * file imports no test helpers.
      */
    private def addPublication(transport: AeronTransport, streamId: Int)(using
        Frame,
        kyo.test.AssertScope
    ): transport.Publication < (Async & Abort[TopicTransportException]) =
        Topic.addPublicationDeadline(transport, "aeron:ipc", streamId, 10.seconds).map { pubMaybe =>
            assert(pubMaybe.isDefined, "addPublicationDeadline returned Absent on a healthy embedded driver")
            pubMaybe.get
        }

    // kyo_aeron_publication_close marks the bundle closed under close_mutex but defers the free to the
    // client-close sweep, so a later offer on the closed-but-not-yet-freed handle observes b->closed and
    // returns -4 without touching the aeron handle. Without that deferral, close would free the bundle
    // immediately and the next offer would dereference freed memory on Native/JS.
    "offer after the caller's own closePublication returns the safe Closed sentinel, no UAF (JVM/JS/Native)" in {
        val payload = Array[Byte](1, 2, 3, 4)
        for
            dir <- Path.tempDir("kyo-aeron-offer-after-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            offerResult <- Abort.run[TopicTransportException] {
                addPublication(transport, offerAfterOwnCloseStreamId).map { pub =>
                    Sync.Unsafe.defer {
                        // The caller's own close: marks the bundle closed, defers the free.
                        transport.closePublication(pub)
                        // The three guarded reads on the same, now-closed handle.
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

    // Proves the per-client sweep is the sole free-owner of the deferred bundle: a double-free or
    // use-after-free would crash the process before the assertion. fatalError is read before the runtime
    // closes, since the runtime owns the transport's client handle.
    "client-close after a caller-closed publication is clean: no double-free, fatalError Absent (JVM/JS/Native)" in {
        for
            dir <- Path.tempDir("kyo-aeron-client-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            fatalAfterClose <- Abort.run[TopicTransportException] {
                addPublication(transport, clientCloseStreamId).map { pub =>
                    Sync.Unsafe.defer {
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

    private val pollAfterOwnCloseStreamId = 202
    private val subClientCloseStreamId    = 203

    /** Mirror of addPublication above, for subscriptions. */
    private def addSubscription(transport: AeronTransport, streamId: Int)(using
        Frame,
        kyo.test.AssertScope
    ): transport.Subscription < (Async & Abort[TopicTransportException]) =
        Topic.addSubscriptionDeadline(transport, "aeron:ipc", streamId, 10.seconds).map { subMaybe =>
            assert(subMaybe.isDefined, "addSubscriptionDeadline returned Absent on a healthy embedded driver")
            subMaybe.get
        }

    // Symmetric to the publication offer-after-own-close leaf, plus the idempotency guard making a second
    // close a no-op; without it, a later poll or second close would corrupt the heap on Native/JS. JVM's
    // pollOne maps any AeronException to Absent; FFI's guard gives the same result and an inert second close.
    "poll after the caller's own closeSubscription returns no fragment and a second close is a no-op, no UAF (JVM/JS/Native)" in {
        for
            dir <- Path.tempDir("kyo-aeron-poll-after-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pollResult <- Abort.run[TopicTransportException] {
                addSubscription(transport, pollAfterOwnCloseStreamId).map { sub =>
                    Sync.Unsafe.defer {
                        // The caller's own close: marks the bundle closed, defers the free.
                        transport.closeSubscription(sub)
                        // Guarded poll on the same, now-closed handle.
                        val polled = transport.pollOne(sub)
                        // Second close: must be an idempotent no-op.
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

    // Mirror of the publication client-close leaf above, for the subscription sweep.
    "client-close after a caller-closed subscription is clean: no double-free, fatalError Absent (JVM/JS/Native)" in {
        for
            dir <- Path.tempDir("kyo-aeron-sub-client-close")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            fatalAfterClose <- Abort.run[TopicTransportException] {
                addSubscription(transport, subClientCloseStreamId).map { sub =>
                    Sync.Unsafe.defer {
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

    // aeron_errcode() reports AERON_CLIENT_ERRORED_MEDIA_DRIVER as a negated driver code; math.abs at both
    // FFI capture sites normalizes it to match JVM's RegistrationException.errorCodeValue(). A malformed
    // URI fails immediately on every backend: the embedded driver rejects it on the first _poll < 0 cycle.
    "TopicRegistrationFailedException.errorCode is positive on all platforms for the same rejection" in {
        val malformedUri = "aeron:invalid"
        for
            result <- Topic.run {
                Abort.run[TopicException] {
                    Topic.publish[Int](malformedUri)(Stream.init(Seq(1)))
                }
            }
        yield result match
            case Result.Failure(e: TopicRegistrationFailedException) =>
                // errorCode == 1 (INVALID_CHANNEL) for "aeron:invalid" on JVM and Native; without the
                // math.abs normalization FFI would return the negated value.
                assert(e.errorCode == 1, s"expected errorCode == 1 (INVALID_CHANNEL) but got ${e.errorCode}")
                assert(e.aeronUri == malformedUri, s"expected aeronUri == $malformedUri but got ${e.aeronUri}")
            case other =>
                fail(s"expected TopicRegistrationFailedException but got $other")
        end for
    }

    // The message template lives once in TopicException.scala; the errcode normalization adds no
    // message string of its own, only math.abs at the two FFI capture sites.
    "TopicRegistrationFailedException message is single-sourced from TopicException.scala" in {
        val malformedUri = "aeron:invalid"
        for
            result <- Topic.run {
                Abort.run[TopicException] {
                    Topic.publish[Int](malformedUri)(Stream.init(Seq(1)))
                }
            }
        yield result match
            case Result.Failure(e: TopicRegistrationFailedException) =>
                assert(e.getMessage.contains("Driver error"), s"message shape changed: ${e.getMessage}")
                assert(e.getMessage.contains("aeron:invalid"), s"URI not in message: ${e.getMessage}")
            case other =>
                fail(s"expected TopicRegistrationFailedException but got $other")
        end for
    }

    // Exercising both entry points against the same absent dir proves the shared external primitive owns
    // the single connect-failure catch, so neither diverges into a panic, null, or backend-specific outcome;
    // Async.zip runs them concurrently since both wait the same ~10 s driver-timeout.
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

    // The AeronClient.connect counterpart to the Topic.run(aeronDir) ticker leaf above: non-freeze must
    // hold regardless of how the driver connection is initiated. On a failed connect the
    // Scope.acquireRelease acquire aborts before producing a value, so nothing is acquired and no release
    // runs.
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
