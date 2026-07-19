package kyo.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kyo.*
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** JVM-only verification of the [[BrowserLauncher]] subprocess + user-data-dir cleanup contract.
  *
  * Verifies the launcher cleanup contract for the subprocess + user-data-dir lifecycle. The contract is:
  *
  * > "no zombie process or locked dir survives a test failure"
  *
  * `BrowserLauncher.launch` implements the contract via two independent finalizer chains:
  *
  *   1. The Chrome subprocess is registered with the enclosing `Scope` via `Command.spawn`'s
  *      `Scope.acquireRelease(proc)((_) => destroyForcibly())`.
  *   2. The user-data temp directory is registered via `Scope.ensure(Abort.run[FileFsException](tmpDir.removeAll).unit)`,
  *      which runs LIFO **after** the process is killed so the directory is no longer locked when removeAll runs.
  *
  * Plus a JVM-level safety net via `BrowserLauncherPlatform.registerShutdownHook(proc)` for the case where the JVM exits before scope
  * finalizers run (BrowserLauncherPlatform.scala:5-27).
  *
  * These tests are therefore regression coverage for an already-correct invariant. They probe OS-level state (`ProcessHandle.allProcesses`
  * for PIDs, `java.nio.file.Files.exists` for the user-data-dir) before and after a `Browser.run` block that aborts mid-action, so a future
  * regression that breaks either finalizer chain surfaces here instead of as a flaky test in another suite or as a CI Chrome-leak.
  *
  * Lives in the JVM test tree because:
  *   - `ProcessHandle.allProcesses` and `ProcessHandle.info().arguments()` are JVM-only.
  *   - `java.nio.file.Files.exists` does not have a usable Scala.js shim.
  *   - The launcher subprocess itself is a JVM/Native concept (there is no Chrome subprocess on Scala.js).
  *
  * Each test launches its own ephemeral Chrome via `Browser.run(config)` (NOT `withBrowser` / `SharedChrome`), so the shared-Chrome
  * instance used by the rest of the test suite is unaffected. To disambiguate "ours" from any unrelated Chrome on the host, every test
  * injects a unique `--user-agent=<tag>` extra-arg into the `Browser.LaunchConfig`; the tag is a UUID-suffixed string scanned via
  * `ProcessHandle.info().arguments()`.
  */
