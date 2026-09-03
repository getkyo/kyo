package kyo

import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.internal.AeronBindings
import kyo.internal.AeronClientHandle
import kyo.internal.AeronPlatform
import kyo.internal.AeronPublication
import kyo.internal.AeronRuntime
import kyo.internal.AeronSentinels
import kyo.internal.AeronSubscription
import kyo.internal.AeronTransport

class AeronTransportTest extends Test:

    val ipcUri = "aeron:ipc"

    /** Stream-ids are distinct per test to avoid cross-test subscription bleed: the embedded Aeron IPC
      * driver keeps subscription registrations until explicitly closed, so a lingering subscription from
      * a prior test would otherwise receive a later publish.
      */
    val roundTripStreamId    = 7
    val notConnectedStreamId = 8
    val absentStreamId       = 9
    val closeStreamId        = 10

    val addTimeoutStreamId    = 102
    val tickerStreamId        = 103
    val interruptStreamId     = 107
    val regressionAddStreamId = 108

    val uafLoopStreamId     = 109
    val uafStreamId         = 110
    val uafSentinelStreamId = 111

    val oversizeJvmStreamId   = 104
    val oversizeCrossStreamId = 105

    val tokenOwnershipStreamId = 117

    val closedClientPubStreamId = 115
    val closedClientSubStreamId = 116

    val errorHandlerInjectStreamId     = 106
    val errorHandlerPublishStreamId    = 112
    val errorHandlerRegressionStreamId = 113
    val errorHandlerEmptyMsgStreamId   = 114

    /** Distinct message type for the saturated UAF leaf, so its tag hash gives it its own stream. */
    case class UafSaturatedMsg(pad: String) derives CanEqual, Schema

    /** Waits until cond returns true, polling every 1ms with a non-blocking Async suspension. Returns
      * false if maxAttempts is exhausted.
      */
    private def awaitTrue(maxAttempts: Int)(cond: => Boolean < Sync)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(false)
            else
                cond.map { ok =>
                    if ok then Loop.done(true)
                    else Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    /** Retries pollFn every 1ms until it returns Present or maxAttempts is exhausted. */
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

    /** Retries offerFn every 1ms until it returns > 0 or maxAttempts is exhausted. */
    private def offerUntil(maxAttempts: Int)(offerFn: => Long < Sync)(using Frame): Long < Async =
        Loop.indexed { i =>
            if i >= maxAttempts then Loop.done(-1L)
            else
                offerFn.map { r =>
                    if r > 0 then Loop.done(r)
                    else Async.sleep(1.millis).andThen(Loop.continue)
                }
        }

    /** Polls an async publication token until Done, or Absent on Failed / maxAttempts exhausted. */
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

    /** Polls an async subscription token until Done, Absent (on Failed), or maxAttempts exhausted. */
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

    /** Drives one async-add publication to Done using the raw AeronBindings rather than the
      * AeronTransport abstraction. The after-close leaf below needs to close the client out from under a
      * still-held inner handle, a sequence the transport API hides.
      */
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

    /** Drives one async-add subscription to Done via the Scala Async poll loop, using the raw bindings. */
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

    /** JS-safe description of an add-deadline outcome for assertion clues. Interpolating a raw Result
      * whose success side holds an FFI Handle (an opaque koffi pointer) throws "TypeError: Cannot convert
      * object to primitive value" on Scala.js string coercion; this projects the failure side to its
      * exception class name and never coerces the Handle.
      */
    private def describeAddFailure[E, A](result: Result[E, A]): String =
        result match
            case Result.Failure(e) => s"Failure(${e.getClass.getSimpleName})"
            case Result.Panic(t)   => s"Panic(${t.getClass.getSimpleName})"
            case Result.Success(_) => "Success"

    /** asyncAdd* hand back a token immediately but pollAdd* always return Awaiting, modelling a
      * registration the driver never confirms. This drives the real deadline loop deterministically on
      * every platform without manipulating a live driver.
      *
      * Injected at the transport seam rather than using a real stopped/absent driver because on the C
      * (Native/JS) path a real conductor error triggers the Aeron C client's default error handler, which
      * calls exit(EXIT_FAILURE) and kills the test process. The real FFI add path (Done) is covered by
      * the round-trip and normal-add leaves.
      */
    /** Transport whose registration poll reports Done and, inside that same synchronous call, interrupts the
      * fiber that is observing it.
      *
      * On a Done poll the C layer frees the async token and hands the client reference to the publication
      * bundle, so the Scala flag recording that handoff is the only thing standing between the scope
      * finalizer and a second free of memory the C layer has already released. The question this fake exists
      * to answer is whether an interrupt can be observed BETWEEN the poll and that flag, which is only
      * possible if a suspension point separates them. Issuing the interrupt from inside the poll puts a
      * pending interrupt in place before the caller has decided anything, which is the earliest the window
      * can open.
      *
      * The counter is deliberately gated on `doneObserved`: a free BEFORE any Done is the ordinary
      * still-owned path and is correct, and counting it would make the leaf pass or fail for the wrong
      * reason. `interruptTook` and `doneObserved` are asserted by the leaf so a fixture that never armed the
      * window cannot be read as evidence that the window is closed.
      */
    final private class InterruptOnDoneTransport extends AeronTransport:
        type Publication  = Int
        type Subscription = Int
        type AsyncPub     = Int
        type AsyncSub     = Int

        /** Set by the leaf once it holds the fiber handle; a thunk rather than the fiber itself so the fake
          * carries no type parameters of the computation it interrupts.
          */
        @volatile var interrupter: () => Boolean = () => false
        @volatile var interruptTook: Boolean     = false
        @volatile var doneObserved: Boolean      = false

        val freesAfterDone = new java.util.concurrent.atomic.AtomicInteger(0)

        def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] = Present(streamId)
        def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
            interruptTook = interrupter()
            doneObserved = true
            AeronTransport.AddPoll.Done(async)
        end pollAddPublication
        def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit =
            if doneObserved then discard(freesAfterDone.incrementAndGet())
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

        def fatalError(using AllowUnsafe): Maybe[String] = Absent
    end InterruptOnDoneTransport

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

        /** NeverConfirmTransport has no error slot: inherits the default no-op injectError. */
        def fatalError(using AllowUnsafe): Maybe[String] = Absent
    end NeverConfirmTransport

    /** Transport reproducing the state a fiber sees when the client closes underneath it: the
      * registration never confirms, connectivity reads false, polls come back empty, and the error
      * slot stays empty, because closing a client is not a conductor error.
      *
      * Every one of those signals is also a legitimate transient state (a registration still in
      * flight, a peer that has not attached yet, a poll with no message), so `clientClosed` is the
      * only thing separating "wait and retry" from "this can never succeed". Without the
      * `clientClosed` gates in `Topic`, a fiber holding a handle across a client close retried its
      * backoff schedule forever, or polled the add loop to its 10s deadline, keeping a carrier busy
      * long after the client was gone.
      */
    final private class ClosedClientTransport extends AeronTransport:
        type Publication  = Int
        type Subscription = Int
        type AsyncPub     = Int
        type AsyncSub     = Int
        def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] = Present(streamId)
        def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
            AeronTransport.AddPoll.Awaiting
        def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit                               = ()
        def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean                 = false
        def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long               = AeronSentinels.Closed
        def maxMessageLength(pub: Publication)(using AllowUnsafe): Int                           = 0
        def closePublication(pub: Publication)(using AllowUnsafe): Unit                          = ()
        def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] = Present(streamId)
        def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription] =
            AeronTransport.AddPoll.Awaiting
        def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit                 = ()
        def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean = false
        def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]]      = Absent
        def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit          = ()
        def fatalError(using AllowUnsafe): Maybe[String]                           = Absent

        override def clientClosed(using AllowUnsafe): Boolean = true
    end ClosedClientTransport

    "round-trip a known byte payload through AeronPlatform.embedded" in {
        val payload = Array[Byte](1, 2, 3, 4)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                }
            yield assert(
                java.util.Arrays.equals(bytes, payload),
                s"payload mismatch: ${bytes.toList} != ${payload.toList}"
            )
            end for
        }
    }

    "offer to not-connected publication takes the not-connected path" in {
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, notConnectedStreamId))
                _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
                pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
                _   = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
                pub = pubMaybe.get
                notConnectedInitially <- Sync.Unsafe.defer(!transport.publicationIsConnected(pub))
                subTokMaybe           <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, notConnectedStreamId))
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
                }
            yield
                assert(notConnectedInitially, "Expected publication to be not-connected with no subscriber")
                assert(connectedPosition > 0, s"Expected a positive position after connection; got $connectedPosition")
            end for
        }
    }

    "pollOne with zero fragments returns Absent" in {
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                }
            yield assert(result.isEmpty, s"Expected Absent when no message published; got $result")
            end for
        }
    }

    "close releases resources without leak" in {
        // The explicit close in the middle is the behaviour under test, and the scope closes again on the
        // way out; the driver close is one-shot, so the repeat is a no-op rather than a fault.
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                pubTokMaybe <- Sync.Unsafe.defer(transport.asyncAddPublication(ipcUri, closeStreamId))
                subTokMaybe <- Sync.Unsafe.defer(transport.asyncAddSubscription(ipcUri, closeStreamId))
                _ = assert(pubTokMaybe.isDefined, "asyncAddPublication returned Absent on a live client")
                _ = assert(subTokMaybe.isDefined, "asyncAddSubscription returned Absent on a live client")
                // The poll helper yields to the event loop between polls (Async.sleep), which the
                // single-threaded JS runtime needs to converge; a synchronous spin would never advance.
                pubMaybe <- pollAddPubUntilDone(transport, pubTokMaybe.get, 5000)
                subMaybe <- pollAddSubUntilDone(transport, subTokMaybe.get, 5000)
                _ = assert(pubMaybe.isDefined, "pollAddPublication never returned Done within 5000 attempts")
                _ = assert(subMaybe.isDefined, "pollAddSubscription never returned Done within 5000 attempts")
                _ <- Sync.Unsafe.defer {
                    transport.closePublication(pubMaybe.get)
                    transport.closeSubscription(subMaybe.get)
                    rt.close()
                }
                // A second embedded() confirms the close was clean.
                out <- withEmbeddedRuntime() { rt2 =>
                    val transport2 = rt2.transport
                    for
                        pub2TokMaybe <- Sync.Unsafe.defer(transport2.asyncAddPublication(ipcUri, closeStreamId))
                        _ = assert(pub2TokMaybe.isDefined, "asyncAddPublication returned Absent on a freshly-started client")
                        // Free the pending token without waiting for registration to complete.
                        _ <- Sync.Unsafe.defer(transport2.freeAsyncPub(pub2TokMaybe.get))
                    // Reaching here without a crash proves the close-and-reopen cycle succeeded.
                    yield succeed
                    end for
                }
            yield out
            end for
        }
    }

    "offer result maps through AeronSentinels identically on every platform" in {
        // AeronPlatform.embedded() reaches FfiAeronTransport on every platform, so one leaf covers the
        // single impl. TopicInvariantsTest defers its offer-sentinel coverage here, keeping only a
        // `.onlyJs` leaf for the koffi BigInt->Long sign-marshalling guard.
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                payload  = Array[Byte](0)
                result <- Sync.Unsafe.defer(transport.offer(pub, payload))
                _ <- Sync.Unsafe.defer {
                    transport.closePublication(pub)
                }
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
    }

    "two exact types isolate by stream-id" in {
        // The stream-id derivation is tag.hash.abs, so two distinct Scala types land on different
        // stream-ids and cannot deliver each other's messages.
        val intId    = Tag[Int].hash.abs
        val stringId = Tag[String].hash.abs
        val payload  = Array[Byte](99)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                connected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pubInt)))
                _ = assert(connected, "Int publication did not connect to Int subscriber within 5s")
                pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pubInt, payload)))
                _ = assert(pos > 0, s"offer to Int stream did not succeed; last pos=$pos")
                intReceived <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(subInt)))
                _ = assert(
                    intReceived.isDefined,
                    "Int subscription did not receive its own message within 5000 poll attempts"
                )
                stringResult <- Sync.Unsafe.defer(transport.pollOne(subString))
                _ <- Sync.Unsafe.defer {
                    transport.closePublication(pubInt)
                    transport.closeSubscription(subInt)
                    transport.closeSubscription(subString)
                }
            yield assert(
                stringResult.isEmpty,
                s"Expected Absent on String subscription after publishing to Int stream; got $stringResult"
            )
            end for
        }
    }

    "pollOne reassembles a multi-fragment message (>MTU) correctly" in {
        // 4096 bytes > 1408-byte IPC MTU; triggers multi-fragment reassembly on both
        // the JVM FragmentAssembler and the C shim's aeron_fragment_assembler.
        val multiFragId = 98 // distinct stream-id; no cross-test bleed
        val payload     = Array.tabulate[Byte](4096)(i => (i & 0xff).toByte)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                }
            yield assert(
                java.util.Arrays.equals(received.get, payload),
                s"multi-fragment payload mismatch: expected 4096 bytes, got ${received.get.length}"
            )
            end for
        }
    }

    // Messages between 1 MiB and Aeron's protocol ceiling must round-trip on all platforms.
    "pollOne reassembles a 2 MiB multi-fragment message identically on all platforms" in {
        val largeFragId = 99 // distinct stream-id; no cross-test bleed
        val large       = Array.tabulate[Byte](2 * 1024 * 1024)(i => (i & 0xff).toByte)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                }
            yield assert(
                java.util.Arrays.equals(received.get, large),
                s"2 MiB payload mismatch: expected ${large.length} bytes, got ${received.get.length}"
            )
            end for
        }
    }

    // Slot reuse / regrow correctness: large then small on the same subscription.
    "pollOne handles a small message after a large one on the same subscription" in {
        val reuseStreamId = 100 // distinct stream-id; no cross-test bleed
        val large         = Array.tabulate[Byte](256 * 1024)(i => ((i * 31) & 0xff).toByte)
        val small         = Array[Byte](9, 8, 7, 6, 5)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                posLarge <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, large)))
                _ = assert(posLarge > 0, s"large offer did not succeed; last pos=$posLarge")
                receivedLarge <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
                _ = assert(receivedLarge.isDefined, "no reassembled large message received within 5000 poll attempts")
                _ = assert(
                    java.util.Arrays.equals(receivedLarge.get, large),
                    s"large payload mismatch: expected ${large.length} bytes, got ${receivedLarge.get.length}"
                )
                posSmall <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, small)))
                _ = assert(posSmall > 0, s"small offer did not succeed; last pos=$posSmall")
                receivedSmall <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
                _ = assert(receivedSmall.isDefined, "no small message received within 5000 poll attempts")
                _ <- Sync.Unsafe.defer {
                    transport.closePublication(pub)
                    transport.closeSubscription(sub)
                }
            yield assert(
                java.util.Arrays.equals(receivedSmall.get, small),
                s"small payload mismatch after large: expected ${small.toList}, got ${receivedSmall.get.toList}"
            )
            end for
        }
    }

    // Use-after-free regression guard: kyo_aeron_async_add_publication checks the registry (bundle still
    // live under g_registry_mutex) before touching any field. Without that check, locking the bundle's
    // own c->close_mutex to increment c->refcount would dereference memory client_bundle_release already
    // freed, a UAF that segfaults on Native.
    "add-vs-close: asyncAddPublication and asyncAddSubscription on a closed client return Absent, not a crash" in {
        Loop.indexed { i =>
            if i >= 20 then Loop.done(succeed)
            else
                Path.run(Path.tempDir("kyo-aeron-embedded-test")).flatMap { dir =>
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
                        }
                    }
                }.andThen(Loop.continue)
        }
    }

    // ---------------------------------------------------------------------------
    // Bounded Scala Async poll loop (add-deadline behavior)
    // ---------------------------------------------------------------------------

    "an interrupt on a completed add does not free a token the transport has already taken" in {
        val transport = new InterruptOnDoneTransport
        Latch.initWith(1) { gate =>
            Fiber.initUnscoped(
                gate.await.andThen(
                    Abort.run[TopicTransportException](
                        Topic.addPublicationDeadline(transport, ipcUri, tokenOwnershipStreamId, 10.seconds)
                    )
                )
            ).flatMap { fiber =>
                Sync.Unsafe.defer {
                    // Unsafe: the fake has to interrupt from inside a synchronous transport call, which is the
                    // only vantage point where the window under test is open.
                    transport.interrupter = () => fiber.unsafe.interrupt()
                }
                    .andThen(gate.release)
                    .andThen(fiber.getResult)
                    .map { result =>
                        assert(transport.doneObserved, "the poll never reported Done, so the fixture never armed the window")
                        assert(
                            transport.interruptTook,
                            "the fixture's interrupt did not take, so a zero free count says nothing about the window"
                        )
                        // The outcome is what separates the two readings of a zero free count: an interrupted add proves the
                        // interrupt was OBSERVED and the flag still won, while a completed add proves only that the add
                        // outran the interrupt and the window was never entered at all.
                        assert(
                            result.isPanic,
                            s"the add was not interrupted, so this leaf did not exercise the ownership window: $result"
                        )
                        assert(
                            transport.freesAfterDone.get() == 0,
                            s"the async token was freed ${transport.freesAfterDone.get()} time(s) after the transport had " +
                                "already taken ownership of it on a Done poll"
                        )
                    }
            }
        }
    }

    /** Closing an embedded runtime twice must not take the process down.
      *
      * The client half already treats this as a real caller shape: its close wins a CAS before touching the
      * handle, because the shim's client close calls `aeron_close` unconditionally and a second one tears
      * down freed memory. The driver half has the same shim contract, `kyo_aeron_driver_close` frees its
      * bundle outright, and had no such guard, so the second close read a freed pointer and faulted inside
      * the conductor-agent stop. A resource released on both an error path and its scope exit is exactly the
      * caller this protects, and it is not exotic: this leaf closes explicitly and lets the scope close again.
      */
    "closing an embedded runtime twice does not tear down freed driver state" in {
        withEmbeddedRuntime() { rt =>
            Sync.Unsafe.defer(rt.close()).andThen(succeed)
        }
    }

    /** A body that throws must still release its driver.
      *
      * This is the property the scoped helper exists for, and it is the one that cannot be read off the
      * source: whether the finalizer actually runs is a fact about execution. Every leaf in this module
      * depends on it, because the alternative is a live C media driver and its conductor thread surviving a
      * failed assertion for the rest of the process.
      */
    "a driver is released even when the body that uses it throws" in {
        val before = embeddedRuntimeReleases.get()
        Abort.run[Throwable](
            withEmbeddedRuntime() { _ =>
                Sync.defer[Unit, Any](throw new RuntimeException("the body fails after the driver is up"))
            }
        ).map { result =>
            assert(result.isPanic, s"the fixture must fail inside the scope for this to prove anything, got $result")
            assert(
                embeddedRuntimeReleases.get() == before + 1,
                s"the driver was not released: releases went from $before to ${embeddedRuntimeReleases.get()}"
            )
        }
    }

    /** A live driver must name itself where a descriptor leak is reported.
      *
      * The leak check attaches the diagnostics dump to every surviving descriptor so the survivor says who
      * owns it. A media driver holds descriptors and, until it registered, contributed nothing to that dump,
      * so an abandoned one was reported as a bare inode with no owner. Asserting on this leaf's own unique
      * directory rather than on the entry name keeps it specific while other suites run in parallel.
      */
    "a live driver names itself in the diagnostics dump" in {
        val probeDir = "kyo-aeron-diagnostics-probe"
        withEmbeddedRuntime(probeDir) { rt =>
            Sync.Unsafe.defer {
                val whileLive = kyo.internal.Diagnostics.dumpAll()
                rt.close()
                val afterClose = kyo.internal.Diagnostics.dumpAll()
                assert(whileLive.contains(probeDir), "a live driver is not named in the diagnostics dump")
                assert(!afterClose.contains(probeDir), "a closed driver is still named in the diagnostics dump")
            }
        }
    }

    "a never-confirming add aborts with TopicAddTimeoutException at the deadline" in {
        val deadline  = 200.millis
        val transport = new NeverConfirmTransport
        // The tested behavior is the 200ms TopicAddTimeoutException firing; the outer watchdog only turns a genuine hang (the exception never
        // arriving) into a clean failure. It sits at 10s, far above the 200ms deadline, so a slow runner detecting the deadline a little late
        // cannot let the watchdog win the race and mask the real behavior as a hang.
        Abort.run[Timeout | TopicTransportException] {
            Async.timeout(10.seconds) {
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
                fail("addPublicationDeadline hung past the watchdog instead of aborting with TopicAddTimeoutException")
            case Result.Failure(other) =>
                fail(s"expected TopicAddTimeoutException, got ${other.getClass.getSimpleName}")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getClass.getSimpleName}")
        }
    }

    // Closed-client termination. A fiber can outlive the client it publishes through: Topic.run's scope
    // can exit while a fiber forked with Fiber.initUnscoped still holds handles, which is exactly what the
    // UAF leaves below provoke. Every post-close signal the transport can give is also a legitimate
    // transient one (a registration still awaiting confirmation, a peer that has not attached, a poll with
    // no message), so before Topic gated these loops on clientClosed such a fiber never stopped: the add
    // loop polled to its 10s deadline and the publish/stream loops retried their backoff schedule forever,
    // keeping a carrier busy long after the client was gone. Both watchdogs are far below the 10s add
    // deadline, so a missing gate surfaces as the Timeout winning rather than as a hang.
    "a publish whose client is closed aborts terminally instead of polling to the add deadline" in {
        val transport = new ClosedClientTransport
        Abort.run[Timeout | TopicException] {
            Async.timeout(2.seconds) {
                Topic.runWith(transport) {
                    Topic.publish[Int](ipcUri, streamId = Present(closedClientPubStreamId))(Stream.init(Seq(1)))
                }
            }
        }.map {
            case Result.Failure(_: TopicPublicationClosedException) =>
                succeed
            case Result.Failure(_: Timeout) =>
                fail("publish kept polling past the 2s watchdog instead of aborting on the closed client")
            case other =>
                fail(s"expected TopicPublicationClosedException, got $other")
        }
    }

    "a stream whose client is closed aborts terminally instead of polling to the add deadline" in {
        val transport = new ClosedClientTransport
        Abort.run[Timeout | TopicException] {
            Async.timeout(2.seconds) {
                Topic.runWith(transport) {
                    Topic.stream[Int](ipcUri, streamId = Present(closedClientSubStreamId)).take(1).run
                }
            }
        }.map {
            case Result.Failure(_: TopicBackpressureExhaustedException) =>
                succeed
            case Result.Failure(_: Timeout) =>
                fail("stream kept polling past the 2s watchdog instead of aborting on the closed client")
            case other =>
                fail(s"expected TopicBackpressureExhaustedException, got $other")
        }
    }

    // addPublicationDeadline sleeps between poll steps rather than blocking the carrier, so a concurrent
    // ticker fiber keeps advancing while the add is pending; the deadline and tick counts below leave
    // enough headroom to make that observation reliable.
    "a pending never-confirming add does not starve a concurrent ticker fiber" in {
        val tickerThreshold = 5
        val deadline        = 500.millis
        val transport       = new NeverConfirmTransport
        for
            counter <- AtomicInt.init(0)
            tickerFiber <- Fiber.initUnscoped {
                Loop.indexed { i =>
                    if i >= tickerThreshold then Loop.done(())
                    else Async.sleep(1.millis).andThen(counter.incrementAndGet).andThen(Loop.continue)
                }
            }
            addFiber <- Fiber.initUnscoped {
                Abort.run[TopicTransportException] {
                    Topic.addPublicationDeadline(transport, ipcUri, tickerStreamId, deadline)
                }
            }
            _             <- Async.sleep(20.millis)
            tickerReached <- awaitTrue(300)(counter.get.map(_ >= tickerThreshold))
            _ = assert(
                tickerReached,
                "ticker fiber did not advance while add was pending: carrier starvation by blocking add"
            )
            addResult <- addFiber.get
            _ = addResult match
                case Result.Failure(_: TopicAddTimeoutException) => ()
                case other => fail(s"pending add did not abort with TopicAddTimeoutException: ${describeAddFailure(other)}")
            _ <- tickerFiber.get
        yield succeed
        end for
    }

    // Either outcome is acceptable (Success if the live driver completes before the timeout,
    // Failure(Timeout) if not); what matters is that it never hangs past 100ms, proving the poll loop is
    // interruptible.
    "an interrupt during a pending add is honored and does not hang" in {
        // The runtime close cleans up any publication the add produced; the Sync.ensure inside
        // addPublicationDeadline frees the async token if interrupted mid-poll.
        withEmbeddedRuntime() { rt =>
            Abort.run[Timeout | TopicTransportException] {
                Async.timeout(100.millis) {
                    Topic.addPublicationDeadline(rt.transport, ipcUri, interruptStreamId, 10.seconds)
                }
            }.andThen(succeed)
        }
    }

    "a normal add via addPublicationDeadline completes and round-trips bytes" in {
        val payload = Array[Byte](42, 43, 44)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                pubMaybeResult <- Abort.run[TopicTransportException] {
                    Topic.addPublicationDeadline(transport, ipcUri, regressionAddStreamId, 10.seconds)
                }
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
                }
            yield assert(
                java.util.Arrays.equals(received.get, payload),
                s"round-trip payload mismatch: got ${received.get.toList}"
            )
            end for
        }
    }

    // ---------------------------------------------------------------------------
    // UAF guard: no inner-handle deref reads memory freed by a concurrent client close.
    // ---------------------------------------------------------------------------

    // Because the forked fibers are unscoped, Topic.run's body completes and fires runtime.close() while
    // the publish fiber may still be mid-offer and the stream fiber mid-poll. Every platform turns that
    // into a safe sentinel rather than a UAF, through kyo_aeron.c's close_mutex+closing guard on every
    // platform. The JVM is not exempt by virtue of running on a managed heap: offer copies into a claimed
    // log-buffer region and poll writes the subscriber position into the CnC, both mapped memory that
    // closing the client unmaps. Running the JVM on the Java client instead is what made this leaf crash:
    // that path re-derived the guard as a per-handle gate whose drain could miss a publication registered
    // concurrently, so an offer wrote into a term buffer the close had already unmapped.
    // 100 full Topic.run lifecycles (add + fork pub/sub + close) is heavy and on the slow single-threaded windows JS
    // runner runs near the 120s default budget. The count is the regression sensitivity (pre-fix UAF reproduced ~once
    // per run at 100), so give the leaf headroom rather than reduce it; fast platforms still finish in seconds (ceiling).
    "UAF-loop: a high-iteration forked-then-close loop does not use-after-free".timeout(5.minutes) in {
        val iterations = 100
        val messages   = Seq(1, 2, 3)
        Loop.indexed { i =>
            if i >= iterations then Loop.done(succeed)
            else
                Topic.run {
                    for
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.stream[Int]("aeron:ipc").take(messages.size).run
                        )
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.publish[Int]("aeron:ipc")(Stream.init(messages))
                        )
                        // Let both fibers enter their offer/poll loops before the body completes.
                        _ <- Async.sleep(1.millis)
                    yield ()
                }.andThen(Loop.continue)
        }
    }

    // The loop above publishes three ints, so its fibers spend most of the window in add and backoff
    // rather than inside offer, which made the crash it guards against intermittent: it reproduced on CI
    // roughly once per run. Saturating the window instead (20k messages of ~1KB) keeps the publish fiber
    // continuously inside offer's claim-and-copy when the close lands, which segfaulted the carrier on
    // every run before the OpsGate existed.
    // Same heavy profile as UAF-loop (50 iterations, each publishing 20k ~1KB messages), so give it the
    // same explicit headroom above the 120s default.
    "UAF-saturated: a close landing inside a running offer/poll loop does not use-after-free".timeout(5.minutes) in {
        val iterations = 50
        val payload    = "x" * 1024
        val messages   = Seq.fill(20000)(UafSaturatedMsg(payload))
        Loop.indexed { i =>
            if i >= iterations then Loop.done(succeed)
            else
                Topic.run {
                    for
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.stream[UafSaturatedMsg]("aeron:ipc").take(messages.size).run
                        )
                        _ <- Fiber.initUnscoped(using Topic.isolate)(
                            Topic.publish[UafSaturatedMsg]("aeron:ipc")(Stream.init(messages))
                        )
                        // Long enough for both fibers to be deep inside offer/poll, short enough that
                        // the close lands mid-flight rather than after the stream drains.
                        _ <- Async.sleep(5.millis)
                    yield ()
                }.andThen(Loop.continue)
        }
    }

    // The close_mutex+closing freed-handle guard lives in kyo_aeron.c, which every platform now reaches,
    // so this runs on the JVM too rather than being gated off it. The after-close
    // interleaving is deterministic through Scala-side sequencing, not a C-side busy-spin: each read runs
    // only after the client-close downcall already freed the inner handle. The bundles' refcounts keep the
    // client bundle's close_mutex alive across the close, so each guard's acquire is always valid.
    "UAF-reads: publicationIsConnected/subscriptionIsConnected/subscriptionPoll after a client close return safe sentinels, not a UAF" in {
        val pollDstCap = 64 * 1024
        for
            bindings <- Sync.Unsafe.defer(Ffi.load[AeronBindings])
            // A unique per-instance dir rather than null, which would route to Aeron's single
            // shared default directory and risk colliding with a concurrent run.
            dir <- Path.run(Path.tempDir("kyo-aeron-uaf-reads"))
            // driverStart/clientConnect are @Ffi.blocking, so each yields a Fiber.Unsafe bridged
            // via .safe.get.
            driver   <- Sync.Unsafe.defer(bindings.driverStart(dir.unsafe.show, 0L, 0L)).flatMap(_.safe.get)
            client   <- Sync.Unsafe.defer(bindings.clientConnect(dir.unsafe.show)).flatMap(_.safe.get)
            pubMaybe <- addPublicationRaw(bindings, client, ipcUri, uafStreamId, 5000)
            subMaybe <- addSubscriptionRaw(bindings, client, ipcUri, uafStreamId, 5000)
            _   = assert(pubMaybe.isDefined, "addPublication never returned Done within 5000 attempts")
            _   = assert(subMaybe.isDefined, "addSubscription never returned Done within 5000 attempts")
            pub = pubMaybe.get
            sub = subMaybe.get
            // Close the client out from under the still-held publication and subscription: frees the
            // inner aeron_publication_t / aeron_subscription_t.
            _ <- Sync.Unsafe.defer(bindings.clientClose(client))
            // The three guarded reads, each on a now-freed inner handle.
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
            _ <- Path.run(dir.removeAll)
        yield
            assert(pubConnected == 0, s"publicationIsConnected after close must be 0 (not connected); got $pubConnected")
            assert(subConnected == 0, s"subscriptionIsConnected after close must be 0 (not connected); got $subConnected")
            assert(polled == 0L, s"subscriptionPoll after close must be 0 (no fragment / Absent); got $polled")
        end for
    }

    // Reproduces the freed-handle deref directly: add a publication, close the client (freeing the inner
    // aeron_publication_t while the bundle's refcount keeps it valid), then offer on the still-held handle,
    // reaching kyo_aeron_publication_offer's deref of b->publication. Native/JS return AeronSentinels.Closed
    // (-4) via the guard; JVM's Publication.offer on a closed publication returns the same sentinel without
    // faulting. Raw transport.offer avoids Topic.publish, which would re-add a publication first.
    "UAF-sentinel: an offer on a publication after a concurrent client close returns the safe sentinel" in {
        val payload = Array[Byte](1, 2, 3, 4)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                _ <- Sync.Unsafe.defer(rt.close())
                // Offer on the freed-inner-handle publication.
                offerResult <- Sync.Unsafe.defer(transport.offer(pub, payload))
                // Release the publication bundle's client-bundle ref (closing=1, so it skips the freed
                // handle and frees the bundle).
                _ <- Sync.Unsafe.defer(transport.closePublication(pub))
            yield assert(
                offerResult == AeronSentinels.Closed,
                s"expected offer on a closed-client publication to return AeronSentinels.Closed (-4); got $offerResult"
            )
            end for
        }
    }

    // ---------------------------------------------------------------------------
    // Oversize offer: an oversize offer returns -6 identically on all platforms;
    // JVM must not throw IllegalArgumentException.
    // ---------------------------------------------------------------------------

    // The C client returns -6 (AeronSentinels.Error) for an oversize offer on every platform, so this
    // runs everywhere rather than pinning the JVM-only IllegalArgumentException-to--6 conversion the Java
    // client needed. The subscriber and awaited publicationIsConnected are required since an unconnected
    // offer returns -1.
    "oversize offer on term-length=65536 returns -6, does not throw" in {
        val oversizeUri = "aeron:ipc?term-length=65536"
        val oversize    = Array.fill[Byte](8193)(0)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                pubConnected <- awaitTrue(5000)(Sync.Unsafe.defer(transport.publicationIsConnected(pub)))
                _ = assert(pubConnected, "publication did not connect within 5s")
                result <- Abort.run[Throwable](Sync.Unsafe.defer(transport.offer(pub, oversize)))
                _ <- Sync.Unsafe.defer {
                    transport.closePublication(pub)
                    transport.closeSubscription(sub)
                }
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
    }

    // Same subscriber+awaitConnected anti-flakiness pattern as the JVM oversize-offer leaf.
    "oversize offer on term-length=65536 returns -6 on every platform" in {
        val oversizeUri = "aeron:ipc?term-length=65536"
        val oversize    = Array.fill[Byte](8193)(0)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                }
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
    }

    // ---------------------------------------------------------------------------
    // Error handler: non-exiting recording handler + TopicTransportFailedException surfacing
    // ---------------------------------------------------------------------------

    // The inject seam fires synchronously: FfiAeronTransport.injectError calls the C
    // kyo_aeron_test_inject_error. Reaching the yield proves the recording error handler did not
    // exit() the process.
    "an injected fatal error is recorded in the slot and the process survives" in {
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                _        <- Sync.Unsafe.defer(transport.injectError(-1000, "driver timeout"))
                recorded <- Sync.Unsafe.defer(transport.fatalError)
                _ = assert(
                    recorded == Present("driver timeout"),
                    s"fatalError expected Present('driver timeout') after inject; got $recorded"
                )
            yield succeed
            end for
        }
    }

    // The next offer boundary checks fatalError and aborts, never exits the process.
    "a publish after a recorded fatal error aborts TopicTransportFailedException" in {
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                _ <- Sync.Unsafe.defer(transport.injectError(-1000, "driver timeout"))
                result <- Abort.run[TopicException] {
                    Topic.runWith(transport) {
                        Topic.publish[Int](ipcUri, Schedule.never)(Stream.init(Seq(42)))
                    }
                }
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
    }

    // Symmetric to the publish path, at the stream's poll boundary.
    "a stream after a recorded fatal error aborts TopicTransportFailedException" in {
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                _ <- Sync.Unsafe.defer(transport.injectError(-1000, "driver timeout"))
                result <- Abort.run[TopicException] {
                    Topic.runWith(transport) {
                        Topic.stream[Int](ipcUri, Schedule.never).take(1).run
                    }
                }
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
    }

    // Regression guard: installing the error handler must not perturb the happy path.
    "no recorded error means normal operation, fatalError stays Absent" in {
        val payload = Array[Byte](10, 20, 30)
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
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
                afterOp <- Sync.Unsafe.defer(transport.fatalError)
                _ = assert(afterOp.isEmpty, s"fatalError must remain Absent after successful ops; got $afterOp")
                pos <- offerUntil(5000)(Sync.Unsafe.defer(transport.offer(pub, payload)))
                _ = assert(pos > 0, s"offer did not succeed; pos=$pos")
                received <- pollUntil(5000)(Sync.Unsafe.defer(transport.pollOne(sub)))
                _ = assert(received.isDefined, "no message received")
                _ <- Sync.Unsafe.defer {
                    transport.closePublication(pub)
                    transport.closeSubscription(sub)
                }
            yield assert(
                java.util.Arrays.equals(received.get, payload),
                s"round-trip payload mismatch: got ${received.get.toList}"
            )
            end for
        }
    }

    // fatalError keys off the slot's presence flag, not msg.nonEmpty, so an empty message still surfaces
    // the terminal typed error. The detail is then derived from the errcode ("fatal client error (code
    // 42)" on FFI; the throwable's toString on JVM, since getMessage is null for messageless throwables).
    // Were presence not the key, an empty-message fatal error would map to Absent and retry forever.
    "empty-message fatal error inject surfaces TopicTransportFailedException with non-empty detail" in {
        withEmbeddedRuntime() { rt =>
            val transport = rt.transport
            for
                _        <- Sync.Unsafe.defer(transport.injectError(42, ""))
                recorded <- Sync.Unsafe.defer(transport.fatalError)
                _ = assert(
                    recorded.isDefined,
                    "fatalError must be Present after inject with empty message; the empty-message fatal error must surface as a typed failure"
                )
                _ = assert(
                    recorded.get.nonEmpty,
                    s"fatalError detail must be non-empty after inject with empty message; got '${recorded.get}'"
                )
                result <- Abort.run[TopicException] {
                    Topic.runWith(transport) {
                        Topic.publish[Int](ipcUri, Schedule.never)(Stream.init(Seq(42)))
                    }
                }
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
    }

end AeronTransportTest
