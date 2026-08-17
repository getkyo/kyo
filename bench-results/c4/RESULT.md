# C4: the mechanism is real, the location in the candidate is not

Candidate 8, and the only one of the ten whose deliverable is a failing test rather than a number. It
is in the list deliberately, to test whether the harness can handle such a candidate at all.

**Status: reproduced, fixed, suite green, cost measured. One decision open, below.**

## The cost, measured

Bracket `9685c9b445` against `2fc76b9cc6`, five legs, whole class, A/A null clean across all 15 rows.

| | row | control | variant | delta |
|---|---|---|---|---|
| 🔴 | `evalFixedOverhead` | 0.005853 ± 0.000317 | 0.007364 ± 0.000111 | **+26.2%** |
| ⚪ | every other row | | | flat |

That is the row that measures the fixed overhead of `Eval.apply`, which is the method the fix changes,
so it is the row that should move and the only one that did. **About 1.5 ns per top-level `eval`**:
a `Safepoint.get()` thread-local lookup, an array read, an array write, and a `finally`.

The legs are unusually clean for this campaign, three controls at 0.005853 / 0.005827 / 0.005775 and
two variants at 0.007364 / 0.007319, with no overlap. Nothing about this verdict is marginal.

Two caveats the harness attached and I am not overriding: the session resolves flat rows only to
±32.91% at worst (df 3), so "every other row flat" is a weak statement; and this is a two-sha
comparison, so no source-level mechanism is attributable from it, however obvious the mechanism looks.

## The decision this opens

1.5 ns per top-level `eval` buys the elimination of a permanent per-thread budget leak. That is a
real trade and it is yours, not mine.

There may be a cheaper shape, and it is measurable rather than arguable. The guard currently pays on
*every* eval, but the defect only exists on the exception path, so a `catch` that restores and
rethrows would pay nothing on the happy path:

```scala
try Nested.unnest[A](loop(v, armed = false, neverStop))
catch case ex: Throwable => Safepoint.reset(Safepoint.get()); throw ex
```

**Stated as a hypothesis, not a recommendation**, because it is not equivalent: `reset` installs a
fresh budget rather than the outer drive's exact value, and that differs whenever user code catches a
throw from a nested `eval` and continues the outer drive. Whether that is reachable, and whether the
saving is real, are two separate measurements. The honest way to settle it is a chain of three shas
(pristine, `save`/`finally`, `catch`/`reset`), since a pair would attribute nothing.

**Default if you say nothing:** keep the `save`/`finally` version. It is exactly correct, its cost is
confined to one row that measures nothing but call overhead, and correctness at 1.5 ns is the trade
this project's rules would take by default.

The reproduction failed on pristine sources for exactly the right reason, which is the only kind of
red that counts:

    a fresh budget is 512 deep
    fresh budget 512, budget after 50 throws through a root eval 462, lost 50

Fifty throws, fifty depth. Linear, one per throw, and nothing ever gives it back. After the fix the
same test reports `lost 0`, and the whole proto suite is 128 tests green.

## What C4 claims

> `Safepoint.exit` is unguarded, so every exception unwinding through a delivery leaks budget
> permanently.

## What the delivery arms actually look like

Eight sites in `Pending.scala` and `ArrowEffect.scala`, all this shape:

```scala
if !Safepoint.enter(slot) then Arrow.Bind(v, arrow.chain(next))
else
    val out = step.head(f(res), step.tail)   // arbitrary user code
    Safepoint.exit(slot)
    out
```

`enter` decrements the depth, `exit` increments it back, and a throw out of `step.head` skips the
`exit`. So far the candidate is right.

## Why guarding those eight sites is the wrong fix

It is not an oversight that they are unguarded, and the owner confirmed it is by design. A
`try`/`finally` at each of the eight would put an exception handler on the hottest path in the
kernel, at the one place per suspension that every delivery runs through. The design instead puts a
single guard at the evaluation boundary, and `Eval.partial` has exactly that:

```scala
val saved = Safepoint.save(slot)
Safepoint.arm(slot)
try loop(v.asInstanceOf[A < Any], armed = true, ...)
finally Safepoint.restore(slot, saved)
```

One handler, at the boundary, restoring unconditionally on any unwind. Every skipped `exit` inside
that drive is made whole when the throw passes through it. That is the correct shape and it is
already there.

## Where the hole is

