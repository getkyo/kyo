# P11 Audit — dual-axis right-scale + right grid (commit `0628bd1f4`)

READ-ONLY post-commit audit. All citations are against the COMMITTED blob
(`git show 0628bd1f4:<path>`), not the dirty working tree. Files audited:
`kyo-ui/shared/src/main/scala/kyo/UI.scala`,
`kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala`,
`kyo-ui/shared/src/test/scala/kyo/internal/ScaleTest.scala`,
`kyo-ui/shared/src/test/scala/kyo/ChartAxisTest.scala`.

---

## Area 1 — PUBLIC-API CORRECTNESS — PASS

Locked surface = 02-public-api §2.1 candidate B = exactly two additions, both nested
under existing owners. The commit adds exactly those two, nothing more, nothing less.

- **Field** `ChartSpec.yScaleRightOverride: Maybe[ScaleOverride]` — UI.scala:2270 (committed),
  placed immediately after `yScaleOverride` (2269), mirroring the §2.1-locked position.
  Type is `Maybe[ScaleOverride]` (Kyo type, Absent = inferred). PASS.
- **Factory default** — UI.scala:949 passes `yScaleRightOverride = Absent` in the single
  `UI.chart` constructor site. Default semantics = `Absent`, exactly as specified
  ("internal constructor-site update, not a public default"). PASS.
- **Builder** `def yScaleRight(f: ScaleOverride => ScaleOverride): ChartSpec[A]` — UI.scala:2416.
  - EXPLICIT return type `ChartSpec[A]`. PASS.
  - Body `spec.copy(yScaleRightOverride = Present(f(ScaleOverride.default)))` — identical
    shape to `yScale` (UI.scala:2398: `spec.copy(yScaleOverride = Present(f(ScaleOverride.default)))`).
    1:1 mirror, same extension block, same `Present(f(ScaleOverride.default))` body. PASS.
  - Scaladoc 16 lines (UI.scala:2400-2415), within the 8-35 bound, verbatim the §2.1 text. PASS.
  - No symbolic operator; pure total `ChartSpec[A] => ChartSpec[A]`, no effect row, no Abort. PASS.

No extra/missing public member. Surface EXACTLY matches the locked §2.1. No deviation found.

---

## Area 2 — BYTE-IDENTITY of the resolveYScale extraction — PASS (highest-risk; verified line-by-line)

This is the load-bearing area. I compared the extracted `resolveYScale` (committed
ChartLower.scala:744-786) against (a) the OLD inline LEFT logic (deleted lines in the diff)
and (b) the OLD hardcoded RIGHT call (`git show 0628bd1f4^:.../ChartLower.scala:744`).

### (a) LEFT axis byte-identity — PASS

Old inline left → new `resolveYScale(yExt, yNoZero, yOverride, yAxisCfg, baseline, top)`
where `baseline = layout.plotBaseline`, `top = layout.plotY + layout.topHeadroom` (ChartLower.scala:810-812):

| Quantity | OLD inline left | NEW resolveYScale (left call) | Match |
|----------|-----------------|-------------------------------|-------|
| pad      | `effectivePad(yOverride, yAxisCfg)` | `effectivePad(ov, axisCfg)` | ✓ |
| nice     | `yOverride.map(_.nice).getOrElse(true)` | `ov.map(_.nice).getOrElse(true)` | ✓ |
| reverse  | `yAxisCfg.reversed` | `axisCfg.reversed` | ✓ |
| kindOpt  | 7-arm `ScaleKind→Scale.Kind` match, `getOrElse(Linear)` | identical 7-arm match, `getOrElse(Linear)` | ✓ |
| range base | `top = plotY + topHeadroom`; `if reverse (top,baseline) else (baseline,top)` | `rangeLo=baseline, rangeHi=top`; `if reverse (rangeHi,rangeLo) else (rangeLo,rangeHi)` ⇒ same `(top,baseline)/(baseline,top)` | ✓ |
| Linear arm | `padExtent(Continuous(domLo,domHi), pad)`, nice=false | identical | ✓ |
| Log arm | `padExtent(yLeftExtentNoZero.getOrElse(Continuous(1,10)), pad)`, nice=false | `padExtent(extNoZero, pad)` where `extNoZero=yNoZero=yLeftExtentNoZero.getOrElse(Continuous(1.0,10.0))` (ChartLower.scala:809) | ✓ |
| default arm | `padExtent(yExt, pad)`, nice=`yNice` | `padExtent(ext, pad)`, nice=`nice` | ✓ |
| clamp | `yOverride.map(_.clamp).getOrElse(false)` | `ov.map(_.clamp).getOrElse(false)` | ✓ |
| fit | `Scale.fit(kind, extFinal, rLo, rHi, nice=useNice, clamp=clamp)` | identical | ✓ |

The only structural change is that `yNoZero` is now computed EAGERLY before the call
(ChartLower.scala:809) instead of LAZILY inside the old Log arm. `yLeftExtentNoZero` is a pure
total fold (no side effects), so eager-vs-lazy evaluation cannot change the result. Byte-identity holds.

### (b) RIGHT-axis DEFAULT path byte-identity — PASS

