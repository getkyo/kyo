package kyo.internal

import kyo.*

class BrowserLauncherTest extends BaseChromeTest:

    override def timeout = 2.minutes

    "launch returns wsUrl starting with ws://" in {
        Scope.run {
            SharedChrome.chromeConfig.map { cfg =>
                BrowserLauncher.launch(cfg).map { wsUrl =>
                    assert(wsUrl.startsWith("ws://"))
                }
            }
        }
    }

    "wsUrl contains host and port" in {
        Scope.run {
            SharedChrome.chromeConfig.map { cfg =>
                BrowserLauncher.launch(cfg).map { wsUrl =>
                    // Chrome emits the URL using 127.0.0.1 (not localhost). Accept either.
                    assert(wsUrl.contains("127.0.0.1") || wsUrl.contains("localhost"))
                    assert(wsUrl.startsWith("ws://"))
                }
            }
        }
    }

    "two concurrent launches use different ports" in {
        Scope.run {
            SharedChrome.chromeConfig.map { cfg =>
                Async.zip(
                    BrowserLauncher.launch(cfg),
                    BrowserLauncher.launch(cfg)
                ).map { (wsUrl1, wsUrl2) =>
                    assert(wsUrl1.startsWith("ws://"))
                    assert(wsUrl2.startsWith("ws://"))
                    assert(wsUrl1 != wsUrl2, s"Expected different URLs, got: $wsUrl1 and $wsUrl2")
                }
            }
        }
    }

    "invalid executable fails with BrowserSetupException" in {
        Abort.run[BrowserSetupException] {
            Scope.run {
                BrowserLauncher.launch(
                    Browser.LaunchConfig.chromium("/nonexistent/browser/executable").launchTimeout(2.seconds)
                )
            }
        }.map {
            case Result.Success(_)                               => fail("Expected failure for invalid executable")
            case Result.Failure(ex: BrowserSetupFailedException) => assert(ex.getMessage.contains("/nonexistent/browser/executable"))
            case Result.Panic(ex)                                => fail(s"Expected Failure, got Panic: ${ex.getMessage}")
        }
    }

    "very short timeout fails fast" in {
        for
            result <- Abort.run[BrowserSetupException] {
                Scope.run {
                    BrowserLauncher.launch(
                        Browser.LaunchConfig.chromium("/nonexistent/browser").launchTimeout(500.millis)
                    )
                }
            }
        yield
            // A nonexistent executable fails at spawn immediately (not a launch-timeout path), so the typed
            // BrowserSetupFailedException IS the property. A genuine hang is caught by the per-leaf timeout; no wall-clock envelope.
            result match
                case Result.Success(_)                              => fail("Expected failure for invalid executable")
                case Result.Failure(_: BrowserSetupFailedException) => succeed
                case Result.Panic(ex)                               => fail(s"Expected Failure, got Panic: ${ex.getMessage}")
            end match
    }

    "extraArgs are passed through" in {
        Scope.run {
            SharedChrome.chromeConfig.map { cfg =>
                val config = cfg.copy(extraArgs = Chunk("--disable-field-trial-config"))
                BrowserLauncher.launch(config).map { wsUrl =>
                    assert(wsUrl.startsWith("ws://"))
                }
            }
        }
    }

    // --- DevToolsActivePort parser ---

    // Chrome writes a 2-line file: line 1 is the port (decimal), line 2 is the WS path
    // beginning with "/devtools/browser/<uuid>". `parseDevToolsActivePort` assembles the
    // ws:// URL from this content; the launcher polls the file until a valid pair appears.
    "parseDevToolsActivePort assembles ws://host:port<path> from a valid two-line content" in {
        val content  = "9222\n/devtools/browser/2c1c1f1f-aaaa-bbbb-cccc-1234567890ab\n"
        val expected = "ws://127.0.0.1:9222/devtools/browser/2c1c1f1f-aaaa-bbbb-cccc-1234567890ab"
        assert(BrowserLauncher.parseDevToolsActivePort(content) == Maybe(expected))
    }

    "parseDevToolsActivePort returns Absent when content has fewer than two lines" in {
        // File sampled mid-write: only the port has been flushed.
        assert(BrowserLauncher.parseDevToolsActivePort("9222\n") == Maybe.empty)
        assert(BrowserLauncher.parseDevToolsActivePort("") == Maybe.empty)
    }

    "parseDevToolsActivePort returns Absent when port line is not numeric" in {
        // Defensive: Chrome occasionally writes garbage if the listener fails to bind.
        val content = "garbage\n/devtools/browser/uuid\n"
        assert(BrowserLauncher.parseDevToolsActivePort(content) == Maybe.empty)
    }

    "parseDevToolsActivePort returns Absent when path line does not start with '/'" in {
        val content = "9222\nnot-a-path\n"
        assert(BrowserLauncher.parseDevToolsActivePort(content) == Maybe.empty)
    }

    // chromiumFlags(headless=false) excludes --headless and includes documented flags.
    "chromiumFlags(headless = false) excludes --headless and includes non-headless flags" in {
        val flags = BrowserLauncher.chromiumFlags(Path("tmp", "kyo-browser-fixture"), headless = false)
        // No --headless flag (any variant) should be present.
        assert(!flags.exists(_.startsWith("--headless")), s"--headless should be absent but got: $flags")
        // Documented non-headless flags must be present.
        val expectedAlways = Set(
            "--remote-debugging-port=0",
            "--enable-bidi",
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--disable-extensions",
            "--no-first-run",
            "--no-startup-window",
            "--disable-background-networking",
            "--disable-default-apps",
            "--disable-sync",
            "--disable-translate",
            "--password-store=basic",
            "--use-mock-keychain"
        )
        for f <- expectedAlways do
            assert(flags.contains(f), s"missing flag $f in $flags")
        // user-data-dir flag is present and points to the fixture path.
        assert(flags.exists(_.startsWith("--user-data-dir=")), s"--user-data-dir flag missing: $flags")
    }

    // Pin the Chrome anti-throttling flags by exact string match. These three flags are load-bearing for
    // timing-sensitive tests; if a future BrowserLauncher refactor silently drops them, affected tests would
    // flake intermittently rather than fail loudly. We assert each flag is present via `.contains` of the
    // exact string against the args Chunk (NOT a substring of the joined args), so a typo or partial-flag drop is caught.
    "chromiumFlags pins the three Chrome anti-throttling flags by exact string match" in {
        val flags = BrowserLauncher.chromiumFlags(Path("tmp", "kyo-browser-fixture"), headless = true)
        assert(
            flags.contains("--disable-background-timer-throttling"),
            s"--disable-background-timer-throttling missing from chromiumFlags args: $flags"
        )
        assert(
            flags.contains("--disable-renderer-backgrounding"),
            s"--disable-renderer-backgrounding missing from chromiumFlags args: $flags"
        )
        assert(
            flags.contains("--disable-features=IntensiveWakeUpThrottling"),
            s"--disable-features=IntensiveWakeUpThrottling missing from chromiumFlags args: $flags"
        )
    }

    "chromiumFlags(headless = false) also pins the three anti-throttling flags (the throttling flags are not headless-conditional)" in {
        val flags = BrowserLauncher.chromiumFlags(Path("tmp", "kyo-browser-fixture"), headless = false)
        assert(flags.contains("--disable-background-timer-throttling"), s"flags=$flags")
        assert(flags.contains("--disable-renderer-backgrounding"), s"flags=$flags")
        assert(flags.contains("--disable-features=IntensiveWakeUpThrottling"), s"flags=$flags")
    }

    // Genuine-timeout path. pollDevToolsActivePort against a tmpDir where Chrome never writes the
    // file must time out within the configured budget and surface BrowserSetupFailedException.
    "pollDevToolsActivePort times out when DevToolsActivePort is never written" in {
        val timeout = 200.millis
        Scope.run {
            for
                tmp     <- Path.run(Path.tempDir("kyo-browser-pollDevTools-test-"))
                outcome <- Abort.run[BrowserSetupException] {
                    BrowserLauncher.pollDevToolsActivePort(tmp, timeout, 50.millis)
                }
            yield
                outcome match
                    case Result.Success(url) =>
                        fail(s"Expected timeout but pollDevToolsActivePort returned $url")
                    case Result.Failure(_: BrowserSetupFailedException) =>
                        ()
                    case Result.Panic(ex) =>
                        fail(s"Expected Failure, got Panic: ${ex.getMessage}")
                end match
        }
    }

    // Happy-path. When the DevToolsActivePort file IS present with valid content, the poller returns
    // the assembled ws:// URL without running the timeout.
    "pollDevToolsActivePort returns the URL when DevToolsActivePort exists and is well-formed" in {
        Scope.run {
            for
                tmp <- Path.run(Path.tempDir("kyo-browser-pollDevTools-happy-"))
                _   <- Path.run((tmp / BrowserLauncher.devToolsActivePortFile).write("9222\n/devtools/browser/test-uuid\n"))
                url <- BrowserLauncher.pollDevToolsActivePort(tmp, 5.seconds, 50.millis)
            yield assert(url == "ws://127.0.0.1:9222/devtools/browser/test-uuid")
        }
    }

    // killOrphans pgrep-missing; exercises the CommandException-swallow branch in
    // BrowserLauncher.scala (the `Abort.run[CommandException]` wrapping `Command(command, ...).text`).
    "killOrphans is a no-op when pgrep is missing" in {
        // The CommandException-swallow branch executes when the command binary
        // cannot be exec'd. Inject an absolute path to a non-existent binary;
        // ProcessBuilder raises IOException → CommandException → Abort.run swallows.
        val nonexistentPgrep = "/nonexistent-pgrep-test-stub"
        Random.nextLong.map { n =>
            val tag = f"$n%016x"
            Abort.run[BrowserConnectionException] {
                BrowserLauncher.killOrphans(
                    pattern = s"never-matches-$tag",
                    command = nonexistentPgrep
                )
            }.map {
                case Result.Success(_) =>
                    // killOrphans swallowed the CommandException and returned normally.
                    succeed("killOrphans swallows a CommandException from a missing pgrep binary and returns normally")
                case Result.Failure(err) =>
                    fail(s"killOrphans should swallow CommandException, got Failure: $err")
                case Result.Panic(ex) =>
                    fail(s"killOrphans should swallow CommandException, got Panic: ${ex.getMessage}")
            }
        }
    }

    // A user-data-dir that names no owner comes from a launcher older than owner-named directories. Such a
    // Chrome is left behind only when the process that launched it has exited, which leaves it adopted by
    // pid 1. The sentinel is started by a shell that exits at once, so it is adopted the same way.
    //
    // Sentinel form: `sh -c 'true; sleep 30 # --user-data-dir=$pattern'`. Multi-statement script
    // prevents sh from exec-optimizing into sleep (a single-stmt `sh -c 'sleep 30'` would replace
    // sh's argv with sleep's, losing the tag), so `pgrep -f "user-data-dir=.*<tag>"` matches the sh PID.
    "killOrphans kills an ownerless process whose launcher has exited" in {
        Scope.run {
            System.operatingSystem.map {
                case System.OS.Windows =>
                    // The sweep is documented as a silent no-op where pgrep does not exist
                    // (Windows, minimal Docker). The POSIX sentinel below cannot run here, so
                    // this platform asserts the production contract instead: the sweep with the
                    // real `pgrep` command completes without failing.
                    Abort.run[Throwable](BrowserLauncher.killOrphans("kyo-browser-orphans-test-none", command = "pgrep")).map { result =>
                        assert(result.isSuccess, s"killOrphans must be a silent no-op without pgrep, got $result")
                    }
                case _ =>
                    for
                        n <- Random.nextLong
                        pattern = f"kyo-browser-orphans-test-$n%016x"
                        script  = s"sh -c 'true; sleep 30 # --user-data-dir=$pattern' >/dev/null 2>&1 & echo $$!"
                        pid     <- Command("sh", "-c", script).text.map(_.trim.toLong)
                        _       <- Scope.ensure(Abort.run[CommandException](Command("kill", "-9", pid.toString).waitFor).unit)
                        adopted <- awaitParent(pid, 1L)
                        _       <- BrowserLauncher.killOrphans(pattern, command = "pgrep")
                        killed  <- awaitDeath(pid)
                    yield
                        assert(adopted, s"sentinel pid=$pid should be adopted by pid 1 once its launcher exits")
                        assert(killed, s"sentinel pid=$pid has no owner and no live launcher, so the sweep should kill it")
                    end for
            }
        }
    }

    // The same ownerless sentinel, started directly by this process: its launcher is alive, as a Chrome launched
    // by an older kyo-browser in a concurrent run would be, so the sweep leaves it running.
    "killOrphans spares an ownerless process whose launcher is alive" in {
        Scope.run {
            System.operatingSystem.map {
                case System.OS.Windows =>
                    Abort.run[Throwable](BrowserLauncher.killOrphans("kyo-browser-orphans-test-none", command = "pgrep")).map { result =>
                        assert(result.isSuccess, s"killOrphans must be a silent no-op without pgrep, got $result")
                    }
                case _ =>
                    for
                        n <- Random.nextLong
                        pattern = f"kyo-browser-orphans-test-$n%016x"
                        proc   <- Command("sh", "-c", s"true; sleep 30 # --user-data-dir=/nonexistent/$pattern").spawn
                        pid    <- proc.pid
                        _      <- BrowserLauncher.killOrphans(pattern, command = "pgrep")
                        killed <- awaitDeath(pid)
                    yield assert(!killed, s"sentinel pid=$pid has a live launcher and must survive the sweep")
                    end for
            }
        }
    }

    // A Chrome whose owning run is still alive is not an orphan, even when another process runs the sweep: two
    // test JVMs (or a JVM and a Node run) on one machine must not kill each other's Chrome. The sentinel's
    // user-data-dir names a live owner (a second process this leaf holds open), so the sweep leaves it running.
    "killOrphans spares a process whose user-data-dir names a live owner" in {
        Scope.run {
            System.operatingSystem.map {
                case System.OS.Windows =>
                    Abort.run[Throwable](BrowserLauncher.killOrphans("kyo-browser-orphans-test-none", command = "pgrep")).map { result =>
                        assert(result.isSuccess, s"killOrphans must be a silent no-op without pgrep, got $result")
                    }
                case _ =>
                    for
                        n        <- Random.nextLong
                        owner    <- Command("sh", "-c", "true; sleep 30").spawn
                        ownerPid <- owner.pid
                        pattern = f"kyo-browser-$ownerPid-orphans-test-$n%016x"
                        proc   <- Command("sh", "-c", s"true; sleep 30 # --user-data-dir=/nonexistent/$pattern").spawn
                        pid    <- proc.pid
                        _      <- BrowserLauncher.killOrphans(pattern, command = "pgrep")
                        killed <- awaitDeath(pid)
                    yield assert(!killed, s"sentinel pid=$pid names live owner pid=$ownerPid and must survive the sweep")
                    end for
            }
        }
    }

    // The owner named by the user-data-dir has exited, so its Chrome is an orphan and the sweep kills it.
    "killOrphans kills a process whose user-data-dir names an owner that has exited" in {
        Scope.run {
            System.operatingSystem.map {
                case System.OS.Windows =>
                    Abort.run[Throwable](BrowserLauncher.killOrphans("kyo-browser-orphans-test-none", command = "pgrep")).map { result =>
                        assert(result.isSuccess, s"killOrphans must be a silent no-op without pgrep, got $result")
                    }
                case _ =>
                    for
                        n          <- Random.nextLong
                        owner      <- Command("true").spawn
                        ownerPid   <- owner.pid
                        _          <- owner.waitFor
                        ownerAlive <- isPidAlive(ownerPid)
                        pattern = f"kyo-browser-$ownerPid-orphans-test-$n%016x"
                        proc   <- Command("sh", "-c", s"true; sleep 30 # --user-data-dir=/nonexistent/$pattern").spawn
                        pid    <- proc.pid
                        _      <- BrowserLauncher.killOrphans(pattern, command = "pgrep")
                        killed <- awaitDeath(pid)
                    yield
                        assert(!ownerAlive, s"owner pid=$ownerPid should have exited before the sweep")
                        assert(killed, s"sentinel pid=$pid names exited owner pid=$ownerPid and should be killed by the sweep")
                    end for
            }
        }
    }

    // End to end: the sweep every run performs at startup leaves a Chrome launched by a live run reachable.
    "killOrphans leaves the Chrome of a live launch reachable" in {
        Scope.run {
            SharedChrome.chromeConfig.map { cfg =>
                BrowserLauncher.launch(cfg).map { wsUrl =>
                    BrowserLauncher.killOrphans(BrowserLauncher.userDataDirPrefix, command = "pgrep").andThen {
                        // `init` probes the connection with Browser.getVersion and fails if Chrome is gone.
                        Scope.run(CdpBackend.init(wsUrl, cfg)).andThen(succeed("the launched Chrome answered after the sweep"))
                    }
                }
            }
        }
    }

    // An adopted process whose argv only mentions the pattern (a script naming it, not a Chrome flag) matches pgrep but
    // is no Chrome of this launcher's, so the sweep leaves it alone even though no live process launched it.
    "killOrphans spares an adopted process whose argv mentions the pattern without a user-data-dir flag" in {
        Scope.run {
            System.operatingSystem.map {
                case System.OS.Windows =>
                    Abort.run[Throwable](BrowserLauncher.killOrphans("kyo-browser-orphans-test-none", command = "pgrep")).map { result =>
                        assert(result.isSuccess, s"killOrphans must be a silent no-op without pgrep, got $result")
                    }
                case _ =>
                    for
                        n <- Random.nextLong
                        pattern = f"kyo-browser-orphans-test-$n%016x"
                        script  = s"sh -c 'true; sleep 30 # grep user-data-dir=$pattern' >/dev/null 2>&1 & echo $$!"
                        pid     <- Command("sh", "-c", script).text.map(_.trim.toLong)
                        _       <- Scope.ensure(Abort.run[CommandException](Command("kill", "-9", pid.toString).waitFor).unit)
                        adopted <- awaitParent(pid, 1L)
                        _       <- BrowserLauncher.killOrphans(pattern, command = "pgrep")
                        killed  <- awaitDeath(pid)
                    yield
                        assert(adopted, s"sentinel pid=$pid should be adopted by pid 1 once its launcher exits")
                        assert(!killed, s"sentinel pid=$pid carries no --user-data-dir flag and must survive the sweep")
                    end for
            }
        }
    }

    "userDataDir reads the owner pid from a launcher-created directory" in {
        val args =
            "/opt/chrome-headless-shell --remote-debugging-port=0 --enable-bidi --user-data-dir=/var/folders/zk/T/kyo-browser-4242-8316029 --headless=new --no-sandbox"
        assert(BrowserLauncher.userDataDir(args) == BrowserLauncher.UserDataDir.Owned(4242L))
    }

    "userDataDir reads a directory given as the last argument and one with a random suffix of letters" in {
        assert(BrowserLauncher.userDataDir("chrome --type=renderer --user-data-dir=/tmp/kyo-browser-77-aB3xYz") ==
            BrowserLauncher.UserDataDir.Owned(77L))
    }

    "userDataDir reads a Windows directory" in {
        assert(BrowserLauncher.userDataDir(
            """chrome.exe --user-data-dir=C:\Users\me\AppData\Local\Temp\kyo-browser-901-123 --no-first-run"""
        ) ==
            BrowserLauncher.UserDataDir.Owned(901L))
    }

    "userDataDir is Unowned for a launcher directory that names no owner" in {
        assert(BrowserLauncher.userDataDir("chrome --user-data-dir=/tmp/kyo-browser-kwDSnG --headless=new") ==
            BrowserLauncher.UserDataDir.Unowned)
        assert(BrowserLauncher.userDataDir("chrome --user-data-dir=/tmp/kyo-browser-orphans-test-00ff --headless=new") ==
            BrowserLauncher.UserDataDir.Unowned)
    }

    "userDataDir is NotLaunched without a user-data-dir flag or with a directory the launcher does not create" in {
        assert(BrowserLauncher.userDataDir("chrome --headless=new --no-sandbox") == BrowserLauncher.UserDataDir.NotLaunched)
        assert(BrowserLauncher.userDataDir("") == BrowserLauncher.UserDataDir.NotLaunched)
        assert(BrowserLauncher.userDataDir("sh -c true; sleep 30 # grep user-data-dir=kyo-browser-7-1") ==
            BrowserLauncher.UserDataDir.NotLaunched)
        assert(BrowserLauncher.userDataDir("chrome --user-data-dir=/home/me/profile-kyo-browser-7-1 --headless=new") ==
            BrowserLauncher.UserDataDir.NotLaunched)
    }

    "processLine splits the parent pid from the argv" in {
        assert(BrowserLauncher.processLine("    1 /opt/chrome --user-data-dir=/tmp/kyo-browser-7-1 --headless=new") ==
            Present((1L, "/opt/chrome --user-data-dir=/tmp/kyo-browser-7-1 --headless=new")))
        assert(BrowserLauncher.processLine("4242 sh -c true; sleep 30\n") == Present((4242L, "sh -c true; sleep 30")))
    }

    "processLine is Absent for an empty or malformed line" in {
        assert(BrowserLauncher.processLine("") == Absent)
        assert(BrowserLauncher.processLine("   \n") == Absent)
        assert(BrowserLauncher.processLine("chrome --headless=new") == Absent)
    }

    "createTempDir names the directory after this process" in {
        Scope.run {
            for
                dir <- BrowserLauncher.createTempDir
                pid <- BrowserProcessId.current
                owner = BrowserLauncher.userDataDir(s"chrome --user-data-dir=$dir")
            yield assert(owner == BrowserLauncher.UserDataDir.Owned(pid), s"expected $dir to name owner $pid, got $owner")
        }
    }

    // --- helpers ---

    /** Polls for up to ~250ms until the parent of `pid` is `parent`. Returns whether it was in that window. */
    private def awaitParent(pid: Long, parent: Long)(using Frame): Boolean < (Async & Abort[BrowserConnectionException]) =
        Loop(0) { attempt =>
            Abort.recover[CommandException]((_: CommandException) => "") {
                Command("ps", "-o", "ppid=", "-p", pid.toString).text
            }.map { output =>
                if output.trim.toLongOption.contains(parent) then Loop.done(true)
                else if attempt >= 5 then Loop.done(false)
                else Async.delay(50.millis)(Kyo.unit).andThen(Loop.continue(attempt + 1))
            }
        }

    /** Polls for up to ~250ms until `pid` is gone. Returns whether it died in that window. */
    private def awaitDeath(pid: Long)(using Frame): Boolean < (Async & Abort[BrowserConnectionException]) =
        Loop(0) { attempt =>
            isPidAlive(pid).map { alive =>
                if !alive then Loop.done(true)
                else if attempt >= 5 then Loop.done(false)
                else Async.delay(50.millis)(Kyo.unit).andThen(Loop.continue(attempt + 1))
            }
        }

    /** Cross-platform check: is the given OS PID still alive? Uses `kill -0 <pid>` which sends signal 0 (no-op) and exits 0 if the process
      * exists, non-zero otherwise. Available on POSIX systems.
      */
    private def isPidAlive(pid: Long)(using Frame): Boolean < (Async & Abort[BrowserConnectionException]) =
        Abort.recover[CommandException]((_: CommandException) => false) {
            Command("kill", "-0", pid.toString).waitFor.map(_.isSuccess)
        }

end BrowserLauncherTest
