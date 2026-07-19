package kyo.net.internal

import java.nio.channels.Pipe
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kyo.*
import kyo.net.Test

/** Unit tests for the flat array-backed selected-key set NioIoDriver installs into the JDK selector via reflection.
  *
  * The set is a deliberate append-only structure, not a real java.util.Set: [[SelectedSelectionKeySet.add]] never dedupes (the JDK selector
  * deposits each ready key at most once per cycle), so it is exercised here with real SelectionKeys drawn from a live Selector rather than
  * fakes. The reflection install path is gated by module access (--add-opens java.base/sun.nio.ch) and is covered separately; these tests pin
  * the data structure's own contract: append order, O(1) growth past the initial capacity, reset reuse, and the count-snapshotting iterator.
  */
class SelectedSelectionKeySetTest extends Test:

    /** Open a Selector and register `n` distinct non-blocking channels on it, returning the Selector and its real SelectionKeys. The caller
      * closes the Selector (which cancels the keys and closes the registered channels' selector registration); each pipe's channels are closed too.
      */
    private def withKeys[A](n: Int)(f: (Selector, IndexedSeq[SelectionKey]) => A): A =
        val selector = Selector.open()
        val pipes    = IndexedSeq.fill(n)(Pipe.open())
        try
            val keys = pipes.map { pipe =>
                val src = pipe.source()
                src.configureBlocking(false)
                src.register(selector, SelectionKey.OP_READ)
            }
            f(selector, keys)
        finally
            pipes.foreach { pipe =>
                pipe.source().close()
                pipe.sink().close()
            }
            selector.close()
        end try
    end withKeys

    "SelectedSelectionKeySet" - {
        "starts empty" in {
            val set = new SelectedSelectionKeySet
            assert(set.size() == 0, s"a fresh set must be empty, got ${set.size()}")
            assert(!set.iterator().hasNext, "a fresh set's iterator must have no next")
        }

        "append preserves order and is visible through size, iterator, and filledKeys" in {
            withKeys(3) { (_, keys) =>
                val set = new SelectedSelectionKeySet
                keys.foreach(k => assert(set.add(k), "add returns true (append always succeeds)"))
                assert(set.size() == 3, s"size must reflect three appends, got ${set.size()}")

                val iterated = Iterator.unfold(set.iterator())(it => if it.hasNext then Some((it.next(), it)) else None).toList
                assert(iterated.corresponds(keys)(_ eq _), s"iterator must yield the keys in append order, got $iterated")

                val filled = set.filledKeys
                assert((0 until 3).map(filled(_)).corresponds(keys)(_ eq _), "filledKeys[0,size) must hold the appended keys in order")
            }
        }

        "grows past the initial 256 capacity without loss (append-only, duplicates retained)" in {
            withKeys(1) { (_, keys) =>
                val key = keys.head
                val set = new SelectedSelectionKeySet
                val n   = 300 // > 256, forces at least one doubling
                var i   = 0
                while i < n do
                    discard(set.add(key))
                    i += 1
                assert(set.size() == n, s"append-only set must retain all $n entries (no dedupe), got ${set.size()}")
                assert(set.filledKeys.length >= n, s"backing array must have grown to hold $n entries, got ${set.filledKeys.length}")
                assert(set.filledKeys(299) eq key, "the last appended slot must hold the key")
            }
        }

        "reset zeroes the count and nulls the filled prefix for GC, reusing the array" in {
            withKeys(2) { (_, keys) =>
                val set = new SelectedSelectionKeySet
                keys.foreach(k => discard(set.add(k)))
                val arrayBefore = set.filledKeys
                set.reset()
                assert(set.size() == 0, s"reset must zero the count, got ${set.size()}")
                assert(!set.iterator().hasNext, "reset must leave the iterator empty")
                assert(
                    set.filledKeys(0) == null && set.filledKeys(1) == null,
                    "reset must null the filled prefix so cancelled keys can be collected"
                )
                assert(set.filledKeys eq arrayBefore, "reset must reuse the existing backing array, not allocate a new one")
            }
        }

        "the iterator snapshots the count at creation and throws past its end" in {
            withKeys(2) { (_, keys) =>
                val set = new SelectedSelectionKeySet
                discard(set.add(keys(0)))
                val it = set.iterator()
                discard(set.add(keys(1))) // added after the iterator was created
                assert(it.next() eq keys(0), "iterator yields the first snapshotted key")
                assert(!it.hasNext, "iterator's length is fixed at the count when it was created, ignoring the later append")
                val threw =
                    try
                        discard(it.next()); false
                    catch case _: java.util.NoSuchElementException => true
                assert(threw, "next past the snapshot end must throw NoSuchElementException")
            }
        }
    }
end SelectedSelectionKeySetTest
