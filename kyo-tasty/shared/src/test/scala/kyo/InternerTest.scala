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

end InternerTest
