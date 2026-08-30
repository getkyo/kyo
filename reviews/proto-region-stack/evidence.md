# Evidence

Change: `a10624dfa4` in the isolated worktree. Control: `31a7b4bde9`.

## Green

**Clean batch build**: `kyo-kernelJVM/clean` then `compile`, zero errors. Run because the change
could summon the lift in a kernel file and incremental green is not clean green.

**Proto suites**, each in its own JVM (see the blocked section for why not together):

| suite | before | after |
|---|---|---|
| `ArrowEffectTest` | aborted after 17 of 88, `StackOverflowError` | **88 of 88** |
| `PendingTest` | 63 of 63 | 63 of 63 |
| `EvalTest` | 51 of 54 | 51 of 54 |

`EvalTest`'s three failures are the pre-existing eval-boundary item (`Eval.apply` returns an
unanswered suspension instead of rejecting it), untouched by this change and recorded as its own
open question.

**Demo**: every one of the 28 scenarios returns its recorded value, the bracket guarantees included:
83, -1, -1, 4221, -9, 991. This was the check most at risk, because `recover` now reads the state the
region has reached rather than the one it was installed with. The values holding is consistent with
the encoded bracket keeping its finalizers in a handler-owned registry rather than in loop state.

**Depth**: `ArrowEffectTest` "handles nested per recursion step in bounded stack" nests 100000
regions and now passes at the default fork stack, where before it needed 1 GB. The standalone
measurement (1426 nested regions at 1 MB) is superseded by that pass and has not been re-run as a
separate probe.

## Blocked: the benchmark rows

This is a tier-three change, so the whole benchmark class on both legs is mandatory, and it is not
done. The bracket refuses to run, correctly:

> the suite is red, so nothing measured here would be a result.

The control leg's suite is red **by construction**: `ArrowEffectTest` aborts there with the
StackOverflowError that this change exists to fix. So no green control exists for this change, and
none exists anywhere in the history, since the abort reproduces at `eabef556e0` and earlier.

The protocol assumes both legs are green, which is right for an optimisation and unreachable for a
change that fixes a suite-aborting defect. Narrowing the gate's task to the suites that are green on
both legs would weaken a gate because it is inconvenient, which is banned, so this is a decision
rather than something to route around.

`flags.md` has one group of rows, the four column allocations and the four in `grow`, whose verdict
is `measurement pending`. **Until those numbers exist this change does not satisfy the EVIDENCE or
REVIEW gates, and the package is not proposable.**

## Known and pre-existing, not caused here

The three suites run together hang at `ArrowEffectTest:969`, the double-boxed-value test. Evidence
that it predates this change:

- the baseline hangs at the identical test when given `-Xss1g` so that it reaches it, where normally
  the StackOverflowError ends the suite at test 17 and it never does;
- `parallelExecution := false` hangs identically, so it is not a race between suites;
- each suite alone passes that test, so it needs a preceding suite in the same JVM.

Hypothesis, supported but not proven: the safepoint budget is per thread and the proto never resets
it at an eval boundary, so once drained a `map` over a settled value always defers, the eval applies
the deferral, and that rebuilds another one. The reference kernel does `Safepoint.save` and
`Safepoint.restore` around its eval; the proto does not. Fixing that is outside this change's
declared surface.
