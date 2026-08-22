# Migrating the codebase to kyo-kernel2: what is left

State at `340adb262a`. 976 tests green on JVM, JS and Native compile. Counts are main-source call sites in
this tree, measured, not estimated.

---

## 1. What the kernel now has

Everything below landed and is covered by tests, so none of it is scope any more:

- the evaluator, its stack, regions (`handleCont` / `handleLoop` / `handleLoopState` and the three `*With`
  variants), parking and resumption (`Eval.partial`, `Kyo.Park`)
- failure handling: `Effect.catching`, `ArrowEffect.handleCatching`, and `EffectTrace` reconstruction
- resources: `Effect.bracket` as an unnamed binding whose release is told how the extent ended
  (`Result[Nothing, A]`: the value, the failure, or `Finalizer.Abandoned`)
- context effects: `ContextEffect` on `Kyo.Binding`, with `fork`, `join` and `release` per binding
- first-operation handling, as a pattern over `handleCont` rather than a `handleFirst` primitive

Two mechanisms the old kernel has are **replaced rather than ported**, so they are not scope either:

- **`Trace` / `TracePool`.** The old kernel keeps a per-computation ring buffer of frames, pooled, snapshotted
  by `Trace.saved()` and reinstalled by `Isolate.restoring`. `EffectTrace` records nothing during evaluation
  and reconstructs frames at failure time from the node graph and the live stack, so carrying a trace across a
  boundary needs no mechanism: the continuation already carries its arrows.
- **`internal/Context`.** A binding is a stack entry, so the map-threaded-through-continuations disappears.
  What `Context.inherit` did is now `Binding.fork` answering per binding.

---

## 2. What blocks the stack, in dependency order

### 2.1 `Isolate`, and the boundary that calls `fork` / `join`
FB explore how we'd implement this
**29 files across 11 modules.** kyo-core 63 refs, kyo-flow 17, kyo-reactive-streams 11, kyo-prelude 11,
kyo-ai 11, kyo-combinators 9, plus STM, Caliban, Actor, Offheap, Aeron.

The kernel side is half-built: `Binding.fork` and `Binding.join` exist and say what a child receives and what
the parent holds afterwards, which is exactly an isolate strategy (`Var.isolate.update` is
`join = (_, child) => child`, `merge(f)` is `join = f`, `discard` is `join = (parent, _) => parent`). What does
not exist is the code that *calls* them at a fork: nothing walks the binding entries, builds the child's
initial stack, or folds the child's values back.

Two open questions to settle when it lands, both raised and parked tonight:

- whether `fork` / `join` need to be effectful (`< S`). `Isolate.capture` and `restore` both are today, so
  probably yes, and the change is one line per member with no call sites to migrate.
- whether `Var` becomes a binding with a writable slot, which would make `Var.isolate`'s three strategies two
  functions and remove `Isolate` instances for `Var` and `Emit` entirely. This is the larger prize and the
  larger risk.

### 2.2 `handleLoop`'s clause reshape
FB The differences in API are by design but the ordering of params should follow kyo-kernel
**46 sites**: 39 kyo-prelude, 5 kyo-core, 1 kyo-http, 1 kyo-combinators. Every one passes the old
`(input, state, cont)` clause and calls `cont(...)` inside `Loop.continue`. The new clause is `(state, input)`
answering with a value, so each site drops the continuation and answers directly. Canonical example:
`kyo-prelude/shared/src/main/scala/kyo/Var.scala:147-159`.

Mechanical per site. The one shape that cannot survive the reshape is a clause that wraps the *resumed
computation* rather than answering it, and the only site doing that (`Choice.scala:102`) is on `handle`, not
`handleLoop`, so it lands on `handleCont` where the continuation is an arrow.

### 2.3 `handle` to `handleCont`

**9 sites**: `Stream.scala:585,607`, `Emit.scala:136,156`, `Check.scala:77,104`, `Choice.scala:99`,
`Batch.scala:134`, `EmitCombinators.scala:47`.

