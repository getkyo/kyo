# What the captured artifacts actually say

Every claim the plan rests on, re-verified against `qa-artifacts/` rather than taken from a review.
Commands included so any of it can be re-run.

## The instruments

| # | Claim | Measured | Command |
|---|---|---|---|
| 1 | Receiver profiles are rare, so "polymorphic" is unmeasurable for nearly every site | 5093 `<call>`, **12** with `receiver=`, 664 with `count=` | `grep -o '<call ' qa-logc.xml \| wc -l` |
| 2 | "Deopts" counts compiler-planted guards, not runtime events | 639 `<uncommon_trap>`, **6** with `thread=`; **89** unparsed `<make_not_entrant>` | `grep -o "<uncommon_trap thread='" qa-logc.xml \| wc -l` |
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

`qa-alloc.txt` is **8.0 MB** containing **109,799** stack-frame lines. `parseAlloc` reads the
four-line JMH summary at the tail and discards all of it. Per-method allocation attribution, which
v1 proposed adding a JFR pipeline to obtain, is already in the capture.

## What this means

Three of the four signals the investigator was to be built on are broken, the fourth (the drift
band) is a 3-sample range on one row applied to fifteen heterogeneous rows, and the bracket does not
constrain the independent variable. The repair order in `bench-harness-plan.md` follows from that.
