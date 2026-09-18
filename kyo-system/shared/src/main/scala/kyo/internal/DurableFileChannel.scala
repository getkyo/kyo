package kyo.internal

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Private write channel for an exclusively acquired replacement file.
  *
  * Acquisition transfers cleanup ownership before returning to the effect interpreter.
  * The generic replacement protocol performs the final atomic move only after this
  * channel has restored the destination's access controls, synchronized, and closed.
  * A failed acquisition never changes the destination.
  */
private[kyo] object DurableFileChannel:
    def validatePaths(target: Path, temporary: Path)(using Frame): Unit < Abort[FileInvalidPathException] =
        nativePath(target).andThen(nativePath(temporary)).unit

    private[kyo] def nativePath(path: Path)(using Frame): String < Abort[FileInvalidPathException] =
        path.unsafe.hostPath match
            case Present(value) if validEncoding(value) => value
            case _ => Abort.fail(FileInvalidPathException(path.unsafe.show, FileSystemOperation.Channel))

    private def validEncoding(value: String): Boolean =
        @tailrec def loop(index: Int): Boolean =
            if index == value.length then true
            else
                val character = value.charAt(index)
                if character == 0.toChar then false
                else if Character.isHighSurrogate(character) then
                    if index + 1 < value.length && Character.isLowSurrogate(value.charAt(index + 1)) then loop(index + 2)
                    else false
                else if Character.isLowSurrogate(character) then false
                else loop(index + 1)
                end if
        loop(0)
    end validEncoding

    def open(target: Path, temporary: Path, onAcquire: Path.ChannelCloseHandle => Unit)(using
        Frame
    ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
        (Sync & Abort[FileWriteException | FileStructureException]) =
        nativePath(target).map { targetPath =>
            nativePath(temporary).map { temporaryPath =>
                // Unsafe: native acquisition and cleanup ownership transfer must share one effect node.
                Sync.Unsafe.defer {
                    val acquired =
                        try
                            val bindings = Ffi.load[DurableFileBindings]
                            Buffer.use[Int, Result[FileWriteException | FileStructureException, Channel]](2) { error =>
                                val handle = bindings.kyo_durable_open(targetPath, temporaryPath, error)
                                if handle != 0L then Result.succeed(new Channel(temporary, bindings, handle))
                                else
                                    val failure: FileWriteException | FileStructureException = error.get(0) match
                                        case 1 => FileAlreadyExistsException(temporary)
                                        case 2 => FileAccessDeniedException(target)
                                        case 3 => FileNotADirectoryException(temporary.parent.getOrElse(Path()))
                                        case _ => nativeFailure(target, FileSystemOperation.Channel, error.get(1))
                                    Result.fail(failure)
                                end if
                            }
                        catch
                            // A generated JVM binding can report an unloadable library through static initialization.
                            case error: LinkageError => Result.fail(FileIOException(target, FileSystemOperation.Channel, error))
                            case NonFatal(error)     => Result.fail(FileIOException(target, FileSystemOperation.Channel, error))
                    Abort.get(acquired.map { channel =>
                        onAcquire(channel)
                        (channel, () => Sync.Unsafe.defer(channel.close()), channel)
                    })
                }
            }
        }

    private def nativeFailure(path: Path, operation: FileSystemOperation, code: Int)(using Frame): FileIOException =
        FileIOException(path, operation, new IOException(s"Native durable file operation failed with OS error $code"))

    final private class Channel(path: Path, bindings: DurableFileBindings, initial: Long)
        extends Path.ChannelCloseHandle with Path.WriteChannel[Sync]:
        private val handle = new AtomicLong(initial)

        private def invoke(f: Long => Int)(using Frame, AllowUnsafe): Result[FileWriteException, Unit] =
            val value = handle.get()
            if value == 0L then Result.fail(FileIOException(path, FileSystemOperation.Channel, new IOException("Channel is closed")))
            else
                val code = f(value)
                if code == 0 then Result.unit
                else Result.fail(nativeFailure(path, FileSystemOperation.Write, code))
            end if
        end invoke

        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: the buffer and native session remain owned throughout this write.
            Sync.Unsafe.defer {
                if position < 0L then
                    Abort.fail(FileIOException(path, FileSystemOperation.Write, new IOException("Negative write position")))
                else
                    val buffer = Buffer.fromArray(bytes.toArray)
                    try Abort.get(invoke(bindings.kyo_durable_write(_, position, buffer, bytes.size)))
                    finally buffer.close()
            }

        def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: permission restoration and verification precede the native data and metadata flush.
            Sync.Unsafe.defer(Abort.get(invoke(bindings.kyo_durable_sync(_))))

        def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            // Unsafe: operates only on this session's exclusively acquired descriptor.
            Sync.Unsafe.defer {
                if size < 0L then Abort.fail(FileIOException(path, FileSystemOperation.Write, new IOException("Negative truncate size")))
                else Abort.get(invoke(bindings.kyo_durable_truncate(_, size)))
            }

        def close()(using AllowUnsafe): Unit =
            val value = handle.getAndSet(0L)
            if value != 0L then
                val code = bindings.kyo_durable_close(value)
                if code != 0 then throw new IOException(s"Failed to close durable temporary $path: OS error $code")
        end close
    end Channel
end DurableFileChannel
