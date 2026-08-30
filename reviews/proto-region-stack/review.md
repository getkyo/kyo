# Live review: nested regions on a stack, and four defects behind it

Worktree `kyo-root-impl`, branch `proto-lift-fix`, 14 commits off `31a7b4bde9`, tip `6a40f9de4b`.

Ten edits, applied one at a time with the Edit tool, each with the sentence beside it. The sequence
is `sequence.json`; `sequence.py --verify` applies it to the baseline and compares digests, so the
claim that it produces the shipped files is checked rather than made.

## Read this first

**The performance evidence in the first four rounds of this package was about a different kernel.**
`kyo.kernel.bench.ProtoKernelBench` contains no reference to `proto` and measures `kyo.kernel`; it
was named when the proto was `kyo-kernel2` and the rename left the name behind. Nothing under
`src/jmh` referenced `kyo.proto` at all, so no benchmark had ever executed the file this change
edits. Every "no row regressed" this campaign said was true of code the change does not touch.

`ProtoBench` now exists and the real comparison is in `evidence.md`. `package-check.sh` resolves
every benchmark class a package names and reports one that does not reference the package under
review, so this cannot recur silently.

The stale name is left alone. Renaming it changes what every historical number in this repository
refers to, which is your call and not a drive-by.

## What this fixes

**One.** `ArrowEffect` "handles nested per recursion step in bounded stack" aborted the whole suite
with a `StackOverflowError` after 17 of 88 tests. The proto held its installed handlers in the Java
call stack, so nesting depth was the Java stack's: about 700 bytes per open region, and 1426 regions
at 1 MB where the reference is flat past 400000. Iteration inside a region was already flat.

**Two.** Fixing that revealed a second defect it had been hiding: the three suites hang when run in
one JVM. An eval inherited whatever safepoint budget the thread had left, and a spent budget is a
fixed point rather than a slow path.

**Three.** Fixing that exposed a third: a throw leaks a budget entry, so an extent that recovers a
few hundred times drains its own and reaches the same fixed point.

**Four.** The fix for three covered only the eval's guard. A foreign crossing carried its own copy of
the region's guard, on a path where no repair is possible, so every recovered crossing still lost
one. Fixed by removing the copy rather than by repairing it: the resumed application is deferred, so
the region installs around it and the throw reaches the one guard.

**Five.** `Eval.apply` handed back a suspension nobody answered as though it were a value, so
`<.eval` returned a node typed as a value and the cast that discovered it fired arbitrarily far from
the operation. Three `EvalTest` cases assert the rejection and had been red since the corpus was
ported. Rejecting them surfaced a representation bug underneath: the `Loop.done` arm stripped one
wrapper too many, so a region completing with a computation held as data delivered it bare.

Four and five were found by the review pipeline's own lenses, not by me, and both were real.

## The equation, in one paragraph

A region is already a value here. `Kyo.Handle` is the region installed around a computation, and the
foreign-crossing arm already turns an open one back into that value. So N open regions are a
right-nested chain of `Handle` values, and a chain of values can live on the heap. **The stack is
that chain, unrolled.** Nothing about what a region means changes; only where the chain is kept.

Full derivation, including why `ctx` is a memoization rather than a new piece, and the two new
sections deriving the crossing and the boundary, is in `derivation.md`.

## Edit sequence

Ten edits, verified. Intermediate states do not compile, which is expected: you see each change in
the order it is reasoned about, and the tree is green again at the end.

| # | edit | file | lines | the sentence |
|---|---|---|---|---|
| 1 | the region stack | `Stack.scala`, new | 78 | four arrays and a size; `push` typed because the pushing site knows all five, the reads asserted because the reader knows none |
| 2 | two imports | `Eval.scala` | 11 → 14 | two names for the unwind's `Maybe`, and the annotation that makes the loop's self calls jumps |
| 3 | the loop's signature, the stack, the budget | `Eval.scala` | 1 → 22 | the loop returns the eval's answer, because once a region's continuation waits on the stack the composition of the arguments stops describing what the call produces |
| 4 | the three context arms retyped | `Eval.scala` | 12 → 12 | mechanical, and forced by the edit above: `A` and `S` are the eval's now, and were shadowing it |
| 5 | the Suspend arm absorbs, then asks the regions | `Eval.scala` | 56 → 182 | the innermost region answers if the tag is its own; if it is foreign the region joins the suspension's continuation and ends, and re-entering asks the next region out the same question |
| 6 | the Handle arm installs instead of entering | `Eval.scala` | 127 → 16 | a region is installed rather than entered, so its interior is evaluated by this same loop instead of a nested one; `region` is deleted here |
| 7 | the settled arm completes and pops | `Eval.scala` | 14 → 24 | `done` runs with the region still installed, so a throw in it reaches the same recover its interior would |
| 8 | the guard, replacing `run` | `Eval.scala` | 10 → 71 | the regions a throw unwinds are the ones the stack holds, and both recovering and declining are self tail calls, so neither costs stack |
| 9 | the scaladoc of `recover` and `release` | `Handler.scala` | 8 → 14 | both say the state they are consulted with, and both are the live one |
| 10 | four pinning tests | `EvalTest.scala` | 1 → 121 | a context update outliving an answered operation, ten thousand recover cycles, a hundred crossings that recover, and a recover that fails itself |

