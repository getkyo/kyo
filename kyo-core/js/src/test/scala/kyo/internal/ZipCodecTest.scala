package kyo.internal

import java.util.Arrays
import kyo.*
import scala.scalajs.js.typedarray.*
import scala.scalajs.js as sjs

/** The Scala.js compressor: Node's zlib where the host has it, the portable codec elsewhere.
  *
  * Its output has to be one DEFLATE stream whichever engine wrote it, so every round trip here reads back through the portable decoder,
  * which runs on every host, and on Node through zlib's own decoder too, which holds it to an implementation other than the one that
  * wrote it. The JVM holds the portable codec to the JDK (`PortableZipTest`).
  */
class ZipCodecTest extends kyo.test.Test[Any]:

    private val prose =
        ("The wolves stopped in their tracks, sizing up the mother and her cubs. It had been over a week since their last meal and they " +
            "were getting desperate. ").getBytes("UTF-8")

    /** Text past several segments, with enough variety that compression levels differ. */
    private val longText: Array[Byte] =
        val out = Array.newBuilder[Byte]
        var i   = 0
        while i < 4000 do
            out ++= s"$i: ".getBytes("UTF-8")
            out ++= prose
            i += 1
        end while
        out.result()
    end longText

    private def compress(data: Array[Byte], level: Int = -1, noWrap: Boolean = false, strategy: Int = 0, flush: Int = 0): Array[Byte] =
        val deflater = new ZipCodec.Deflater(level, noWrap)
        deflater.setStrategy(strategy)
        val out           = Array.newBuilder[Byte]
        val buffer        = new Array[Byte](8192)
        def drain(): Unit =
            var n = deflater.deflate(buffer, 0, buffer.length, flush)
            while n > 0 do
                out ++= buffer.take(n)
                n = deflater.deflate(buffer, 0, buffer.length, flush)
        end drain
        var pos = 0
        while pos < data.length do
            val n = math.min(4096, data.length - pos)
            deflater.setInput(Arrays.copyOfRange(data, pos, pos + n))
            pos += n
            drain()
        end while
        deflater.finish()
        while !deflater.finished do drain()
        out.result()
    end compress

    private def portableInflate(data: Array[Byte], noWrap: Boolean = false): Array[Byte] =
        val inflater = new PortableZip.Inflater(noWrap)
        inflater.setInput(data)
        val out    = Array.newBuilder[Byte]
        val buffer = new Array[Byte](8192)
        var n      = inflater.inflate(buffer)
        while n > 0 do
            out ++= buffer.take(n)
            n = inflater.inflate(buffer)
        out.result()
    end portableInflate

    /** zlib's own decoder, on a host that has it. */
    private def zlibInflate(data: Array[Byte], noWrap: Boolean): Maybe[Array[Byte]] =
        NodeZlib.module.map { zlib =>
            val source = byteArray2Int8Array(data)
            val input  = new Uint8Array(source.buffer, source.byteOffset, source.length)
            val result = (if noWrap then zlib.inflateRawSync(input) else zlib.inflateSync(input)).asInstanceOf[Uint8Array]
            int8Array2ByteArray(new Int8Array(result.buffer, result.byteOffset, result.length))
        }

    private def readsBack(compressed: Array[Byte], original: Array[Byte], noWrap: Boolean = false): Boolean =
        portableInflate(compressed, noWrap).sameElements(original) &&
            zlibInflate(compressed, noWrap).forall(_.sameElements(original))

    "a Node-like host compresses through zlib, and any other host through the portable codec" in {
        assert(new ZipCodec.Deflater(-1, false).native == Platform.isNodeLike)
    }

    "the output is one stream that reads back" - {

        "ZLIB framed, across several segments" in {
            assert(longText.length > NodeZlib.SegmentSize * 3)
            assert(readsBack(compress(longText), longText))
        }

        "raw" in {
            assert(readsBack(compress(longText, noWrap = true), longText, noWrap = true))
        }

        "no input at all" in {
            assert(readsBack(compress(Array.empty), Array.empty))
        }

        "at every level, and the levels are honored" in {
            val sizes = Chunk(0, 1, 6, 9).map { level =>
                val out = compress(longText, level = level)
                assert(readsBack(out, longText), s"level $level")
                out.length
            }
            // Level 0 stores, which RFC 1951 makes larger than the input: the level reaches the engine.
            assert(sizes(0) > longText.length, s"sizes $sizes")
            // Every other level compresses, to a small fraction of the input on text this repetitive.
            // The compressing levels are NOT ordered against each other. DEFLATE does not promise that a higher
            // level is never a byte larger, and zlib builds disagree on this input: one answers 12682, 12689,
            // 12429 for levels 1, 6 and 9, putting level 6 above level 1. Asserting the order tests zlib's
            // match-search tuning rather than this codec.
            assert(sizes.tail.forall(_ < longText.length / 10), s"sizes $sizes")
        }

        "under the huffman-only strategy, which finds no matches" in {
            val huffman = compress(longText, strategy = 2)
            assert(readsBack(huffman, longText))
            assert(huffman.length > compress(longText).length)
        }
    }

    "a sync flush leaves everything given so far readable" in {
        val deflater = new ZipCodec.Deflater(-1, true)
        val out      = Array.newBuilder[Byte]
        val buffer   = new Array[Byte](8192)
        val supplied = Array.newBuilder[Byte]
        Chunk("first part, ", "second part, ", "third").foreach { part =>
            val bytes = part.getBytes("UTF-8")
            supplied ++= bytes
            deflater.setInput(bytes)
            var n = deflater.deflate(buffer, 0, buffer.length, NodeZlib.SyncFlush)
            while n > 0 do
                out ++= buffer.take(n)
                n = deflater.deflate(buffer, 0, buffer.length, NodeZlib.SyncFlush)
            // Nothing is finished yet, so a decoder reads to the flush point and waits for more.
            assert(new String(portableInflate(out.result(), noWrap = true), "UTF-8") == new String(supplied.result(), "UTF-8"))
        }
    }

    "a host's zlib is used only when it writes the sync flush segments rely on" - {

        "no zlib at all" in {
            assert(NodeZlib.select(sjs.undefined) == Absent)
        }

        "a zlib whose sync-flushed output does not end in the empty stored block" in {
            val garbage: sjs.Function2[sjs.Any, sjs.Any, Uint8Array] = (_, _) => new Uint8Array(sjs.Array[Short](1, 2, 3))
            assert(NodeZlib.select(sjs.Dynamic.literal(deflateRawSync = garbage)) == Absent)
        }

        "a zlib that throws" in {
            val throwing: sjs.Function2[sjs.Any, sjs.Any, Uint8Array] = (_, _) => throw new RuntimeException("no")
            assert(NodeZlib.select(sjs.Dynamic.literal(deflateRawSync = throwing)) == Absent)
        }

        "Node's own" in {
            if !Platform.isNodeLike then cancel("a page has no node:zlib")
            else assert(NodeZlib.select(PlatformJs.nodeBuiltin("node:zlib")).nonEmpty)
        }
    }
end ZipCodecTest
