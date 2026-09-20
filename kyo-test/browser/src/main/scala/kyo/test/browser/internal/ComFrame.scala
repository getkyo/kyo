package kyo.test.browser.internal

import kyo.*
import scala.annotation.tailrec

/** The framing of the Scala.js test adapter's com channel, as scalajs-env-nodejs speaks it: a big-endian 32-bit count of UTF-16 code
  * units, then those code units, each big-endian. Messages are strings of arbitrary content, including unpaired surrogates, so the frame
  * carries code units rather than an encoding of code points.
  */
private[browser] object ComFrame:

    /** The bytes of one frame carrying `message`. */
    def encode(message: String): Span[Byte] =
        val length = message.length
        val bytes  = new Array[Byte](4 + length * 2)
        bytes(0) = (length >>> 24).toByte
        bytes(1) = (length >>> 16).toByte
        bytes(2) = (length >>> 8).toByte
        bytes(3) = length.toByte
        @tailrec def loop(i: Int): Unit =
            if i < length then
                val unit = message.charAt(i)
                bytes(4 + i * 2) = (unit >>> 8).toByte
                bytes(5 + i * 2) = unit.toByte
                loop(i + 1)
        loop(0)
        Span.fromUnsafe(bytes)
    end encode

    /** Reassembles frames from byte chunks of arbitrary boundaries. `pending` holds the bytes of a frame not yet complete. */
    final case class Decoder(pending: Span[Byte]):

        /** Appends `bytes` and returns every message they complete, in order, with the decoder for what remains. Fails when a frame
          * declares a negative length, which no sender produces.
          */
        def feed(bytes: Span[Byte]): Result[String, (Chunk[String], Decoder)] =
            val buffer = if pending.isEmpty then bytes else pending.concat(bytes)
            @tailrec def loop(offset: Int, messages: Chunk[String]): Result[String, (Chunk[String], Decoder)] =
                if buffer.size - offset < 4 then Result.succeed((messages, Decoder(buffer.slice(offset, buffer.size))))
                else
                    val length = readInt(buffer, offset)
                    if length < 0 then Result.fail(s"com frame declares a negative length ($length)")
                    else if buffer.size - offset - 4 < length.toLong * 2 then
                        Result.succeed((messages, Decoder(buffer.slice(offset, buffer.size))))
                    else loop(offset + 4 + length * 2, messages.append(readUnits(buffer, offset + 4, length)))
                    end if
            loop(0, Chunk.empty)
        end feed
    end Decoder

    object Decoder:
        val empty: Decoder = Decoder(Span.empty[Byte])

    private def readInt(bytes: Span[Byte], offset: Int): Int =
        ((bytes(offset) & 0xff) << 24) | ((bytes(offset + 1) & 0xff) << 16) | ((bytes(offset + 2) & 0xff) << 8) | (bytes(offset + 3) & 0xff)

    private def readUnits(bytes: Span[Byte], offset: Int, length: Int): String =
        val units                       = new Array[Char](length)
        @tailrec def loop(i: Int): Unit =
            if i < length then
                units(i) = (((bytes(offset + i * 2) & 0xff) << 8) | (bytes(offset + i * 2 + 1) & 0xff)).toChar
                loop(i + 1)
        loop(0)
        new String(units)
    end readUnits

end ComFrame
