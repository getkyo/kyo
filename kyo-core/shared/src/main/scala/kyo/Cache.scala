package kyo

import Cache.Unsafe.internal.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.Maybe.Absent
import scala.annotation.tailrec

/** A bounded, thread-safe cache with automatic eviction and optional time-based expiration.
  *
  * Cache acts as a fixed-size key-value store that automatically evicts entries when it reaches capacity. All storage is in pre-allocated
  * flat arrays, so get, add, and remove perform zero allocations on the hot path besides optimized effect suspensions. It uses a CLOCK
  * eviction policy (an approximation of LRU) that gives each entry one chance to survive eviction if it was accessed since the last sweep.
  * This provides good hit rates with minimal bookkeeping overhead compared to true LRU.
  *
  * The primary use cases are:
  *   - Direct key-value caching via `get`, `add`, and `getOrElse`
  *   - Function memoization via `Cache.memo`, which creates a self-contained cache for a function's results and handles concurrent callers
  *     by deduplicating in-flight computations so only one fiber computes while others wait on the result
  *
  * Expiration policies can be configured independently:
  *   - `expireAfterAccess`: evicts entries not read within a time window
  *   - `expireAfterWrite`: evicts entries after a fixed time since insertion, regardless of access
  *
  * IMPORTANT: The actual table capacity is rounded up to the next power of two above `maxSize * 5/4` to maintain probe chain efficiency.
  * Maximum `maxSize` is 1,048,576 entries.
  *
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  *
  * @see
  *   [[Cache.Unsafe]] for the low-level API
  */
opaque type Cache[K, V] = Cache.Unsafe[K, V]