`Eval.partial` is the *partial* path. The root entry has no such guard:

```scala
def apply[A](v: A < Any): A =
    Nested.unnest[A](loop(v, armed = false, neverStop))
```

No `save`, no `try`, no `finally`, no `reset`. Every top-level `.eval` goes through this, so a throw
that escapes the root unwinds past every skipped `Safepoint.exit` with nothing restoring the depth.

The slot is per thread (`local.set(Integer.valueOf(slot))`), so the loss is not scoped to the
computation that threw. It persists on that thread and is paid by every later, unrelated computation
scheduled onto it, which is the permanent accumulation C4 describes, reachable through the root entry
and not through the partial one.

The consequence is not a wrong answer. `enter` returning false is the ordinary budget-exhausted path
and simply parks, allocating an `Arrow.Bind` instead of taking the fast path. So the symptom is a
thread that gets progressively slower and allocates progressively more after throws, with no
correctness failure to point at, which is the kind of thing that is found years late.

## The fix

One boundary guard at `Eval.apply`, matching the one `Eval.partial` already carries:

```scala
val slot  = Safepoint.get()
val saved = Safepoint.save(slot)
try Nested.unnest[A](loop(v, armed = false, neverStop))
finally Safepoint.restore(slot, saved)
```

`arm` is not wanted at the root, since `armed = false` there. `save` rather than a bare read, because
`save` resets to a fresh budget: a nested eval must get its own rather than inherit a nearly-drained
one and trampoline immediately for no reason.

**Why one guard is sufficient, which is the part worth keeping.** `Safepoint.exit` is unprotected in
all eight delivery arms and does not need protecting. Every normal path balances its pairs, *including
a drained budget*: the arm that parks never completed an `enter`, and every frame that did runs its
`exit` as the parked value propagates back up. So the only way to skip an `exit` is an exception, and
all eight catch sites in `Eval.scala` are `EffectTrace.attach(...); throw ex`, so no exception is ever
absorbed mid-drive. Every one reaches a boundary. There are exactly two boundaries, `partial` and
`apply`, and only one of them was guarded.

Ordinary kyo failure does not reach this at all: `Abort` is a typed effect that suspends and
short-circuits through the normal drive path with balanced pairs, never unwinding the JVM stack. The
leak needs a genuine `Throwable`, which is why it survived this long with no visible symptom.

Owner's direction, verbatim: *"eval should reset it at the root of the execution if it doesn't."*

## Two wrong turns, recorded because they were both plausible

**`peek` instead of `save`.** The first fix read the depth without resetting and restored it after, on
the reasoning that a nested eval inheriting a fresh budget could let the real stack reach 512 + 512.
Wrong: a nested eval that inherits a nearly-drained budget trampolines immediately, allocating a
`Bind` per step for an unrelated computation. The bound that matters is per drive. The owner's
question, *"I don't see where you reset the depth at all"*, is what surfaced it, and the old kernel's
own commented-out tests carry the correct `save` / `reset` / `restore` shape.

**A reset on every trampoline iteration.** Argued next, on the reasoning that each `cur = ...` in the
drive loop is a point where the JVM stack has unfolded, so the counter should be re-anchored to ground
truth there. It would be an unconditional write to a static array on the kernel's hottest loop, and it
buys nothing: the normal path is already balanced, so there is no drift for it to correct. Retracted
after the owner asked whether the exception path is the only one that needs it. It is.

## What this says about the harness, which is why C4 is in the list

The harness has **no path for this candidate at all**. Every shape it models ends in a `Run`, a
`Comparison` and a `Verdict` over benchmark rows. A candidate whose deliverable is a failing test
produces none of those, and `bench compare` has nothing to compare.

Two honest observations rather than one:

1. The diagnosis above came from reading source, and the harness contributed nothing to it. That is
   allowed here only because the deliverable is a test, and the test has not been written yet.
2. The *symptom* is measurable and the harness can express it exactly: a leaked depth forces parks,
   parks allocate `Arrow.Bind`, and `gc.alloc.rate.norm` is exact and per-operation. A row that throws
   N times and then measures allocation would show it. So the harness could carry the confirmation
   even though it cannot carry the diagnosis.

## Next step, not yet taken

Write the reproduction in the throwaway worktree: measure the budget, throw N times through a
delivery from a root `eval`, measure again, assert it is unchanged. It must fail for the right reason
before any edit.
