# bench-harness: implementation plan (v3)

The harness measures kernel changes and is meant to make a wrong conclusion hard to reach.

v1 proposed building an investigator on the signals collected today. Review found three of four
broken; each was re-verified here (see `bench-harness-evidence.md`). v2 inverted the order to
repair-first. Review of v2 found its allocation phase unsound, its A/A estimator wrong, and its
automation promise undecidable as written. v3 fixes those.

The order is: repair the instruments, prove they can be wrong, then automate only what is
mechanically decidable.

## The QA rule these bugs all defeated

Every one shipped past a green suite. `QaParsers` asserted shape: "sites parsed", `nonEmpty`,
`bytes > 0`. All passed while the morphism verdict was inverted, the deopt count measured guards,
and the allocation parser discarded 109,799 of the lines in the file it was reading.

Binding on every phase:

**A parser is tested by independently deriving the same fact from the raw artifact and asserting
equality.** The oracle is a `grep`/`python` derivation recorded beside the assertion. "12 receiver
profiles" is checked against `grep -c "receiver='"`, never against `nonEmpty`.

**A rendered claim is tested against the tool output backing it.** If the report prints a mechanism,
the test confirms the mechanism's evidence exists.

**Coverage is part of the assertion.** A parser reading 664 of 5093 elements, or 4 lines of an 8 MB
file, states the fraction it consumed and fails when that fraction drops.

**An acceptance criterion must be able to fail.** Criteria are written so that a pipeline which
always abstains, or always confirms, is rejected.

## Phase 1: repair the instruments

1. **Morphism.** A missing `receiver` means "not a profiled virtual call", never "megamorphic".
   Report only sites carrying a receiver profile; label the rest unclassified. Fix the `Call` regex
   (664 of 5093 matched) to stop the silent drop. **State plainly that this adds no signal:** the
   4429 unmatched are the C1 `<call method instr>` form carrying neither `count` nor `receiver`.
   Morphism is a signal about 12 sites, correctly labelled, and cannot answer more than that.
2. **Deopts.** Separate runtime events (`thread=`, 6) from compiler-planted guards (`bci=`, 633).
   Parse `<make_not_entrant>` (89), `decompiles=`, `unstable_if_traps=`. **Distinguish OSR tasks**
   from standard ones; 19 OSR tasks exist, including the JMH stub loop recompiled 3+ times, and that
   loop is where the measured code runs.
3. **Inlining.** Never fold to one verdict per method. Per-site, C2-only, post-warmup. A flip renders
   as "1/6 sites, low call site frequency". Fix the `Method` regex, which requires both `bytes=` and
   `iicount=` and so drops 89 of 4466 declarations (the `unloaded='1'` form), producing the 118
   `method#N` entries the `kyo.` filter then hides.
4. **No mechanism on flat rows**, ever.
5. **CPU profile: raise to `-i 10`.** v2 dropped this with the JFR run. It is the only instrument
   that speaks to a `moved-unexplained` row, and it currently yields ~12 methods at ~90 samples.
   This is an instrument repair, not a nicety.
6. **Repeat each evidence step within a leg; keep only signals present in both.** A precondition for
   Phase 6, not a Phase 7 guardrail.
7. **Perturbation check.** Compare the profiled run's top-N `kyo.*` methods and row ordering against
   the unprofiled leg. Mark any attribution built on a profile whose hot set disagrees. The alloc
   profile measured 8.428 against 5.839 unprofiled, concentrated in the path it attributes.

Acceptance: re-parsing the captured log reproduces the fixed table (5093/12/664, 639/6/89,
4466/89). With mechanism-suppression **off**, per-site verdict sets for methods of identical
bytecode agree across the two stored captures, and `Nested::unnest` renders as "1/6 sites, low call
site frequency" in both. (v2's criterion was satisfiable by item 4 simply suppressing the output.)

## Phase 2: make a verdict answerable for its own uncertainty

8. Per-row error enters the verdict. Test behaviorally: a synthetic delta smaller than the combined
   error must not classify Faster or Regressed.
