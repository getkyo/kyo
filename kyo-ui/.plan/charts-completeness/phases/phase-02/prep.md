# Phase P2 prep: extract shared tick-label chrome

## 1. Exact current loci (fresh read, ChartLower.scala = 4011 lines)

All line numbers are from the current worktree. Design estimates were +/-2 accurate.

### `buildYAxis` (LOWER:862-954)

```
862  private def buildYAxis(layout, ys, cfg, isRight, theme, chrome, gridColor)
870  )(using Frame): Chunk[Svg.SvgElement] =
871  val ticks  = ys.ticks(cfg.tickCount)
872  val axisX  = if isRight then ... else ...
873  val labelX = if isRight then axisX + TickLen + 4.0 else axisX - TickLen - 4.0
874  val anchor = if isRight then Svg.TextAnchor.Start else Svg.TextAnchor.End
     [no withFont; no tickRotation; no tickAnchor cfg]
909  val tickLabel: Svg.SvgElement =
910      Svg.text
911          .x(labelX)
912          .y(py)
913          .textAnchor(anchor)               <- hardcoded side-default (End/Start)
914          .dominantBaseline(Svg.DominantBaseline.Middle)
915          .fill(Svg.Paint.Color(chrome))
916          .apply(labelStr)
917  val elements = grid match ...
921  val base = loop(0, Chunk.empty)
927  cfg.axisLabel match
930      val midY = ...
931      if isRight then ...
932          Svg.text...rotate(90.0, ...)     <- title, no withFont
942      else
943          Svg.text...rotate(-90.0, ...)    <- title, no withFont
954  end buildYAxis
```

Key observations:
- `buildYAxis` tick label (909-916): no `withFont`, no rotation, no `cfg.tickAnchor` -- uses raw side-default `anchor` (874).
- `buildYAxis` axis title (927-952): no `withFont`.
- `buildYAxis` passes `chrome` as a parameter (caller passes `leftChrome`/`rightChrome` which are already resolved from theme/spec).

### `buildXAxis` (LOWER:957-1034)

```
957  private def buildXAxis(layout, xs, cfg, theme)
963  val ticks   = xs.ticks(cfg.tickCount)
964  val axisY   = layout.plotBaseline
965  val chrome  = axisChromeColor(theme)         <- resolved inline
966  val gridCol = gridlineColor(theme)
967  val labelY  = axisY + TickLen + 4.0
968  // D17: map the configured tick anchor to the SVG text-anchor token.
969  val svgAnchor = cfg.tickAnchor match
970      case TextAnchor.Start  => Svg.TextAnchor.Start
971      case TextAnchor.Middle => Svg.TextAnchor.Middle
972      case TextAnchor.End    => Svg.TextAnchor.End
973
974  // D25: optional theme font on tick labels.
975  def withFont(t: Svg.Text): Svg.Text =
976      val t1 = theme.fontFamily.fold(t)(f => t.fontFamily(f))
977      theme.fontSize.fold(t1)(px => t1.fontSize(Svg.SvgLength.Px(px)))
978
979  @tailrec
980  def loop(i, acc): ...
...
985  val withGrid = ...          <- gridline (x-specific: vertical, x1==x2)
996  val tickMark: Svg.SvgElement = Svg.line...  <- tick mark (x-specific)
1002 val xLabelStr = cfg.tickFormat match ...
1005 val tickLabelBase: Svg.Text =
1006     withFont(
1007         Svg.text
1008             .x(px)
1009             .y(labelY)
1010             .textAnchor(svgAnchor)         <- cfg.tickAnchor mapped above
1011             .dominantBaseline(Svg.DominantBaseline.Hanging)
1012             .fill(Svg.Paint.Color(chrome))
1013     ).apply(xLabelStr)
1014 // D17: rotate the label about its anchor point when a rotation is configured.
1015 val tickLabel: Svg.SvgElement =
1016     if cfg.tickRotation != 0.0 then
1017         tickLabelBase.transform(Svg.Transform.Rotate(cfg.tickRotation, Present(px), Present(labelY)))
1018     else tickLabelBase
1019 loop(i + 1, withGrid.append(tickMark).append(tickLabel))
1020 val base = loop(0, Chunk.empty)
1021 // axis label
1022 cfg.axisLabel match
1023     case Present(lbl) =>
1024         val labelElem: Svg.SvgElement =
1025             Svg.text
1026                 .x(layout.plotX + layout.plotW / 2.0)
1027                 .y(layout.svgH - 4.0)
1028                 .textAnchor(Svg.TextAnchor.Middle)
1029                 .fill(Svg.Paint.Color(chrome))
1030                 .apply(lbl)
1031         base.append(labelElem)
1032     case Absent => base
1034 end buildXAxis
```

