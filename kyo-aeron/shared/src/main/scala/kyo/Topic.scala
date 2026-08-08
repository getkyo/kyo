package kyo

import kyo.internal.AeronPlatform
import kyo.internal.AeronSentinels
import kyo.internal.AeronTransport

/** Typed publish-subscribe messaging for local and distributed systems.
  *
  * `Topic` carries typed messages over the Aeron transport: low-latency inter-process
  * communication (IPC) on the same machine through shared memory, and UDP to remote services.
  *
  * Each message type needs a `Schema[A]` instance; the transport layer handles message
  * fragmentation and flow control. Publish a stream of messages with [[Topic.publish]];
  * subscribe to typed messages with [[Topic.stream]].
  *
  * The message type's `Tag` selects a distinct Aeron stream and is carried in every message, so a
  * subscriber only receives messages published under its exact type. A stream of a parent type
  * does not receive messages published as a subtype.
  *
  * @see [[Topic.run]] to handle `Topic` with an embedded or external driver
  * @see [[AeronDriver.Settings]] to tune the embedded driver's liveness timeouts via `run(settings)`
  * @see [[Topic.publish]] to publish a stream of messages
  * @see [[Topic.stream]] to subscribe to a stream of messages
  * @see [[TopicException]] for the failures publish and stream can abort with
  * @see [[https://aeron.io/]] for documentation on Aeron URIs.
  */
opaque type Topic <: Env[AeronTransport] = Env[AeronTransport]

