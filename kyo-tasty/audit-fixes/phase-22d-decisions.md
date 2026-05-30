# Phase 22d Decisions

## Fixture construction approach

Synthetic symbol chain. No real classpath fixture was needed or used. The
`computeBinaryName` function in `Tasty.Symbol` is a pure owner-chain walk
with no classpath I/O, so a synthetic chain built with `makeRoot`,
`makeClass`, and the existing `Symbol.make` helpers is sufficient and
cross-platform (jvm, js, native).

The chain is: root (Package, null owner) -> A (Class) -> B (Class) ->
C (Class) -> D (Class) -> E (Class). Five nesting levels exercise the
recursive `$` separator logic in `computeBinaryName`.

## Package or top-level for A

A is top-level: its owner is the root sentinel (a Package with empty name and
null owner). This means no package prefix appears in the binary name. The
`computeFullName` loop terminates when it reaches the root, and the filtered
parts list starts with "A" directly, so the binary name has no `/` separator
at all.

## Separator chosen for binary name

`$` between every segment. `computeBinaryName` appends `$` whenever the
previous segment's kind is `Class`, `Trait`, or `Object`. Because every
symbol in the chain (A through E) has `SymbolKind.Class`, all four
transitions produce `$`, yielding `"A$B$C$D$E"`.

## Result

The assertion `eSym.binaryName == "A$B$C$D$E"` passes on all three
platforms (jvm, js, native). 467 tests pass total.
