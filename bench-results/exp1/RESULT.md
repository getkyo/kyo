# Experiment 1: what the continuationBodiesFuse regression actually is

Run on an idle machine, no agents, heap and collector pinned (`-Xms4g -Xmx4g -XX:+UseG1GC`),
`-f 2 -wi 10 -i 5`, 10 measured iterations per cell.

**This file was rewritten after its first version attributed the regression to the wrong method.**
That correction is recorded below rather than quietly fixed, because catching it is the only reason
the harness's own rules exist.

## The four numbers

| | default | `-XX:FreqInlineSize=600` |
|---|---|---|
| old design `36b41336fb` | 26.261 ± 0.575 us/op | 24.058 ± 0.473 |
| current design `d85ee6821f` | 27.455 ± 0.336 | 22.678 ± 0.251 |

    default:  current is +4.55% against old      (reproduces the known +4.3%)
    freq600:  current is -5.74% against old      (the sign flips)
    raising the budget helps old by -8.39%, current by -17.40%

## What is established

**The regression reproduces** independently, at the expected size. The +4.3% was a stored number from
an earlier session; it is real.

**Raising the inlining budget reverses it.** At 600 the current design is faster than the old. So the
current design is not slower; it is slower only while something on its path cannot inline.

**The flag took effect**, which licenses reading the above: 57 methods changed C2 verdict between the
two configurations of the current design, and size-refusal sites fell.

**The structural difference between the designs, at the same default budget, is one method.**
Comparing the two designs' C2 verdicts directly:

    kyo.kernel.proto.Eval$::dispatch$1   607B   old: absent      new: 0 inlined / 2 refused
                                                                      'hot method too big'
    kyo.kernel.proto.Arrow$Identity$::apply     old: 30ok/12fail  new: 25ok/0fail  'too big'

`dispatch$1` exists only in the current design and cannot inline. This is exactly the out-of-line
dispatch frame a static analysis named before any measurement (candidate DIS-1): the `Suspend` arm is
expanded into the drive loop while the `SuspendWith` arm calls a separate 607-byte method.

Note the current design is *better* by the crude measures: fewer total C2 refusal sites (50 against
65) and fewer size refusals (27 against 43), having eliminated twelve `Identity$::apply` refusals. It
is still slower. Refusal counts are not the mechanism; one refusal on the hot path is.

## The correction

The first version of this file said the mechanism was `ProtoKernelBench::run$56`, the benchmark's
fused continuation body at 379 bytes, refused as `hot method too big` under the default budget and
inlining at 600.

That was measured but not compared. `run$56` is the benchmark's own code, identical in both designs,
and comparing the designs at the same budget shows it refused in **both**:

    run$56  379B   old: 0 inlined / 15 refused    new: 0 inlined / 10 refused

So it cannot be what separates them. The first version compared the current design against itself at
two budgets and read a design difference out of it, which the data never contained. The error is the
one this whole harness exists to prevent, made in the harness's own result file, and it survived
because the comparison that would have caught it had not been run.

## What is still not established

- **Why raising the budget helps the current design twice as much.** `dispatch$1` at 607 bytes is
  still above `FreqInlineSize=600`, so the flag does not inline it. The differential benefit is real
  and unexplained; it is not the simple story that raising the budget inlines the offending method.
- **Which fix relieves it.** DIS-1 (split dispatch into an inlined degenerate tier and an out-of-line
  general tier) and DIS-2 (Transform-first delivery, taking the entry under the budget) both target
  this. Experiment 2 decides, by adding the frame to the old design and seeing whether +4.55%
  reproduces.
- **`FreqInlineSize=600` is a diagnostic, not a fix.** Global, changes inlining everywhere, nobody
  should ship it.
- **One row, one configuration per cell, no replication.** The effects are large against their errors
  but the harness's own threshold machinery was not applied.
