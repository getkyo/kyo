# Analysis: `ArrowEffectTest` "handle.catching / failure" flaky CI failure

## Symptom

CI run https://github.com/getkyo/kyo/actions/runs/29657662740/job/88114788457
(branch `machine-stats-ship`, job `build (linux-x64, ubuntu-latest) / build (JVM)`):

```
[FAIL] handle.catching › failure  (33ms)
java.lang.RuntimeException: Test exception
```

The test expects `handleCatching`'s `recover` to catch the exception thrown by the
handler and produce `"recovered"`. Instead the raw `RuntimeException` escaped and
failed the test. The failure is rare and timing-dependent; the same test passes on
virtually every run, including locally.

## The test

`kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:181`

```scala
"failure" in {
    val effect = ArrowEffect.suspend[Int](Tag[TestEffect1], 42)
    val result = ArrowEffect.handleCatching(Tag[TestEffect1], effect)(
        [C] => (input, cont) => throw new RuntimeException("Test exception"),
        recover = { case _: RuntimeException => "recovered" }
    )
    assert(result.eval == "recovered")
}
```

The test is correct. It exercises exactly the contract `handleCatching` claims:
exceptions thrown by the handler are routed to `recover`.

## Root cause

`handleCatching` has an unprotected re-entry path: when the `Safepoint` declines
inline evaluation at the matched suspension, the handler runs later inside an
`Effect.defer` continuation that is outside every `try/catch recover` block.

`kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:581`:

```scala
def handleLoop(v: ..., context: Context)(using Safepoint): B < (S & S2 & S3) =
    v match
        case kyo: KyoSuspend[...] if effectTag <:< kyo.tag && accept(kyo.input) =>
            Safepoint.handle(kyo.input)(
                eval = handle[Any](kyo.input, kyo(_, context)),
                continue = handleLoop(_, context),
                suspend = handleLoop(kyo, context)          // (A) unprotected
            )
        case kyo: KyoSuspend[...] =>
            new KyoContinue[...](kyo):
                def apply(v, context)(using Safepoint) =
                    try handleLoop(kyo(v, context), context) // (B) protected
                    catch case ex if NonFatal(ex) => ... recover(ex)
        case kyo =>
            done(kyo.unsafeGet)

try handleLoop(v, Context.empty)                             // (C) protected
catch case ex if NonFatal(ex) => ... recover(ex)
```

`Safepoint.handle` (`kyo-kernel/.../internal/Safepoint.scala:212`):

```scala
if !self.enter(frame, value) then
    Effect.defer(suspend)      // handler NOT evaluated now
else
    val a = try eval finally self.exit()
    continue(a)
```

`Safepoint.enter` (`Safepoint.scala:30`) returns `false` when the stack-depth
budget is exceeded, or when an installed interceptor denies entry. In fiber
execution the interceptor is the task itself (`kyo-core/.../scheduler/IOPromise.scala:21`):

```scala
final override def enter(frame: Frame, value: Any) = !shouldPreempt()
```

so a fiber whose time slice is exhausted denies entry, and `Safepoint.handle`
returns `Effect.defer(handleLoop(kyo, context))` instead of evaluating the handler.
That deferred thunk (A) is evaluated later by whatever loop resumes the
computation, with no `recover` wrapper anywhere on the stack: path (C) already
returned normally (it returned the `Defer` suspension, no exception was thrown),
and path (B) never fires because the resumed value is re-entered directly through
the defer's `apply`, not through a `KyoContinue` of a foreign effect.

## Exact failure sequence in CI

kyo-test leaf bodies have type `Unit < (S & Async & Abort[Any] & Scope)`
(`kyo-test/api/shared/src/main/scala/kyo/test/Test.scala`), so the test body runs
on a fiber with the preemption interceptor installed.

1. The fiber evaluates the test body. `val result = ArrowEffect.handleCatching(...)`
   executes inline (it is an `inline def`): `handleLoop` matches the `TestEffect1`
   suspension and calls `Safepoint.handle`.
2. The fiber's time slice happens to be exhausted at that moment (slow, loaded CI
   runner), so `interceptor.enter` returns `false`. `Safepoint.handle` returns
   `Effect.defer(handleLoop(kyo, context))`. The outer `try/catch recover` in
   `handleCatching` completes without observing any exception; `result` is a
   `Defer` suspension.
