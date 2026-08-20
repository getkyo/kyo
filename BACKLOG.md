# kyo-kernel2 backlog

Working doc. Sections in priority order: what needs you, what is running, what I can pick up, what is deliberately
set aside, what is finished.

---

## 🔴 Blocked on the owner

### B1. Bracket and Park: implementation
FB this is not blocked if it's not readyt for review, it's executing
Design is written (`reviews/BRACKET-PARK-DESIGN.md`), held-out review is running. **Implementation needs your
explicit approval and I will not start without it.**

Two decisions the design cannot make for you:

1. **Is `Effect.bracket` `inline`?** The design ships it non-inline; the prior art was `inline`
   (`3a95636fa8:Effect.scala:56`). Not a free choice: an inline `bracket` mints `new Kyo.Bracket` at the call site,
   so `Kyo.Bracket` must be nameable from an expansion site outside package `kyo`. That is the same constraint that
   produced the unqualified `Arrow.Transform` imports, and it becomes a requirement on `KyoInternal.scala`.
2. **`finalizeResources` visibility.** Design ships `private[kyo]` per prior art; the original brief called it a
   public extension on the pending type.

One limitation the design reports rather than hides: a `handleCont` clause that receives the interior fold and
neither applies it nor throws leaks the resource. Inherent to a continuation delivered as a `Function1`. Pinned as a
specification, with `Scope` named as the correct home for the fix.

### B2. `EffectTrace` becomes `KyoException`

Blocker analysis running. The question that decides whether it is worth starting at all: **one mechanism or two?**
Throwables that are not `KyoException` (a user `RuntimeException`, an NPE) need the suppressed carrier regardless, so
the swap likely *adds* a fast path rather than removing a mechanism.

---

## 🟡 Executing

### E1. Compile-time benchmark, kyo-kernel vs kyo-kernel2

Full class, 8 warmup iterations, `-prof gc`. Produces the time and allocation tables. Nearly finished.

### E2. Bracket design review

Held-out. Checking whether the three node types are a real split or the rejected `Defer` reduction wearing a
subclass, and whether exactly-once holds on every path.

### E3. `EffectTrace` / `KyoException` blocker analysis

See B2.

### E4. Visibility reduction

`Kyo`, `Stack`, `Safepoint`, `Handler`, `Nested` narrowed to `private[kyo]`. `EffectTrace` stays public by your
decision, since it becomes `KyoException`.

Edits are in; the reference pass is not. Every qualified selection through a now-private object inside an inline body
has to become an unqualified import, the same fix as `Arrow.Transform`. Known sites: 15 `Safepoint.*` in
`Pending.scala`, 6 in `Arrow.scala`, 13 `Kyo.*` and 4 `Stack.*`/`Safepoint.*` in `Eval.scala`, 14 in
`ArrowEffect.scala`, 3 in `Effect.scala`, 1 `Nested.lift` in `Implicits.scala`.

`private[kernel]` is not reachable: `Arrow.scala` is `package kyo`, not `kyo.kernel`, and names `Kyo` and
`Safepoint` directly.

---

## 🟢 Ready for execution

### R1. Re-run the runtime benchmark board

**The board I gave you this morning is stale.** It predates the unqualify change, which touched `Pending.scala`,
`Eval.scala` and `ArrowEffect.scala`, all hot path. Same rows, `-f 2`, `-prof gc`.

### R2. `map` expansion tree dump

Static answer is in hand: `f`, `unsafeGet`, `self` and **`lift`** expand; `Effect.defer`, `Safepoint.*`, `Arrow.id`,
`next.head/tail` do not. What is left is size, on three lambda shapes: bare value, bare singleton (which reaches the
`CanLift` splice macro), already-pending. Tells R3 where to look.

### R3. The inline sweep, one experiment at four sites

`CanLift` givens · `Kyo.lift`/`unit` → `@static` · `inline self` on the extension · `evalNow`/`unsafeGet` binding
`self` once. Bytecode pins first, then runtime rows, then compile time.

