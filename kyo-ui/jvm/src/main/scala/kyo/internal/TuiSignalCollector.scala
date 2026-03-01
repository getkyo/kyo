package kyo.internal

import kyo.Signal
import kyo.Span

/** Pre-allocated, reusable collector for signals encountered during flatten.
  *
  * Append-only during flatten, read-only during await, reset before next frame.
  */
final private[kyo] class TuiSignalCollector(initialCapacity: Int = 64):

    private var signals = new Array[Signal[?]](initialCapacity)
    private var count   = 0

    def reset(): Unit = count = 0

    def add(s: Signal[?]): Unit =
        if count == signals.length then grow()
        signals(count) = s
        count += 1
    end add

    def toSpan: Span[Signal[?]] =
        if count == 0 then Span.empty[Signal[?]]
        else Span.fromUnsafe(java.util.Arrays.copyOf(signals, count))

    def size: Int = count

    private def grow(): Unit =
        val newSignals = new Array[Signal[?]](signals.length * 2)
        java.lang.System.arraycopy(signals, 0, newSignals, 0, count)
        signals = newSignals
    end grow

end TuiSignalCollector
