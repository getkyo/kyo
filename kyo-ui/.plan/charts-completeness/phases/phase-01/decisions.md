# Phase P1 decisions

## D1: Added `(using Frame)` to `applyBarChannels`

The design signature in `02-design.md` §0.2 does not show a `using Frame` clause. However, the
helper body calls `Svg.title(...)` and `Svg.text` (lines 1845/1853), both of which require a
`Frame` context (the compiler rejected the helper without it). The original inline block inside
`lowerBarSimple` was implicitly covered by `lowerBarSimple`'s own `(using Frame)` clause. Adding
`(using Frame)` to the extracted helper is correct per CLAUDE.md: "Frame required on every
effectful method." The call site in `lowerBarSimple` already has `Frame` in scope, so no change
needed there. Behavior is identical.

## D2: Helper placed immediately after `lowerBarSimple` (not before)

Prep §2 says "immediately before line 1768 or immediately after line 1842." Placement after
`lowerBarSimple` (after `end lowerBarSimple`) was chosen because the helper is logically a
factoring-out of code that was inside `lowerBarSimple`; placing it right below makes the
relationship visible to the reader. P7 will then add a second call from
`lowerBarSimpleWithTransitions`.

## Verification result

`sbt 'kyo-ui/testOnly kyo.ChartLowerTest kyo.ChartInvariantsTest'`

- Tests run: 98
- Succeeded: 98, Failed: 0, Canceled: 0, Ignored: 0, Pending: 0
- All passed, including:
  - INV-019 opacity clamp test (line 1040)
  - INV-019 label channel test (line 1055)
  - INV-019 no-channel co-pin test (line 1086)
  - INV-004 golden byte-identity (ChartInvariantsTest)
- Recompile confirmed: "compiling 1 Scala source" for ChartLower.scala appeared in the log.
