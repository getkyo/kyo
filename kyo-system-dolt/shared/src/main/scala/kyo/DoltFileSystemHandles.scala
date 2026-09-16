package kyo

import kyo.internal.dolt.DoltVfsStore

/** The handle and channel types [[DoltFileSystem]] vends.
  *
  * Every method on the `Path` handle contracts is synchronous and takes `AllowUnsafe`, so a database-backed handle has
  * no effect to run a query in. Reads therefore carry content fetched before the handle existed, writes carry only a
  * cursor, and the entry removal `close()` cannot perform goes onto a queue the filesystem drains.
  */

/** A read handle over content already in memory. */
final private[kyo] class MaterializedReadHandle(content: Array[Byte]) extends Path.ReadHandle:

    private var cursor: Int = 0

    def readChunk(buffer: Array[Byte])(using AllowUnsafe): Path.ReadResult =
        if cursor >= content.length then Path.ReadResult.Eof
        else
            val n = math.min(buffer.length, content.length - cursor)
            java.lang.System.arraycopy(content, cursor, buffer, 0, n)
            cursor += n
            Path.ReadResult(n)

    def position(offset: Long)(using AllowUnsafe): Unit =
        cursor = math.max(0L, math.min(offset, content.length.toLong)).toInt

    /** The size captured at open, so a later write through the path does not change what this handle reports. */
    def size()(using AllowUnsafe, Frame): Result[FileReadException, Long] = Result.succeed(content.length.toLong)

    def readLong()(using AllowUnsafe): Long = Path.ReadHandle.parseLeadingLong(content, content.length)

    def close()(using AllowUnsafe): Unit = ()

end MaterializedReadHandle

/** A line reader over lines already split. */
final private[kyo] class MaterializedLineReadHandle(lines: Chunk[String]) extends Path.LineReadHandle:

    private var cursor: Int = 0

    def readLine()(using AllowUnsafe): Maybe[String] =
        if cursor >= lines.size then Absent
        else
            val line = lines(cursor)
            cursor += 1
            Present(line)

    def close()(using AllowUnsafe): Unit = ()

end MaterializedLineReadHandle

/** A walker over paths already gathered. */
final private[kyo] class MaterializedWalkHandle(paths: Chunk[Path]) extends Path.WalkHandle:

    private var cursor: Int = 0

    def next()(using AllowUnsafe): Maybe[Path] =
        if cursor >= paths.size then Absent
        else
            val path = paths(cursor)
            cursor += 1
            Present(path)

    def close()(using AllowUnsafe): Unit = ()

end MaterializedWalkHandle

/** A write handle that carries a cursor and nothing else. The bytes go to the database through
  * `DoltFileSystem.writeChunk`; `close()` without a prior `finish()` must remove the partial entry and cannot, since
  * it takes no effect, so it hands the path to `pending` for the filesystem to drain.
  */
