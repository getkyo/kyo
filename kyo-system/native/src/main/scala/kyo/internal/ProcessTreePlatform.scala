package kyo.internal

import kyo.*
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.errno.EPERM
import scala.scalanative.posix.errno.errno
import scala.scalanative.posix.signal as posixsignal
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The process-tree operations [[ProcessTree]] composes, on the operating system directly.
  *
  * Scala Native's `ProcessHandle.children()` returns nothing, so children are read from the kernel: from `/proc` on Linux and from
  * libproc on macOS. Signals go through `kill(2)`. Windows has no process tree to walk here, so only the root is killed there.
  */
private[kyo] object ProcessTreePlatform:

    // libproc's `proc_listpids` selector for the pids whose parent is the given pid.
    private val ProcPpidOnly = 6
    private val MaxChildren  = 4096

    def children(parents: Chunk[Long])(using Frame): Chunk[Long] < Sync =
        Sync.defer {
            if LinktimeInfo.isLinux then linuxChildren(parents.toSet)
            else if LinktimeInfo.isMac then parents.flatMap(macChildren)
            else Chunk.empty
        }

    def stop(pids: Chunk[Long])(using Frame): Unit < Sync =
        signal(pids, posixsignal.SIGSTOP)

    def kill(pids: Chunk[Long])(using Frame): Unit < Sync =
        signal(pids, posixsignal.SIGKILL)

    def exists(pid: Long)(using Frame): Boolean < Sync =
        Sync.defer {
            if LinktimeInfo.isWindows then false
            else posixsignal.kill(pid.toInt, 0) == 0 || errno == EPERM
        }

    private def signal(pids: Chunk[Long], sig: CInt)(using Frame): Unit < Sync =
        Sync.defer {
            if !LinktimeInfo.isWindows then pids.foreach(pid => discard(posixsignal.kill(pid.toInt, sig)))
        }

    // Each /proc/<pid>/stat reads `pid (comm) state ppid ...`; comm may contain spaces and parentheses, so fields are read after the
    // last `)`.
    private def linuxChildren(parents: Set[Long]): Chunk[Long] =
        val entries = Option(new java.io.File("/proc").list()).getOrElse(Array.empty[String])
        Chunk.from(entries.iterator.filter(name => name.nonEmpty && name.forall(_.isDigit)).flatMap { name =>
            val stat =
                try Some(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc", name, "stat"))))
                catch case _: java.io.IOException => None
            stat.flatMap { text =>
                val fields = text.substring(text.lastIndexOf(')') + 1).trim.split(" ")
                if fields.length > 1 then fields(1).toLongOption.filter(parents.contains).map(_ => name.toLong)
                else None
            }
        }.toSeq)
    end linuxChildren

    private def macChildren(parent: Long): Chunk[Long] =
        val buffer = stackalloc[CInt](MaxChildren)
        val bytes  = LibProc.proc_listpids(ProcPpidOnly.toUInt, parent.toUInt, buffer.asInstanceOf[Ptr[Byte]], MaxChildren * 4)
        if bytes <= 0 then Chunk.empty
        else Chunk.from((0 until bytes / 4).map(i => buffer(i).toLong).filter(_ > 0))
    end macChildren

end ProcessTreePlatform

@extern
private[internal] object LibProc:
    def proc_listpids(kind: CUnsignedInt, typeinfo: CUnsignedInt, buffer: Ptr[Byte], buffersize: CInt): CInt = extern
