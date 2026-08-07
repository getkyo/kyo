package kyo

/** The read-only zip backend put through the read tier, over an archive the rewrite backend committed. */
class ZipReadOnlyFileSystemReadConformanceTest extends FileSystemReadConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        ZipFileSystemFixtures.withReadOnly((fileSystem, root) => use(fileSystem, root / "read.txt", "read-value"))
    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        ZipFileSystemFixtures.withReadOnly((fileSystem, root) => use(fileSystem, root / "target.bin"))
end ZipReadOnlyFileSystemReadConformanceTest
