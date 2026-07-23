package kyo.test

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.Chunk
import scala.annotation.implicitNotFound

/** Per-leaf evidence value that captures assertion failures, including those raised by detached or leaked fibers, and counts how many
  * assertions the leaf evaluated.
  *
  * Introduced only by the leaf operator `in` as an implicit context-function parameter, so every `assert` inside a leaf (and any fiber that
  * leaf spawns) captures the SAME instance lexically. The assert macro splices `scope.record(failure)` immediately before the throw, so a
  * failure recorded by a fiber the runner never joins still lands in this sink. Alongside the failure sink the scope holds an evaluation
  * counter that the assert runtime increments, which drives the runner's no-assertion check (a leaf that evaluates zero assertions fails).
  *
  * The constructor is `private[kyo]` (an `AssertScope` is unforgeable: instances come only from `in`), but `record` is public because the
  * assert macro splices the call into the user's package. `close` and `drain` are `private[kyo]`: the runner and the api self-test harness
  * (both under `kyo`) drain the sink right after the body join. `recordEvaluated` is `private[kyo]` (source-restricted to the kyo package),
  * which compiles to a public JVM method; macro-generated and inline code can therefore splice calls to it from any call site, including
  * non-kyo packages, without it being part of the public source API.
  *
  * @param path
  *   the full name path of the leaf this scope belongs to
  * @param diagnostics
  *   sink for the after-close "fiber outlived its test" warning; defaults to `java.lang.System.err`. Injectable so the api
  *   self-test can capture it deterministically without mutating process-global `System.err`, which races under concurrent leaves.
  */
@implicitNotFound(
    """no given kyo.test.AssertScope is in scope, so `assert`, `fail`, and `intercept` cannot be used here.

kyo.test.AssertScope is the per-leaf evidence kyo-test uses to record assertion failures, including ones raised by detached or leaked fibers. The `in { ... }` leaf operator supplies it implicitly, which is why assertions are only valid inside a leaf.

To fix, one of:
  - move this assertion inside an `in { ... }` leaf body (it cannot sit in a `-` group builder or the suite class body);
  - if it lives in a helper method, give the helper a `(using kyo.test.AssertScope)` parameter so it shares the calling leaf's scope."""
)
final class AssertScope private[kyo] (
    val path: Chunk[String],
    diagnostics: String => Unit = (s: String) => java.lang.System.err.println(s)
):

    private val sink: ConcurrentLinkedQueue[AssertionFailed] = new ConcurrentLinkedQueue[AssertionFailed]()

    private val evaluations: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(0L)

    @volatile private var closed: Boolean = false

    /** Record an assertion failure. While the scope is open the failure is queued for the runner to drain. After close (the leaf was already
      * scored) a failure can only come from a fiber that outlived its test, so it is logged via the `diagnostics` sink (default stderr)
      * instead of queued.
      */
    def record(failure: AssertionFailed): Unit =
        if closed then
            diagnostics(
                s"[kyo-test] assertion failure after leaf '${path.mkString(" > ")}' was scored (a fiber outlived its test): ${failure.diagram}"
            )
            // Best-effort: also enqueue into the process-global collector so the runner can turn this into a failed leaf
            // (the run goes red) IF the running suite still reaches its drain point. A fiber that asserts after ALL suites
            // are scored cannot become a test event (the tasks are already done); it still emits the stderr warning above.
            AssertScope.leakedAfterClose.add((path, failure)): Unit
        else
            sink.add(failure): Unit
    end record

    /** Remove a previously recorded failure from the sink by identity. Used when a failure is HANDLED on the leaf's own joined path (the
      * `intercept` macro deliberately catches an `AssertionFailed`), so it should not survive as a stale leak record that the runner would
      * otherwise flip the leaf to Failed for. `AssertionFailed` defines no `equals`, so removal matches the exact instance.
      *
      * Public for the same reason `record` is: the `intercept` macro splices this call into the user's package.
      */
    def remove(failure: AssertionFailed): Unit =
        val _ = sink.remove(failure)

    /** Record that one assertion was evaluated. Increments the counter unconditionally (regardless of whether the scope
      * is open or closed) so counts accumulate across retry attempts. Called only by the framework-internal assert
      * runtime (AssertMacro, Intercept, TypeCheck, TestBase). Qualified `private[kyo]` restricts direct source
      * references to the kyo package, but qualified-private compiles to a public JVM method, so macro-generated code
      * can splice calls to it from non-kyo call sites without it being part of the public source API.
      */
    private[kyo] def recordEvaluated(): Unit =
        val _ = evaluations.incrementAndGet()
        ()
    end recordEvaluated

    /** The total number of assertion evaluations recorded since this scope was minted. Read once by the runner and the
      * api self-test harness after close().
      */
    private[kyo] def evaluationCount: Long = evaluations.get()

    /** Close the scope: the leaf has been scored, so later records are logged rather than queued. */
    private[kyo] def close(): Unit = closed = true

    /** Drain the queued failures into a Chunk, emptying the sink. */
    private[kyo] def drain(): Chunk[AssertionFailed] =
        val builder = Chunk.newBuilder[AssertionFailed]
        var next    = sink.poll()
        while next != null do
            builder += next
            next = sink.poll()
        builder.result()
    end drain

