package kyo.internal.websocket

import java.nio.charset.StandardCharsets
import java.util.Base64
import kyo.*
import kyo.internal.transport.*
import kyo.internal.util.*
import scala.annotation.tailrec

/** WebSocket frame codec — pure encoding/decoding functions plus deferred I/O primitives.
  *
  * Read path: Stream[Span[Byte], Async] -> readFrame -> (HttpWebSocket.Payload, remainingStream). Write path:
  * TransportStream.write(encodeFrameHeader + payload).
  *
  * The codec is transport-independent: it works over any TransportStream (Connection channels, test channels, etc.) and is shared between
  * client and server.
  *
  * Frame types (RFC 6455 §5.2): 0x0 = Continuation, 0x1 = Text, 0x2 = Binary, 0x8 = Close, 0x9 = Ping, 0xA = Pong
  *
  * readFrame handles Ping/Pong control frames transparently (auto-pong, skip-pong) and fails with Abort[Closed] on Close frames. Fragmented
  * messages are not reassembled — the caller receives each fragment as a separate frame.
  *
  * Server frames are unmasked (mask=false); client frames are masked (mask=true) per RFC 6455 §5.3. Sha1 is used for accept key computation
  * (WebSocketCodec.computeAcceptKey) because java.security.MessageDigest is unavailable on Scala Native.
  */
