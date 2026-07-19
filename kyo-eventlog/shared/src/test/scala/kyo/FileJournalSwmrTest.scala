package kyo

import kyo.internal.BinarySegmentFormat
import kyo.internal.ClaimSeam
import kyo.internal.FileJournalCore
import kyo.internal.FlushStrategy
import kyo.internal.GroupCommitCoordinator
import kyo.internal.SegmentStore
import kyo.internal.StoreSeam

class FileJournalSwmrTest extends kyo.test.Test[Any]:

    import BinarySegmentFormat.TerminatorSize

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private val sid       = valid(Event.StreamId("swmr-1"))
    private val journalId = JournalId.validate("fj-swmr")(using Frame.internal).getOrElse(throw new AssertionError("valid journal id"))
    private def env(n: Int): Event.New =
        Event.New(valid(Event.Id(s"e-$n")), valid(Event.Type("T")), Span.from(s"payload-$n".getBytes("UTF-8")), Event.Metadata.empty)

    private def binaryConfiguration(options: FileJournal.Options)(using Frame) =
        for
            codecs        <- EventLogCodecs.bytes()
            configuration <- FileJournal.Binary.configuration(journalId, codecs, options)
        yield configuration

    private def freshDir(prefix: String)(using Frame): Path < (Sync & Scope) =
        Abort.run[FileException](Path.run(Path.tempDir(prefix))).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    private def gatedAsyncStore(
        inner: StoreSeam[Async],
        gate: Channel[Unit],
        holdNextWrite: AtomicRef.Unsafe[Boolean],
        terminatorSize: Int
    )(using AllowUnsafe): StoreSeam[Async] = new StoreSeam[Async]:
        def open(path: Path)(using Frame): StoreSeam.Handle[Async] < (Async & Abort[JournalStorageError]) =
            inner.open(path).map(wrapHandle)

        def acquireLock(root: Path)(using Frame): SegmentStore.Lock < (Sync & Abort[JournalStorageError]) =
            inner.acquireLock(root)

        def syncDir(dir: Path)(using Frame): Unit < Async =
            inner.syncDir(dir)

        private def wrapHandle(h: StoreSeam.Handle[Async]): StoreSeam.Handle[Async] = new StoreSeam.Handle[Async]:
            def readAt(pos: Long, len: Int)(using Frame): Array[Byte] < Async = h.readAt(pos, len)
            def writeAt(pos: Long, bytes: Array[Byte])(using Frame): Unit < Async =
                if holdNextWrite.get() && holdNextWrite.compareAndSet(true, false) then
                    val partial = bytes.take(bytes.length - terminatorSize)
                    val tail    = bytes.drop(partial.length)
                    h.writeAt(pos, partial).andThen(
                        Abort.run[Closed](gate.take).map {
                            case Result.Success(_)      => ()
                            case Result.Failure(closed) => throw new IllegalStateException(s"gate channel closed: $closed")
                            case Result.Panic(e)        => throw e
                        }
                    ).andThen(h.writeAt(pos + partial.length, tail))
                else h.writeAt(pos, bytes)
            def sync()(using Frame): Unit < Async               = h.sync()
            def truncate(size: Long)(using Frame): Unit < Async = h.truncate(size)
            def size()(using Frame): Long < Async               = h.size()
            def close()(using Frame): Unit < Async              = h.close()
        end wrapHandle
    end gatedAsyncStore

    "concurrent writer and reader" - {
        "the reader sees only the committed frontier while the writer is held mid-batch" in {
            import AllowUnsafe.embrace.danger
            val batch0 = Chunk(env(0))
            val batch1 = Chunk(env(1))
            for
                dir           <- freshDir("fj-swmr-live")
                configuration <- binaryConfiguration(FileJournal.Options(fsync = FileJournal.Fsync.Always))
                holdNextWrite <- Sync.Unsafe.defer(AtomicRef.Unsafe.init(false))
                gate          <- Channel.initUnscoped[Unit](1)
                writerReady   <- Channel.initUnscoped[Unit](1)
                batch1Go      <- Channel.initUnscoped[Unit](1)
                readerDone    <- Channel.initUnscoped[Chunk[Event.Recorded]](1)
                (seam, claim, flushFor) <- Sync.Unsafe.defer {
                    val coordinator = GroupCommitCoordinator.init
                    (
                        gatedAsyncStore(platformAsyncStore(), gate, holdNextWrite, TerminatorSize),
                        ClaimSeam.async(),
                        (fsync: FileJournal.Fsync) => FlushStrategy.groupCommit(fsync, coordinator)
                    )
                }
                writerFiber <- Fiber.initUnscoped(
                    Scope.run {
                        FileJournalCore.open(
                            dir,
                            configuration,
                            seam,
                            claim,
                            flushFor
                        ).map { backend =>
                            for
                                _ <- Abort.run[JournalError](backend.append(sid, ExpectedOffset.NoStream, batch0))
                                _ <- writerReady.put(())
                                _ <- batch1Go.take
                                _ <- Abort.run[JournalError](backend.append(sid, ExpectedOffset.Any, batch1))
                            yield ()
                        }
                    }
                )
                _ <- writerReady.take
                _ <- Sync.Unsafe.defer(holdNextWrite.set(true))
                _ <- batch1Go.put(())
                _ <- Fiber.initUnscoped(
                    Scope.run {
                        Abort.run[JournalStorageError](Journal.Reader.file(dir, configuration)).map {
                            case Result.Success(reader) =>
                                Abort.run[JournalError](reader.read(sid, Event.StreamOffset.first, Int.MaxValue)).map {
                                    case Result.Success(evs) => readerDone.put(evs)
                                    case Result.Failure(err) => throw new AssertionError(s"reader failed: $err")
                                    case panic: Result.Panic => throw panic.exception
                                }
                            case Result.Failure(err) => throw err
                            case panic: Result.Panic => throw panic.exception
                        }
                    }
                ).map(_.get)
                mid <- readerDone.take
                _   <- gate.put(())
                _   <- writerFiber.get
                after <- Scope.run {
                    Abort.run[JournalStorageError](Journal.Reader.file(dir, configuration)).map {
                        case Result.Success(reader) =>
                            Abort.run[JournalError](reader.read(sid, Event.StreamOffset.first, Int.MaxValue)).map {
                                case Result.Success(evs) => evs
                                case Result.Failure(err) => throw new AssertionError(s"reader failed: $err")
                                case panic: Result.Panic => throw panic.exception
                            }
                        case Result.Failure(err) => throw err
                        case panic: Result.Panic => throw panic.exception
                    }
                }
            yield
                assert(mid.map(_.offset.value) == List(0L))
                assert(after.map(_.offset.value) == List(0L, 1L))
            end for
        }
    }

    "rotation follow" - {
        "a reader following repeated reads sees every committed record across a rotation boundary" in {
            for
                dir           <- freshDir("fj-swmr-rot")
                configuration <- binaryConfiguration(FileJournal.Options(fsync = FileJournal.Fsync.Always, segmentSize = 256L.bytes))
                _ <- Scope.run {
                    Abort.run[JournalStorageError](Journal.Backend.file(dir, configuration)).map {
                        case Result.Success(backend) =>
                            Kyo.foreach(0 until 20)(n =>
                                Abort.run[JournalError](backend.append(sid, ExpectedOffset.Any, Chunk(env(n)))).map {
                                    case Result.Success(_)   => ()
                                    case Result.Failure(err) => throw new AssertionError(s"append failed: $err")
                                    case panic: Result.Panic => throw panic.exception
                                }
                            ).map(_ => ())
                        case Result.Failure(err) => throw err
                        case panic: Result.Panic => throw panic.exception
                    }
                }
                collected <- Loop.indexed(Chunk.empty[Event.Recorded], Event.StreamOffset.first) { (_, acc, from) =>
                    Scope.run {
                        Abort.run[JournalStorageError](Journal.Reader.file(dir, configuration)).map {
                            case Result.Success(reader) =>
                                Abort.run[JournalError](reader.read(sid, from, 100)).map {
                                    case Result.Success(chunk) =>
                                        if chunk.isEmpty then
                                            Loop.done[Chunk[Event.Recorded], Event.StreamOffset, Chunk[Event.Recorded]](acc)
                                        else
                                            val next = valid(Event.StreamOffset(chunk.last.offset.value + 1L))
                                            Loop.continue(acc ++ chunk, next)
                                    case Result.Failure(err) => throw new AssertionError(s"read failed: $err")
                                    case panic: Result.Panic => throw panic.exception
                                }
                            case Result.Failure(err) => throw err
                            case panic: Result.Panic => throw panic.exception
                        }
                    }
                }
            yield
                assert(collected.map(_.offset.value) == (0L until 20L).toList)
                assert(collected.map(e => new String(e.payload.toArray, "UTF-8")) == (0 until 20).map(n => s"payload-$n").toList)
            end for
        }
    }

end FileJournalSwmrTest
