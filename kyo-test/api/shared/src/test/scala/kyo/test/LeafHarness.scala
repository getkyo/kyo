package kyo.test

import kyo.*
import kyo.test.internal.TestContext
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Drives a single leaf of a `kyo.test` subject suite to a [[TestResult]] from the api self-tests.
  *
  * The api module cannot depend on `kyo-test-runner` (circular), so it cannot use `TestRunner.runToFuture`. This harness reproduces the
  * exact per-leaf discharge the runner performs (TestRunner.runLeaf / resultToTestResult): it installs the next registration context and
  * the legacy assert-reporting context, instantiates the subject (which registers via the `-` DSL), then either returns the terminal result
  * (Ignored / Pending / Skipped) recorded during registration, or discharges the buffered baseline-row body through Kyo (`Scope.run` +
  * `Fiber.initUnscoped` + `Abort.run[Throwable]`) and maps the outcome to a [[TestResult]].
  *
  * Only the single terminal `Fiber#toFuture` bridges to ScalaTest's async `Future`; there is no internal `Future` composition.
  */
object LeafHarness:

    /** Install the next registration context and instantiate the subject. The V3 assert path is context-free (throws directly), so no
      * legacy assert context is installed.
      */
    private def instantiate[S](target: Chunk[Int], discovery: Boolean)(make: => S): TestContext =
        val ctx = new TestContext(target, discovery)
        TestContext.setForInstantiation(ctx)
        val _ = make
        ctx.signalPastEnd()
        ctx
    end instantiate

    /** Map an `Abort.run[Throwable]` `Result` to a [[TestResult]], mirroring `TestRunner.resultToTestResult`. A thrown `AssertionFailed` /
      * `TestCancelled` arrives as a `Result.Panic`; everything else as `Success` / `Failure`.
      */
    private def toTestResult(result: Result[Throwable, Unit]): TestResult =
        result match
            case Result.Success(_) => TestResult.Passed(Duration.Zero)
            case Result.Failure(af: AssertionFailed) =>
                TestResult.Failed(af.diagram, Maybe(af.getCause), Duration.Zero)
            case Result.Failure(tc: TestCancelled) => TestResult.Cancelled(tc.reason, Duration.Zero)
            case Result.Failure(t)                 => TestResult.Failed(t.toString, Maybe(t), Duration.Zero)
            case panic: Result.Panic =>
                panic.exception match
                    case af: AssertionFailed => TestResult.Failed(af.diagram, Maybe(af.getCause), Duration.Zero)
                    case tc: TestCancelled   => TestResult.Cancelled(tc.reason, Duration.Zero)
                    case t =>
                        java.lang.System.err.println(s"[kyo-test] unexpected panic in api self-test leaf: $t")
                        TestResult.Failed(t.toString, Maybe(t), Duration.Zero)

    /** Discharge the buffered baseline-row body of `ctx` to a `Future[TestResult]`. */
    private def discharge(ctx: TestContext, failOnNoAssertion: Boolean = true)(using ExecutionContext, Frame): Future[TestResult] =
        ctx.peekRegisteredLeaf match
            case Maybe.Present((_, terminal)) => Future.successful(terminal)
            case Maybe.Absent                 =>
                // Mint the per-leaf evidence the runner would mint; the body and any fiber it spawns capture it lexically.
                val as = new AssertScope(ctx.peekRegisteredPath.getOrElse(Chunk.empty))
                // The leaf baseline is Abort[Any]; convert it to the Abort[Throwable] discharge here, mirroring
                // TestRunner: `Abort.catching[Throwable]` turns a thrown assertion into an Abort failure, a Throwable
                // abort passes through, a non-Throwable abort is wrapped in LeafAborted.
                val body: Unit < (Async & Abort[Throwable] & Scope) =
                    Abort.run[Any](Abort.catching[Throwable](Sync.defer(ctx.takeRegisteredBody(as)))).map {
                        case Result.Success(value)            => value
                        case Result.Failure(error: Throwable) => Abort.fail(error)
                        case Result.Failure(error)            => Abort.fail(LeafAborted(error))
                        case panic: Result.Panic              => Abort.get(panic)
                    }
                val computation: Result[Throwable, Unit] < (Async & Scope) =
                    Abort.run[Throwable](body)
                val asFuture: Future[Result[Throwable, Unit]] < Sync =
                    Scope.run(computation).handle(Fiber.initUnscoped).map(_.toFuture)
                // Unsafe: sole Kyo->Future bridge at the ScalaTest-edge boundary, mirroring TestRunner.runToFuture (the api module
                // cannot depend on kyo-test-runner, so it cannot reuse that bridge). Discharging the terminal Sync to the produced
                // Future is the single sanctioned seam; everything upstream is pure Kyo.
                import kyo.AllowUnsafe.embrace.danger
                Sync.Unsafe.evalOrThrow(asFuture).map { result =>
                    // Mirror the runner: close the scope after the body join, drain the leaf sink, and flip a non-failing
                    // result to Failed when a detached fiber recorded a failure during the leaf.
                    as.close()
                    val leaked = as.drain()
                    val mapped = toTestResult(result)
                    // Leaked-fiber flip (identical order as runner):
                    val tr = mapped match
                        case _: TestResult.Failed => mapped
                        case _ if leaked.nonEmpty =>
                            val first = leaked.head
                            TestResult.Failed(first.diagram, Maybe(first.getCause), Duration.Zero)
                        case _ => mapped
                    // No-assertion flip (mirrors TestRunner.runRegisteredBody, same ordering):
                    if failOnNoAssertion && as.evaluationCount == 0L then
                        tr match
                            case _: TestResult.Passed =>
                                TestResult.Failed(kyo.test.AssertScope.noAssertionDiagram, Maybe.empty, Duration.Zero)
                            case other => other
                    else tr
                    end if
                }

    /** Run the first (or `target`-addressed) leaf of `make` and return its `(path, TestResult)`. */
    def runLeafWithPath[S](make: => S)(using ExecutionContext, Frame): Future[(Chunk[String], TestResult)] =
        runLeafWithPathOpt(Chunk(0), failOnNoAssertion = true)(make)

    def runLeafWithPath[S](target: Chunk[Int])(make: => S)(using ExecutionContext, Frame): Future[(Chunk[String], TestResult)] =
        runLeafWithPathOpt(target, failOnNoAssertion = true)(make)

    def runLeafWithPath[S](target: Chunk[Int], failOnNoAssertion: Boolean)(make: => S)(using
        ExecutionContext,
        Frame
    ): Future[(Chunk[String], TestResult)] =
        runLeafWithPathOpt(target, failOnNoAssertion)(make)

    private def runLeafWithPathOpt[S](target: Chunk[Int], failOnNoAssertion: Boolean)(make: => S)(using
        ExecutionContext,
        Frame
    ): Future[(Chunk[String], TestResult)] =
        val ctx  = instantiate(target, discovery = false)(make)
        val path = ctx.peekRegisteredLeaf.map(_._1).orElse(ctx.peekRegisteredPath).getOrElse(Chunk.empty)
        discharge(ctx, failOnNoAssertion).map(r => (Chunk.from(path), r))
    end runLeafWithPathOpt

    /** Run the first (or `target`-addressed) leaf of `make` and return only its `TestResult`. */
    def runLeaf[S](make: => S)(using ExecutionContext, Frame): Future[TestResult] =
        runLeafWithPath(Chunk(0))(make).map(_._2)

    def runLeaf[S](target: Chunk[Int])(make: => S)(using ExecutionContext, Frame): Future[TestResult] =
        runLeafWithPath(target)(make).map(_._2)

    def runLeaf[S](target: Chunk[Int], failOnNoAssertion: Boolean)(make: => S)(using ExecutionContext, Frame): Future[TestResult] =
        runLeafWithPath(target, failOnNoAssertion)(make).map(_._2)

    /** Run the discovery pass only (no body discharge): returns `(leafOpt, wasGroup)` exactly like the legacy `ctx.result` shape. */
    def runDiscovery[S](target: Chunk[Int])(make: => S)(using ExecutionContext): Future[(Maybe[(Chunk[String], TestResult)], Boolean)] =
        val ctx = instantiate(target, discovery = true)(make)
        Future.successful((ctx.peekRegisteredLeaf, ctx.peekWasGroup))

end LeafHarness
