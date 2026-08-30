# Escapes

What the lenses caught, which rung should have caught it, and what the repair is. Recorded because
the pipeline only gets sharper if a finding names the hole it came through, and because three of
these are the same hole seen three times.

## The repeat

`kernel-rehearsal` round 3 marked one finding REPEAT: `Handler.release`'s scaladoc was rewritten
while the derivation declared `recover`'s "only". The ruling it repeats is recorded under Scope:

> you should only update the regions handling?

A repeat is the worst finding in the set, because it means the preparation ignored a ruling already
given. The correction was to declare the file in the surface, not to revert the sentence, which was
false as written. But the reason it happened is worth naming: the sentence was *wrong*, so fixing it
felt like hygiene rather than a change, and "it was an improvement" is exactly what the ruling
forbids as a justification.

**Rung**: `kernel-conformance`, which owns the surface, caught it in round 2 for `recover` and the
same escape recurred one method over. **Repair**: none to the pipeline; the lens worked. The entry
exists so the rehearsal keeps weighting it.

## The recurring one: an artifact that describes a tree that is not shipping

Three rounds, three instances, one hole:

- **r1**: the adjudication table carried `measurement pending` after the benchmarks had closed the
  question, because `evidence.md` was updated and `flags.md` was not.
- **r2**: the benchmark table measured `ffc1819ecc`, which was not the tip; the package walked a
  `finally` the tip did not contain.
- **r3**: the package named four commits and tip `7a7cd22ad8` when the tree was five commits at
  `d197133298`; the suite numbers dated to a commit two behind; the row count said 88 against 89; an
  edit step showed a `run` that an earlier step had deleted.

Every instance is the same shape: **prose asserting a fact about the tree, written once and not
re-derived when the tree moved**. No rung owned it, because `kernel-discipline` checks verdicts,
`kernel-conformance` checks the design, and the rehearsal reads the package as a reader rather than
as a fact-checker. All three found instances anyway, which is luck rather than coverage.

**Repair**: `package-check.sh`, which re-derives every mechanical claim a package makes (tip sha,
commit count, flag-row count, suite totals, benchmark leg shas) and diffs them against what the
artifacts say. Mechanical, so it cannot get bored, and it runs in the REVIEW phase beside `flags.sh`.
This is the one stage this campaign adds, and these three instances are its justification.

## The other recurring one: verification narrower than the claim it licenses

- the `moved` group's "a `git diff -w` shows only two substitutions" (r1). What was run was a grep
  for two identifiers, which cannot see a signature change; the real answer was five differences,
  one of them `reenter`'s rows.
- the generated table's `moved` fallback (r3). Filing any `new ...` line as `moved` **without
  comparing it**, inside a table whose preamble claimed comparison had replaced recollection.
- the budget-restore pinning test, caught by the author: it passed with the fix reverted, so it
  pinned nothing.

**Rung**: `kernel-discipline`, and it caught the first two. The third was caught only because the
negative check was run at all. **Repair**: the negative check is now the rule rather than a habit. A
test offered as a pin is run against the baseline, and a test that passes there is not a pin. Added
to the skill's REVIEW phase.

## What did not escape

Worth recording so it is not re-litigated: the code itself walked clean in round 3's rehearsal, and
every finding in that round was about the artifacts. The three defects the change fixes, the guard's
shape, the cast set, and the loop's return type each survived a held-out reading against the rulings
file without a stop.
