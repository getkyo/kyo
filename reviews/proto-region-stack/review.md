# Live review: nested regions on a stack, and an eval's own budget

Two changes, in dependency order. Each is applied one edit at a time with the Edit tool, in the
sequence below, with the sentence beside each edit said as it goes in.

Worktree `kyo-root-impl`, five commits off `31a7b4bde9`, tip `d197133298`.

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

The loop is the point. The first shape had the recovery resume by calling back into the guard from
inside its own catch, which cost a frame per *recovered* region: the dependency this change exists to
remove, reintroduced one level over. Two review lenses caught it independently.

This edit deletes `run` and `recovered`, so the budget's save and restore, which lived on `run`,
move with it; edits 7 and 8 place them.

Two behavioural consequences, both pinned:

- `recover` reads the state the region has reached, not the one it was installed with, per your
  ruling that it and `release` should both see the current state.
- an answered operation resumes with the loop's **current** context, where the baseline used the
  install-time one. `ContextEffect` says a read rebinds the value "for the rest of that region's
  extent", and an answered operation is inside that extent.

**7. `Eval.apply`, entry and exit: the eval's own budget.**

```scala
val slot  = Safepoint.get()
val saved = Safepoint.save(slot)
...
try  <the guard loop from edit 6>
finally Safepoint.restore(slot, saved)
```

> The depth guard bounds strict recursion within one eval, so the budget is the eval's and not
> whatever the thread had left, and the caller gets back what it had.

Inheriting a spent budget is a fixed point, not a slow path: every application defers, the settled arm
applies the deferral, and its continuation is the application that just deferred. The reference
kernel's eval does exactly this; the proto had both operations and called neither.

The `restore` has no pinning test, and that is stated in the code beside it rather than left to be
inferred. A first attempt passed with the fix reverted, so it pinned nothing and was deleted.

**8. The same guard, one line: `Safepoint.reset(slot)` on catching.**

> A throw leaves every strict application between it and the guard without its matching exit, and the
> guard is where the true depth is known to be zero.

Saving at entry fixes the leak across evals, not within one. An extent recovering a few hundred times
drains its own budget and reaches the same fixed point. Flat from 200 to 12800 recoveries with the
reset; livelocks past 800 without it.

**9. `Handler.scala`, the scaladoc of `recover` and `release`.**

> Both state the state they are consulted with, and both are now the live one.

`recover`'s was made false by edit 6. `release`'s was false before it: a region only becomes
abandonable by being reified into a node, and the reification writes the live state in. Correcting a
sentence that was already wrong is still outside the surface this change first declared, so it is
declared rather than passed off as an improvement.

**10. `EvalTest.scala`, two tests.**

> "regions that fail and recover in sequence cost no stack", 10000 cycles, which livelocks without
> edit 8. And "a context update outlives an operation answered after it", which fails at the baseline
> and passes here, pinning edit 6's second consequence.

The second builds a `SuspendContext` with a non-identity update directly, because the public surface
cannot: every `ContextEffect.suspend` carries `update(v) = v`. An earlier draft called that change
unobservable and shipped it unpinned, which was true of the surface and false of the tree.

## Evidence

Full detail in `evidence.md`, all of it measured on the shipped tip `d197133298`.

| | before | after |
|---|---|---|
| `ArrowEffectTest` | aborted after 17 of 88 | **88 of 88** at the default stack |
| `PendingTest` | 63 of 63 | 63 of 63 |
| `EvalTest` | 51 of 54 | 53 of 56, the two added being pins |
| the three proto suites in one JVM | hung | **207 tests, 204 passing** |
| `kyo-kernelJVM/test` | 1 suite aborted | **35 suites, 0 aborted, 1400 passing** |
| demo, 28 scenarios | recorded values | identical, 4221 / -9 / 991 included |
| clean batch build | green | green |
| 12800 throw/recover cycles | livelocks past 400 | flat, 0.89 us each |

The three remaining failures are the pre-existing eval-boundary item, untouched here.

Benchmarks: the full class on both legs back to back in one session on the shipped tip, 20 of 20
rows. The one row outside the drift band on `-f 1`, `trailingMapsStayLinear` at +4.9%, was confirmed
at `-f 3` and reads **-3.8%** there (735.974 ± 31.941 against 707.966 ± 13.356), so it is not a
regression in either direction that the errors support. **No row regressed.**

Adjudication: `flags.md`, 99 rows, every one with a verdict, generated from the script's output in
one pass over both the main and test trees.

## What I want you to push on

- **The loop's return type.** Everything else follows from it, and if you do not accept `A < S` as
  the honest description, the rest of the shape is wrong too.
- **`recover` reading the working state.** It is the one behavioural change, it follows your ruling,
  and the demo values did not move, but it is a contract and you should say so out loud.
- **The four columns versus a typed entry.** I kept the columns and the storage-boundary cast rather
  than a typed entry object that would allocate per region. The ladder permits the cast; you may
  still not want it here.
- **The context an answered operation resumes with.** It is now the loop's current one, where the
  baseline used the install-time one, which matches what `ContextEffect`'s own documentation says.
  Pinned by a test that fails at the baseline.

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
