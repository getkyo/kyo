package kyo.internal

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import kyo.*

/** TEMPORARY diagnostic (not for merge). Reproduces the Windows strand behind
  * HostPathLockTest's "repeated interrupted acquisitions leave the path acquirable" and reports
  * which layer holds the claim: the registry, or a leaked FileLock inside this JVM.
  */
class PathPlatformSpecificLockDiagJVMTest extends kyo.test.Test[Any]:

    private def poll(target: Path, remaining: Int)(using Frame): Boolean < (Async & Abort[FileSystemException]) =
        if remaining <= 0 then false
        else
            Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_.isDefined)).map {
                case true  => true
                case false => Async.sleep(50.millis).andThen(poll(target, remaining - 1))
            }

    private def spawn(target: Path, remaining: Int)(using Frame): Unit < (Async & Abort[FileSystemException]) =
        if remaining <= 0 then ()
        else
            Fiber.initUnscoped(Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_ => ())))
                .map(_.interrupt)
                .andThen(spawn(target, remaining - 1))

    "diag: repeated interrupted acquisitions leave the path acquirable" in {
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-diag"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            val target = handle.path / "contended.bin"
            spawn(target, 200).andThen(poll(target, 100)).map { acquired =>
                Sync.Unsafe.defer {
                    val jpath    = java.nio.file.Path.of(target.parts.mkString(java.io.File.separator))
                    val absolute = jpath.toAbsolutePath.normalize()
                    val key =
                        try absolute.toRealPath().toString
                        catch case _: Throwable => absolute.toString
                    val registry = NioPathLockRegistry.describe(key)
                    val all      = NioPathLockRegistry.describeAll
                    val direct =
                        try
                            val ch = FileChannel.open(
                                absolute,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE
                            )
                            try
                                val fl =
                                    try ch.tryLock(0L, Long.MaxValue, false)
                                    catch case e: OverlappingFileLockException => null
                                if fl == null then "direct=null (overlapping or contended)"
                                else
                                    fl.release()
                                    "direct=acquired"
                                end if
                            finally ch.close()
                            end try
                        catch case e: Throwable => s"direct threw $e"
                    println(s"DIAG acquired=$acquired key=$key registry=$registry allKeys=$all $direct")
                }.andThen(assert(acquired, "path never became acquirable"))
            }
        }
    }
end PathPlatformSpecificLockDiagJVMTest
