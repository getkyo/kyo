# kyo-kernel2 backlog

Working doc. Sections in priority order: what needs you, what is running, what I can pick up, what is deliberately
set aside, what is finished.

---

## 🔴 Blocked on the owner

### B1. Bracket and Park: review 2 came back REWORK

`reviews/BRACKET-PARK-REVIEW-2.md`. It closes six of the first review's seven blocking findings, including the
minting rule, and confirms the value half of the subclass split is real and load-bearing. Two findings are open,
both of the shape "release never runs", neither "release runs twice":

1. **The arrow half of the split does not hold.** Once a `Finalizer` is a stack entry it is an `Arrow` like any
   other, and `Stack.dump(pos)`'s `wrap` guard merges runs of entries into one chain-valued slot. After a region
   fold or a park-and-resume the finalizer can sit inside an `Arrow.Chain`, where the new `Barrier` boundary check
   does not see it. That is the first review's B3 in a new location, and the section that would have caught it
   asserts a round-trip property that is false and pins it with a test that passes anyway.
2. **A park with a consumed acquire owes its release.** The design answers "what does a park owe when the bracket
   has not been consumed" with "nothing", which is right when `acquire` has not run and wrong when it has and its
   trailing continuations are still on the stack. Its own test asserts the leak as correct, contradicting
   `Effect.bracket`'s scaladoc in the same document.

It also flags that the design puts two `private[kernel]` symbols inside `Eval.apply`, which is `inline`. That one
is now settled by evidence rather than by the rule: see D2 below for what an inline body may and may not name.

**Nothing here is implemented, per your standing instruction.** What I need from you is whether to send the design
back for a third pass, or park Bracket and move on.

### B2. `Loop.repeat` runs a settled body n+1 times, in kyo-kernel too

Found by the new expansion-site guard. `repeat` carried the previous iteration's value into the bound check, so it
evaluated `run` once more than `n`. For a deferred body the extra evaluation only builds a node that is then
discarded unexecuted, which is why the existing test never saw it: it uses `defer`. For a settled body the
evaluation *is* the execution, so `Loop.repeat(3)` ran the body four times.