object Topic:

    private[kyo] case class Envelope[A](typeTag: String, messages: Chunk[A]) derives Schema

    /** Default retry schedule for [[Topic.publish]] and [[Topic.stream]]: linear 10ms backoff, capped
      * at 1s, with 20% jitter. Transient backpressure, not-connected, and no-data conditions retry on
      * this schedule, so a momentarily quiet or slow channel does not fail; a failure surfaces as
      * [[TopicBackpressureExhaustedException]] only once the schedule exhausts.
      */
    val defaultRetrySchedule: Schedule = Schedule.linear(10.millis).min(Schedule.fixed(1.second)).jitter(0.2)

    /** Backoff between async-add poll steps. */
    private val addBackoff: Duration = 1.milli

    /** Default timeout for the add-publication / add-subscription deadline loop. */
    private val defaultAddTimeout: Duration = 10.seconds

    /** Runs `v` with an embedded Aeron media driver that this call starts and owns.
      *
      * The zero-config entry point. A fresh driver and client are created before `v` runs and closed
      * when `v` finishes, normally or not. Concurrent `Topic.run` calls are isolated from one another.
      * To widen or shrink the embedded driver's timeouts, take the `run(settings)` overload; to use a
      * driver you manage yourself, take the `run(aeronDir)` or `run(client)` overload.
      *
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`. Embedded-startup failures (the working directory
      *   cannot be allocated, or the media driver fails to launch, e.g. a missing JVM `--add-opens`)
      *   are environment defects rather than per-call recoverable conditions, so they surface as a
      *   panic. The external overloads, whose connect failure is per-call recoverable, abort
      *   [[TopicTransportFailedException]] instead.
      */
    def run[A, S](v: A < (Topic & S))(using Frame): A < (Async & S) =
        val (livenessNs, unblockNs) = embeddedDriverTimeouts(AeronDriver.Settings.embedded)
        runEmbedded(livenessNs, unblockNs)(v)

    /** Runs `v` with an embedded driver whose liveness and publication-unblock timeouts come from
      * `settings` instead of the [[AeronDriver.Settings.embedded]] defaults the no-arg [[run]] applies.
      *
      * Reach for this on a host where the default embedded timeouts do not fit: a driver conductor
      * starved past the liveness window is read as client death, so a slower or busier host may need a
      * wider one. Every other embedded behaviour is identical to `run(v)`.
      *
      * @param settings
      *   embedded-driver timeouts; `publicationUnblockTimeout` must exceed `clientLivenessTimeout`
      *   when both are set (non-Zero), else the call aborts [[TopicTransportFailedException]] before
      *   any driver starts. [[Duration.Zero]] keeps Aeron's own default for that timeout.
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`, aborting [[TopicTransportFailedException]] only on
      *   inconsistent `settings`; embedded-startup defects still surface as a panic, as in `run(v)`.
      */
    def run[A, S](settings: AeronDriver.Settings)(v: A < (Topic & S))(using
        Frame
    ): A < (Async & Abort[TopicTransportFailedException] & S) =
        if settings.publicationUnblockTimeout != Duration.Zero &&
            settings.publicationUnblockTimeout <= settings.clientLivenessTimeout
        then
            Abort.fail(TopicTransportFailedException(
                s"publicationUnblockTimeout (${settings.publicationUnblockTimeout}) must exceed " +
                    s"clientLivenessTimeout (${settings.clientLivenessTimeout})"
            ))
        else
            val (livenessNs, unblockNs) = embeddedDriverTimeouts(settings)
            runEmbedded(livenessNs, unblockNs)(v)
        end if
    end run

    /** The driver-start nanos the embedded [[run]] overloads derive from `settings`, split out so the
      * mapping is unit-testable without launching a driver.
      */
    private[kyo] def embeddedDriverTimeouts(settings: AeronDriver.Settings): (Long, Long) =
        (settings.clientLivenessTimeout.toNanos, settings.publicationUnblockTimeout.toNanos)

    /** Shared embedded-driver body for both `run(v)` and `run(settings)`: allocate a private
      * directory, start a driver and client with the given timeouts, run `v`, then tear both down.
      */
    private def runEmbedded[A, S](clientLivenessNs: Long, publicationUnblockNs: Long)(
        v: A < (Topic & S)
    )(using Frame): A < (Async & S) =
        Scope.run {
            Abort.run[FileSystemException](Path.run(Path.tempDir("kyo-aeron-embedded"))).map {
                case Result.Success(dir) =>
                    AeronPlatform.embedded(dir.unsafe.show, clientLivenessNs, publicationUnblockNs).map { runtime =>
                        // The driver deletes its own directory on close, once its conductor has
                        // stopped. The scope that owns the temp dir is the backstop for the paths
                        // that leaves behind: a driver that failed to launch, or one whose close did
                        // not complete. Path.tempDir registers a recursive removal that discards its
                        // outcome, so the backstop finding nothing is not an error and cannot replace
                        // the result of the body it guards.
                        //
                        // Closing the runtime from inside the scope is what orders the two: the
                        // client and driver are shut down on the way out, and only then does the
                        // scope remove the directory underneath them.
                        Sync.ensure(Sync.Unsafe.defer(runtime.close())) {
                            runWith(runtime.transport)(v)
                        }
                    }
                case Result.Failure(e) =>
                    Abort.panic(e)
                case Result.Panic(t) =>
                    Abort.panic(t)
            }
        }

    /** Runs `v` against an Aeron media driver already running at `aeronDir`.
      *
      * A fresh client connects, runs `v`, and closes when `v` finishes. The driver is neither started
      * nor stopped, so its lifecycle remains the caller's. Connecting to a directory where no driver
      * is running aborts [[TopicTransportFailedException]] before any publish or stream runs.
      *
      * @param aeronDir
      *   the directory of the externally-running media driver
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`, aborting [[TopicTransportFailedException]] on a failed connect
      */
    def run[A, S](aeronDir: Path)(v: A < (Topic & S))(using Frame): A < (Async & Abort[TopicTransportFailedException] & S) =
        AeronPlatform.external(aeronDir.unsafe.show).map { runtime =>
            Sync.ensure(Sync.Unsafe.defer(runtime.close())) {
                runWith(runtime.transport)(v)
            }
        }

    /** Runs `v` against a caller-owned [[AeronClient]] (from [[AeronClient.connect]]).
      *
      * Does NOT close the client: its lifetime belongs to the `Scope` that produced it. Several
      * `run(client)` scopes may share one client, paying the connect cost once rather than per scope.
      *
      * @param client
      *   a connected, Scope-managed client
      * @param v
      *   the computation requiring `Topic` capabilities
      * @return
      *   the computation result within `Async`
      */
    def run[A, S](client: AeronClient)(v: A < (Topic & S))(using Frame): A < (Async & S) =
        runWith(client.unsafe.transport)(v)

    /** Supplies a pre-built transport to the `Topic` environment; the single funnel for all three
      * `run` overloads.
      */
    private[kyo] def runWith[A, S](transport: AeronTransport)(v: A < (Topic & S))(using Frame): A < (Async & S) =
        Env.run(transport)(v)

    /** Publishes a stream of messages to a specified Aeron URI.
      *
      * Each batch is type-tagged on the wire, so a subscriber of a different type rejects it.
      *
      * Transient conditions (back-pressure, not-connected, admin action) are retried per
      * `retrySchedule` and escape as [[TopicBackpressureExhaustedException]] on exhaustion. Terminal
      * offer conditions abort with a [[TopicPublishException]] leaf; terminal add and transport
      * conditions abort with a [[TopicTransportException]] leaf.
      *
      * Ordering is per call: subscribers receive the messages of one `source` in the order the stream
      * emits them. Each call publishes over its own Aeron publication, and Aeron orders messages
      * within a publication but not across them, so two separate `publish` calls have no defined
      * relative order at the subscriber even when the calls are sequenced. Publish one stream when
      * the order of several messages matters, rather than calling `publish` once per message.
      *
      * @param aeronUri
      *   The Aeron URI to publish to (e.g. `"aeron:ipc"`, `"aeron:udp?endpoint=..."`)
      * @param retrySchedule
      *   Schedule for retrying transient backpressure / not-connected conditions; defaults to
      *   [[defaultRetrySchedule]]
      * @param streamId
      *   The Aeron stream id to publish on; defaults to the message type's content-stable
      *   `tag.hash.abs`, so a publish and a subscribe of the same type land on the same stream.
      *   Pass an explicit id to run several independent streams of one type over one URI.
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
    def publish[A](
        aeronUri: String,
        retrySchedule: Schedule = defaultRetrySchedule,
        streamId: Maybe[Int] = Absent
    ): PublishTo[A] =
        new PublishTo[A](aeronUri, retrySchedule, streamId)

    /** The destination and options from [[Topic.publish]], awaiting the stream to publish.
      *
      * `publish` hands this back rather than taking the source in a following parameter list so that
      * its arguments are fully applied before `S` is inferred. Scala 3.8.4 fails an internal
      * assertion when it applies inferred type arguments to the block that argument desugaring
      * produces, which a defaulted or reordered argument before a type-parameter clause creates
      * (`Typer.adapt1` calling `appliedToTypeTrees`, which asserts its function is not a block).
      * Taking `S` here keeps every call shape compiling, including `streamId = id` and arguments
      * named out of order.
      *
      * `Schema[A]` is required here rather than on `publish` so that `A` can still be inferred from
      * `source`: bounding it on `publish` would force the instance to resolve before `A` is known.
      */
    final class PublishTo[A] private[kyo] (
        aeronUri: String,
        retrySchedule: Schedule,
        streamId: Maybe[Int]
    ):
        def apply[S](source: Stream[A, S])(using
            schema: Schema[A],
            frame: Frame,
            tag: Tag[A],
            etag: Tag[Emit[Chunk[A]]]
        ): Unit < (Topic & S & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException] & Async) =
            Env.use[AeronTransport] { transport =>
                val resolvedStreamId = streamId.getOrElse(tag.hash.abs)
                addPublicationDeadline(transport, aeronUri, resolvedStreamId, defaultAddTimeout).map {
                    case Absent =>
                        Abort.fail(TopicPublicationClosedException(aeronUri, resolvedStreamId))
                    case Present(publication) =>
                        val backpressured = Abort.fail(TopicBackpressureExhaustedException(aeronUri, resolvedStreamId))
                        Sync.ensure(Sync.Unsafe.defer(transport.closePublication(publication))) {
                            source.foreachChunk { messages =>
                                // Encoded outside the retry so the bytes are reused across every attempt.
                                val bytes = MsgPack.encode(Envelope(tag.show, messages)).toArray
                                Retry[TopicBackpressureException](retrySchedule) {
                                    Sync.Unsafe.defer {
                                        // fatalError is set by the non-exiting conductor error handler
                                        // (C: kyo_aeron_error_handler; JVM: Aeron.Context.errorHandler).
                                        transport.fatalError match
                                            case Present(detail) =>
                                                Abort.fail(TopicTransportFailedException(detail))
                                            case Absent if transport.clientClosed =>
                                                // Terminal: a closed client reports the same
                                                // "not connected" as a publication still waiting for
                                                // a subscriber, so without this the retry below would
                                                // never stop on a client that can never come back.
                                                Abort.fail(TopicPublicationClosedException(aeronUri, resolvedStreamId))
                                            case Absent =>
                                                val maxLen = transport.maxMessageLength(publication)
                                                // Checked before connectivity: oversize is terminal regardless of
                                                // whether a subscriber is attached, so retrying could never help.
                                                // maxLen == 0 is the closed-publication sentinel on both backends,
                                                // not a real limit, so it falls through to the connectivity check.
                                                if maxLen > 0 && bytes.length > maxLen then
                                                    Abort.fail(TopicMessageTooLargeException(bytes.length, maxLen))
                                                else if !transport.publicationIsConnected(publication) then backpressured
                                                else
                                                    mapOfferResult(
                                                        transport.offer(publication, bytes),
                                                        aeronUri,
                                                        resolvedStreamId,
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
    end PublishTo

    /** Creates a stream of messages from a specified Aeron URI.
      *
      * Each subscription is consumed by a single reader; to fan out, subscribe more than once. A
      * received message whose on-wire type does not match `A`, or a payload that fails to decode,
      * is a defect and surfaces as a panic, not a recoverable abort.
      *
      * @param aeronUri
      *   The Aeron URI to subscribe to
      * @param retrySchedule
      *   Schedule for retrying when no message is available or the subscriber is not connected;
      *   defaults to [[defaultRetrySchedule]]
      * @param streamId
      *   The Aeron stream id to subscribe to; defaults to the message type's content-stable
      *   `tag.hash.abs`, matching [[Topic.publish]]'s default. Pass the same explicit id the
      *   publisher used to subscribe to a specific stream.
      * @tparam A
      *   The message type (must have a `Schema`)
      * @return
      *   A stream of messages within `Topic` with potential [[TopicBackpressureException]] or
      *   [[TopicTransportException]] aborts
      * @see [[Topic.publish]] to publish messages onto the subscribed URI
      */
    def stream[A: Schema](
        aeronUri: String,
        retrySchedule: Schedule = defaultRetrySchedule,
        streamId: Maybe[Int] = Absent
    )(using
        frame: Frame,
        tag: Tag[A],
        etag: Tag[Emit[Chunk[A]]]
    ): Stream[A, Topic & Abort[TopicBackpressureException | TopicTransportException] & Async] =
        Stream {
            Env.use[AeronTransport] { transport =>
                val resolvedStreamId = streamId.getOrElse(tag.hash.abs)
                addSubscriptionDeadline(transport, aeronUri, resolvedStreamId, defaultAddTimeout).map {
                    case Absent =>
                        // Closed client: reported as backpressure so the retry schedule absorbs it. A driver
                        // rejection already aborted terminally inside addSubscriptionDeadline.
                        Abort.fail(TopicBackpressureExhaustedException(aeronUri, resolvedStreamId))
                    case Present(subscription) =>
                        val backpressured = Abort.fail(TopicBackpressureExhaustedException(aeronUri, resolvedStreamId))
                        Sync.ensure(Sync.Unsafe.defer(transport.closeSubscription(subscription))) {
                            def loop(): Unit < (Emit[Chunk[A]] & Async & Abort[TopicBackpressureException | TopicTransportException]) =
                                Retry[TopicBackpressureException](retrySchedule) {
                                    Sync.Unsafe.defer {
                                        // Same fatal-error slot as the publish path.
                                        transport.fatalError match
                                            case Present(detail) =>
                                                Abort.fail(TopicTransportFailedException(detail))
                                            case Absent if transport.clientClosed =>
                                                // Terminal, for the reason the publish path documents:
                                                // a closed client is indistinguishable from a
                                                // subscription still waiting for a publisher, and no
                                                // amount of retrying revives it.
                                                Abort.fail(TopicTransportFailedException("aeron client closed"))
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
                                                                    // MsgPack.decode catches only DecodeException, so other
                                                                    // throwables arrive here; re-panic rather than masking
                                                                    // them behind a MatchError.
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
      * Polls the registration in a cooperative `Async` loop until a deadline, sleeping `addBackoff`
      * between steps. Aborts `TopicAddTimeoutException` on expiry and `TopicRegistrationFailedException`
      * on driver rejection; returns `Absent` on a closed client so the caller maps it to
      * `TopicPublicationClosedException`. The `Sync.ensure` handler frees the async token when the
      * fiber is cancelled mid-`Awaiting`, preventing a leak.
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
                        (Absent: Maybe[Pub])
                    case Present(tok) =>
                        // Token free-ownership: on Done, pollAddPublication's _get frees the token; on
                        // Failed the C layer does not, so each Failed arm frees it and clears tokOwned
                        // to keep the finalizer from double-freeing. The var is confined to one fiber.
                        var tokOwned = true
                        Sync.ensure(Sync.Unsafe.defer(if tokOwned then transport.freeAsyncPub(tok) else ())) {
                            Loop.foreach[Maybe[Pub], Async & Abort[TopicTransportException]] {
                                Sync.Unsafe.defer(transport.pollAddPublication(tok)).map {
                                    poll =>
                                        (poll: AeronTransport.AddPoll[Pub]) match
                                            case AeronTransport.AddPoll.Done(pub) =>
                                                tokOwned = false
                                                Loop.done[Unit, Maybe[Pub]](Maybe(pub))
                                            case AeronTransport.AddPoll.Failed(code, detail)
                                                if code != 0 || detail.nonEmpty =>
                                                // Driver rejected the registration: abort terminally.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncPub(tok)
                                                    tokOwned = false
                                                }.andThen(Abort.fail(TopicRegistrationFailedException(aeronUri, streamId, code, detail)))
                                            case AeronTransport.AddPoll.Failed(_, _) =>
                                                // Failed(0, "") is the closed-client (JVM AeronException) or
                                                // FFI _get alloc-failure sentinel.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncPub(tok)
                                                    tokOwned = false
                                                }.andThen(Loop.done[Unit, Maybe[Pub]](Absent))
                                            case _ => // AeronTransport.AddPoll.Awaiting
                                                Sync.Unsafe.defer(transport.clientClosed).map { closed =>
                                                    if closed then
                                                        // The client went away mid-registration, so the
                                                        // poll can never confirm. Take the same
                                                        // closed-client exit as Failed(0, "") instead of
                                                        // polling on to the deadline: the caller's fiber
                                                        // may outlive the client (Topic.run's scope can
                                                        // exit under it), and a registration that cannot
                                                        // complete must not keep a carrier busy.
                                                        Sync.Unsafe.defer {
                                                            transport.freeAsyncPub(tok)
                                                            tokOwned = false
                                                        }.andThen(Loop.done[Unit, Maybe[Pub]](Absent))
                                                    else
                                                        dl.isOverdue.map { over =>
                                                            if over then
                                                                Abort.fail(TopicAddTimeoutException(aeronUri, streamId, timeout))
                                                            else Async.sleep(addBackoff).andThen(Loop.continue)
                                                        }
                                                }
                                }
                            }
                        }
                }
            }
        }
    end addPublicationDeadline

    /** Bounded async add loop for subscriptions, symmetric to [[addPublicationDeadline]] including its
      * token free-ownership rule. `Absent` means closed client, which `Topic.stream` routes to a
      * transient backpressure signal.
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
                                                // Failed(0, "") is the closed-client (JVM AeronException) or
                                                // FFI _get alloc-failure sentinel.
                                                Sync.Unsafe.defer {
                                                    transport.freeAsyncSub(tok)
                                                    tokOwned = false
                                                }.andThen(Loop.done[Unit, Maybe[Sub]](Absent))
                                            case _ => // AeronTransport.AddPoll.Awaiting
                                                Sync.Unsafe.defer(transport.clientClosed).map { closed =>
                                                    if closed then
                                                        // Same closed-client exit as the publication loop.
                                                        Sync.Unsafe.defer {
                                                            transport.freeAsyncSub(tok)
                                                            tokOwned = false
                                                        }.andThen(Loop.done[Unit, Maybe[Sub]](Absent))
                                                    else
                                                        dl.isOverdue.map { over =>
                                                            if over then
                                                                Abort.fail(TopicAddTimeoutException(aeronUri, streamId, timeout))
                                                            else Async.sleep(addBackoff).andThen(Loop.continue)
                                                        }
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
      * Retryable, routed to `transientSignal` and absorbed by the caller's
      * `Retry[TopicBackpressureException]`:
      *   - BACK_PRESSURED (-2): subscriber queue full.
      *   - NOT_CONNECTED (-1): no active subscriber yet. The not-connected pre-check handles the same
      *     condition, so both paths route here and one physical state yields one outcome regardless
      *     of when the connectivity transition lands.
      *   - ADMIN_ACTION (-3): log rotation or term-count race, documented by Aeron as retryable.
      *
      * Terminal:
      *   - CLOSED (-4): publication or client closed.
      *   - MAX_POSITION_EXCEEDED (-5): stream position limit reached.
      *   - ERROR (-6): oversize message (normalized from IAE on the JVM).
      *
      * There is deliberately no `case _` arm: Aeron's contract fixes the return-value set, so an
      * unmatched value is a defect rather than a transport failure.
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
                case AeronSentinels.NotConnected        => transientSignal
                case AeronSentinels.AdminAction         => transientSignal
                case AeronSentinels.Closed              => Abort.fail(TopicPublicationClosedException(aeronUri, streamId))
                case AeronSentinels.MaxPositionExceeded => Abort.fail(TopicMaxPositionExceededException(aeronUri, streamId))
                case AeronSentinels.Error               => Abort.fail(TopicMessageTooLargeException(messageSize, maxLen))

    /** Lets `Topic`-effectful computations run inside `Async.race`, `Async.parallel`, and forked
      * fibers, resolved implicitly by those combinators. `Topic` carries no per-fiber state, so every
      * isolated branch shares the same transport unchanged.
      */
    given isolate: Isolate[Topic, Any, Any] = Isolate.derive[Env[AeronTransport], Any, Any]

end Topic
