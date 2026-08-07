# proto2 JIT Compilation Analysis

A complete account of how the proto2 kernel prototype compiles and executes on
HotSpot C2, alongside proto1 as the reference, built from direct measurement at
every observable layer: inlining decisions, speculation, deoptimization
dynamics, allocation, and disassembled machine code.

Environment: Temurin 25.0.3, arm64 macOS, `-XX:+UseCompactObjectHeaders` as the
canonical harness flag (matching the repo's test JVM options). Tools:
`PrintInlining`, `LogCompilation` XML, `hsdis` + `PrintAssembly`
(`CompileCommand=print`), `ThreadMXBean.getThreadAllocatedBytes` for
deterministic bytes per operation, and interleaved A/B runs (checkout per arm,
two cycles, marker-verified) for every code change.

## 1. Compilation model by execution regime

proto2 has four execution regimes, each with its own compilation story.

### Eager (sync) fusion

`<.map` is an inline method that mints a per-call-site `Transform` subclass and
applies it immediately. Every mint site therefore owns private bytecode, and
C2 inlines the chain apply -> run -> f -> next apply from the caller's root.
Escape analysis then scalar-replaces both the minted arrow objects and the
boxing across the whole fused region. Proof, measured as best of five batches:

| row | B/op |
|---|---|
| eager5 (5 fused maps) | 0.0 |
| eager10 | 0.0 |

The 5-map chain executes in ~5ns total. Two prerequisites were established
empirically: the erased `run(v: Any, ...)` signature (a typed parameter mints
an erasure bridge per subclass, costing an inline level per step), and the
receiver-profile pooling law: profiles attach to the declaring method's
bytecode, so fusion requires the successor dispatch to live in per-site minted
code, never in a shared helper.

### Resumed (suspension) fusion

A parked continuation is optimized into a pre-linked cons chain
(`Offset(head, next)`). The resume enters through `Arrow.apply`'s Offset arm
and each fragment hops with `o.head.run(Kyo.unwrap(w), o.next)`: two field
reads, zero allocation. The hop call site is minted per position, so its
receiver profile is monomorphic and C2 devirtualizes and inlines transitively
(`callee changed to anon$N::run`, TypeProfile counts like 779066/779066).

| row | B/op |
|---|---|
| resumeFused10 (10-step parked cont, re-driven 1M times) | 0.0 |
| suspension (full park + handle + resume round trip) | 176.0 |

The 176 bytes are the capture itself (`Continue`, `AndThen`, respine cells),
inherent to materializing a continuation.

### The drive loop and trails

`Offset.run` drives chains with a tailrec loop and a generational jump for
trailing Offsets, so stateMap trails run on a flat stack: 1M generations in
~60ms with the correct result, where proto1 overflows intermittently at 10k.

### Rescue and preemption

Stack safety uses two mechanisms chosen for their compilation behavior:

- Eager recursion: a depth guard in `apply`. The counter-fired alternative was
  measured and rejected: a probe firing 1/512 makes the rescue branch
  profile-reachable at every fused site and escape analysis dies (eager rows
  went 0.0 to 81.2 and 164.7 B/op). The depth guard's fire branch is never
  taken in shallow code, compiles as an uncommon trap, and the eager rows hold
  0.0 with the guard live. Depth lives in reserved per-thread slots
  (CAS-registered, single-writer plain long cells, cache-line strided, a
  pinned overflow cell past the probe budget), all JVM statics.
- Resumed chains: a segment boundary node spliced every 512 elements at
  optimize time. The boundary's `run` returns `Defer(v, cont)`, unwinding the
  segment and bouncing through the trampoline. Sampling lives in the chain
  structure, not in hot code, so there is no branch to poison.

Preemption polls at all trampoline entries: the Defer arm (depth fires and
segment boundaries) and the handler-dispatch arm (suspension-heavy flows),
giving every regime a poll within Period units of work.

## 2. Code geometry

The two prototypes compile into differently shaped units:

| metric | proto2 | proto1 |
|---|---|---|
| speculative hop devirtualizations (narrow run) | 63 | 1 |
| max inline nesting of run frames | ~40 | ~59 |
| inline entries at depth >= 10 | 409 | 593 |
| binding inline limiter | callee size | depth budget (76 too-deep) |
| C2 unit sizes, narrow drive | 0.8 to 1.9 KB | 1.6 to 6.0 KB |
| minted fragment bytecode | 72 B | 128 B plus bridge |
| `apply` bytecode | 184 B | 281 B |

proto1 consolidates the drive into a few large compilation units cut by
MaxInlineLevel; proto2 compiles many small per-fragment units linked by
per-site speculation. Attempts to modulate this were all measured neutral:
shrinking `apply` (hot/slow split), removing its catch (also removed the
"many throws" penalty), and `-XX:InlineSmallCode=8000`.

## 3. Compilation dynamics

From `LogCompilation` over full bench runs:

| metric | proto2 | proto1 |
|---|---|---|
| uncommon trap firings | 64 | 701,777 |
| dominant reason | class_check / maybe_recompile | unreached / reinterpret |
| made_not_entrant | 233 | 244 |

proto1's 700k firings are a genuine pathology: its unguarded deepBind stack
overflows saturate the trap counters in four units (its loop, its fragments,
and `Arrow.apply`), HotSpot stops recompiling them, and the whole rest of the
run pays ~40k interpreter round-trips per second through those units. proto2's
depth guard prevents the overflow and with it the saturation: its entire run
fires 64 traps. Recompilation churn is otherwise equal.

## 4. Machine code

With hsdis installed, `CompileCommand=print` on the hot methods:

| unit | instrs | loads | stores | cmp | branches | calls |
|---|---|---|---|---|---|---|
| p2 anon$7::run (76 B bc) | 242 | 28 | 28 | 14 | 26 | 14 |
| p2 anon$8::run (95 B bc) | 394 | 41 | 43 | 17 | 44 | 20 |
| p1 anon$6 unit (consolidated) | 781 | 54 | 120 | 34 | 72 | 58 |

Instruction mix and call density are equivalent (~17 instructions between
calls for proto2, ~13.5 for proto1); per-step code quality is the same. The
difference is granularity: proto1 amortizes one 781-instruction region where
proto2 crosses unit boundaries every ~250 instructions.

The compiled hop guard, from p2 anon$8's unit (annotated):

```
cmp   w12, w13          ; klass == Arrow$Offset?      (hop guard)
b.eq  0x...960          ; taken: fused hop continues
cmp   x11, x12          ; klass == expected Transform (speculation check)
b.ne  0x...9a4          ; miss: uncommon trap
ldr   w29, [x14, #8]    ; owners array length         (Depth.slot)
ldar  x11, [x11]        ; volatile owners read
lsl   w16, w17, #3      ; cache-line stride shift     (Depth cells)
```

The depth machinery compiles to the intended shape: one volatile read, one
compare, strided plain cells, statics reached without module loads.

## 5. The narrow residual: hypothesis ledger

narrowBindMap remains proto2's one deficit: ~125ms vs proto1's ~75ms,
~45ns per iteration (11 appends, one suspend, resolve, 12-step fused drive).
Every hypothesis tested, each by direct measurement:

| hypothesis | verdict |
|---|---|
| compact-headers type-check pricing | dead: flag-invariant (119/120 vs 71/77) |
| fragment bytecode size | dead: proto1's fragment is larger and wins |
| `apply` size and catch penalty | dead: proto1's is larger; no-catch A/B neutral |
| erasure bridges | dead: proto1 has them and wins |
| per-hop allocation | fixed by pre-linking (was real: 25% of narrow) |
| resume flatten passes | fixed by respine (~6ms) |
| pair representation | fixed by typed AndThen (was real: 16%) |
| tag guard `=:=` | dead: fastPathEqual, state near parity |
| speculation quality | dead: proto2's is far better (63 vs 1) |
| inline depth budget | dead: proto1 exhausts it and wins |
| already-compiled rejection | dead: InlineSmallCode=8000 neutral |
| allocation volume | dead: 5552 vs 5376 B/op (3%) |
| deopt or trap dynamics | dead: proto2 is pristine, proto1 pathological |
| instruction-level code quality | dead: equivalent mix and density |

What remains is the only unfalsified attribution: distributed costs of the
more fragmented code layout (call/return traffic between units, i-cache
footprint, lost cross-step optimization), not any single fixable site.
Confirming or refuting that requires hardware-counter attribution
(cycle/instruction/i-cache sampling), which is beyond hsdis and would need
async-profiler or Instruments on this platform.

Two rows contextualize the deficit: suspension's gap is purely a
compact-headers artifact (13 vs 12 with the flag off), and proto1's overall
numbers coexist with the trap-saturation pathology above.

## 6. Current reference numbers

Same box, canonical harness, all safety features live in proto2:

| row | proto1 | proto2 | kernel |
|---|---|---|---|
| eager (1M x 5 maps) | n/a | 5ms | n/a |
| deepBind | SO | 6ms, completes | 6ms |
| narrowBindMap | ~75 | ~125 | 99 |
| suspension | 12 | 14 (13 headers-off) | 27 |
| state | 20 | ~30 | 38 |
| stateMap 100k / 1M | SO | 9 / 62ms | quadratic |
| eager alloc | 0.0 (no guard) | 0.0 (guard live) | 0.0 |
| resumed fused alloc | n/a | 0.0 | n/a |

## 7. Validated design rules

Distilled from the measured record, for the production kernel:

1. Fusion requires per-site minted bytecode; shared dispatch helpers pool
   receiver profiles and kill it.
2. Rescue triggers must be profile-never-taken (depth or structure), never
   sampled counters; a 1/512 branch in hot code destroys escape analysis.
3. Sampling can live in data: segment boundary nodes give cadence with zero
   hot-path cost.
4. The inline seam is byte-sensitive: three separate regressions (16, 32 B/op)
   came from a few dozen bytes in `apply` or its callees; fast/slow splits
   restore it. Gate every change with the allocation bench.
5. Erase minted `run` signatures; erasure bridges halve fusable depth.
6. JVM statics (via companion-class hosting) remove module loads and captured
   outer references; `@static` on classes is a no-op.
7. Depth carriers: reserved single-writer slots beat ThreadLocal beat atomic
   striping; unreserved plain sharing is incorrect (lost updates can mask the
   rescue).
8. Measure under the canonical flags: UseCompactObjectHeaders moves rows by
   15 to 40%, and JIT basins make single runs untrustworthy; interleave arms.

## 8. proto3: typed phased dispatch

proto3 forks proto2 and turns the two-phase dispatch protocol into a typed
public surface: `Step[A, B, S]` (existential intermediate `X`, `head`, `next`),
`Offset` typed as both the chain node and its own Step handle, and a `step`
extension for phase 1. Phase 2 is the existing `Transform.run` and `Kyo.map`;
the protocol's entire content is where those calls are written. A handler that
resumes with `cont.step` plus `s.head.run(v, s.next)` hosts the first dispatch
in its own bytecode, so the receiver profile is private to that handler and the
fused region roots in the handler instead of behind `apply`'s pooled entry.

Isolated per-JVM interleaved rows, two cycles:

| row | cont resume | step resume |
|---|---|---|
| state | 41 / 45ms | 33 / 33ms |
| narrowBindMap | 130 / 130ms | 99 / 97ms |
| suspension | 13 / 13ms | 13 / 13ms |

The step-hosted narrow drive reaches kernel parity (99 vs the kernel's 99) and
closes roughly a third of the proto1 gap (130 to 98 against proto1's 75).
PrintInlining confirms the mechanism: the state handler's compiled unit shows
`s.head.run` speculated bimorphically (`callee changed to anon$23::run` and
`anon$24::run`, TypeProfiles 14261/28522 and 13473/26945) with both fragment
bodies inlined into the handler, and the lone-transform shape drops
`guardedRun`'s depth accounting from every resume.

Two follow-on toggles were measured and rejected, each reverted with its
record in the commit message:

- Fragment-direct suspension capture (`w.map(cont)` in the mint): suspension
  13 to 16ms, allocation up on every suspension row (suspension 152 to 176,
  narrowIter 4664 to 5552 B/op). The ~20 added bytes per fragment reopen the
  hop inline seam, and the pooled capture site (4 receivers, biased, once per
  suspension) has no fusion win to offset them.
- Pre-linking lone transforms at optimize time: time neutral, allocation
  strictly worse (stateCont10 904 to 1240 B/op, paid always). The park-time
  wrap escapes into the parked handle, while `stepSlow`'s per-resume wrap at a
  monomorphic handler site usually scalar-replaces to zero.

Additional design rules from the proto3 record:

9. Phase 2 belongs in caller-owned bytecode, and handler lambdas are
   caller-owned: `step` gives per-handler first-hop fusion with no `inline`.
   One handler per workload; a handler shared across workloads pools its own
   site (measured: a shared bench handler's first hop stayed a plain virtual
   call).
10. Fragment bytes beat capture-site profiling: relocating an amortized,
    biased dispatch into the mint is a net loss.
11. Do not force identity decomposition: a pre-linked wrap that escapes into
    the parked handle is paid on every park; an EA-erased wrap at a
    monomorphic site is usually free.
12. The hosting discipline is auditable: every dispatch that executes once per
    fused step or once per resume must be written in per-site code (a mint or
    a handler body), shared resume helpers are forbidden, and any new pooled
    site must name its amortization boundary.

### The inline round

Four approved inline changes, each its own gated toggle with an interleaved
A/B (probe was removed outright rather than folded, being dead since the
depth-guard rescue):

| toggle | time | allocation | value |
|---|---|---|---|
| remove probe() | neutral | baseline | dead branches deleted from apply, applySlow, and the drive loop; seam bytes |
| inline lift (summonFrom elision for value types) | neutral | baseline | zero lift bytecode in every primitive-returning mint (javap-verified); a folded `<:<.refl` residue per site |
| inline step | neutral | stateStep10 flake gone (904 stable) | phase 1 in caller bytecode unconditionally |
| inline trampoline (evalLoop, evalPartial, eval) | neutral | suspensionStep 168 to 152, stable | per-eval-site loop copies; the wrap scalar-replaces in every measured shape; handle(kyo) monomorphic per handler |

Two negative findings worth keeping: the state row did not recover the pooled
handler-dispatch cost when the trampoline went per-site, so that pooling was
not the binding cost there; and inline apply was rejected without measurement
because its expansion lands inside every mint's fallback arm, the seam the
capture toggle already proved byte-critical.

### Allocation vs the kernel

Kernel-equivalent rows added to `pendingtest.PendingAllocBench` (mode
`kernel`): the same programs expressed with `ArrowEffect.suspend` and
`ArrowEffect.handle`, run under the identical harness, flags
(`-Xms2G -Xmx3G -Xss10M -XX:+UseCompactObjectHeaders`), and isolated JVMs as
the proto3 baseline. Two runs each side, bit-identical:

| row | kernel | proto3 |
|---|---|---|
| eager5 / eager10 | 0.0 / 0.0 | 0.0 / 0.0 |
| suspension | 16 | 152 |
| stateCont10 | 872 | 904 (stateStep10 also 904) |
| narrowIter | 2872 | 4664 |
| resumeFused10 | 0.0 | 0.0 |

Both sides hold the eager and resumed-fused floors at zero, and the state row
is near parity. The kernel allocates far less on the suspension-bearing rows:
suspension 16 vs 152 and narrowIter 2872 vs 4664. Unverified hypothesis for
the gap, not yet confirmed with escape analysis logs: the kernel's `handle` is
an inline def, so the entire drive loop expands at each handler call site and
suspension construction plus consumption land in one compiled unit where the
nodes scalar-replace; proto3's parked chain nodes escape into the drive loop
at park time and are paid per suspension. Closing that per-suspension residual
is the next allocation target for proto3.

### Census: where the suspension bytes go

Exact per-op census via class-histogram deltas bracketed in-process around a
steady-state loop under EpsilonGC (nothing collected, so the delta is exactly
what allocated), canonical flags plus compact headers. The proto-attributable
objects sum to the bench numbers to the byte.

suspension row, 152 B/op = 11 surviving objects:

| objects | count | bytes | why they survive |
|---|---|---|---|
| Suspend (echo anon) | 1 | 16 | the suspension itself, escapes into the handler |
| Continue (park node) | 1 | 16 | pairs suspend with its continuation at park |
| AndThen spine | 2 | 32 | composition-time chain of the 3-map continuation |
| minted fragments | 3 | 24 | one instance per map call, 8 B each (fieldless, compact headers) |
| Offset chain | 2 | 32 | park-time respine in cont.optimize (3 built, 1 scalar-replaced) |
| per-eval closures | 2 | 32 | evalPartial's handler Function1 plus eval's Maybe wrapper |

narrowIter row, 4664 B/op:

| objects | count | bytes | share |
|---|---|---|---|
| Offset chain | 110 | 1760 | 38% |
| AndThen spine | 100 | 1600 | 34% |
| minted fragments | 111 | 888 | 19% |
| Suspend + Continue | 12 + 12 | 384 | 8% |
| per-eval closures | 2 | 32 | 1% |

The suspensionStep census is byte-identical to suspension: step decomposition
is allocation-free by identity, as designed.

The structural finding: on suspension-heavy paths the continuation is
represented twice. Composition builds the AndThen spine (one node per map on a
suspended value), then every park rebuilds the same chain as Offset nodes in
cont.optimize. In narrowIter that double representation is 72% of all bytes.
AndThen exists for O(1) append, Offset for O(1) caller-site decomposition, and
the respine is the O(n) conversion between them paid per suspension.

Three escape holes account for every surviving object:

1. Parked data escapes by definition. Suspend, Continue, the spine, and the
   fragments are reachable from the handler's cont argument; parking is
   escaping, and no EA can touch it.
2. The respine recursion defeats EA for the rebuilt chain. Offsets are
   allocated inside non-inlined recursive respine frames and returned upward;
   only in shallow chains does the outermost node scalar-replace (3 built, 1
   elided on the suspension row; 11 built, 0 elided per narrow resume).
3. The two per-eval closures escape into drive, which is recursive (bracket
   arm) and never fully inlined. Amortized per eval: 21% of the small
   suspension row, invisible in narrow.

What EA already elides, confirmed by diffing the census against the static
walk of the path: every intermediate Continue relink (each map on a suspended
value constructs Continue(suspend, cont.map(f)); exactly one Continue survives
per suspension, the final park), all Integer boxes (cache hits), all Maybe
wrappers (identity encoding), and the step wrap (chain already an Offset).

Reduction levers this census exposes, none executed or measured yet:

- The double representation is the big one (72% of narrow). Any design that
  parks the composition structure directly, or drives the AndThen spine
  without materializing Offsets, removes up to a third to two thirds of
  suspension-row bytes. It trades against the Offset chain being what makes
  caller-site step dispatch and fragment fusion work, and the rejected
  pre-link toggle showed park-time wraps are paid always; this needs its own
  design round.
- Minted fragments (19% of narrow) allocate per map call although the inline
  map bakes the lambda into the body and they are fieldless. A per-site cached
  instance would remove them; anonymous class instantiation inside an inline
  def has no automatic caching, unlike lambdas.
- The per-eval closures could be hoisted or the handler shape changed so they
  do not cross the recursive drive call; 32 B per eval, only visible on tiny
  evals.

### The bracket split (measured, rejected)

First lever executed from the census, reverted on its record: bracket driving
moved out of the inline trampoline into driveBracket (no handler: nested
depths never handle), making the expanded eval loop non-recursive. The lifted trampoline loop shrinks from
542 to 304 bytes. Isolated per-row interleaved A/B, 4 cycles:

| row | A (recursive loop) | B (split) | verdict |
|---|---|---|---|
| suspension alloc | 152 | 120 | closures scalar-replace, every run |
| suspensionStep alloc | 152 | 120 | same |
| stateCont10 / stateStep10 / narrowIter alloc | 904 / 904 / 4664 | unchanged | handler units too large to inline the loop; barrier moved from recursion to unit size |
| suspension, suspensionStep time | 13 to 19 ms | 10 to 11 ms | consistent win, 4 of 4 cycles |
| narrowBindMapStep time | 107 to 124 | 108 to 127 | neutral, overlapping |
| narrowBindMap (cont) time | 123 to 129 | 134 to 142 | consistent regression, 4 of 4 cycles |

PrintInlining on the narrow row: identical fused handler-body trees in both
arms; the handler dispatch devirtualizes monomorphically inside the drive root
in both. The one structural difference is the drive root itself: the 542-byte
arm exhausts its budget and fails to inline the defer-arm Arrow.apply (86
bytes, callee is too large), the 304-byte arm pulls it in. The cont-narrow
delta is drive-root code layout on the pooled legacy path, the same bucket the
narrow residual ledger already names, not a semantic cost of the split.

Rejected: the cont-resume narrow regression is reproducible on a supported
path, and the suspension-row wins do not buy it back. The revert commit
carries the verdict. Any re-land needs the drive-root layout understood well
enough that the pooled path holds its band.
