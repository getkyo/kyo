# Phase 18a Audit

## Summary

Phase 18a delivers the byteOffset close-out cleanly: DecodeException gains a mandatory `byteOffset: Long` field (no default), all four throw sites pass explicit values, and both Tasty.scala catch blocks forward `ex.byteOffset`, closing INV-006. The 36-arm `decodeCategoryOneModifier` dispatch is well-structured. However, three tag-numeric modifier constants present in the real Scala 3.8.3 TASTy format -- OBJECT(19), TRAIT(20), ENUM(21) -- are absent from the mapping table. All three correspond to Flag values already in the enum (Module, Trait, Enum) and will appear in every class, trait, and object body that is decoded. Any call to `Symbol.body` for a non-trivial symbol will trigger the "unknown category-1 modifier tag" throw path, making this a correctness blocker for real-world use. SUBMATCH(48) is also absent, with no Flag equivalent; that is a lower-severity gap. Everything else in the phase is correct.

## Findings

### 1. Modifier tag mapping completeness - BLOCKER

The `decodeCategoryOneModifier` table contains 36 arms (the commit message says 35; the extra entry is INLINEPROXY, which brings the count to 36). Cross-referencing TastyFormat.scala and the dotty 3.8.3 `tasty-core` bytecode confirms that all category-1 tags in the range [1, 60) are:

- Tags 2-5: UNITconst, FALSEconst, TRUEconst, NULLconst -- handled by dedicated arms before the dispatch; correct.
- Tags 6, 8-18, 22-29, 31-44, 47, 49: all mapped; correct.
- Tags 1, 7, 30, 50-59: not defined in TastyFormat for either 3.3.1 or 3.8.3; gap tags; hitting them throws, which is correct.

Missing and NOT handled elsewhere:

| Tag | Constant | Should map to |
|-----|----------|---------------|
| 19 | OBJECT | Flag.Module |
| 20 | TRAIT | Flag.Trait |
| 21 | ENUM | Flag.Enum |
| 48 | SUBMATCH | no Flag equivalent |

OBJECT, TRAIT, and ENUM are emitted inside every TYPEDEF payload for a class, trait, object, or enum definition. They are read via `readTree` during body decoding. Any call to `Symbol.body` on such a symbol will produce `DecodeException("unknown category-1 modifier tag 19/20/21", ...)`, wrapped as `MalformedSection`. This breaks the primary use case (reading real TASTy bodies). Flag.Module, Flag.Trait, and Flag.Enum are already present in the enum; the fix is three case arms. The decisions doc is silent on why these were omitted.

SUBMATCH(48) appears in match-type and subtype-match contexts. No corresponding Flag exists in the current enum. Encountering it would also throw. Lower severity than OBJECT/TRAIT/ENUM but still a gap that will surface on match-type-heavy code.

### 2. Flag enum coverage - OK

Every flag referenced in the 36-arm table compiles and resolves correctly. No subtle naming mismatches were found. The flag names used -- CoVariant, ContraVariant, PARAMsetter, PARAMalias, InlineProxy -- all exist in `Tasty.Flag` with the same capitalisation. The Phase 17 audit recommendation to add Macro, Transparent, Tracked was addressed: all three are present and mapped.

### 3. DecodeException byteOffset - OK

Final signature at line 30 of TreeUnpickler.scala: `final class DecodeException(msg: String, val byteOffset: Long)` -- no default, as required. All four throw sites pass explicit values: 0L (snapshot-symbol, no cursor), `startAddr.toLong` (BLOCK empty payload), `view.position.toLong` (category-1 fallback), and 0L (Java origin, no cursor). Both Tasty.scala catch blocks at lines 220-224 and 763-767 forward `ex.byteOffset`. INV-006 is fully closed. The decisions doc describes an intermediate design with `= 0L` default that was not carried into the final code; the code is correct, the decisions doc is stale on that point.

### 4. firstASTtag value 60 - OK

Dotty 3.8.3 `tasty-core` bytecode confirms `firstNatTreeTag()` returns 60, equal to SHAREDterm. The kyo `firstASTtag = 60` constant and the guard `other < TastyFormat.firstASTtag` are correct.

### 5. Test 2 byte deviation (50 not 5) - OK

Tag 5 is NULLconst, handled by a dedicated arm before the category-1 dispatch; using it would have decoded to `Literal(NullConst)` rather than throwing. Tag 50 is confirmed unassigned in both dotty 3.3.1 and 3.8.3 (the gap between INTO=49 and SHAREDterm=60 is unoccupied). The test correctly exercises the throw path and asserts on the right error message. The deviation is sound.

### 6. Tree.Modifier ADT placement - OK

`Modifier(flag: Flag)` is inserted immediately before `Unknown(tag, length)` at the end of the ADT, after `Shared(addr)`. This mirrors the Phase 17 `Unknown` placement pattern: new structural or meta cases are appended at the tail, after the semantically richer AST cases. The ordering is consistent.

### 7. Code quality - OK

No em-dashes, no semicolons, no `asInstanceOf`, no `Option`/`Some`/`None`, and no default parameters introduced. The `// No cursor:` inline comments at the 0L throw sites are clear and accurate.

## Recommendations

- **Phase 18b (route immediately):** Add three arms to `decodeCategoryOneModifier`: OBJECT(19) -> Flag.Module, TRAIT(20) -> Flag.Trait, ENUM(21) -> Flag.Enum. These are required for any real TASTy body decode to succeed on non-trivial symbols.
- **Phase 18b or later:** Add `Flag.SubMatch` (or decide to route SUBMATCH(48) to `Tree.Unknown` instead) to close the SUBMATCH gap.
- **Decisions doc cleanup:** `phase-18a-decisions.md` incorrectly shows `val byteOffset: Long = 0L` (with default); the actual implementation has no default. Low priority, but should be corrected to avoid confusion in future audits.
