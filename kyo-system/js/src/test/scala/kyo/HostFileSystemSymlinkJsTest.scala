package kyo

import kyo.internal.NodeFs

/** Runs the shared read contract against the host with symbolic links enabled, on Node.
  *
  * The JS backend is a separate implementation from the JVM one, with its own `realPath` and
  * `isSymbolicLink`. Running the same contract here is what keeps the symlink-escape fix asserted on
  * this platform rather than inferred from the JVM passing.
  */
class HostFileSystemSymlinkJsTest extends FileSystemReadTest:

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