Key observations:
- `svgAnchor` mapping: 969-972 (4 lines).
- `withFont` local closure: 975-977 (3 lines; captures `theme` from outer param).
- `tickLabelBase` construction with `withFont` applied: 1005-1013.
- `tickLabel` rotation gate: 1015-1018.
- The loop appends: `withGrid.append(tickMark).append(tickLabel)` at 1019 (order: grid, then tickMark, then tickLabel).
- Axis title (1022-1031): currently NO `withFont` -- this is part of P8's fix, not P2's scope; P2 only extracts `tickLabel` and hoists `withFont` as a reusable method. P2 does NOT add `withFont` to the title yet (that is P8).

### `buildLegend` / `buildLegendItems` (LOWER:1047-1084, 1434-1501)

Legend label text (1487-1494): no `withFont`. Applying `withFont` to legend text is P8's scope, not P2.

### `applyBarChannels` (LOWER:1830-1863) -- already extracted by P1

P1 is already committed (per campaign workflow). P2 is independent of P1.

---

## 2. Signatures and home for the two extracted pieces

Both are placed ABOVE `buildXAxis` (i.e., before line 957), private to the object. They are pure helpers with no effect row.

### `toSvgAnchor` (hoisted from 969-972)

```scala
/** Map a configured `TextAnchor` to the SVG text-anchor token.
  *
  * Used by `tickLabel` and by `buildXAxis` when setting the anchor on x tick labels.
  * Callers that need a side-default anchor (e.g. `buildYAxis`) resolve it before calling
  * `tickLabel`, passing the already-resolved `Svg.TextAnchor` directly.
  */
private def toSvgAnchor(a: TextAnchor): Svg.TextAnchor =
    a match
        case TextAnchor.Start  => Svg.TextAnchor.Start
        case TextAnchor.Middle => Svg.TextAnchor.Middle
        case TextAnchor.End    => Svg.TextAnchor.End
```

Home: private method on the `ChartLower` object (or inner object, matching existing style), placed directly before the `buildXAxis` definition.

### `withFont` (hoisted from 975-977)

```scala
/** Apply theme font family and font size to an Svg.Text element when the theme sets them.
  *
  * A no-op when both `theme.fontFamily` and `theme.fontSize` are `Absent`; output is
  * byte-identical to today in that case. Called from `tickLabel` and directly from axis-title
  * and legend-text sites (which are not rotated and are not passed through `tickLabel`).
  */
private def withFont(theme: Theme, t: Svg.Text): Svg.Text =
    val t1 = theme.fontFamily.fold(t)(f => t.fontFamily(f))
    theme.fontSize.fold(t1)(px => t1.fontSize(Svg.SvgLength.Px(px)))
```

Note: the local closure at LOWER:975-977 captures `theme` implicitly. The hoisted method takes `theme` as an explicit parameter. The call site in `buildXAxis` changes from `withFont(...)` to `withFont(theme, ...)`. This is the ONLY behavioral difference at the call site; the computation is identical.

### `tickLabel` (hoisted from 1005-1018)

```scala
/** Apply theme font, configured anchor, and configured rotation to a tick-label text element.
  *
  * Builds an `Svg.text` at `(x, y)` for `labelStr`, fills it with `chrome`, sets
  * `dominantBaseline`, applies `theme.fontFamily`/`theme.fontSize` when set (via `withFont`),
  * sets `anchor` as the SVG text-anchor, and rotates about `(x, y)` when `cfg.tickRotation !=
  * 0.0`. This is the single tick-label chrome path shared by `buildXAxis` and (in P8) `buildYAxis`
  * so the two axes cannot drift.
  *
  * `anchor` is CALLER-RESOLVED: `buildXAxis` passes `toSvgAnchor(cfg.tickAnchor)`; `buildYAxis`
  * passes its side-default (`Svg.TextAnchor.End` for left, `Svg.TextAnchor.Start` for right)
  * unless the user set `cfg.tickAnchor` explicitly (the P8 override logic). This design keeps the
  * helper anchor-agnostic and avoids the helper reading `cfg` for a side-dependent default it
  * cannot know.
  *
  * `baseline` is also caller-supplied: x-axis uses `Svg.DominantBaseline.Hanging` (label below
  * the tick mark); y-axis uses `Svg.DominantBaseline.Middle` (label beside the tick mark).
  */
private def tickLabel(
    x: Double,
    y: Double,
    labelStr: String,
    chrome: Style.Color,
    baseline: Svg.DominantBaseline,
    anchor: Svg.TextAnchor,
    cfg: AxisConfig,
    theme: Theme
): Svg.SvgElement =
    val base: Svg.Text =
        withFont(
            theme,
            Svg.text
                .x(x)
                .y(y)
                .textAnchor(anchor)
                .dominantBaseline(baseline)
                .fill(Svg.Paint.Color(chrome))
        ).apply(labelStr)
    if cfg.tickRotation != 0.0 then
        base.transform(Svg.Transform.Rotate(cfg.tickRotation, Present(x), Present(y)))
    else base
```

