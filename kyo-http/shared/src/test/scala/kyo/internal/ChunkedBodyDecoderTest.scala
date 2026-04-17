package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.http1.*
import kyo.internal.util.*

class ChunkedBodyDecoderTest extends kyo.Test:

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

            "single chunk complete in one read" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhello\r\n0\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "multiple chunks" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "abcde")
                }
            }

            "chunk size split across reads" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // First read contains only "5" (partial hex line), second completes it
                val initial = spanOf("5")
                discard(inbound.offer(spanOf("\r\nhello\r\n0\r\n\r\n")))
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "chunk data split across reads" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhel")
                discard(inbound.offer(spanOf("lo\r\n0\r\n\r\n")))
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "chunk header and data in separate reads" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\n")
                discard(inbound.offer(spanOf("hello\r\n0\r\n\r\n")))
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "zero-length chunk terminates" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("0\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(body.isEmpty)
                }
            }

            "chunk extensions ignored" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5;ext=val\r\nhello\r\n0\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "trailer headers after final chunk" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhello\r\n0\r\nTrailer: value\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }

            "buffered mode accumulates all chunks" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("3\r\nabc\r\n")
                discard(inbound.offer(spanOf("4\r\ndefg\r\n")))
                discard(inbound.offer(spanOf("2\r\nhi\r\n0\r\n\r\n")))
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "abcdefghi")
                }
            }

            "large chunk size (hex)" in run {
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

                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(body.size == size)
                    // Verify all bytes are 'A'
                    val arr = body.toArray
                    assert(arr.forall(_ == 'A'.toByte))
                }
            }

            "empty body (immediate terminal chunk)" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("0\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(body.isEmpty)
                }
            }

            "inbound closed mid-chunk" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("5\r\nhel") // Partial chunk data
                discard(inbound.close()) // Close the channel — next take will fail
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    assert(result.isFailure)
                }
            }

            "decoder reset between requests" in run {
                val inbound1 = Channel.Unsafe.init[Span[Byte]](16)
                val initial1 = spanOf("5\r\nhello\r\n0\r\n\r\n")
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound1, initial1)).map { result1 =>
                    assert(spanToString(result1.getOrThrow) == "hello")

                    // Second request with new state (readBuffered creates a fresh DecoderState)
                    val inbound2 = Channel.Unsafe.init[Span[Byte]](16)
                    val initial2 = spanOf("5\r\nworld\r\n0\r\n\r\n")
                    Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound2, initial2)).map { result2 =>
                        assert(spanToString(result2.getOrThrow) == "world")
                    }
                }
            }

            "mixed chunk sizes" in run {
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
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = spanToString(result.getOrThrow)
                    assert(body == data1 + data100 + data10k)
                }
            }

            "CRLF boundary split" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                // Split the CRLF after chunk data: \r at end of one read, \n at start of next
                val initial = spanOf("5\r\nhello\r")
                discard(inbound.offer(spanOf("\n0\r\n\r\n")))
                Abort.run[Closed](ChunkedBodyDecoder.readBuffered(inbound, initial)).map { result =>
                    val body = result.getOrThrow
                    assert(spanToString(body) == "hello")
                }
            }
        }

        "readStreaming" - {

            "streaming mode delivers chunks to body channel" in run {
                val inbound = Channel.Unsafe.init[Span[Byte]](16)
                val output  = Channel.Unsafe.init[Span[Byte]](16)
                val initial = spanOf("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
                // Run decoder — it puts decoded chunks to output, then returns
                ChunkedBodyDecoder.readStreaming(inbound, initial, output).map { _ =>
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
        }
    }
end ChunkedBodyDecoderTest
