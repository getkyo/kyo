# Phase P6 decisions — GAP-COLOR-TEXT + GAP-COLOR-ERRORBAR

## Summary

Closed GAP-COLOR-TEXT (L2) and GAP-COLOR-ERRORBAR (L3) by threading `spec: Maybe[ChartSpec[A]]`
into `lowerText` and `lowerErrorBar`, routing a Present colorScale through `resolvePalette` and
leaving the Absent arm byte-identical. All four call sites updated.

---

## D1: Use `spec match { Present(s) => resolvePalette | Absent => old code }` form

**Decision:** Used the explicit `spec match` form in both palette blocks rather than calling
`resolvePalette` unconditionally.

**Rationale:** The prep noted that `resolvePalette`'s Absent branch is equivalent to the old code,
so either form preserves byte-identity. The explicit match keeps the Absent arm visually verbatim
and makes it obvious to a reader that the Absent path is unchanged (the same reason `lowerLine` and
`lowerBarGrouped` use this form). Cleaner audit trail.

## D2: Keep `theme` as the 6th param; add `spec` as the 7th

**Decision:** Added `spec: Maybe[ChartSpec[A]] = Absent` after `theme`, not replacing `theme`.

**Rationale:** The Absent arm still needs `theme` for `basePaletteText`/`basePaletteErr`
(`themePalette(theme)`). Removing `theme` would require a `spec.map(_.theme).getOrElse(...)` in the
Absent arm, changing existing correct code for no benefit. Mirrors the prep design exactly.

## D3: CS3/CS4 wrap concrete `spec: ChartSpec[A]` as `Present(spec)`, not `spec` directly

**Decision:** `marksRegionWithTransitions` call sites pass `Present(spec)` where `spec` is the
concrete (non-Maybe) `ChartSpec[A]`.

**Rationale:** `lowerText`/`lowerErrorBar` now take `Maybe[ChartSpec[A]]`; the transitions path
holds a concrete `ChartSpec[A]`, not a `Maybe`. Wrapping as `Present(spec)` is the only
type-correct form. Prep explicitly flagged this trap (Trap 3).

## D4: L3 test groups lines by center x ((x1+x2)/2), not x1

**Decision:** The L3 errorBar test groups the 6 lines by `math.round((x1+x2)/2)` rather than by
`x1`.

**Rationale:** The prep suggested grouping by x1, but a cap line has x1=px-halfCap and x2=px+halfCap
(different from the vLine's x1=px=x2). Grouping by x1 alone produced 4 groups instead of 2 (initial
failure: `List(1970, 2000, 4770, 4800)`). The center `(x1+x2)/2 == px` for all three sub-shapes
(vLine, capLow, capHigh), so center-x grouping correctly clusters each row's 3 lines into one group.

---

## Verify outcomes

- **L2 before fix:** `Hex("#3b82f6") did not equal Hex("#e63946")` (DefaultPalette blue; expected
  colorScale red `#e63946`). Confirmed failure for the right reason.
- **L3 before fix:** `Hex("#3b82f6") did not equal Hex("#e63946")` (DefaultPalette blue; expected
  colorScale red `#e63946`). Confirmed failure for the right reason.
- **After fix:** Both L2 and L3 pass. 139 total tests pass (0 failed).
- **Leaf 17 (text Absent path):** still GREEN (`#cc00cc`/`#00cccc` custom palette, not DefaultPalette).
- **Leaf 18 (errorBar Absent path):** still GREEN (`#cc00cc`/`#00cccc` custom palette, not DefaultPalette).
- **INV-004 golden (ChartInvariantsTest):** unchanged and GREEN.

## Signature delta (4 call sites)

| Site | Function | Change |
|------|----------|--------|
| LOWER:1762 | `marksRegion` lowerText | +`, spec` (7th arg, in-scope `Maybe[ChartSpec[A]]`) |
| LOWER:1763 | `marksRegion` lowerErrorBar | +`, spec` (7th arg, in-scope `Maybe[ChartSpec[A]]`) |
| LOWER:3668 | `marksRegionWithTransitions` lowerText | +`, Present(spec)` (7th arg, wrapping concrete `ChartSpec[A]`) |
| LOWER:3670 | `marksRegionWithTransitions` lowerErrorBar | +`, Present(spec)` (7th arg, wrapping concrete `ChartSpec[A]`) |

Test run log: `phases/phase-06/runs/impl-testOnly-jvm-001.log`