3. The body then calls `result.eval`. `Safepoint.eval` (`Safepoint.scala:190`)
   nulls the interceptor, `evalLoop` (`Pending.scala:407`) evaluates the `Defer`,
   which re-enters `handleLoop`. This time `enter` succeeds, `eval` runs the
   handler, and the handler throws `RuntimeException("Test exception")`.
4. Nothing between the throw and the test runner catches it: it propagates out of
   `evalLoop`, out of `.eval`, out of the test body. The framework reports the raw
   exception as the failure. `recover` was never consulted.

Why it is flaky: step 2 requires preemption to be requested in the window between
the start of the test body's slice and this exact `Safepoint.handle` call. That
depends on scheduler timing and machine load, which is why it surfaces on CI
runners and essentially never locally.

## Blast radius

`handleCatching` is `private[kyo]` with two production call sites:

- `Abort.runWith` (`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:202`), the
  basis of every `Abort.run`. Its `handle` is `[C] => (input, _) => input` and
  never throws, and user-code throws always surface inside continuation calls
  (path B, protected) or the initial synchronous pass (path C, protected). So
  `Abort.run` does not currently lose exceptions through this hole in practice,
  but only because its handler happens to be non-throwing; the hole is in the
  generic combinator, and any future caller with a throwing `handle` or `done`
  inherits the race.
- The `ArrowEffectTest` suite, where the race is observable directly.

The bug predates the `machine-stats-ship` PR that hit it; it is a latent kernel
race unrelated to that change.

## Fix: APPLIED

`handleCatching`'s `suspend` thunk now carries the same protection the other two
entry points have (`ArrowEffect.scala:584`):

```scala
Safepoint.handle(kyo.input)(
    eval = handle[Any](kyo.input, kyo(_, context)),
    continue = handleLoop(_, context),
    suspend =
        try handleLoop(kyo, context)
        catch
            case ex if NonFatal(ex) =>
                Safepoint.enrich(ex)
                recover(ex)
)
```

`suspend` is passed by-name into `Effect.defer`, so the `try/catch` executes at
resume time, exactly around the re-entry that was unprotected. Repeated
preemption nests further defers, each carrying the same wrapper, so every
re-entry stays covered. This makes every entry into `handleLoop` protected: the
initial synchronous call (outer try), foreign-effect continuations
(`KyoContinue.apply`), and deferred re-entries (this wrapper). The previously
failing regression test now passes.

## Reproduction: DONE, deterministic

Two tests added to the `handle.catching` block of `ArrowEffectTest`
(`kyo-kernel/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:206`), using a
`Safepoint.Interceptor` that denies `enter` once, forcing the defer path without
any scheduler timing:

- `handle.catching › failure when the safepoint defers the handler`: throwing
  handler under forced deferral, expects `"recovered"`. **Fails deterministically
  today with the exact CI symptom** (`java.lang.RuntimeException: Test exception`
  escaping `.eval`); becomes the regression guard once fixed.
- `handle.catching › success when the safepoint defers the handler`: non-throwing
  handler under the same forced deferral, expects `"42"`. Passes today; guards
  the fix's resume path.

Verified with `sbt 'kyo-kernelJVM/testOnly kyo.kernel.ArrowEffectTest'`:

```
FAILURES:
  handle.catching > failure when the safepoint defers the handler  [FAIL]
    java.lang.RuntimeException: Test exception
```

All other `handle.catching` tests (including the original flaky one, which ran on
a non-preempted slice this time) pass.

## Second finding: racy `evalNow` assertions (same preemption family)

The same local run also flaked on a different test:

```
nested effects handling > handleFirst on Nested > done callback receives unwrapped value  [FAIL]
    finalResult.evalNow == Maybe(50)   (observed Absent)
    // at ArrowEffectTest.scala:432
```

Mechanism, confirmed deterministically with a scratch test (denyOnce interceptor
around an untouched `handleFirst` call produced `evalNow == Absent` for a
computation whose handled value is 50; scratch deleted after the run):

- `handleFirst` uses `Safepoint.handle` (`ArrowEffect.scala:384`); one denied
  `enter` (fiber preemption) makes it return `Effect.defer(...)` instead of the
  handled value. This is intended preemption behavior, not a code bug.
- `evalNow` (`Pending.scala:361`) returns `Absent` for any suspension, including
  that `Defer`.
