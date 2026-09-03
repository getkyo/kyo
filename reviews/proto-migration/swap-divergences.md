# Swap divergences: kyo-kernel sources against origin/main

Reference: origin/main `bd7f20b146`. Branch state after the diff-minimization pass over the sources
(commits `935ebde214` and `b741da8349`): every kernel source with a counterpart on main was rewritten from
main's text, so that the remaining hunks are design divergences, each marked at its site with a
`// Diverges from main:` or `// Not on main:` comment. Names of type and value parameters, member order,
imports, line breaking, blank lines and the scaladoc of members whose meaning did not change are main's.
The per-file hunk reports are in `swap-hunks/A.md` to `G.md`. Tests are covered in the section at the end.

## Diff size per file, before and after the pass

Added/removed lines against origin/main (`git diff --numstat`).

| file | before | after |
|---|---|---|
| `kyo/Kyo.scala` | 285/438 | 177/227 |
| `kyo/kernel.scala` | 2/0 | 2/0 |
| `kyo/kernel/ArrowEffect.scala` | 583/450 | 574/440 |
| `kyo/kernel/ContextEffect.scala` | 188/55 | 182/44 |
| `kyo/kernel/Effect.scala` | 107/44 | 104/44 |
| `kyo/kernel/Isolate.scala` | 205/50 | 121/41 |
| `kyo/kernel/Loop.scala` | 362/218 | 327/170 |
| `kyo/kernel/Pending.scala` | 200/247 | 115/202 |
| `kyo/kernel/internal/CanLift.scala` | 42/21 | 17/12 |
| `kyo/kernel/internal/Context.scala` | 36/48 | 32/44 |
| `kyo/kernel/internal/KyoInternal.scala` | 8/75 | 8/75 |
| `kyo/kernel/internal/LiftMacro.scala` | 0/41 (deleted) | 11/31 |
| `kyo/kernel/internal/package.scala` | 24/6 | 23/6 |
| `kyo/kernel/internal/Implicits.scala` | 72/0 (new) | 75/0 (new) |

`Kyo.scala` is main's file with `Safepoint` removed from every signature, verified by applying that one
transformation to main's copy and diffing: the only other line is the divergence comment. `ArrowEffect.scala`
and `Pending.scala` stay large because main's multi-effect `handle` overloads, `handleCatching`,
`handlePartial` and the lift block are on the removed side, and this kernel's handler family and node
constructions on the added side.

## Files with a counterpart on main

