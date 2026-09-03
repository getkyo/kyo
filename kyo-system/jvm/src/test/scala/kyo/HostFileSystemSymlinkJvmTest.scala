package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** Runs the shared read contract against the host with symbolic links enabled.
  *
  * The shared registrations cannot do this: link creation has no public operation, so it has to come
  * from a platform source set. Without a fixture that can create one, the suite's link assertions are
  * one-sided and a backend that stopped resolving links entirely would still pass.
  */
class HostFileSystemSymlinkJvmTest extends FileSystemReadTest:

    override protected def realPathRequiresExistence: Boolean = true
    override protected def supportsSymbolicLinks: Boolean     = true

    override protected def createSymbolicLink(link: Path, target: Path)(using Frame): Unit < (Sync & Abort[FileSystemException]) =
        // Unsafe: creates a real symbolic link, which no Path operation exposes
        Sync.Unsafe.defer {
            discard(JFiles.createSymbolicLink(
                JPaths.get(link.parts.mkString("/")),
                JPaths.get(target.parts.mkString("/"))
            ))
        }

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.hostRead

end HostFileSystemSymlinkJvmTest

/** The host lock registry enforces same-process exclusion through a map keyed by path, so two names
  * for one file are two entries unless the key resolves links first. Link creation has no public
  * operation, which is why this sits in a platform source set rather than in the shared lock suite.
  */
class HostFileSystemLockAliasJvmTest extends kyo.test.Test[Any]:

    "a symlinked alias of a path shares its lock registry entry" in {
        Scope.run {
            Path.run(Path.tempDir("host-lock-alias")).map { dir =>
                val target = dir / "real.bin"
                val alias  = dir / "link.bin"
                FileSystem.host.write(target, "contents", Path.WriteOptions()).andThen {
                    // Unsafe: creates a real symbolic link, which no Path operation exposes
                    Sync.Unsafe.defer {
                        discard(JFiles.createSymbolicLink(JPaths.get(alias.unsafe.show), JPaths.get(target.unsafe.show)))
                    }
                }.andThen {
                    // Two shared claims on one file are compatible, so both must be granted. Keyed on
                    // the name as written, the alias took an entry of its own, opened a second channel
                    // to the same file, and the JVM refused the overlapping claim. That is a denial of
                    // a lock the contract grants, not a conflict.
                    Scope.run {
                        FileSystem.host.tryLock(target, Path.LockMode.Shared).map { first =>
                            assert(first.isDefined, "the first shared claim on the target was refused")
                            FileSystem.host.tryLock(alias, Path.LockMode.Shared).map { second =>
                                assert(second.isDefined, "the alias was refused a shared claim its target already holds")
                            }
                        }
                    }
                }
            }
        }
    }
end HostFileSystemLockAliasJvmTest
