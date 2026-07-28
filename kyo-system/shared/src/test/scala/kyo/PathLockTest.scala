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
        FileSystem.inMemory.map { fileSystem =>
            Path.runWith(fileSystem) {
                Scope.run(Path.stageWrites(Path("staged-lock.bin").lock(
                    Path.LockMode.Exclusive,
                    Path.LockWait.Immediate
                ).unit).unit).andThen {
                    Scope.run(Path.discardWrites(Path("discarded-lock.bin").lock(Path.LockMode.Exclusive, Path.LockWait.Immediate).unit))
                }.andThen {
                    Scope.run(Path.commitWritesOnSuccess(Path("committed-lock.bin").lock(
                        Path.LockMode.Exclusive,
                        Path.LockWait.Immediate
                    ).unit))
                }.map(_ => assert(true))
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