A rename plus a `done` clause. The old signature's separate `S2` for the clause is *not* a lost capability:
instantiate `S` as `S & S2` and the body conforms by contravariance, which the kernel's own tests already
exercise (`EvalTest.scala:284` handles `Ask` while its clause suspends `Say`). Four of the nine sites need
that (`Check.runAbort`, `Emit.runForeach`, `Stream.foreachChunk`, `EmitCombinators:47`), and may need explicit
type arguments where inference cannot split the row.

### 2.4 `handleFirst` sites

**15 sites** in 6 prelude files: `Poll.scala:170,214,219`, `Sink.scala:44,49,61,196,199`,
`Stream.scala:705,709,738`, `Pipe.scala:247,250`, `Emit.scala:202`, `Choice.scala:125`.

No kernel primitive is coming; the shape is `handleCont` with a currency that carries both outcomes, proven by
seven cases in `ArrowEffectTest`. Each site changes in three ways:

1. the region's currency becomes an ADT with a "standing" case holding the continuation
2. the clause becomes a **pure capture**; anything effectful moves after the region, because `handleCont`'s
   clause answers at the region's own currency and is therefore self-re-entrant. `Sink.zip` and `Poll.runEmit`
   raise their own tag inside the clause today, so they are rewrites, not substitutions
3. the handed-out continuation produces the widened currency, which internal uses absorb but `Poll.runFirst`
   and `Emit.scala:202` expose in their signatures

### 2.5 `accept`: declining an operation
FB can't about just handle and suspend again if it doesn't match?
**1 site, and it gates `Abort`.** `Abort.runWith` (`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:202`)
passes `accept = [C] => input => input.isPanic || ct.accepts(...)`, because every `Abort` raise carries the
same erased tag and only the *value* says which handler owns it.

No handler in kernel2 can decline. This is a dispatch change, not a handler kind: a declined operation takes
the same path a foreign tag takes, folding and propagating to the next matching handler outward. Two shapes
to choose between: an `accepts(input)` pre-check on the handler (a virtual call per dispatch), or a decline
sentinel from the clause (free on the happy path, but the dump has to be undone by re-pushing the
continuation).

Tag subtyping cannot replace it: a read's answer type comes from the operation for an `ArrowEffect`, so `find`
can widen safely, but Abort's routing depends on a runtime value.

### 2.5b What already fits, measured against kyo-core's actual calls

Grepping kyo-core's main sources for kernel APIs returns four files, and one of them is already satisfied:

- **`Scope.scala` (3 calls) fits as written.** `ContextEffect.suspendWith(Tag[Scope])(_.ensure(f))` and
  `ContextEffect.handle(Tag[Scope], finalizer, _ => finalizer)(v)` are exactly the surface that landed
  tonight, including the layered `handle` shape. Scope is the resource effect, so this is the first real
  consumer of `Kyo.Binding` outside the kernel's own tests.
- **`Sync.scala:115` fits with an adaptation.** It already calls the two-argument `Effect.bracket`, and the
  only delta is the outcome's type: it expects `Maybe[Error[Any]]` where ours is `Result[Nothing, B]`, which
  carries strictly more, since it distinguishes an abandoned extent from a failed one.
- **`Fiber.scala` (4 calls) does not**: `ContextEffect.runDetached` at `:173,749,788,896` plus a direct
  `import kyo.kernel.internal.Context`. Part of §2.1.
- **`IOTask.scala` (5 calls) does not**: see below.

### 2.6 Safepoint and `IOTask`
FB this is for later
The old interceptor machinery has **no remaining consumer**: zero references to `Interceptor`,
`Safepoint.ensure`, `immediate` or `propagating` anywhere outside `kyo-kernel` itself. `IOTask` already calls
`Safepoint.beginSlice(deadline)` / `endSlice()`, which exist in *neither* kernel, so kyo-core is already
written toward the slice model that `Eval.partial` implements.

Three concrete deltas:

