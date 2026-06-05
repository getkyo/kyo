# Phase P9 Prep — GAP-HIGHLIGHT-COVERAGE

**3-line summary:**
Today-covered: `lowerBarSimple` (approx line 1872) and `lowerBarGrouped` (approx line 2002) call
`withHighlight`; `lowerPoint` (approx line 3047) calls it too. Today-missing worklist: `lowerLine` /
`lowerLineSeries`, `lowerArea` / `lowerAreaStacked` / `lowerBandRibbon`, `lowerText`, `lowerErrorBar`,
`lowerRule`, and the transition twins `lowerLineWithTransitions`, `lowerAreaWithTransitions`.
`resolveHighlight` sets `stroke="#000000"` and `stroke-width="2px"` (via `applyHighlightStyle` Absent
arm); `withHighlight(_, Absent)` is a no-op, preserving byte-identity for non-interactive charts.

---

## 1. Helper signatures and the covered-mark call pattern

### `resolveHighlight` (LOWER line 1624, exact)

```scala
private def resolveHighlight[A](spec: Maybe[ChartSpec[A]]): Maybe[Highlight[A]] =
    spec match
        case Absent => Absent
        case Present(s) =>
            val cfg = s.interactionCfg
            if cfg.selectHighlight then
                s.onSelect.map(ref => Highlight(ref, cfg.selectStyle))
            else if cfg.hoverHighlight then
                s.onHover.map(ref => Highlight(ref, cfg.hoverStyle))
            else Absent
            end if
end resolveHighlight
```

Takes: `spec: Maybe[ChartSpec[A]]`. Returns: `Maybe[Highlight[A]]`.
Returns `Absent` when spec is Absent, when neither selectHighlight nor hoverHighlight is configured,
or when the respective ref is absent. This means non-interactive charts produce `Absent` and the
no-op branch fires.

### `withHighlight` (LOWER line 1683, exact)

```scala
private def withHighlight[A](
    tagged: Chunk[(A, Svg.SvgElement)],
    highlight: Maybe[Highlight[A]]
)(using Frame): Chunk[Svg.SvgElement]
```

Takes: a `Chunk[(A, Svg.SvgElement)]` (each shape row-tagged with its source row A) and a
`Maybe[Highlight[A]]`. Returns: `Chunk[Svg.SvgElement]`.

Absent branch (line 1688): `tagged.map(_._2)` — strips the tag, returns shapes unchanged. This is
the byte-identity no-op for all charts that have no `.interaction(_.highlightSelect)` config.

Present branch (lines 1690-1697): wraps all shapes in a single `Svg.g(Reactive[Svg.G])` driven by
`h.ref.render`. The Reactive renders all shapes; for the active row it calls `applyHighlightStyle(el,
h.style)`, others are returned unchanged. Result is `Chunk(Svg.g(reactive))` — a single element.

### `applyHighlightStyle` attribute effect (LOWER line 1645-1669)

`Absent` style arm (the default when no custom style is set): sets
`stroke = Present(Svg.Paint.Color(Style.Color.black))` and `strokeWidth = Present(Svg.SvgLength.px(2.0))`.

Rendered as: `stroke="#000000"` and `stroke-width="2px"` in HTML output. These are the EXACT
attribute strings the L20 test asserts.

### How the covered marks call the helpers

**`lowerBar` dispatcher (LOWER line 1798):**
```scala
val highlight = resolveHighlight(spec)
```
Then passes `highlight` to `lowerBarSimple` or `lowerBarGrouped`.

**`lowerBarSimple` (LOWER approx line 1872):**
Loop accumulates `Chunk[(A, Svg.SvgElement)]` as `bars`, then:
```scala
withHighlight(bars, highlight) ++ labels
```
Labels (Chunk[Svg.SvgElement]) are NOT tagged and bypass highlight (correct: label texts stay outside
the interactive region).

**`lowerBarGrouped` (LOWER approx line 2002):**
Loop accumulates `Chunk[(A, Svg.SvgElement)]` as `acc`, then:
```scala
withHighlight(loop(0, Chunk.empty), highlight)
```
highlight passed in from `lowerBar` dispatcher.