private[kyo] object WebSocketCodec:

    private val Utf8     = StandardCharsets.UTF_8
    private val WsGuid   = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val OpText   = 0x1
    private val OpBinary = 0x2
    private val OpClose  = 0x8
    private val OpPing   = 0x9
    private val OpPong   = 0xa

    case class FrameHeader(fin: Boolean, opcode: Int, masked: Boolean, payloadLen: Int)

    // ── Public API ──────────────────────────────────────────────

    /** Read one complete frame in the server role: no frame-size cap, no close hook, and an unmasked auto-Pong (RFC 6455 §5.1). Handles
      * Ping by auto-sending Pong; on a Close frame, aborts `Closed`. A client must use the full overload with `mask = true`.
      */
    def readFrameWith[A, S](src: Stream[Span[Byte], Async], dst: TransportStream)(
        f: (HttpWebSocket.Payload, Stream[Span[Byte], Async]) => A < S
    )(using Frame): A < (S & Async & Abort[Closed]) =
        readFrameWith(src, dst, Int.MaxValue, _ => Kyo.unit, mask = false)(f)

    /** Read one complete frame; on receipt of a Close frame, invoke `onClose` with the parsed (code, reason) before aborting.
      *
      * `mask` is the caller's role (client `true` / server `false`); it governs whether the auto-Pong reply to a server Ping is masked,
      * which RFC 6455 §5.1 requires of a client.
      */
    def readFrameWith[A, S](
        src: Stream[Span[Byte], Async],
        dst: TransportStream,
        maxFrameSize: Int,
        onClose: ((Int, String)) => Unit < (S & Async),
        mask: Boolean
    )(
        f: (HttpWebSocket.Payload, Stream[Span[Byte], Async]) => A < S
    )(using Frame): A < (S & Async & Abort[Closed]) =
        Abort.recover[HttpException](_ => Abort.fail(new Closed("HttpWebSocket", summon[Frame]))) {
            readRawFrameWith(src, maxFrameSize, expectMasked = !mask) { (opcode, payload, remaining) =>
                opcode match
                    case OpText   => f(HttpWebSocket.Payload.Text(new String(payload.toArrayUnsafe, Utf8)), remaining)
                    case OpBinary => f(HttpWebSocket.Payload.Binary(payload), remaining)
                    case OpClose =>
                        val (code, reason) = decodeClosePayload(payload)
                        onClose((code, reason)).andThen(
                            Abort.fail(new Closed("HttpWebSocket", summon[Frame], s"Close frame received: $code $reason"))
                        )
                    case OpPing =>
                        writeRawFrame(dst, OpPong, payload, mask).andThen(readFrameWith(remaining, dst, maxFrameSize, onClose, mask)(f))
                    case OpPong =>
                        readFrameWith(remaining, dst, maxFrameSize, onClose, mask)(f)
                    case other =>
                        Abort.fail(new Closed("HttpWebSocket", summon[Frame], s"Unknown opcode: $other"))
                end match
            }
        }
    end readFrameWith

    /** Write one data frame (Text or Binary). */
    def writeFrame(dst: TransportStream, frame: HttpWebSocket.Payload, mask: Boolean)(using Frame): Unit < Async =
        frame match
            case HttpWebSocket.Payload.Text(data) =>
                writeRawFrame(dst, OpText, Span.fromUnsafe(data.getBytes(Utf8)), mask)
            case HttpWebSocket.Payload.Binary(data) =>
                writeRawFrame(dst, OpBinary, data, mask)
        end match
    end writeFrame

    /** Write a Close frame (opcode 0x8). */
    def writeClose(dst: TransportStream, code: Int, reason: String, mask: Boolean)(using Frame): Unit < Async =
        writeRawFrame(dst, OpClose, encodeClosePayload(code, reason), mask)

    /** Server: validate upgrade headers, write 101 response. */
    def acceptUpgrade(dst: TransportStream, headers: HttpHeaders, config: HttpWebSocket.Config)(using
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
                    // Subprotocol negotiation (RFC 6455 section 4.2.2):
                    // If the server config lists supported subprotocols and the client offered any,
                    // select the first client-offered subprotocol that the server supports.
                    if config.subprotocols.nonEmpty then
                        headers.get("Sec-WebSocket-Protocol") match
                            case Present(offered) =>
                                val clientProtocols = offered.split(',').map(_.trim)
                                val serverSet       = config.subprotocols.toSet
                                // The selected value is echoed onto a header line of this handshake, so it goes through the same rule every
                                // other kyo-http response header follows: a subprotocol is a token (RFC 6455 section 4.1 defines it as one),
                                // and a name that is not would let a recipient read this line as two.
                                clientProtocols.find(p => serverSet.contains(p) && HttpHeaders.isToken(p)) match
                                    case Some(selected) =>
                                        discard(response.append("Sec-WebSocket-Protocol: ").append(selected).append("\r\n"))
                                    case None => ()
                                end match
                            case Absent => ()
                    end if
                    discard(response.append("\r\n"))
                    dst.write(Span.fromUnsafe(response.toString.getBytes(Utf8)))
                }

    /** Client: send upgrade request, validate 101 response, pass remaining stream after headers to f. */
    def requestUpgradeWith[A, S](
        conn: TransportStream,
        host: String,
        path: String,
        headers: HttpHeaders,
        config: HttpWebSocket.Config
    )(
        f: Stream[Span[Byte], Async] => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        unsendableField(path, host, headers) match
            case Present(ex) => Abort.fail(ex)
            case Absent =>
                unsendableSubprotocol(config, headers) match
                    case Present(ex) => Abort.fail(ex)
                    case Absent      => writeUpgradeRequestWith(conn, host, path, headers, config)(f)

    /** Names the first element of an upgrade handshake that cannot go on the wire, or `Absent` when all of them can.
      *
      * The handshake is its own header serializer: it appends the caller's headers to a request line and encodes the whole thing as UTF-8,
      * so a CR or an LF in any of them ends a line early and injects a header the caller never set. The rules are the ones every kyo-http
      * serializer follows: a name is a token, and no field or request-line element carries a control character. A non-ASCII header value is
      * legal obs-text (RFC 9110 section 5.5) and is sent as UTF-8, which is what this handshake already did for every value.
      */
    private def unsendableField(path: String, host: String, headers: HttpHeaders)(using Frame): Maybe[HttpException] =
        if !HttpHeaders.isControlFree(path) then Present(HttpInvalidFieldException("the request path"))
        else if !HttpHeaders.isControlFree(host) then Present(HttpInvalidFieldException("the Host header"))
        else headers.invalidField.map(HttpInvalidFieldException(_))

    /** The failure for a configured subprotocol the client cannot advertise, or `Absent` when it advertises none or all are sendable.
      *
      * A subprotocol is a token (RFC 6455 section 4.1). The client writes `config.subprotocols` onto the Sec-WebSocket-Protocol request
      * line, so a value that is not a token would let a recipient read that line as two, the same injection the server side already refuses
      * when it echoes a selected subprotocol (`HttpHeaders.isToken`, `acceptUpgrade`). The client advertises its configured list only when the
      * caller did not supply its own Sec-WebSocket-Protocol header, so that is the only case checked, to avoid rejecting a value that never
      * reaches the wire. The failure names the field, not the value, which is untrusted and logged.
      */
    private def unsendableSubprotocol(config: HttpWebSocket.Config, headers: HttpHeaders)(using Frame): Maybe[HttpException] =
        val advertisesConfigured = config.subprotocols.nonEmpty && headers.get("Sec-WebSocket-Protocol").isEmpty
        if advertisesConfigured && !config.subprotocols.forall(p => HttpHeaders.isToken(p)) then
            Present(HttpInvalidFieldException("a Sec-WebSocket-Protocol subprotocol"))
        else Absent
    end unsendableSubprotocol

    private def writeUpgradeRequestWith[A, S](
        conn: TransportStream,
        host: String,
        path: String,
        headers: HttpHeaders,
        config: HttpWebSocket.Config
    )(
        f: Stream[Span[Byte], Async] => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Sync.defer {
            val clientKey = Base64.getEncoder.encodeToString(randomBytes(16))
            val request   = new StringBuilder
            discard(request.append("GET ").append(path).append(" HTTP/1.1\r\n"))
            discard(request.append("Host: ").append(host).append("\r\n"))
            discard(request.append("Upgrade: websocket\r\n"))
            discard(request.append("Connection: Upgrade\r\n"))
            discard(request.append("Sec-WebSocket-Version: 13\r\n"))
            discard(request.append("Sec-WebSocket-Key: ").append(clientKey).append("\r\n"))
            // Advertise configured subprotocols (RFC 6455 §1.9 / §4.2.2). When the caller's `headers` parameter also carries
            // Sec-WebSocket-Protocol we suppress the duplicate so the upgrade request has exactly one such header.
            val callerSuppliedProtocol = headers.get("Sec-WebSocket-Protocol")
            if config.subprotocols.nonEmpty && callerSuppliedProtocol.isEmpty then
                discard(request.append("Sec-WebSocket-Protocol: ").append(config.subprotocols.mkString(", ")).append("\r\n"))
            headers.foreach { (name, value) =>
                if !isRequiredClientUpgradeHeader(name) then
                    discard(request.append(name).append(": ").append(value).append("\r\n"))
            }
            discard(request.append("\r\n"))
            conn.write(Span.fromUnsafe(request.toString.getBytes(Utf8))).andThen {
                ByteStream.readUntilWith(conn.read, "\r\n\r\n".getBytes(Utf8), 4096) { (headerBytes, remaining) =>
                    val responseStr = new String(headerBytes.toArrayUnsafe, Utf8)
                    if !responseStr.startsWith("HTTP/1.1 101") then
                        Abort.fail(HttpProtocolException(s"HttpWebSocket upgrade failed: expected 101, got: ${responseStr.take(40)}"))
                    else
                        val expectedAccept = computeAcceptKey(clientKey)
                        if !responseStr.contains(expectedAccept) then
                            Abort.fail(HttpProtocolException("HttpWebSocket upgrade: invalid Sec-WebSocket-Accept"))
                        else
                            // RFC 6455 §4.1: if the client offered subprotocols, the server's selected subprotocol (if any)
                            // MUST be one of them. Fail the connection on a mismatched echo. The "offered" list is whatever
                            // we put on the wire — caller-supplied header takes precedence over config.subprotocols.
                            val offered =
                                callerSuppliedProtocol match
                                    case Present(v) => v.split(',').iterator.map(_.trim).toList
                                    case Absent     => config.subprotocols.toList
                            val selected = parseResponseSubprotocol(responseStr)
                            selected match
                                case Some(s) if offered.nonEmpty && !offered.contains(s) =>
                                    Abort.fail(HttpProtocolException(
                                        s"HttpWebSocket upgrade: server selected subprotocol '$s' that was not offered"
                                    ))
                                case _ => f(remaining)
                            end match
                        end if
                    end if
                }
            }
        }
    end writeUpgradeRequestWith

    // ── Pure functions (unit testable) ──────────────────────────

    private def isRequiredClientUpgradeHeader(name: String): Boolean =
        name.equalsIgnoreCase("Host") ||
            name.equalsIgnoreCase("Upgrade") ||
            name.equalsIgnoreCase("Connection") ||
            name.equalsIgnoreCase("Sec-WebSocket-Version") ||
            name.equalsIgnoreCase("Sec-WebSocket-Key")

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
            @tailrec def applyMask(i: Int): Unit =
                if i < payload.size then
                    result(i) = (payload(i) ^ maskKey(i % 4)).toByte
                    applyMask(i + 1)
            applyMask(0)
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
            @tailrec def writeShifted(shift: Int): Unit =
                if shift >= 0 then
                    headerBuf.write((length >> shift).toInt & 0xff)
                    writeShifted(shift - 8)
            writeShifted(56)
        end if
        if mask then
            val key = randomBytes(4)
            headerBuf.write(key, 0, 4)
        end if
        Span.fromUnsafe(headerBuf.toByteArray)
    end encodeFrameHeader

    /** Extract the value of the `Sec-WebSocket-Protocol` response header from a raw HTTP/1.1 upgrade response, if present. Case-insensitive
      * header-name match per RFC 7230 §3.2.
      */
    private[internal] def parseResponseSubprotocol(responseStr: String): Option[String] =
        val needle = "sec-websocket-protocol:"
        responseStr.linesIterator.drop(1).collectFirst {
            case line if line.toLowerCase.startsWith(needle) =>
                line.substring(needle.length).trim
        }
    end parseResponseSubprotocol

    /** Decode the payload of a Close frame (opcode 0x8): first 2 bytes big-endian code, remaining bytes UTF-8 reason. Returns (1005, "") if
      * the frame had no payload (per RFC 6455 section 7.1.5, "no status received").
      */
    private[internal] def decodeClosePayload(payload: Span[Byte]): (Int, String) =
        if payload.size < 2 then (1005, "")
        else
            val code      = ((payload(0) & 0xff) << 8) | (payload(1) & 0xff)
            val reasonLen = payload.size - 2
            val reason =
                if reasonLen <= 0 then ""
                else new String(payload.toArrayUnsafe, 2, reasonLen, Utf8)
            (code, reason)
    end decodeClosePayload

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
    private inline def readRawFrameWith[A, S2](src: Stream[Span[Byte], Async], maxFrameSize: Int, expectMasked: Boolean)(
        inline f: (Int, Span[Byte], Stream[Span[Byte], Async]) => A < S2
    )(using inline frame: Frame): A < (S2 & Async & Abort[HttpException]) =
        ByteStream.readExactWith(src, 2) { (header, rem1) =>
            val fh          = parseFrameHeader(header(0), header(1))
            val payloadLen  = fh.payloadLen
            val extLenBytes = if payloadLen == 126 then 2 else if payloadLen == 127 then 8 else 0
            val isControl   = fh.opcode >= OpClose

            if fh.masked != expectMasked then
                // A server MUST receive masked frames and a client MUST receive unmasked ones (RFC 6455 section 5.1);
                // the wrong masking direction is a protocol violation and (server side) a smuggling lever.
                Abort.fail(HttpProtocolException("HttpWebSocket frame masking violates role (RFC 6455 section 5.1)"))
            else if isControl && payloadLen > 125 then
                // A control frame's payload MUST be <= 125 bytes, so its length is never the 126/127 extended form
                // (RFC 6455 section 5.5).
                Abort.fail(HttpProtocolException("HttpWebSocket control frame payload exceeds 125 bytes (RFC 6455 section 5.5)"))
            else if isControl && !fh.fin then
                // A control frame MUST NOT be fragmented (RFC 6455 section 5.5).
                Abort.fail(HttpProtocolException("HttpWebSocket control frame is fragmented (RFC 6455 section 5.5)"))
            else if extLenBytes == 0 then
                val actualLen = payloadLen.toLong
                if actualLen > maxFrameSize.toLong then
                    Abort.fail(HttpProtocolException("HttpWebSocket frame exceeds max frame size"))
                else if fh.masked then
                    ByteStream.readExactWith(rem1, 4) { (maskKey, rem2) =>
                        ByteStream.readExactWith(rem2, actualLen.toInt) { (payload, rem3) =>
                            f(fh.opcode, unmask(payload, maskKey), rem3)
                        }
                    }
                else
                    ByteStream.readExactWith(rem1, actualLen.toInt) { (payload, rem2) =>
                        f(fh.opcode, payload, rem2)
                    }
                end if
            else
                ByteStream.readExactWith(rem1, extLenBytes) { (ext, rem2) =>
                    val actualLen =
                        if extLenBytes == 2 then ((ext(0) & 0xff) << 8) | (ext(1) & 0xff).toLong
                        else
                            @tailrec def readLong(i: Int, acc: Long): Long =
                                if i >= 8 then acc else readLong(i + 1, (acc << 8) | (ext(i) & 0xff))
                            readLong(0, 0L)
                    if actualLen < 0 || actualLen > maxFrameSize.toLong || actualLen > Int.MaxValue.toLong then
                        Abort.fail(HttpProtocolException("HttpWebSocket frame exceeds max frame size"))
                    else if fh.masked then
                        ByteStream.readExactWith(rem2, 4) { (maskKey, rem3) =>
                            ByteStream.readExactWith(rem3, actualLen.toInt) { (payload, rem4) =>
                                f(fh.opcode, unmask(payload, maskKey), rem4)
                            }
                        }
                    else
                        ByteStream.readExactWith(rem2, actualLen.toInt) { (payload, rem3) =>
                            f(fh.opcode, payload, rem3)
                        }
                    end if
                }
            end if
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

end WebSocketCodec