9. Record run config from the JMH json (`jdkVersion`, `vmName`, `jvmArgs`, `forks`, warmup and
   measurement iterations, blackhole mode), not harness constants. **And gate on it: the legs must
   agree.** Recording a field without comparing it is the defect that produced the original
   `lastCompileAt` debt.
10. Record the source diff and diffstat between shas. Gate item 7's rule: with the 8-file QA bracket,
    the report must state that no single-method mechanism is admissible.
11. Add the JMH benchmark source to restored, hashed and markered paths.
12. **Pin heap and collector** (`-Xms=-Xmx`, fixed GC) via `-jvmArgsAppend`. One flag, removes a real
    variance source: the row allocates ~32 KB/op at ~6 us/op, roughly 5 GB/s, with G1 frames visible.
    v2 called this unmitigable; it is one line.

Acceptance: the sub-error synthetic does not classify; the 8-file bracket refuses a single-method
mechanism; a leg-config mismatch fails the comparison.

## Phase 3: the interleaved A/A null

The centerpiece, respecified with the right estimator.

13. Legs run **control, variant, control**.
14. **Compare V against the per-row mean of C1 and C2.** Under linear drift this estimates the
    control at the time V ran and cancels ordering bias to first order. Comparing V against C1 alone
    makes the null span two intervals while the A/B spans one, so the null overstates and every
    monotone machine trend lands in the delta with the same sign.
15. **The null is per-row `|C2 - C1|`**, giving 15 per-row bands for free. This retires the
    single-scalar band applied to 15 heterogeneous rows.
16. **Decompose, do not threshold.** Report `median(C2-C1)` as common-mode (machine state, which the
    mean-of-controls cancels) and per-row `|(C2-C1) - median|` as noise. Common-mode 3% with 0.5%
    residual is a usable bracket; a 3% residual on one row suppresses that row only; large residuals
    on most rows fail the session.
17. **`measureDrift` and the scalar band are deleted.** The session band comes from the null. Two
    competing noise estimates means the report keeps using the worse one.
18. **Classfile equality guard.** C1 and C2 are separated by two `git restore`s and two incremental
    recompiles, which are not guaranteed to reproduce C1's classfiles. Hash the compiled classes for
    both control legs and require byte equality, or the null measures the build system. This also
    catches stale-compilation classes of bug for free.

Acceptance: the A/A on the repaired pipeline names **zero** mechanisms and classifies **zero** rows
Faster or Regressed; and against the stored pre-repair fixture, the null flags the old inlining
diff. The null's numbers are the band the report uses.

## Phase 4: bytecode

19. `javap -c -p`, size plus listing, diffed between legs.

Acceptance: reports **added and removed instructions**, not both listings side by side.

## Phase 5: allocation attribution, done soundly

v2 proposed parsing async-profiler's stack tree. Measured, that is unsound: the capture holds 200
trace sections covering **14.46%** of allocation (2,713,185,225 of 19,017,986,638 bytes), largest
trace 0.09%, smallest 0.07%, depths 23 to 1046, so one logical site fragments across hundreds of
stacks by recursion depth. Re-aggregating collapses to 4 (class, allocator) pairs recovering ~14% of
each class's bytes. Enough to rank; useless for a between-leg per-method delta, because the
truncation point itself moves between runs.

20. Use **`output=collapsed`**: folded stacks, one line per stack, untruncated, machine-defined.
    Aggregate by frame[0] (allocated class) and frame[1] (allocating method).
21. Parse the **file** async-profiler writes, not sbt stdout with its `[info]` prefixes and ANSI.
22. Take `-XX:+PrintEliminateAllocations` (separates eliminated from cheap; this kernel's design
    arguments turn on it). Drop the unified JFR run.

Acceptance: **conservation.** Per-method totals summed by allocated class reproduce the flat table's
per-class bytes within a stated tolerance. That fails loudly on truncation, regex rot and format
change, which naming a method does not.

## Phase 6: the investigator, split by what is actually decidable

