package kyo.net.internal.util

/** A small open-addressing `int -> V` map with a primitive `int` key (no `Integer` boxing of the key), for the
  * [[kyo.net.internal.posix.PollerIoDriver]] pending tables (`pendingReads` / `pendingWritables` / `pendingAccepts`, each fd -> a handle or a
  * writable entry).
  *
  * Concurrent-collection audit: this map is NOT thread-safe. It is safe in the driver because EVERY mutation is
  * applied ONLY on the poll-loop carrier (the single-writer confinement): callers ENQUEUE a registration / change and the poll fiber
  * applies the `put` / `remove`, never the caller. The confinement precedes this swap (an unconfined map would be a data race).
  * kyo has no primitive-keyed map, so the raw arrays are the documented no-equivalent exception, poll-fiber-confined. The map lives on the
  * per-driver scratch: one driver's pending table never leaks into another's.
  *
  * Layout: a `keys` array (a slot holds [[IntRefMap.Empty]] when free) parallel to a `values` array of references, linear probing on `key & mask`.
  * Capacity is a power of two and grows (never shrinks) when the load factor would exceed 0.75. Removal uses backward-shift deletion (Robin-Hood
  * style) so a probe chain is never broken by a tombstone. A key of [[IntRefMap.Empty]] (`-1`) marks a free slot; OS file descriptors are always
  * `>= 0`, so no real key collides with it. `get` / `remove` return `null` when the key is absent; callers wrap with `Maybe`.
  */
final private[internal] class IntRefMap[V <: AnyRef](initialCapacity: Int = 16):

    import IntRefMap.Empty

    private var keys: Array[Int]      = new Array[Int](IntRefMap.roundUp(initialCapacity))
    private var values: Array[AnyRef] = new Array[AnyRef](keys.length)
    private var sz: Int               = 0
    java.util.Arrays.fill(keys, Empty)

    private def mask: Int = keys.length - 1

    /** The number of entries currently held. */
    def size: Int = sz

    /** Store `value` under `key`, overwriting any existing value.
      *
      * `key` MUST be `>= 0`: [[IntRefMap.Empty]] (`-1`) is the reserved free-slot marker, so storing it would write the marker into a live slot
      * and silently break every later lookup (the slot would read as free). The key domain is OS file descriptors, which are always `>= 0`, so a
      * negative key is a caller bug; it is rejected here rather than corrupting the table.
      */
    def put(key: Int, value: V): Unit =
        if key < 0 then throw new IllegalArgumentException(s"IntRefMap key must be >= 0 ($key reserved as the empty-slot marker)")
        if (sz + 1) * 4 > keys.length * 3 then grow()
        var i = key & mask
        while keys(i) != Empty && keys(i) != key do i = (i + 1) & mask
        if keys(i) == Empty then sz += 1
        keys(i) = key
        values(i) = value
    end put

    /** Whether `key` has an entry. */
    def contains(key: Int): Boolean = indexOf(key) >= 0

    /** Return the value stored under `key`, or `null` when `key` is absent. */
    def get(key: Int): V | Null =
        val i = indexOf(key)
        // Unsafe: erased-safe downcast of the reference slot. Only `put(key, value: V)` ever writes a slot, so a non-Empty slot at `key` holds a
        // `V`; the cast cannot fail and V is erased so it cannot throw.
        if i >= 0 then values(i).asInstanceOf[V] else null
    end get

    /** Remove `key` and return its value, or `null` when `key` was absent. Backward-shift keeps probe chains intact. */
    def remove(key: Int): V | Null =
        var i = indexOf(key)
        if i < 0 then null
        else
            // Unsafe: erased-safe downcast (see `get`): the slot at a found index was written by `put(key, value: V)`.
            val removed = values(i).asInstanceOf[V]
            sz -= 1
            var j        = (i + 1) & mask
            var continue = true
            while continue do
                val k = keys(j)
                if k == Empty then
                    keys(i) = Empty
                    values(i) = null
                    continue = false
                else
                    val home   = k & mask
                    val cyclic = (j - home) & mask
                    val gap    = (j - i) & mask
                    if cyclic >= gap then
                        keys(i) = k
                        values(i) = values(j)
                        i = j
                    end if
                    j = (j + 1) & mask
                end if
            end while
            removed
        end if
    end remove

    /** Apply `f` to every `(key, value)` entry. Used by the driver close path to fail every pending promise. Iteration order is unspecified. */
    def foreach(f: (Int, V) => Unit): Unit =
        var i = 0
        while i < keys.length do
            val k = keys(i)
            // Unsafe: erased-safe downcast (see `get`): a non-Empty slot holds a `V`.
            if k != Empty then f(k, values(i).asInstanceOf[V])
            i += 1
        end while
    end foreach

    /** Drop every entry (used by the driver close path after failing pending promises). */
    def clear(): Unit =
        java.util.Arrays.fill(keys, Empty)
        java.util.Arrays.fill(values, null)
        sz = 0
    end clear

    private def indexOf(key: Int): Int =
        var i = key & mask
        while keys(i) != Empty do
            if keys(i) == key then return i
            i = (i + 1) & mask
        end while
        -1
    end indexOf

    private def grow(): Unit =
        val oldKeys   = keys
        val oldValues = values
        val newKeys   = new Array[Int](oldKeys.length << 1)
        val newValues = new Array[AnyRef](newKeys.length)
        java.util.Arrays.fill(newKeys, Empty)
        keys = newKeys
        values = newValues
        val newMask = newKeys.length - 1
        var idx     = 0
        while idx < oldKeys.length do
            val k = oldKeys(idx)
            if k != Empty then
                var i = k & newMask
                while newKeys(i) != Empty do i = (i + 1) & newMask
                newKeys(i) = k
                newValues(i) = oldValues(idx)
            end if
            idx += 1
        end while
    end grow

end IntRefMap

private[internal] object IntRefMap:
    /** Free-slot marker. OS file descriptors are always `>= 0`, so this never collides with a real key. */
    final val Empty: Int = -1

    private def roundUp(n: Int): Int =
        var c = 1
        while c < n do c <<= 1
        math.max(c, 4)
    end roundUp
end IntRefMap