**`lowerPoint` (LOWER approx line 3047):**
Loop accumulates `Chunk[(A, Svg.SvgElement)]` in `glyphs` plus labels:
```scala
val (glyphs, labels) = loop(0, Chunk.empty, Chunk.empty)
withHighlight(glyphs, highlight) ++ labels
```
In `marksRegion` (LOWER line 1758): `resolveHighlight(Present(s))` is computed at the call site and
passed as the `highlight` param.
In `marksRegionWithTransitions` (LOWER line 3657): same pattern, `resolveHighlight(Present(spec))`.

`lowerPoint` signature includes `highlight: Maybe[Highlight[A]] = Absent` as a named parameter
(LOWER line 2885), placed after `theme: Theme = Theme.default` before `(using Frame)`.

---

## 2. Full mark-lowering inventory: withHighlight coverage today

The following table covers every mark lowerer enumerated in the task. Line numbers are from the
current worktree read; treat all as approximate because P6 is concurrently editing
`lowerText` and `lowerErrorBar`.

| Lowerer | Current sig | Calls withHighlight today? | Notes |
|---|---|---|---|
| `lowerBar` (dispatcher) | line 1788 | calls resolveHighlight (1798), passes to below | dispatcher only |
| `lowerBarSimple` | line 1821 | YES (approx 1872) | `withHighlight(bars, highlight)` |
| `lowerBarGrouped` | line 1918 | YES (approx 2002) | `withHighlight(loop(...), highlight)` |
| `lowerBarStacked` | line 2017 | NO | no highlight param; stacked bars intentionally excluded (no per-row identity) |
| `lowerLine` | line 2206 | NO | no highlight param; delegates to lowerLineSeries; no tagging |
| `lowerLineSeries` | line 2290 | NO | returns a plain `Svg.Path`, never tagged |
| `lowerText` | line 2351 | NO | loop returns `Chunk[Svg.SvgElement]`, never tagged; no highlight param |
| `lowerErrorBar` | line 2425 | NO | loop returns `Chunk[Svg.SvgElement]`, never tagged; no highlight param |
| `lowerArea` | line 2590 | NO | delegates to buildSimpleAreaPath / lowerAreaStacked / buildBandRibbon; no tagging |
| `lowerAreaStacked` | line 2711 | NO | no highlight param |
| `buildSimpleAreaPath` | line 2515 | NO | returns `Maybe[Svg.SvgElement]`, never tagged |
| `buildBandRibbon` | line 2640 | NO | returns `Chunk[Svg.SvgElement]`, never tagged |
| `lowerPoint` | line 2875 | YES (approx 3047) | `withHighlight(glyphs, highlight)` |
| `lowerRule` | line 3090 | NO | Reactive value or Const line; no row identity |
| `lowerRuleChildren` | line 3107 | NO | same; Reactive node, not row-tagged |
| `lowerBarSimpleWithTransitions` | line 3363 | NO | calls lowerBarSimple indirectly via helper; no highlight |
| `lowerLineWithTransitions` | line 3438 | NO | calls lowerLineSeries; no tagging |
| `lowerAreaWithTransitions` | line 3525 | NO | calls lowerArea; no tagging |

### Today-COVERED set (withHighlight applied)
- `lowerBarSimple`
- `lowerBarGrouped`
- `lowerPoint`

### P9 worklist (today-MISSING, must add withHighlight)
Per DESIGN §GAP-HIGHLIGHT-COVERAGE and §0.4, the fix applies to:

1. **`lowerLine`** (series-level) -- tag each per-series path with its representative row; wrap via
   `withHighlight`. In the no-color (single-series) case: the one path gets tagged with
   `rows.toSeq.headOption.getOrElse(rows(0))` (the same representative row `lowerLineSeries` uses for
   interaction attrs at approx line 2337).
2. **`lowerArea`** (series-level, non-stacked + stacked + band) -- after P5 landed, the non-stacked
   `buildSimpleAreaPath` path emits one path per series. Tag each path with its series-representative
   row (the first row of the series chunk). The stacked path and band-ribbon have no per-series row
   identity suitable for per-row highlight (the DESIGN calls out series-level for area); for those,
   the fix still wraps (using the first row as representative for the whole stacked group), but see
   Trap 5 below for the exact handling.
