# Items 5, 6, 7, 10, 11: designed against the code

The five v3 items that Step 0 does not gate, plus item 4 in its own file. Prepared while a held-out
reviewer holds the sources, so implementation is one pass.

**Schema note that governs three of these.** `Resolution` and `Comparison` live on `Delta`, which is
computed by `compare`/`compareReplicated` and **never persisted**; only `Run` is stored. So adding
fields to them carries **no defect-17 risk**. `Run.warmup` *is* stored, so item 11 does.

## Item 5, run the A/A null in `compare` and `chain`

Today `Cli.scala:210-224` interleaves five concerns inside `BenchBracket`: compute the null, compute
the comparison, merge both blocker sets, print either the null note or a banner, render, and abort.
None of it is reachable from `compare` or `chain`.

Extract it whole, so the three commands cannot drift:

    // Report
    case class Session(text: String, blockers: Chunk[String])
    def session(aa: Maybe[Comparison], controlLegs: Int, cmp: Comparison): Session

`bracket` and `chain` pass a real null. `compare` is the interesting case: `nullComparison` needs at
least two control legs, and `--control` may be given once.

**It must not silently skip.** Per v3's rule, the absence is itself a finding and carries its own
evidence:

    ⚠️  One control leg, so the A/A null could not run. The verdict below rests on the
        replicate spread alone, with nothing checking that a control leg classifies clean
        against another control leg. Pass every control leg of the bracket to get it.

With two or more, it runs exactly as `bracket` does. **Acceptance:** re-reading the C4 store through
`compare` with its three controls prints the same `A/A null: clean` line that `bracket` printed, and the
single-control form prints the refusal above. The current output prints neither, which is the defect.

For `chain`, each step already has `--legs 2` per sha, so every step can carry its own null.

## Item 6, annotate `resolves` with the binding term

`Stats.threshold` (`Stats.scala:154-162`) already computes both terms and discards which one won:

    val abs = Math.max(t * se, r.ownError * r.controlMean)

Carry it:

    enum Bound:
        case Spread, OwnError
    case class Resolution(percent: Double, absolute: Double, df: Int, alpha: Double, bound: Bound)

    val spread = t * se
    val floor  = r.ownError * r.controlMean
    val bound  = if floor >= spread then Bound.OwnError else Bound.Spread

Rendered in the existing `resolves` column as `±14.4% (own error)` / `±12.6% (spread)`.

**This is not cosmetic.** Seven of fifteen rows in the replicated sweep are floor-bound, and
`handleLoopAnswersInPlace` at −10.7% carries a ±14.4% threshold set **entirely** by own error against a
±8.2% spread term. That is the row whose demotion turned five wins into three. `(own error)` says more
forks or longer iterations; `(spread)` says more legs. `Plan.scala` currently tells the operator
unconditionally that forks do not help, which for those seven rows is wrong.

**Acceptance:** the sweep's 15 rows label 7 as `own error`, and `PlanTest`, which already pins the
forecast against real stored legs, gains the assertion that the label matches which term was larger.

## Item 7, key the `-f N` note on the threshold, not the fork count

    val forksNote = if variant.forks < 3 then s", -f ${variant.forks} is diagnostic and not a claim" else ""

`Store.scala:229`. Fires on fork count alone, so every replicated bracket in this campaign carries it
while reporting a real threshold at df 3. Reproduced live: one render says "`-f 1` is diagnostic and not
a claim" in the header and "df 3" in the footer.

    val earnedThreshold = c.deltas.exists(_.resolution.exists(_.df > 0))
    val forksNote = if earnedThreshold then "" else s", -f ${variant.forks} is diagnostic and not a claim"

`exists` rather than `forall`, and deliberately: a row with no resolution should not suppress the note
for a session that did earn one elsewhere. **Acceptance:** the C4 render loses the note; a genuine
single-pair `compare`, where every `df` is 0, keeps it.

## Item 10, `Stats.commonMode`: wire it, and it closes defect 37 too

`Bench.scala:729` binds `val common = Stats.commonMode(reps)` and never reads it. `Stats.residual` has
no caller. A session-drift estimate computed and discarded is worse than absent, because a reader of
`compareReplicated` assumes drift is accounted for.

The wiring writes itself once you notice **defect 37**: `Session.driftPercent` is hardcoded `0.0`, so
`Bench.band` always falls back to the assumed 4.0 and every report prints "drift 4.0% assumed, not
measured". The measured value the report wants is exactly what `commonMode` computes.

    case class Comparison(control, variant, deltas, jitChanges, drift: Maybe[Double])

`compareReplicated` sets `drift = Stats.commonMode(reps)`; `compare` sets `Maybe.empty`. `Report.render`
prefers it over `Bench.band`, printing `drift 2.3% measured across 5 legs` instead of the assumed
constant. `residual` then has a home as well: a row whose residual is large after removing the common
mode is a row that is noisy *on its own*, not one riding session drift.

**This closes defects 37 and 40 together**, and gives dead `measureDrift`'s intent a home without
resurrecting it. **Acceptance:** the C4 and sweep renders print a measured drift; a single-pair
`compare` still prints "assumed, not measured", which is then true rather than universal.

## Item 11, `Ingest` fabricates a warmup

`Ingest.scala:56` writes `warmup = Bench.WarmupIterations` unconditionally. `e6-base-wi25` was measured
at `-wi 25` and **is stored as 10**. Not a missing field, a fabricated one.

`Run.warmup` **is persisted**, so this is the one item that touches the store schema and the one that
must not repeat defect 17:

    warmup: Maybe[Int] = Maybe.empty   // defaulted, so all 49 stored runs still decode

`runLeg` keeps setting it because it genuinely knows. `IngestOpts` gains `--warmup`, unset by default,
and ingest stores what it was told or nothing. **A value it cannot know is not a value it may invent.**

**Acceptance:** ingesting `e6-base-wi25` without `--warmup` stores no warmup rather than 10; with
`--warmup 25` it stores 25; and a `StoreSchemaTest` case decodes an existing run that predates the
change. `Run.warmup` currently reaches no output at all, so rendering it belongs with item 3.

## Suggested order within the five

**7, 6, 5, 10, 11.** 7 is a one-line predicate with a live acceptance case. 6 adds the field 10 will
also touch. 5 is the extraction. 10 depends on 6 having established that adding to `Comparison` is
safe. 11 is last because it is the only one touching the store schema and should not be entangled with
the others when it lands.
