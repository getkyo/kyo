package kyo

import kyo.internal.BinarySegmentCodec

class JournalReaderTest extends kyo.test.Test[Any]:

    import BinarySegmentCodec.HeaderSize
    import BinarySegmentCodec.TerminatorSize
    import BinarySegmentCodec.segmentName

    private val binaryCodec = new BinarySegmentCodec(EventLogCodecs.MetadataCodec(IonBinary()))

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private val sid       = valid(StreamId("reader-1"))
    private val journalId = JournalId.validate("fj-reader")(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))
    private def env(n: Int): EventEnvelope =
        EventEnvelope(valid(EventId(s"e-$n")), valid(EventType("T")), Span.from(s"p$n".getBytes("UTF-8")), EventMetadata.empty)

    // Unsafe: eagerly resolves the pure (no real Sync/IO) codec + configuration construction to a
    // plain value at class-init time; both factories can only fail on a genuine construction-time
    // misconfiguration, never on this fixed, valid, journalId/codecs pairing.
    private def evalPure[E, A](v: A < Abort[E])(using Frame, ConcreteTag[E]): A =
        Abort.run[E](v).eval match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new AssertionError(s"unexpected construction failure: $err")
            case panic: Result.Panic => throw panic.exception

    private val identityCodecs: EventLog.Codecs[Span[Byte]] =
        evalPure(EventLogCodecs.bytes())(using Frame.internal)

    private val binaryConfiguration: FileJournal.Configuration[Span[Byte], FileJournal.Binary] =
        evalPure(FileJournal.Binary.configuration(journalId, identityCodecs))(using Frame.internal)

    private val jsonlConfiguration: FileJournal.Configuration[Span[Byte], FileJournal.Jsonl] =
        evalPure(FileJournal.Jsonl.configuration(journalId, identityCodecs))(using Frame.internal)

    private val e0End: Int = HeaderSize + binaryCodec.recordSize(env(0)).toInt + TerminatorSize

    private def freshDir(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir("fj-reader"))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    private def appendClosed(dir: Path, batches: Seq[Chunk[EventEnvelope]], configuration: FileJournal.Configuration[Span[Byte], ?])(using
        Frame
    ): Unit < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Backend.file(dir, configuration)).map {
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

    private def readWithSyncReader(dir: Path, configuration: FileJournal.Configuration[Span[Byte], ?])(using
        Frame
    ): Chunk[RecordedEvent] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Reader.file(dir, configuration)).map {
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

    private def readResultSync(dir: Path, configuration: FileJournal.Configuration[Span[Byte], ?])(using
        Frame
    ): Result[JournalError, Chunk[RecordedEvent]] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Reader.file(dir, configuration)).map {
                case Result.Success(reader) => Abort.run[JournalError](reader.read(sid, StreamOffset.first, Int.MaxValue))
                case Result.Failure(err)    => throw err
                case panic: Result.Panic    => throw panic.exception
            }
        }

    "torn tail invisibility" - {
        def tornTailCase(
            label: String,
            configuration: FileJournal.Configuration[Span[Byte], ?],
            segPath: Path => Path,
            cutLength: (Array[Byte]) => Int
        ): Unit =
            s"$label stops at the committed frontier when the active tail is torn" in {
                for
                    dir  <- freshDir
                    _    <- appendClosed(dir, Seq(Chunk(env(0)), Chunk(env(1))), configuration)
                    full <- readSegmentBytes(segPath(dir))
                    _    <- writePrefix(segPath(dir), full, cutLength(full))
                    evs  <- readWithSyncReader(dir, configuration)
                yield assert(evs.map(_.offset.value) == List(0L))
            }
        end tornTailCase

        // Reader.fileAsync is not part of the locked surface (design/02-public-api.yaml locks only
        // Journal.Reader.file); no async-reader torn-tail cases exist on the public surface.
        tornTailCase("sync binary reader", binaryConfiguration, binarySegmentPath, _ => e0End)
        tornTailCase("sync jsonl reader", jsonlConfiguration, jsonlSegmentPath, _.length - 1)
    }

    "mid-file corruption" - {
        def corruptCase(
            label: String,
            configuration: FileJournal.Configuration[Span[Byte], ?],
            segPath: Path => Path
        ): Unit =
            s"$label fails with JournalCorruptedError on a CRC flip before the frontier" in {
                for
                    dir <- freshDir
                    _   <- appendClosed(dir, Seq(Chunk(env(0), env(1), env(2))), configuration)
                    _   <- flipByte(segPath(dir), (HeaderSize + 8 + 2).toLong)
                    res <- readResultSync(dir, configuration)
                yield
                    val isCorrupt = res match
                        case Result.Failure(_: JournalCorruptedError) => true
                        case _                                        => false
                    assert(isCorrupt)
            }
        end corruptCase

        // Reader.fileAsync is not part of the locked surface; no async-reader corruption cases
        // exist on the public surface.
        corruptCase("sync binary reader", binaryConfiguration, binarySegmentPath)
        corruptCase("sync jsonl reader", jsonlConfiguration, jsonlSegmentPath)
    }

end JournalReaderTest
