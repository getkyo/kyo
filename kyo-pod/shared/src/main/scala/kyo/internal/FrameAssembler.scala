package kyo.internal

import kyo.*
import kyo.Container.LogEntry

/** Pure-functional demultiplexer for Docker's multiplexed stdout/stderr stream format.
  *
  * Frame layout: byte[0] = stream type (1 = stdout, 2 = stderr, anything else = stdout), bytes[1-3] = padding, bytes[4-7] = uint32
  * big-endian payload size, then `size` bytes payload.
  *
  * Implemented as a [[Pipe]] that threads a carry-over [[Span]] across input chunks via [[Loop]], so frames whose header or payload
  * straddle a chunk boundary are correctly reassembled and emitted once the rest arrives in a later poll. Stream end discards any partial
  * residual.
  */
object FrameAssembler:

    def pipe(using
        Tag[Poll[Chunk[Span[Byte]]]],
        Tag[Emit[Chunk[(String, LogEntry.Source)]]],
        Frame
    ): Pipe[Span[Byte], (String, LogEntry.Source), Any] =
        Pipe:
            Loop(Span.empty[Byte]) { carry =>
                Poll.andMap[Chunk[Span[Byte]]] {
                    case Absent => Loop.done
                    case Present(spans) =>
                        val combined           = spans.foldLeft(carry)((acc, s) => acc ++ s)
                        val (leftover, frames) = parseFrames(combined)
                        Emit.valueWith(frames)(Loop.continue(leftover))
                }
            }

    /** Parse all complete frames from `buf`. Returns the unconsumed suffix (a partial frame, possibly empty) and the parsed frames. */
    private def parseFrames(buf: Span[Byte]): (Span[Byte], Chunk[(String, LogEntry.Source)]) =
        val arr = buf.toArray
        val len = arr.length
        val out = Chunk.newBuilder[(String, LogEntry.Source)]
        @scala.annotation.tailrec
        def parse(offset: Int): Int =
            if offset + 8 > len then offset
            else
                val streamType = arr(offset) & 0xff
                val size = ((arr(offset + 4) & 0xff) << 24) |
                    ((arr(offset + 5) & 0xff) << 16) |
                    ((arr(offset + 6) & 0xff) << 8) |
                    (arr(offset + 7) & 0xff)
                if offset + 8 + size > len then offset
                else
                    val content = new String(arr, offset + 8, size, java.nio.charset.StandardCharsets.UTF_8)
                    val source  = if streamType == 2 then LogEntry.Source.Stderr else LogEntry.Source.Stdout
                    out.addOne((content, source))
                    parse(offset + 8 + size)
                end if
        end parse
        val finalOffset = parse(0)
        val leftover    = if finalOffset >= len then Span.empty[Byte] else buf.slice(finalOffset, len)
        (leftover, out.result())
    end parseFrames

end FrameAssembler
