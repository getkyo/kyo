package kyo.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kyo.*

/** WebSocket frame codec — pure functions + deferred I/O.
  *
  * Works on any TransportStream. Independent of HTTP version (WebSocket upgrade happens at protocol level, framing is version-independent).
  *
  * Frame types (RFC 6455 §5.2): 0x1 = Text, 0x2 = Binary, 0x8 = Close, 0x9 = Ping, 0xA = Pong, 0x0 = Continuation
  *
  * readFrame reassembles fragmented messages and handles Ping/Pong transparently.
  */
object WsCodec:

    private val Utf8     = StandardCharsets.UTF_8
    private val WsGuid   = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val OpText   = 0x1
    private val OpBinary = 0x2
    private val OpClose  = 0x8
    private val OpPing   = 0x9
    private val OpPong   = 0xa

    case class FrameHeader(fin: Boolean, opcode: Int, masked: Boolean, payloadLen: Int)

    // ── Public API ──────────────────────────────────────────────

    /** Read one complete frame. Reassembles fragmented messages. Handles Ping by auto-sending Pong. On Close frame: Abort[Closed]. */
    def readFrame(stream: TransportStream)(using Frame): WebSocketFrame < (Async & Abort[Closed]) =
        readRawFrame(stream).map { (opcode, payload) =>
            opcode match
                case OpText   => WebSocketFrame.Text(new String(payload.toArrayUnsafe, Utf8))
                case OpBinary => WebSocketFrame.Binary(payload)
                case OpClose  => Abort.fail(new Closed("WebSocket", summon[Frame], "Close frame received"))
                case OpPing   =>
                    // Auto-respond with Pong, then read next frame
                    writeRawFrame(stream, OpPong, payload, mask = false).andThen(readFrame(stream))
                case OpPong =>
                    // Ignore pong, read next
                    readFrame(stream)
                case _ =>
                    // Unknown opcode, skip
                    readFrame(stream)
        }

    /** Write one data frame (Text or Binary). */
    def writeFrame(stream: TransportStream, frame: WebSocketFrame, mask: Boolean)(using Frame): Unit < (Async & Abort[Closed]) =
        frame match
            case WebSocketFrame.Text(data) =>
                writeRawFrame(stream, OpText, Span.fromUnsafe(data.getBytes(Utf8)), mask)
            case WebSocketFrame.Binary(data) =>
                writeRawFrame(stream, OpBinary, data, mask)

    /** Write a Close frame (opcode 0x8). */
    def writeClose(stream: TransportStream, code: Int, reason: String, mask: Boolean)(using Frame): Unit < Async =
        writeRawFrame(stream, OpClose, encodeClosePayload(code, reason), mask)

    /** Server: validate upgrade headers, write 101 response. */
    def acceptUpgrade(stream: TransportStream, headers: HttpHeaders, config: WebSocketConfig)(using
        Frame
    )
        : Unit < (Async & Abort[HttpException]) =
        headers.get("Sec-WebSocket-Key") match
            case Absent =>
                Abort.fail(HttpProtocolException("Missing Sec-WebSocket-Key header"))
            case Present(clientKey) =>
                val acceptKey = computeAcceptKey(clientKey)
                val response  = new StringBuilder
                discard(response.append("HTTP/1.1 101 Switching Protocols\r\n"))
                discard(response.append("Upgrade: websocket\r\n"))
                discard(response.append("Connection: Upgrade\r\n"))
                discard(response.append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n"))
                discard(response.append("\r\n"))
                stream.write(Span.fromUnsafe(response.toString.getBytes(Utf8)))

    /** Client: send upgrade request, validate 101 response. */
    def requestUpgrade(
        stream: TransportStream,
        host: String,
        path: String,
        headers: HttpHeaders,
        config: WebSocketConfig
    )(using Frame): Unit < (Async & Abort[HttpException]) =
        val clientKey = Base64.getEncoder.encodeToString(randomBytes(16))
        val request   = new StringBuilder
        discard(request.append("GET ").append(path).append(" HTTP/1.1\r\n"))
        discard(request.append("Host: ").append(host).append("\r\n"))
        discard(request.append("Upgrade: websocket\r\n"))
        discard(request.append("Connection: Upgrade\r\n"))
        discard(request.append("Sec-WebSocket-Version: 13\r\n"))
        discard(request.append("Sec-WebSocket-Key: ").append(clientKey).append("\r\n"))
        headers.foreach { (name, value) =>
            discard(request.append(name).append(": ").append(value).append("\r\n"))
        }
        discard(request.append("\r\n"))
        stream.write(Span.fromUnsafe(request.toString.getBytes(Utf8))).andThen {
            // Read 101 response
            readUpgradeResponse(stream, clientKey)
        }
    end requestUpgrade

    // ── Pure functions (unit testable) ──────────────────────────

    private[internal] def computeAcceptKey(clientKey: String): String =
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((clientKey + WsGuid).getBytes(Utf8))
        Base64.getEncoder.encodeToString(hash)
    end computeAcceptKey

    private[internal] def parseFrameHeader(b0: Byte, b1: Byte): FrameHeader =
        val fin        = (b0 & 0x80) != 0
        val opcode     = b0 & 0x0f
        val masked     = (b1 & 0x80) != 0
        val payloadLen = b1 & 0x7f
        FrameHeader(fin, opcode, masked, payloadLen)
    end parseFrameHeader

    private[internal] def unmask(payload: Span[Byte], maskKey: Span[Byte]): Span[Byte] =
        if maskKey.size < 4 then payload
        else
            val result = new Array[Byte](payload.size)
            var i      = 0
            while i < payload.size do
                result(i) = (payload(i) ^ maskKey(i % 4)).toByte
                i += 1
            end while
            Span.fromUnsafe(result)

    private[internal] def encodeFrameHeader(opcode: Int, length: Long, mask: Boolean): Span[Byte] =
        val b0        = (0x80 | opcode).toByte // FIN + opcode
        val maskBit   = if mask then 0x80 else 0
        val headerBuf = new java.io.ByteArrayOutputStream(14)
        if length < 126 then
            headerBuf.write(b0.toInt)
            headerBuf.write((maskBit | length.toInt).toByte.toInt)
        else if length <= 0xffff then
            headerBuf.write(b0.toInt)
            headerBuf.write((maskBit | 126).toByte.toInt)
            headerBuf.write((length >> 8).toInt & 0xff)
            headerBuf.write(length.toInt & 0xff)
        else
            headerBuf.write(b0.toInt)
            headerBuf.write((maskBit | 127).toByte.toInt)
            var shift = 56
            while shift >= 0 do
                headerBuf.write((length >> shift).toInt & 0xff)
                shift -= 8
            end while
        end if
        if mask then
            val key = randomBytes(4)
            headerBuf.write(key, 0, 4)
        end if
        Span.fromUnsafe(headerBuf.toByteArray)
    end encodeFrameHeader

    private[internal] def encodeClosePayload(code: Int, reason: String): Span[Byte] =
        val reasonBytes  = reason.getBytes(Utf8)
        val maxReasonLen = 123 // 125 - 2 for code
        val truncatedLen = math.min(reasonBytes.length, maxReasonLen)
        val payload      = new Array[Byte](2 + truncatedLen)
        payload(0) = ((code >> 8) & 0xff).toByte
        payload(1) = (code & 0xff).toByte
        if truncatedLen > 0 then
            java.lang.System.arraycopy(reasonBytes, 0, payload, 2, truncatedLen)
        Span.fromUnsafe(payload)
    end encodeClosePayload

    // ── Internal I/O ────────────────────────────────────────────

    /** Read a single raw frame (handles extended length + masking). */
    private def readRawFrame(stream: TransportStream)(using Frame): (Int, Span[Byte]) < Async =
        readBytes(stream, 2).map { header =>
            val fh          = parseFrameHeader(header(0), header(1))
            val payloadLen  = fh.payloadLen
            val extLenBytes = if payloadLen == 126 then 2 else if payloadLen == 127 then 8 else 0

            val readExtLen =
                if extLenBytes == 0 then Sync.defer(payloadLen.toLong)
                else
                    readBytes(stream, extLenBytes).map { ext =>
                        if extLenBytes == 2 then
                            ((ext(0) & 0xff) << 8) | (ext(1) & 0xff).toLong
                        else
                            var len = 0L
                            var i   = 0
                            while i < 8 do
                                len = (len << 8) | (ext(i) & 0xff)
                                i += 1
                            end while
                            len
                    }

            readExtLen.map { actualLen =>
                val readMask =
                    if fh.masked then readBytes(stream, 4)
                    else Sync.defer(Span.empty[Byte])

                readMask.map { maskKey =>
                    readBytes(stream, actualLen.toInt).map { payload =>
                        val unmasked = if fh.masked then unmask(payload, maskKey) else payload
                        (fh.opcode, unmasked)
                    }
                }
            }
        }

    /** Write a raw frame with header + optional masking. */
    private def writeRawFrame(stream: TransportStream, opcode: Int, payload: Span[Byte], mask: Boolean)(using Frame): Unit < Async =
        val header = encodeFrameHeader(opcode, payload.size.toLong, mask)
        if mask then
            // Extract mask key from last 4 bytes of header, apply to payload
            val maskKey = header.slice(header.size - 4, header.size)
            val masked  = unmask(payload, maskKey) // XOR is symmetric
            stream.write(header).andThen(stream.write(masked))
        else
            stream.write(header).andThen(
                if payload.isEmpty then Sync.defer(()) else stream.write(payload)
            )
        end if
    end writeRawFrame

    /** Read exactly n bytes from the stream. */
    private def readBytes(stream: TransportStream, n: Int)(using Frame): Span[Byte] < Async =
        if n == 0 then Sync.defer(Span.empty[Byte])
        else
            val result = new Array[Byte](n)
            var offset = 0
            Loop.foreach {
                if offset >= n then Loop.done(())
                else
                    val buf = new Array[Byte](n - offset)
                    stream.read(buf).map { read =>
                        if read <= 0 then Loop.done(())
                        else
                            java.lang.System.arraycopy(buf, 0, result, offset, read)
                            offset += read
                            Loop.continue
                    }
            }.andThen(Span.fromUnsafe(result))

    /** Read upgrade response, validate 101 + Sec-WebSocket-Accept. */
    private def readUpgradeResponse(stream: TransportStream, clientKey: String)(using
        Frame
    )
        : Unit < (Async & Abort[HttpException]) =
        // Read response headers (reuse Http1Protocol's header reader pattern)
        val buf   = new Array[Byte](4096)
        val accum = new java.io.ByteArrayOutputStream(512)
        val sep   = "\r\n\r\n".getBytes(Utf8)

        Loop.foreach {
            stream.read(buf).map { n =>
                if n <= 0 then Loop.done(())
                else
                    accum.write(buf, 0, n)
                    val bytes = accum.toByteArray
                    if indexOf(bytes, sep) >= 0 then Loop.done(())
                    else Loop.continue
            }
        }.andThen {
            val responseStr = new String(accum.toByteArray, Utf8)
            if !responseStr.startsWith("HTTP/1.1 101") then
                Abort.fail(HttpProtocolException(s"WebSocket upgrade failed: expected 101, got: ${responseStr.take(40)}"))
            else
                val expectedAccept = computeAcceptKey(clientKey)
                if !responseStr.contains(expectedAccept) then
                    Abort.fail(HttpProtocolException("WebSocket upgrade: invalid Sec-WebSocket-Accept"))
                else
                    Sync.defer(())
                end if
            end if
        }
    end readUpgradeResponse

    private def randomBytes(n: Int): Array[Byte] =
        val bytes = new Array[Byte](n)
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes)
        bytes
    end randomBytes

    private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
        val limit = haystack.length - needle.length
        var i     = 0
        while i <= limit do
            var j     = 0
            var found = true
            while j < needle.length && found do
                if haystack(i + j) != needle(j) then found = false
                j += 1
            if found then return i
            i += 1
        end while
        -1
    end indexOf

end WsCodec