| file | divergence | ruling |
|---|---|---|
| `kyo/kernel.scala` | `export kernel.Arrow` added; the rest is main's file verbatim | the continuation type is public |
| `kyo/Kyo.scala` | every combinator's function parameter loses the `Safepoint ?=>` context and the `Safepoint` evidence; bodies are main's | the evaluator polls the budget |
| `kyo/kernel/Pending.scala` | `A \| Pending[A, S]` for `A \| Kyo[A, S]`; the four combinators build `Arrow` and `DeferWith` nodes and poll the safepoint slot inline; `chain` replaces `unsafeGet` (`Nested.unnest` fills its role); `flatten` builds an `Arrow.Step`; `eval` runs `Eval`; `fromKyo` converts from `Pending`; the `Render` given has a `Nested` case; the lift block is in `Implicits`; the ten `handle` overloads are main's verbatim | representation |
| `kyo/kernel/ArrowEffect.scala` | `handle` is `handleCont` (the clause receives the continuation as an `Arrow` and may introduce `S2`); the two, three and four effect `handle` overloads, `handleCatching` and `handlePartial` are gone; stateful `handleLoop` is `handleLoopState`; `done` and `recover` overloads on every handler; `handleContOperation`, the `With` variants and `Mask` are new; `handleFirst` is `private[kyo]` (its inline body names `FirstSuspended`); `dispatchFirst` walks down through handle, park and defer nodes and is not inline | D2, D5 |
| `kyo/kernel/ContextEffect.scala` | suspensions build `SuspendContext` nodes; `handle(tag, value)` and `handle(tag, ifUndefined, ifDefined)` are `handleInheritable`; `handle` takes explicit `fork`, `join`, `done`, `release` strategies; the `Noninheritable` marker is gone | D4 |
| `kyo/kernel/Effect.scala` | `Effect.catching` gone; `bracket` with `Finalize` and `Cell`, the node-building `defer` overloads, a public `defer` and `deferInline` over `DeferWith` | D2 |
| `kyo/kernel/Isolate.scala` | `nest` lifts with `Nested.nest`; a state-taking `run` and an `apply` are new; `andThen` has no `Identity` short circuit; `Identity`, `runDetached` and `restoring` are gone, `Contextual` (stack snapshot, `Forked` handler wrapper) is the base case of `deriveImpl`'s fold | D4 |
| `kyo/kernel/Loop.scala` | `Outcome*` covariant in `O`; `Done` wraps a settled answer that is itself a `Continue`; `continue` and `done` answer as computations; the combinators defer the rest of the loop through a cached `Arrow.Step`; `repeat` and `indexed` test the body for `Pending`; `Continue*` report to the debugger and print themselves | representation |
| `kyo/kernel/internal/Context.scala` | region-ordered bindings (`bind`, `get` returning `Maybe`, `unbind`) for main's opaque `Map[Tag[Any], AnyRef]` with the `NoninheritableFlag` and `inherit`; visibility is main's `private[kyo]` | D4 |
| `kyo/kernel/internal/CanLift.scala` | plain givens guarded by `NotGiven`, a `checkSingleton` macro for singletons that also rejects nested computations, no `unsafe.bypass` | lift design |
| `kyo/kernel/internal/LiftMacro.scala` | only `abortCastUnitImpl` (the issue-903 guidance); `liftMacro` and `defaultLift` are gone | lift design |
| `kyo/kernel/internal/KyoInternal.scala` | `Kyo` is the public supertype of `Pending` and `Arrow` with `frame`; main's suspension ADT (`KyoSuspend`, `KyoContinue`, `KyoDefer`, `Nested`) is the `Pending` node family in `PendingInternal` | representation |
| `kyo/kernel/internal/package.scala` | `maxStackDepth` fixed at 512 (main: `Platform.maxStackDepth`); `maxTraceFrames` 64 (main: 16); `IX`, `OX`, `EX` live in `Eval`; `short` and `site` rendering helpers | platform sweep pending |
| `kyo/kernel/internal/Implicits.scala` | the lift is a plain implicit gated by `CanLift` (main: the `LiftMacro.liftMacro` splice) with `liftAnyVal` and `liftUnit` folded into its match; `abortCastUnit` and the function liftings as on main, in a trait mixed into the `<` companion | lift design, see below |
| `internal/Safepoint.scala` | one shared file on main; `jvm-native` and `js-wasm` files here | platform split |

## Restorations from main that were tried and kept back

Each is measured, and the reason is in a comment at the site.

- **`Arrow` under main's `import kyo.*`.** Restoring the wildcard made `Arrow` resolve through the forwarder
  that `export kernel.Arrow` generates in package `kyo`: a wildcard import outranks a same-package name from
  another file. Every inlined `Arrow.id` then carried an extra `invokevirtual` (the `map` pin went from 18 to
  21 bytes). The five files that use the wildcard (`Pending`, `ArrowEffect`, `Loop`, `Isolate`,
  `ContextEffect`) import `kyo.kernel.Arrow` explicitly, one line each.
- **`fromKyo` as main's `implicit private[kernel] inline def`.** The inline form binds a prefix proxy at
  every expansion site (+2 bytes per suspension); a `private[kernel]` non-inline form goes through a
  generated inline accessor with an extra `getstatic` (+3). The pinned shape, public and not inline, expands
  to one `invokevirtual`. `ArrowEffectBytecodeTest` pins it.
- **The lift block inside the `<` companion at main's position.** Does not compile: inside the companion the
  alias is transparent, so a `liftPureFunction` lambda is typed with the dealiased union as its result,
  which the inliner's opaque proxies do not map back when the conversion feeds `map` (`PendingTest`, "a pure
  function passes to map point-free"). Ascribing the match and delegating it to an inline helper in the
  internal package fail the same way. The `Implicits` trait stays; its comment records this. The clean
  batch build itself was fine, so the compile-suspension concern was not the blocker.