final private[kyo] class DoltWriteHandle(
    val path: Path,
    cursor: AtomicLong.Unsafe,
    pending: AtomicRef.Unsafe[Chunk[Path]]
) extends Path.WriteHandle:

    private val finished: AtomicBoolean.Unsafe =
        // Unsafe: a flag read only by close, which is itself an unsafe synchronous callback
        import AllowUnsafe.embrace.danger
        AtomicBoolean.Unsafe.init(false)
    end finished

    /** Reserves `length` bytes and answers the offset they start at, so concurrent chunks cannot overlap. */
    def claimRange(length: Long)(using AllowUnsafe): Long = cursor.getAndAdd(length)

    /** Always fails. Content reaches the database through the filesystem's effectful `writeChunk`, so a direct call
      * here has nowhere to send bytes.
      */
    def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        Result.fail(FileIOException(path, FileSystemOperation.Write, DoltWriteHandle.DirectWrite(path)))

    def writeString(s: String, charset: java.nio.charset.Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
        Result.fail(FileIOException(path, FileSystemOperation.Write, DoltWriteHandle.DirectWrite(path)))

    def finish()(using AllowUnsafe): Unit = finished.set(true)

    def close()(using AllowUnsafe): Unit =
        if !finished.get() then discard(pending.updateAndGet(_.append(path)))

end DoltWriteHandle

private[kyo] object DoltWriteHandle:
    final case class DirectWrite(path: Path)
        extends RuntimeException(s"Write to $path must go through the filesystem, which is where the effect lives")

/** A scope-managed temporary directory. Removal is queued for the reason given on [[DoltWriteHandle]]. */
final private[kyo] class DoltTempDirHandle(val path: Path, pending: AtomicRef.Unsafe[Chunk[Path]]) extends Path.TempDirHandle:
    def remove()(using AllowUnsafe): Unit = discard(pending.updateAndGet(_.append(path)))

/** A scope-managed temporary file. Removal is queued for the reason given on [[DoltWriteHandle]]. */
final private[kyo] class DoltTempFileHandle(val path: Path, pending: AtomicRef.Unsafe[Chunk[Path]]) extends Path.TempFileHandle:
    def remove()(using AllowUnsafe): Unit = discard(pending.updateAndGet(_.append(path)))

/** A positioned channel over a stored file. Holds no content: the channel contract, unlike the handle contract,
  * carries the backend's effect in its own signatures, so every read and write is a query against the blocks it
  * overlaps. The `open` flag is what makes an operation after scope exit fail rather than quietly keep working against
  * storage that is still reachable.
  */
final private[kyo] class DoltChannel(
    path: Path,
    store: DoltVfsStore,
    open: AtomicBoolean.Unsafe
) extends Path.ReadWriteChannel[Async]:

    private def whileOpen[A, E](onClosed: => A < Abort[E])(body: => A < (Async & Abort[E]))(using Frame): A < (Async & Abort[E]) =
        Sync.Unsafe.defer(open.get()).map(isOpen => if isOpen then body else onClosed)

    private def node(using Frame): Long < (Async & Abort[FileReadException]) =
        store.node(path).map {
            case Present(n) => n.sizeBytes
            case Absent     => Abort.fail(FileNotFoundException(path))
        }

    def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (Async & Abort[FileReadException]) =
        whileOpen[Span[Byte], FileReadException](Abort.fail(FileNotFoundException(path))) {
            node.map(size => store.readRange(path, size, position, length))
        }

    def size(using Frame): Long < (Async & Abort[FileReadException]) =
        whileOpen[Long, FileReadException](Abort.fail(FileNotFoundException(path)))(node)

    def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Async & Abort[FileWriteException]) =
        whileOpen[Unit, FileWriteException](Abort.fail(FileNotFoundException(path))) {
            Abort.recover[FileReadException](e => Abort.fail(FileIOException(path, FileSystemOperation.Channel, e)))(node).map { size =>
                Clock.now.map(at => store.writeAt(path, size, position, bytes, at.toDuration.toMillis))
            }
        }

    /** Nothing is buffered, so there is nothing to flush: every write is already a committed statement. */
    def sync(metadata: Boolean)(using Frame): Unit < (Async & Abort[FileWriteException]) = ()

    def truncate(size: Long)(using Frame): Unit < (Async & Abort[FileWriteException]) =
        whileOpen[Unit, FileWriteException](Abort.fail(FileNotFoundException(path))) {
            Abort.recover[FileReadException](e => Abort.fail(FileIOException(path, FileSystemOperation.Channel, e)))(node).map { current =>
                Clock.now.map(at => store.truncate(path, current, size, at.toDuration.toMillis))
            }
        }

    def close(using Frame): Unit < Sync = Sync.Unsafe.defer(open.set(false))

end DoltChannel

/** One holder's claim on a path's advisory lock. The claim itself lives in the filesystem's map rather than in this
  * object, so releasing through a stale handle and releasing through a live one are the same operation.
  */
final private[kyo] class DoltLock(
    path: Path,
    val mode: Path.LockMode,
    private[kyo] val ownership: Path.LockOwnership,
    owner: DoltFileSystem
) extends Path.Lock:

    def check(using Frame): Unit < (Sync & Abort[FileLockException]) =
        Sync.Unsafe.defer(owner.holds(path, ownership)).map { held =>
            if held then () else Abort.fail(FileLockOwnershipLostException(path))
        }

    /** Releases this handle's claim. A foreign token fails and the real claim survives, otherwise an advisory lock
      * would be releasable by anyone holding a reference. The owner's own token is idempotent, because a scope
      * finalizer runs after an explicit release and must not turn that into an error.
      */
    private[kyo] def release(ownership: Path.LockOwnership)(using Frame): Unit < (Sync & Abort[FileLockException]) =
        if ownership != this.ownership then Abort.fail(FileLockOwnershipLostException(path))
        else Sync.Unsafe.defer(discard(owner.surrender(path, ownership)))

end DoltLock
