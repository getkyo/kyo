# Diagnostics: the three regressed rows

Subject: `uncachedValuesPayBoxingOnly`, `idleHandlerAddsNothing`, `fusionPastBudgetPaysRescuesOnly` on
kyo-kernel2 at `9dcec36058`. Diagnostic only: every number here was measured in this session, and anything
not measured is labelled as such.

Control row throughout: `fusionAllocatesNothing`, which is the same source shape as
`fusionPastBudgetPaysRescuesOnly` and is unaffected.

---

## 1. What the rows are, at the bytecode level

The four rows compile to `loop$1` (control), `loop$2`, `loop$3` and `loop$13`. Disassembling them:

| row | helper | depth guard | bytes |
|---|---|---|---:|
| `fusionAllocatesNothing` | `loop$1` | `bipush 32` | 235 |
| `fusionPastBudgetPaysRescuesOnly` | `loop$2` | `sipush 1000` | 236 |
| `uncachedValuesPayBoxingOnly` | `loop$3` | `sipush 1000` | 235 |
| `idleHandlerAddsNothing` | `loop$13` | `sipush 1000` | 236 |

`loop$1` and `loop$2` are identical apart from the constant `32` against `1000`. **The control and the
regressed row differ only in recursion depth**, so nothing in the benchmark's own code accounts for the
difference between them. Whatever the cost is, it is in what the kernel does once depth crosses the
safepoint budget.

## 2. Per-step accounting

Each level runs ten `.map(v => ...)` plus one `.map(_ => loop(i + 1))`, so 11 map steps per level.

| row | depth | map steps/op | us/op | ns per map step | B/op |
|---|---:|---:|---:|---:|---:|
| `fusionAllocatesNothing` | 32 | ~363 | 0.55 | **~1.5** | 0 |
| `fusionPastBudgetPaysRescuesOnly` | 1000 | ~11,011 | 43.56 | **~4.0** | 664 |
| `idleHandlerAddsNothing` | 1000 | ~11,011 | 43.47 | **~4.0** | 704 |
| `uncachedValuesPayBoxingOnly` | 1000 | ~11,011 | 45.91 | **~4.3** | 155,376 |

Same instructions, roughly 2.7x the cost per step once past the budget. `uncachedValuesPayBoxingOnly`'s
155,376 B/op is ~9,700 boxed `Integer`s, consistent with its `.map(_ - 1)` producing values outside the
`Integer` cache; the other two allocate essentially nothing.

## 3. The safepoint budget, and a coupling that matters

`Safepoint.State.Initial` is `DepthGuard | period()`, `enter` decrements and `exit` increments, so the
budget counts **nesting depth**, not total calls, and the default `period` is 512.

`period` is a `StaticFlag`, so it is settable as `-Dkyo.kernel.internal.Safepoint.period=N` with no source
change (the key is `getClass.getName.stripSuffix("$").replace('$','.')`, verified in `Flag.scala:43`).

**The coupling**: `Stack.scala:19` reads

```scala
private val reach = Safepoint.period() / 2
```

and `reach` bounds `Stack.dump()`'s boundary walk. So the period controls two things at once: how often the
budget is exhausted, and how many entries `dump` chains per settled delivery. A period sweep is therefore a
dose-response over the coupled mechanism and **cannot by itself separate `enter`'s cost from `dump`'s**.

## 4. What the JIT does (measured, `fusionPastBudgetPaysRescuesOnly`)

| method | bytes | decision |
|---|---:|---|
| `Eval$::loop$1` | 1479 | `failed to inline: hot method too big`, always |
| `Eval$::apply` | 219 | `failed to inline: callee is too large` |
| `Nested::unnest` | 23 | inlines, 64 sites, zero failures |
| `Safepoint::enter` (before fix) | 49 | 51 inlined hot, **9 `callee is too large`** |
| `Safepoint::exit` (before fix) | 18 | 49 inlined hot, **9 `callee uses too much stack`** |

## 5. The Safepoint size fix, and what it was worth

`enter` and `exit` both delegated to inline extensions on `State`. The expansion loaded two module
references it never used and reached the depth array through an `inline$depths` accessor rather than the
static field it had already read directly a few bytes earlier.

Written out directly (`9dcec36058`): **`enter` 49 -> 31 bytes, `exit` 18 -> 14**, both now under HotSpot's
35-byte `MaxInlineSize`. 976 tests green.

Runtime A/B, both legs `-f 2 -wi 5 -i 5 -prof gc`, same session:

| row | before | after | |
|---|---:|---:|---|
| `uncachedValuesPayBoxingOnly` | 47.34 ± 0.99 | 45.91 ± 0.97 | -3.0% |
| `idleHandlerAddsNothing` | 43.84 ± 0.68 | 43.47 ± 0.35 | -0.8% |
| `fusionPastBudgetPaysRescuesOnly` | 43.85 ± 0.51 | 43.56 ± 0.23 | -0.7% |
| `fusionAllocatesNothing` | 0.55 | 0.55 | flat |

Allocation identical to the byte on every row. **The fix is real but small**: about 3% on the boxing row,
where the intervals barely separate and an `-f 3` confirm is owed, and inside noise on the other two. It is
not the mechanism behind the 26 to 40 percent gap.

## 6. Why the CPU profile cannot answer the `enter` question

The itimer profile of `fusionPastBudgetPaysRescuesOnly` attributes leaves as `Effect.defer` 36.8%,
`Arrow.chain` under `Stack.dump` 24.4%, `boxToInteger` 9.6%, the `arrow$NN` factories ~28% combined,
`Nested.unnest` 1.0%.

`Safepoint.enter` does not appear. That is **not** evidence it is cheap: 51 of 60 sites inlined it, and an
inlined method's cycles are attributed to the enclosing frame, never to itself. A leaf-frame profile
structurally cannot see it. At 473 total samples a 5% contributor is ~10 samples, inside the noise. The
profile is used here only to notice which frames exist, never to attribute percentages.

## 7. Open, and being measured

- period sweep at 64 / 512 / 4096 / 32767, four rows, `-prof gc`, forked with `-Xss512m` since at a high
  period the computation nests ~11,000 frames with no rescue. Reads as a dose-response over the coupled
  rescue mechanism, per section 3.
- exact invocation counts for `Safepoint.enter`, `Effect.defer` and `Stack.dump` via async-profiler method
  events. Instrumentation blocks inlining, so those runs give **counts only** and their times must not be
  read as timings.
- `PrintCompilation` and `TraceDeoptimization` on all four rows, to rule a deopt or recompilation storm in
  or out.

An arithmetic prediction to be checked against those counts rather than trusted: 664 B/op divided by ~24
bytes per `Arrow.Chain` is ~27 chain allocations per op, which is far fewer than 21 rescues times a reach of
256 would imply. So the model of the rescue path in section 3 is incomplete, and the counts decide it.

## 8. Not available

No `hsdis` on this machine, so `PrintAssembly` cannot disassemble and no assembly-level check of whether
`enter` is inlined into the map body was possible.
