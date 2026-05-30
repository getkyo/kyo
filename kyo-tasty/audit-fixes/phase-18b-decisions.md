# Phase 18b Decisions

## Tree.Literal

Pre-existing. `final case class Literal(constant: Constant) extends Tree` was added in Phase 17 work (present at line 466 of Tasty.scala before this phase began). No changes needed to its signature.

## Tree.Shared

New in Phase 18b. Added `final case class Shared(addr: Int) extends Tree` before `Modifier` in the Tree ADT (Tasty.scala). Represents both SHAREDtype (tag 61) and SHAREDterm (tag 60) back-references; `addr` is the byte address of the original node in the section.

## Constant.IntConst exact name

`Tasty.Constant.IntConst` with field `i: Int` (defined in the `Constant` enum in Tasty.scala). Confirmed at line 167.

## decodeCtx shape passed to fromTastyTag

`Constant.fromTastyTag` takes `(tag: Int, view: ByteView, session: TypeUnpickler.DecodeSession)` and returns `Tasty.Constant < (Sync & Abort[TastyError])`. However, TreeUnpickler is synchronous and does not use Kyo effects; it therefore does NOT call `Constant.fromTastyTag`. Instead, the category-2 constant tags (BYTEconst, SHORTconst, CHARconst, INTconst, LONGconst, FLOATconst, DOUBLEconst, STRINGconst) were already decoded inline in `decodeTreeTag` via direct `view.readNat()` / `view.readInt()` / `view.readLongNat()` calls (pre-existing from earlier phases). Phase 18b only adds `Tree.Shared` and routes SHAREDtype and SHAREDterm to it.

## SHAREDterm behaviour change

Previously SHAREDterm did a `treeAddrCache.getOrElse(addr, Tree.Unknown(...))` lookup. Now it produces `Tree.Shared(addr)` directly. The cache recording guard in `readTree` was updated to also exclude SHAREDtype (it already excluded SHAREDterm).

## Test encoding

- Test 18b-1 (SHAREDtype + nat 42): bytes `[61, 0xAA]` (0xAA = 42 | 0x80 stop-bit).
- Test 18b-2 (INTconst + int 7): bytes `[70, 0x87]` (0x87 = 7 | 0x80 stop-bit, signed readInt single-byte).
