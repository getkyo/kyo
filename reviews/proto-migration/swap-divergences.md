# Swap divergences: kyo-kernel sources against origin/main

Reference: origin/main `bd7f20b146`. Branch state after step 2 of the swap (the proto moved into
`kyo.kernel`, main's scaladoc restored on unchanged members and updated on changed ones, a code
comment at every divergence that remains). Each entry names the file, what main has, what this
branch has, and the ruling behind it where one exists. Tests are covered in the step 4 pass.

## Files with a counterpart on main

| file | divergence | ruling |
|---|---|---|
| `kyo/kernel.scala` | `export kernel.Arrow` added; the rest is main's file verbatim | the continuation type is public |
| `kyo/Kyo.scala` | every combinator's function parameter loses the `Safepoint ?=>` context and the `Safepoint` evidence; bodies are the same walks over this kernel's `Loop` | the evaluator polls the budget |
| `kyo/kernel/Pending.scala` | `A \| Pending[A, S]` for `A \| Kyo[A, S]`; combinators build Arrow and Defer nodes; `eval` and `flatten` through `Eval`; the multi-arity `handle` overloads are unchanged | representation |
| `kyo/kernel/ArrowEffect.scala` | `handle` is `handleCont` (clause receives the continuation as an Arrow, may introduce `S2`); the two, three and four effect `handle` overloads are gone; stateful `handleLoop` is `handleLoopState`; every handler has a `recover` overload; `handleContOperation` and the `With` variants are new; `Mask` lives here | D2, D5 |
| `kyo/kernel/ContextEffect.scala` | `handle(tag, value)` and `handle(tag, ifUndefined, ifDefined)` are `handleInheritable`; `handle` takes explicit `fork`, `join`, `done`, `release` strategies; the `Noninheritable` marker is gone | D4 |
| `kyo/kernel/Effect.scala` | `Effect.catching` gone; `bracket` with `Finalize` and `Cell`, the `defer` overloads and `deferInline` over nodes are new | D2 |
| `kyo/kernel/Isolate.scala` | same public surface; `Identity` gone; the stack-snapshot `Contextual` isolate and the `Forked` handler wrapper under `internal` are new | D4 |
| `kyo/kernel/Loop.scala` | `Outcome*` covariant in `O`; `Done` wraps a settled answer; `continue` answers as `Outcome < Any`; `repeat` and `indexed` test the body for `Pending`; `Continue*` report to the debugger | representation |
| `kyo/kernel/internal/Context.scala` | region-ordered bindings (`bind`, `get`, `unbind`) for main's opaque `Map[Tag[Any], AnyRef]` with the `NoninheritableFlag` and `inherit` | D4, S10 |
| `kyo/kernel/internal/CanLift.scala` | plain givens guarded by `NotGiven` plus a macro check for singletons, no `unsafe.bypass`; `LiftMacro` here carries only the issue-903 guidance | lift design |
| `kyo/kernel/internal/KyoInternal.scala` | `Kyo` is the supertype of `Pending` and `Arrow` with `frame`; main's suspension ADT (`KyoSuspend`, `KyoContinue`, `KyoDefer`, `Nested`) is the `Pending` node family in `PendingInternal` | representation |
| `kyo/kernel/internal/package.scala` | `maxStackDepth` fixed at 512 (main: `Platform.maxStackDepth`); `maxTraceFrames` 64 (main: 16); `IX`, `OX`, `EX` live in `Eval`; `short` and `site` helpers new | platform sweep pending |
| `kyo/kernel/internal/Implicits.scala` | the lift is a plain implicit gated by `CanLift` (main: `LiftMacro.liftMacro`); function liftings as on main | lift design |
| `internal/Safepoint.scala` | one shared file on main; `jvm-native` and `js-wasm` files here | platform split |

## Files without a counterpart

New: `kyo/kernel/Arrow.scala`; `internal/{Eval, Stack, Handler, PendingInternal, Nested, Debugger,
EffectTrace}.scala`; `internal/Report.scala` (jvm-native, js-wasm); jvm `kyo/kernel/debug/{ConsoleDebugger,
DebugSession}.scala`.

Removed: `internal/LiftMacro.scala` (into `CanLift`), `internal/Trace.scala` and the platform
`TracePool.scala` (replaced by `EffectTrace` and `Report`).

Kept from the branch, not on main's kernel: `kyo/Closed.scala` (main keeps it in kyo-core; `Effect.bracket`
throws it), `kyo/Test.scala` (test base `IsolateTest` extends), `kyo/TestVariant.scala` (main has it).

## Build

`build.sbt`, kyo-kernel block: `Test / unmanagedJars := Seq.empty` while the stack above the kernel is
red (the doctest driver jar is built from it); the javassist Test dependency for the bytecode pins; the
jmh scope with the cross-bench dependencies and the compile-bench compiler. The JOL dependency and the
demo fork settings are gone with `protodemo`.
