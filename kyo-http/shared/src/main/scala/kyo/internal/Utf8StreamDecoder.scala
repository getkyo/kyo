package kyo.internal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** Stateful UTF-8 decoder for streaming byte chunks.
  *
  * This wrapper around Java's CharsetDecoder is necessary because CharsetDecoder alone doesn't handle the case where multi-byte UTF-8
  * sequences are split across chunk boundaries. When a chunk ends with an incomplete sequence (e.g., the first byte of a 3-byte character),
  * we must buffer those bytes and prepend them to the next chunk. Netty provides no equivalent utility for this use case.
  */
final private[kyo] class Utf8StreamDecoder private (
    private val decoder: CharsetDecoder,
    private var leftover: Array[Byte]
):
    /** Decode a chunk of bytes, returning decoded string. Incomplete trailing bytes are buffered for the next chunk. */
    def decode(chunk: Chunk[Byte])(using AllowUnsafe): String =
        val bytes = chunk.toArray
        // Prepend incomplete multi-byte sequence from previous chunk
        val input =
            if leftover.isEmpty then bytes
            else
                val combined = new Array[Byte](leftover.length + bytes.length)
                java.lang.System.arraycopy(leftover, 0, combined, 0, leftover.length)
                java.lang.System.arraycopy(bytes, 0, combined, leftover.length, bytes.length)
                leftover = Array.empty
                combined

        if input.isEmpty then ""
        else
            val inBuf = ByteBuffer.wrap(input)

            @tailrec def loop(outBuf: CharBuffer): CharBuffer =
                val result = decoder.decode(inBuf, outBuf, false)
                if result.isUnderflow then
                    // Underflow with remaining bytes means an incomplete multi-byte sequence at the end
                    if inBuf.hasRemaining then
                        leftover = new Array[Byte](inBuf.remaining)
                        discard(inBuf.get(leftover))
                    outBuf
                else if result.isMalformed || result.isUnmappable then
                    result.throwException()
                    outBuf // unreachable but needed for return type
                else if result.isOverflow then
                    // Grow output buffer and retry
                    val bigger = CharBuffer.allocate(outBuf.capacity() * 2)
                    outBuf.flip()
                    discard(bigger.put(outBuf))
                    loop(bigger)
                else
                    outBuf
                end if
            end loop

            val finalBuf = loop(CharBuffer.allocate(input.length * 2))
            finalBuf.flip()
            finalBuf.toString
        end if
    end decode

    /** Flush any remaining buffered bytes at end of stream. */
    def flush()(using AllowUnsafe): String =
        if leftover.isEmpty then
            decoder.reset()
            ""
        else
            val inBuf  = ByteBuffer.wrap(leftover)
            val outBuf = CharBuffer.allocate(leftover.length * 2)
            leftover = Array.empty

            val result = decoder.decode(inBuf, outBuf, true)
            if result.isMalformed || result.isUnmappable then
                result.throwException()

            val flushResult = decoder.flush(outBuf)
            if flushResult.isMalformed || flushResult.isUnmappable then
                flushResult.throwException()

            decoder.reset()
            outBuf.flip()
            outBuf.toString
        end if
    end flush

end Utf8StreamDecoder

private[kyo] object Utf8StreamDecoder:
    def init()(using AllowUnsafe): Utf8StreamDecoder =
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        new Utf8StreamDecoder(decoder, Array.empty)
    end init
end Utf8StreamDecoder
