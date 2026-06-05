# Phase P1 prep — extract shared `applyBarChannels` helper (behavior-preserving)

**Goal:** factor the opacity/tooltip/label channel block out of `lowerBarSimple` into a private
`applyBarChannels` helper in `ChartLower.scala`. Pure extraction, byte-identical static-bar SVG.
Closes no gap; UNBLOCKS P7 (animated-bar channels reuse the same helper).

**File:** `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (3990 lines, confirmed by read).

---

## 1. Exact current code locus (re-read & confirmed)

- `lowerBarSimple` definition: **lines 1768-1842** (`def` 1768, `end lowerBarSimple` 1842).
- The channel block to extract: **lines 1814-1837** (design said ~1815-1836; drifted +1 due to the
  leading `// Opacity channel (INV-019).` comment at 1814 and the final tuple at 1837). Verbatim:
  - **1814-1817 opacity:** `mark.opacity match { Present(fn) => baseRect.fillOpacity(math.max(0.0, math.min(1.0, fn(row)))); Absent => baseRect }` -> `withOpacity`.
  - **1818-1821 tooltip:** `mark.tooltip match { Present(fn) => withOpacity(Svg.title(fn(row))); Absent => withOpacity }` -> `withTooltip`.
  - **1822-1836 label:** `mark.label match { Present(fn) => Chunk(Svg.text.x(barX+barW/2.0).y(barY-2.0).textAnchor(Middle).dominantBaseline(Auto).fill(Paint.Color(defaultFill)).apply(fn(row))); Absent => Chunk.empty }` -> `labelElems`.
  - **1837:** `(bars.append((row, withTooltip)), labels ++ labelElems)` — the tuple appended in `loop`.
- Inputs already in scope at the block: `baseRect` (built 1807-1813: `Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Paint.Color(defaultFill)).withAttrs(iAttrs)`), `mark`, `row`, `barX`, `barW`, `barY`, `defaultFill`. NOTE: the block uses `barW` (label cx) and `barY` (label cy) but NOT `barH` — match the design signature exactly anyway (`barX, barW, barY`).
- P7 reuse target (do NOT edit in P1): `lowerBarSimpleWithTransitions` **lines 3235-3288**, rect built
  at **3272-3284** (`val r: Svg.Rect = ...`). P7 will call `applyBarChannels` there. P1 only needs the
  helper to exist and be proven on the static path.

## 2. Helper signature + placement

Place the private method adjacent to `lowerBarSimple` (design §0.2 says "near lowerBarSimple"; put it
immediately before line 1768 or immediately after line 1842, object-private). Signature is locked by
design §0.2 (lines 66-74):

```scala
private def applyBarChannels[A, X, Y](
    rect: Svg.Rect,
    mark: Mark.Bar[A, X, Y],
    row: A,
    barX: Double,
    barW: Double,
    barY: Double,
    fill: Style.Color
): (Svg.SvgElement, Chunk[Svg.SvgElement])
```

- Body = the current 1814-1836 block verbatim, returning `(withTooltip, labelElems)`. The opacity arm
  starts from the passed-in `rect` (not `baseRect`); the label `fill` uses the passed-in `fill`.
- **Return-type widening is intentional and required:** the first component MUST be `Svg.SvgElement`,
  not `Svg.Rect`. `rect.fillOpacity(...)` stays a `Svg.Rect`, but the tooltip arm
  `withOpacity(Svg.title(...))` applies a child and widens to the generic element type — so the union
  of the two match arms is `Svg.SvgElement`. This matches what `bars: Chunk[(A, Svg.SvgElement)]`
  already holds at 1787, so the call site is unchanged.
- Scaladoc: copy the design §0.2 doc comment (lines 58-65) verbatim (8-line block, within the 8-35
  guidance and accurate). No em/en-dashes.
- Call site rewrite (design lines 78-80): replace 1814-1837 with
  `val (rectEl, labelElems) = applyBarChannels(baseRect, mark, row, barX, barW, barY, defaultFill)`
  then `(bars.append((row, rectEl)), labels ++ labelElems)`. The `withHighlight(bars, highlight) ++ labels`
  tail at 1841 is unchanged (highlight still tags the rects via `bars`).

## 3. Reproduce-before-fix angle (PURE REFACTOR — no failing-test-first)

This phase has NO behavior change, so there is nothing to "fail first." The plan (05-plan.yaml P1,
`reproduce_before_fix: false`, `leaves: []`) is explicit: the verification is the **existing** bar
channel tests staying green + byte-identity, NOT a new failing leaf. The failing transition-channel
test (L18) belongs to **P7**, which depends on P1. So the P1 guard contract is:

