package kyo.internal

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** FFI-backed `AeronTransport` implementation for Scala Native and Scala.js.
  *
  * Bridges the `AeronBindings` C downcalls to the platform-neutral
  * `AeronTransport` contract. Each method forwards directly to the corresponding
  * binding with no intermediate allocation, except for the two hot-path
  * buffer operations:
  *
  *   - `offer` copies the serialized byte array into a C-visible `Buffer[Byte]`
  *     via `Buffer.useArray`, which closes the buffer even if `publicationOffer`
  *     throws.
  *   - `pollOne` does NOT allocate a max-sized receive buffer per call. Each
  *     `Subscription` owns a reusable `Buffer[Byte]` (`SubscriptionState`) that
  *     starts at `InitialReceiveCap` and grows on demand toward the Aeron protocol
  *     ceiling. The steady-state small-message path reuses the same buffer across
  *     polls and allocates nothing off-heap. This mirrors the JVM
  *     `FragmentAssembler`, whose reassembly buffer also grows on demand rather than
  *     allocating the protocol maximum eagerly.
  *
  * Receive-buffer growth handshake with the C shim (see `kyo_aeron.c`
  * `kyo_aeron_subscription_poll`): the shim owns the reassembly slot and grows it on
  * demand. When a reassembled message is larger than the `dst` buffer this impl
  * passes, the shim retains the message in its slot and returns the negated required
  * length (`< -2`). This impl grows the reusable buffer to that length and re-polls;
  * the shim then copies the retained message out WITHOUT consuming another message
  * from Aeron, so no message is lost while the buffer grows. After the first large
  * message the reusable buffer matches the slot and the path is copy-once again.
  *
  * 16 MiB is the Aeron protocol-level hard ceiling (`AERON_FRAME_MAX_MESSAGE_LENGTH`):
  * no publication can send a single logical message larger than this value regardless
  * of term buffer configuration. A message exceeding it is undeliverable; the shim
  * returns -2 and this impl maps it to `Absent`. Every message the JVM
  * `FragmentAssembler` can reassemble is therefore also receivable here (parity).
  *
  * `asyncAddPublication` / `asyncAddSubscription` delegate to the C shim's start
  * functions and return opaque tokens immediately. `pollAddPublication` /
  * `pollAddSubscription` perform one poll step each, mapping the C return value to
  * `AddPoll.Done` / `AddPoll.Awaiting` / `AddPoll.Failed`. The shared
  * `Topic.addPublicationDeadline` drives the loop with cooperative Async.sleep.
  * `freeAsyncPub` / `freeAsyncSub` release the token on Fiber cancellation.
  *
  * All methods are unsafe-tier (`using AllowUnsafe`), forwarded from the
  * `AeronTransport` trait method clauses. No `import AllowUnsafe.embrace.danger`
  * is used or permitted here.
  */
