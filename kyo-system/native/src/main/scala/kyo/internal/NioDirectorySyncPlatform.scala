package kyo.internal

import java.io.IOException
import scala.scalanative.libc.errno
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsafe.toCString

private[kyo] object NioDirectorySyncPlatform:

    /** Darwin's `F_FULLFSYNC`, which posixlib does not name. */
    private val FullFsync = 51

    def sync(path: java.nio.file.Path): Unit =
        val value =
            val rendered = path.toString
            if rendered.isEmpty then "." else rendered
        if scala.scalanative.meta.LinktimeInfo.isWindows then
            throw new IOException("Directory synchronization is unsupported on Windows")
        else
            Zone.acquire { implicit zone =>
                val fd = fcntl.open(toCString(value), fcntl.O_RDONLY)
                if fd < 0 then throw new IOException(s"Cannot open directory $value: errno=${errno.errno}")
                // Darwin's fsync leaves the entries in the drive's cache, where a power loss discards them. F_FULLFSYNC asks
                // the drive to flush that cache too. A volume that cannot honor it falls back to fsync, as libuv does.
                val fullSynced = scala.scalanative.meta.LinktimeInfo.isMac && fcntl.fcntl(fd, FullFsync, 0) == 0
                val syncErrno  = if fullSynced || unistd.fsync(fd) == 0 then 0 else errno.errno
                val closeErrno = if unistd.close(fd) == 0 then 0 else errno.errno
                if syncErrno != 0 then throw new IOException(s"Cannot synchronize directory $value: errno=$syncErrno")
                if closeErrno != 0 then throw new IOException(s"Cannot close directory $value: errno=$closeErrno")
            }
        end if
    end sync
end NioDirectorySyncPlatform
