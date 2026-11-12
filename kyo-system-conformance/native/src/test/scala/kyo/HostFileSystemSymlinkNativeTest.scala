package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** Runs the shared read contract against the host with symbolic links enabled, on Native.
  *
  * Native shares the jvm-native path implementation but not its test coverage: before this, no
  * Native test created a symbolic link or exercised confinement, so the symlink-escape fix was
  * asserted on one platform and assumed on this one.
  */
class HostFileSystemSymlinkNativeTest extends FileSystemReadConformanceTest[Sync]:

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

end HostFileSystemSymlinkNativeTest

/** The same contract with a staged-write overlay installed over the host, on Native. This is the configuration
  * that carried the original symlink-escape defect: an overlay whose own link answers shadowed the lower's.
  */
class OverlayFileSystemSymlinkNativeTest extends FileSystemReadConformanceTest[Sync]:

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
        OverlayFileSystemFixtures.overHostWithLowerFile("kyo-overlay-symlink-read-native")(use)

    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-symlink-lock-native")((overlay, root) => use(overlay, root / "target.bin"))

end OverlayFileSystemSymlinkNativeTest
