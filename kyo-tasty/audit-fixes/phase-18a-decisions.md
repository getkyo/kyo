# Phase 18a Decisions

## DecodeException.byteOffset extension

**Decision: YES -- extended.**

`TreeUnpickler.DecodeException` was extended from a single-argument constructor to a two-argument constructor:

```scala
final class DecodeException(msg: String, val byteOffset: Long = 0L) extends RuntimeException(msg)
```

The `byteOffset` field defaults to `0L` for callers that do not have a cursor available (e.g. the "body not available for Java symbols" and "body bytes not available" throw sites). The new `decodeCategoryOneModifier` helper passes `view.position.toLong` as the offset.

Both catch sites in `Tasty.scala` (`ann.args` and `Symbol.body`) were updated to forward `ex.byteOffset` to `TastyError.MalformedSection` instead of hard-coding `0L`. This closes the Phase 17 workaround noted in the plan.

## Missing Flag enum members

None were added. All flags required by the `decodeCategoryOneModifier` table were already present in `Tasty.Flag`:

- Private, Protected, Abstract, Final, Sealed, Case, Implicit, Given, Lazy, Override
- Inline, Macro, Opaque, Open, Transparent, Infix, Erased, Tracked, Synthetic, Artifact
- Stable, Static, Mutable, FieldAccessor, CaseAccessor, PARAMsetter, PARAMalias
- Exported, Local, HasDefault, Extension, InlineProxy, CoVariant, ContraVariant
- Invisible, Into

Note: the plan listed `PARAMACCESSOR` mapping to `Flag.ParamAccessor`, but `TastyFormat.PARAMACCESSOR` does not exist in the TASTy format. The `ParamAccessor` flag is set by the AstUnpickler from class membership, not from a standalone modifier tag. This mapping was omitted.

The `FIELDaccessor` (tag 26) and `CASEaccessor` (tag 27) tags are mapped to `Flag.FieldAccessor` and `Flag.CaseAccessor` respectively, following the TastyFormat constants.

## Tag-numeric source

Tag numerics were read from `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TastyFormat.scala` in this repository. The constant `firstASTtag = 60` was added to that file as the boundary between category-1 (single-byte, tags 1-59) and category-2 (tag + Nat, tags 60-89).

## Test byte choice for Test 18a-2

The plan specifies bytes `[5]` (NULLconst) for the "unknown category-1 modifier" negative test. In the current dispatch structure, the constant tags (UNITconst=2, FALSEconst=3, TRUEconst=4, NULLconst=5) are handled by dedicated match arms BEFORE the category-1 fallback. Using tag 5 would decode to `Literal(NullConst)` rather than throw, and would break the Phase17-A test which also relies on UNITconst(2) decoding as a literal.

To keep all existing tests passing, Test 18a-2 uses byte value `50`, which is in the category-1 range (< 60), is not defined in TastyFormat, and is not handled by any specific match arm. It hits the `case other if other < firstASTtag` fallback and is routed to `decodeCategoryOneModifier(50, ...)` which throws "unknown category-1 modifier tag 50".