Design note vs. `02-design.md §0.3`: the design showed `cfg: AxisConfig` as the parameter, with the body calling `toSvgAnchor(cfg.tickAnchor)` internally. After re-reading the actual `buildYAxis` code (874: the side-default anchor is computed from `isRight`, not from `cfg.tickAnchor`), the CORRECT design is to take a PRE-RESOLVED `anchor: Svg.TextAnchor` param (as the task statement says), NOT to call `toSvgAnchor(cfg.tickAnchor)` inside. The `cfg` param is still needed for `cfg.tickRotation`. This matches the task specification exactly: "the `tickLabel` helper should take a PRE-RESOLVED `anchor: Svg.TextAnchor` param rather than reading cfg itself". The `02-design.md` body example (which calls `toSvgAnchor(cfg.tickAnchor)` inline) is OVERRIDDEN by this prep finding -- the correct signature passes `anchor` resolved by the caller.

### Revised call site in `buildXAxis` after extraction

The local `svgAnchor` val (969-972) becomes `toSvgAnchor(cfg.tickAnchor)` passed directly to `tickLabel`. The local `withFont` def (975-977) is deleted. The `tickLabelBase` + `tickLabel` block (1005-1018) becomes a single call:

```scala
val tickLabelElem: Svg.SvgElement =
    tickLabel(px, labelY, xLabelStr, chrome,
              Svg.DominantBaseline.Hanging,
              toSvgAnchor(cfg.tickAnchor), cfg, theme)
loop(i + 1, withGrid.append(tickMark).append(tickLabelElem))
```

The loop append order is PRESERVED: `withGrid.append(tickMark).append(tickLabelElem)` -- identical to line 1019 today. This is critical for byte-identity (INV-004 golden).

### What is NOT extracted (stays inline in `buildXAxis`)

- Gridline emission (985-995): x-specific vertical gridlines, stays inline.
- Tick-mark line (996-1000): x-specific, stays inline.
- Axis title (1022-1031): currently NO `withFont`, stays as-is in P2 (P8 adds `withFont` to titles).

### What is NOT extracted from `buildYAxis`

P2 does NOT touch `buildYAxis` at all. The y-axis tick label at 909-916 keeps its current form. P8 routes it through `tickLabel`. This is intentional: P2 proves x output is byte-identical BEFORE y is changed.

---

## 3. Reproduce-before-fix angle

P2 is a PURE REFACTOR. `reproduce_before_fix: false` for this phase -- no new failing test is introduced first, because there is no broken behavior to reproduce. The guard is the EXISTING x-axis tests:

- `ChartAxisTest:972` -- "xAxis(_.rotateTicks(-45)) gives every x tick label a Rotate(-45) transform" (L17 CO-PIN).
- `ChartAxisTest:987` -- "xAxis(_.anchor(TextAnchor.End)) sets text-anchor=end on x tick labels" (L17 CO-PIN).
- `ChartInvariantsTest:114` -- INV-004 golden `html == ChartInvariantsTest.expectedGolden` (byte-identity anchor, L8 CO-PIN).

There is currently NO existing test for `theme.font`/`theme.fontSize` on x-axis tick labels. The design says "x-tick font test" as part of L17, but the actual ChartAxisTest has no such test today (confirmed by grep). P2 does NOT add L14/L15/L16 tests -- those are P8's NEW leaves. P2's guard is the rotation + anchor x-tests plus the INV-004 golden. If the extracted `withFont` with an Absent theme is a no-op (as it must be), those tests stay green. The Absent-theme case is self-proving: `theme.fontFamily.fold(t)(...)` returns `t` unchanged when `fontFamily` is `Absent`, which is the default.

---

## 4. Verification command

```
sbt 'kyo-ui/testOnly kyo.ChartAxisTest kyo.ChartInvariantsTest'
```

