package kyo.internal

import io.aeron.exceptions.AeronException
import java.nio.channels.ClosedByInterruptException
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

    val ipcUri          = "aeron:ipc"
    val addRaceStreamId = 120

    /** pollOne, injectError, and fatalError never touch the `aeron` field, so a null client exercises the
      * real catch without an embedded driver.
      */
    private def transport(): JvmAeronTransport =
        new JvmAeronTransport(null, AtomicRef.Unsafe.init(Absent: Maybe[String]))

    private def launchDriver(dir: String)(using AllowUnsafe): io.aeron.driver.MediaDriver =
        io.aeron.driver.MediaDriver.launchEmbedded(
            new io.aeron.driver.MediaDriver.Context().aeronDirectoryName(dir).dirDeleteOnStart(true)
        )

    private def connectClient(dir: String)(using AllowUnsafe): io.aeron.Aeron =
        io.aeron.Aeron.connect(new io.aeron.Aeron.Context().aeronDirectoryName(dir))

    /** Whether the gate still admits an operation, restoring its count when it does. A handle the
      * transport has closed refuses every mapped-memory operation through this gate, so it is what
      * distinguishes a handle handed back live from one that closed itself.
      */
    private def holds(gate: JvmAeronTransport#OpsGate)(using AllowUnsafe): Boolean =
        if gate.enter() then
            gate.exit()
            true
        else false

    /** Steps an async add to Done, suspending between attempts so the conductor can advance. */
    private def pollAddUntilDone[H](kind: String)(step: AllowUnsafe ?=> AeronTransport.AddPoll[H])(using
        Frame,
        kyo.test.AssertScope
    ): H < Async =
        Loop.indexed { i =>
            Sync.Unsafe.defer(step).map {
                case AeronTransport.AddPoll.Done(handle) => Loop.done(handle)
                case AeronTransport.AddPoll.Failed(code, detail) =>
                    fail(s"the $kind registration failed: code=$code detail=$detail")
                case _ =>
                    if i >= 5000 then fail(s"the $kind registration never confirmed")
                    else Async.sleep(1.millis).andThen(Loop.continue)
            }
        }

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
        val t = new JvmAeronTransport(null, AtomicRef.Unsafe.init(Absent: Maybe[String]))
        t.injectError(-1000, "driver timeout")
        assert(t.fatalError == Present("driver timeout"))
    }

    "a fatal client error with an empty message records a non-empty detail derived from the code" in {
        val t = new JvmAeronTransport(null, AtomicRef.Unsafe.init(Absent: Maybe[String]))
        t.injectError(-7, "")
        assert(t.fatalError == Present("fatal client error (code -7)"))
    }

    "a healthy transport with no recorded error reports fatalError Absent" in {
        assert(transport().fatalError == Absent)
    }

    // closeAll sweeps livePubs/liveSubs, whose ConcurrentHashMap iteration is weakly consistent and may
    // miss a handle published alongside the sweep. Calling closeAll while the registration is still in
    // flight puts the poll on the far side of the sweep deterministically, which is the ordering a
    // concurrent add reaches by luck. The client stays open here so the check reads a gate rather than
    // the freed memory the same handle would face once the runtime closes the client behind closeAll.
    "a registration that completes after closeAll is handed back closed, not live" in {
        for
            dir    <- Path.tempDir("kyo-aeron-add-vs-close")
            driver <- Sync.Unsafe.defer(launchDriver(dir.unsafe.show))
            client <- Sync.Unsafe.defer(connectClient(dir.unsafe.show))
            t = new JvmAeronTransport(client, AtomicRef.Unsafe.init(Absent: Maybe[String]))
            asyncPub <- Sync.Unsafe.defer(t.asyncAddPublication(ipcUri, addRaceStreamId))
            asyncSub <- Sync.Unsafe.defer(t.asyncAddSubscription(ipcUri, addRaceStreamId))
            _ = assert(asyncPub.isDefined && asyncSub.isDefined, "the driver rejected the registrations before the race began")
            _       <- Sync.Unsafe.defer(t.closeAll())
            pub     <- pollAddUntilDone("publication")(t.pollAddPublication(asyncPub.get))
            sub     <- pollAddUntilDone("subscription")(t.pollAddSubscription(asyncSub.get))
            pubLive <- Sync.Unsafe.defer(holds(pub.gate))
            subLive <- Sync.Unsafe.defer(holds(sub.gate))
            _ <- Sync.Unsafe.defer {
                t.closePublication(pub)
                t.closeSubscription(sub)
                client.close()
                driver.close()
            }
            _ <- dir.removeAll
        yield
            assert(!pubLive, "closeAll handed back a live publication, which the client close then unmaps under")
            assert(!subLive, "closeAll handed back a live subscription, which the client close then unmaps under")
    }

end JvmAeronTransportTest
