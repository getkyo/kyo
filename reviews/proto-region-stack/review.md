# Live review: nested regions on a stack, and an eval's own budget

Two changes, in dependency order. Each is applied one edit at a time with the Edit tool, in the
sequence below, with the sentence beside each edit said as it goes in.

Worktree `kyo-root-impl`, commits `a10624dfa4` and `ffc1819ecc` off `31a7b4bde9`.

## What this fixes

**One.** `ArrowEffect` "handles nested per recursion step in bounded stack" aborted the whole suite
with a `StackOverflowError` after 17 of 88 tests. The proto held its installed handlers in the Java
call stack, so nesting depth was the Java stack's depth: about 700 bytes per open region, and 1426
regions at 1 MB where the reference is flat past 400000. Iteration inside a region was already flat.

**Two.** Fixing that revealed a second defect it had been hiding: the three suites hang when run in
one JVM. Pre-existing, and proven so.

## The equation, in one paragraph

A region is already a value here. `Kyo.Handle` is the region installed around a computation, and the
foreign-crossing arm already turns an open one back into that value with
`Kyo.handle(k0.head(x, k0.tail), handler, st).chain(cont2)`. So N open regions are a right-nested
chain of `Handle` values, and a chain of values can live on the heap. **The stack is that chain,
unrolled.** Nothing about what a region means changes; only where the chain is kept.

Full derivation, including the mapping of every piece to an existing value and why `ctx` is a
memoization rather than a new piece, is in `derivation.md` beside this file.

## Edit sequence

### Change one: the region stack

**1. New file, `kyo/proto/kernel/internal/Stack.scala`.**

> The open regions, in four arrays and a size. Entries are strictly heterogeneous, one set of types
> per region, so the columns are erased and read back at the storage boundary, which is the cast the
> ladder names with this carrier.

`pop` drops the entry without clearing its slots, because the whole stack is garbage when the eval
ends and clearing would put four stores on the path every region exit takes.

**2. `Eval.apply`, the loop's signature.**

```scala
def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S
```

> The loop returns the eval's answer rather than the composition of its arguments, because once a
> region's continuation waits on the stack, "v with contA then contB applied" stops describing what
> the call produces.

This is the load-bearing line of the change. It is what makes entering a region a tail call with no
cast between it and the result, and it is why no carrier type appears anywhere below.

**3. The `Handle` arm: install and continue, replacing the nested `region` method.**

```scala
stack.push(kyo.handler, st0, ctx, kyo.cont.chain(contA.chain(contB)))
loop(kyo.value, Arrow.id, Arrow.id, bound)
```

> A region is installed rather than entered: what follows it waits on the stack, so its interior is
> evaluated by this same loop instead of by a nested one.

**4. The settled arm: complete the innermost region and pop.**

> `done` runs with the region still installed, so a throw in it reaches the same recover its interior
> would, which is where the old per-region `try` had it.

**5. The `Suspend` arm: after the registers absorb, ask the regions.**

> The innermost region answers if the tag is its own; if it is foreign, the region becomes part of
> the suspension's continuation through the existing rebuild and ends, and re-entering with what that
> produced asks the next region out the same question.

The rebuild block and `reenter` are the baseline's code, relocated. They read the handler and state
from the entry instead of from a closure, and `git diff -w` over those ranges shows only that and
the loop's renamed type parameters.

**6. `run` and `recovered`: the extent guard, once for the eval.**

> The regions a throw unwinds are the ones the stack holds, each consulted with the state it holds
> there, so a region that declines costs no frame and neither does one that recovers.

Consequence, and the one behavioural change in the set: `recover` now reads the state the region has
reached rather than the one it was installed with. That follows your ruling that both `recover` and
`release` should see the current state. `release` already did, structurally, since a region only
becomes releasable by being reified into a node and the reification writes the live state into it.

### Change two: an eval's own safepoint budget

**7. `Eval.apply`, entry and exit.**

```scala
val slot  = Safepoint.get()
val saved = Safepoint.save(slot)
...
try run(v, Context.empty)
finally Safepoint.restore(slot, saved)
```

> The depth guard bounds strict recursion within one eval, so the budget is the eval's and not
> whatever the thread had left.

Inheriting a spent budget is a fixed point rather than a slow path: every application defers, the
settled arm applies the deferral, and its continuation is the application that just deferred. Neither
side is wrong alone. The reference kernel's eval already does exactly this; the proto had both
operations and called neither. Its own derivation is in `reviews/proto-eval-budget/`.

## Evidence

Full detail in `evidence.md`. Summary:

| | before | after |
|---|---|---|
| `ArrowEffectTest` | aborted after 17 of 88 | **88 of 88** at the default stack |
| `PendingTest` | 63 of 63 | 63 of 63 |
| `EvalTest` | 51 of 54 | 51 of 54 |
| three suites in one JVM | hung | **205 tests, 202 passing** |
| `kyo-kernelJVM/test` | 1 suite aborted | **35 suites, 0 aborted, 1398 passing** |
| demo, 28 scenarios | recorded values | identical, 4221 / -9 / 991 included |
| clean batch build | green | green |

Benchmarks: the full class on both legs back to back, 20 of 20 rows. Nothing regressed. The one
delta outside its errors, `deepRecursionPaysRescuesOnly` at +5.4% on `-f 1`, does not reproduce at
`-f 3`, where it reads -0.7%. So moving the open regions from the Java stack to four heap arrays
costs nothing measurable, including on the region-heavy rows.

Adjudication: `flags.md`, 76 rows, every one with a verdict. No verdict is `REMOVE`; the constructs
`rulings.md` names are absent from the diff rather than justified in it.

## What I want you to push on

- **The loop's return type.** Everything else follows from it, and if you do not accept `A < S` as
  the honest description, the rest of the shape is wrong too.
- **`recover` reading the working state.** It is the one behavioural change, it follows your ruling,
  and the demo values did not move, but it is a contract and you should say so out loud.
- **The four columns versus a typed entry.** I kept the columns and the storage-boundary cast rather
  than a typed entry object that would allocate per region. The ladder permits the cast; you may
  still not want it here.

## Open, and not mine to decide

**The eval boundary returns an unanswered suspension** instead of rejecting it, which is the three
`EvalTest` failures and predates all of this. It carries two decisions: whether `Eval.apply` narrows
to returning a raw `A` as the reference does, and what the message says. The ported tests assert
"unhandled suspension"; the reference says "Unexpected pending effect".

## Deviation, recorded

The harness bracket refuses a leg whose suite is red, and the control's is red **by construction**,
since it aborts with the overflow this change fixes, back through `eabef556e0`. No green control
exists or can exist for this change. Narrowing the gate's task to the suites green on both legs was
available and rejected as weakening a gate for convenience, so the legs were run directly and the
bracket's A/A null and warmup guards did not run over them.
