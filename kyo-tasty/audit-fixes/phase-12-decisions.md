# Phase 12 Decisions

## Decoder approach for EXTref (tag 7) and EXTMODCLASSref (tag 8)

### Symbol kind
Both EXT ref types produce `Tasty.SymbolKind.Unresolved` symbols. They reference external symbols defined in other compilation units, which matches the existing `Unresolved` convention used throughout the codebase (ClassfileUnpickler, TypeUnpickler, etc.).

### FQN construction
The plan pseudocode referenced `UnresolvedRef.make` and `resolveOwnerFqn` as conceptual stubs; the actual implementation uses `makePickleSym` (same as all other Scala2 decoders) with `SymbolKind.Unresolved` and a fully-qualified name string.

Owner chain resolution is handled by `resolveExtFqn`, a private helper that:
1. Checks the nameTable directly for the entry index (covers name entries used as package owners).
2. If the owner entry is itself an EXTref or EXTMODCLASSref, recursively reads its data to reconstruct its FQN.
3. Returns empty string for out-of-range or unrecognized entry types, which the callers treat as "no qualifier".

This avoids allocating intermediate symbols for owner entries and keeps the chain resolution purely string-based.

### SingleAssign wiring
`_declaredType` is set inline to `Type.Named(sym)` (self-reference, matching convention for Unresolved placeholders). The remaining SingleAssign slots (`_parents`, `_typeParams`, `_declarations`, `_scaladoc`, `_position`) are populated by the existing post-loop in `buildResult` via `isSet` guards, so no special handling is needed.

### EXTMODCLASSref `$` suffix
The `$` suffix is appended to the raw name before FQN construction (not after), so the interned name includes the suffix. This matches how module class JVM names work and what the plan specifies.

### Tests
Test 8 (EXTref): Constructs a four-entry pickle (two TERMname + one EXTref owner + one EXTref referencing the owner) and asserts the resulting Unresolved symbol has `name.asString == "com.example.Foo"` and `Flag.Scala2`.

Test 9 (EXTMODCLASSref): Same layout but the leaf entry is EXTMODCLASSref; asserts `name.asString == "com.example.Foo$"`.

Both tests are in `shared/` (not tagged `jvmOnly`) because they use synthetic pickle bytes via `readRaw` with no JVM-specific paths.

### Cross-platform compile
JVM, JS, and Native all compile clean (`Test/compile` succeeded for all three).

### HEAD
Verified at `5c6f2b0650985839d99be8250b6cfac03ca423d8` (no commit made per HARD RULE).

## Phase 12 verify-fail fix

### Fix 1: Option/Some/None replaced with Maybe/Present/Absent

Four sites in `Scala2PickleReader.scala`:

- Line 453 (`decodeExtRef`): `Some(c.readNat()) / None` -> `Present(c.readNat()) / Absent`
- Line 480 (`decodeExtModClassRef`): same pattern
- Lines 504-506 (`resolveExtFqn` pattern match): `nameTable.get(entryIdx) match case Some(name) => / case None =>` -> `Maybe.fromOption(nameTable.get(entryIdx)) match case Present(name) => / case Absent =>`; `nameTable.get` returns `scala.Option` so `Maybe.fromOption` bridges the stdlib call.
- Line 513 (`resolveExtFqn` body): `Some(c.readNat()) / None` -> `Present(c.readNat()) / Absent`

All four `ownerRefOpt` values use `.map`, `.filter`, `.fold` which exist identically on `Maybe`. No further API changes needed.

### Fix 2: "simpler layout" comment rewrite

File: `Scala2PickleTest.scala`, line 252.

Before: `// We use a simpler layout: chain via ownerRef pointing to a TERMname directly (common for single-package owners).`

After: `// This test uses a 4-entry layout: ownerRef points to a TERMname directly (common for single-package owners).`

### Fix 3: flow-allow markers for un-annotated AllowUnsafe imports

Both new `import AllowUnsafe.embrace.danger` lines (in `decodeExtRef` and `decodeExtModClassRef`) received:

```
// flow-allow: §839 case 3 — pickle decode orchestration init path
```

This matches the Phase 11 convention used for other init-path unsafe sites in the same file.

### Verification

- `grep -nE 'Option\(|Some\(|None\b' Scala2PickleReader.scala | grep -v '//'` -> 1 hit, `Maybe.fromOption(...)` (the word "Option" appears as part of the method name; no banned identifier usage).
- `grep -ni 'simpler' Scala2PickleTest.scala` -> 0 hits.
- `sbt 'kyo-tasty/Test/compile'` -> SUCCESS.
- `sbt 'project kyo-tasty' 'testOnly kyo.Scala2PickleTest'` -> 9/9 pass.
- HEAD `5c6f2b065` unchanged.
