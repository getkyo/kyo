package kyo.internal

import kyo.Absent
import kyo.Maybe
import kyo.Present
import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** The compression primitives `StreamCompression` drives, on Scala.js: compression through Node's zlib where the host has it, and
  * `PortableZip` for everything else.
  *
  * Every method keeps the effect row it has on the JVM, `Sync`, so only a host's synchronous entry points can serve it:
  *   - Node's zlib compresses a whole buffer at once synchronously (`deflateRawSync`), so a stream compresses as a run of such buffers,
  *     each ended with a sync flush: an empty stored block that leaves the output byte aligned, after which the next buffer's blocks follow
  *     as the same raw DEFLATE stream, and the last buffer is finished. That is [[NodeDeflater]]. Each buffer starts a fresh window, so a
  *     match cannot reach across the 128 KB boundary, and the flush markers add four or five bytes each; what zlib's own streaming writes
  *     differs by those, and reads back the same.
  *   - Node's zlib streams are asynchronous, and no synchronous entry point decompresses a stream arriving in pieces, since a one-shot
  *     call cannot resume in the middle of a block. So decompression is `PortableZip` on Node too.
  *   - A page's `CompressionStream` and `DecompressionStream` are asynchronous both ways and take no level or strategy, so a page runs
  *     `PortableZip` in both directions.
  */
private[kyo] object ZipCodec:

    type DataFormatException = PortableZip.DataFormatException

    export PortableZip.Crc32
    export PortableZip.Inflater

    /** A compressor with `java.util.zip.Deflater`'s shape: [[NodeDeflater]] where [[NodeZlib.module]] finds a zlib, `PortableZip` elsewhere. */
    final class Deflater(level: Int, noWrap: Boolean):
        private val engine: Engine =
            NodeZlib.module match
                case Present(zlib) => new NodeDeflater(zlib, level, noWrap)
                case Absent        => new PortableEngine(new PortableZip.Deflater(level, noWrap))

        /** Whether this compressor runs through Node's zlib. */
        private[kyo] def native: Boolean = engine.isInstanceOf[NodeDeflater]

        def setStrategy(value: Int): Unit                                     = engine.setStrategy(value)
        def setInput(bytes: Array[Byte]): Unit                                = engine.setInput(bytes)
        def finish(): Unit                                                    = engine.finish()
        def deflate(target: Array[Byte], off: Int, len: Int, flush: Int): Int = engine.deflate(target, off, len, flush)
        def needsInput: Boolean                                               = engine.needsInput
        def finished: Boolean                                                 = engine.finished
        def getBytesRead: Long                                                = engine.getBytesRead
        def end(): Unit                                                       = engine.end()
    end Deflater

    private trait Engine:
        def setStrategy(value: Int): Unit
        def setInput(bytes: Array[Byte]): Unit
        def finish(): Unit
        def deflate(target: Array[Byte], off: Int, len: Int, flush: Int): Int
        def needsInput: Boolean
        def finished: Boolean
        def getBytesRead: Long
        def end(): Unit
    end Engine

    final private class PortableEngine(under: PortableZip.Deflater) extends Engine:
        def setStrategy(value: Int): Unit                                     = under.setStrategy(value)
        def setInput(bytes: Array[Byte]): Unit                                = under.setInput(bytes)
        def finish(): Unit                                                    = under.finish()
        def deflate(target: Array[Byte], off: Int, len: Int, flush: Int): Int = under.deflate(target, off, len, flush)
        def needsInput: Boolean                                               = under.needsInput
        def finished: Boolean                                                 = under.finished
        def getBytesRead: Long                                                = under.getBytesRead
        def end(): Unit                                                       = under.end()
    end PortableEngine

    /** Compression through Node's zlib, a buffer of up to [[NodeZlib.SegmentSize]] at a time (see [[ZipCodec]]).
      *
      * Input is kept until a segment's worth has arrived, the caller asks for a flush, or the stream finishes, which is when zlib's own
      * streaming compressor would write output too. The ZLIB frame is written here, around the raw segments: the header before the first,
      * and the Adler-32 of the input after the last. Like `PortableZip`'s, this takes every byte handed to it into its own buffer, so it
      * is always ready for more input.
      */
    final private class NodeDeflater(zlib: js.Dynamic, level: Int, noWrap: Boolean) extends Engine:
        private var strategy      = 0
        private var pending       = new Array[Byte](NodeZlib.SegmentSize)
        private var pendingLength = 0
        private var output        = Array.empty[Byte]
        private var outputStart   = 0
        private val adler         = new PortableZip.Adler32
        private var headerWritten = noWrap
        private var finishing     = false
        private var complete      = false
        private var unflushed     = false
        private var read          = 0L

        def setStrategy(value: Int): Unit = strategy = value

        def setInput(bytes: Array[Byte]): Unit =
            if pendingLength + bytes.length > pending.length then
                val grown = new Array[Byte](math.max(pending.length * 2, pendingLength + bytes.length))
                Array.copy(pending, 0, grown, 0, pendingLength)
                pending = grown
            end if
            Array.copy(bytes, 0, pending, pendingLength, bytes.length)
            pendingLength += bytes.length
            read += bytes.length
            if !noWrap then adler.update(bytes)
            if bytes.length > 0 then unflushed = true
        end setInput

        def finish(): Unit = finishing = true

        def needsInput: Boolean = true

        /** Finished once the last segment and the trailer are written and the caller has taken every byte of them. */
        def finished: Boolean = complete && outputStart == output.length

        def getBytesRead: Long = read

        def end(): Unit = ()

        def deflate(target: Array[Byte], off: Int, len: Int, flush: Int): Int =
            // More output is made only once the caller has taken what is queued, so the queue holds one segment at most.
            if outputStart == output.length then
                if !headerWritten then
                    enqueue(NodeZlib.zlibHeader(level))
                    headerWritten = true
                if !complete then
                    if finishing then
                        enqueue(segment(NodeZlib.Finish))
                        if !noWrap then enqueue(NodeZlib.bigEndian(adler.getValue))
                        complete = true
                    else if (flush == NodeZlib.SyncFlush || flush == NodeZlib.FullFlush) && unflushed then
                        // Each segment starts a fresh window, so a full flush's reset needs nothing more than a sync flush's marker.
                        enqueue(segment(NodeZlib.SyncFlush))
                    else if pendingLength >= NodeZlib.SegmentSize then
                        enqueue(segment(NodeZlib.SyncFlush))
                    end if
                end if
            end if
            val available = output.length - outputStart
            val n         = if available < len then available else len
            Array.copy(output, outputStart, target, off, n)
            outputStart += n
            n
        end deflate

        /** The pending input as one raw DEFLATE segment ending at `flushMode`, which empties the pending input. */
        private def segment(flushMode: Int): Array[Byte] =
            val source = byteArray2Int8Array(java.util.Arrays.copyOf(pending, pendingLength))
            val result = zlib.deflateRawSync(
                new Uint8Array(source.buffer, source.byteOffset, source.length),
                js.Dynamic.literal(level = level, strategy = strategy, finishFlush = flushMode)
            ).asInstanceOf[Uint8Array]
            pendingLength = 0
            unflushed = false
            int8Array2ByteArray(new Int8Array(result.buffer, result.byteOffset, result.length))
        end segment

        private def enqueue(bytes: Array[Byte]): Unit =
            if outputStart == output.length then
                output = bytes
                outputStart = 0
            else
                val joined = new Array[Byte](output.length - outputStart + bytes.length)
                Array.copy(output, outputStart, joined, 0, output.length - outputStart)
                Array.copy(bytes, 0, joined, output.length - outputStart, bytes.length)
                output = joined
                outputStart = 0
            end if
        end enqueue
    end NodeDeflater
