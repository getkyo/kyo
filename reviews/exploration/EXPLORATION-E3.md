# E3: span-backed continuations for HandlerCont — negative result

Branch `e3-span-continuation`, commit `8214b5c0b2`, baseline `26f14ecdd4`, all three legs same session
back to back under the bench mutex, `-f 2 -wi 5 -i 5 -prof gc`, FQ-anchored regexes.

## The design as implemented

`Arrow.Resume(entries: Span, states: Span)`: the continuation handed to a `HandlerCont` clause is the
dumped range's entries held verbatim, not a fold of them. Applying it builds a `Kyo.Park`, whose eval arm
already restores spans above whatever the running eval holds. One copy out at `Stack.dumpResume(pos)`, one
copy back at the resume. Spans immutable, every application builds its own park: multi-shot, replayable in
any eval, complete-value contract kept.

Guard rails carried over from the AndThen rule: only a region-free run takes the shape (a handler, a
recovery, a binding or a finalizer in the range falls back to the folded dump, so pushing it lands the
region as a visible entry); a run of one is handed back bare; a `Resume` may contain another `Resume`
entry, so an accumulated remainder rides as one shared slot. `EffectTrace` walks the spans the way its
Park arm does. The loop dispatches (`dispatchLoop`, `dispatchLoopState`) and the settled-path `dump()`
kept the folded shape: one variable.

Diff: `Arrow.scala` (+Resume, ~45 lines), `Stack.scala` (+dumpResume, ~40 lines), `Eval.scala` (one call
site), `EffectTrace.scala` (+one walk arm). Two new casts in `Resume`, both the park mechanism's own
(value-in-flight typed by its result, exactly as `park$1` stores it; Kyo asserted into the pending union
where the alias is not transparent) — they need sign-off if any of this survives.

976 tests green, including the hostile axes: multi-shot capture, replay in a foreign eval, capture across
an inner region keeping the region as an entry, stateful handler captured with live state, park interplay.

## Three-way measurement

| row | base µs | E3 µs | vs base | old µs | E3/old | base B/op | E3 B/op | old B/op |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `fusionAfterSuspension` | 217.09 | 336.20 | **1.55x** 🔴 | 84.12 | 4.00x | 736,810 | 872,946 | 408,437 |
| `fusionAfterSuspensionRunOnly` | 0.90 | 1.35 | **1.49x** 🔴 | 0.27 | 4.99x | 1,264 | 1,720 | 0 |
| `trailingMapsStayLinear` | 854.93 | 913.04 | 1.07x 🔴 | 464,094 | 0.002x | 2,560,982 | 2,561,182 | 1.6e9 |
| `sharedHandlerPaysDispatch` | 161.55 | 169.19 | 1.05x ⚪ | 131.29 | 1.29x | 240,457 | 240,457 | 240,407 |
| `continuationBodiesFuse` | 32.41 | 32.82 | 1.01x ⚪ | 23.76 | 1.38x | 64,136 | 64,136 | 56,072 |
| `emittingClausesPayRegionRebuild` | 148.73 | 149.69 | 1.01x ⚪ | — | — | 184,313 | 184,313 | — |
| `foreignCrossingsPayRotation` | 878.36 | 854.43 | 0.97x ⚪ | 332.81 | 2.57x | 1,760,310 | 1,760,310 | 1,680,202 |
| `suspensionBaseline` | 186.76 | 176.88 | **0.95x** 🟢 | 123.81 | 1.43x | 640,137 | 640,137 | 560,081 |
| `suspensionFusesContinuation` | 99.51 | 92.94 | **0.93x** 🟢 | 68.23 | 1.36x | 240,097 | 240,097 | 240,050 |

## The mechanism, named

**Why the fusion rows regressed (the decisive finding).** In the baseline, `dump(pos)` folds the range
into one `AndThen`, the clause's `cont(v)` defers, and the flatten stores that AndThen back as **one
entry**; the settled path then peels it link by link through the stored `cont` references — a zero-cost
peel, no re-fold. The baseline's 1,264 B/op on `fusionAfterSuspensionRunOnly` is ~one fold, once. E3's
restore **re-expands the range into 51 separate entries**, so the settled path's no-arg `dump()` has to
fold them all over again: E3 pays the span copy out, the copy back, *and* the fold the baseline had
already paid once and then kept. The +456 B/op (1,264 → 1,720) is the two 51-slot arrays plus the park,
on top of a re-fold. Restore undoes exactly the shape the settled path wants.

**Why the two suspension rows won.** Their B/op is *identical to the byte* across base and E3
(640,137 / 240,097), so the −5%/−7% is pure path shape: when the resumed entries are consumed as entries
by the next suspension (rather than re-folded by settled delivery), the restore arraycopy beats the
flatten walk. Real, but small, and it does not survive the trade.

**Why trailing maps slipped 1.07x.** The shared-remainder slot works (allocation flat), but every answer
now routes through a Park node dispatched by an arm deliberately placed last in the eval's node match,
plus a Defer wrapper from the two-arg apply. Shallow, frequent resumes pay the round trip.

## Verdict

**Negative. Do not adopt as-is.** The folded `AndThen` is the better universal currency because the
settled path peels it for free, and a span continuation destroys that property at exactly the rows where
it matters most (`fusionAfterSuspension*`). The two genuine wins (suspension rows, identical allocation,
restore-beats-flatten) point at a narrower follow-up: make *flattening an AndThen back onto the stack*
cheaper (it is a link walk today), rather than changing the continuation's representation. A hybrid that
picks Resume only when the range will not be re-folded is unknowable at dump time and was rejected on that
ground.

Risks if revived anyway: the two casts in `Resume`; Park's arm position making resume dispatch pay three
failed type tests; and the `emittingClausesPayRegionRebuild` row only exists on ProtoKernelBench so the
old-kernel column has no anchor for it.