3. **`lowerText`** (per-row) -- tag each emitted glyph with its `row`; wrap `Chunk[(A, Svg.SvgElement)]`
   via `withHighlight`.
4. **`lowerErrorBar`** (per-row) -- each row emits 4 elements (vLine, capLow, capHigh, marker). Group
   them per row into an `Svg.G` then tag the group `(row, g)`; wrap the chunk of per-row groups.

`lowerBarStacked`, `lowerRule`, `lowerRuleChildren`, and the `*WithTransitions` twins
(`lowerBarSimpleWithTransitions`, `lowerLineWithTransitions`, `lowerAreaWithTransitions`) are
intentionally excluded:
- Stacked bar has no per-row identity across the group (per DESIGN §0.4).
- Rule is a static/reactive value line, not a row-tagged shape.
- Transition twins are for animated morph; DESIGN §GAP-HIGHLIGHT-COVERAGE notes that line/area go
  through transition lowerers where highlight is not applied during animation (consistent with today:
  `lowerPoint` in `marksRegionWithTransitions` line 3657 DOES pass highlight, so text/errorBar
  transition call sites at lines 3664/3666 SHOULD pass `resolveHighlight(Present(spec))` too).

---

## 3. Exact fix pattern per missing mark

The pattern must mirror `lowerBarSimple`/`lowerPoint` EXACTLY. Do not invent a variant.

### Signature delta (shared for all four)

Add `highlight: Maybe[Highlight[A]] = Absent` as a parameter AFTER all existing optional params,
BEFORE `(using Frame)`. This mirrors the `lowerPoint` sig:
```scala
// lowerPoint sig, approx line 2881-2885:
highlight: Maybe[Highlight[A]] = Absent
)(using Frame): Chunk[Svg.SvgElement]
```

For `lowerText` and `lowerErrorBar`: P6 concurrently adds `spec: Maybe[ChartSpec[A]] = Absent`
to these signatures. The P9 impl agent MUST re-read `lowerText` and `lowerErrorBar` after P6
commits to see the exact post-P6 signature, then add `highlight: Maybe[Highlight[A]] = Absent`
after `spec` and before `(using Frame)`. Do NOT assume line numbers from this prep.

For `lowerLine` and `lowerArea`: add `highlight: Maybe[Highlight[A]] = Absent` after
`internalHoverRef: Maybe[Signal.SignalRef[Maybe[A]]] = Absent` and before `(using Frame)`.

### Call-site additions in `marksRegion` (approx lines 1737-1763)

Mirror how `lowerPoint` is called: pass `resolveHighlight(spec)` at the call site. Currently:

```scala
// line 1737 (lowerLine, today):
case m: Mark.Line[A, ?, ?] =>
    spec match
        case Present(s) =>
            lowerLine(rows, m, layout, xs, ys, markColor, Present(s), internalHoverRef).asInstanceOf[Chunk[UI]]
        case Absent => lowerLine(rows, m, layout, xs, ys, markColor).asInstanceOf[Chunk[UI]]
```

After P9:
```scala
case m: Mark.Line[A, ?, ?] =>
    spec match
        case Present(s) =>
            lowerLine(rows, m, layout, xs, ys, markColor, Present(s), internalHoverRef,
                resolveHighlight(Present(s))).asInstanceOf[Chunk[UI]]
        case Absent => lowerLine(rows, m, layout, xs, ys, markColor).asInstanceOf[Chunk[UI]]
```

Similarly for `lowerArea` (approx line 1742-1744). Text and errorBar call sites (approx lines
1762-1763) pass `spec` after P6, then also pass `resolveHighlight(spec)` in P9.

### Call-site additions in `marksRegionWithTransitions` (approx lines 3643-3666)

The transition fallback calls at approx lines 3643-3644 pass `lowerLine` and `lowerArea` without
a highlight param today. After P9 they pass `resolveHighlight(Present(spec))`. The text/errorBar
calls at approx lines 3664/3666 already receive `spec.theme` today; after P6 they receive `Present(spec)`;
after P9 they also receive `resolveHighlight(Present(spec))`.

