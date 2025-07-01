package kyo

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Loop
import scala.annotation.tailrec

object StreamCompression:

    final class StreamCompressionException(cause: Text | Throwable)(using Frame) extends KyoException("", cause)

    enum CompressionLevel(val value: Int) derives CanEqual:
        case Default         extends CompressionLevel(-1)
        case NoCompression   extends CompressionLevel(0)
        case BestSpeed       extends CompressionLevel(1)
        case Level2          extends CompressionLevel(2)
        case Level3          extends CompressionLevel(3)
        case Level4          extends CompressionLevel(4)
        case Level5          extends CompressionLevel(5)
        case Level6          extends CompressionLevel(6)
        case Level7          extends CompressionLevel(7)
        case Level8          extends CompressionLevel(8)
        case BestCompression extends CompressionLevel(9)
    end CompressionLevel

    enum CompressionStrategy(val value: Int) derives CanEqual:
        case Default         extends CompressionStrategy(0)
        case Filtered        extends CompressionStrategy(1)
        case HuffmanOnly     extends CompressionStrategy(2)
        case BestSpeed       extends CompressionStrategy(2)
        case BestCompression extends CompressionStrategy(0)
    end CompressionStrategy

    enum FlushMode(val value: Int) derives CanEqual:
        case Default         extends FlushMode(0)
        case NoFlush         extends FlushMode(0)
        case SyncFlush       extends FlushMode(2)
        case FullFlush       extends FlushMode(3)
        case BestSpeed       extends FlushMode(3)
        case BestCompression extends FlushMode(0)
    end FlushMode

    private inline def toUnboxByteArray(byteChunk: Chunk[Byte]): Array[Byte] =
        val builder = Array.newBuilder[Byte]
        builder.addAll(byteChunk)
        builder.result()
    end toUnboxByteArray

    private inline def fromUnboxByteArray(byteArray: Array[Byte], size: Int): Chunk[Byte] =
        val builder = Chunk.newBuilder[Byte]
        var i       = 0
        var it      = byteArray.iterator
        while it.hasNext && i < size do
            builder.addOne(it.next())
            i += 1
        builder.result()
    end fromUnboxByteArray

    extension [A, Ctx](stream: Stream[A, Ctx])
        inline def deflate(
            bufferSize: Int = 1 << 15,
            compressionLevel: CompressionLevel = CompressionLevel.Default,
            strategy: CompressionStrategy = CompressionStrategy.Default,
            flushMode: FlushMode = FlushMode.Default,
            noWrap: Boolean = false
        )(
            using
            tag: Tag[Emit[Chunk[Byte]]],
            frame: Frame,
            ev: A <:< Byte
        ): Stream[Byte, Resource & Sync & Ctx] =
            deflateStream(stream.map(ev.apply), bufferSize, compressionLevel, strategy, flushMode, noWrap)
        end deflate

        inline def inflate(
            bufferSize: Int = 1 << 15,
            noWrap: Boolean = false
        )(
            using
            tag: Tag[Emit[Chunk[Byte]]],
            frame: Frame,
            ev: A <:< Byte
        ): Stream[Byte, Sync & Resource & Ctx & Abort[StreamCompressionException]] =
            inflateStream(stream.map(ev.apply), bufferSize, noWrap)
        end inflate

        inline def gzip(
            bufferSize: Int = 1 << 15,
            compressionLevel: CompressionLevel = CompressionLevel.Default,
            strategy: CompressionStrategy = CompressionStrategy.Default,
            flushMode: FlushMode = FlushMode.Default
        )(
            using
            tag: Tag[Emit[Chunk[Byte]]],
            frame: Frame,
            ev: A <:< Byte
        ): Stream[Byte, Resource & Sync & Ctx] =
            gzipStream(stream.map(ev.apply), bufferSize, compressionLevel, strategy, flushMode)
        end gzip

        inline def gunzip(
            bufferSize: Int = 1 << 15
        )(
            using
            tag: Tag[Emit[Chunk[Byte]]],
            frame: Frame,
            ev: A <:< Byte
        ): Stream[Byte, Sync & Resource & Ctx & Abort[StreamCompressionException]] =
            gunzipStream(stream.map(ev.apply), bufferSize)
        end gunzip
    end extension

    private def deflateStream[Ctx](
        stream: Stream[Byte, Ctx],
        bufferSize: Int,
        compressionLevel: CompressionLevel,
        strategy: CompressionStrategy,
        flushMode: FlushMode,
        noWrap: Boolean
    )(
        using
        Tag[Emit[Chunk[Byte]]],
        Frame
    ) =

        enum DeflateState derives CanEqual:
            case Initialize                                                               extends DeflateState
            case DeflateInput(delfater: Deflater, emit: Unit < (Emit[Chunk[Byte]] & Ctx)) extends DeflateState
            case PullDeflater(
                delfater: Deflater,
                maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)],
                chunk: Chunk[Byte]
            ) extends DeflateState
            case EmitDeflated(delfater: Deflater, maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)], chunk: Chunk[Byte])
                extends DeflateState
        end DeflateState

        def emit(
            stream: Stream[Byte, Ctx],
            bufferSize: Int,
            compressionLevel: CompressionLevel,
            strategy: CompressionStrategy,
            flushMode: FlushMode,
            noWrap: Boolean
        )(
            using
            Tag[Emit[Chunk[Byte]]],
            Frame
        ): Unit < (Resource & Sync & Emit[Chunk[Byte]] & Ctx) =
            Loop(DeflateState.Initialize):
                case DeflateState.Initialize =>
                    Resource.acquireRelease(Sync {
                        val deflater = new Deflater(compressionLevel.value, noWrap)
                        deflater.setStrategy(strategy.value)
                        deflater
                    }) { deflater =>
                        Sync(deflater.end())
                    }.map { deflater =>
                        Loop.continue(DeflateState.DeflateInput(deflater, stream.emit))
                    }
                case DeflateState.DeflateInput(deflater, emit) =>
                    Emit.runFirst(emit).map {
                        case Present(bytes) -> cont =>
                            Sync(deflater.setInput(toUnboxByteArray(bytes))).andThen(Loop.continue(DeflateState.PullDeflater(
                                deflater,
                                Present(cont),
                                Chunk.empty[Byte]
                            )))
                        case _ =>
                            Sync(deflater.finish()).andThen(Loop.continue(DeflateState.PullDeflater(
                                deflater,
                                Absent,
                                Chunk.empty[Byte]
                            )))
                    }
                case DeflateState.PullDeflater(deflater, maybeEmitFn, chunk) =>
                    Sync(new Array[Byte](bufferSize)).map: buffer =>
                        Sync(deflater.deflate(buffer, 0, buffer.length, flushMode.value))
                            .map:
                                case 0 =>
                                    Loop.continue(DeflateState.EmitDeflated(deflater, maybeEmitFn, chunk))
                                case size =>
                                    Sync(chunk.concat(fromUnboxByteArray(buffer, size)))
                                        .map(nextChunk => Loop.continue(DeflateState.PullDeflater(deflater, maybeEmitFn, nextChunk)))
                case DeflateState.EmitDeflated(deflater, maybeEmitFn, chunk) =>
                    Emit.valueWith(chunk):
                        maybeEmitFn match
                            case Present(emitFn) => Loop.continue(DeflateState.DeflateInput(deflater, emitFn()))
                            case Absent          => Sync(deflater.end()).andThen(Loop.done)
        end emit

        Stream(emit(stream, bufferSize, compressionLevel, strategy, flushMode, noWrap))
    end deflateStream

    private def gzipStream[Ctx](
        stream: Stream[Byte, Ctx],
        bufferSize: Int,
        compressionLevel: CompressionLevel,
        strategy: CompressionStrategy,
        flushMode: FlushMode
    )(
        using
        tag: Tag[Emit[Chunk[Byte]]],
        _frame: Frame
    ): Stream[Byte, Resource & Sync & Ctx] =
        enum GZipState derives CanEqual:
            case Initialize                                                                             extends GZipState
            case SendHeader(delfater: Deflater, crc32: CRC32)                                           extends GZipState
            case DeflateInput(delfater: Deflater, crc32: CRC32, emit: Unit < (Emit[Chunk[Byte]] & Ctx)) extends GZipState
            case PullDeflater(
                delfater: Deflater,
                crc32: CRC32,
                maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)],
                chunk: Chunk[Byte]
            ) extends GZipState
            case EmitDeflated(
                delfater: Deflater,
                crc32: CRC32,
                maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)],
                chunk: Chunk[Byte]
            )                                                  extends GZipState
            case SendTrailer(delfater: Deflater, crc32: CRC32) extends GZipState
        end GZipState

        def emit(
            stream: Stream[Byte, Ctx],
            bufferSize: Int,
            compressionLevel: CompressionLevel,
            strategy: CompressionStrategy,
            flushMode: FlushMode
        ): Unit < (Resource & Sync & Emit[Chunk[Byte]] & Ctx) =
            val bufferIO = Sync(new Array[Byte](bufferSize))

            Loop(GZipState.Initialize):
                case GZipState.Initialize =>
                    Resource.acquireRelease(Sync {
                        val deflater = new Deflater(compressionLevel.value, true)
                        val crc32    = new CRC32()
                        deflater.setStrategy(strategy.value)
                        deflater -> crc32
                    }) { (deflater, crc32) =>
                        Sync {
                            deflater.end()
                            crc32.reset()
                        }
                    }.map { (deflater, crc32) =>
                        Loop.continue(GZipState.SendHeader(deflater, crc32))
                    }
                case GZipState.SendHeader(deflater, crc32) =>
                    Sync {
                        // no MTIME timestamp, unknown OS
                        Chunk(
                            0x1f, // ID1: Identification 1
                            0x8b, // ID2: Identification 2
                            0x8,  // CM: Compression Method
                            0,
                            0,
                            0,
                            0,
                            0,
                            compressionLevel match
                                case CompressionLevel.BestSpeed       => 0x4
                                case CompressionLevel.BestCompression => 0x2
                                case _ =>
                                    0
                            ,    // XFL: Extra flags
                            0xff // OS: Operating System
                        ).map(_.toByte)
                    }.map { header =>
                        Emit.valueWith(header)(Loop.continue(GZipState.DeflateInput(deflater, crc32, stream.emit)))
                    }
                case GZipState.DeflateInput(deflater, crc32, emit) =>
                    Emit.runFirst(emit).map {
                        case Present(bytes) -> cont =>
                            Sync {
                                crc32.update(toUnboxByteArray(bytes))
                                deflater.setInput(toUnboxByteArray(bytes))
                            }.andThen(Loop.continue(GZipState.PullDeflater(deflater, crc32, Present(cont), Chunk.empty[Byte])))
                        case _ =>
                            Sync(deflater.finish()).andThen(Loop.continue(GZipState.PullDeflater(
                                deflater,
                                crc32,
                                Absent,
                                Chunk.empty[Byte]
                            )))
                    }
                case GZipState.PullDeflater(deflater, crc32, maybeEmitFn, chunk) =>
                    bufferIO.map: buffer =>
                        Sync(deflater.deflate(buffer, 0, buffer.length, flushMode.value))
                            .map:
                                case 0 => Loop.continue(GZipState.EmitDeflated(deflater, crc32, maybeEmitFn, chunk))
                                case size => Sync(chunk.concat(fromUnboxByteArray(buffer, size)))
                                        .map(nextChunk => Loop.continue(GZipState.PullDeflater(deflater, crc32, maybeEmitFn, nextChunk)))
                case GZipState.EmitDeflated(deflater, crc32, maybeEmitFn, chunk) =>
                    Emit.valueWith(chunk):
                        maybeEmitFn match
                            case Present(emitFn) => Loop.continue(GZipState.DeflateInput(deflater, crc32, emitFn()))
                            case Absent          => Loop.continue(GZipState.SendTrailer(deflater, crc32))
                case GZipState.SendTrailer(deflater, crc32) =>
                    Sync {
                        val crcValue  = crc32.getValue
                        val bytesRead = deflater.getBytesRead()
                        val trailer = Chunk(
                            crcValue & 0xff, // CRC-32: Cyclic Redundancy Check
                            (crcValue >> 8) & 0xff,
                            (crcValue >> 16) & 0xff,
                            (crcValue >> 24) & 0xff,
                            bytesRead & 0xff, // ISIZE: Input size
                            (bytesRead >> 8) & 0xff,
                            (bytesRead >> 16) & 0xff,
                            (bytesRead >> 24) & 0xff
                        ).map(_.toByte)
                        crc32.reset()
                        deflater.end()
                        trailer
                    }.map { trailer =>
                        Emit.valueWith(trailer)(Loop.done)
                    }
        end emit

        Stream(emit(stream, bufferSize, compressionLevel, strategy, flushMode))
    end gzipStream

    private def inflateStream[Ctx](
        stream: Stream[Byte, Ctx],
        bufferSize: Int,
        noWrap: Boolean
    )(
        using
        Tag[Emit[Chunk[Byte]]],
        Frame
    ) =

        val bufferIO = Sync(new Array[Byte](bufferSize))

        enum InflateState derives CanEqual:
            case Initialize                                                               extends InflateState
            case InflateInput(inflater: Inflater, emit: Unit < (Emit[Chunk[Byte]] & Ctx)) extends InflateState
            case PullInflater(inflater: Inflater, maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)], bytes: Chunk[Byte])
                extends InflateState
        end InflateState

        def emit(
            stream: Stream[Byte, Ctx],
            bufferSize: Int,
            noWrap: Boolean
        )(
            using
            Tag[Emit[Chunk[Byte]]],
            Frame
        ): Unit < (Resource & Sync & Abort[StreamCompressionException] & Ctx & Emit[Chunk[Byte]]) =
            Loop(InflateState.Initialize):
                case InflateState.Initialize =>
                    Resource
                        .acquireRelease(Sync(new Inflater(noWrap)))(inflater => Sync(inflater.end()))
                        .map: inflater =>
                            Loop.continue(InflateState.InflateInput(inflater, stream.emit))
                case InflateState.InflateInput(inflater, emit) =>
                    Emit.runFirst(emit).map:
                        case Present(bytes) -> emitFn =>
                            Sync(inflater.setInput(toUnboxByteArray(bytes)))
                                .andThen(
                                    Loop.continue(InflateState.PullInflater(inflater, Present(emitFn), bytes))
                                )
                        case _ =>
                            Sync(Loop.continue(InflateState.PullInflater(inflater, Absent, Chunk.empty)))
                case InflateState.PullInflater(inflater, maybeEmitFn, bytes) =>
                    if inflater.finished then
                        val leftOver = inflater.getRemaining match
                            case remaining if remaining > 0 => bytes.takeRight(remaining)
                            case _                          => Chunk.empty[Byte]
                        Emit.valueWith(leftOver):
                            maybeEmitFn match
                                case Present(emitFn) =>
                                    Resource
                                        .acquireRelease(Sync(new Inflater(noWrap)))(inflater => Sync(inflater.end()))
                                        .map: inflater =>
                                            Loop.continue(InflateState.InflateInput(inflater, emitFn()))
                                case Absent =>
                                    Loop.done
                    else if inflater.needsInput then
                        Emit.valueWith(Chunk.empty[Byte]):
                            maybeEmitFn match
                                case Present(emitFn) => Loop.continue(InflateState.InflateInput(inflater, emitFn()))
                                case Absent          => Loop.done
                    else
                        bufferIO.map: buffer =>
                            Abort
                                .catching[DataFormatException](dfe => StreamCompressionException(dfe))(inflater.inflate(buffer))
                                .map(read => fromUnboxByteArray(buffer, read))
                                .map(inflated =>
                                    Emit.valueWith(inflated)(Loop.continue(InflateState.PullInflater(inflater, maybeEmitFn, bytes)))
                                )
                    end if
        end emit

        Stream(emit(stream, bufferSize, noWrap))
    end inflateStream

    private def gunzipStream[Ctx](
        stream: Stream[Byte, Ctx],
        bufferSize: Int
    )(
        using
        Tag[Chunk[Byte]],
        Frame
    ) =

        enum GunzipState derives CanEqual:
            case Initialize                                                                                  extends GunzipState
            case ParseHeader(bytes: Chunk[Byte], headerCrc32: CRC32, emit: Unit < (Emit[Chunk[Byte]] & Ctx)) extends GunzipState
            case ParseHeaderExtra(
                bytes: Chunk[Byte],
                headerCrc32: CRC32,
                checkCrc16: Boolean,
                commentsToSkip: Int,
                emit: Unit < (Emit[Chunk[Byte]] & Ctx)
            ) extends GunzipState
            case SkipComments(
                bytes: Chunk[Byte],
                headerCrc32: CRC32,
                checkCrc16: Boolean,
                commentsToSkip: Int,
                emit: Unit < (Emit[Chunk[Byte]] & Ctx)
            ) extends GunzipState
            case CheckCrc16(
                bytes: Chunk[Byte],
                headerCrc32: CRC32,
                emit: Unit < (Emit[Chunk[Byte]] & Ctx)
            ) extends GunzipState
            case InflateInput(leftOver: Chunk[Byte], inflater: Inflater, contentCrc32: CRC32, emit: Unit < (Emit[Chunk[Byte]] & Ctx))
                extends GunzipState
            case PullInflater(
                inflater: Inflater,
                contentCrc32: CRC32,
                maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)],
                bytes: Chunk[Byte]
            ) extends GunzipState
            case CheckTrailer(
                leftOver: Chunk[Byte],
                inflater: Inflater,
                contentCrc32: CRC32,
                maybeEmitFn: Maybe[() => Unit < (Emit[Chunk[Byte]] & Ctx)]
            ) extends GunzipState
        end GunzipState

        val fixedHeaderLength = 10

        inline def usize8(b: Byte): Int = b & 0xff

        inline def usize16(b1: Byte, b2: Byte): Int = usize8(b1) | (usize8(b2) << 8)

        inline def usize32(b1: Byte, b2: Byte, b3: Byte, b4: Byte) = usize16(b1, b2) | (usize16(b3, b4) << 16)

        inline def readInt(a: Array[Byte]): Int = usize32(a(0), a(1), a(2), a(3))

        def nextState(
            hasExtra: Boolean,
            commentsToSkip: Int,
            checkCrc16: Boolean
        )(
            using Frame
        ): (Chunk[Byte], CRC32, Unit < (Emit[Chunk[Byte]] & Ctx)) => Loop.Outcome[GunzipState, Unit] < (Resource & Sync) =
            (bytes: Chunk[Byte], headerCrc32: CRC32, emit: Unit < (Emit[Chunk[Byte]] & Ctx)) =>
                if hasExtra then
                    Sync(Loop.continue(GunzipState.ParseHeaderExtra(
                        bytes,
                        headerCrc32,
                        checkCrc16,
                        commentsToSkip,
                        emit
                    )))
                else if commentsToSkip > 0 then
                    Sync(Loop.continue(GunzipState.SkipComments(
                        bytes,
                        headerCrc32,
                        checkCrc16,
                        commentsToSkip,
                        emit
                    )))
                else if checkCrc16 then
                    Sync(Loop.continue(GunzipState.CheckCrc16(
                        bytes,
                        headerCrc32,
                        emit
                    )))
                else
                    Resource.acquireRelease(Sync {
                        val inflater     = new Inflater(true)
                        val contentCrc32 = new CRC32()
                        inflater -> contentCrc32
                    })((inflater, contentCrc32) =>
                        Sync {
                            inflater.end()
                            contentCrc32.reset()
                        }
                    )
                        .map { (inflater, contentCrc32) =>
                            Loop.continue(GunzipState.InflateInput(bytes, inflater, contentCrc32, emit))
                        }
        end nextState

        def emit(
            stream: Stream[Byte, Ctx],
            bufferSize: Int
        )(
            using
            Tag[Chunk[Byte]],
            Frame
        ): Unit < (Resource & Sync & Abort[StreamCompressionException] & Ctx & Emit[Chunk[Byte]]) =
            Loop(GunzipState.Initialize):
                case GunzipState.Initialize =>
                    Resource.acquireRelease(Sync(new CRC32()))(headerCrc32 => Sync(headerCrc32.reset()))
                        .map: headerCrc32 =>
                            Loop.continue(GunzipState.ParseHeader(Chunk.empty, headerCrc32, stream.emit))
                case GunzipState.ParseHeader(accBytes, headerCrc32, emit) =>
                    if accBytes.length < fixedHeaderLength then
                        Emit.runFirst(emit).map:
                            case Present(bytes) -> emitFn =>
                                Loop.continue(GunzipState.ParseHeader(accBytes.concat(bytes), headerCrc32, emitFn()))
                            case _ =>
                                if accBytes.isEmpty then
                                    // No data, we stop
                                    Loop.done
                                else
                                    // There are some data, invalid GZip header
                                    Abort
                                        .fail(new StreamCompressionException("Invalid GZip header"))
                                        .andThen(Loop.done)
                    else
                        val header = accBytes.take(fixedHeaderLength)
                        val rest   = accBytes.drop(fixedHeaderLength)
                        headerCrc32.update(toUnboxByteArray(header))
                        if (usize8(header(0)) != 0x1f) || (usize8(header(1)) != 0x8b) then
                            Abort.fail(new StreamCompressionException("Invalid GZip header"))
                                .andThen(Loop.done[GunzipState, Unit](()))
                        else if usize8(header(2)) != 0x8 then
                            Abort.fail(new StreamCompressionException(
                                s"Only deflate (8) compression method is supported, present: ${header(2)}"
                            ))
                                .andThen(Loop.done[GunzipState, Unit](()))
                        else
                            val flags           = usize8(header(3))
                            val checkCrc16      = (flags & 2) > 0
                            val hasExtra        = (flags & 4) > 0
                            val skipFileName    = (flags & 8) > 0
                            val skipFileComment = (flags & 16) > 0
                            val commentsToSkip  = (if skipFileName then 1 else 0) + (if skipFileComment then 1 else 0)
                            nextState(hasExtra, commentsToSkip, checkCrc16)(
                                if hasExtra then header.concat(rest) else rest,
                                headerCrc32,
                                emit
                            )
                        end if
                    end if
                case GunzipState.ParseHeaderExtra(accBytes, headerCrc32, checkCrc16, commentsToSkip, emit) =>
                    if accBytes.length < 12 then
                        Emit.runFirst(emit).map:
                            case Present(bytes) -> emitFn =>
                                Loop.continue(GunzipState.ParseHeaderExtra(
                                    accBytes.concat(bytes),
                                    headerCrc32,
                                    checkCrc16,
                                    commentsToSkip,
                                    emitFn()
                                ))
                            case _ =>
                                Abort
                                    .fail(new StreamCompressionException("Invalid GZip header"))
                                    .andThen(Loop.done)
                    else
                        val xLen                  = 2
                        val extraBytes            = usize16(accBytes(fixedHeaderLength), accBytes(fixedHeaderLength + 1))
                        val headerWithExtraLength = fixedHeaderLength + xLen + extraBytes
                        if accBytes.length < headerWithExtraLength then
                            Emit.runFirst(emit).map:
                                case Present(bytes) -> emitFn =>
                                    Loop.continue(GunzipState.ParseHeaderExtra(
                                        accBytes.concat(bytes),
                                        headerCrc32,
                                        checkCrc16,
                                        commentsToSkip,
                                        emitFn()
                                    ))
                                case _ =>
                                    Abort
                                        .fail(new StreamCompressionException("Invalid GZip header"))
                                        .andThen(Loop.done)
                        else
                            val headerWithExtra = accBytes.take(headerWithExtraLength)
                            val rest            = accBytes.drop(headerWithExtraLength)
                            headerCrc32.update(toUnboxByteArray(headerWithExtra.drop(fixedHeaderLength)))
                            nextState(false, commentsToSkip, checkCrc16)(
                                rest,
                                headerCrc32,
                                emit
                            )
                        end if
                    end if
                case GunzipState.SkipComments(bytes, headerCrc32, checkCrc16, commentsToSkip, emit) =>
                    val zeroIdx = bytes.indexOf(0)
                    if zeroIdx == -1 then
                        Emit.runFirst(emit).map:
                            case Present(nextBytes) -> emitFn =>
                                headerCrc32.update(toUnboxByteArray(bytes))
                                Loop.continue(GunzipState.SkipComments(
                                    nextBytes,
                                    headerCrc32,
                                    checkCrc16,
                                    commentsToSkip,
                                    emitFn()
                                ))
                            case _ =>
                                Abort
                                    .fail(new StreamCompressionException("Invalid GZip header"))
                                    .andThen(Loop.done)
                    else
                        val upToZero = bytes.take(zeroIdx + 1)
                        val rest     = bytes.drop(zeroIdx + 1)
                        headerCrc32.update(toUnboxByteArray(upToZero))
                        nextState(false, commentsToSkip - 1, checkCrc16)(
                            rest,
                            headerCrc32,
                            emit
                        )
                    end if
                case GunzipState.CheckCrc16(accBytes, headerCrc32, emit) =>
                    if accBytes.length < 2 then
                        Emit.runFirst(emit).map:
                            case Present(bytes) -> emitFn =>
                                Loop.continue(GunzipState.CheckCrc16(accBytes.concat(bytes), headerCrc32, emitFn()))
                            case _ =>
                                Abort
                                    .fail(new StreamCompressionException("Invalid GZip header"))
                                    .andThen(Loop.done)
                    else
                        val crc16Bytes    = accBytes.take(2)
                        val rest          = accBytes.drop(2)
                        val computedCrc16 = (headerCrc32.getValue & 0xffffL).toInt
                        val expectedCrc16 = usize16(crc16Bytes(0), crc16Bytes(1))
                        if computedCrc16 != expectedCrc16 then
                            Abort
                                .fail(new StreamCompressionException("Invalid GZip header crc16"))
                                .andThen(Loop.done)
                        else
                            nextState(false, 0, false)(
                                rest,
                                headerCrc32,
                                emit
                            )
                        end if
                    end if
                case GunzipState.InflateInput(leftOver, inflater, contentCrc32, emit) =>
                    if leftOver.isEmpty then
                        Emit.runFirst(emit).map:
                            case Present(bytes) -> emitFn =>
                                Sync(inflater.setInput(toUnboxByteArray(bytes)))
                                    .andThen(
                                        Loop.continue(GunzipState.PullInflater(inflater, contentCrc32, Present(emitFn), bytes))
                                    )
                            case _ =>
                                Loop.continue(GunzipState.PullInflater(inflater, contentCrc32, Absent, Chunk.empty))
                    else
                        Sync(inflater.setInput(toUnboxByteArray(leftOver)))
                            .andThen(
                                Loop.continue(GunzipState.PullInflater(inflater, contentCrc32, Present(() => emit), leftOver))
                            )
                    end if
                case GunzipState.PullInflater(inflater, contentCrc32, maybeEmitFn, bytes) =>
                    if inflater.finished then
                        val leftOver = inflater.getRemaining match
                            case remaining if remaining > 0 => bytes.takeRight(remaining)
                            case _                          => Chunk.empty[Byte]
                        Loop.continue(GunzipState.CheckTrailer(leftOver, inflater, contentCrc32, maybeEmitFn))
                    else if inflater.needsInput then
                        Emit.valueWith(Chunk.empty):
                            maybeEmitFn match
                                case Present(emitFn) =>
                                    Loop.continue(GunzipState.InflateInput(Chunk.empty, inflater, contentCrc32, emitFn()))
                                case Absent =>
                                    Loop.continue(GunzipState.CheckTrailer(Chunk.empty, inflater, contentCrc32, Absent))
                    else
                        Sync(new Array[Byte](bufferSize)).map: buffer =>
                            Abort
                                .catching[DataFormatException](dfe => StreamCompressionException(dfe))(inflater.inflate(buffer))
                                .map(read => fromUnboxByteArray(buffer, read))
                                .map(inflated =>
                                    Sync(contentCrc32.update(toUnboxByteArray(inflated))).andThen(
                                        Emit.valueWith(inflated)(
                                            Loop.continue(GunzipState.PullInflater(inflater, contentCrc32, maybeEmitFn, bytes))
                                        )
                                    )
                                )
                    end if
                case GunzipState.CheckTrailer(accBytes, inflater, contentCrc32, maybeEmitFn) =>
                    if accBytes.length < 8 then
                        maybeEmitFn match
                            case Present(emitFn) =>
                                Emit.runFirst(emitFn()).map:
                                    case Present(bytes) -> nextEmitFn =>
                                        Loop.continue(GunzipState.CheckTrailer(
                                            accBytes.concat(bytes),
                                            inflater,
                                            contentCrc32,
                                            Present(nextEmitFn)
                                        ))
                                    case _ =>
                                        Loop.continue(GunzipState.CheckTrailer(accBytes, inflater, contentCrc32, Absent))
                            case Absent =>
                                Abort.fail[StreamCompressionException](StreamCompressionException("Checksum error"))
                                    .andThen(Loop.done)
                    else
                        val trailerBytes  = accBytes.take(8)
                        val rest          = accBytes.drop(8)
                        val crc32         = readInt(toUnboxByteArray(trailerBytes.take(4)))
                        val isize         = readInt(toUnboxByteArray(trailerBytes.drop(4)))
                        val expectedCrc32 = contentCrc32.getValue()
                        val expectedIsize = inflater.getBytesWritten
                        if expectedCrc32.toInt != crc32 then
                            Abort
                                .fail(StreamCompressionException("Invalid CRC32"))
                                .andThen(Loop.done)
                        else if expectedIsize.toInt != isize then
                            Abort
                                .fail(StreamCompressionException("Invalid ISIZE"))
                                .andThen(Loop.done)
                        else
                            maybeEmitFn match
                                case Present(emitFn) =>
                                    Resource.acquireRelease(Sync(new CRC32()))(headerCrc32 => Sync(headerCrc32.reset()))
                                        .map: headerCrc32 =>
                                            Loop.continue(GunzipState.ParseHeader(rest, headerCrc32, emitFn()))
                                case Absent =>
                                    Emit.valueWith(rest)(Loop.done)
                        end if
                    end if
        end emit

        Stream(emit(stream, bufferSize))
    end gunzipStream

end StreamCompression
