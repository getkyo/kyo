# bench-harness: implementation plan (v4)

The harness measures kernel changes and is meant to make a wrong conclusion hard to reach.

Three reviews got it here. v1 proposed building an investigator on signals that were broken. v2
inverted the order to repair-first but got the allocation phase, the A/A estimator and the
automation promise wrong. v3 fixed those and was then held-out-reviewed, which found the A/A
statistic unsound in both directions, most acceptance criteria degenerate-satisfiable, and two live
parser bugs. Every number below was re-derived locally, not accepted.

Phase 1 is implemented and green. The rest follows.

## The rule these bugs all defeated

QA asserted shape: "entries parsed", `nonEmpty`, `bytes > 0`. All passed while the morphism verdict
was inverted, deopts counted compiler-planted guards, and 118 sites resolved to raw ids.

Binding on every phase:

**Derive the oracle, never subtract it.** `oracles.sh` computes each fact from the artifact by means
the parser does not share. The planted-trap count is the cautionary case: taking it as
`total - runtime` gave 633, which is correct by arithmetic and useless as a check, and it concealed
a parser that saw only 512 of them.

**Match elements by shape, never by attribute order.** Four separate bugs, three of them introduced
while fixing the first, came from patterns that fixed an attribute ordering: `<call>` (dropped
4456), `<method>` (dropped 89), `<uncommon_trap>` (dropped 121), `<task>` (dropped all 5 OSR).

**Coverage is part of the assertion.** A parser states the fraction it consumed and fails when it
drops. Populations that partition must be asserted to partition: runtime + planted == total.

**Every criterion needs a must-fire twin.** A criterion satisfiable by a pipeline that always
abstains is not a criterion. Each below pairs a must-not-fire case with a must-fire one.

## Phase 1: repair the instruments (DONE, green)

Morphism reports only sites the JIT profiled a receiver for; absence is `Maybe.empty`, not `false`.
Deopts separate runtime events (6, run-scoped: all of them precede the first task) from planted
guards (633, both attribute forms). Inlining is per site with a denominator; a flip counts only when
unanimous. Mechanisms are suppressed on flat rows. `Run.jit` comes from the log, deleting the
`PrintInlining` run. C2 tasks (88), OSR tasks (5), `make_not_entrant` (89), `count='-1'` (27) and
bimorphic sites (3) are all parsed and pinned by oracles.

Corrections this phase forced on the plan's own text: `Nested::unnest` is inlined at **18 of 18**
sites, not 1 of 6; the 1-of-6 shape is `Safepoint::enter`. The stored runs cannot serve as the
per-site fixture at all, because `parseJit` folded verdicts before storing them.

Green: 34 BenchTest, 13 oracle, 13 bytecode.

## Phase 2: make a verdict answerable for its own uncertainty

1. Per-row error enters the verdict, with the combination rule **stated**. At `-f 1` the stored
   errors are ±3.8% and ±4.6% against a measured session drift of 3.95%; if the rule is additive the
   floor is ~8% and the harness resolves nothing smaller. State the floor in the report.
   - must-not-fire: a sub-error synthetic does not classify.
   - **must-fire**: a supra-threshold synthetic does classify, at the right sign and size.
2. Record run config from the JMH json, and **gate on leg equality**.
   - must-fire: a mismatched pair fails. must-not-fire: a matching pair compares.
3. Record the diffstat **over the restored paths**, and say which. The three scopes differ:
   8 files/304/221 (proto main), 11 files/509/221 (main+test, what `Cli.protoPaths` restores),
   14 files/983/224 (whole tree). v3 paired an 11-file insertion count with an 8-file file count.
   - must-not-fire: the 11-file bracket refuses a single-method mechanism.
   - **must-fire**: a one-file bracket permits one.
4. Add the JMH benchmark source to restored, hashed and markered paths.
5. Pin heap and collector (`-Xms=-Xmx`, fixed GC).

## Phase 3: the A/A null, respecified

v3's estimator was `V - mean(C1,C2)` with band `|C2 - C1|`. Simulated locally under its own model:

    pure noise:        per-row P(classified) = 0.455, P(zero of 15 rows) = 0.000113
    drift 3 sigma/leg: band median 6.00 sigma, a true +4 sigma regression detected 14% of the time

