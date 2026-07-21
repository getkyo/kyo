package kyo.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.internal.client.*

/** Concurrent-invariant stress for [[ConnectionPool]]'s lock-free Vyukov-style MPMC ring (`HostPool`) under contention.
  *
  * Every existing `ConnectionPoolTest` leaf is single-threaded, so a CAS / sequence-number regression (a lost reservation, a double-claimed
  * slot, a torn `release`/`poll`) would slip through. These leaves drive many fibers concurrently over `tryReserve` / `unreserve` / `poll` /
  * `release` and assert invariants that hold under ANY interleaving, so they cannot false-fail on timing yet still catch a conservation or bound
  * violation. They are NOT sleep-to-make-likely: each assertion is a structural property of the final state (and of a continuously-maintained
  * max tracker), true for every possible interleaving.
  *
  * The suite lives in `shared/src/test` so it runs on JVM, Native, and JS. On JVM/Native the fibers run with real parallelism (genuine
  * contention on the ring's CAS loops). On JS fibers are cooperative on one thread, so a single pool op is never preempted mid-operation; the
  * same invariants still hold (trivially there), confirming the pool links and behaves on JS too.
  *
  * Four leaves:
  *   - "connection conservation": every released connection (unique id) ends up polled-out exactly once, discarded by a full-ring release, or
  *     still in the pool at the end. None lost, none duplicated. Disjoint-union equality + no-duplicate-poll catch both loss and double-claim.
  *   - "bounded in-flight": with the ring kept empty, the number of simultaneously-held reservations never exceeds capacity (the bound
  *     `tryReserve` enforces), asserted via a live max tracker around the reserve/unreserve wrap.
  *   - "no permit leak": after a storm of reserve+unreserve pairs, the in-flight count is back to 0, proven behaviorally by reserving exactly
  *     `capacity` times in a row afterwards (and the (capacity+1)-th failing).
  *   - "unreserve correctness under concurrency": reserve to the limit, concurrent unreserves, then reserves succeed again, the live held count
  *     never exceeding capacity throughout.
  */
