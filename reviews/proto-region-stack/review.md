# Live review: nested regions on a stack, and an eval's own budget

Two changes, in dependency order. Each is applied one edit at a time with the Edit tool, in the
sequence below, with the sentence beside each edit said as it goes in.

Worktree `kyo-root-impl`, four commits off `31a7b4bde9`, tip `7a7cd22ad8`.

## What this fixes

**One.** `ArrowEffect` "handles nested per recursion step in bounded stack" aborted the whole suite
with a `StackOverflowError` after 17 of 88 tests. The proto held its installed handlers in the Java
call stack, so nesting depth was the Java stack's depth: about 700 bytes per open region, and 1426
regions at 1 MB where the reference is flat past 400000. Iteration inside a region was already flat.

**Two.** Fixing that revealed a second defect it had been hiding: the three suites hang when run in
one JVM, pre-existing and proven so. An eval inherited whatever safepoint budget the thread had left,
and a spent budget is a fixed point. Fixing *that* exposed a third: a throw leaks a budget entry, so
an extent that recovers a few hundred times drains its own and reaches the same fixed point.

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

`pop` drops the entry without clearing its slots, so a stack that reached depth n holds up to n
entries' worth for the rest of that eval. Clearing is the alternative and costs four stores on the
path every region exit takes; neither has been measured, and the retention is bounded by peak depth.

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

The rebuild block and `reenter` are the baseline's code, relocated, but not byte-identical, and
`flags.md` names all five differences. The one worth your attention is a signature: `reenter`'s rows
narrow from `Arrow[P, AX, EX & S]` to `Arrow[P, AX, EX]` and its result from `D < (S & S2)` to
`D < S3`, because the handler is read at row `Any` so the region's `S` is no longer in scope. The new
result type is the more specific one, so it is asserted nowhere.

**6. The guard, replacing `run`: the extent guard, once for the eval.**

> The regions a throw unwinds are the ones the stack holds, each consulted with the state it holds
> there, and it is a loop rather than a recursion so that recovering costs no more stack than
> declining does.

The loop is the point. The first shape of this had the recovery resume by calling back into the
guard from inside its own catch, which cost a frame per *recovered* region: the dependency this
change exists to remove, reintroduced one level over. Two review lenses caught it independently.

Consequence, and the one behavioural change in the set: `recover` now reads the state the region has
reached rather than the one it was installed with. That follows your ruling that both `recover` and
`release` should see the current state. `release` already did, structurally, since a region only
becomes releasable by being reified into a node and the reification writes the live state into it.
`Handler.recover`'s scaladoc stated the old contract and changes with it. A second, smaller
behavioural change: a `recover` that fails is now the failure the regions outside it see, which is
what nesting the per-region tries used to do implicitly.

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
settled arm applies the deferral, and its continuation is the application that just deferred. The
reference kernel's eval already does exactly this; the proto had both operations and called neither.

**8. `Eval.apply`, the exit: `finally Safepoint.restore(slot, saved)`.**

> The caller gets back what it had, its part-spent depth and its armed bit included.

This has no pinning test, and the reason is in the code beside it rather than left to be inferred: a
first attempt passed with the fix reverted, so it pinned nothing and was deleted rather than kept for
the look of it. A nested eval's own exits return most of what it spent, so the enclosing eval survives
losing the depth, and the armed bit is unobservable while nothing in the proto arms. It stays because
discarding a caller's state is wrong whether or not this tree can see it, and because the reference
does the same at the same place.

**9. The same guard, one line: `Safepoint.reset(slot)` on catching.**

> A throw leaves every strict application between it and the guard without its matching exit, and the
> guard is where the true depth is known to be zero.

Saving at entry fixes the leak across evals; it does not fix it within one. An extent that recovers a
few hundred times drains its own budget and reaches the same fixed point. Measured flat from 200 to
12800 recoveries with the reset, livelocking past 800 without it.

## Evidence

Full detail in `evidence.md`. Summary:

| | before | after |
|---|---|---|
| `ArrowEffectTest` | aborted after 17 of 88 | **88 of 88** at the default stack |
| `PendingTest` | 63 of 63 | 63 of 63 |
| `EvalTest` | 51 of 54 | 51 of 54 |
| three suites in one JVM | hung | **205 tests, 202 passing** |
| `kyo-kernelJVM/test` | 1 suite aborted | **35 suites, 0 aborted, 1399 passing** |
| demo, 28 scenarios | recorded values | identical, 4221 / -9 / 991 included |
| clean batch build | green | green |
| 12800 throw/recover cycles | livelocks past 400 | flat, 0.89 us each |

Benchmarks: the full class on both legs back to back in one session, measured on the shipped tip,
20 of 20 rows. **No row regressed beyond the drift band or beyond its own error.** An earlier pair
measured a commit that is not what ships and is discarded rather than carried forward.

Adjudication: `flags.md`, 88 rows, every one with a verdict, rebuilt after `kernel-discipline`
blocked the first version and recording what that version got wrong. No verdict is `REMOVE`; the constructs
`rulings.md` names are absent from the diff rather than justified in it.

## What I want you to push on

- **The loop's return type.** Everything else follows from it, and if you do not accept `A < S` as
  the honest description, the rest of the shape is wrong too.
- **`recover` reading the working state.** It is the one behavioural change, it follows your ruling,
  and the demo values did not move, but it is a contract and you should say so out loud.
- **The four columns versus a typed entry.** I kept the columns and the storage-boundary cast rather
  than a typed entry object that would allocate per region. The ladder permits the cast; you may
  still not want it here.
- **The context an answered operation resumes with.** It is now the loop's current one, where the
  baseline used the install-time one. It matches what `ContextEffect`'s own documentation says, and it
  is unobservable while every `update` in the tree is identity, so nothing pins it.

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