class BrowserLauncherCleanupJvmTest extends BaseBrowserTest:

    // Hang-guard only: must exceed the legitimate worst-case runtime of any single test so it fires
    // solely on a true hang, never on a correct-but-slow run. Worst case ≈ Chrome launch (≤90s
    // launchTimeout) + CdpClient close grace (≤30s) + cleanup poll (≤90s, see the `waitUntil` calls
    // below) + overhead ≈ 220s. 6 minutes leaves a comfortable margin while still bounding a real hang.
    override def timeout = 6.minutes

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Generates a sentinel string unique to this test invocation, used as `--user-agent=<tag>` so the test can locate its own Chrome
      * process(es) via `ProcessHandle.info().arguments()` without colliding with any other Chrome on the host (or in another
      * sequentially-run test JVM).
      */
    private def freshTag(): String = s"kyo-cleanup-test-${UUID.randomUUID()}"

    /** Returns a `Browser.LaunchConfig` that downloads (or reuses) the cached Chrome-for-Testing binary, then injects the given sentinel as
      * `--user-agent=<tag>`. Chrome accepts arbitrary user-agent strings and reflects them into `argv`, where
      * `ProcessHandle.info().arguments()` reads them back.
      */
    private def configWithTag(tag: String)(using Frame): Browser.LaunchConfig < (Async & Abort[BrowserSetupException]) =
        Browser.chromeForTestingLaunchConfig().map { base =>
            base.copy(extraArgs = Chunk(s"--user-agent=$tag"))
        }

    /** Captures the snapshot of currently-running OS processes whose command line contains the given sentinel tag. Returns a sequence of
      * `(pid, userDataDir)` pairs, where `userDataDir` is the value of the `--user-data-dir=...` argument the launcher passed to that Chrome
      * process.
      *
      * Implementation note: macOS's Java implementation of `ProcessHandle.info().arguments()` returns the full argv for processes the JVM
      * owns (its own children); for other processes the result may be an empty Optional. On Windows `arguments()` is always empty and
      * `commandLine()` carries the unsplit command line instead, so both are consulted. Since this test only spawns Chromes via
      * `Browser.run` from the same JVM, our own Chromes are always discoverable.
      */
    private val userDataDirArg = """--user-data-dir=("([^"]+)"|(\S+))""".r

    private def chromesByTag(tag: String): Seq[(Long, String)] =
        ProcessHandle.allProcesses().iterator().asScala.flatMap { ph =>
            val info    = ph.info()
            val argsOpt = info.arguments()
            // We require BOTH the sentinel tag AND a `--user-data-dir=<...>` arg. The user-data-dir arg is what
            // we actually want to capture. If the command line doesn't contain a tag match, this process isn't ours.
            val captured: Maybe[(Long, String)] =
                if argsOpt.isPresent then
                    val args = argsOpt.get
                    if !args.exists(_.contains(tag)) then Absent
                    else
                        Maybe.fromOption(args.collectFirst {
                            case a if a.startsWith("--user-data-dir=") => (ph.pid(), a.substring("--user-data-dir=".length))
                        })
                    end if
                else
                    val cl = info.commandLine().orElse("")
                    if !cl.contains(tag) then Absent
                    else
                        Maybe.fromOption(userDataDirArg.findFirstMatchIn(cl).map { m =>
                            (ph.pid(), Option(m.group(2)).getOrElse(m.group(3)))
                        })
                    end if
            captured.toList
        }.toSeq

    /** Wait (≤ `timeoutMs`, polling every `stepMs`) until `cond()` becomes true. Returns `true` on success, `false` on timeout.
      * Plain blocking call. Used by post-scope assertions where the kyo runtime has already torn everything down and no further
      * effect chaining is appropriate.
      */
    private def waitUntil(timeoutMs: Long, stepMs: Long = 50)(cond: () => Boolean): Boolean =
        val deadline = java.lang.System.currentTimeMillis() + timeoutMs
        @tailrec def loop(): Boolean =
            if cond() then true
            else if java.lang.System.currentTimeMillis() >= deadline then false
            else
                try java.lang.Thread.sleep(stepMs)
                catch case _: InterruptedException => ()
                loop()
        loop()
    end waitUntil

    /** True if a process with the given PID is currently alive. Uses the JVM's `ProcessHandle.of(pid).isAlive`, which works for any process, not
      * just children.
      */
    private def isAlive(pid: Long): Boolean =
        val opt = ProcessHandle.of(pid)
        opt.isPresent && opt.get().isAlive

    // ─────────────────────────────────────────────────────────────────────────
    // Browser.run that throws mid-action terminates the Chrome subprocess
    // ─────────────────────────────────────────────────────────────────────────

    "Browser.run that throws mid-action terminates the Chrome subprocess" in {
        val tag = freshTag()
        // Capture the (pid, dir) pairs observed inside the action block.
        AtomicRef.initWith(Seq.empty[(Long, String)]) { capturedRef =>
            Abort.run[Throwable] {
                Scope.run {
                    configWithTag(tag).map { config =>
                        Browser.run(config) {
                            for
                                // Sanity action so Chrome is provably running before we try to capture it.
                                _ <- Browser.eval("1+1")
                                // Capture the live PID(s)/dir(s) for our tag.
                                _ <- Sync.defer {
                                    val live = chromesByTag(tag)
                                    capturedRef.set(live)
                                }
                                // Deliberately abort the action mid-flight; the surrounding scope finalizers must run.
                                _ <- Abort.fail[Throwable](new RuntimeException("deliberate boom"))
                            yield ()
                        }
                    }
                }
            }.map { runResult =>
                capturedRef.get.map { captured =>
                    // The action MUST have aborted (the PID capture happened before the abort, so we always
                    // populate `captured` even when abort succeeds). The Result must be a Failure with our
                    // sentinel: anything else means the abort didn't propagate.
                    runResult match
                        case Result.Failure(ex) if ex.getMessage == "deliberate boom" => ()
                        case other => fail(s"test: expected the deliberate Abort.fail to surface, got $other")
                    end match
                    assert(captured.nonEmpty, "test: captured no Chrome PID for our tag (is `--user-agent` reaching argv?)")
                    val pids = captured.map(_._1)
                    // Bounded poll: SIGTERM/SIGKILL takes a moment to drop the process. The contract is
                    // "no zombie", not "fast cleanup": 90s is the upper bound on macOS for a Chrome process
                    // tree to fully die after destroyForcibly() under full-suite load. CdpClient.close uses a
                    // 30-second grace internally; the wait must be larger than that grace plus the OS reaping
                    // window when the host is saturated by preceding tests' I/O.
                    val allDead = waitUntil(90000) { () =>
                        pids.forall(p => !isAlive(p))
                    }
                    val stillAlive = pids.filter(isAlive)
                    assert(
                        allDead,
                        s"test: Chrome PID(s) still alive after Browser.run aborted, leaked subprocess(es): $stillAlive"
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Browser.run that throws mid-action deletes the user-data-dir
    // ─────────────────────────────────────────────────────────────────────────

    "Browser.run that throws mid-action deletes the user-data-dir" in {
        val tag = freshTag()
        AtomicRef.initWith(Seq.empty[(Long, String)]) { capturedRef =>
            Abort.run[Throwable] {
                Scope.run {
                    configWithTag(tag).map { config =>
                        Browser.run(config) {
                            for
                                _ <- Browser.eval("1+1")
                                _ <- Sync.defer {
                                    capturedRef.set(chromesByTag(tag))
                                }
                                _ <- Abort.fail[Throwable](new RuntimeException("deliberate boom"))
                            yield ()
                        }
                    }
                }
            }.map { runResult =>
                capturedRef.get.map { captured =>
                    runResult match
                        case Result.Failure(ex) if ex.getMessage == "deliberate boom" => ()
                        case other => fail(s"test: expected the deliberate Abort.fail to surface, got $other")
                    end match
                    assert(captured.nonEmpty, "test: captured no Chrome user-data-dir for our tag")
                    val dirs = captured.map(_._2).distinct
                    // While Chrome was alive the dir MUST have existed (sanity check our path-extraction).
                    // (We assert this at capture time below, after the abort, but only on dirs that are gone
                    // post-cleanup; if we stored "before" state we'd race the cleanup. Instead we rely on the
                    // `--user-data-dir=...` arg pointing to a path the launcher created, see `BrowserLauncher.launch`.)
                    //
                    // Generous timeout: Chrome's user-data-dir can be a few hundred files (cache, prefs, cookies,
                    // service worker registrations, etc.); recursive removeAll runs LIFO after Chrome's
                    // destroyForcibly returns, but the OS-level reaping of the process tree can briefly hold
                    // file handles. The contract is "no leak", not "leak removed quickly": 90s is a safe
                    // upper bound on macOS for the worst case (cold Chrome with full profile under
                    // full-suite load where preceding tests may saturate file-system I/O).
                    val allGone = waitUntil(90000) { () =>
                        dirs.forall(d => !Files.exists(Paths.get(d)))
                    }
                    val leaked = dirs.filter(d => Files.exists(Paths.get(d)))
                    assert(
                        allGone,
                        s"test: user-data-dir(s) still present after Browser.run aborted, leaked: $leaked"
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Browser.run with abrupt CDP WebSocket close cleans up
    // ─────────────────────────────────────────────────────────────────────────

    // Pattern B: page-side / OS-side WS drop. We simulate by killing the Chrome subprocess externally
    // (SIGKILL) while the action is mid-flight; the CDP socket then drops and any subsequent CDP request
    // raises BrowserConnectionLostException. Cleanup must still complete and not leave a zombie subprocess
    // or locked user-data-dir behind.
    "Browser.run with abrupt CDP WebSocket close cleans up" in {
        val tag = freshTag()
        AtomicRef.initWith(Seq.empty[(Long, String)]) { capturedRef =>
            Abort.run[Throwable] {
                Scope.run {
                    configWithTag(tag).map { config =>
                        Browser.run(config) {
                            for
                                _    <- Browser.eval("1+1")
                                live <- Sync.defer(chromesByTag(tag))
                                _    <- capturedRef.set(live)
                                _ <- Sync.defer {
                                    // Externally drop Chrome's process tree. After this point the CDP WebSocket
                                    // is half-open from the launcher's perspective; the next CDP send (or the
                                    // scope finalizer's disposeBrowserContext call) raises Closed → ConnectionLost.
                                    live.foreach { case (pid, _) =>
                                        val opt = ProcessHandle.of(pid)
                                        if opt.isPresent then discard(opt.get().destroyForcibly())
                                    }
                                }
                                // Trigger a CDP request after the kill so the WS-drop is observed by the action
                                // boundary, not just by the scope finalizer. Either an abort or a panic is
                                // acceptable for §3.3 Pattern B; the contract is that cleanup completes.
                                _ <- Browser.eval("2+2")
                            yield ()
                        }
                    }
                }
            }.map { runResult =>
                capturedRef.get.map { captured =>
                    // The action MUST have aborted: the killed Chrome cannot service a follow-up CDP request.
                    runResult match
                        case Result.Failure(_) | Result.Panic(_) => ()
                        case Result.Success(_) =>
                            fail("test: expected an abort/panic after externally killing Chrome, got Success")
                    end match
                    assert(captured.nonEmpty, "test: captured no Chrome process for our tag")
                    val pids = captured.map(_._1)
                    val dirs = captured.map(_._2).distinct
                    // Generous upper bound: the orderly close grace (30s) may apply when the WS dropped
                    // mid-handshake. The contract is "no zombie", not "fast cleanup", so we allow up to
                    // the close-grace + a small margin. If you see this poll exhaust, the bug is real:
                    // the launcher's Scope finalizers are NOT killing the process.
                    val allDead = waitUntil(35000) { () =>
                        pids.forall(p => !isAlive(p))
                    }
                    val stillAlive = pids.filter(isAlive)
                    assert(allDead, s"test: Chrome PID(s) still alive after WS-drop cleanup, leaked: $stillAlive")
                    val allGone = waitUntil(10000) { () =>
                        dirs.forall(d => !Files.exists(Paths.get(d)))
                    }
                    val leaked = dirs.filter(d => Files.exists(Paths.get(d)))
                    assert(allGone, s"test: user-data-dir(s) still present after WS-drop cleanup, leaked: $leaked")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // back-to-back Browser.run after a failure both succeed
    // ─────────────────────────────────────────────────────────────────────────

    // Cascading-failure pattern: leftover state from a failed run must NOT poison subsequent launches.
    "back-to-back Browser.run after a failure both succeed" in {
        val tag1 = freshTag()
        val tag2 = freshTag()
        val tag3 = freshTag()

        // First run: a Browser.run that aborts mid-action.
        val firstRun: Result[Throwable, Unit] < (Async & Scope) =
            Abort.run[Throwable] {
                configWithTag(tag1).map { config =>
                    Browser.run(config) {
                        Browser.eval("1+1").andThen(Abort.fail[Throwable](new RuntimeException("first deliberate boom")))
                    }
                }
            }

        // Subsequent runs: trivial Browser.run that should complete cleanly.
        def trivialRun(tag: String): Result[Throwable, String] < (Async & Scope) =
            Abort.run[Throwable] {
                configWithTag(tag).map { config =>
                    Browser.run(config) {
                        Browser.eval("21+21")
                    }
                }
            }

        Scope.run {
            for
                r1 <- firstRun
                _ <- Sync.defer {
                    r1 match
                        case Result.Failure(ex) if ex.getMessage == "first deliberate boom" =>
                            assert(ex.getMessage == "first deliberate boom", s"sentinel message mismatch: ${ex.getMessage}")
                        case other => fail(s"test: first run expected to abort with sentinel, got $other")
                    end match
                }
                r2 <- trivialRun(tag2)
                _ <- Sync.defer {
                    r2 match
                        case Result.Success(v) =>
                            assert(v == "42", s"test: second run expected '42' but got '$v'")
                        case other => fail(s"test: second run failed unexpectedly: $other")
                    end match
                }
                r3 <- trivialRun(tag3)
                _ <- Sync.defer {
                    r3 match
                        case Result.Success(v) =>
                            assert(v == "42", s"test: third run expected '42' but got '$v'")
                        case other => fail(s"test: third run failed unexpectedly: $other")
                    end match
                }
            yield ()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parallel Browser.run instances each get a unique user-data-dir
    // ─────────────────────────────────────────────────────────────────────────

    "parallel Browser.run instances each get a unique user-data-dir" in {
        val tagA = freshTag()
        val tagB = freshTag()

        // Each branch captures the user-data-dir(s) Chrome was launched with, and asserts a trivial eval works.
        def branch(tag: String): (String, String) < (Async & Scope & Abort[Throwable]) =
            configWithTag(tag).map { config =>
                Browser.run(config) {
                    for
                        v <- Browser.eval("21+21")
                        d <- Sync.defer {
                            val live = chromesByTag(tag)
                            assert(live.nonEmpty, s"test: branch $tag captured no Chrome process")
                            // For our launch there should be one --user-data-dir; defensively pick the first.
                            live.head._2
                        }
                    yield (v, d)
                }
            }

        Abort.run[Throwable] {
            Scope.run {
                Async.zip(branch(tagA), branch(tagB))
            }
        }.map {
            case Result.Success(((vA, dA), (vB, dB))) =>
                assert(vA == "42", s"test: branch A unexpected eval result: $vA")
                assert(vB == "42", s"test: branch B unexpected eval result: $vB")
                assert(dA != dB, s"test: parallel branches got the SAME user-data-dir '$dA': launcher reused a path")
                // Both temp dirs must be cleaned up after the scope closes; allow up to 90s for the worst-case
                // recursive removeAll under the OS reaping window with full-suite I/O saturation
                // (matches the other cleanup tests' budgets).
                val allGone = waitUntil(90000) { () =>
                    !Files.exists(Paths.get(dA)) && !Files.exists(Paths.get(dB))
                }
                assert(
                    allGone,
                    s"test: parallel-branch user-data-dirs still present after scope close (A=$dA exists=${Files.exists(Paths.get(dA))}, B=$dB exists=${Files.exists(Paths.get(dB))})"
                )
            case other => fail(s"test: parallel runs failed: $other")
        }
    }

end BrowserLauncherCleanupJvmTest
