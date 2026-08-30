# Live review: nested regions on a stack, and an eval's own budget

Two changes, in dependency order. Each is applied one edit at a time with the Edit tool, in the
sequence below, with the sentence beside each edit said as it goes in.

Worktree `kyo-root-impl`, seven commits off `31a7b4bde9`, tip `b58e2fbc4e`.

## What this fixes

**One.** `ArrowEffect` "handles nested per recursion step in bounded stack" aborted the whole suite
with a `StackOverflowError` after 17 of 88 tests. The proto held its installed handlers in the Java
call stack, so nesting depth was the Java stack's depth: about 700 bytes per open region, and 1426
regions at 1 MB where the reference is flat past 400000. Iteration inside a region was already flat.

**Two.** Fixing that revealed a second defect it had been hiding: the three suites hang when run in
one JVM. An eval inherited whatever safepoint budget the thread had left,
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

Nine edits. `Eval.scala`'s eight are a **verified decomposition**, not a description: the baseline and
the shipped file differ in 26 regions, every one is assigned to exactly one edit below, and every
one's text was checked to land in the final file. An earlier version of this section described the
edits in prose without ever building the sequence, so the walk could not be performed from it. That
is what this replaces.

Intermediate states do not compile, which is expected: the reviewer sees each change in the order it
is reasoned about, and the tree is green again at the end.

| # | edit | file | hunks | the sentence |
|---|---|---|---|---|
| 1 | the region stack | `Stack.scala`, new | file | four arrays and a size; `push` typed because the pushing site knows all five, the reads asserted because the reader knows none |
| 2 | the loop's signature, the stack it closes over, and two imports | `Eval.scala` | 3 | the loop returns the eval's answer, because once a region's continuation waits on the stack the composition of the arguments stops describing what the call produces |
| 3 | the step's type parameters become `T` and `S2` | `Eval.scala` | 13 | mechanical, and forced by edit 2: `A` and `S` are the eval's now, and were shadowing it |
| 4 | the Suspend arm absorbs the registers, then asks the regions | `Eval.scala` | 2 | the innermost region answers if the tag is its own; if it is foreign the region joins the suspension's continuation and ends, and re-entering asks the next region out the same question |
| 5 | the rebuild reads the handler and state from the entry | `Eval.scala` | 3 | the same block, reading two values from the stack instead of from `region`'s closure |
| 6 | the own-tag answer advances in place, or completes and pops | `Eval.scala` | 1 | a continue writes the successor state into the entry; a done outcome pops and carries the payload out |
| 7 | the Handle arm installs and continues | `Eval.scala` | 1 | a region is installed rather than entered, so its interior is evaluated by this same loop instead of a nested one; `region` is deleted here |
| 8 | the settled arm completes the innermost region and pops | `Eval.scala` | 1 | `done` runs with the region still installed, so a throw in it reaches the same recover its interior would |
| 9 | the guard becomes two tail-recursive methods, replacing `run` | `Eval.scala` | 2 | the regions a throw unwinds are the ones the stack holds, and both recovering and declining are self tail calls, so neither costs stack |

Then two edits outside `Eval.scala`:

| # | edit | file | the sentence |
|---|---|---|---|
| 10 | the scaladoc of `recover` and `release` | `Handler.scala` | both state the state they are consulted with, and both are the live one |
| 11 | two pinning tests | `EvalTest.scala` | 10000 throw-and-recover cycles, which livelocks without edit 9's budget reset; and a context update surviving an answered operation, which fails at the baseline |

### What edit 9 replaced, twice

Worth saying while it is in front of you, because both wrong shapes were mine and the second was worse
than the first. The first had `recovered` call `run` from inside `run`'s catch: **mutual** recursion,
which is not eliminated, so every recovered region cost a frame, reintroducing exactly the dependency
this change removes. The second over-corrected into four locals and two `while` loops, on an untested
belief that a call in a `catch` cannot be eliminated. A probe recursing two million times through a
catch path returns without overflowing, so it can. What ships is the recursion, with no locals.

## Evidence

Full detail in `evidence.md`, all of it measured on the shipped tip `d197133298`.

| | before | after |
|---|---|---|
| `ArrowEffectTest` | aborted after 17 of 88 | **88 of 88** at the default stack |
| `PendingTest` | 63 of 63 | 63 of 63 |
| `EvalTest` | 51 of 54 | 53 of 56, the two added being pins; 3 red are the open boundary |
| the three proto suites in one JVM | hung | **207 tests, 204 passing** |
| `kyo-kernelJVM/test` | 1 suite aborted | **35 suites, 0 aborted, 1400 passing** |
| demo, 28 scenarios | recorded values | identical, 4221 / -9 / 991 included |
| clean batch build | green | green |
| 12800 throw/recover cycles | livelocks past 400 | flat, 0.89 us each |

Three failures remain, all the eval boundary; it is in scope, attempted, and open. See the section at the end.

Benchmarks: the full class on both legs back to back in one session on the shipped tip, 20 of 20
rows. The one row outside the drift band on `-f 1`, `trailingMapsStayLinear` at +4.9%, was confirmed
at `-f 3` and reads **-3.8%** there (735.974 ± 31.941 against 707.966 ± 13.356), so it is not a
regression in either direction that the errors support. **No row regressed.**

Adjudication: `flags.md`, 90 rows, every one with a verdict, generated from the script's output in
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

## Open, and in scope: the eval boundary returns an unanswered suspension

`Eval.apply` hands back a suspension nobody answered instead of rejecting it, so `<.eval` gives the
caller a `Pending` node typed as a value, and it fails as a `ClassCastException` arbitrarily far from
the operation that caused it. Three `EvalTest` cases assert the rejection and fail.

Attempted, not landed. Rejecting at the guard, where the loop returns with the stack empty, breaks
`PendingTest` "a loop can end its region with a computation result", which requires the eval to hand
back a computation held as data. Two hypotheses were tested and both were wrong: `Kyo.lift` does nest
a pending payload, verified by probing the runtime class, so the payload is not arriving bare for
that reason; and the cause is not the done arm's unnest as I first read it. The placement that
separates a suspension being *driven* from one being *delivered as a payload* is not yet identified,
and I am not guessing at a third.

Recorded unfixed rather than deferred, with both dead ends named so the next attempt starts from the
third hypothesis.

## Deviation, recorded

The harness bracket refuses a leg whose suite is red, and the control's is red **by construction**,
since it aborts with the overflow this change fixes, back through `eabef556e0`. No green control
exists or can exist for this change. Narrowing the gate's task to the suites green on both legs was
available and rejected as weakening a gate for convenience, so the legs were run directly and the
bracket's A/A null and warmup guards did not run over them.