New right call (ChartLower.scala:820): `resolveYScale(rExt, rNoZero, yRightOverride, yAxisRightCfg, layout.plotBaseline, layout.plotY)`.
With the default path (`yRightOverride = Absent`, `yAxisRightCfg = AxisConfig.default`):

- `pad = effectivePad(Absent, default) = default.padding = 0.0` (AxisConfig.default.padding, UI.scala:2524 `padding: Double = 0.0`). Then `padExtent(e, 0.0)` hits the `pad != 0.0` guard false ⇒ returns `e` unchanged (ChartLower.scala:736-739). Identity. ✓
- `nice = Absent.map(_.nice).getOrElse(true) = true`. ✓
- `reverse = default.reversed = false` (UI.scala:2523 `reversed: Boolean = false`) ⇒ range = `(plotBaseline, plotY)`, NO swap. ✓
- `kind = Absent.flatMap(_.kind)` ⇒ `_` arm ⇒ Absent ⇒ `getOrElse(Linear)`. ✓
- default arm ⇒ `(padExtent(rExt, 0)=rExt, plotBaseline, plotY, nice=true)`, `clamp = false`.
- ⇒ `Scale.fit(Linear, rExt, plotBaseline, plotY, nice=true, clamp=false)`.

OLD hardcoded right (`0628bd1f4^:744`): `Scale.fit(Scale.Kind.Linear, rExt, layout.plotBaseline, layout.plotY, nice = true)`.
`Scale.fit`'s `clamp` defaults to `false` (Scale.scala:62), so the OLD call had clamp=false too. **Identical.**

- **rangeHi = `layout.plotY` (NOT `plotY + topHeadroom`)** — confirmed at ChartLower.scala:820. The left
  uses `top` (with topHeadroom); the right uses bare `plotY`. The required left/right asymmetry is preserved.
- **extent source identical**: both old and new use `yRightExtent(rows, marks).getOrElse(Extent.Continuous(0.0, 1.0))` (ChartLower.scala:818). ✓
- Linear kind ✓, nice=true ✓ as shown above.

### (c) INV-004 golden + single-axis charts — PASS (by construction)

Single-axis charts have `computeRight=false` ⇒ `ysR=Absent`, right block untaken; the LEFT path
is byte-identical (a). For dual-axis unconfigured, the right default path is byte-identical (b).
INV-004 golden is unaffected. The L12a/L12b co-pins (Area 5) explicitly pin this.

**No silent left/right shift. Byte-identity holds.**

---

## Area 3 — GRID LEFT-WINS — PASS

The `!isRight` gate is replaced by a `drawGrid: Boolean` param on `buildYAxis`
(ChartLower.scala:956, ordered before `(using Frame)` — correct using-clause ordering; `buildYAxis`
is a handling path and correctly NOT inline). Gate becomes `if drawGrid` (ChartLower.scala:969).

All 3 frame builders compute the pair identically:
- `buildFrame` (def 843): ChartLower.scala:858-859.
- `buildStaticFrameLive` (def 3960): ChartLower.scala:3971-3972.
- `buildReactiveRegion` (def 4012): ChartLower.scala:4045-4046.

Each: `leftDrawGrid = spec.yAxisCfg.showGrid`; `rightDrawGrid = spec.yAxisRightCfg.exists(_.showGrid) && !leftDrawGrid`.
No caller diverges. These are the only `buildYAxis` callers (6 calls = left+right ×3), and every call
passes `drawGrid` explicitly (the `= false` default is never silently relied upon).

- **Left byte-identity**: old `cfg.showGrid && !isRight` for the left axis (isRight=false) = `spec.yAxisCfg.showGrid`
  = new `leftDrawGrid`. A left-grid chart draws the same lines as before. ✓
- **No double gridlines**: when both axes request grid, `rightDrawGrid = ... && !leftDrawGrid = false`,
  so only the left set emits. The gridline element (full plot width + `strokeOpacity(0.3)`,
  ChartLower.scala:970-977) is reused unchanged for the right when it does draw. ✓

---

## Area 4 — resolveAllScales 4-call-site threading — PASS

The def gains two new params with defaults `yRightOverride: Maybe[ScaleOverride] = Absent`,
`yAxisRightCfg: AxisConfig = AxisConfig.default` (ChartLower.scala:726-727). All 4 call sites pass
the correct values:
- lowerLive initial (ChartLower.scala:4143): `spec.yScaleRightOverride`, `spec.yAxisRightCfg.getOrElse(AxisConfig.default)`.
- lowerLive reactive (ChartLower.scala:4191): same.
- lowerWithScales (ChartLower.scala:4263): same.
- lowerStatic (ChartLower.scala:4356): same.

None passes the wrong override or drops it. (Verified via per-call extraction; all 4 emit the two
new args in order.)

---

## Area 5 — TESTS + REWARD-HACKING — WARN (3 weak assertions; no hacking; fixes genuinely reproduce)