final private[kyo] class FfiAeronTransport(
    bindings: AeronBindings,
    client: Ffi.Handle[AeronClientHandle]
) extends AeronTransport:
    type Publication  = Ffi.Handle[AeronPublication]
    type Subscription = FfiAeronTransport.SubscriptionState
    type AsyncPub     = Ffi.Handle[AeronAsyncPub]
    type AsyncSub     = Ffi.Handle[AeronAsyncSub]

    import FfiAeronTransport.*

    def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] =
        bindings.asyncAddPublication(client, uri, streamId)

    def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
        val r = bindings.asyncAddPublicationPoll(async)
        if r > 0 then
            // Registration complete: retrieve the bundle. _get frees the token internally.
            bindings.asyncAddPublicationGet(async) match
                case Present(pub) => AeronTransport.AddPoll.Done(pub)
                case Absent       => AeronTransport.AddPoll.Failed(0, "") // defensive: _get returned NULL
        else if r == 0 then AeronTransport.AddPoll.Awaiting
        else
            // r < 0: registration failed; token is still alive (not freed by _poll on < 0 path).
            // Read errcode/errmsg before the token is freed by freeAsyncPub in the Failed arm.
            // asyncAddPublicationErrMsg returns Ffi.Borrowed[String]: extract via .value before free.
            // Errcode normalize: aeron_errcode() reports AERON_CLIENT_ERRORED_MEDIA_DRIVER as the NEGATED
            // driver code (aeron_client.c); take the magnitude so errorCode is the raw POSITIVE
            // driver code on FFI, matching JVM RegistrationException.errorCodeValue(). No row widens;
            // no message added here. Meaning documented once on TopicRegistrationFailedException (TopicException.scala).
            val code   = math.abs(bindings.asyncAddPublicationErrCode(async))
            val detail = bindings.asyncAddPublicationErrMsg(async).value
            AeronTransport.AddPoll.Failed(code, detail)
        end if
    end pollAddPublication

    def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit =
        bindings.asyncAddPublicationFree(async)

    def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean =
        bindings.publicationIsConnected(pub) != 0

    def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long =
        // Unsafe: copies the serialized bytes into a C-read input buffer; AllowUnsafe scoped to this method.
        Buffer.useArray(message) { buf =>
            bindings.publicationOffer(pub, buf, message.length)
        }

    def maxMessageLength(pub: Publication)(using AllowUnsafe): Int =
        bindings.publicationMaxMessageLength(pub)

    def closePublication(pub: Publication)(using AllowUnsafe): Unit =
        bindings.publicationClose(pub)

    def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] =
        bindings.asyncAddSubscription(client, uri, streamId)

    def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription] =
        val r = bindings.asyncAddSubscriptionPoll(async)
        if r > 0 then
            bindings.asyncAddSubscriptionGet(async) match
                case Present(sub) => AeronTransport.AddPoll.Done(SubscriptionState(sub))
                case Absent       => AeronTransport.AddPoll.Failed(0, "")
        else if r == 0 then AeronTransport.AddPoll.Awaiting
        else
            // r < 0: registration failed; token is still alive (not freed by _poll on < 0 path).
            // asyncAddSubscriptionErrMsg returns Ffi.Borrowed[String]: extract via .value before free.
            // Errcode normalize (symmetric to pollAddPublication): positive driver code on FFI, matching JVM.
            val code   = math.abs(bindings.asyncAddSubscriptionErrCode(async))
            val detail = bindings.asyncAddSubscriptionErrMsg(async).value
            AeronTransport.AddPoll.Failed(code, detail)
        end if
    end pollAddSubscription

    def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit =
        bindings.asyncAddSubscriptionFree(async)

    def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean =
        bindings.subscriptionIsConnected(sub.handle) != 0

    def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]] =
        // Unsafe: reads the reassembled fragment into a C-written dst buffer; AllowUnsafe scoped to this method.
        // The shim returns:
        //   > 0  -> message bytes copied into the reusable buffer (the common path).
        //   0    -> no message this poll.
        //   -1   -> poll error.
        //   -2   -> message exceeds the 16 MiB protocol ceiling (undeliverable).
        //   < -2 -> message retained in the shim slot; -len is the required dst capacity.
        //           Grow the reusable buffer to that size and re-poll; the shim copies
        //           the retained message out without consuming a new Aeron message.
        val len = bindings.subscriptionPoll(sub.handle, sub.receive, sub.receiveCap)
        if len > 0 then Maybe(Buffer.copyToArray[Byte](sub.receive, 0, len.toInt))
        else if len >= -2 then Absent // 0 (no message), -1 (error), or -2 (ceiling overflow)
        else
            val required = (-len).toInt // shim's reassembled length; fits int (<= 16 MiB ceiling)
            sub.grow(required)
            val copied = bindings.subscriptionPoll(sub.handle, sub.receive, sub.receiveCap)
            if copied > 0 then Maybe(Buffer.copyToArray[Byte](sub.receive, 0, copied.toInt))
            else Absent
        end if
    end pollOne

    def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit =
        bindings.subscriptionClose(sub.handle)
        sub.close()

    def fatalError(using AllowUnsafe): Maybe[String] =
        // Check the error slot recorded by the non-exiting C handler.
        // hasClientError is the authoritative "a fatal error occurred" signal. Surface on
        // present=1 REGARDLESS of message emptiness. When the Aeron handler received a NULL
        // or empty message (some fatal conditions produce no message text), derive a non-empty
        // detail from the numeric errcode so the Scala layer always gets a typed, non-empty
        // TopicTransportFailedException detail. Read the message once before deciding.
        if bindings.hasClientError(client) != 0 then
            val msg = bindings.clientErrorMsg(client).value
            if msg.nonEmpty then Present(msg)
            else Present(s"fatal client error (code ${bindings.clientErrorCode(client)})")
        else Absent

    override def injectError(errcode: Int, errmsg: String)(using AllowUnsafe): Unit =
        // Test-inject seam: fires the error handler with a synthetic error.
        bindings.testInjectError(client, errcode, errmsg)

end FfiAeronTransport

private[kyo] object FfiAeronTransport:

    // Initial receive-buffer capacity, matching the shim's KYO_AERON_SLOT_INITIAL_CAP
    // (kyo_aeron.c). 64 KiB holds an unfragmented IPC message and most multi-fragment
    // messages without a single regrow, so the steady-state small-message poll path
    // reuses one buffer and allocates nothing off-heap.
    private inline val InitialReceiveCap = 64 * 1024

    /** Per-subscription state: the FFI subscription handle plus a reusable receive
      * buffer that grows on demand toward the 16 MiB Aeron protocol ceiling.
      *
      * The single-poller-per-subscription contract means `pollOne` is never
      * called concurrently on the same `SubscriptionState`, so the mutable `receive`
      * buffer is confined to one caller and needs no synchronization. The buffer is
      * released by `closeSubscription` via [[close]].
      */
    final private[kyo] class SubscriptionState private (val handle: Ffi.Handle[AeronSubscription], private var buffer: Buffer[Byte]):
        def receive: Buffer[Byte] = buffer
        def receiveCap: Int       = buffer.size

        /** Grow the reusable buffer to at least `required` bytes (no-op if already
          * large enough). The old buffer is closed before the larger one replaces it,
          * so growth does not leak. Called only from the growth handshake in `pollOne`.
          */
        def grow(required: Int)(using AllowUnsafe): Unit =
            if required > buffer.size then
                // Unsafe: off-heap buffer realloc (close the old buffer, alloc a larger one); AllowUnsafe scoped to this method.
                buffer.close()
                buffer = Buffer.alloc[Byte](required)

        /** Release the reusable receive buffer. Idempotent (`Buffer.close` is). */
        // Unsafe: off-heap buffer release; AllowUnsafe scoped to this method.
        def close()(using AllowUnsafe): Unit = buffer.close()
    end SubscriptionState

    private[kyo] object SubscriptionState:
        /** Smart constructor: allocates the reusable off-heap receive buffer. AllowUnsafe
          * is scoped to this factory only, never to the class or its primary constructor.
          * The primary constructor takes the pre-allocated buffer and performs no unsafe
          * operation.
          */
        def apply(handle: Ffi.Handle[AeronSubscription])(using AllowUnsafe): SubscriptionState =
            // Unsafe: off-heap receive buffer owned for the subscription's lifetime; released in close().
            new SubscriptionState(handle, Buffer.alloc[Byte](InitialReceiveCap))
    end SubscriptionState
end FfiAeronTransport
