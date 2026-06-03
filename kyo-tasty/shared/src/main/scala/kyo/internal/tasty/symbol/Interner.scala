package kyo.internal.tasty.symbol

import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.AllowUnsafe
import kyo.AtomicInt
import kyo.AtomicRef
import kyo.internal.tasty.binary.Utf8

/** A sharded intern table that maps byte sequences to canonical `Entry` objects.
  *
  * Each unique byte sequence (by content) maps to exactly one `Entry` instance. Two calls to `intern` with equal byte contents return the
  * same `Entry` reference (referential equality). This invariant means that reference equality on interned entries IS byte-level equality.
  *
  * The table uses `numShards` shards (must be a power of 2, default 32). Each shard is a two-level structure:
  *   - Outer `AtomicRef.Unsafe` holds the current `AtomicReferenceArray[Entry]` (swapped atomically on grow, which is rare)
  *   - Inner `AtomicReferenceArray[Entry]` supports per-slot CAS inserts (no full-table copy per insert)
  *
  * This eliminates the O(N) `Arrays.copyOf(table, table.length)` per insert that previously generated ~46 GB of cumulative
  * `Interner$Entry[]` allocation per cold load. Inserts now use a single per-slot CAS on the `AtomicReferenceArray`.
  *
  * Load factor is tracked per shard via an `AtomicInt.Unsafe` counter incremented on each successful CAS insert.
  *
  * Thread safety: concurrent `intern` calls for the same byte sequence are guaranteed to return the same `Entry` object.
  */