- **EXISTING regression guards (must stay green, unchanged, byte-identical):**
  - `ChartLowerTest` line **1040** "opacity channel: bar fills are clamped to [0,1] fill-opacity (INV-019)"
    — bar opacity 0.5 + clamp-to-1.0 (this also guards the opacity clamp trap).
  - `ChartLowerTest` line **1055** "label channel: bar emits per-datum Svg.Text elements (INV-019)".
  - `ChartLowerTest` line **1086** the no-channel co-pin ("bar without new channels must still emit rects").
  - Tooltip on bar is exercised through the helper but Test 25 (1065) asserts tooltip on *point*; the
    bar-tooltip byte-identity rides the L8 golden (INV-004) + full-suite run, not a dedicated bar assert.
- **Byte-identity anchor:** L8 / INV-004 golden (`ChartInvariantsTest` golden, `html == expectedGolden`)
  must stay unchanged. The full `kyo-ui/test` run on P1 (or at minimum `ChartLowerTest` +
  `ChartInvariantsTest`) is the byte-identity assertion. Because the extraction is a verbatim move of
  the same expressions in the same order, the rendered SVG is identical by construction; the golden is
  the alarm if any expression silently reorders or drops.

There is no new test to author in P1. Do NOT add a new leaf — that is reward-hacking the plan's
`leaves: []`.

## 4. Verification command (targeted, JVM)

From 05-plan.yaml P1:

```
sbt 'kyo-ui/testOnly kyo.ChartLowerTest'
```

Run also `sbt 'kyo-ui/testOnly kyo.ChartInvariantsTest'` to exercise the INV-004 golden byte-identity
anchor (the plan's P1 command names ChartLowerTest; the golden lives in ChartInvariantsTest, so include
it explicitly for the byte-identity guard). Project id is `kyo-ui` (JVM), per steering — never
`kyo-uiJVM`. Targeted JVM only; cross-platform is the P13 gate.

Stale-artifact trap (00-guides Trap 6): a green with zero "Compiling ChartLower.scala" in the log is
suspect for a source edit — confirm the recompile happened.

## 5. Traps (do not violate — these break byte-identity)

1. **Emission order is load-bearing.** Keep opacity -> tooltip -> label in that exact order, and keep
   the tuple as `(rect-element, labels)`. Bars accumulate via `bars.append((row, rectEl))`, labels via
   `labels ++ labelElems`; the final assembly `withHighlight(bars, highlight) ++ labels` puts all rects
   before all labels. Do not reorder, merge, or interleave.
2. **Opacity clamp 0..1.** Preserve `math.max(0.0, math.min(1.0, fn(row)))` verbatim. The existing test
   asserts 1.7 clamps to 1.0; dropping the clamp silently breaks it.
3. **Label glyph attributes verbatim:** `textAnchor(Svg.TextAnchor.Middle)`,
   `dominantBaseline(Svg.DominantBaseline.Auto)`, position `x = barX + barW/2.0`, `y = barY - 2.0`,
   `fill = Svg.Paint.Color(fill)` (the passed-in fill = `defaultFill` on the static path). Do not change
   the baseline to anything other than `Auto`.
4. **Tooltip = `Svg.title(...)` child** applied via `withOpacity(Svg.title(fn(row)))` (an apply-child,
   not an attribute). Keep it on the opacity-decorated element so ordering is preserved.
5. **Return type must widen to `Svg.SvgElement`** (see §2) — do not annotate the helper's first return
   as `Svg.Rect`; the tooltip arm will not type-check and, if forced, would drop the title child.
6. **Use the passed-in `rect` and `fill` params**, not the outer `baseRect`/`defaultFill`, inside the
   helper body — that is what makes the helper reusable by P7 (which passes a SMIL-animated rect and a
   per-row resolved fill).
7. **Touch ONLY `lowerBarSimple` + the new helper.** Do not edit `lowerBarSimpleWithTransitions` (that
   is P7), `lowerBarGrouped`, or `lowerBarStacked`. No drive-by changes; this is `estimated_loc: 40`.

---

### 3-line summary
P1 extracts `applyBarChannels[A,X,Y](rect, mark, row, barX, barW, barY, fill): (Svg.SvgElement, Chunk[Svg.SvgElement])` from `ChartLower.scala` lines **1814-1837** (current `lowerBarSimple` 1768-1842; drifted +1 vs design's 1815-1836); return type MUST widen to `Svg.SvgElement` because the tooltip arm adds an `Svg.title` child. It is a pure REFACTOR: no failing-test-first (plan `reproduce_before_fix:false`, `leaves:[]`) — the guard is existing bar tests `ChartLowerTest` (opacity 1040, label 1055, no-channel co-pin 1086) staying green plus INV-004 golden byte-identity; verify with `sbt 'kyo-ui/testOnly kyo.ChartLowerTest'` (+ `ChartInvariantsTest` for the golden). Traps: preserve opacity->tooltip->label order, the 0..1 opacity clamp, label `dominantBaseline=Auto`/`textAnchor=Middle`, tooltip-as-title-child, and use the passed-in `rect`/`fill` so P7 can reuse it.
