package kyo

/** The overlay put through every tier, over an in-memory lower and over a root-confined host lower. Writes stage in the overlay's
  * upper layer and reach the lower only on commit, so the read and lock fixtures write through the overlay itself.
  */
private[kyo] object OverlayFileSystemFixtures:

    def overInMemory[A](
        use: (FileSystem.Write[Sync] & FileSystem.Watch & FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { lower =>
            val root = Path("overlay-conformance")
            lower.mkDir(root).andThen(FileSystem.overlay(lower).map(overlay => use(overlay, root)))
        }

    def overHost[A](prefix: String)(
        use: (FileSystem.Write[Sync] & FileSystem.Watch & FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host(prefix).map { (_, root) =>
            FileSystem.host(root).map(lower => FileSystem.overlay(lower).map(overlay => use(overlay, root)))
        }

    /** An overlay over the host whose lower already holds `root/read.txt`. The file is written to the lower rather than through
      * the overlay, so a link a platform test creates beside it points at a file that exists on disk.
      */
    def overHostWithLowerFile[A](prefix: String)(
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host(prefix).map { (lower, root) =>
            val file = root / "read.txt"
            lower.write(file, "read-value", Path.WriteOptions()).andThen {
                FileSystem.overlay(lower).map(overlay => use(overlay, file, "read-value"))
            }
        }

end OverlayFileSystemFixtures

class OverlayFileSystemReadConformanceTest extends FileSystemReadConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overInMemory { (overlay, root) =>
            val file = root / "read.txt"
            overlay.write(file, "read-value", Path.WriteOptions()).andThen(use(overlay, file, "read-value"))
        }
    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overInMemory((overlay, root) => use(overlay, root / "target.bin"))
end OverlayFileSystemReadConformanceTest

class OverlayFileSystemWriteConformanceTest extends FileSystemWriteConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overInMemory(use)
end OverlayFileSystemWriteConformanceTest

class OverlayFileSystemWatchConformanceTest extends FileSystemWatchConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync] & FileSystem.Watch, Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overInMemory(use)
end OverlayFileSystemWatchConformanceTest

class OverlayFileSystemStagedChangesConformanceTest extends FileSystemStagedChangesConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overInMemory((overlay, root) => use(overlay, overlay, root))
end OverlayFileSystemStagedChangesConformanceTest

class OverlayOverHostFileSystemReadConformanceTest extends FileSystemReadConformanceTest[Sync]:
    override protected def realPathRequiresExistence: Boolean = true
    protected def withFileSystem[A](
        use: (FileSystem.Read[Sync], Path, String) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-host-read-suite") { (overlay, root) =>
            val file = root / "read.txt"
            overlay.write(file, "read-value", Path.WriteOptions()).andThen(use(overlay, file, "read-value"))
        }
    protected def withLockTarget(
        use: (FileSystem.Read[Sync], Path) => Unit < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-host-lock-suite")((overlay, root) => use(overlay, root / "target.bin"))
end OverlayOverHostFileSystemReadConformanceTest

class OverlayOverHostFileSystemWriteConformanceTest extends FileSystemWriteConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-host-write-suite")(use)
end OverlayOverHostFileSystemWriteConformanceTest

class OverlayOverHostFileSystemWatchConformanceTest extends FileSystemWatchConformanceTest[Sync]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync] & FileSystem.Watch, Path) => A < (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-host-watch-suite")(use)
end OverlayOverHostFileSystemWatchConformanceTest

class OverlayOverHostFileSystemStagedChangesConformanceTest extends FileSystemStagedChangesConformanceTest[Sync]:
    // Windows offers no directory synchronization, so a commit to the host there is refused rather than claimed.
    override protected def supportsCommit: Boolean = !kyo.internal.Platform.isWindows
    protected def withFileSystem[A](
        use: (FileSystem.Write[Sync], FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        OverlayFileSystemFixtures.overHost("kyo-overlay-host-staged-suite")((overlay, root) => use(overlay, overlay, root))
end OverlayOverHostFileSystemStagedChangesConformanceTest
