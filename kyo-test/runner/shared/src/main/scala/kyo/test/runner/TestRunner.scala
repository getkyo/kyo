package kyo.test.runner

import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Clock
import kyo.Duration
import kyo.Fiber
import kyo.Frame
import kyo.Kyo
import kyo.Local
import kyo.Maybe
import kyo.Result
import kyo.Retry
import kyo.Scope
import kyo.Sync
import kyo.Timeout
import kyo.kernel.<
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import kyo.test.LeafAborted
import kyo.test.LeafInfo
import kyo.test.RunConfig
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestBuilder
import kyo.test.TestCancelled
import kyo.test.TestFilter
import kyo.test.TestReport
import kyo.test.TestReporter
import kyo.test.TestResult
import kyo.test.internal.TestBase
import kyo.test.internal.TestContext
import kyo.test.runner.ConsoleReporter
import kyo.test.runner.internal.Glob
import kyo.test.runner.internal.Instantiate
import kyo.test.runner.internal.LeafPool
import kyo.test.runner.internal.LeakDebug
import kyo.test.runner.internal.Randomize
import scala.concurrent.Future

/** Pure-Kyo runner for the V3 (next) self-contained framework.
  *
  * The ENTIRE run, discovery AND execution, is one Kyo computation. Discovery is a synchronous `Sync` walk (per RI-008 discovery is
  * inherently sequential): each probe allocates its own [[TestContext]], instantiates the suite single-threaded, and reads the synchronous
  * `peekRegisteredLeaf` / `peekWasGroup` accessors. Execution fans out through the process-global
  * `kyo.test.runner.internal.LeafPool`: each leaf's `Chunk[(Chunk[String], TestResult)] < Async` computation is submitted via
  * `LeafPool.submit` and the suite awaits the returned promises in INPUT ORDER. The pool drains its bounded channel with `LeafPool.globalK` detached worker fibers, so total
  * concurrent leaf execution is bounded across ALL suites in the process (not per suite). Each leaf runs under a per-leaf `Scope.run`
  * (Scope is fiber-shared, so per-leaf `Scope.run` bounds resource lifetime to leaf end), the per-leaf `Async.timeout` and `Retry`
  * decorators when present, and an `Abort.run[Throwable]` boundary that routes a thrown `AssertionFailed` / `TestCancelled` / `Timeout`
  * into a `TestResult`. The ONLY `Future` is produced once, at the sbt edge, by `Fiber#toFuture`.
  *
  * The shared report / reporter / filter types under `kyo.test.*` are reused as-is.
  *
  * @see
  *   [[kyo.test.internal.TestBase]] the suite base the runner instantiates and discharges
  * @see
  *   [[kyo.test.TestReport]] the aggregate result the run yields
  * @see
  *   [[kyo.test.TestBuilder]] for the decorator metadata carrier used during discovery
  */
