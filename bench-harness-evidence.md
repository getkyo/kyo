# What the captured artifacts actually say

Every claim the plan rests on, re-verified against `qa-artifacts/` rather than taken from a review.
Commands included so any of it can be re-run.

## The instruments

| # | Claim | Measured | Command |
|---|---|---|---|
| 1 | Receiver profiles are rare, so "polymorphic" is unmeasurable for nearly every site | 5093 `<call>`, **12** with `receiver=`, 664 with `count=` | `grep -o '<call ' qa-logc.xml \| wc -l` |
| 2 | "Deopts" counts compiler-planted guards, not runtime events | 639 `<uncommon_trap>`, **6** with `thread=`; **89** unparsed `<make_not_entrant>` | `oracles.sh` |
| 3 | The inlining diff is irreproducible | Two runs of one comparison named disjoint mechanisms, both on flat rows | `grep 'refused ->' e2e.log e2e2.log` |
| 7 | The steady-state guard is far too lax | `compiler.time.profiled` = **4.0 ms** against a 50 ms limit (0.08% of a 5000 ms window) | `qa-comp.json` secondaryMetrics |
| 8 | Config is recorded from constants, not from the run | json says `warmupIterations = 5`; `Run.warmup` would record the constant `10` | `qa-jmh.json` |
| 11 | Dead code | `MinCpuSamples`, `NoiseShare`: 1 reference each, their own definition | `grep -rn` over the sources |

Run 1 and run 2 of the *identical* comparison, verbatim:

    run 1: Nested::unnest 21B refused -> inlined ; Nested$::apply 9B refused -> inlined
    run 2: Pending$package$$less$::fromArrow 2B refused -> inlined

A 2-byte method is not refused for size. These are warmup-era sites surviving a
worst-verdict-per-method fold. Both rows were flat (+0.8%, +0.3%) and both printed a mechanism.

## The measurement

| # | Claim | Measured |
|---|---|---|
| 5 | A bracket does not isolate one variable | **8 files, 304 insertions, 221 deletions** between the QA shas |
| 9 | Profiling perturbs, unevenly | alloc-profiled **8.428**, unprofiled **5.839** / **5.867** us/op |
| 9b | v1 misattributed the perturbation figure | 8.428 is the **alloc-only** run; v1 credited it to the unified JFR run |
| - | Blackhole mode is unrecorded | JMH's own warning appears in the capture: "the performance difference caused by different Blackhole modes can be very significant" |

## The discarded data

`qa-alloc.txt` is **8.0 MB** containing **109,799** stack-frame lines across **200** trace sections.
`parseAlloc` reads only the flat table at the tail.

Precisely what that costs, since it is easy to overstate: the flat table is a **complete** per-class
attribution (4 classes, 19,017,986,638 bytes, every allocated byte accounted for). Class-level
totals are therefore correct today. What is lost is **per-method** resolution, which is the level a
mechanism claim needs.

But the traces cannot supply it either, as captured: their 200 sections cover 2,713,185,225 bytes,
**14.27%** of the total, largest section 0.09% and smallest 0.07%, with depths from 23 to 1046
frames, so one logical site fragments across hundreds of stacks by recursion depth. Enough to rank
on a row with one dominant allocator per class; useless for a between-leg per-method delta, because
the truncation point itself moves between runs. Hence `output=collapsed` plus conservation.

## What this means

Three of the four signals the investigator was to be built on are broken, the fourth (the drift
band) is a 3-sample range on one row applied to fifteen heterogeneous rows, and the bracket does not
constrain the independent variable. The repair order in `bench-harness-plan.md` follows from that.

## The oracles are a script, not constants

`bench-harness/oracles.sh` derives every value above from the artifacts by means the parsers do
not share, and prints them as `name=value`. Tests compare typed parser output against it. A
constant copied into a test rots silently when the capture is replaced; a derivation does not.

Current output:

    calls_total=5093            calls_with_receiver=12      calls_with_count=664
    traps_total=639             traps_runtime=6             make_not_entrant=89
    methods_total=4466          methods_unloaded=89
    tasks_total=883             tasks_osr=5                 osr_mentions=19
    alloc_trace_sections=200    alloc_frame_lines=109799    alloc_total_samples=36274
    alloc_traced_bytes=2713185225

Two corrections it produced on first run, both against figures I had accepted:

- **OSR tasks are 5, not 19.** 19 counts `compile_kind='osr'` occurrences across `task`,
  `task_queued` and `nmethod` elements. Only 5 are compilation tasks.
- My own first draft of the script reported `alloc_trace_sections=0`, because the capture is sbt
  console output and every line carries an `[info] ` prefix. That is the same defect the harness
  has: it reads sbt stdout rather than the file async-profiler writes.

`alloc_traced_bytes` (2,713,185,225) against the flat table's 19,017,986,638 is the 14.27% ceiling
on anything per-method attribution can explain from these truncated traces.

