package kyo.net.internal.util

/** A small open-addressing `int -> long` map with primitive keys and values (no `Integer` / `Long` boxing), for the
  * [[kyo.net.internal.posix.PollerIoDriver]] `activeFds` table (fd -> current handle id).
  *
  * Concurrent-collection audit: this map is NOT thread-safe. It is safe in the driver because EVERY mutation is
  * applied ONLY on the poll-loop carrier (the single-writer confinement): callers ENQUEUE a registration / change and the poll fiber
  * applies the `put` / `remove` from its change-apply path, never the caller. The confinement precedes this primitive swap (an unconfined
  * primitive map would be a data race). kyo has no primitive-keyed map, so the raw arrays are the documented no-equivalent exception,
  * poll-fiber-confined. The map lives on the per-driver scratch: one driver's fd table never leaks into another's.
  *
  * Layout: a `keys` array (a slot holds [[Empty]] when free) parallel to a `values` array, linear probing on `key & mask`. Capacity is a power
  * of two and grows (never shrinks) when the load factor would exceed 0.75. Removal uses backward-shift deletion (Robin-Hood style) so a probe
  * chain is never broken by a tombstone: the slot after the removed one is shifted back when it would still find its home through the gap. A key
  * of [[Empty]] (`-1`) is reserved as the free-slot marker; OS file descriptors are always `>= 0`, so no real key collides with it.
  */
final private[internal] class IntLongMap(initialCapacity: Int = 16):

    import IntLongMap.Empty

    private var keys: Array[Int]    = new Array[Int](IntLongMap.roundUp(initialCapacity))
    private var values: Array[Long] = new Array[Long](keys.length)
    private var sz: Int             = 0
    java.util.Arrays.fill(keys, Empty)

    private def mask: Int = keys.length - 1

    /** The number of entries currently held. */
    def size: Int = sz

    /** Store `value` under `key`, overwriting any existing value for `key`.
      *
      * `key` MUST be `>= 0`: [[IntLongMap.Empty]] (`-1`) is the reserved free-slot marker, so storing it would write the marker into a live
      * slot and silently break every later lookup (the slot would read as free). The key domain is OS file descriptors, which are always `>= 0`,
      * so a negative key is a caller bug; it is rejected here rather than corrupting the table.
      */
    def put(key: Int, value: Long): Unit =
        if key < 0 then throw new IllegalArgumentException(s"IntLongMap key must be >= 0 ($key reserved as the empty-slot marker)")
        if (sz + 1) * 4 > keys.length * 3 then grow()
        var i = key & mask
        while keys(i) != Empty && keys(i) != key do i = (i + 1) & mask
        if keys(i) == Empty then sz += 1
        keys(i) = key
        values(i) = value
    end put

    /** Whether `key` has an entry. */
    def contains(key: Int): Boolean = indexOf(key) >= 0

    /** Return the value stored under `key`, or `default` when `key` is absent. */
    def getOrElse(key: Int, default: Long): Long =
        val i = indexOf(key)
        if i >= 0 then values(i) else default
    end getOrElse

    /** Drop every entry. */
    def clear(): Unit =
        java.util.Arrays.fill(keys, Empty)
        sz = 0
    end clear

    /** Remove `key` if present (backward-shift to keep probe chains intact). A no-op when `key` is absent. */
    def remove(key: Int): Unit =
        var i = indexOf(key)
        if i >= 0 then
            sz -= 1
            // Backward-shift deletion: walk forward from the freed slot; move back any entry that would otherwise be unreachable through the gap.
            var j        = (i + 1) & mask
            var continue = true
            while continue do
                val k = keys(j)
                if k == Empty then
                    keys(i) = Empty
                    continue = false
                else
                    val home = k & mask
                    // Move entry j back to i only when i lies in the cyclic range [home, j): otherwise j is already at or before its home and
                    // moving it would corrupt its lookup.
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
        end if
    end remove

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
        val newValues = new Array[Long](newKeys.length)
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

end IntLongMap

private[internal] object IntLongMap:
    /** Free-slot marker. OS file descriptors are always `>= 0`, so this never collides with a real key. */
    final val Empty: Int = -1

    private def roundUp(n: Int): Int =
        var c = 1
        while c < n do c <<= 1
        math.max(c, 4)
    end roundUp
end IntLongMap
