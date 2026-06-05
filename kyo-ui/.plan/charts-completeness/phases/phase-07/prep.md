# Phase P7 prep — Animated simple-bar opacity/label/tooltip channels (GAP-TRANS-BAR-CHANNELS)

**Gap closed:** GAP-TRANS-BAR-CHANNELS  
**Leaf:** L18 (NEW) — animated simple bar with opacity/label/tooltip channels emits them; no-channel co-pin  
**Source file:** `kyo-ui/shared/src/main/scala/kyo/internal/ChartLower.scala` (4064 lines, post-P1-P4)  
**Test file:** `kyo-ui/shared/src/test/scala/kyo/ChartTransitionTest.scala`  
**Dependency:** must follow P1 (which placed `applyBarChannels` at lines 1883-1916); P5 and P6 are not yet committed and are not dependencies for P7.

---

## 1. Confirmed: `applyBarChannels` — exact signature and COMPLETE channel list

`applyBarChannels` is at **lines 1883-1916** of ChartLower.scala. Current signature (post-P1):

```scala
private def applyBarChannels[A, X, Y](
    rect: Svg.Rect,
    mark: Mark.Bar[A, X, Y],
    row: A,
    barX: Double,
    barW: Double,
    barY: Double,
    fill: Style.Color
)(using Frame): (Svg.SvgElement, Chunk[Svg.SvgElement])
```

**COMPLETE list of channels/attributes applied by `applyBarChannels`:**

1. **opacity** (`mark.opacity: Maybe[A => Double]`) — applies `rect.fillOpacity(clamped_value)` when Present; emitted as `fill-opacity="..."` on the rect.
2. **tooltip** (`mark.tooltip: Maybe[A => String]`) — applies `withOpacity(Svg.title(fn(row)))` when Present; adds a `<title>` child to the rect (SVG tooltip).
3. **label** (`mark.label: Maybe[A => String]`) — emits a sibling `Svg.text` positioned above the bar (`x = barX + barW/2`, `y = barY - 2`, `TextAnchor.Middle`, `DominantBaseline.Auto`, `fill = fill`) when Present; returned in the `Chunk[Svg.SvgElement]` second element.

No other channels are applied. `cornerRadius` and `stroke` are NOT applied by this helper (they do not appear in the P1 body). The three channels above are exactly what the plan/invariants say: "opacity/tooltip/label" (L18 leaf).

---

## 2. Confirmed: animated-bar lowering function and exact lines where bar rect is emitted WITHOUT channels

**Function:** `lowerBarSimpleWithTransitions` — **lines 3309-3362**.

The rect-construction block is at **lines 3346-3358** (inside the `Present(xd)` match arm, inside the `Present(yd)` match arm, inside the `loop` tail-recursive function):

```scala
// lines 3346-3358 — CURRENT CODE (channels NOT applied)
val r: Svg.Rect =
    if !animOk then
        Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
    else
        val (fromH, fromY) = fromGeom.get(key) match
            case Some(MarkGeom.Bar(ph, py)) => (ph, py)
            case _                          => (0.0, baseline) // enter from baseline
        val rectBase = Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
        rectBase(
            smilAnimate("height", fromH, barH, durStr),
            smilAnimate("y", fromY, barY, durStr)
        )
(acc.append(r), newG2)   // line 3358 — r appended directly, no channels, no labels
```

The current loop accumulator signature (line 3324-3328):
```scala
def loop(
    i: Int,
    acc: Chunk[Svg.SvgElement],
    geom: Map[String, MarkGeom]
): (Chunk[Svg.SvgElement], Map[String, MarkGeom]) =
```

The current loop call (line 3361) returns `loop(0, Chunk.empty, newGeom)` with NO labels accumulator.

---

## 3. How `lowerBarSimple` calls `applyBarChannels` — exact destructuring and label threading

From **lines 1860-1872** in `lowerBarSimple`:

