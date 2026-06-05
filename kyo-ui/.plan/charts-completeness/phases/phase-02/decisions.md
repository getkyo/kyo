# Phase P2 decisions

## D-P2-01: `tickLabel` requires `using Frame`

The prep doc specified `tickLabel(...)` with no `using` clause. However `Svg.text` (Svg.scala:44)
is declared `def text(using Frame): Text`, so any caller of `Svg.text` must have a `Frame` in
scope. The extracted `tickLabel` helper calls `Svg.text` internally, making `(using Frame)`
mandatory. The signature was updated to:

```scala
private def tickLabel(...)(using Frame): Svg.SvgElement
```

This matches the `using Frame` already present on `buildXAxis` and `buildYAxis` (i.e., the
`Frame` propagates from those callers). No behavioral difference; the first compile attempt
produced `Frame cannot be derived within the kyo package`, confirming the fix.

## D-P2-02: `buildXAxis` call-site formatted by scalafmt

scalafmt re-indented the `tickLabel(...)` call from a single-line form to a multi-line form with
each argument on its own line. The call is semantically identical; the re-read after build was
done to confirm. Append order `withGrid.append(tickMark).append(tickLabelElem)` is preserved
exactly (INV-004 guard).

## D-P2-03: three pre-existing exhaustivity warnings are NOT from this phase

The compile produced three `[E029] Pattern Match Exhaustivity Warning` at lines 2076, 2938, 2964.
These are in `lowerBarStacked` and `lowerBarSimpleWithTransitions` -- unchanged code, not touched
by P2. They were present before P2; no action taken.

## D-P2-04: `buildYAxis` is fully untouched

Confirmed by re-read: lines 862-954 are byte-identical to before the edit. The local `val anchor`
(874), inline tick label (909-916), and axis title (927-952) are all unchanged. P8 routes these
through `tickLabel`.

## Verification result

`sbt 'kyo-ui/testOnly kyo.ChartAxisTest kyo.ChartInvariantsTest kyo.ChartLowerTest'`
-> 132 tests, 0 failed, 0 pending. INV-004 golden byte-identical (no x-axis output changed).
ChartLower.scala was recompiled (confirmed in log: "compiling 1 Scala source").
