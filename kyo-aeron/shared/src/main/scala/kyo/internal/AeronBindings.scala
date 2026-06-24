package kyo.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** FFI binding trait for the Aeron C client and embedded C media driver.
  *
  * Each method maps 1:1 to a `kyo_aeron_*` C symbol exported by the kyo_aeron
  * shim (see `kyo_aeron.c`). The symbol name is derived by prepending
  * `symbolPrefix` to the snake_case of the Scala method name, per the kyo-ffi
  * codegen convention (e.g. `asyncAddPublication` -> `kyo_aeron_async_add_publication`).
  *
  * `driverStart` and `clientConnect` are `@Ffi.blocking`: the driver-start /
  * connect handshake can block up to the driver-timeout (~10 s on a driver-absent
  * connect). `@Ffi.blocking` routes the downcall off the JS event loop (libuv
  * worker) and parks the Native/JVM carrier under the scheduler's blocking monitor,
  * never freezing the event loop or stranding a compute carrier. The generated
  * signature returns `kyo.Fiber.Unsafe[Handle[A], Any]` (Ffi.scala `@blocking`
  * scaladoc); consumers bridge via `.safe.get`. The hot-path methods (`offer`, `poll`,
  * `is_connected`, the async-add steps) are plain synchronous downcalls: those Aeron C
  * operations are non-blocking by contract, and the embedded driver runs its own
  * conductor/sender/receiver C pthreads that never call back into Scala.
  * The close methods (`driverClose`, `clientClose`) are plain downcalls too, but are not
  * strictly non-blocking: `aeron_driver_close` joins the driver's conductor/sender/receiver
  * pthreads. That join is bounded and runs only at `Topic.run` teardown, so on JVM/Native the
  * scheduler's blocking monitor compensates and on JS it is a one-shot synchronous join at scope
  * exit, distinct from the unbounded ~10 s connect that `@Ffi.blocking` is reserved for.
  *
  * `publicationIsConnected` and `subscriptionIsConnected` return `Int` (1 or 0)
  * because kyo-ffi has no cross-platform bool return type. The FFI-backed
  * `AeronTransport` implementation converts the int to `Boolean`.
  *
  * `asyncAddPublication` and `asyncAddSubscription` return `Maybe[Handle[...]]`
  * because the C shim returns NULL when the client bundle has been closed
  * concurrently (add-vs-close race guard). NULL maps to `Absent`; the
  * FFI-backed transport maps `Absent` to `TopicPublicationClosedException`. A bare `Handle`
  * return would throw `FfiNullPointer` (a panic) instead of a clean typed error.
  *
  * The async add split:
  *   `asyncAddPublication` starts the registration and returns an async token immediately.
  *   `asyncAddPublicationPoll` performs one poll step: >0 = done, 0 = awaiting, <0 = error.
  *   `asyncAddPublicationGet` retrieves the completed publication handle after poll >0.
  *   `asyncAddPublicationFree` releases the token on fiber interrupt or after a < 0 error.
  *   `asyncAddPublicationErrCode` / `asyncAddPublicationErrMsg`: read the driver error code
  *   and message from the token after poll returns < 0, BEFORE calling _free.
  *   Symmetric quartet for subscriptions.
  *
  * After any edit to this file, run `sbt clean` (full clean, not `ffiClean`)
  * before re-validating: the ffiGenerate cache is keyed on TASTy and silently drops
  * new methods without a full cache invalidation.
  */
