# Phase 02c Decisions

## Decision 1: ClasspathRef.get and isAssigned signatures

Migrated both methods from inner `import AllowUnsafe.embrace.danger` to `(using AllowUnsafe)` per INV-001 and §828 option 1. `assign` was not touched (§839 case 3 initialization boundary).

## Decision 2: Tasty.scala body accessor cascade fix (deviation from prep concern 1)

The prep doc (concern 1) claimed that `home.isAssigned` at body line 698 and `home.get()` at line 700 were "inside a Sync.Unsafe.defer block that already has import danger at line 708." This is incorrect: those calls are in the outer `case o: Symbol.TastyOrigin =>` branch, not inside the `andThen` block containing the import. The import at line 708 was in a nested `else` branch and did not cover lines 698/700.

Fix: moved the `import AllowUnsafe.embrace.danger` from line 708 to the top of the `case o: Symbol.TastyOrigin =>` branch, before line 698. Both comment lines were consolidated. This covers all three uses of `home` in the body accessor (lines 698, 700, 712) without changing the body's public signature.

## Decision 3: ClasspathTestHelpers.assignExtraHomes cascade fix

The prep doc claimed `ClasspathTestHelpers.scala:32` was "already covered by the local import danger." The import at line 18 is inside `assignHomesForTest`, not `assignExtraHomes`. Added `import AllowUnsafe.embrace.danger` inside `assignExtraHomes` to cover the `sym.home.isAssigned` call at line 32.

## Decision 4: ClasspathRefDedupTest.scala import

Added `import kyo.AllowUnsafe` and `import kyo.AllowUnsafe.embrace.danger` at file top, covering `home.isAssigned` at line 84. File-top placement per §839 case 2 (test boundary).

## Decision 5: ClasspathRefTest.scala created with 2 scenarios

Created `ClasspathRefTest.scala` with 2 test scenarios:
1. `ClasspathRef.get returns the assigned Classpath` - verifies get() returns the assigned Classpath after assign (identity via `unwrap` + `eq` since Classpath lacks CanEqual).
2. `ClasspathRef.isAssigned returns false before assign and true after` - verifies the Boolean state transition.

Both scenarios use `import AllowUnsafe.embrace.danger` at the class level and `Tasty.Classpath.fromPickles(Seq.empty)` for the minimal Classpath fixture.
