package kyo.test.internal

import kyo.Abort
import kyo.Async
import kyo.Duration
import kyo.Frame
import kyo.Maybe
import kyo.Retry
import kyo.Schedule
import kyo.Scope
import kyo.kernel.<
import kyo.millis
import kyo.test.PlatformSet
import kyo.test.PlatformTestBuilder
import kyo.test.RunConfig
import kyo.test.TestBuilder
import kyo.test.discardGroup
import kyo.test.discardScoped
import kyo.test.gateOf

/** Implementation base for kyo-test V3 (next) suites.
  *
  * Self-contained: inherits from none of the legacy test base classes. It mixes in the ported [[KyoTestReflect]] (reflective instantiation)
  * and [[TypeCheck]] (`typeCheck` / `typeCheckFailure`), and the public [[kyo.test.Test]] subclass adds the
  * [[kyo.test.SuiteFingerprintMarker]] mixin so sbt's `SubclassFingerprint` discovery picks up user suites. Internal fixtures inside
  * `kyo-test-runner` extend this `TestBase` directly to avoid being auto-discovered.
  *
  * The type parameter `S` is an additive extra effect row. Every leaf body has type `Unit < (S & Async & Abort[Any] & Scope)`: the
  * baseline `Async & Abort[Any] & Scope` is always unioned in regardless of `S`. A pure `()` auto-lifts into `Unit < (...)`, so a
  * sync leaf and an `Async` leaf share one body type with no `Future`/`toFuture` shim.
  *
  * The DSL methods use `protected` visibility because the framework contract is inheritance-based; this is a deliberate exception to the
  * `No protected` convention (CONTRIBUTING P5), documented here as permitted for abstract DSL base classes.
  *
  * @tparam S
  *   the additive extra effect row a suite's leaf bodies may use beyond the always-present baseline
  * @see
  *   [[kyo.test.Test]] the public subclass that adds sbt discovery via SuiteFingerprintMarker
  * @see
  *   [[kyo.test.TestBuilder]] for the decorator type produced by `.retry`, `.timeout`, etc.
  * @see
  *   [[kyo.test.AssertionFailed]] thrown by assertion macros on mismatch
  * @see
  *   [[kyo.Schedule]] used by `.retry(s: Schedule)` and `.flaky` for retry policies
  */
