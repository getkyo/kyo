package kyo.internal

import java.io.IOException
import scala.scalanative.libc.errno
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsafe.toCString

private[kyo] object NioDirectorySyncPlatform:
    def sync(path: java.nio.file.Path): Unit =
        val value =
            val rendered = path.toString
            if rendered.isEmpty then "." else rendered
        Zone.acquire { implicit zone =>
            val fd = fcntl.open(toCString(value), fcntl.O_RDONLY)
            if fd < 0 then throw new IOException(s"Cannot open directory $value: errno=${errno.errno}")
            val syncErrno  = if unistd.fsync(fd) == 0 then 0 else errno.errno
            val closeErrno = if unistd.close(fd) == 0 then 0 else errno.errno
            if syncErrno != 0 then throw new IOException(s"Cannot synchronize directory $value: errno=$syncErrno")
            if closeErrno != 0 then throw new IOException(s"Cannot close directory $value: errno=$closeErrno")
        }
    end sync
end NioDirectorySyncPlatform
