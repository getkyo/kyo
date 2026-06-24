package kyo.internal

import io.aeron.exceptions.AeronException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.atomic.AtomicReference
import kyo.*
// Unsafe: this test drives JvmAeronTransport's AllowUnsafe transport tier (pollOne /
// injectError / fatalError) directly, so it embraces AllowUnsafe ambiently.
import kyo.AllowUnsafe.embrace.danger

/** A transient Aeron error mid-poll must become an empty poll (the backpressure sentinel) so
  * Topic.stream's Retry[TopicBackpressureException] loop absorbs it instead of letting a panic escape;
  * a fatal error is not absorbed, and fatalError surfaces it as TopicTransportFailedException.
  *
  * JVM-only: io.aeron is a JVM-only dependency; the C-shim backend has no AeronException and reaches
  * the same sentinel via subscriptionPoll's return value.
  */
class JvmAeronTransportTest extends kyo.Test:

    /** pollOne, injectError, and fatalError never touch the `aeron` field, so a null client exercises the
      * real catch without an embedded driver.
      */
    private def transport(): JvmAeronTransport =
        new JvmAeronTransport(null, new AtomicReference[String]())

    /** io.aeron.Subscription is final and unmockable here, so the poll() seam is overridden; the real
      * subscription is never touched and a null is safe.
      */
    private def subscriptionPolling(t: JvmAeronTransport)(pollResult: => Int): t.SubscriptionState =
        new t.SubscriptionState(null):
            override private[kyo] def poll(): Int = pollResult

    "a transient AeronException on poll yields the empty-poll sentinel (Absent), not a panic" in {
        val t   = transport()
        val sub = subscriptionPolling(t)(throw new AeronException("transient transport error under load"))
        assert(t.pollOne(sub) == Absent)
    }

    "a poll interrupted during teardown (ClosedByInterruptException) yields the empty-poll sentinel" in {
        val t   = transport()
        val sub = subscriptionPolling(t)(throw new ClosedByInterruptException)
        assert(t.pollOne(sub) == Absent)
    }

    "after a transient error clears, the next poll yields the buffered message" in {
        val t         = transport()
        var firstPoll = true
        val sub =
            new t.SubscriptionState(null):
                override private[kyo] def poll(): Int =
                    if firstPoll then
                        firstPoll = false
                        throw new AeronException("transient transport error under load")
                    else
                        result = Maybe("payload".getBytes("UTF-8"))
                        1
        assert(t.pollOne(sub) == Absent)
        assert(t.pollOne(sub).map(bytes => new String(bytes, "UTF-8")) == Maybe("payload"))
    }

    "a non-transient RuntimeException is NOT absorbed as backpressure: it propagates" in {
        val t   = transport()
        val sub = subscriptionPolling(t)(throw new RuntimeException("genuinely fatal"))
        interceptThrown[RuntimeException](t.pollOne(sub))
    }

    "a healthy poll returns the buffered fragment without entering the catch" in {
        val t = transport()
        val sub =
            new t.SubscriptionState(null):
                override private[kyo] def poll(): Int =
                    result = Maybe("ok".getBytes("UTF-8"))
                    1
        assert(t.pollOne(sub).map(bytes => new String(bytes, "UTF-8")) == Maybe("ok"))
    }

    "a fatal client error surfaces via fatalError (routes to TopicTransportFailedException, not backpressure)" in {
        val t = new JvmAeronTransport(null, new AtomicReference[String]())
        t.injectError(-1000, "driver timeout")
        assert(t.fatalError == Present("driver timeout"))
    }

    "a fatal client error with an empty message records a non-empty detail derived from the code" in {
        val t = new JvmAeronTransport(null, new AtomicReference[String]())
        t.injectError(-7, "")
        assert(t.fatalError == Present("fatal client error (code -7)"))
    }

    "a healthy transport with no recorded error reports fatalError Absent" in {
        assert(transport().fatalError == Absent)
    }
end JvmAeronTransportTest