private[kyo] trait AeronBindings extends Ffi:
    // @Ffi.blocking methods declare a Fiber.Unsafe[..., Any] return: the codegen enforces this
    // (FfiInspector) and surfaces the blocking downcall as an already-completed (JVM/Native) or
    // callback-resolved (JS) fiber; consumers bridge via .safe.get.
    @Ffi.blocking
    def driverStart(dir: String)(using AllowUnsafe): Fiber.Unsafe[Ffi.Handle[AeronDriver], Any]
    def driverClose(driver: Ffi.Handle[AeronDriver])(using AllowUnsafe): Unit
    @Ffi.blocking
    def clientConnect(dir: String)(using AllowUnsafe): Fiber.Unsafe[Ffi.Handle[AeronClientHandle], Any]
    def clientClose(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Unit

    def asyncAddPublication(client: Ffi.Handle[AeronClientHandle], uri: String, streamId: Int)(using
        AllowUnsafe
    ): Maybe[Ffi.Handle[AeronAsyncPub]]
    def asyncAddPublicationPoll(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Long
    def asyncAddPublicationGet(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Maybe[Ffi.Handle[AeronPublication]]
    def asyncAddPublicationFree(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Unit
    def asyncAddPublicationErrCode(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Int
    def asyncAddPublicationErrMsg(async: Ffi.Handle[AeronAsyncPub])(using AllowUnsafe): Ffi.Borrowed[String]
    def publicationIsConnected(pub: Ffi.Handle[AeronPublication])(using AllowUnsafe): Int
    def publicationOffer(pub: Ffi.Handle[AeronPublication], buffer: Buffer[Byte], length: Int)(using AllowUnsafe): Long
    def publicationMaxMessageLength(pub: Ffi.Handle[AeronPublication])(using AllowUnsafe): Int
    def publicationClose(pub: Ffi.Handle[AeronPublication])(using AllowUnsafe): Unit

    def asyncAddSubscription(client: Ffi.Handle[AeronClientHandle], uri: String, streamId: Int)(using
        AllowUnsafe
    ): Maybe[Ffi.Handle[AeronAsyncSub]]
    def asyncAddSubscriptionPoll(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Long
    def asyncAddSubscriptionGet(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Maybe[Ffi.Handle[AeronSubscription]]
    def asyncAddSubscriptionFree(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Unit
    def asyncAddSubscriptionErrCode(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Int
    def asyncAddSubscriptionErrMsg(async: Ffi.Handle[AeronAsyncSub])(using AllowUnsafe): Ffi.Borrowed[String]
    def subscriptionIsConnected(sub: Ffi.Handle[AeronSubscription])(using AllowUnsafe): Int
    def subscriptionPoll(sub: Ffi.Handle[AeronSubscription], dst: Buffer[Byte], dstCap: Int)(using AllowUnsafe): Long
    def subscriptionClose(sub: Ffi.Handle[AeronSubscription])(using AllowUnsafe): Unit

    // Error slot accessors for the recording error handler.
    // hasClientError returns 1 if a fatal error was recorded, 0 otherwise.
    // clientErrorMsg returns the recorded error message (may be empty when the handler received
    //   a NULL message; clientErrorCode is used to derive a non-empty detail in that case).
    // clientErrorCode returns the recorded errcode, which Aeron may report as 0 even when an
    //   error is present; it is used only to derive a fallback detail when the message is empty.
    // Maps to kyo_aeron_has_client_error / kyo_aeron_client_error_msg / kyo_aeron_client_error_code.
    // Three-step binding: hasClientError checks presence, clientErrorMsg reads the message,
    //   clientErrorCode reads the numeric code for deriving a non-empty detail when the message is empty.
    def hasClientError(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Int
    def clientErrorMsg(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Ffi.Borrowed[String]
    def clientErrorCode(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Int

    // Test-inject seam: fires the error handler with a synthetic errcode/errmsg.
    // Always compiled in; inert in production (only behavioral tests call it).
    // Maps to kyo_aeron_test_inject_error.
    def testInjectError(client: Ffi.Handle[AeronClientHandle], errcode: Int, errmsg: String)(using AllowUnsafe): Unit
end AeronBindings

private[kyo] object AeronBindings extends Ffi.Config(
        library = "kyo_aeron",
        symbolPrefix = "kyo_aeron_",
        headers = Chunk("aeronc.h", "aeronmd.h"),
        // nativeBundled = true: the kyo_aeron C sources are statically folded into the
        // Native binary via the Scala Native resource mechanism and force-loaded archives.
        // The codegen must NOT emit @link("kyo_aeron") on the @extern object; doing so
        // would cause the linker to look for a dynamic -lkyo_aeron, which does not exist.
        nativeBundled = true
    )

final private[kyo] class AeronDriver
final private[kyo] class AeronClientHandle
final private[kyo] class AeronPublication
final private[kyo] class AeronSubscription
final private[kyo] class AeronAsyncPub
final private[kyo] class AeronAsyncSub