### Per-mark accumulator change

**`lowerLine`:** Change the return from `lowerLineSeries(...)` (a `Svg.Path`) to a tagged tuple
`(representativeRow, lowerLineSeries(...))`. Accumulate a `Chunk[(A, Svg.SvgElement)]` of series
paths. At the end: `withHighlight(taggedPaths, highlight)`.

No-color arm: the one path is tagged with `rows.toSeq.headOption.getOrElse(...)` (mirror
`lowerLineSeries` line 2337 which uses `rows.toSeq.headOption`).

Color arm: each series path is tagged with the first row of `seriesRows`
(i.e. `seriesRows.toSeq.headOption.getOrElse(rows(0))`).

Current `lowerLine` return: `Chunk[Svg.SvgElement]` (collected from `lowerLineSeries` calls, approx
lines 2220 and 2235). After P9 the intermediate is `Chunk[(A, Svg.SvgElement)]` fed to
`withHighlight`, which returns `Chunk[Svg.SvgElement]`.

**`lowerArea`:** After P5, the non-stacked color-channel arm in `lowerArea` calls
`buildSimpleAreaPath(seriesRows, ...)` per series (approx lines 2623-2627). Tag each result with
the series-representative row (first of `seriesRows`). The no-color arm calls
`buildSimpleAreaPath(rows, ...)` once (approx line 2611); tag with `rows.toSeq.headOption`.

The stacked arm (`lowerAreaStacked`) and band-ribbon arm (`buildBandRibbon`) have no natural
per-row identity for highlight; per DESIGN §0.4 the series-granularity decision: use the first row
of `rows` as the representative for the whole stacked/band chunk. Tag those paths similarly, then
call `withHighlight`.

Collect a `Chunk[(A, Svg.SvgElement)]` tagged list; call `withHighlight(tagged, highlight)`.

**`lowerText`:** Change the loop accumulator from `Chunk[Svg.SvgElement]` to `Chunk[(A, Svg.SvgElement)]`.
At each emitted `withOpacity(mark.label(row))` glyph (approx line 2411), accumulate
`acc.append((row, withOpacity(mark.label(row))))`. After the loop:
```scala
withHighlight(loop(0, Chunk.empty), highlight)
```
Mirror of `lowerBarSimple` line 1872.

**`lowerErrorBar`:** Per DESIGN §GAP-HIGHLIGHT-COVERAGE, each row's 4 elements (vLine, capLow,
capHigh, marker) are grouped into an `Svg.G` then tagged. At the point where the current loop does
`acc.append(vLine).append(capLow).append(capHigh).append(marker)` (approx line 2492), change to:
```scala
val rowG = Svg.g(vLine)(capLow)(capHigh)(marker)
acc.append((row, rowG))
```
Loop accumulator changes from `Chunk[Svg.SvgElement]` to `Chunk[(A, Svg.SvgElement)]`. After loop:
```scala
withHighlight(loop(0, Chunk.empty), highlight)
```

The `applyHighlightStyle` applied to the group `Svg.G` sets `stroke="#000000"` and `stroke-width="2px"`
on the group element; SVG stroke inheritance makes the child lines and circle inherit the stroke.
This is the DESIGN-specified mechanism ("the group's children inherit via the highlight stroke override").

**Do NOT wrap each of the 4 sub-shapes individually.** The `withHighlight` reactive iterates the
tagged chunk: `(row, g)` where `g` is the group. The Absent path returns `g` unchanged; the Present
path calls `applyHighlightStyle(g, h.style)`. The group inherits stroke to all children.

---

## 4. Reproduce-before-fix tests (L20, ChartInteractionTest)

