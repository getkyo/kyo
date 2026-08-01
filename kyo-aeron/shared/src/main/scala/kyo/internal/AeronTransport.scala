package kyo.internal

import kyo.*

/** Platform-neutral Aeron transport capability backing the public `Topic`.
  *
  * Every method is unsafe-tier (trailing `using AllowUnsafe`, plain values, no effect row);
  * `Topic` bridges them to its `< (... & Async)` rows via `Sync.Unsafe.defer` and `Sync.ensure`.
  * The JVM impl wraps `io.aeron.*`; Native and JS wrap the Aeron C client through `AeronBindings`.
  * The handle types are abstract members each impl fixes, so neither `io.aeron.Publication` nor
  * `Ffi.Handle` leaks here.
  *
  * `offer` returns the raw Aeron position (>0) or a negative sentinel; the sentinel-to-error
  * mapping lives once in shared `Topic` (`AeronSentinels`), never here, so the error policy is
  * identical across platforms. `pollOne` returns the reassembled message bytes (`Absent` = zero
  * fragments); fragment reassembly stays inside the impl (JVM `FragmentAssembler`, FFI C shim).
  *
  * The add path is split into a non-blocking start plus a poll loop, keeping fibers interruptible
  * and carriers free. `asyncAdd*` starts the registration and returns an opaque token; `pollAdd*`
  * performs one step returning `AddPoll`. The token stays owned by the caller until `Done` or
  * `freeAsync*`. `Topic.addPublicationDeadline` / `addSubscriptionDeadline` drive the loop with
  * `Async.sleep(addBackoff)` bounded by a deadline, surfacing `TopicAddTimeoutException` on expiry.
  *
  * `asyncAdd*` returns `Maybe` so a closed client surfaces as `Absent` rather than an exception or
  * a use-after-free: the FFI impl detects it via the C shim's live-bundle registry (closed client
  * returns NULL), the JVM impl maps the `AeronException` thrown on a closed client. `Topic.publish`
  * maps `Absent` to `TopicPublicationClosedException`; `Topic.stream` maps it to
  * `TopicBackpressureExhaustedException` (transient, retried per the retry schedule).
  */
abstract private[kyo] class AeronTransport:
    type Publication
    type Subscription
    type AsyncPub
    type AsyncSub

    def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub]
    def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication]
    def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit

    def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean
    def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long
    def maxMessageLength(pub: Publication)(using AllowUnsafe): Int
    def closePublication(pub: Publication)(using AllowUnsafe): Unit

    def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub]
    def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription]
    def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit

    def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean
    def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]]
    def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit

    /** Returns a fatal transport error recorded since this client connected, `Absent` otherwise.
      *
      * The Aeron conductor calls the installed recording error handler on fatal conditions (driver
      * timeout, conductor service timeout, buffer full), which writes a slot (C:
      * kyo_aeron_error_slot under pthread_mutex; JVM: AtomicRef.Unsafe). `Topic` reads that slot
      * here at the offer/poll boundary on every transport operation and aborts with
      * TopicTransportFailedException when present.
      */
    def fatalError(using AllowUnsafe): Maybe[String]

    /** Whether the Aeron client backing this transport has been closed.
      *
      * Once true it never reverts: the client is gone for the process's lifetime, so no operation
      * against it can ever succeed again. `Topic.publish` and `Topic.stream` read this before their
      * retry decision and abort terminally, because every other post-close signal is
      * indistinguishable from a transient one: a closed client reports `isConnected == false`, which
      * is also how "no peer has attached yet" is reported, and `pollOne` reports `Absent`, which is
      * also how "no message this poll" is reported. Without this a fiber still holding a handle when
      * the client closes retries its backoff schedule forever instead of failing.
      *
      * The no-op default serves fakes whose client never closes, as `injectError` does.
      */
    def clientClosed(using AllowUnsafe): Boolean = false

    /** Injects a synthetic fatal error into the transport error slot, exercising the
      * TopicTransportFailedException path without a real conductor condition.
      *
      * The no-op default serves the NeverConfirmTransport test fixture; production transports
      * override it.
      */
    def injectError(errcode: Int, errmsg: String)(using AllowUnsafe): Unit = ()
end AeronTransport

object AeronTransport:
    /** Result of a single async-add poll step.
      *
      * `Awaiting` means the caller should suspend and poll again. `Failed` carries the driver's
      * rejection, filled from `aeron_errcode()` / `aeron_errmsg()` on the FFI path and from
      * `RegistrationException.errorCodeValue()` / `.getMessage()` on the JVM path, so
      * `TopicRegistrationFailedException` gets driver detail either way.
      */
    enum AddPoll[+H]:
        case Done(handle: H)
        case Awaiting
        case Failed(errorCode: Int, detail: String)
    end AddPoll
end AeronTransport
