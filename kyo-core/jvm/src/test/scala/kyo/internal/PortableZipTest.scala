package kyo.internal

import java.util.Arrays
import kyo.*

/** The portable codec against the JDK's zlib, in both directions.
  *
  * A round trip through one implementation proves only that it agrees with itself, and a stream that only kyo can
  * read is not a DEFLATE stream. This is the one platform with another implementation to hold it to, so the
  * assertions here are what the JS and Native platforms cannot ask for themselves.
  */
class PortableZipTest extends kyo.test.Test[Any]:

    private val shortText = "abcdefg1234567890".getBytes
    private val longText  = Array.fill(1000)("abcdefg1234567890").flatten.mkString.getBytes
    private val allBytes  = Array.tabulate(256)(_.toByte)

    /** Text with enough structure to exercise matching, and enough variety to keep literals in the block. */
    private val prose =
        ("The wolves stopped in their tracks, sizing up the mother and her cubs. It had been over a week since their " +
            "last meal and they were getting desperate. The cubs would make a good meal, but there were high risks " +
            "taking on the mother Grizzly. A decision had to be made and the wrong choice could signal the end of " +
            "the pack.").getBytes

    private def portableDeflate(
        data: Array[Byte],
        level: Int = -1,
        noWrap: Boolean = false,
        strategy: Int = 0,
        flush: Int = 0,
        chunk: Int = 4096
    ): Array[Byte] =
        val deflater = new PortableZip.Deflater(level, noWrap)
        deflater.setStrategy(strategy)
        val out    = Array.newBuilder[Byte]
        val buffer = new Array[Byte](chunk)
        def drain(): Unit =
            var n = deflater.deflate(buffer, 0, buffer.length, flush)
            while n > 0 do
                out ++= buffer.take(n)
                n = deflater.deflate(buffer, 0, buffer.length, flush)
        end drain
        var pos = 0
        while pos < data.length do
            val n = math.min(chunk, data.length - pos)
            deflater.setInput(Arrays.copyOfRange(data, pos, pos + n))
            pos += n
            drain()
        end while
        deflater.finish()
        drain()
        deflater.end()
        out.result()
    end portableDeflate

    private def portableInflate(data: Array[Byte], noWrap: Boolean = false, chunk: Int = 4096): Array[Byte] =
        val inflater = new PortableZip.Inflater(noWrap)
        val out      = Array.newBuilder[Byte]
        val buffer   = new Array[Byte](chunk)
        var pos      = 0
        var done     = false
        while !done do
            if inflater.finished then done = true
            else if inflater.needsInput then
                if pos >= data.length then done = true
                else
                    val n = math.min(chunk, data.length - pos)
                    inflater.setInput(Arrays.copyOfRange(data, pos, pos + n))
                    pos += n
            else
                val n = inflater.inflate(buffer)
                if n > 0 then out ++= buffer.take(n)
            end if
        end while
        inflater.end()
        out.result()
    end portableInflate

    private def jdkDeflate(data: Array[Byte], level: Int = -1, noWrap: Boolean = false): Array[Byte] =
        val deflater = new java.util.zip.Deflater(level, noWrap)
        deflater.setInput(data)
        deflater.finish()
        val out    = Array.newBuilder[Byte]
        val buffer = new Array[Byte](4096)
        while !deflater.finished() do
            val n = deflater.deflate(buffer)
            out ++= buffer.take(n)
        deflater.end()
        out.result()
    end jdkDeflate

    private def jdkInflate(data: Array[Byte], noWrap: Boolean = false): Array[Byte] =
        val inflater = new java.util.zip.Inflater(noWrap)
        inflater.setInput(data)
        val out    = Array.newBuilder[Byte]
        val buffer = new Array[Byte](4096)
        while !inflater.finished() && !inflater.needsInput() do
            val n = inflater.inflate(buffer)
            out ++= buffer.take(n)
        inflater.end()
        out.result()
    end jdkInflate

    "the JDK reads what the codec writes" - {

        "a ZLIB framed stream" in {
            assert(jdkInflate(portableDeflate(prose)).sameElements(prose))
        }

        "a raw stream" in {
            assert(jdkInflate(portableDeflate(prose, noWrap = true), noWrap = true).sameElements(prose))
        }

        "text long enough to cross the block boundary" in {
            assert(jdkInflate(portableDeflate(longText)).sameElements(longText))
        }

        "every byte value" in {
            assert(jdkInflate(portableDeflate(allBytes)).sameElements(allBytes))
        }

        "no input at all" in {
            assert(jdkInflate(portableDeflate(Array.empty)).isEmpty)
        }

        "stored blocks, at level 0" in {
            assert(jdkInflate(portableDeflate(prose, level = 0)).sameElements(prose))
        }

        "literals only, under the huffman-only strategy" in {
            assert(jdkInflate(portableDeflate(prose, strategy = 2)).sameElements(prose))
        }

        "a stream flushed at every chunk" in {
            assert(jdkInflate(portableDeflate(longText, flush = 2, chunk = 64)).sameElements(longText))
        }

        "at every level" in {
            val levels = Seq(0, 1, 3, 6, 9)
            assert(levels.forall(level => jdkInflate(portableDeflate(prose, level = level)).sameElements(prose)))
        }
    }

    "the codec reads what the JDK writes" - {

        "a ZLIB framed stream" in {
            assert(portableInflate(jdkDeflate(prose)).sameElements(prose))
        }

        "a raw stream" in {
            assert(portableInflate(jdkDeflate(prose, noWrap = true), noWrap = true).sameElements(prose))
        }

        "a block with a dynamic code table" in {
            // The JDK reaches for one at its highest level on text this repetitive.
            assert(portableInflate(jdkDeflate(longText, level = 9)).sameElements(longText))
        }

        "an uncompressed stream, at level 0" in {
            assert(portableInflate(jdkDeflate(prose, level = 0)).sameElements(prose))
        }

        "arriving one byte at a time" in {
            assert(portableInflate(jdkDeflate(longText), chunk = 1).sameElements(longText))
        }

        "into a one-byte output buffer" in {
            assert(portableInflate(jdkDeflate(prose), chunk = 1).sameElements(prose))
        }

        "text longer than the window" in {
            val wide = Array.tabulate(200000)(i => ((i * 31 + i / 97) & 0xff).toByte)
            assert(portableInflate(jdkDeflate(wide)).sameElements(wide))
        }
    }

    "the codec reads its own output" - {

        "text longer than the window, where a match reaches back the furthest" in {
            val wide = Array.tabulate(200000)(i => ((i * 31 + i / 97) & 0xff).toByte)
            assert(portableInflate(portableDeflate(wide)).sameElements(wide))
        }

        "one byte" in {
            assert(portableInflate(portableDeflate(Array(42.toByte))).sameElements(Array(42.toByte)))
        }

        "input that repays no match at all" in {
            val random = new scala.util.Random(1234)
            val noise  = Array.fill(50000)(random.nextInt(256).toByte)
            assert(portableInflate(portableDeflate(noise)).sameElements(noise))
        }
    }

    "what the fixed tables cost" - {

        // The codec writes fixed Huffman codes where zlib builds a table per block, so its output is larger. These
        // two say by how much, in the two shapes that differ most: prose, where a table of its own saves zlib
        // little, and a long repeat, where that table makes every length code cheap and this one pays full price
        // for each.

        "prose stays close to what the JDK writes" in {
            val portable = portableDeflate(prose).length
            val jdk      = jdkDeflate(prose).length
            assert(portable <= jdk * 3 / 2, s"portable $portable against the JDK's $jdk")
        }

        "a long repeat still compresses to a small fraction of itself" in {
            val portable = portableDeflate(longText).length
            assert(portable * 20 < longText.length, s"portable $portable of ${longText.length}")
        }
    }

    "the checksums agree with the JDK's" - {

        "CRC-32 over one array" in {
            val jdk = new java.util.zip.CRC32
            jdk.update(longText)
            val portable = new PortableZip.Crc32
            portable.update(longText)
            assert(portable.getValue == jdk.getValue)
        }

        "CRC-32 over several updates, and after a reset" in {
            val jdk = new java.util.zip.CRC32
            jdk.update(shortText)
            jdk.update(prose)
            val portable = new PortableZip.Crc32
            portable.update(shortText)
            portable.update(prose)
            val both = portable.getValue == jdk.getValue
            jdk.reset()
            portable.reset()
            jdk.update(allBytes)
            portable.update(allBytes)
            assert(both && portable.getValue == jdk.getValue)
        }

        "Adler-32 over input long enough to reduce the sums" in {
            val jdk = new java.util.zip.Adler32
            jdk.update(longText)
            val portable = new PortableZip.Adler32
            portable.update(longText)
            assert(portable.getValue == jdk.getValue)
        }

        "Adler-32 of nothing" in {
            val jdk      = new java.util.zip.Adler32
            val portable = new PortableZip.Adler32
            assert(portable.getValue == jdk.getValue)
        }
    }

    "what the stream did not reach" - {

        "is the bytes after a finished stream" in {
            val stream   = jdkDeflate(shortText) ++ "trailing".getBytes
            val inflater = new PortableZip.Inflater(false)
            inflater.setInput(stream)
            val buffer = new Array[Byte](1024)
            while !inflater.finished do discard(inflater.inflate(buffer))
            assert(new String(inflater.remainingBytes) == "trailing")
        }

        "is nothing when the stream ends exactly at the input" in {
            val stream   = jdkDeflate(shortText)
            val inflater = new PortableZip.Inflater(false)
            inflater.setInput(stream)
            val buffer = new Array[Byte](1024)
            while !inflater.finished do discard(inflater.inflate(buffer))
            assert(inflater.remainingBytes.isEmpty)
        }
    }

    "a stream that is not one fails" - {

        "a ZLIB header that is not zlib" in {
            val thrown =
                try
                    discard(portableInflate(Array[Byte](1, 2, 3, 4, 5)))
                    false
                catch case _: PortableZip.DataFormatException => true
            assert(thrown)
        }

        "damage anywhere is reported, never leaked" in {
            // One valid stream, each of its bytes flipped in turn. Every outcome has to be one the caller can act
            // on: the data back, other data with a checksum that catches it, or a DataFormatException. Anything
            // else is the decoder reading somewhere it should not, which is what an incomplete or over-subscribed
            // code table used to let a damaged stream do.
            val clean = jdkDeflate(prose)
            val leaked =
                (0 until clean.length).flatMap { at =>
                    val damaged = clean.clone()
                    damaged(at) = (damaged(at) ^ 0x5a).toByte
                    try
                        val _ = portableInflate(damaged)
                        None
                    catch
                        case _: PortableZip.DataFormatException => None
                        case other: Throwable                   => Some(s"byte $at: ${other.getClass.getName}")
                }
            assert(leaked.isEmpty, leaked.take(5).mkString(", "))
        }

        "a corrupted checksum" in {
            val stream = jdkDeflate(prose)
            stream(stream.length - 1) = (stream(stream.length - 1) ^ 0xff).toByte
            val thrown =
                try
                    discard(portableInflate(stream))
                    false
                catch case _: PortableZip.DataFormatException => true
            assert(thrown)
        }
    }
end PortableZipTest
