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

`build.sbt`, kyo-kernel block: `Test / unmanagedJars := Seq.empty` while the stack above the kernel is
red (the doctest driver jar is built from it); the javassist Test dependency for the bytecode pins; the
jmh scope with the cross-bench dependencies and the compile-bench compiler. The JOL dependency and the
demo fork settings are gone with `protodemo`.

## Tests

To be regenerated after the test-suite minimization pass (agents T1 to T6, reports in `swap-hunks/T*.md`).
The mapping below is the pre-pass content comparison.

| suite (main leaves) | twin under the proto's names | ported | divergent by ruling |
|---|---|---|---|
| `KyoTest` (108), `KyoForeachCollTest` (57), `LoopTest` (84) | all | | |
| `PendingTest` (50) | all: map, flatMap, for-comprehension, flatten, unit, andThen, eval, the lift rejections (`ImplicitsTest`), `evalNow` (3), `handle` (4 plus the arities), the nested computations including the denied-budget cases, the Render case (`ImplicitsTest`) | | |
| `CanLiftTest` (11) | all, as "resolves for" and "rejects" cases | | |
| `ArrowEffectTest` (38) | single-effect handling, stack safety, `handleFirst` (5), the `catching` group as "recover, ported from catching", the `Nested` cases as "nested box", `handlePartial` as `Eval.partial` in `EvalTest`, the plain multi-shot case | delimited continuation: multi shot with another effect, multiple shifts over different effect sets, short circuiting; flow effect with dynamic tags: single poll, poll and emit, multiple flows | the two, three and four effect `handle` overloads (D2 family) |
| `ContextEffectTest` (11) | reads, layering, ifUndefined and ifDefined, nesting | three bindings: each read takes its own; binding order | the `Noninheritable` marker (D4) |
| `EffectTest` (8) | `defer` (simple, nested, order) | | `catching` (4 cases) and `defer with catching` (D2); "defer with a recovery inside" is the port over a recovering region |
| `IsolateTest` (28) | `derive`, `run`, `andThen`, `use`, `apply`, the variance groups, `nest`, the contextual isolate | | the `Isolate.internal.runDetached`, `Trace` and `Safepoint.Interceptor` cases (the proto's `Contextual` isolate has no such internals; their properties live under "Contextual" and "ported crossings"); "should propagate only non-isolated effects" (D4) |
| `ContextTest` (11) | empty, read, unbound, shadowing, different tags | | the `Map` API (`isEmpty`, `contains`, `getOrElse`, `set`) and `inherit` (D4) |

Main-only test files: `internal/TraceTest`, `internal/TracePoolTest`, jvm `internal/TracePoolConcurrencyTest`
(the trace pool is gone; `EffectTraceTest`, `EffectTracePhysicalTest` and `EffectTraceThreadingTest` cover the
replacement), jvm `BytecodeTest` (split into `PendingBytecodeTest`, `ArrowEffectBytecodeTest` and
`DebuggerBytecodeTest`).
