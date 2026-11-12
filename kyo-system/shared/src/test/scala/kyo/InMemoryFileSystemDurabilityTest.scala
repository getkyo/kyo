package kyo

class InMemoryFileSystemDurabilityTest extends PathDurabilityTest:
    override private[kyo] def supportsDirectorySync: Boolean = true
    override private[kyo] def hostFileSystem(prefix: String)(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { fs =>
            val root = Path(prefix)
            fs.mkDir(root).map(_ => (fs, root))
        }
end InMemoryFileSystemDurabilityTest
