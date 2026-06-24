package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronSentinels
import kyo.internal.AeronTransport

/** Typed publish-subscribe messaging for local and distributed systems.
  *
  * `Topic` carries typed messages over the Aeron transport. It targets
  * low-latency inter-process communication (IPC) on the same machine through shared
  * memory, and also carries messages over UDP to remote services.
  *
  * Each message type needs a `Schema[A]` instance; the transport layer handles message
  * fragmentation and flow control. Publish a stream of messages with
  * [[Topic.publish]]; subscribe to typed messages with [[Topic.stream]].
  *
  * Messaging is type-safe: the message type's `Tag` selects a distinct Aeron stream and
  * is carried in every message, so a subscriber only receives messages published under
  * its exact type. A stream of a parent type does not receive messages published as a
  * subtype.
  *
  * @see [[Topic.run]] to handle `Topic` with an embedded or external driver
  * @see [[Topic.publish]] to publish a stream of messages
  * @see [[Topic.stream]] to subscribe to a stream of messages
  * @see [[TopicException]] for the failures publish and stream can abort with
  * @see [[https://aeron.io/]] for documentation on Aeron URIs.
  */
opaque type Topic <: Env[AeronTransport] = Env[AeronTransport]

object Topic:

    private[kyo] case class Envelope[A](typeTag: String, messages: Chunk[A]) derives Schema

    /** Default retry schedule used by [[Topic.publish]] and [[Topic.stream]] when no `retrySchedule`
      * is given: linear 10ms backoff, capped at 1s, with 20% jitter. Because transient backpressure,
      * not-connected, and no-data conditions are retried on this schedule, a momentarily quiet or slow
      * channel does not fail; a failure surfaces only after the schedule exhausts, as
      * [[TopicBackpressureExhaustedException]].
      */
    val defaultRetrySchedule: Schedule = Schedule.linear(10.millis).min(Schedule.fixed(1.second)).jitter(0.2)

    /** Backoff between async-add poll steps. */
    private val addBackoff: Duration = 1.milli

    /** Default timeout for the add-publication / add-subscription deadline loop. */
    private val defaultAddTimeout: Duration = 10.seconds

    /** Runs `v` with an embedded Aeron media driver that this call starts and owns.
      *
      * This is the zero-config entry point: reach for it when no external driver is running and you
      * want a working `Topic`. A fresh driver and client are created before `v` runs and closed
      * when `v` finishes, whether it completes normally, fails, or is cancelled. Concurrent
      * `Topic.run` calls are isolated and do not interfere with one another.
      *
      * To run against a driver you manage yourself, use the `run(aeronDir)` or `run(client)`
      * overloads instead.
      *
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`. Embedded-startup failures (the driver working
      *   directory cannot be allocated, or the embedded media driver fails to launch, e.g. a
      *   missing JVM `--add-opens`) are environment defects, not per-call recoverable conditions,
      *   so they surface as a panic; the external overloads, whose connect failure IS a per-call
      *   recoverable condition, abort [[TopicTransportFailedException]] instead.
      */
    def run[A, S](v: A < (Topic & S))(using Frame): A < (Async & S) =
        Abort.run[FileFsException](Path.tempDir("kyo-aeron-embedded")).map {
            case Result.Success(dir) =>
                // embedded(dir) returns AeronRuntime < Async (the @Ffi.blocking .safe.get bridge);
                // map over it to bracket v with teardown (the Sync.ensure closes the runtime, then
                // dir.removeAll deletes the temp dir).
                AeronPlatform.embedded(dir.unsafe.show).map { runtime =>
                    // Teardown order: close the Aeron client and driver first (the driver writes
                    // to files in dir until its conductor threads are joined), then delete the dir.
                    // Two nested Sync.ensure finalizers so the temp dir is deleted even when the
                    // runtime close fails: the inner finalizer closes the runtime after v, the outer
                    // finalizer runs dir.removeAll afterwards regardless of the inner outcome.
                    // Abort.run[FileFsException](dir.removeAll) discharges removeAll's effect row so
                    // the finalizer stays Unit < Sync without widening the outer row.
                    Sync.ensure(Abort.run[FileFsException](dir.removeAll).unit) {
                        Sync.ensure(Sync.Unsafe.defer(runtime.close())) {
                            runWith(runtime.transport)(v)
                        }
                    }
                }
            case Result.Failure(e) =>
                // Path.tempDir allocation failed: surface as a Panic (defect) so Topic.run
                // stays exception-free in the pure .map continuation. Abort.panic is
                // subsumed by the outer A < (Async & S) row; no effect-row widening.
                // FileFsException IS a Throwable, so Abort.panic(e) is well-typed.
                Abort.panic(e)
            case Result.Panic(t) =>
                Abort.panic(t)
        }

    /** Runs `v` against an Aeron media driver already running at `aeronDir`.
      *
      * Reach for this when a driver is managed outside the program (a standalone or shared driver).
      * A fresh client connects to the driver at `aeronDir`, runs `v`, and is closed when `v` finishes;
      * the driver itself is neither started nor stopped, so its lifecycle remains the caller's.
      *
      * Connecting to a directory where no driver is running aborts with [[TopicTransportFailedException]] at
      * the connect, before any publish or stream runs.
      *
      * @param aeronDir
      *   the directory of the externally-running media driver
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`, aborting [[TopicTransportFailedException]] on a failed connect
      */
    def run[A, S](aeronDir: Path)(v: A < (Topic & S))(using Frame): A < (Async & Abort[TopicTransportFailedException] & S) =
        // The shared eager-typed external connect: a failed connect aborts TopicTransportFailedException
        // HERE, before any publish/stream runs. aeronDir.unsafe.show renders the kyo Path to a
        // filesystem string (a pure accessor, no AllowUnsafe). On success, bracket the client
        // teardown around v via runWith.
        AeronPlatform.external(aeronDir.unsafe.show).map { runtime =>
            Sync.ensure(Sync.Unsafe.defer(runtime.close())) {
                runWith(runtime.transport)(v)
            }
        }

    /** Runs `v` against a caller-owned [[AeronClient]] (from [[AeronClient.connect]]).
      *
      * Runs `v` against the shared client; does NOT close the client (its lifetime is owned
      * by the `Scope` that produced it via [[AeronClient.connect]]). Multiple `run(client)`
      * scopes may share one [[AeronClient]], so the connect cost is paid once rather than per scope.
      *
      * @param client
      *   a connected, Scope-managed client
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`
      */
    def run[A, S](client: AeronClient)(v: A < (Topic & S))(using Frame): A < (Async & S) =
        // Reads client.unsafe.transport (private[kyo], readable here because Topic is in package kyo).
        // No Sync.ensure teardown: the client is caller-owned and closed by its own Scope.
        runWith(client.unsafe.transport)(v)

    // Supplies a pre-built AeronTransport to the Topic environment; the single funnel
    // for all three run overloads.
    private[kyo] def runWith[A, S](transport: AeronTransport)(v: A < (Topic & S))(using Frame): A < (Async & S) =
        Env.run(transport)(v)

    /** Publishes a stream of messages to a specified Aeron URI.
      *
      * Messages are published with automatic handling of backpressure and connection
      * issues. Each batch is type-tagged on the wire, so a subscriber of a different type
      * rejects it.
      *
      * Transient conditions (back-pressure, not-connected, admin action) are retried per
      * `retrySchedule`; on exhaustion they escape as [[TopicBackpressureExhaustedException]].
      * Terminal offer conditions abort with a [[TopicPublishException]] leaf; terminal
      * add/transport conditions abort with a [[TopicTransportException]] leaf.
      *
      * @param aeronUri
      *   The Aeron URI to publish to (e.g. `"aeron:ipc"`, `"aeron:udp?endpoint=..."`)
      * @param retrySchedule
      *   Schedule for retrying transient backpressure / not-connected conditions; defaults to
      *   [[defaultRetrySchedule]]
      * @param source
      *   The stream of messages to publish
      * @tparam A
      *   The message type (must have a `Schema`)
      * @tparam S
      *   Additional effects in the computation
      * @return
      *   Unit within `Topic`, with potential [[TopicBackpressureException]], [[TopicPublishException]],
      *   or [[TopicTransportException]] aborts
      * @see [[Topic.stream]] to subscribe to the published messages
      */
    def publish[A: Schema](
        aeronUri: String,
        retrySchedule: Schedule = defaultRetrySchedule
    )[S](source: Stream[A, S])(using
        frame: Frame,
        tag: Tag[A],
        etag: Tag[Emit[Chunk[A]]]
    ): Unit < (Topic & S & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException] & Async) =
        Env.use[AeronTransport] { transport =>
            val streamId = tag.hash.abs
            addPublicationDeadline(transport, aeronUri, streamId, defaultAddTimeout).map {
                case Absent =>
                    Abort.fail(TopicPublicationClosedException(aeronUri, streamId))
                case Present(publication) =>
                    val backpressured = Abort.fail(TopicBackpressureExhaustedException(aeronUri, streamId))
                    Sync.ensure(Sync.Unsafe.defer(transport.closePublication(publication))) {
                        source.foreachChunk { messages =>
                            // Encode once per chunk (pure, deterministic): the bytes are reused across every
                            // backpressure retry rather than re-encoded each attempt.
                            val bytes = MsgPack.encode(Envelope(tag.show, messages)).toArray
                            Retry[TopicBackpressureException](retrySchedule) {
                                Sync.Unsafe.defer {
                                    // Check for a recorded fatal transport error before each offer attempt.
                                    // A fatal error is set by the non-exiting error handler
                                    // (C: kyo_aeron_error_handler; JVM: Aeron.Context.errorHandler) when
                                    // the Aeron conductor reports an unrecoverable condition. The check is
                                    // a single volatile/atomic read per iteration; cost is acceptable.
                                    transport.fatalError match
                                        case Present(detail) =>
                                            Abort.fail(TopicTransportFailedException(detail))
                                        case Absent =>
                                            if !transport.publicationIsConnected(publication) then backpressured
                                            else
                                                val maxLen = transport.maxMessageLength(publication)
                                                if bytes.length > maxLen then
                                                    Abort.fail(TopicMessageTooLargeException(bytes.length, maxLen))
                                                else
                                                    mapOfferResult(
                                                        transport.offer(publication, bytes),
                                                        aeronUri,
                                                        streamId,
                                                        backpressured,
                                                        bytes.length,
                                                        maxLen
                                                    )
                                                end if
                                }
                            }
                        }
                    }
            }
        }

    /** Creates a stream of messages from a specified Aeron URI.
      *
      * Subscribes to messages with automatic handling of backpressure and connection
      * issues. Each subscription is consumed by a single reader; to fan out, subscribe more
      * than once. A received message whose on-wire type does not match `A`, or a payload that
      * fails to decode, is a defect and surfaces as a panic, not a recoverable abort.
      *
      * @param aeronUri
      *   The Aeron URI to subscribe to
      * @param retrySchedule
      *   Schedule for retrying when no message is available or the subscriber is not connected;
      *   defaults to [[defaultRetrySchedule]]
      * @tparam A
      *   The message type (must have a `Schema`)
      * @return
      *   A stream of messages within `Topic` with potential [[TopicBackpressureException]] or
      *   [[TopicTransportException]] aborts
      * @see [[Topic.publish]] to publish messages onto the subscribed URI
      */
    def stream[A: Schema](
        aeronUri: String,
        retrySchedule: Schedule = defaultRetrySchedule
    )(using
        frame: Frame,
        tag: Tag[A],
        etag: Tag[Emit[Chunk[A]]]
    ): Stream[A, Topic & Abort[TopicBackpressureException | TopicTransportException] & Async] =
        Stream {
            Env.use[AeronTransport] { transport =>
                val streamId = tag.hash.abs
                addSubscriptionDeadline(transport, aeronUri, streamId, defaultAddTimeout).map {
                    case Absent =>
                        // Client was closed concurrently (or JVM closed-client AeronException):
                        // no subscription can be established. Surface as TopicBackpressureExhaustedException so
                        // the retry schedule can exhaust cleanly (transient closed-client condition).
                        // A driver registration rejection aborts terminally with TopicRegistrationFailedException
                        // inside addSubscriptionDeadline before this arm is reached.
                        Abort.fail(TopicBackpressureExhaustedException(aeronUri, streamId))
                    case Present(subscription) =>
                        val backpressured = Abort.fail(TopicBackpressureExhaustedException(aeronUri, streamId))
                        Sync.ensure(Sync.Unsafe.defer(transport.closeSubscription(subscription))) {
                            def loop(): Unit < (Emit[Chunk[A]] & Async & Abort[TopicBackpressureException | TopicTransportException]) =
                                Retry[TopicBackpressureException](retrySchedule) {
                                    Sync.Unsafe.defer {
                                        // Check for a recorded fatal transport error before each
                                        // poll attempt. Same slot/handler as the publish path.
                                        transport.fatalError match
                                            case Present(detail) =>
                                                Abort.fail(TopicTransportFailedException(detail))
                                            case Absent =>
                                                if !transport.subscriptionIsConnected(subscription) then backpressured
                                                else
                                                    transport.pollOne(subscription) match
                                                        case Absent =>
                                                            backpressured
                                                        case Present(bytes) =>
                                                            MsgPack.decode[Envelope[A]](Span.from(bytes)) match
                                                                case Result.Failure(error) =>
                                                                    Abort.panic(error)
                                                                case Result.Panic(t) =>
                                                                    // MsgPack.decode is Result.catching[DecodeException], so any
                                                                    // non-DecodeException throwable arrives as Panic. Surface it as
                                                                    // the original defect rather than letting the match fall through
                                                                    // to a MatchError that would mask it.
                                                                    Abort.panic(t)
                                                                case Result.Success(envelope) =>
                                                                    if envelope.typeTag != tag.show then
                                                                        Abort.panic(
                                                                            new IllegalStateException(
                                                                                s"Expected messages of type ${tag.show} but got ${envelope.typeTag}"
                                                                            )
                                                                        )
                                                                    else
                                                                        Emit.valueWith(envelope.messages)(loop())
                                                                    end if
                                    }
                                }
                            end loop
                            loop()
                        }
                }
            }
        }
    end stream

    /** Bounded async add loop for publications.
      *
      * Starts an async publication registration, then polls in a cooperative Async loop
      * with `Async.sleep(addBackoff)` between steps, bounded by a deadline. On expiry,
      * aborts with `TopicAddTimeoutException`. On driver rejection (`Failed` with a non-zero
      * errorCode or non-empty detail), aborts with `TopicRegistrationFailedException`. On
      * closed-client (`asyncAddPublication` returns `Absent`, or `Failed(0, "")` from
      * the JVM closed-client AeronException), returns `Absent` so the caller can map it
      * to `TopicPublicationClosedException`.
      *
      * The `Sync.ensure` handler frees the async token if the enclosing Fiber is
      * cancelled while the loop is in the `Awaiting` state, preventing a token leak.
      */
    private[kyo] def addPublicationDeadline(
        transport: AeronTransport,
        aeronUri: String,
        streamId: Int,
        timeout: Duration
    )(using Frame): Maybe[transport.Publication] < (Async & Abort[TopicTransportException]) =
        type Pub = transport.Publication
        Clock.use { clock =>
            clock.deadline(timeout).map { dl =>
                Sync.Unsafe.defer(transport.asyncAddPublication(aeronUri, streamId)).map {
                    case Absent =>
                        // Closed client: return Absent so caller maps to TopicPublicationClosedException.
                        (Absent: Maybe[Pub])
                    case Present(tok) =>
                        // tokOwned tracks whether the token still needs cleanup.
                        // pollAddPublication calls _get (which frees the token) on Done.
                        // On _poll < 0 (Failed) the token is NOT freed by the C layer;
                        // the Failed arm below calls freeAsyncPub explicitly and sets
                        // tokOwned=false so the Sync.ensure finalizer does not double-free.
                        // A local var is safe here because addPublicationDeadline runs in one fiber.
                        var tokOwned = true
                        Sync.ensure(Sync.Unsafe.defer(if tokOwned then transport.freeAsyncPub(tok) else ())) {
                            Loop.foreach[Maybe[Pub], Async & Abort[TopicTransportException]] {
                                Sync.Unsafe.defer(transport.pollAddPublication(tok)).map {
                                    poll =>
                                        (poll: AeronTransport.AddPoll[Pub]) match
                                            case AeronTransport.AddPoll.Done(pub) =>
                                                // _get freed the token internally.
                                                tokOwned = false
                                                Loop.done[Unit, Maybe[Pub]](Maybe(pub))
                                            case AeronTransport.AddPoll.Failed(code, detail)
                                                if code != 0 || detail.nonEmpty =>
                                                // Driver rejected the registration (non-zero errorCode or
                                                // non-empty detail). Token still alive after _poll < 0;
                                                // free it now, then abort terminally.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncPub(tok)
                                                    tokOwned = false
                                                }.andThen(Abort.fail(TopicRegistrationFailedException(aeronUri, streamId, code, detail)))
                                            case AeronTransport.AddPoll.Failed(_, _) =>
                                                // Failed(0, ""): closed-client (JVM AeronException) or an FFI _get
                                                // alloc failure. Token still alive; free it once, then return Absent
                                                // so the caller maps it to TopicPublicationClosedException.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncPub(tok)
                                                    tokOwned = false
                                                }.andThen(Loop.done[Unit, Maybe[Pub]](Absent))
                                            case _ => // AeronTransport.AddPoll.Awaiting
                                                dl.isOverdue.map { over =>
                                                    if over then Abort.fail(TopicAddTimeoutException(aeronUri, streamId, timeout))
                                                    else Async.sleep(addBackoff).andThen(Loop.continue)
                                                }
                                }
                            }
                        }
                }
            }
        }
    end addPublicationDeadline

    /** Bounded async add loop for subscriptions (symmetric to addPublicationDeadline).
      *
      * On driver rejection (`Failed` with a non-zero errorCode or non-empty detail),
      * aborts terminally with `TopicRegistrationFailedException`. On closed-client (`Absent`
      * or `Failed(0, "")`), returns `Absent` so the caller can route to a transient
      * signal (stream retries on closed-client, publish maps to TopicPublicationClosedException).
      */
    private[kyo] def addSubscriptionDeadline(
        transport: AeronTransport,
        aeronUri: String,
        streamId: Int,
        timeout: Duration
    )(using Frame): Maybe[transport.Subscription] < (Async & Abort[TopicTransportException]) =
        type Sub = transport.Subscription
        Clock.use { clock =>
            clock.deadline(timeout).map { dl =>
                Sync.Unsafe.defer(transport.asyncAddSubscription(aeronUri, streamId)).map {
                    case Absent =>
                        (Absent: Maybe[Sub])
                    case Present(tok) =>
                        var tokOwned = true
                        Sync.ensure(Sync.Unsafe.defer(if tokOwned then transport.freeAsyncSub(tok) else ())) {
                            Loop.foreach[Maybe[Sub], Async & Abort[TopicTransportException]] {
                                Sync.Unsafe.defer(transport.pollAddSubscription(tok)).map {
                                    poll =>
                                        (poll: AeronTransport.AddPoll[Sub]) match
                                            case AeronTransport.AddPoll.Done(sub) =>
                                                tokOwned = false
                                                Loop.done[Unit, Maybe[Sub]](Maybe(sub))
                                            case AeronTransport.AddPoll.Failed(code, detail)
                                                if code != 0 || detail.nonEmpty =>
                                                // Driver rejected the registration: abort terminally.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncSub(tok)
                                                    tokOwned = false
                                                }.andThen(Abort.fail(TopicRegistrationFailedException(aeronUri, streamId, code, detail)))
                                            case AeronTransport.AddPoll.Failed(_, _) =>
                                                // Failed(0, ""): closed-client (JVM AeronException) or an FFI _get
                                                // alloc failure. Token still alive; free it once, then return Absent.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncSub(tok)
                                                    tokOwned = false
                                                }.andThen(Loop.done[Unit, Maybe[Sub]](Absent))
                                            case _ => // AeronTransport.AddPoll.Awaiting
                                                dl.isOverdue.map { over =>
                                                    if over then Abort.fail(TopicAddTimeoutException(aeronUri, streamId, timeout))
                                                    else Async.sleep(addBackoff).andThen(Loop.continue)
                                                }
                                }
                            }
                        }
                }
            }
        }
    end addSubscriptionDeadline

    /** Maps an Aeron offer return value to a typed Kyo outcome.
      *
      * All offer-sentinel-to-exception wiring lives here so every sentinel maps to an
      * explicit typed outcome. No `case _` arm is present; a runtime value not matching
      * any arm produces a `MatchError` (a defect, not a transport failure, since Aeron's
      * contract fixes the return-value set).
      *
      * Retryable (routed to `transientSignal`, absorbed by the `Retry[TopicBackpressureException]`
      * caller; surfaces as `TopicBackpressureExhaustedException` on schedule exhaustion):
      *   - BACK_PRESSURED (-2): subscriber queue full, retry is the standard Aeron flow.
      *   - NOT_CONNECTED (-1): no active subscriber yet; same condition handled by the
      *     not-connected pre-check, so both paths route consistently to `transientSignal`:
      *     the same physical state yields one outcome regardless of when the connectivity
      *     transition occurs.
      *   - ADMIN_ACTION (-3): log rotation or term-count race; Aeron documents this as
      *     retryable, so it is not mapped to a terminal failure.
      *
      * Terminal (abort with a typed `TopicPublishException` leaf):
      *   - CLOSED (-4): the publication or client was closed; maps to `TopicPublicationClosedException`.
      *   - MAX_POSITION_EXCEEDED (-5): stream position limit reached; maps to
      *     `TopicMaxPositionExceededException`.
      *   - ERROR (-6): oversize message (normalized from IAE on JVM); maps to
      *     `TopicMessageTooLargeException` with the actual size and configured maximum.
      */
    private[kyo] def mapOfferResult(
        result: Long,
        aeronUri: String,
        streamId: Int,
        transientSignal: Unit < (Async & Abort[TopicBackpressureException]),
        messageSize: Int,
        maxLen: Int
    )(using Frame): Unit < (Async & Abort[TopicBackpressureException | TopicPublishException]) =
        if result > 0 then ()
        else
            result match
                case AeronSentinels.BackPressured       => transientSignal
                case AeronSentinels.NotConnected        => transientSignal // -1 retryable, matches the not-connected pre-check
                case AeronSentinels.AdminAction         => transientSignal // -3 retryable (log-rotation / term-count race)
                case AeronSentinels.Closed              => Abort.fail(TopicPublicationClosedException(aeronUri, streamId))
                case AeronSentinels.MaxPositionExceeded => Abort.fail(TopicMaxPositionExceededException(aeronUri, streamId))
                case AeronSentinels.Error               => Abort.fail(TopicMessageTooLargeException(messageSize, maxLen))

    /** Lets `Topic`-effectful computations run inside `Async.race`, `Async.parallel`, and forked
      * fibers. You rarely reference this directly; it is resolved implicitly when those combinators
      * run a `Topic` computation. `Topic` carries no per-fiber state, so every isolated branch shares
      * the same transport unchanged.
      */
    given isolate: Isolate[Topic, Any, Any] = Isolate.derive[Env[AeronTransport], Any, Any]

end Topic
