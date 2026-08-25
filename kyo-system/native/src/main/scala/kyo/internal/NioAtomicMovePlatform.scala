package kyo.internal

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import kyo.Path
import scalanative.libc
import scalanative.libc.LibcExt.strError
import scalanative.meta.LinktimeInfo.isWindows
import scalanative.posix.errno.EACCES
import scalanative.posix.errno.EEXIST
import scalanative.posix.errno.ENOENT
import scalanative.posix.errno.ENOTDIR
import scalanative.posix.errno.ENOTEMPTY
import scalanative.posix.errno.EXDEV
import scalanative.posix.errno.errno
import scalanative.unsafe.Zone
import scalanative.unsafe.toCString
import scalanative.unsafe.toCWideStringUTF16LE
import scalanative.unsigned.*
import scalanative.windows.ErrorCodes
import scalanative.windows.ErrorHandlingApi.*
import scalanative.windows.ErrorHandlingApiOps.errorMessage
import scalanative.windows.FileApiExt.*
import scalanative.windows.WinBaseApi.*
import scalanative.windows.WinBaseApiExt.*

private[kyo] object NioAtomicMovePlatform:

    private[kyo] def move(source: java.nio.file.Path, target: java.nio.file.Path, replace: Path.Replace): Unit =
        if Files.exists(target.toAbsolutePath) && replace == Path.Replace.Never then
            throw FileAlreadyExistsException(target.toString)

        Zone.acquire { implicit zone =>
            val sourceAbsolute = source.toAbsolutePath.toString
            val targetAbsolute = target.toAbsolutePath.toString
            if isWindows then
                val replaceFlag = if replace == Path.Replace.Existing then MOVEFILE_REPLACE_EXISTING else 0.toUInt
                val flags       = MOVEFILE_WRITE_THROUGH | replaceFlag
                val moved = MoveFileExW(
                    toCWideStringUTF16LE(sourceAbsolute),
                    toCWideStringUTF16LE(targetAbsolute),
                    flags
                )
                if !moved then
                    val error = GetLastError()
                    if isAtomicMoveUnsupportedWindowsError(error) then throw NioAtomicMoveUnsupportedException()
                    else if error != ErrorCodes.ERROR_SUCCESS then throw windowsException(target.toString, error)
                end if
            else
                val moved = libc.stdio.rename(toCString(sourceAbsolute), toCString(targetAbsolute))
                if moved != 0 then
                    val error = errno
                    if isAtomicMoveUnsupportedPosixError(error) then throw NioAtomicMoveUnsupportedException()
                    else throw posixException(target.toString, error)
                end if
            end if
        }
    end move

    private[kyo] def isAtomicMoveUnsupportedPosixError(error: Int): Boolean =
        error == EXDEV

    private[kyo] def isAtomicMoveUnsupportedWindowsError(error: UInt): Boolean =
        error == ErrorCodes.ERROR_NOT_SAME_DEVICE

    private[kyo] def isMissingPosixError(error: Int): Boolean =
        error == ENOENT

    private[kyo] def isMissingWindowsError(error: UInt): Boolean =
        error == ErrorCodes.ERROR_FILE_NOT_FOUND || error == ErrorCodes.ERROR_PATH_NOT_FOUND

    private def windowsException(target: String, error: UInt): IOException =
        error match
            case ErrorCodes.ERROR_ACCESS_DENIED                                 => AccessDeniedException(target)
            case error if isMissingWindowsError(error)                          => NoSuchFileException(target)
            case ErrorCodes.ERROR_ALREADY_EXISTS | ErrorCodes.ERROR_FILE_EXISTS => FileAlreadyExistsException(target)
            case _                                                              => IOException(s"$target: ${errorMessage(error)} ($error)")

    private def posixException(target: String, error: Int): IOException =
        if error == EACCES then AccessDeniedException(target)
        else if error == EEXIST then FileAlreadyExistsException(target)
        else if isMissingPosixError(error) then NoSuchFileException(target)
        else if error == ENOTDIR then NotDirectoryException(target)
        else if error == ENOTEMPTY then DirectoryNotEmptyException(target)
        else IOException(s"Operation on file $target failed: ${strError(error)}")

end NioAtomicMovePlatform
