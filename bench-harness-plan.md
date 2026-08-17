# bench-harness: implementation plan (v2, after review)

The harness measures kernel changes and is meant to make a wrong conclusion hard to reach.

v1 of this plan proposed building an investigator on top of the signals the harness collects today.
A review verified those signals against the captured artifacts and found three of the four are
broken. Their numbers are reproduced below and I re-verified each one before rewriting. Building
the investigator on them would have industrialized the failure it exists to prevent: an automatic
generator of confident, well-cited, wrong mechanisms.

So the order inverts. Fix the instruments, prove they can be wrong, and only then automate on top.

## What the artifacts say

Verified directly, not taken from the review:

| Claim | Measured |
|---|---|
| Receiver profiles are rare | 5093 `<call>` elements, **12** carry `receiver=`, 664 carry `count=` |
| "Deopts" are not deopts | 639 `<uncommon_trap>`, **6** runtime (`thread=`); 89 unparsed `<make_not_entrant>` |
| The inlining diff is irreproducible | Two runs of the *identical* comparison named disjoint mechanisms |

The third, in full, because it is the one that would have shipped:

    run 1: Nested::unnest 21B refused -> inlined ; Nested$::apply 9B refused -> inlined
    run 2: Pending$package$$less$::fromArrow 2B refused -> inlined

Same two shas, same machine. A 2-byte method is not refused for size; these are warmup-era sites.
Both rows were **flat** (+0.8%, +0.3%) and both still printed a mechanism.

## The redirection

The operator's documented failure is asserting mechanisms about code that was never run. More
evidence about the same two runs does not touch that. The project's own record is that no profile
ever settled an attribution; the isolation run did, every time.

So the investigator's deliverable is a hypothesis **plus the discriminating experiment that would
kill it**, and the automation budget goes to running that experiment. Automate the isolation run,
not the narrative.

## Phases

### Phase 1: fix the broken instruments

These are parser and aggregation bugs, not design questions.

1. **Morphism.** A missing `receiver` means "not a profiled virtual call", never "megamorphic".
   Report morphism only for sites that carry a receiver profile; everything else is unclassified
   and says so. Fix the `Call` regex, which matches 664 of 5093 elements and silently drops the C1
   form.
2. **Deopts.** Separate compile-time planted traps (`bci=`, a property of the code shape) from
   runtime deopt events (`thread=`). Parse `<make_not_entrant>`, plus `decompiles=` and
   `unstable_if_traps=` on `<task>`. The current count compares guard censuses and calls the
   difference a deoptimization change.
3. **Inlining.** Never fold to one verdict per method. Keep per-site verdicts, C2 and post-warmup
   only, and report a flip as "1/6 sites" with its reason. A method whose refusal rests on one
   warmup site is not a refused method.
4. **Mechanism on flat rows.** A row inside the band gets no mechanism string, ever.

Acceptance: re-parsing the captured log reproduces the measured table above; the two stored e2e
runs no longer name disjoint mechanisms on flat rows.

### Phase 2: make a verdict answerable for its own uncertainty

5. Per-row error enters the verdict. The current rule (`error > score * 0.5`) is twelve times
   looser than the skill's own stated rule; a delta smaller than the combined error is not a result.
6. Record the run configuration **from the JMH json** (`jdkVersion`, `vmName`, `vmArgs`, `forks`,
   `warmupIterations`, `measurementIterations`, blackhole mode), not from harness constants. Today
   `Run.warmup` records a constant and `Session.jvm` records the harness's JVM, not the forked one.
7. Record the source diff and diffstat between the two shas over the restored paths. The QA bracket
   compared commits differing by 509 insertions and 221 deletions across 8 files; nothing recorded
   that. A single-method mechanism is inadmissible while the diff spans multiple implicated methods.
8. Add the JMH benchmark source to the restored, hashed and markered paths. An edit to it is
   currently invisible to every guard.

Acceptance: a comparison states its own resolution and its independent-variable count.

### Phase 3: the A/A null, interleaved

The highest-value single addition, and the falsification test v1 lacked.

9. Legs run interleaved: control, variant, control. The variant currently always runs second on a
   hotter machine, a bias confounded with the design under test that always points one way.
10. The two control legs form a free A/A comparison. **Any row it calls Faster or Regressed, and
    any mechanism it names, is false by construction.**
11. The A/A result gates the A/B report: a comparison whose own null is dirty says so at the top.

Acceptance: the A/A pair runs through the full pipeline, and today's inlining diff fails it.

### Phase 4: bytecode

The only rung with no statistics to get wrong: deterministic, zero-run, free.

12. `Bytecode.of(worktree, class, method)` via `javap -c -p`, size plus listing, diffed between legs.

Acceptance: a size change reports the instruction-level difference.

### Phase 5: allocation attribution from what is already collected

13. `qa-alloc.txt` is 8.1 MB of per-site stack trees and the parser reads only the 4-line flat
    summary at the bottom. Parse the trees. This delivers v1's stated goal for the JFR pipeline
    (which method allocated it) with no new tool and no 22k-event json decode.
14. Drop the unified JFR run. Its scores are quarantined anyway, and nothing here uses lock events.
15. Take `-XX:+PrintEliminateAllocations` instead: one flag on a run already being made, and it
    separates "allocation eliminated" from "allocation cheap", which this kernel's design arguments
    turn on.

Acceptance: allocation attribution names methods, from the existing capture.

### Phase 6: the investigator, redirected

16. Symptom classification per flagged row, over the repaired signals only.
17. For each candidate mechanism, emit the **discriminating experiment**: the isolation run that
    would kill it. Reverse the one change onto the other design and measure.
18. Run it automatically. This is where the budget goes, replacing v1's automatic assembly, which
    no decision in this project's history ever turned on.
19. A mechanism is reported as confirmed only when its isolation run confirms it.

Acceptance: a flagged row produces a hypothesis, its falsifier, and the falsifier's result.

### Phase 7: guardrails, honesty, and QA

20. A dossier lists what was checked, and abstains when evidence is ambiguous.
21. Test the **confident** direction: a synthetic case where a wrong mechanism is available must be
    refused. v1's acceptance test was satisfied by an investigator that abstains always.
22. Repeat each evidence step within a leg; keep only signals present in both.
23. Delete the dead and the wrong: unused `MinCpuSamples` and `NoiseShare`, stranded doc comments,
    the JIT-cost table's meaningless rows, README drift about the worktree guard.
24. QA phase 4 (the CLI, never run), the red-tree gate (never made to refuse anything), and the
    retry path (probably unreachable, reads a stale json).

Acceptance: every QA phase passes, including one that fails on purpose.

## Rulings taken without the user

Recorded for morning, defaults chosen to be reversible:

- Assembly automation: **dropped**, per the review's evidence that no decision here ever turned on it.
- Unified JFR: **dropped** in favor of parsing the capture we already produce.
- `PrintEliminateAllocations`: **taken**.
- Dossier scope: flagged rows only, since the A/A null now runs on every comparison and is the
  expensive part.

## What this plan still cannot do

Comparing two git commits does not isolate a variable, and no phase here changes that. Phase 2's
diffstat makes the problem visible and Phase 6's isolation run is the only real answer. Machine
state (CPU frequency, thermal, GC ergonomics, heap) remains unpinned and unrecorded; the interleaved
A/A is the mitigation, not a fix.
