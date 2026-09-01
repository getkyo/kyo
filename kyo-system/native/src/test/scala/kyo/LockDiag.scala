package kyo

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kyo.internal.NioPathLockRegistry

/** TEMPORARY diagnostic helper (not for merge). */
object LockDiag:
    def dump(target: Path)(using AllowUnsafe): String =
        val jpath    = java.nio.file.Path.of(target.parts.mkString(java.io.File.separator))
        val absolute = jpath.toAbsolutePath.normalize()
        val key =
            try absolute.toRealPath().toString
            catch case _: Throwable => absolute.toString
        val registry = NioPathLockRegistry.describe(key)
        val all      = NioPathLockRegistry.describeAll
        val direct =
            try
                val ch = FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                try
                    val fl =
                        try ch.tryLock(0L, Long.MaxValue, false)
                        catch case _: OverlappingFileLockException => null
                    if fl == null then "direct=null(overlapping-or-contended)"
                    else
                        fl.release()
                        "direct=acquired"
                    end if
                finally ch.close()
                end try
            catch case e: Throwable => s"direct-threw=$e"
        s"key=$key registry=$registry allKeys=$all $direct"
    end dump
end LockDiag
