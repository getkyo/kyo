# Phase 18c Decisions

## Boundary derivation strategy for APPLIEDtype and MATCHtype

The plan pseudocode passed `view.position` as the end argument to `decodeTreeListUntil`, which would stop immediately before reading anything. This was wrong.

The correct strategy mirrors TypeUnpickler exactly: all 11 tags except RECtype (100) and BYNAMEtype (93) are TASTy wire-format category-5 nodes (tag >= 128, length-prefixed). Their payload boundary is obtained by calling `view.readEnd()` immediately after the tag byte, then decoding nodes until that boundary.

Specifically:
- APPLIEDtype (161): `readEnd()` -> read one tycon Tree -> `readTreesUntil(view, end, ctx)` for args
- MATCHtype (190): `readEnd()` -> read bound Tree -> read scrutinee Tree -> `readTreesUntil(view, end, ctx)` for cases
- SUPERtype (158), REFINEDtype (159), ANDtype (165), ORtype (167), FLEXIBLEtype (193): `readEnd()` -> read fixed child nodes -> `view.goto(end)`
- TYPEBOUNDS (163): `readEnd()` -> read lo Tree -> read hi Tree if position < end (hi is optional in alias-only bounds) -> `view.goto(end)`

RECtype (100) and BYNAMEtype (93) are TASTy wire-format category-3 (tag + one sub-AST, no length prefix). These decode by calling `readTree` once without any `readEnd()` call.

The same boundary logic appears in TypeUnpickler.scala: APPLIEDtype uses `view.readEnd()` then `readTypesUntil(view, end, ctx)`; MATCHtype uses `view.readEnd()` then reads bound, scrutinee, and `readTypesUntil(view, end, ctx)` for cases.

## Name collisions

No name collisions exist. The 11 new Tree ADT cases are: RecType, SuperType, RefinedType, AppliedType, TypeBounds, AnnotatedType, AndType, OrType, ByNameType, MatchType, FlexibleType. None of these names exist in the pre-phase-18c Tree ADT (which has: Ident, Select, Apply, TypeApply, Block, If, Match, CaseDef, Literal, New, Assign, Return, Throw, Lambda, Typed, Inlined, Try, While, Bind, Alternative, Unapply, ValDef, DefDef, TypeDef, PackageDef, ClassDef, Template, Super, This, NamedArg, Annotated, Shared, Modifier, Unknown).

One structural note: `Tree.Annotated` (pre-existing, used for ANNOTATEDtpt) and `Tree.AnnotatedType` (new, used for ANNOTATEDtype) coexist with different names. The existing `ANNOTATEDtpt | ANNOTATEDtype` arm was split: ANNOTATEDtpt keeps `Tree.Annotated`, ANNOTATEDtype now returns `Tree.AnnotatedType`.

## Fixture-byte construction strategy

Both test fixtures use handcrafted byte sequences via TERMREFdirect (62) references into a synthetic `IntMap`-based addrMap. This avoids the need for a TASTy header, name section, or full AST section.

TASTy nat encoding: big-endian base-128 with continuation bit 0x80 CLEAR and stop bit 0x80 SET on the last byte. Single-byte nat `n` (1 <= n <= 127) encodes as `n | 0x80`. The length field after the category-5 tag uses the same nat encoding; `readEnd()` reads this nat and returns `cursor + nat_value`.

Test 18c-1 (APPLIEDtype): bytes `[0xA1, 0x84, 0x3E, 0x81, 0x3E, 0x82]` with addrMap(1)=listSym, addrMap(2)=intSym. After `readEnd()` returns position+4, the tycon TERMREFdirect reads symbol at addr 1 (List), and `readTreesUntil` reads the single arg TERMREFdirect at addr 2 (Int).

Test 18c-2 (MATCHtype): bytes `[0xBE, 0x88, 0x3E, 0x81, 0x3E, 0x82, 0x3E, 0x83, 0x3E, 0x84]` with addrMap(1..4) for bound/scrut/case1/case2. After `readEnd()` returns position+8, bound reads addr 1, scrutinee reads addr 2, and `readTreesUntil` reads the two case trees at addrs 3 and 4.

The `decodeAnnotationTerm` entry point is used for both tests: it builds a ByteView from the pickle bytes starting at position 0, so no section overhead is needed.