class ConnectionPoolConcurrencyTest extends BaseHttpTest:

    import AllowUnsafe.embrace.danger

    private val key = HttpAddress.Tcp("contended-host", 80)

    /** Number of concurrent fibers and per-fiber iterations. High enough to exercise the ring's CAS loops on a multicore JVM/Native run while
      * keeping every assertion interleaving-independent.
      */
    private val fibers     = 64
    private val iterations = 200

    /** Build a pool over Int connection ids. `isAlive` is always true (this suite tests the ring and the reservation accounting, not eviction)
      * and `discard` records every discarded id (into a thread-safe queue read after the parallel phase) so conservation can account for
      * full-ring drops. A `ConcurrentLinkedQueue` is used over a converter-backed set so the collector is portable to JS and Native.
      */
    private def mkPool(capacity: Int, discarded: ConcurrentLinkedQueue[Int]): ConnectionPool[HttpAddress, Int] =
        ConnectionPool.init[HttpAddress, Int](
            capacity,
            Duration.Infinity,
            _ => true,
            id => discard(discarded.add(id))
        )

    "ConnectionPool under contention" - {

        /** Connection conservation: every released id ends up polled exactly once, discarded by a full-ring release, or still in the pool.
          *
          * `fibers` producer fibers each release `iterations` connections with globally-unique ids; the same fibers also poll, accumulating
          * whatever they take. After the parallel phase, the test drains the ring with repeated single-threaded polls. The asserted invariants
          * hold under any interleaving:
          *   - no id is polled more than once (a double-claimed slot would surface a duplicate),
          *   - the polled, discarded, and still-in-pool sets are pairwise disjoint,
          *   - their union equals exactly the set of released ids (a lost slot would drop an id from the union; a fabricated id would add one).
          */
        "connection conservation: no connection lost or duplicated under concurrent release/poll" in {
            val capacity  = 16
            val discarded = new ConcurrentLinkedQueue[Int]()
            val pool      = mkPool(capacity, discarded)

            // Each fiber owns a disjoint id range so every released id is globally unique.
            val perFiber = iterations
            Async.fillIndexed(fibers, fibers) { fiber =>
                Sync.defer {
                    val polled = scala.collection.mutable.ListBuffer.empty[Int]
                    var i      = 0
                    while i < perFiber do
                        val id = fiber * perFiber + i
                        pool.release(key, id)
                        // Interleave a poll so producers and consumers contend on the same ring.
                        pool.poll(key).foreach(c => polled += c)
                        i += 1
                    end while
                    polled.toList
                }
            }.map { perFiberPolled =>
                Sync.defer {
                    // Drain whatever remains, single-threaded now that all fibers have finished.
                    val remaining = scala.collection.mutable.ListBuffer.empty[Int]
                    var draining  = true
                    while draining do
                        pool.poll(key) match
                            case Present(c) => remaining += c
                            case Absent     => draining = false
                    end while

                    // Drain the discard queue single-threaded now that all fibers have finished. Iterate (not poll) to avoid unboxing a null
                    // sentinel; `hasNext` guards every `next`, so no null is ever unboxed.
                    val discardList = scala.collection.mutable.ListBuffer.empty[Int]
                    val it          = discarded.iterator()
                    while it.hasNext do discardList += it.next()

                    val allReleased = (0 until fibers * perFiber).toSet
                    val polledAll   = perFiberPolled.flatten ++ remaining.toList
                    val polledSet   = polledAll.toSet
                    val discardSet  = discardList.toSet

                    // No connection polled twice (a double-claimed slot would duplicate one).
                    assert(
                        polledAll.size == polledSet.size,
                        s"polled connections must be unique; ${polledAll.size - polledSet.size} duplicate(s) observed"
                    )
                    // Polled and discarded sets are disjoint: an id is either taken or dropped, never both.
                    assert(
                        polledSet.intersect(discardSet).isEmpty,
                        s"a connection was both polled and discarded: ${polledSet.intersect(discardSet)}"
                    )
                    // Conservation: every released id is accounted for exactly once across polled and discarded; nothing left in the ring
                    // (the drain emptied it) so the union must equal the full released set.
                    val accounted = polledSet ++ discardSet
                    assert(
                        accounted == allReleased,
                        s"conservation violated: ${allReleased.diff(accounted).size} lost, ${accounted.diff(allReleased).size} fabricated"
                    )
                }
            }
        }

        /** Bounded in-flight: simultaneously-held reservations never exceed capacity.
          *
          * The ring is kept empty (no release), so `tryReserve`'s bound reduces to `inFlight < capacity`. Each fiber loops: on a successful
          * reserve it bumps a live held counter (tracking the running max) and immediately unreserves (decrementing). The test's held counter
          * exactly mirrors the pool's `inFlight` because every reserve is paired with one unreserve, so its observed max is the max in-flight the
          * pool ever permitted. That max must never exceed capacity under any interleaving.
          */
        "bounded in-flight: concurrent reservations never exceed capacity" in {
            val capacity = 8
            val pool     = mkPool(capacity, new ConcurrentLinkedQueue[Int]())
            val held     = new AtomicInteger(0)
            val maxHeld  = new AtomicLong(0)

            Async.fill(fibers, fibers) {
                Sync.defer {
                    var i = 0
                    while i < iterations do
                        if pool.tryReserve(key) then
                            val now = held.incrementAndGet()
                            kyo.discard(maxHeld.updateAndGet(prev => math.max(prev, now.toLong)))
                            kyo.discard(held.decrementAndGet())
                            pool.unreserve(key)
                        end if
                        i += 1
                    end while
                }
            }.map { _ =>
                Sync.defer {
                    assert(
                        maxHeld.get() <= capacity.toLong,
                        s"in-flight reservations exceeded capacity: peak ${maxHeld.get()} > $capacity"
                    )
                    // Every reserve was matched by an unreserve, so the live count is back to 0.
                    assert(held.get() == 0, s"held counter did not return to 0: ${held.get()}")
                }
            }
        }

        /** No permit leak: after a storm of matched reserve+unreserve pairs the in-flight count is back to 0.
          *
          * Each fiber does a reserve immediately followed (when it succeeded) by an unreserve, `iterations` times. After the parallel phase the
          * pool must accept exactly `capacity` fresh reservations in a row and then refuse the next one: that is only true if `inFlight` returned
          * to 0 (a single leaked permit would make the post-phase succeed fewer than `capacity` times).
          */
        "no permit leak: in-flight returns to 0 after concurrent reserve/unreserve pairs" in {
            val capacity = 8
            val pool     = mkPool(capacity, new ConcurrentLinkedQueue[Int]())

            Async.fill(fibers, fibers) {
                Sync.defer {
                    var i = 0
                    while i < iterations do
                        if pool.tryReserve(key) then pool.unreserve(key)
                        i += 1
                    end while
                }
            }.map { _ =>
                Sync.defer {
                    // The ring is empty and no permit should be outstanding: exactly `capacity` reservations must succeed, then one must fail.
                    var succeeded = 0
                    var j         = 0
                    while j < capacity do
                        if pool.tryReserve(key) then succeeded += 1
                        j += 1
                    end while
                    val overflow = pool.tryReserve(key)
                    assert(succeeded == capacity, s"expected $capacity reservations after the storm, got $succeeded (leaked permit?)")
                    assert(!overflow, "a reservation succeeded past capacity after the storm (leaked negative permit?)")
                }
            }
        }

        /** Unreserve correctness under concurrency: reserve to the limit, concurrent unreserves, then reserves succeed again, never over the limit.
          *
          * Three sequenced steps, with concurrency inside the contended ones (and no unbounded spin, so JS's cooperative fibers cannot wedge):
          *   1. Saturate the pool to `capacity` (the ring is empty so all succeed); the next reserve must then fail.
          *   2. `capacity` concurrent unreserve fibers each free one permit. Sequenced after step 1 (via `andThen`), so once this phase ends
          *      `inFlight` is back to 0. Concurrent unreserves are exactly the contention this leaf targets.
          *   3. `capacity` concurrent reserve fibers each reserve exactly once, tracking the running held max. Starting from `inFlight == 0` with
          *      exactly `capacity` reservers, `tryReserve`'s bound guarantees all succeed under every interleaving while the held total never
          *      exceeds capacity. The (capacity+1)-th reserve afterward must fail, proving the freed permits were re-reservable but still bounded.
          */
        "unreserve correctness: concurrent unreserve frees permits, re-reserve never exceeds capacity" in {
            val capacity = 16
            val pool     = mkPool(capacity, new ConcurrentLinkedQueue[Int]())
            val held     = new AtomicInteger(0)
            val maxHeld  = new AtomicLong(0)

            Sync.defer {
                // Saturate the pool: capacity reservations, all succeed on the empty ring.
                var k        = 0
                var reserved = 0
                while k < capacity do
                    if pool.tryReserve(key) then reserved += 1
                    k += 1
                end while
                kyo.discard(held.set(reserved))
                assert(reserved == capacity, s"setup: expected to reserve all $capacity, got $reserved")
                assert(!pool.tryReserve(key), "setup: pool should be saturated, no further reservation allowed")
            }.andThen {
                // Concurrent unreserves: each frees one permit. After this phase, inFlight is back to 0.
                Async.fill(capacity, capacity) {
                    Sync.defer {
                        pool.unreserve(key)
                        kyo.discard(held.decrementAndGet())
                    }
                }
            }.andThen {
                // Concurrent re-reserves from an empty in-flight count: exactly `capacity` reservers, each one reserve, so all succeed and the
                // held total never exceeds capacity under any interleaving.
                Async.fill(capacity, capacity) {
                    Sync.defer {
                        if pool.tryReserve(key) then
                            val now = held.incrementAndGet()
                            kyo.discard(maxHeld.updateAndGet(prev => math.max(prev, now.toLong)))
                    }
                }
            }.map { _ =>
                Sync.defer {
                    assert(
                        maxHeld.get() <= capacity.toLong,
                        s"held reservations exceeded capacity during the re-reserve phase: peak ${maxHeld.get()} > $capacity"
                    )
                    // Every freed permit was reclaimed by exactly one racing reserve, so the pool is saturated again.
                    assert(held.get() == capacity, s"expected $capacity permits held after re-reserve, got ${held.get()}")
                    val overflow = pool.tryReserve(key)
                    assert(!overflow, "a reservation succeeded past capacity after the unreserve/reserve race")
                }
            }
        }

    }

end ConnectionPoolConcurrencyTest
