package kyo

import kyo.internal.AeronTransport

/** Whether `Topic.run` releases its media driver when the body fails.
  *
  * `Topic.run` owns an embedded driver for the duration of the body and shuts it down on the way out. A
  * driver that is not shut down leaves its conductor threads running for the rest of the process, which is
  * the one resource in this module whose escape is invisible: the computation returns the caller's error
  * perfectly well and nothing else reports it.
  *
  * The runtime names its directory in the diagnostics registry, so the check is on the entry that appeared
  * while the body ran. Comparing against the entries present beforehand keeps it specific while other
  * suites run in parallel, and asserting the fixture saw its own runtime keeps a vacuous pass from reading
  * as a release.
  */
class TopicRuntimeReleaseTest extends Test:

    private def aeronDirs()(using AllowUnsafe): Set[String] =
        kyo.internal.Diagnostics.dumpAll().linesIterator
            .filter(_.contains("dir=")).map(_.trim).toSet

    "a body that fails with a typed error still releases the driver"
        .pendingUntilFixed(
            "Sync.ensure does not run its finalizer when the guarded body short-circuits via Abort, so Topic.run's "
                + "runtime close is skipped on a typed failure"
        ) in {
        Sync.Unsafe.defer(aeronDirs()).flatMap { before =>
            Sync.Unsafe.defer(new java.util.concurrent.atomic.AtomicReference(Set.empty[String])).flatMap { seen =>
                Abort.run[String] {
                    Topic.run {
                        Sync.Unsafe.defer(seen.set(aeronDirs() -- before))
                            .andThen(Abort.fail("the body fails after the driver is up"))
                    }
                }.map { result =>
                    Sync.Unsafe.defer {
                        val mine  = seen.get()
                        val after = aeronDirs()
                        assert(result.isFailure, s"the fixture must fail typed for this to prove anything, got $result")
                        assert(mine.nonEmpty, "the fixture never saw a live runtime, so a clean dump proves nothing")
                        val survivors = mine.intersect(after)
                        assert(
                            survivors.isEmpty,
                            s"the driver outlived a typed abort: ${survivors.mkString(", ")}"
                        )
                    }
                }
            }
        }
    }

    /** Transport that hands out a publication and then reports a fatal error.
      *
      * The fatal slot is only armed once a publication has been added, so the body under test acquires the
      * resource first and fails afterwards, which is the ordering that decides whether the release runs.
      * Counting the close is the whole point: a leaked publication holds a client-bundle refcount, so it
      * keeps the client alive too, and nothing above the transport reports either.
      */
    final private class FatalAfterAddTransport extends AeronTransport:
        type Publication  = Int
        type Subscription = Int
        type AsyncPub     = Int
        type AsyncSub     = Int

        @volatile private var added = false
        val closedPublications      = new java.util.concurrent.atomic.AtomicInteger(0)
        val closedSubscriptions     = new java.util.concurrent.atomic.AtomicInteger(0)

        def asyncAddPublication(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncPub] = Present(streamId)
        def pollAddPublication(async: AsyncPub)(using AllowUnsafe): AeronTransport.AddPoll[Publication] =
            added = true
            AeronTransport.AddPoll.Done(async)
        def freeAsyncPub(async: AsyncPub)(using AllowUnsafe): Unit                 = ()
        def publicationIsConnected(pub: Publication)(using AllowUnsafe): Boolean   = true
        def offer(pub: Publication, message: Array[Byte])(using AllowUnsafe): Long = 1L
        def maxMessageLength(pub: Publication)(using AllowUnsafe): Int             = 1 << 20
        def closePublication(pub: Publication)(using AllowUnsafe): Unit            = discard(closedPublications.incrementAndGet())
        def asyncAddSubscription(uri: String, streamId: Int)(using AllowUnsafe): Maybe[AsyncSub] = Present(streamId)
        def pollAddSubscription(async: AsyncSub)(using AllowUnsafe): AeronTransport.AddPoll[Subscription] =
            added = true
            AeronTransport.AddPoll.Done(async)
        def freeAsyncSub(async: AsyncSub)(using AllowUnsafe): Unit                 = ()
        def subscriptionIsConnected(sub: Subscription)(using AllowUnsafe): Boolean = false
        def pollOne(sub: Subscription)(using AllowUnsafe): Maybe[Array[Byte]]      = Absent
        def closeSubscription(sub: Subscription)(using AllowUnsafe): Unit          = discard(closedSubscriptions.incrementAndGet())

        def fatalError(using AllowUnsafe): Maybe[String] =
            if added then Present("injected after the publication was added") else Absent
    end FatalAfterAddTransport

    "a publish that fails with a typed error still closes its publication"
        .pendingUntilFixed(
            "Sync.ensure does not run its finalizer when the guarded body short-circuits via Abort, so the "
                + "publication close is skipped when publish aborts typed"
        ) in {
        val transport = new FatalAfterAddTransport
        Abort.run[TopicTransportException] {
            Topic.runWith(transport) {
                Topic.publish[Int]("aeron:ipc", streamId = Present(4242))(Stream.init(Seq(1, 2, 3)))
            }
        }.map { result =>
            assert(result.isFailure, s"the fixture must fail typed for this to prove anything, got $result")
            assert(
                transport.closedPublications.get() == 1,
                s"the publication was not closed: closePublication ran ${transport.closedPublications.get()} time(s)"
            )
        }
    }

    "a stream that fails with a typed error still closes its subscription"
        .pendingUntilFixed(
            "Sync.ensure does not run its finalizer when the guarded body short-circuits via Abort, so the "
                + "subscription close is skipped when stream aborts typed"
        ) in {
        val transport = new FatalAfterAddTransport
        Abort.run[TopicTransportException] {
            Topic.runWith(transport) {
                Topic.stream[Int]("aeron:ipc", streamId = Present(4343)).take(1).run
            }
        }.map { result =>
            assert(result.isFailure, s"the fixture must fail typed for this to prove anything, got $result")
            assert(
                transport.closedSubscriptions.get() == 1,
                s"the subscription was not closed: closeSubscription ran ${transport.closedSubscriptions.get()} time(s)"
            )
        }
    }

end TopicRuntimeReleaseTest
