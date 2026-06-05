# Phase P4 decisions — GAP-COLOR-GROUPEDBAR

## D1: Test domain and color choices

Used the existing `Sale`/`Region` domain types (NA, EU, APAC) already defined in `ChartLowerTest`.
Three rows all at `x = "Jan"` with distinct regions guarantee `dodge = true` (multiple distinct colors
in one x-band), which is the exact path through `lowerBarGrouped` that the bug affects.

Color choices for the colorScale:
- NA: `#e63946` (red)
- EU: `#2a9d8f` (teal)
- APAC: `#e9c46a` (yellow)

These are unambiguously distinct from the DefaultPalette entries (`#3b82f6` blue, `#f97316` orange)
so a fallback to the wrong path produces a clearly different actual value.

## D2: Test insertion point

Inserted Leaf L1 immediately after Test 8b (line 486), before Test 9. This follows the prep.md
instruction and keeps the grouped-bar tests grouped together.

## D3: Legend swatch assertion approach

Used the existing `legendSwatchRects(root)` helper (12x12 rect detection) to isolate legend swatches
from mark rects. Sorted swatches by y-position to recover encounter order (NA, EU, APAC -- the same
order as `collectColorCategoriesWithRaw`). This matches the prep.md trap note about isolating swatch
rects from mark rects.

## D4: Fix form selection

Applied the explicit `case Present(_)` / `case Absent` form (not the fully-collapsed
`resolvePalette` unconditionally form), following the mirror of `lowerBarStacked`'s pattern and the
prep.md directive. The comment was updated from the misleading "Sequential only" framing to the
correct "any Present colorScale" framing.

## D5: Absent arm byte-identity confirmed

The `case Absent` arm inside the inner match is the existing by-index `basePalette` code verbatim --
identical character-by-character to the removed `case _` arm. The outer `case Absent =>` arm is
also unchanged. Leaf 15 (custom theme.palette) and Leaf 16 (DefaultPalette byte-identity gate)
both stayed green without modification.

## D6: Test results

- Before fix: 88 tests pass, 1 fails (L1) with `Hex("#3b82f6") did not equal Hex("#e63946")` --
  the NA bar got DefaultPalette blue instead of the colorScale red. Correct failure reason.
- After fix: 89 tests pass (ChartLowerTest), 0 failed.
- ChartInvariantsTest: 12 tests pass, 0 failed (INV-004 golden unchanged).
- Combined run: 101 tests, 0 failed.
- Log: `phases/phase-04/runs/impl-testOnly-jvm-001.log`
