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

    /** Handles this transport has produced and the caller has not closed.
      *
      * `closeAll` drains and closes them before the client goes away, which is what makes closing
      * the client safe while fibers still hold handles. Kyo has no unsafe-tier concurrent set, so
      * this uses ConcurrentHashMap as kyo-core's Exchange and kyo-net's NioTransport do.
      */
    private val livePubs = java.util.concurrent.ConcurrentHashMap.newKeySet[PublicationState]()
    private val liveSubs = java.util.concurrent.ConcurrentHashMap.newKeySet[SubscriptionState]()

    /** Excludes the mapped-memory operations from a concurrent close of the region they write.
      *
      * `offer` copies into a claimed log-buffer region and `pollOne` writes the subscriber position
      * into the CnC mapping, both on the caller's carrier. Closing the client unmaps those regions,
      * and io.aeron's only protection is `closeLingerDurationNs`, which defaults to 0. A bare
      * `isClosed` test is check-then-act, so without this an offer in flight when the client closes
      * writes freed memory and takes a SIGSEGV on the carrier. This is the JVM counterpart of the C
      * shim's close_mutex + closing guard, giving every platform the same safe-sentinel contract.
      *
      * `enter` increments then re-reads `closed`, so an operation that raced past the first test
      * backs out and `close` never waits on it. The drain spin is bounded by one offer or poll.
      */
    final class OpsGate(using AllowUnsafe):
        private val closed   = AtomicBoolean.Unsafe.init(false)
        private val inFlight = AtomicInt.Unsafe.init(0)

        /** True when the caller holds the gate and owes a matching `exit`. */
        def enter()(using AllowUnsafe): Boolean =
            if closed.get() then false
            else
                discard(inFlight.incrementAndGet())
                if closed.get() then
                    discard(inFlight.decrementAndGet())
                    false
                else true
                end if

        def exit()(using AllowUnsafe): Unit = discard(inFlight.decrementAndGet())

        /** Marks the handle closed and waits out in-flight operations. True for the caller that
          * performed the transition, so the underlying close runs exactly once.
          *
          * The wait spins rather than parking: it is bounded by one offer or poll, and it runs on
          * the teardown path, which already blocks on the Aeron client and driver close beneath it.
          */
        def close()(using AllowUnsafe): Boolean =
            if !closed.compareAndSet(false, true) then false
            else
                while inFlight.get() > 0 do Thread.onSpinWait()
                true
    end OpsGate

    /** Pairs a publication with a reusable `BufferClaim`, saving a per-offer allocation on the
      * publish hot path. Exactly one publish call owns a publication and offers sequentially, so
      * the claim is never raced.
      */
    class PublicationState(val publication: AeronPublication)(using AllowUnsafe):
        val claim: BufferClaim = new BufferClaim
        val gate: OpsGate      = new OpsGate

    class SubscriptionState(val subscription: AeronSubscription)(using AllowUnsafe):
        val gate: OpsGate = new OpsGate

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
            else
                val state = new PublicationState(pub)
                discard(livePubs.add(state))
                AeronTransport.AddPoll.Done(state)
            end if
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
        if !pub.gate.enter() then false
        else
            try
                if pub.publication.isClosed() then false
                else pub.publication.isConnected()
            finally pub.gate.exit()

    def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long =
        // Unsafe: writes into an off-heap claimed log-buffer region (tryClaim + commit), or wraps
        // the bytes in an UnsafeBuffer on the fragmented fallback.
        // tryClaim is the zero-copy fast path but rejects lengths above maxPayloadLength (~1376
        // bytes on IPC), so larger messages take offer's copy-and-fragment path. Both throw IAE on
        // an oversize message, which Topic.publish's up-front check makes unreachable outside a
        // race; the catch normalizes that race to the same -6 the FFI offer returns.
        // The gate keeps the claimed region mapped for the duration of the copy and commit; without
        // it a concurrent client close unmaps underneath and the write segfaults the carrier.
        if !pub.gate.enter() then AeronSentinels.Closed
        else
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
            finally pub.gate.exit()
            end try
        end if
    end offer

    def maxMessageLength(pub: Publication)(using AllowUnsafe): Int =
        // io.aeron caches maxMessageLength and would keep returning the live value after the
        // caller's own close; guarding on isClosed() matches the FFI shim, which returns 0 once
        // the bundle is closed.
        if !pub.gate.enter() then 0
        else
            try
                if pub.publication.isClosed() then 0
                else
                    // Long return, but bounded by the 16 MiB ceiling, so the Int cast is safe.
                    pub.publication.maxMessageLength().toInt
            finally pub.gate.exit()

    def closePublication(pub: Publication)(using AllowUnsafe): Unit =
        if pub.gate.close() then
            discard(livePubs.remove(pub))
            pub.publication.close()

    def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] =
        // Closed-client handling as in asyncAddPublication.
        try Present(aeron.asyncAddSubscription(uri, streamId))
        catch case _: AeronException => Absent

    def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription] =
        try
            val sub = aeron.getSubscription(async)
            if sub == null then AeronTransport.AddPoll.Awaiting
            else
                val state = new SubscriptionState(sub)
                discard(liveSubs.add(state))
                AeronTransport.AddPoll.Done(state)
            end if
        catch
            case e: RegistrationException =>
                AeronTransport.AddPoll.Failed(e.errorCodeValue(), e.getMessage)
            case _: AeronException =>
                AeronTransport.AddPoll.Failed(0, "")

    // No native token to free, as in freeAsyncPub.
    def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit = ()

    def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean =
        if !sub.gate.enter() then false
        else
            try sub.subscription.isConnected()
            finally sub.gate.exit()

    def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]] =
        // Gated for the same reason as offer: poll writes the subscriber position into the CnC
        // mapping, which a concurrent client close unmaps.
        if !sub.gate.enter() then Absent
        else
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
                finally sub.gate.exit()
            if fragmentsRead == 0 then Absent else sub.result
        end if
    end pollOne

    def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit =
        if sub.gate.close() then
            discard(liveSubs.remove(sub))
            sub.subscription.close()

    /** Drains and closes every handle the caller still holds.
      *
      * The runtime calls this before closing the client, so by the time the client unmaps its
      * buffers no offer or poll can be in flight against them. Fibers still holding a handle then
      * see the ordinary post-close sentinels rather than freed memory.
      */
    def closeAll()(using AllowUnsafe): Unit =
        livePubs.forEach(pub => closePublication(pub))
        liveSubs.forEach(sub => closeSubscription(sub))

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