end ZipCodec

/** Node's zlib, for [[ZipCodec.NodeDeflater]]. */
private[kyo] object NodeZlib:

    /** How much input one raw segment holds before it is compressed. */
    val SegmentSize: Int = 1 << 17

    // zlib's flush values, the ones `java.util.zip.Deflater` also uses for the two flushes.
    val SyncFlush: Int = 2
    val FullFlush: Int = 3
    val Finish: Int    = 4

    /** `node:zlib` on a host that provides it through `process.getBuiltinModule` and whose sync-flushed segments pass [[select]]'s check,
      * and `Absent` elsewhere, such as in a page.
      */
    lazy val module: Maybe[js.Dynamic] = select(PlatformJs.nodeBuiltin("node:zlib"))

    /** `candidate` when it compresses the way [[ZipCodec.NodeDeflater]] relies on: a sync-flushed segment ends with the empty stored block
      * RFC 1951 defines, byte aligned, and the segment followed by a finished empty one reads back as the input. A Node-compatible host that
      * implements `node:zlib` on its own, as Bun and Deno do, is held to the same check, and anything else runs the portable codec.
      */
    private[kyo] def select(candidate: js.UndefOr[js.Dynamic]): Maybe[js.Dynamic] =
        candidate.toOption match
            case None => Absent
            case Some(zlib) =>
                try
                    val input   = "kyo kyo kyo kyo kyo kyo kyo kyo".getBytes("UTF-8")
                    val typed   = byteArray2Int8Array(input)
                    val options = (flush: Int) => js.Dynamic.literal(level = -1, finishFlush = flush)
                    def raw(source: Int8Array, flush: Int): Array[Byte] =
                        val result = zlib.deflateRawSync(new Uint8Array(source.buffer, source.byteOffset, source.length), options(flush))
                            .asInstanceOf[Uint8Array]
                        int8Array2ByteArray(new Int8Array(result.buffer, result.byteOffset, result.length))
                    end raw
                    val flushed  = raw(typed, SyncFlush)
                    val finished = raw(new Int8Array(0), Finish)
                    val marker   = flushed.length >= 4 && flushed.takeRight(4).sameElements(Array[Byte](0, 0, -1, -1))
                    if marker && inflateRaw(flushed ++ finished).sameElements(input) then Present(zlib) else Absent
                catch case _: Throwable => Absent

    /** A ZLIB header for `level`, with the level bits zlib writes and the check bits that make it a multiple of 31. */
    def zlibHeader(level: Int): Array[Byte] =
        val effective = if level < 0 then 6 else level
        val levelBits =
            if effective <= 1 then 0
            else if effective >= 9 then 3
            else if effective < 6 then 1
            else 2
        val header = 0x7800 | (levelBits << 6)
        val framed = header + (31 - (header % 31))
        Array(((framed >>> 8) & 0xff).toByte, (framed & 0xff).toByte)
    end zlibHeader

    def bigEndian(value: Long): Array[Byte] =
        Array(((value >>> 24) & 0xff).toByte, ((value >>> 16) & 0xff).toByte, ((value >>> 8) & 0xff).toByte, (value & 0xff).toByte)

    private def inflateRaw(stream: Array[Byte]): Array[Byte] =
        val inflater = new PortableZip.Inflater(true)
        inflater.setInput(stream)
        val out    = Array.newBuilder[Byte]
        val buffer = new Array[Byte](256)
        var n      = inflater.inflate(buffer)
        while n > 0 do
            out.addAll(buffer.take(n))
            n = inflater.inflate(buffer)
        out.result()
    end inflateRaw
end NodeZlib
