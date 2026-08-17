# Work ledger

Single source of truth for what is done, what is open, and what is currently being worked on. Read
this first on every wake-up; update it after every step, before reporting anything.

The other documents are subordinate to this one: `bench-harness-plan.md` is the design,
`review-findings.md` and `tool-defects.md` are per-stream ledgers, `bench-results/*/RESULT.md` are
measurements, `overnight-procedure.md` is the operating rules. If any of them disagrees with this
file about state, this file is wrong and gets fixed.

## The rule that governs all of it

Every measurement, comparison and verdict goes through the harness. Python and grep are oracles only:
they expose facts to check output the tool already produced. Computing a delta, threshold or verdict
by hand is the failure this project exists to prevent, and it happened for four experiments before
being caught.

## Now

**Validating the tool against the manual work.** Procedure: three-way comparison of raw data, tool
verdict, and my recorded claim, per experiment. The raw data is the arbiter; two of three agreeing
settles nothing.

| exp | ingested | tool verdict | 3-way table | disagreements discharged |
|---|---|---|---|---|
| exp3 sweep | yes | yes | partial | **no** |
| exp1 budget probes | no | no | no | no |
| exp4 escape analysis | no | no | no | no |
| exp5 tier splits | no | no | no | no |

### Open findings from this stream

1. **`Bench.compare` has no floor at the legs' own error.** Classified −9.8% a win on a row whose
   control leg reports ±15.2%. The floor exists only in `compareReplicated`. *Classification: I was
   right, tool wrong.* Obliges: the floor on single-pair comparison, plus a test using the real
   numbers. **Open.**
2. **No steady-state detection from the iteration series.** The same row's control iterations are
   `[86.4, 80.8, 81.4, 78.5, 78.6]`: a warmup ramp the harness cannot see, because `stillCompiling`
   reads `compiler.time.profiled`, which ingested runs do not carry. *Classification: tool cannot
   express it.* Obliges: detection from `rawData`. **Open.**
3. **`TOOL-VERDICT.md` claims "the tool is right each time".** False, per finding 1. *Obliges:
   correction, error left visible.* **Open.**

## Done

- Plan through three review rounds; v4 is current.
- Phase 1 instruments, Phase 3 A/A null and bracket orchestration, Phase 4 bytecode, Phase 8
  known-answer fixture, the verdict statistic, efficacy gate, budget-proximity ranking, JVM args per
  leg, drift off the session path, the ingest path.
- All 12 implementation-review findings closed.
- 153 checks green across five suites.
- Experiment data force-added to git; `.gitignore` had a global `*.json` hiding all of it.

## Open, beyond the validation stream

- **Phases 5, 6, 7**: allocation attribution via `output=collapsed`; the investigator's rule table;
  steady-state recalibration and the CLI QA that has never run.
- **The DIS-1 tier split is unresolved.** Variants 1 and 2 measured and both regress the handler rows
  22-35%; variant 3 (index found once, so a fast-path miss does not pay for a second stack scan) was
  edited and compiled with 126 tests green, but **its measurement was interrupted and never ran**.
  `bench-results/exp5/tiered3-1.json` is 0 bytes.
- **The sweep was never replicated**: one leg per configuration.
- **Ten tool defects** in `tool-defects.md`, of which two are now fixed (efficacy gate, budget
  ranking) and one is being fixed (ingest). Seven open.

## Standing constraints

No kernel source landed. Candidates measured in the detached throwaway worktree only. No `inline`
without approval. No PR interaction. Commits under the owner's identity, no attribution.
