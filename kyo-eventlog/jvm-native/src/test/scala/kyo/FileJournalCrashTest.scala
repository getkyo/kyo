package kyo

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class FileJournalCrashTest extends kyo.test.Test[Any]:

    import SegmentCodec.HeaderSize
    import SegmentCodec.TerminatorSize
    import SegmentCodec.recordSize
    import SegmentCodec.segmentName

    private def valid[A](r: Result[JournalInvalidIdentifierError, A]): A =
        r.getOrElse(throw new AssertionError("valid identifier"))
    private val sid = valid(StreamId("crash-1"))
    private def env(n: Int): EventEnvelope =
        EventEnvelope(valid(EventId(s"e-$n")), valid(EventType("T")), Span.from(s"p$n".getBytes("UTF-8")), EventMetadata.empty)

    // Byte after the committed terminator of a single-event batch [e0]: the exhaustive sweep cuts from here
    // (e0 already durable) to EOF, so every cut drops only the torn trailing e1 batch.
    private val e0End: Int = HeaderSize + recordSize(env(0)).toInt + TerminatorSize

    // The single active segment file for `sid` (base offset 0; crash fixtures never rotate).
    private def segmentPath(dir: Path): Path = dir / "streams" / "crash-1" / segmentName(0L)

    // A fresh temp root; a temp-dir failure is test-infra breakage, surfaced as a defect.
    private def freshDir(using Frame): Path < Sync =
        Abort.run[FileFsException](Path.tempDir("fj-crash")).map {
            case Result.Success(d)   => d
            case Result.Failure(err) => throw err
            case panic: Result.Panic => throw panic.exception
        }

    // Append each batch through Backend.file in a fresh scope, then close it so the LOCK releases before any
    // reopen (no process kill, no sleep: the "crash" is a byte mutation between two scoped backends).
    private def appendClosed(dir: Path, batches: Seq[Chunk[EventEnvelope]])(using Frame): Unit < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Backend.file(dir)).map {
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

    // Reopen `dir` and read the whole stream (recovery runs on first touch). Returns the read Result so a
    // corruption case can assert the failure; open only acquires the LOCK, so an open failure is panicked on.
    private def readResult(dir: Path)(using Frame): Result[JournalError, Chunk[RecordedEvent]] < Async =
        Scope.run {
            Abort.run[JournalStorageError](Journal.Backend.file(dir)).map {
                case Result.Success(backend) => Abort.run[JournalError](backend.read(sid, StreamOffset.first, Int.MaxValue))
                case Result.Failure(err)     => throw err
                case panic: Result.Panic     => throw panic.exception
            }
        }

    private def readEvents(dir: Path)(using Frame): Chunk[RecordedEvent] < Async =
        readResult(dir).map {
            case Result.Success(evs) => evs
            case Result.Failure(err) => throw new AssertionError(s"unexpected read failure: $err")
            case panic: Result.Panic => throw panic.exception
        }

    // --- raw segment mutation (test-only; the safe tier cannot truncate or flip a chosen byte) ----------

    private def readSegmentBytes(dir: Path)(using Frame): Array[Byte] < Sync =
        // Unsafe: raw positional read of the whole segment for byte-level fixture inspection.
        Sync.Unsafe.defer {
            val ch = FileChannel.open(segmentPath(dir).toJava, StandardOpenOption.READ)
            try
                val buf = ByteBuffer.allocate(ch.size().toInt)
                discard(ch.read(buf, 0L))
                buf.array()
            finally ch.close()
            end try
        }

    // Truncate to `length` then rewrite the first `length` bytes of `full`, restoring the fixture even after
    // a prior reopen truncated the torn tail.
    private def writePrefix(dir: Path, full: Array[Byte], length: Int)(using Frame): Unit < Sync =
        // Unsafe: raw truncate + positional write to synthesize a partial (torn) segment.
        Sync.Unsafe.defer {
            val ch = FileChannel.open(segmentPath(dir).toJava, StandardOpenOption.WRITE)
            try
                ch.truncate(length.toLong)
                discard(ch.write(ByteBuffer.wrap(full, 0, length), 0L))
                discard(ch.force(true))
            finally ch.close()
            end try
        }

    private def flipByte(dir: Path, pos: Long)(using Frame): Unit < Sync =
        // Unsafe: raw positional read/write to corrupt one byte (mid-file CRC-failure fixture).
        Sync.Unsafe.defer {
            val ch = FileChannel.open(segmentPath(dir).toJava, StandardOpenOption.READ, StandardOpenOption.WRITE)
            try
                val one = ByteBuffer.allocate(1)
                discard(ch.read(one, pos))
                discard(ch.write(ByteBuffer.wrap(Array((one.get(0) ^ 0xff).toByte)), pos))
                discard(ch.force(true))
            finally ch.close()
            end try
        }

    private def overwriteVersion(dir: Path, v: Byte)(using Frame): Unit < Sync =
        // Unsafe: raw positional write of the header version byte (index 4) for the unknown-version fixture.
        Sync.Unsafe.defer {
            val ch = FileChannel.open(segmentPath(dir).toJava, StandardOpenOption.WRITE)
            try
                discard(ch.write(ByteBuffer.wrap(Array(v)), 4L))
                discard(ch.force(true))
            finally ch.close()
            end try
        }

    "tail recovery" - {
        "truncating the active segment at every byte offset keeps all prior acknowledged events" in {
            // Write two single-event batches [e0], [e1]. For every cut from the byte after e0's committed
            // terminator up to the byte before EOF, e1's batch is torn (its terminator is incomplete) so the read
            // yields exactly [e0]; at the full length both survive. Exhaustive over the final record +
            // terminator byte range, deterministic (no sleeps; the crash is a byte truncation).
            for
                dir  <- freshDir
                _    <- appendClosed(dir, Seq(Chunk(env(0)), Chunk(env(1))))
                full <- readSegmentBytes(dir)
                torn <- Kyo.foreach((e0End until full.length).toList) { cut =>
                    for
                        _   <- writePrefix(dir, full, cut)
                        evs <- readEvents(dir)
                    yield evs.map(_.offset.value)
                }
                _    <- writePrefix(dir, full, full.length)
                both <- readEvents(dir)
            yield
                assert(torn.forall(_ == List(0L)))
                assert(both.map(_.offset.value) == List(0L, 1L))
        }
        "emits a WARN naming the segment and truncated byte range" in {
            import AllowUnsafe.embrace.danger
            val captured = AtomicRef.Unsafe.init(List.empty[String])
            val sink = new Log.Unsafe:
                val level                                                          = Log.Level.warn
                val name                                                           = "test"
                def withName(n: String)                                            = this
                private def rec(p: String, m: => String)(using AllowUnsafe)        = discard(captured.getAndUpdate(s"$p:$m" :: _))
                def trace(m: => String)(using Frame, AllowUnsafe)                  = rec("trace", m)
                def trace(m: => String, t: => Throwable)(using Frame, AllowUnsafe) = rec("trace", m)
                def debug(m: => String)(using Frame, AllowUnsafe)                  = rec("debug", m)
                def debug(m: => String, t: => Throwable)(using Frame, AllowUnsafe) = rec("debug", m)
                def info(m: => String)(using Frame, AllowUnsafe)                   = rec("info", m)
                def info(m: => String, t: => Throwable)(using Frame, AllowUnsafe)  = rec("info", m)
                def warn(m: => String)(using Frame, AllowUnsafe)                   = rec("warn", m)
                def warn(m: => String, t: => Throwable)(using Frame, AllowUnsafe)  = rec("warn", m)
                def error(m: => String)(using Frame, AllowUnsafe)                  = rec("error", m)
                def error(m: => String, t: => Throwable)(using Frame, AllowUnsafe) = rec("error", m)
            // Build a durable [e0] and a torn trailing [e1] (drop e1's last terminator byte); reopen under
            // Log.let(Log(sink)) so first-touch recovery truncates the torn tail and WARNs.
            for
                dir  <- freshDir
                _    <- appendClosed(dir, Seq(Chunk(env(0)), Chunk(env(1))))
                full <- readSegmentBytes(dir)
                _    <- writePrefix(dir, full, full.length - 1)
                res  <- Log.let(Log(sink))(readResult(dir))
            yield
                val events = res.getOrElse(throw new AssertionError(s"recovery read failed: $res"))
                val warns  = captured.get().filter(_.startsWith("warn:"))
                assert(events.map(_.offset.value) == List(0L))
                assert(warns.exists(w => w.contains(".seg") && w.contains("byte")))
            end for
        }
    }

    "multi-record batch atomicity" - {
        "a torn 3-event batch yields zero of its events and leaves prior state intact" in {
            // Write [e0] (durable) then a 3-event batch [e1,e2,e3]; truncate one byte before the batch
            // terminator so the whole batch is torn; reopen; the read yields exactly [e0].
            for
                dir  <- freshDir
                _    <- appendClosed(dir, Seq(Chunk(env(0)), Chunk(env(1), env(2), env(3))))
                full <- readSegmentBytes(dir)
                _    <- writePrefix(dir, full, full.length - TerminatorSize - 1)
                evs  <- readEvents(dir)
            yield assert(evs.map(_.offset.value) == List(0L))
        }
    }

    "corruption" - {
        "a mid-file CRC failure is fatal and the segment is byte-unchanged" in {
            // Write [e0,e1,e2] in one batch; flip a body byte of e0 (a non-tail record, followed by a valid
            // terminator) so recovery must treat it as mid-file damage, not a torn tail: fatal, no truncate.
            for
                dir       <- freshDir
                _         <- appendClosed(dir, Seq(Chunk(env(0), env(1), env(2))))
                _         <- flipByte(dir, (HeaderSize + 8 + 2).toLong)
                corrupted <- readSegmentBytes(dir)
                res       <- readResult(dir)
                after     <- readSegmentBytes(dir)
            yield
                val isCorrupt = res match
                    case Result.Failure(_: JournalCorruptedError) => true
                    case _                                        => false
                assert(isCorrupt)
                assert(java.util.Arrays.equals(corrupted, after)) // reopen did not modify the file
        }
        "the corruption error names the failing segment and byte offset" in {
            for
                dir <- freshDir
                _   <- appendClosed(dir, Seq(Chunk(env(0), env(1), env(2))))
                _   <- flipByte(dir, (HeaderSize + 8 + 2).toLong)
                res <- readResult(dir)
            yield
                val detail = res match
                    case Result.Failure(c: JournalCorruptedError) => c.detail
                    case other                                    => throw new AssertionError(s"expected corruption, got $other")
                assert(detail.contains(".seg"))
                assert(detail.contains("byte")) // names the failing segment and the offending byte offset
        }
        "a corrupt record length prefix is fatal, not a crash" in {
            // Flip the most significant byte of the first record's 4-byte length prefix in a 3-event batch, whose
            // single committed terminator survives after it, so recovery classifies the damage as mid-file rather
            // than a torn tail. The length field is deliberately not CRC-covered, so the flip reaches the decoder
            // unfiltered and turns the length huge/negative. Without a bound check on the length, ByteBuffer.allocate
            // throws (NegativeArraySize / IllegalArgument / OutOfMemory) and escapes as a panic; the fix resolves it
            // to JournalCorruptedError instead.
            for
                dir <- freshDir
                _   <- appendClosed(dir, Seq(Chunk(env(0), env(1), env(2))))
                _   <- flipByte(dir, HeaderSize.toLong) // MSB of record e0's length prefix (frame starts at HeaderSize)
                res <- readResult(dir)
            yield
                val isCorrupt = res match
                    case Result.Failure(_: JournalCorruptedError) => true
                    case _                                        => false
                assert(isCorrupt)
        }
        "an unknown version byte is fatal with streamId absent" in {
            // Write a valid stream, overwrite the header version byte with 0x02; on first touch validateHeader
            // fails before any record parse, so the error is header-level with an absent streamId.
            for
                dir <- freshDir
                _   <- appendClosed(dir, Seq(Chunk(env(0))))
                _   <- overwriteVersion(dir, 0x02.toByte)
                res <- readResult(dir)
            yield
                val headerLevel = res match
                    case Result.Failure(c: JournalCorruptedError) => c.streamId == Absent
                    case _                                        => false
                assert(headerLevel)
        }
        "an unknown metadata version byte is fatal" in {
            // Construct a raw segment whose single record carries metadata version byte 0x02 (an
            // unknown future codec). The record CRC is valid, so the failure must originate in
            // decodeMetadata, not in CRC verification. Without the fix, decodeMetadata silently
            // returns EventMetadata.empty and the read succeeds; with the fix it fails and rebuild
            // surfaces a JournalCorruptedError.
            for
                dir <- freshDir
                _   <- appendClosed(dir, Seq(Chunk(env(0)))) // creates streams/crash-1/ and the segment
                _   <-
                    // Unsafe: raw byte-level segment rewrite to plant an unknown metadata version fixture.
                    Sync.Unsafe.defer {
                        val badMeta = Array[Byte](0x02.toByte) // unknown future version, no body
                        val rec     = SegmentCodec.encodeRecord(0L, "e-0", "T", badMeta, "p0".getBytes("UTF-8"))
                        val term    = SegmentCodec.encodeTerminator(1)
                        val total   = SegmentCodec.HeaderSize + rec.length + term.length
                        val seg     = ByteBuffer.allocate(total)
                        discard(seg.put(SegmentCodec.SegmentHeader))
                        discard(seg.put(rec))
                        discard(seg.put(term))
                        discard(seg.flip())
                        val ch = FileChannel.open(segmentPath(dir).toJava, StandardOpenOption.WRITE)
                        try
                            ch.truncate(total.toLong)
                            discard(ch.write(seg, 0L))
                            discard(ch.force(true))
                        finally ch.close()
                        end try
                    }
                res <- readResult(dir)
            yield
                val isCorrupt = res match
                    case Result.Failure(_: JournalCorruptedError) => true
                    case _                                        => false
                assert(isCorrupt)
        }
    }
end FileJournalCrashTest
