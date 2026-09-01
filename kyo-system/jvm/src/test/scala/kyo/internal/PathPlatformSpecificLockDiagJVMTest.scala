package kyo.internal

import kyo.*

/** TEMPORARY diagnostic (not for merge). Stresses the scenario behind HostPathLockTest's "repeated
  * interrupted acquisitions leave the path acquirable" until a strand shows up, then reports which
  * layer holds the claim: the registry, or a leaked FileLock inside this JVM.
  */
class PathPlatformSpecificLockDiagJVMTest extends kyo.test.Test[Any]:

    private val rounds  = 40
    private val spawns  = 200
    private val polls   = 40
    private val backoff = 25.millis

    private def poll(target: Path, remaining: Int)(using Frame): Boolean < (Async & Abort[FileSystemException]) =
        if remaining <= 0 then false
        else
            Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_.isDefined)).map {
                case true  => true
                case false => Async.sleep(backoff).andThen(poll(target, remaining - 1))
            }

    private def spawn(target: Path, remaining: Int)(using Frame): Unit < (Async & Abort[FileSystemException]) =
        if remaining <= 0 then ()
        else
            Fiber.initUnscoped(Scope.run(FileSystem.host.tryLock(target, Path.LockMode.Exclusive).map(_ => ())))
                .map(_.interrupt)
                .andThen(spawn(target, remaining - 1))

    private def round(dir: Path, index: Int)(using Frame): Boolean < (Async & Abort[FileSystemException]) =
        val target = dir / s"contended-$index.bin"
        spawn(target, spawns).andThen(poll(target, polls)).map { acquired =>
            if acquired then true
            else
                Sync.Unsafe.defer {
                    println(s"DIAG-STRAND round=$index target=$target " + LockDiag.dump(target))
                    false
                }
        }
    end round

    private def loop(dir: Path, index: Int)(using Frame): Int < (Async & Abort[FileSystemException]) =
        if index >= rounds then -1
        else
            round(dir, index).map {
                case true  => loop(dir, index + 1)
                case false => index
            }

    "diag: repeated interrupted acquisitions leave the path acquirable, under repetition" in {
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-lock-stress"))(h => Sync.Unsafe.defer(h.remove())).map { handle =>
            loop(handle.path, 0).map { stranded =>
                assert(stranded == -1, s"strand first observed at round $stranded")
            }
        }
    }
end PathPlatformSpecificLockDiagJVMTest
