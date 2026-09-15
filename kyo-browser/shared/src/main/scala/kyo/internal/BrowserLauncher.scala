package kyo.internal

import kyo.*

private[kyo] object BrowserLauncher:

    /** Name of the file under the user-data-dir that Chrome populates with the DevTools listener address once the debug server is up. Line
      * 1 is the port; line 2 is the WebSocket path (e.g. `/devtools/browser/<uuid>`).
      */
    private[kyo] val devToolsActivePortFile = "DevToolsActivePort"

    /** Prefix of every Chrome user-data directory this launcher creates. The name continues with the id of the process that owns the launch
      * (`kyo-browser-<pid>-<random>`), which is how [[killOrphans]] tells a Chrome whose run is still alive from one left behind.
      */
    private[kyo] val userDataDirPrefix = "kyo-browser-"

    /** Launches a Chrome process and returns its CDP WebSocket URL.
      *
      * Uses Chrome's built-in `--remote-debugging-port=0` mode: Chrome picks a free port and writes the address to
      * `${user-data-dir}/${devToolsActivePortFile}`, which we poll for. The process is spawned via `Command.spawn`, which registers it with
      * the enclosing `Scope` for automatic termination. The user-data directory is created via `Path.tempDir`, named after this process
      * (see [[userDataDirPrefix]]), and removed on scope exit.
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
      * writing into the user-data-dir until it does. Kill every process still bound to THIS dir first (matched by `killMatching` on its
      * unique name, without the owner check `killOrphans` applies, since this process is the owner), then remove. `removeAll` is retried
      * for the brief OS file-reaping window. Residual failures are swallowed so a leaked temp dir cannot fail a scope teardown.
      */
    private def removeTmpDir(tmpDir: Path, removalSchedule: Schedule)(using Frame): Unit < Async =
        tmpDir.name.fold(Kyo.unit)(name => killMatching(pattern = name, command = "pgrep")).andThen {
            Abort.run[FileSystemException] {
                Retry[FileSystemException](removalSchedule)(Path.run(tmpDir.removeAll))
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

    /** Creates a fresh user-data temp directory for the Chrome process, named after this process (see [[userDataDirPrefix]]).
      *
      * The 0-arg form delegates to the JDK's default temp-directory placement via `Path.tempDir`. The 1-arg `parent` overload is a test
      * seam: it composes a unique child name under the supplied parent and calls `mkDir`, surfacing EACCES as
      * `BrowserSetupFailedException`. The seam type is `kyo.Path` (not `java.nio.file.Path`) so this file remains compilable across
      * JVM/JS/Native.
      */
    private[kyo] def createTempDir(using Frame): Path < (Sync & Scope & Abort[BrowserSetupException]) =
        Abort.recover[FileSystemException] { (ex: FileSystemException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException("failed to create Chrome user-data temp dir", ex)
            )
        } {
            BrowserProcessId.current.map(pid => Path.run(Path.tempDir(s"$userDataDirPrefix$pid-")))
        }

    private[kyo] def createTempDir(parent: Path)(using Frame): Path < (Sync & Abort[BrowserSetupException]) =
        Abort.recover[FileSystemException] { (ex: FileSystemException) =>
            Abort.fail[BrowserSetupException](
                BrowserSetupFailedException("failed to create Chrome user-data temp dir", ex)
            )
        } {
            for
                pid <- BrowserProcessId.current
                n   <- Random.nextLong
                target = parent / f"$userDataDirPrefix$pid-$n%016x"
                _ <- Path.run(target.mkDir)
            yield target
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

    /** Kills Chrome processes left behind by runs that are gone (e.g. after SIGKILL or an abrupt exit).
      *
      * Uses `pgrep -f` to list the processes whose argv contains a user-data-dir matching `pattern`. A candidate is killed only when its
      * run is gone: the directory names its owning process (see [[userDataDirPrefix]]) and that process is no longer running, or the
      * directory names no owner (one created by an older launcher) and the process that launched the candidate has exited, leaving it
      * adopted by pid 1. A Chrome whose run is alive is left running, whichever process on the machine owns it: a second test JVM, a Node
      * test run, or a browser test runner each sweep at startup, and none may kill another's Chrome. A candidate's parent and argv come
      * from `ps -o ppid= -o args=` and an owner's liveness from `ps -o pid=`; when either cannot be read the candidate is left alone. This
      * is a best-effort sweep: if `pgrep` is not found (Windows, minimal Docker) the call silently succeeds.
      *
      * `SharedChrome.ensureStarted` calls it with [[userDataDirPrefix]]. The `pattern` parameter is a test seam that lets a unique-tag
      * fixture target only its own sentinel processes. The `command` parameter is a second test seam that allows injecting an absolute path
      * to a non-existent binary to force the `CommandException`-swallow branch (verifying the no-op-when-pgrep-missing contract);
      * production callers pass `"pgrep"`.
      */
    private[kyo] def killOrphans(
        pattern: String,
        command: String
    )(using Frame): Unit < Async =
        matchingPids(pattern, command).map { pids =>
            Kyo.foreachDiscard(pids) { pid =>
                isLeftBehind(pid).map(leftBehind => if leftBehind then kill(pid) else Kyo.unit)
            }
        }

    /** Kills every process whose argv contains a user-data-dir matching `pattern`, with no owner check. A launch uses it on its own
      * directory during teardown, where this process is the owner. Silently succeeds without `pgrep`, like [[killOrphans]].
      */
    private[kyo] def killMatching(
        pattern: String,
        command: String
    )(using Frame): Unit < Async =
        matchingPids(pattern, command).map(pids => Kyo.foreachDiscard(pids)(kill))

    private def matchingPids(pattern: String, command: String)(using Frame): Chunk[Long] < Async =
        Abort.run[CommandException](Command(command, "-f", s"user-data-dir=.*$pattern").text).map {
            case Result.Success(output) => pidsIn(output)
            case _                      => Chunk.empty
        }

    private def pidsIn(output: String): Chunk[Long] =
        Chunk.from(output.linesIterator.flatMap(_.trim.toLongOption).toSeq)

    private def kill(pid: Long)(using Frame): Unit < Async =
        Abort.run[CommandException](Command("kill", "-9", pid.toString).waitFor).unit

    /** Whether the candidate `pid` belongs to a run that is gone. False when its process line cannot be read (it exited, or `ps` is
      * missing).
      */
    private def isLeftBehind(pid: Long)(using Frame): Boolean < Async =
        Abort.run[CommandException](Command("ps", "-ww", "-o", "ppid=", "-o", "args=", "-p", pid.toString).text).map {
            case Result.Success(line) =>
                processLine(line) match
                    case Present((parent, args)) =>
                        userDataDirOwner(args) match
                            case Present(owner) => isRunning(owner).map(running => !running)
                            // No owner in the name: the Chrome is left behind once the process that launched it has exited
                            // and it has been adopted by pid 1.
                            case Absent => parent == 1L
                    case Absent => false
            case _ => false
        }

    /** Splits a `ps -o ppid= -o args=` line into the parent pid and the argv. */
    private[internal] def processLine(line: String): Maybe[(Long, String)] =
        val trimmed = line.trim
        val space   = trimmed.indexWhere(_.isWhitespace)
        if space < 0 then Absent
        else Maybe.fromOption(trimmed.substring(0, space).toLongOption).map(parent => (parent, trimmed.substring(space).trim))
    end processLine

    /** Whether process `pid` exists. True when that cannot be read, so an unreadable owner is never taken for a gone one. */
    private def isRunning(pid: Long)(using Frame): Boolean < Async =
        Abort.run[CommandException](Command("ps", "-o", "pid=", "-p", pid.toString).text).map {
            case Result.Success(output) => pidsIn(output).contains(pid)
            case _                      => true
        }

    private val userDataDirArg = "--user-data-dir=(.*?)(?=\\s+--|\\s*$)".r
    private val ownedDirName   = s"^${java.util.regex.Pattern.quote(userDataDirPrefix)}(\\d+)-".r

    /** The owning process id named by the `--user-data-dir` in a process argv, or `Absent` when the argv has no such flag or its directory
      * name carries no owner.
      */
    private[internal] def userDataDirOwner(args: String): Maybe[Long] =
        Maybe.fromOption(userDataDirArg.findFirstMatchIn(args)).flatMap { m =>
            val dir  = m.group(1)
            val name = dir.substring(math.max(dir.lastIndexOf('/'), dir.lastIndexOf('\\')) + 1)
            Maybe.fromOption(ownedDirName.findFirstMatchIn(name).flatMap(owner => owner.group(1).toLongOption))
        }

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
                Abort.run[FileSystemException](Path.runReadOnly(portFile.read)).map {
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