Added tests: ScaleTest L11a/L12b; ChartAxisTest L11b/L12a/L13a/L13b/L19b. Helpers (`numOf`,
`assertClose`, `frameLinesIn`, `frameTextsIn`, `hGridLinesIn`, `Row2Ax`) all exist. `toSvgWithScales`
+ `ChartScales.yRight.kind` readback path exists (UI.scala:2490, 2823). No em-dash/en-dash on any
added `.scala` line (verified: `git show 0628bd1f4 -- '*.scala' | grep '^+' | grep '[—–]'` is empty;
the em-dashes flagged by a naive grep are all in `phases/phase-11/prep.md`, not source/test). No
TODO/FIXME/pending/.ignore on added lines. Frame ordering correct; no inline on handling paths.

Concrete, genuinely-failing-pre-fix tests (PASS):
- **L11a** (ScaleTest): asserts right `kind == ScaleKind.Log` under `.yScaleRight(_.log)`. Pre-fix
  hardcoded Linear ⇒ `fail`. Concrete, reproduces. ✓
- **L12b** (ScaleTest CO-PIN): asserts default right `kind == Linear`. Concrete byte-identity pin. ✓
- **L11b** (ChartAxisTest): guard `allYPx.nonEmpty` THEN concrete `hasLogBaseline` (y≈440) AND
  `!hasLinearFallback` (no y in 433-438). Guard-then-concrete, discriminates log vs linear by ~4px.
  Pre-fix linear ⇒ y≈435.8 ⇒ first concrete assert fails. ✓
- **L13b** (ChartAxisTest): asserts `gridLines.size == 5` (exact left tick count, left-wins guard).
  Concrete count via the full-width+strokeOpacity filter (`hGridLinesIn`), which correctly excludes
  short tick marks (no strokeOpacity). ✓

WARN — three weak assertions (none is reward-hacking, but they fall short of the
04-invariants prescribed assertion shape):
- **W1 — L13a is `nonEmpty`-only.** L13a asserts ONLY `gridLines.nonEmpty` (ChartAxisTest, Phase-11
  block). 04-invariants L13 prescribes "count == rightTickCount and assert their y-positions equal
  the right scale's tick pixels." The committed test drops both the count and the y-position checks.
  It DOES genuinely fail pre-fix (the `!isRight` gate suppressed all right gridlines ⇒ empty), so it
  is a valid reproduce test, but `nonEmpty` as the sole assert is weaker than the invariant contract
  and would not catch a wrong gridline COUNT or wrong y-positions.
- **W2 — L12a concrete pixel asserts are escapable.** The tick-10/tick-20 pixel assertions
  (`assertClose(py, 440.0)` / `230`-region / `20.0`) sit behind `case None => succeed`
  (ChartAxisTest L12a). If `niceTicks` does not emit those exact labels, the test passes trivially on
  `rightTickLabels.nonEmpty` alone. The byte-identity it co-pins IS otherwise anchored by ScaleTest
  L12b (kind) + INV-004 golden, so the gap is partially covered, but L12a's own pixel pin is
  non-load-bearing.
- **W3 — L19b independence asserts are escapable.** L19b proves left-log/right-linear independence
  only via `leftTicks.nonEmpty` + `rightTicks.nonEmpty`; the one discriminating pixel assert
  (`tick05` at y≈230) is behind `case None => succeed`. The `nonEmpty` pair does not by itself prove
  the two axes carry DIFFERENT scale kinds (both would have ticks even if shared). Independence is
  separately and concretely pinned by L11a (right Log) + the disjoint-field design, so the property
  holds, but L19b's body is weak.

Note (not a defect for P11): L12a uses `.yAxis(_.left).yAxisRight(_.right)` — the `side` setters
that P12 REMOVES. Correct for the committed P11 state (the setters still exist here); P12's removal
will need to update this chain start. Flagging for the P12 sweep, not a P11 issue.

None of W1-W3 weakens a PRE-EXISTING assertion, suppresses an error, or relabels a failure; they are
under-specified NEW leaves whose core properties are concretely pinned elsewhere (L11a/L11b/L12b/L13b).
No reward-hacking. Classified WARN, not FAIL.

---

## Summary (PASS/WARN/FAIL)

- PASS: 4 areas (1 public-API, 2 byte-identity, 3 grid left-wins, 4 four-site threading).
- WARN: 1 area (5 tests) — three under-specified leaves (L13a nonEmpty-only; L12a + L19b discriminating
  asserts escapable via `case None => succeed`).
- FAIL: 0.

**Most important finding:** byte-identity holds — the `resolveYScale` extraction reproduces the OLD
inline LEFT path and the OLD hardcoded RIGHT `Scale.fit(Linear, rExt, plotBaseline, plotY, nice=true)`
EXACTLY (default pad=0 ⇒ padExtent identity, nice=true, no reverse, no clamp, kind Linear, right
uses bare `plotY` with no topHeadroom). The public surface matches the locked §2.1 1:1. The only
follow-up is tightening three new test leaves (W1-W3) to assert counts/pixels rather than
`nonEmpty`/`None⇒succeed`, per the 04-invariants L13/L11/L19 assertion shape; their underlying
properties are already concretely pinned by L11a/L11b/L12b/L13b, so this is hardening, not a
correctness hole.
