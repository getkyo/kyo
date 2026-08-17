# What the report should show, given what the results were

Written while a fourth review runs, deliberately before reading its answer, so the two can be compared
rather than one anchoring the other.

**The founding requirement, restated:** the operator forgets to go looking. So the tool must decide
*which* evidence to surface **from the result itself**, and put it in the output the operator is
already reading. A command that must be remembered is a failure by construction.

**v4's weakness, stated plainly:** v4 is largely a defect list. It corrects many wrong statements and
wires several stranded functions, but it never writes down *the selection rule*. Without that, the
report after v4 is the same shape with fewer wrong numbers, which is not what was asked for.

## The selection table

One row per result shape. The left column is computable from `Comparison` today or after a named item.

| result shape | what the report must surface | source | status |
|---|---|---|---|
| **Regressed, allocation moved** | that row's allocation sites, ranked by sample delta, with the minting method | `allocByMethod` | rendered, but not row-scoped |
| **Regressed, allocation flat** | inlining verdict changes on that row's path, then near-budget methods, ranked by proximity | `jitShift`, `nearBudget`, `budgetCandidates` | `budgetCandidates` unwired |
| **Regressed, no jit evidence** | **what is missing and the exact command that would get it**, never "check the inlining log" | `evidence` | defect 43 |
| **Flat, delta large** | which term bound the threshold, and therefore whether more legs or more forks would resolve it | `Stats.threshold` both terms | item 6 |
| **Flat, allocation moved** | the allocation as a real result, since it is exact and per-operation | `allocDelta` | rendered |
| **Win, no mechanism** | the inlining changes that accompany it, and explicitly that a two-sha pair cannot attribute | `jitShift`, `partitionNote` | rendered |
| **Legs differ in `jvmArgs`** | **did the instructed method's verdict actually move**, and did anything else move with it | `Investigate.efficacy` | item 1 |
| **A/A null dirty** | which rows classified, as a blocker | `nullComparison` | wired in `bracket` only |
| **Whole-class Full leg** | that jit evidence describes one row, not the leg | `Run.jit` provenance | defect 30 |
| **Any Full leg** | the three-way CPU partition, as shares, with the largest contributors named | `cpuPartition` | item 4 rework |

## The two rules that make it work

**1. A flag carries its own evidence.** If a paragraph names a problem, it contains the data it stands
on. This is v3's rule and it survives.

**2. Absence is a result, and it names its remedy.** This is the rule v4 is missing, and it is the one
that matters most for an operator who forgets. When the report cannot say something, it must say:
*what it cannot say, why, and the command that would change that.* Not "check the inlining log", which
is homework; instead:

    Timing evidence only, so no inlining data exists for this pair.
    To get it: bench bracket --evidence full --row evalFixedOverhead ...

That single change converts every "the operator must remember" case into a "the output already told
them" case, which is the founding requirement in one sentence.

## What this implies for v4's items, and where I think v4 is over-hedged

**Item 4 should not refuse the inference.** v4 says "state the partition, not the inference", which is
a defensible reaction to having been wrong twice, but it produces a tool that reports three numbers and
declines to help. The honest and useful form separates what is measured from what follows:

    Sampled time: 16% kernel, 48% the benchmark's own closures, 36% boxing and infrastructure.
    A kernel change moves the 16% directly. It moves the 48% only by changing how often those
    closures run or whether they allocate, which is what candidate C3 proposes.

That states the partition, names the mechanism by which the contested 48% *is* reachable, and cites the
candidate that depends on it. It is more useful than three bare numbers **and** more defensible than
either two-way split, because it stops asserting a ceiling.

**The always-fires problem is real and the fix is not a threshold.** Gating on a percentage was always
wrong: the partition is a fact about every Full run. Print it always, as a fact line, and reserve the
warning form for when the kernel share is *small enough to bound the possible effect* — which is a
statement about this comparison's deltas, not a fixed 25%.

## The one thing genuinely missing from v4

**No item makes the report state what it could not evaluate.** Every item adds or corrects a positive
statement. Nothing enumerates, per comparison, the checks that did not run and why: no A/A null because
one control leg, no efficacy because both legs share `jvmArgs`, no mechanism because evidence is
Timing, no attribution because the leg is whole-class.

That enumeration is the single highest-value addition for an operator who forgets, because it converts
silence into a checklist. I would put it above every remaining item except the correctness fix in
item 12.

**Correction to my own paragraph above, made before it propagated.** I first wrote that the enumeration
"is computable today from `Comparison` plus `Run.evidence`". Checked: `Comparison` (`Model.scala:371`)
carries `control: Run, variant: Run, deltas, jitChanges`, **one control `Run`, not the leg count**. So:

- *no mechanism, evidence is Timing*: computable, `Run.evidence`.
- *no attribution, whole-class leg*: computable, `Run.wholeClass`.
- *no efficacy, both legs share `jvmArgs`*: computable, `Run.jvmArgs`.
- *no A/A null, fewer than three control legs*: **not computable**. The leg count exists only at the
  `Cli` level and is thrown away before `Report.render` sees it.

Three of four are free; the fourth needs the leg count carried on `Comparison`, which is the same model
change item 6 needs for `Resolution` and item 10 needs for drift. **Those three items should land
together as one change to `Comparison`**, not as three separate ones, and that is an ordering fact v4
does not have.
