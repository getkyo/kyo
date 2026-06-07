package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.transport.*
import kyo.internal.util.*
import kyo.internal.websocket.*

class WebSocketCodecTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

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
            val h = WebSocketCodec.encodeFrameHeader(0x1, 5, mask = false)
            assert(h.size == 2)
            assert((h(0) & 0xff) == 0x81) // FIN + text
            assert((h(1) & 0xff) == 5)
        }

        "medium payload (126-65535)" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 300, mask = false)
            assert(h.size == 4)
            assert((h(1) & 0x7f) == 126)
        }

        "large payload (> 65535)" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 70000, mask = false)
            assert(h.size == 10)
            assert((h(1) & 0x7f) == 127)
        }

        "mask adds 4 bytes" in {
            val h = WebSocketCodec.encodeFrameHeader(0x1, 5, mask = true)
            assert(h.size == 2 + 4) // header + mask key
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

    "fragmented messages (continuation frames) reassemble into a single message" - {
        "three-frame text message: text/FIN=0 + continuation/FIN=0 + continuation/FIN=1".ignore(
            "WebSocketCodec.readFrameWith does not yet reassemble multi-frame continuation messages; it delivers each frame raw (proper reassembly is a follow-up)"
        ) in {
            // Spec: a WebSocket message MAY be split across multiple frames per RFC 6455.
            // The first frame carries the opcode (text or binary) with FIN=0; subsequent
            // frames use opcode 0x0 (continuation) with FIN=0; the final frame uses
            // opcode 0x0 with FIN=1. WebSocketCodec.readFrameWith should reassemble
            // the concatenated payload and yield a single Payload value with the
            // original text opcode.
            //
            // Current behavior: readFrameWith delivers each frame raw (opcode 0x1 for
            // the first, 0x0 for the continuations), and maxFrameSize is checked per
            // individual frame. The kyo-http 16 MiB default is a per-frame pragmatic
            // ceiling; proper reassembly is the follow-up.
            val frame1 = makeFrame(opcode = 0x1, fin = false, payload = "AAA".getBytes(Utf8))
            val frame2 = makeFrame(opcode = 0x0, fin = false, payload = "BBB".getBytes(Utf8))
            val frame3 = makeFrame(opcode = 0x0, fin = true, payload = "CCC".getBytes(Utf8))
            val mock   = new MockConn(frame1 ++ frame2 ++ frame3)
            WebSocketCodec.readFrameWith(mock.read, mock, Int.MaxValue) { (payload, _) =>
                payload match
                    case HttpWebSocket.Payload.Text(s) =>
                        assert(s == "AAABBBCCC")
                    case other =>
                        fail(s"expected Text payload with reassembled content, got $other")
            }
        }
    }

    // ── Roundtrip ───────────────────────────────────────────────

    "writeFrame → readFrame roundtrip" - {
        "text unmasked" in {
            val writeConn = new MockConn(Array.empty[Byte])
            WebSocketCodec.writeFrame(writeConn, HttpWebSocket.Payload.Text("hello"), mask = false).andThen {
                val readConn = new MockConn(writeConn.written)
                Abort.run[Closed](WebSocketCodec.readFrameWith(readConn.read, readConn)((frame, _) => frame)).map { result =>
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
            val payload = "hello".getBytes(Utf8)
            val frame = Array[Byte](
                (0x80 | 0x01).toByte,
                payload.length.toByte
            ) ++ payload
            val conn = new MockConn(frame)
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn, maxFrameSize = 4)((frame, _) => frame)).map { result =>
                assert(result.isFailure)
            }
        }

        "rejects 64-bit frame lengths before integer overflow" in {
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
            Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((frame, _) => frame)).map { result =>
                assert(result.isFailure)
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
                WebSocketCodec.requestUpgradeWith(conn, "localhost", "/ws", HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
            }.map { _ =>
                val written = conn.writtenString
                assert(written.contains("GET /ws HTTP/1.1"))
                assert(written.contains("Upgrade: websocket"))
                assert(written.contains("Sec-WebSocket-Key:"))
                assert(written.contains("Sec-WebSocket-Version: 13"))
            }
        }

        "fails on non-101 response" in {
            val response = "HTTP/1.1 400 Bad Request\r\n\r\n"
            // A conn that discards writes and serves a 400 response on read
            val conn = new MockConn(response.getBytes(Utf8)):
                override def write(data: Span[Byte])(using Frame): Unit < Async = Kyo.unit
            Abort.run[HttpException] {
                WebSocketCodec.requestUpgradeWith(conn, "localhost", "/ws", HttpHeaders.empty, HttpWebSocket.Config()) { _ => () }
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    // ── Ping/Pong handling ────────────────────────────────────

    "ping auto-sends pong and returns next data frame" in {
        val textPayload = "after-ping".getBytes(Utf8)
        val pingPayload = "pingdata".getBytes(Utf8)

        // Ping frame: FIN=1, opcode=9, no mask
        val pingFrame = Array[Byte](
            (0x80 | 0x09).toByte, // FIN + Ping
            pingPayload.length.toByte
        ) ++ pingPayload

        // Text frame: FIN=1, opcode=1, no mask
        val textFrame = Array[Byte](
            (0x80 | 0x01).toByte, // FIN + Text
            textPayload.length.toByte
        ) ++ textPayload

        val conn = new MockConn(pingFrame ++ textFrame)
        Abort.run[Closed](WebSocketCodec.readFrameWith(conn.read, conn)((frame, _) => frame)).map {
            case Result.Success(HttpWebSocket.Payload.Text(text)) =>
                assert(text == "after-ping")
                val written = conn.written
                assert(written.nonEmpty) // Pong frame was auto-sent
            case other =>
                fail(s"Expected Text after Ping, got $other")
        }
    }

    "pong is ignored, returns next data frame" in {
        val textPayload = "afterpong".getBytes(Utf8)
        val pongPayload = "pongdata".getBytes(Utf8)

        val pongFrame = Array[Byte](
            (0x80 | 0x0a).toByte, // FIN + Pong
            pongPayload.length.toByte
        ) ++ pongPayload

        val textFrame = Array[Byte](
            (0x80 | 0x01).toByte, // FIN + Text
            textPayload.length.toByte
        ) ++ textPayload

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

    "parseResponseSubprotocol returns None when header absent" in {
        val r = WebSocketCodec.parseResponseSubprotocol(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"
        )
        assert(r.isEmpty)
    }

end WebSocketCodecTest
