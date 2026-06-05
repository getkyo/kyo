# Phase P9 Decisions — GAP-HIGHLIGHT-COVERAGE

## 1. Scope Gate: Reconciled Worklist

### Design Authority (design/02-design.md §GAP-HIGHLIGHT-COVERAGE)

The design explicitly states: "thread `highlight` into `lowerLine` / `lowerArea` / `lowerText` /
`lowerErrorBar` and wrap their row-tagged shapes via `withHighlight`. Today only lowerBarSimple
(1841), lowerBarGrouped (1928), lowerPoint (2919) apply it."

### Excluded marks — design-grounded reasons (verbatim from design §0.4 and §GAP-HIGHLIGHT-COVERAGE)

- **`lowerBarStacked`**: VERBATIM from design §0.4: "stacked bars represent group aggregates across
  x-slots, not individual rows, so per-row tagging is semantically undefined." Design §GAP-HIGHLIGHT-
  COVERAGE also states: "stacked bars have no per-row identity". Prep §Trap 7 confirms.

- **`lowerRule` / `lowerRuleChildren`**: VERBATIM from design §GAP-HIGHLIGHT-COVERAGE: "rule is not
  a row-tagged shape" and from prep §Trap 9: "`lowerRule` / `lowerRuleChildren` lower a chart-level
  reference line (a scalar value or a signal value), not per-row shapes. There is no row to tag."

- **`*WithTransitions` lowerers** (`lowerBarSimpleWithTransitions`, `lowerLineWithTransitions`,
  `lowerAreaWithTransitions`): These are animated-morph paths. Per design §GAP-HIGHLIGHT-COVERAGE:
  "line/area go through transition lowerers where highlight is not applied during animation."
  However, the fallback static calls within `marksRegionWithTransitions` (lines 3678-3679 for
  line/area, lines 3699/3701 for text/errorBar) do pass `spec` already and MUST also pass
  `resolveHighlight(Present(spec))` for parity with the static path. So the transition *lowerers*
  themselves stay unchanged, but their *call sites* in `marksRegionWithTransitions` are updated
  (this is a call-site update, not a signature change to the transition lowerers).

### Reconciled worklist (design coverage set TODAY-MISSING)

| Mark | Granularity | Change required |
|---|---|---|
| `lowerLine` | series-level: tag each series path with first row of series | Add `highlight` param + change to `Chunk[(A, Svg.SvgElement)]` accumulator + `withHighlight` |
| `lowerArea` | series-level (non-stacked/stacked/band all tag with representative row) | Add `highlight` param + tag each path result + `withHighlight` |
| `lowerText` | per-row: each glyph tagged with its `row` | Add `highlight` param + change loop accumulator to tagged chunk + `withHighlight` |
| `lowerErrorBar` | per-row: each row's 4 shapes grouped into `Svg.g` tagged `(row, g)` | Add `highlight` param + group 4 shapes per row + change accumulator to tagged chunk + `withHighlight` |

Call-site updates required:
- `marksRegion`: Line and Area call sites pass `resolveHighlight(spec)` (like lowerPoint); Text and
  ErrorBar call sites similarly pass `resolveHighlight(spec)`.
- `marksRegionWithTransitions`: Line/Area static fallback calls (lines 3678-3679) and Text/ErrorBar
  calls (lines 3699/3701) also pass `resolveHighlight(Present(spec))`.

## 2. Granularity decisions (per design §0.4)

- **text, errorBar**: per-row. Each glyph / each error-bar group tagged with its own `row`.
- **line, area**: series-level. The per-series path tagged with the series-representative row (the
  first row of the series chunk, same row `lowerLineSeries` uses for interaction attrs).

## 3. errorBar grouping decision

Each row's 4 sub-shapes (vLine, capLow, capHigh, marker) are wrapped in `Svg.g(vLine)(capLow)(capHigh)(marker)` tagged `(row, rowG)`. This ensures `applyHighlightStyle` fires ONCE on the group element (not 4 times on sub-shapes), giving exactly 1 `stroke="#000000"` occurrence per highlighted row in the HTML output. SVG stroke inheritance propagates the highlight stroke to all child lines and the circle.

## 4. No-op invariant (byte-identity)

`withHighlight(tagged, Absent)` calls `tagged.map(_._2)`, which strips the tag and returns shapes unchanged. This is the no-op path for all charts without `.interaction(_.highlightSelect/.hoverHighlight)`. The accumulator change from `Chunk[Svg.SvgElement]` to `Chunk[(A, Svg.SvgElement)]` in loops must preserve emission order; `Chunk.empty[(A, Svg.SvgElement)]` + `acc.append((row, el))` is order-preserving. INV-004 golden stays byte-identical.

## 5. Failing tests written (before fix)

Test names added to ChartInteractionTest under "Phase-9 tests: highlight coverage (L20)":
1. "line with highlightSelect: the active series path carries the select style (L20)"
2. "area with highlightSelect: the active series path carries the select style (L20)"
3. "text with highlightSelect: the active glyph carries the select style (L20)"
4. "errorBar with highlightSelect: the active row group carries the select style (L20)"

Each test: asserts no stroke before selection, drives ref to known row, asserts stroke="#000000" + stroke-width="2px" after, counts occurrences == 1.

## 6. Implementation reuse guarantee

All new `withHighlight` calls use the existing `withHighlight` and `resolveHighlight` helpers verbatim. No stroke attributes are inlined. No copy of the stroke logic was created.