Both classes run on JVM only (targeted per-phase). `ChartAxisTest` covers the x-axis rotateTicks/anchor tests (L17 CO-PIN); `ChartInvariantsTest` covers the INV-004 golden (L8 CO-PIN byte-identity).

---

## 5. Traps

### Trap 1: Emission order must be preserved byte-exactly

The loop at LOWER:1019 appends: `withGrid.append(tickMark).append(tickLabelElem)`. After extraction, the single call to `tickLabel(...)` replaces the `tickLabelBase` + `tickLabel` block, but the APPEND ORDER stays identical. Do not reorder to `withGrid.append(tickLabelElem).append(tickMark)` or similar. The INV-004 golden will catch any reordering.

### Trap 2: Rotation origin is `Present(px), Present(labelY)` -- both must be `Present`

`Svg.Transform.Rotate(cfg.tickRotation, Present(x), Present(y))` -- the cx/cy are `Present`, not `Absent`. The extraction must pass `Present(x)` and `Present(y)`, not bare values. Passing `Absent` would silently produce a rotation around the SVG origin instead of around the tick anchor point, breaking the visual output while potentially not breaking the string-equality golden (the golden chart has no `rotateTicks`).

### Trap 3: `withFont` is a no-op when both theme fields are `Absent` -- do not add a guard

Do not add `if theme.fontFamily.isDefined || theme.fontSize.isDefined then withFont(theme, t) else t`. The `fold` calls already short-circuit to the identity when `Absent`. Adding an outer guard introduces a branch that could diverge if the `fold` semantics ever change, and makes the code harder to follow. Keep the body as two `fold` calls exactly mirroring LOWER:975-977.

### Trap 4: Do not touch `buildYAxis` tick labels (909-916) in P2

P2 is an enabling refactor. `buildYAxis` tick labels stay on their current inline path (909-916). Only P8 routes them through `tickLabel`. If an agent accidentally routes `buildYAxis` through `tickLabel` in P2 and adds the y-anchor override logic prematurely, the P8 reproduce-before-fix sequence (L14/L15 must FAIL before the fix) will be broken.

### Trap 5: Do not add `withFont` to axis titles or legend text in P2

The design says "A separate one-liner `withFont(theme, t)` is also applied to axis TITLES ... and legend text ... Titles and legend text use the existing anchors and are not rotated, so they call `withFont` directly, not the full `tickLabel` helper." This is P8 work. In P2, the x-axis title (1022-1031) stays WITHOUT `withFont`. The INV-004 golden will catch accidental title changes.

### Trap 6: `withFont` takes `theme` as explicit param -- update the call site signature

The current local `def withFont(t: Svg.Text): Svg.Text` closes over `theme` from the outer function scope. The hoisted private method `def withFont(theme: Theme, t: Svg.Text): Svg.Text` takes `theme` explicitly. The call site in `buildXAxis` changes from `withFont(Svg.text...)` to `withFont(theme, Svg.text...)`. Missing this update causes a compile error (method not found with 1 arg), which is safe-to-fail -- but confirm the call site is updated.

### Trap 7: `toSvgAnchor` and `withFont` must not be `inline`

Per CONTRIBUTING.md, `inline` is only for effect suspension paths. These are pure helpers on non-effectful values. Do not mark them `inline`.

### Trap 8: `buildXAxis` passes `theme` as a parameter; `buildYAxis` passes `theme` also

`buildXAxis(layout, xs, cfg, theme)` -- `theme` is the 4th param. `buildYAxis(layout, ys, cfg, isRight, theme, chrome, gridColor)` -- `theme` is the 5th. Both methods already have `theme` in scope. The hoisted `withFont(theme, t)` call in `buildXAxis` uses the same `theme` param it already had; no new parameter threading is needed for P2. (P8 calls `tickLabel(...)` in `buildYAxis`, which also already has `theme` in scope.)

---

## Summary

P2 extracts three small pure pieces from `buildXAxis`: a `toSvgAnchor` one-liner (4 lines), a `withFont(theme, t)` 2-line helper, and a `tickLabel(x, y, labelStr, chrome, baseline, anchor, cfg, theme)` 10-line body -- all placed above `buildXAxis` as private methods. `buildXAxis` is updated to call them; its SVG emission is byte-identical. `buildYAxis` is untouched. No new test is written in P2; the guard is the existing x-rotation/anchor tests plus INV-004 golden. The critical design decision is that `tickLabel` takes a pre-resolved `anchor: Svg.TextAnchor` (not `cfg.tickAnchor` internally) so P8 can pass the side-default anchor for `buildYAxis` without guessing.
