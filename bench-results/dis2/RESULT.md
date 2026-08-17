# DIS-2's flag probe: the score moved 17.4% and the hypothesis is untested

Candidate 2 in `optimization-plan.md`, and second in its run order, because it needs no source change.
The hypothesis: the new design's regression on `continuationBodiesFuse` is a *budget* effect, so
raising `FreqInlineSize` should recover it.

Every number and every verdict below came out of the harness. Nothing here was computed by hand.

## What the timing says

`bench compare --control e1-new-default --variant e1-new-f600`:

| | row | control | variant | delta |
|---|---|---|---|---|
| 🟢 | `continuationBodiesFuse` | 27.45 ± 0.34 | 22.68 ± 0.25 | **-17.4%** |

with the tool's own qualifications attached, all of which apply: `-f 1` is diagnostic and not a claim,
one selected row of fifteen so it says nothing about the suite, timing only so no movement is
attributed, and the mechanism column reads **none found**.

-17.4% is more than twice the -6.8% that forcing `dispatch$1` inline delivered under replication. Read
alone, that invites the conclusion that the budget is the whole story.

## What the efficacy gate says, and it is the opposite

`LogCompilation.efficacy` over the two compilation logs from those same runs:

    methods whose verdict moved: 37
    size refusals: 22 -> 12

    kyo.kernel.proto.Eval$::dispatch$1
        default  607B refused (hot method too big)
        f600     607B refused (hot method too big)

**The flag did not take on the method the hypothesis is about.** `FreqInlineSize=600` is below 607, so
`dispatch$1` is over the raised budget exactly as it was over the default one, and it is refused for
the same reason in both logs. `Eval$::loop` at 1582 B is likewise unmoved. Raising to 700 was tried in
experiment 2 and was also refused, for a different reason.

So this run cannot confirm the hypothesis and cannot refute it either. Under
`Investigate.adjudicate` it is **Inconclusive**, which is the answer the efficacy gate exists to
produce, and the one a pipeline without that gate cannot express.

## Where the 17.4% actually went

The 37 verdicts that did move are almost entirely the benchmark's own closures, not the kernel:

    ProtoKernelBench::run$57 .. run$67   119B, 1 site inlined -> 3 sites
    ProtoKernelBench$$anon$81::<init>     27B, 1 -> 5
    ProtoKernelBench$$anon$95::<init>     10B, 7 -> 9
    ProtoKernelBench$::ask                15B, 7 -> 9

The kernel methods checked are unchanged in both logs: `dispatch$1`, `loop`, `Safepoint::enter`,
`Safepoint::exit`, `Safepoint$::inline$depths`, `Stack::loop$1`.

The reading that survives: **a JVM flag made the benchmark's own harness code inline better, and the
row got faster because of that.** The skill's own warning covers this exactly, that a large share of
these rows is the benchmark threading its own `Int`s through an erased union, and that an
"optimization" which moves that is measuring the benchmark.

## Status

DIS-2 is **not tested**. Its falsifier as written cannot reach the method it is about, because the
method is 607 bytes and the flag values tried are 600 and 700, one below and one that the JIT refuses
for another reason.

To test it, the falsifier has to be the one the harness now proposes for this shape: force the method
by name with `CompileCommandFile`, which is the experiment already run and replicated at **-6.8%**.
That number, not -17.4%, is what this candidate's mechanism is worth.

## Defect this surfaced

The compilation log is XML and its attribute values are escaped, so a constructor arrived as
`&lt;init&gt;`. Every other tool in the ladder prints `<init>`, so those names cross-referenced against
none of them, silently. Fixed, with the parse output now checked to carry no raw entity.
