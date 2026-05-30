package kyo

import kyo.internal.tasty.symbol.Interner

class InternerTest extends Test:

    import AllowUnsafe.embrace.danger

    private def utf8Bytes(s: String): Array[Byte] =
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8)

    // Test 1: two intern calls for the same byte sequence return reference-equal Name instances.
    "two intern calls for the same bytes return reference-equal Name instances" in run {
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val bytes    = utf8Bytes("hello")
        val n1       = interner.intern(bytes, 0, bytes.length)
        val n2       = interner.intern(bytes, 0, bytes.length)
        assert(n1 eq n2)
    }

    // Test 2: two intern calls for different byte sequences return non-equal Name instances.
    "two intern calls for different bytes return non-equal Name instances" in run {
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val b1       = utf8Bytes("hello")
        val b2       = utf8Bytes("world")
        val n1       = interner.intern(b1, 0, b1.length)
        val n2       = interner.intern(b2, 0, b2.length)
        assert(!(n1 eq n2))
    }

    // Test 3: intern from two different shards (different hash values) produces distinct entries.
    "names in different shards are distinct entries" in run {
        // Use a 2-shard interner. FNV-1a("kyo") & 1 == 0 and FNV-1a("foo") & 1 == 1,
        // so "kyo" lands in shard 0 and "foo" lands in shard 1. Computed statically:
        //   FNV-1a("kyo") = 1444849266 -> & 1 = 0 (shard 0)
        //   FNV-1a("foo") = 703823575  -> & 1 = 1 (shard 1)
        val interner = new Interner(numShards = 2, initialShardCapacity = 16)
        val bKyo     = utf8Bytes("kyo")
        val bFoo     = utf8Bytes("foo")
        val nKyo     = interner.intern(bKyo, 0, bKyo.length)
        val nFoo     = interner.intern(bFoo, 0, bFoo.length)
        // Entries are not the same object.
        assert(!(nKyo eq nFoo))
        // Each shard has exactly 1 entry: shard 0 has "kyo", shard 1 has "foo".
        assert(interner.shardSize(0) == 1, "shard 0 should contain exactly 1 entry")
        assert(interner.shardSize(1) == 1, "shard 1 should contain exactly 1 entry")
    }

    // Test 4: Name.asString returns the correct UTF-8 decoded string.
    "Name.asString returns the correct decoded string" in run {
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val s        = "PlainClass"
        val bytes    = utf8Bytes(s)
        val entry    = interner.intern(bytes, 0, bytes.length)
        // Wrap via the package-internal factory so we can test the Name extension method.
        val name: Tasty.Name = Tasty.Name.wrap(entry)
        assert(name.asString == s)
    }

    // Test 5: Name.asString called twice returns the same (reference-equal) String (OnceCell caching).
    "Name.asString called twice returns the same String reference (OnceCell caching)" in run {
        val interner         = new Interner(numShards = 32, initialShardCapacity = 16)
        val bytes            = utf8Bytes("cached")
        val entry            = interner.intern(bytes, 0, bytes.length)
        val name: Tasty.Name = Tasty.Name.wrap(entry)
        val s1               = name.asString
        val s2               = name.asString
        assert(s1 eq s2)
    }

    // Test 6: CanEqual[Name, Name] holds for two names with the same bytes.
    "CanEqual[Name, Name]: two names interned from the same bytes are equal" in run {
        val interner       = new Interner(numShards = 32, initialShardCapacity = 16)
        val bytes          = utf8Bytes("equal")
        val n1: Tasty.Name = Tasty.Name.wrap(interner.intern(bytes, 0, bytes.length))
        val n2: Tasty.Name = Tasty.Name.wrap(interner.intern(bytes, 0, bytes.length))
        // CanEqual allows == comparison; since both are the same Entry, == is true.
        assert(n1 == n2)
    }

    // T-P6-1: pre-sized Interner with ample capacity avoids resizes for 1,000 entries.
    // With numShards=4 and initialShardCapacity=512, each shard holds 512 slots.
    // Load factor threshold per shard: 512 * 3 / 4 = 384. With 1,000 entries spread
    // across 4 shards (~250 avg per shard), well below the per-shard threshold, so no
    // grow events are expected.
    "T-P6-1: pre-sized Interner with initialShardCapacity=512 interns 1000 entries with growCount==0" in run {
        val interner = new Interner(numShards = 4, initialShardCapacity = 512)
        var i        = 0
        while i < 1000 do
            val bytes = utf8Bytes(s"entry-$i")
            interner.intern(bytes, 0, bytes.length)
            i += 1
        end while
        assert(interner.growCount.get() == 0, s"expected no grow events but growCount=${interner.growCount.get()}")
    }

    // T-P6-2: pre-sizing does not affect identity semantics within a single Interner.
    // Two Interners with different initial capacities each return reference-equal Entry
    // objects for the same interned byte sequence (within each Interner).
    "T-P6-2: pre-sized and small Interners both return reference-equal Entry for same bytes" in run {
        val smallInterner = new Interner(numShards = 4, initialShardCapacity = 16)
        val largeInterner = new Interner(numShards = 4, initialShardCapacity = 256)
        // Verify identity semantics for a set of byte sequences in both Interners.
        val seqs = Array("alpha", "beta", "gamma", "delta", "epsilon")
        var idx  = 0
        while idx < seqs.length do
            val seq   = seqs(idx)
            val bytes = utf8Bytes(seq)
            val s1    = smallInterner.intern(bytes, 0, bytes.length)
            val s2    = smallInterner.intern(bytes, 0, bytes.length)
            val l1    = largeInterner.intern(bytes, 0, bytes.length)
            val l2    = largeInterner.intern(bytes, 0, bytes.length)
            assert(s1 eq s2, s"smallInterner: expected reference equality for '$seq'")
            assert(l1 eq l2, s"largeInterner: expected reference equality for '$seq'")
            idx += 1
        end while
        assert(idx == seqs.length)
    }

    // T-P6-3: filling below the load threshold avoids resizes; exceeding it triggers one.
    // With numShards=4 and initialShardCapacity=256, each shard's grow threshold is
    // 256 * 3 / 4 = 192 entries. We intern 760 entries (well within 4 * 192 = 768) and
    // confirm growCount == 0. Then intern additional entries until at least one shard
    // exceeds its threshold and confirm growCount > 0.
    "T-P6-3: filling below load threshold avoids resize; exceeding it triggers grow" in run {
        val interner       = new Interner(numShards = 4, initialShardCapacity = 256)
        val belowThreshold = 760 // 4 * 190 = 760, safely below 4 * 192 = 768
        var i              = 0
        while i < belowThreshold do
            val bytes = utf8Bytes(s"t3-entry-$i")
            interner.intern(bytes, 0, bytes.length)
            i += 1
        end while
        assert(interner.growCount.get() == 0, s"expected 0 grows at $belowThreshold entries but got ${interner.growCount.get()}")
        // Push past the threshold: add more entries until a grow event is observed.
        var triggered = false
        while !triggered && i < belowThreshold + 100 do
            val bytes = utf8Bytes(s"t3-entry-$i")
            interner.intern(bytes, 0, bytes.length)
            i += 1
            if interner.growCount.get() > 0 then triggered = true
        end while
        assert(triggered, s"expected at least one grow event after exceeding load threshold, i=$i")
    }

    // T-P6-4: insert below load threshold with cap=64 numShards=4; assert growCount==0.
    // Then push past threshold; assert growCount > 0.
    // With numShards=4 and initialShardCapacity=64, the per-shard grow threshold is
    // 64 * 3 / 4 = 48. Insert 4 * 47 = 188 entries spread across shards (below all per-shard
    // thresholds) and confirm no grows. Then insert additional entries until a grow fires.
    "T-P6-4: growCount==0 below threshold, grows when threshold exceeded (cap=64 numShards=4)" in run {
        val interner = new Interner(numShards = 4, initialShardCapacity = 64)
        // Insert entries, steering them to specific shards to stay below 75% in each.
        // Use a generous count well under the minimum threshold across 4 shards.
        var i = 0
        while i < 180 do
            val bytes = utf8Bytes(s"t4-entry-$i")
            interner.intern(bytes, 0, bytes.length)
            i += 1
        end while
        assert(interner.growCount.get() == 0, s"expected 0 grows after $i entries but got ${interner.growCount.get()}")
        // Push past threshold: keep inserting until a grow is observed.
        var triggered = false
        while !triggered && i < 280 do
            val bytes = utf8Bytes(s"t4-entry-$i")
            interner.intern(bytes, 0, bytes.length)
            i += 1
            if interner.growCount.get() > 0 then triggered = true
        end while
        assert(triggered, s"expected at least one grow event after threshold, i=$i")
    }

    // T-P6-5: 8 concurrent fibers each intern 1000 unique sequences plus a set of shared keys.
    // All fibers that intern a shared key must get back the same (reference-equal) Entry instance.
    "T-P6-5: concurrent interns from 8 fibers preserve reference-equality for shared keys" in run {
        val interner    = new Interner(numShards = 16, initialShardCapacity = 256)
        val sharedCount = 50
        val sharedKeys  = Array.tabulate(sharedCount)(i => utf8Bytes(s"shared-key-$i"))
        // Each fiber: intern 1000 unique keys + all shared keys; collect the Entry for each shared key.
        val fiberCount = 8
        val fibers     = Chunk.fill(fiberCount)(())
        Async.foreach(fibers, fiberCount) { _ =>
            Sync.defer {
                // Intern 1000 unique entries to create contention.
                val salt = java.util.concurrent.ThreadLocalRandom.current().nextInt()
                var j    = 0
                while j < 1000 do
                    val bytes = utf8Bytes(s"unique-$salt-$j")
                    interner.intern(bytes, 0, bytes.length)
                    j += 1
                end while
                // Intern all shared keys and return the entries.
                Chunk.tabulate(sharedCount) { k =>
                    val b = sharedKeys(k)
                    interner.intern(b, 0, b.length)
                }
            }
        }.map { resultChunks =>
            // For each shared key, all 8 fibers must have the same Entry reference.
            var k = 0
            while k < sharedCount do
                val expected = resultChunks(0)(k)
                var f        = 1
                while f < fiberCount do
                    val got = resultChunks(f)(k)
                    assert(got eq expected, s"shared-key-$k: fiber $f returned different Entry instance")
                    f += 1
                end while
                k += 1
            end while
            succeed
        }
    }

    // Phase 03b / B10 / INV-010: intern rejects offset + length > bytes.length.
    // The guard in bytesEqual fires for negative offset before computeHash can throw.
    // For negative offset, computeHash is short-circuited: the guard fires on the first
    // bytesEqual call when a hash collision occurs. We test the overall intern contract:
    // calling intern with arguments that violate the bounds invariant throws AIOOBE.
    //
    // Case 1: offset + length > bytes.length (4+2=6 > 5). computeHash itself throws a JVM
    // ArrayIndexOutOfBoundsException at bytes(5) before reaching bytesEqual.
    "B10/INV-010: intern throws ArrayIndexOutOfBoundsException when offset + length > bytes.length" in run {
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val bytes    = Array[Byte](10, 20, 30, 40, 50) // length = 5
        intercept[ArrayIndexOutOfBoundsException](interner.intern(bytes, 4, 2))
        succeed
    }

    // Case 2: negative offset. The bytesEqual guard (offset < 0) fires on any hash-collision
    // probe. For a fresh interner with no prior entries the slot is empty and intern proceeds to
    // copyOfRange which also throws; either way AIOOBE is the contract.
    "B10: intern throws ArrayIndexOutOfBoundsException for negative offset" in run {
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val bytes    = Array[Byte](1, 2, 3, 4, 5)
        intercept[ArrayIndexOutOfBoundsException](interner.intern(bytes, -1, 3))
        succeed
    }

    // Case 3: negative length - bytesEqual guard path.
    // computeHash with length=-1 iterates zero times, producing the FNV-1a seed hash
    // (0x011c9dc5 after masking). We pre-populate shard 5 with an entry whose hash equals
    // that seed value so the probe loop hits a non-null slot, evaluates existing.hash == hash,
    // and calls bytesEqual(existing, bytes, 0, -1). The bytesEqual guard (length < 0) fires
    // and throws AIOOBE with our custom message before any array access occurs.
    "B10: bytesEqual guard throws ArrayIndexOutOfBoundsException with custom message for negative length" in run {
        // FNV-1a of zero iterations = 0x811c9dc5 masked = 0x011c9dc5 = 18413021
        val emptySliceHash = 0x011c9dc5
        // Plant a seed entry whose hash equals emptySliceHash. We need a byte sequence that
        // hashes to exactly emptySliceHash under FNV-1a. Rather than searching, we exploit the
        // fact that FNV-1a("") = seed constant. We intern the empty slice on a dedicated
        // pre-seeder interner first (to confirm hash), then plant the same hash in our test
        // interner by interning the empty slice directly.
        val interner  = new Interner(numShards = 32, initialShardCapacity = 16)
        val seedBytes = Array[Byte]() // empty -> hash = FNV seed masked = 0x011c9dc5
        interner.intern(seedBytes, 0, 0) // plant entry with hash=0x011c9dc5 in shard 5
        val bytes     = Array[Byte](1, 2, 3, 4, 5)
        // Now intern(bytes, 0, -1): computeHash returns 0x011c9dc5 (same hash as empty entry),
        // slot probe finds the pre-planted entry, hash matches, bytesEqual(entry, bytes, 0, -1)
        // fires the length < 0 guard and throws AIOOBE with our custom diagnostic message.
        val ex = intercept[ArrayIndexOutOfBoundsException](interner.intern(bytes, 0, -1))
        assert(ex.getMessage.contains("length=-1"), s"expected length=-1 in message: ${ex.getMessage}")
        assert(ex.getMessage.contains("bytes.length=5"), s"expected bytes.length=5 in message: ${ex.getMessage}")
    }

    // Case 4: guard allows valid bounds - zero-length intern at a mid-array offset succeeds.
    // offset=2, length=0 => offset+length=2 <= 5, all guard conditions false, no throw.
    "B10: zero-length intern with valid offset succeeds (guard allows valid bounds)" in run {
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val bytes    = Array[Byte](1, 2, 3, 4, 5)
        val entry    = interner.intern(bytes, 2, 0)
        assert(entry ne null)
        succeed
    }

    // Phase 07a / B12: concurrent grow-and-insert under contention preserves all entries.
    // Interner with numShards=2 and initialShardCapacity=4 so shards grow frequently.
    // 8 fibers each insert 1000 unique byte sequences; after all fibers complete the
    // total unique entry count equals 8000 and every byte sequence re-interned returns
    // the same (reference-equal) Entry as the original insert.
    "B12/Phase-07a: concurrent grow-and-insert preserves all entries under contention" in run {
        val interner   = new Interner(numShards = 2, initialShardCapacity = 4)
        val fiberCount = 8
        val perFiber   = 1000
        val fibers     = Chunk.fill(fiberCount)(())
        // Use an atomic counter so each fiber gets a unique ID even though all elements are ().
        val fiberIdCounter = new java.util.concurrent.atomic.AtomicInteger(0)
        Async.foreach(fibers, fiberCount) { _ =>
            Sync.defer {
                val fid = fiberIdCounter.getAndIncrement()
                // Each fiber inserts perFiber unique sequences using its assigned ID.
                Chunk.tabulate(perFiber) { j =>
                    val bytes = utf8Bytes(s"b12-f$fid-$j")
                    interner.intern(bytes, 0, bytes.length)
                }
            }
        }.map { allChunks =>
            // Flatten the results and re-intern every sequence: must get the same Entry back.
            var total = 0
            var k     = 0
            while k < allChunks.length do
                var j = 0
                while j < allChunks(k).length do
                    val original = allChunks(k)(j)
                    // Re-intern via the original entry's bytes: must be reference-equal.
                    val again = interner.intern(original.bytes, 0, original.bytes.length)
                    assert(again eq original, s"fiber $k entry $j: re-intern returned different Entry")
                    total += 1
                    j += 1
                end while
                k += 1
            end while
            assert(total == fiberCount * perFiber, s"expected ${fiberCount * perFiber} total entries, got $total")
            succeed
        }
    }

    // Phase 07a / B12: grow during contention preserves reference equality.
    // 4 fibers all race to intern the same byte sequence [1, 2, 3] on a single-shard
    // interner with initialShardCapacity=2 (grows immediately under any real load).
    // Every fiber must return the exact same Entry reference.
    "B12/Phase-07a: grow during contention preserves reference equality for shared key" in run {
        val interner   = new Interner(numShards = 1, initialShardCapacity = 2)
        val sharedKey  = Array[Byte](1, 2, 3)
        val fiberCount = 4
        val fibers     = Chunk.fill(fiberCount)(())
        Async.foreach(fibers, fiberCount) { _ =>
            Sync.defer {
                interner.intern(sharedKey, 0, sharedKey.length)
            }
        }.map { results =>
            val expected = results(0)
            var f        = 1
            while f < fiberCount do
                assert(results(f) eq expected, s"fiber $f returned a different Entry for the shared key")
                f += 1
            end while
            succeed
        }
    }

end InternerTest
