# Where the tool left me guessing

Recorded while running Experiment 1, which is the point of QA-ing the harness by using it. Two
things were under test: the candidate, and the tool. The tool is the one that matters, and every
moment I reached past it for a shell script or a python one-liner is a defect in it.

The experiment succeeded. The tool contributed almost nothing to that success, which is the finding.

## What I had to do by hand

**1. Run the experiment at all.** The harness has no flag-probe path. I wrote a 25-line shell script
to restore two designs, issue four JMH invocations with `-jvmArgsAppend`, and collect the json.
`runLeg` cannot take extra JVM arguments, so even with the harness running I could not have asked it
for this. *This is Phase 6 Tier A, unimplemented: the entire point of that phase is that a falsifier
which is a JVM flag can be derived from the hypothesis and run automatically.*

**2. The efficacy gate.** The protocol demands proving a flag took effect before reading its result.
The tool has no function for it. I wrote 40 lines of python to parse two compilation logs, group C2
inline verdicts by method, and diff them. `LogCompilation` already produces exactly this structure;
what is missing is a `diff(a, b)` over two `Parsed` values. Without that, the most important guard in
the protocol is a manual step, which means in practice it is an optional one.

**3. Finding the 379-byte method.** The mechanism was `run$56` at 379 bytes refused for
`hot method too big`. The tool holds every part of that (per-site verdicts, byte sizes, reasons) and
would not have surfaced it, because nothing ranks refusals by whether they sit near a budget
threshold. `Bytecode.Change.crossedBudget` does exactly this reasoning for a size *change* between
legs; the same question about an absolute size against the budget is not asked anywhere.

**4. Comparing two configurations of one design.** Every comparison the harness models is
design-against-design. Configuration-against-configuration, the same sources under two JVM settings,
has no representation at all, though it is the shape of every Tier A falsifier.

**5. Replication.** I ran one configuration per cell. The harness's own statistic needs C V C V C and
its runner still produces one leg per invocation, so the thresholds and the minimum detectable effect
I built tonight could not be applied to the one measurement that was actually taken. The numbers are
reported as approximate for exactly this reason.

## What the tool did contribute

The discipline, which is not nothing. Heap and collector pinned because the plan says to. The
efficacy gate run *before* the numbers were read, because the protocol says to, and it is the step
that turned a suggestive result into a named mechanism. The reading rules written down in advance,
so the interpretation was fixed before the data existed. And the honest limits section, which exists
because the plan requires every result to state what it does not establish.

That is the harness working as a procedure while contributing nothing as a program.

## Ranked fixes

1. **`LogCompilation.diff`** over two `Parsed` values, returning per-method verdict changes. Turns the
   efficacy gate from 40 lines of throwaway python into a call. Cheapest and highest value.
2. **`runLeg` accepting extra JVM arguments**, and a configuration-pair comparison alongside the
   design-pair one. Without these Tier A cannot exist.
3. **Rank refusals by proximity to a budget.** A method refused `hot method too big` at 379 bytes
   against a 325 default is a finding; the same refusal at 4000 bytes is furniture.
4. **Leg orchestration for C V C V C**, so the statistic that is implemented and tested can actually
   be applied to a measurement.

Nothing here is a surprise: items 1, 2 and 4 are Phases 6 and 3 of the plan, unimplemented. The value
of running the experiment first is knowing which order to build them in, and that item 3 was not in
the plan at all.

## Added after experiment 2

**6. A malformed JVM flag produces a completely normal-looking run.** `-XX:CompileCommand=inline,...`
on a Scala method name cannot survive the shell and sbt quoting chain: two runs were spent before the
log showed `CompileCommand: An error occurred during parsing`, and both had produced plausible
output. `CompileCommandFile` avoids the escaping entirely. The harness must, when it issues a compile
command, assert that the JVM parsed it, and must prefer the file form. Without that, Phase 6 Tier A
would silently test nothing while reporting refutations.

**7. The efficacy gate earned its place twice, in opposite directions.** On the budget probes it
showed the flag never achieved the intended state (`dispatch$1` refused at 600 and at 700, for two
different reasons), which made those runs inconclusive rather than evidence against the hypothesis.
On the forced-inline run it showed the change took exactly, which is what licensed reading the score.
A harness without it would have concluded the opposite of the truth from the same four numbers.

## Added after the first real bracket

**8. The harness reports its resolution only after spending the session.** The A/A at five legs and
one fork resolves to ±5.77%, which means it cannot see the +4.55% regression this campaign was built
around. That was discovered by running the session and reading the footer. A tool whose purpose is to
prevent wasted conclusions should say, before the first leg, what effect size the requested
configuration can detect, and refuse or warn when the target effect is below it. Everything needed is
already there: the legs' own errors are in the JMH json of any prior run on the same rows.

**9. Synthetic validation agrees with the code's blind spots by construction.** The replicate
statistic had 31 unit tests, a simulation-backed review, and three rounds of plan review, and none of
them found a missing `max` between the threshold and the legs' own error. Five legs of identical
sources on a real machine found it in one run. Every fixture in this suite was generated by the same
understanding that wrote the code under it; the A/A is the only check whose input the author does not
control. It should run on every session, not on request.


## Status, reconciled

| # | defect | state |
|---|---|---|
| 1 | no `LogCompilation.diff`, so the efficacy gate was 40 lines of throwaway python | **fixed** |
| 2 | `runLeg` could not take JVM args, so no configuration probe was expressible | **fixed** |
| 3 | refusals not ranked by proximity to a budget | **fixed** |
| 4 | no C V C V C orchestration | **fixed** |
| 5 | (superseded by 4) | - |
| 6 | a malformed `CompileCommand` produces a normal-looking run | open |
| 7 | the efficacy gate earned its place in both directions | observation, not a defect |
| 8 | resolution reported only after spending the session | open |
| 9 | synthetic validation agrees with the code's blind spots | open, mitigated by the A/A |
| 10 | the report showed leg one beside a mean-based delta | **fixed** |
| 11 | **no ingest path**: the tool could only compare runs it produced, which is what drove four experiments' verdicts into hand-written python | **fixed** |
| 12 | no floor at the legs' own error in single-pair comparison | **fixed** |
| 13 | no steady-state detection from the iteration series | **fixed**, and now a blocker |

Seven fixed, three open, one an observation. The three open ones share a shape: the tool knows
something after the fact that it could have said beforehand, or fails to assert that an instruction
it issued was obeyed.
