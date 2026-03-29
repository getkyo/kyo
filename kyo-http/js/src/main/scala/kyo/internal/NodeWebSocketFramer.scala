package kyo.internal

import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** RFC 6455 WebSocket frame encoder/decoder for Node.js raw sockets.
  *
  * Handles frame parsing (with masking/unmasking for client→server), encoding (unmasked for server→client), fragmentation reassembly, and
  * control frame dispatch (ping/pong/close).
  */
private[kyo] object NodeWebSocketFramer:
    val OPCODE_CONTINUATION = 0x0
    val OPCODE_TEXT         = 0x1
    val OPCODE_BINARY       = 0x2
    val OPCODE_CLOSE        = 0x8
    val OPCODE_PING         = 0x9
    val OPCODE_PONG         = 0xa

    /** Encode a frame for sending (server→client, NOT masked per RFC 6455 section 5.1). */
    def encode(opcode: Int, payload: Uint8Array, fin: Boolean = true): Uint8Array =
        val len = payload.length
        val headerSize =
            if len < 126 then 2
            else if len < 65536 then 4
            else 10
        val frame = new Uint8Array(headerSize + len)

        frame(0) = ((if fin then 0x80 else 0) | opcode).toByte
        if len < 126 then
            frame(1) = len.toByte
        else if len < 65536 then
            frame(1) = 126.toByte
            frame(2) = ((len >> 8) & 0xff).toByte
            frame(3) = (len & 0xff).toByte
        else
            frame(1) = 127.toByte
            var remaining = len.toLong
            var i         = 9
            while i >= 2 do
                frame(i) = (remaining & 0xff).toByte
                remaining = remaining >> 8
                i -= 1
            end while
        end if

        var j = 0
        while j < len do
            frame(headerSize + j) = payload(j)
            j += 1
        frame
    end encode

    def encodeText(text: String): Uint8Array =
        val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
        val payload = encoder.encode(text).asInstanceOf[Uint8Array]
        encode(OPCODE_TEXT, payload)
    end encodeText

    def encodeBinary(data: Array[Byte]): Uint8Array =
        val payload = new Uint8Array(data.length)
        var i       = 0
        while i < data.length do
            payload(i) = data(i)
            i += 1
        encode(OPCODE_BINARY, payload)
    end encodeBinary

    def encodeClose(code: Int, reason: String): Uint8Array =
        val encoder     = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
        val reasonBytes = encoder.encode(reason).asInstanceOf[Uint8Array]
        val payload     = new Uint8Array(2 + reasonBytes.length)
        payload(0) = ((code >> 8) & 0xff).toByte
        payload(1) = (code & 0xff).toByte
        var i = 0
        while i < reasonBytes.length do
            payload(2 + i) = reasonBytes(i)
            i += 1
        encode(OPCODE_CLOSE, payload)
    end encodeClose

    def encodePong(payload: Uint8Array): Uint8Array =
        encode(OPCODE_PONG, payload)

    /** Incremental frame parser. Feed raw bytes, get parsed frames. */
    class Parser:
        private var buffer: Uint8Array                   = new Uint8Array(0)
        private var fragmentOpcode: Int                  = -1
        private var fragmentChunks: js.Array[Uint8Array] = null

        def feed(data: Uint8Array): js.Array[ParsedFrame] =
            val newBuf = new Uint8Array(buffer.length + data.length)
            newBuf.set(buffer, 0)
            newBuf.set(data, buffer.length)
            buffer = newBuf

            val frames   = new js.Array[ParsedFrame]()
            var continue = true
            while continue && buffer.length >= 2 do
                tryParseFrame() match
                    case null        => continue = false
                    case parsedFrame => frames.push(parsedFrame): Unit
            end while
            frames
        end feed

        private def tryParseFrame(): ParsedFrame | Null =
            if buffer.length < 2 then return null

            val byte0      = buffer(0) & 0xff
            val byte1      = buffer(1) & 0xff
            val fin        = (byte0 & 0x80) != 0
            val opcode     = byte0 & 0x0f
            val masked     = (byte1 & 0x80) != 0
            var payloadLen = byte1 & 0x7f
            var offset     = 2

            if payloadLen == 126 then
                if buffer.length < 4 then return null
                payloadLen = ((buffer(2) & 0xff) << 8) | (buffer(3) & 0xff)
                offset = 4
            else if payloadLen == 127 then
                if buffer.length < 10 then return null
                payloadLen = 0
                var i = 2
                while i < 10 do
                    payloadLen = (payloadLen << 8) | (buffer(i) & 0xff)
                    i += 1
                offset = 10
            end if

            val maskSize    = if masked then 4 else 0
            val totalNeeded = offset + maskSize + payloadLen
            if buffer.length < totalNeeded then return null

            val maskKey = if masked then buffer.subarray(offset, offset + 4) else null
            offset += maskSize

            val payload = new Uint8Array(payloadLen)
            var i       = 0
            while i < payloadLen do
                val b = buffer(offset + i)
                payload(i) = if masked then (b ^ maskKey(i % 4)).toByte else b
                i += 1
            end while

            buffer = buffer.subarray(offset + payloadLen)

            opcode match
                case OPCODE_CONTINUATION =>
                    if fragmentOpcode == -1 then return null
                    fragmentChunks.push(payload): Unit
                    if fin then
                        val full = concatArrays(fragmentChunks)
                        val op   = fragmentOpcode
                        fragmentOpcode = -1
                        fragmentChunks = null
                        ParsedFrame(op, full)
                    else null
                    end if

                case op if op >= OPCODE_CLOSE =>
                    ParsedFrame(op, payload)

                case op =>
                    if !fin then
                        fragmentOpcode = op
                        fragmentChunks = new js.Array[Uint8Array]()
                        fragmentChunks.push(payload): Unit
                        null
                    else
                        ParsedFrame(op, payload)
            end match
        end tryParseFrame

        private def concatArrays(chunks: js.Array[Uint8Array]): Uint8Array =
            var totalLen = 0
            chunks.foreach(c => totalLen += c.length)
            val result = new Uint8Array(totalLen)
            var off    = 0
            chunks.foreach { c =>
                result.set(c, off)
                off += c.length
            }
            result
        end concatArrays
    end Parser

    case class ParsedFrame(opcode: Int, payload: Uint8Array)

end NodeWebSocketFramer