- **`default` as the parameter name of the defaulting `suspend`/`suspendWith`.** Kept as main's, since
  named arguments make it user visible, through a local def alias because the node built has a `default`
  member. A local `inline def` is rejected by the compiler (nested inline methods), the plain def keeps the
  by-name timing and allocates nothing.

## Main text kept although it is stale or worse

Recorded so a follow-up on main can fix them; changing them here would add hunks that are not divergences.

- `ArrowEffect.suspend`/`suspendWith` keep main's misspelt `funcionInput` parameter name.
- `Isolate.deriveImpl`'s error message says `new Isolate.Stateful[MyEffect, Any]`, a class main no longer
  has; the branch had corrected it and the correction was reverted to main's text.
- `Loop.continue[A, B, o]` keeps main's lowercase `o`; `import scala.util.NotGiven` in `Loop.scala` and
  `import scala.annotation.tailrec` in `Kyo.scala` are unused on main and here.
- `Loop.repeat` on main evaluates its body one more time than `n` and discards the last answer; here it
  evaluates it exactly `n` times, with the comment at the site.
- `package.scala` keeps main's sentence that each platform sets `maxStackDepth` above the hardcoded 512.

Doc text that was updated because the member changed meaning: the `Kyo` scaladoc, the `Context` scaladoc,
the `ContextEffect` class doc paragraph on async boundaries, the `ArrowEffect` class doc list of handler
families and the `handleLoop` doc that compared it to `handle`.

## Files without a counterpart

New: `kyo/kernel/Arrow.scala`; `internal/{Eval, Stack, Handler, PendingInternal, Nested, Debugger,
EffectTrace, Implicits}.scala`; `internal/Report.scala` (jvm-native, js-wasm); jvm `kyo/kernel/debug/{ConsoleDebugger,
DebugSession}.scala`.

Removed: `internal/Trace.scala` and the platform `TracePool.scala` (replaced by `EffectTrace` and `Report`).

Kept from the branch, not on main's kernel: `kyo/Closed.scala` (main keeps it in kyo-core; `Effect.bracket`
throws it), `kyo/Test.scala` (test base `IsolateTest` extends), `kyo/TestVariant.scala` (main has it).

## Build

`build.sbt`, kyo-kernel block: the scalatest Test dependency in place of main's `.withKyoTest` (the
kernel suites run on ScalaTest, see the Tests section); `Test / unmanagedJars := Seq.empty` while the
stack above the kernel is red (the doctest driver jar is built from it); the javassist Test dependency
for the bytecode pins; the jmh scope with the cross-bench dependencies and the compile-bench compiler.
Main's `Test / sourceGenerators += TestVariant.generate.taskValue` is back, so `KyoForeachCollTest`'s
`List` and `Chunk` variants are generated and run again (the branch had dropped the line, and with it
that coverage). The JOL dependency and the demo fork settings are gone with `protodemo`.

## Tests

The twelve suites with a counterpart on main were rewritten on main's skeleton by agents T1 to T6
(reports in `swap-hunks/T*.md`, reviewed hunk by hunk): main's case names, groups, order, fixtures and
imports, this kernel's own cases after main's, every main case present under main's name or ported to
this kernel's API, and a main case omitted only where the feature is gone by design. Base class: main's
suites extend `kyo.test.Test[Any]`; here they extend ScalaTest's `AnyFreeSpec`, or the module-local
`kyo.Test` (an `AnyFreeSpec` with `typeCheckFailure`) where main's cases pin compiler messages. kyo-test
runs its leaves as fibers on the scheduler the kernel powers, so the kernel suites stay on ScalaTest;
nothing moved toward kyo-test.

### Diff size per suite, before and after the pass

