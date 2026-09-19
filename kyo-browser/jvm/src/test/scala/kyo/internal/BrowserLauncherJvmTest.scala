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
                case Result.Success(p) =>
                    fail(s"Expected BrowserSetupFailedException but createTempDir returned $p")
                case Result.Panic(ex) =>
                    fail(s"Expected Failure, got Panic: ${ex.getMessage}")
            }
        }
    }

    // `launch` registers the temp directory's removal before it spawns Chrome, and that removal sweeps by the directory's
    // unique name first, so a Chrome the spawn step handed to a continuation the stop dropped is still found and killed
    // when the scope closes. The rounds stop the launch at staggered sub-millisecond offsets from the step before it,
    // across the spawn and the port poll after it; each round's Chrome carries a unique flag Chrome ignores, so the
    // count afterwards is of this round's tree alone, and whatever the sweep missed is killed here.
    "a launch stopped around its spawn leaves no Chrome behind" in {
        assume(!Platform.isWindows, "POSIX process tree")
        val rounds = 40
        def alive(token: String): Int < Async =
            Abort.run[CommandException](Command("pgrep", "-f", token).textWithExitCode).map {
                case Result.Success((out, _)) => out.linesIterator.count(_.trim.nonEmpty)
                case _                        => 0
            }
        def kill(token: String): Unit < Async =
            Abort.run[CommandException](Command("pkill", "-9", "-f", token).textWithExitCode).unit
        SharedChrome.chromeConfig.map { base =>
            Loop.indexed { i =>
                if i >= rounds then Loop.done(succeed)
                else
                    val token     = s"--kyo-launch-probe-${UUID.randomUUID().toString.take(8)}"
                    val cfg       = base.copy(extraArgs = Chunk(token))
                    val launching = new java.util.concurrent.atomic.AtomicBoolean(false)
                    for
                        fiber <- Fiber.initUnscoped(Abort.run[BrowserSetupException](Scope.run(
                            Sync.defer(launching.set(true)).andThen(BrowserLauncher.launch(cfg)).andThen(Async.never)
                        )))
                        _ <- Sync.Unsafe.defer {
                            val bound = java.lang.System.nanoTime() + 200_000_000L
                            while !launching.get() && java.lang.System.nanoTime() < bound do ()
                            val target = java.lang.System.nanoTime() + (i % 40) * 500_000L
                            while java.lang.System.nanoTime() < target do ()
                            discard(fiber.unsafe.interrupt())
                        }
                        _    <- fiber.getResult
                        gone <- Abort.run[Timeout](Async.timeout(10.seconds)(assertEventually(alive(token).map(_ == 0))))
                        _    <- if gone.isSuccess then Kyo.unit else kill(token)
                    yield
                        assert(gone.isSuccess, s"round $i: a Chrome process outlived the launch that was stopped")
                        Loop.continue
                    end for
            }
        }
    }

    // terminateTree: Chrome's helpers (zygotes, GPU process, network service) outlive the main process by a few
    // milliseconds and write into the user-data-dir as they go down, so a removal that runs as soon as the main process
    // is dead can find the directory re-created behind it. The tree here has that shape: a parent whose background child
    // keeps re-creating a directory and survives the parent's death on its own.
    "terminateTree leaves no descendant alive to write into the directory" in {
        assume(!Platform.isWindows, "POSIX process tree")
        val outerTmp = Paths.get(java.lang.System.getProperty("java.io.tmpdir"))
        val dir      = outerTmp.resolve(s"kyo-browser-jvm-test-${UUID.randomUUID()}")
        val script   = s"mkdir -p '$dir'; (while true; do mkdir -p '$dir/x'; sleep 0.005; done) & wait"
        def removeDir: Unit < Async =
            Abort.run[FileSystemException](Path.run(Path(dir.toString).removeAll)).unit
        Scope.run {
            Scope.ensure(removeDir).andThen {
                for
                    proc <- Command("sh", "-c", script).spawnUnscoped
                    _    <- assertEventually(Sync.defer(Files.exists(dir.resolve("x"))))
                    _    <- BrowserLauncher.terminateTree(proc)
                    _    <- removeDir
                    _    <- Async.sleep(300.millis)
                yield assert(!Files.exists(dir), s"a descendant survived terminateTree and re-created $dir")
            }
        }
    }

end BrowserLauncherJvmTest
