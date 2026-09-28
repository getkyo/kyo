package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

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