```scala
val baseRect = Svg.rect
    .x(barX)
    .y(barY)
    .width(barW)
    .height(barH)
    .fill(Svg.Paint.Color(defaultFill))
    .withAttrs(iAttrs)
val (rectEl, labelElems) = applyBarChannels(baseRect, mark, row, barX, barW, barY, defaultFill)
(bars.append((row, rectEl)), labels ++ labelElems)
```

The loop in `lowerBarSimple` tracks two accumulators:
- `bars: Chunk[(A, Svg.SvgElement)]` — row-tagged rect elements (for `withHighlight`)
- `labels: Chunk[Svg.SvgElement]` — label texts, NOT row-tagged

At end: `withHighlight(bars, highlight) ++ labels` (line 1872).

The transition function does NOT support `highlight` (no `highlight` parameter, no `withHighlight` call). The fix mirrors only the `applyBarChannels` call and label threading, not the `withHighlight` wrapping.

---

## 4. The exact edit: where and how to call `applyBarChannels` from `lowerBarSimpleWithTransitions`

### Step 1 — Restructure the loop to track a `labels` accumulator

The current loop signature has `(i, acc, geom)`. Add `labels: Chunk[Svg.SvgElement]` as the 4th accumulator, mirroring `lowerBarSimple`'s split:

```scala
@scala.annotation.tailrec
def loop(
    i: Int,
    acc: Chunk[Svg.SvgElement],
    geom: Map[String, MarkGeom],
    labels: Chunk[Svg.SvgElement]          // NEW
): (Chunk[Svg.SvgElement], Map[String, MarkGeom]) =
    if i >= rows.size then (acc ++ labels, geom)   // append labels at end
```

### Step 2 — Replace the rect-construction block and accumulation at lines 3346-3358

**Before (current):**
```scala
val r: Svg.Rect =
    if !animOk then
        Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
    else
        val (fromH, fromY) = fromGeom.get(key) match
            case Some(MarkGeom.Bar(ph, py)) => (ph, py)
            case _                          => (0.0, baseline) // enter from baseline
        val rectBase = Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
        rectBase(
            smilAnimate("height", fromH, barH, durStr),
            smilAnimate("y", fromY, barY, durStr)
        )
(acc.append(r), newG2)
```

**After (fix):**
```scala
val baseRect = Svg.rect.x(barX).y(barY).width(barW).height(barH).fill(Svg.Paint.Color(defaultFill))
val (channelRect, labelEls) = applyBarChannels(baseRect, mark, row, barX, barW, barY, defaultFill)
val r: Svg.SvgElement =
    if !animOk then channelRect
    else
        val (fromH, fromY) = fromGeom.get(key) match
            case Some(MarkGeom.Bar(ph, py)) => (ph, py)
            case _                          => (0.0, baseline) // enter from baseline
        // channelRect is a Svg.Rect at runtime (applyBarChannels returns the rect with opacity/title
        // applied; only .fillOpacity and .apply(ShapeChild) are called, both returning Svg.Rect).
        // Cast back to Svg.Rect so we can attach the SMIL animate children.
        // Children order: tooltip (<title>) was added first by applyBarChannels, animates follow.
        channelRect.asInstanceOf[Svg.Rect](
            smilAnimate("height", fromH, barH, durStr),
            smilAnimate("y", fromY, barY, durStr)
        )
(acc.append(r), newG2, labels ++ labelEls)
```

### Step 3 — Update the base loop call

```scala
loop(0, Chunk.empty, newGeom, Chunk.empty)
```

### Why `asInstanceOf[Svg.Rect]` is safe here

`applyBarChannels` receives a `Svg.Rect` and applies:
- `rect.fillOpacity(...)` — defined on `HasOpacity`, returns the concrete type via covariant `Self` (for `Rect`, `Self = Rect`); returns `Svg.Rect`.
- `withOpacity(Svg.title(fn(row)))` — calls `Rect.apply(cs: ShapeChild*)` which returns `Rect`.
- If both channels are Absent, returns the original `rect: Svg.Rect` unchanged.

All paths return a `Svg.Rect` at runtime. The declared return type `Svg.SvgElement` is a widening; the cast recovers the concrete type. This is the minimal-intrusion approach that avoids changing `applyBarChannels`'s signature (which is shared with `lowerBarSimple` and must not be disturbed).

