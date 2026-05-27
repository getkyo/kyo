package kyo.internal.tasty.symbol

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.internal.tasty.binary.Utf8

/** A sharded intern table that maps byte sequences to canonical `Entry` objects.
  *
  * Each unique byte sequence (by content) maps to exactly one `Entry` instance. Two calls to `intern` with equal byte contents return the
  * same `Entry` reference (referential equality). This invariant means that reference equality on interned entries IS byte-level equality.
  *
  * The table uses `numShards` shards (must be a power of 2, default 32). Each shard is a lock-free linear-probe hash table backed by an
  * `AtomicReference[Array[Entry]]`. Reads are lock-free; inserts use CAS with retry on grow.
  *
  * Load factor is tracked per shard via an `AtomicInteger` counter that is incremented on each successful CAS insert. This replaces the
  * previous O(N) `countFilled` scan with an O(1) check.
  *
  * Thread safety: concurrent `intern` calls for the same byte sequence are guaranteed to return the same `Entry` object.
  */
final class Interner(numShards: Int, initialShardCapacity: Int):
    require((numShards & (numShards - 1)) == 0, "numShards must be a power of 2")
    require((initialShardCapacity & (initialShardCapacity - 1)) == 0, "initialShardCapacity must be a power of 2")
    require(initialShardCapacity >= 1, "initialShardCapacity must be at least 1")

    private[kyo] val growCount: AtomicInteger =
        new AtomicInteger(0)

    private val shards: Array[AtomicReference[Array[Interner.Entry]]] =
        Array.tabulate(numShards)(_ =>
            new AtomicReference(new Array[Interner.Entry](initialShardCapacity))
        )

    // Per-shard load counters: incremented on each successful CAS insert.
    // The counter is never reset on grow because the items remain present after copying.
    // A slightly stale read is acceptable: it only means we might grow one slot late.
    private[kyo] val shardLoadCounters: Array[AtomicInteger] =
        Array.fill(numShards)(new AtomicInteger(0))

    /** Intern the byte slice `bytes[offset .. offset+length)`, returning a canonical `Entry`. */
    def intern(bytes: Array[Byte], offset: Int, length: Int): Interner.Entry =
        val hash     = computeHash(bytes, offset, length)
        val shardIdx = hash & (numShards - 1)
        val shard    = shards(shardIdx)
        internInShard(shard, shardLoadCounters(shardIdx), hash, bytes, offset, length)
    end intern

    private def internInShard(
        shard: AtomicReference[Array[Interner.Entry]],
        loadCounter: AtomicInteger,
        hash: Int,
        bytes: Array[Byte],
        offset: Int,
        length: Int
    ): Interner.Entry =
        val table = shard.get()
        val mask  = table.length - 1
        var slot  = hash & mask
        var i     = 0
        // Linear probe: look for existing entry or empty slot.
        while i < table.length do
            val entry = table(slot)
            if entry == null then
                // Empty slot: try to insert a new entry via CAS.
                val newEntry = new Interner.Entry(
                    hash,
                    bytes,
                    offset,
                    length,
                    new OnceCell(() => Utf8.decode(bytes, offset, length))
                )
                // O(1) load factor check using the per-shard counter.
                if loadCounter.get() * 4 >= table.length * 3 then
                    // Load factor >= 0.75: grow and retry from scratch.
                    grow(shard, loadCounter, table)
                    return internInShard(shard, loadCounter, hash, bytes, offset, length)
                end if
                // Atomically claim the slot by publishing from null to newEntry.
                // We can't CAS a single slot in an Array atomically without a lock;
                // instead we use AtomicReference on the entire table and swap in a copy.
                // Strategy: copy table, set slot, CAS. If CAS fails, retry from scratch.
                val copy = java.util.Arrays.copyOf(table, table.length)
                // Re-check the slot in the copy (another thread may have filled it).
                if copy(slot) == null then
                    copy(slot) = newEntry
                    if shard.compareAndSet(table, copy) then
                        // Successful insert: increment the load counter.
                        loadCounter.incrementAndGet(): Unit
                        return newEntry
                    else
                        // CAS failed: another thread modified the table. Retry.
                        return internInShard(shard, loadCounter, hash, bytes, offset, length)
                    end if
                else
                    // Another thread filled this slot while we were preparing. Check it.
                    val concurrent = copy(slot)
                    if concurrent.hash == hash && bytesEqual(concurrent, bytes, offset, length) then
                        return concurrent
                    else
                        return internInShard(shard, loadCounter, hash, bytes, offset, length)
                    end if
                end if
            else if entry.hash == hash && bytesEqual(entry, bytes, offset, length) then
                // Found an existing entry with the same content.
                return entry
            else
                // Collision: linear probe to next slot.
                slot = (slot + 1) & mask
                i += 1
            end if
        end while
        // Table is full (shouldn't happen if load factor check is working): grow and retry.
        grow(shard, loadCounter, table)
        internInShard(shard, loadCounter, hash, bytes, offset, length)
    end internInShard

    private def grow(
        shard: AtomicReference[Array[Interner.Entry]],
        loadCounter: AtomicInteger,
        expected: Array[Interner.Entry]
    ): Unit =
        val current = shard.get()
        if current eq expected then
            growCount.incrementAndGet(): Unit
            val newTable = new Array[Interner.Entry](expected.length * 2)
            val newMask  = newTable.length - 1
            var i        = 0
            while i < expected.length do
                val entry = expected(i)
                if entry != null then
                    var slot = entry.hash & newMask
                    while newTable(slot) != null do
                        slot = (slot + 1) & newMask
                    newTable(slot) = entry
                end if
                i += 1
            end while
            // If CAS fails, another thread already grew; that's fine.
            shard.compareAndSet(expected, newTable): Unit
        end if
    end grow

    private def bytesEqual(entry: Interner.Entry, bytes: Array[Byte], offset: Int, length: Int): Boolean =
        entry.length == length && {
            var i  = 0
            var eq = true
            while eq && i < length do
                if entry.bytes(entry.offset + i) != bytes(offset + i) then eq = false
                i += 1
            eq
        }
    end bytesEqual

    /** Compute a hash for the byte slice. Uses FNV-1a for cross-platform simplicity. */
    private def computeHash(bytes: Array[Byte], offset: Int, length: Int): Int =
        var h = 0x811c9dc5
        var i = 0
        while i < length do
            h ^= (bytes(offset + i) & 0xff)
            h = h * 0x01000193
            i += 1
        end while
        h & 0x7fffffff // keep positive
    end computeHash

    /** Return the number of filled slots in shard `idx`. Package-accessible; for testing only. */
    private[kyo] def shardSize(idx: Int): Int = shardLoadCounters(idx).get()

end Interner

object Interner:

    /** A single interned byte-sequence entry.
      *
      * The `string` field is a lazy `OnceCell[String]` that decodes the interned bytes to a `String` on first access. Equality between
      * interned entries is reference equality (the interner guarantees unique `Entry` per unique byte sequence).
      */
    final class Entry(
        val hash: Int,
        val bytes: Array[Byte],
        val offset: Int,
        val length: Int,
        val string: OnceCell[String]
    )

end Interner
