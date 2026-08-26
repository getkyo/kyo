package kyo.test

import kyo.Chunk
import kyo.Duration
import kyo.Maybe
import kyo.minutes

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
  * @param globallySequential
  *   when `true`, this suite's leaves run one at a time across every globally-sequential suite in the process. For a resource shared
  *   beyond one suite (a CLI, an account, a fixed port), which `parallelism` cannot protect since it only orders one suite's own leaves
  *   inside the shared pool. Suites without the flag are unaffected.
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
  * @param heartbeatInterval
  *   how long a single leaf may run before the runner reports it as still running via `TestReporter.onLeafHeartbeat`, repeating every
  *   interval thereafter. This makes a slow or hung leaf visible while it runs (the console reporter is silent between a leaf's start and
  *   finish otherwise, so a hung leaf is invisible). `Duration.Infinity` disables heartbeats; defaults to 1 minute.
  * @param leakCheck
  *   when `true` (the default), a forked test JVM runs end-of-run leak detection once all of its suites finish: it fails the run if a fiber is
  *   still running on the scheduler, a file descriptor opened during the run is still open, or a non-daemon thread the run started is still
  *   alive. Only active inside an sbt forked JVM (the one quiescent, isolated point); a no-op otherwise. This is the master switch; the four
  *   category toggles ([[leakCheckSockets]], [[leakCheckFileDescriptors]], [[leakCheckThreads]], [[leakCheckFibers]]) turn off one category
  *   while keeping the rest. Override per suite with `def config = super.config.leakCheck(false)` (all categories) or a single category toggle
  *   for a suite whose design legitimately holds one kind of resource for the whole run.
  * @param leakCheckSockets
  *   when `true` (the default), socket descriptors are included in the file-descriptor probe along with files, directories, and pipes. A suite
  *   that drives a transport which defers a closed socket's fd release (so the fd briefly outlives the run) can turn off this one category via
  *   `super.config.leakCheckSockets(false)` while keeping file-descriptor, thread, and fiber detection on.
  * @param leakCheckFileDescriptors
  *   when `true` (the default), non-socket descriptors (files, directories, pipes) are included in the file-descriptor probe.
  * @param leakCheckThreads
  *   when `true` (the default), the non-daemon thread probe runs.
  * @param leakCheckFibers
  *   when `true` (the default), the scheduler/fiber probe runs.
  * @param leakCheckAllowlist
  *   substring patterns that excuse an expected long-lived resource from [[leakCheck]] without disabling the whole check. A fiber finding is
  *   excused if any pattern appears in the offending worker's full stack; a thread finding if any pattern appears in the thread's name or
  *   stack; a descriptor finding if any pattern appears in the descriptor's target (e.g. a file path). A socket's target is an opaque
  *   `socket:[inode]` with a per-run inode, so it has no stable substring to match; a suite whose fork leaves sockets open uses
  *   [[leakCheckSockets]]`(false)` instead. Prefer the allowlist over disabling when an expected resource is identifiable: the suite keeps
  *   detecting every other leak.
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
    globallySequential: Boolean = false,
    timeout: Duration = Duration.Infinity,
    randomize: Maybe[Long] = Maybe.empty,
    strictStructure: Boolean = false,
    countOnly: Boolean = false,
    listOnly: Boolean = false,
    failOnNoAssertion: Boolean = true,
    heartbeatInterval: Duration = 1.minutes,
    leakCheck: Boolean = true,
    leakCheckSockets: Boolean = true,
    leakCheckFileDescriptors: Boolean = true,
    leakCheckThreads: Boolean = true,
    leakCheckFibers: Boolean = true,
    leakCheckAllowlist: Chunk[String] = Chunk.empty
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

    /** Returns a copy whose leaves run one at a time WITHIN THIS SUITE (parallelism = 1).
      *
      * This is suite-scoped and does not stop other suites' leaves from running alongside them. It is the
      * right setting for state this suite owns; it is NOT enough for a resource shared with other suites,
      * because leaves from those suites keep running in the same process-global pool. A suite guarding a
      * shared resource wants [[globallySequential]] instead, and the difference is invisible whenever the
      * build forks one JVM per suite, since there the two coincide.
      */
    def sequential: RunConfig = copy(parallelism = 1)

    /** Returns a copy whose leaves run one at a time ACROSS EVERY globally-sequential suite in the process.
      *
      * For a resource that is shared beyond one suite: a CLI, an account, a subscription, a fixed port.
      * [[sequential]] cannot protect those, because it only orders one suite's own leaves inside a pool
      * that every suite pushes into, so several suites each running "sequentially" still contend.
      *
      * Leaves of every suite carrying this flag form a single sequential stream. Suites WITHOUT it are
      * unaffected and keep running in parallel: the flag exists to serialize contenders for one resource,
      * not to quiesce the run, and freezing unrelated leaves would cost the whole suite's parallelism for
      * nothing.
      */
    def globallySequential(globallySequential: Boolean): RunConfig = copy(globallySequential = globallySequential)

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

    /** Returns a copy with the given heartbeat interval. A leaf still running after this interval is reported via
      * `TestReporter.onLeafHeartbeat`, and again every interval thereafter; `Duration.Infinity` disables heartbeats.
      */
    def heartbeatInterval(heartbeatInterval: Duration): RunConfig = copy(heartbeatInterval = heartbeatInterval)

    /** Returns a copy with end-of-run leak detection enabled or disabled. This is the master switch: when `false`, none of the category probes
      * (sockets, file descriptors, threads, fibers) run for this suite. Prefer a single category toggle below, or [[leakCheckAllowlist]], over
      * disabling everything when only a specific category or resource needs excusing.
      */
    def leakCheck(leakCheck: Boolean): RunConfig = copy(leakCheck = leakCheck)

    /** Returns a copy with socket-descriptor leak detection enabled or disabled, leaving the other categories alone. Socket detection is on by
      * default; disable it only for the specific suites that drive a transport which defers a closed socket's fd release, so every other
      * category and every other suite stays checked.
      */
    def leakCheckSockets(leakCheckSockets: Boolean): RunConfig = copy(leakCheckSockets = leakCheckSockets)

    /** Returns a copy with non-socket file-descriptor leak detection (files, directories, pipes) enabled or disabled, leaving the other
      * categories on.
      */
    def leakCheckFileDescriptors(leakCheckFileDescriptors: Boolean): RunConfig = copy(leakCheckFileDescriptors = leakCheckFileDescriptors)

    /** Returns a copy with non-daemon thread leak detection enabled or disabled, leaving the other categories on. */
    def leakCheckThreads(leakCheckThreads: Boolean): RunConfig = copy(leakCheckThreads = leakCheckThreads)

    /** Returns a copy with fiber (scheduler-still-busy) leak detection enabled or disabled, leaving the other categories on. */
    def leakCheckFibers(leakCheckFibers: Boolean): RunConfig = copy(leakCheckFibers = leakCheckFibers)

    /** Returns a copy with the given allowlist patterns ADDED to the existing ones (additive, so `super.config.leakCheckAllowlist(...)`
      * accumulates). A fiber, thread, or descriptor leak whose stack, thread name, or descriptor target contains any pattern is excused from
      * [[leakCheck]].
      */
    def leakCheckAllowlist(patterns: String*): RunConfig = copy(leakCheckAllowlist = leakCheckAllowlist ++ Chunk.from(patterns))

end RunConfig

object RunConfig:
    /** Default run configuration: parallelism 0 (auto), absent reporter (runner substitutes ConsoleReporter), normal verbosity, no filter,
      * no randomization, no strict-structure validation.
      */
    val default: RunConfig = RunConfig()
end RunConfig
