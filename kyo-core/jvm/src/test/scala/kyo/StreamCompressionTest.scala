package kyo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.InflaterOutputStream
import kyo.*
import kyo.Result.Failure
import kyo.StreamCompression.*
import scala.annotation.tailrec

class StreamCompressionTest extends Test:
    private inline val shortText      = "abcdefg1234567890"
    private inline val otherShortText = "AXXX\u0000XXXA"

    private inline def toUnboxByteArray(byteChunk: Chunk[Byte]): Array[Byte] =
        val builder = Array.newBuilder[Byte]
        builder.addAll(byteChunk)
        builder.result()
    end toUnboxByteArray

    private inline def longText = new String(Array.fill(1000)(shortText).flatten)

    private inline def inflateRandomExampleThatFailed =
        Chunk[Byte](100, 96, 2, 14, 108, -122, 110, -37, 35, -11, -10, 14, 47, 30, 43, 111, -80, 44, -34, 35, 35, 37, -103)

    private def jdkDeflate(bytes: Chunk[Byte], deflater: Deflater): Chunk[Byte] =
        val bigBuffer = new Array[Byte](1024 * 1024)
        val dif       = new DeflaterInputStream(new ByteArrayInputStream(toUnboxByteArray(bytes)), deflater)
        val read      = dif.read(bigBuffer, 0, bigBuffer.length)
        Chunk.from(Array.copyOf(bigBuffer, read))
    end jdkDeflate

    private def jdkInflate(bytes: Chunk[Byte], inflater: Inflater): Chunk[Byte] =
        val bigBuffer = new Array[Byte](1024 * 1024)
        val iif       = new InflaterInputStream(new ByteArrayInputStream(toUnboxByteArray(bytes)), inflater)

        @tailrec def loop(acc: Chunk[Byte]): Chunk[Byte] =
            val read = iif.read(bigBuffer, 0, bigBuffer.length)
            if read <= 0 then acc
            else loop(acc.concat(Chunk.from(bigBuffer.take(read))))
        end loop

        loop(Chunk.empty)
    end jdkInflate

    private def jdkGzip(bytes: Chunk[Byte], syncFlush: Boolean): Chunk[Byte] =
        val baos = new ByteArrayOutputStream(1024)
        val gzos = new GZIPOutputStream(baos, 1024, syncFlush)
        gzos.write(toUnboxByteArray(bytes))
        gzos.finish()
        gzos.flush()
        Chunk.from(baos.toByteArray())
    end jdkGzip

    private def jdkGunzip(gzipped: Chunk[Byte]): Chunk[Byte] =
        val bigBuffer = new Array[Byte](1024 * 1024)
        val bais      = new ByteArrayInputStream(toUnboxByteArray(gzipped))
        val gzis      = new GZIPInputStream(bais)

        @tailrec def loop(acc: Chunk[Byte]): Chunk[Byte] =
            val read = gzis.read(bigBuffer, 0, bigBuffer.length)
            if read <= 0 then acc
            else loop(acc.concat(Chunk.from(bigBuffer.take(read))))
        end loop

        loop(Chunk.empty)
    end jdkGunzip

    private inline def getBytes(s: String): Array[Byte] = s.getBytes

    "deflate/inflate" - {
        "inflate please wrap" in run {
            for
                inStream <- Sync(Stream.init(
                    Chunk.from(Array[Byte](120, -100, -53, -52, 75, -53, 73, 44, 73, 85, 40, -56, 73, 77, 44, 78, 85, 40, 47, 74, 44, 0, 0,
                        73, -20, 7, 88))
                ))
                outStream <- Sync(inStream.inflate())
                bytes     <- outStream.run.map(toUnboxByteArray)
                string    <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == "inflate please wrap")
        }

        "inflate please nowrap" in run {
            for
                inStream <- Sync(Stream.init(
                    Chunk.from(Array[Byte](-53, -52, 75, -53, 73, 44, 73, 85, 40, -56, 73, 77, 44, 78, 85, -56, -53, 47, 47, 74, 44, 0, 0))
                ))
                outStream <- Sync(inStream.inflate(noWrap = true))
                bytes     <- outStream.run.map(toUnboxByteArray)
                string    <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == "inflate please nowrap")
        }

        "short stream" in run {
            for
                inStream <- Sync(Stream.init(Chunk.from(getBytes(shortText))))
                inChunk  <- inStream.run
                deflatedStream <- Sync {
                    val deflatedChunk = jdkDeflate(inChunk, new Deflater(CompressionLevel.Default.value, false))
                    Stream.init(deflatedChunk)
                }
                inflatedStream <- Sync(deflatedStream.inflate(noWrap = false))
                bytes          <- inflatedStream.run.map(toUnboxByteArray)
                string         <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == shortText)
        }

        "stream of two deflated inputs" in run {
            for
                inStream1 <- Sync(Stream.init(Chunk.from(getBytes(shortText))))
                inChunk1  <- inStream1.run
                deflatedStream1 <- Sync {
                    val deflatedChunk = jdkDeflate(inChunk1, new Deflater(CompressionLevel.Default.value, false))
                    Stream.init(deflatedChunk)
                }
                inStream2 <- Sync(Stream.init(Chunk.from(getBytes(otherShortText))))
                inChunk2  <- inStream2.run
                deflatedStream2 <- Sync {
                    val deflatedChunk = jdkDeflate(inChunk2, new Deflater(CompressionLevel.Default.value, false))
                    Stream.init(deflatedChunk)
                }
                inflatedStream <- Sync(deflatedStream1.concat(deflatedStream2).inflate(noWrap = false))
                bytes          <- inflatedStream.run.map(toUnboxByteArray)
                string         <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == shortText ++ otherShortText)
        }

        "inflate input (deflated larger than inflated)" in runJVM {
            for
                inStream <- Sync(Stream.init(
                    Chunk.from(getBytes("꒔諒ᇂ즆ᰃ遇ኼ㎐만咘똠ᯈ䕍쏮쿻ࣇ㦲䷱瘫椪⫐褽睌쨘꛹騏蕾☦余쒧꺠ܝ猸b뷈埣ꂓ琌ཬ隖㣰忢鐮橀쁚誅렌폓㖅ꋹ켗餪庺Đ懣㫍㫌굦뢲䅦苮Ѣқ闭䮚ū﫣༶漵>껆拦휬콯耙腒䔖돆圹Ⲷ曩ꀌ㒈")),
                    1
                ))
                deflatedStream <- Sync(inStream.deflate(noWrap = false))
                byteChunk      <- deflatedStream.run
                expected <- Sync {
                    val bos = new ByteArrayOutputStream()
                    val ios = new InflaterOutputStream(bos, new Inflater(false))
                    ios.write(toUnboxByteArray(byteChunk), 0, byteChunk.length)
                    ios.flush()
                    ios.finish()
                    val inflatedBytes = Chunk.from(bos.toByteArray)
                    bos.close()
                    ios.close()
                    inflatedBytes
                }
                inflatedStream <- Sync(deflatedStream.inflate(noWrap = false))
                result         <- inflatedStream.run
            yield assert(expected == result)
        }

        "long input, buffer smaller than chunks" in run {
            for
                inStream <- Sync(Stream.init(
                    Chunk.from(getBytes(longText)),
                    64
                ))
                inChunk <- inStream.run
                deflatedStream <- Sync {
                    val deflatedChunk = jdkDeflate(inChunk, new Deflater(CompressionLevel.Default.value, false))
                    Stream.init(deflatedChunk)
                }
                inflatedStream <- Sync(deflatedStream.inflate(bufferSize = 8, noWrap = false))
                bytes          <- inflatedStream.run.map(toUnboxByteArray)
                string         <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == longText)
        }

        "long input, chunks smaller then buffer" in run {
            for
                inStream <- Sync(Stream.init(
                    Chunk.from(getBytes(longText)),
                    8
                ))
                inChunk <- inStream.run
                deflatedStream <- Sync {
                    val deflatedChunk = jdkDeflate(inChunk, new Deflater(CompressionLevel.Default.value, false))
                    Stream.init(deflatedChunk)
                }
                inflatedStream <- Sync(deflatedStream.inflate(bufferSize = 64, noWrap = false))
                bytes          <- inflatedStream.run.map(toUnboxByteArray)
                string         <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == longText)
        }

        "long input, nowrap = true" in run {
            for
                inStream <- Sync(Stream.init(Chunk.from(getBytes(longText))))
                inChunk  <- inStream.run
                deflatedStream <- Sync {
                    val deflatedChunk = jdkDeflate(inChunk, new Deflater(CompressionLevel.BestCompression.value, true))
                    Stream.init(deflatedChunk)
                }
                inflatedStream <- Sync(deflatedStream.inflate(noWrap = true))
                bytes          <- inflatedStream.run.map(toUnboxByteArray)
                string         <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == longText)
        }

        "fail early if header is corrupted" in run {
            Abort.run(
                for
                    inStream       <- Sync(Stream.init(Chunk[Byte](1, 2, 3, 4, 5)))
                    inflatedStream <- Sync(inStream.inflate())
                    _              <- inflatedStream.run
                yield ()
            ).map: result =>
                result match
                    case Failure(_: StreamCompressionException) => assert(true)
                    case _                                      => assert(false)
        }

        "inflate nowrap: remaining = 0 but not all was pulled" in run {
            for
                deflatedStream <- Sync {
                    val deflatedChunk =
                        jdkDeflate(inflateRandomExampleThatFailed, new Deflater(CompressionLevel.BestCompression.value, true))
                    Stream.init(deflatedChunk)
                }
                inflatedStream <- Sync(deflatedStream.inflate(noWrap = true))
                resultChunk    <- inflatedStream.run
            yield assert(resultChunk == inflateRandomExampleThatFailed)
        }

        "deflate and then inflate" in run {
            val longTextChunk = Chunk.from(getBytes(longText))
            for
                inStream       <- Sync(Stream.init(longTextChunk))
                deflatedStream <- Sync(inStream.deflate(noWrap = true))
                inflatedStream <- Sync(deflatedStream.inflate(noWrap = true))
                byteChunk      <- inflatedStream.run
            yield assert(byteChunk == longTextChunk)
            end for
        }

        "deflate does compress" in run {
            val uncompressed = Chunk.from(getBytes(
                """"
                |According to the caption on the bronze marker placed by the Multnomah Chapter of the Daughters of the American Revolution on May 12, 1939,
                | “College Hall (is) the oldest building in continuous use for Educational purposes west of the Rocky Mountains. Here were educated men and women who have won recognition throughout the world in all the learned professions.”""".stripMargin
            ))
            for
                inStream       <- Sync(Stream.init(uncompressed))
                deflatedStream <- Sync(inStream.deflate(compressionLevel = CompressionLevel.BestCompression))
                deflatedChunk  <- deflatedStream.run
            yield assert(deflatedChunk.length < uncompressed.length)
            end for
        }

        "JDK inflates what was deflated" in run {
            val input = Chunk.from(getBytes(
                """
                    |The headphones were on.
                    | They had been utilized on purpose.
                    | She could hear her mom yelling in the background, but couldn't make out exactly what the yelling was about.
                    | That was exactly why she had put them on.
                    | She knew her mom would enter her room at any minute, and she could pretend that she hadn't heard any of the previous yelling.""".stripMargin
            ))
            for
                inStream       <- Sync(Stream.init(input))
                deflatedStream <- Sync(inStream.deflate())
                deflatedChunk  <- deflatedStream.run
                inflatedChunk  <- Sync(jdkInflate(deflatedChunk, new Inflater(false)))
            yield assert(inflatedChunk == input)
            end for
        }

        "JDK inflates what was deflated, nowrap" in run {
            val input = Chunk.from(getBytes(
                """
                    |The wolves stopped in their tracks, sizing up the mother and her cubs.
                    | It had been over a week since their last meal and they were getting desperate.
                    | The cubs would make a good meal, but there were high risks taking on the mother Grizzly.
                    | A decision had to be made and the wrong choice could signal the end of the pack.""".stripMargin
            ))
            for
                inStream       <- Sync(Stream.init(input))
                deflatedStream <- Sync(inStream.deflate(noWrap = true))
                deflatedChunk  <- deflatedStream.run
                inflatedChunk  <- Sync(jdkInflate(deflatedChunk, new Inflater(true)))
            yield assert(inflatedChunk == input)
            end for
        }

        "same as JDKs" in run {
            val input = Chunk.from(getBytes(
                """
                    |Stormi is a dog. She is dark grey and has long legs.
                    | Her eyes are expressive and are able to let her humans know what she is thinking.
                    | Her tongue is long, pink, and wet.
                    | Her long legs allow her to sprint after other dogs, people or bunnies.
                    | She can be a good dog, but also very bad.
                    | Her tail wags when happy or excited and hides between her back legs when she is bad.
                    | Stormi is a dog I love.""".stripMargin
            ))
            for
                inStream         <- Sync(Stream.init(input))
                deflatedStream   <- Sync(inStream.deflate(bufferSize = 1))
                deflatedChunk    <- deflatedStream.run
                jdkDeflatedChunk <- Sync(jdkDeflate(input, new Deflater(StreamCompression.CompressionLevel.Default.value, false)))
                inflatedStream   <- Sync(deflatedStream.inflate(bufferSize = 1))
                inflatedChunk    <- inflatedStream.run
                jdkInflatedChunk <- Sync(jdkInflate(jdkDeflatedChunk, new Inflater(false)))
            yield assert(deflatedChunk == jdkDeflatedChunk && inflatedChunk == jdkInflatedChunk)
            end for
        }

        "deflate empty bytes" in run {
            for
                inStream       <- Sync(Stream.empty[Byte])
                deflatedStream <- Sync(inStream.deflate())
                deflatedChunk  <- deflatedStream.run
                jdkDeflatedChunk <-
                    Sync(jdkDeflate(Chunk.empty[Byte], new Deflater(StreamCompression.CompressionLevel.Default.value, false)))
            yield assert(deflatedChunk == jdkDeflatedChunk)
            end for
        }
    }

    "gzip/gunzip" - {
        "short stream" in run {
            val shortTextChunk = Chunk.from(getBytes(shortText))
            for
                jdkGzippedStream <- Sync {
                    val gzipByte = jdkGzip(shortTextChunk, syncFlush = true)
                    Stream.init(gzipByte)
                }
                gunzippedStream <- Sync(jdkGzippedStream.gunzip())
                gunzippedChunk  <- gunzippedStream.run
                string          <- Sync(new String(toUnboxByteArray(gunzippedChunk), StandardCharsets.UTF_8))
            yield assert(string == shortText)
            end for
        }

        "stream of two gzipped inputs" in run {
            for
                inStream1 <- Sync(Stream.init(Chunk.from(getBytes(shortText))))
                inChunk1  <- inStream1.run
                jdkGzippedStream1 <- Sync {
                    val gzipByte = jdkGzip(inChunk1, syncFlush = true)
                    Stream.init(gzipByte)
                }
                inStream2 <- Sync(Stream.init(Chunk.from(getBytes(otherShortText))))
                inChunk2  <- inStream2.run
                jdkGzippedStream2 <- Sync {
                    val gzipByte = jdkGzip(inChunk2, syncFlush = true)
                    Stream.init(gzipByte)
                }
                gunzippedStream <- Sync(jdkGzippedStream1.concat(jdkGzippedStream2).gunzip())
                bytes           <- gunzippedStream.run.map(toUnboxByteArray)
                string          <- Sync(new String(bytes, StandardCharsets.UTF_8))
            yield assert(string == shortText ++ otherShortText)
        }

        "long stream, no sync flush" in run {
            val longTextChunk = Chunk.from(getBytes(longText))
            for
                jdkGzippedStream <- Sync {
                    val gzipByte = jdkGzip(longTextChunk, syncFlush = false)
                    Stream.init(gzipByte)
                }
                gunzippedStream <- Sync(jdkGzippedStream.gunzip())
                gunzippedChunk  <- gunzippedStream.run
                string          <- Sync(new String(toUnboxByteArray(gunzippedChunk), StandardCharsets.UTF_8))
            yield assert(string == longText)
            end for
        }

        "with extras" in run {
            // length = 310
            val text = getBytes("""
                                  |It was that terrifying feeling you have as you tightly hold the covers over you with the knowledge that there is something hiding under your bed.
                                  | You want to look, but you don't at the same time.
                                  | You're frozen with fear and unable to act.
                                  | That's where she found herself and she didn't know what to do next""".stripMargin) ++ Array(0.toByte)
            val header      = Array(31, 139, 8, 2 | 4 | 8 | 16, 0, 0, 0, 0, 0, 0xff).map(_.toByte)
            val headerExtra = Array(0x36, 0x1).map(_.toByte) ++ text
            val comment     = getBytes("Kyo rock!") ++ Array(0.toByte)
            val fileName    = getBytes("kyo-readme.md") ++ Array(0.toByte)
            val crc16 = locally:
                val crc32 = new CRC32()
                crc32.update(header)
                crc32.update(headerExtra)
                crc32.update(comment)
                crc32.update(fileName)
                val crc16      = (crc32.getValue & 0xffffL).toInt
                val crc16Byte1 = (crc16 & 0xff).toByte
                val crc16Byte2 = (crc16 >> 8).toByte
                Array(crc16Byte1, crc16Byte2)
            end crc16
            val totalHeader = Chunk.from(header ++ headerExtra ++ comment ++ fileName ++ crc16)
            for
                jdkGzippedStream <- Sync {
                    val gzipByte        = jdkGzip(Chunk.from(getBytes(shortText)), syncFlush = false)
                    val withTotalHeader = totalHeader.concat(gzipByte.drop(10))
                    Stream.init(withTotalHeader)
                }
                gunzippedStream <- Sync(jdkGzippedStream.gunzip())
                gunzippedChunk  <- gunzippedStream.run
                string          <- Sync(new String(toUnboxByteArray(gunzippedChunk), StandardCharsets.UTF_8))
            yield assert(string == shortText)
            end for
        }

        "gzip empty bytes" in run {
            for
                inStream     <- Sync(Stream.empty[Byte])
                gzipStream   <- Sync(inStream.gzip())
                gzipChunk    <- gzipStream.run
                jdkGzipChunk <- Sync(jdkGzip(Chunk.empty[Byte], syncFlush = false))
            yield assert(gzipChunk == jdkGzipChunk)
            end for
        }

        "gzip and then gunzip" in run {
            val longTextChunk = Chunk.from(getBytes(longText))
            for
                inStream     <- Sync(Stream.init(longTextChunk))
                gzipStream   <- Sync(inStream.gzip())
                gunzipStream <- Sync(gzipStream.gunzip())
                byteChunk    <- gunzipStream.run
            yield assert(byteChunk == longTextChunk)
            end for
        }

        "gzip does compress" in run {
            val uncompressed = Chunk.from(getBytes(""""
                   |Love isn't always a ray of sunshine.
                   |That's what the older girls kept telling her when she said she had found the perfect man.
                   |She had thought this was simply bitter talk on their part since they had been unable to find true love like hers.
                   |But now she had to face the fact that they may have been right.
                   |Love may not always be a ray of sunshine.
                   |That is unless they were referring to how the sun can burn.""".stripMargin))
            for
                inStream   <- Sync(Stream.init(uncompressed))
                gzipStream <- Sync(inStream.gzip(bufferSize = 2048))
                gzipChunk  <- gzipStream.run
            yield assert(gzipChunk.length < uncompressed.length)
            end for
        }

        "same as JDKs" in run {
            val input = Chunk.from(getBytes(
                """
                    |Sometimes there isn't a good answer.
                    | No matter how you try to rationalize the outcome, it doesn't make sense.
                    | And instead of an answer, you are simply left with a question. Why?""".stripMargin
            ))
            for
                inStream       <- Sync(Stream.init(input))
                gzipStream     <- Sync(inStream.gzip(bufferSize = 1))
                gzipChunk      <- gzipStream.run
                jdkGzipChunk   <- Sync(jdkGzip(input, true))
                gunzipStream   <- Sync(gzipStream.gunzip(bufferSize = 1))
                gunzipChunk    <- gunzipStream.run
                jdkGunzipChunk <- Sync(jdkGunzip(jdkGzipChunk))
            yield assert(gzipChunk == jdkGzipChunk && gunzipChunk == jdkGunzipChunk)
            end for
        }
    }
end StreamCompressionTest
