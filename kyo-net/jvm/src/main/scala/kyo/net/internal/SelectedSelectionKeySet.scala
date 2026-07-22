package kyo.net.internal

import java.nio.channels.SelectionKey
import java.util.Iterator as JIterator

/** Flat array-backed selection-key set for the NIO selector.
  *
  * The JDK's default selected-key set is a `HashSet`, which allocates a new iterator object on every poll cycle. This set replaces it via
  * reflection: `installSelectedKeySet` in `NioIoDriver` installs this instance into the `SelectorImpl.selectedKeys` and
  * `SelectorImpl.publicSelectedKeys` fields. After installation every `Selector.select()` call writes ready keys directly into `keys`, and
  * `dispatchReadyKeys` iterates the filled prefix without boxing.
  *
  * The set is write-optimised for the selector loop: `add` is O(1) amortised (array doubling), `reset` clears the filled prefix in O(1)
  * (a single int write), and `iterator` covers only the filled prefix. `remove`, `contains`, `size`, and `clear` satisfy the
  * `AbstractSet` contract; the selector never calls `remove` or `contains` on the selected-key set between poll cycles so those paths
  * are not on the hot path.
  *
  * `private[net]`: accessed only from `NioIoDriver` (same package) and its test.
  */
final private[net] class SelectedSelectionKeySet extends java.util.AbstractSet[SelectionKey]:

    // Initial capacity matches a small server's typical concurrent-connection count.
    private var keys: Array[SelectionKey] = new Array[SelectionKey](256)
    private var count: Int                = 0

    /** O(1) amortised append. Called by the JDK selector implementation when a key becomes ready. */
    override def add(key: SelectionKey): Boolean =
        if count >= keys.length then
            val grown = new Array[SelectionKey](keys.length * 2)
            // System.arraycopy: no kyo equivalent for a bulk array copy; fully qualified so kyo.System does not shadow it.
            java.lang.System.arraycopy(keys, 0, grown, 0, keys.length)
            keys = grown
        end if
        keys(count) = key
        count += 1
        true
    end add

    /** Reset the filled prefix to zero, reusing the existing array. Called after each dispatch cycle. */
    def reset(): Unit =
        // Null out references so GC can collect cancelled keys.
        var i = 0
        while i < count do
            keys(i) = null
            i += 1
        count = 0
    end reset

    /** Number of ready keys deposited since the last reset. */
    override def size(): Int = count

    /** Iterate over the filled prefix only. */
    override def iterator(): JIterator[SelectionKey] =
        val snapshot = count
        val arr      = keys
        new JIterator[SelectionKey]:
            private var pos      = 0
            def hasNext: Boolean = pos < snapshot
            def next(): SelectionKey =
                if pos >= snapshot then throw new java.util.NoSuchElementException()
                val k = arr(pos)
                pos += 1
                k
            end next
        end new
    end iterator

    /** Direct access to the backing array for allocation-free dispatch loops.
      *
      * Callers must read only indices `[0, size())`. The array content is valid only until the next `reset()` call.
      */
    def filledKeys: Array[SelectionKey] = keys

end SelectedSelectionKeySet
