package kyo

/** The in-memory backend put through every tier the host answers. Its paths are pure keys with no links, so `realPath` returns
  * a path unchanged, and its watcher is pushed to by each mutation rather than polling.
  */
private object InMemoryFileSystemFixtures:

    def withFileSystem[A](
        use: (FileSystem.Write[Sync] & FileSystem.Watch, Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("conformance")
            fileSystem.mkDir(root).andThen(use(fileSystem, root))
        }

end InMemoryFileSystemFixtures

class InMemoryFileSystemReadConformanceTest extends FileSystemReadConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        InMemoryFileSystemFixtures.withFileSystem { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).andThen(use(fileSystem, file, "read-value"))
        }
    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        InMemoryFileSystemFixtures.withFileSystem((fileSystem, root) => use(fileSystem, root / "target.bin"))
end InMemoryFileSystemReadConformanceTest

class InMemoryFileSystemWriteConformanceTest extends FileSystemWriteConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        InMemoryFileSystemFixtures.withFileSystem(use)
end InMemoryFileSystemWriteConformanceTest

class InMemoryFileSystemWatchConformanceTest extends FileSystemWatchConformanceTest[Sync]:
    // Each mutation pushes its events before it returns, so there is no poll to wait for.
    override protected def awaitQueued(clock: Clock.TimeControl)(using Frame): Unit < Async = ()
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync] & FileSystem.Watch, Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        InMemoryFileSystemFixtures.withFileSystem(use)
end InMemoryFileSystemWatchConformanceTest