object Cache:

    /** Maximum allowed maxSize. The table allocates the next power of two above maxSize * 5/4, typically 1.3x-2x maxSize. Each slot uses
      * 16-24 bytes across three arrays (keys, values, timestamps), so at maximum capacity the table is ~32-48MB.
      */
    private val MaxMaxSize = Math.pow(2, 20).toInt

    extension [K, V](self: Cache[K, V])

        /** Looks up a key and returns its value if present and not expired. */
        def get(key: K)(using Frame): Maybe[V] < Sync =
            Sync.Unsafe.defer(self.get(key))

        /** Inserts value for key, or returns the existing value if already present. */
        def add(key: K, value: V)(using Frame): V < Sync =
            Sync.Unsafe.defer(self.add(key, value))

        /** Returns the existing value if present, or evaluates `value` and inserts it. */
        inline def getOrElse(key: K, inline value: => V)(using Frame): V < Sync =
            Sync.Unsafe.defer(self.getOrElse(key, value))

        /** Removes an entry by marking it as a tombstone. */
        def remove(key: K)(using Frame): Unit < Sync =
            Sync.Unsafe.defer(self.remove(key))

        /** Returns the underlying unsafe cache for low-level access. */
        def unsafe: Unsafe[K, V] = self

    end extension

    /** Creates a new typed Cache.
      *
      * @param maxSize
      *   Maximum number of entries (must be between 1 and 1,048,576)
      * @param expireAfterAccess
      *   Duration after last access before an entry expires (Duration.Zero = no expiration)
      * @param expireAfterWrite
      *   Duration after creation/update before an entry expires (Duration.Zero = no expiration)
      */
    def init[K, V](
        maxSize: Int,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero
    )(using Frame): Cache[K, V] < Sync =
        Sync.Unsafe.defer(Unsafe.init[K, V](maxSize, expireAfterAccess, expireAfterWrite))

    /** Creates a new typed Cache and applies the given function to it. */
    inline def initWith[K, V, A, S](
        maxSize: Int,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero
    )(inline f: Cache[K, V] => A < S)(using inline frame: Frame): A < (S & Sync) =
        Sync.Unsafe.defer(f(Unsafe.init[K, V](maxSize, expireAfterAccess, expireAfterWrite)))

    /** Creates a memoized version of a function with its own internal cache.
      *
      * @param maxSize
      *   Maximum number of cached results
      * @param expireAfterAccess
      *   Duration after last access before an entry expires (Duration.Zero = no expiration)
      * @param expireAfterWrite
      *   Duration after creation/update before an entry expires (Duration.Zero = no expiration)
      * @param f
      *   The function to memoize
      * @return
      *   A memoized version of the function
      */
    def memo[A](
        maxSize: Int,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero
    )[B, S](
        f: A => B < S
    )(using Frame): (A => B < (Async & S)) < Sync =
        Sync.Unsafe.defer {
            val store = Unsafe.init[A, Promise[B, Any]](maxSize, expireAfterAccess, expireAfterWrite)
            (v: A) =>
                Sync.Unsafe.defer {
                    val promise       = Promise.Unsafe.init[B, Any]().safe
                    val cachedPromise = store.getOrElse(v, promise)
                    // Identity check: if cachedPromise is our promise, we won the race and must compute.
                    // Otherwise, another caller already inserted their Promise — wait on it.
                    if (cachedPromise.asInstanceOf[AnyRef]) eq (promise.asInstanceOf[AnyRef]) then
                        // Won the race — compute the value
                        Sync.Unsafe.ensure {
                            // On interruption, remove from cache so next caller retries
                            if promise.unsafe.interrupt() then
                                store.remove(v)
                        } {
                            Abort.run[Throwable](f(v)).map {
                                case Result.Success(v) =>
                                    // Success — complete promise, keep in cache
                                    Sync.Unsafe.defer {
                                        promise.unsafe.completeDiscard(Result.Success(v))
                                        v
                                    }
                                case r: Result.Error[Nothing] @unchecked =>
                                    // Failure — remove from cache, propagate error
                                    Sync.Unsafe.defer {
                                        store.remove(v)
                                        promise.unsafe.completeDiscard(r)
                                        Abort.get(r)
                                    }
                            }
                        }
                    else
                        // Lost the race — wait on existing promise
                        cachedPromise.get
                    end if
                }
        }

    /** Creates a memoized version of a two-argument function. */
    def memo2[T1, T2](
        maxSize: Int,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero
    )[B, S](
        f: (T1, T2) => B < S
    )(using Frame): ((T1, T2) => B < (Async & S)) < Sync =
        memo[(T1, T2)](maxSize, expireAfterAccess, expireAfterWrite)(f.tupled).map { m => (t1: T1, t2: T2) => m((t1, t2)) }

    /** Creates a memoized version of a three-argument function. */
    def memo3[T1, T2, T3](
        maxSize: Int,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero
    )[B, S](
        f: (T1, T2, T3) => B < S
    )(using Frame): ((T1, T2, T3) => B < (Async & S)) < Sync =
        memo[(T1, T2, T3)](maxSize, expireAfterAccess, expireAfterWrite)(f.tupled).map { m => (t1: T1, t2: T2, t3: T3) => m((t1, t2, t3)) }

    /** Creates a memoized version of a four-argument function. */
    def memo4[T1, T2, T3, T4](
        maxSize: Int,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero
    )[B, S](
        f: (T1, T2, T3, T4) => B < S
    )(using Frame): ((T1, T2, T3, T4) => B < (Async & S)) < Sync =
        memo[(T1, T2, T3, T4)](maxSize, expireAfterAccess, expireAfterWrite)(f.tupled).map { m => (t1: T1, t2: T2, t3: T3, t4: T4) =>
            m((t1, t2, t3, t4))
        }

    /** Diagnostic stats. */
    case class Stats(
        entries: Int,
        ghosts: Int,
        size: Int,
        locked: Int,
        orphanKeys: Int,
        orphanValues: Int,
        tombstones: Int
    )

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details.
      *
      * Lock-free cache implemented as a struct-of-arrays hash table with open addressing (linear probing). Three parallel arrays (keys,
      * values, and packed timestamps) avoid per-entry object allocation. Zero allocations on the hot path.
      *
      * Thread safety relies on the `values` array (AtomicReferenceArray). All structural changes go through `values` via CAS or volatile
      * set, and the `keys` array uses plain access. Because `values` is volatile, any write to `keys` that happens before a `values.set` is
      * visible to any thread that later reads that slot via `values.get`. The `states` array uses AtomicLongArray to ensure cross-thread
      * visibility of access timestamps and eviction flags on all platforms (including Scala Native, where plain writes lack happens-before
      * guarantees). Each slot transitions through four states:
      *
      *   - empty → locked (CAS)
      *     - Claims the slot for insertion. No other fields are read or written yet.
      *   - locked → present (volatile set)
      *     - The inserter writes `keys(slot)` and `states(slot)` first, then publishes the value via `values.set`. Since the volatile set
      *       flushes all prior writes, any thread that reads the present value is guaranteed to see the key and initial state. Readers that
      *       observe locked spin until the value is published.
      *   - present → present (no transition on `values`)
      *     - `get` updates `states(slot)` (access time, accessed flag) and `evict` clears the accessed flag, both via atomic writes on the
      *       `states` AtomicLongArray. These are not CAS-guarded: concurrent updates may overwrite each other, but this only affects
      *       eviction quality (an entry might survive one extra sweep or be evicted one sweep early) without compromising structural
      *       integrity. Expiration checks are similarly best-effort at centisecond granularity.
      *   - present → locked → present (CAS + volatile set)
      *     - When `add` finds a matching key that has expired, it reclaims the slot in-place. The CAS to locked prevents concurrent access,
      *       then the new state and value are written and published. `size` is unchanged since the slot was already occupied.
      *   - present → tombstone (CAS)
      *     - Deletion and eviction. The CAS ensures exactly one thread performs the removal. `keys(slot)` is intentionally not cleared
      *       after the CAS: a concurrent `add` may have already reclaimed the tombstone and written a new key, so clearing would clobber
      *       the new entry. Stale keys in tombstone slots are harmless since probes skip tombstones and `add` overwrites the key on
      *       reclamation. For the same reason, `evict` reads the key before its CAS — after tombstoning, the slot may already belong to a
      *       different entry. `size` is decremented atomically but may be momentarily stale, which is safe since eviction is a capacity
      *       hint. The CLOCK hand (`clockHand`) advances via `getAndIncrement`, giving each concurrent evictor a distinct slot to examine.
      *   - tombstone → locked (CAS)
      *     - Insertion reclaims a tombstone. See "Tombstone reclamation" below.
      *
      * Tombstone reclamation
      *
      * When `add` probes past a tombstone, it remembers the first one and inserts there after confirming the key is absent. Two threads
      * inserting the same key can each find a different tombstone and CAS it to locked simultaneously (different slots, no contention).
      * Without a guard, both would complete their insert, creating duplicate entries. The re-scan after locking prevents this: the inserter
      * walks the probe chain again and rolls back to tombstone if it finds the key already present or another locked slot (conservatively
      * assumed to be the same key). This also handles the case where a concurrent `remove` turns a present slot into a new tombstone
      * between the initial probe and the re-scan, which could otherwise open a second insertion point for the same key.
      *
      * Slot-recycle guard
      *
      * `get` and `add` read the value via `values.get(slot)` then check the key via `keyMatch`. These two reads are not atomic: between
      * them, a concurrent `remove` + `add` can recycle the slot with a different key and value. Without protection, `keyMatch` would match
      * the new key while the caller uses the old value. After `keyMatch` succeeds, both methods re-read `values.get(slot)` and verify the
      * value reference is unchanged. On mismatch, the probe retries from the same position rather than restarting the entire operation,
      * which avoids livelock under heavy contention.
      *
      * Eviction
      *
      * When `add` pushes `size` past `maxSize`, it advances a shared hand (`clockHand`) sequentially through the table via
      * `getAndIncrement`. Each slot has an accessed flag in its state. On the first pass over a slot, the hand clears the slot's flag and
      * moves on (second chance). On the next rotation, a slot that was accessed in the meantime will have its flag set again by `get` and
      * survive, while a slot that remains unaccessed is evicted. This approximates LRU without maintaining a sorted access-order list:
      * frequently accessed slots keep getting their flag reset and always survive, while the least recently used slots are the first to be
      * evicted.
      */
    final class Unsafe[K, V] private[Cache] (
        clock: Clock.Unsafe,
        epoch: Long,
        maxSize: Int,
        expireAfterAccessCentis: Int,
        expireAfterWriteCentis: Int,
        onEvict: (K, V) => Unit,
        onExpire: (K, V) => Unit,
        onRemove: (K, V) => Unit
    ):
        import Unsafe.internal.*

        // Hot fields: accessed on get/getOrElse calls

        private val mask: Int =
            val target = (maxSize * 5 / 4).max(16)
            (Integer.highestOneBit(target - 1) << 1) - 1

        private val values =
            new AtomicReferenceArray[Slot[V]](mask + 1)

        private val keys: Array[Maybe[K]] =
            val a = new Array[AnyRef](mask + 1)
            java.util.Arrays.fill(a, Absent)
            a.asInstanceOf[Array[Maybe[K]]]
        end keys

        private val states = State.AtomicArray(mask + 1)

        // Cold fields: accessed only on insert/eviction

        private val size      = new AtomicInteger(0)
        private val clockHand = new AtomicInteger(0)

        /** Looks up a key and returns its value if present and not expired. */
        def get(key: K)(using AllowUnsafe): Maybe[V] =
            val now = nowCentis()
            @tailrec def loop(slot: Int, dist: Int): Maybe[V] =
                if dist > mask then
                    // Probed entire table
                    Maybe.empty
                else
                    val s = values.get(slot)
                    if s.isLocked then
                        // Spin — slot being written
                        loop(slot, dist)
                    else if s.isPresent then
                        if keyMatch(slot, key) then
                            // Re-read to guard against slot recycle (remove+add between reading value and key)
                            val s2 = values.get(slot)
                            if !s2.isPresent || (s2.value.asInstanceOf[AnyRef] ne s.value.asInstanceOf[AnyRef]) then
                                // Slot was recycled — re-read from same position
                                loop(slot, dist)
                            else if !isExpired(slot, now) then
                                // Cache hit
                                states.set(slot, states.get(slot).withAccess(now))
                                Maybe(s.value)
                            else
                                // Expired
                                Maybe.empty
                            end if
                        else
                            // Collision — keep probing
                            loop(next(slot), dist + 1)
                        end if
                    else if s.isTombstone then
                        // Skip deleted slot
                        loop(next(slot), dist + 1)
                    else
                        // Empty slot — key absent
                        Maybe.empty
                    end if
            loop(spread(key.hashCode()) & mask, 0)
        end get

        /** Returns the existing value if present, or evaluates `value` and inserts it. */
        inline def getOrElse(key: K, inline value: => V)(using AllowUnsafe): V =
            get(key).getOrElse(add(key, value))

        /** Inserts value for key, or returns the existing value if already present. */
        def add(key: K, value: V)(using AllowUnsafe): V =
            val now = nowCentis()

            @tailrec def loop(slot: Int, dist: Int, firstTomb: Int): V =
                if dist > mask then
                    // Table full — use saved tombstone or force eviction
                    if firstTomb >= 0 then
                        claim(firstTomb, key, value, now)
                    else
                        evict()
                        add(key, value)
                    end if
                else
                    val s = values.get(slot)
                    if s.isLocked then
                        // Slot being written — restart
                        add(key, value)
                    else if s.isPresent then
                        if keyMatch(slot, key) then
                            // Re-read to guard against slot recycle (remove+add between reading value and key)
                            val s2 = values.get(slot)
                            if !s2.isPresent || (s2.value.asInstanceOf[AnyRef] ne s.value.asInstanceOf[AnyRef]) then
                                // Slot was recycled — re-read from same position (not full restart, to avoid livelock)
                                loop(slot, dist, firstTomb)
                            else if !isExpired(slot, now) then
                                // Key exists and is valid — return existing
                                states.set(slot, states.get(slot).withAccess(now))
                                s.value
                            else if values.compareAndSet(slot, s, Slot.locked) then
                                // Expired — reclaim in-place
                                states.set(slot, State(now))
                                values.set(slot, Slot(value))
                                onExpire(key, s.value)
                                value
                            else
                                // CAS failed — retry
                                add(key, value)
                            end if
                        else
                            // Different key — keep probing
                            loop(next(slot), dist + 1, firstTomb)
                        end if
                    else if s.isTombstone then
                        // Remember first tombstone for potential reuse
                        loop(next(slot), dist + 1, if firstTomb < 0 then slot else firstTomb)
                    else
                        // Empty — key absent, insert at first tombstone or here
                        if firstTomb >= 0 then claim(firstTomb, key, value, now)
                        else claim(slot, key, value, now)
                    end if

            def claim(target: Int, key: K, value: V, now: Int): V =
                val current = values.get(target)
                if (current.isEmpty || current.isTombstone) &&
                    values.compareAndSet(target, current, Slot.locked)
                then
                    // Re-scan chain to detect duplicate key inserted concurrently
                    @tailrec def isDuplicate(slot: Int, dist: Int): Boolean =
                        dist <= mask && {
                            if slot == target then
                                // Skip our locked slot
                                isDuplicate(next(slot), dist + 1)
                            else
                                val s = values.get(slot)
                                // Empty = end of chain
                                !s.isEmpty && {
                                    // Present + matching key = confirmed duplicate
                                    (s.isPresent && keyMatch(slot, key)) ||
                                    // Locked or tombstone or different key = keep probing
                                    isDuplicate(next(slot), dist + 1)
                                }
                        }

                    if isDuplicate(spread(key.hashCode()) & mask, 0) then
                        // Rollback — restore as tombstone to preserve chain
                        values.set(target, Slot.tombstone)
                        add(key, value)
                    else
                        // Commit the new entry
                        keys(target) = Maybe(key)
                        states.set(target, State(now))
                        values.set(target, Slot(value))
                        discard(size.incrementAndGet())
                        if size.get() > maxSize then evict()
                        value
                    end if
                else
                    // CAS failed — retry
                    add(key, value)
                end if
            end claim

            loop(spread(key.hashCode()) & mask, 0, -1)
        end add

        /** Removes an entry by marking it as a tombstone. */

        def remove(key: K)(using AllowUnsafe): Unit =

            @tailrec def loop(slot: Int, dist: Int): Unit =
                if dist <= mask then
                    val s = values.get(slot)
                    if s.isLocked then
                        // Slot being written — restart
                        remove(key)
                    else if s.isPresent then
                        if keyMatch(slot, key) then
                            // Found — tombstone it
                            if values.compareAndSet(slot, s, Slot.tombstone) then
                                // Don't clear keys(slot) — a concurrent add may have already
                                // reclaimed the tombstone and written a new key here.
                                discard(size.decrementAndGet())
                                onRemove(key, s.value)
                            end if
                        else
                            // Different key — keep probing
                            loop(next(slot), dist + 1)
                        end if
                    else if s.isTombstone then
                        // Skip deleted slot
                        loop(next(slot), dist + 1)
                    // else: empty slot — key absent, done
                    end if

            loop(spread(key.hashCode()) & mask, 0)
        end remove

        /** Diagnostic stats. Scans all slots for entries, ghosts, and consistency violations. */
        def stats(using AllowUnsafe): Cache.Stats =

            // Checks if slot i is reachable by probing from its key's home slot
            def probes(slot: Int): Boolean =
                val v = values.get(slot)
                v.isTombstone || v.isLocked || (v.isPresent && keys(slot).isDefined)

            @tailrec def reachable(i: Int, slot: Int): Boolean =
                slot == i || (probes(slot) && reachable(i, next(slot)))

            @tailrec def scan(
                i: Int,
                entries: Int,
                ghosts: Int,
                locked: Int,
                orphanKeys: Int,
                orphanValues: Int,
                tombstones: Int
            ): Cache.Stats =
                if i > mask then
                    Cache.Stats(entries, ghosts, size.get(), locked, orphanKeys, orphanValues, tombstones)
                else
                    val v = values.get(i)
                    val k = keys(i)
                    if v.isLocked then
                        scan(i + 1, entries, ghosts, locked + 1, orphanKeys, orphanValues, tombstones)
                    else if v.isTombstone then
                        scan(i + 1, entries, ghosts, locked, orphanKeys, orphanValues, tombstones + 1)
                    else if v.isPresent then
                        if k.isEmpty then
                            // Value without key — orphan value
                            scan(i + 1, entries + 1, ghosts, locked, orphanKeys, orphanValues + 1, tombstones)
                        else
                            // Check if entry is reachable from its home slot
                            val isGhost = !k.map(key => reachable(i, spread(key.hashCode()) & mask)).getOrElse(false)
                            scan(i + 1, entries + 1, if isGhost then ghosts + 1 else ghosts, locked, orphanKeys, orphanValues, tombstones)
                        end if
                    else if k.isDefined then
                        // Key without value — orphan key
                        scan(i + 1, entries, ghosts, locked, orphanKeys + 1, orphanValues, tombstones)
                    else
                        // Empty slot
                        scan(i + 1, entries, ghosts, locked, orphanKeys, orphanValues, tombstones)
                    end if

            scan(0, 0, 0, 0, 0, 0, 0)
        end stats

        /** Returns all present, non-expired entries as a Dict. For testing and diagnostics only. */
        def contents(using AllowUnsafe): Dict[K, V] =
            val now = nowCentis()
            val b   = DictBuilder.init[K, V]
            @tailrec def loop(i: Int): Unit =
                if i <= mask then
                    val s = values.get(i)
                    if s.isLocked then
                        // Spin — slot being written
                        loop(i)
                    else if s.isPresent && !isExpired(i, now) then
                        val k = keys(i)
                        // Re-read to verify slot wasn't recycled between reading value and key
                        if values.get(i).value.asInstanceOf[AnyRef] eq s.value.asInstanceOf[AnyRef] then
                            k.foreach(key => b.add(key, s.value))
                        loop(i + 1)
                    else
                        loop(i + 1)
                    end if
            loop(0)
            b.result()
        end contents

        /** Wraps this unsafe cache in the safe API. */
        def safe: Cache[K, V] = this

        private def isExpired(slot: Int, now: Int): Boolean =
            val st = states.get(slot)
            (expireAfterWriteCentis > 0 && (now - st.writeTime) > expireAfterWriteCentis) ||
            (expireAfterAccessCentis > 0 && (now - st.accessTime) > expireAfterAccessCentis)
        end isExpired

        /** CLOCK second-chance eviction. Sweeps via a rotating hand, giving each entry one chance to survive if accessed since the last
          * sweep.
          */
        private def evict(): Unit =
            val max = (mask + 1) * 2

            @tailrec def loop(n: Int): Unit =
                if n < max then
                    val slot = clockHand.getAndIncrement() & mask
                    val s    = values.get(slot)
                    if s.isPresent then
                        val st = states.get(slot)
                        if st.accessed then
                            // Recently accessed — clear flag, give second chance
                            states.set(slot, st.clearAccessed)
                            loop(n + 1)
                        else
                            // Read key before CAS — after tombstoning, a concurrent add
                            // may reclaim the slot and overwrite the key.
                            val k = keys(slot)
                            if values.compareAndSet(slot, s, Slot.tombstone) then
                                // Don't clear keys(slot) — same reason as remove.
                                discard(size.decrementAndGet())
                                k.foreach(onEvict(_, s.value))
                            else
                                // CAS failed — another thread modified, keep looking
                                loop(n + 1)
                            end if
                        end if
                    else
                        // Empty, tombstone, or locked — skip
                        loop(n + 1)
                    end if
                end if
            end loop

            loop(0)
        end evict

        private def nowCentis()(using AllowUnsafe): Int =
            ((clock.nowMonotonic().toNanos - epoch) / 10_000_000L).toInt

        // MurmurHash3 fmix32 — scrambles low bits of hashCode to prevent clustering in open-addressing probes.
        private def spread(h: Int): Int =
            val x1 = h ^ (h >>> 16)
            val x2 = x1 * 0x85ebca6b
            val x3 = x2 ^ (x2 >>> 13)
            val x4 = x3 * 0xc2b2ae35
            x4 ^ (x4 >>> 16)
        end spread

        private inline def next(slot: Int): Int = (slot + 1) & mask

        private inline def keyMatch(slot: Int, key: K): Boolean =
            keys(slot).exists(k => (k.asInstanceOf[AnyRef] eq key.asInstanceOf[AnyRef]) || k.equals(key))

    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        def init[K, V](
            maxSize: Int,
            expireAfterAccess: Duration = Duration.Zero,
            expireAfterWrite: Duration = Duration.Zero,
            clock: Clock.Unsafe = Clock.live.unsafe,
            onEvict: (K, V) => Unit = ((_: K, _: V) => ()),
            onExpire: (K, V) => Unit = ((_: K, _: V) => ()),
            onRemove: (K, V) => Unit = ((_: K, _: V) => ())
        )(using AllowUnsafe): Unsafe[K, V] =
            require(maxSize > 0, s"maxSize must be > 0, got $maxSize")
            require(maxSize <= MaxMaxSize, s"maxSize must be <= $MaxMaxSize, got $maxSize")
            val epoch = clock.nowMonotonic().toNanos
            new Unsafe[K, V](
                clock,
                epoch,
                maxSize,
                toCentis(expireAfterAccess),
                toCentis(expireAfterWrite),
                onEvict,
                onExpire,
                onRemove
            )
        end init

        private[kyo] object internal:
            def toCentis(duration: Duration): Int =
                if duration > Duration.Zero then (duration.toMillis / 10).toInt.max(1) else -1

            opaque type Slot[+V] = V | AnyRef | Null

            object Slot:
                val empty: Slot[Nothing]     = null
                val locked: Slot[Nothing]    = new AnyRef
                val tombstone: Slot[Nothing] = new AnyRef

                inline def apply[V](v: V): Slot[V] = v

                extension [V](s: Slot[V])
                    private inline def ref: AnyRef  = s.asInstanceOf[AnyRef]
                    inline def isEmpty: Boolean     = ref eq empty.asInstanceOf[AnyRef]
                    inline def isLocked: Boolean    = ref eq locked.asInstanceOf[AnyRef]
                    inline def isTombstone: Boolean = ref eq tombstone.asInstanceOf[AnyRef]
                    inline def isPresent: Boolean   = !isEmpty && !isLocked && !isTombstone
                    inline def value: V             = s.asInstanceOf[V]
                end extension
            end Slot

            opaque type State = Long

            object State:
                inline def apply(nowCentis: Int): State =
                    Long.MinValue | (nowCentis.toLong << 31) | (nowCentis.toLong & 0x7fffffffL)

                extension (s: State)
                    inline def writeTime: Int       = ((s >>> 31) & 0xffffffffL).toInt
                    inline def accessTime: Int      = (s & 0x7fffffffL).toInt
                    inline def accessed: Boolean    = s < 0
                    inline def clearAccessed: State = s & Long.MaxValue
                    inline def withAccess(nowCentis: Int): State =
                        (s & 0xffffffff80000000L) | Long.MinValue | (nowCentis.toLong & 0x7fffffffL)
                end extension

                opaque type AtomicArray = AtomicLongArray

                object AtomicArray:
                    def apply(size: Int): AtomicArray = new AtomicLongArray(size)
                    extension (a: AtomicArray)
                        inline def get(i: Int): State          = a.get(i)
                        inline def set(i: Int, s: State): Unit = a.set(i, s)
                    end extension
                end AtomicArray

            end State
        end internal
    end Unsafe
end Cache
