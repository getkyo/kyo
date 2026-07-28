package kyo.internal

import kyo.*

class BrowserLauncherTest extends BaseBrowserTest:

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
            timedRes <- timed(Abort.run[BrowserSetupException] {
                Scope.run {
                    BrowserLauncher.launch(
                        Browser.LaunchConfig.chromium("/nonexistent/browser").launchTimeout(500.millis)
                    )
                }
            })
            (elapsedDur, result) = timedRes
            elapsed              = elapsedDur.toMillis
        yield
            // Behavior contract: the launch MUST fail with BrowserSetupFailedException; that is the deterministic shape
            // ("fails for the right reason"). The timing bound is a soft envelope: cold-start on CI can exceed 5s, but if
            // we exceed 30s (60× the configured 500ms timeout) something is genuinely wrong.
            result match
                case Result.Success(_) => fail("Expected failure for invalid executable")
                case Result.Failure(_: BrowserSetupFailedException) =>
                    assert(elapsed < 30000, s"Took too long: ${elapsed}ms (>30s soft envelope, 60× the configured 500ms timeout)")
                case Result.Panic(ex) => fail(s"Expected Failure, got Panic: ${ex.getMessage}")
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
                tmp <- Path.run(Path.tempDir("kyo-browser-pollDevTools-test-"))
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

    // killOrphans PID kill verification. Spawn a sentinel whose argv matches the
    // injected pgrep regex `user-data-dir=.*<unique-tag>`, call killOrphans(pattern), then poll
    // until the sentinel pid is no longer alive (bounded retry).
    //
    // The unique tag (16 hex chars from a 64-bit random Long) gives a 2^64 collision space and
    // ensures the regex matches ONLY this test's sentinel; not SharedChrome (whose argv contains
    // `kyo-browser-NNNN` but NOT `kyo-browser-orphans-test-...`).
    //
    // Sentinel form: `sh -c 'true; sleep 30 # --user-data-dir=$pattern'`. Multi-statement script
    // prevents sh from exec-optimizing into sleep (a single-stmt `sh -c 'sleep 30'` would replace
    // sh's argv with sleep's, losing the tag). Verified empirically on macOS before writing the
    // test: `pgrep -f "user-data-dir=.*<tag>"` matches the sh PID for this form, does NOT match
    // for the single-stmt form.
    "killOrphans kills processes matching the kyo-browser user-data-dir pattern" in {
        Scope.run {
            for
                n <- Random.nextLong
                uniqueId = f"$n%016x"
                pattern  = s"kyo-browser-orphans-test-$uniqueId"
                script   = s"true; sleep 30 # --user-data-dir=$pattern"
                proc        <- Command("sh", "-c", script).spawn
                pid         <- proc.pid
                aliveBefore <- isPidAlive(pid)
                _           <- BrowserLauncher.killOrphans(pattern, command = "pgrep")
                killed <- Loop(0) { attempt =>
                    isPidAlive(pid).map { alive =>
                        if !alive then Loop.done(true)
                        else if attempt >= 5 then Loop.done(false)
                        else Async.delay(50.millis)(Kyo.unit).andThen(Loop.continue(attempt + 1))
                    }
                }
            yield
                assert(aliveBefore, s"sentinel pid=$pid should be alive before killOrphans")
                assert(killed, s"sentinel pid=$pid should be killed by killOrphans within ~250ms")
            end for
        }
    }

    // --- helpers ---

    /** Cross-platform check: is the given OS PID still alive? Uses `kill -0 <pid>` which sends signal 0 (no-op) and exits 0 if the process
      * exists, non-zero otherwise. Available on POSIX systems.
      */
    private def isPidAlive(pid: Long)(using Frame): Boolean < (Async & Abort[BrowserConnectionException]) =
        Abort.recover[CommandException]((_: CommandException) => false) {
            Command("kill", "-0", pid.toString).waitFor.map(_.isSuccess)
        }

end BrowserLauncherTest
