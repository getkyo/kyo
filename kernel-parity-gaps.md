# Kernel parity: files and APIs, old kernel vs kernel2

Method: `origin/main`'s `kyo-kernel` main sources against the worktree's
`kyo-kernel2`, member-level extraction of public and `private[kyo]`
declarations per file, and downstream consumption measured on
`origin/main`'s kyo-prelude, kyo-core, and kyo-combinators main sources
(the modules the kernel swap must eventually carry).

## 1. File-level cross

| old kernel file | kernel2 | status |
|---|---|---|
| `kyo/Kyo.scala` | same name | ported, name parity IDENTICAL (only the `Safepoint` using-clause dropped) |
| `kyo/kernel.scala` | same name | ported minus `bug.failTag` extension (kernel-internal, no downstream use) |
| `kernel/Pending.scala` | same name | ported; lift implicits moved out (see organization) |
| `kernel/ArrowEffect.scala` | same name | ported with API gaps (section 3) |
| `kernel/ContextEffect.scala` | same name | parity; now `extends ArrowEffect[Const[Unit], Const[A]]` |
| `kernel/Effect.scala` | same name | parity except the `defer`/`deferInline` pair collapsed to one inline `defer` |
| `kernel/Loop.scala` | same name | member parity; deliberate `Outcome < S` return-type change |
| `kernel/Isolate.scala` | **MISSING** | largest gap, 19 downstream files |
| `internal/KyoInternal.scala` | same name | same role, new node set (Suspend/Defer/Handled/HandledState, Nested/Boxed) |
| `internal/Safepoint.scala` | same name | same name, different machine; old downstream surface not covered (section 3) |
| `internal/LiftMacro.scala` | same name | ported and extended (single-macro lift) |
| `internal/CanLift.scala` | deleted | deliberate: LiftMacro subsumes it, protections pinned by tests |
| `internal/Context.scala` | **MISSING** | superseded by the Handlers stack; capabilities needed again for Isolate |
| `internal/Trace.scala` | **MISSING** | trace machinery dropped; product decision needed |
| `{jvm,native,js-wasm}/internal/TracePool.scala` | **MISSING** | with Trace |
| `internal/package.scala` | **MISSING** | old contents (maxStackDepth/maxTraceFrames, IX/OX/EX aliases) obsolete in kernel2; nothing to port |

kernel2 files with no old counterpart:

| kernel2 file | disposition |
|---|---|
| `kyo/Arrow.scala` | new public architecture piece (approved move to package kyo) |
| `kernel/Implicits.scala` | lift machinery that old kernel kept inside `object <` in Pending.scala (see organization) |
| `internal/Eval.scala` | new: the evaluator (old kernel evaluated inside per-handle loops) |
| `internal/Handler.scala` | new: handler kinds |
| `internal/Handlers.scala` | new: the region stack |

## 2. Organization drift (target: old kernel names and layout)

1. **`kernel/Implicits.scala`**: the old kernel kept every lift implicit
   inside `object <` in Pending.scala. Folding the `Implicits` trait and
   its companion back into Pending.scala restores the old organization;
   the `Implicits.liftInternal` escape hatch works from a companion nested
   there as well. Candidate to fold; user call.
2. `internal/Eval.scala`, `internal/Handler.scala`, `internal/Handlers.scala`
   have no old-kernel analog to mirror; names are consistent with the
   internal package style.
3. Stray empty directory `kyo-kernel2/kyo-kernel2/` (untracked, no files):
   remove.

## 3. API gaps inside same-named files, ranked by downstream impact

### ArrowEffect

| old API | downstream consumers (main sources) | kernel2 today |
|---|---|---|
| `handleFirst` | **7 files**: IOTask, Choice, Emit, Pipe, Poll, Sink, Stream | missing |
| `dispatchFirst` (`private[kyo]`) | IOTask | missing |
| `handleCatching` (`private[kyo]`) | Abort | missing as such; `Effect.catching` + guard covers part of the role, mapping needed |
| `handlePartial` (`private[kyo]`, two tags) | IOTask | public `handlePartial` with ONE tag; two-effect form missing |
| multi-effect `handle` (2/3/4 tags) | none found | missing; no downstream main-source callers, lowest priority |

