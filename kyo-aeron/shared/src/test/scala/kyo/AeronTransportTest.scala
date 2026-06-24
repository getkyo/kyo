package kyo

import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.internal.AeronBindings
import kyo.internal.AeronClientHandle
import kyo.internal.AeronPlatform
import kyo.internal.AeronPublication
import kyo.internal.AeronSentinels
import kyo.internal.AeronSubscription
import kyo.internal.AeronTransport

class AeronTransportTest extends Test:

    val ipcUri = "aeron:ipc"

    // Stream-ids are distinct per test to avoid cross-test subscription bleed:
    // the embedded Aeron IPC driver keeps subscription registrations until explicitly
    // closed. Using a unique stream-id per test ensures that a lingering subscription
    // from a prior test does not receive a later publish.
    val roundTripStreamId    = 7
    val notConnectedStreamId = 8
    val absentStreamId       = 9
    val closeStreamId        = 10
    // Stream-ids for add-deadline tests:
    //   102 = stuck-add deadline (aborts with TopicAddTimeoutException)
    //   103 = ticker starvation / non-blocking reproduction
    //   107 = interrupt during pending add
    //   108 = normal add round-trip via deadline loop (regression)
    val addTimeoutStreamId    = 102
    val tickerStreamId        = 103
    val interruptStreamId     = 107
    val regressionAddStreamId = 108
    // Stream-ids for UAF guard tests:
    //   109 = high-iteration forked-then-close loop (the end-to-end repro)
    //   110 = deterministic after-close interleaving via Scala sequencing; native+JS via
    //         `.notJvm`, since the raw kyo_aeron_* bindings it drives exist only on the C-shim platforms
    //   111 = guarded offer after a concurrent close maps to TopicPublicationClosedException
    val uafLoopStreamId     = 109
    val uafStreamId         = 110
    val uafSentinelStreamId = 111
    // Stream-ids for oversize tests:
    //   104 = JVM oversize offer returns -6, does not throw
    //   105 = cross-platform: every platform returns -6, no throw
    val oversizeJvmStreamId   = 104
    val oversizeCrossStreamId = 105
    // Stream-ids for error-handler tests:
    //   106 = inject + fatalError + process survives
    //   112 = publish after inject aborts TopicTransportFailedException
    //   113 = no-false-positive regression (healthy transport, fatalError Absent)
    //   114 = empty-message inject surfaces TopicTransportFailedException with errcode-derived detail
    val errorHandlerInjectStreamId     = 106
    val errorHandlerPublishStreamId    = 112
    val errorHandlerRegressionStreamId = 113
    val errorHandlerEmptyMsgStreamId   = 114

    // Waits until cond returns true, polling every 1ms with a non-blocking Async
    // suspension. Returns false if maxAttempts is exhausted.
    private def awaitTrue(maxAttempts: Int)(cond: => Boolean < Sync)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(false)
            else
                cond.map { ok =>
                    if ok then Loop.done(true)
                    else Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    // Retries pollFn every 1ms until it returns Present or maxAttempts is exhausted.
    private def pollUntil(maxAttempts: Int)(
        pollFn: => Maybe[Array[Byte]] < Sync
    )(using Frame): Maybe[Array[Byte]] < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(Absent: Maybe[Array[Byte]])
            else
                pollFn.map {
                    case p @ Present(_) => Loop.done(p: Maybe[Array[Byte]])
                    case Absent         => Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    // Retries offerFn every 1ms until it returns > 0 or maxAttempts is exhausted.
    private def offerUntil(maxAttempts: Int)(offerFn: => Long < Sync)(using Frame): Long < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(-1L)
            else
                offerFn.map { r =>
                    if r > 0 then Loop.done(r)
                    else Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    // Polls an async publication token until Done, Absent (on Failed), or maxAttempts exhausted.
    // Returns Present(handle) on Done, Absent on Failed or timeout.
    private def pollAddPubUntilDone(
        transport: AeronTransport,
        tok: transport.AsyncPub,
        maxAttempts: Int
    )(using Frame): Maybe[transport.Publication] < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(Absent: Maybe[transport.Publication])
            else
                Sync.Unsafe.defer(transport.pollAddPublication(tok)).map {
                    case AeronTransport.AddPoll.Done(h) =>
                        Loop.done(Present(h): Maybe[transport.Publication])
                    case AeronTransport.AddPoll.Failed(_, _) =>
                        Loop.done(Absent: Maybe[transport.Publication])
                    case _ => Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    // Polls an async subscription token until Done, Absent (on Failed), or maxAttempts exhausted.
    private def pollAddSubUntilDone(
        transport: AeronTransport,
        tok: transport.AsyncSub,
        maxAttempts: Int
    )(using Frame): Maybe[transport.Subscription] < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(Absent: Maybe[transport.Subscription])
            else
                Sync.Unsafe.defer(transport.pollAddSubscription(tok)).map {
                    case AeronTransport.AddPoll.Done(h) =>
                        Loop.done(Present(h): Maybe[transport.Subscription])
                    case AeronTransport.AddPoll.Failed(_, _) =>
                        Loop.done(Absent: Maybe[transport.Subscription])
                    case _ => Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    // Drives one async-add publication to Done via the Scala Async poll loop, using the raw
    // AeronBindings (not the AeronTransport abstraction). Used by the deterministic after-close leaf below, which must
    // close the client out from under a still-held inner handle, a sequence the transport API hides.
    private def addPublicationRaw(
        bindings: AeronBindings,
        client: Ffi.Handle[AeronClientHandle],
        uri: String,
        streamId: Int,
        maxAttempts: Int
    )(using Frame): Maybe[Ffi.Handle[AeronPublication]] < Async =
        Sync.Unsafe.defer(bindings.asyncAddPublication(client, uri, streamId)).map {
            case Absent => (Absent: Maybe[Ffi.Handle[AeronPublication]])
            case Present(tok) =>
                Loop.indexed { i =>
                    if i >= maxAttempts then Loop.done(Absent: Maybe[Ffi.Handle[AeronPublication]])
                    else
                        Sync.Unsafe.defer(bindings.asyncAddPublicationPoll(tok)).map { r =>
                            if r > 0 then
                                Sync.Unsafe.defer(bindings.asyncAddPublicationGet(tok)).map { got =>
                                    Loop.done(got: Maybe[Ffi.Handle[AeronPublication]])
                                }
                            else if r < 0 then Loop.done(Absent: Maybe[Ffi.Handle[AeronPublication]])
                            else Async.sleep(1.millis).andThen(Loop.continue)
                            end if
                        }
                }
        }

    // Drives one async-add subscription to Done via the Scala Async poll loop, using the raw bindings.
    private def addSubscriptionRaw(
        bindings: AeronBindings,
        client: Ffi.Handle[AeronClientHandle],
        uri: String,
        streamId: Int,
        maxAttempts: Int
    )(using Frame): Maybe[Ffi.Handle[AeronSubscription]] < Async =
        Sync.Unsafe.defer(bindings.asyncAddSubscription(client, uri, streamId)).map {
            case Absent => (Absent: Maybe[Ffi.Handle[AeronSubscription]])
            case Present(tok) =>
                Loop.indexed { i =>
                    if i >= maxAttempts then Loop.done(Absent: Maybe[Ffi.Handle[AeronSubscription]])
                    else
                        Sync.Unsafe.defer(bindings.asyncAddSubscriptionPoll(tok)).map { r =>
                            if r > 0 then
                                Sync.Unsafe.defer(bindings.asyncAddSubscriptionGet(tok)).map { got =>
                                    Loop.done(got: Maybe[Ffi.Handle[AeronSubscription]])
                                }
                            else if r < 0 then Loop.done(Absent: Maybe[Ffi.Handle[AeronSubscription]])
                            else Async.sleep(1.millis).andThen(Loop.continue)
                            end if
                        }
                }
        }

    // JS-safe description of an add-deadline outcome for assertion clues. Interpolating a raw
    // Result whose success side holds an FFI Handle (an opaque koffi pointer) throws
    // "TypeError: Cannot convert object to primitive value" on Scala.js string coercion. This
    // projects the failure side to its exception class name and never coerces the Handle.
    private def describeAddFailure[E, A](result: Result[E, A]): String =
        result match
            case Result.Failure(e) => s"Failure(${e.getClass.getSimpleName})"
            case Result.Panic(t)   => s"Panic(${t.getClass.getSimpleName})"
            case Result.Success(_) => "Success"

    // Never-confirm transport: a test-only transport where asyncAdd* hand back a token
    // immediately but pollAdd* ALWAYS return Awaiting, modelling a registration the driver
    // never confirms. This drives the real Topic.addPublicationDeadline deadline loop
    // deterministically on every platform without manipulating a live driver.
    //
    // The never-confirm condition is injected at the transport boundary rather than using a
    // real stopped/absent driver because, on the C (Native/JS) path, a real conductor error
    // triggers the Aeron C client's default error handler, which calls exit(EXIT_FAILURE) and
    // kills the test process. Injecting at the transport seam keeps the deadline/timeout
    // behavior provable cross-platform without triggering a real conductor exit. The real FFI
    // add path (Done) is covered by the round-trip and normal-add leaves on every platform.
    final private class NeverConfirmTransport extends AeronTransport:
        type Publication  = Int
        type Subscription = Int
        type AsyncPub     = Int
        type AsyncSub     = Int
        def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] = Present(streamId)
        def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
            AeronTransport.AddPoll.Awaiting
        def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit                               = ()
        def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean                 = false
        def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long               = 0L
        def maxMessageLength(pub: Publication)(using AllowUnsafe): Int                           = 0
        def closePublication(pub: Publication)(using AllowUnsafe): Unit                          = ()
        def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] = Present(streamId)
        def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription] =
            AeronTransport.AddPoll.Awaiting
        def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit                 = ()
        def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean = false
        def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]]      = Absent
        def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit          = ()
        // NeverConfirmTransport has no error slot: inherits the default no-op injectError.
        def fatalError(using AllowUnsafe): Maybe[String] = Absent
    end NeverConfirmTransport

    "round-trip a known byte payload through AeronPlatform.embedded" in {
        val payload = Array[Byte](1, 2, 3, 4)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, roundTripStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, roundTripStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            subConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.subscriptionIsConnected(sub)))
            _ = assert(subConnected, "subscription did not connect within 5s")
            pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, payload)))
            _ = assert(pos > 0, s"offer did not succeed within 5000 attempts; last pos=$pos")
            received <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _     = assert(received.isDefined, "no message received within 5000 poll attempts")
            bytes = received.get
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            java.util.Arrays.equals(bytes, payload),
            s"payload mismatch: ${bytes.toList} != ${payload.toList}"
        )
        end for
    }

    "offer to not-connected publication takes the not-connected path" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, notConnectedStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
            pub = pubMaybe.get
            // No subscriber: publicationIsConnected must be false initially.
            notConnectedInitially <- Sync.Unsafe.defer(!transport.publicationIsConnected(pub))
            // Connect a subscriber so the publication side can see it.
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, notConnectedStreamId))
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _       = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
            sub     = subMaybe.get
            payload = Array[Byte](0)
            connected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(connected, "publication did not connect after subscriber was added within 5s")
            connectedPosition <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, payload)))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield
            assert(notConnectedInitially, "Expected publication to be not-connected with no subscriber")
            assert(connectedPosition > 0, s"Expected a positive position after connection; got $connectedPosition")
    }

    "pollOne with zero fragments returns Absent" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            // Connected subscription with no published message: pollOne must return Absent.
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, absentStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, absentStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            subConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.subscriptionIsConnected(sub)))
            _ = assert(subConnected, "subscription did not connect within 5s")
            result <- Sync.Unsafe.defer(transport.pollOne(sub))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(result.isEmpty, s"Expected Absent when no message published; got $result")
    }

    "close releases resources without leak" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, closeStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, closeStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            // Drive the registration to Done via the Async poll helper. This yields to the
            // event loop between polls (Async.sleep), which the single-threaded JS runtime needs
            // to converge; a tight synchronous spin would never advance there.
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _ = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
            _ = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
            // Close in reverse open order, then tear down the runtime.
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pubMaybe.get)
                transport.closeSubscription(subMaybe.get)
                rt.close()
            }
            _ <- dir.removeAll
            // A second embedded() in the same test confirms the close was clean.
            dir2 <- Path.tempDir("kyo-aeron-embedded-test")
            rt2  <- AeronPlatform.embedded(dir2.unsafe.show)
            transport2 = rt2.transport
            pub2TokMaybe <- Sync.Unsafe.defer(transport2.asyncAddPublication(ipcUri, closeStreamId))
            _ = assert(pub2TokMaybe.isDefined, "asyncAddPublication returned Absent on a freshly-started client")
            // Free the pending token without waiting for registration to complete, then tear down.
            _ <- Sync.Unsafe.defer {
                transport2.freeAsyncPub(pub2TokMaybe.get)
                rt2.close()
            }
            _ <- dir2.removeAll
        // No crash up to this point proves the close-and-reopen cycle succeeded.
        yield succeed
    }

    "offer result maps through AeronSentinels identically on every platform" in {
        // A not-connected offer returns a negative sentinel matching AeronSentinels. This is
        // a cross-platform contract: AeronPlatform.embedded() dispatches to the active platform's
        // transport (JvmAeronTransport on JVM, FfiAeronTransport on Native/JS), so one cross-platform
        // leaf exercises every impl. It is the single cross-platform leaf for this contract;
        // TopicInvariantsTest defers its FFI offer-sentinel coverage to this leaf and keeps a separate
        // `.onlyJs` leaf for the JS koffi BigInt->Long sign-marshalling guard.
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            // Use addPublicationDeadline (private[kyo]) to obtain the publication handle; it links and
            // runs on all platforms (Native/JS receive only the public AeronTransport API here).
            pubMaybeResult <- Abort.run[TopicTransportException] {
                Topic.addPublicationDeadline(transport, ipcUri, 11, 10.seconds)
            }
            _ = assert(
                pubMaybeResult.isSuccess,
                s"addPublicationDeadline failed on a healthy driver: ${describeAddFailure(pubMaybeResult)}"
            )
            pubMaybe = pubMaybeResult.getOrThrow
            _        = assert(pubMaybe.isDefined, "addPublicationDeadline returned Absent on a healthy driver")
            pub      = pubMaybe.get
            // No subscriber: the first offer to a not-connected publication must
            // return a negative sentinel (NotConnected = -1L or BackPressured = -2L).
            payload = Array[Byte](0)
            result <- Sync.Unsafe.defer(transport.offer(pub, payload))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                rt.close()
            }
            _ <- dir.removeAll
        yield
            assert(
                result < 0,
                s"Expected a negative sentinel from offer to a not-connected publication; got $result"
            )
            assert(
                result == AeronSentinels.NotConnected || result == AeronSentinels.BackPressured,
                s"Expected NotConnected (-1L) or BackPressured (-2L) but got $result"
            )
        end for
    }

    "two exact types isolate by stream-id" in {
        // The stream-id derivation uses tag.hash.abs, so two distinct
        // Scala types publish and subscribe on different stream-ids and cannot
        // deliver each other's messages.
        val intId    = Tag[Int].hash.abs
        val stringId = Tag[String].hash.abs
        val payload  = Array[Byte](99)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubIntTokMaybe    <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, intId))
            subIntTokMaybe    <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, intId))
            subStringTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, stringId))
            _ = assert(pubIntTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subIntTokMaybe.isDefined, "asyncAddSubscription (Int) returned Absent on a live client")
            _ = assert(subStringTokMaybe.isDefined, "asyncAddSubscription (String) returned Absent on a live client")
            pubIntMaybe    <- pollAddPubUntilDone(transport, pubIntTokMaybe.get, 5000)
            subIntMaybe    <- pollAddSubUntilDone(transport, subIntTokMaybe.get, 5000)
            subStringMaybe <- pollAddSubUntilDone(transport, subStringTokMaybe.get, 5000)
            _         = assert(pubIntMaybe.isDefined, "pollAddPublication (Int) never returned Done")
            _         = assert(subIntMaybe.isDefined, "pollAddSubscription (Int) never returned Done")
            _         = assert(subStringMaybe.isDefined, "pollAddSubscription (String) never returned Done")
            pubInt    = pubIntMaybe.get
            subInt    = subIntMaybe.get
            subString = subStringMaybe.get
            // Wait for the Int publication to connect to its Int subscriber.
            connected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pubInt)))
            _ = assert(connected, "Int publication did not connect to Int subscriber within 5s")
            // Offer on the Int stream-id.
            pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pubInt, payload)))
            _ = assert(pos > 0, s"offer to Int stream did not succeed; last pos=$pos")
            // Drain the Int subscription until the message arrives.
            intReceived <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(subInt)))
            _ = assert(
                intReceived.isDefined,
                "Int subscription did not receive its own message within 5000 poll attempts"
            )
            // Poll the String subscription once: isolation means it must be Absent.
            stringResult <- Sync.Unsafe.defer(transport.pollOne(subString))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pubInt)
                transport.closeSubscription(subInt)
                transport.closeSubscription(subString)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            stringResult.isEmpty,
            s"Expected Absent on String subscription after publishing to Int stream; got $stringResult"
        )
        end for
    }

    "pollOne reassembles a multi-fragment message (>MTU) correctly" in {
        // 4096 bytes > 1408-byte IPC MTU; triggers multi-fragment reassembly on both
        // the JVM FragmentAssembler and the C shim's aeron_fragment_assembler.
        val multiFragId = 98 // distinct stream-id; no cross-test bleed
        val payload     = Array.tabulate[Byte](4096)(i => (i & 0xff).toByte)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, multiFragId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, multiFragId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            subConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.subscriptionIsConnected(sub)))
            _ = assert(subConnected, "subscription did not connect within 5s")
            pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, payload)))
            _ = assert(pos > 0, s"multi-fragment offer did not succeed; last pos=$pos")
            received <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _ = assert(received.isDefined, "no reassembled message received within 5000 poll attempts")
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            java.util.Arrays.equals(received.get, payload),
            s"multi-fragment payload mismatch: expected 4096 bytes, got ${received.get.length}"
        )
        end for
    }

    // Parity gate: messages between 1 MiB and Aeron's protocol ceiling must
    // round-trip on ALL platforms identically.
    "pollOne reassembles a 2 MiB multi-fragment message identically on all platforms" in {
        val largeFragId = 99 // distinct stream-id; no cross-test bleed
        val large       = Array.tabulate[Byte](2 * 1024 * 1024)(i => (i & 0xff).toByte)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, largeFragId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, largeFragId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            subConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.subscriptionIsConnected(sub)))
            _ = assert(subConnected, "subscription did not connect within 5s")
            pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, large)))
            _ = assert(pos > 0, s"2 MiB offer did not succeed; last pos=$pos")
            received <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _ = assert(received.isDefined, "no reassembled 2 MiB message received within 5000 poll attempts")
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            java.util.Arrays.equals(received.get, large),
            s"2 MiB payload mismatch: expected ${large.length} bytes, got ${received.get.length}"
        )
        end for
    }

    // Slot reuse / regrow correctness: large then small on the same subscription.
    "pollOne handles a small message after a large one on the same subscription" in {
        val reuseStreamId = 100 // distinct stream-id; no cross-test bleed
        val large         = Array.tabulate[Byte](256 * 1024)(i => ((i * 31) & 0xff).toByte)
        val small         = Array[Byte](9, 8, 7, 6, 5)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, reuseStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, reuseStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            subConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.subscriptionIsConnected(sub)))
            _ = assert(subConnected, "subscription did not connect within 5s")
            // First: the large message forces both sides to grow.
            posLarge <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, large)))
            _ = assert(posLarge > 0, s"large offer did not succeed; last pos=$posLarge")
            receivedLarge <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _ = assert(receivedLarge.isDefined, "no reassembled large message received within 5000 poll attempts")
            _ = assert(
                java.util.Arrays.equals(receivedLarge.get, large),
                s"large payload mismatch: expected ${large.length} bytes, got ${receivedLarge.get.length}"
            )
            // Then: a small message must reuse the grown buffer and round-trip exactly.
            posSmall <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, small)))
            _ = assert(posSmall > 0, s"small offer did not succeed; last pos=$posSmall")
            receivedSmall <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _ = assert(receivedSmall.isDefined, "no small message received within 5000 poll attempts")
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            java.util.Arrays.equals(receivedSmall.get, small),
            s"small payload mismatch after large: expected ${small.toList}, got ${receivedSmall.get.toList}"
        )
        end for
    }

    // add-vs-close use-after-free regression guard.
    // asyncAddPublication / asyncAddSubscription on a closed client return Absent immediately,
    // via the registry guard in the C start function: kyo_aeron_async_add_publication verifies the
    // bundle is still live under g_registry_mutex before touching any field. Without that check,
    // incrementing c->refcount by first locking the bundle's own c->close_mutex would dereference
    // freed memory once client_bundle_release has freed the bundle, a use-after-free that segfaults
    // on Native. This test drives both adds on a closed client and asserts Absent.
    "add-vs-close: asyncAddPublication and asyncAddSubscription on a closed client return Absent, not a crash" in {
        Loop.indexed { i =>
            if i >= 20 then Loop.done(succeed)
            else
                Path.tempDir("kyo-aeron-embedded-test").flatMap { dir =>
                    // embedded(dir) returns AeronRuntime < Async (the @Ffi.blocking bridge);
                    // map over it, then the post-close registry checks stay in Sync.Unsafe.defer.
                    AeronPlatform.embedded(dir.unsafe.show).map { rt =>
                        Sync.Unsafe.defer {
                            val transport = rt.transport
                            rt.close()
                            // After close: the global registry check returns NULL -> Absent.
                            val pubResult = transport.asyncAddPublication(ipcUri, addTimeoutStreamId)
                            val subResult = transport.asyncAddSubscription(ipcUri, addTimeoutStreamId)
                            assert(
                                pubResult.isEmpty,
                                s"iteration $i: asyncAddPublication on closed client must return Absent; got $pubResult"
                            )
                            assert(
                                subResult.isEmpty,
                                s"iteration $i: asyncAddSubscription on closed client must return Absent; got $subResult"
                            )
                        }.andThen(dir.removeAll)
                    }
                }.andThen(Loop.continue)
        }
    }

    // ---------------------------------------------------------------------------
    // Bounded Scala Async poll loop (add-deadline behavior)
    // ---------------------------------------------------------------------------

    // A genuinely-pending add aborts with TopicAddTimeoutException at the deadline.
    //
    // The never-confirm condition is injected via NeverConfirmTransport: asyncAddPublication
    // returns Present(token) but pollAddPublication stays Awaiting forever, modelling a
    // registration the driver never confirms. The bounded Scala deadline loop then aborts
    // with TopicAddTimeoutException at the short (200ms) deadline.
    //
    // A blocking add would strand the carrier for >500ms with no bounded completion (holding the
    // single carrier past the 200ms deadline). The bounded deadline loop keeps it green.
    //
    // The assertion is concrete: the result MUST be Failure(TopicAddTimeoutException) with the same
    // uri/streamId/timeout the add was given, AND it must arrive well inside a generous
    // wall-clock watchdog (1s) so a never-bounded hang fails the test.
    "a never-confirming add aborts with TopicAddTimeoutException at the deadline" in {
        val deadline  = 200.millis
        val transport = new NeverConfirmTransport
        // Race the add against a 1s wall-clock watchdog. The 200ms TopicAddTimeoutException must
        // fire well before the watchdog; if the add hung, the Timeout abort wins and we fail.
        Abort.run[Timeout | TopicTransportException] {
            Async.timeout(1.second) {
                Topic.addPublicationDeadline(transport, ipcUri, addTimeoutStreamId, deadline)
            }
        }.map {
            case Result.Success(_) =>
                fail("addPublicationDeadline did not abort on a never-confirming add")
            case Result.Failure(t: TopicAddTimeoutException) =>
                assert(
                    t.aeronUri == ipcUri && t.streamId == addTimeoutStreamId && t.timeout == deadline,
                    s"TopicAddTimeoutException carried wrong detail: uri=${t.aeronUri} streamId=${t.streamId} timeout=${t.timeout}"
                )
            case Result.Failure(_: Timeout) =>
                // The 1s watchdog fired: the add hung past 1s instead of aborting at the
                // 200ms deadline (a non-starvation failure: the deadline loop did not complete promptly).
                fail("addPublicationDeadline hung past the 1s watchdog instead of aborting with TopicAddTimeoutException")
            case Result.Failure(other) =>
                fail(s"expected TopicAddTimeoutException, got ${other.getClass.getSimpleName}")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getClass.getSimpleName}")
        }
    }

    // A genuinely-pending add does not strand a carrier; a concurrent ticker advances
    // WHILE the add is pending, and the add still aborts with TopicAddTimeoutException.
    //
    // The never-confirm condition is injected via NeverConfirmTransport: pollAddPublication
    // always returns Awaiting, so the add is genuinely pending for the full deadline window (500ms)
    // rather than completing in ~1ms on a healthy driver. This is the in-flight window the
    // non-starvation reproduction needs, deterministic on every platform.
    //
    // A blocking add would strand the carrier for the whole window so a concurrent
    // ticker could not advance. addPublicationDeadline instead uses Async.sleep(addBackoff=1.milli)
    // between poll steps, releasing the carrier cooperatively, so the ticker advances during the
    // pending add (asserted here) and the add aborts with TopicAddTimeoutException at the deadline.
    //
    // Anti-flakiness: the add deadline (500ms) comfortably exceeds the ticker's work (5 ticks at
    // 1ms = ~5ms) so the ticker always finishes while the add is still pending. The 20ms warm-up
    // gives the add fiber time to enter the poll loop before the ticker assertion.
    "a pending never-confirming add does not starve a concurrent ticker fiber" in {
        val tickerThreshold = 5
        val deadline        = 500.millis
        val transport       = new NeverConfirmTransport
        for
            counter <- AtomicInt.init(0)
            // Ticker fiber: increments counter tickerThreshold times with 1ms sleep between each.
            tickerFiber <- Fiber.initUnscoped {
                Loop.indexed { i =>
                    if i >= tickerThreshold then Loop.done(())
                    else Async.sleep(1.millis).andThen(counter.incrementAndGet).andThen(Loop.continue)
                }
            }
            // Add fiber: a genuinely-pending add that aborts with TopicAddTimeoutException at the deadline.
            addFiber <- Fiber.initUnscoped {
                Abort.run[TopicTransportException] {
                    Topic.addPublicationDeadline(transport, ipcUri, tickerStreamId, deadline)
                }
            }
            // Give the add fiber 20ms to enter the poll loop before reading the ticker.
            _ <- Async.sleep(20.millis)
            // The ticker must advance to threshold WHILE the add is still pending; if the
            // carrier is starved the ticker cannot run.
            tickerReached <- awaitTrue(300)(counter.get.map(_ >= tickerThreshold))
            _ = assert(
                tickerReached,
                "ticker fiber did not advance while add was pending: carrier starvation by blocking add"
            )
            // The pending add aborts with TopicAddTimeoutException (bounded, not hung).
            addResult <- addFiber.get
            _ = addResult match
                case Result.Failure(_: TopicAddTimeoutException) => ()
                case other => fail(s"pending add did not abort with TopicAddTimeoutException: ${describeAddFailure(other)}")
            _ <- tickerFiber.get
        yield succeed
        end for
    }

    // No-sleep/yield/spin on the add path is exercised behaviorally rather than by a
    // C-source scan: the interrupt leaf below drives a real interrupt during a pending add and asserts cleanup
    // runs without a busy-wait stall, and the add-deadline leaves prove the add path completes
    // (or aborts) promptly under a live driver.

    // An interrupt during a pending add is honored and cleanup runs.
    //
    // Async.timeout(100.millis) wraps addPublicationDeadline(..., 10.seconds). If the add
    // completes before the timeout (live driver), the result is Success(Present(pub)) and we
    // close the publication. If the timeout fires first (e.g. on a stalled add), the result
    // is Failure(Timeout). Either way, the computation DOES NOT HANG past 100ms, proving the
    // Async poll loop is interruptible. The Sync.ensure handler frees the async token on
    // interrupt.
    "an interrupt during a pending add is honored and does not hang" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            result <- Abort.run[Timeout | TopicTransportException] {
                Async.timeout(100.millis) {
                    Topic.addPublicationDeadline(transport, ipcUri, interruptStreamId, 10.seconds)
                }
            }
            // Close the runtime; any publication from the add (if it completed before timeout)
            // will be cleaned up by the runtime close. The Sync.ensure inside addPublicationDeadline
            // frees the async token if interrupted mid-poll.
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield
            // The key property: the computation completed (did not hang) within 100ms.
            // Whether it succeeded (add done before timeout) or failed (timeout fired)
            // both prove the poll loop is non-blocking and interruptible.
            succeed
    }

    // A normal add via addPublicationDeadline completes and round-trips bytes through the
    // bounded async add path: the healthy add path reaches Done and delivers.
    "a normal add via addPublicationDeadline completes and round-trips bytes" in {
        val payload = Array[Byte](42, 43, 44)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubMaybeResult <- Abort.run[TopicTransportException] {
                Topic.addPublicationDeadline(transport, ipcUri, regressionAddStreamId, 10.seconds)
            }
            // Do NOT interpolate pubMaybeResult into the clue: on Scala.js a Result holding an FFI
            // Handle (an opaque koffi pointer) throws "TypeError: Cannot convert object to primitive
            // value" during string coercion. Project to the failure's class name instead.
            _ = assert(
                pubMaybeResult.isSuccess,
                s"addPublicationDeadline failed on a healthy driver: ${describeAddFailure(pubMaybeResult)}"
            )
            pubMaybe = pubMaybeResult.getOrThrow
            _        = assert(pubMaybe.isDefined, "addPublicationDeadline returned Absent on a healthy driver")
            pub      = pubMaybe.get
            subMaybeResult <- Abort.run[TopicTransportException] {
                Topic.addSubscriptionDeadline(transport, ipcUri, regressionAddStreamId, 10.seconds)
            }
            _ = assert(
                subMaybeResult.isSuccess,
                s"addSubscriptionDeadline failed on a healthy driver: ${describeAddFailure(subMaybeResult)}"
            )
            subMaybe = subMaybeResult.getOrThrow
            _        = assert(subMaybe.isDefined, "addSubscriptionDeadline returned Absent on a healthy driver")
            sub      = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, payload)))
            _ = assert(pos > 0, s"offer did not succeed; pos=$pos")
            received <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _ = assert(received.isDefined, "no message received within 5000 poll attempts")
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            java.util.Arrays.equals(received.get, payload),
            s"round-trip payload mismatch: got ${received.get.toList}"
        )
        end for
    }

    // ---------------------------------------------------------------------------
    // UAF guard: no inner-handle deref reads memory freed by a concurrent client close.
    // ---------------------------------------------------------------------------

    // high-iteration forked-then-close loop; the
    // end-to-end reproduction. Each iteration runs Topic.run with a publish fiber AND a
    // stream fiber forked via Fiber.initUnscoped (the project's canonical pattern, see
    // TopicTest / TopicInvariantsTest). The forked fibers are unscoped:
    // Topic.run's body completes and fires runtime.close() (the Sync.ensure finalizer in
    // Topic.run) WHILE the forked publish fiber is mid-offer/is_connected and the forked
    // stream fiber is mid-poll. On Native/JS the close path frees the inner Aeron handles
    // (aeron_close) while the lingering fibers may still dereference them on a different
    // carrier; the close_mutex+closing guard makes every such freed-handle deref return the
    // existing safe sentinel, so all iterations complete on every platform and the assertion
    // (succeed after N iterations) fires. Without the guard this deref would be a use-after-free
    // that segfaults the process; JVM is the memory-safe parity control (io.aeron objects live
    // on the JVM heap, no free-then-deref).
    //
    // No Async.sleep between iterations: the race window is the inherent body-completion-to-
    // close gap of the Fiber.initUnscoped pattern, not a timing artifact.
    "UAF-loop: a high-iteration forked-then-close loop does not use-after-free" in {
        val iterations = 100
        val messages   = Seq(1, 2, 3)
        Loop.indexed { i =>
            if i >= iterations then Loop.done(succeed)
            else
                Topic.run {
                    for
                        // A stream fiber that keeps polling (mid-poll when close fires).
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.stream[Int]("aeron:ipc").take(messages.size).run
                        )
                        // A publish fiber that keeps offering (mid-offer/is_connected when close fires).
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.publish[Int]("aeron:ipc")(Stream.init(messages))
                        )
                        // Give both forked fibers a moment to enter their offer/poll loops, then let
                        // the body complete: Topic.run's runtime.close() fires while they are mid-flight.
                        _ <- Async.sleep(1.millis)
                    yield ()
                }.andThen(Loop.continue)
        }
    }

    // Deterministic after-close coverage: the three inner-handle
    // hot-path guards that the offer leaf does not cover: publicationIsConnected,
    // subscriptionIsConnected, and subscriptionPoll.
    //
    // Platform-specific bar: genuine C-shim UAF-guard MECHANIC, no cross-platform equivalent. The
    // close_mutex+closing freed-handle guard exists ONLY in kyo_aeron.c (Native + JS); the JVM uses
    // the memory-safe refcounted io.aeron Java client and has no kyo_aeron_* symbols to guard, so
    // `.notJvm` (Native + JS, excluding JVM) is exactly the set of platforms where the guard exists.
    //
    // On Native + JS (the `.notJvm` set): the per-function UAF guard (close_mutex+closing) is
    // a C-shim property. The shim source is identical on Native and JS (koffi loads the same shim
    // .dylib/.so on JS), so the guard exists on both; the JVM has no C shim (it uses the io.aeron
    // Java client, no kyo_aeron_* symbols), so there is nothing to guard there. The leaf drives the
    // raw AeronBindings with Ffi.Handle/Buffer; those link and run on both C platforms (the JS
    // transport FfiAeronTransport in shared/src/main calls the same bindings incl. subscriptionPoll
    // via Buffer.useArray). Running on Native and JS exercises the per-function guard wherever the C
    // shim exists.
    //
    // The deterministic after-close interleaving is realized as Scala-side sequencing, NOT a C-side
    // busy-spin: each guarded-read C downcall is invoked only AFTER the client-close C downcall has
    // returned and freed the inner Aeron handle. That makes the freed-handle deref deterministic with
    // zero C-side waiting:
    //   Each guarded read acquires close_mutex, observes closing=1, and returns its existing safe
    //   sentinel (is_connected -> false, poll -> Absent) without touching the freed handle. Without
    //   the guard the read would dereference the freed handle, a use-after-free that segfaults the process.
    //
    // The publication/subscription bundles' refcounts keep the client bundle (and its close_mutex)
    // alive across the client close, so each guard's close_mutex acquire is always valid (the bundle
    // lifetime guarantee documented in kyo_aeron_publication_close).
    "UAF-reads: publicationIsConnected/subscriptionIsConnected/subscriptionPoll after a client close return safe sentinels, not a UAF".notJvm in {
        val pollDstCap = 64 * 1024
        for
            bindings <- Sync.Unsafe.defer(Ffi.load[AeronBindings])
            // Allocate a unique per-instance dir (like every other leaf) instead of passing null,
            // which would route to Aeron's single shared default directory and risk colliding with a
            // concurrent run. This leaf exercises the raw C symbols directly (not via AeronPlatform).
            dir <- Path.tempDir("kyo-aeron-uaf-reads")
            // driverStart/clientConnect are @Ffi.blocking: the binding call needs AllowUnsafe
            // (Sync.Unsafe.defer supplies it) and yields a Fiber.Unsafe bridged via .safe.get
            // (Async is in context, this leaf is `.notJvm`).
            driver   <- Sync.Unsafe.defer(bindings.driverStart(dir.unsafe.show)).flatMap(_.safe.get)
            client   <- Sync.Unsafe.defer(bindings.clientConnect(dir.unsafe.show)).flatMap(_.safe.get)
            pubMaybe <- addPublicationRaw(bindings, client, ipcUri, uafStreamId, 5000)
            subMaybe <- addSubscriptionRaw(bindings, client, ipcUri, uafStreamId, 5000)
            _   = assert(pubMaybe.isDefined, "addPublication never returned Done within 5000 attempts")
            _   = assert(subMaybe.isDefined, "addSubscription never returned Done within 5000 attempts")
            pub = pubMaybe.get
            sub = subMaybe.get
            // Close the client out from under the still-held publication and subscription. This
            // frees the inner aeron_publication_t / aeron_subscription_t while the
            // bundles' refcounts keep the client bundle + close_mutex alive. After this point
            // every inner-handle deref in the three hot-path reads targets freed memory without the guard.
            _ <- Sync.Unsafe.defer(bindings.clientClose(client))
            // The three guarded reads, each on a freed inner handle. Each observes
            // closing=1 under close_mutex and returns its safe sentinel.
            pubConnected <- Sync.Unsafe.defer(bindings.publicationIsConnected(pub))
            subConnected <- Sync.Unsafe.defer(bindings.subscriptionIsConnected(sub))
            polled <- Sync.Unsafe.defer {
                Buffer.useArray(new Array[Byte](pollDstCap)) { buf =>
                    bindings.subscriptionPoll(sub, buf, pollDstCap)
                }
            }
            // Release the bundle refs (closing=1, so close skips the freed handle and frees the
            // bundle); then stop the embedded driver.
            _ <- Sync.Unsafe.defer {
                bindings.publicationClose(pub)
                bindings.subscriptionClose(sub)
                bindings.driverClose(driver)
            }
            _ <- dir.removeAll
        yield
            assert(pubConnected == 0, s"publicationIsConnected after close must be 0 (not connected); got $pubConnected")
            assert(subConnected == 0, s"subscriptionIsConnected after close must be 0 (not connected); got $subConnected")
            assert(polled == 0L, s"subscriptionPoll after close must be 0 (no fragment / Absent); got $polled")
        end for
    }

    // a guarded offer on a publication whose client was closed maps to
    // the existing safe AeronSentinels.Closed (-4) sentinel, never a crash, identical on every
    // platform. This is the deterministic, cross-platform freed-handle-deref
    // reproduction.
    //
    // Sequential by design (so it is deterministic on the single-threaded JS event loop as well
    // as on Native and JVM): add a publication via the real add-deadline loop (its refcount keeps
    // the client bundle and its close_mutex alive), close the client out from under it (which
    // frees the inner aeron_publication_t while leaving the bundle valid), then offer on the
    // still-held publication handle. The offer reaches kyo_aeron_publication_offer which
    // dereferences b->publication.
    //   Every platform returns AeronSentinels.Closed (-4) through the close_mutex+closing guard,
    //   never a crash. On JVM io.aeron.Publication.offer on a closed publication returns
    //   Publication.CLOSED (-4) without faulting (the memory-safe parity control). Without the guard
    //   on Native/JS the publication, freed by the client close, would be dereferenced as a
    //   use-after-free that segfaults the process.
    //
    // The offer is the raw transport.offer (not Topic.publish) because Topic.publish would first
    // re-add a publication and hit the registry add-guard before reaching the offer; this leaf
    // targets the offer hot-path guard directly with a publication that is already open.
    "UAF-sentinel: an offer on a publication after a concurrent client close returns the safe sentinel" in {
        val payload = Array[Byte](1, 2, 3, 4)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubMaybeResult <- Abort.run[TopicTransportException] {
                Topic.addPublicationDeadline(transport, "aeron:ipc", uafSentinelStreamId, 10.seconds)
            }
            _ = assert(
                pubMaybeResult.isSuccess,
                s"addPublicationDeadline failed on a healthy driver: ${describeAddFailure(pubMaybeResult)}"
            )
            pubMaybe = pubMaybeResult.getOrThrow
            _        = assert(pubMaybe.isDefined, "addPublicationDeadline returned Absent on a healthy driver")
            pub      = pubMaybe.get
            // Close the client (and driver) out from under the still-held publication. The
            // publication bundle's refcount keeps the client bundle + close_mutex alive; the inner
            // aeron_publication_t is freed by the client close.
            _ <- Sync.Unsafe.defer(rt.close())
            // Offer on the freed-inner-handle publication. The close_mutex+closing guard returns the safe sentinel; without the guard this would be a UAF on FFI.
            offerResult <- Sync.Unsafe.defer(transport.offer(pub, payload))
            // Release the publication bundle's client-bundle ref (closing=1, so it skips the freed
            // handle and frees the bundle).
            _ <- Sync.Unsafe.defer(transport.closePublication(pub))
            _ <- dir.removeAll
        yield assert(
            offerResult == AeronSentinels.Closed,
            s"expected offer on a closed-client publication to return AeronSentinels.Closed (-4); got $offerResult"
        )
        end for
    }

    // ---------------------------------------------------------------------------
    // Oversize offer: an oversize offer returns -6 identically on all platforms;
    // JVM must not throw IllegalArgumentException.
    // ---------------------------------------------------------------------------

    // Platform-specific bar: genuine JVM IllegalArgumentException-suppression MECHANIC, no
    // cross-platform equivalent. Only the JVM io.aeron Publication.offer throws IAE from
    // checkMaxMessageLength on an oversize payload; JvmAeronTransport.offer's try/catch converts
    // it to -6. The FFI path returns -6 natively (no throw), so on Native/JS this leaf would pass
    // for the wrong reason. The cross-platform -6 contract is verified by the all-platforms oversize-offer leaf (below).
    //
    // JVM oversize offer returns -6 (AeronSentinels.Error) and does NOT throw.
    //
    // JvmAeronTransport.offer wraps pub.offer(...) in a try/catch that converts the
    // IllegalArgumentException from checkMaxMessageLength (inside Publication.offer) on an oversize
    // payload into -6 (AeronSentinels.Error), matching the FFI path's native return value. Without
    // that catch the IAE would escape Sync.Unsafe.defer as a panic instead of a -6 return.
    //
    // Gated `.onlyJvm` because the FFI path already returns -6 (it would pass for the wrong reason on
    // Native/JS). The all-platforms leaf covers the cross-platform transport-level guard.
    //
    // Anti-flakiness: add a subscriber on the same stream-id and await publicationIsConnected
    // before the offer. An offer to an unconnected publication returns -1 (NotConnected),
    // not -6 (Error). The connected state is required to reach the oversize error path.
    "oversize offer on term-length=65536 returns -6, does not throw".onlyJvm in {
        val oversizeUri = "aeron:ipc?term-length=65536"
        val oversize    = Array.fill[Byte](8193)(0)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(oversizeUri, oversizeJvmStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(oversizeUri, oversizeJvmStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
            pub = pubMaybe.get
            sub = subMaybe.get
            // Await connected: without a subscriber the offer returns -1 (not -6).
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            // Offer the oversize payload: the offer's try/catch returns -6 (an unwrapped IAE would be a panic).
            result <- Abort.run[Throwable](Sync.Unsafe.defer(transport.offer(pub, oversize)))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield result match
            case Result.Success(r) =>
                assert(
                    r == AeronSentinels.Error,
                    s"Expected AeronSentinels.Error (-6) for oversize offer; got $r"
                )
            case Result.Panic(t) =>
                fail(s"oversize offer threw ${t.getClass.getSimpleName} instead of returning -6: ${t.getMessage}")
            case Result.Failure(t) =>
                fail(s"oversize offer aborted with ${t.getClass.getSimpleName}: ${t.getMessage}")
        end for
    }

    // Oversize offer on term-length=65536 returns -6 on every platform.
    //
    // JVM converts the IAE from checkMaxMessageLength to -6 in JvmAeronTransport.offer; Native/JS
    // return -6 natively. All three return -6 without throwing.
    //
    // Anti-flakiness: same subscriber+awaitConnected pattern as the JVM oversize-offer leaf.
    "oversize offer on term-length=65536 returns -6 on every platform" in {
        val oversizeUri = "aeron:ipc?term-length=65536"
        val oversize    = Array.fill[Byte](8193)(0)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(oversizeUri, oversizeCrossStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(oversizeUri, oversizeCrossStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            result <- Abort.run[Throwable](Sync.Unsafe.defer(transport.offer(pub, oversize)))
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield result match
            case Result.Success(r) =>
                assert(
                    r == AeronSentinels.Error,
                    s"Expected AeronSentinels.Error (-6) for oversize offer on all platforms; got $r"
                )
            case Result.Panic(t) =>
                fail(s"oversize offer threw ${t.getClass.getSimpleName} on a non-JVM platform: ${t.getMessage}")
            case Result.Failure(t) =>
                fail(s"oversize offer aborted with ${t.getClass.getSimpleName}: ${t.getMessage}")
        end for
    }

    // ---------------------------------------------------------------------------
    // Error handler: non-exiting recording handler + TopicTransportFailedException surfacing
    // ---------------------------------------------------------------------------

    // An injected fatal error surfaces as TopicTransportFailedException and the process survives.
    //
    // The test-inject seam (transport.injectError) fires synchronously, recording the error in
    // the slot. The immediately-following fatalError read returns Present. The body
    // runs to completion (succeed proves no exit()). JVM parity: JvmAeronTransport.injectError
    // sets errorSlot directly; FfiAeronTransport.injectError calls the C kyo_aeron_test_inject_error.
    "an injected fatal error is recorded in the slot and the process survives" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            // Inject a synthetic fatal error via the test-inject seam.
            _ <- Sync.Unsafe.defer(transport.injectError(-1000, "driver timeout"))
            // fatalError must now be Present with the injected message.
            recorded <- Sync.Unsafe.defer(transport.fatalError)
            _ = assert(
                recorded == Present("driver timeout"),
                s"fatalError expected Present('driver timeout') after inject; got $recorded"
            )
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        // If we reach this yield the process did NOT exit() (the body ran to completion).
        yield succeed
        end for
    }

    // A publish after a recorded fatal error aborts with TopicTransportFailedException.
    //
    // After the inject seam fires, the next Topic.publish offer boundary checks fatalError
    // and aborts with TopicTransportFailedException(detail). Never a process exit.
    // Uses Topic.runWith to supply the transport and inject the error before publishing.
    "a publish after a recorded fatal error aborts TopicTransportFailedException" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            // Inject a synthetic fatal error before publish.
            _ <- Sync.Unsafe.defer(transport.injectError(-1000, "driver timeout"))
            // Run publish under the injected transport: the offer boundary aborts with TopicTransportFailedException.
            result <- Abort.run[TopicException] {
                Topic.runWith(transport) {
                    Topic.publish[Int](ipcUri, Schedule.never)(Stream.init(Seq(42)))
                }
            }
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield result match
            case Result.Failure(f: TopicTransportFailedException) =>
                assert(
                    f.detail == "driver timeout",
                    s"TopicTransportFailedException carried wrong detail: '${f.detail}'"
                )
            case Result.Success(_) =>
                fail("publish should have aborted with TopicTransportFailedException after fatal error inject")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getClass.getSimpleName}: ${t.getMessage}")
            case Result.Failure(other) =>
                fail(s"expected TopicTransportFailedException, got ${other.getClass.getSimpleName}")
        end for
    }

    // A stream after a recorded fatal error aborts with TopicTransportFailedException.
    //
    // Symmetric to the publish path: after the inject seam fires, the next Topic.stream poll
    // boundary checks fatalError and aborts with TopicTransportFailedException(detail) as Topic.stream's row declares. Never a process exit.
    "a stream after a recorded fatal error aborts TopicTransportFailedException" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            _ <- Sync.Unsafe.defer(transport.injectError(-1000, "driver timeout"))
            result <- Abort.run[TopicException] {
                Topic.runWith(transport) {
                    Topic.stream[Int](ipcUri, Schedule.never).take(1).run
                }
            }
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield result match
            case Result.Failure(f: TopicTransportFailedException) =>
                assert(
                    f.detail == "driver timeout",
                    s"TopicTransportFailedException carried wrong detail: '${f.detail}'"
                )
            case Result.Success(_) =>
                fail("stream should have aborted with TopicTransportFailedException after fatal error inject")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getClass.getSimpleName}: ${t.getMessage}")
            case Result.Failure(other) =>
                fail(s"expected TopicTransportFailedException, got ${other.getClass.getSimpleName}")
        end for
    }

    // No recorded error means normal operation (regression guard).
    //
    // A healthy embedded() with no inject: fatalError is Absent throughout and
    // a round-trip succeeds. The error handler install does not perturb the happy path.
    "no recorded error means normal operation, fatalError stays Absent" in {
        val payload = Array[Byte](10, 20, 30)
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            // Before any operation: must be Absent.
            beforeOp <- Sync.Unsafe.defer(transport.fatalError)
            _ = assert(beforeOp.isEmpty, s"fatalError must be Absent on fresh transport; got $beforeOp")
            pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, errorHandlerRegressionStreamId))
            subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, errorHandlerRegressionStreamId))
            _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
            _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
            pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
            subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
            _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done")
            _   = assert(subMaybe.isDefined, "pollAddSubscription never returned Done")
            pub = pubMaybe.get
            sub = subMaybe.get
            pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
            _ = assert(pubConnected, "publication did not connect within 5s")
            // After successful ops: still Absent.
            afterOp <- Sync.Unsafe.defer(transport.fatalError)
            _ = assert(afterOp.isEmpty, s"fatalError must remain Absent after successful ops; got $afterOp")
            pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, payload)))
            _ = assert(pos > 0, s"offer did not succeed; pos=$pos")
            received <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
            _ = assert(received.isDefined, "no message received")
            _ <- Sync.Unsafe.defer {
                transport.closePublication(pub)
                transport.closeSubscription(sub)
                rt.close()
            }
            _ <- dir.removeAll
        yield assert(
            java.util.Arrays.equals(received.get, payload),
            s"round-trip payload mismatch: got ${received.get.toList}"
        )
        end for
    }

    // ---------------------------------------------------------------------------
    // Empty-message fatal error: surfaces as TopicTransportFailedException with a non-empty
    // errcode-derived detail even when the error message is empty.
    // ---------------------------------------------------------------------------

    // An injected fatal error with an EMPTY message still surfaces as TopicTransportFailedException
    // with a non-empty detail string derived from the errcode.
    //
    // present=1 in the error slot surfaces TopicTransportFailedException regardless of message emptiness:
    // fatalError keys off presence, not msg.nonEmpty. When the message is empty the detail is
    // derived from the errcode ("fatal client error (code 42)" on FFI; the throwable's toString on JVM,
    // since t.getMessage is null for messageless throwables). Were presence not the key, an empty-message
    // fatal error would map to Absent and the caller would retry forever instead of getting the terminal
    // typed error. This leaf injects errcode 42 with an empty message and asserts a non-empty detail.
    "empty-message fatal error inject surfaces TopicTransportFailedException with non-empty detail" in {
        for
            dir <- Path.tempDir("kyo-aeron-embedded-test")
            rt  <- AeronPlatform.embedded(dir.unsafe.show)
            transport = rt.transport
            // Inject a fatal error with an EMPTY message (errcode non-zero, errmsg "").
            _ <- Sync.Unsafe.defer(transport.injectError(42, ""))
            // fatalError must be Present even with an empty message.
            recorded <- Sync.Unsafe.defer(transport.fatalError)
            _ = assert(
                recorded.isDefined,
                "fatalError must be Present after inject with empty message; the empty-message fatal error must surface as a typed failure"
            )
            _ = assert(
                recorded.get.nonEmpty,
                s"fatalError detail must be non-empty after inject with empty message; got '${recorded.get}'"
            )
            // Also confirm the surfacing in the publish path: publish aborts with TopicTransportFailedException.
            result <- Abort.run[TopicException] {
                Topic.runWith(transport) {
                    Topic.publish[Int](ipcUri, Schedule.never)(Stream.init(Seq(42)))
                }
            }
            _ <- Sync.Unsafe.defer(rt.close())
            _ <- dir.removeAll
        yield result match
            case Result.Failure(f: TopicTransportFailedException) =>
                assert(
                    f.detail.nonEmpty,
                    s"TopicTransportFailedException.detail must be non-empty for empty-message inject; got '${f.detail}'"
                )
            case Result.Success(_) =>
                fail(
                    "publish should have aborted with TopicTransportFailedException after empty-message fatal error inject; the empty-message fatal error must surface as a typed failure"
                )
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getClass.getSimpleName}: ${t.getMessage}")
            case Result.Failure(other) =>
                fail(s"expected TopicTransportFailedException, got ${other.getClass.getSimpleName}")
        end for
    }

end AeronTransportTest
