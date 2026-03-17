package kyo

import java.util.ArrayList
import scala.annotation.tailrec

/** Thread-local buffer used during the STM commit protocol to lock, validate, and commit TRef entries.
  *
  * Pairs are stored as consecutive elements [ref0, entry0, ref1, entry1, ...] to avoid tuple allocations. All index parameters refer to
  * logical pair indices; the stride encoding is handled internally. Entries are sorted by TRef id before lock acquisition to prevent
  * deadlocks.
  */
opaque type CommitBuffer = ArrayList[Any]

private[kyo] object CommitBuffer:

    private inline def insertionSortThreshold = 8

    private val cache = new ThreadLocal[CommitBuffer]

    /** Provides a thread-local cached buffer to the given function, clearing it after use. The buffer will not be cleared on exception.
      * This is safe because `f` should never throw unless it's a fatal exception, which shouldn't be handled.
      */
    inline def withBuffer[A](inline f: CommitBuffer => A)(using AllowUnsafe): A =
        var buffer = cache.get()
        if buffer == null then
            buffer = new ArrayList[Any]
            cache.set(buffer)
        val result = f(buffer)
        buffer.clear()
        result
    end withBuffer

    extension (self: CommitBuffer)

        inline def ref(idx: Int)(using AllowUnsafe): TRef[Any] =
            self.get(idx * 2).asInstanceOf[TRef[Any]]

        inline def entry(idx: Int)(using AllowUnsafe): TRefLog.Entry[Any] =
            self.get(idx * 2 + 1).asInstanceOf[TRefLog.Entry[Any]]

        inline def append(inline ref: TRef[Any], inline entry: TRefLog.Entry[Any])(using AllowUnsafe): Unit =
            discard(self.add(ref))
            discard(self.add(entry))

        def sort(size: Int)(using AllowUnsafe): Unit =
            if size <= insertionSortThreshold then
                insertionSort(self, size)
            else
                quickSort(self, size)

        inline def lock(tick: STM.Tick, size: Int)(using AllowUnsafe): Int =
            @tailrec def loop(idx: Int): Int =
                if idx == size then size
                else if !self.ref(idx).lock(tick, self.entry(idx)) then idx
                else loop(idx + 1)
            loop(0)
        end lock

        inline def unlock(upTo: Int)(using AllowUnsafe): Unit =
            @tailrec def loop(idx: Int): Unit =
                if idx < upTo then
                    self.ref(idx).unlock(self.entry(idx))
                    loop(idx + 1)
            loop(0)
        end unlock

        inline def commit(tick: STM.Tick, size: Int)(using AllowUnsafe): Unit =
            @tailrec def loop(idx: Int): Unit =
                if idx < size then
                    self.ref(idx).commit(tick, self.entry(idx))
                    loop(idx + 1)
            loop(0)
        end commit

        def clear(): Unit = self.clear()
    end extension

    private inline def sortKey(buffer: CommitBuffer, idx: Int): Int =
        buffer.get(idx * 2).asInstanceOf[TRef[Any]].id

    private def swap(buffer: CommitBuffer, i: Int, j: Int): Unit =
        val ri    = i * 2
        val rj    = j * 2
        val ref   = buffer.get(ri)
        val entry = buffer.get(ri + 1)
        buffer.set(ri, buffer.get(rj))
        buffer.set(ri + 1, buffer.get(rj + 1))
        buffer.set(rj, ref)
        discard(buffer.set(rj + 1, entry))
    end swap

    private def insertionSort(buffer: CommitBuffer, size: Int): Unit =
        var i = 1
        while i < size do
            var j = i
            while j > 0 && sortKey(buffer, j) < sortKey(buffer, j - 1) do
                swap(buffer, j, j - 1)
                j -= 1
            i += 1
        end while
    end insertionSort

    private def quickSort(buffer: CommitBuffer, size: Int): Unit =
        @tailrec def partitionLoop(low: Int, hi: Int, pivot: Int, i: Int, j: Int): Int =
            if j >= hi then
                swap(buffer, i, pivot)
                i
            else if sortKey(buffer, j) < sortKey(buffer, pivot) then
                swap(buffer, i, j)
                partitionLoop(low, hi, pivot, i + 1, j + 1)
            else
                partitionLoop(low, hi, pivot, i, j + 1)

        def partition(low: Int, hi: Int): Int =
            partitionLoop(low, hi, hi, low, low)

        def loop(low: Int, hi: Int): Unit =
            if low < hi then
                val p = partition(low, hi)
                loop(low, p - 1)
                loop(p + 1, hi)

        loop(0, size - 1)
    end quickSort
end CommitBuffer
