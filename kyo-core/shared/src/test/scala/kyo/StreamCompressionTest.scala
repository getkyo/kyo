package kyo

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Result.Failure
import kyo.StreamCompression.*

/** What the four compression methods owe on every platform.
  *
  * The fixtures a test would otherwise build with `java.util.zip` are here as bytes that zlib itself produced, so a
  * platform with no zlib is still held to reading what zlib writes rather than only to reading itself.
  * `StreamCompressionJdkTest`, on the JVM, adds what only that platform can ask: that the JDK reads back what these
  * drivers write.
  */
class StreamCompressionTest extends kyo.test.Test[Any]:

    private inline val shortText = "abcdefg1234567890"
    // The fixture below carries a zero byte in the middle, spelled rather than written into the source.
    private val otherShortText = "AXXX" + 0.toChar + "XXXA"

    private def longText = new String(Array.fill(1000)(shortText).flatten)

    private def toArray(chunk: Chunk[Byte]): Array[Byte] =
        val builder = Array.newBuilder[Byte]
        builder.addAll(chunk)
        builder.result()
    end toArray

    private def text(chunk: Chunk[Byte]): String = new String(toArray(chunk), StandardCharsets.UTF_8)

    private def bytes(values: Int*): Chunk[Byte] = Chunk.from(values.map(_.toByte).toArray)

    /** `shortText` as zlib compresses it: the ZLIB frame, raw DEFLATE, and gzip with no name and a zero timestamp. */
    private val zlibShort = bytes(120, -100, 75, 76, 74, 78, 73, 77, 75, 55, 52, 50, 54, 49, 53, 51, -73, -80, 52, 0, 0, 49, 95, 4, -54)
    private val rawShort  = bytes(75, 76, 74, 78, 73, 77, 75, 55, 52, 50, 54, 49, 53, 51, -73, -80, 52, 0, 0)
    private val gzipShort =
        bytes(31, -117, 8, 0, 0, 0, 0, 0, 0, -1, 75, 76, 74, 78, 73, 77, 75, 55, 52, 50, 54, 49, 53, 51, -73, -80, 52, 0, 0, 49, 15, -76,
            45, 17, 0, 0, 0)
    private val gzipOther =
        bytes(31, -117, 8, 0, 0, 0, 0, 0, 0, -1, 115, -116, -120, -120, 96, 0, 98, 71, 0, -52, 56, 107, 70, 9, 0, 0, 0)

    /** `longText` at zlib's highest level, which is where it reaches for a dynamic code table. Nothing here writes one. */
    private val zlibLong = bytes(
        120, -38, -19, -56, -47, 21, -128, 16, 0, 0, -64, -107, 16, -47, 56, -120, -10, -33, -64, 30, -67, -69,
        -49, -21, 99, -66, 107, 127, 49, 93, -71, -36, -75, 61, -95, 11, 33, -124, 16, 66, 8, 33, -124, 16, 66, 8, 33, -124, 16, 66, 8, 33,
        -124, 16, 66, 8, 33, -124, 16, 66, 8, 33, -124, 16, 66, 8, 33, -124, 16, 66, 8, 33, -124, 16, 66, -120, 63, -58, 1, 126, -4, -78,
        55
    )

    "reads what zlib wrote" - {

        "a ZLIB framed stream" in {
            Stream.init(zlibShort).inflate().run.map(out => assert(text(out) == shortText))
        }

        "a raw deflate stream" in {
            Stream.init(rawShort).inflate(noWrap = true).run.map(out => assert(text(out) == shortText))
        }

        "a gzip member" in {
            Stream.init(gzipShort).gunzip().run.map(out => assert(text(out) == shortText))
        }

        "two gzip members back to back" in {
            Stream.init(gzipShort).concat(Stream.init(gzipOther)).gunzip().run.map: out =>
                assert(text(out) == shortText + otherShortText)
        }

        "two ZLIB framed streams back to back" in {
            Stream.init(zlibShort).concat(Stream.init(zlibShort)).inflate().run.map: out =>
                assert(text(out) == shortText + shortText)
        }

        // The two above arrive as one chunk each, so the first stream ends exactly where a chunk does. These two put
        // both streams in one chunk, which is where the input the first stream did not reach has to become the second
        // stream's input rather than anything else.
        "two ZLIB framed streams in one chunk" in {
            Stream.init(zlibShort.concat(zlibShort)).inflate().run.map: out =>
                assert(text(out) == shortText + shortText)
        }

        "two gzip members in one chunk" in {
            Stream.init(gzipShort.concat(gzipOther)).gunzip().run.map: out =>
                assert(text(out) == shortText + otherShortText)
        }

        "a block with a dynamic code table" in {
            Stream.init(zlibLong).inflate().run.map(out => assert(text(out) == longText))
        }

        "a stream arriving one byte at a time" in {
            Stream.init(zlibLong, 1).inflate(bufferSize = 1).run.map(out => assert(text(out) == longText))
        }
    }

    "deflate and inflate" - {

        "a short text" in {
            Stream.init(Chunk.from(shortText.getBytes)).deflate().inflate().run.map(out => assert(text(out) == shortText))
        }

        "a long text" in {
            Stream.init(Chunk.from(longText.getBytes)).deflate().inflate().run.map(out => assert(text(out) == longText))
        }

        "a long text with no frame" in {
            Stream.init(Chunk.from(longText.getBytes)).deflate(noWrap = true).inflate(noWrap = true).run.map: out =>
                assert(text(out) == longText)
        }

        "input longer than one block" in {
            // Three blocks at the compressor's 32 KB block size, so a match cannot span every boundary.
            val input = Chunk.from(new String(Array.fill(6000)(shortText).flatten).getBytes)
            Stream.init(input).deflate().inflate().run.map(out => assert(out == input))
        }

        "chunks smaller than the buffer" in {
            Stream.init(Chunk.from(longText.getBytes), 8).deflate(bufferSize = 64).inflate(bufferSize = 64).run.map: out =>
                assert(text(out) == longText)
        }

        "a buffer smaller than the chunks" in {
            Stream.init(Chunk.from(longText.getBytes), 64).deflate(bufferSize = 8).inflate(bufferSize = 8).run.map: out =>
                assert(text(out) == longText)
        }

        "no input at all" in {
            Stream.empty[Byte].deflate().inflate().run.map(out => assert(out.isEmpty))
        }

        "every byte value" in {
            val input = Chunk.from(Array.tabulate(256)(_.toByte))
            Stream.init(input).deflate().inflate().run.map(out => assert(out == input))
        }

        "at each compression level" in {
            val input = Chunk.from(longText.getBytes)
            Kyo.foreach(Chunk(
                CompressionLevel.NoCompression,
                CompressionLevel.BestSpeed,
                CompressionLevel.Level4,
                CompressionLevel.Default,
                CompressionLevel.BestCompression
            )) { level =>
                Stream.init(input).deflate(compressionLevel = level).inflate().run
            }.map(results => assert(results.forall(_ == input)))
        }

        // Input arrives here in the stream's default 4 KB chunks, which is what makes this more than a repeat of the
        // level leaf: a deflater that has not read all of a chunk must not be handed the next one.
        "at each strategy" in {
            val input = Chunk.from(longText.getBytes)
            Kyo.foreach(Chunk(
                CompressionStrategy.Default,
                CompressionStrategy.Filtered,
                CompressionStrategy.HuffmanOnly
            )) { strategy =>
                Stream.init(input).deflate(strategy = strategy).inflate().run
            }.map(results => assert(results.forall(_ == input)))
        }

        "at each strategy, through gzip" in {
            val input = Chunk.from(longText.getBytes)
            Kyo.foreach(Chunk(
                CompressionStrategy.Default,
                CompressionStrategy.Filtered,
                CompressionStrategy.HuffmanOnly
            )) { strategy =>
                Stream.init(input).gzip(strategy = strategy).gunzip().run
            }.map(results => assert(results.forall(_ == input)))
        }

        "at each flush mode" in {
            val input = Chunk.from(longText.getBytes)
            Kyo.foreach(Chunk(FlushMode.NoFlush, FlushMode.SyncFlush, FlushMode.FullFlush)) { mode =>
                Stream.init(input, 64).deflate(flushMode = mode).inflate().run
            }.map(results => assert(results.forall(_ == input)))
        }

        "text gets smaller" in {
            val input = Chunk.from(new String(Array.fill(40)(shortText).flatten).getBytes)
            Stream.init(input).deflate(compressionLevel = CompressionLevel.BestCompression).run.map: out =>
                assert(out.length < input.length)
        }

        "a corrupted header fails" in {
            Abort.run(Stream.init(bytes(1, 2, 3, 4, 5)).inflate().run).map:
                case Failure(_: StreamCompressionException) => succeed
                case other                                  => fail(s"Expected a StreamCompressionException but got $other")
        }

        "a truncated stream does not answer with the whole text" in {
            Abort.run(Stream.init(zlibShort.take(12)).inflate().run).map:
                case Result.Success(out) => assert(text(out) != shortText)
                case _                   => succeed
        }
    }

    "gzip and gunzip" - {

        "a short text" in {
            Stream.init(Chunk.from(shortText.getBytes)).gzip().gunzip().run.map(out => assert(text(out) == shortText))
        }

        "a long text" in {
            Stream.init(Chunk.from(longText.getBytes)).gzip().gunzip().run.map(out => assert(text(out) == longText))
        }

        "no input at all" in {
            for
                gzipped   <- Stream.empty[Byte].gzip().run
                gunzipped <- Stream.init(gzipped).gunzip().run
            yield assert(gzipped.take(3) == bytes(31, -117, 8) && gunzipped.isEmpty)
        }

        "a buffer of one byte" in {
            Stream.init(Chunk.from(longText.getBytes), 1).gzip(bufferSize = 1).gunzip(bufferSize = 1).run.map: out =>
                assert(text(out) == longText)
        }

        "text gets smaller" in {
            val input = Chunk.from(new String(Array.fill(40)(shortText).flatten).getBytes)
            Stream.init(input).gzip(bufferSize = 2048).run.map(out => assert(out.length < input.length))
        }

        "a header with extra fields, a name, a comment and a header checksum" in {
            val extraText   = Chunk.from("a header field long enough to span a chunk".getBytes).append(0.toByte)
            val header      = Array(31, 139, 8, 2 | 4 | 8 | 16, 0, 0, 0, 0, 0, 0xff).map(_.toByte)
            val headerExtra = Array(extraText.length.toByte, 0.toByte) ++ toArray(extraText)
            val comment     = "kyo rocks".getBytes ++ Array(0.toByte)
            val fileName    = "kyo-readme.md".getBytes ++ Array(0.toByte)
            val crc16 =
                val crc32 = new kyo.internal.ZipCodec.Crc32
                crc32.update(header)
                crc32.update(headerExtra)
                crc32.update(comment)
                crc32.update(fileName)
                val value = (crc32.getValue & 0xffffL).toInt
                Array((value & 0xff).toByte, ((value >> 8) & 0xff).toByte)
            end crc16
            val framed = Chunk.from(header ++ headerExtra ++ comment ++ fileName ++ crc16).concat(gzipShort.drop(10))
            Stream.init(framed).gunzip().run.map(out => assert(text(out) == shortText))
        }

        "a wrong header checksum fails" in {
            val header = Array(31, 139, 8, 2, 0, 0, 0, 0, 0, 0xff).map(_.toByte)
            val framed = Chunk.from(header ++ Array(0.toByte, 0.toByte)).concat(gzipShort.drop(10))
            Abort.run(Stream.init(framed).gunzip().run).map:
                case Failure(_: StreamCompressionException) => succeed
                case other                                  => fail(s"Expected a StreamCompressionException but got $other")
        }

        "a wrong content checksum fails" in {
            val corrupted = gzipShort.take(gzipShort.length - 8).concat(bytes(0, 0, 0, 0, 17, 0, 0, 0))
            Abort.run(Stream.init(corrupted).gunzip().run).map:
                case Failure(_: StreamCompressionException) => succeed
                case other                                  => fail(s"Expected a StreamCompressionException but got $other")
        }

        "a wrong uncompressed size fails" in {
            val corrupted = gzipShort.take(gzipShort.length - 4).concat(bytes(99, 0, 0, 0))
            Abort.run(Stream.init(corrupted).gunzip().run).map:
                case Failure(_: StreamCompressionException) => succeed
                case other                                  => fail(s"Expected a StreamCompressionException but got $other")
        }

        "a header that is not gzip fails" in {
            Abort.run(Stream.init(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)).gunzip().run).map:
                case Failure(_: StreamCompressionException) => succeed
                case other                                  => fail(s"Expected a StreamCompressionException but got $other")
        }
    }
end StreamCompressionTest
