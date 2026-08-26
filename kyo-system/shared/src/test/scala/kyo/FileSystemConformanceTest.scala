package kyo

private object FileSystemConformanceFixtures:

    private given Frame = Frame.internal

    def host(prefix: String)(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            (FileSystem.host, handle.path)
        }

    def hostRead(using Frame): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        host("kyo-host-read-suite").map { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }

end FileSystemConformanceFixtures

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

/** Minimal user-defined backend fixture that deliberately exposes only the read tier. */
final class UserReadOnlyFileSystemFixture(delegate: FileSystem.Read[Sync]) extends FileSystem.Read[Sync]:
    // Delegated by name, not `export delegate.*`. A wildcard emits one forwarder per member in an
    // order the compiler does not fix, so this class's TASTy differs between clean builds; that
    // reaches the doctest classpath fingerprint and costs the module its cached results. Omitting a
    // member here fails to compile, since the class must implement all of FileSystem.Read.
    export delegate.defaultCaseSensitivity
    export delegate.exists
    export delegate.isDirectory
    export delegate.isRegularFile
    export delegate.isSymbolicLink
    export delegate.list
    export delegate.openRead
    export delegate.openReadLines
    export delegate.openWalk
    export delegate.read
    export delegate.readBytes
    export delegate.readLines
    export delegate.realPath
    export delegate.size
    export delegate.stat
end UserReadOnlyFileSystemFixture

class UserReadOnlyFileSystemConformanceTest extends FileSystemReadTestSuite:
    override protected def realPathRequiresExistence: Boolean = true
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead.map { (fileSystem, path, expected) =>
            (UserReadOnlyFileSystemFixture(fileSystem), path, expected)
        }
end UserReadOnlyFileSystemConformanceTest
