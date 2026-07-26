package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.http1.*
import kyo.internal.util.*

class ChunkedBodyDecoderTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    private def spanOf(s: String): Span[Byte] =
        Span.fromUnsafe(s.getBytes(StandardCharsets.US_ASCII))

    private def spanOfBytes(bytes: Array[Byte]): Span[Byte] =
        Span.fromUnsafe(bytes)

    private def spanToString(s: Span[Byte]): String =
        new String(s.toArray, StandardCharsets.US_ASCII)

    "ChunkedBodyDecoder" - {

        "readBuffered" - {

            "single chunk complete in one read" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhello\r\n0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "multiple chunks" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "abcde")
                }
            }

            "chunk size split across reads" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // First read contains only "5" (partial hex line), second completes it
                val initial = spanOf("5")
                discard(inbound.offer(spanOf("\r\nhello\r\n0\r\n\r\n")))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "chunk data split across reads" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhel")
                discard(inbound.offer(spanOf("lo\r\n0\r\n\r\n")))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "chunk header and data in separate reads" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\n")
                discard(inbound.offer(spanOf("hello\r\n0\r\n\r\n")))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "zero-length chunk terminates" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(body.isEmpty)
                }
            }

            "chunk extensions ignored" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5;ext=val\r\nhello\r\n0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            // RFC 9112 section 7.1.1: a chunk extension is token / quoted-string, and neither admits a bare CR or LF.
            // Jetty CVE-2026-2332 and Netty CVE-2026-33870 both terminated the chunk-size line at the first CRLF
            // without rejecting a CR embedded in the extension, so a quoted-string-aware front end read the line end
            // at a different byte and the two disagreed on where the chunk data began, a smuggling desync. A decoder
            // must refuse a chunk-size line whose extension carries a bare CR rather than frame the chunk from it.
            "rejects a bare CR inside a chunk extension (CVE-2026-2332, CVE-2026-33870)" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // "5;a=" then a bare CR, then "b", then the real CRLF. The extension "a=<CR>b" is invalid.
                val initial = spanOfBytes("5;a=\rb\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    assert(
                        result.isFailure,
                        s"a chunk extension carrying a bare CR must be refused, but decoded: ${result.map(spanToString)}"
                    )
                }
            }

            "trailer headers after final chunk" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhello\r\n0\r\nTrailer: value\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "buffered mode accumulates all chunks" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("3\r\nabc\r\n")
                discard(inbound.offer(spanOf("4\r\ndefg\r\n")))
                discard(inbound.offer(spanOf("2\r\nhi\r\n0\r\n\r\n")))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "abcdefghi")
                }
            }

            "large chunk size (hex)" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // ffff = 65535 bytes
                val size = 0xffff
                val data = new Array[Byte](size)
                java.util.Arrays.fill(data, 'A'.toByte)
                val header  = "ffff\r\n"
                val trailer = "\r\n0\r\n\r\n"

                // Build initial bytes with header + partial data
                val headerBytes  = header.getBytes(StandardCharsets.US_ASCII)
                val trailerBytes = trailer.getBytes(StandardCharsets.US_ASCII)

                // Send in chunks: header + first half, then second half + trailer
                val half       = size / 2
                val firstChunk = new Array[Byte](headerBytes.length + half)
                java.lang.System.arraycopy(headerBytes, 0, firstChunk, 0, headerBytes.length)
                java.lang.System.arraycopy(data, 0, firstChunk, headerBytes.length, half)
                val initial = spanOfBytes(firstChunk)

                val secondChunk = new Array[Byte](size - half + trailerBytes.length)
                java.lang.System.arraycopy(data, half, secondChunk, 0, size - half)
                java.lang.System.arraycopy(trailerBytes, 0, secondChunk, size - half, trailerBytes.length)
                discard(inbound.offer(spanOfBytes(secondChunk)))

                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(body.size == size)
                    // Verify all bytes are 'A'
                    val arr = body.toArray
                    assert(arr.forall(_ == 'A'.toByte))
                }
            }

            "empty body (immediate terminal chunk)" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(body.isEmpty)
                }
            }

            "inbound closed mid-chunk" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhel") // Partial chunk data
                discard(inbound.close()) // Close the channel — next take will fail
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    assert(result.isFailure)
                }
            }

            "decoder reset between requests" in {
                val inbound1 = Channel.Unsafe.init[Span[Byte]](16)
                val initial1 = spanOf("5\r\nhello\r\n0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound1,
                    initial1,
                    maxBytes = Int.MaxValue
                )).map { result1 =>
                    assert(spanToString(result1.getOrThrow) == "hello")

                    // Second request with new state (readBuffered creates a fresh DecoderState)
                    val inbound2 = Channel.Unsafe.init[Span[Byte]](16)
                    val initial2 = spanOf("5\r\nworld\r\n0\r\n\r\n")
                    Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                        inbound2,
                        initial2,
                        maxBytes = Int.MaxValue
                    )).map { result2 =>
                        assert(spanToString(result2.getOrThrow) == "world")
                    }
                }
            }

            "mixed chunk sizes" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // 1 byte, 100 bytes, 10000 bytes
                val data1   = "X"
                val data100 = "A" * 100
                val data10k = "B" * 10000
                val chunked =
                    s"1\r\n$data1\r\n" +
                        s"64\r\n$data100\r\n" +   // 64 hex = 100 decimal
                        s"2710\r\n$data10k\r\n" + // 2710 hex = 10000 decimal
                        "0\r\n\r\n"
                // Split into multiple reads
                val bytes   = chunked.getBytes(StandardCharsets.US_ASCII)
                val mid     = bytes.length / 3
                val initial = spanOfBytes(java.util.Arrays.copyOfRange(bytes, 0, mid))
                discard(inbound.offer(spanOfBytes(java.util.Arrays.copyOfRange(bytes, mid, mid * 2))))
                discard(inbound.offer(spanOfBytes(java.util.Arrays.copyOfRange(bytes, mid * 2, bytes.length))))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = spanToString(result.getOrThrow)
                    assert(body == data1 + data100 + data10k)
                }
            }

            "CRLF boundary split" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // Split the CRLF after chunk data: \r at end of one read, \n at start of next
                val initial = spanOf("5\r\nhello\r")
                discard(inbound.offer(spanOf("\n0\r\n\r\n")))
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = Int.MaxValue
                )).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            // A chunked body has no declared length, so decoding into memory must be bounded (CWE-400). The buffered
            // decoder aborts HttpPayloadTooLargeException once the accumulated decoded bytes exceed maxBytes.
            "aborts HttpPayloadTooLargeException when the decoded body exceeds maxBytes (CWE-400)" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // Two 5-byte chunks decode to 10 bytes, over the 8-byte cap.
                val initial = spanOf("5\r\nhello\r\n5\r\nworld\r\n0\r\n\r\n")
                Abort.run[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException](ChunkedBodyDecoder.readBuffered(
                    inbound,
                    initial,
                    maxBytes = 8
                )).map {
                    case Result.Failure(_: HttpPayloadTooLargeException) => succeed
                    case other => fail(s"expected HttpPayloadTooLargeException for a 10-byte body under an 8-byte cap, got $other")
                }
            }
        }

        "readStreaming" - {

            "streaming mode delivers chunks to body channel" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val output  = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
                // Run decoder — it puts decoded chunks to output, then returns
                ChunkedBodyDecoder.readStreaming(inbound, initial, output, maxControlBytes = Int.MaxValue).map { _ =>
                    // Decoder completed — chunks should be in the output channel
                    val r1 = output.poll().getOrThrow // Result[Closed, Maybe[Span[Byte]]]
                    assert(r1.isDefined, "Expected chunk1")
                    assert(spanToString(r1.get) == "abc", s"chunk1: ${r1.map(s => spanToString(s))}")
                    val r2 = output.poll().getOrThrow
                    assert(r2.isDefined, "Expected chunk2")
                    assert(spanToString(r2.get) == "de", s"chunk2: ${r2.map(s => spanToString(s))}")
                    val r3 = output.poll().getOrThrow
                    assert(r3.isEmpty, "Expected no more chunks")
                }
            }

            // The streaming control plane must be bounded even though the delivered body is not: a chunk-size line
            // with an unterminated extension grows the decoder's internal buffer across reads without ever delivering
            // a chunk (CWE-400). maxControlBytes bounds that undelivered control plane.
            "rejects an oversized chunk control plane in streaming mode (CWE-400)" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val output  = Channel.Unsafe.init[Span[Byte]](16)
                // "5;" then a 200000-byte extension and no terminating LF: the size line never completes.
                val initial = spanOf("5;" + ("a" * 200000))
                Abort.run[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException](
                    ChunkedBodyDecoder.readStreaming(inbound, initial, output, maxControlBytes = 64)
                ).map {
                    case Result.Failure(_: HttpPayloadTooLargeException) => succeed
                    case other => fail(s"expected HttpPayloadTooLargeException for an oversized control plane, got $other")
                }
            }

            // The delivered body is NOT capped by maxControlBytes: a body whose total decoded size far exceeds the
            // control-plane bound, but whose per-chunk control bytes are tiny, must stream to completion. This guards
            // against capping cumulative delivered bytes (which would reject a legitimately large streaming body).
            "streams a body larger than maxControlBytes (delivered bytes are not capped)" in {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val output  = Channel.Unsafe.init[Span[Byte]](32)
                // Ten 10-byte chunks decode to 100 bytes, far over the 16-byte control cap, but each size line is tiny.
                val body    = (1 to 10).map(_ => "a\r\n0123456789\r\n").mkString + "0\r\n\r\n"
                val initial = spanOf(body)
                Abort.run[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException](
                    ChunkedBodyDecoder.readStreaming(inbound, initial, output, maxControlBytes = 16)
                ).map {
                    case Result.Success(_) =>
                        var total = 0
                        var done  = false
                        while !done do
                            output.poll().getOrThrow match
                                case Present(span) => total += span.size
                                case Absent        => done = true
                        end while
                        assert(total == 100, s"all 100 delivered bytes must stream despite the 16-byte control cap, got $total")
                    case other => fail(s"a large streaming body under the per-chunk control cap must stream, got $other")
                }
            }
        }

        // Resource bounds on the chunk CONTROL bytes, not just the decoded body. The maxBytes cap in the buffered
        // loop is checked against the decoded accumulator, but the chunk-size line (including its extension) and the
        // trailer section are accumulated in separate internal buffers that maxBytes never sees. An attacker who
        // streams control bytes that never complete a data chunk grows those buffers without bound while the
        // accumulator stays at zero, so onTooLarge never fires. These leaves assert the secure behavior (the cap must
        // fire) and therefore FAIL until the control-plane buffers are bounded.
        //
        // Origin: Node CVE-2024-22019 (unbounded chunk extension), Go CVE-2023-39326 (chunk non-data overhead),
        // Tomcat CVE-2012-3544 / CVE-2023-46589 (unbounded chunk extension / trailer).
        "resource bounds" - {

            "rejects a chunk-size line whose extension exceeds maxBytes" in {
                // "5;" then a 200000-byte extension and no terminating LF. No data chunk completes, so the decoded
                // accumulator stays empty and the accumulator-based cap cannot fire; the extension is buffered whole.
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val initial  = spanOf("5;" + ("a" * 200000))
                var tooLarge = false
                var resulted = false
                ChunkedBodyDecoder.readBufferedUnsafe(inbound, initial, maxBytes = 64)(
                    onResult = _ => resulted = true,
                    onTooLarge = _ => tooLarge = true,
                    onInvalid = _ => ()
                )
                assert(
                    tooLarge,
                    s"a ${200002}-byte chunk-size line under a 64-byte cap must be refused, but onTooLarge did not fire (resulted=$resulted)"
                )
            }

            "rejects a trailer section that exceeds maxBytes" in {
                // After the terminal "0\r\n", an endless trailer run with no closing empty line. The body is complete
                // and empty, so the accumulator stays at zero and the cap cannot fire; the trailer is buffered whole.
                val inbound  = Channel.Unsafe.init[Span[Byte]](16)
                val initial  = spanOf("0\r\nX-Pad: " + ("a" * 200000))
                var tooLarge = false
                var resulted = false
                ChunkedBodyDecoder.readBufferedUnsafe(inbound, initial, maxBytes = 64)(
                    onResult = _ => resulted = true,
                    onTooLarge = _ => tooLarge = true,
                    onInvalid = _ => ()
                )
                assert(
                    tooLarge,
                    s"a ${200007}-byte trailer section under a 64-byte cap must be refused, but onTooLarge did not fire (resulted=$resulted)"
                )
            }
        }
    }
end ChunkedBodyDecoderTest