An alternative avoiding the cast: pass `baseRect` to `applyBarChannels`, add SMIL animates to `baseRect` for the animated arm, and merge by calling `applyBarChannels` twice or restructuring the helper. The cast is preferable — it keeps the shared helper unchanged (DRY per P1's whole point) and is a single-site narrow pattern.

### Interaction between channel attributes and animation attributes on the same rect

- **fill-opacity + `<animate>`:** No conflict. `fill-opacity` is a CSS property on the rect; the SMIL `<animate>` elements animate `height` and `y` geometry attributes. They coexist on the same `<rect>` element without interference.
- **`<title>` + `<animate>`:** Both are children of the `<rect>`. With the "apply channels first, then SMIL" ordering: tooltip `<title>` is added first by `applyBarChannels`; SMIL `<animate>` children are added after. Child order: `[<title>, <animate height>, <animate y>]`. The browser renders the tooltip from `<title>` independently of SMIL animation; there is no conflict.
- **The no-channel case:** When `mark.opacity`, `mark.tooltip`, and `mark.label` are all Absent, `applyBarChannels` returns `(baseRect, Chunk.empty)` with the rect unchanged and no label. The cast recovers `baseRect` as-is, then SMIL animates are added exactly as before. Animated output for a no-channel bar is **byte-identical** to today — the co-pin requirement is satisfied.

---

## 5. Reproduce-before-fix test (L18) — exact test for `ChartTransitionTest`

Add this test to `ChartTransitionTest.scala`, after the existing 8 tests (append before the closing `end class` or last `}`). This is the L18 leaf from `04-invariants.md §E`.

```scala
// ---- Leaf L18 (GAP-TRANS-BAR-CHANNELS): animated bar emits opacity/label/tooltip channels ----

"animated bar emits opacity/label/tooltip channels matching the static path (L18, GAP-TRANS-BAR-CHANNELS)" in run {
    // Static bar (no Signal ref): lowerBarSimple -> applyBarChannels -> channels applied.
    // Animated bar (Signal ref + .animate): lowerBarSimpleWithTransitions -> was missing applyBarChannels.
    // Both must emit fill-opacity="0.5", a <title> tooltip child, and a sibling label <text>.
    //
    // Scale: linear(0, 4000), baseline=440.
    //   rev=2000: barY=230, barH=210
    //   barX = plotX = 60 (single band), barW = plotW = 560 (single category)
    //   labelX = barX + barW/2 = 60 + 280 = 340, labelY = barY - 2 = 228
    val rows = Chunk(Sale("Jan", Rev(2000.0)))
    for
        ref <- Signal.initRef(rows)
        animSpec = UI.chart(ref: Signal[Chunk[Sale]])(
            bar(
                x       = _.month,
                y       = _.revenue,
                opacity = _ => 0.5,
                label   = _.month,
                tooltip = _.month
            )
        ).yScale(_.linear(0.0, 4000.0))
            .animate(_.ease(300.millis))
        animRoot = summon[Conversion[ChartSpec[Sale], Svg.Root]](animSpec)
        // First render (ENTER): bar is new, from=0 to=210 height, from=440 to=230 y.
        html <- HtmlRenderer.render(animRoot, Seq.empty)
    yield
        // (a) fill-opacity channel: the animated rect must carry fill-opacity="0.5".
        assert(
            html.contains("fill-opacity=\"0.5\""),
            s"Animated bar must carry fill-opacity=0.5 (opacity channel dropped before fix):\n$html"
        )
        // (b) tooltip channel: the animated rect must contain a <title>Jan</title> child.
        assert(
            html.contains("<title>Jan</title>"),
            s"Animated bar must contain <title>Jan</title> (tooltip channel dropped before fix):\n$html"
        )
        // (c) label channel: a sibling label <text>Jan</text> must be present.
        assert(
            html.contains(">Jan<"),
            s"Animated bar must emit a label text >Jan< (label channel dropped before fix):\n$html"
        )
        // (d) SMIL animates still present (animation not disturbed): both <animate> children preserved.
        assert(
            html.contains("attributeName=\"height\""),
            s"Animated bar must still carry attributeName=height SMIL animate:\n$html"
        )
        assert(
            html.contains("attributeName=\"y\""),
            s"Animated bar must still carry attributeName=y SMIL animate:\n$html"
        )
        // (e) ENTER animate values: from=0 to=210 (height), from=440 to=230 (y).
        assert(
            html.contains("from=\"0\"") && html.contains("to=\"210\""),
            s"Animated bar ENTER must have from=0 to=210 height animate:\n$html"
        )
        assert(
            html.contains("from=\"440\"") && html.contains("to=\"230\""),
            s"Animated bar ENTER must have from=440 to=230 y animate:\n$html"
        )
    end for
}

// ---- L18 co-pin: no-channel animated bar is byte-identical to today (L8 co-pin arm for transitions) ----

"no-channel animated bar is byte-identical through the fix (L18 co-pin)" in run {
    // A bar with NO opacity/label/tooltip channels. After the fix, applyBarChannels returns the rect
    // unchanged (Absent arms for all three channels) and an empty label Chunk. The SMIL animates are
    // attached as before. Output must be byte-identical to the pre-fix animated bar.
    val rows = Chunk(Sale("Jan", Rev(1000.0)))
    for
        ref <- Signal.initRef(rows)
        spec = UI.chart(ref: Signal[Chunk[Sale]])(bar(x = _.month, y = _.revenue))
            .yScale(_.linear(0.0, 4000.0))
            .animate(_.ease(300.millis))
        root = summon[Conversion[ChartSpec[Sale], Svg.Root]](spec)
        html <- HtmlRenderer.render(root, Seq.empty)
    yield
        // No fill-opacity attribute (opacity channel absent).
        assert(
            !html.contains("fill-opacity"),
            s"No-channel animated bar must NOT carry fill-opacity (Absent arm):\n$html"
        )
        // No <title> child (tooltip absent).
        assert(
            !html.contains("<title>"),
            s"No-channel animated bar must NOT carry <title> (Absent arm):\n$html"
        )
        // SMIL animates still present.
        assert(
            html.contains("attributeName=\"height\"") && html.contains("attributeName=\"y\""),
            s"No-channel animated bar must still carry SMIL animates:\n$html"
        )
        // ENTER animate for Jan rev=1000: barH=105, barY=335.
        assert(
            html.contains("from=\"0\"") && html.contains("to=\"105\""),
            s"No-channel animated bar ENTER must have from=0 to=105 height:\n$html"
        )
    end for
}
```

**Failure mode before fix:** The first test (`L18`) fails because `html` does not contain `fill-opacity="0.5"` (opacity dropped), does not contain `<title>Jan</title>` (tooltip dropped), and does not contain `>Jan<` for the label text. The second test (`co-pin`) passes today and must stay green after the fix.

**Cross-check (static/animated parity):** The test uses `html.contains(...)` assertions on rendered HTML from `HtmlRenderer.render(root, Seq.empty)`. The existing `ChartTransitionTest` already uses this pattern (e.g. line 113: `html1.contains("from=\"105\"")`). No new helpers needed.

---

## 6. Verification command

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"
sbt 'kyo-ui/testOnly kyo.ChartTransitionTest kyo.ChartLowerTest kyo.ChartInvariantsTest'
```

- `ChartTransitionTest`: exercises L18 (NEW) + L18 co-pin (NEW) + all 8 pre-existing transition tests (co-pin: tests 1-6 from original suite + FIX-B animated colorScale line + curved-path morph).
- `ChartLowerTest`: guards the static `lowerBarSimple` path (INV-019 opacity/label/tooltip tests at ~lines 1100-1142) — confirms the shared helper extraction is not broken by the P7 changes.
- `ChartInvariantsTest`: guards INV-004 golden (byte-identity for the no-colorScale, no-channel, no-animation static path).

---

## 7. Traps

**Trap 1 — DRY: never inline a copy of the channel logic.** The entire point of P1 was to extract `applyBarChannels` so both static and transition paths share one implementation. P7 must CALL `applyBarChannels`, never reproduce the opacity/tooltip/label logic inline inside `lowerBarSimpleWithTransitions`. Inlining would silently undo the refactor and re-create the original drift risk.

**Trap 2 — Preserve animation attributes byte-identical.** After the fix, the SMIL `<animate>` children (`attributeName="height"` and `attributeName="y"`) must be present for the animated arm exactly as before. The co-pin test verifies this. The `asInstanceOf[Svg.Rect]` cast is safe (see §4 justification) and lets `.apply(smilAnimate(...), smilAnimate(...))` attach animates to the channel-decorated rect.

**Trap 3 — Loop accumulator arity change.** The `loop` function currently takes 3 params `(i, acc, geom)`. After the fix it takes 4 `(i, acc, geom, labels)`. Update ALL recursive `loop(...)` call sites within the function: the base case `if i >= rows.size then (acc ++ labels, geom)`, the Absent-domain early-continue `(acc, geom, labels)`, and the inner `end match` fallback `(acc, geom, labels)`. Missing any of them causes a compile error (wrong arity) or labels leak.

**Trap 4 — The `!animOk` rect: channels must also be applied.** The `!animOk` arm currently builds `Svg.rect.x(...).fill(...)` directly. After the fix, it too must pass through `applyBarChannels`. The new structure builds `baseRect` once, calls `applyBarChannels(baseRect, ...)` unconditionally, then branches on `animOk` only to decide whether to add SMIL animates. This is cleaner than branching before the channel call.

**Trap 5 — The no-channel transitions tests stay green (L8 co-pin for transitions).** The existing 6 transition tests (Tests 1-6) use `bar(x = _.month, y = _.revenue)` with NO `opacity`/`label`/`tooltip`. After the fix, `applyBarChannels` returns the rect unchanged (all Absent arms) and `Chunk.empty` labels; `labels ++ Chunk.empty` = no labels appended; `acc ++ Chunk.empty` = same output. The HTML substring assertions in Tests 1-6 (`from="..."`, `to="..."`, `attributeName="height"`) are unchanged. These tests must stay green — run the full `ChartTransitionTest` suite, not just L18.

**Trap 6 — `lowerBarSimpleWithTransitions` does NOT call `withHighlight`.** The transition path has no `highlight` parameter and no `withHighlight` call. Do NOT add one. The fix is limited to `applyBarChannels` + label accumulation, mirroring `lowerBarSimple`'s channel application only. The highlight functionality is out of scope for P7 (it belongs to P9 if transitions support is added there, per the design).

---

## 3-line summary

The animated-bar lowering function is `lowerBarSimpleWithTransitions` at **lines 3309-3362**, with the rect built at lines 3346-3358 — both the `!animOk` arm (plain rect) and the `animOk` arm (rect with SMIL animate children) emit the rect without calling `applyBarChannels`, so all three channels (opacity, tooltip as `<title>` child, label as sibling `<text>`) are silently dropped.

`applyBarChannels` (lines 1883-1916, placed by P1) applies exactly three channels: `mark.opacity` as `fill-opacity`, `mark.tooltip` as a `<title>` child, `mark.label` as a sibling `Svg.text`; the fix restructures the loop to accept a `labels` accumulator (4th param), calls `applyBarChannels(baseRect, ...)` before the `animOk` branch, casts the channel-decorated element back to `Svg.Rect` in the animated arm (safe: all `applyBarChannels` paths return `Svg.Rect` at runtime), and appends SMIL animates after — ordering `[<title>, <animate height>, <animate y>]`, preserving animation attributes byte-identical.

Test file is `kyo-ui/shared/src/test/scala/kyo/ChartTransitionTest.scala`; add two tests: L18 asserting the three channel attributes (`fill-opacity="0.5"`, `<title>Jan</title>`, label `>Jan<`) plus animation attribute co-pins (`attributeName="height"`, `attributeName="y"`, ENTER `from/to` values), and L18-co-pin asserting a no-channel animated bar carries no `fill-opacity` / no `<title>` while still carrying SMIL animates.
