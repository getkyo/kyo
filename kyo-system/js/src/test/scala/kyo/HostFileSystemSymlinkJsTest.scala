package kyo

import kyo.internal.NodeFs

/** Runs the shared read contract against the host with symbolic links enabled, on Node.
  *
  * The JS backend is a separate implementation from the JVM one, with its own `realPath` and
  * `isSymbolicLink`. Running the same contract here is what keeps the symlink-escape fix asserted on
  * this platform rather than inferred from the JVM passing.
  */
class HostFileSystemSymlinkJsTest extends FileSystemReadTestSuite:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(link: Path, target: Path)(using Frame): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: creates a real symbolic link, which no Path operation exposes
        Sync.Unsafe.defer(NodeFs.symlinkSync(target.unsafe.show, link.unsafe.show))

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead

end HostFileSystemSymlinkJsTest

/** The same contract with a staged-write overlay installed over the host, on Node.
  *
  * The file is written to the lower rather than through the overlay: a file staged in the upper does
  * not exist on disk, so a link pointing at it would be dangling and the suite would assert against a
  * broken link instead of against the overlay's resolution.
  */
class OverlayFileSystemSymlinkJsTest extends FileSystemReadTestSuite:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(link: Path, target: Path)(using Frame): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: the link is created in the lower, beneath the overlay under test
        Sync.Unsafe.defer(NodeFs.symlinkSync(target.unsafe.show, link.unsafe.show))

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-overlay-symlink-read-js").map { (lower, root) =>
            val file = root / "read.txt"
            lower.write(file, "read-value", Path.WriteOptions()).andThen {
                FileSystem.overlay(lower).map(overlay => (overlay, file, "read-value"))
            }
        }

end OverlayFileSystemSymlinkJsTest