abstract class TestBase[S] extends KyoTestReflect with TypeCheck:

    /** Cursor context for `-` registration, captured at instantiation via thread-local. Closes over `this` so registrations resolve against
      * the right context.
      */
    protected val regCtx: TestContext = TestContext.takeFromThreadLocal()

    // ── DSL: extension on String ──────────────────────────────────────

    extension (name: String)

        /** Register a GROUP: the body always runs at registration so its nested `-`/`in` calls (however produced: inline, loops, helper
          * defs, conditionals) fire and register children. No compile-time inference; leaves are written with `in`. Mirrors ScalaTest's `-`
          * (scope).
          */
        inline infix def -(inline body: => Unit < (S & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            regCtx.visitGroup[S](name, body)
        end -

        /** Register a LEAF: the body is the test, deferred (never run during registration). Mirrors ScalaTest's `in`. Together with `-`
          * (always a group, body runs), this removes all compile-time inference; the marker says exactly what the node is.
          */
        inline infix def in(inline body: kyo.test.AssertScope ?=> Unit < (S & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            regCtx.visitLeaf[S](name, body)
        end in

        /** Discharge one or more effects beyond the baseline locally, producing an enriched builder whose terminal `-` registers a
          * baseline-shaped leaf/group.
          *
          * The handler `h` peels `S1` (the body's extra row) down to baseline: it is a result-PRESERVING poly-function
          * `[A] => (A < (S1 & baseline)) => A < baseline`. Result-changing handlers (those returning `(X, A)`, e.g. `Var.runTuple`,
          * `Console.withOut`) must be wrapped by the author (`.map(_._2)`); they do not fit this contract (RI-003).
          *
          * Per-leaf granularity (O7): when applied to a group node, the composed transform is invoked freshly around each descended leaf
          * body, so each test gets fresh `Var`/`Env`/`Random` state.
          */
        def handle[S1](
            h: [A] => (A < (S1 & Async & Abort[Any] & Scope)) => A < (Async & Abort[Any] & Scope)
        ): EnrichedTestBuilder[S1] =
            new EnrichedTestBuilder[S1](TestBuilder(name), h)

        def focus: TestBuilder = TestBuilder(name).copy(focus = true)
        def slow: TestBuilder  = TestBuilder(name).copy(tags = Set("slow"))

        /** Non-terminal decorator: marks the leaf ignored; the body is registered but not run. `.ignore(reason)` records why. */
        def ignore: TestBuilder                 = name.ignore("")
        def ignore(reason: String): TestBuilder = TestBuilder(name).copy(ignore = Maybe(reason))

        /** Non-terminal decorator: runs the body and inverts its outcome (still-failing -> Pending, now-passing -> Failed, the tripwire to
          * remove the marker). The kyo-native equivalent of ScalaTest's pendingUntilFixed. Runs once.
          */
        def pendingUntilFixed: TestBuilder                 = name.pendingUntilFixed("")
        def pendingUntilFixed(reason: String): TestBuilder = TestBuilder(name).copy(pendingUntilFixed = Maybe(reason))

        def tagged(tags: String*): TestBuilder =
            TestBuilder(name).copy(tags = tags.toSet)

        def timeout(d: Duration): TestBuilder =
            TestBuilder(name).copy(timeout = Maybe(d))

        def retry(n: Int): TestBuilder =
            TestBuilder(name).copy(retrySchedule = Maybe(kyo.Schedule.fixed(Duration.Zero).take(n)))

        def retry(s: kyo.Schedule): TestBuilder =
            TestBuilder(name).copy(retrySchedule = Maybe(s))

        /** Mark this leaf as known-flaky: retries up to 3 times with linear 100ms backoff, and tags the leaf "flaky" for CI filtering. */
        def flaky: TestBuilder =
            TestBuilder(name).copy(
                retrySchedule = Maybe(kyo.Schedule.linear(100L.millis).take(3)),
                tags = Set("flaky")
            )

        def times(n: Int): TestBuilder =
            TestBuilder(name).copy(repeat = n)

        /** Run this leaf 100 times (or `n`); EVERY run must pass. The inverse of `.flaky` (ZIO's `nonFlaky`): proves a leaf is not
          * flaky rather than tolerating flakiness. Alias for `.times`.
          */
        def nonFlaky: TestBuilder         = name.times(100)
        def nonFlaky(n: Int): TestBuilder = name.times(n)

        def only(cond: => Boolean): TestBuilder =
            TestBuilder(name).copy(onlyIf = Maybe(() => cond))

        // ── Platform filters ─────────────────────────────────────────────
        // Each produces a PlatformTestBuilder carrying a phantom PlatformSet marker in P.
        // The terminal `in`/`-` on that carrier gates the body with a compile-time
        // `inline if gateOf[P]`, so an excluded leaf body is NOT emitted on a disabled
        // platform (absent, not skipped). `.jvm`/`.js`/`.native` are single-platform
        // restrictions identical to `.onlyJvm`/`.onlyJs`/`.onlyNative`.

        def jvm: PlatformTestBuilder[PlatformSet.OnlyJvm]         = PlatformTestBuilder(TestBuilder(name))
        def js: PlatformTestBuilder[PlatformSet.OnlyJs]           = PlatformTestBuilder(TestBuilder(name))
        def native: PlatformTestBuilder[PlatformSet.OnlyNative]   = PlatformTestBuilder(TestBuilder(name))
        def wasm: PlatformTestBuilder[PlatformSet.OnlyWasm]       = PlatformTestBuilder(TestBuilder(name))
        def notJvm: PlatformTestBuilder[PlatformSet.NotJvm]       = PlatformTestBuilder(TestBuilder(name))
        def notJs: PlatformTestBuilder[PlatformSet.NotJs]         = PlatformTestBuilder(TestBuilder(name))
        def notNative: PlatformTestBuilder[PlatformSet.NotNative] = PlatformTestBuilder(TestBuilder(name))
        def notWasm: PlatformTestBuilder[PlatformSet.NotWasm]     = PlatformTestBuilder(TestBuilder(name))

        /** Restrict this leaf to exactly JVM; the body is compile-excluded on JS and Native (absent, not skipped). */
        def onlyJvm: PlatformTestBuilder[PlatformSet.OnlyJvm] = PlatformTestBuilder(TestBuilder(name))

        /** Restrict this leaf to exactly JS; the body is compile-excluded on JVM and Native (absent, not skipped). */
        def onlyJs: PlatformTestBuilder[PlatformSet.OnlyJs] = PlatformTestBuilder(TestBuilder(name))

        /** Restrict this leaf to exactly Native; the body is compile-excluded on JVM and JS (absent, not skipped). */
        def onlyNative: PlatformTestBuilder[PlatformSet.OnlyNative] = PlatformTestBuilder(TestBuilder(name))

        /** Restrict this leaf to exactly WebAssembly; the body is compile-excluded on JVM, JS, and Native (absent, not skipped). */
        def onlyWasm: PlatformTestBuilder[PlatformSet.OnlyWasm] = PlatformTestBuilder(TestBuilder(name))

    end extension

    // ── DSL: extension on TestBuilder ────────────────────────────────

    extension (b: TestBuilder)

        /** Register with the TestBuilder's accumulated metadata. Honor terminal decorators (ignore, pending, onlyIf) before dispatching. */
        inline infix def -(inline body: => Unit < (S & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            b.ignore match
                case Maybe.Present(reason) => regCtx.registerIgnored(b.name, reason)
                case _ =>
                    b.onlyIf match
                        case Maybe.Present(cond) if !cond() => regCtx.registerSkipped(b.name, "condition false")
                        case _ =>
                            regCtx.visitGroupWithBuilder[S](b.name, b, body)
        end -

        /** Like the String-extension `in`, but carrying this TestBuilder's decorators: register a LEAF unconditionally (deferred body),
          * after honoring the terminal decorators (ignore, pending, platform filter, onlyIf). No group inference.
          */
        inline infix def in(inline body: kyo.test.AssertScope ?=> Unit < (S & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            b.ignore match
                case Maybe.Present(reason) => regCtx.registerIgnored(b.name, reason)
                case _ =>
                    b.onlyIf match
                        case Maybe.Present(cond) if !cond() => regCtx.registerSkipped(b.name, "condition false")
                        case _                              => regCtx.visitLeafWithBuilder[S](b.name, b, body)
        end in

        /** Discharge one or more effects beyond the baseline locally, carrying this TestBuilder's accumulated decorators (`.timeout`,
          * `.retry`, `.ignore`, platform filters, etc.) forward into the enriched builder. See the String-extension `handle` scaladoc for
          * the handler contract and per-leaf granularity.
          */
        def handle[S1](
            h: [A] => (A < (S1 & Async & Abort[Any] & Scope)) => A < (Async & Abort[Any] & Scope)
        ): EnrichedTestBuilder[S1] =
            new EnrichedTestBuilder[S1](b, h)

        def slow: TestBuilder = b.copy(tags = b.tags + "slow")

        /** Non-terminal decorator: marks the leaf ignored; the body is registered but not run. The reason records why. */
        def ignore(reason: String): TestBuilder = b.copy(ignore = Maybe(reason))

        /** Non-terminal decorator: runs the body and inverts its outcome (still-failing -> Pending, now-passing -> Failed, the tripwire to
          * remove the marker). The kyo-native equivalent of ScalaTest's pendingUntilFixed. Runs once.
          */
        def pendingUntilFixed(reason: String): TestBuilder = b.copy(pendingUntilFixed = Maybe(reason))

        def tagged(tags: String*): TestBuilder  = b.copy(tags = b.tags ++ tags.toSet)
        def timeout(d: Duration): TestBuilder   = b.copy(timeout = Maybe(d))
        def retry(n: Int): TestBuilder          = b.copy(retrySchedule = Maybe(kyo.Schedule.fixed(Duration.Zero).take(n)))
        def retry(s: kyo.Schedule): TestBuilder = b.copy(retrySchedule = Maybe(s))

        /** Mark this leaf as known-flaky on top of existing decorators: 3 retries with linear 100ms backoff, adds "flaky" tag. */
        def flaky: TestBuilder =
            b.copy(
                retrySchedule = Maybe(kyo.Schedule.linear(100L.millis).take(3)),
                tags = b.tags + "flaky"
            )

        def times(n: Int): TestBuilder = b.copy(repeat = n)

        /** Run this leaf 100 times (or `n`); EVERY run must pass (ZIO's `nonFlaky`, the inverse of `.flaky`). Alias for `.times`. */
        def nonFlaky: TestBuilder         = b.times(100)
        def nonFlaky(n: Int): TestBuilder = b.times(n)

        def only(cond: => Boolean): TestBuilder =
            b.copy(onlyIf = Maybe(() => cond))

        // ── Platform filters ─────────────────────────────────────────────
        // Convert the accumulated builder into a PlatformTestBuilder carrying a phantom
        // PlatformSet marker; the terminal `in`/`-` gates the body at compile time.

        def jvm: PlatformTestBuilder[PlatformSet.OnlyJvm]         = PlatformTestBuilder(b)
        def js: PlatformTestBuilder[PlatformSet.OnlyJs]           = PlatformTestBuilder(b)
        def native: PlatformTestBuilder[PlatformSet.OnlyNative]   = PlatformTestBuilder(b)
        def wasm: PlatformTestBuilder[PlatformSet.OnlyWasm]       = PlatformTestBuilder(b)
        def notJvm: PlatformTestBuilder[PlatformSet.NotJvm]       = PlatformTestBuilder(b)
        def notJs: PlatformTestBuilder[PlatformSet.NotJs]         = PlatformTestBuilder(b)
        def notNative: PlatformTestBuilder[PlatformSet.NotNative] = PlatformTestBuilder(b)
        def notWasm: PlatformTestBuilder[PlatformSet.NotWasm]     = PlatformTestBuilder(b)

        /** Restrict this leaf to exactly JVM; the body is compile-excluded on JS and Native (absent, not skipped). */
        def onlyJvm: PlatformTestBuilder[PlatformSet.OnlyJvm] = PlatformTestBuilder(b)

        /** Restrict this leaf to exactly JS; the body is compile-excluded on JVM and Native (absent, not skipped). */
        def onlyJs: PlatformTestBuilder[PlatformSet.OnlyJs] = PlatformTestBuilder(b)

        /** Restrict this leaf to exactly Native; the body is compile-excluded on JVM and JS (absent, not skipped). */
        def onlyNative: PlatformTestBuilder[PlatformSet.OnlyNative] = PlatformTestBuilder(b)

        /** Restrict this leaf to exactly WebAssembly; the body is compile-excluded on JVM, JS, and Native (absent, not skipped). */
        def onlyWasm: PlatformTestBuilder[PlatformSet.OnlyWasm] = PlatformTestBuilder(b)

    end extension

    // ── DSL: extension on PlatformTestBuilder[P] ─────────────────────────
    // Produced by a platform filter. Chainable decorators preserve P; the terminal
    // `in`/`-` branches on `inline if gateOf[P]`: enabled -> register exactly as the
    // unfiltered DSL; disabled -> discard the body UNAPPLIED so its code is never emitted.

    extension [P](pb: PlatformTestBuilder[P])

        /** Register a platform-gated LEAF. On a disabled platform `gateOf[P]` is the literal `false`, so the registration arm is dropped and
          * `discardScoped` consumes the body unapplied: no code for it reaches the platform's output.
          */
        inline infix def in(inline body: kyo.test.AssertScope ?=> Unit < (S & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            inline if gateOf[P] then
                val b = pb.builder
                b.ignore match
                    case Maybe.Present(reason) => regCtx.registerIgnored(b.name, reason)
                    case _ =>
                        b.onlyIf match
                            case Maybe.Present(cond) if !cond() => regCtx.registerSkipped(b.name, "condition false")
                            case _                              => regCtx.visitLeafWithBuilder[S](b.name, b, body)
                end match
            else
                discardScoped[S](body)
        end in

        /** Register a platform-gated GROUP. Same compile-time gate as `in`: on a disabled platform the group body is discarded unapplied. */
        inline infix def -(inline body: => Unit < (S & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            inline if gateOf[P] then
                val b = pb.builder
                b.ignore match
                    case Maybe.Present(reason) => regCtx.registerIgnored(b.name, reason)
                    case _ =>
                        b.onlyIf match
                            case Maybe.Present(cond) if !cond() => regCtx.registerSkipped(b.name, "condition false")
                            case _                              => regCtx.visitGroupWithBuilder[S](b.name, b, body)
                end match
            else
                discardGroup[S](body)
        end -

        // Decorators chained after a platform filter preserve P so the gate still applies at the terminal.

        def focus: PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(focus = true))
        def slow: PlatformTestBuilder[P]  = PlatformTestBuilder(pb.builder.copy(tags = pb.builder.tags + "slow"))

        def ignore: PlatformTestBuilder[P]                 = PlatformTestBuilder(pb.builder.copy(ignore = Maybe("")))
        def ignore(reason: String): PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(ignore = Maybe(reason)))

        def pendingUntilFixed: PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(pendingUntilFixed = Maybe("")))
        def pendingUntilFixed(reason: String): PlatformTestBuilder[P] =
            PlatformTestBuilder(pb.builder.copy(pendingUntilFixed = Maybe(reason)))

        def tagged(tags: String*): PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(tags = pb.builder.tags ++ tags.toSet))
        def timeout(d: Duration): PlatformTestBuilder[P]  = PlatformTestBuilder(pb.builder.copy(timeout = Maybe(d)))
        def retry(n: Int): PlatformTestBuilder[P] =
            PlatformTestBuilder(pb.builder.copy(retrySchedule = Maybe(kyo.Schedule.fixed(Duration.Zero).take(n))))
        def retry(s: kyo.Schedule): PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(retrySchedule = Maybe(s)))

        def flaky: PlatformTestBuilder[P] =
            PlatformTestBuilder(pb.builder.copy(
                retrySchedule = Maybe(kyo.Schedule.linear(100L.millis).take(3)),
                tags = pb.builder.tags + "flaky"
            ))

        def times(n: Int): PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(repeat = n))

        def only(cond: => Boolean): PlatformTestBuilder[P] = PlatformTestBuilder(pb.builder.copy(onlyIf = Maybe(() => cond)))

        // A second platform filter combines with P via PlatformSet.Both rather than replacing it (gateOf reduces Both to an &&),
        // so `.notNative.notWasm` is enabled only where both hold. Single-platform filters keep the `.jvm` == `.onlyJvm` identity.

        def jvm: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyJvm]]           = PlatformTestBuilder(pb.builder)
        def js: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyJs]]             = PlatformTestBuilder(pb.builder)
        def native: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyNative]]     = PlatformTestBuilder(pb.builder)
        def wasm: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyWasm]]         = PlatformTestBuilder(pb.builder)
        def notJvm: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.NotJvm]]         = PlatformTestBuilder(pb.builder)
        def notJs: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.NotJs]]           = PlatformTestBuilder(pb.builder)
        def notNative: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.NotNative]]   = PlatformTestBuilder(pb.builder)
        def notWasm: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.NotWasm]]       = PlatformTestBuilder(pb.builder)
        def onlyJvm: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyJvm]]       = PlatformTestBuilder(pb.builder)
        def onlyJs: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyJs]]         = PlatformTestBuilder(pb.builder)
        def onlyNative: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyNative]] = PlatformTestBuilder(pb.builder)
        def onlyWasm: PlatformTestBuilder[PlatformSet.Both[P, PlatformSet.OnlyWasm]]     = PlatformTestBuilder(pb.builder)

    end extension

    // ── Assertion macros ──────────────────────────────────────────────────────
    // The assertion surface is a single power-assert. The six structured asserts
    // (assertEquals, assertNotEquals, assertMatches, assertContains, assertEmpty,
    // assertNonEmpty) and softly are not provided by this base; intercept*/fail/cancel are kept as-is.

    /** Power-assert that throws `AssertionFailed` on `cond == false`, including a diagram of subexpression values to aid diagnosis. */
    protected inline def assert(inline cond: Boolean)(using inline f: Frame, inline as: kyo.test.AssertScope): Unit =
        ${ kyo.test.internal.AssertMacro.assertImpl('cond, 'f, 'as) }

    /** Power-assert with an explicit user message appended to the diagram on failure. */
    protected inline def assert(inline cond: Boolean, inline msg: String)(using inline f: Frame, inline as: kyo.test.AssertScope): Unit =
        ${ kyo.test.internal.AssertMacro.assertWithMsgImpl('cond, 'msg, 'f, 'as) }

    /** Marks a leaf as intentionally asserting no runtime value: its verification is structural (it compiles,
      * runs without error, or matched an expected case) rather than a checked value. Records an evaluation so the
      * no-assertion check is satisfied. Equivalent to `assert(true)`; prefer a real assertion when a value exists.
      */
    protected inline def succeed(using inline f: Frame, inline as: kyo.test.AssertScope): Unit =
        assert(true)

    /** `succeed` with a note documenting why the leaf has no runtime value to assert (shown only if it ever fails). */
    protected inline def succeed(inline msg: String)(using inline f: Frame, inline as: kyo.test.AssertScope): Unit =
        assert(true, msg)

    /** Evaluates `body` and asserts that it throws an exception of type `E`; returns the caught exception on success. */
    protected def intercept[E <: Throwable](body: => Any)(
        using
        f: Frame,
        ct: scala.reflect.ClassTag[E],
        as: kyo.test.AssertScope
    ): E =
        kyo.test.internal.Intercept.intercept[E](body)

    /** Evaluates `body` and asserts that it throws an exception of type `E` whose message equals `msg`; returns the caught exception. */
    protected def interceptMessage[E <: Throwable](msg: String)(body: => Any)(
        using
        f: Frame,
        ct: scala.reflect.ClassTag[E],
        as: kyo.test.AssertScope
    ): E =
        kyo.test.internal.Intercept.interceptMessage[E](msg)(body)

    /** Evaluates `body` and asserts that it throws an exception of type `E`; returns Unit on success. */
    protected def interceptThrown[E <: Throwable](body: => Any)(
        using
        f: Frame,
        ct: scala.reflect.ClassTag[E],
        as: kyo.test.AssertScope
    ): Unit =
        kyo.test.internal.Intercept.interceptThrown[E](body)

    /** Evaluates `body` and asserts that it throws an exception of type `E` whose message equals `msg`; returns Unit on success. */
    protected def interceptThrownMessage[E <: Throwable](msg: String)(body: => Any)(
        using
        f: Frame,
        ct: scala.reflect.ClassTag[E],
        as: kyo.test.AssertScope
    ): Unit =
        kyo.test.internal.Intercept.interceptThrownMessage[E](msg)(body)

    /** Unconditionally throws `AssertionFailed` with the given message. */
    protected inline def fail(inline msg: String = "test failed")(using inline f: Frame, inline as: kyo.test.AssertScope): Nothing =
        ${ kyo.test.internal.AssertMacro.failImpl('msg, 'f, 'as) }

    /** Unconditionally fails the test, preserving `cause` as the underlying throwable (and its stack trace). Mirrors
      * ScalaTest's `fail(Throwable)`: use when a caught/observed exception is itself the reason the test failed. Records the failure into the
      * leaf scope before throwing so a detached fiber's `fail(cause)` still reaches the leaf.
      */
    protected def fail(cause: Throwable)(using f: Frame, as: kyo.test.AssertScope): Nothing =
        as.recordEvaluated()
        val failure = new kyo.test.AssertionFailed(cause.toString, f, kyo.Maybe(cause.toString), kyo.Maybe(cause))
        as.record(failure)
        throw failure
    end fail

    /** Unconditionally throws `TestCancelled` with the given message. */
    protected inline def cancel(inline msg: String = "test cancelled")(using inline f: Frame): Nothing =
        ${ kyo.test.internal.AssertMacro.cancelImpl('msg, 'f) }

    /** Cancel the test (not fail it) when `cond` is false. Mirrors ScalaTest's `assume`: an unmet precondition
      * (wrong platform, too few cores, missing external dependency) should SKIP the test, not mark it failed.
      * Distinct from `assert`, whose false condition is a genuine failure. (Without this, a bare `assume` would
      * bind to `scala.Predef.assume`, which throws an `AssertionError` (a failure) on false instead.)
      */
    protected inline def assume(inline cond: Boolean, inline msg: String = "assumption not met")(using inline f: Frame): Unit =
        if !cond then cancel(msg)

    /** Assert that `cond` eventually holds: re-evaluates every 10ms until it yields true, bounded by the per-test timeout. For
      * eventually-consistent concurrent state. A condition still false at the timeout surfaces as the leaf's TimedOut/failure.
      *
      * The `using AssertScope` keeps `assertEventually` inside the assert family (so it is only well-typed inside a leaf body). If the retry
      * ever surfaces a final `AssertionError`, it is converted to an `AssertionFailed`, recorded into the leaf scope, and then surfaced as a
      * leaf failure, so a detached fiber's exhausted `assertEventually` is captured the same way a plain `assert` is.
      *
      * When `cond` itself contains an `assert`, the macro records an `AssertionFailed` into the leaf scope before throwing. On a non-final
      * failing attempt the retry catches and discards that throwable, so its stale record must be removed (mirroring `intercept`); otherwise
      * the runner would flip a leaf that eventually passes to Failed. Removal is by the exact caught instance, never a blanket drain, so
      * records made by other fibers are untouched.
      */
    protected def assertEventually[S1](cond: => Boolean < S1)(using f: Frame, as: kyo.test.AssertScope): Unit < (Async & S1) =
        as.recordEvaluated()
        Abort.run[AssertionError] {
            Retry[AssertionError](Schedule.fixed(10.millis)) {
                // Run only the AssertionError channel here so the thrown assertion outcomes are observable for the un-record/retry
                // handling below, while any OTHER failure (an Abort[Closed] from `cond`, an arbitrary Abort[Throwable] value)
                // flows straight through unhandled and propagates out of assertEventually with its original effect/semantics.
                Abort.run[AssertionError] {
                    cond.map {
                        case false => throw new AssertionError("assertEventually: condition not met")
                        case true  =>
                    }
                }.map {
                    case kyo.Result.Success(_) => ()
                    // The condition-not-met AssertionError assertEventually itself throws, or any other AssertionError raised by an
                    // inner assert: re-raise it so the surrounding Retry[AssertionError] retries this attempt.
                    case kyo.Result.Failure(error) => throw error
                    // An inner `assert` recorded its AssertionFailed into the leaf scope before throwing (it surfaces here as a Panic
                    // since AssertionFailed is a RuntimeException, not an AssertionError). Un-record exactly that instance (mirroring
                    // `intercept`) so a passing later attempt is not flipped to Failed, then normalize to an AssertionError so
                    // Retry[AssertionError] retries this attempt. Never a blanket drain, never another fiber's record.
                    case kyo.Result.Panic(af: kyo.test.AssertionFailed) =>
                        as.remove(af)
                        throw new AssertionError(af.getMessage, af)
                    // Any other panic (e.g. InterruptedException): re-panic so it propagates unchanged rather than being normalized
                    // into a retryable AssertionError.
                    case panic: kyo.Result.Panic => Abort.error(panic)
                }
            }
        }.map {
            case kyo.Result.Success(_)     => ()
            case kyo.Result.Failure(error) =>
                // The retry exhausted: this final failure must stay recorded. Surface the original assert failure when the
                // attempt wrapped one, so the leaf reports the real diagram rather than the normalized AssertionError.
                val failure = error.getCause match
                    case af: kyo.test.AssertionFailed => af
                    case _ => new kyo.test.AssertionFailed(error.getMessage, f, kyo.Maybe(error.getMessage), kyo.Maybe(error))
                as.record(failure)
                throw failure
            case panic: kyo.Result.Panic => throw panic.exception
        }
    end assertEventually

    // ── Hooks (override per suite) ─────────────────────────────────────

    protected def name: String       = getClass.getSimpleName.stripSuffix("$")
    protected def randomize: Boolean = false
    protected val randomSeed: Long   = java.lang.System.currentTimeMillis()

    /** Default per-test timeout for every leaf in this suite that has no explicit `.timeout(...)`. Override to change it, e.g.
      * `override def timeout = 30.seconds`. Returns `Duration.Infinity` (no timeout) when a debugger is attached so breakpoints don't trip
      * it; 120s otherwise (raised from 60s after legitimate tests intermittently exceeded 60s under CI load;
      * the bound still stops a stuck leaf from burning CI credits).
      */
    protected def timeout: Duration =
        if kyo.internal.Platform.isDebugEnabled then Duration.Infinity
        else Duration.fromJava(java.time.Duration.ofSeconds(120))

    /** User-overridable per-suite configuration hook: the runner uses this when the caller does not pass an explicit RunConfig. Override it
      * to opt a suite into, e.g., sequential execution: `override def config = super.config.sequential`.
      */
    def config: RunConfig = RunConfig.default.copy(timeout = timeout)

    /** User-overridable per-suite hook applied around EVERY leaf body. The default is identity. Override to establish
      * setup that must surround each leaf in the suite, e.g.
      * `override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame) =
      *      HttpClient.withConfig(_.timeout(60.seconds))(body)`.
      * This is the kyo-test equivalent of overriding ScalaTest's `withFixture`: a single point that wraps every leaf,
      * instead of repeating the wrapper at each `in { … }`. The runner applies it
      * to the raw leaf body before the per-leaf retry/timeout/scope discharge.
      */
    def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) = body

    /** Carrier produced by `.handle` that tracks the still-undischarged body row `S0` as a type parameter and composes additional handlers.
      *
      * The `transform` field is a BY-VALUE polymorphic function: a polymorphic-function TYPE forbids by-name parameters (a hard Scala 3
      * restriction, RI-003.md:26-43), so the laziness is preserved only on the terminal `-`'s `inline body: => ...` (a normal method, where
      * `=>` is legal); the by-name `body` adapts to the by-value poly param at the `transform[Unit](body)` application.
      *
      * `transform` peels `S0 & baseline` to `baseline` (`Async & Abort[Any] & Scope`). Each `handle[S1]` prepends a handler `h`
      * peeling `S1 & baseline` to `S0 & baseline`, composing inner-to-outer: `transform[A](h[A](body))`. The terminal `-` routes the
      * baseline-shaped result through the Phase-2 `regCtx.visit*` path under `S := Any`, so the runner only ever sees
      * `Unit < (Async & Abort[Any] & Scope)` leaves (00-locked-decisions.md:50-59).
      *
      * @tparam S0
      *   the residual (still-undischarged) body row this builder's terminal `-` expects
      */
    final private[test] class EnrichedTestBuilder[S0](
        builder: TestBuilder,
        private val transform: [A] => (A < (S0 & Async & Abort[Any] & Scope)) => A < (Async & Abort[Any] & Scope)
    ):

        /** Chain a further handler that peels `S1` down to the current residual row `S0`, returning a builder whose tracked row is now
          * `S1`. The composition applies `h` inner (peeling `S1` to `S0`) then the carried `transform` outer (peeling `S0` to baseline).
          */
        def handle[S1](
            h: [A] => (A < (S1 & Async & Abort[Any] & Scope)) => A < (S0 & Async & Abort[Any] & Scope)
        ): EnrichedTestBuilder[S1] =
            new EnrichedTestBuilder[S1](
                builder,
                [A] => (body: A < (S1 & Async & Abort[Any] & Scope)) => transform[A](h[A](body))
            )

        /** Terminal: register a leaf/group whose body is `S0`-shaped. The composed `transform` peels it to baseline, then it routes through
          * the Phase-2 `regCtx.visit*` path (under `S := Any`) so the runner sees a baseline-shaped leaf.
          *
          * Contravariance of `<`'s `-S` (Pending.scala:42) makes this REJECT an under-discharged body (one whose residual row still needs
          * an effect beyond `S0`): such a body is not a subtype of `Unit < (S0 & baseline)`. An over-discharged body (needing fewer
          * effects) is safely accepted.
          *
          * The honored terminal decorators (`ignore`/`pending`/platform/`onlyIf`) carried in `builder` gate dispatch exactly as the
          * `TestBuilder` `-` does.
          */
        inline infix def -(inline body: => Unit < (S0 & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            builder.ignore match
                case Maybe.Present(reason) => regCtx.registerIgnored(builder.name, reason)
                case _ =>
                    builder.onlyIf match
                        case Maybe.Present(cond) if !cond() => regCtx.registerSkipped(builder.name, "condition false")
                        case _                              =>
                            // `-` is ALWAYS a group: register the RAW `S0`-shaped block so its nested `-`/`in` calls fire during
                            // discovery descent. `transform` is applied per descended leaf by the runner (O7). Leaves use `in`.
                            regCtx.visitGroupWithBuilder[S0](builder.name, builder, body)
        end -

        /** Always-leaf form of the enriched terminal (deferred body, `transform` peels `S0` to baseline), honoring the builder's terminal
          * decorators first. No group inference.
          */
        inline infix def in(inline body: kyo.test.AssertScope ?=> Unit < (S0 & Async & Abort[Any] & Scope))(using inline f: Frame): Unit =
            builder.ignore match
                case Maybe.Present(reason) => regCtx.registerIgnored(builder.name, reason)
                case _ =>
                    builder.onlyIf match
                        case Maybe.Present(cond) if !cond() => regCtx.registerSkipped(builder.name, "condition false")
                        case _                              =>
                            // The runner mints the per-leaf scope; peel S0 to baseline inside the context function so
                            // `transform` is applied per descended leaf with the leaf's scope already supplied to `body`.
                            regCtx.visitLeafWithBuilder[Async & Abort[Any] & Scope](
                                builder.name,
                                builder,
                                (as: kyo.test.AssertScope) ?=> transform[Unit](body(using as))
                            )
        end in

    end EnrichedTestBuilder

end TestBase