23. **Tier A, automated now: mechanisms whose falsifier is a JVM flag.** Derived from the hypothesis
    alone, needing nothing from the diff, each one extra run of a configuration already issued:

    | hypothesis | falsifier | reads |
    |---|---|---|
    | X stopped inlining, and that is the delta | rerun variant `-XX:CompileCommand=inline,...X::y` | delta gone: confirmed |
    | X exceeded the budget | rerun variant `-XX:FreqInlineSize=<bytes+1>`, then sweep | the byte count is the threshold or it is not |
    | the win came from inlining, not layout | rerun **control** `-XX:CompileCommand=dontinline,...` | control should lose the win |
    | the row rides scalar replacement | `-XX:-EliminateAllocations` | disappears or does not |
    | the site is megamorphic | `-XX:TypeProfileWidth`, `-XX:-UseTypeSpeculation` | speculation is load-bearing or not |
    | the delta is GC or ergonomics | pin heap and collector | disappears or does not |

24. **Tier B, not automatable: source-level reversal.** "Reverse the one change" presumes a partition
    that does not exist in a two-sha input (509 insertions, 221 deletions, 8 files). Hunk bisection is
    not a workaround: most subsets do not compile, the space is exponential, and the meaningful
    partition is semantic, not syntactic. Instead make the partition **declarable**: a bracket accepts
    a **chain** of shas (base, step1, step2), measures all legs in one interleaved session, and renders
    each isolated delta. With only two shas the harness refuses to state any source-level mechanism,
    emits the Tier A falsifiers, and says the partition was never declared.
25. A mechanism is reported confirmed only when its falsifier ran and failed to kill it.

Acceptance: a **planted false hypothesis** ("X stopped inlining" asserted on a pair where X's
per-site verdicts and bytecode are identical) must be refuted by its isolation run, and at least one
real refutation is recorded before the loop is trusted. A pipeline whose falsifier always confirms
fails this.

## Phase 7: guardrails, honesty, QA that can fail

26. Dossiers list what was checked and abstain on ambiguous evidence.
27. **Recalibrate the steady-state guard.** `CompilingShareLimit = 1.0` means 50 ms of a 5000 ms
    window while the run that motivated raising warmup spent **4.0 ms** in-window: ~12x too lax to
    have ever fired. Calibrate against measured values, and fix `QaEndToEnd.scala:74`, which passes
    whenever nothing is flagged.
28. Delete the dead and the wrong: `MinCpuSamples` and `NoiseShare` (1 reference each, their own
    definitions), stranded doc comments, the JIT-cost table's meaningless rows, README drift about
    the worktree guard.
29. QA phase 4 (the CLI, never run). Reconcile the QA doc with the code: P1.2 tests `parseJit` on
    output Phase 1 makes irrelevant, P2.3 is unimplemented, and QaGuards' numbering no longer matches.

Acceptance: named failing-on-purpose cases, not a general claim. **The red-tree gate must actually
refuse** a leg whose suite is red (QA P2.3, never implemented, so the gate protecting against
measuring a red tree has never refused anything). **The retry path must be exercised** by a fixture
returning a short row set; today it fires only after a zero exit and a successful parse, which the
documented failure cannot produce, and it re-reads a fixed path that may hold a stale json.

## Rulings taken without the user

Reversible defaults, recorded for morning:

- Assembly automation: **dropped** (no decision in this project's history turned on it).
- Unified JFR: **dropped** in favor of `output=collapsed`.
- `PrintEliminateAllocations`: **taken**.
- Heap and collector: **pinned**.
- Dossier scope: flagged rows only, since the A/A null now runs on every comparison.

## What this still cannot do

Two shas do not isolate a variable. Phase 2's diffstat makes that visible, Phase 6's sha chain makes
it declarable, and neither infers it. CPU frequency and thermal state stay unmeasured; the
interleaved A/A with common-mode decomposition is the mitigation, not a fix. Morphism remains a
signal about 12 call sites and no amount of parsing changes that.
