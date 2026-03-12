package kyo.internal.tui2

import kyo.Signal

/** Pre-allocated, reusable collector for signals encountered during tree walk.
  *
  * Append-only during render, indexed access during await, reset before next frame. Uses direct indexed access instead of toSpan/toArray to
  * avoid per-frame copies.
  */
final private[kyo] class SignalCollector(initialCapacity: Int = 64):

    private var signals = new Array[Signal[?]](initialCapacity)
    private var count   = 0

    def reset(): Unit = count = 0

    def add(s: Signal[?]): Unit =
        if count == signals.length then grow()
        signals(count) = s
        count += 1
    end add

    def size: Int        = count
    def isEmpty: Boolean = count == 0

    def apply(i: Int): Signal[?] = signals(i)

    private def grow(): Unit =
        val newSignals = new Array[Signal[?]](signals.length * 2)
        java.lang.System.arraycopy(signals, 0, newSignals, 0, count)
        signals = newSignals
    end grow

end SignalCollector
