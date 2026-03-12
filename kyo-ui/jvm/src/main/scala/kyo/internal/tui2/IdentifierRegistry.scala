package kyo.internal.tui2

import kyo.Maybe
import kyo.Maybe.*
import kyo.UI
import scala.annotation.tailrec

/** Pre-allocated parallel-array registry for element identifiers. Follows the same pattern as OverlayCollector / SignalCollector.
  */
final private[kyo] class IdentifierRegistry(initialCapacity: Int = 64):

    private var keys  = new Array[String](initialCapacity)
    private var elems = new Array[UI.Element](initialCapacity)
    private var count = 0

    def reset(): Unit = count = 0

    def register(id: String, elem: UI.Element): Unit =
        if count == keys.length then grow()
        keys(count) = id
        elems(count) = elem
        count += 1
    end register

    def lookup(id: String): Maybe[UI.Element] =
        @tailrec def loop(i: Int): Maybe[UI.Element] =
            if i >= count then Absent
            else if keys(i) == id then Present(elems(i))
            else loop(i + 1)
        loop(0)
    end lookup

    private def grow(): Unit =
        val newKeys  = new Array[String](keys.length * 2)
        val newElems = new Array[UI.Element](elems.length * 2)
        java.lang.System.arraycopy(keys, 0, newKeys, 0, count)
        java.lang.System.arraycopy(elems, 0, newElems, 0, count)
        keys = newKeys
        elems = newElems
    end grow

end IdentifierRegistry
