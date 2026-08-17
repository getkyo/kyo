# Overnight status

`bench-harness-plan.md` (v4) is the design, `optimization-plan.md` the candidates,
`bench-results/*/RESULT.md` the measurements, `tool-defects.md` what the tool failed to do for me.

## The headline

**The `continuationBodiesFuse` regression is diagnosed and measured.** It was open across sessions
with allocation, inlining and megamorphism all previously "ruled out".

The cause is `kyo.kernel.proto.Eval$::dispatch$1`, 607 bytes, refused by HotSpot as
`hot method too big`. Forcing it to inline:

    current design, default           27.455 +- 0.336 us/op   (+4.55% vs old design)
    current design, dispatch$1 forced 25.566 +- 0.190         (-6.88%, and -2.65% vs old)

Efficacy gate passed on the target and nothing else: `0 inlined / 2 refused` becomes
`2 inlined / 0 refused`. `ask.map{...}` used to mint a `Suspend`, expanded into the drive loop, and
now mints a `SuspendWith` whose delivery is that separate 607-byte method. On this row alone the
continuation body is itself too big to repay the frame through downstream inlining.

This confirms candidate DIS-1 by isolation, and says the current design is **better** than the old
one once the frame is gone.

## Done and green

Plan finalized through three review rounds; each found real defects and the third proved the plan's
own centerpiece statistic unsound. Phase 1 (instruments), Phase 4 (bytecode), the verdict statistic,
the A/A null, the efficacy gate and budget-proximity ranking are implemented and committed.

**92 checks green** across four suites (BenchTest, LogCompilationTest oracle-based, BytecodeTest,
StatsTest). The build works: `Jmh/compile` succeeds in 16s.

## Bugs found and fixed in the harness

Ten, nearly all written tonight. Four shared one root cause: matching XML elements by attribute
*order* rather than element *shape*, which I reintroduced three times while fixing it once. The
mutation-testing review found three more that the tests could not catch:

- `ParseCoverage` counted `seen` inside the parse loop, so a 12.5% parse reported 100% coverage
- `tCritical` returned Infinity for df 7 and 9; a 100% regression classified Flat under a green
  all-clear
- `Bytecode` merged static initializers into the preceding method, reporting 11B against an actual 5B

I built three guards against silent failure tonight and two of them could not themselves fail. **A
check that has never failed is a claim, not a check.**

## One error of mine, caught and corrected

My first Experiment 1 writeup blamed `run$56`, the benchmark's 379-byte continuation body. That was
measured but never compared: it is refused in *both* designs, so it cannot separate them. I had
compared the current design against itself at two budgets and read a design difference out of data
that never contained one. Corrected in `bench-results/exp1/RESULT.md`, with the error left visible.

## Now also done

- **Phase 3 orchestration.** `bracketPlan` and `bracket` run C V C V C, so the replication statistic
  and the A/A null finally have legs to consume. The ordering is a pure function and tested as one.
- **Phase 8 known-answer fixture**, built from the sweep rather than synthetic data. It requires the
  harness to find the regression, find the wins, and name allocation on exactly the row where
  allocation moved. None of that can be satisfied by staying silent.
- **All twelve review findings closed.**
- **A leg carries JVM arguments**, and heap and collector are pinned on every leg. Without this a
  configuration probe was inexpressible, which is why tonight's two diagnostic experiments ran from a
  shell script the harness knew nothing about.

**150 checks green across five suites.**

## Not done

- **Phases 5, 6, 7.** Allocation attribution via `output=collapsed`; the investigator's automatic
  rule table (its two most valuable pieces, the efficacy gate and budget ranking, are built);
  guardrails including the steady-state recalibration.
- **No bracket has actually been run.** The orchestration is implemented and its ordering tested, but
  a five-leg session takes roughly half an hour and none was run tonight, so the replication
  statistic still has never consumed real legs.
- **The actual DIS-1 fix is unmeasured.** A compile command is a diagnostic; making the method small
  enough to inline unaided is the candidate's real content.
- **A second sweep leg.** The 15-row comparison ran one leg per configuration, so its five wins and
  one loss sit outside their combined errors but were never replicated.

## Standing constraints honoured

No kernel source edited or landed. Every candidate measured in the detached throwaway worktree. No
`inline` added. No PR touched. Every commit under your identity, no attribution.