- `Sync.ensure` (`kyo-core/.../Sync.scala:115`) calls `Effect.bracket(())((_, outcome) => ...)` where its `f`
  takes `Maybe[Error[Any]]`. Ours hands `Result[Nothing, B]`, which carries strictly more (it distinguishes
  abandonment from failure), so the site adapts rather than blocks.
- `IOTask.scala:185` calls `remainder.finalizeBracket(pollError())`, passing the error the fiber is failing
  with. Ours is `<.finalizeResources` and takes nothing, always reporting `Finalizer.Abandoned`. **Ready to
  do**: give it the outcome parameter, three lines, and the name should match whichever the caller keeps.
- `handlePartial` (`IOTask.scala:74`) is the parking handler. `Eval.partial` plus a clause that parks by
  returning is already exercised (`ArrowEffectTest.scala:805`), so this is likely a composition rather than a
  primitive, to be confirmed when IOTask is ported.

### 2.7 `dispatchFirst`

**2 sites**, `IOTask.scala:170,195`, for interrupt propagation over a stalled remainder. Written and reverted
tonight, deliberately: it has no consumer until IOTask lands, and its reach is an open question. The old
kernel could read a mapped suspension because a `map` did not wrap it; here it does, and reading through a
deferral means running user code, which `IOTask.scala:190` explicitly forbids. Deciding it needs the use that
needs it.

### 2.8 Cross-platform

`kyo-kernel2` cross-builds for JS, Native and Wasm with no platform-specific sources, and its `Safepoint` uses
`thread.threadId` (`Safepoint.scala:79`), which the old kernel documents as breaking Scala.js and Wasm linking
(`kyo-kernel/.../Safepoint.scala:32-36`). JVM and Native tests exist; JS and Wasm are compile-only today.

---

## 3. Test debt

452 cases in the old kernel's suite: **310 ported, 23 parked, 119 missing.** Most of the missing follow a
feature and arrive with it:

| what | cases | arrives with |
|---|---:|---|
| `ContextEffectTest` | 11 | portable now: the surface exists, 25 new cases already cover it |
| `IsolateTest` | 28 | §2.1 |
| `ContextTest` | 11 | never: `internal/Context` is replaced by bindings |
| `TracePoolTest` + concurrency | 9 | never: replaced by `EffectTrace` |
| `TraceTest` | 10 | never, except any case about failure enrichment |
| `SafepointTest` | 42 | §2.6, and most describe the interceptor protocol that is gone |
| `ArrowEffectTest` | 7 | **real gap, unrelated to any feature** |
| `PendingTest` | 1 | small |

**The one that is genuinely owed**: no kernel2 test instantiates a non-`Const` effect. All 38 effect
declarations in the new suite are `Const[X]` or `Id`. The old suite covered a non-`Const` effect, a
delimited-continuation effect whose operation type carries the continuation, and a variant `Flow` effect with
per-call-site computed tags. Worth porting on its own.

Kernel2 surface with no test at all: the `Map` receiver block of `Kyo` (~25 overloads, untested in the old
kernel too), `<.finalizeResources`, `Safepoint.arm`, `Loop.forever`, and `Stack.Pool` growth past its initial
capacity.

Also mine to fix: **43 occurrences of "drive"** in kernel2 test names and comments.

---

## 4. Suggested order

1. **`Isolate` and the fork boundary** (§2.1), because it is the largest and everything above the kernel waits
   on it, and because the `Var`-as-binding question inside it changes §2.2's shape if it goes the other way.
2. **`accept`** (§2.5), small and gates `Abort`, which gates most of prelude's tests.
3. **The mechanical site rewrites**: §2.3, then §2.2, then §2.4, in that order, since each is a superset of the
   previous one's difficulty.
4. **IOTask** (§2.6, §2.7), which is where the slice model, the outcome-carrying finalize, and the standing-
   operation inspection all get decided together by their only consumer.
5. **Test debt** (§3), with the non-`Const` coverage first since it is not downstream of anything.