Test file: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/frolicking-dazzling-koala/kyo-ui/shared/src/test/scala/kyo/ChartInteractionTest.scala`

Existing pattern to mirror (bar test, approx lines 408-438, "Test 12"):
```scala
"bar with highlightSelect: after the select ref is set, the active bar carries the select style (INV-024)" in run {
    for
        selectRef <- Signal.initRef[Maybe[Sale]](Absent)
        rows = Chunk(Sale("Jan", Rev(1000.0)), Sale("Feb", Rev(2000.0)))
        spec = UI.chart(rows)(bar(x = _.month, y = _.revenue))
            .onSelect(selectRef)
            .interaction(_.highlightSelect)
        root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        htmlBefore <- HtmlRenderer.render(root, Seq.empty)
        _ = assert(!htmlBefore.contains("stroke=\"#000000\""), ...)
        _         <- selectRef.set(Present(Sale("Jan", Rev(1000.0))))
        htmlAfter <- HtmlRenderer.render(root, Seq.empty)
    yield
        assert(htmlAfter.contains("stroke=\"#000000\"") && htmlAfter.contains("stroke-width=\"2px\""), ...)
        val strokeOccurrences = "stroke=\"#000000\"".r.findAllMatchIn(htmlAfter).size
        assert(strokeOccurrences == 1, ...)
}
```

Write one test per today-missing mark (line, area, text, errorBar), all under the
`// ---- Phase-4 tests: highlight (INV-024) ----` section. Each test:

1. Asserts `!html.contains("stroke=\"#000000\"")` BEFORE the ref is driven (fails today because
   highlight is silently dropped; the mark renders but without any stroke override).
2. Drives the `selectRef` to a known row.
3. Asserts `html.contains("stroke=\"#000000\"")` AND `html.contains("stroke-width=\"2px\"")` AFTER.
4. Counts occurrences: for a 2-row chart with 1 active element, `== 1`.

**For errorBar:** count the stroke occurrences on the GROUP element, not on individual sub-shapes.
Because `applyHighlightStyle` is applied to the `Svg.G` wrapper, the group carries `stroke="#000000"`;
the child lines already have their own stroke attribute from their rendering. The count of
`stroke="#000000"` should be 1 (the group attr), not 4 (child attrs). Confirm by reading
`HtmlRenderer.render` output for a minimal errorBar chart.

**For line/area:** these are series-level. A 2-series chart will have 2 paths. After driving the
ref to a row belonging to series 1, exactly 1 path carries the highlight stroke. Count == 1.

**Domain type:** reuse the existing `Sale` / `Rev` opaque type defined at the top of `ChartInteractionTest`.
For error bar you need `low` and `high` accessors; define a local case class or use an inner val:
```scala
case class EB(x: String, y: Double, lo: Double, hi: Double) derives CanEqual
```
No new opaque types needed for line/area/text (use `Sale`).

**Co-pin tests:** the existing bar highlight tests (Test 12, Test 13, Test 14, Test 15 approx lines
407-511) MUST stay green. Do not touch them. Point highlight: `lowerPoint` already calls
`withHighlight`; if a point highlight test exists add it to the co-pin list; otherwise add a minimal
point co-pin to L20's test block.

---

