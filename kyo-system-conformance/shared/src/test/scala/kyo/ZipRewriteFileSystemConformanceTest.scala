package kyo

/** The zip backends put through the tiers they implement. The rewrite backend reads, writes, and stages its changes; the read-only one
  * serves an archive the rewrite backend committed. Both keep their archive in a fresh host directory, and their paths are pure keys with
  * no links, so `realPath` returns a path unchanged.
  */
private[kyo] object ZipFileSystemFixtures:

    private def archiveDirectory[A](prefix: String)(use: Path => A < (Sync & Async & Scope & Abort[FileSystemException]))(using
        Frame
    ): A < (Async & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Sync.Unsafe.defer(handle.remove())).map(handle => use(handle.path))

    def withRewrite[A](
        use: (FileSystem.Write[Sync] & FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        archiveDirectory("kyo-zip-conformance") { directory =>
            FileSystem.zip(directory / "conformance.zip").map { fileSystem =>
                val root = Path("conformance")
                fileSystem.mkDir(root).andThen(use(fileSystem, root))
            }
        }

    /** A read-only backend over an archive holding `root/read.txt`, committed by the rewrite backend. */
    def withReadOnly[A](use: (FileSystem.Read[Sync], Path) => A < (Sync & Async & Scope & Abort[FileSystemException]))(using
        Frame
    ): A < (Async & Scope & Abort[FileSystemException]) =
        archiveDirectory("kyo-zip-read-conformance") { directory =>
            val archive = directory / "conformance.zip"
            val root    = Path("conformance")
            FileSystem.zip(archive).map { writable =>
                writable.write(root / "read.txt", "read-value", Path.WriteOptions()).andThen {
                    Abort.run[CommitConflict](writable.commit).map {
                        case Result.Success(_)     => FileSystem.zipReadOnly(archive).map(readOnly => use(readOnly, root))
                        case Result.Failure(error) => Abort.fail(FileIOException(archive, FileSystemOperation.Write, error))
                        case Result.Panic(error)   => Abort.panic(error)
                    }
                }
            }
        }

end ZipFileSystemFixtures

class ZipRewriteFileSystemReadConformanceTest extends FileSystemReadConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        ZipFileSystemFixtures.withRewrite { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).andThen(use(fileSystem, file, "read-value"))
        }
    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        ZipFileSystemFixtures.withRewrite((fileSystem, root) => use(fileSystem, root / "target.bin"))
end ZipRewriteFileSystemReadConformanceTest

class ZipRewriteFileSystemWriteConformanceTest extends FileSystemWriteConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        ZipFileSystemFixtures.withRewrite(use)
end ZipRewriteFileSystemWriteConformanceTest

class ZipRewriteFileSystemStagedChangesConformanceTest extends FileSystemStagedChangesConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        ZipFileSystemFixtures.withRewrite((fileSystem, root) => use(fileSystem, fileSystem, root))
end ZipRewriteFileSystemStagedChangesConformanceTest
