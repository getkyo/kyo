package kyo

class OverlayFileSystemDurabilityTest extends PathDurabilityTest:
    override private[kyo] def supportsDirectorySync: Boolean = true
    override private[kyo] def hostFileSystem(prefix: String)(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { lower =>
            val root = Path(prefix)
            lower.mkDir(root).andThen(FileSystem.overlay(lower).map(overlay => (overlay, root)))
        }
end OverlayFileSystemDurabilityTest
