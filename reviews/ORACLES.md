# Oracles: the raw data behind v3's load-bearing numbers

v3 quoted three figures it inherited from a held-out review rather than deriving. Re-derived here from
the artifacts. **One confirmed exactly, two wrong.** Raw data is the arbiter, so these supersede both
the review's numbers and my own earlier writeups.

These are oracle extractions, not verdicts: they expose raw facts so the tool's output can be checked,
and they are the ground truth the corresponding fixes get tested against.

## 1. `InlineSites.bytes` population: CONFIRMED, exactly

    qa-control-36b41336fb  entries= 944  bytes>0= 887  (94%)
    qa-variant-0f9b4b69f7  entries= 958  bytes>0= 904  (94%)

Both 94%, matching the review. **Cutting v2's item 7 is correct**: every Full leg already records
per-method sizes from HotSpot's own `bytes=` attribute, so capturing them again via javap is
redundant, and `Bytecode.verifyAgainst` exists precisely to treat javap as the side needing
verification.

## 2. RETRACTED. My "correction" was wrong; the original 8.9% was right

**This section originally claimed the review's 8.9% was wrong and 2.2% was right. That is backwards.**
A third held-out reviewer caught it and I re-derived it myself:

    all tiers : 6329 verdicts
    C2 (level>=4, the population Run.jit is actually built from): 1117 verdicts in 109 tasks
       ProtoKernelBench's own code    625   56.0%
       JDK/JMH                        236   21.1%
       kyo kernel                     157   14.1%
       row-own jmhStub                 99    8.9%
       -> row-attributable             724   64.8%

`LogCompilation.inlining` filters `p.tasks.filter(_.level >= 4)` (`LogCompilation.scala:272`), and the
rationale twelve lines above says why: "of 1969 refusals 1929 come from C1 tier 3 and 40 from C2 ...
Reporting C1 verdicts as a method's inlining behaviour would point every optimization at code the
measured score never executes."

Checked directly: **837 of 946 tasks carry `level=`, every one of them 1, 2 or 3. The 109 with no
`level` attribute default to 4** (`LogCompilation.scala:167`), and those 109 are exactly the C2 tasks
holding 1,117 verdicts. So 1,117 was never "an unstated subset"; it is the population `Run.jit` holds.
My 2.2% counted 5,212 C1 verdicts the harness deliberately discards, describing a population it never
stores and never renders.

**And the number that matters is neither.** v3 said "the other 91% are shared methods and belong to no
row". On the C2 population, **64.8% is row-attributable** (99 stub + 625 the benchmark's own code), or
74.5% on the further `kyo.`-callee-filtered population the reviewer used. The 602-to-625 verdicts under
`run$56`, `run$57..67` and `continuationBodiesFuse` are the measured row's own body and its lambdas.
Both prior documents, mine included, mislabelled them "shared".

**Caveat that survives all of it:** this log is a *single-row* leg, where attribution is trivial. Item 2
is about a *whole-class* leg, and per defect 30 no whole-class Full log exists. The whole-class ratio is
**unmeasured by anyone**, and any number quoted for it comes from the case where the question does not
arise.

### The original section, kept so the error is legible

## 2 (superseded). Row-to-method attribution: mechanism CONFIRMED, fraction WRONG

The review said **99 of 1,117 C2 verdicts (8.9%)** were made compiling the row's own `jmhStub`. On
`bench-results/exp1/logc-new-default.xml`, splitting on `<task ` boundaries (the blocks are not closed
with `</task>`, which is what made my first attempt find 8 verdicts instead of thousands):

    inline verdicts inside task blocks: 6329
      JMH/JDK-rooted                4200   66.4%
      kyo.-rooted (shared)          1988   31.4%
      row-own jmhStub/jmhTest        141    2.2%

**141 of 6,329, or 2.2%**, not 99 of 1,117. Same direction, different denominator: the review filtered
to some C2/`kyo.`-prefixed subset it did not state. The fraction is **filter-dependent and should not
be quoted as a bare number.**

What is not filter-dependent, and is the actual finding:

- The row key **is** present, on the `<task>` element:
  `ProtoKernelBench_continuationBodiesFuse_jmhTest continuationBodiesFuse_avgt_jmhStub`.
- The harness **already parses it** into `Task.method` (`LogCompilation.scala:170`).
- One `flatMap` at `LogCompilation.scala:272` discards it.
- A non-trivial slice of verdicts is attributable to the row's own OSR stub, which the harness itself
  calls "where the measured code actually runs".

So v2's "hard limit" claim is still false and item 2 still stands. Only the number changes.

This log names exactly **one** benchmark row (`continuationBodiesFuse`), consistent with defect 30 but
not proof of it: it was a single-row invocation, so it cannot distinguish per-fork truncation from
having only ever had one row.

## 3. `KnownNoise` understatement: WRONG, and worse than recorded

Three documents said the true non-kernel share is **70%** against the 29% the tool prints. From
`qa-artifacts/qa-cpu.txt` (28 frames, percentages summing to 100.01):

    KnownNoise matches                  :  29.07%   <- what the tool prints
    ProtoKernelBench.* (benchmark code) :  48.37%   <- missed entirely
    kyo.kernel.proto.* (kernel-owned)   :  16.04%   <- the ONLY movable part
    JDK / native / other                :   6.53%
    => truly not movable by a kernel change: 83.97%

`KnownNoise` is `Seq("BoxesRunTime", "java.lang.Integer", "jmh_generated")`, and on this profile it
matches `scala.runtime.BoxesRunTime.boxToInteger` and **nothing else**. The benchmark's own generated
code, `ProtoKernelBench.loop$9` (17.14%), `run$39` (13.23%), `ask` (11.06%), `anon$95.<init>` (6.72%),
contains no `jmh_generated` in its frame names and is missed in full.