Edit 5 is the big one, and most of its bulk is not new.

### What actually changed inside the two relocated blocks

Generated, not recalled: each block is compared against the baseline with indentation, reflow and
the type rename normalized away, and what survives is printed.

**The absorb block**, exactly two differences:

- `then kyo.asInstanceOf[C < S]` becomes `then kyo`. **A cast is removed.** The block now produces
  the suspension for the regions to be asked about, rather than the arm's answer.
- `end if` becomes `end match`, because the block is now a `val`'s right-hand side.

**The rebuild block**, exactly three, all the same rename: the pattern binder `kyo` becomes `p` in
the three `Pending` arms, because `kyo` no longer names an enclosing `Handle` node. Everything else
is the baseline's text with the handler and the state read from the stack entry instead of from a
closure.

Round 4 stopped on the previous version of this section, which claimed `flags.md` named five
differences it did not name and structurally could not. This replaces it with a script's output.

## Evidence

`evidence.md`, all of it at the tip `6a40f9de4b`.

| | result |
|---|---|
| clean batch build | green |
| `kyo-kernelJVM/test` | 35 suites, 0 aborted, **1405 tests, 0 failed** |
| the three proto suites in one JVM | **208 tests, 0 failed** |
| the edit sequence | 10 edits reproduce all 4 files, by digest |
| flags | 98 rows, every one adjudicated |

At the baseline the module aborts a suite and cannot be run green.

**Proto against the kernel**, twenty paired rows on identical work: three faster, eight at parity,
nine slower. The slow rows are not scattered. Every row where an operation suspends and a handler
answers it is 4x to 4.9x slower, and every row that does not suspend is at parity or faster;
`suspensionFusesContinuation`, which suspends ten thousand times with its continuation fused into
the node, is at parity. Roughly 30ns per answered operation against the kernel's 4ns. Named, not
diagnosed: that is the first rung of the ladder and it needs an allocation profile and an inlining
log before anything is claimed.

## What I want you to push on

- **The loop's return type.** Everything else follows from it, and if you do not accept `A < S` as
  the honest description, the rest of the shape is wrong too.
- **The deferred crossing.** It removes a `recover` call site and a hand-carried guard, and it costs
  one `Defer` allocation per foreign resume. `emittingClausesPayRegionRebuild`, the row that
  exercises it, is at parity with the kernel, but I have not isolated that allocation's cost.
- **`recover` reading the working state.** The one behavioural change; it follows your ruling and the
  demo values did not move, but it is a contract and you should say so out loud.
- **The four columns versus a typed entry.** I kept the columns and the storage-boundary cast rather
  than a typed entry object that would allocate per region. The ladder permits the cast; you may
  still not want it here.
- **The context an answered operation resumes with.** Now the loop's current one, where the baseline
  used the install-time one, which matches what `ContextEffect`'s own documentation says. Pinned by a
  test that fails at the baseline.

## Deviations, recorded

**No green control exists for the A/B suite gate.** The baseline's suite is red by construction: it
aborts with the overflow this change fixes, back through `eabef556e0`. Narrowing the gate's task to
the suites green on both legs was available and rejected as weakening a gate for convenience, so the
legs were run directly and the bracket's A/A null and warmup guards did not run over them.

**The benchmark host was not quiet.** The user's IDE and its Bloop server ran throughout, at a load
average between 5 and 8. Ratios of 3x and up are far outside anything that explains; rows inside 15%
are reported as inside the noise rather than as differences.