## 5. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartInteractionTest kyo.ChartLowerTest kyo.ChartInvariantsTest'
```

- `ChartInteractionTest`: the primary L20 tests (line/area/text/errorBar highlight) plus bar/point
  co-pins.
- `ChartLowerTest`: byte-identity guard for the no-highlight path (INV-004 golden neighbour; the
  `withHighlight(_, Absent)` no-op ensures static output is unchanged).
- `ChartInvariantsTest`: INV-004 golden must remain byte-identical (L8 guard).

Per steering.md §Stage 2 discipline: targeted JVM-only per phase.

---

## 6. Traps and hazards

**Trap 1 (CRITICAL): reuse withHighlight/resolveHighlight, never inline a copy.**
Both helpers are already in `ChartLower.scala`. Any duplication creates a divergence risk and is
an immediate audit failure. Every new call must spell out `withHighlight(tagged, highlight)` and
`resolveHighlight(spec)` verbatim, same package, same object.

**Trap 2: match multi-element handling for errorBar.**
The errorBar emits 4 elements per row. Tagging each of the 4 individually with `(row, vLine)`,
`(row, capLow)`, etc., then passing all 4 as separate entries to `withHighlight` would result in the
Reactive wrapping all 4 in one Svg.G and applying `applyHighlightStyle` to ALL 4 separately.
`applyHighlightStyle` on a `Svg.Line` sets its `stroke` attribute, but the highlight would fire 4
times per active row (one per shape) which is correct semantically but produces 4 `stroke="#000000"`
occurrences in the HTML output. The DESIGN explicitly says to wrap in a `Svg.G` first so the
`applyHighlightStyle` fires ONCE on the group. The test assertion `strokeOccurrences == 1` validates
this. Use the group approach: `Svg.g(vLine)(capLow)(capHigh)(marker)` tagged as `(row, group)`.

**Trap 3: do NOT break the bar/point co-pin.**
`lowerBarSimple`, `lowerBarGrouped`, and `lowerPoint` must not change their `withHighlight` calls.
The only change in those functions (if any) is at their call sites if they gain a new `spec` param
from a different phase -- but P9 does not touch bar/point internals.

**Trap 4: byte-identical output when no highlight is configured.**
`withHighlight(tagged, Absent)` = `tagged.map(_._2)` (line 1688). The shapes come out in exactly
the same order, with exactly the same attributes, as a plain `acc.toSeq.map(_._2)` iteration would
produce. The accumulator switch from `Chunk[Svg.SvgElement]` to `Chunk[(A, Svg.SvgElement)]` in
the loops must preserve emission order. Use `Chunk.empty[(A, Svg.SvgElement)]` as the initial
accumulator and `acc.append((row, el))` to keep order.

**Trap 5: P5 area split is a prerequisite.**
The non-stacked area path after P5 is `buildSimpleAreaPath` per series (not the old single merged
path). P9 tags the result of each `buildSimpleAreaPath` call with `seriesRows.toSeq.headOption`.
If P5 has not committed yet when P9 impl runs, the area will still use the old single-path form;
the impl agent must re-read `lowerArea` at impl time and apply tagging to whatever path-structure
P5 produced. Do not assume the P5 code matches what was written in P5's prep -- re-read.

**Trap 6: P6 lowerText/lowerErrorBar signatures are in-flux (stated in the task).**
P6 is concurrently editing both functions. The P9 impl agent MUST wait for P6's commit, then
re-read `lowerText` and `lowerErrorBar` at impl time to see the exact post-P6 signatures
(with the new `spec: Maybe[ChartSpec[A]] = Absent` param). Then add
`highlight: Maybe[Highlight[A]] = Absent` immediately after `spec` and before `(using Frame)`.
The line numbers given in this prep for those two functions are approximate; treat them as search
hints, not exact positions.

**Trap 7: lowerBarStacked intentionally excluded.**
`lowerBarStacked` does not receive a `highlight` param and must NOT get one. Per DESIGN §0.4:
stacked bars represent group aggregates across x-slots, not individual rows, so per-row tagging is
semantically undefined. Do not pass highlight to `lowerBarStacked` from `lowerBar`.

**Trap 8: transition lowerers get highlight too.**
In `marksRegionWithTransitions`, the fallback lowerLine and lowerArea calls (approx lines 3643-3644)
currently do not pass `highlight`. After P9 they must pass `resolveHighlight(Present(spec))`.
Similarly, text and errorBar fallback calls (approx lines 3664/3666) receive `Present(spec)` from
P6; after P9 they also receive `resolveHighlight(Present(spec))`. Check these call sites carefully
at impl time after re-reading the post-P6 source.

**Trap 9: lowerRule stays excluded.**
`lowerRule` / `lowerRuleChildren` lower a chart-level reference line (a scalar value or a signal
value), not per-row shapes. There is no row to tag. Per DESIGN §GAP-HIGHLIGHT-COVERAGE: rule is
not in the worklist. Do not add highlight to it.

**Trap 10: `withHighlight` wraps everything in one `Svg.g(Reactive[...])` when highlight is Present.**
This means the marks-region `foldLeft` (approx line 1765-1773) must match `Svg.G` children, which it
already does (line 1772: `case inner: Svg.G => g(inner)`). No change needed there. But for
`marksRegionWithTransitions` the fold (approx line 3672-3679) has:
`case inner: Svg.G => g(inner)` and `case other => g(other.asInstanceOf[...])`. The wrapped
`Svg.g(Reactive[Svg.G])` is an `Svg.G`, so it matches the existing `Svg.G` arm. No change
to either fold.
