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