Fixed in kernel2, with a settled-body case added to `LoopTest`. **`kyo-kernel` has the identical shape**
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Loop.scala:568-583`: `loop(0)(())`, and `loop(i + 1)(run)` on the
settled arm), so the bug is there too and inherited rather than introduced. I have not touched that module: it is
the one being replaced and it has its own suite. Say the word and it is a one-line fix plus a test.

---

## 🟡 Executing

### E1. P1, the inline sweep, unparked per your note

Your directive: optimize compilation time without regressing runtime performance. Two things landed under it so far
and both were correctness fixes as much as performance ones.

**`evalNow` built its receiver twice.** `self` is `inline` on the extension, so every occurrence re-expands the
receiver expression, and `evalNow` had one per branch. `v.map(f).evalNow` therefore built the map twice and ran `f`
twice on the settled path. Bound once now, with a reproducing test in `PendingTest`.

**The measurement harness no longer needs sbt.** `dotty.tools.dotc.Main` runs directly off the coursier cache, so
a fixture can be compiled and its tree dumped while a suite is running. Note for anyone repeating it: at 3.8.4
`scala3-library_3` is an empty forwarder jar and the classes are in `scala-library` 3.8.4.

Still open under this heading: `Kyo.lift` and `Kyo.unit` to `@static`, dropping `inline` from `self`, and the
per-shape expansion sizes (was R2, folded in here since it is the measurement this work is keyed to).

---

## 🟢 Ready for execution

### R1. Re-run the runtime benchmark board

Stale, and now more so: it predates the unqualify change, the visibility work, and the `evalNow` fix. Same rows,
`-f 2`, `-prof gc`.

### R3. Recursion benchmarks and the `Loop`-on-`Arrow.recursive` question

Six rows, `Arrow.recursive` vs `Loop` vs plain recursive `def`, pure and effectful. Allocation is the
discriminator: `Loop.continue` mints a `Continue` per iteration, `Arrow.recursive` mints one arrow total. Ends in a
proposal, not a change, since `Loop.continue`/`done` at four state values is public surface.

---

## ⚪ Parked

### P2. `EffectTrace` becomes `KyoException`

Parked at your instruction. `EffectTrace` is an `Exception` but not the one that gets thrown: today it is a
carrier the drive attaches via `addSuppressed`, rewriting the crossing exception's own trace with the effect
frames. Your proposal is to wrap instead, with the original as `cause`; `KyoException` already takes
`cause: String | Throwable`, so it needs no new surface, and it is genuinely one mechanism rather than two.

The cost to weigh when this restarts: wrapping changes what user code catches at every drive boundary.

```scala
try Eval(computation)
catch case e: MyDomainException => recover(e)   // matches today, would not after wrapping
```

The case to check hardest is `Abort[E]` with a user exception type as `E`. Other blockers identified but not
verified: `KyoException` extends `NoStackTrace` and `splice` deliberately skips exactly that class; the
`getMessage` change reaches 32 subclasses across 20 modules; `KyoException` lives in kyo-data, below the kernel
that would fill it; a fatal error must not be swallowed into a wrapper, which `EffectTraceTest` pins; and an
exception crossing three nested drives must not be wrapped three times. Notes in
`reviews/EFFECTTRACE-KYOEXCEPTION.md`.

### P3. `Effect.catching`

Needs a stack entry the drive consults while unwinding. CPS let the old kernel wrap the continuation and thereby
guard everything downstream, because in CPS the continuation *is* the rest of the computation. Here the drive owns
a stack and the rest is spread across its entries, so a `try` inside one arrow's `apply` guards building the next
deferral rather than running it. Two workarounds were traced and both fail. Same mechanism as Bracket's
release-on-throw, so it rides on that work. Signature recorded commented in `Effect.scala`.

It also owes a tracing contract: `splice` currently runs only at the drive boundary, which assumes an exception is
observed only there. A catching handler is a second observation point, so it must attach and splice before calling
`f`.

### P4. `Eval.partial`

Lands with Bracket and Park, since a park is what a partial drive hands back. Consumers already written and
waiting: the commented partial-evaluation group in `EvalTest`, two cases in `ArrowEffectTest`, and the
stop-observability case in `SafepointConcurrencyTest`.

### P5. `handleFirst`, `dispatchFirst`, `handleCatching`, `handlePartial`

Signatures recorded commented in `ArrowEffect.scala`, marked `private[kyo]`. `handleFirst` has no primitive to
stand on. `dispatchFirst` and `handlePartial` wait on the IOTask integration design, and `handlePartial`
additionally needs partial evaluation. `handleCatching` wants the same unwind mechanism as P3.

### P6. `ContextEffect` and `Effect.detach`

A whole feature absent from this kernel, not a gap in an existing one. Parked test corpora reference it in
`EffectTest` and `PendingTest`, kept commented against the replacement design.

### P7. Bench coverage audit

Mapping the 17 benchmark rows to the computation shapes they exercise, surveying what the modules above actually
build on the kernel, and producing the gap list. Matters because every performance decision in this campaign is
keyed to those 17 rows.

### P8. The deferred block's own frame in a trace

A throw from the body of `Effect.defer` happens while the drive reads the node's payload, the one path into user
code the eight attach sites do not cover. Guarding it would put a `try` on the hottest arm of the drive to describe
a failure on a surface that carries no frame of its own. The exception propagates correctly; it simply arrives
without effect frames.

### P9. `Arrow.step`

Dropped for good, not deferred. `head` and `tail` are on `Arrow` itself and say the same thing.

---

## ✅ Done

| what | evidence |
|---|---|
| Proto adopted as the kernel; test corpora merged from proto, previous kernel2, and kyo-kernel | **789 tests green**, clean batch build, JS and Native compile |
| `Loop` dispatched on the old node type | All 13 combinators detected pending with `case arrow: Arrow`, dead in this kernel. Found by a hang in a test that had never run. |
| `Effect.defer` by-name restored | Lost in the move; re-expressed over `Kyo.Defer` whose payload is a method |
| `EffectTrace` ported | Value position vs arrow position is what makes the six self-referential continuation slots terminate; eight attach sites, boundary splice |
| `map` and `Eval` did not compile from outside package `kyo` | Caught only by the compile-bench fixtures. Fixed by unqualified imports, **not** by widening visibility. |
| Compile-bench harness repaired | One shared corpus against both classpaths, with an override only where the surface genuinely differs |
| Trivial surfaces restored | `handleLoopState` overload without `done`, `toString` on nodes and arrows, `Render` for the pending type, `ArrowEffectBytecodeTest` revived |
| Compile-time board against kyo-kernel | 13 fixtures, both kernels, 8 warmup, `-prof gc`. kernel2 faster on 6 of 13, materially on 4. Raw JSON in `bench-results/compile-0820/` |
| D2. Visibility reduction, with the rule the compiler actually enforces | below |
| The expansion-site guard | below |
| `Loop.repeat` off-by-one on a settled body | B2 above; fixed in kernel2 with a settled-body test |

### D2. Visibility reduction, and the rule that came out of it

Narrowing `Kyo`, `Stack`, `Safepoint`, `Handler` and `Nested` to `private[kyo]` broke the build in two ways, and
both are worth keeping written down because neither is guessable from the source.

**A `private[kyo]` top-level object in `kyo.kernel.internal` that an inline body in another package names** makes
dotty emit an accessor whose receiver is the *package*: `inline$Safepoint$i1(kyo.kernel.internal)`. The call site
loads it as `getstatic kyo/kernel/internal.MODULE$`, and no such class exists, so every drive died with
`NoClassDefFoundError: kyo/kernel/internal` across 13 suites. Same-package references are unaffected, which is why
`Eval`'s own accessors are nullary and work.

**A `private[kyo]` member gets a well formed accessor, but the accessor is a second call the expansion pays for.**
Narrowing `Nested.lift` and the `Safepoint` depth guard took the value lift from 5 bytes to 20.
`PendingBytecodeTest` caught it, which is what those pins are for.

So the rule, now stated in the source at `Safepoint.scala` and referenced from `Handler.scala` and
`KyoInternal.scala`: **what an inline body reaches stays public; the narrowing lives on everything else.** What
that leaves:

- `private[kyo]`: `class Kyo`, `Kyo.Defer`, `Kyo.Suspend`, `Kyo.Handle`, `class Handler`, `Handler.HandlerCont`,
  `Handler.HandlerLoop`, `Handler.HandlerLoopState`, `class Nested`, `class Safepoint`, `Stack`, and Safepoint's
  `reset`, `arm`, `stop`, `consumeStopped`, `period`, `slotCount`, `object State`.
- public: the objects `Kyo`, `Handler`, `Safepoint`, `Nested`, plus `Nested.lift` and Safepoint's `get`, `enter`,
  `exit`, `save`, `restore` and its two opaque types.

No node type and no handler type a user could hold is nameable outside `kyo`, which is the property that was
actually wanted. `private[kernel]` remains unreachable for `Kyo` and `Safepoint`: `Arrow.scala` is `package kyo`
and names both directly.

One cost surfaced and it is a signal rather than a nuisance. The narrowing pushed `KyoTest`'s
`assert(widen(TypeMap(1, true)).eval.get[Boolean])` past the JVM's 64KB string-constant limit, because `eval`
expands the whole drive inline and scalatest renders its argument into a constant. Binding the value before the
assert fixes it and is the better test regardless, but the underlying fact stands: the drive expansion is large
enough that adding one accessor indirection to it crosses a hard JVM limit. That belongs to E1.

### The expansion-site guard

`outsidekyo/PendingExpansionSiteTest` exercises the whole public inline surface from outside package `kyo` and
asserts on the results: 74 cases covering the pending combinators, `handle` at all ten arities, both arrow
constructors, all nine `ArrowEffect` entry points, all 23 `Loop` entry points, the lifts including the pure
function conversions at every arity, and the 18 collection combinators.

Both halves of this failure mode need it. Compiling proves the names resolve at an expansion site; running proves
the accessor the compiler emitted is well formed. Until now the only code outside package `kyo` was the
compile-bench fixtures, which is why both failures above reached a full suite run before anything noticed.

Two things it turned up immediately beyond what it was written for: `kyo.discard` is `private[kyo]` and so not
available to user code, and `Loop.repeat` was miscounting (B2).

---

## Open questions carried forward

1. `suspensionBaseline` is red against the last proto board and **not accepted**. Same-session isolation attributed
   2.8 of 9.6 points to the EffectTrace wiring; the residual is unexplained and the comparison is cross-session.
   R1 re-measures.
2. `handleCont` compiles to 87 bytes against the old kernel's 33 for `handle`. Not the same expansion, but the size
   says the call site allocates twice, the region node and the handler it holds, where the old region was one node.
3. `HandleSites` compiles 1.49x slower on kernel2 and allocates 1.26x more. It is the one fixture with a per-kernel
   override, so the delta carries a source difference. Together with the 87 bytes, two signals point at region
   construction being more expensive.
4. The concrete-class lift moved from 2 bytes to 5. This lift has no emission analysis to prove a final class
   admits no payload, so a concrete class pays one union `instanceof`.