object TestRunner:

    /** The per-leaf context set into [[leafLocal]] before the leaf body runs, so it is visible inside the leaf (worker) fiber via
      * `Local.init` inheritance. Carries the execution [[TestContext]] and the discovery-time path.
      */
    final private[runner] case class LeafContext(ctx: TestContext, path: Chunk[String])

    private val leafLocal: Local[Maybe[LeafContext]] = Local.init(Maybe.empty)

    /** Resolves the effective reporter: the configured reporter when present, else a default `ConsoleReporter`. */
    private[test] def resolveReporter(config: RunConfig): TestReporter =
        config.reporter match
            case Maybe.Present(r) => r
            case Maybe.Absent     => ConsoleReporter(config.verbosity, ConsoleReporter.autoDetect)

    /** Run all leaves in `suite` as one Kyo computation, returning the assembled report as a single Kyo value.
      *
      * This is the pure-Kyo surface (no `Future`): the whole run upstream of the sbt edge. [[runToFuture]] performs the single terminal
      * `Fiber#toFuture` conversion for the eventual sbt Runner.
      */
    def runReport(suite: Class[? <: TestBase[?]], config: RunConfig = RunConfig.default)(using
        Frame
    ): TestReport < (Async & Abort[Throwable] & Scope) =
        // Resolve effective config: when the caller uses RunConfig.default, substitute the suite's own config (via a
        // throw-away single-threaded probe instantiation).
        val effectiveConfig: RunConfig =
            if config == RunConfig.default then
                val probeCtx = new TestContext(Chunk.empty, discovery = true)
                installContext(probeCtx)
                try Instantiate.newInstance(suite).config
                catch
                    case t: Throwable =>
                        java.lang.System.err.println(s"[kyo-test] config probe failed: $t")
                        config
                end try
            else config

        val reporter: TestReporter = resolveReporter(effectiveConfig)
        val suiteInfo = SuiteInfo(
            name = simpleName(suite),
            className = suite.getName,
            expectedLeafCount = Maybe.empty
        )
        // Under the process-global pool there is no per-suite K. The meaningful run-start figure is the pool's
        // global bound, LeafPool.globalK (Q-002 resolution): reporting the suite's requested parallelism would
        // mislead, since the pool, not the suite, sets the real degree of concurrency.
        if !effectiveConfig.countOnly then
            reporter.onRunStart(kyo.test.RunInfo(suiteCount = 1, parallelism = LeafPool.globalK))
            reporter.onSuiteStart(suiteInfo)
        val suiteStart = java.lang.System.nanoTime()

        val pipeline: TestReport < (Async & Abort[Throwable] & Scope) =
            if effectiveConfig.countOnly then
                // Count-only (discovery) mode: the discovery walk enumerates every leaf (including those generated at construction
                // by macros, loops, and fan-out helpers) without executing any leaf body. Emit one machine-parseable line per suite.
                walkNode(suite, Chunk.empty).map { rawLeaves =>
                    if effectiveConfig.listOnly then
                        rawLeaves.foreach { case (path, _, _) =>
                            java.lang.System.out.println(s"[kyo-test:leaf] ${suite.getName} :: ${path.mkString(" > ")}")
                        }
                    end if
                    java.lang.System.out.println(s"[kyo-test:count] ${suite.getName} = ${rawLeaves.size}")
                    TestReport(Chunk.empty)
                }
            else
                walkNode(suite, Chunk.empty).map { rawLeaves =>
                    val allLeaves: Chunk[(Chunk[String], Maybe[TestBuilder])] =
                        rawLeaves.map { case (path, _, builderOpt) => (path, builderOpt) }

                    val filtered = applyFilter(allLeaves, effectiveConfig.filter)

                    val ordered = effectiveConfig.randomize match
                        case Maybe.Present(seed) =>
                            Randomize.shuffle[(Chunk[String], Maybe[TestBuilder])](filtered, seed)
                        case Maybe.Absent => filtered

                    val hasFocus = ordered.exists { case (_, builderOpt) => builderOpt.exists(_.focus) }

                    val cursorMap: Map[Chunk[String], Chunk[Int]] =
                        rawLeaves.map { case (path, cursor, _) => path -> cursor }.toMap

                    (ordered, hasFocus, cursorMap)
                }.flatMap { case (ordered, hasFocus, cursorMap) =>
                    // Build one submittable leaf-computation per leaf. The reporter callbacks fire INSIDE this
                    // computation (on the pool worker) at REAL leaf start/finish (more accurate than fire-at-fork).
                    def leafComp(path: Chunk[String], builderOpt: Maybe[TestBuilder]): Chunk[(Chunk[String], TestResult)] < Async =
                        val rawBuilder = builderOpt.getOrElse(TestBuilder(path.lastMaybe.getOrElse("")))
                        val builder =
                            rawBuilder.timeout match
                                case Maybe.Absent if effectiveConfig.timeout != Duration.Infinity =>
                                    rawBuilder.copy(timeout = Maybe(effectiveConfig.timeout))
                                case _ => rawBuilder
                        val leafInfo = LeafInfo(suiteInfo.name, path, builder.tags)
                        val cursor   = cursorMap.getOrElse(path, Chunk.empty)
                        Sync.defer {
                            reporter.onLeafStart(leafInfo)
                            // Leak-debug attribution: snapshot the open descriptors before the leaf body and get the after-leaf finalizer. A
                            // no-op (single shared closure) unless leak-debug mode installed a probe, so the normal path is untouched.
                            LeakDebug.beginLeaf(path)
                        }.map { probeFinish =>
                            withHeartbeat(leafInfo, effectiveConfig.heartbeatInterval, reporter)(
                                runLeaf(suite, cursor, path, builder, hasFocus, effectiveConfig.failOnNoAssertion)
                            ).map { entries =>
                                // Run after the leaf body (which includes the leaf's Scope.run, so the leaf's own finalizers have already run):
                                // any descriptor still open here that the leaf opened is the leaf's leak, recorded against this leaf path.
                                probeFinish()
                                entries.headMaybe match
                                    case Maybe.Present((_, result)) => reporter.onLeafComplete(leafInfo, result)
                                    case Maybe.Absent               => ()
                                entries
                            }
                        }
                    end leafComp

                    // Fan out via the global pool. Sequential (parallelism == 1): push-await-each in a serial
                    // Kyo.foreach so at most one of THIS suite's leaves is in the pool at a time (within-suite
                    // ordering preserved). Parallel (otherwise): push ALL leaves (backpressured by the channel),
                    // then await all promises in INPUT ORDER.
                    val results: Chunk[(Chunk[String], TestResult)] < (Async & Abort[Throwable] & Scope) =
                        if effectiveConfig.parallelism == 1 then
                            Kyo.foreach(ordered) { case (path, builderOpt) =>
                                LeafPool.submit(leafComp(path, builderOpt), effectiveConfig.globallySequential).map(_.get)
                            }.map(_.foldLeft(Chunk.empty[(Chunk[String], TestResult)])(_ ++ _))
                        else
                            Kyo.foreach(ordered) { case (path, builderOpt) =>
                                LeafPool.submit(leafComp(path, builderOpt), effectiveConfig.globallySequential)
                            }.flatMap { promises =>
                                Kyo.foreach(promises)(_.get)
                                    .map(_.foldLeft(Chunk.empty[(Chunk[String], TestResult)])(_ ++ _))
                            }

                    results.map { leaf =>
                        val duration = Duration.fromNanos(java.lang.System.nanoTime() - suiteStart)
                        // After-leaf leaks: a fiber that asserted AFTER its leaf was scored hit the CLOSED branch of
                        // AssertScope.record, which (besides the stderr warning) enqueued into the process-global collector.
                        // Drain it here and append one synthetic failed leaf per leak, so the leak fails the run on every
                        // platform with no per-platform task edits (these are ordinary Failed leaves that onSuiteComplete,
                        // emitEvents, and Summary all pick up). Disjoint from during-leaf leaks: those come from the per-leaf
                        // instance sink (open scope); these come from the global collector (closed scope). Best-effort: an
                        // after-leaf leak only lands as a failed leaf if it was enqueued before THIS drain point. A fiber that
                        // asserts after all suites are scored cannot become an event; it still emits the stderr warning.
                        val leakedAfter = AssertScope.drainLeakedAfterClose()
                        val synthetic = leakedAfter.map { case (leakPath, failure) =>
                            (
                                leakPath :+ "(leaked fiber assertion)",
                                TestResult.Failed(
                                    "leaked fiber assertion (a fiber outlived its test and failed an assert; timing-sensitive):\n" + failure.diagram,
                                    Maybe(failure.getCause),
                                    Duration.Zero
                                )
                            )
                        }
                        val sr = SuiteReport(
                            suiteInfo.name,
                            leaf ++ synthetic,
                            duration,
                            leakCheck = effectiveConfig.leakCheck,
                            leakCheckSockets = effectiveConfig.leakCheckSockets,
                            leakCheckFileDescriptors = effectiveConfig.leakCheckFileDescriptors,
                            leakCheckThreads = effectiveConfig.leakCheckThreads,
                            leakCheckFibers = effectiveConfig.leakCheckFibers,
                            leakCheckAllowlist = effectiveConfig.leakCheckAllowlist
                        )
                        reporter.onSuiteComplete(suiteInfo, sr)
                        val report = TestReport(Chunk(sr))
                        reporter.onRunComplete(report)
                        report
                    }
                }

        // Recover a discovery / constructor failure into a single <constructor> failure SuiteReport so the run never yields a
        // failed Kyo value. A Panic is logged (never swallowed) and recorded as a failure.
        Abort.run[Throwable](pipeline).map {
            case Result.Success(report) => report
            case Result.Failure(t)      => constructorFailureReport(suiteInfo, reporter, effectiveConfig, t)
            case panic: Result.Panic =>
                java.lang.System.err.println(s"[kyo-test] unexpected panic during run: ${panic.exception}")
                constructorFailureReport(suiteInfo, reporter, effectiveConfig, panic.exception)
        }
    end runReport

    /** The single sbt-edge boundary: run the whole-run Kyo computation to a `Fiber` and convert to a `Future` exactly once. This is the
      * ONLY `Fiber#toFuture` in the codebase. The `Sync` produced by `Scope.run` / `Fiber.initUnscoped` / `toFuture` is discharged once
      * here.
      */
    def runToFuture(suite: Class[? <: TestBase[?]], config: RunConfig = RunConfig.default)(using Frame): Future[TestReport] =
        val asFuture: Future[TestReport] < Sync =
            Scope.run(runReport(suite, config)).handle(Fiber.initUnscoped).map(_.toFuture)
        // Unsafe: sole sbt-edge boundary (justified per kyo-sql precedent). Discharging the terminal Sync to the produced Future is
        // the single sanctioned bridge; everything upstream is pure Kyo.
        import kyo.AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(asFuture)
    end runToFuture

    /** CLI-boundary entry: identical to [[runToFuture]] but supplies the boundary `Frame` internally so the `kyo.test.runner.Cli` entry
      * point (which has no caller `Frame` to propagate and lives in the `kyo` package where `Frame.derive` is forbidden) does not have to
      * fabricate one at its call site. This is the sanctioned CLI-edge `Frame` source (debt D8), the analogue of the sbt-edge `Frame` the
      * `*Task` files pass; everything upstream of this boundary threads a real `Frame`.
      */
    def runToFutureAtCliEdge(suite: Class[? <: TestBase[?]], config: RunConfig): Future[TestReport] =
        // Frame.internal here is the single sanctioned CLI-edge boundary Frame; the Cli entry point has no caller Frame.
        runToFuture(suite, config)(using Frame.internal)
    end runToFutureAtCliEdge

    // ── Discovery: synchronous Kyo walk ──────────────────────────────────────────────────────

    /** Probe a single cursor target: instantiate the suite single-threaded, signal past-end, read the synchronous accessors. */
    private def probe(
        suite: Class[? <: TestBase[?]],
        target: Chunk[Int]
    )(using Frame): (TestContext, Maybe[(Chunk[String], TestResult)], Boolean) < Sync =
        Sync.defer {
            val ctx = new TestContext(target, discovery = true)
            installContext(ctx)
            val _ = Instantiate.newInstance(suite)
            ctx.signalPastEnd()
            (ctx, ctx.peekRegisteredLeaf, ctx.peekWasGroup)
        }

    /** Discover all (namePath, cursor, builderOpt) tuples under `prefix`. The DFS probes one cursor at a time (discovery is inherently
      * sequential, RI-008), descending into groups and stopping at the first past-end (neither leaf nor group) sibling.
      */
    private def walkNode(
        suite: Class[? <: TestBase[?]],
        prefix: Chunk[Int]
    )(using Frame): Chunk[(Chunk[String], Chunk[Int], Maybe[TestBuilder])] < Sync =
        def loop(
            i: Int,
            acc: Chunk[(Chunk[String], Chunk[Int], Maybe[TestBuilder])]
        ): Chunk[(Chunk[String], Chunk[Int], Maybe[TestBuilder])] < Sync =
            probe(suite, prefix.append(i)).flatMap { (ctx, leaf, group) =>
                leaf match
                    case Maybe.Present((path, _)) =>
                        // LEAF: record (path, cursor, builderOpt) and continue to the next sibling.
                        val cursor     = prefix.append(i)
                        val builderOpt = ctx.builderFor(path)
                        loop(i + 1, acc.append((path, cursor, builderOpt)))
                    case Maybe.Absent =>
                        if group then
                            // GROUP: descend into its children, then continue to the next sibling.
                            walkNode(suite, prefix.append(i)).flatMap(children => loop(i + 1, acc.concat(children)))
                        else
                            // PAST-END: neither a leaf nor a group at this index; stop the sibling scan.
                            acc
            }
        loop(0, Chunk.empty)
    end walkNode

    // ── Execution: one leaf ───────────────────────────────────────────────────────────────────

    /** Wrap a leaf computation with a forked heartbeat fiber that reports the leaf as still running once it has run longer than `interval`,
      * repeating every interval thereafter, via `reporter.onLeafHeartbeat`. `Clock.repeatWithDelay` forks the fiber; `Sync.acquireReleaseWith`
      * interrupts it the instant the leaf finishes (or fails), so a leaf that completes before the interval produces no extra output.
      * `Duration.Infinity` disables it (the body runs unchanged). This is an ordinary forked fiber, not a dedicated thread: a leaf that is
      * merely parked (waiting on a channel, fiber, or latch, the common hung-test shape) is still observed, because the scheduler runs the
      * heartbeat fiber on another worker. It cannot report a leaf that has wedged every worker; that rarer case is out of scope here.
      */
    private def withHeartbeat[A](
        info: LeafInfo,
        interval: Duration,
        reporter: TestReporter
    )(body: A < Async)(using Frame): A < Async =
        if interval == Duration.Infinity then body
        else
            Sync.acquireReleaseWith(
                Clock.stopwatch.map { sw =>
                    Clock.repeatWithDelay(interval, interval) {
                        sw.elapsed.map(elapsed => reporter.onLeafHeartbeat(info, elapsed))
                    }
                }
            )(_.interrupt)(_ => body)
    end withHeartbeat

    /** Run one leaf at `cursor`, producing its result entry plus any synthetic stray entries.
      *
      * Re-instantiates the suite at this leaf's execution cursor. When the re-instantiation produced a TERMINAL registration
      * (`peekRegisteredLeaf` is `Present`: a `.pending` / `.ignore` / `.only(false)` / platform-mismatch leaf that buffered no body), the
      * terminal `TestResult` is returned directly. Otherwise the buffered baseline body is discharged via [[runRegisteredBody]]: `Retry`
      * wraps the body (it already retries a thrown failure, which the Abort machinery lifts into a retryable `Result.Failure`), and
      * `Async.timeout` wraps the whole retry loop so it bounds the leaf including retries. A thrown `AssertionFailed` / `TestCancelled` on
      * the no-retry path surfaces as a `Result.Panic`, and an `Async.timeout` expiry surfaces as a `Result.Failure(Timeout)`, both mapped
      * to the matching `TestResult` at the `Abort.run[Throwable]` boundary.
      */
    private def runLeaf(
        suite: Class[? <: TestBase[?]],
        cursor: Chunk[Int],
        path: Chunk[String],
        builder: TestBuilder,
        hasFocus: Boolean,
        failOnNoAssertion: Boolean
    )(using Frame): Chunk[(Chunk[String], TestResult)] < Async =
        if hasFocus && !builder.focus then
            Chunk((path, TestResult.Skipped("not focused")))
        else
            Sync.defer {
                val ctx     = new TestContext(cursor, discovery = false)
                val startNs = java.lang.System.nanoTime()
                installContext(ctx)
                val instance = Instantiate.newInstance(suite)
                ctx.signalPastEnd()

                // A TERMINAL registration (`.ignore` / `.only(false)` / platform-mismatch) calls
                // registerIgnored / registerSkipped during re-instantiation: it sets `producedLeaf`
                // and buffers NO body. Honor that terminal result directly rather than discharging an empty
                // `takeRegisteredBody` (which would run as () -> Passed and lose the terminal status). `pendingUntilFixed`
                // is NOT terminal (it buffers a body, so peekRegisteredLeaf is Absent), so its inversion path below is
                // unaffected.
                ctx.peekRegisteredLeaf match
                    case Maybe.Present((_, terminalResult)) =>
                        Chunk((path, terminalResult))
                    case Maybe.Absent =>
                        runRegisteredBody(instance, ctx, builder, path, startNs, failOnNoAssertion)
                end match
            }
        end if
    end runLeaf

    /** Discharge the buffered baseline-row body for a non-terminal leaf. Applies the retry/timeout/repeat decorators, runs the body under a
      * per-leaf `Scope.run` + fiber, and maps the `Abort.run[Throwable]` boundary into a `TestResult` (including the `pendingUntilFixed`
      * inversion).
      */
    private def runRegisteredBody(
        instance: kyo.test.internal.TestBase[?],
        ctx: TestContext,
        builder: TestBuilder,
        path: Chunk[String],
        startNs: Long,
        failOnNoAssertion: Boolean
    )(using Frame): Chunk[(Chunk[String], TestResult)] < Async =
        // Mint the per-leaf evidence value. The body, and any fiber it spawns (including a detached `Fiber.initUnscoped`
        // one), captures this SAME instance lexically when `takeRegisteredBody(as)` is applied, so an assert failing in an
        // unjoined fiber records here. The ctor is `private[kyo]`; this site is under `kyo` so it is reachable, and this
        // method is non-inline so there is no `@publicInBinary` constraint.
        val as = new AssertScope(path)
        // Retrieve the buffered body INSIDE a `Sync.defer` so a body that throws synchronously (e.g. a bare
        // `assert(1 == 2)` whose entire body is the throwing expression) is captured by the conversion below rather than
        // escaping eagerly during retrieval. The suite's `aroundLeaf` hook wraps every leaf body (default identity).
        val rawBody: Unit < (Async & Abort[Any] & Scope) =
            // Start each evaluation of the body from an empty sink. Retry/repeat re-run this computation, and an early
            // attempt that THREW a failure (which the assert macro recorded into the sink before throwing) then RECOVERED
            // would otherwise leave a stale record that the drain-then-flip below wrongly turns into a Failed leaf. The
            // clear runs BEFORE the body spawns any detached fiber, so a detached fiber's later record (the plain
            // detached-capture path) is preserved and still flips the leaf; only the FINAL attempt's records survive.
            Sync.defer { val _ = as.drain(); () }
                .andThen(instance.aroundLeaf(Sync.defer(ctx.takeRegisteredBody(as))))
        // The leaf baseline is `Abort[Any]` (a leaf may abort with ANY value, not only a Throwable). Convert it to the
        // runner's `Abort[Throwable]` pipeline here, at the single production point, so Retry/timeout/repeat and the
        // `Abort.run[Throwable]` boundary below stay Throwable-shaped (mirrors `KyoApp.abortAnyToThrowable`):
        //   - `Abort.catching[Throwable]` turns a thrown failure (e.g. `assert` throws `AssertionFailed`) into an Abort
        //     FAILURE, which is what makes retry-on-throw work: Kyo's `Retry` re-raises a `Result.Panic` WITHOUT retrying,
        //     and a raw `throw` is a Panic, so an uncaught thrown assertion would never be retried.
        //   - a `Throwable` abort passes through unchanged; a non-`Throwable` abort is wrapped in `LeafAborted`.
        val body: Unit < (Async & Abort[Throwable] & Scope) =
            Abort.run[Any](Abort.catching[Throwable](rawBody)).map {
                case Result.Success(value)            => value
                case Result.Failure(error: Throwable) => Abort.fail(error)
                case Result.Failure(error)            => Abort.fail(LeafAborted(error))
                case panic: Result.Panic              => Abort.get(panic)
            }

        // pendingUntilFixed bodies are EXPECTED to fail and run exactly once; retry/repeat do not apply.
        val isXfail = builder.pendingUntilFixed.isDefined

        // Retry wraps the body. Retry already retries a THROWN failure: a raw throw is lifted into an `Abort[Throwable]`
        // `Result.Failure` by the Abort machinery (verified empirically), NOT a Panic, so Retry sees it as retryable with
        // no `Abort.catching`. pendingUntilFixed bodies run exactly once (no retry).
        val retried: Unit < (Async & Abort[Throwable] & Scope) =
            builder.retrySchedule match
                case Maybe.Present(s) if !isXfail => Retry[Throwable](s)(body)
                case _                            => body

        // Timeout wraps the WHOLE retry loop, so it bounds the leaf including its retries (not each attempt). Keeping the
        // timeout OUTSIDE Retry is also what makes retry-on-throw work: a throw wrapped in `Async.timeout` surfaces as a
        // `Result.Panic` (which Retry would not retry), whereas the un-wrapped body's throw stays a retryable
        // `Result.Failure`. A genuine timeout expiry raises `Abort[Timeout]`, mapped to TimedOut at the boundary below.
        // The no-retry path keeps the single-attempt behavior: a thrown AssertionFailed surfaces as a `Result.Panic` that
        // resultToTestResult maps to the same Failed leaf.
        val timed: Unit < (Async & Abort[Throwable] & Scope) =
            builder.timeout match
                case Maybe.Present(d) => Async.timeout(d)(retried)
                case Maybe.Absent     => retried

        val repeated: Unit < (Async & Abort[Throwable] & Scope) =
            if isXfail || builder.repeat <= 1 then timed
            else Kyo.foreachDiscard(0 until builder.repeat)(_ => timed)

        // Discharge Scope per-leaf (Scope is fiber-shared; per-leaf Scope.run bounds resource release to leaf end), spawn the
        // body as its own fiber so the per-leaf Local context is inherited, then catch the Abort/Panic boundary.
        leafLocal.let(Maybe(LeafContext(ctx, path))) {
            Abort.run[Throwable](Scope.run(repeated).handle(Fiber.initUnscoped).flatMap(_.get)).map { result =>
                val elapsed = Duration.fromNanos(java.lang.System.nanoTime() - startNs)
                // The body fiber has joined and the leaf is about to be scored. Close the scope (later records, from a
                // fiber that outlived its test, are logged rather than queued) and drain the per-leaf sink. A detached
                // fiber that failed an assert DURING the leaf recorded into this sink without throwing on the joined path,
                // so it would otherwise be lost.
                as.close()
                val leaked = as.drain()
                val base   = resultToTestResult(result, elapsed)
                // One detached failure is enough to fail the leaf. Flip a non-failing base to Failed when the sink holds a
                // record; if the joined path already produced a failure (Failed / TimedOut / Cancelled), keep it.
                val tr =
                    if leaked.nonEmpty then
                        base match
                            case _: TestResult.Failed | _: TestResult.TimedOut | _: TestResult.Cancelled => base
                            case _ =>
                                val first = leaked.head
                                val labeled =
                                    "detached-fiber assertion (a fiber spawned by this leaf failed an assert before the leaf was scored; timing-sensitive):\n" + first.diagram
                                TestResult.Failed(labeled, first.cause, elapsed)
                    else base
                // No-assertion flip: a leaf that passed with an empty drain AND zero evaluation counter is a bug.
                // Runs AFTER the leaked flip (so a detached-fiber failure already makes it Failed, checked first)
                // and BEFORE the pendingUntilFixed inversion (so a pendingUntilFixed no-assert leaf sees
                // Passed->Failed here, then the inversion below turns that Failed->Pending, which is correct).
                val trNoAssert =
                    if failOnNoAssertion && as.evaluationCount == 0L then
                        tr match
                            case _: TestResult.Passed => TestResult.Failed(noAssertionDiagram, Maybe.empty, elapsed)
                            case other                => other
                    else tr
                val finalTr = builder.pendingUntilFixed match
                    case Maybe.Present(reason) =>
                        trNoAssert match
                            case _: TestResult.Passed =>
                                val detail = if reason.nonEmpty then s" (was: $reason)" else ""
                                TestResult.Failed(
                                    s"test marked pendingUntilFixed now passes; remove the pendingUntilFixed marker$detail",
                                    Maybe.empty,
                                    elapsed
                                )
                            case _: TestResult.Failed | _: TestResult.TimedOut | _: TestResult.Cancelled =>
                                TestResult.Pending(reason)
                            case other => other
                    case Maybe.Absent => trNoAssert
                Chunk((path, finalTr))
            }
        }
    end runRegisteredBody

    /** Convert an `Abort.run[Throwable]` `Result` into a `TestResult`. A thrown `AssertionFailed` / `TestCancelled` arrives as a
      * `Result.Panic` (raw `throw` in a Kyo computation surfaces as a panic, not a failure); a `Timeout` from `Async.timeout` arrives as a
      * `Result.Failure`. Any other panic is logged and recorded as a failure (never swallowed).
      */
    private def resultToTestResult(result: Result[Throwable, Unit], elapsed: Duration): TestResult =
        result match
            case Result.Success(_)          => TestResult.Passed(elapsed)
            case Result.Failure(_: Timeout) => TestResult.TimedOut(elapsed)
            case Result.Failure(af: AssertionFailed) =>
                TestResult.Failed(af.diagram, Maybe(af.getCause), elapsed)
            case Result.Failure(tc: TestCancelled) => TestResult.Cancelled(tc.reason, elapsed)
            case Result.Failure(t)                 => TestResult.Failed(t.toString, Maybe(t), elapsed)
            case panic: Result.Panic =>
                panic.exception match
                    case af: AssertionFailed => TestResult.Failed(af.diagram, Maybe(af.getCause), elapsed)
                    case tc: TestCancelled   => TestResult.Cancelled(tc.reason, elapsed)
                    case _: Timeout          => TestResult.TimedOut(elapsed)
                    case t =>
                        java.lang.System.err.println(s"[kyo-test] unexpected panic in leaf: $t")
                        TestResult.Failed(t.toString, Maybe(t), elapsed)
    end resultToTestResult

    // ── Filtering ─────────────────────────────────────────────────────────────────────────────

    private def applyFilter(
        leaves: Chunk[(Chunk[String], Maybe[TestBuilder])],
        filter: TestFilter
    ): Chunk[(Chunk[String], Maybe[TestBuilder])] =
        leaves.filter { case (path, builderOpt) =>
            val joined = path.mkString(".")
            val tags   = builderOpt.map(_.tags).getOrElse(Set.empty)

            val passPathInclude = filter.pathInclude.isEmpty || filter.pathInclude.exists(p => Glob.matches(p, joined))
            val passPathExclude = filter.pathExclude.isEmpty || !filter.pathExclude.exists(p => Glob.matches(p, joined))
            val passTagsInclude = filter.tagsInclude.isEmpty || filter.tagsInclude.exists(tags.contains)
            val passTagsExclude = filter.tagsExclude.isEmpty || !filter.tagsExclude.exists(tags.contains)

            passPathInclude && passPathExclude && passTagsInclude && passTagsExclude
        }

    // ── Constructor failure ─────────────────────────────────────────────────────────────────────

    private def constructorFailureReport(suiteInfo: SuiteInfo, reporter: TestReporter, config: RunConfig, t: Throwable): TestReport =
        val sr = SuiteReport(
            suiteInfo.name,
            Chunk((Chunk("<constructor>"), TestResult.Failed(t.toString, Maybe(t), Duration.Zero))),
            Duration.Zero,
            leakCheck = config.leakCheck,
            leakCheckSockets = config.leakCheckSockets,
            leakCheckFileDescriptors = config.leakCheckFileDescriptors,
            leakCheckThreads = config.leakCheckThreads,
            leakCheckFibers = config.leakCheckFibers,
            leakCheckAllowlist = config.leakCheckAllowlist
        )
        reporter.onSuiteComplete(suiteInfo, sr)
        val report = TestReport(Chunk(sr))
        reporter.onRunComplete(report)
        report
    end constructorFailureReport

    // ── No-assertion check ────────────────────────────────────────────────────────────────────────

    /** Diagram string emitted when a leaf passes with zero assertion evaluations. Shared with LeafHarness (api module)
      * via kyo.test.AssertScope so the api self-tests do not import from kyo-test-runner.
      */
    private[runner] val noAssertionDiagram: String = kyo.test.AssertScope.noAssertionDiagram

    // ── Utilities ───────────────────────────────────────────────────────────────────────────────

    /** Install the thread-confined registration context that `TestBase` reads in its constructor. Read synchronously on this thread inside
      * `Instantiate.newInstance`.
      */
    private def installContext(next: TestContext): Unit =
        TestContext.setForInstantiation(next)

    private def simpleName(cls: Class[?]): String =
        cls.getSimpleName.stripSuffix("$")

end TestRunner
