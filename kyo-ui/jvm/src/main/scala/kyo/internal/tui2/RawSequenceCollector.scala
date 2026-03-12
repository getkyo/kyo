package kyo.internal.tui2

/** Pre-allocated collector for raw escape sequences to emit after cell flush. Stores fully-assembled byte sequences (including cursor
  * positioning).
  */
final private[kyo] class RawSequenceCollector(initialCapacity: Int = 8):
    private var seqs  = new Array[Array[Byte]](initialCapacity)
    private var count = 0

    /** Add a fully-assembled sequence (including cursor positioning). */
    def add(seq: Array[Byte]): Unit =
        if count == seqs.length then grow()
        seqs(count) = seq
        count += 1
    end add

    def size: Int                  = count
    def apply(i: Int): Array[Byte] = seqs(i)
    def reset(): Unit              = count = 0

    private def grow(): Unit =
        val n  = seqs.length * 2
        val ns = new Array[Array[Byte]](n)
        java.lang.System.arraycopy(seqs, 0, ns, 0, count)
        seqs = ns
    end grow

end RawSequenceCollector
