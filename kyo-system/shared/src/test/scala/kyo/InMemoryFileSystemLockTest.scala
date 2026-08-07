package kyo

class InMemoryFileSystemLockTest extends FileSystemLockTest:
    protected def withFileSystem(
        use: (FileSystem.Read[Sync], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map(fileSystem => use(fileSystem, Path("lock-target.bin")))

    "different path and suffix combinations contend for the same sentinel" in {
        FileSystem.inMemory.map { fs =>
            Scope.run {
                fs.lock(Path("a"), Path.LockMode.Exclusive, Path.LockWait.Immediate, ".b.c").map { held =>
                    fs.tryLock(Path("a.b"), Path.LockMode.Exclusive, ".c").map { attempted =>
                        assert(attempted.isEmpty)
                        held.check
                    }
                }
            }.andThen {
                Scope.run(fs.tryLock(Path("a.b"), Path.LockMode.Exclusive, ".c").map(lock => assert(lock.isDefined)))
            }
        }
    }

    "reevaluating a shared lock acquisition creates independent owners" in {
        FileSystem.inMemory.map { fs =>
            val acquire = fs.lock(Path("shared"), Path.LockMode.Shared, Path.LockWait.Immediate)
            Scope.run {
                acquire.map { first =>
                    acquire.map { second =>
                        assert(!Path.LockOwnership.same(first.ownership, second.ownership))
                        first.release(first.ownership).andThen(second.check)
                    }
                }
            }
        }
    }

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
end InMemoryFileSystemLockTest
