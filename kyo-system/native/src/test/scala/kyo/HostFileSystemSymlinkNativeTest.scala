package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** Runs the shared read contract against the host with symbolic links enabled, on Native.
  *
  * Native shares the jvm-native path implementation but not its test coverage: before this, no
  * Native test created a symbolic link, so the symlink-escape fix was asserted on one platform
  * and assumed on this one.
  */
class HostFileSystemSymlinkNativeTest extends FileSystemReadTest[Sync]:

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

end HostFileSystemSymlinkNativeTest