The sentence the tool prints is "% of sampled time is in classes no kernel change can move". The
answer is **83.97%** and it prints **29.07%**: understated by **54.9 points**, not 41, and in the
direction that flatters the kernel. Only **16.04%** of this profile is kernel-owned at all.

**Item 4's acceptance is this table**, and its fixture is this capture rather than authored frames.

## 4. The C4 table: TOOL RIGHT, MY DOCUMENT WRONG

`bench-results/c4/RESULT.md` showed `0.005853 → 0.007364` beside **+26.2%**. Recomputing from those two
numbers gives **+25.82%**, so the document contradicted itself.

Adjudicated by re-running the tool over the stored legs rather than by hand:

    | 🔴 | evalFixedOverhead | 0.005818 ± 0.000037 | 0.007342 ± 0.000037 | +26.2% | ±5.4% |

The tool prints the **arm means** (3 controls, 2 variants) and +26.2% follows from them exactly. The
document had transcribed **leg one's** scores beside the mean-based percentage. `Bench.scala:736-739`
documents fixing precisely this on the tool's side; I reintroduced it by hand in the writeup.

**Classification: tool-right / document-wrong. Discharged** by correcting the table to the tool's own
output. This one matters more than the others because that document is the basis of the C4 ruling.

## 5. Defect 30's evidence status, stated honestly

- **Mechanism: proven.** Two JVMs sharing one `-XX:LogFile` leave one `<hotspot_log>` header and one
  pid, and the survivor carries only the second JVM's content. HotSpot truncates on open.
- **End-to-end on a multi-row leg: NOT yet confirmed.** All five compilation logs in `bench-results/`
  contain exactly one row (`continuationBodiesFuse`), and all were single-row invocations, so none can
  distinguish per-fork truncation from having only ever had one row.
- **Open task, not a limitation:** one two-row `--evidence full` invocation settles it. Deferred only
  because a measurement must not share the machine with a running agent.

## 6. The ladder line fires on evidence it knows is absent

Re-running the C4 comparison reproduced v3's headline defect in a **sharper** form than v3 states. On a
**Timing** leg, whose `jit` is empty by construction, the report still prints:

    ⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:
      - evalFixedOverhead: check allocation sites and the inlining log before proposing a mechanism

It is not merely withholding evidence it holds. It is directing the operator to an inlining log **this
run provably does not contain**. Filed as defect 43; item 2's acceptance must cover the Timing case.

## 6b. Items 5 and 7 also reproduced live, by the same single command

Re-running the C4 comparison to adjudicate finding 4 confirmed three separate plan items at once,
which is worth recording because it cost one command and no measurement.

**Item 5, the A/A null is absent from `compare`.** The output opens with
`Replicated over 3 control and 2 variant leg(s)` and goes straight to the table. **No A/A null line at
all.** The same five legs read through `bracket` print `A/A null: clean, ... (N rows)`. So re-reading a
stored bracket reproduces its verdict while silently dropping the strongest refusal the tool has, which
is exactly what item 5 says and what `Bench.scala` itself calls "the only check here that can fail in
the direction that matters".

**Item 7, the `-f N` note contradicts the report's own footer.** In one render:

    header: JMH -f 1, -f 1 is diagnostic and not a claim, all 15 rows, timing only, ...
    footer: ... (alpha 0.00333 after correcting for 15 rows, df 3).

It disclaims the result as diagnostic-only *because of the fork count*, and three lines later reports a
Bonferroni-corrected threshold at **df 3**, which it earned from five legs. Five legs at `-f 1` are five
independent JVMs; the claim rests on replication, not on forks. Keying the note on `resolution.df == 0`
is the fix, and this render is its acceptance case.

**Defect 43** (finding 6 above) came from the same output.

## 7. Corpus sweep: every other recorded delta is consistent

Having found one table that contradicted itself, I swept the rest rather than assuming it was isolated.
Every `RESULT.md` line carrying a control and variant score with a printed delta, recomputed:

    bracket1   28.68  -> 26.72   printed -6.80%  recomputes -6.83%  OK
    bracket1   303.17 -> 331.83  printed +9.50%  recomputes +9.45%  OK
    exp2       27.455 -> 25.566  printed -6.88%  recomputes -6.88%  OK
    exp2       implied baseline 26.260, forced-inline vs baseline -2.64% (printed -2.65%)  OK

**One inconsistency in the corpus, the C4 table, now corrected.** The rest hold. `exp4`'s table prints
scores without deltas and `exp6`'s figures are JMH-reported per-leg errors rather than derived
percentages, so neither has a delta to check.

## The pattern

Six numbers I have published in this campaign have been wrong: a one-arm forecast called
"systematically optimistic" when it errs both ways; "the two largest deltas are flat" when it was
three; "three of eight selectors wired" when it is one; "70%" when it is 84%, itself now contested; the
C4 table's scores, which were leg one's beside a mean-based percentage; and the 2.2% above.

**The sixth is the worst, and it is a different kind.** The first five were wrong the first time. The
sixth was me taking a *correct* number and "correcting" it into a wrong one, with a re-derivation I
believed was more careful than the source's. Going back to the raw data is necessary and it is not
sufficient: I went back to the raw data and still got it wrong, because I re-derived over a population
the tool does not use and never checked my denominator against the code that builds it.

The check that would have caught it is the one the campaign already knows: **read the code that
produces the number, not just the artifact it came from.** `LogCompilation.scala:272` was eleven lines
from something I had already quoted twice.
