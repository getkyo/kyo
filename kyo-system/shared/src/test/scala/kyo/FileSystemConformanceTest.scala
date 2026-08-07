package kyo

private object FileSystemConformanceFixtures:

    private given Frame = Frame.internal

    def inMemory(using Frame): (FileSystem.Write[Sync], Path) < (Sync & Abort[FileSystemException]) =
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("conformance")
            fileSystem.mkDir(root).map(_ => (fileSystem, root))
        }

    def host(prefix: String)(using
        Frame
    ): (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            (FileSystem.host, handle.path)
        }

    def inMemoryRead(using Frame): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        inMemory.map { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }

    def hostRead(using Frame): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        host("kyo-host-read-suite").map { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }

    def zip(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException]) =
        host("kyo-zip-conformance").map { (_, directory) =>
            FileSystem.zip(directory / "conformance.zip").map { fileSystem =>
                val root = Path("conformance")
                fileSystem.mkDir(root).map(_ => (fileSystem, fileSystem, root))
            }
        }

    def zipReadOnly(using Frame): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        host("kyo-zip-read-conformance").map { (_, directory) =>
            val archive = directory / "conformance.zip"
            val file    = Path("conformance", "read.txt")
            FileSystem.zip(archive).map { writable =>
                writable.write(file, "read-value", Path.WriteOptions()).andThen {
                    Abort.run[CommitConflict](writable.commit).map {
                        case Result.Success(_) =>
                            FileSystem.zipReadOnly(archive).map(readOnly => (readOnly, file, "read-value"))
                        case Result.Failure(error) => Abort.fail(FileIOException(archive, FileSystemOperation.Write, error))
                    }
                }
            }
        }

end FileSystemConformanceFixtures

class InMemoryFileSystemReadConformanceTest extends FileSystemReadTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemoryRead
end InMemoryFileSystemReadConformanceTest

class InMemoryFileSystemWriteConformanceTest extends FileSystemWriteTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemory
end InMemoryFileSystemWriteConformanceTest

class InMemoryFileSystemChannelConformanceTest extends FileSystemChannelTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemory
end InMemoryFileSystemChannelConformanceTest

class InMemoryFileSystemDurabilityConformanceTest extends FileSystemDurabilityTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemory
end InMemoryFileSystemDurabilityConformanceTest

class HostFileSystemReadConformanceTest extends FileSystemReadTestSuite:
    override protected def realPathRequiresExistence: Boolean = true
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead
end HostFileSystemReadConformanceTest

class HostFileSystemWriteConformanceTest extends FileSystemWriteTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-host-write-suite")
end HostFileSystemWriteConformanceTest

class HostFileSystemChannelConformanceTest extends FileSystemChannelTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-host-channel-suite")
end HostFileSystemChannelConformanceTest

class HostFileSystemDurabilityConformanceTest extends FileSystemDurabilityTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-host-durability-suite")
end HostFileSystemDurabilityConformanceTest

class ZipReadOnlyFileSystemReadConformanceTest extends FileSystemReadTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zipReadOnly
end ZipReadOnlyFileSystemReadConformanceTest

class ZipRewriteFileSystemReadConformanceTest extends FileSystemReadTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map { (fileSystem, _, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }
end ZipRewriteFileSystemReadConformanceTest

class ZipRewriteFileSystemWriteConformanceTest extends FileSystemWriteTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => (fileSystem, root))
end ZipRewriteFileSystemWriteConformanceTest

class ZipRewriteFileSystemChannelConformanceTest extends FileSystemChannelTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => (fileSystem, root))
end ZipRewriteFileSystemChannelConformanceTest

class ZipRewriteFileSystemDurabilityConformanceTest extends FileSystemDurabilityTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => (fileSystem, root))
end ZipRewriteFileSystemDurabilityConformanceTest

class ZipRewriteFileSystemStagedChangesConformanceTest extends FileSystemStagedChangesTestSuite:
    protected def createFileSystem(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip
end ZipRewriteFileSystemStagedChangesConformanceTest

/** Minimal user-defined backend fixture that deliberately exposes only the read tier. */
final class UserReadOnlyFileSystemFixture(delegate: FileSystem.Read[Sync]) extends FileSystem.Read[Sync]:
    export delegate.*
end UserReadOnlyFileSystemFixture

class UserReadOnlyFileSystemConformanceTest extends FileSystemReadTestSuite:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemoryRead.map { (fileSystem, path, expected) =>
            (UserReadOnlyFileSystemFixture(fileSystem), path, expected)
        }
end UserReadOnlyFileSystemConformanceTest
