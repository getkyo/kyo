package kyo.internal

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import kyo.*
import scalanative.posix.errno.EACCES
import scalanative.posix.errno.ENOENT
import scalanative.posix.errno.EXDEV
import scalanative.windows.ErrorCodes

class NioAtomicMovePlatformTest extends kyo.test.Test[Any]:

    final private case class Fixture(
        root: java.nio.file.Path,
        lockedDirectory: java.nio.file.Path,
        source: java.nio.file.Path,
        target: java.nio.file.Path,
        permissions: java.util.Set[PosixFilePermission]
    )

    private def cleanup(fixture: Fixture): Unit =
        Files.setPosixFilePermissions(fixture.lockedDirectory, fixture.permissions)
        Files.deleteIfExists(fixture.source)
        Files.deleteIfExists(fixture.target)
        Files.deleteIfExists(fixture.lockedDirectory)
        Files.deleteIfExists(fixture.root)
        ()
    end cleanup

    "Native atomic move recognizes only exact unsupported OS results" in {
        assert(NioAtomicMovePlatform.isAtomicMoveUnsupportedPosixError(EXDEV))
        assert(!NioAtomicMovePlatform.isAtomicMoveUnsupportedPosixError(EACCES))
        assert(NioAtomicMovePlatform.isAtomicMoveUnsupportedWindowsError(ErrorCodes.ERROR_NOT_SAME_DEVICE))
        assert(!NioAtomicMovePlatform.isAtomicMoveUnsupportedWindowsError(ErrorCodes.ERROR_ACCESS_DENIED))
    }

    "Native atomic move recognizes only exact missing OS results" in {
        assert(NioAtomicMovePlatform.isMissingPosixError(ENOENT))
        assert(!NioAtomicMovePlatform.isMissingPosixError(EACCES))
        assert(NioAtomicMovePlatform.isMissingWindowsError(ErrorCodes.ERROR_FILE_NOT_FOUND))
        assert(NioAtomicMovePlatform.isMissingWindowsError(ErrorCodes.ERROR_PATH_NOT_FOUND))
        assert(!NioAtomicMovePlatform.isMissingWindowsError(ErrorCodes.ERROR_ACCESS_DENIED))
    }

    "an inaccessible atomic-move source is not reported as missing" in {
        Scope.run {
            for
                fixture <- Sync.defer {
                    val root            = Files.createTempDirectory("kyo-native-atomic-access")
                    val lockedDirectory = Files.createDirectory(root.resolve("locked"))
                    val source          = Files.writeString(lockedDirectory.resolve("source.txt"), "content")
                    val target          = root.resolve("target.txt")
                    Fixture(root, lockedDirectory, source, target, Files.getPosixFilePermissions(lockedDirectory))
                }
                _            <- Scope.ensure(Sync.defer(cleanup(fixture)))
                _            <- Sync.defer(Files.setPosixFilePermissions(fixture.lockedDirectory, java.util.Set.of()))
                inaccessible <- Sync.defer(!Files.exists(fixture.source, LinkOption.NOFOLLOW_LINKS))
                result <- Abort.run[FileSystemException] {
                    Path.run(Path.of(fixture.source).move(Path.of(fixture.target), Path.MoveOptions(atomicity = Path.Atomicity.Required)))
                }
            yield
                assert(inaccessible)
                result match
                    case Result.Failure(_: FileAccessDeniedException) => succeed("access denied retained")
                    case Result.Failure(_: FileIOException)           => succeed("precise OS failure retained")
                    case Result.Failure(_: FileNotFoundException)     => fail("inaccessible source was reported as missing")
                    case other                                        => fail(s"Expected an access failure, got $other")
                end match
            end for
        }
    }

end NioAtomicMovePlatformTest