So it over-classifies without drift and goes blind with it, and its acceptance ("zero rows
classified") fails 99.99% of the time on a *correct* implementation. The predictable response is to
widen the band until it passes: reward-hacking the harness's own gate. Items 15 and 16 also
contradicted each other, the band being the very common mode the estimator cancels.

6. **Replicate**: legs run C V C V C. Two variant legs and three control legs.
7. **Estimate σ̂ from the replicates**, with at least 2 degrees of freedom. A one-sample absolute
   difference is not a threshold.
8. **Threshold** at `t(df, α) · σ̂ · √(1/n_V + 1/n_C)`, with **α stated** and **multiplicity over 15
   rows stated**. A family-wise "zero of 15" gate needs α ≈ 0.7% per row to pass 90% of the time;
   v3 never derived one.
9. **Detrend across legs and test the residual.** Linear cancellation is not enough: curvature in the
   drift biases the estimator, which local simulation confirms as a real effect (though not at the
   magnitude the review cited).
10. **Report the minimum detectable effect on every Flat row**, so "flat" means "flat to within X%"
    rather than "nothing was found".
11. `measureDrift` and the scalar band are deleted; the band comes from the replicates.
12. **Classfile equality guard** between control legs, specifying both open questions: hash the
    compiled classes **including the JMH generated classes** (item 4 puts the benchmark source in the
    restored set) and **excluding the zinc analysis store**; on failure **fail the session** rather
    than rebuilding, since a clean per-leg build costs minutes and perturbs the machine state the
    replication exists to hold still.

Acceptance: on the repaired pipeline the A/A classifies at the stated α and no more; and against a
**known-positive fixture** it classifies correctly (see Phase 8).

## Phase 4: bytecode (DONE, scoped)

`javap -c -p`, sizes exact (verified: every method ends in a one-byte return, so last offset + 1 is
the code length), diffed as added and removed instructions, with budget crossings named.

**Scoped deliberately**: diffing two shas that differ by 304 lines yields thousands of instruction
deltas that decide nothing. It runs for methods a Tier A falsifier names, or for one step of a
declared sha chain, never across a whole bracket.

## Phase 5: allocation attribution

v3 proposed `output=collapsed` aggregated by `frame[0]`, gated on conservation. Three corrections:

13. **Index from the leaf end.** Collapsed is FlameGraph folded format, root-first, so `frame[0]` is
    `java.lang.Thread.run` and the allocated class is *last*. v3 had it inverted.
14. **State the counter.** Collapsed values are sample counts unless the total counter is requested,
    while the flat table is bytes. As v3 specified it, conservation compared samples to bytes and
    could never pass. Verify the option reaches the agent through JMH before gating on it.
15. **Capture text and collapsed from one recording**, so the two aggregations describe the same
    samples. Otherwise conservation is bounded by run-to-run variance rather than parser correctness.
16. **Conservation is a coverage guard, not the acceptance.** It holds equally for a correct
    attribution and for one assigning every byte of a class to a single arbitrary method.
17. Take `-XX:+PrintEliminateAllocations`. Drop the unified JFR run.

Acceptance: a **planted row with one known allocation site** whose top allocator the parser names a
priori. Conservation additionally holds.

Known limitation, stated because it cannot be removed: with inlining, the attributed frame is where
the JIT placed the allocation, so an inlining change relocates it with no change in what allocated.
A per-method allocation "mechanism" is therefore confounded with the inlining mechanism, and the
report says so rather than implying independence.

## Phase 6: the investigator

18. **Tier A, automated: falsifiers that are JVM flags**, each one extra run of a configuration
    already issued.

    | hypothesis | falsifier |
    |---|---|
    | X stopped inlining, and that is the delta | rerun variant `-XX:CompileCommand=inline,...X::y` |
    | X exceeded a budget | sweep `-XX:FreqInlineSize` (hot sites) **and** `-XX:MaxInlineSize` (cold), naming which applies |
    | the win came from inlining, not layout | rerun **control** with `dontinline` |
    | the row rides scalar replacement | `-XX:-EliminateAllocations` |

19. **Every falsifier carries an efficacy gate.** `CompileCommand=inline` is a hint: HotSpot still
    refuses on `MaxInlineLevel`, node budget, or not-compilable. Without a gate, a harness whose
    flags silently never take effect **refutes every hypothesis and passes**. The isolation run
    re-collects the site's per-site verdict and proves the flag took before any verdict is recorded.
    Forcing a callee also changes the caller's remaining budget, so a confirm additionally requires
    that only X's verdict moved.
20. **Cut**: the megamorphism falsifier (12 profiled sites in 5093: the hypothesis is unstatable from
    these instruments) and the GC/ergonomics falsifier (Phase 2 pins heap and collector on every leg,
    so the condition cannot arise; to keep it, it would have to *unpin*).
21. **Tier B, not automatable**: source reversal presumes a partition absent from a two-sha input.
    Make it declarable: a bracket accepts a **chain** of shas and renders each isolated delta. With
    two shas the harness refuses any source-level mechanism and says the partition was never declared.

Acceptance, both directions: a planted **false** hypothesis must be refuted, and a planted **true**
one must be confirmed. v3 had only the refute direction, so an always-refutes pipeline passed both
halves of its own anti-unfalsifiability criterion.

## Phase 7: guardrails, dead code, QA that can fail

22. Recalibrate the steady-state guard: 1% of the window is 50 ms while the motivating run spent
    4.0 ms, ~12x too lax. Fix `QaEndToEnd.scala:74`, which passes whenever nothing is flagged.
23. Delete `MinCpuSamples`, `NoiseShare`, stranded doc comments, README worktree-guard drift.
24. **Delete `bench-harness-qa.md`.** Its acceptance criteria are now per-phase and keeping two
    documents in sync changes no decision. Its real findings (P2.3, the CLI) are carried below.
25. The red-tree gate must **actually refuse** a leg whose suite is red. Never once exercised.
26. The retry path must be exercised by a fixture returning a short row set; today it fires only
    after a zero exit and a successful parse, which the failure it exists for cannot produce, and it
    re-reads a fixed path that may hold a stale json.
27. QA phase 4, the CLI, never run.

## Phase 8: known-answer fixtures  —  **DONE**

Built from measured reality rather than synthetic data. The 15-row sweep of `dispatch$1` forced
inline against default is a known-answer pair whose truth was established by four separate
experiments: five rows improve 5 to 10%, `trailingMapsStayLinear` regresses 23.8%, fourteen rows are
byte-identical in allocation and that one gains 239,976 B/op, which a further run showed is exactly
the scalar replacement the enlarged compilation unit destroyed.

`BenchKnownAnswerTest` requires the harness to find the regression at roughly the right size, find
the wins, name allocation as the mechanism on the row where allocation moved **and on no other**, and
flag the win-and-loss shape. None of that is expressible as staying quiet, which is the point: every
other check in the suite is a null and is satisfied by silence.

## Phase 8 as originally specified

The gap under everything else: **every validation is a null**. A/A classifies nothing, sub-error
does not classify, planted-false is refuted. Nothing establishes that a known effect of known size is
detected, named and sized correctly, and a harness tuned only against nulls converges on abstaining.

28. **Known null across different shas.** One already exists: between the two QA shas the benchmark
    source differs by a single added comment. It exercises restore, recompile, the classfile guard
    and the whole ladder, which a same-sha A/A does not.
29. **Known positive.** A variant with a deliberate sized cost (an extra hot-path allocation, or a
    control leg under `-XX:-Inline`). The harness must classify it, size it within its own band, and
    name the right mechanism.

These are the positive twins for every criterion above.

## Cost

A session is 5 legs. Item 6's repetition is scoped to the two signals that can carry a claim
(per-site inlining, allocation) and to rows under investigation, not the whole class; unscoped it is
24 JMH invocations and roughly 90 minutes of evidence alone, and a guard too expensive to run
protects nothing. State the measured wall clock once calibrated.

## Rulings taken without the user

Reversible, recorded for morning: assembly automation dropped; unified JFR dropped; two Tier A
falsifiers cut; `PrintEliminateAllocations` taken; heap and collector pinned; Phase 4 scoped to named
methods; `bench-harness-qa.md` deleted; A/A moved from 3 legs to 5.

## What this still cannot do

Two shas do not isolate a variable; the sha chain makes it declarable, not inferable. Morphism speaks
for 12 sites and no parsing changes that. Allocation attribution is confounded with inlining. CPU
frequency and thermal state stay unmeasured, and replication plus detrending is a mitigation, not a
fix.
