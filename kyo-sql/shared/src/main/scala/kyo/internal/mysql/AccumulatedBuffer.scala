package kyo.internal.mysql

import kyo.Chunk
import kyo.Span

// --- AccumulatedBuffer ---

/** Connection-scoped state for accumulating incoming MySQL wire bytes.
  *
  * Holds the received-but-not-yet-consumed bytes as a growable buffer. `peek` and `consume` are used by [[MysqlPacket.readOne]] to
  * reassemble logical packets without copying more than necessary.
  *
  * Not thread-safe, each connection has its own [[AccumulatedBuffer]] accessed from a single fiber.
  */
final class AccumulatedBuffer:
    // Simple ring-free approach: accumulate all bytes in a list of Spans; consume from front
    private var pending: Chunk[Span[Byte]] = Chunk.empty
    private var totalBytes: Int            = 0
    // Absolute position within the first Span (how many bytes of pending.head have been consumed)
    private var headOffset: Int = 0

    /** Number of bytes available to read. */
    def available: Int = totalBytes

    /** Appends more bytes received from the network. */
    def append(bytes: Span[Byte]): Unit =
        if bytes.size > 0 then
            pending = pending.appended(bytes)
            totalBytes += bytes.size
    end append

    /** Peeks at the next `n` bytes without consuming them. Requires `available >= n`. */
    def peek(n: Int): Span[Byte] =
        val result  = new Array[Byte](n)
        var written = 0
        var chunks  = pending
        var offset  = headOffset
        // Performance: while loop for span-crossing peek, encapsulated, CONTRIBUTING permits this.
        while written < n do
            val head    = chunks.head
            val canRead = math.min(n - written, head.size - offset)
            val src     = head.toArray
            java.lang.System.arraycopy(src, offset, result, written, canRead)
            written += canRead
            if offset + canRead >= head.size then
                chunks = chunks.tail
                offset = 0
            else
                offset += canRead
            end if
        end while
        Span.from(result)
    end peek

    /** Consumes exactly `n` bytes and returns them. Requires `available >= n`. */
    def consume(n: Int): Span[Byte] =
        val result = peek(n)
        totalBytes -= n
        var remaining = n
        // Performance: while loop for span-crossing consume, encapsulated, CONTRIBUTING permits this.
        while remaining > 0 do
            val head   = pending.head
            val inHead = head.size - headOffset
            if inHead <= remaining then
                remaining -= inHead
                pending = pending.tail
                headOffset = 0
            else
                headOffset += remaining
                remaining = 0
            end if
        end while
        result
    end consume

end AccumulatedBuffer
