package kyo.internal

import java.io.IOException
import java.util.UUID
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import scala.util.control.NonFatal

/** Private host directory for recovery records containing file contents.
  *
  * Native creation installs restrictive access controls before the directory is visible.
  * Inherited ACLs cannot expose records subsequently written inside it. Acquisition
  * retries only exclusive-create collisions and never removes a colliding entry.
  */
private[kyo] object DurableDirectory:
    def ensure(path: Path)(using Frame): Unit < (Sync & Abort[FileStructureException]) =
        DurableFileChannel.nativePath(path).map { nativePath =>
            // Unsafe: native verification reads only the directory acquired by this call.
            Sync.Unsafe.defer {
                val result =
                    try
                        val bindings = Ffi.load[DurableFileBindings]
                        Buffer.use[Int, Result[FileStructureException, Unit]](2) { error =>
                            val created  = bindings.kyo_durable_mkdir(nativePath, error) == 0
                            val verified = !created && error.get(0) == 1 && bindings.kyo_durable_verify_directory(nativePath, error) == 0
                            if created || verified then Result.unit
                            else Result.fail(failure(path, error))
                        }
                    catch
                        // Includes first and repeated access to a failed generated JVM binding initializer.
                        case error: LinkageError => Result.fail(FileIOException(path, FileSystemOperation.Create, error))
                        case NonFatal(error)     => Result.fail(FileIOException(path, FileSystemOperation.Create, error))
                Abort.get(result)
            }
        }

    private def failure(path: Path, error: Buffer[Int])(using Frame, AllowUnsafe): FileStructureException =
        error.get(0) match
            case 1 => FileAlreadyExistsException(path)
            case 2 => FileAccessDeniedException(path)
            case 3 => FileNotADirectoryException(path)
            case _ => FileIOException(
                    path,
                    FileSystemOperation.Create,
                    new IOException(s"Native private directory operation failed with OS error ${error.get(1)}")
                )

    def create(parent: Path, prefix: String)(using Frame): Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
        if prefix.exists(c => c == '/' || c == '\\' || c == 0.toChar) then
            Abort.fail(FileInvalidPathException(prefix, FileSystemOperation.Create))
        else
            def attempt: Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
                Sync.defer(parent / s"$prefix-${UUID.randomUUID()}").map { directory =>
                    DurableFileChannel.nativePath(directory).map { nativePath =>
                        // Unsafe: exclusive native creation and ownership transfer share one effect node.
                        Sync.Unsafe.defer {
                            val result =
                                try
                                    val bindings = Ffi.load[DurableFileBindings]
                                    Buffer.use[Int, Result[FileStructureException, Path.TempDirHandle]](2) { error =>
                                        if bindings.kyo_durable_mkdir(nativePath, error) == 0 then
                                            Result.succeed(new Path.TempDirHandle:
                                                def path: Path = directory
                                                // Unsafe: cleanup belongs to this exclusively created directory.
                                                def remove()(using AllowUnsafe): Unit = directory.unsafe.removeAll().getOrThrow)
                                        else
                                            Result.fail(failure(directory, error))
                                    }
                                catch
                                    case error: LinkageError => Result.fail(FileIOException(directory, FileSystemOperation.Create, error))
                                    case NonFatal(error)     => Result.fail(FileIOException(directory, FileSystemOperation.Create, error))
                            result match
                                case Result.Failure(_: FileAlreadyExistsException) => attempt
                                case other                                         => Abort.get(other)
                        }
                    }
                }
            attempt
    end create
end DurableDirectory
