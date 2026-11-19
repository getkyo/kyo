package kyo

import kyo.internal.NodeFs

/** Runs the shared read contract against the host with symbolic links enabled, on Node.
  *
  * The JS backend is a separate implementation from the JVM one, with its own `realPath` and
  * `isSymbolicLink`. Running the same contract here is what keeps the symlink-escape fix asserted on
  * this platform rather than inferred from the JVM passing.
  */
class HostFileSystemSymlinkJsTest extends FileSystemReadConformanceTest[Sync]:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(fileSystem: FileSystem.Read[Sync], link: Path, target: Path)(using
        Frame
    ): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: creates a real symbolic link, which no Path operation exposes
        Sync.Unsafe.defer(NodeFs.symlinkSync(target.unsafe.show, link.unsafe.show))

    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead.map(use.tupled)

    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostLockTarget.map(use.tupled)

end HostFileSystemSymlinkJsTest

/** The same contract with a staged-write overlay installed over the host, on Js. This is the configuration
  * that carried the original symlink-escape defect: an overlay whose own link answers shadowed the lower's.
  */
class OverlayFileSystemSymlinkJsTest extends FileSystemReadConformanceTest[Sync]:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(fileSystem: FileSystem.Read[Sync], link: Path, target: Path)(using
        Frame
    ): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: the link is created in the lower, beneath the overlay under test
        Sync.Unsafe.defer(NodeFs.symlinkSync(target.unsafe.show, link.unsafe.show))

    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHostWithLowerFile("kyo-overlay-symlink-read-js")(use)

    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-symlink-lock-js")((overlay, root) => use(overlay, root / "target.bin"))

end OverlayFileSystemSymlinkJsTest
