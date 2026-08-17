# Held-out review of the improvement plan: findings, and what I verified

The reviewer re-derived the replicated sweep's thresholds from the five stored legs independently and
reproduced the tool's numbers exactly, so its arithmetic is checked against the harness rather than
against my writeups. I then spot-checked its two highest-value claims myself. **Both hold.**

## The thesis is wrong, and the wrong half is load-bearing

My plan claimed the tool "refuses well and volunteers poorly". Classifying all 29 defects by shape,
the reviewer found: **7** cases of the tool stating something wrong or silently substituting a weaker
thing, **8** guards that did not guard, **6** missing capabilities, and only **3-4** genuine
had-the-data-did-not-say-it. So my diagnosis covers about one defect in eight.

The dominant failure is **a confident statement that was wrong, or was not the statement it claimed to
be**. Four of my five section-A items add a *new derived claim* to the report, landing squarely in the
category that has produced seven wrong outputs, and the plan proposed no new cross-validation for any
of them.

## Two findings I verified myself

**1. `bench compare` and `bench chain` never run the A/A null.** `Bench.nullComparison` and
`Report.nullBlockers` have exactly two call sites in the harness, `Cli.scala:210` and `Cli.scala:215`,
both inside `BenchBracket`. So re-reading a stored bracket through `compare` reproduces the replicated
verdict and **silently drops the strongest refusal the tool has** — the one `Bench.scala` calls "the
only check here that can fail in the direction that matters". This is defect 27 one level up, and it
is not in my plan. `bench chain` never checks itself on any step.

**2. `Bench.KnownNoise` is incomplete and the tool prints a wrong number because of it.**
It is `Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")`. On the only real CPU profile in the
repository it matches `boxToInteger` at 29.1% and **nothing else**: the benchmark's own generated code
is `ProtoKernelBench.loop$9` (17.1%), `run$39` (13.2%), `ask` (11.1%), `anon$95.<init>` (6.7%), none
of which contain `jmh_generated`. So `noiseShare` reports **29%** where the true non-kernel share is
**70%**, under the sentence "% of sampled time is in classes no kernel change can move" — understated
by 41 points, biased toward making the kernel look more relevant than it is. Its test fixture cannot
catch this, being two authored frames with no `ProtoKernelBench` in them: **a live instance of open
defect 9.**

## A factual error of mine that propagated

`bench-results/sweep-replicated/RESULT.md` and `WORK.md` both say "the two largest deltas do not
resolve", naming −12.5% and −10.7%. **The largest delta on the board is `trailingMapsStayLinear` at
+43.2%, also flat.** It is three, not two. The plan inherited it from the writeup. This is the
counterintuitive fact the campaign leans on hardest and I stated it wrong in three documents.

## The finding I would not have reached

The reviewer split each row's threshold into its two terms, `max(t·se, ownError·controlMean)`, and
found **7 of 15 rows are floor-bound**, i.e. their resolution is set by the legs' own JMH error rather
than by between-leg spread. Including this one:

> **`handleLoopAnswersInPlace` is −10.7% against a ±14.4% resolution set entirely by the own-error
> floor; its between-leg spread term is ±8.2%.** Halve the legs' own error and that row resolves as a
> **win**.

That is the exact row whose demotion turned the sweep's "five wins" into three. So the campaign's most
consequential flat verdict sits on a threshold that forks would plausibly have moved, while
`Plan.scala` tells the operator, unconditionally, that "forks do not help" — a claim resting on one
A/A on one row, which on inspection cut own error ~2x (not six-fold) and made the spread term *worse*.

**The item this yields, which beats everything in my plan:** annotate the existing `resolves` column
with which term binds. `±14.4% (own error)` is fixed by forks or longer iterations; `±12.6% (spread)`
is fixed by more legs. Both terms are already computed inside `Stats.threshold`. Nothing needs
measuring to say it.

## Verdict per item

**Cut:** A2 (not implementable: the four readings span different stores, and the runs that matter have
no `jvmArgs`, so it would fire on differences the store cannot explain), A3 as written (no CPU profile
exists in any campaign store except two QA fixtures, and `Run.cpu` has no row key, so it is a schema
change plus a re-measurement rather than a synthesis), B2 (contradicted by the tool's own text and by
the floor analysis above), C2 (busywork; covered by B1).

**Keep with rework:** A1 (evidence wrong, three rows not two; make it a table property not appended
prose), A4 (premise wrong: `budgetCandidates` has no CLI surface at all, so the item is *adding the
command*, not labelling it), A5 (needs a stated selection rule; pooling a whole store mixes shas,
configurations and sessions).

**Keep as-is:** B3, C1.

## Recommended order, which I accept

1. Run the A/A null in `compare` and `chain` (missing refusal)
2. Fix `KnownNoise`, name the top noise frames, fixture from the real capture
3. Annotate `resolves` with its binding term (replaces A1's prose and B2 entirely)
4. B3, dangling configuration reference
5. Fix the `-f N is diagnostic` note to key on `df == 0` rather than fork count
6. Give `budgetCandidates` a CLI surface, partitioned kernel/non-kernel
7. B1, after (1)
8. A5 with the selection rule
9. C1
10. A1 reduced to table ordering, acceptance corrected to three rows
11. Fix `Ingest` warmup provenance: it writes `warmup = 10` unconditionally, so `e6-base-wi25`,
    measured at `-wi 25`, is **stored as a falsehood**. Unfiled, and defect 25's family with the sign
    flipped: not a missing field but a fabricated one.

## Ledger drift the reviewer caught

`WORK.md` still says "Twenty-three tool defects... twenty-one fixed" against the current 29/25, and
still states defect 9's open part as "runs on request rather than every session", which defect 24
closed.
