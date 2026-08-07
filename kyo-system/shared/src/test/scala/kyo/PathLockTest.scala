package kyo

class PathLockTest extends FileSystemLockTestSuite:
    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map(fileSystem => use(fileSystem, Path("lock-target.bin")))

    "Path lock operations dispatch through PathRead" in {
        FileSystem.inMemory.map { fileSystem =>
            val path = Path("path-lock-target.bin")
            Scope.run {
                Path.runReadOnlyWith(fileSystem) {
                    path.lock(Path.LockMode.Shared, Path.LockWait.Immediate).map { lock =>
                        path.tryLock(Path.LockMode.Exclusive).map { conflicting =>
                            assert(lock.mode == Path.LockMode.Shared)
                            assert(conflicting.isEmpty)
                        }
                    }
                }
            }
        }
    }

    "Path lock operations forward through staged-write overlays" in {
        // Previously this ran the three staged-write shapes and asserted `true`, which held for any
        // implementation that did not throw, including one that granted nothing or leaked every
        // claim. Each arm now asserts the mode it was granted and that the claim is gone once the
        // scope closes, which is what "forwards correctly" has to mean.
        FileSystem.inMemory.map { fileSystem =>
            Path.runWith(fileSystem) {
                val staged    = Path("staged-lock.bin")
                val discarded = Path("discarded-lock.bin")
                val committed = Path("committed-lock.bin")
                def acquire(path: Path) =
                    path.lock(Path.LockMode.Exclusive, Path.LockWait.Immediate).map(lock => assert(lock.mode == Path.LockMode.Exclusive))
                def reacquirable(path: Path) =
                    Scope.run(path.tryLock(Path.LockMode.Exclusive)).map { again =>
                        assert(again.isDefined, s"the claim on $path outlived the scope that took it")
                    }
                Scope.run(Path.stageWrites(acquire(staged)).unit).andThen(reacquirable(staged)).andThen {
                    Scope.run(Path.discardWrites(acquire(discarded))).andThen(reacquirable(discarded))
                }.andThen {
                    Scope.run(Path.commitWritesOnSuccess(acquire(committed))).andThen(reacquirable(committed))
                }
            }
        }
    }
end PathLockTest

class HostPathLockTest extends FileSystemLockTestSuite:
    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-path-lock"))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            use(FileSystem.host, handle.path / "target.bin")
        }

    "failed raw release remains retryable" in {
        AtomicInt.init(0).map { releases =>
            val raw = new Path.RawLock:
                def isExclusive: Boolean                                               = true
                def check()(using AllowUnsafe, Frame): Result[FileLockException, Unit] = Result.unit
                def release()(using AllowUnsafe, Frame): Result[FileLockException, Unit] =
                    if releases.unsafe.incrementAndGet() == 1 then Result.fail(FileLockOwnershipLostException(Path("retry-lock")))
                    else Result.unit
            AtomicInt.init(0).map { state =>
                val service = new HostFileSystem.HostFileSystem
                val lock    = service.lockFrom(Path("retry-lock"), raw, Path.LockMode.Exclusive, state)
                Abort.run[FileLockException](lock.release(lock.ownership)).map { first =>
                    assert(first.isFailure)
                    lock.release(lock.ownership).andThen(releases.get.map(count => assert(count == 2)))
                }
            }
        }
    }
end HostPathLockTest

/** The read-only zip backend carries its own lock implementation, with its own claim map and its
  * own shared/exclusive merge, entirely separate from the host registry. It was registered for read
  * conformance and never once run against the lock contract, so none of its sixteen cases had ever
  * been asked of it.
  */
class ZipReadOnlyPathLockTest extends FileSystemLockTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zipReadOnly.map((fileSystem, file, _) => use(fileSystem, file))
end ZipReadOnlyPathLockTest

/** The rewritable zip backend delegates locks to its in-memory upper, so a claim never reaches the
  * archive and is visible only within one instance. Running the contract states that semantic
  * rather than leaving it undeclared.
  */
class ZipRewritePathLockTest extends FileSystemLockTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => use(fileSystem, root / "lock-target.bin"))
end ZipRewritePathLockTest
