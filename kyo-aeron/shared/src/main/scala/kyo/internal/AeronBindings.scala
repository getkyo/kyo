package kyo.internal

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.Maybe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** FFI binding trait for the Aeron C client and embedded C media driver.
  *
  * Each method maps 1:1 to a `kyo_aeron_*` C symbol in `kyo_aeron.c`, the name derived by
  * prepending `symbolPrefix` to the snake_case of the Scala method name.
  *
  * Only `driverStart` and `clientConnect` are `@Ffi.blocking`, since their handshake runs up to the
  * ~10s driver timeout. Everything else is a plain downcall: the hot-path Aeron C operations are
  * non-blocking by contract, and although `driverClose` and `clientClose` join the driver's
  * pthreads, that join is bounded and runs only at teardown.
  *
  * Two return types are shaped by the FFI boundary rather than by Aeron. The `isConnected` pair
  * returns `Int` because kyo-ffi has no cross-platform bool. The `asyncAdd*` pair returns `Maybe`
  * because the shim returns NULL when the client bundle was closed concurrently, and a bare
  * `Handle` would turn that race into an `FfiNullPointer` panic instead of a typed error.
  *
  * The async add split, per resource: `asyncAdd*` starts the registration and returns a token;
  * `asyncAdd*Poll` steps it (>0 done, 0 awaiting, <0 error); `asyncAdd*Get` takes the handle and
  * frees the token; `asyncAdd*Free` releases it on interrupt or after an error; `asyncAdd*ErrCode`
  * and `asyncAdd*ErrMsg` read the driver error, which must happen before the free.
  *
  * After any edit here, run a full `sbt clean` (not `ffiClean`) before re-validating: the
  * ffiGenerate cache is keyed on TASTy and silently drops new methods otherwise.
  */
private[kyo] trait AeronBindings extends Ffi:
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

    /** Reads the recording error handler's slot. `hasClientError` is the authoritative presence
      * signal: `clientErrorMsg` is empty when the handler received a NULL message, and Aeron may
      * report `clientErrorCode` as 0 even with an error present, so the code is good only as a
      * fallback detail.
      */
    def hasClientError(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Int
    def clientErrorMsg(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Ffi.Borrowed[String]
    def clientErrorCode(client: Ffi.Handle[AeronClientHandle])(using AllowUnsafe): Int

    /** Fires the error handler with a synthetic errcode and errmsg. Always compiled in, inert in
      * production since only tests call it.
      */
    def testInjectError(client: Ffi.Handle[AeronClientHandle], errcode: Int, errmsg: String)(using AllowUnsafe): Unit
end AeronBindings

private[kyo] object AeronBindings extends Ffi.Config(
        library = "kyo_aeron",
        symbolPrefix = "kyo_aeron_",
        headers = Chunk("aeronc.h", "aeronmd.h"),
        // The C sources are statically folded into the Native binary, so the codegen must not emit
        // @link("kyo_aeron"): that sends the linker looking for a dynamic library that does not exist.
        nativeBundled = true
    )

final private[kyo] class AeronDriver
final private[kyo] class AeronClientHandle
final private[kyo] class AeronPublication
final private[kyo] class AeronSubscription
final private[kyo] class AeronAsyncPub
final private[kyo] class AeronAsyncSub
