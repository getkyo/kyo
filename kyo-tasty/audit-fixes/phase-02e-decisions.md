# Phase 02e decisions

## D1: addrMap visibility change (Tasty.scala line 859)

BEFORE: `def addrMap(using AllowUnsafe): IntMap[Tasty.Symbol]`
AFTER: `private[kyo] def addrMap: IntMap[Tasty.Symbol]` with `import AllowUnsafe.embrace.danger` inside the body.

The change follows the plan's BEFORE/AFTER block exactly. The accessor no longer requires the caller to supply `AllowUnsafe`; instead the body self-supplies proof via `import danger`, which is permitted for private[kyo] internal-only accessors at a §839 case 3 boundary. All 47 callsites are in `kyo.*` or `kyo.internal.*` so `private[kyo]` lets them compile unchanged. The `(using AllowUnsafe)` parameter that callers previously supplied is simply no longer required; no callsite needs editing.

## D2: Stale comment update (Tasty.scala line 552)

The OnceCell init lambda in `_bodyOnce` previously commented "AllowUnsafe is needed for TastyOrigin.addrMap SingleAssign read." After the change, addrMap self-supplies its own proof, so the comment was updated to "OnceCell init runs via TreeUnpickler.decodeSync, which reads unsafe-tier helpers." This preserves the intent of the comment while removing the stale reference.

## D3: Negative-compilation test location

The plan specifies tests in TastyTest.scala but the task instructions explicitly requested a new file `external/AddrMapVisibilityTest.scala` in `package external`. The external package ensures `assertDoesNotCompile` evaluates the string in a context outside `kyo`, making the private[kyo] restriction operative. Created at: `kyo-tasty/shared/src/test/scala/external/AddrMapVisibilityTest.scala`.

## D4: Positive reachability coverage

AstUnpicklerTest.scala (package kyo, line 611) already calls `o.addrMap` on a TastyOrigin. After the change this call compiles (private[kyo] is accessible from package kyo). The successful `Test/compile` run confirms internal reachability without adding a redundant test.

## Results

- `kyo-tasty/Test/compile`: PASS
- `testOnly external.AddrMapVisibilityTest`: 1 test, succeeded 1, failed 0
- HEAD: c8fb91dd8 (unchanged)
- addrMap signature: `private[kyo] def addrMap: IntMap[Tasty.Symbol]`
