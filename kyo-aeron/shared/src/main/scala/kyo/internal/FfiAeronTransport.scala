package kyo.internal

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** FFI-backed `AeronTransport` implementation for Scala Native and Scala.js.
  *
  * Every method forwards to its `AeronBindings` downcall with no intermediate allocation, except
  * the two hot-path buffer operations. `offer` copies into a C-visible `Buffer[Byte]` via
  * `Buffer.useArray`, which closes the buffer even if the downcall throws. `pollOne` reuses a
  * per-subscription buffer that grows on demand, so the steady-state small-message path allocates
  * nothing off-heap.
  *
  * Growing that buffer is a handshake with the shim, which owns the reassembly slot: when a
  * reassembled message exceeds the `dst` passed in, the shim retains it and returns the negated
  * required length (`< -2`), so this impl grows and re-polls and the shim copies the retained
  * message out without consuming another from Aeron. A message above Aeron's 16 MiB protocol
  * ceiling is undeliverable instead: the shim returns -2, which maps to `Absent`.
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
            // _get frees the token internally.
            bindings.asyncAddPublicationGet(async) match
                case Present(pub) => AeronTransport.AddPoll.Done(pub)
                case Absent       => AeronTransport.AddPoll.Failed(0, "") // defensive: _get returned NULL
        else if r == 0 then AeronTransport.AddPoll.Awaiting
        else
            // The token survives a < 0 poll, so the errcode and the borrowed errmsg must both be
            // read here, before the Failed arm frees it. aeron_errcode() reports a driver rejection
            // as the negated driver code, so take the magnitude to match JVM errorCodeValue().
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
        // Unsafe: copies the serialized bytes into a C-read input buffer.
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
            // Same token-lifetime and errcode-magnitude rules as pollAddPublication.
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
        // Unsafe: reads the reassembled fragment into a C-written dst buffer.
        // The shim returns:
        //   > 0  -> message bytes copied into the reusable buffer (the common path).
        //   0    -> no message this poll.
        //   -1   -> poll error.
        //   -2   -> message exceeds the 16 MiB protocol ceiling (undeliverable).
        //   < -2 -> message retained in the shim slot; -len is the required dst capacity.
        val len = bindings.subscriptionPoll(sub.handle, sub.receive, sub.receiveCap)
        if len > 0 then Maybe(Buffer.copyToArray[Byte](sub.receive, 0, len.toInt))
        else if len >= -2 then Absent // 0 (no message), -1 (error), or -2 (ceiling overflow)
        else
            val required = (-len).toInt // fits int: bounded by the 16 MiB ceiling
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
        // hasClientError alone decides presence, regardless of message emptiness: some fatal
        // conditions produce no message text, and the errcode fallback keeps the
        // TopicTransportFailedException detail non-empty.
        if bindings.hasClientError(client) != 0 then
            val msg = bindings.clientErrorMsg(client).value
            if msg.nonEmpty then Present(msg)
            else Present(s"fatal client error (code ${bindings.clientErrorCode(client)})")
        else Absent

    override def injectError(errcode: Int, errmsg: String)(using AllowUnsafe): Unit =
        bindings.testInjectError(client, errcode, errmsg)

end FfiAeronTransport

private[kyo] object FfiAeronTransport:

    /** Matches the shim's `KYO_AERON_SLOT_INITIAL_CAP`. 64 KiB holds an unfragmented IPC message
      * and most multi-fragment messages without a regrow.
      */
    private inline val InitialReceiveCap = 64 * 1024

    /** Per-subscription state: the FFI subscription handle plus a reusable receive buffer that
      * grows on demand toward the 16 MiB Aeron protocol ceiling.
      *
      * The single-poller-per-subscription contract rules out concurrent `pollOne` on the same
      * state, so the mutable `receive` buffer is confined to one caller and needs no
      * synchronization. `closeSubscription` releases it via [[close]].
      */
    final private[kyo] class SubscriptionState private (val handle: Ffi.Handle[AeronSubscription], private var buffer: Buffer[Byte]):
        def receive: Buffer[Byte] = buffer
        def receiveCap: Int       = buffer.size

        /** Grow the reusable buffer to at least `required` bytes, no-op if already large enough.
          * The old buffer is closed before the larger one replaces it, so growth does not leak.
          */
        def grow(required: Int)(using AllowUnsafe): Unit =
            if required > buffer.size then
                // Unsafe: off-heap realloc, closing the old buffer before allocating a larger one.
                buffer.close()
                buffer = Buffer.alloc[Byte](required)

        /** Release the reusable receive buffer. Idempotent (`Buffer.close` is). */
        // Unsafe: off-heap buffer release; AllowUnsafe scoped to this method.
        def close()(using AllowUnsafe): Unit = buffer.close()
    end SubscriptionState

    private[kyo] object SubscriptionState:
        /** Smart constructor allocating the reusable off-heap receive buffer. AllowUnsafe stays
          * scoped to this factory: the primary constructor takes the pre-allocated buffer and
          * performs no unsafe operation.
          */
        def apply(handle: Ffi.Handle[AeronSubscription])(using AllowUnsafe): SubscriptionState =
            // Unsafe: off-heap buffer owned for the subscription's lifetime; released in close().
            new SubscriptionState(handle, Buffer.alloc[Byte](InitialReceiveCap))
    end SubscriptionState
end FfiAeronTransport
