package kyo

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kyo.internal.FileJournalCore
import kyo.internal.SegmentStore

/** [[FileChannel]]-backed [[SegmentStore]] for JVM and Native. Each call to `open` opens one
  * segment file for positioned read+write (no channel cursor). `acquireLock` acquires a
  * [[FileLock]] on a per-root `LOCK` file; the released lock closes both the `FileLock` and the
  * backing `FileChannel`. `syncDir` opens a directory read-only and forces it so newly created
  * children (stream directories, segment files) survive a crash on POSIX; on Windows the open
  * throws `IOException` and the call is silently skipped.
  */
final private class FileChannelStore extends SegmentStore:

    def open(path: Path)(using AllowUnsafe): SegmentStore.Handle =
        val ch = FileChannel.open(
            path.toJava,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )
        new SegmentStore.Handle:
            def readAt(pos: Long, len: Int)(using AllowUnsafe): Array[Byte] =
                val buf     = ByteBuffer.allocate(len)
                var filePos = pos
                var eof     = false
                while !eof && buf.hasRemaining do
                    val n = ch.read(buf, filePos)
                    if n <= 0 then eof = true
                    else filePos += n
                end while
                val actual = buf.position()
                if actual == len then buf.array()
                else java.util.Arrays.copyOf(buf.array(), actual)
            end readAt

            def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe): Unit =
                var remaining = ByteBuffer.wrap(bytes)
                var filePos   = pos
                while remaining.hasRemaining do
                    val n = ch.write(remaining, filePos)
                    filePos += n
                end while
            end writeAt

            def sync()(using AllowUnsafe): Unit               = discard(ch.force(false))
            def truncate(size: Long)(using AllowUnsafe): Unit = discard(ch.truncate(size))
            def size()(using AllowUnsafe): Long               = ch.size()
            def close()(using AllowUnsafe): Unit              = ch.close()
        end new
    end open

    def acquireLock(root: Path)(using AllowUnsafe, Frame): Result[JournalStorageError, SegmentStore.Lock] =
        val lockPath = root / "LOCK"
        val lockChannel =
            try
                FileChannel.open(
                    lockPath.toJava,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
                )
            catch
                case e: java.io.IOException =>
                    return Result.fail(JournalStorageError(s"Journal root '${root.unsafe.show}' lock failed", Present(e)))
        try
            val fl =
                try lockChannel.tryLock()
                catch
                    case e: OverlappingFileLockException =>
                        lockChannel.close()
                        return Result.fail(JournalStorageError(s"Journal root '${root.unsafe.show}' is locked", Present(e)))
                    case e: java.io.IOException =>
                        lockChannel.close()
                        return Result.fail(JournalStorageError(s"Journal root '${root.unsafe.show}' lock failed", Present(e)))
            if fl == null then
                lockChannel.close()
                Result.fail(JournalStorageError(s"Journal root '${root.unsafe.show}' is locked by another owner", Absent))
            else
                Result.succeed(new SegmentStore.Lock:
                    def release()(using AllowUnsafe): Unit =
                        discard(Result.catching[Throwable](fl.release()))
                        discard(Result.catching[Throwable](lockChannel.close())))
            end if
        catch
            case e: java.io.IOException =>
                lockChannel.close()
                Result.fail(JournalStorageError(s"Journal root '${root.unsafe.show}' lock failed", Present(e)))
        end try
    end acquireLock

    // Opens the directory as a FileChannel and forces it so a newly created child entry
    // (a stream directory or a segment file) becomes durable. On POSIX, the parent directory must
    // be fsync'd for a new child's link to survive a crash; FileChannel.force on the child alone
    // does not cover this. Windows cannot open a directory as a FileChannel; the open throws
    // IOException there, which is silently skipped (Windows makes the entry durable without an
    // explicit directory sync). A force failure on a successfully-opened directory is a genuine
    // durability fault and propagates to the caller.
    def syncDir(dir: Path)(using AllowUnsafe): Unit =
        val opened =
            try Maybe(FileChannel.open(dir.toJava, StandardOpenOption.READ))
            catch case _: java.io.IOException => Absent
        opened.foreach { ch =>
            try discard(ch.force(true))
            finally ch.close()
        }
    end syncDir

end FileChannelStore

/** Opens (or creates) a file-backed journal rooted at `dir`. Available on JVM and Native only.
  *
  * @see
  *   [[FileJournal.Config]] for the durability and rotation knobs
  * @see
  *   [[EventPayloadCodec]] for payload encoding strategies
  */
extension (backend: Journal.Backend.type)
    def file(
        dir: Path,
        config: FileJournal.Config = FileJournal.Config.default,
        payloadCodec: EventPayloadCodec = EventPayloadCodec.bytes
    )(using
        Frame
    )
        : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
        FileJournalCore.open(dir, config, new FileChannelStore, payloadCodec)
end extension
