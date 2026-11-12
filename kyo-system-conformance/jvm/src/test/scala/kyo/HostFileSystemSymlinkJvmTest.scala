package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** Runs the shared read contract against the host with symbolic links enabled.
  *
  * The shared registrations cannot do this: link creation has no public operation, so it has to come
  * from a platform source set. Without a fixture that can create one, the suite's link assertions are
  * one-sided and a backend that stopped resolving links entirely would still pass.
  */
class HostFileSystemSymlinkJvmTest extends FileSystemReadConformanceTest[Sync]:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(fileSystem: FileSystem.Read[Sync], link: Path, target: Path)(using
        Frame
    ): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: creates a real symbolic link, which no Path operation exposes
        Sync.Unsafe.defer {
            discard(JFiles.createSymbolicLink(
                JPaths.get(link.parts.mkString("/")),
                JPaths.get(target.parts.mkString("/"))
            ))
        }

    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead.map(use.tupled)

    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostLockTarget.map(use.tupled)

end HostFileSystemSymlinkJvmTest

/** The same contract with a staged-write overlay installed over the host. This is the configuration
  * that carried the original symlink-escape defect: an overlay whose own link answers shadowed the lower's.
  */
class OverlayFileSystemSymlinkJvmTest extends FileSystemReadConformanceTest[Sync]:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(fileSystem: FileSystem.Read[Sync], link: Path, target: Path)(using
        Frame
    ): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: the link is created in the lower, beneath the overlay under test
        Sync.Unsafe.defer {
            discard(JFiles.createSymbolicLink(
                JPaths.get(link.parts.mkString("/")),
                JPaths.get(target.parts.mkString("/"))
            ))
        }

    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHostWithLowerFile("kyo-overlay-symlink-read-jvm")(use)

    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-symlink-lock-jvm")((overlay, root) => use(overlay, root / "target.bin"))

end OverlayFileSystemSymlinkJvmTest
