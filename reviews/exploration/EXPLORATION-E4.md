# E4: is the stateful row's boxing cost morphism-dependent?

Probe for the design fork between a shared-eval register (state as an eval-loop local) and a
per-call-site inline drive (the old kernel's structure). Bench-only; no kernel changes. Worktree at
`26f14ecdd4` plus two benchmark rows per kernel.

## The rows

kernel2 (`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel/bench/KernelBench.scala`):

```scala
@Benchmark
def statefulTwiceMono: Int =
    def loop0(i: Int): Int < Ask =
        if i > Depth then i
        else ask.map(a => loop0(i + a))
    def run(): Int =
        ArrowEffect.handleLoopState(Tag[Ask], 0, loop0(0))(
            [X] => (state, _) => Loop.continue(state + 1, 1: Int < Any),
            (_, a) => a
        ).eval
    run() + run()
end statefulTwiceMono

@Benchmark
def statefulTwiceBi: Int =
    def loop0(i: Int): Int < Ask =
        if i > Depth then i
        else ask.map(a => loop0(i + a))
    val first =
        ArrowEffect.handleLoopState(Tag[Ask], 0, loop0(0))(
            [X] => (state, _) => Loop.continue(state + 1, 1: Int < Any),
            (_, a) => a
        ).eval
    val second =
        ArrowEffect.handleLoopState(Tag[Ask], 0, loop0(0))(
            [X] => (state, _) => Loop.continue(state + 1, 1: Int < Any),
            (_, a) => a
        ).eval
    first + second
end statefulTwiceBi
```

`handleLoopState` is inline, so Mono mints one anon `HandlerLoopState` class invoked twice and Bi
mints two distinct classes: same total work, the eval's shared clause dispatch goes from
monomorphic to bimorphic. The old-kernel mirror uses `ArrowEffect.handleLoop(Tag[Ask], 0, ...)`
with the identical successor clause, same Mono/Bi split.

## Results

`-f 2 -wi 10 -i 5 -prof gc` (the first kernel2 default run at `-wi 5` reproduced the same numbers
with wider bands). Per op = two full runs of the original row (Depth 10000, so 20,002 answers/op).

| | time us/op | alloc B/op |
|---|---:|---:|
| **kernel2 mono, default** | 913 ± 1214 (bimodal, see below) | 1,596,246 |
| **kernel2 bi, default** | 635 ± 106 | 2,076,292 |
| **kernel2 mono, AutoBoxCacheMax=20000** | 468 ± 8 | 1,280,211 |
| **kernel2 bi, AutoBoxCacheMax=20000** | 575 ± 17 | 1,760,260 |
| old kernel mono, default | 388 ± 124 | 2,080,275 |
| old kernel bi, default | 325 ± 132 | 2,396,242 |
| old kernel mono, AutoBoxCacheMax=20000 | 271 ± 3 | 2,080,242 |
| old kernel bi, AutoBoxCacheMax=20000 | 254 ± 2 | 2,080,242 |

kernel2 mono default is not a mean, it is two modes. Raw fork iterations:
`[637, 487, 487, 492, 488]` and `[3106, 1102, 527, 957, 843]` (second run:
`[1102, 1071, 1099, 1567, 708]`, `[1427, 1197, 1275, 1236, 1300]`). A fork that settles reaches
~490, which equals the box-cache configuration, meaning escape analysis elided the boxes; a fork
that does not is 2 to 6 times slower. Bi never shows either extreme: stable around 600 to 640.

## Answer

**The cost is morphism-dependent, on the allocation axis exactly and on the time axis strongly.**
kernel2's bi rows allocate **+480,046 B/op over mono in both JVM configurations**, which at 20,002
answers/op is **+24 bytes per answer: the `Continue2` outcome stops being scalar-replaced the
moment the shared dispatch's clause call site sees a second class.** With boxing made free
(AutoBoxCacheMax), bimorphism still costs **+23% time** (575 vs 468, tight bands). The old kernel
shows **no bimorphism penalty at all** (bi is at or below mono in both configurations, allocation
identical under the flag), because its inline-expanded drive gives every call site its own clause
site: monomorphic by construction, which is the structural property, not an accident of the
benchmark. Two further facts sharpen the verdict against the shared-eval register design: even
*monomorphic* kernel2 only reaches its EA-elided fast mode in some forks (the bimodal default
column: JIT-unstable exactly where the register design needs stability), and even the best kernel2
cell (468) is 1.7x the old kernel's equivalent (271), so eliding boxes is necessary but not
sufficient. The evidence supports the per-call-site inline family for the stateful hot path, or any
design that removes the per-answer allocation structurally rather than by hoping escape analysis
spans a shared megamorphic call site.
