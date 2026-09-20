package kyo.internal

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.StandardOpenOption
import kyo.discard
import scalanative.libc.LibcExt.strError
import scalanative.meta.LinktimeInfo.isWindows
import scalanative.posix.errno.EACCES
import scalanative.posix.errno.EEXIST
import scalanative.posix.errno.ENOENT
import scalanative.posix.errno.ENOTDIR
import scalanative.posix.errno.errno
import scalanative.posix.fcntl
import scalanative.posix.unistd
import scalanative.unsafe.Zone
import scalanative.unsafe.toCString
import scalanative.unsafe.toCWideStringUTF16LE
import scalanative.unsigned.*
import scalanative.windows.ErrorCodes
import scalanative.windows.ErrorHandlingApi.GetLastError
import scalanative.windows.ErrorHandlingApiOps.errorMessage
import scalanative.windows.FileApi.CreateFileW
import scalanative.windows.FileApiExt.CREATE_NEW
import scalanative.windows.FileApiExt.FILE_ATTRIBUTE_NORMAL
import scalanative.windows.FileApiExt.FILE_SHARE_ALL
import scalanative.windows.HandleApi.CloseHandle
import scalanative.windows.HandleApi.Handle
import scalanative.windows.HandleApiExt.INVALID_HANDLE_VALUE
import scalanative.windows.winnt.AccessRights.FILE_GENERIC_WRITE

private[kyo] object NioExclusiveCreatePlatform:

    // Handle is an opaque pointer alias, so strict equality has no instance for it and the
    // INVALID_HANDLE_VALUE check below will not compile without one.
    private given CanEqual[Handle, Handle] = CanEqual.derived

    /** Claims `jpath` exclusively and returns the options the follow-up `FileChannel.open` must use.
      *
      * The claim happens here rather than in the open, because javalib's `CREATE_NEW` does not exclude. It reaches the filesystem through
      * three stacked exists-checks (`FileChannel.open` to `Files.createFile` to `FileHelpers.createNewFile`) and lands on `fopen(path, "w")`
      * on POSIX and `CREATE_ALWAYS` on Windows. Neither carries `O_EXCL`, and both truncate, so two creators that interleave anywhere in
      * that stack both succeed and the first one's content is destroyed. Measured on linux-arm64 at 2 bad rounds in 3000 rounds of 32
      * contending creators, so 96000 creations.
      *
      * `O_EXCL` (`CREATE_NEW` on Windows) gives the exclusion in one syscall. The descriptor is closed immediately: this call exists to win
      * the race, and the caller reopens the file it provably created. The window between the two is not closed: another process that
      * deletes the file there turns the reopen into a `NoSuchFileException`, and one that replaces it with a symlink is followed silently.
      * Holding the descriptor would close it, but `java.io.FileDescriptor`'s int constructor and `FileChannelImpl` are `private[java]`, so
      * it takes a shim compiled into those packages rather than anything this file can reach.
      *
      * Throws `FileAlreadyExistsException` when the path already exists.
      */
    private[kyo] def claimExclusively(jpath: java.nio.file.Path): Array[StandardOpenOption] =
        Zone.acquire { implicit zone =>
            val absolute = jpath.toAbsolutePath.toString
            if isWindows then
                val handle = CreateFileW(
                    toCWideStringUTF16LE(absolute),
                    desiredAccess = FILE_GENERIC_WRITE,
                    shareMode = FILE_SHARE_ALL,
                    securityAttributes = null,
                    creationDisposition = CREATE_NEW,
                    flagsAndAttributes = FILE_ATTRIBUTE_NORMAL,
                    templateFile = null
                )
                if handle == INVALID_HANDLE_VALUE then throw windowsException(absolute, GetLastError())
                discard(CloseHandle(handle))
            else
                // 0666 octal. The umask narrows it, which is what the JDK's CREATE_NEW produces too.
                val fd = fcntl.open(toCString(absolute), fcntl.O_WRONLY | fcntl.O_CREAT | fcntl.O_EXCL, 438.toUInt)
                if fd < 0 then throw posixException(absolute, errno)
                discard(unistd.close(fd))
            end if
        }
        // The file exists and is ours, so the reopen must not ask to create it again.
        Array(StandardOpenOption.WRITE)
    end claimExclusively

    private def windowsException(target: String, error: UInt): IOException =
        error match
            case ErrorCodes.ERROR_ACCESS_DENIED                                    => AccessDeniedException(target)
            case ErrorCodes.ERROR_ALREADY_EXISTS | ErrorCodes.ERROR_FILE_EXISTS    => FileAlreadyExistsException(target)
            case ErrorCodes.ERROR_FILE_NOT_FOUND | ErrorCodes.ERROR_PATH_NOT_FOUND => NoSuchFileException(target)
            case _ => IOException(s"$target: ${errorMessage(error)} ($error)")

    private def posixException(target: String, error: Int): IOException =
        if error == EEXIST then FileAlreadyExistsException(target)
        else if error == EACCES then AccessDeniedException(target)
        else if error == ENOENT then NoSuchFileException(target)
        else if error == ENOTDIR then NotDirectoryException(target)
        else IOException(s"Operation on file $target failed: ${strError(error)}")

end NioExclusiveCreatePlatform
