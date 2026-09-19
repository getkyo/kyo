package kyo

import kyo.internal.doltlite.DoltLiteEngineProbe

/** The Dolt filesystem put through the same four suites the host answers.
  *
  * Every fixture runs against a real DoltLite engine over `:memory:` with a single connection, without which
  * `:memory:` names one database per connection rather than one shared database.
  *
  * The engine is published for some platforms and not others, so each suite cancels where it is absent rather
  * than reporting a declared absence as a filesystem defect. Its unavailability contract is asserted on its own
  * in `DoltLiteClientTest`.
  */
private object DoltFileSystemFixtures:

    /** Opens a client, builds a filesystem on it, and hands both to `use` under a fresh root. */
    def withVfs[A](root: String)(
        use: (DoltFileSystem, Path) => A < (Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        translated {
            SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    Dolt.use { dolt =>
                        DoltFileSystem.init(dolt).map { files =>
                            val base = Path(root)
                            files.mkDir(base).andThen(use(files, base))
                        }
                    }
                }
            }
        }

    /** A filesystem holding one file, for the read suite. */
    def withReadable[A](
        use: (DoltFileSystem, Path, String) => A < (Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        withVfs("read-suite") { (files, root) =>
            val file = root / "read.txt"
            files.write(file, "read-value", Path.WriteOptions()).andThen(use(files, file, "read-value"))
        }

    private def translated[A](body: A < (Async & Scope & Abort[FileSystemException] & Abort[SqlException]))(using
        Frame
    ): A < (Async & Scope & Abort[FileSystemException]) =
        Abort.recover[SqlException](e => Abort.fail(FileIOException(Path(), FileSystemOperation.Read, e)))(body)

end DoltFileSystemFixtures

class DoltFileSystemReadConformanceTest extends FileSystemReadTest[Async]:

    override protected def realPathRequiresExistence: Boolean = true

    override protected def supportsSymbolicLinks: Boolean = true

    override protected def createSymbolicLink(fileSystem: FileSystem.Read[Async], link: Path, target: Path)(using
        Frame
    ): Unit < (Async & Sync & Abort[FileSystemException]) =
        fileSystem match
            case files: DoltFileSystem => files.symlink(link, target)
            case other                 => Abort.panic(new IllegalStateException(s"expected a DoltFileSystem, got $other"))

    protected def withFileSystem[A](
        use: (FileSystem.Read[Async], Path, String) => A < (Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        DoltFileSystemFixtures.withReadable(use)
    end withFileSystem
end DoltFileSystemReadConformanceTest

class DoltFileSystemWriteConformanceTest extends FileSystemWriteTest[Async]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Async], Path) => A < (Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        DoltFileSystemFixtures.withVfs("write-suite")(use)
    end withFileSystem
end DoltFileSystemWriteConformanceTest

class DoltFileSystemChannelConformanceTest extends FileSystemChannelTest[Async]:
    protected def withFileSystem[A](
        use: (FileSystem.Write[Async], Path) => A < (Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException]) =
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        DoltFileSystemFixtures.withVfs("channel-suite")(use)
    end withFileSystem
end DoltFileSystemChannelConformanceTest

class DoltFileSystemLockConformanceTest extends FileSystemLockTest[Async]:
    protected def withFileSystem(
        use: (FileSystem.Read[Async], Path) => Unit < (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        DoltFileSystemFixtures.withVfs("lock-suite")((files, root) => use(files, root / "target.bin"))
    end withFileSystem
end DoltFileSystemLockConformanceTest