- Therefore any test that builds a computation through a safepoint-entering
  handler inline in a fiber-run body and asserts `evalNow == Maybe(value)` fails
  whenever preemption lands on that handler call. These are test bugs (racy
  assertions), latent CI flakes waiting to fire.

Racy sites found (kyo-kernel):

- `ArrowEffectTest.scala:432` (`handleFirst on Nested`): the one that flaked.
- `PendingTest.scala:156-162` (`evalNow › accepts nested computations`):
  `TestEffect.run(v).evalNow` goes through `ArrowEffect.handle`, same exposure.

Not racy (checked): the `handlePartial` `evalNow` tests (`ArrowEffectTest`
`handlePartial` block and `handlePartial on Nested`), because `handlePartial`
never calls `Safepoint.enter` (it only does `pushFrame`,
`ArrowEffect.scala:637`); and the pure-value/suspension `evalNow` tests in
`PendingTest:145-153`.

### Third mechanism in the same family: `handlePartial` drain spin

`handlePartial` never returns a `Defer`: `partialLoop` drains them inline
(`ArrowEffect.scala:633`). But each drained deferral re-runs the deferred step,
which calls `Safepoint.enter` again. The fiber preempt flag is sticky for the
rest of the slice (`kyo-scheduler/.../Task.scala:19`, `state.preempting` set by
`doPreempt`), so with the test-only `stop = false` a preempted slice turns the
drain into a tight retry spin (test hang) instead of a wrong value. Production
always passes a real `stop` tied to preemption, so this is confined to tests.
Affected: `handlePartial › suspends at effects` and `handlePartial on Nested`
(via `flatten`'s `map`, which enters the safepoint on the pure path,
`Pending.scala:78`).

### Test fixes: APPLIED

All racy sites now wrap their body in `Safepoint.eval { ... }`, which nulls the
interceptor for the duration (exactly what `.eval` does internally), making the
inline-evaluation assertions deterministic without weakening them:

- `ArrowEffectTest › handleFirst on Nested › done callback receives unwrapped value`
- `ArrowEffectTest › handlePartial › suspends at effects`
- `ArrowEffectTest › handlePartial on Nested › unwraps Nested and handles inner suspension`
- `PendingTest › evalNow › accepts nested computations`

New deterministic defer-path regression tests (using the shared `denyOnce`
interceptor, now defined at class level, under `Safepoint.eval` so the real
fiber interceptor cannot compose in):

- `handle.catching › failure when the safepoint defers the handler` (the original
  repro; was failing, passes with the fix)
- `handle.catching › success when the safepoint defers the handler`
- `handleFirst › defers when the safepoint denies entry and completes on evaluation`
  (asserts the denial actually defers via `evalNow.isEmpty`, then that evaluation
  completes with the handled value)
- `handlePartial › drains a deferred handler continuation` (exercises
  `partialLoop`'s Defer drain deterministically)

Also removed: the dead `TestInterceptor` abstract class in the `handlePartial`
block (never instantiated; its finalizer signatures did not even match
`Safepoint.Interceptor`).

Production `evalNow` usages were checked and are sound under deferral:
`Choice.runStream` uses it only as a partition gate (Absent values take the
pending path and still complete) and `IOTask` is the scheduler loop itself.

## Validation

- `kyo-kernelJVM/test`: green (all suites).
- `kyo-kernelJVM/testOnly ArrowEffectTest PendingTest`: 38 + 47 passed, 0 failed.
- `kyo-preludeJVM/test` (Abort inlines `handleCatching`): 823 passed, 0 failed.
- `kyo-kernelJS/testOnly ArrowEffectTest PendingTest`: green.
- `kyo-kernelNative/testOnly` ArrowEffectTest and PendingTest: 38 + 47 passed, 0 failed.
- Repeated JVM runs of both suites (3x): stable green.

## Side observation (not this failure)

`Safepoint.eval` (`Safepoint.scala:190`) restores the interceptor with a direct
field write (`self.interceptor = prevInterceptor`) instead of `setInterceptor`,
so the `hasInterceptor` state bit stays cleared after a nested `.eval` inside a
fiber slice: the restored interceptor is never consulted again until something
calls `setInterceptor`. This does not cause the observed test failure (it makes
preemption less likely, not more), but it looks like an unintended inconsistency
worth a separate look.
