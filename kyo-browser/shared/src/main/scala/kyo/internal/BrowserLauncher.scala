package kyo.internal

import kyo.*

private[kyo] object BrowserLauncher:

    /** Name of the file under the user-data-dir that Chrome populates with the DevTools listener address once the debug server is up. Line
      * 1 is the port; line 2 is the WebSocket path (e.g. `/devtools/browser/<uuid>`).
      */
    private[kyo] val devToolsActivePortFile = "DevToolsActivePort"

    /** Launches a Chrome process and returns its CDP WebSocket URL.
      *
      * Uses Chrome's built-in `--remote-debugging-port=0` mode: Chrome picks a free port and writes the address to
      * `${user-data-dir}/${devToolsActivePortFile}`, which we poll for. The process is spawned via `Command.spawn`, which registers it with
      * the enclosing `Scope` for automatic termination. The user-data directory is created via `Path.tempDir` and removed on scope exit.
      *
      * Chrome's stderr is inherited from the parent process (`Command.inheritStderr`). The OS forwards Chrome's diagnostic output directly
      * to the test runner's terminal, so nothing on the JVM/Native/Node side has to consume it, and the OS pipe never fills, eliminating
      * the stderr-buffer-fill deadlock that would happen if the launcher only read up to the URL marker and then stopped.
      */
    def launch(config: Browser.LaunchConfig)(using Frame): String < (Async & Scope & Abort[BrowserSetupException]) =
        for
            tmpDir <- createTempDir
            _      <- Scope.ensure(removeTmpDir(tmpDir, config.tmpDirRemovalSchedule))
            proc   <- spawnChrome(config, tmpDir)
            url    <- pollDevToolsActivePort(tmpDir, config.launchTimeout, config.devToolsActivePortPollInterval)
        yield url
    end launch

    /** Best-effort recursive removal of the Chrome user-data temp directory.
      *
      * Chrome's helper process tree (renderer / GPU / network service) self-terminates asynchronously after the parent kill and keeps
      * writing into the user-data-dir until it does. Kill every process still bound to THIS dir first (matched by `killOrphans` on its
      * unique name), then remove. `removeAll` is retried for the brief OS file-reaping window. Residual failures are swallowed so a leaked
      * temp dir cannot fail a scope teardown.
      */
    private def removeTmpDir(tmpDir: Path, removalSchedule: Schedule)(using Frame): Unit < Async =
        tmpDir.name.fold(Kyo.unit)(name => killOrphans(pattern = name, command = "pgrep")).andThen {
            Abort.run[FileException] {
                Retry[FileException](removalSchedule)(Path.run(tmpDir.removeAll))
            }.map {
                case Result.Failure(err) =>
                    // Leaked tmp dirs are not a hard failure (they are cleaned up by `killOrphans` next launch),
                    // but the silent swallow makes debugging stuck test runs harder. Log so the cleanup decision is auditable.
                    Log.warn(s"removeTmpDir: failed to remove $tmpDir after retry schedule: ${err.getMessage}")
                case Result.Panic(ex) =>
                    Log.warn(s"removeTmpDir: panicked removing $tmpDir: ${ex.getMessage}")
                case Result.Success(_) => Kyo.unit
            }
        }

    /** Creates a fresh user-data temp directory for the Chrome process.
      *
      * The 0-arg form delegates to the JDK's default temp-directory placement via `Path.tempDir`. The 1-arg `parent` overload is a test
      * seam: it composes a unique child name under the supplied parent and calls `mkDir`, surfacing EACCES as
      * `BrowserSetupFailedException`. The seam type is `kyo.Path` (not `java.nio.file.Path`) so this file remains compilable across
      * JVM/JS/Native.
      */
    private[kyo] def createTempDir(using Frame): Path < (Sync & Scope & Abort[BrowserSetupException]) =
        Abort.recover[FileException] { (ex: FileException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException("failed to create Chrome user-data temp dir", ex)
            )
        } {
            Path.run(Path.tempDir("kyo-browser-"))
        }

    private[kyo] def createTempDir(parent: Path)(using Frame): Path < (Sync & Abort[BrowserSetupException]) =
        Abort.recover[FileException] { (ex: FileException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException("failed to create Chrome user-data temp dir", ex)
            )
        } {
            Random.nextLong.map { n =>
                val childName = f"kyo-browser-$n%016x"
                val target    = parent / childName
                Path.run(target.mkDir).andThen(target)
            }
        }

    private def spawnChrome(config: Browser.LaunchConfig, tmpDir: Path)(using
        Frame
    )
        : Process < (Sync & Scope & Abort[BrowserSetupException]) =
        val args = (config.executable +: chromiumFlags(tmpDir, config.headless)) ++ config.extraArgs
        Abort.recover[CommandException] { (ex: CommandException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException(s"failed to start ${config.executable}", ex)
            )
        } {
            Command(args*).inheritStderr.spawn.map { proc =>
                BrowserLauncherPlatform.registerShutdownHook(proc).andThen(proc)
            }
        }
    end spawnChrome

    /** Kills any Chrome processes from previous runs that were not cleaned up (e.g. after SIGKILL or abrupt JVM exit).
      *
      * Uses `pgrep -f` to match processes whose argv contains the user-data-dir prefix `pattern`, then sends SIGKILL to each. This is a
      * best-effort sweep: if `pgrep` is not found (Windows, minimal Docker) the call silently succeeds.
      *
      * The default `pattern = "kyo-browser-"` matches every Chrome user-data-dir this launcher creates and is what
      * `SharedChrome.ensureStarted` calls. The parameter is a test seam that lets a unique-tag fixture target only its own sentinel
      * processes without disturbing the shared Chrome instance. The `command` parameter is a second test seam that allows injecting an
      * absolute path to a non-existent binary to force the `CommandException`-swallow branch (verifying the no-op-when-pgrep-missing
      * contract); production callers rely on its default `"pgrep"`.
      */
    private[kyo] def killOrphans(
        pattern: String,
        command: String
    )(using Frame): Unit < Async =
        Abort.run[CommandException] {
            Command(command, "-f", s"user-data-dir=.*$pattern").text.map { output =>
                val pids = output.linesIterator.flatMap { line =>
                    val trimmed = line.trim
                    if trimmed.isEmpty then None
                    else trimmed.toIntOption
                }.toSeq
                Kyo.foreachDiscard(Chunk.from(pids)) { pid =>
                    Abort.run[CommandException](Command("kill", "-9", pid.toString).waitFor).unit
                }
            }
        }.unit

    private[kyo] def chromiumFlags(tmpDir: Path, headless: Boolean): Chunk[String] =
        Chunk(
            "--remote-debugging-port=0",
            "--enable-bidi",
            s"--user-data-dir=${tmpDir.toString}",
            if headless then "--headless=new" else "",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--disable-extensions",
            "--no-first-run",
            // Suppress Chrome's default startup window. `attachAndSetupTab` opens the automation target in
            // its own isolated browser context, so the default-context startup window would linger unused
            // for the whole session (an extra renderer, and a visible idle window in headed mode). The
            // remote-debugging server keeps Chrome alive with no window open, so nothing else is needed.
            "--no-startup-window",
            "--disable-background-networking",
            // Disable Chromium's headless-mode timer/wakeup throttling so setInterval/setTimeout
            // cadences in test pages and instrumentation are not clamped.
            "--disable-background-timer-throttling",
            "--disable-renderer-backgrounding",
            "--disable-features=IntensiveWakeUpThrottling",
            "--disable-default-apps",
            "--disable-sync",
            "--disable-translate",
            // Prevent Chrome from prompting for macOS Keychain access (would block on a system dialog).
            "--password-store=basic",
            "--use-mock-keychain",
            // Silence Chrome's own stderr at the source: with inheritStderr the parent JVM otherwise
            // sees per-launch `task_policy_set` warnings (macOS denies the priority-set syscall without
            // entitlements; Chrome falls back to default priority, no functional impact). `--log-level=3`
            // = FATAL only; everything below is dropped before being written to stderr.
            "--log-level=3"
        ).filter(_.nonEmpty)

    /** Pure parser for `DevToolsActivePort` content. Chrome writes a two-line file: line 1 is the port, line 2 is the WebSocket path
      * starting with `/devtools/browser/`. Returns the assembled `ws://127.0.0.1:<port><path>` URL or `Maybe.Absent` if the content is not
      * yet shaped that way (e.g. the file was sampled mid-write).
      */
    private[internal] def parseDevToolsActivePort(content: String): Maybe[String] =
        val lines = content.linesIterator.toSeq
        if lines.length < 2 then Maybe.empty
        else
            val portLine = lines(0).trim
            val pathLine = lines(1).trim
            try
                val port = portLine.toInt
                if pathLine.startsWith("/") then Maybe(s"ws://127.0.0.1:$port$pathLine")
                else Maybe.empty
            catch case _: NumberFormatException => Maybe.empty
            end try
        end if
    end parseDevToolsActivePort

    /** Polls `${tmpDir}/${devToolsActivePortFile}` until Chrome populates it with a complete two-line address, or `timeout` elapses. Chrome
      * writes the file as soon as the debug server is listening, so this is the canonical signal the launcher waits for.
      */
    private[kyo] def pollDevToolsActivePort(tmpDir: Path, timeout: Duration, pollInterval: Duration)(using
        Frame
    )
        : String < (Async & Abort[BrowserSetupException]) =
        val portFile = tmpDir / devToolsActivePortFile
        val poll: String < (Async & Abort[BrowserSetupException]) =
            Loop(()) { _ =>
                Abort.run[FileException](Path.runReadOnly(portFile.read)).map {
                    case Result.Success(content) =>
                        parseDevToolsActivePort(content) match
                            case Present(url) => Loop.done(url)
                            case Absent       => Async.delay(pollInterval)(Loop.continue(()))
                    case _ =>
                        // File not yet present (or transiently unreadable): retry until the timeout fires.
                        Async.delay(pollInterval)(Loop.continue(()))
                }
            }
        Abort.recover[Timeout] { (timeoutEx: Timeout) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException(
                    s"Chrome did not write $devToolsActivePortFile within $timeout (under $tmpDir)",
                    timeoutEx
                )
            )
        } {
            Async.timeout(timeout)(poll)
        }
    end pollDevToolsActivePort

end BrowserLauncher
