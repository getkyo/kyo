package kyo.internal.websocket

import java.nio.charset.StandardCharsets
import java.util.Base64
import kyo.*
import kyo.crypto.Sha1
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
  * readFrame handles Ping/Pong control frames transparently (auto-pong, skip-pong), reassembles a fragmented message from its continuation
  * frames (RFC 6455 §5.4), and fails with Abort[Closed] on Close frames and on frames that fail the connection.
  *
  * Server frames are unmasked (mask=false); client frames are masked (mask=true) per RFC 6455 §5.3. The shared Sha1 implementation is used
  * for accept key computation because java.security.MessageDigest is unavailable on Scala Native.
  */
private[kyo] object WebSocketCodec:

    private val Utf8           = StandardCharsets.UTF_8
    private val WsGuid         = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val OpContinuation = 0x0
    private val OpText         = 0x1
    private val OpBinary       = 0x2
    private val OpClose        = 0x8
    private val OpPing         = 0x9
    private val OpPong         = 0xa

    private val CloseProtocolError = 1002
    private val CloseInvalidData   = 1007
    private val CloseTooBig        = 1009

    case class FrameHeader(fin: Boolean, opcode: Int, masked: Boolean, payloadLen: Int)

    /** What fails the connection over a frame read, with the Close code RFC 6455 section 7.4.1 names for it. */
    final private case class ConnectionFailure(code: Int, reason: String)

    // ── Public API ──────────────────────────────────────────────

    /** Read one complete message in the server role: no size cap, no close or failure hook, and an unmasked auto-Pong (RFC 6455 §5.1).
      * Handles Ping by auto-sending Pong; on a Close frame, aborts `Closed`. A client must use the full overload with `mask = true`.
      */
    def readFrameWith[A, S](src: Stream[Span[Byte], Async], dst: TransportStream)(
        f: (HttpWebSocket.Payload, Stream[Span[Byte], Async]) => A < S
    )(using Frame): A < (S & Async & Abort[Closed]) =
        readFrameWith(src, dst, Int.MaxValue, _ => Kyo.unit, mask = false)(f)

    /** Read one complete message; on receipt of a Close frame, invoke `onClose` with the parsed (code, reason) before aborting.
      *
      * `maxFrameSize` bounds each data frame and the reassembled message alike. A frame that fails the connection (a protocol error, a message
      * over the bound, text that is not UTF-8) invokes `onFail` with the Close code and reason the endpoint owes its peer, then aborts
      * `Closed`; a stream that ends mid-frame aborts `Closed` alone, since there is no peer left to tell.
      *
      * `mask` is the caller's role (client `true` / server `false`); it governs whether the auto-Pong reply to a server Ping is masked,
      * which RFC 6455 §5.1 requires of a client.
      */
    def readFrameWith[A, S](
        src: Stream[Span[Byte], Async],
        dst: TransportStream,
        maxFrameSize: Int,
        onClose: ((Int, String)) => Unit < (S & Async),
        mask: Boolean,
        onFail: ((Int, String)) => Unit < (S & Async) = (_: (Int, String)) => Kyo.unit
    )(
        f: (HttpWebSocket.Payload, Stream[Span[Byte], Async]) => A < S
    )(using Frame): A < (S & Async & Abort[Closed]) =
        Abort.runWith[HttpException | ConnectionFailure](readMessage(src, dst, maxFrameSize, onClose, mask)) {
            case Result.Success((payload, remaining))       => f(payload, remaining)
            case Result.Failure(failure: ConnectionFailure) =>
                onFail((failure.code, failure.reason)).andThen(
                    Abort.fail(new Closed("HttpWebSocket", summon[Frame], s"Connection failed: ${failure.code} ${failure.reason}"))
                )
            case Result.Failure(_) => Abort.fail(new Closed("HttpWebSocket", summon[Frame]))
            case Result.Panic(t)   => Abort.panic(t)
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

    /** Write a Close frame (opcode 0x8). The codes that only report a close (1005 no status, 1006 abnormal, 1015 TLS failure) are never
      * sent in a Close frame (RFC 6455 section 7.4.1), so for those the frame carries no payload.
      */
    def writeClose(dst: TransportStream, code: Int, reason: String, mask: Boolean)(using Frame): Unit < Async =
        if code == 1005 || code == 1006 || code == 1015 then writeRawFrame(dst, OpClose, Span.empty[Byte], mask)
        else writeRawFrame(dst, OpClose, encodeClosePayload(code, reason), mask)

    /** Write an empty Ping frame (opcode 0x9), the keep-alive of `HttpWebSocket.Config.autoPingInterval`. */
    def writePing(dst: TransportStream, mask: Boolean)(using Frame): Unit < Async =
        writeRawFrame(dst, OpPing, Span.empty[Byte], mask)

    /** Closes a session's frame channel without losing what is in it: puts are refused at once, and its taker still receives every frame
      * already queued before a take fails with `Closed`. `closeDiscard` would drop frames put just before a close, which a peer sees as
      * a Close frame overtaking data sent ahead of it.
      */
    def closeKeepingQueued(channel: Channel[HttpWebSocket.Payload])(using Frame): Unit < Sync =
        // Unsafe: the drain is not awaited, because the closing side must not wait on a taker that may never take.
        Sync.Unsafe.defer(discard(channel.unsafe.closeAwaitEmpty()))

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

    /** Client: send the upgrade request for `url`, validate the 101 response, pass the remaining stream after headers to f. A well-formed
      * answer with any other status is the server refusing the upgrade: `HttpWebSocketHandshakeException` naming `url` and that status.
      */
    def requestUpgradeWith[A, S](
        conn: TransportStream,
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config
    )(
        f: Stream[Span[Byte], Async] => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        unsendableField(url.pathWithQuery, url.host, headers) match
            case Present(ex) => Abort.fail(ex)
            case Absent      =>
                unsendableSubprotocol(config, headers) match
                    case Present(ex) => Abort.fail(ex)
                    case Absent      => writeUpgradeRequestWith(conn, url, headers, config)(f)

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
        url: HttpUrl,
        headers: HttpHeaders,
        config: HttpWebSocket.Config
    )(
        f: Stream[Span[Byte], Async] => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        val host = url.host
        val path = url.pathWithQuery
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
            val callerSuppliedProtocols = listValues(headers.getAll("Sec-WebSocket-Protocol"))
            if config.subprotocols.nonEmpty && headers.getAll("Sec-WebSocket-Protocol").isEmpty then
                discard(request.append("Sec-WebSocket-Protocol: ").append(config.subprotocols.mkString(", ")).append("\r\n"))
            headers.foreach { (name, value) =>
                if !isRequiredClientUpgradeHeader(name) then
                    discard(request.append(name).append(": ").append(value).append("\r\n"))
            }
            discard(request.append("\r\n"))
            conn.write(Span.fromUnsafe(request.toString.getBytes(Utf8))).andThen {
                ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BeforeHead, conn.read, "\r\n\r\n".getBytes(Utf8), 4096) {
                    (headerBytes, remaining) =>
                        val responseStr = new String(headerBytes.toArrayUnsafe, Utf8)
                        val switched    = responseStr.startsWith("HTTP/1.1 ") &&
                            responseStatus(responseStr).contains(HttpStatus.SwitchingProtocols.code)
                        if !switched then
                            responseStatus(responseStr) match
                                case Present(status) => Abort.fail(HttpWebSocketHandshakeException(url.full, status))
                                case Absent          => Abort.fail(HttpProtocolException(upgradeRefusalDetail(responseStr)))
                        else
                            // The "offered" subprotocols are whatever went on the wire: caller-supplied fields, on as many lines as the
                            // caller wrote, take precedence over config.subprotocols.
                            val offered: Chunk[String] =
                                if headers.getAll("Sec-WebSocket-Protocol").nonEmpty then callerSuppliedProtocols
                                else Chunk.from(config.subprotocols)
                            upgradeRefusal(responseStr, computeAcceptKey(clientKey), offered) match
                                case Present(detail) => Abort.fail(HttpProtocolException(s"HttpWebSocket upgrade: $detail"))
                                case Absent          => f(remaining)
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
        val hash = Sha1.hashArray((clientKey + WsGuid).getBytes(Utf8))
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
            val result                           = new Array[Byte](payload.size)
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

    /** The status code of an HTTP/1.x status line (RFC 9112 section 4: version, SP, three digits, SP), or `Absent` when the answer does
      * not start with one.
      */
    private[internal] def responseStatus(responseStr: String): Maybe[Int] =
        val codeStart = "HTTP/1.1 ".length
        val codeEnd   = codeStart + 3
        if responseStr.length >= codeEnd &&
            (responseStr.startsWith("HTTP/1.1 ") || responseStr.startsWith("HTTP/1.0 ")) &&
            (codeStart until codeEnd).forall(i => responseStr.charAt(i) >= '0' && responseStr.charAt(i) <= '9') &&
            (responseStr.length == codeEnd || responseStr.charAt(codeEnd) == ' ' || responseStr.charAt(codeEnd) == '\r')
        then Present(responseStr.substring(codeStart, codeEnd).toInt)
        else Absent
        end if
    end responseStatus

    /** The detail of a refused upgrade: the status code the peer's status line carries, never its bytes. */
    private[internal] def upgradeRefusalDetail(responseStr: String): String =
        val statusLine =
            responseStr.length >= 12 && responseStr.startsWith("HTTP/1.") && responseStr.charAt(7).isDigit &&
                responseStr.charAt(8) == ' ' &&
                responseStr.substring(9, 12).forall(_.isDigit) &&
                (responseStr.length == 12 || responseStr.charAt(12) == ' ' || responseStr.charAt(12) == '\r')
        val status = if statusLine then s"got ${responseStr.substring(9, 12)}" else "no status line"
        s"HttpWebSocket upgrade failed: expected 101, $status"
    end upgradeRefusalDetail

    /** Why a 101 answer does not complete the handshake (RFC 6455 section 4.1), or `Absent` when it does: an Upgrade field that is not
      * `websocket`, a Connection field without the `Upgrade` option, a Sec-WebSocket-Accept field that is not exactly `expectedAccept`, any
      * extension, or a subprotocol the client did not offer. kyo-http implements no extension, so a 101 that selects one, requested or not,
      * would fail on the first frame that uses it; refusing it here fails where the caller can read why. The reason names the field, never
      * the value, which is the peer's and is logged.
      */
    private[internal] def upgradeRefusal(responseStr: String, expectedAccept: String, offered: Chunk[String]): Maybe[String] =
        responseFields(responseStr) match
            case Absent          => Present("malformed header field in the 101 answer")
            case Present(fields) =>
                def values(name: String): Chunk[String] = fields.filter(_._1.equalsIgnoreCase(name)).map(_._2)
                val protocols                           = values("Sec-WebSocket-Protocol")
                if listValues(values("Upgrade")).map(_.toLowerCase) != Chunk("websocket") then
                    Present("the 101 does not upgrade to websocket")
                else if !listValues(values("Connection")).exists(_.equalsIgnoreCase("upgrade")) then
                    Present("the 101 has no Connection: Upgrade")
                else if values("Sec-WebSocket-Accept") != Chunk(expectedAccept) then Present("invalid Sec-WebSocket-Accept")
                else if listValues(values("Sec-WebSocket-Extensions")).nonEmpty then Present("the 101 selects an extension")
                else if protocols.size > 1 then Present("the 101 selects more than one subprotocol")
                else if protocols.exists(s => !offered.contains(s)) then Present("the 101 selects a subprotocol the client did not offer")
                else Absent
                end if
        end match
    end upgradeRefusal

    /** The header fields of a raw HTTP/1.1 response head as (name, value) with the value's surrounding whitespace removed, or `Absent`
      * when a line after the status line is not a field (RFC 9112 section 5: a token name directly followed by a colon).
      */
    private[internal] def responseFields(responseStr: String): Maybe[Chunk[(String, String)]] =
        val lines  = Chunk.from(responseStr.split("\r\n", -1)).drop(1).filter(_.nonEmpty)
        val parsed = lines.map { line =>
            val colon = line.indexOf(':')
            if colon > 0 && HttpHeaders.isToken(line.substring(0, colon)) then
                Present((line.substring(0, colon), line.substring(colon + 1).trim))
            else Absent
        }
        if parsed.exists(_.isEmpty) then Absent else Present(parsed.flatMap(_.toChunk))
    end responseFields

    /** The elements of comma-separated field values (RFC 9110 section 5.6.1), without their surrounding whitespace or empty elements. */
    private def listValues(values: Seq[String]): Chunk[String] =
        Chunk.from(values).flatMap(v => Chunk.from(v.split(','))).map(_.trim).filter(_.nonEmpty)

    /** Decode the payload of a Close frame (opcode 0x8): first 2 bytes big-endian code, remaining bytes UTF-8 reason. Returns (1005, "") if
      * the frame had no payload (per RFC 6455 section 7.1.5, "no status received").
      */
    private[internal] def decodeClosePayload(payload: Span[Byte]): (Int, String) =
        if payload.size < 2 then (1005, "")
        else
            val code      = ((payload(0) & 0xff) << 8) | (payload(1) & 0xff)
            val reasonLen = payload.size - 2
            val reason    =
                if reasonLen <= 0 then ""
                else new String(payload.toArrayUnsafe, 2, reasonLen, Utf8)
            (code, reason)
    end decodeClosePayload

    /** A Close payload: the code, then as much of the reason as fits in 123 bytes, cut on a character boundary so it stays UTF-8. */
    private[internal] def encodeClosePayload(code: Int, reason: String): Span[Byte] =
        val reasonBytes                      = reason.getBytes(Utf8)
        val maxReasonLen                     = 123 // 125 - 2 for code
        @tailrec def boundary(len: Int): Int =
            if len < reasonBytes.length && len > 0 && (reasonBytes(len) & 0xc0) == 0x80 then boundary(len - 1) else len
        val truncatedLen = boundary(math.min(reasonBytes.length, maxReasonLen))
        val payload      = new Array[Byte](2 + truncatedLen)
        payload(0) = ((code >> 8) & 0xff).toByte
        payload(1) = (code & 0xff).toByte
        if truncatedLen > 0 then
            java.lang.System.arraycopy(reasonBytes, 0, payload, 2, truncatedLen)
        Span.fromUnsafe(payload)
    end encodeClosePayload

    // ── Internal I/O ────────────────────────────────────────────

    /** The (code, reason) of a received Close frame's payload, or the failure it is: a one-byte payload or a code no endpoint sends is a
      * protocol error, and a reason that is not UTF-8 is invalid data (RFC 6455 sections 5.5.1, 7.4 and 8.1).
      */
    private def closeStatus(payload: Span[Byte]): Result[ConnectionFailure, (Int, String)] =
        if payload.size == 1 then Result.fail(ConnectionFailure(CloseProtocolError, "Close frame payload of one byte"))
        else
            val (code, reason) = decodeClosePayload(payload)
            if payload.size >= 2 && !receivableCloseCode(code) then Result.fail(ConnectionFailure(CloseProtocolError, s"Close code $code"))
            else if payload.size > 2 && !isUtf8(payload.toArrayUnsafe, 2) then
                Result.fail(ConnectionFailure(CloseInvalidData, "Close reason is not UTF-8"))
            else Result.succeed((code, reason))
            end if

    /** Whether a peer may send `code` in a Close frame: the codes RFC 6455 section 7.4.1 defines for use in one, those registered since
      * (1012 to 1014), and the ranges for libraries (3000 to 3999) and applications (4000 to 4999).
      */
    private def receivableCloseCode(code: Int): Boolean =
        (code >= 1000 && code <= 1003) ||
            (code >= 1007 && code <= 1014) ||
            (code >= 3000 && code <= 4999)

    /** Whether `bytes` from `from` are well-formed UTF-8 (RFC 3629): no overlong form, no surrogate, nothing past U+10FFFF. */
    private[internal] def isUtf8(bytes: Array[Byte], from: Int = 0): Boolean =
        def continuation(i: Int, lo: Int = 0x80, hi: Int = 0xbf): Boolean =
            i < bytes.length && (bytes(i) & 0xff) >= lo && (bytes(i) & 0xff) <= hi
        @tailrec def loop(i: Int): Boolean =
            if i >= bytes.length then true
            else
                val b = bytes(i) & 0xff
                if b < 0x80 then loop(i + 1)
                else if b >= 0xc2 && b <= 0xdf then continuation(i + 1) && loop(i + 2)
                else if b >= 0xe0 && b <= 0xef then
                    val (lo, hi) = if b == 0xe0 then (0xa0, 0xbf) else if b == 0xed then (0x80, 0x9f) else (0x80, 0xbf)
                    continuation(i + 1, lo, hi) && continuation(i + 2) && loop(i + 3)
                else if b >= 0xf0 && b <= 0xf4 then
                    val (lo, hi) = if b == 0xf0 then (0x90, 0xbf) else if b == 0xf4 then (0x80, 0x8f) else (0x80, 0xbf)
                    continuation(i + 1, lo, hi) && continuation(i + 2) && continuation(i + 3) && loop(i + 4)
                else false
                end if
        loop(from)
    end isUtf8

    /** The message a complete data payload is: text must be UTF-8 (RFC 6455 section 8.1). */
    private def message(opcode: Int, payload: Span[Byte])(using Frame): HttpWebSocket.Payload < Abort[ConnectionFailure] =
        if opcode == OpBinary then HttpWebSocket.Payload.Binary(payload)
        else if isUtf8(payload.toArrayUnsafe) then HttpWebSocket.Payload.Text(new String(payload.toArrayUnsafe, Utf8))
        else Abort.fail(ConnectionFailure(CloseInvalidData, "text message is not UTF-8"))

    /** Reads frames until one completes a message, answering Pings and skipping Pongs met on the way, inside a fragmented message too
      * (RFC 6455 section 5.4). The state carried between frames is the opcode of the message in progress (`OpContinuation` for none), its
      * fragments so far and their total size.
      */
    private def readMessage[S](
        src: Stream[Span[Byte], Async],
        dst: TransportStream,
        maxFrameSize: Int,
        onClose: ((Int, String)) => Unit < (S & Async),
        mask: Boolean
    )(using
        Frame
    ): (HttpWebSocket.Payload, Stream[Span[Byte], Async]) < (S & Async & Abort[Closed | HttpException | ConnectionFailure]) =
        Loop(src, OpContinuation, Chunk.empty[Span[Byte]], 0) { (stream, messageOpcode, fragments, size) =>
            val inMessage = messageOpcode != OpContinuation
            readRawFrameWith(stream, maxFrameSize - size, expectMasked = !mask) { (fin, opcode, payload, remaining) =>
                opcode match
                    case OpPing =>
                        writeRawFrame(dst, OpPong, payload, mask).andThen(Loop.continue(remaining, messageOpcode, fragments, size))
                    case OpPong =>
                        Loop.continue(remaining, messageOpcode, fragments, size)
                    case OpClose =>
                        closeStatus(payload) match
                            case Result.Success((code, reason)) =>
                                onClose((code, reason)).andThen(
                                    Abort.fail(new Closed("HttpWebSocket", summon[Frame], s"Close frame received: $code $reason"))
                                )
                            case Result.Failure(failure) => Abort.fail(failure)
                            case Result.Panic(t)         => Abort.panic(t)
                    case OpText | OpBinary if inMessage =>
                        Abort.fail(ConnectionFailure(CloseProtocolError, "data frame inside a fragmented message"))
                    case OpText | OpBinary =>
                        if fin then message(opcode, payload).map(m => Loop.done((m, remaining)))
                        else Loop.continue(remaining, opcode, Chunk(payload), payload.size)
                    case OpContinuation if !inMessage =>
                        Abort.fail(ConnectionFailure(CloseProtocolError, "continuation frame outside a fragmented message"))
                    case OpContinuation =>
                        val all = fragments.append(payload)
                        if fin then message(messageOpcode, Span.concat(all.toSeq*)).map(m => Loop.done((m, remaining)))
                        else Loop.continue(remaining, messageOpcode, all, size + payload.size)
                    case other =>
                        Abort.fail(ConnectionFailure(CloseProtocolError, s"unknown opcode $other"))
                end match
            }
        }
    end readMessage

    /** Read a single raw frame (handles extended length + masking). A data frame is bounded by `dataLimit`, what is left of the bound on
      * its message; a control frame only by the 125 bytes RFC 6455 section 5.5 allows it, so a small message bound never refuses a Ping.
      */
    private inline def readRawFrameWith[A, S2](
        src: Stream[Span[Byte], Async],
        dataLimit: Int,
        expectMasked: Boolean
    )(
        inline f: (Boolean, Int, Span[Byte], Stream[Span[Byte], Async]) => A < S2
    )(using inline frame: Frame): A < (S2 & Async & Abort[HttpException | ConnectionFailure]) =
        ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, src, 2) { (header, rem1) =>
            val fh          = parseFrameHeader(header(0), header(1))
            val payloadLen  = fh.payloadLen
            val extLenBytes = if payloadLen == 126 then 2 else if payloadLen == 127 then 8 else 0
            val isControl   = fh.opcode >= OpClose
            val limit       = if isControl then 125 else dataLimit

            if (header(0) & 0x70) != 0 then
                // No extension is negotiated, so every reserved bit must be clear (RFC 6455 section 5.2).
                Abort.fail(ConnectionFailure(CloseProtocolError, "reserved bit set with no extension negotiated"))
            else if fh.masked != expectMasked then
                // A server MUST receive masked frames and a client MUST receive unmasked ones (RFC 6455 section 5.1);
                // the wrong masking direction is a protocol violation and (server side) a smuggling lever.
                Abort.fail(ConnectionFailure(CloseProtocolError, "frame masking violates role"))
            else if isControl && payloadLen > 125 then
                // A control frame's payload MUST be <= 125 bytes, so its length is never the 126/127 extended form
                // (RFC 6455 section 5.5).
                Abort.fail(ConnectionFailure(CloseProtocolError, "control frame payload exceeds 125 bytes"))
            else if isControl && !fh.fin then
                // A control frame MUST NOT be fragmented (RFC 6455 section 5.5).
                Abort.fail(ConnectionFailure(CloseProtocolError, "control frame is fragmented"))
            else
                ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, rem1, extLenBytes) { (ext, rem2) =>
                    val actualLen =
                        if extLenBytes == 0 then payloadLen.toLong
                        else if extLenBytes == 2 then ((ext(0) & 0xff) << 8) | (ext(1) & 0xff).toLong
                        else
                            @tailrec def readLong(i: Int, acc: Long): Long =
                                if i >= 8 then acc else readLong(i + 1, (acc << 8) | (ext(i) & 0xff))
                            readLong(0, 0L)
                    if actualLen < 0 then
                        // The most significant bit of a 64-bit length MUST be 0 (RFC 6455 section 5.2).
                        Abort.fail(ConnectionFailure(CloseProtocolError, "frame length has its most significant bit set"))
                    else if actualLen > limit.toLong then
                        Abort.fail(ConnectionFailure(CloseTooBig, "message exceeds max frame size"))
                    else
                        ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, rem2, if fh.masked then 4 else 0) {
                            (maskKey, rem3) =>
                                ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, rem3, actualLen.toInt) {
                                    (payload, rem4) =>
                                        f(fh.fin, fh.opcode, if fh.masked then unmask(payload, maskKey) else payload, rem4)
                                }
                        }
                    end if
                }
            end if
        }
    end readRawFrameWith

    /** Write a raw frame with header + optional masking, as one write: a frame is never split around another writer's (see
      * `TransportStream.write`).
      */
    private def writeRawFrame(dst: TransportStream, opcode: Int, payload: Span[Byte], mask: Boolean)(using Frame): Unit < Async =
        dst.write(encodeFrame(opcode, payload, mask))

    /** A whole frame: its header, then its payload, masked with the header's key when `mask`. */
    private[internal] def encodeFrame(opcode: Int, payload: Span[Byte], mask: Boolean): Span[Byte] =
        val header = encodeFrameHeader(opcode, payload.size.toLong, mask)
        val frame  = new Array[Byte](header.size + payload.size)
        discard(header.copyToArray(frame))
        @tailrec def copy(i: Int): Unit =
            if i < payload.size then
                frame(header.size + i) = if mask then (payload(i) ^ header(header.size - 4 + i % 4)).toByte else payload(i)
                copy(i + 1)
        copy(0)
        Span.fromUnsafe(frame)
    end encodeFrame

    private def randomBytes(n: Int): Array[Byte] =
        val bytes = new Array[Byte](n)
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes)
        bytes
    end randomBytes

end WebSocketCodec
