package kyo.test

import kyo.Duration
import kyo.Maybe

/** Configuration for a test run.
  *
  * Pass a `RunConfig` to `TestRunner.run` to control which tests execute and how results are reported. All fields have sensible defaults;
  * `RunConfig.default` is equivalent to `RunConfig()`.
  *
  * @param reporter
  *   the reporter that receives lifecycle events; defaults to `Maybe.empty`; when absent, the runner substitutes `ConsoleReporter` with the
  *   run's verbosity setting
  * @param verbosity
  *   controls how much output the console reporter emits; used by `TestRunner.run` when constructing the default `ConsoleReporter`
  * @param filter
  *   path/tag/class filter applied before any leaf runs; `TestFilter.empty` runs everything
  * @param parallelism
  *   selects sequential vs parallel execution under the process-global leaf pool; 1 means within-suite sequential (this suite's leaves run
  *   one at a time in the global pool); 0 (the default) and any N > 1 mean parallel (the suite pushes all its leaves to the pool, whose
  *   process-global `globalK` bound sets the real degree of concurrency). N > 1 is NO LONGER a per-suite cap.
  * @param timeout
  *   maximum duration for each leaf; `Duration.Infinity` means no timeout (default)
  * @param randomize
  *   when `Present`, shuffles leaf execution order using the given seed for reproducibility; `Absent` preserves declaration order
  * @param strictStructure
  *   when `true`, the runner validates that each leaf's observed name path during execution matches the path captured during discovery; on
  *   mismatch the leaf's result is replaced with `TestResult.Cancelled("structural drift detected: …")` instead of accepting the
  *   executed-but-wrong leaf. The cursor model trusts the suite's tree shape to be stable across instantiations; turn this on for suites
  *   that branch on side effects or unseeded randomness and want loud failures instead of silently mis-attributed results.
  * @param failOnNoAssertion
  *   when `true` (the default), a leaf that completes Passed having evaluated zero assertions is flipped to Failed. A test that makes no
  *   assertions proves nothing. To opt out per-leaf, write `succeed` (or `succeed("note")`) in the leaf body (counts as one evaluation,
  *   always passes). To disable suite-wide, override `def config = super.config.failOnNoAssertion(false)`.
  * @see
  *   `kyo.test.runner.TestRunner.runReport` which accepts a RunConfig as its second argument
  * @see
  *   [[kyo.test.TestFilter]] for path/tag/class filtering options stored in the filter field
  * @see
  *   [[kyo.test.TestReporter]] the reporter interface configured via the reporter field
  * @see
  *   [[kyo.test.Verbosity]] controls console output detail level
  */
final case class RunConfig(
    reporter: Maybe[TestReporter] = Maybe.empty,
    verbosity: Verbosity = Verbosity.Normal,
    filter: TestFilter = TestFilter.empty,
    parallelism: Int = 0,
    timeout: Duration = Duration.Infinity,
    randomize: Maybe[Long] = Maybe.empty,
    strictStructure: Boolean = false,
    countOnly: Boolean = false,
    listOnly: Boolean = false,
    failOnNoAssertion: Boolean = true
) derives CanEqual:

    /** Returns a copy with the given reporter installed. */
    def reporter(reporter: TestReporter): RunConfig = copy(reporter = Maybe(reporter))

    /** Returns a copy with the given console verbosity. */
    def verbosity(verbosity: Verbosity): RunConfig = copy(verbosity = verbosity)

    /** Returns a copy with the given leaf filter. */
    def filter(filter: TestFilter): RunConfig = copy(filter = filter)

    /** Returns a copy with the given parallelism. Under the process-global leaf pool: 1 = within-suite sequential; 0 (auto) and N > 1 =
      * parallel (the pool's globalK bound sets the real degree; N > 1 is not a per-suite cap).
      */
    def parallelism(parallelism: Int): RunConfig = copy(parallelism = parallelism)

    /** Returns a copy that runs fully sequentially (parallelism = 1). */
    def sequential: RunConfig = copy(parallelism = 1)

    /** Returns a copy with the given per-leaf timeout. */
    def timeout(timeout: Duration): RunConfig = copy(timeout = timeout)

    /** Returns a copy that shuffles leaf order using the given seed. */
    def randomize(seed: Long): RunConfig = copy(randomize = Maybe(seed))

    /** Returns a copy with the given strict leaf-name-path validation setting. */
    def strictStructure(strictStructure: Boolean): RunConfig = copy(strictStructure = strictStructure)

    /** Returns a copy with the given count-only (discovery) setting. When true, the runner walks the suite to enumerate every leaf
      * (including those generated at construction by macros, loops, and fan-out helpers) and reports the leaf count WITHOUT executing any
      * leaf body. Deterministic and fast: no assertions, async, timeouts, or external resources run.
      */
    def countOnly(countOnly: Boolean): RunConfig = copy(countOnly = countOnly)

    /** Returns a copy with the given list-only (discovery) setting. Like [[countOnly]], but additionally prints every discovered leaf's
      * full name path (one per line), enabling an exact name-by-name diff against another framework's test listing. Implies count-only
      * behavior: no leaf body runs.
      */
    def listOnly(listOnly: Boolean): RunConfig = copy(listOnly = listOnly)

    /** Returns a copy with the given fail-on-no-assertion setting. When false, a leaf that completes Passed with zero
      * assertion evaluations is left as Passed instead of flipped to Failed.
      */
    def failOnNoAssertion(failOnNoAssertion: Boolean): RunConfig = copy(failOnNoAssertion = failOnNoAssertion)

end RunConfig

object RunConfig:
    /** Default run configuration: parallelism 0 (auto), absent reporter (runner substitutes ConsoleReporter), normal verbosity, no filter,
      * no randomization, no strict-structure validation.
      */
    val default: RunConfig = RunConfig()
end RunConfig
