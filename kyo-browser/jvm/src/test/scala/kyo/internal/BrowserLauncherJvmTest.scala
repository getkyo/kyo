package kyo.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kyo.*

/** JVM-only scenarios for [[BrowserLauncher]].
  *
  * Lives in the JVM test tree because it drives `BrowserLauncher.createTempDir`'s read-only-parent abort path via the JVM-only
  * `java.nio.file.Files` / `java.nio.file.attribute.PosixFilePermissions` APIs, which have no Scala.js shim.
  */
class BrowserLauncherJvmTest extends BaseBrowserTest:

    // createTempDir failure path: point at a read-only parent directory; assert Abort shape.
    "createTempDir aborts with BrowserSetupFailedException when the temp parent is not writable" in {
        // POSIX permissions throw UnsupportedOperationException on Windows, and NTFS does not
        // block an owner from creating entries in a read-only directory anyway, so the
        // unwritable-parent scenario is expressible on POSIX hosts only.
        assume(!Platform.isWindows, "POSIX directory permissions")
        assume(java.lang.System.getProperty("user.name") != "root", "a non-root user, for whom a read-only parent refuses writes")
        val outerTmp = Paths.get(java.lang.System.getProperty("java.io.tmpdir"))
        val parent   = Files.createTempDirectory(outerTmp, s"kyo-browser-jvm-test-${UUID.randomUUID()}-")

        val readOnly = PosixFilePermissions.fromString("r-x------")
        val writable = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(parent, readOnly)

        Sync.ensure(
            Sync.defer {
                try Files.setPosixFilePermissions(parent, writable)
                catch case _: Throwable => ()
                try Files.deleteIfExists(parent)
                catch case _: Throwable => ()
            }
        ) {
            val kyoParent = Path(parent.toString)
            Abort.run[BrowserSetupException] {
                BrowserLauncher.createTempDir(kyoParent)
            }.map {
                case Result.Failure(ex: BrowserSetupFailedException) => assert(ex.getMessage.contains("temp dir"))
                case Result.Success(p)                               =>
                    fail(s"Expected BrowserSetupFailedException but createTempDir returned $p")
                case Result.Panic(ex) =>
                    fail(s"Expected Failure, got Panic: ${ex.getMessage}")
            }
        }
    }

    // The Chrome carries a unique flag Chrome ignores, so the count is of this launch's tree alone. The token starts with dashes, so it follows a `--`: without it
    // `pgrep` rejects the pattern as an option, prints nothing, and the count reads zero whatever is running.
    "a launch stopped while its Chrome is up leaves no Chrome behind".times(40) in {
        assume(!Platform.isWindows, "POSIX process tree")
        val token                                          = s"--kyo-launch-probe-${UUID.randomUUID().toString.take(8)}"
        def alive: Int < (Async & Abort[CommandException]) =
            Command("pgrep", "-f", "--", token).textWithExitCode.map {
                case (out, ExitCode.Success)  => out.linesIterator.count(_.trim.nonEmpty)
                case (_, ExitCode.Failure(1)) => 0
                case (out, code)              => fail(s"pgrep could not count the launch's processes: $code $out")
            }
        // A Chrome this leaf fails to reap would otherwise run for the rest of the suite.
        def kill: Unit < Async =
            Abort.run[CommandException](Command("pkill", "-9", "-f", "--", token).textWithExitCode).unit
        Abort.run[BrowserSetupException](SharedChrome.chromeConfig).map { obtained =>
            val cfg = obtained match
                case Result.Success(base) => base.copy(extraArgs = Chunk(token))
                case other                => cancel(s"no Chrome to launch here: $other")
            Scope.run(Scope.ensure(kill).andThen {
                for
                    fiber <- Fiber.initUnscoped(Abort.run[BrowserSetupException](Scope.run(
                        BrowserLauncher.launch(cfg).andThen(Async.never)
                    )))
                    _ <- assertEventually(alive.map(_ > 0))
                    _ <- fiber.interrupt
                    _ <- fiber.getResult
                    _ <- assertEventually(alive.map(_ == 0))
                yield succeed
            })
        }
    }

    // Chrome's helpers (zygotes, GPU process, network service) outlive the main process by a few milliseconds and write
    // into the user-data-dir as they go down, so a removal that runs as soon as the main process is dead can find the
    // directory re-created behind it. `terminateTree` returns once none of the tree is left, so the survivors are counted
    // the moment it returns. The quoted `mkdir` text picks out the shell and its subshell, whose argv carry the script:
    // the `mkdir` each iteration forks has the path unquoted in its own argv and is not part of the tree.
    "terminateTree leaves no descendant alive to write into the directory" in {
        assume(!Platform.isWindows, "POSIX process tree")
        val outerTmp                                           = Paths.get(java.lang.System.getProperty("java.io.tmpdir"))
        val dir                                                = outerTmp.resolve(s"kyo-browser-jvm-test-${UUID.randomUUID()}")
        val step                                               = s"mkdir -p '$dir/x'"
        val script                                             = s"mkdir -p '$dir'; (while true; do $step; sleep 0.005; done) & wait"
        def survivors: Int < (Async & Abort[CommandException]) =
            Command("pgrep", "-f", "--", step).textWithExitCode.map {
                case (out, ExitCode.Success)  => out.linesIterator.count(_.trim.nonEmpty)
                case (_, ExitCode.Failure(1)) => 0
                case (out, code)              => fail(s"pgrep could not count the tree's processes: $code $out")
            }
        def removeDir: Unit < Async =
            Abort.run[FileSystemException](Path.run(Path(dir.toString).removeAll)).unit
        Scope.run {
            Scope.ensure(removeDir).andThen {
                for
                    proc   <- Command("sh", "-c", script).spawnUnscoped
                    _      <- assertEventually(Sync.defer(Files.exists(dir.resolve("x"))))
                    before <- survivors
                    _      <- BrowserLauncher.terminateTree(proc)
                    left   <- survivors
                yield
                    assert(before > 0, "the process count never saw the tree, so a zero afterwards would prove nothing")
                    assert(left == 0, s"terminateTree returned with $left process(es) of the tree still alive")
            }
        }
    }

end BrowserLauncherJvmTest
