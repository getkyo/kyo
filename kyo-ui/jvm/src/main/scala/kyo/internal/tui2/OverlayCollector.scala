package kyo.internal.tui2

import kyo.UI

/** Deferred overlay rendering collector.
  *
  * Elements with position:overlay are collected during the main tree walk, then painted in a second pass after the primary render
  * completes. Pre-allocated array, reused across frames.
  */
final private[kyo] class OverlayCollector(initialCapacity: Int = 4):

    private var elems = new Array[UI.Element](initialCapacity)
    private var count = 0

    def add(elem: UI.Element): Unit =
        if count == elems.length then grow()
        elems(count) = elem
        count += 1
    end add

    def size: Int                 = count
    def apply(i: Int): UI.Element = elems(i)

    def reset(): Unit = count = 0

    private def grow(): Unit =
        val next = new Array[UI.Element](elems.length * 2)
        java.lang.System.arraycopy(elems, 0, next, 0, count)
        elems = next
    end grow

end OverlayCollector
