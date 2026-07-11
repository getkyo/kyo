package kyo

import kyo.internal.GroupCommitCoordinator
import kyo.internal.StoreSeam

/** Deterministic behavioral test of [[GroupCommitCoordinator]]: several concurrent flush
  * requesters that arrive while a round is already in flight are coalesced into the next round
  * instead of each starting their own, so the observed `sync()` count stays strictly below the
  * requester count. The leader's own `sync()` call is held open on a [[Latch]] the test controls,
  * so the in-flight round cannot complete (and therefore cannot fall back to idle) until every
  * follower has been confirmed launched; every follower that reaches the coordinator before that
  * point coalesces into the same round regardless of the exact moment it gets scheduled. Never a
  * sleep or a wall-clock wait.
  */
class FileJournalGroupCommitTest extends kyo.test.Test[Any]:

    private def fakeHandle(syncCount: AtomicRef.Unsafe[Int], syncStarted: Channel[Unit], gate: Latch)(using
        AllowUnsafe
    ): StoreSeam.Handle[Async] = new StoreSeam.Handle[Async]:
        def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Async     = Array.emptyByteArray
        def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Async = ()
        def size()(using Frame): Long < Async                                 = 0L
        def truncate(size: Long)(using Frame): Unit < Async                   = ()
        def close()(using Frame): Unit < Async                                = ()
        // Every round's fsync is gated on `gate`. The gate is released exactly once, so only the
        // first (leader) round actually blocks here; every later round's `await` returns
        // immediately, matching a real fsync that never blocks once issued.
        def sync()(using Frame): Unit < Async =
            Sync.Unsafe.defer(syncCount.getAndUpdate(_ + 1)).andThen(
                Abort.run[Closed](syncStarted.put(())).map:
                    case Result.Success(_)      => ()
                    case Result.Failure(closed) => throw new IllegalStateException(s"test channel closed unexpectedly: $closed")
                    case Result.Panic(e)        => throw e
            ).andThen(gate.await)

    "group commit" - {
        "requesters arriving while a round is in flight coalesce into it instead of starting their own" in {
            import AllowUnsafe.embrace.danger
            val coordinator    = new GroupCommitCoordinator
            val key            = "seg-1"
            val followerCount  = 5
            val requesterCount = followerCount + 1
            for
                syncCount   <- Sync.Unsafe.defer(AtomicRef.Unsafe.init(0))
                gate        <- Latch.init(1)
                syncStarted <- Channel.initUnscoped[Unit](requesterCount)
                arrived     <- Channel.initUnscoped[Unit](followerCount)
                order       <- Channel.initUnscoped[String](requesterCount)
                handle      <- Sync.Unsafe.defer(fakeHandle(syncCount, syncStarted, gate))
                leader <- Fiber.initUnscoped(
                    coordinator.requestFlush(key, handle).andThen(order.put("done-leader"))
                )
                // Confirm the leader's round has actually reached its (gated) sync() call before any
                // follower is launched, ruling out a follower ever observing an idle coordinator.
                _ <- syncStarted.take
                followers <- Kyo.fill(followerCount)(
                    Fiber.initUnscoped(
                        arrived.put(()).andThen(coordinator.requestFlush(key, handle)).andThen(order.put("done-follower"))
                    )
                )
                // Confirm every follower has been launched before releasing the leader's round: the
                // round cannot complete (the coordinator cannot fall back to idle) until `gate` is
                // released below, so every follower that reaches the coordinator before this point is
                // guaranteed to coalesce into it, independent of the exact moment it gets scheduled.
                _           <- Kyo.fill(followerCount)(arrived.take)
                _           <- gate.release
                _           <- leader.get
                _           <- Kyo.foreachDiscard(followers)(_.get)
                completions <- Kyo.fill(requesterCount)(order.take)
                finalCount  <- Sync.Unsafe.defer(syncCount.get())
            yield
                assert(finalCount < requesterCount, s"expected coalescing (< $requesterCount rounds), got $finalCount")
                assert(completions.count(_ == "done-leader") == 1)
                assert(completions.count(_ == "done-follower") == followerCount)
            end for
        }
    }
end FileJournalGroupCommitTest
