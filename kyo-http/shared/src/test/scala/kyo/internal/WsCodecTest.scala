package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class WsCodecTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    /** Mock TransportStream backed by byte arrays. */
    class MockStream(input: Array[Byte]) extends TransportStream:
        private var readPos = 0
        private val output  = new java.io.ByteArrayOutputStream()

        def read(buf: Array[Byte])(using Frame): Int < Async =
            Sync.defer {
                if readPos >= input.length then -1
                else
                    val available = math.min(buf.length, input.length - readPos)
                    java.lang.System.arraycopy(input, readPos, buf, 0, available)
                    readPos += available
                    available
            }

        def write(data: Span[Byte])(using Frame): Unit < Async =
            Sync.defer(output.write(data.toArrayUnsafe))

        def written: Array[Byte]  = output.toByteArray
        def writtenString: String = output.toString(Utf8)
    end MockStream

    // ── Accept key ──────────────────────────────────────────────

    "computeAcceptKey" - {
        "known vector (RFC 6455 §4.2.2)" in {
            val key = WsCodec.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ==")
            assert(key == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        }

        "produces valid base64" in {
            val key = WsCodec.computeAcceptKey("testkey1234567890123=")
            assert(key.length == 28)
            assert(key.matches("[A-Za-z0-9+/]+=*"))
        }
    }

    // ── Frame header parsing ────────────────────────────────────

    "parseFrameHeader" - {
        "text frame, no mask, len 5" in {
            val fh = WsCodec.parseFrameHeader(0x81.toByte, 0x05.toByte) // FIN + text, len 5
            assert(fh.fin == true)
            assert(fh.opcode == 0x1)
            assert(fh.masked == false)
            assert(fh.payloadLen == 5)
        }

        "binary frame, masked, len 100" in {
            val fh = WsCodec.parseFrameHeader(0x82.toByte, (0x80 | 100).toByte)
            assert(fh.opcode == 0x2)
            assert(fh.masked == true)
            assert(fh.payloadLen == 100)
        }

        "16-bit length marker" in {
            val fh = WsCodec.parseFrameHeader(0x81.toByte, 126.toByte) // 126 = extended 16-bit
            assert(fh.payloadLen == 126)
        }

        "64-bit length marker" in {
            val fh = WsCodec.parseFrameHeader(0x81.toByte, 127.toByte) // 127 = extended 64-bit
            assert(fh.payloadLen == 127)
        }

        "FIN unset" in {
            val fh = WsCodec.parseFrameHeader(0x01.toByte, 0x05.toByte) // no FIN
            assert(fh.fin == false)
        }

        "control frames" in {
            assert(WsCodec.parseFrameHeader(0x89.toByte, 0x00.toByte).opcode == 0x9) // Ping
            assert(WsCodec.parseFrameHeader(0x8a.toByte, 0x00.toByte).opcode == 0xa) // Pong
            assert(WsCodec.parseFrameHeader(0x88.toByte, 0x00.toByte).opcode == 0x8) // Close
        }
    }

    // ── Masking ─────────────────────────────────────────────────

    "unmask" - {
        "known key" in {
            val mask     = Span.fromUnsafe(Array[Byte](0x37, 0xfa.toByte, 0x21, 0x3d))
            val masked   = Span.fromUnsafe(Array[Byte](0x7f, 0x9f.toByte, 0x4d, 0x51, 0x58))
            val result   = WsCodec.unmask(masked, mask)
            val expected = Array[Byte]('H', 'e', 'l', 'l', 'o')
            assert(result.toArrayUnsafe.sameElements(expected))
        }

        "self-inverse" in {
            val data     = Span.fromUnsafe("hello world".getBytes(Utf8))
            val key      = Span.fromUnsafe(Array[Byte](1, 2, 3, 4))
            val masked   = WsCodec.unmask(data, key)
            val unmasked = WsCodec.unmask(masked, key)
            assert(unmasked.toArrayUnsafe.sameElements(data.toArrayUnsafe))
        }

        "zero key unchanged" in {
            val data = Span.fromUnsafe("test".getBytes(Utf8))
            val key  = Span.fromUnsafe(Array[Byte](0, 0, 0, 0))
            assert(WsCodec.unmask(data, key).toArrayUnsafe.sameElements(data.toArrayUnsafe))
        }

        "empty payload" in {
            val result = WsCodec.unmask(Span.empty[Byte], Span.fromUnsafe(Array[Byte](1, 2, 3, 4)))
            assert(result.isEmpty)
        }
    }

    // ── Frame encoding ──────────────────────────────────────────

    "encodeFrameHeader" - {
        "small payload (< 126)" in {
            val h = WsCodec.encodeFrameHeader(0x1, 5, mask = false)
            assert(h.size == 2)
            assert((h(0) & 0xff) == 0x81) // FIN + text
            assert((h(1) & 0xff) == 5)
        }

        "medium payload (126-65535)" in {
            val h = WsCodec.encodeFrameHeader(0x1, 300, mask = false)
            assert(h.size == 4)
            assert((h(1) & 0x7f) == 126)
        }

        "large payload (> 65535)" in {
            val h = WsCodec.encodeFrameHeader(0x1, 70000, mask = false)
            assert(h.size == 10)
            assert((h(1) & 0x7f) == 127)
        }

        "mask adds 4 bytes" in {
            val h = WsCodec.encodeFrameHeader(0x1, 5, mask = true)
            assert(h.size == 2 + 4) // header + mask key
        }
    }

    // ── Close payload ───────────────────────────────────────────

    "encodeClosePayload" - {
        "code + reason" in {
            val payload = WsCodec.encodeClosePayload(1000, "normal")
            assert(payload.size == 2 + "normal".length)
            assert(((payload(0) & 0xff) << 8 | (payload(1) & 0xff)) == 1000)
            assert(new String(payload.toArrayUnsafe, 2, payload.size - 2, Utf8) == "normal")
        }

        "code only" in {
            val payload = WsCodec.encodeClosePayload(1000, "")
            assert(payload.size == 2)
        }

        "truncates long reason" in {
            val longReason = "x" * 200
            val payload    = WsCodec.encodeClosePayload(1000, longReason)
            assert(payload.size == 2 + 123) // 125 max total, minus 2 for code
        }
    }

    // ── Roundtrip ───────────────────────────────────────────────

    "writeFrame → readFrame roundtrip" - {
        "text unmasked" in run {
            val stream = new MockStream(Array.empty[Byte])
            WsCodec.writeFrame(stream, WebSocketFrame.Text("hello"), mask = false).andThen {
                val readStream = new MockStream(stream.written)
                Abort.run[Closed](WsCodec.readFrame(readStream)).map { result =>
                    result match
                        case Result.Success(WebSocketFrame.Text(text)) =>
                            assert(text == "hello")
                        case other =>
                            fail(s"Expected Text(hello), got $other")
                }
            }
        }

        "binary masked" in run {
            val data   = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            val stream = new MockStream(Array.empty[Byte])
            WsCodec.writeFrame(stream, WebSocketFrame.Binary(data), mask = true).andThen {
                val readStream = new MockStream(stream.written)
                Abort.run[Closed](WsCodec.readFrame(readStream)).map { result =>
                    result match
                        case Result.Success(WebSocketFrame.Binary(received)) =>
                            assert(received.toArrayUnsafe.sameElements(data.toArrayUnsafe))
                        case other =>
                            fail(s"Expected Binary, got $other")
                }
            }
        }

        "close frame aborts" in run {
            val stream = new MockStream(Array.empty[Byte])
            WsCodec.writeClose(stream, 1000, "bye", mask = false).andThen {
                val readStream = new MockStream(stream.written)
                Abort.run[Closed](WsCodec.readFrame(readStream)).map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    // ── Upgrade handshake ───────────────────────────────────────

    "acceptUpgrade" - {
        "writes 101 with correct accept key" in run {
            val stream  = new MockStream(Array.empty[Byte])
            val headers = HttpHeaders.empty.add("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            WsCodec.acceptUpgrade(stream, headers, WebSocketConfig()).andThen {
                val response = stream.writtenString
                assert(response.contains("101 Switching Protocols"))
                assert(response.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
                assert(response.contains("Upgrade: websocket"))
            }
        }

        "fails on missing key" in run {
            val stream = new MockStream(Array.empty[Byte])
            Abort.run[HttpException] {
                WsCodec.acceptUpgrade(stream, HttpHeaders.empty, WebSocketConfig())
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "requestUpgrade" - {
        "writes correct upgrade request" in run {
            // Create a mock that returns a 101 response when read
            val acceptKey   = WsCodec.computeAcceptKey("testkey")
            val response101 = s"HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: PLACEHOLDER\r\n\r\n"
            // We can't predict the exact key since requestUpgrade generates a random one,
            // so we test the write side only
            val stream = new MockStream(Array.empty[Byte])
            // This will fail because the mock has no response to read, but we can check what was written
            Abort.run[HttpException] {
                WsCodec.requestUpgrade(stream, "localhost", "/ws", HttpHeaders.empty, WebSocketConfig())
            }.map { _ =>
                val written = stream.writtenString
                assert(written.contains("GET /ws HTTP/1.1"))
                assert(written.contains("Upgrade: websocket"))
                assert(written.contains("Sec-WebSocket-Key:"))
                assert(written.contains("Sec-WebSocket-Version: 13"))
            }
        }

        "fails on non-101 response" in run {
            val response = "HTTP/1.1 400 Bad Request\r\n\r\n"
            val stream   = new MockStream(response.getBytes(Utf8))
            // Override write to capture then read the 400
            val combined = new MockStream(response.getBytes(Utf8)):
                override def write(data: Span[Byte])(using Frame): Unit < Async = Sync.defer(())
            Abort.run[HttpException] {
                WsCodec.requestUpgrade(combined, "localhost", "/ws", HttpHeaders.empty, WebSocketConfig())
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

end WsCodecTest
