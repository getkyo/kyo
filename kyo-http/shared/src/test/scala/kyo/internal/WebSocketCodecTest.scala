package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.transport.*
import kyo.internal.util.*
import kyo.internal.websocket.*

class WebSocketCodecTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    private def wsUrl(path: String = "/ws"): HttpUrl = HttpUrl(Present("ws"), "localhost", 80, path, Absent)

    /** Mock TransportStream backed by byte arrays for testing WebSocketCodec.
      *
      * Each call to read() returns the entire remaining input as a single-span stream. Writes are captured in an output buffer.
      */
    class MockConn(input: Array[Byte]) extends TransportStream:
        private val output = new java.io.ByteArrayOutputStream()

        def read(using Frame): Stream[Span[Byte], Async] =
            if input.isEmpty then Stream.empty[Span[Byte]]
            else Stream.init(Seq(Span.fromUnsafe(input)))

        def write(data: Span[Byte])(using Frame): Unit < Async =
            Sync.defer(output.write(data.toArrayUnsafe))

        def written: Array[Byte]  = output.toByteArray
        def writtenString: String = new String(output.toByteArray, Utf8)
    end MockConn

    /** Build an unmasked WebSocket frame (server-to-client direction).
      *
      * Layout:
      *   - byte 0: FIN bit + opcode
      *   - byte 1: payload-length encoding (7-bit, 16-bit, or 64-bit)
      *   - bytes after length: payload (no masking key for server frames)
      */
    private def makeFrame(opcode: Int, fin: Boolean, payload: Array[Byte]): Array[Byte] =
        val finBit = if fin then 0x80 else 0
        val b0     = ((finBit | opcode) & 0xff).toByte
        val len    = payload.length
        val header =
            if len < 126 then
                Array[Byte](b0, len.toByte)
            else if len < 65536 then
                Array[Byte](
                    b0,
                    126.toByte,
                    ((len >> 8) & 0xff).toByte,
                    (len & 0xff).toByte
                )
            else
                val ll = len.toLong
                Array[Byte](
                    b0,
                    127.toByte,
                    ((ll >> 56) & 0xff).toByte,
                    ((ll >> 48) & 0xff).toByte,
                    ((ll >> 40) & 0xff).toByte,
                    ((ll >> 32) & 0xff).toByte,
                    ((ll >> 24) & 0xff).toByte,
                    ((ll >> 16) & 0xff).toByte,
                    ((ll >> 8) & 0xff).toByte,
                    (ll & 0xff).toByte
                )
        header ++ payload
    end makeFrame

    /** Build a masked WebSocket frame (client-to-server direction) with a fixed mask key, the framing a server-role
      * reader requires (RFC 6455 section 5.1). Handles 7-bit, 16-bit, and 64-bit length encodings.
      */
    private def makeMaskedFrame(opcode: Int, fin: Boolean, payload: Array[Byte]): Array[Byte] =
        val finBit    = if fin then 0x80 else 0
        val b0        = ((finBit | opcode) & 0xff).toByte
        val len       = payload.length
        val maskKey   = Array[Byte](0x12, 0x34, 0x56, 0x78)
        val lenHeader =
            if len < 126 then
                Array[Byte](b0, (0x80 | len).toByte)
            else if len < 65536 then
                Array[Byte](b0, (0x80 | 126).toByte, ((len >> 8) & 0xff).toByte, (len & 0xff).toByte)
            else
                val ll = len.toLong
                Array[Byte](
                    b0,
                    (0x80 | 127).toByte,
                    ((ll >> 56) & 0xff).toByte,
                    ((ll >> 48) & 0xff).toByte,
                    ((ll >> 40) & 0xff).toByte,
                    ((ll >> 32) & 0xff).toByte,
                    ((ll >> 24) & 0xff).toByte,
                    ((ll >> 16) & 0xff).toByte,
                    ((ll >> 8) & 0xff).toByte,
                    (ll & 0xff).toByte
                )
        val maskedPayload = new Array[Byte](len)
        var i             = 0
        while i < len do
            maskedPayload(i) = (payload(i) ^ maskKey(i % 4)).toByte
            i += 1
        lenHeader ++ maskKey ++ maskedPayload
    end makeMaskedFrame

    // ── Accept key ──────────────────────────────────────────────

    "computeAcceptKey" - {
        "known vector (RFC 6455 §4.2.2)" in {
            val key = WebSocketCodec.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ==")
            assert(key == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        }

        "produces valid base64" in {
            val key = WebSocketCodec.computeAcceptKey("testkey1234567890123=")
            assert(key.length == 28)
            assert(key.matches("[A-Za-z0-9+/]+=*"))
        }
    }

    // ── Frame header parsing ────────────────────────────────────

    "parseFrameHeader" - {
        "text frame, no mask, len 5" in {
            val fh = WebSocketCodec.parseFrameHeader(0x81.toByte, 0x05.toByte) // FIN + text, len 5
            assert(fh.fin == true)
            assert(fh.opcode == 0x1)
            assert(fh.masked == false)
            assert(fh.payloadLen == 5)
        }

        "binary frame, masked, len 100" in {
            val fh = WebSocketCodec.parseFrameHeader(0x82.toByte, (0x80 | 100).toByte)
            assert(fh.opcode == 0x2)
            assert(fh.masked == true)
            assert(fh.payloadLen == 100)
        }

        "16-bit length marker" in {
            val fh = WebSocketCodec.parseFrameHeader(0x81.toByte, 126.toByte) // 126 = extended 16-bit
            assert(fh.payloadLen == 126)
        }

        "64-bit length marker" in {
            val fh = WebSocketCodec.parseFrameHeader(0x81.toByte, 127.toByte) // 127 = extended 64-bit
            assert(fh.payloadLen == 127)
        }

        "FIN unset" in {
            val fh = WebSocketCodec.parseFrameHeader(0x01.toByte, 0x05.toByte) // no FIN
            assert(fh.fin == false)
        }

        "control frames" in {
            assert(WebSocketCodec.parseFrameHeader(0x89.toByte, 0x00.toByte).opcode == 0x9) // Ping
            assert(WebSocketCodec.parseFrameHeader(0x8a.toByte, 0x00.toByte).opcode == 0xa) // Pong
            assert(WebSocketCodec.parseFrameHeader(0x88.toByte, 0x00.toByte).opcode == 0x8) // Close
        }
    }

    // ── Masking ─────────────────────────────────────────────────

    "unmask" - {
        "known key" in {
            val mask     = Span.fromUnsafe(Array[Byte](0x37, 0xfa.toByte, 0x21, 0x3d))
            val masked   = Span.fromUnsafe(Array[Byte](0x7f, 0x9f.toByte, 0x4d, 0x51, 0x58))
            val result   = WebSocketCodec.unmask(masked, mask)
            val expected = Array[Byte]('H', 'e', 'l', 'l', 'o')
            assert(result.toArrayUnsafe.sameElements(expected))
        }

        "self-inverse" in {
            val data     = Span.fromUnsafe("hello world".getBytes(Utf8))
            val key      = Span.fromUnsafe(Array[Byte](1, 2, 3, 4))
            val masked   = WebSocketCodec.unmask(data, key)
            val unmasked = WebSocketCodec.unmask(masked, key)
            assert(unmasked.toArrayUnsafe.sameElements(data.toArrayUnsafe))
        }

        "zero key unchanged" in {
            val data = Span.fromUnsafe("test".getBytes(Utf8))
            val key  = Span.fromUnsafe(Array[Byte](0, 0, 0, 0))
            assert(WebSocketCodec.unmask(data, key).toArrayUnsafe.sameElements(data.toArrayUnsafe))
        }

        "empty payload" in {
            val result = WebSocketCodec.unmask(Span.empty[Byte], Span.fromUnsafe(Array[Byte](1, 2, 3, 4)))
            assert(result.isEmpty)
        }
    }

    // ── Frame encoding ──────────────────────────────────────────

    "encodeFrameHeader" - {
        "small payload (< 126)" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 5, Absent)
            assert(h.size == 2)
            assert((h(0) & 0xff) == 0x81) // FIN + text
            assert((h(1) & 0xff) == 5)
        }

        "medium payload (126-65535)" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 300, Absent)
            assert(h.size == 4)
            assert((h(1) & 0x7f) == 126)
        }

        "large payload (> 65535)" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 70000, Absent)
            assert(h.size == 10)
            assert((h(1) & 0x7f) == 127)
        }

        "a masking key sets the mask bit and follows the length, most significant byte first" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 5, Present(0x12345678))
            assert(h.size == 2 + 4)
            assert((h(1) & 0x80) == 0x80)
            assert(h.slice(2, 6).toArrayUnsafe.toSeq == Seq[Byte](0x12, 0x34, 0x56, 0x78))
        }

        "the masking key is the first four drawn bytes; a short draw leaves the rest zero instead of failing" in {
            assert(WebSocketCodec.maskKey(Span.fromUnsafe(Array[Byte](0x12, 0x34, 0x56, 0x78, -0x66))) == 0x12345678)
            assert(WebSocketCodec.maskKey(Span.fromUnsafe(Array[Byte](-1, 1))) == 0xff010000)
            assert(WebSocketCodec.maskKey(Span.empty[Byte]) == 0)
        }

        "mask and unmask with the same key's bytes agree" in {
            val payload = Span.fromUnsafe("hello, frame".getBytes("UTF-8"))
            val masked  = WebSocketCodec.maskPayload(payload, 0x12345678)
            val bytes   = Span.fromUnsafe(Array[Byte](0x12, 0x34, 0x56, 0x78))
            assert(WebSocketCodec.unmask(masked, bytes).toArrayUnsafe.toSeq == payload.toArrayUnsafe.toSeq)
            assert(masked.toArrayUnsafe.toSeq != payload.toArrayUnsafe.toSeq)
        }
    }

    // ── Close payload ───────────────────────────────────────────

    "encodeClosePayload" - {
        "code + reason" in {
            val payload = WebSocketCodec.encodeClosePayload(1000, "normal")
            assert(payload.size == 2 + "normal".length)
            assert(((payload(0) & 0xff) << 8 | (payload(1) & 0xff)) == 1000)
            assert(new String(payload.toArrayUnsafe, 2, payload.size - 2, Utf8) == "normal")
        }

        "code only" in {
            val payload = WebSocketCodec.encodeClosePayload(1000, "")
            assert(payload.size == 2)
        }

        "truncates long reason" in {
            val longReason = "x" * 200
            val payload    = WebSocketCodec.encodeClosePayload(1000, longReason)
            assert(payload.size == 2 + 123) // 125 max total, minus 2 for code
        }
    }

    // ── Fragmented messages (continuation frames) ──────────────

    // Frames here are unmasked server frames, so they are read in the client role (mask = true).
    private def readClientMessage(bytes: Array[Byte], maxFrameSize: Int = Int.MaxValue, maxMessageSize: Int = Int.MaxValue)(using
        Frame
    ): (Result[Closed, HttpWebSocket.Payload], MockConn) < Async =
        val conn = new MockConn(bytes)
        Abort.run[Closed](
            WebSocketCodec.readFrameWith(conn.read, conn, maxFrameSize, maxMessageSize, _ => Kyo.unit, mask = true)((frame, _) => frame)
        ).map(result => (result, conn))
    end readClientMessage

    "fragmented messages (continuation frames) reassemble into a single message (RFC 6455 section 5.4)" - {
        "three-frame text message: text/FIN=0 + continuation/FIN=0 + continuation/FIN=1" in {
            val frames = makeFrame(0x1, fin = false, "AAA".getBytes(Utf8)) ++
                makeFrame(0x0, fin = false, "BBB".getBytes(Utf8)) ++
                makeFrame(0x0, fin = true, "CCC".getBytes(Utf8))
            readClientMessage(frames).map { (result, _) =>
                assert(result == Result.succeed(HttpWebSocket.Payload.Text("AAABBBCCC")), s"got $result")
            }
        }

        "a binary message keeps its opcode and every fragment's bytes" in {
            val frames = makeFrame(0x2, fin = false, Array[Byte](1, 2, 3)) ++ makeFrame(0x0, fin = true, Array[Byte](4, 5, 6))
            readClientMessage(frames).map {
                case (Result.Success(HttpWebSocket.Payload.Binary(data)), _) =>
                    assert(data.toArrayUnsafe.toSeq == Seq[Byte](1, 2, 3, 4, 5, 6))
                case (other, _) => fail(s"expected one Binary message, got $other")
            }
        }

        "a UTF-8 character split across fragments decodes whole" in {
            // U+20AC is E2 82 AC; the first fragment ends inside it.
            val frames = makeFrame(0x1, fin = false, Array[Byte](0x61, 0xe2.toByte)) ++
                makeFrame(0x0, fin = true, Array[Byte](0x82.toByte, 0xac.toByte, 0x62))
            readClientMessage(frames).map { (result, _) =>
                assert(result == Result.succeed(HttpWebSocket.Payload.Text("a€b")), s"got $result")
            }
        }

        "a Ping between fragments is answered and the message still reassembles" in {
            val frames = makeFrame(0x1, fin = false, "AB".getBytes(Utf8)) ++
                makeFrame(0x9, fin = true, "p".getBytes(Utf8)) ++
                makeFrame(0x0, fin = true, "CD".getBytes(Utf8))
            readClientMessage(frames).map { (result, conn) =>
                assert(result == Result.succeed(HttpWebSocket.Payload.Text("ABCD")), s"got $result")
                assert(conn.written.headOption.contains((0x80 | 0xa).toByte), "the Ping is answered with a Pong")
            }
        }

        // Each malformed stream below is followed by a valid text frame, so a reader that tolerates the violation delivers
        // "abc" and the assertion discriminates.

        "a continuation frame with no message to continue is a protocol error" in {
            val frames = makeFrame(0x0, fin = true, "xyz".getBytes(Utf8)) ++ makeFrame(0x1, fin = true, "abc".getBytes(Utf8))
            readClientMessage(frames).map { (result, _) =>
                assert(result.isFailure, s"an orphan continuation must fail the connection, got $result")
            }
        }

        "a new data frame before the fragmented message completes is a protocol error" in {
            val frames = makeFrame(0x1, fin = false, "AB".getBytes(Utf8)) ++ makeFrame(0x1, fin = true, "abc".getBytes(Utf8))
            readClientMessage(frames).map { (result, _) =>
                assert(result.isFailure, s"a data frame inside a fragmented message must fail the connection, got $result")
            }
        }

        "a reassembled message larger than maxMessageSize is refused though every frame fits maxFrameSize" in {
            val frames = makeFrame(0x1, fin = false, "AAA".getBytes(Utf8)) ++
                makeFrame(0x0, fin = false, "BBB".getBytes(Utf8)) ++
                makeFrame(0x0, fin = true, "CCC".getBytes(Utf8))
            for
                over <- readClientMessage(frames, maxFrameSize = 4, maxMessageSize = 8)
                fits <- readClientMessage(frames, maxFrameSize = 4, maxMessageSize = 9)
            yield
                assert(over._1.isFailure, s"a 9-byte message must be refused under an 8-byte cap, got ${over._1}")
                assert(fits._1 == Result.succeed(HttpWebSocket.Payload.Text("AAABBBCCC")), s"got ${fits._1}")
            end for
        }
    }

    // RFC 6455 section 5.2: RSV1-3 MUST be 0 unless an extension defining them was negotiated, and kyo-http negotiates none.
    "a frame with a reserved bit set is a protocol error" in {
        val rsv1   = Array[Byte]((0x80 | 0x40 | 0x01).toByte, 3) ++ "xyz".getBytes(Utf8)
        val frames = rsv1 ++ makeFrame(0x1, fin = true, "abc".getBytes(Utf8))
        readClientMessage(frames).map { (result, _) =>
            assert(result.isFailure, s"a frame with RSV1 set must fail the connection, got $result")
        }
    }

    // ── Roundtrip ───────────────────────────────────────────────

    "writeFrame → readFrame roundtrip" - {
        "text unmasked" in {
            val writeConn = new MockConn(Array.empty[Byte])
            WebSocketCodec.writeFrame(writeConn, HttpWebSocket.Payload.Text("hello"), mask = false).andThen {
                val readConn = new MockConn(writeConn.written)
                // The written frame is unmasked (a server-to-client frame), so it is read in the client role (mask =
                // true), which expects unmasked incoming frames per RFC 6455 section 5.1.
                Abort.run[Closed](WebSocketCodec.readFrameWith(
                    readConn.read,
                    readConn,
                    Int.MaxValue,
                    Int.MaxValue,
                    _ => Kyo.unit,
                    mask = true
                )(
                    (
                        frame,
                        _
                    ) => frame
                )).map { result =>
                    result match
                        case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                            assert(text == "hello")
                        case other =>
                            fail(s"Expected Text(hello), got $other")
                }
            }
        }

        "binary masked" in {
            val data      = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            val writeConn = new MockConn(Array.empty[Byte])
            WebSocketCodec.writeFrame(writeConn, HttpWebSocket.Payload.Binary(data), mask = true).andThen {
                val readConn = new MockConn(writeConn.written)
                Abort.run[Closed](WebSocketCodec.readFrameWith(readConn.read, readConn)((frame, _) => frame)).map { result =>
                    result match
                        case Result.Success(HttpWebSocket.Payload.Binary(received)) =>
                            assert(received.toArrayUnsafe.sameElements(data.toArrayUnsafe))
                        case other =>
                            fail(s"Expected Binary, got $other")
                }
            }
        }

        "close frame aborts" in {
            val writeConn = new MockConn(Array.empty[Byte])
            WebSocketCodec.writeClose(writeConn, 1000, "bye", mask = false).andThen {
                val readConn = new MockConn(writeConn.written)
                Abort.run[Closed](WebSocketCodec.readFrameWith(readConn.read, readConn)((frame, _) => frame)).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "rejects frame larger than maxFrameSize" in {
            // A 5-byte unmasked (server) frame read in the client role (mask = true, so the masking check passes) under
            // a 4-byte cap, so the SIZE check is what fires.
            val frame = makeFrame(0x1, fin = true, "hello".getBytes(Utf8))
            val conn  = new MockConn(frame)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, 4, Int.MaxValue, _ => Kyo.unit, mask = true)((frame, _) =>
                frame
            )).map {
                result =>
                    assert(result.isFailure)
            }
        }

        // RFC 6455 section 5.1: a server MUST close the connection on receiving a frame that is not masked. Masking is
        // what stops an intermediary from being tricked into caching or misrouting attacker-chosen bytes, so a server
        // that accepts an unmasked client frame loses that protection. The server-role reader (mask = false) applies no
        // such check: it unmasks only when the mask bit is set and otherwise reads the frame as-is, whatever the role.
        // This asserts the secure behavior and therefore FAILS until the server rejects an unmasked client frame.
        //
        // Origin: RFC 6455 section 5.1; Tomcat Bug 69844 (the mirror, a client accepting a masked server frame).
        "rejects an unmasked client frame in the server role" in {
            // A well-formed text frame with the mask bit CLEAR (a legal server-to-client frame, illegal client-to-server).
            val unmasked = Array[Byte]((0x80 | 0x01).toByte, 0x05.toByte) ++ "hello".getBytes(Utf8)
            val conn     = new MockConn(unmasked)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((frame, _) => frame)).map { result =>
                assert(result.isFailure, s"a server must reject an unmasked client frame, but it was accepted: $result")
            }
        }

        // RFC 6455 section 5.5: all control frames (Close 0x8, Ping 0x9, Pong 0xA) MUST have a payload of 125 bytes or
        // fewer. A decoder that reads a 126/127 extended length for a control frame lets a peer drive a large
        // allocation from a frame that can carry none, the Tomcat CVE-2020-13935 class.
        //
        // A Ping is used, not a Close, so the assertion is discriminating: a Close would abort the reader regardless of
        // length (a vacuous pass), whereas a Ping is auto-Ponged and the reader continues to the next frame. The stream
        // is an oversized Ping followed by a valid text frame, so a decoder that ACCEPTS the bad Ping delivers "abc"
        // (success), and one that REJECTS it fails. Refusal is the secure outcome.
        "rejects a control frame with an extended payload length (CVE-2020-13935)" in {
            // A MASKED Ping (so the masking check passes and the SIZE check is what fires) whose 200-byte payload forces
            // the 126 extended-length form, which a control frame must never use. The following masked text frame would
            // be delivered as "abc" if the bad Ping were wrongly accepted, keeping the assertion discriminating.
            val ping = makeMaskedFrame(0x9, fin = true, new Array[Byte](200))
            val text = makeMaskedFrame(0x1, fin = true, "abc".getBytes(Utf8))
            val conn = new MockConn(ping ++ text)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((_, _) => "abc")).map { result =>
                assert(
                    result.isFailure,
                    s"a control frame over 125 bytes must be refused, but the reader accepted it and continued: $result"
                )
            }
        }

        // RFC 6455 section 5.5: a control frame MUST NOT be fragmented (FIN must be 1). A Ping with FIN=0 is malformed
        // and must be refused, not auto-Ponged as if complete. Same discriminating shape: fragmented Ping then a valid
        // text frame; accepting the bad Ping delivers "abc".
        "rejects a fragmented control frame (RFC 6455 section 5.5)" in {
            // A MASKED Ping with FIN CLEAR (so the masking check passes and the FIN check is what fires), zero payload.
            // The following masked text frame would be delivered as "abc" if the bad Ping were wrongly accepted.
            val ping = makeMaskedFrame(0x9, fin = false, Array.empty[Byte])
            val text = makeMaskedFrame(0x1, fin = true, "abc".getBytes(Utf8))
            val conn = new MockConn(ping ++ text)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((_, _) => "abc")).map { result =>
                assert(result.isFailure, s"a fragmented control frame must be refused, but the reader accepted it and continued: $result")
            }
        }

        // The client-role mirror of the server masking check: a client MUST reject a MASKED frame, because a server
        // must never mask (RFC 6455 section 5.1). Tomcat Bug 69844 is exactly this direction. The client-role reader is
        // mask = true, so expectMasked is false.
        "rejects a masked server frame in the client role (Tomcat Bug 69844)" in {
            val masked = makeMaskedFrame(0x1, fin = true, "hello".getBytes(Utf8))
            val conn   = new MockConn(masked)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, Int.MaxValue, Int.MaxValue, _ => Kyo.unit, mask = true)(
                (
                    frame,
                    _
                ) => frame
            )).map { result =>
                assert(result.isFailure, s"a client must reject a masked server frame, but it was accepted: $result")
            }
        }

        "rejects 64-bit frame lengths before integer overflow" in {
            // An unmasked (server) attack frame read in the client role (mask = true), so the masking check passes and
            // the 64-bit length check is what fires.
            val frame = Array[Byte](
                (0x80 | 0x01).toByte,
                127.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x80.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte
            )
            val conn = new MockConn(frame)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, Int.MaxValue, Int.MaxValue, _ => Kyo.unit, mask = true)(
                (frame, _) =>
                    frame
            )).map { result =>
                assert(result.isFailure)
            }
        }

        // The 64-bit extended length is read into a Long, so every bit set reads as -1. A decoder that carries that
        // straight to a read length asks for a negative count, which a bounds-tolerant reader answers with zero bytes:
        // the frame then "succeeds" as empty, having consumed only the header, and every subsequent frame on the
        // connection is read from the wrong offset. The pin is the SUCCESS side, not just any failure -- an unguarded
        // decoder returns Text("") here rather than erroring, so a test asserting only "something went wrong" would
        // pass either way.
        "rejects a 64-bit frame length whose bits are all set (GHSA-3j86-pj9g-jchr)" in {
            val frame = Array[Byte](
                (0x80 | 0x01).toByte,
                127.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte,
                0xff.toByte
            )
            val conn = new MockConn(frame)
            // Client role (mask = true) so the unmasked attack frame passes the masking check and the length check fires.
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, Int.MaxValue, Int.MaxValue, _ => Kyo.unit, mask = true)(
                (frame, _) =>
                    frame
            )).map {
                case Result.Failure(_: Closed) => succeed("a negative declared length was rejected")
                case other                     => fail(s"a negative declared length was accepted, got $other")
            }
        }

        // A length that is positive as a Long but truncates to a small positive Int: 2^32 + 100 becomes exactly 100.
        // An unguarded decoder returns a well-formed 100-byte frame while the peer believes it declared 4 GiB, so the
        // two ends disagree about where the next frame starts. Same desync as the case above, reached from the other
        // direction, and again the failure mode is a plausible SUCCESS rather than an error.
        "rejects a 64-bit frame length that truncates to a valid Int (GHSA-3j86-pj9g-jchr)" in {
            val payload = new Array[Byte](100)
            val frame   = Array[Byte](
                (0x80 | 0x01).toByte,
                127.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x01.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x64.toByte
            ) ++ payload
            val conn = new MockConn(frame)
            // Client role (mask = true) so the unmasked attack frame passes the masking check and the length check fires.
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, Int.MaxValue, Int.MaxValue, _ => Kyo.unit, mask = true)(
                (frame, _) =>
                    frame
            )).map {
                case Result.Failure(_: Closed) => succeed("a length exceeding Int range was rejected")
                case other                     => fail(s"a 2^32+100 length was truncated to 100 and accepted, got $other")
            }
        }

        // The configured cap on the EXTENDED-length path. The existing cap test above drives the 7-bit path only, where
        // the declared length cannot exceed 125 and the cap is never the thing under test.
        "enforces maxFrameSize on the 64-bit length path (GHSA-3j86-pj9g-jchr)" in {
            val frame = Array[Byte](
                (0x80 | 0x01).toByte,
                127.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x00.toByte,
                0x01.toByte,
                0x00.toByte,
                0x00.toByte
            )
            val conn = new MockConn(frame)
            // Client role (mask = true) so the unmasked attack frame passes the masking check and the cap is what fires.
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, 4, Int.MaxValue, _ => Kyo.unit, mask = true)((frame, _) =>
                frame
            )).map {
                case Result.Failure(_: Closed) => succeed("a 65536-byte frame was rejected against a 4-byte cap")
                case other                     => fail(s"a 65536-byte frame passed a 4-byte cap, got $other")
            }
        }
    }

    // ── Upgrade handshake ───────────────────────────────────────

    "acceptUpgrade" - {
        "writes 101 with correct accept key" in {
            val conn    = new MockConn(Array.empty[Byte])
            val headers = HttpHeaders.empty.add("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            WebSocketCodec.acceptUpgrade(conn, headers, HttpWebSocket.Config()).andThen {
                val response = conn.writtenString
                assert(response.contains("101 Switching Protocols"))
                assert(response.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
                assert(response.contains("Upgrade: websocket"))
            }
        }

        "fails on missing key" in {
            val conn = new MockConn(Array.empty[Byte])
            Abort.run[HttpException] {
                WebSocketCodec.acceptUpgrade(conn, HttpHeaders.empty, HttpWebSocket.Config())
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "requestUpgrade" - {
        "writes correct upgrade request" in {
            // Create a mock that returns a 101 response when read.
            // We can't predict the exact accept key since requestUpgradeWith generates a random client key,
            // so we test the write side only by examining what was written before the read fails.
            val conn = new MockConn(Array.empty[Byte])
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl(), HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
            }.map { _ =>
                val written = conn.writtenString
                assert(written.contains("GET /ws HTTP/1.1"))
                assert(written.contains("Upgrade: websocket"))
                assert(written.contains("Sec-WebSocket-Key:"))
                assert(written.contains("Sec-WebSocket-Version: 13"))
            }
        }

        "a well-formed non-101 status line is the handshake leaf naming the url and the status" in {
            Kyo.foreach(Chunk("HTTP/1.1 400 Bad Request\r\n\r\n", "HTTP/1.0 403 \r\n\r\n", "HTTP/1.1 200\r\n\r\n")) { response =>
                val conn = new MockConn(response.getBytes(Utf8)):
                    override def write(data: Span[Byte])(using Frame): Unit < Async = Kyo.unit
                Abort.run[HttpException] {
                    WebSocketCodec.requestUpgradeWith(conn, wsUrl("/ws?k=v"), HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
                }
            }.map { results =>
                val refused = results.map(_.failure.collect { case e: HttpWebSocketHandshakeException => (e.url, e.status) })
                assert(refused == Chunk(400, 403, 200).map(s => Present(("ws://localhost/ws", s))), results.toString)
            }
        }

        "a status code that only starts with 101 is refused as no status line, not read as a switch" in {
            val conn = new MockConn("HTTP/1.1 1012 Switching\r\nUpgrade: websocket\r\n\r\n".getBytes(Utf8)):
                override def write(data: Span[Byte])(using Frame): Unit < Async = Kyo.unit
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl(), HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
            }.map { result =>
                assert(
                    result.failure.exists(_.getMessage.contains("HttpWebSocket upgrade failed: expected 101, no status line")),
                    result.toString
                )
            }
        }

        "an answer that is not an HTTP/1.x status line is a protocol failure" in {
            Kyo.foreach(Chunk("HTTP/2 400\r\n\r\n", "HTTP/1.1 4x0 Bad\r\n\r\n", "HTTP/1.1 4000 Bad\r\n\r\n", "garbage\r\n\r\n")) {
                response =>
                    val conn = new MockConn(response.getBytes(Utf8)):
                        override def write(data: Span[Byte])(using Frame): Unit < Async = Kyo.unit
                    Abort.run[HttpException] {
                        WebSocketCodec.requestUpgradeWith(conn, wsUrl(), HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
                    }
            }.map { results =>
                assert(
                    results.map(_.failure.map(_.getClass.getSimpleName)) == Chunk.fill(4)(Present("HttpProtocolException")),
                    results.toString
                )
            }
        }

        // The handshake is a third header serializer, and it appends caller headers to the request with no check at all.
        // A CRLF in a value ends the header line early, so "X-Admin: true" goes out as a header the caller never set.
        // Nothing may be written: the check has to precede the write, not follow it.
        "fails on a CRLF-bearing header value without writing the handshake" in {
            val conn    = new MockConn(Array.empty[Byte])
            val headers = HttpHeaders.empty.add("X-Trace", "bar\r\nX-Admin: true")
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl(), headers, HttpWebSocket.Config()) { _ => () }
            }.map {
                case Result.Failure(ex: HttpInvalidFieldException) =>
                    assert(ex.field == "the value of header 'X-Trace'", s"the failure must name the header, got: ${ex.field}")
                    assert(conn.written.isEmpty, s"nothing may reach the wire, got: ${conn.writtenString}")
                case other =>
                    fail(s"Expected HttpInvalidFieldException for a CRLF-bearing header value, got $other")
            }
        }

        // A field name is a token (RFC 9110 section 5.6.2), and the handshake writes names as given.
        "fails on a header name that is not a token" in {
            val conn    = new MockConn(Array.empty[Byte])
            val headers = HttpHeaders.empty.add("X Trace", "value")
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl(), headers, HttpWebSocket.Config()) { _ => () }
            }.map {
                case Result.Failure(ex: HttpInvalidFieldException) =>
                    assert(ex.field == "the name of the header at index 0", s"the failure must name the position, got: ${ex.field}")
                    assert(conn.written.isEmpty, s"nothing may reach the wire, got: ${conn.writtenString}")
                case other =>
                    fail(s"Expected HttpInvalidFieldException for a non-token header name, got $other")
            }
        }

        // A CRLF in the path splits the request line itself, which is the same vector one layer up.
        "fails on a CRLF-bearing path without writing the handshake" in {
            val conn = new MockConn(Array.empty[Byte])
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl("/ws\r\nX-Admin: true"), HttpHeaders.empty, HttpWebSocket.Config()) {
                    _ => ()
                }
            }.map {
                case Result.Failure(ex: HttpInvalidFieldException) =>
                    assert(ex.field == "the request path", s"the failure must name the path, got: ${ex.field}")
                    assert(conn.written.isEmpty, s"nothing may reach the wire, got: ${conn.writtenString}")
                case other =>
                    fail(s"Expected HttpInvalidFieldException for a CRLF-bearing path, got $other")
            }
        }

        // The over-strictness guard: the handshake already encodes the whole request as UTF-8, and obs-text values are
        // legal (RFC 9110 section 5.5), so a non-ASCII value must still go out rather than fail the upgrade.
        "writes a non-ASCII header value as UTF-8 octets" in {
            val conn    = new MockConn(Array.empty[Byte])
            val headers = HttpHeaders.empty.add("X-Trace", "café")
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl(), headers, HttpWebSocket.Config()) { _ => () }
            }.map { _ =>
                // The read side fails (the mock serves no response); the write side is what this asserts.
                assert(conn.writtenString.contains("X-Trace: café\r\n"), s"Got: ${conn.writtenString}")
                val expected = "X-Trace: caf".getBytes(StandardCharsets.US_ASCII).toSeq ++ Seq[Byte](0xc3.toByte, 0xa9.toByte)
                assert(conn.written.toSeq.containsSlice(expected), "the value must reach the wire as UTF-8 octets")
            }
        }

        // A subprotocol is a token (RFC 6455 section 4.1). The client advertises config.subprotocols on the
        // Sec-WebSocket-Protocol request line, so a CRLF in one splits that line early and injects a header the caller
        // never set, the same vector the server already refuses when it echoes a selected subprotocol (isToken,
        // acceptUpgrade). The check must precede the write: nothing may reach the wire.
        "fails on a non-token configured subprotocol without writing the handshake" in {
            val conn   = new MockConn(Array.empty[Byte])
            val config = HttpWebSocket.Config(subprotocols = Seq("chat\r\nX-Injected: 1"))
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, wsUrl(), HttpHeaders.empty, config) { _ => () }
            }.map {
                case Result.Failure(ex: HttpInvalidFieldException) =>
                    assert(conn.written.isEmpty, s"nothing may reach the wire, got: ${conn.writtenString}")
                case other =>
                    fail(s"Expected HttpInvalidFieldException for a non-token subprotocol, got $other")
            }
        }
    }

    // ── Ping/Pong handling ────────────────────────────────────

    "ping auto-sends pong and returns next data frame" in {
        val textPayload = "after-ping".getBytes(Utf8)
        val pingPayload = "pingdata".getBytes(Utf8)

        // Masked Ping and Text: a server-role reader requires client frames to be masked (RFC 6455 section 5.1).
        val pingFrame = makeMaskedFrame(0x9, fin = true, pingPayload)
        val textFrame = makeMaskedFrame(0x1, fin = true, textPayload)

        val conn = new MockConn(pingFrame ++ textFrame)
        // Default (server) mode: the auto-Pong is UNMASKED per RFC 6455 §5.1 (servers must not mask).
        Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((frame, _) => frame)).map {
            case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                assert(text == "after-ping")
                val pong = conn.written
                assert(pong.nonEmpty) // Pong frame was auto-sent
                assert((pong(0) & 0xff) == 0x8a, s"expected Pong opcode, got ${pong(0) & 0xff}")
                assert((pong(1) & 0x80) == 0, "server auto-Pong must NOT set the mask bit")
            case other =>
                fail(s"Expected Text after Ping, got $other")
        }
    }

    "ping in client mode auto-sends a MASKED pong (RFC 6455 §5.1)" in {
        // A client MUST mask every frame it sends, including the auto-Pong it emits in reply to a
        // server Ping. Slack's Socket Mode gateway enforces this and closes an unmasked frame with
        // 1002. The auto-Pong is written by the shared readFrameWith, so the client role must reach it.
        val pingPayload = "pingdata".getBytes(Utf8)
        val pingFrame   = Array[Byte]((0x80 | 0x09).toByte, pingPayload.length.toByte) ++ pingPayload
        val textPayload = "after-ping".getBytes(Utf8)
        val textFrame   = Array[Byte]((0x80 | 0x01).toByte, textPayload.length.toByte) ++ textPayload

        val conn = new MockConn(pingFrame ++ textFrame)
        Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, Int.MaxValue, Int.MaxValue, _ => Kyo.unit, mask = true)(
            (frame, _) =>
                frame
        )).map {
            case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                assert(text == "after-ping")
                val pong = conn.written
                assert((pong(0) & 0xff) == 0x8a, s"expected Pong opcode, got ${pong(0) & 0xff}")
                assert((pong(1) & 0x80) != 0, "client auto-Pong must set the mask bit")
                val payloadLen = pong(1) & 0x7f
                assert(payloadLen == pingPayload.length, s"pong payload length should be ${pingPayload.length}, got $payloadLen")
                // bytes 2..5 are the mask key; unmasking the remainder must recover the ping payload.
                val maskKey   = Span.fromUnsafe(pong.slice(2, 6))
                val recovered = WebSocketCodec.unmask(Span.fromUnsafe(pong.slice(6, 6 + payloadLen)), maskKey).toArrayUnsafe
                assert(recovered.sameElements(pingPayload), "unmasked pong payload must equal the ping payload")
            case other =>
                fail(s"Expected Text after Ping, got $other")
        }
    }

    "pong is ignored, returns next data frame" in {
        val textPayload = "afterpong".getBytes(Utf8)
        val pongPayload = "pongdata".getBytes(Utf8)

        // Masked Pong and Text: a server-role reader requires client frames to be masked (RFC 6455 section 5.1).
        val pongFrame = makeMaskedFrame(0xa, fin = true, pongPayload)
        val textFrame = makeMaskedFrame(0x1, fin = true, textPayload)

        val conn = new MockConn(pongFrame ++ textFrame)
        Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((frame, _) => frame)).map {
            case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                assert(text == "afterpong")
            case other =>
                fail(s"Expected Text after Pong, got $other")
        }
    }

    "unknown opcode causes Abort[Closed]" in {
        val unknownFrame = Array[Byte](
            (0x80 | 0x0f).toByte, // FIN + opcode 15 (unknown)
            0x00.toByte           // no payload
        )
        val conn = new MockConn(unknownFrame)
        Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((frame, _) => frame)).map { result =>
            assert(result.isFailure)
        }
    }

    "decodeClosePayload extracts code and reason" in {
        val payload = Span.fromUnsafe(Array[Byte](
            ((4001 >> 8) & 0xff).toByte,
            (4001 & 0xff).toByte
        ) ++ "bye".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        val (code, reason) = WebSocketCodec.decodeClosePayload(payload)
        assert(code == 4001)
        assert(reason == "bye")
    }

    "decodeClosePayload returns (1005, \"\") on empty payload" in {
        val (code, reason) = WebSocketCodec.decodeClosePayload(Span.empty[Byte])
        assert(code == 1005)
        assert(reason == "")
    }

    "decodeClosePayload returns (code, \"\") when only the 2-byte code is present" in {
        val payload        = Span.fromUnsafe(Array[Byte](0x03, 0xe8.toByte)) // 1000
        val (code, reason) = WebSocketCodec.decodeClosePayload(payload)
        assert(code == 1000)
        assert(reason == "")
    }

    "parseResponseSubprotocol extracts the value (case-insensitive)" in {
        val r1 = WebSocketCodec.parseResponseSubprotocol(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Protocol: graphql-transport-ws\r\n\r\n"
        )
        assert(r1.contains("graphql-transport-ws"))
        val r2 = WebSocketCodec.parseResponseSubprotocol(
            "HTTP/1.1 101 Switching Protocols\r\nsec-websocket-protocol:  chat  \r\n\r\n"
        )
        assert(r2.contains("chat"))
    }

    "upgradeRefusalDetail names the status code of any HTTP/1.x status line, and says so when the line is not one" in {
        assert(WebSocketCodec.upgradeRefusalDetail("HTTP/1.1 404 Not Found\r\n\r\n") ==
            "HttpWebSocket upgrade failed: expected 101, got 404")
        assert(WebSocketCodec.upgradeRefusalDetail("HTTP/1.0 200 OK\r\n\r\n") == "HttpWebSocket upgrade failed: expected 101, got 200")
        assert(WebSocketCodec.upgradeRefusalDetail("HTTP/1.1 4o4 Not Found\r\n\r\n") ==
            "HttpWebSocket upgrade failed: expected 101, no status line")
        assert(WebSocketCodec.upgradeRefusalDetail("<html>\r\n\r\n") == "HttpWebSocket upgrade failed: expected 101, no status line")
    }

    "upgradeRefusalDetail reads a status code as exactly three digits, so a fourth digit is no status line" in {
        assert(WebSocketCodec.upgradeRefusalDetail("HTTP/1.1 4000 Bad\r\n\r\n") ==
            "HttpWebSocket upgrade failed: expected 101, no status line")
        assert(WebSocketCodec.upgradeRefusalDetail("HTTP/1.1 404") == "HttpWebSocket upgrade failed: expected 101, got 404")
        assert(WebSocketCodec.upgradeRefusalDetail("HTTP/1.1 404\r\n\r\n") == "HttpWebSocket upgrade failed: expected 101, got 404")
    }

    "parseResponseSubprotocol returns Absent when header absent" in {
        val r = WebSocketCodec.parseResponseSubprotocol(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"
        )
        assert(r.isEmpty)
    }

    "keys come from the ambient SecureRandom (RFC 6455 section 5.3)" - {
        // Hands out the bytes 0, 1, 2, ... across calls, so a leaf can name the bytes the codec must have drawn and in which order.
        def counting: SecureRandom =
            var next = 0
            SecureRandom(
                new SecureRandom.Unsafe:
                    def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                        val bytes = Array.tabulate(length)(i => (next + i).toByte)
                        next += length
                        Span.fromUnsafe(bytes)
                    end nextBytes
            )
        end counting

        "the client's Sec-WebSocket-Key is the base64 of 16 bytes from the generator" in {
            val conn = new MockConn(Array.empty[Byte])
            SecureRandom.let(counting) {
                Abort.run[HttpException] {
                    WebSocketCodec.requestUpgradeWith(conn, wsUrl(), HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
                }
            }.map { _ =>
                val expected = java.util.Base64.getEncoder.encodeToString(Array.tabulate(16)(_.toByte))
                assert(conn.writtenString.contains(s"Sec-WebSocket-Key: $expected\r\n"), conn.writtenString)
            }
        }

        "a client frame's masking key is the next 4 bytes from the generator, and the payload is masked with it" in {
            val conn = new MockConn(Array.empty[Byte])
            val data = Span.fromUnsafe(Array[Byte](10, 20, 30, 40, 50))
            SecureRandom.let(counting) {
                WebSocketCodec.writeFrame(conn, HttpWebSocket.Payload.Binary(data), mask = true)
            }.map { _ =>
                val written = conn.written
                assert(written.length == 2 + 4 + 5, written.toSeq.toString)
                assert(written(1) == (0x80 | 5).toByte)
                assert(written.slice(2, 6).toSeq == Seq[Byte](0, 1, 2, 3))
                assert(written.slice(6, 11).toSeq == Seq(10 ^ 0, 20 ^ 1, 30 ^ 2, 40 ^ 3, 50 ^ 0).map(_.toByte))
            }
        }

        "a server frame draws nothing" in {
            val conn  = new MockConn(Array.empty[Byte])
            var drew  = false
            val noisy = SecureRandom(
                new SecureRandom.Unsafe:
                    def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                        drew = true
                        Span.fromUnsafe(new Array[Byte](length))
            )
            SecureRandom.let(noisy) {
                WebSocketCodec.writeFrame(conn, HttpWebSocket.Payload.Text("hi"), mask = false)
            }.map { _ =>
                assert(!drew)
                assert(conn.written.length == 2 + 2)
            }
        }
    }

    "parseResponseSubprotocol does not read a header named with U+212A (Kelvin sign) as Sec-WebSocket-Protocol" in {
        val r = WebSocketCodec.parseResponseSubprotocol(
            "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocKet-Protocol: chat\r\n\r\n"
        )
        assert(r.isEmpty)
    }

end WebSocketCodecTest
