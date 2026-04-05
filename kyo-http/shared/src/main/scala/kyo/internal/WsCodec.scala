package kyo.internal

import java.nio.charset.StandardCharsets
import java.util.Base64
import kyo.*

/** WebSocket frame codec — pure functions + deferred I/O.
  *
  * Works on Stream[Span[Byte], Async] for reads and TransportStream for writes. Independent of HTTP version (WebSocket upgrade happens at
  * protocol level, framing is version-independent).
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

    /** Read one complete frame. Returns (frame, remainingStream). Handles Ping by auto-sending Pong. On Close frame: Abort[Closed]. */
    def readFrame(src: Stream[Span[Byte], Async], dst: TransportStream)(using
        Frame
    ): (WebSocket.Payload, Stream[Span[Byte], Async]) < (Async & Abort[Closed]) =
        Abort.recover[HttpException](_ => Abort.fail(new Closed("WebSocket", summon[Frame]))) {
            readRawFrameWith(src) { (opcode, payload, remaining) =>
                opcode match
                    case OpText   => (WebSocket.Payload.Text(new String(payload.toArrayUnsafe, Utf8)), remaining)
                    case OpBinary => (WebSocket.Payload.Binary(payload), remaining)
                    case OpClose  => Abort.fail(new Closed("WebSocket", summon[Frame], "Close frame received"))
                    case OpPing =>
                        writeRawFrame(dst, OpPong, payload, mask = false).andThen(readFrame(remaining, dst))
                    case OpPong =>
                        readFrame(remaining, dst)
                    case other =>
                        Abort.fail(new Closed("WebSocket", summon[Frame], s"Unknown opcode: $other"))
            }
        }

    /** Write one data frame (Text or Binary). */
    def writeFrame(dst: TransportStream, frame: WebSocket.Payload, mask: Boolean)(using Frame): Unit < Async =
        frame match
            case WebSocket.Payload.Text(data) =>
                writeRawFrame(dst, OpText, Span.fromUnsafe(data.getBytes(Utf8)), mask)
            case WebSocket.Payload.Binary(data) =>
                writeRawFrame(dst, OpBinary, data, mask)

    /** Write a Close frame (opcode 0x8). */
    def writeClose(dst: TransportStream, code: Int, reason: String, mask: Boolean)(using Frame): Unit < Async =
        writeRawFrame(dst, OpClose, encodeClosePayload(code, reason), mask)

    /** Server: validate upgrade headers, write 101 response. */
    def acceptUpgrade(dst: TransportStream, headers: HttpHeaders, config: WebSocket.Config)(using
        Frame
    ): Unit < (Async & Abort[HttpException]) =
        headers.get("Sec-WebSocket-Key") match
            case Absent =>
                Abort.fail(HttpProtocolException("Missing Sec-WebSocket-Key header"))
            case Present(clientKey) =>
                Sync.defer {
                    val acceptKey = computeAcceptKey(clientKey)
                    val response  = new StringBuilder
                    discard(response.append("HTTP/1.1 101 Switching Protocols\r\n"))
                    discard(response.append("Upgrade: websocket\r\n"))
                    discard(response.append("Connection: Upgrade\r\n"))
                    discard(response.append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n"))
                    discard(response.append("\r\n"))
                    dst.write(Span.fromUnsafe(response.toString.getBytes(Utf8)))
                }

    /** Client: send upgrade request, validate 101 response, return remaining stream after headers. */
    def requestUpgrade(
        conn: TransportStream,
        host: String,
        path: String,
        headers: HttpHeaders,
        config: WebSocket.Config
    )(using Frame): Stream[Span[Byte], Async] < (Async & Abort[HttpException]) =
        Sync.defer {
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
            conn.write(Span.fromUnsafe(request.toString.getBytes(Utf8))).andThen {
                ByteStream.readUntil(conn.read, "\r\n\r\n".getBytes(Utf8), 4096).map { case (headerBytes, remaining) =>
                    val responseStr = new String(headerBytes.toArrayUnsafe, Utf8)
                    if !responseStr.startsWith("HTTP/1.1 101") then
                        Abort.fail(HttpProtocolException(s"WebSocket upgrade failed: expected 101, got: ${responseStr.take(40)}"))
                    else
                        val expectedAccept = computeAcceptKey(clientKey)
                        if !responseStr.contains(expectedAccept) then
                            Abort.fail(HttpProtocolException("WebSocket upgrade: invalid Sec-WebSocket-Accept"))
                        else
                            remaining
                        end if
                    end if
                }
            }
        }
    end requestUpgrade

    // ── Pure functions (unit testable) ──────────────────────────

    private[internal] def computeAcceptKey(clientKey: String): String =
        val hash = Sha1.hash((clientKey + WsGuid).getBytes(Utf8))
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
    private inline def readRawFrameWith[A, S2](src: Stream[Span[Byte], Async])(
        inline f: (Int, Span[Byte], Stream[Span[Byte], Async]) => A < S2
    )(using inline frame: Frame): A < (S2 & Async & Abort[HttpException]) =
        ByteStream.readExact(src, 2).map { case (header, rem1) =>
            val fh          = parseFrameHeader(header(0), header(1))
            val payloadLen  = fh.payloadLen
            val extLenBytes = if payloadLen == 126 then 2 else if payloadLen == 127 then 8 else 0

            val readExtLen: (Long, Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
                if extLenBytes == 0 then (payloadLen.toLong, rem1)
                else
                    ByteStream.readExact(rem1, extLenBytes).map { case (ext, rem2) =>
                        val len =
                            if extLenBytes == 2 then ((ext(0) & 0xff) << 8) | (ext(1) & 0xff).toLong
                            else
                                var l = 0L
                                var i = 0
                                while i < 8 do
                                    l = (l << 8) | (ext(i) & 0xff)
                                    i += 1
                                end while
                                l
                        (len, rem2)
                    }

            readExtLen.map { case (actualLen, rem2) =>
                val readMask: (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
                    if fh.masked then ByteStream.readExact(rem2, 4)
                    else (Span.empty[Byte], rem2)

                readMask.map { case (maskKey, rem3) =>
                    ByteStream.readExact(rem3, actualLen.toInt).map { case (payload, rem4) =>
                        val unmasked = if fh.masked then unmask(payload, maskKey) else payload
                        f(fh.opcode, unmasked, rem4)
                    }
                }
            }
        }
    end readRawFrameWith

    /** Write a raw frame with header + optional masking. */
    private def writeRawFrame(dst: TransportStream, opcode: Int, payload: Span[Byte], mask: Boolean)(using Frame): Unit < Async =
        val header = encodeFrameHeader(opcode, payload.size.toLong, mask)
        if mask then
            // Extract mask key from last 4 bytes of header, apply to payload
            val maskKey = header.slice(header.size - 4, header.size)
            val masked  = unmask(payload, maskKey) // XOR is symmetric
            dst.write(header).andThen(
                if masked.isEmpty then Kyo.unit else dst.write(masked)
            )
        else
            dst.write(header).andThen(
                if payload.isEmpty then Kyo.unit else dst.write(payload)
            )
        end if
    end writeRawFrame

    private def randomBytes(n: Int): Array[Byte] =
        val bytes = new Array[Byte](n)
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes)
        bytes
    end randomBytes

end WsCodec
