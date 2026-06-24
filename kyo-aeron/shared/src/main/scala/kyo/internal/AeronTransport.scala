package kyo.internal

import kyo.*

/** Platform-neutral Aeron transport capability backing the public `Topic`.
  *
  * The minimal op set the shared `Topic` logic needs: publication and
  * subscription lifecycle plus their non-blocking hot-path calls. Every method
  * is unsafe-tier (trailing `using AllowUnsafe`, plain values, no effect row);
  * `Topic` bridges them to its `< (... & Async)` rows via `Sync.Unsafe.defer`
  * (which supplies the `AllowUnsafe` proof) and `Sync.ensure`. The JVM impl
  * wraps `io.aeron.*`; the Native and JS impl wrap
  * the Aeron C client + embedded C media driver through `AeronBindings`.
  *
  * `offer` returns the raw Aeron position (>0) or a negative sentinel; the
  * sentinel-to-error mapping lives once in shared `Topic` (`AeronSentinels`),
  * never here, so the wire/error policy is byte-identical across platforms.
  * `pollOne` returns the reassembled message bytes (`Absent` = zero fragments);
  * fragment reassembly stays inside the impl (JVM `FragmentAssembler`, FFI C
  * shim). The publication/subscription handle types are abstract members each
  * impl fixes, so neither `io.aeron.Publication` nor `Ffi.Handle` leaks here.
  *
  * The add path is split into a non-blocking async start + poll loop.
  * `asyncAddPublication` / `asyncAddSubscription` start the registration and
  * return an opaque async token (`AsyncPub` / `AsyncSub`). `pollAddPublication`
  * / `pollAddSubscription` perform one poll step returning `AddPoll[H]`: `Done`
  * when the handle is ready, `Awaiting` when still pending, `Failed` on error.
  * The shared `Topic.addPublicationDeadline` / `addSubscriptionDeadline` helpers
  * drive these with an `Async.sleep(addBackoff)` loop bounded by a deadline,
  * surfacing `TopicAddTimeoutException` on expiry. No C busy-loop remains; no carrier
  * thread is monopolized; fibers are interruptible throughout.
  *
  * `asyncAddPublication` returns `Maybe[AsyncPub]` so that a closed-client
  * condition surfaces as `Absent` rather than an unhandled exception or a
  * use-after-free. The FFI impl detects it via the C shim's global live-bundle
  * registry (a closed client returns NULL); the JVM impl maps the
  * `AeronException` thrown on a closed client to `Absent`. `Topic.publish` maps
  * `Absent` to `TopicPublicationClosedException`; `Topic.stream` maps it to
  * `TopicBackpressureExhaustedException` (transient, retried per the retry schedule).
  */
private[kyo] trait AeronTransport:
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

    /** Returns a recorded fatal transport error if one has been captured since this client
      * connected, Absent otherwise. The check is called at the offer/poll boundary on every
      * transport operation (per-iteration cost is acceptable): on the common no-error path it is
      * a single read (FFI `hasClientError` under the slot mutex; JVM `AtomicReference.get`); when
      * an error is present FFI makes 2-3 mutex-guarded reads (`hasClientError` + `clientErrorMsg`,
      * plus `clientErrorCode` when the message is empty).
      *
      * The Aeron conductor calls the installed recording error handler on fatal conditions
      * (driver timeout, conductor service timeout, buffer full). The handler records the
      * error into a slot (C: kyo_aeron_error_slot under pthread_mutex; JVM: AtomicReference).
      * The Scala layer reads this slot here and aborts with TopicTransportFailedException when present.
      */
    def fatalError(using AllowUnsafe): Maybe[String]

    /** Injects a synthetic fatal error into the transport error slot for testing.
      *
      * Used by the fatal-error behavioral tests to exercise the TopicTransportFailedException surfacing
      * path without triggering a real Aeron conductor condition. The default implementation is a
      * no-op, used only by the NeverConfirmTransport test fixture; the production transports
      * override it: FfiAeronTransport calls the C kyo_aeron_test_inject_error export and
      * JvmAeronTransport writes errorSlot directly.
      */
    def injectError(errcode: Int, errmsg: String)(using AllowUnsafe): Unit = ()
end AeronTransport

object AeronTransport:
    /** Result of a single async-add poll step.
      *
      * `Done(handle)` signals the registration completed successfully.
      * `Awaiting` signals the registration is still pending; the caller should
      * sleep (cooperative suspension) and poll again.
      * `Failed(errorCode, detail)` signals the registration was rejected by the
      * driver. `errorCode` and `detail` are filled from `aeron_errcode()` /
      * `aeron_errmsg()` on the FFI path and from
      * `RegistrationException.errorCodeValue()` / `.getMessage()` on the JVM path,
      * enabling `TopicRegistrationFailedException` with driver detail.
      */
    enum AddPoll[+H]:
        case Done(handle: H)
        case Awaiting
        case Failed(errorCode: Int, detail: String)
    end AddPoll
end AeronTransport
