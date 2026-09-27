package com.example.filesystem

import kyo.*

/** The three tiers extended from outside package `kyo`, as a backend written elsewhere extends them: no package-private access, and frames
  * derived at these call sites.
  */
object FileSystemExternalPackageFixture:
    def root(prefix: String)(using Frame): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Abort.run(FileSystem.host.removeAll(handle.path)).unit).map(_.path)

class FileSystemReadExternalPackageTest extends FileSystemReadConformanceTest[Sync]:
    override protected def realPathRequiresExistence: Boolean = true
    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemExternalPackageFixture.root("external-read").map { root =>
            val file = root / "read.txt"
            FileSystem.host.write(file, "read-value", Path.WriteOptions()).andThen(use(FileSystem.host, file, "read-value"))
        }
    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        FileSystemExternalPackageFixture.root("external-lock").map(root => use(FileSystem.host, root / "target.bin"))
end FileSystemReadExternalPackageTest

class FileSystemWriteExternalPackageTest extends FileSystemWriteConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemExternalPackageFixture.root("external-write").map(root => use(FileSystem.host, root))
end FileSystemWriteExternalPackageTest

class FileSystemWatchExternalPackageTest extends FileSystemWatchConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync] & FileSystem.Watch, Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemExternalPackageFixture.root("external-watch").map(root => use(FileSystem.host, root))
end FileSystemWatchExternalPackageTest
