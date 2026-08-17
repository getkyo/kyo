# bench-harness: implementation plan

The harness measures kernel changes and is meant to make a wrong conclusion hard to reach. It
already enforces the measurement protocol; this plan takes it from a collector that reports
anomalies to an investigator that arrives with the explanation already gathered.

## Why the change in shape

The operator (me) has a demonstrated failure mode: skipping rungs of the evidence ladder,
asserting mechanisms that were never measured, and not remembering to run the right tool at
the right moment. Three mechanism claims in one session were contradicted by the next
measurement. A tool that flags a symptom and expects a human follow-up inherits that failure.
So the design goal is a complete picture out of the box, with the expensive evidence following
the symptom automatically rather than waiting to be requested.

## Current state

Working and verified against live output: session grouping with measured drift, marker
verification per leg, `git restore --worktree` design flips, detached-worktree and clean-tree
guards, JMH json parsing with `gc.alloc.rate.norm` and `compiler.time.*`, compilation-log
parsing (deopts, morphism, inlining), drift-band classification, subset and evidence stamps,
steady-state detection, store round trip, and a 21-check self test plus live QA phases 1, 1.5,
2 and 3.

Known defects and debts carried into this plan: the `PrintInlining` run is dead weight since
the compilation log supersedes it; drift runs omit `-wi`/`-i` and so calibrate under different
warmup than the legs they judge; `lastCompileAt` is displayed but never checked against the
measurement window; evidence runs use `-i 1`, starving the CPU profile to ~300 samples; and
QA phase 4 (the CLI surface) has never run.

## Target run inventory

Per session, 3 runs, drift calibration on one row:

    -f 1 -wi 10 -i 5 -r 1s -w 1s -rf json -rff <file>

Per leg, 2 runs plus a non-JMH correctness gate:

    sbt testOnly kyo.kernel.proto.*
    -f N -wi 10 -i 5 -r 1s -w 1s -prof gc -prof comp -rf json -rff <file>
    -f 1 -wi 10 -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=<file>"

Per flagged row, 1 run, scoped to that row:

    -f 1 -wi 10 -i 10 -prof "async:libPath=<dylib>;event=cpu;alloc=512k;lock=10ms;output=jfr;dir=<dir>"

Per investigated method, 1 run, scoped to that method:

    -f 1 -wi 10 -i 1 -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=print,<Class>::<method>"

Bytecode needs no run: `javap -c -p` on the compiled class, ~0.1s.

## Phases

### Phase A: cut the default cost

1. Delete the `PrintInlining` run and its parser; inlining comes from the compilation log,
   which was measured as strictly more complete (3011 successes, 1865 failures, structured,
   no thread interleaving).
2. Give the drift runs the same `-wi 10 -i 5 -r 1s -w 1s` as the legs, so the noise floor
   describes the same JVM state as the measurements it classifies.
3. Verify a leg is 2 JMH runs and the suite still passes.

Acceptance: a full-evidence leg costs 2 runs; QA phases 1.5 and 3 pass unchanged.

### Phase B: unified profiling as tier 2

4. Replace the separate alloc and itimer runs with one unified JFR run
   (`event=cpu;alloc=512k;lock=10ms;output=jfr`), verified working: one invocation produced
   307 `jdk.ExecutionSample` and 22187 `jdk.ObjectAllocationInNewTLAB` events.
5. Parse it with `jfr print --json --events jdk.ExecutionSample,jdk.ObjectAllocationInNewTLAB`,
   decoding with kyo's `Json`. Allocation events carry full stack traces, so allocation moves
   from class totals to "which method allocated it".
6. Quarantine its scores: this run measured 8.42 against 6.15 unprofiled, a 37% inflation, so
   it is attribution-only and no score from it may enter a delta. Enforce structurally, the
   way subset runs are already barred from suite-wide claims.
7. Scope it to flagged rows rather than the class, and spend the freed budget on `-i 10` for
   roughly a thousand CPU samples instead of three hundred.

Acceptance: tier 2 is one run; allocation attribution names methods; a JFR-derived score
cannot reach a comparison.

### Phase C: bytecode, always

8. `Bytecode.of(worktree, class, method)` via `javap -c -p`, with the method's size and
   instruction listing.
9. Collect for implicated methods on every comparison. It is free, deterministic, and it is
   the source of truth for the byte counts that drove every inlining decision this session.
10. Diff bytecode between legs for methods whose size changed.

Acceptance: a size change reports the instruction-level difference rather than only the number.

### Phase D: the investigator

11. Symptom classification per row: moved-with-allocation, moved-with-inlining-flip,
    moved-unexplained, not-steady-state, polymorphic-hot-site.
12. A rule table from symptom to evidence, executed automatically:
    - allocation moved: allocation events grouped by allocating method, diffed
    - inlining flipped: bytecode of that method in both legs, with sizes
    - unexplained: top `kyo.*` methods by sampled time, their bytecode in both legs, then
      assembly of the single top method
    - not steady: deopt reasons, recompiled methods, `lastCompileAt` against the window
    - polymorphic: receiver distribution for the site
13. Implicated-method selection: intersect top `kyo.*` CPU methods with methods whose inlining
    verdict or byte size changed; rank by sampled time; cap at three.
14. Cost caps: assembly at most twice per comparison; tier 2 only for flagged rows.
15. Dossier rendering per flagged row, stored with the comparison so re-reading never re-runs.

Acceptance: a comparison with a regression emits a dossier containing the evidence that
explains it, with no further commands.

### Phase E: the anti-fabrication guardrail

16. A mechanism is stated only when unambiguous: an allocation delta with nothing else moved,
    or a verdict flip on a method that dominates the profile. Otherwise the dossier lists
    candidates and says plainly that none is conclusive.
17. Every dossier names what was checked, so a wrong rule is visible rather than persuasive.
18. Tests that a dossier with ambiguous evidence refuses to name a cause.

Acceptance: a synthetic ambiguous case produces "no conclusive mechanism" and lists what was
examined.

### Phase F: finish QA

19. `lastCompileAt` becomes a guard, not a display: compilation finishing inside the
    measurement window flags the row.
20. QA phase 4, the CLI surface, which has never run.
21. Re-run phases 1.5, 2 and 3 against the final shape.

Acceptance: every QA phase passes against the code as shipped.

## Open decisions

- Whether assembly fires automatically or only on request. The automation argument says
  automatically; it costs a run and perturbs compilation, so it is capped either way.
- Whether the dossier runs on every comparison or only when a row flags. Always-on is simpler
  and likelier to actually help; it slows a clean comparison for no benefit.
- Whether to add `-XX:+PrintEliminateAllocations` for scalar-replacement evidence, which would
  distinguish "allocation eliminated" from "allocation cheap", a distinction this kernel's
  design arguments turn on.
- The allocation sampling interval (512k) is untuned; these rows allocate megabytes per
  operation, so it may be sampling far more heavily than needed.

## Risks

The investigator could become a machine for confident wrong stories, which is the exact failure
it exists to prevent; phase E is the mitigation and should be treated as load-bearing rather
than polish. Tier 2 and 3 runs perturb what they observe, so their output describes a JVM like
the measured one rather than that one, and reports must say so. Every evidence run remains a
single fork of a single configuration, so deopt counts and site rankings carry no error bars
and small differences between them mean nothing.
