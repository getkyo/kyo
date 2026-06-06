package kyo.test

/** Base class for all kyo-test V3 (next) suites.
  *
  * Extend this class to define a test suite. Register leaves and groups using the `-` infix operator on `String` or `TestBuilder` values in
  * the class body. The body executes at instantiation time; the runner-provided context is wired up at construction.
  *
  * The type parameter `S` is an additive extra effect row. Every leaf body has type `Unit < (S & Async & Abort[Any] & Scope)`: the
  * baseline `Async & Abort[Any] & Scope` is always unioned in regardless of `S`, and a suite cannot drop it by writing a narrow `S`. The
  * baseline `Abort[Any]` (not `Abort[Throwable]`) lets a leaf abort with any value; the runner converts a non-Throwable abort into a
  * `LeafAborted` failure at discharge (mirroring `KyoApp.abortAnyToThrowable`).
  * `Test[Any]` is the common case (baseline only); Scala 3 has no default type arguments, so `extends Test[Any]` is spelled explicitly.
  * Suites that need an extra effect (e.g. `Env[Db]`) declare it in `S` and discharge it per leaf or per group with `.handle`.
  *
  * The implementation lives on [[kyo.test.internal.TestBase]]; this subclass adds the [[SuiteFingerprintMarker]] mixin so sbt's
  * `SubclassFingerprint` discovery picks up user suites. Internal fixtures inside `kyo-test-runner` extend `TestBase` directly to opt out
  * of auto-discovery. The base inherits from none of the legacy test base classes; every feature is ported into kyo-test-owned files.
  *
  * @tparam S
  *   the additive extra effect row a suite's leaf bodies may use beyond the always-present baseline
  * @see
  *   [[kyo.test.internal.TestBase]] for the full DSL and assertion methods
  * @see
  *   [[kyo.test.TestBuilder]] for decorator chaining (.retry, .timeout, .pending, .flaky)
  * @see
  *   [[kyo.test.RunConfig]] for configuring the run (parallelism, filter, reporter)
  */
abstract class Test[S] extends kyo.test.internal.TestBase[S] with SuiteFingerprintMarker
