# Phase 02d decisions

Phase name: Bridge Symbol.body through Sync.Unsafe.defer

## Decision 1: Wrap entire TastyOrigin branch in Sync.Unsafe.defer

The plan's pseudocode showed only `_bodyOnce.get()` inside the defer. The prep file (Concern 1) identified that `home.isAssigned` (line 701), `home.get()` (lines 703 and 714), and `home.get().isClosed` (line 714) all require `AllowUnsafe` because they call into `ClasspathRef.isAssigned`/`ClasspathRef.get()` (which gained `(using AllowUnsafe)` in Phase 02c) and `Classpath.isClosed` (which gained `(using AllowUnsafe)` in Phase 02b). Wrapping only the `_bodyOnce.get()` call would leave the `home.*` calls uncovered. Resolution: wrap the entire body of the `TastyOrigin` case branch in a single `Sync.Unsafe.defer { ... }` block, covering all three unsafe-tier read sites uniformly.

## Decision 2: Single defer block, not nested defers

The alternative of wrapping each unsafe call site in its own `Sync.Unsafe.defer` was rejected because (a) it would require restructuring the if/else chains and (b) it conflicts with INV-002 (zero per-call allocation). A single `Sync.Unsafe.defer` wrapping the whole branch produces no allocation overhead and keeps the logic unchanged.

## Decision 3: end if terminator placement

The `end if` (closing `if !home.isAssigned`) sits inside the `Sync.Unsafe.defer` block. The `Sync.Unsafe.defer:` colon-syntax block ends after the `end if` because no further statements follow. The Scala indentation rules handle this correctly without an explicit `end defer`.

## Decision 4: Test placement in TastyTest.scala

Per Concern 2, `TreeUnpicklerTest.scala` does not exist. The plan says tests live there. Per the steering rule "new test files appear only for new source files", and since Phase 02d modifies `Tasty.scala` (an existing source file), the tests were placed in the existing prefix-matching file `TastyTest.scala`. This is consistent with the steering's allowed exception for topic-split and avoids creating a phase-coded artifact file.

## Decision 5: Test 1 and Test 2 substance

Test 1 ("no import danger in body method") and Test 2 ("Sync.Unsafe.defer present in body method") are both source-text substring checks scoped to the lines between `def body(using Frame)` and `end body`. This scope excludes the `_bodyOnce` init lambda at line ~553 which retains its own `import AllowUnsafe.embrace.danger` per Phase 02d design. The scoping uses `indexWhere` to find the exact line boundaries.