| suite | before | after | main cases | omitted (reason) |
|---|---|---|---|---|
| `kyo/KyoTest` | 51/54 | 23/13 | all | none; the two `pendingUntilFixed` stack-safety cases run everywhere and assert the real answers (`2 * n + 1`, `n + n / 32`); `toString` pins this kernel's exact renderings |
| `kyo/KyoForeachTest` | 6/4 | 2/2 | all | none |
| `kyo/KyoForeachCollTest` | 18/17 | 3/1 | all | none; its `List` and `Chunk` variants are generated again, see Build |
| `kernel/ArrowEffectTest` | 2710/581 | 2902/328 | 34 of 38 | four cases on `Safepoint.Interceptor` deferral (handleFirst, handle.catching x2, handlePartial); every handler is a lazy node here, pinned by the `handleCont` lazy case and the `Eval.partial` cases |
| `kernel/ContextEffectTest` | 403/105 | 393/21 | all 11 | none |
| `kernel/EffectTest` | 89/79 | 81/59 | 4 of 8 | the `catching` group (4): `Effect.catching` is gone, the `recover` arms carry it, covered in `ArrowEffectTest` (pointers per case in T4.md) |
| `kernel/IsolateTest` | 547/174 | 587/82 | 26 of 28 | `restoring` (`Safepoint.Interceptor`, `Isolate.internal.restoring`), `context inheritance` (`Noninheritable`); ten main cases ported over `isolate.run` and `Contextual.capture` |
| `kernel/LoopTest` | 314/2 | 306/1 | all | none; main's body is byte-identical |
| `kernel/PendingTest` | 566/248 | 505/34 | all 50 | none; `evalNow / accepts nested computations` asserts `Absent` before the run (no eager handling here), the three denied-safepoint cases use a drained budget for main's targeted interceptor |
| `kernel/internal/CanLiftTest` | 71/49 | 24/8 | all 11 | none |
| `kernel/internal/ContextTest` | 47/65 | 47/41 | 9 of 11 | the two `inherit` cases (`Noninheritable`) |
| `kyo/TestVariant` | 0 | 0 | | |

Expectations adjusted on main's text, each with a `// Diverges from main:` comment at the site:
`ArrowEffectTest` "execution is tail-recursive" bounds the stack variance at 20 (the evaluator's frames
between the test body and the loop add a constant 12, main's inline handling at most 10) and
"handleFirst on Nested" reads its answer at `eval` by closing the standing row; `KyoTest` as above;
`PendingTest` as above. `Loop.repeat` needed no adjustment in main's case, since main's body is
deferred and its extra evaluation discards an unrun node; the settled-body case that pins the
difference is this kernel's.

New pin, on this kernel's by-name `handle` stages: `PendingTest` "a by-name stage sees an exception
thrown while the receiver is built" fails against main's strict `f1` at the two-stage call and passes
here.

### Main's suites with no file here

| main suite | what became of its cases |
|---|---|
| jvm `BytecodeTest` (4 pins) | every expansion site it pins is pinned in `PendingBytecodeTest` (`map`) and `ArrowEffectBytecodeTest` (`suspend`, `suspendWith`, `handleCont`), with this kernel's numbers |
| `internal/TraceTest` | four cases ported into `EffectTraceTest` ("repeated frames", "counts only consecutive repeats", the carrier's one-line-per-frame rendering, "bug #1172 null frames"); the golden `Trace.render` snapshots, the count suffix and the ring-index cases are main's rendering and ring, which do not exist here; the frame budget is pinned by the cap group |
| `internal/TracePoolTest`, jvm `internal/TracePoolConcurrencyTest` | `TracePool` does not exist; nothing is pooled |
| jvm `internal/SafepointTest` | four cases ported into `SafepointConcurrencyTest` under main's names (slot identity across and within threads); interceptors, `ensure` (now `Effect.bracket`, covered by `EffectBracketTest`) and the `State` depth/interceptor bits are gone. A `SafepointTest` at main's jvm path would duplicate the class the branch already has under `jvm-native` |

Ours-only suites, unchanged by the pass: `ArrowEffectMaskTest`, `ArrowTest`, `EffectBracketTest`,
`internal/{DebuggerTest, EvalCaptureTowerTest, EvalTest, ImplicitsTest, NestedTest, StackTest}`,
jvm `{ArrowEffectBytecodeTest, PendingBytecodeTest, internal/DebuggerBytecodeTest, internal/EvalConcurrencyTest}`,
`outsidekyo/KernelTest`, `kyo/Test`. `ImplicitsTest` now duplicates main's lift rejections and the
`show` case that returned to `PendingTest` at main's positions; the copies to drop, if any, are the ones
in `ImplicitsTest`.
