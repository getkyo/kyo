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

// JVM-only: wraps io.aeron.* (Aeron / Publication / Subscription / BufferClaim / FragmentAssembler),
// JVM primitives with no cross-platform Kyo wrapper. The cross-platform path is the shared FFI transport.
//
// The JVM Aeron client provides a non-blocking async add API:
//   aeron.asyncAddPublication(uri, streamId) -> Long (registration ID)
//   aeron.getPublication(registrationId)     -> null (awaiting) | ConcurrentPublication (done)
//   getPublication throws RegistrationException on driver rejection.
// The shared Topic.addPublicationDeadline drives the poll loop via the same AeronTransport
// contract (asyncAddPublication / pollAddPublication / freeAsyncPub), so both platforms
// share one bounded Scala Async wait loop with Async.sleep backoff + a deadline.
// Confirmed by inspecting the Aeron 1.50.2 JAR: asyncAddPublication returns Long;
// getPublication is non-blocking and returns null while pending.
final private[kyo] class JvmAeronTransport(
    aeron: Aeron,
    errorSlot: java.util.concurrent.atomic.AtomicReference[String]
) extends AeronTransport:
    type Publication  = PublicationState
    type Subscription = SubscriptionState
    // JVM async token types: the Long registration ID returned by asyncAdd*.
    type AsyncPub = Long
    type AsyncSub = Long

    // Wraps io.aeron.Publication with a reusable BufferClaim. A publication is owned by exactly one
    // publish call (Topic.publish creates it, offers to it sequentially via foreachChunk, and never
    // shares it across fibers), so the claim is never raced; reusing it across offers avoids a
    // per-offer BufferClaim allocation on the publish hot path. Mirrors SubscriptionState.
    class PublicationState(val publication: AeronPublication):
        val claim: BufferClaim = new BufferClaim

    class SubscriptionState(val subscription: AeronSubscription):
        // Written by the FragmentAssembler callback, which runs synchronously inside poll() and is
        // read immediately after poll() returns; the single-poller-per-subscription contract
        // rules out concurrent access, so a plain var is safe here. private[kyo] keeps it visible to
        // the handler closure and the JvmAeronTransportTest poll-override subclasses, no wider.
        private[kyo] var result: Maybe[Array[Byte]] = Absent
        val handler: FragmentAssembler =
            new FragmentAssembler((buffer: DirectBuffer, offset: Int, length: Int, header: Header) =>
                val bytes = new Array[Byte](length)
                buffer.getBytes(offset, bytes)
                result = Maybe(bytes)
            )
        // The single io.aeron.Subscription.poll call site, factored as a method (not the
        // inline expression in pollOne) solely so the poll-catch is unit-testable:
        // io.aeron.Subscription is final and no mock framework is on the test classpath, so a
        // test subclass overriding this is the only deterministic way to drive a throwing poll
        // (JvmAeronTransportTest). Non-allocating: a direct virtual call, JIT-inlinable,
        // so the subscriber poll hot path keeps its zero-allocation shape.
        private[kyo] def poll(): Int = subscription.poll(handler, 1)
    end SubscriptionState

    def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] =
        // Catch AeronException("Aeron client is closed") thrown when the client has been
        // closed concurrently. Map closed-client to Absent, matching the C shim's NULL signal.
        try Present(aeron.asyncAddPublication(uri, streamId))
        catch case _: AeronException => Absent

    def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
        try
            val pub = aeron.getPublication(async)
            if pub == null then AeronTransport.AddPoll.Awaiting
            else AeronTransport.AddPoll.Done(new PublicationState(pub))
        catch
            case e: RegistrationException =>
                // Registration rejected by the driver: surface the driver error code and message.
                // RegistrationException.errorCodeValue() is the driver's typed error code (non-zero).
                // e.getMessage includes the driver message with an appended ", errorCodeValue=N" suffix.
                AeronTransport.AddPoll.Failed(e.errorCodeValue(), e.getMessage)
            case _: AeronException =>
                // Closed-client AeronException: no registration error code; map to Failed(0, "").
                // Topic.addPublicationDeadline distinguishes this from a registration failure by
                // errorCode == 0, routing it to Absent (TopicPublicationClosedException) not TopicRegistrationFailedException.
                AeronTransport.AddPoll.Failed(0, "")

    // JVM: no native token to free; the registration ID is a primitive on the JVM heap.
    // The Aeron conductor handles cleanup internally when the registration is abandoned.
    def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit = ()

    def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean =
        // Return false on a closed publication, matching the FFI shim's closed-guard (the C path
        // returns 0 under b->closed) so the post-own-close contract is uniform across platforms,
        // mirroring the isClosed() guard in maxMessageLength.
        if pub.publication.isClosed() then false
        else pub.publication.isConnected()

    def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long =
        // Unsafe: writes the message bytes into an off-heap claimed log-buffer region (tryClaim + commit),
        // or wraps them in an UnsafeBuffer on the fragmented fallback; AllowUnsafe scoped to this method.
        // Use tryClaim for messages within the MTU limit (zero-copy fast path).
        // Fall back to offer (copy + fragmentation) for larger messages; tryClaim
        // throws if the length exceeds maxPayloadLength (typically ~1376 bytes on IPC).
        // Belt-and-suspenders IAE catch: Publication.checkMaxMessageLength throws
        // IllegalArgumentException for oversize messages from both offer and tryClaim.
        // The up-front check in Topic.publish makes this unreachable in normal operation,
        // but the catch normalizes the rare race to -6 (AeronSentinels.Error),
        // matching the FFI path's aeron_publication_offer return value. A zero-length payload
        // would also make tryClaim throw IAE, but a MsgPack-encoded Envelope is never empty
        // (TopicInvariantsTest pins this), so the zero-length case is unreachable here.
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
        // Return 0 on a closed publication so the value matches the FFI shim's closed-guard (the
        // C path returns 0 when the bundle is closed). io.aeron caches maxMessageLength and would
        // otherwise return the live value after the caller's own close, diverging from FFI.
        if pub.publication.isClosed() then 0
        else
            // Publication.maxMessageLength() returns Long; cast to Int is safe because
            // the value is at most 16 MiB (16777216), well within Int range.
            pub.publication.maxMessageLength().toInt

    def closePublication(pub: Publication)(using AllowUnsafe): Unit = pub.publication.close()

    def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] =
        // Catch AeronException("Aeron client is closed") thrown when the client has been
        // closed concurrently. Map closed-client to Absent, matching the C shim's NULL signal.
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

    // JVM: no native token to free.
    def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit = ()

    def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean =
        sub.subscription.isConnected()

    def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]] =
        sub.result = Absent
        // A transient Aeron transport error (a registration error surfaced on
        // poll, or a log buffer closed by an interrupt during teardown) can be thrown by poll
        // under load. Catch it and report an empty poll so the shared Retry[TopicBackpressureException]
        // loop absorbs it as backpressure rather than letting it escape as an unhandled panic.
        // Genuinely fatal errors still surface via fatalError to TopicTransportFailedException.
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
        // Read the AtomicReference slot set by the Aeron.Context error handler.
        // The slot always holds a non-empty string when present (the error handler and
        // injectError both derive a non-empty detail; see AeronPlatformTransport and
        // injectError below). Maybe(null) = Absent (no error); Maybe(nonEmptyString) = Present.
        Maybe(errorSlot.get())

    override def injectError(errcode: Int, errmsg: String)(using AllowUnsafe): Unit =
        // Test-inject seam: write the error slot directly (bypasses the JVM handler,
        // appropriate for test purposes where the actual Aeron conductor is not involved).
        // Record a never-empty detail: when errmsg is empty, derive a detail from the
        // errcode so fatalError always surfaces a non-empty string.
        val detail =
            if errmsg.nonEmpty then errmsg
            else s"fatal client error (code $errcode)"
        errorSlot.set(detail)
    end injectError
end JvmAeronTransport
