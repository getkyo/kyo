package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe.embrace.danger
import kyo.Cache.Unsafe.internal.*
import kyo.internal.Platform
import scala.util.Random

class CacheTest extends Test:

    override def timeout = if Platform.isNative then 30.seconds else super.timeout

    // Test key with controlled hashCode
    class Key(val id: Int, val hash: Int) derives CanEqual:
        override def hashCode(): Int = hash
        override def equals(other: Any): Boolean = other match
            case k: Key => k.id == id
            case _      => false
        override def toString: String = s"Key($id, hash=$hash)"
    end Key

    def assertConsistent(store: Cache.Unsafe[?, ?], context: String = "", quiescent: Boolean = true): Assertion =
        val s = store.stats
        // Ghost detection reads values + keys non-atomically, so it can report false positives during concurrent ops
        if quiescent then
            discard(assert(s.ghosts == 0, s"${s.ghosts} ghosts (unreachable entries). $s. $context"))
            discard(assert(s.locked == 0, s"${s.locked} locked slots at quiescence. $s. $context"))
            discard(assert(s.size == s.entries, s"size counter ${s.size} != actual entries ${s.entries}. $s. $context"))
        end if
        discard(assert(s.orphanKeys == 0, s"${s.orphanKeys} orphan keys (key set but value empty). $s. $context"))
        discard(assert(s.orphanValues == 0, s"${s.orphanValues} orphan values (value set but key absent). $s. $context"))
        succeed
    end assertConsistent

    def cache(
        maxSize: Int = 8,
        expireAfterAccess: Duration = Duration.Zero,
        expireAfterWrite: Duration = Duration.Zero,
        clock: Clock.Unsafe = Clock.live.unsafe,
        onEvict: (Key, String) => Unit = (_, _) => (),
        onExpire: (Key, String) => Unit = (_, _) => (),
        onRemove: (Key, String) => Unit = (_, _) => ()
    ) = Cache.Unsafe.init[Key, String](maxSize, expireAfterAccess, expireAfterWrite, clock, onEvict, onExpire, onRemove)

    "get" - {

        "returns empty on empty store" in {
            val s = cache()
            assert(s.get(new Key(1, 1)).isEmpty)
        }

        "returns empty for non-existent key" in {
            val s = cache()
            s.add(new Key(1, 1), "val-1")
            assert(s.get(new Key(2, 2)).isEmpty)
            assert(s.contents.size == 1)
        }

        "returns present value for existing key" in {
            val s = cache()
            val k = new Key(1, 1)
            s.add(k, "hello")
            assert(s.get(k) == Maybe("hello"))
            assert(s.contents.get(k) == Maybe("hello"))
        }

        "returns empty for expired entry (expireAfterWrite)" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 20.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                yield
                    assert(s.get(k).isEmpty)
                    assert(s.contents.isEmpty)
            }
        }

        "returns empty for expired entry (expireAfterAccess)" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterAccess = 20.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                yield
                    assert(s.get(k).isEmpty)
                    assert(s.contents.isEmpty)
            }
        }

        "probes past collisions to find key" in {
            val s  = cache()
            val k1 = new Key(1, 5)
            val k2 = new Key(2, 5) // same hash
            s.add(k1, "first")
            s.add(k2, "second")
            val c = s.contents
            assert(c.size == 2)
            assert(c.get(k1) == Maybe("first"))
            assert(c.get(k2) == Maybe("second"))
        }

        "returns empty when key not found after probing" in {
            val s = cache()
            for i <- 1 to 5 do s.add(new Key(i, 0), s"val-$i")
            assert(s.get(new Key(99, 0)).isEmpty)
            assert(s.contents.size == 5)
        }

        "updates accessTime on hit" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterAccess = 100.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "val")
                    _ <- tc.advance(30.millis, Duration.Zero)
                    _ = assert(s.get(k).isDefined) // refreshes access
                    _ <- tc.advance(30.millis, Duration.Zero)
                    _ = assert(s.get(k).isDefined) // still alive because of refresh
                    _ <- tc.advance(30.millis, Duration.Zero)
                yield assert(s.get(k).isDefined) // still alive
            }
        }
    }

    "add" - {

        "inserts and returns value on empty slot" in {
            val s      = cache()
            val k      = new Key(1, 1)
            val result = s.add(k, "hello")
            assert(result == "hello")
            assert(s.contents.get(k) == Maybe("hello"))
        }

        "returns existing value when key already present" in {
            val s = cache()
            val k = new Key(1, 1)
            s.add(k, "first")
            val result = s.add(k, "second")
            assert(result == "first")
            assert(s.contents.get(k) == Maybe("first"))
        }

        "replaces expired entry in-place (size unchanged)" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 20.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ = assert(s.contents.size == 1)
                    _ <- tc.advance(50.millis, Duration.Zero)
                    _ = s.add(k, "new")
                yield
                    val c = s.contents
                    assert(c.size == 1)
                    assert(c.get(k) == Maybe("new"))
                end for
            }
        }

        "probes past collisions to find empty slot" in {
            val s = cache()
            for i <- 1 to 5 do s.add(new Key(i, 0), s"val-$i")
            val c = s.contents
            assert(c.size == 5)
            for i <- 1 to 5 do assert(c.get(new Key(i, 0)) == Maybe(s"val-$i")): Unit
            succeed
        }

        "triggers eviction when size exceeds maxSize" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 10 do s.add(new Key(i, i), s"val-$i")
            val c = s.contents
            assert(c.size <= 4)
            // Every surviving entry must have the correct value
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}", s"Key ${k.id} has wrong value: $v")) }
            assertConsistent(s)
        }
    }

    "getOrElse" - {

        "returns cached value on hit (no recomputation)" in {
            val s = cache()
            val k = new Key(1, 1)
            s.getOrElse(k, "first")
            val result = s.getOrElse(k, "second")
            assert(result == "first")
        }

        "computes and caches on miss" in {
            val s      = cache()
            val k      = new Key(1, 1)
            val result = s.getOrElse(k, "computed")
            assert(result == "computed")
            assert(s.contents.get(k) == Maybe("computed"))
        }

        "matches by equals, not just reference" in {
            val s  = cache()
            val k1 = new Key(1, 1)
            val k2 = new Key(1, 1) // different object, same id and hash
            s.getOrElse(k1, "first")
            val result = s.getOrElse(k2, "second")
            assert(result == "first")
        }
    }

    "remove" - {

        "removes existing entry" in {
            val s = cache()
            val k = new Key(1, 1)
            s.add(k, "old")
            s.remove(k)
            assert(s.contents.isEmpty)
            assertConsistent(s)
        }

        "no-op for non-existent key" in {
            val s = cache()
            for i <- 1 to 4 do s.add(new Key(i, i), s"val-$i")
            s.remove(new Key(99, 99))
            assert(s.contents.size == 4)
            assertConsistent(s)
        }

        "no-op on empty store" in {
            val s = cache()
            s.remove(new Key(1, 1)) // should not throw
            assert(s.contents.isEmpty)
        }

        "double remove is safe" in {
            val s = cache()
            for i <- 1 to 4 do s.add(new Key(i, i), s"val-$i")
            s.remove(new Key(2, 2))
            s.remove(new Key(2, 2))
            val c = s.contents
            assert(c.size == 3)
            assert(c.get(new Key(2, 2)).isEmpty)
            assertConsistent(s)
        }

        "removes correct key when collisions exist" in {
            val s = cache()
            for i <- 1 to 5 do s.add(new Key(i, 0), s"val-$i")
            s.remove(new Key(3, 0))
            val c = s.contents
            assert(c.size == 4)
            assert(c.get(new Key(3, 0)).isEmpty)
            assert(c.get(new Key(2, 0)) == Maybe("val-2"))
            assert(c.get(new Key(4, 0)) == Maybe("val-4"))
            assertConsistent(s)
        }

        "remaining colliding keys still reachable after remove" in {
            val s = cache()
            for i <- 1 to 5 do s.add(new Key(i, 3), s"val-$i")
            s.remove(new Key(1, 3))
            val c = s.contents
            assert(c.size == 4)
            assert(c.get(new Key(1, 3)).isEmpty)
            for i <- 2 to 5 do
                assert(c.get(new Key(i, 3)) == Maybe(s"val-$i"), s"Key $i should still be reachable")
            assertConsistent(s)
        }
    }

    "expiration" - {

        "expireAfterWrite — entry expires" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 20.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                yield
                    assert(s.contents.isEmpty)
                    val result = s.getOrElse(k, "new")
                    assert(result == "new")
                    assert(s.contents.get(k) == Maybe("new"))
                end for
            }
        }

        "expireAfterWrite — entry valid before deadline" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 500.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "val")
                yield assert(s.contents.get(k) == Maybe("val"))
            }
        }

        "expireAfterAccess — entry expires when not accessed" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterAccess = 20.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                yield
                    assert(s.contents.isEmpty)
                    val result = s.getOrElse(k, "new")
                    assert(result == "new")
                end for
            }
        }

        "expireAfterAccess — accessing keeps entry alive" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterAccess = 100.millis)
                    k = new Key(1, 1)
                    _ = s.getOrElse(k, "original")
                    _ <- tc.advance(30.millis, Duration.Zero)
                    _ = assert(s.getOrElse(k, "replacement") == "original")
                    _ <- tc.advance(30.millis, Duration.Zero)
                    _ = assert(s.getOrElse(k, "replacement") == "original")
                    _ <- tc.advance(30.millis, Duration.Zero)
                    _ = assert(s.getOrElse(k, "replacement") == "original")
                    _ <- tc.advance(30.millis, Duration.Zero)
                    _ = assert(s.getOrElse(k, "replacement") == "original")
                    _ <- tc.advance(30.millis, Duration.Zero)
                yield assert(s.getOrElse(k, "replacement") == "original")
            }
        }

        "both enabled — write expires first" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterAccess = 500.millis, expireAfterWrite = 20.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                yield assert(s.contents.isEmpty)
            }
        }

        "both enabled — access expires first" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterAccess = 20.millis, expireAfterWrite = 500.millis)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                yield assert(s.contents.isEmpty)
            }
        }

        "expired entry does not break probe chain" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s  = cache(clock = clock.unsafe, expireAfterWrite = 20.millis)
                    k1 = new Key(1, 5)
                    k2 = new Key(2, 5)
                    k3 = new Key(3, 5) // all same hash
                    _  = s.add(k1, "v1")
                    _  = s.add(k2, "v2")
                    _ <- tc.advance(50.millis, Duration.Zero)
                    // k1 and k2 expired, insert k3 which probes past them
                    _ = s.add(k3, "v3")
                yield
                    val c = s.contents
                    assert(c.size == 1)
                    assert(c.get(k3) == Maybe("v3"))
                    assertConsistent(s)
                end for
            }
        }
    }

    "eviction (CLOCK)" - {

        "evicts non-accessed entry over accessed entry" in {
            val s        = cache(maxSize = 4)
            val accessed = new Key(1, 1)
            s.add(accessed, "keep")
            for i <- 2 to 4 do s.add(new Key(i, i), s"val-$i")
            // Access key 1 to set its accessed flag
            s.get(accessed)
            // Insert just enough to trigger eviction without wrapping the hand past key 1
            s.add(new Key(10, 10), "val-10")
            val c = s.contents
            assert(c.size <= 4)
            // Accessed key should survive one round of CLOCK eviction
            assert(c.get(accessed) == Maybe("keep"))
            // Every surviving entry must have the correct value
            c.foreach { (k, v) => discard(assert(v == (if k == accessed then "keep" else s"val-${k.id}"))) }
            assertConsistent(s)
        }

        "accessed flag gives entry a second chance" in {
            val s = cache(maxSize = 4)
            // Insert 4 entries
            for i <- 1 to 4 do s.add(new Key(i, i), s"val-$i")
            // Access entries 1 and 2 to set their accessed flag
            s.get(new Key(1, 1))
            s.get(new Key(2, 2))
            // Insert one new entry to trigger a single eviction pass
            s.add(new Key(5, 5), "val-5")
            val c = s.contents
            assert(c.size <= 4)
            // Accessed entries should survive one round
            assert(c.get(new Key(1, 1)) == Maybe("val-1"))
            assert(c.get(new Key(2, 2)) == Maybe("val-2"))
            // At least one non-accessed entry should have been evicted
            val nonAccessed = List(3, 4).count(i => c.get(new Key(i, i)).isDefined)
            assert(nonAccessed < 2, s"Expected at least one non-accessed entry evicted, but $nonAccessed survived")
            assertConsistent(s)
        }

        "eviction with colliding keys preserves consistency" in {
            val s = cache(maxSize = 4)
            // Insert colliding keys — tombstones from eviction must not break probing
            for i <- 1 to 8 do s.add(new Key(i, 0), s"val-$i")
            val c = s.contents
            assert(c.size <= 4)
            // Every surviving entry must have the correct value
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}", s"Key ${k.id} has wrong value: $v")) }
            assertConsistent(s)
        }

        "maxSize=1 — evicts on every new key" in {
            val s = cache(maxSize = 1)
            s.add(new Key(1, 1), "a")
            s.add(new Key(2, 2), "b")
            s.add(new Key(3, 3), "c")
            val c = s.contents
            assert(c.size <= 1)
            // The last inserted key should be the one remaining
            assert(c.get(new Key(3, 3)) == Maybe("c"))
            assertConsistent(s)
        }
    }

    "consistency (no ghosts)" - {

        "after basic insertions" in {
            val s = cache()
            for i <- 1 to 8 do s.add(new Key(i, i), s"val-$i")
            val c = s.contents
            assert(c.size == 8)
            for i <- 1 to 8 do assert(c.get(new Key(i, i)) == Maybe(s"val-$i")): Unit
            assertConsistent(s)
        }

        "after eviction" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 10 do s.add(new Key(i, i), s"val-$i")
            val c = s.contents
            assert(c.size <= 4)
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}")) }
            assertConsistent(s, "after inserting 10 keys into maxSize=4")
        }

        "after invalidation" in {
            val s = cache()
            for i <- 1 to 8 do s.add(new Key(i, i), s"val-$i")
            for i <- 1 to 4 do s.remove(new Key(i, i))
            val c = s.contents
            assert(c.size == 4)
            for i <- 1 to 4 do assert(c.get(new Key(i, i)).isEmpty): Unit
            for i <- 5 to 8 do assert(c.get(new Key(i, i)) == Maybe(s"val-$i")): Unit
            assertConsistent(s, "after invalidating half the entries")
        }

        "after eviction with colliding keys" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 8 do s.add(new Key(i, 0), s"val-$i")
            val c = s.contents
            assert(c.size <= 4)
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}")) }
            assertConsistent(s, "after eviction with all keys hashing to slot 0")
        }

        "after invalidation with colliding keys" in {
            val s = cache()
            for i <- 1 to 6 do s.add(new Key(i, 0), s"val-$i")
            s.remove(new Key(2, 0))
            s.remove(new Key(4, 0))
            val c = s.contents
            assert(c.size == 4)
            assert(c.get(new Key(2, 0)).isEmpty)
            assert(c.get(new Key(4, 0)).isEmpty)
            for i <- List(1, 3, 5, 6) do assert(c.get(new Key(i, 0)) == Maybe(s"val-$i")): Unit
            assertConsistent(s, "after invalidating mid-chain entries")
        }

        "after invalidation then re-insertion" in {
            val s = cache()
            for i <- 1 to 6 do s.add(new Key(i, 0), s"val-$i")
            s.remove(new Key(1, 0))
            s.remove(new Key(3, 0))
            s.add(new Key(1, 0), "new-1")
            s.add(new Key(3, 0), "new-3")
            val c = s.contents
            assert(c.size == 6)
            assert(c.get(new Key(1, 0)) == Maybe("new-1"))
            assert(c.get(new Key(3, 0)) == Maybe("new-3"))
            assertConsistent(s, "after invalidate + re-insert with colliding keys")
        }

        "after eviction with non-displaced neighbor" in {
            val s = cache(maxSize = 4)
            s.add(new Key(1, 5), "A")
            s.add(new Key(2, 6), "B")
            s.add(new Key(3, 5), "C")
            s.add(new Key(4, 5), "D")
            for i <- 10 to 20 do s.add(new Key(i, i), s"val-$i")
            val c = s.contents
            assert(c.size <= 4)
            c.foreach { (k, v) => discard(assert(v == (if k.id <= 4 then v else s"val-${k.id}"))) }
            assertConsistent(s, "after eviction with non-displaced neighbor in chain")
        }

        "after repeated eviction cycles" in {
            val s = cache(maxSize = 4)
            for round <- 1 to 5 do
                for i <- 1 to 8 do s.add(new Key(round * 100 + i, i), s"val-$round-$i")
            val c = s.contents
            assert(c.size <= 4)
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id / 100}-${k.id % 100}")) }
            assertConsistent(s, "after 5 rounds of eviction")
        }

        "after high churn with diverse keys" in {
            val s = cache()
            for i <- 1 to 100 do s.add(new Key(i, i), s"val-$i")
            val c = s.contents
            assert(c.size <= 8)
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}")) }
            assertConsistent(s, "after inserting 100 keys into maxSize=8")
        }

        "after high churn with colliding keys" in {
            val s = cache()
            for i <- 1 to 50 do
                s.add(new Key(i, if i % 2 == 0 then 0 else 1), s"val-$i")
            val c = s.contents
            assert(c.size <= 8)
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}")) }
            assertConsistent(s, "after 50 keys hashing to 2 slots in maxSize=8")
        }

        "after interleaved insert and invalidate" in {
            val s = cache()
            for i <- 1 to 30 do
                s.add(new Key(i, i % 4), s"val-$i")
                if i % 3 == 0 then s.remove(new Key(i - 1, (i - 1) % 4))
            assertConsistent(s, "after interleaved inserts and invalidations")
        }

        "after post-spike shrink" in {
            val s = cache(maxSize = 16)
            for i <- 1 to 16 do s.add(new Key(i, i), s"val-$i")
            for i <- 5 to 16 do s.remove(new Key(i, i))
            for i <- 1 to 4 do s.add(new Key(i, i), s"new-$i")
            val c = s.contents
            assert(c.size == 4)
            for i <- 1 to 4 do assert(c.get(new Key(i, i)) == Maybe(s"val-$i")): Unit
            assertConsistent(s, "after spike then shrink to small working set")
        }

        "with wraparound probe chains" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 6 do s.add(new Key(i, 14), s"val-$i")
            assertConsistent(s, "after wraparound probe chain with eviction")
        }

        "after invalidating cluster head" in {
            val s = cache()
            for i <- 1 to 5 do s.add(new Key(i, 3), s"val-$i")
            s.remove(new Key(1, 3))
            val c = s.contents
            assert(c.size == 4)
            assert(c.get(new Key(1, 3)).isEmpty)
            for i <- 2 to 5 do
                assert(c.get(new Key(i, 3)) == Maybe(s"val-$i"), s"Key $i should still be reachable")
            assertConsistent(s, "after invalidating cluster head")
        }

        "after invalidating mid-cluster entry" in {
            val s = cache()
            for i <- 1 to 5 do s.add(new Key(i, 3), s"val-$i")
            s.remove(new Key(3, 3))
            val c = s.contents
            assert(c.size == 4)
            assert(c.get(new Key(3, 3)).isEmpty)
            for i <- List(1, 2, 4, 5) do
                assert(c.get(new Key(i, 3)) == Maybe(s"val-$i"), s"Key $i should still be reachable")
            assertConsistent(s, "after invalidating mid-cluster entry")
        }

        "re-inserting after eviction does not create duplicates" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 4 do s.add(new Key(i, 0), s"val-$i")
            for i <- 10 to 15 do s.add(new Key(i, i), s"val-$i")
            for i <- 1 to 4 do s.add(new Key(i, 0), s"fresh-$i")
            val c = s.contents
            // Re-inserted keys should have fresh values
            for i <- 1 to 4 do
                c.get(new Key(i, 0)).foreach(v => discard(assert(v == s"fresh-$i", s"Key $i should have fresh value, got $v"))): Unit
            assertConsistent(s, "after re-inserting keys that may have been ghosted")
        }
    }

    "size accuracy" - {

        "after insertions" in {
            val s = cache(maxSize = 16)
            for i <- 1 to 10 do s.add(new Key(i, i), s"val-$i")
            assert(s.contents.size == 10)
        }

        "after insertions and invalidations" in {
            val s = cache(maxSize = 16)
            for i <- 1 to 10 do s.add(new Key(i, i), s"val-$i")
            for i <- 3 to 7 do s.remove(new Key(i, i))
            val c = s.contents
            assert(c.size == 5)
            for i <- 3 to 7 do assert(c.get(new Key(i, i)).isEmpty): Unit
            for i <- List(1, 2, 8, 9, 10) do assert(c.get(new Key(i, i)) == Maybe(s"val-$i")): Unit
            assertConsistent(s)
        }

        "respects maxSize after eviction with collisions" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 10 do s.add(new Key(i, 0), s"val-$i")
            val c = s.contents
            assert(c.size <= 4)
            c.foreach { (k, v) => discard(assert(v == s"val-${k.id}")) }
            assertConsistent(s)
        }

        "add replacing expired entry does not change size" in run {
            Clock.withTimeControl { tc =>
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 20.millis)
                    _ = s.add(new Key(1, 1), "a")
                    _ = s.add(new Key(2, 2), "b")
                    _ = assert(s.contents.size == 2)
                    _ <- tc.advance(50.millis, Duration.Zero)
                    _ = s.add(new Key(1, 1), "a2")
                yield
                    val c = s.contents
                    assert(c.size == 1)
                    assert(c.get(new Key(1, 1)) == Maybe("a2"))
            }
        }
    }

    "listeners" - {

        "onRemove fires on explicit remove" in {
            var removed = List.empty[(Int, String)]
            val s       = cache(onRemove = (k, v) => removed = (k.id, v) :: removed)
            val k       = new Key(1, 1)
            s.add(k, "val")
            s.remove(k)
            assert(removed == List((1, "val")))
            assert(s.contents.isEmpty)
        }

        "onRemove does not fire for non-existent key" in {
            var removed = List.empty[(Int, String)]
            val s       = cache(onRemove = (k, v) => removed = (k.id, v) :: removed)
            s.remove(new Key(1, 1))
            assert(removed.isEmpty)
            assert(s.contents.isEmpty)
        }

        "onEvict fires on CLOCK eviction" in {
            var evicted = List.empty[(Int, String)]
            val s       = cache(maxSize = 2, onEvict = (k, v) => evicted = (k.id, v) :: evicted)
            for i <- 1 to 10 do s.add(new Key(i, i), s"val-$i")
            assert(evicted.nonEmpty)
            // Evicted entries must have correct key-value pairing
            evicted.foreach { (id, v) => discard(assert(v == s"val-$id", s"Evicted key $id had wrong value: $v")) }
            assert(s.contents.size <= 2)
        }

        "onEvict does not fire for explicit remove" in {
            var evicted = List.empty[(Int, String)]
            val s       = cache(onEvict = (k, v) => evicted = (k.id, v) :: evicted)
            val k       = new Key(1, 1)
            s.add(k, "val")
            s.remove(k)
            assert(evicted.isEmpty)
            assert(s.contents.isEmpty)
        }

        "onExpire fires when add replaces expired entry" in run {
            Clock.withTimeControl { tc =>
                var expired = List.empty[(Int, String)]
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 20.millis, onExpire = (k, v) => expired = (k.id, v) :: expired)
                    k = new Key(1, 1)
                    _ = s.add(k, "old")
                    _ <- tc.advance(50.millis, Duration.Zero)
                    _ = s.add(k, "new")
                yield
                    assert(expired == List((1, "old")))
                    assert(s.contents.get(k) == Maybe("new"))
                end for
            }
        }

        "onExpire includes old value, not new" in run {
            Clock.withTimeControl { tc =>
                var expired = List.empty[(Int, String)]
                for
                    clock <- Clock.get
                    s = cache(clock = clock.unsafe, expireAfterWrite = 20.millis, onExpire = (k, v) => expired = (k.id, v) :: expired)
                    k = new Key(1, 1)
                    _ = s.add(k, "first")
                    _ <- tc.advance(50.millis, Duration.Zero)
                    _ = s.add(k, "second")
                yield
                    assert(expired.length == 1)
                    assert(expired.head._2 == "first")
                    assert(s.contents.get(k) == Maybe("second"))
                end for
            }
        }

        "listeners are independent — only relevant one fires" in {
            var evicted = List.empty[(Int, String)]
            var removed = List.empty[(Int, String)]
            val s = cache(
                maxSize = 2,
                onEvict = (k, v) => evicted = (k.id, v) :: evicted,
                onRemove = (k, v) => removed = (k.id, v) :: removed
            )
            s.add(new Key(1, 1), "a")
            s.add(new Key(2, 2), "b")
            s.remove(new Key(1, 1))
            assert(removed == List((1, "a")))
            assert(evicted.isEmpty)
            // Now trigger eviction
            for i <- 10 to 20 do s.add(new Key(i, i), s"val-$i")
            assert(evicted.nonEmpty)
            evicted.foreach { (id, v) => discard(assert(v == s"val-$id" || v == "b", s"Evicted key $id had wrong value: $v")) }
            succeed
        }
    }

    "concurrency" - {

        val repeats = if Platform.isNative then 50 else 1000

        "add" - {

            "parallel adds, same key — all threads agree on winner" in runNotJS {
                // Each thread adds a DIFFERENT value for the same key.
                // All must get back the same value (the winner's).
                // Uses shared key object to test the eq fast-path.
                (for
                    maxSize <- Choice.eval(2, 4, 8, 32)
                    s = cache(maxSize = maxSize)
                    k = new Key(1, 1)
                    results <- Async.fillIndexed(50, 50)(i => Sync.Unsafe.defer(s.add(k, s"val-$i")))
                yield
                    val unique = results.toSet
                    discard(assert(unique.size == 1, s"Expected all same value, got $unique"))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "parallel adds, same key with eviction pressure — value coherence" in runNotJS {
                // Flood cache so the target key gets evicted and re-inserted.
                // After each add, any get must return a valid value for that key.
                (for
                    maxSize <- Choice.eval(2, 4)
                    s = cache(maxSize = maxSize)
                    k = new Key(1, 1)
                    _ <- Async.fill(100, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        if rng.nextInt(3) == 0 then
                            // flood with other keys to force eviction
                            val i = rng.nextInt(50) + 100
                            discard(s.add(new Key(i, i), s"val-$i"))
                        else
                            val v = s.add(k, "target")
                            // add must return either "target" (ours) or "target" (previous)
                            discard(assert(v == "target", s"add returned: $v"))
                            s.get(k).foreach { v2 =>
                                discard(assert(v2 == "target", s"get after add returned: $v2"))
                            }
                        end if
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "parallel adds, colliding keys — different values per key" in runNotJS {
                // All keys hash to 0, maximum probe chain contention.
                // Each key has a unique value format — catches key/value slot mixups.
                (for
                    maxSize <- Choice.eval(4, 8, 32)
                    s = cache(maxSize = maxSize)
                    results <- Async.foreach(1 to 50, 50)(i =>
                        Sync.Unsafe.defer {
                            val k = new Key(i, 0)
                            (i, s.add(k, s"val-$i"))
                        }
                    )
                yield
                    results.foreach { (i, v) =>
                        discard(assert(v == s"val-$i", s"Key $i got wrong value: $v"))
                    }
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "add with eviction — bounded size, correct values" in runNotJS {
                // 100 unique keys into tiny cache. Every add must return its own value
                // (never another key's value). Tests CAS retry + eviction interaction.
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    s = cache(maxSize = maxSize)
                    _ <- Async.foreach(1 to 100, 100)(i =>
                        Sync.Unsafe.defer {
                            val k = new Key(i, i)
                            val v = s.add(k, s"val-$i")
                            discard(assert(v == s"val-$i", s"Key $i got wrong value: $v"))
                        }
                    )
                yield
                    discard(assert(s.stats.entries <= maxSize, s"Expected <= $maxSize entries, got ${s.stats.entries}"))
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "pure adds into full table — eviction + tombstone reclamation" in runNotJS {
                // Only adds (no explicit removes), rely on eviction to make room.
                // Eviction creates tombstones, which adds reclaim.
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    s = cache(maxSize = maxSize)
                    _ <- Async.foreach(1 to 200, 100)(i =>
                        Sync.Unsafe.defer {
                            val k = new Key(i, i)
                            val v = s.add(k, s"val-$i")
                            discard(assert(v == s"val-$i", s"Key $i got wrong value: $v"))
                        }
                    )
                yield
                    discard(assert(s.stats.entries <= maxSize, s"Expected <= $maxSize entries, got ${s.stats.entries}"))
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "add-then-get within same thread — immediate visibility" in runNotJS {
                // Within a single Sync.Unsafe.defer (same thread), add then get
                // must return the value unless another thread evicted it.
                (for
                    maxSize <- Choice.eval(4, 8, 16)
                    s = cache(maxSize = maxSize)
                    _ <- Async.foreach(1 to 100, 50)(i =>
                        Sync.Unsafe.defer {
                            val k = new Key(i, i)
                            s.add(k, s"val-$i")
                            s.get(k).foreach { v =>
                                discard(assert(v == s"val-$i", s"Key $i: add then get returned: $v"))
                            }
                        }
                    )
                yield succeed).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "concurrent getOrElse — all callers agree on value" in runNotJS {
                // getOrElse = get + add. Multiple threads calling getOrElse for the
                // same key with different fallback values must all get the same result.
                (for
                    maxSize <- Choice.eval(4, 16, 64)
                    s = cache(maxSize = maxSize)
                    k = new Key(1, 1)
                    results <- Async.fillIndexed(50, 50)(i =>
                        Sync.Unsafe.defer(s.getOrElse(k, s"fallback-$i"))
                    )
                yield
                    val unique = results.toSet
                    discard(assert(unique.size == 1, s"Expected all same value, got $unique"))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "concurrent add with different values — return value matches stored" in runNotJS {
                // Thread A adds key with "A", thread B adds same key with "B".
                // Whatever add returns, a subsequent get must agree (or be empty).
                (for
                    maxSize <- Choice.eval(4, 16)
                    s = cache(maxSize = maxSize)
                    k = new Key(1, 1)
                    results <- Async.fillIndexed(50, 50)(i =>
                        Sync.Unsafe.defer {
                            val returned = s.add(k, s"val-$i")
                            val stored   = s.get(k)
                            (returned, stored)
                        }
                    )
                yield
                    // All returned values must be the same (first writer wins)
                    val returned = results.map(_._1).toSet
                    discard(assert(returned.size == 1, s"Different return values: $returned"))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }

        "remove" - {

            "parallel removes, same key — onRemove fires exactly once" in runNotJS {
                (for
                    maxSize <- Choice.eval(4, 16, 64)
                    removeCount = new java.util.concurrent.atomic.AtomicInteger(0)
                    s           = cache(maxSize = maxSize, onRemove = (_, _) => discard(removeCount.incrementAndGet()))
                    k           = new Key(1, 1)
                    _           = s.add(k, "val")
                    _ <- Async.fill(50, 50)(Sync.Unsafe.defer(s.remove(k)))
                yield discard(assert(removeCount.get() == 1, s"Expected 1 remove, got ${removeCount.get()}"))).handle(
                    Choice.run,
                    _.unit,
                    Loop.repeat(repeats)
                ).andThen(succeed)
            }

            "parallel removes, same probe chain — remaining keys reachable" in runNotJS {
                // 8 colliding keys, remove first 4 concurrently, verify last 4 survive.
                // Stresses concurrent tombstone creation on overlapping chain.
                (for
                    maxSize <- Choice.eval(16, 32)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 8).map(i => new Key(i, 0))
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.foreach(keys.take(4).toSeq, 4)(k => Sync.Unsafe.defer(s.remove(k)))
                yield
                    keys.drop(4).foreach { k =>
                        discard(assert(s.get(k) == Maybe(s"val-${k.id}"), s"Key ${k.id} should still be reachable"))
                    }
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "concurrent removes at chain head — cascading ghost creation" in runNotJS {
                // Remove the first N entries in a long chain concurrently.
                // Each removal creates a tombstone. Probes must skip past tombstones
                // to find tail entries.
                (for
                    maxSize <- Choice.eval(16, 32)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 12).map(i => new Key(i, 0)).toArray // all same hash
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.foreach(keys.take(6).toSeq, 6)(k => Sync.Unsafe.defer(s.remove(k)))
                yield
                    // After removing first 6, remaining 6 must still be findable
                    keys.drop(6).foreach { k =>
                        discard(assert(s.get(k) == Maybe(s"val-${k.id}"), s"Key ${k.id} unreachable after head removal"))
                    }
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "remove every-other slot in chain — interleaved gaps" in runNotJS {
                // Remove entries at positions 0, 2, 4, ... concurrently.
                // The non-contiguous tombstones test that probing correctly skips
                // over tombstones to find surviving entries.
                (for
                    maxSize <- Choice.eval(16, 32)
                    s     = cache(maxSize = maxSize)
                    keys  = (1 to 10).map(i => new Key(i, 0)).toArray
                    _     = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    evens = keys.indices.filter(_ % 2 == 0).map(keys(_)).toSeq
                    odds  = keys.indices.filter(_ % 2 == 1).map(keys(_)).toSeq
                    _ <- Async.foreach(evens, evens.size)(k => Sync.Unsafe.defer(s.remove(k)))
                yield
                    odds.foreach { k =>
                        discard(assert(s.get(k) == Maybe(s"val-${k.id}"), s"Key ${k.id} unreachable after interleaved removal"))
                    }
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }

        "tombstone races" - {

            "remove vs add race on tombstone — size accuracy" in runNotJS {
                // Pre-fill a chain, then half the threads remove entries
                // (creating tombstones) while the other half add new entries
                // (reclaiming tombstones). Checks size == entries.
                (for
                    maxSize <- Choice.eval(8, 16)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 6).map(i => new Key(i, 0)).toArray
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        if rng.nextBoolean() then
                            // remove from existing chain — creates tombstone
                            val k = keys(rng.nextInt(keys.length))
                            s.remove(k)
                        else
                            // add new key with same hash — may reclaim tombstone
                            val i = rng.nextInt(20) + 100
                            val k = new Key(i, 0)
                            discard(s.add(k, s"val-$i"))
                        end if
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "concurrent removes on shared chain" in runNotJS {
                // Multiple removes on the same probe chain create adjacent tombstones.
                // Checks for lost entries and size drift.
                (for
                    maxSize <- Choice.eval(16, 32)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 10).map(i => new Key(i, 0)).toArray
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.fill(100, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val k   = keys(rng.nextInt(keys.length))
                        s.remove(k)
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "add and remove, colliding keys — tombstone contention" in runNotJS {
                (for
                    maxSize <- Choice.eval(4, 16)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(100, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(20)
                        val k   = new Key(i, 0) // all hash to 0
                        if rng.nextBoolean() then discard(s.add(k, s"val-$i"))
                        else s.remove(k)
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "add reclaiming tombstones — colliding chain" in runNotJS {
                // Remove creates tombstones. Concurrent add reclaims them.
                // Tests tombstone reclamation + CAS interaction.
                (for
                    maxSize <- Choice.eval(8, 16)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 8).map(i => new Key(i, 0)).toArray
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        if rng.nextInt(3) == 0 then
                            val k = keys(rng.nextInt(keys.length))
                            s.remove(k)
                        else
                            val i = rng.nextInt(20) + 1
                            val k = new Key(i, 0) // same hash as existing keys
                            val v = s.add(k, s"val-$i")
                            discard(assert(v == s"val-$i", s"Key $i got: $v"))
                        end if
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "heavy remove and add on long chain" in runNotJS {
                // Long probe chain, remove from head and tail concurrently
                // while other threads add new entries that reclaim tombstones.
                (for
                    maxSize <- Choice.eval(16, 32)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 12).map(i => new Key(i, 0)).toArray
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        rng.nextInt(3) match
                            case 0 =>
                                // remove from head — creates tombstone in early chain
                                val k = keys(rng.nextInt(3))
                                s.remove(k)
                            case 1 =>
                                // add new key — may reclaim tombstone
                                val i = rng.nextInt(20) + 100
                                val k = new Key(i, 0)
                                discard(s.add(k, s"val-$i"))
                            case _ =>
                                // remove from tail — creates tombstone in late chain
                                val k = keys(keys.length - 1 - rng.nextInt(3))
                                s.remove(k)
                        end match
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }

        "mixed operations" - {

            "get during concurrent writes — no wrong values" in runNotJS {
                // Writers continuously add/remove while readers check values.
                // Readers may see empty (evicted/removed) but never a wrong key's value.
                // Uses shared key objects so get uses the eq fast-path.
                (for
                    maxSize <- Choice.eval(4, 8, 16)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 20).map(i => new Key(i, i)).toArray
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val idx = rng.nextInt(keys.length)
                        val k   = keys(idx)
                        rng.nextInt(3) match
                            case 0 => discard(s.add(k, s"val-${k.id}"))
                            case 1 => s.remove(k)
                            case _ =>
                                s.get(k).foreach { v =>
                                    discard(assert(v == s"val-${k.id}", s"Key ${k.id} got wrong value: $v"))
                                }
                        end match
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "add and remove, same key rapid cycles" in runNotJS {
                // Each thread adds then removes in a tight loop.
                // Threads interleave: one thread's add may see another's in-progress remove.
                (for
                    maxSize <- Choice.eval(4, 16, 64)
                    s = cache(maxSize = maxSize)
                    k = new Key(1, 1)
                    _ <- Async.fill(100, 50)(Sync.Unsafe.defer {
                        s.add(k, "val")
                        s.remove(k)
                    })
                yield discard(assertConsistent(s))).handle(
                    Choice.run,
                    _.unit,
                    Loop.repeat(if Platform.isNative then 10 else repeats)
                ).andThen(succeed)
            }

            "rapid remove-then-add of same key — size accuracy" in runNotJS {
                // Single key, many threads doing remove then add in tight loops.
                // The add after remove reclaims the tombstone left by remove.
                (for
                    maxSize <- Choice.eval(4, 16)
                    s = cache(maxSize = maxSize)
                    k = new Key(1, 0)
                    _ = s.add(k, "initial")
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        s.remove(k)
                        discard(s.add(k, "val"))
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "remove during get of neighbor in probe chain" in runNotJS {
                // Shared key objects: get uses eq fast-path, remove creates tombstones.
                // Tests that tombstones don't break a concurrent get's probe walk.
                (for
                    maxSize <- Choice.eval(16, 32)
                    s    = cache(maxSize = maxSize)
                    keys = (1 to 8).map(i => new Key(i, 0)).toArray // all same hash
                    _    = keys.foreach(k => s.add(k, s"val-${k.id}"))
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val k   = keys(rng.nextInt(keys.length))
                        if rng.nextBoolean() then
                            s.remove(k)
                        else
                            s.get(k).foreach { v =>
                                discard(assert(v == s"val-${k.id}", s"Key ${k.id} got: $v"))
                            }
                        end if
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "size consistent after mixed operations" in runNotJS {
                (for
                    maxSize <- Choice.eval(4, 8, 16)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(30)
                        val k   = new Key(i, i)
                        rng.nextInt(3) match
                            case 0 => discard(s.add(k, s"val-$i"))
                            case 1 => s.get(k); ()
                            case _ => s.remove(k)
                        end match
                    })
                yield
                    val stats = s.stats
                    discard(assert(stats.entries <= maxSize, s"Expected <= $maxSize entries, got ${stats.entries}"))
                    discard(assert(stats.entries >= 0))
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "sustained churn with periodic consistency check" in runNotJS {
                // Run adds/removes for a longer period, checking assertConsistent
                // periodically via an atomic counter to catch transient corruption.
                (for
                    maxSize <- Choice.eval(4, 8)
                    s       = cache(maxSize = maxSize)
                    counter = new java.util.concurrent.atomic.AtomicInteger(0)
                    _ <- Async.fill(500, 100)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(30)
                        val k   = new Key(i, 0) // all same hash
                        if rng.nextBoolean() then discard(s.add(k, s"val-$i"))
                        else s.remove(k)
                        // check consistency every 50 operations
                        if counter.incrementAndGet() % 50 == 0 then
                            discard(assertConsistent(s, s"at operation ${counter.get()}", quiescent = false))
                        end if
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }

        "eviction" - {

            "eviction + add + remove triple race" in runNotJS {
                // Small cache (maxSize=2), many keys. Eviction, add, and remove
                // all race to claim/tombstone the same slots.
                (for
                    maxSize <- Choice.eval(2, 3, 4)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(200, 100)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(20)
                        val k   = new Key(i, 0)
                        rng.nextInt(3) match
                            case 0 =>
                                val v = s.add(k, s"val-$i")
                                discard(assert(v == s"val-$i", s"add for key $i returned: $v"))
                            case 1 => s.remove(k)
                            case _ =>
                                s.get(k).foreach { v =>
                                    discard(assert(v == s"val-$i", s"get for key $i returned: $v"))
                                }
                        end match
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "onEvict count consistency" in runNotJS {
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    evictCount = new java.util.concurrent.atomic.AtomicInteger(0)
                    s          = cache(maxSize = maxSize, onEvict = (_, _) => discard(evictCount.incrementAndGet()))
                    _ <- Async.foreach(1 to 100, 50)(i =>
                        Sync.Unsafe.defer {
                            val k = new Key(i, i)
                            discard(s.add(k, s"val-$i"))
                        }
                    )
                yield
                    val remaining = s.stats.entries
                    discard(assert(
                        evictCount.get() + remaining >= 100 - maxSize,
                        s"evicted=${evictCount.get()} remaining=$remaining total adds=100"
                    ))
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "all-same-hash with maxSize=1 — maximum conflict" in runNotJS {
                // Every operation fights over the same 1-2 slots with tombstones
                // on every remove. The smallest possible table with maximum contention.
                (for
                    s = cache(maxSize = 1)
                    _ <- Async.fill(200, 50)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(10)
                        val k   = new Key(i, 0)
                        if rng.nextBoolean() then
                            val v = s.add(k, s"val-$i")
                            discard(assert(v == s"val-$i", s"add for key $i returned: $v"))
                        else
                            s.remove(k)
                        end if
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }

        "phantom values" - {

            "no phantom values — unique value per key detects slot mixup" in runNotJS {
                // Each key i always maps to "val-$i". If get/add ever returns a
                // value belonging to a different key, the key/value arrays are out of sync.
                // Uses tiny cache to force heavy eviction + CAS retries.
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(300, 100)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(30)
                        val k   = new Key(i, i)
                        rng.nextInt(3) match
                            case 0 =>
                                val v = s.add(k, s"val-$i")
                                discard(assert(v == s"val-$i", s"add for key $i returned: $v"))
                            case 1 =>
                                s.get(k).foreach { v =>
                                    discard(assert(v == s"val-$i", s"get for key $i returned: $v"))
                                }
                            case _ =>
                                s.remove(k)
                        end match
                    })
                yield succeed).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "no phantom values — colliding keys, same hash" in runNotJS {
                // Same as above but all keys hash to 0. This forces every operation
                // through the same probe chain, maximizing tombstone/evict interference.
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(300, 100)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(30)
                        val k   = new Key(i, 0) // all hash to 0
                        rng.nextInt(3) match
                            case 0 =>
                                val v = s.add(k, s"val-$i")
                                discard(assert(v == s"val-$i", s"add for key $i returned: $v"))
                            case 1 =>
                                s.get(k).foreach { v =>
                                    discard(assert(v == s"val-$i", s"get for key $i returned: $v"))
                                }
                            case _ =>
                                s.remove(k)
                        end match
                    })
                yield discard(assertConsistent(s))).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }

        "stress" - {

            "all operations, small cache" in runNotJS {
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(500, 100)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(50)
                        val k   = new Key(i, i)
                        rng.nextInt(4) match
                            case 0 => discard(s.add(k, s"val-$i"))
                            case 1 => discard(s.getOrElse(k, s"val-$i"))
                            case 2 =>
                                s.get(k).foreach { v =>
                                    discard(assert(v == s"val-$i", s"get for key $i returned: $v"))
                                }
                            case _ => s.remove(k)
                        end match
                    })
                yield
                    discard(assert(s.stats.entries <= maxSize))
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }

            "all same hash — maximum probe chain contention" in runNotJS {
                (for
                    maxSize <- Choice.eval(2, 4, 8)
                    s = cache(maxSize = maxSize)
                    _ <- Async.fill(500, 100)(Sync.Unsafe.defer {
                        val rng = java.util.concurrent.ThreadLocalRandom.current()
                        val i   = rng.nextInt(50)
                        val k   = new Key(i, 0) // all hash to 0
                        rng.nextInt(4) match
                            case 0 => discard(s.add(k, s"val-$i"))
                            case 1 => discard(s.getOrElse(k, s"val-$i"))
                            case 2 =>
                                s.get(k).foreach { v =>
                                    discard(assert(v == s"val-$i", s"get for key $i returned: $v"))
                                }
                            case _ => s.remove(k)
                        end match
                    })
                yield
                    discard(assert(s.stats.entries <= maxSize))
                    discard(assertConsistent(s))
                ).handle(Choice.run, _.unit, Loop.repeat(repeats)).andThen(succeed)
            }
        }
    }

    "stress" - {

        "random insert, invalidate, re-insert" in {
            val rng = new Random(42)
            val s   = cache(maxSize = 16)
            val ks  = (1 to 100).map(i => new Key(i, rng.nextInt(8))).toArray
            for _ <- 1 to 500 do
                val k = ks(rng.nextInt(ks.length))
                if rng.nextBoolean() then s.add(k, s"val-${k.id}")
                else s.remove(k)
            end for
            assertConsistent(s, "after 500 random operations")
        }

        "sequential keys, small cache" in {
            val s = cache(maxSize = 4)
            for i <- 1 to 200 do
                s.add(new Key(i, i % 3), s"val-$i")
                if i % 10 == 0 then discard(assertConsistent(s, s"after $i insertions"))
            succeed
        }
    }

    "State timestamp arithmetic" - {

        "writeTime and accessTime round-trip" in {
            val s = State(12345)
            assert(s.writeTime == 12345)
            assert(s.accessTime == 12345)
            assert(s.accessed == true)
        }

        "clearAccessed preserves times" in {
            val s  = State(12345)
            val s2 = s.clearAccessed
            assert(s2.writeTime == 12345)
            assert(s2.accessTime == 12345)
            assert(s2.accessed == false)
        }

        "withAccess updates accessTime, preserves writeTime, sets accessed" in {
            val s  = State(100)
            val s2 = s.withAccess(200)
            assert(s2.writeTime == 100)
            assert(s2.accessTime == 200)
            assert(s2.accessed == true)
        }

        "accessTime wraps at 31 bits" in {
            val maxAccess = (1 << 31) - 1 // 2^31 - 1
            val s         = State(100).withAccess(maxAccess)
            assert(s.accessTime == maxAccess)
            val s2 = s.withAccess(0)
            assert(s2.accessTime == 0)
        }

        "large writeTime" in {
            val large = Int.MaxValue
            val s     = State(large)
            assert(s.writeTime == large)
            assert(s.accessTime == (large & 0x7fffffff))
        }

        "expiration check with time progression" in {
            val s   = State(100)
            val now = 200
            assert(now - s.writeTime == 100)
            assert(now - s.accessTime == 100)
        }

        "small time wrap is handled by signed arithmetic" in {
            val writtenAt = Int.MaxValue - 10
            val s         = State(writtenAt)
            val now       = Int.MinValue + 10
            assert(now - s.writeTime == 21)
        }

        "accessTime loses high bit due to 31-bit field" in {
            val negativeCentis = -100
            val s              = State(negativeCentis)
            assert(s.writeTime == negativeCentis)
            assert(s.accessTime != negativeCentis)
            assert(s.accessTime == (negativeCentis & 0x7fffffff))
        }
    }

    "memo" - {

        "sync" in run {
            for
                m <- Cache.memo(4) { (v: Int) =>
                    v + 1
                }
                v1 <- m(1)
                v2 <- m(1)
            yield assert(v1 == 2 && v2 == 2)
            end for
        }

        "async" in run {
            for
                m <- Cache.memo(4) { (v: Int) =>
                    Fiber.initUnscoped {
                        v + 1
                    }.map(_.get)
                }
                v1 <- m(1)
                v2 <- m(1)
            yield assert(v1 == 2 && v2 == 2)
            end for
        }

        "failure invalidates entry" in run {
            val ex    = new Exception
            var calls = 0
            for
                m <- Cache.memo(4) { (v: Int) =>
                    Fiber.initUnscoped {
                        calls += 1
                        if calls == 1 then
                            throw ex
                        else
                            v + 1
                        end if
                    }.map(_.get)
                }
                v1 <- Abort.run[Throwable](m(1))
                v2 <- Abort.run[Throwable](m(1))
            yield assert(calls == 2 && v1 == Result.fail(ex) && v2 == Result.succeed(2))
            end for
        }

        "distinct keys" in run {
            val calls = new AtomicInteger(0)
            for
                m <- Cache.memo(100) { (v: Int) =>
                    discard(calls.incrementAndGet())
                    v * 10
                }
                results <- Kyo.foreach(1 to 10)(m)
            yield
                assert(calls.get() == 10)
                assert(results == (1 to 10).map(_ * 10))
            end for
        }

        "memo2" in run {
            var calls = 0
            for
                m <- Cache.memo2(4) { (a: Int, b: Int) =>
                    calls += 1
                    a + b
                }
                v1 <- m(1, 2)
                v2 <- m(1, 2)
                v3 <- m(2, 1)
            yield assert(calls == 2 && v1 == 3 && v2 == 3 && v3 == 3)
            end for
        }

        "memo3" in run {
            var calls = 0
            for
                m <- Cache.memo3(4) { (a: Int, b: Int, c: Int) =>
                    calls += 1
                    a + b + c
                }
                v1 <- m(1, 2, 3)
                v2 <- m(1, 2, 3)
            yield assert(calls == 1 && v1 == 6 && v2 == 6)
            end for
        }

        "memo4" in run {
            var calls = 0
            for
                m <- Cache.memo4(4) { (a: Int, b: Int, c: Int, d: Int) =>
                    calls += 1
                    a + b + c + d
                }
                v1 <- m(1, 2, 3, 4)
                v2 <- m(1, 2, 3, 4)
            yield assert(calls == 1 && v1 == 10 && v2 == 10)
            end for
        }
    }

    "memo eviction" - {

        "respects maxSize" in run {
            val calls = new AtomicInteger(0)
            for
                m <- Cache.memo(4) { (v: Int) =>
                    discard(calls.incrementAndGet())
                    v * 10
                }
                _ <- Kyo.foreach(1 to 4)(m)
                _ = calls.set(0)
                r1 <- Kyo.foreach(1 to 4)(m)
                _ = assert(calls.get() == 0)
                _ <- Kyo.foreach(5 to 10)(m)
                _ = calls.set(0)
                _ <- Kyo.foreach(1 to 4)(m)
            yield assert(calls.get() > 0)
            end for
        }

        "evicted entries are recomputed" in run {
            val calls = new AtomicInteger(0)
            for
                m <- Cache.memo(2) { (v: Int) =>
                    discard(calls.incrementAndGet())
                    v
                }
                _ <- m(1)
                _ <- m(2)
                _ = assert(calls.get() == 2)
                _ <- m(3)
                _ <- m(4)
                _ <- m(5)
                _ = calls.set(0)
                _ <- m(1)
                _ <- m(2)
            yield assert(calls.get() == 2)
            end for
        }
    }

    "memo concurrency" - {

        val repeats = if Platform.isNative then 10 else 100

        "parallel calls to same key compute exactly once" in runNotJS {
            Loop.repeat(repeats) {
                for
                    calls = new AtomicInteger(0)
                    latch <- Latch.init(1)
                    m <- Cache.memo(100) { (v: Int) =>
                        discard(calls.incrementAndGet())
                        v + 1
                    }
                    fibers <- Kyo.foreach(1 to 20) { _ =>
                        Fiber.initUnscoped(latch.await.andThen(m(42)))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield
                    assert(calls.get() == 1)
                    assert(results.forall(_ == 43))
                end for
            }.andThen(succeed)
        }

        "parallel calls to different keys" in runNotJS {
            Loop.repeat(repeats) {
                for
                    calls = new AtomicInteger(0)
                    latch <- Latch.init(1)
                    m <- Cache.memo(100) { (v: Int) =>
                        discard(calls.incrementAndGet())
                        v * 10
                    }
                    fibers <- Kyo.foreach(1 to 50) { i =>
                        Fiber.initUnscoped(latch.await.andThen(m(i)))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield
                    assert(calls.get() == 50)
                    assert(results.toSet == (1 to 50).map(_ * 10).toSet)
                end for
            }.andThen(succeed)
        }

        "parallel calls with async computation" in runNotJS {
            Loop.repeat(repeats) {
                for
                    calls = new AtomicInteger(0)
                    latch <- Latch.init(1)
                    m <- Cache.memo(100) { (v: Int) =>
                        Fiber.initUnscoped {
                            discard(calls.incrementAndGet())
                            v + 1
                        }.map(_.get)
                    }
                    fibers <- Kyo.foreach(1 to 20) { _ =>
                        Fiber.initUnscoped(latch.await.andThen(m(1)))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield
                    assert(calls.get() == 1)
                    assert(results.forall(_ == 2))
                end for
            }.andThen(succeed)
        }

        "concurrent failure and retry" in runNotJS {
            Loop.repeat(repeats) {
                for
                    calls = new AtomicInteger(0)
                    latch <- Latch.init(1)
                    m <- Cache.memo(100) { (v: Int) =>
                        val c = calls.incrementAndGet()
                        if c == 1 then throw new Exception("first call")
                        else v + 1
                    }
                    fibers <- Kyo.foreach(1 to 10) { _ =>
                        Fiber.initUnscoped(latch.await.andThen(Abort.run[Throwable](m(1))))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield
                    val failures  = results.count(_.isFailure)
                    val successes = results.count(_.isSuccess)
                    assert(failures + successes == results.size)
                    assert(results.filter(_.isSuccess).forall(_.contains(2)))
                end for
            }.andThen(succeed)
        }

        "concurrent eviction under contention" in runNotJS {
            Loop.repeat(repeats) {
                for
                    latch <- Latch.init(1)
                    m     <- Cache.memo(4) { (v: Int) => v * 10 }
                    fibers <- Kyo.foreach(1 to 20) { i =>
                        Fiber.initUnscoped(latch.await.andThen(m(i)))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield assert(results.toSet == (1 to 20).map(_ * 10).toSet)
                end for
            }.andThen(succeed)
        }

        "concurrent memo and invalidation via failure" in runNotJS {
            Loop.repeat(repeats) {
                for
                    calls = new AtomicInteger(0)
                    latch <- Latch.init(1)
                    m <- Cache.memo(100) { (v: Int) =>
                        val c = calls.incrementAndGet()
                        if v == 1 && c % 2 == 1 then
                            throw new Exception("odd call")
                        else
                            v * 10
                        end if
                    }
                    fibers <- Kyo.foreach(1 to 20) { i =>
                        Fiber.initUnscoped(latch.await.andThen(Abort.run[Throwable](m(i % 5))))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield discard(results.foreach { r =>
                    r.foreach { v =>
                        discard(assert(v % 10 == 0))
                    }
                })
                end for
            }.andThen(succeed)
        }

        "high contention same key repeated calls" in runNotJS {
            Loop.repeat(repeats) {
                for
                    calls = new AtomicInteger(0)
                    m <- Cache.memo(100) { (v: Int) =>
                        discard(calls.incrementAndGet())
                        v + 1
                    }
                    _       <- Async.fill(50, 50)(m(1))
                    results <- Async.fill(50, 50)(m(1))
                yield
                    assert(calls.get() == 1)
                    assert(results.forall(_ == 2))
                end for
            }.andThen(succeed)
        }

        "interleaved puts and gets across many keys" in runNotJS {
            Loop.repeat(repeats) {
                for
                    latch <- Latch.init(1)
                    m     <- Cache.memo(50) { (v: Int) => v }
                    writers <- Kyo.foreach(1 to 100) { i =>
                        Fiber.initUnscoped(latch.await.andThen(m(i)))
                    }
                    readers <- Kyo.foreach(1 to 50) { i =>
                        Fiber.initUnscoped(latch.await.andThen(m(i)))
                    }
                    _            <- latch.release
                    writeResults <- Kyo.foreach(writers)(_.get)
                    readResults  <- Kyo.foreach(readers)(_.get)
                yield
                    writeResults.zipWithIndex.foreach { (v, i) => assert(v == i + 1) }
                    readResults.zipWithIndex.foreach { (v, i) => assert(v == i + 1) }
                end for
            }.andThen(succeed)
        }

        "no lost updates under concurrent eviction" in runNotJS {
            Loop.repeat(repeats) {
                for
                    m     <- Cache.memo(8) { (v: Int) => v * v }
                    latch <- Latch.init(1)
                    fibers <- Kyo.foreach(1 to 100) { i =>
                        Fiber.initUnscoped(latch.await.andThen(m(i % 20)))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield assert(results.forall { v =>
                    val sqrt = math.sqrt(v.toDouble).toInt
                    sqrt * sqrt == v
                })
                end for
            }.andThen(succeed)
        }

        "concurrent calls during slow computation" in runNotJS {
            Loop.repeat(10) {
                for
                    calls = new AtomicInteger(0)
                    latch <- Latch.init(1)
                    m <- Cache.memo(100) { (v: Int) =>
                        Async.sleep(1.millis).andThen {
                            discard(calls.incrementAndGet())
                            v + 1
                        }
                    }
                    fibers <- Kyo.foreach(1 to 10) { _ =>
                        Fiber.initUnscoped(latch.await.andThen(m(1)))
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield
                    assert(calls.get() == 1)
                    assert(results.forall(_ == 2))
                end for
            }.andThen(succeed)
        }

        "stress test - many keys, small cache, high parallelism" in runNotJS {
            for
                m       <- Cache.memo(16) { (v: Int) => v * 3 }
                results <- Async.fill(500, 500)(m(Random.nextInt(100)))
            yield assert(results.forall(v => v % 3 == 0 && v >= 0 && v < 300))
        }
    }

    "stack safety" - {

        "add under contention" in runJVM {
            for
                c     <- Cache.init[Int, String](1)
                latch <- Latch.init(1)
                fibers <- Kyo.foreach(1 to 1000)(i =>
                    Fiber.initUnscoped(latch.await.andThen(c.add(i % 4, s"val-$i")))
                )
                _       <- latch.release
                results <- Kyo.foreach(fibers)(_.get)
            yield
                val distinct = results.distinct
                assert(distinct.nonEmpty)
                assert(distinct.forall(_.startsWith("val-")))
            end for
        }

        "add and remove under contention" in runJVM {
            for
                c     <- Cache.init[Int, String](1)
                latch <- Latch.init(1)
                fibers <- Kyo.foreach(1 to 1000)(i =>
                    Fiber.initUnscoped(latch.await.andThen {
                        if i % 2 == 0 then c.add(i % 4, s"val-$i")
                        else c.remove(i % 4).andThen("removed")
                    })
                )
                _       <- latch.release
                results <- Kyo.foreach(fibers)(_.get)
                final0  <- c.get(0)
                final1  <- c.get(1)
                final2  <- c.get(2)
                final3  <- c.get(3)
            yield
                assert(results.size == 1000)
                // After contention settles, each key is either present or removed
                Seq(final0, final1, final2, final3).foreach { v =>
                    v.foreach(s => discard(assert(s.startsWith("val-"))))
                }
                succeed
            end for
        }
    }

end CacheTest
