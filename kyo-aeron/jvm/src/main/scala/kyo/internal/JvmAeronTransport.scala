package kyo.internal

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Publication as AeronPublication
import io.aeron.Subscription as AeronSubscription
import io.aeron.exceptions.AeronException
import io.aeron.exceptions.RegistrationException
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import kyo.*
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer

/** JVM transport over io.aeron's `Aeron`, `Publication`, `Subscription`, `BufferClaim`, and
  * `FragmentAssembler`, none of which have a cross-platform Kyo wrapper.
  *
  * The Java client's async add API returns a `Long` registration id, then `getPublication` yields
  * null while awaiting, the publication once done, or throws `RegistrationException` on driver
  * rejection. That maps onto the same `AeronTransport` contract the FFI path implements, so both
  * share `Topic.addPublicationDeadline`'s bounded wait loop.
  */
final private[kyo] class JvmAeronTransport(
    aeron: Aeron,
    errorSlot: java.util.concurrent.atomic.AtomicReference[String]
) extends AeronTransport:
    type Publication  = PublicationState
    type Subscription = SubscriptionState
    // The async tokens are the Long registration IDs returned by asyncAdd*.
    type AsyncPub = Long
    type AsyncSub = Long

    /** Pairs a publication with a reusable `BufferClaim`, saving a per-offer allocation on the
      * publish hot path. Exactly one publish call owns a publication and offers sequentially, so
      * the claim is never raced.
      */
    class PublicationState(val publication: AeronPublication):
        val claim: BufferClaim = new BufferClaim

    class SubscriptionState(val subscription: AeronSubscription):
        /** Holds the last polled message. The `FragmentAssembler` callback writes it synchronously
          * inside `poll()` and `pollOne` reads it immediately after, so under the
          * single-poller-per-subscription contract a plain var is safe. `private[kyo]` keeps it
          * reachable from the handler closure and the test subclasses, no wider.
          */
        private[kyo] var result: Maybe[Array[Byte]] = Absent
        val handler: FragmentAssembler =
            new FragmentAssembler((buffer: DirectBuffer, offset: Int, length: Int, header: Header) =>
                val bytes = new Array[Byte](length)
                buffer.getBytes(offset, bytes)
                result = Maybe(bytes)
            )

        /** The single `io.aeron.Subscription.poll` call site, extracted from `pollOne` so a test
          * subclass can drive a throwing poll: `io.aeron.Subscription` is final and no mock
          * framework is on the test classpath. The virtual call inlines, so the hot path keeps its
          * zero-allocation shape.
          */
        private[kyo] def poll(): Int = subscription.poll(handler, 1)
    end SubscriptionState

    def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] =
        // A concurrently closed client throws AeronException("Aeron client is closed"); Absent
        // matches the C shim's NULL signal for the same condition.
        try Present(aeron.asyncAddPublication(uri, streamId))
        catch case _: AeronException => Absent

    def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
        try
            val pub = aeron.getPublication(async)
            if pub == null then AeronTransport.AddPoll.Awaiting
            else AeronTransport.AddPoll.Done(new PublicationState(pub))
        catch
            case e: RegistrationException =>
                // errorCodeValue() is the driver's typed error code, always non-zero.
                AeronTransport.AddPoll.Failed(e.errorCodeValue(), e.getMessage)
            case _: AeronException =>
                // A closed client carries no registration error code, and errorCode == 0 is
                // exactly how Topic.addPublicationDeadline tells the two apart: it routes this to
                // Absent (TopicPublicationClosedException), not TopicRegistrationFailedException.
                AeronTransport.AddPoll.Failed(0, "")

    /** No native token to free: the registration id is a JVM primitive and the conductor cleans up
      * an abandoned registration itself.
      */
    def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit = ()

    def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean =
        // The FFI shim returns 0 under b->closed, so guard on isClosed() to keep the
        // post-own-close contract uniform across platforms (as maxMessageLength does).
        if pub.publication.isClosed() then false
        else pub.publication.isConnected()

    def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long =
        // Unsafe: writes into an off-heap claimed log-buffer region (tryClaim + commit), or wraps
        // the bytes in an UnsafeBuffer on the fragmented fallback.
        // tryClaim is the zero-copy fast path but rejects lengths above maxPayloadLength (~1376
        // bytes on IPC), so larger messages take offer's copy-and-fragment path. Both throw IAE on
        // an oversize message, which Topic.publish's up-front check makes unreachable outside a
        // race; the catch normalizes that race to the same -6 the FFI offer returns.
        val publication = pub.publication
        try
            if message.length <= publication.maxPayloadLength() then
                val claim  = pub.claim
                val result = publication.tryClaim(message.length, claim)
                if result > 0 then
                    val buffer = claim.buffer()
                    val offset = claim.offset()
                    buffer.putBytes(offset, message)
                    claim.commit()
                end if
                result
            else
                publication.offer(new UnsafeBuffer(message), 0, message.length)
            end if
        catch case _: IllegalArgumentException => AeronSentinels.Error
        end try
    end offer

    def maxMessageLength(pub: Publication)(using AllowUnsafe): Int =
        // io.aeron caches maxMessageLength and would keep returning the live value after the
        // caller's own close; guarding on isClosed() matches the FFI shim, which returns 0 once
        // the bundle is closed.
        if pub.publication.isClosed() then 0
        else
            // Long return, but bounded by the 16 MiB ceiling, so the Int cast is safe.
            pub.publication.maxMessageLength().toInt

    def closePublication(pub: Publication)(using AllowUnsafe): Unit = pub.publication.close()

    def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] =
        // Closed-client handling as in asyncAddPublication.
        try Present(aeron.asyncAddSubscription(uri, streamId))
        catch case _: AeronException => Absent

    def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription] =
        try
            val sub = aeron.getSubscription(async)
            if sub == null then AeronTransport.AddPoll.Awaiting
            else AeronTransport.AddPoll.Done(new SubscriptionState(sub))
        catch
            case e: RegistrationException =>
                AeronTransport.AddPoll.Failed(e.errorCodeValue(), e.getMessage)
            case _: AeronException =>
                AeronTransport.AddPoll.Failed(0, "")

    // No native token to free, as in freeAsyncPub.
    def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit = ()

    def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean =
        sub.subscription.isConnected()

    def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]] =
        sub.result = Absent
        // poll throws transiently under load (a registration error surfaced on poll, a log buffer
        // closed by an interrupt during teardown). Reporting an empty poll lets the shared retry
        // loop absorb it as backpressure rather than escaping as a panic; fatal errors still
        // surface through fatalError.
        val fragmentsRead =
            try sub.poll()
            catch
                case _: AeronException                               => 0
                case _: java.nio.channels.ClosedByInterruptException => 0
        if fragmentsRead == 0 then Absent else sub.result
    end pollOne

    def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit =
        sub.subscription.close()

    def fatalError(using AllowUnsafe): Maybe[String] =
        // The slot the Aeron.Context error handler sets holds null or a non-empty string: both
        // that handler (AeronPlatformTransport) and injectError below derive a non-empty detail.
        Maybe(errorSlot.get())

    override def injectError(errcode: Int, errmsg: String)(using AllowUnsafe): Unit =
        // Writes the slot directly, bypassing the JVM handler, since no conductor is involved.
        // The errcode fallback keeps fatalError's detail non-empty.
        val detail =
            if errmsg.nonEmpty then errmsg
            else s"fatal client error (code $errcode)"
        errorSlot.set(detail)
    end injectError
end JvmAeronTransport