kernel2 additions with no old counterpart (additive, fine): `handleWith`,
`handleLoopWith` (plain and stateful).

### Isolate (whole file)

Missing entirely: `Isolate[Remove, Keep, Restore]` with
capture/isolate/restore/nest/run/use/andThen, `Isolate.derive`, the
identity given, and `Isolate.internal.{runDetached, restoring}`.
Consumers: Var, Emit, Memo, Check (prelude); Async, Fiber, Clock, KyoApp,
Stat, StreamCoreExtensions, IOTask (core). The old implementation is
built on `internal/Context.scala` (capture and re-install of the context
map across boundaries); kernel2 has no Context, so the port is a redesign
over the Handlers stack, not a copy. This is the largest single work item
between kernel2 and a prelude/core port.

### Safepoint (integration surface, not same-shape API)

The old Safepoint exposed a downstream integration surface the new one
deliberately replaced: `Interceptor` (used by Fiber, IOPromise, and
Debug), `Safepoint.ensure` (Sync), `Safepoint.immediate` (no main-source
uses). kernel2's equivalents are the slot/budget machine with
`stop`/`consumeStopped` preemption. Fiber and IOPromise integration and
`Sync.ensure` need a mapping design at port time (task #9 already tracks
the Sync.ensure tests). Debug additionally depends on Trace (below).

### Trace / TracePool

Dropped in kernel2 (performance). Old consumers of the capability: frame
traces in failures, fiber traces, and `Debug` in prelude (via
Interceptor). No kernel2 replacement exists; this needs an explicit
product decision (ship without traces, or design a cheaper trace hook)
before the prelude/core port reaches Debug and fiber tracing.

### Effect

Old `defer` (non-inline) and `deferInline` collapsed into one inline
`defer` (`private[kyo]`). Sync.scala uses both old forms; at port time
the non-inline call sites map to the inline one (or a non-inline wrapper
if bytecode size at Sync call sites matters). Low risk.

### Pending / lift

- `unsafeGet` (`private[kyo]`) removed: no downstream consumers;
  `evalNow` and internal unnest cover the role. Non-gap.
- `lift[A: CanLift]`, `liftAnyVal`, `liftUnit` consolidated into the
  single macro-backed `lift` plus `abortCastUnit` and
  `liftPureFunction1-6` in Implicits; protections pinned by tests.
  Surface parity, stronger implementation.

### Loop

Member parity. Deliberate change: `continue`/`done` now return
`Outcome[..] < S` instead of bare `Outcome`, and the `suspended` carriers
exist per arity. Downstream handler code will feel this at port time
(source-level adjustment, not a missing capability). Open TODO at
Loop.scala:181 tracked in task #58.

### Kyo collection utilities

Name-for-name identical; every signature dropped the `Safepoint`
using-parameter. External callers passing it explicitly (rare to none)
would adjust.

## 4. Platform coverage note

kernel2 declares JS, JVM, Native, and Wasm targets with zero
platform-specific sources; the campaign has only ever compiled and run
JVM. The shared Safepoint uses `AtomicReferenceArray`,
`Thread.currentThread`, and `Thread.threadId()`; whether all four
platforms compile and link is unverified. Not an API gap, but it gates
any claim of platform parity with the old kernel (which shipped per-
platform TracePools and compiled everywhere).

## 5. Recommended order

1. **Isolate redesign** over Handlers (unblocks 19 downstream files; also
   forces the Context-capability answer).
2. **ArrowEffect.handleFirst** (+ `dispatchFirst`): 7 downstream files,
   mechanical addition to the existing handler-kind family.
3. **Abort mapping**: `handleCatching` role onto `Effect.catching`/guard,
   and the two-tag `handlePartial` for IOTask.
4. **Safepoint integration design** for Fiber/IOPromise/Sync.ensure
   (with task #9).
5. **Trace decision** (ship without, or new hook) before Debug and fiber
   traces port.
6. Organization: fold Implicits into Pending.scala if strict old layout
   is wanted; remove the stray empty directory.
7. Cross-platform compile check (JS/Native/Wasm) of kernel2 as-is.

Multi-effect handle overloads (2/3/4 tags) can wait for a demonstrated
consumer.
