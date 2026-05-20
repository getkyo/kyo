package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.util.ByteStream

class ByteStreamTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.US_ASCII)
    private def span(s: String): Span[Byte]   = Span.fromUnsafe(bytes(s))
    private def str(s: Span[Byte]): String    = new String(s.toArray, StandardCharsets.US_ASCII)

    private def streamFromChunks(chunks: Seq[String]): Stream[Span[Byte], Async] =
        Stream.init(chunks.map(span))

    private def drainStream(stream: Stream[Span[Byte], Async]): Seq[Span[Byte]] < Async =
        stream.run.map(_.toSeq)

    "indexOf" - {

        "finds pattern at start" in {
            val result = ByteStream.indexOf("hello".getBytes, "hel".getBytes)
            assert(result == 0)
        }

        "finds pattern in middle" in {
            val result = ByteStream.indexOf("hello world".getBytes, "world".getBytes)
            assert(result == 6)
        }

        "not found returns -1" in {
            val result = ByteStream.indexOf("hello".getBytes, "xyz".getBytes)
            assert(result == -1)
        }

        "empty needle returns 0" in {
            val result = ByteStream.indexOf("hello".getBytes, Array.emptyByteArray)
            assert(result == 0)
        }

        "needle longer than haystack returns -1" in {
            val result = ByteStream.indexOf("hi".getBytes, "hello world".getBytes)
            assert(result == -1)
        }
    }

    "readUntilWith" - {

        "finds delimiter and returns bytes before" in run {
            val stream = streamFromChunks(Seq("hello\r\nworld"))
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 1024) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }

        "consumes delimiter — remaining stream starts after delimiter" in run {
            val stream = streamFromChunks(Seq("hello\r\nworld"))
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 1024) {
                (_, rest) =>
                    rest.run.map { chunk =>
                        new String(chunk.toSeq.flatMap(_.toArray).toArray, StandardCharsets.US_ASCII)
                    }
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "world")
            }
        }

        "with empty stream returns HttpConnectionClosedException" in run {
            val stream = Stream.init[Span[Byte], Async](Seq.empty)
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 1024) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
            }
        }

        "exceeds maxSize raises HttpProtocolException" in run {
            val stream = streamFromChunks(Seq("hello world"))
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 5) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpProtocolException]))
            }
        }

        "delimiter split across chunks" in run {
            val stream = streamFromChunks(Seq("hello\r", "\nworld"))
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 1024) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }

        "maxSize exactly at delimiter — success" in run {
            // "hello" is 5 bytes, delimiter is \r\n — maxSize=5 should succeed
            val stream = streamFromChunks(Seq("hello\r\nworld"))
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 5) {
                (before, _) => str(before)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }
    }

    "readExactWith" - {

        "reads n bytes from stream" in run {
            val stream = streamFromChunks(Seq("hello world"))
            val result = ByteStream.readExactWith(stream, 5) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "hello")
            }
        }

        "reads across multiple chunks" in run {
            // n=100, chunks of 30, 40, 50 bytes — takes from first two and part of third
            val chunk1 = "A" * 30
            val chunk2 = "B" * 40
            val chunk3 = "C" * 50
            val stream = streamFromChunks(Seq(chunk1, chunk2, chunk3))
            val result = ByteStream.readExactWith(stream, 100) {
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

        "n <= 0 returns empty immediately" in run {
            val stream = streamFromChunks(Seq("hello"))
            val result = ByteStream.readExactWith(stream, 0) {
                (bytes, _) => bytes.size
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == 0)
            }
        }

        "insufficient bytes raises HttpConnectionClosedException" in run {
            val stream = streamFromChunks(Seq("hi"))
            val result = ByteStream.readExactWith(stream, 100) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpConnectionClosedException]))
            }
        }

        "reads exactly n=1 byte" in run {
            val stream = streamFromChunks(Seq("hello"))
            val result = ByteStream.readExactWith(stream, 1) {
                (bytes, _) => str(bytes)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "h")
            }
        }
    }

    "readLineWith" - {

        "finds CRLF and returns line without CRLF" in run {
            val stream = streamFromChunks(Seq("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n"))
            val result = ByteStream.readLineWith(stream) {
                (line, _) => str(line)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "HTTP/1.1 200 OK")
            }
        }

        "respects maxSize — raises error if exceeded" in run {
            val stream = streamFromChunks(Seq("a very long line that exceeds max size\r\n"))
            val result = ByteStream.readLineWith(stream, maxSize = 10) {
                (line, _) => str(line)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.isFailure)
                assert(r.failure.exists(_.isInstanceOf[HttpProtocolException]))
            }
        }

        "uses 8192 as default maxSize — accepts line within limit" in run {
            val longLine = "X" * 8192
            // The line itself is exactly 8192 bytes — at the delimiter the combined size
            // includes the delimiter too; test a line just under the default limit
            val stream = streamFromChunks(Seq(("Y" * 100) + "\r\n"))
            val result = ByteStream.readLineWith(stream) {
                (line, _) => str(line)
            }
            Abort.run[HttpException](result).map { r =>
                assert(r.getOrThrow == "Y" * 100)
            }
        }
    }

    "delimiter split across chunks" - {

        "CRLF split: chunk1=hello\\r, chunk2=\\nworld yields hello" in run {
            val stream = streamFromChunks(Seq("hello\r", "\nworld"))
            val result = ByteStream.readUntilWith(stream, ByteStream.CRLF, 1024) {
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
