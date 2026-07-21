package kyo.internal.mysql

import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Span

/** Low-level MySQL packet framing.
  *
  * Every MySQL wire packet is framed as:
  *   - 3 bytes: payload length (little-endian uint24)
  *   - 1 byte: sequence ID (increments per-packet within a command; resets to 0 at the start of each new command)
  *   - N bytes: payload
  *
  * Payloads >= 16MB-1 bytes (0xFFFFFF = 16777215) are split across multiple packets, each carrying exactly 0xFFFFFF bytes except (possibly)
  * the last. If the payload is an exact multiple of 0xFFFFFF bytes, a final empty packet (length=0) MUST be sent so the receiver knows
  * there is no more data.
  *
  * Reference: MySQL Internals Manual — MySQL Packets
  */
object MysqlPacket:

    /** Maximum payload bytes in a single MySQL packet (16MB - 1). */
    val MaxPayload: Int = 0xffffff // 16777215

    /** The size of a packet header in bytes (3 bytes length + 1 byte sequence ID). */
    val HeaderSize: Int = 4

    /** Writes one logical payload as one or more framed MySQL packets.
      *
      * If `payload.size < MaxPayload`, a single packet is produced. If `payload.size >= MaxPayload`, the payload is split into chunks of
      * exactly `MaxPayload` bytes, with a trailing empty (length=0) packet appended when the payload size is an exact multiple of
      * `MaxPayload`.
      *
      * @param payload
      *   the logical payload bytes
      * @param seq
      *   the starting sequence ID (typically 0 for the first packet of a command)
      * @return
      *   a [[Chunk]] of framed packet byte spans (header + payload slice per element)
      */
    def writeOne(payload: Span[Byte], seq: Int): Chunk[Span[Byte]] =
        val total = payload.size
        if total < MaxPayload then
            // Fast path: single packet
            Chunk(framePacket(payload, seq))
        else
            // Slow path: split into MaxPayload-byte chunks
            var result     = Chunk.empty[Span[Byte]]
            var offset     = 0
            var currentSeq = seq & 0xff
            // Performance: while loop for split-packet framing — encapsulated, CONTRIBUTING permits this.
            while offset < total do
                val chunkLen = math.min(MaxPayload, total - offset)
                val slice    = payload.slice(offset, offset + chunkLen)
                result = result.appended(framePacket(slice, currentSeq))
                offset += chunkLen
                currentSeq = (currentSeq + 1) & 0xff
            end while
            // If the payload was an exact multiple of MaxPayload, append a trailing empty packet
            if total % MaxPayload == 0 then
                result = result.appended(framePacket(Span.empty, currentSeq))
            result
        end if
    end writeOne

    /** Reassembles one logical payload from an [[AccumulatedBuffer]].
      *
      * Reads packets from `buf` until it receives one with a payload length < [[MaxPayload]], then concatenates all payload slices into a
      * single [[Span[Byte]]].
      *
      * Returns [[Maybe.Absent]] if the buffer does not yet contain a complete packet.
      *
      * @param buf
      *   connection-scoped state holding pending bytes and the next expected sequence ID
      * @return
      *   [[Maybe.Present]] of `(reassembledPayload, firstSeqId)` when a complete logical packet is available; [[Maybe.Absent]] otherwise
      */
    def readOne(buf: AccumulatedBuffer): Maybe[(Span[Byte], Int)] =
        var payloadChunks = Chunk.empty[Span[Byte]]
        var firstSeq      = -1
        var done          = false

        // Performance: while loop for packet reassembly — encapsulated, CONTRIBUTING permits this.
        while !done do
            if buf.available < HeaderSize then
                // Not enough bytes for a header
                done = true
            else
                val headerBytes = buf.peek(HeaderSize)
                val payloadLen  = (headerBytes(0) & 0xff) | ((headerBytes(1) & 0xff) << 8) | ((headerBytes(2) & 0xff) << 16)
                val seqId       = headerBytes(3) & 0xff
                val totalNeeded = HeaderSize + payloadLen
                if buf.available < totalNeeded then
                    // Not enough bytes for the full packet
                    done = true
                else
                    // Consume the header (discard header bytes — we already decoded them via peek)
                    val _            = buf.consume(HeaderSize)
                    val payloadSlice = buf.consume(payloadLen)
                    if firstSeq == -1 then firstSeq = seqId
                    payloadChunks = payloadChunks.appended(payloadSlice)
                    if payloadLen < MaxPayload then
                        // Last packet in a logical message
                        done = true
                end if
        end while

        if payloadChunks.isEmpty then
            Absent
        else
            // Concatenate all payload chunks
            val totalLen = payloadChunks.foldLeft(0)(_ + _.size)
            val combined = new Array[Byte](totalLen)
            var offset   = 0
            // Performance: arraycopy loop for chunk concatenation — unavoidable for multi-packet reassembly.
            payloadChunks.foreach { chunk =>
                val arr = chunk.toArray
                java.lang.System.arraycopy(arr, 0, combined, offset, arr.length)
                offset += arr.length
            }
            Present((Span.from(combined), firstSeq))
        end if
    end readOne

    /** Frames a single packet: writes the 4-byte header (3-byte LE uint24 length + 1-byte seqId) followed by the payload bytes. */
    private def framePacket(payload: Span[Byte], seq: Int): Span[Byte] =
        val len    = payload.size
        val result = new Array[Byte](HeaderSize + len)
        // 3-byte LE uint24 length
        result(0) = (len & 0xff).toByte
        result(1) = ((len >> 8) & 0xff).toByte
        result(2) = ((len >> 16) & 0xff).toByte
        // sequence ID
        result(3) = (seq & 0xff).toByte
        // payload
        if len > 0 then
            val arr = payload.toArray
            java.lang.System.arraycopy(arr, 0, result, HeaderSize, len)
        Span.from(result)
    end framePacket

end MysqlPacket