end AssertScope

object AssertScope:

    /** Diagram text for the no-assertion check, shared so the runner (kyo-test-runner) and the api self-test harness (LeafHarness) report the
      * identical message without kyo-test-api depending on kyo-test-runner.
      */
    private[kyo] val noAssertionDiagram: String =
        "leaf passed without evaluating any assertion\n\n" +
            "The leaf body ran to completion and produced no failures, but no assert, intercept, fail,\n" +
            "assertEventually, typeCheck, assertSnapshot, assertSchemaSnapshot, assertGoldenSnapshot, or forAll call was reached on this run.\n\n" +
            "Fix: add a concrete assertion on the result (preferred), or if the intent is genuinely\n" +
            "'verify it runs without error', write `succeed` (or `succeed(\"why\")`) in the leaf body.\n\n" +
            "To disable this check run-wide: RunConfig.default.failOnNoAssertion(false).\n" +
            "To disable suite-wide: override def config = super.config.failOnNoAssertion(false)."

    /** Process-global collector for assertion failures recorded AFTER their leaf's scope was closed (a fiber that outlived its test). The
      * instance `record` enqueues `(path, failure)` here on its CLOSED branch, in addition to the stderr warning. The runner drains this once
      * per suite run (just before assembling the SuiteReport) and turns each entry into a synthetic failed leaf, so an after-leaf leak fails
      * the run on JVM / JS / Native with no per-platform task edits.
      *
      * Best-effort: an after-leaf leak only becomes a failed leaf if it is enqueued before the running suite reaches the drain point. A fiber
      * that asserts after ALL suites are scored cannot become a test event (the tasks are already done); it still emits the stderr warning.
      */
    private[kyo] val leakedAfterClose: java.util.concurrent.ConcurrentLinkedQueue[(kyo.Chunk[String], AssertionFailed)] =
        new java.util.concurrent.ConcurrentLinkedQueue[(kyo.Chunk[String], AssertionFailed)]()

    /** Poll-drain the global after-close collector into a Chunk, emptying it. Mirrors the instance `drain`. */
    private[kyo] def drainLeakedAfterClose(): kyo.Chunk[(kyo.Chunk[String], AssertionFailed)] =
        val builder = Chunk.newBuilder[(kyo.Chunk[String], AssertionFailed)]
        var next    = leakedAfterClose.poll()
        while next != null do
            builder += next
            next = leakedAfterClose.poll()
        builder.result()
    end drainLeakedAfterClose

end AssertScope
