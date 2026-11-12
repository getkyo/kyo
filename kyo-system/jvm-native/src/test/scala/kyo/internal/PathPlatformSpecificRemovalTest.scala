package kyo.internal

import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.attribute.PosixFilePermission
import kyo.*

class PathPlatformSpecificRemovalTest extends kyo.test.Test[Any]:

    "removeAll deletes a broken symbolic link" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-remove-broken-link")
                link = dir / "link"
                _      <- Sync.defer(Files.createSymbolicLink(link.toJava, (dir / "missing").toJava))
                _      <- link.removeAll
                exists <- link.exists(followLinks = false)
            yield assert(!exists)
        })
    }

    "removeAll deletes a directory link without traversing its target" in {
        Scope.run(Path.run {
            for
                dir <- Path.tempDir("kyo-remove-directory-link")
                link   = dir / "link"
                target = dir / "target"
                file   = target / "keep.txt"
                _        <- file.write("keep me")
                _        <- Sync.defer(Files.createSymbolicLink(link.toJava, target.toJava))
                _        <- link.removeAll
                exists   <- link.exists(followLinks = false)
                contents <- file.read
            yield
                assert(!exists)
                assert(contents == "keep me")
        })
    }

    "removeAll reports an inaccessible descendant without deleting it" in {
        Scope.run(Path.run {
            for
                _ <- Sync.defer {
                    if !FileSystems.getDefault.supportedFileAttributeViews().contains("posix") then
                        cancel("requires POSIX directory permissions")
                }
                dir <- Path.tempDir("kyo-remove-inaccessible")
                locked = dir / "locked"
                file   = locked / "keep.txt"
                _           <- file.write("keep me")
                permissions <- Sync.defer(Files.getPosixFilePermissions(locked.toJava))
                _           <- Scope.ensure(Sync.defer(Files.setPosixFilePermissions(locked.toJava, permissions)))
                _           <- Sync.defer(Files.setPosixFilePermissions(locked.toJava, java.util.Set.of[PosixFilePermission]()))
                _           <- Sync.defer {
                    if Files.isReadable(locked.toJava) then cancel("requires an unprivileged filesystem identity")
                }
                result   <- Abort.run[FileSystemException](Path.run(dir.removeAll))
                _        <- Sync.defer(Files.setPosixFilePermissions(locked.toJava, permissions))
                contents <- file.read
            yield
                result match
                    case Result.Failure(error: FileAccessDeniedException) => assert(error.path == dir)
                    case other                                            => fail(s"expected access denied, got $other")
                assert(contents == "keep me")
        })
    }
end PathPlatformSpecificRemovalTest
