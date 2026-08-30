# Escapes

What the lenses caught, which rung should have caught it, and what the repair is. Recorded because
the pipeline only gets sharper if a finding names the hole it came through, and because most of
these are the same hole seen repeatedly.

## The one that matters: four rounds of numbers about a kernel the change does not touch

Every performance claim this campaign made was measured on `kyo.kernel.bench.ProtoKernelBench`.
That class contains no reference to `proto`, and until this round nothing under `src/jmh`
referenced `kyo.proto` at all. It measures `kyo.kernel`. It was named when the proto was
`kyo-kernel2`, and the rename that made kernel2 the kernel (`959554afd0`) left the name behind.

So "no row regressed", the `trailingMapsStayLinear` confirmation at `-f 3`, the drift-band
argument, and every number the four rehearsal rounds argued about were about code in a different
package from the change. Not stale, not mismeasured: unrelated. The change is in
`kyo/proto/kernel/internal/Eval.scala` and no benchmark executed that file.

**Rung**: none. Four rounds of lenses read those tables and argued about their contents, and the
discipline lens twice caught a claim being broader than its verification, which is the neighbouring
error. Nobody asked the prior question, whether the thing measured is the thing changed, because a
class called `ProtoKernelBench` sitting beside a proto kernel answers it by looking right.

**Repair**, two parts:

- `package-check.sh` now resolves every benchmark class the package names to its source and checks
  that the source references the package under review. A class that does not is reported STALE with
  the words "its rows are not evidence about this change". Mechanical, so a plausible name cannot
  satisfy it.
- `ProtoBench` exists, in `kyo.proto.bench`, with the twenty rows ported body for body against
  `kyo.proto.kernel`, so the proto has a benchmark for the first time.

The stale name is left alone and flagged to the user rather than renamed here: renaming it changes
what every historical number in this repository refers to, which is their call, not a drive-by.

## The recurring one: an artifact that describes a tree that is not shipping

Four rounds, four instances, one hole:

- **r1**: the adjudication table carried `measurement pending` after the benchmarks had closed the
  question, because `evidence.md` was updated and `flags.md` was not.
- **r2**: the benchmark table measured `ffc1819ecc`, which was not the tip; the package walked a
  `finally` the tip did not contain.
- **r3**: the package named four commits and tip `7a7cd22ad8` when the tree was five commits at
  `d197133298`; an edit step showed a `run` that an earlier step had deleted.
- **r4**: every number dated to `d197133298`, two commits behind a tip that had rewritten the guard
  each of them enters; `flags.md` cited lines from an uncommitted working tree.

Every instance is the same shape: **prose asserting a fact about the tree, written once and not
re-derived when the tree moved**. No rung owned it, because `kernel-discipline` checks verdicts,
`kernel-conformance` checks the design, and the rehearsal reads the package as a reader rather than
as a fact-checker.

**Repair**: `package-check.sh` re-derives every mechanical claim (tip sha, commit count, the surface
the range touches, working-tree cleanliness, flag-row count, benchmark coverage, and whether the
recorded edit sequence reproduces the tip) and reports STALE per claim. It runs in the REVIEW phase
beside `flags.sh`. Round 4 named this file's promise of it as itself a stale claim, since the script
did not exist; it does now, and this paragraph describes what it checks rather than what it should.

## The other recurring one: verification narrower than the claim it licenses

- the `moved` group's "a `git diff -w` shows only two substitutions" (r1). What was run was a grep
  for two identifiers, which cannot see a signature change.
- the generated table's `moved` fallback (r3): filing a line as `moved` **without comparing it**,
  inside a table whose preamble claimed comparison had replaced recollection.
- the budget-restore pinning test, caught by the author: it passed with the fix reverted, so it
  pinned nothing.
- r4's "flags.md names all five differences", which flags.md did not do and structurally cannot: a
  relocation with a signature change produces no flag row.

**Repair**: relocation claims are now generated rather than asserted. The absorb block and the
rebuild block are each compared against the baseline with indentation, reflow and the type rename
normalized away, and the surviving differences are printed and carried into `review.md`. There are
two in the absorb block and three renames in the rebuild block, and they are named because a script
found them, not because someone remembered them.

And the negative check is the rule rather than a habit: a test offered as a pin is run against the
baseline, and a test that passes there is not a pin. Where the baseline gave the behaviour for free
(the `recover` that fails itself, which nested tries handled), the negative check is a mutation of
the shipped control flow instead, and the mutation is recorded with the pin.

## The repeat

`kernel-rehearsal` round 3 marked one finding REPEAT: `Handler.release`'s scaladoc was rewritten
while the derivation declared `recover`'s "only". The ruling it repeats:

> you should only update the regions handling?

A repeat is the worst finding in the set, because it means the preparation ignored a ruling already
given. The correction was to declare the file in the surface, not to revert the sentence, which was
false as written. The reason it happened is worth naming: the sentence was *wrong*, so fixing it
felt like hygiene rather than a change, and "it was an improvement" is exactly what the ruling
forbids as a justification.

The same pressure produced round 4's R6, two `TODO` notes riding along in `Effect.scala`. Those are
resolved the other way, by taking them out of the range: they are the user's questions about
`unitValue` and about `deferInline`'s branch structure, they are not this change, and they are
written up with analysis in `notes.md` so the questions survive without the surface growing.

## What did not escape

Worth recording so it is not re-litigated: the code walked clean in round 3's and round 4's
rehearsals. The three defects the change fixes, the guard's shape, the cast set, and the loop's
return type each survived a held-out reading against the rulings file without a stop, and round 4
said so before stopping on the artifacts. The two code findings it did raise, R8 and R9, were both
real, and both are fixed rather than argued with.
