# F3: the two small reds — userTypesSkipKernelWrapping and evalFixedOverhead

Worktree branch `f3-small-reds`, base `26f14ecdd4`. Same-session measurements, bench mutex held for
every run, old kernel via `kyo-kernel-bench`.

## Row 1: userTypesSkipKernelWrapping — mechanism named, lever identified, fix not small

### Same-session confirmation (-f 2 -wi 5 -i 5, gc)

| row | kernel2 | old kernel | ratio |
|---|---:|---:|---:|
| `userTypesSkipKernelWrapping` | 46.97 ± 1.11 µs | 41.64 ± 0.13 µs | **1.13x** |
| B/op | 176,848 | 177,056 | 1.00x |
| `uncachedValuesPayBoxingOnly` (control) | 46.76 ± 0.31 | 71.25 ± 2.26 | 0.66x |

The "asymmetry" dissolves on inspection: kernel2 costs the same (~46.9 µs) whatever flows through the
chain; the old kernel's Int row is anomalously slow (its own uncached-boxing pathology, 71 µs), which
*hides* the old kernel's per-step overhead there. The Box row is the honest settled-path comparison,
and kernel2 loses it by 5.3 µs over ~11,011 map steps = 0.48 ns per step.

### The diagnosis chain

Allocation parity (0.1%) excluded allocation. Inclusive cycle attribution (collapsed itimer, one fork
each, same session):

| family | kernel2 | old kernel |
|---|---:|---:|
| `Safepoint.get` inclusive | **23.5%** | 18.4% |
| of which the slot read | `AtomicReferenceArray.get` + `VarHandleReferences$Array.getVolatile` = **18.2% as leaves** | `ThreadLocal.getEntry` 10.2% |
| `Safepoint.enter` / `exit` | 6.2% / 2.2% | 7.6% / 1.1% |
| `Safepoint.home` | 3.8% | — |
| Safepoint family total | **~32% ≈ 15.0 µs** | **~27% ≈ 11.2 µs** |
| `given_Frame` (old kernel only) | — | 8.6% ≈ 3.6 µs |

**Mechanism**: kernel2 resolves its safepoint slot on every settled map step through
`Thread.currentThread()` + `home` arithmetic + a **volatile `AtomicReferenceArray` read** (the slot
ownership check `slots.get(h) eq thread`). The old kernel resolves once per drive via ThreadLocal and
threads the instance as an implicit parameter, so its per-step cost is plain field arithmetic. The
volatile read alone is 18.2% of kernel2's row. The +3.8 µs Safepoint delta accounts for most of the
5.3 µs gap; the old kernel gives back 3.6 µs of it in `given_Frame` allocation kernel2 does not pay,
which is why the net is only 1.13x.

### The lever (recommended, not implemented)

Make the hot ownership check a plain read: `slots` as a plain array with VarHandle access for the
CAS/stop paths and a relaxed read for `get`'s own-slot check. A thread reads a slot it published
itself, so the only visibility question is a concurrently-installed `Stop` sentinel, which a relaxed
read may observe late by at most one step; stop delivery is already periodic, but that argument must
be made precisely and pinned (SafepointConcurrencyTest) before it is code. This touches the kernel's
concurrency semantics, which is beyond "small and clean" for this exploration; it is a
concession-shaped change needing its own review. Expected value: most of 18% on every settled row,
not only this one.

## Row 2: evalFixedOverhead — made readable; mechanism named; fix implemented and measured

### The probe

`evalFixedOverheadBatch` added to both boards (`@OperationsPerInvocation(1000)`, seed varies per
iteration, results accumulate): the fixed path at readable resolution without touching the original
row. Commit `374a878ce0`.

### Before the fix (-f 2 -wi 5 -i 5, gc, same session)

| | kernel2 | old kernel |
|---|---:|---:|
| per eval | 12 ns | 9 ns |
| B/op | **13.98** | ~0 |

13.98 B/op is exact: seeds 1..1001 leave 874 values outside the Integer cache, 874/1001 × 16 B =
13.97. **One result box per settled eval.** The box is born in the caller's map expansion and dies
right after `.eval` returns, but `.eval` delivered it through `Eval.apply`, a deliberately non-inline
method (the 132-copies fix), so escape analysis cannot span the boundary and the box becomes a real
allocation. The old kernel's eval is inline, the whole chain is one compilation, EA elides it.

### The fix

`.eval` gains the settled fast path, same shape as `evalNow` two lines above it: bind once, match
`Kyo`, `Nested.unnest` in the caller's compilation; only a node graph enters the interpreter.
Observationally identical: the interpreter's own settled arm is exactly unnest-empty-stack-return,
with no park, no finalizers, no safepoint interaction on that path. Clean batch build; 976 tests
green. Commit: see worktree log.

### After the fix (-f 2 -wi 5 -i 5, gc, same session, mutex held)

| | kernel2 before | kernel2 after | old kernel | verdict |
|---|---:|---:|---:|---|
| `evalFixedOverhead` | 12 ns / 13.98 B | **2 ns / ~0 B** | 9 ns / ~0 B | **0.22x, green** |
| `evalFixedOverheadBatch` | 12 ns / 13.98 B | **2 ns / ~0 B** | 9 ns / ~0 B | **0.22x, green** |
| `userTypesSkipKernelWrapping` (control) | 46.97 | 46.55 ± 0.62 | 41.64 | unchanged, still 1.12x |
| `uncachedValuesPayBoxingOnly` (control) | 46.76 | 47.10 ± 0.59 | 71.25 | unchanged, green |
| `fusionAllocatesNothing` (control) | 0.56 | 0.58 ± 0.05 | 0.79 | within noise, green |

The settled chain now compiles entirely in the caller: the box is elided, and the remaining 2 ns is
the map arithmetic plus the safepoint enter/exit pair. The batch row and the single-shot row agree
exactly, which is the batch row doing its job. Kernel2's floor is now 4.5x *below* the old kernel's,
because the old kernel still enters its inline drive's dispatch for a settled value.

## Risks

- The `.eval` fast path duplicates the "settled means unnest" law at one more site; it is pinned by
  the whole suite passing and by `PendingBytecodeTest`'s expansion pins if any cover `.eval`.
- The Safepoint lever is deliberately NOT taken here; adopting it requires a memory-ordering argument
  and concurrency pins.

## Verdict

- `evalFixedOverhead`: red was real (a box per settled eval crossing the non-inline interpreter
  boundary) and is **closed**: 12→2 ns, 13.98→0 B/op, now 0.22x of the old kernel. Fix is the
  `.eval` settled fast path (`0bbe821892`), 976 tests green, clean batch build, expansion outside
  package kyo pinned by `PendingExpansionSiteTest`. The batch row (`374a878ce0`) stays as the
  readable instrument on both boards.
- `userTypesSkipKernelWrapping`: mechanism named with numbers (the volatile `AtomicReferenceArray`
  slot read inside `Safepoint.get`, per settled map step, 18.2% of the row's samples; family total
  ~32% vs the old kernel's ~27% ThreadLocal-based cost). The fix is a Safepoint memory-ordering
  change (plain read on the own-slot check, VarHandle on the stop/CAS paths) that is deliberately
  NOT taken here: it needs a precise visibility argument for the `Stop` sentinel and concurrency
  pins before it is code. Expected value if taken: most of ~18% on every settled row, which would
  also close this row's 1.12x.
