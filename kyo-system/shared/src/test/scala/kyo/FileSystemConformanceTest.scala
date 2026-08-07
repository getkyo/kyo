package kyo

private object FileSystemConformanceFixtures:

    def inMemory(using Frame): (FileSystem.Write[Sync], Path) < (Sync & Abort[FileSystemException]) =
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("conformance")
            fileSystem.mkDir(root).map(_ => (fileSystem, root))
        }

    def inMemoryRead(using Frame): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        inMemory.map { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }

    def host(prefix: String)(using
        Frame
    ): (FileSystem.Write[Sync] & FileSystem.Watch, Path) < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            (FileSystem.host, handle.path)
        }

    def hostRead(using Frame): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        host("kyo-host-read-suite").map { (fileSystem, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }

    def overlay(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { lower =>
            FileSystem.overlay(lower).map { overlay =>
                val root = Path("overlay-conformance")
                overlay.mkDir(root).map(_ => (overlay, overlay, root))
            }
        }

    def overlayOverHost(prefix: String)(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException]) =
        host(prefix).map { (lower, root) =>
            FileSystem.overlay(lower).map(overlay => (overlay, overlay, root))
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

class InMemoryFileSystemReadConformanceTest extends FileSystemReadTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemoryRead
end InMemoryFileSystemReadConformanceTest

class InMemoryFileSystemWriteConformanceTest extends FileSystemWriteTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemory
end InMemoryFileSystemWriteConformanceTest

class InMemoryFileSystemChannelConformanceTest extends FileSystemChannelTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemory
end InMemoryFileSystemChannelConformanceTest

class InMemoryFileSystemDurabilityConformanceTest extends FileSystemDurabilityTest:
    private[kyo] def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.inMemory
end InMemoryFileSystemDurabilityConformanceTest

class HostFileSystemReadConformanceTest extends FileSystemReadTest:
    override protected def realPathRequiresExistence: Boolean = true
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead
end HostFileSystemReadConformanceTest

class HostFileSystemWriteConformanceTest extends FileSystemWriteTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-host-write-suite")
end HostFileSystemWriteConformanceTest

class HostFileSystemChannelConformanceTest extends FileSystemChannelTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-host-channel-suite")
end HostFileSystemChannelConformanceTest

class HostFileSystemDurabilityConformanceTest extends FileSystemDurabilityTest:
    override private[kyo] def supportsDirectorySync: Boolean = !kyo.internal.Platform.isWindows
    private[kyo] def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-host-durability-suite")
end HostFileSystemDurabilityConformanceTest

class OverlayFileSystemReadConformanceTest extends FileSystemReadTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlay.map { (fileSystem, _, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }
end OverlayFileSystemReadConformanceTest

class OverlayFileSystemWriteConformanceTest extends FileSystemWriteTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlay.map((fileSystem, _, root) => (fileSystem, root))
end OverlayFileSystemWriteConformanceTest

class OverlayFileSystemChannelConformanceTest extends FileSystemChannelTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlay.map((fileSystem, _, root) => (fileSystem, root))
end OverlayFileSystemChannelConformanceTest

class OverlayFileSystemDurabilityConformanceTest extends FileSystemDurabilityTest:
    private[kyo] def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlay.map((fileSystem, _, root) => (fileSystem, root))
end OverlayFileSystemDurabilityConformanceTest

class OverlayFileSystemStagedChangesConformanceTest extends FileSystemStagedChangesTestSuite:
    protected def createFileSystem(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlay
end OverlayFileSystemStagedChangesConformanceTest

class OverlayOverHostFileSystemReadConformanceTest extends FileSystemReadTest:
    override protected def realPathRequiresExistence: Boolean = true
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlayOverHost("kyo-overlay-host-read-suite").map { (fileSystem, _, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }
end OverlayOverHostFileSystemReadConformanceTest

class OverlayOverHostFileSystemWriteConformanceTest extends FileSystemWriteTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlayOverHost("kyo-overlay-host-write-suite").map((fileSystem, _, root) => (fileSystem, root))
end OverlayOverHostFileSystemWriteConformanceTest

class OverlayOverHostFileSystemChannelConformanceTest extends FileSystemChannelTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlayOverHost("kyo-overlay-host-channel-suite").map((fileSystem, _, root) => (fileSystem, root))
end OverlayOverHostFileSystemChannelConformanceTest

class OverlayOverHostFileSystemDurabilityConformanceTest extends FileSystemDurabilityTest:
    private[kyo] def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlayOverHost("kyo-overlay-host-durability-suite").map((fileSystem, _, root) =>
            (fileSystem, root)
        )
end OverlayOverHostFileSystemDurabilityConformanceTest

class OverlayOverHostFileSystemStagedChangesConformanceTest extends FileSystemStagedChangesTestSuite:
    protected def createFileSystem(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.overlayOverHost("kyo-overlay-host-staged-suite")
end OverlayOverHostFileSystemStagedChangesConformanceTest

class ZipReadOnlyFileSystemReadConformanceTest extends FileSystemReadTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zipReadOnly
end ZipReadOnlyFileSystemReadConformanceTest

class ZipRewriteFileSystemReadConformanceTest extends FileSystemReadTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map { (fileSystem, _, root) =>
            val file = root / "read.txt"
            fileSystem.write(file, "read-value", Path.WriteOptions()).map(_ => (fileSystem, file, "read-value"))
        }
end ZipRewriteFileSystemReadConformanceTest

class ZipRewriteFileSystemWriteConformanceTest extends FileSystemWriteTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => (fileSystem, root))
end ZipRewriteFileSystemWriteConformanceTest

class ZipRewriteFileSystemChannelConformanceTest extends FileSystemChannelTest:
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.zip.map((fileSystem, _, root) => (fileSystem, root))
end ZipRewriteFileSystemChannelConformanceTest

class ZipRewriteFileSystemDurabilityConformanceTest extends FileSystemDurabilityTest:
    private[kyo] def createFileSystem(using
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
    export delegate.lock
    export delegate.openRead
    export delegate.openReadChannel
    export delegate.openReadChannelUnscoped
    export delegate.openReadLines
    export delegate.openWalk
    export delegate.read
    export delegate.readBytes
    export delegate.readLines
    export delegate.realPath
    export delegate.size
    export delegate.stat
    export delegate.tryLock
end UserReadOnlyFileSystemFixture

class UserReadOnlyFileSystemConformanceTest extends FileSystemReadTest:
    override protected def realPathRequiresExistence: Boolean = true
    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead.map { (fileSystem, path, expected) =>
            (UserReadOnlyFileSystemFixture(fileSystem), path, expected)
        }
end UserReadOnlyFileSystemConformanceTest
