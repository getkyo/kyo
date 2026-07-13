package kyo

import kyo.internal.BinarySegmentCodec

class JournalReaderTest extends kyo.test.Test[Any]:

    import BinarySegmentCodec.HeaderSize
    import BinarySegmentCodec.TerminatorSize
    import BinarySegmentCodec.recordSize
    import BinarySegmentCodec.segmentName

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private val sid = valid(StreamId("reader-1"))
    private def env(n: Int): EventEnvelope =
        EventEnvelope(valid(EventId(s"e-$n")), valid(EventType("T")), Span.from(s"p$n".getBytes("UTF-8")), EventMetadata.empty)

    private val e0End: Int = HeaderSize + recordSize(env(0)).toInt + TerminatorSize

    private def freshDir(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir("fj-reader"))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    private def appendClosed(dir: Path, batches: Seq[Chunk[EventEnvelope]], config: FileJournal.Config)(using
        Frame
    ): Unit < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Backend.file(dir, config)).map {
                case Result.Success(backend) =>
                    Kyo.foreach(batches.toList) { batch =>
                        Abort.run[JournalError](backend.append(sid, ExpectedOffset.Any, batch)).map {
                            case Result.Success(_)   => ()
                            case Result.Failure(err) => throw new AssertionError(s"append failed: $err")
                            case panic: Result.Panic => throw panic.exception
                        }
                    }.map(_ => ())
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            }
        }

    private def binarySegmentPath(dir: Path): Path = dir / "streams" / "reader-1" / segmentName(0L)

    private def jsonlSegmentPath(dir: Path): Path = dir / "streams" / "reader-1" / "00000000000000000000.jsonl"

    private def readSegmentBytes(segPath: Path)(using Frame): Array[Byte] < Sync =
        Sync.Unsafe.defer {
            segPath.unsafe.readBytes() match
                case Result.Success(span) => span.toArray
                case Result.Failure(e)    => throw e
        }

    private def writePrefix(segPath: Path, full: Array[Byte], length: Int)(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            segPath.unsafe.writeBytes(Span.from(full.take(length))) match
                case Result.Success(_) => ()
                case Result.Failure(e) => throw e
        }

    private def flipByte(segPath: Path, pos: Long)(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            val arr = (segPath.unsafe.readBytes() match
                case Result.Success(span) => span.toArray
                case Result.Failure(e)    => throw e
            )
            arr(pos.toInt) = (arr(pos.toInt) ^ 0xff).toByte
            segPath.unsafe.writeBytes(Span.from(arr)) match
                case Result.Success(_) => ()
                case Result.Failure(e) => throw e
        }

    private def readWithSyncReader(dir: Path, config: FileJournal.Config)(using Frame): Chunk[RecordedEvent] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Reader.file(dir, config)).map {
                case Result.Success(reader) =>
                    Abort.run[JournalError](reader.read(sid, StreamOffset.first, Int.MaxValue)).map {
                        case Result.Success(evs) => evs
                        case Result.Failure(err) => throw new AssertionError(s"unexpected read failure: $err")
                        case panic: Result.Panic => throw panic.exception
                    }
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            }
        }

    private def readWithAsyncReader(dir: Path, config: FileJournal.Config)(using Frame): Chunk[RecordedEvent] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Reader.fileAsync(dir, config)).map {
                case Result.Success(reader) =>
                    Abort.run[JournalError](reader.read(sid, StreamOffset.first, Int.MaxValue)).map {
                        case Result.Success(evs) => evs
                        case Result.Failure(err) => throw new AssertionError(s"unexpected read failure: $err")
                        case panic: Result.Panic => throw panic.exception
                    }
                case Result.Failure(err) => throw err
                case panic: Result.Panic => throw panic.exception
            }
        }

    private def readResultSync(dir: Path, config: FileJournal.Config)(using Frame): Result[JournalError, Chunk[RecordedEvent]] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Reader.file(dir, config)).map {
                case Result.Success(reader) => Abort.run[JournalError](reader.read(sid, StreamOffset.first, Int.MaxValue))
                case Result.Failure(err)    => throw err
                case panic: Result.Panic    => throw panic.exception
            }
        }

    private def readResultAsync(dir: Path, config: FileJournal.Config)(using Frame): Result[JournalError, Chunk[RecordedEvent]] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Reader.fileAsync(dir, config)).map {
                case Result.Success(reader) => Abort.run[JournalError](reader.read(sid, StreamOffset.first, Int.MaxValue))
                case Result.Failure(err)    => throw err
                case panic: Result.Panic    => throw panic.exception
            }
        }

    private val binaryConfig = FileJournal.Config.default
    private val jsonlConfig  = FileJournal.Config(format = FileJournal.SegmentFormat.Jsonl)

    "torn tail invisibility" - {
        def tornTailCase(
            label: String,
            config: FileJournal.Config,
            segPath: Path => Path,
            read: (Path, FileJournal.Config) => Chunk[RecordedEvent] < Async,
            cutLength: (Array[Byte]) => Int
        ): Unit =
            s"$label stops at the committed frontier when the active tail is torn" in {
                for
                    dir  <- freshDir
                    _    <- appendClosed(dir, Seq(Chunk(env(0)), Chunk(env(1))), config)
                    full <- readSegmentBytes(segPath(dir))
                    _    <- writePrefix(segPath(dir), full, cutLength(full))
                    evs  <- read(dir, config)
                yield assert(evs.map(_.offset.value) == List(0L))
            }
        end tornTailCase

        tornTailCase("sync binary reader", binaryConfig, binarySegmentPath, readWithSyncReader, _ => e0End)
        tornTailCase("async binary reader", binaryConfig, binarySegmentPath, readWithAsyncReader, _ => e0End)
        tornTailCase("sync jsonl reader", jsonlConfig, jsonlSegmentPath, readWithSyncReader, _.length - 1)
        tornTailCase("async jsonl reader", jsonlConfig, jsonlSegmentPath, readWithAsyncReader, _.length - 1)
    }

    "mid-file corruption" - {
        def corruptCase(
            label: String,
            config: FileJournal.Config,
            segPath: Path => Path,
            read: (Path, FileJournal.Config) => Result[JournalError, Chunk[RecordedEvent]] < Async
        ): Unit =
            s"$label fails with JournalCorruptedError on a CRC flip before the frontier" in {
                for
                    dir <- freshDir
                    _   <- appendClosed(dir, Seq(Chunk(env(0), env(1), env(2))), config)
                    _   <- flipByte(segPath(dir), (HeaderSize + 8 + 2).toLong)
                    res <- read(dir, config)
                yield
                    val isCorrupt = res match
                        case Result.Failure(_: JournalCorruptedError) => true
                        case _                                        => false
                    assert(isCorrupt)
            }
        end corruptCase

        corruptCase("sync binary reader", binaryConfig, binarySegmentPath, readResultSync)
        corruptCase("async binary reader", binaryConfig, binarySegmentPath, readResultAsync)
        corruptCase("sync jsonl reader", jsonlConfig, jsonlSegmentPath, readResultSync)
        corruptCase("async jsonl reader", jsonlConfig, jsonlSegmentPath, readResultAsync)
    }

end JournalReaderTest