`derivedSingleton` stays inline regardless: as a plain given its macro would expand against an abstract type and the
module lint would silently become a no-op.

### R4. Recursion benchmarks and the `Loop`-on-`Arrow.recursive` question

Six rows, `Arrow.recursive` vs `Loop` vs plain recursive `def`, pure and effectful, then the design question in the
same breath since the numbers are the argument. Allocation is the discriminator: `Loop.continue` mints a `Continue`
per iteration, `Arrow.recursive` mints one arrow total. Ends in a proposal, not a change: `Loop.continue`/`done` at
four state values is public surface.

---

## ⚪ Parked

| what | why |
|---|---|
| `Effect.catching` | Needs a stack entry the drive consults while unwinding. CPS let the old kernel wrap the continuation and guard everything downstream; here the drive owns a stack and a `try` in one arrow's `apply` guards building the next deferral, not running it. Same mechanism as Bracket's release-on-throw, so it rides on that. |
| `Eval.partial` | Lands with Bracket and Park. Consumers waiting: the parked group in `EvalTest`, two cases in `ArrowEffectTest`, `SafepointConcurrencyTest`. |
| `handleFirst`, `dispatchFirst`, `handleCatching`, `handlePartial` | Signatures recorded `private[kyo]` and commented. `dispatchFirst` and `handlePartial` want the IOTask integration design; `handleFirst` has no primitive to stand on; `handleCatching` wants the unwind mechanism. |
| `ContextEffect`, `Effect.detach` | Whole feature absent from this kernel. |
| Bench coverage audit | Mapping the 17 rows against real usage across kyo modules. |
| `Arrow.step` | Dropped for good. `head`/`tail` are on `Arrow` and say the same thing. |
| Deferred block's own frame in a trace | Guarding it puts a `try` region on the drive's hottest arm to describe a failure on a surface carrying no frame of its own. |

---

## ✅ Done

| what | evidence |
|---|---|
| Proto adopted as the kernel; test corpora merged from proto, previous kernel2, and kyo-kernel | **789 tests green**, clean batch build, JS and Native compile |
| `Loop` dispatched on the old node type | All 13 combinators detected pending with `case arrow: Arrow`, dead in this kernel. `Loop.apply` returned a suspended outcome as the result; `whileTrue` span forever. Found by a hang in a test that had never run. |
| `Effect.defer` by-name restored | Lost in the move; re-expressed over `Kyo.Defer` whose payload is a method |
| `EffectTrace` ported | Value position vs arrow position is what makes the six self-referential continuation slots terminate; eight attach sites, boundary splice |
| `map` and `Eval` did not compile from outside package `kyo` | Caught only by the compile-bench fixtures, the sole code outside `kyo`. Fixed by unqualified imports, **not** by widening visibility. |
| Compile-bench harness repaired | `fixtures-proto` pointed at a removed package. Now one shared corpus against both classpaths, with an override only where the surface genuinely differs. |
| Trivial surfaces restored | `handleLoopState` overload without `done`, `toString` on nodes and arrows, `Render` for the pending type, `ArrowEffectBytecodeTest` revived with measured sizes |
| Commented signatures for missing surface | `Effect.scala`, `ArrowEffect.scala` (marked `private[kyo]`), `Eval.scala` |

---

## Open questions carried forward

1. `suspensionBaseline` is red against the last proto board and **not accepted**. Same-session isolation attributed
   2.8 of 9.6 points to the EffectTrace wiring; the residual is unexplained and the comparison is cross-session,
   which cannot support a claim at that size. R1 re-measures.
2. `handleCont` compiles to 87 bytes against the old kernel's 33 for `handle`. Not the same expansion, but the size
   says the call site allocates twice, the region node and the handler it holds, where the old region was one node.
3. The concrete-class lift moved from 2 bytes to 5. This lift has no emission analysis to prove a final class admits
   no payload, so a concrete class pays one union `instanceof`.