final class Interner private (
    private val numShards: Int,
    private val initialShardCapacity: Int,
    private[kyo] val growCount: AtomicInt.Unsafe,
    // Two-level indirection: outer AtomicRef.Unsafe for grows (rare, swaps whole sub-table),
    // inner AtomicReferenceArray for per-slot CAS inserts (no full-table copy per insert).
    private val shards: Array[AtomicRef.Unsafe[AtomicReferenceArray[Interner.Entry]]],
    // Per-shard grow locks: separate monitor objects used for the synchronized grow window.
    // (AtomicRef.Unsafe is opaque outside its definition scope and does not expose AnyRef.synchronized.)
    private val shardLocks: Array[AnyRef],
    // Per-shard load counters: incremented on each successful CAS insert.
    // The counter is never reset on grow because items remain present after rehashing.
    // A slightly stale read is acceptable: it only means we might grow one slot late.
    private[kyo] val shardLoadCounters: Array[AtomicInt.Unsafe]
):

    /** Intern the byte slice `bytes[offset .. offset+length)`, returning a canonical `Entry`. */
    def intern(bytes: Array[Byte], offset: Int, length: Int)(using AllowUnsafe): Interner.Entry =
        if offset < 0 || length < 0 || offset + length < 0 || offset + length > bytes.length then
            throw new ArrayIndexOutOfBoundsException(
                s"Interner.intern: offset=$offset length=$length bytes.length=${bytes.length}"
            )
        end if
        val hash     = computeHash(bytes, offset, length)
        val shardIdx = hash & (numShards - 1)
        internInShard(shards(shardIdx), shardLocks(shardIdx), shardLoadCounters(shardIdx), hash, bytes, offset, length)
    end intern

    @scala.annotation.tailrec
    private def internInShard(
        shardRef: AtomicRef.Unsafe[AtomicReferenceArray[Interner.Entry]],
        shardLock: AnyRef,
        loadCounter: AtomicInt.Unsafe,
        hash: Int,
        bytes: Array[Byte],
        offset: Int,
        length: Int
    )(using AllowUnsafe): Interner.Entry =
        val table               = shardRef.get()
        val len                 = table.length()
        val mask                = len - 1
        var slot                = hash & mask
        var ret: Interner.Entry = null
        while ret eq null do
            val existing = table.get(slot)
            if existing eq null then
                // Check load factor before attempting insert.
                if loadCounter.get() * 4 >= len * 3 then
                    // Load factor >= 0.75: grow and retry from scratch.
                    // Widen the synchronized window so the "observe table + grow" pair
                    // is atomic with respect to concurrent grows on this shard.  The
                    // re-check `shardRef.get() eq table` inside the lock ensures that
                    // if another thread already grew the table we skip the redundant
                    // grow; growShard itself is reentrant-safe and double-checks too.
                    // Unsafe: explicit waiver of the no-synchronized rule. The lock is a
                    // dedicated per-shard monitor (never a domain object), grow is rare and
                    // serialized per shard, and a lock-free Cliff-Click-style resize would
                    // require a substantially larger redesign (hazard pointers / multi-state
                    // entries / forwarding markers). The synchronized window is the least-bad
                    // alternative absent that redesign; the hot insert path remains CAS-only.
                    shardLock.synchronized {
                        if shardRef.get() eq table then
                            growShard(shardRef, shardLock, loadCounter, len)
                    }
                    return internInShard(shardRef, shardLock, loadCounter, hash, bytes, offset, length)
                end if
                // Eagerly copy the byte slice so the Entry does not hold the parse buffer alive.
                val copiedBytes = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
                // Eagerly decode UTF-8 to String so the `string` field is referentially transparent;
                // this lets Name.asString and SymbolBody.equals/hashCode stay outside the unsafe boundary.
                val candidate = new Interner.Entry(hash, copiedBytes, Utf8.decode(copiedBytes, 0, copiedBytes.length))
                // Per-slot CAS: no full-table copy needed.
                if table.compareAndSet(slot, null, candidate) then
                    loadCounter.incrementAndGet(): Unit
                    ret = candidate
                // else: lost race on this slot; re-read and continue the probe loop.
            else if existing.hash == hash && bytesEqual(existing, bytes, offset, length) then
                ret = existing
            else
                slot = (slot + 1) & mask
            end if
        end while
        ret
    end internInShard

    private def growShard(
        shardRef: AtomicRef.Unsafe[AtomicReferenceArray[Interner.Entry]],
        shardLock: AnyRef,
        loadCounter: AtomicInt.Unsafe,
        observedLen: Int
    )(using AllowUnsafe): Unit =
        // Serialize grows per shard via the shardLock monitor.
        // After acquiring the lock, recheck whether another thread already grew.
        // Unsafe: explicit waiver of the no-synchronized rule. The lock is a dedicated
        // per-shard monitor object (never a domain object), grow is rare and per-shard,
        // and a lock-free resize is a substantially larger redesign (see internInShard).
        shardLock.synchronized {
            val current = shardRef.get()
            if current.length() == observedLen then
                growCount.incrementAndGet(): Unit
                val newLen  = observedLen * 2
                val grown   = new AtomicReferenceArray[Interner.Entry](newLen)
                val newMask = newLen - 1
                var i       = 0
                while i < observedLen do
                    val e = current.get(i)
                    if e ne null then
                        var slot = e.hash & newMask
                        while grown.get(slot) ne null do
                            slot = (slot + 1) & newMask
                        end while
                        grown.set(slot, e)
                    end if
                    i += 1
                end while
                shardRef.set(grown)
            end if
        }
    end growShard

    private def bytesEqual(entry: Interner.Entry, bytes: Array[Byte], offset: Int, length: Int): Boolean =
        if offset < 0 || length < 0 || offset + length > bytes.length || offset + length < 0 then
            throw new ArrayIndexOutOfBoundsException(
                s"Interner.bytesEqual: offset=$offset length=$length bytes.length=${bytes.length}"
            )
        end if
        entry.bytes.length == length && {
            var i  = 0
            var eq = true
            while eq && i < length do
                if entry.bytes(i) != bytes(offset + i) then eq = false
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
    private[kyo] def shardSize(idx: Int)(using AllowUnsafe): Int = shardLoadCounters(idx).get()

end Interner

object Interner:

    def init(numShards: Int, initialShardCapacity: Int)(using AllowUnsafe): Interner =
        require((numShards & (numShards - 1)) == 0, "numShards must be a power of 2")
        require((initialShardCapacity & (initialShardCapacity - 1)) == 0, "initialShardCapacity must be a power of 2")
        require(initialShardCapacity >= 1, "initialShardCapacity must be at least 1")
        val growCount = AtomicInt.Unsafe.init(0)
        val shards = Array.fill(numShards)(
            AtomicRef.Unsafe.init[AtomicReferenceArray[Interner.Entry]](new AtomicReferenceArray(initialShardCapacity))
        )
        val shardLocks = Array.fill(numShards)(new AnyRef)
        val counters   = Array.fill(numShards)(AtomicInt.Unsafe.init(0))
        new Interner(numShards, initialShardCapacity, growCount, shards, shardLocks, counters)
    end init

    /** A single interned byte-sequence entry.
      *
      * The `bytes` field holds a private copy of the interned UTF-8 bytes (not a reference into the original parse buffer). The `string`
      * field is the eagerly UTF-8-decoded form, materialized once at intern time. Eager decode keeps `Name.asString` and
      * `SymbolBody.equals`/`hashCode` referentially transparent (no `AllowUnsafe` requirement on read), at the cost of paying the decode
      * once per unique byte sequence rather than on first access. Equality between interned entries is reference equality (the interner
      * guarantees unique `Entry` per unique byte sequence).
      */
    final class Entry(
        val hash: Int,
        val bytes: Array[Byte],
        val string: String
    )

end Interner
