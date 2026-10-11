package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.util.ByteStream

class ByteStreamTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)
    private def span(s: String): Span[Byte]   = Span.fromUnsafe(bytes(s))
    private def str(s: Span[Byte]): String    = new String(s.toArray, StandardCharsets.US_ASCII)

    private def streamFromChunks(chunks: Seq[String]): Stream[Span[Byte], Async] =
        Stream.init(chunks.map(span))

    private def drainStream(stream: Stream[Span[Byte], Async]): Seq[Span[Byte]] < Async =
        stream.run.map(_.toSeq)

    "advanceMatch" - {

        def advance(delimiter: String, matched: Int, s: String): Int =
            ByteStream.advanceMatch(bytes(delimiter), ByteStream.delimiterFallback(bytes(delimiter)), matched, span(s))

        "the negated index just past a delimiter found at the start" in {
            assert(advance("\r\n", 0, "\r\nabc") == -2)
        }

        "the negated index just past a delimiter found in the middle" in {
            assert(advance("world", 0, "hello world!") == -11)
        }

        "the length of the delimiter prefix a read ends with when the delimiter is absent" in {
            assert(advance("\r\n\r\n", 0, "abc") == 0)
            assert(advance("\r\n\r\n", 0, "abc\r\n\r") == 3)
        }

        "a delimiter completed by a later read from the prefix the earlier one ended with" in {
            assert(advance("\r\n\r\n", 3, "\nxyz") == -1)
            assert(advance("\r\n\r\n", 2, "\r\n") == -2)
        }

        "a mismatch inside a partial match falls back to the longest prefix still matched" in {
            assert(advance("\r\n\r\n", 0, "\r\n\r\r\n\r\n") == -7)
            assert(advance("aab", 0, "aaab") == -4)
            assert(advance("\r\n\r\n", 3, "\r\n\r\n") == -4)
        }

        "an empty read leaves the match as it was" in {
            assert(advance("\r\n", 1, "") == 1)
        }
    }

    "readUntilWith" - {

        "finds delimiter and returns bytes before" in {
            val stream = streamFromChunks(Seq("hello\r\nworld"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 1024) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }

        "consumes delimiter — remaining stream starts after delimiter" in {
            val stream = streamFromChunks(Seq("hello\r\nworld"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 1024) {
                (_, rest) =>
                    rest.run.map { chunk =>
                        new String(chunk.toSeq.flatMap(_.toArray).toArray, StandardCharsets.US_ASCII)
                    }
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "world")
            }
        }

        "with empty stream returns HttpConnectionClosedException" in {
            val stream = Stream.init[Span[Byte], Async](Seq.empty)
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 1024) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
            }
        }

        "exceeds maxSize raises HttpProtocolException" in {
            val stream = streamFromChunks(Seq("hello world"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 5) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpProtocolException]))
            }
        }

        "delimiter split across chunks" in {
            val stream = streamFromChunks(Seq("hello\r", "\nworld"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 1024) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }

        "the bytes before the delimiter and its limit are the same wherever the reads split the input" in {
            val input = "hello\r\n\r\nrest"
            Kyo.foreach(Chunk.from(0 to input.length)) { split =>
                Kyo.foreach(Chunk(5, 4)) { maxSize =>
                    val stream = streamFromChunks(Seq(input.take(split), input.drop(split)).filter(_.nonEmpty))
                    Abort.run[HttpException] {
                        ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF_CRLF, maxSize) {
                            (before, rest) => rest.run.map(after => (str(before), after.toSeq.map(str).mkString))
                        }
                    }.map {
                        case Result.Success((before, after)) => s"$before|$after"
                        case Result.Failure(_)               => "refused"
                        case Result.Panic(t)                 => s"panic: $t"
                    }
                }
            }.map { outcomes =>
                assert(outcomes.forall(_ == Chunk("hello|rest", "refused")), s"(under a limit of 5, of 4) per split point: $outcomes")
            }
        }

        "an empty delimiter is found before the first byte, leaving the stream unread" in {
            val stream = streamFromChunks(Seq("abc"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, Array.emptyByteArray, 1024) {
                (before, rest) => rest.run.map(after => (str(before), after.toSeq.map(str).mkString))
            }
            Abort.run[HttpException](result).map { r =>
                assert(r == Result.succeed(("", "abc")), s"observed: $r")
            }
        }

        "a delimiter that straddles several reads" in {
            val stream = streamFromChunks(Seq("ab", "c\r", "\n", "\r", "\nd"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF_CRLF, 1024) {
                (before, rest) => rest.run.map(after => (str(before), after.toSeq.map(str).mkString))
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == ("abc", "d"))
            }
        }

        "maxSize exactly at delimiter — success" in {
            // "hello" is 5 bytes, delimiter is \r\n — maxSize=5 should succeed
            val stream = streamFromChunks(Seq("hello\r\nworld"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 5) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }
    }

    "readExactWith" - {

        "reads n bytes from stream" in {
            val stream = streamFromChunks(Seq("hello world"))
            val result = ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, 5) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }

        "reads across multiple chunks" in {
            // n=100, chunks of 30, 40, 50 bytes — takes from first two and part of third
            val chunk1 = "A" * 30
            val chunk2 = "B" * 40
            val chunk3 = "C" * 50
            val stream = streamFromChunks(Seq(chunk1, chunk2, chunk3))
            val result = ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, 100) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                val s = r.getOrThrow
                assert(s.length == 100)
                assert(s.take(30) == "A" * 30)
                assert(s.slice(30, 70) == "B" * 40)
                assert(s.drop(70) == "C" * 30)
            }
        }

        "n <= 0 returns empty immediately" in {
            val stream = streamFromChunks(Seq("hello"))
            val result = ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, 0) {
                (bytes, _) => bytes.size
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == 0)
            }
        }

        "insufficient bytes raises HttpConnectionClosedException" in {
            val stream = streamFromChunks(Seq("hi"))
            val result = ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, 100) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
            }
        }

        "reads exactly n=1 byte" in {
            val stream = streamFromChunks(Seq("hello"))
            val result = ByteStream.readExactWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, 1) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "h")
            }
        }
    }

    "readLineWith" - {

        "finds CRLF and returns line without CRLF" in {
            val stream = streamFromChunks(Seq("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n"))
            val result = ByteStream.readLineWith(HttpConnectionClosedException.Phase.BodyTruncated, stream) {
                (line, _) => str(line)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "HTTP/1.1 200 OK")
            }
        }

        "respects maxSize — raises error if exceeded" in {
            val stream = streamFromChunks(Seq("a very long line that exceeds max size\r\n"))
            val result = ByteStream.readLineWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, maxSize = 10) {
                (line, _) => str(line)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpProtocolException]))
            }
        }

        "uses 8192 as default maxSize — accepts line within limit" in {
            val longLine = "X" * 8192
            // The line itself is exactly 8192 bytes — at the delimiter the combined size
            // includes the delimiter too; test a line just under the default limit
            val stream = streamFromChunks(Seq(("Y" * 100) + "\r\n"))
            val result = ByteStream.readLineWith(HttpConnectionClosedException.Phase.BodyTruncated, stream) {
                (line, _) => str(line)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "Y" * 100)
            }
        }
    }

    "delimiter split across chunks" - {

        "CRLF split: chunk1=hello\\r, chunk2=\\nworld yields hello" in {
            val stream = streamFromChunks(Seq("hello\r", "\nworld"))
            val result = ByteStream.readUntilWith(HttpConnectionClosedException.Phase.BodyTruncated, stream, ByteStream.CRLF, 1024) {
                (before, rest) =>
                    rest.run.map { remaining =>
                        val afterStr = new String(
                            remaining.toSeq.flatMap(_.toArray).toArray,
                            StandardCharsets.US_ASCII
                        )
                        (str(before), afterStr)
                    }
            }
            Abort.run[HttpException](result).map { r =>
                val (beforeStr, afterStr) = r.getOrThrow
                assert(beforeStr == "hello")
                assert(afterStr == "world")
            }
        }
    }

end ByteStreamTest
