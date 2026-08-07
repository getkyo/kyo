package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** Runs the shared read contract against the host with symbolic links enabled, on Native.
  *
  * Native shares the jvm-native path implementation but not its test coverage: before this, no
  * Native test created a symbolic link or exercised confinement, so the symlink-escape fix was
  * asserted on one platform and assumed on this one.
  */
class HostFileSystemSymlinkNativeTest extends FileSystemReadTestSuite:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(link: Path, target: Path)(using Frame): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: creates a real symbolic link, which no Path operation exposes
        Sync.Unsafe.defer {
            discard(JFiles.createSymbolicLink(
                JPaths.get(link.parts.mkString("/")),
                JPaths.get(target.parts.mkString("/"))
            ))
        }

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead

end HostFileSystemSymlinkNativeTest

/** The same contract with a staged-write overlay installed over the host, on Native. */
class OverlayFileSystemSymlinkNativeTest extends FileSystemReadTestSuite:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(link: Path, target: Path)(using Frame): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: the link is created in the lower, beneath the overlay under test
        Sync.Unsafe.defer {
            discard(JFiles.createSymbolicLink(
                JPaths.get(link.parts.mkString("/")),
                JPaths.get(target.parts.mkString("/"))
            ))
        }

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-overlay-symlink-read-native").map { (lower, root) =>
            val file = root / "read.txt"
            lower.write(file, "read-value", Path.WriteOptions()).andThen {
                FileSystem.overlay(lower).map(overlay => (overlay, file, "read-value"))
            }
        }

end OverlayFileSystemSymlinkNativeTest
