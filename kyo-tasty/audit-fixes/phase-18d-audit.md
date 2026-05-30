# Phase 18d Audit

## Summary

PASS overall. All 10 new cases decode correctly from the wire. No production pattern matches on the displaced `Tree.Ident` / `Tree.Unknown` shapes were found outside the decoder itself. One NOTE on the test helper `containsApplyOrIdentOrLiteral` and one NOTE on `addr` field documentation.

---

## Findings

### 1. Tag-replacement migration risk - OK

Three tags previously returned `Tree.Ident`: `TERMREFdirect`, `TERMREFpkg`, `TERMREFsymbol`. The only production pattern match on `Tree.Ident` in `kyo-tasty/shared/src/main/scala/` is the IDENT-tag arm itself (TreeUnpickler.scala lines 307, 335, 577) -- all constructors, not matchers. No external consumer pattern-matches `Tree.Ident` to handle TERMREF-sourced trees.

One test-side match exists: `containsApplyOrIdentOrLiteral` in TreeUnpicklerTest.scala line 170 matches `_: Tasty.Tree.Ident`. This helper is used to assert that real method bodies contain _some_ structured node. After Phase 18d, term references that were previously decoded as `Tree.Ident` now produce `TermRefDirect` / `TermRefPkg` / `TermRefSymbol`. If the test fixture body happens to contain only TERMREF-variant nodes (no IDENT, Apply, or Literal), the assertion would spuriously fail. However, the fixture (`someObjectTasty`) contains method bodies with genuine `Apply` and `Literal` nodes, so no regression is expected. The helper should be extended in a future phase to cover the new ref-family cases.

### 2. TermRefDirect addr semantics - OK

`addr` in `TermRefDirect(addr: Int)` is an absolute byte offset into the TASTy section, not a name-table index. Confirmed: `addrMap` is keyed by `view.positionInt` at the point `walkStats` processes each node (AstUnpickler.scala lines 219, 247, 289). `position` is the cursor's byte offset (`ByteView.scala` line 48: `Math.toIntExact(position)`). `Int` is correct; TASTy section sizes fit in 31 bits. The ScalaDoc on `TermRefDirect` says "symbol address" which is accurate but does not say "byte offset into TASTy section" -- a documentation NOTE, not a code defect.

### 3. SELECTin substance - OK

Wire format for SELECTin (tag 176, category 5): `Length nameRef qual_Tree owner_Tree`. The new arm reads: `readEnd()`, `readNat()` (nameRef), `readTree()` (qual), `readTree()` (owner), `goto(end)`. This matches the TERMREFin / TYPEREFin analogues in TypeUnpickler.scala (lines 556-572) which also read nameRef before qual and owner. Order is correct. The test 18d-2 encodes the payload in the same nameRef-qual-owner order and passes, confirming the parse sequence.

### 4. SINGLETONtpt category mismatch - OK

SINGLETONtpt has tag value 101, which is below `firstLengthTreeTag` (128). It is correctly category 3 (tag + sub-AST only, no length prefix). The new arm reads one `readTree()` with no leading `readNat()` or `readEnd()`. The decisions doc explicitly notes the plan title "category 4" was a loose grouping label, not a wire-format claim. No spurious Nat is consumed.

### 5. Phase18c-1 test update - OK

The test previously asserted `Tree.Ident(listSym.name, _)` for the TERMREFdirect-sourced tycon. Now it asserts `Tree.TermRefDirect(1)`. The addr value `1` is the Nat encoded in the pickle bytes (the test builds `nat(1)` = `0x81`). Phase 18d removed the `addrMap` lookup that resolved addr 1 to `listSym`; the new decode is purely structural. The updated assertion is semantically more accurate: it tests what the decoder actually produces without relying on a symbol-name match that depended on the test-local `addrMap` population. No regression hidden.

### 6. Tree ADT total - NOTE

Tree now has 58 `final case class` members. Naming is internally consistent: term-level cases use short names (Apply, Match, Block, Ident, Select); type-form cases added across phases 18b/c/d use qualified names (AppliedType, MatchType, AnnotatedType, IdentTpt, SelectTpt, SingletonTpt). The ref-family cases (TermRefDirect, TypeRefDirect, TermRefPkg, TypeRefPkg, TermRefSymbol, TypeRefSymbol) use the TASTy tag word as-is with camelCase. One mild asymmetry: `SelectIn` does not carry a `Tpt` or `Ref` suffix, whereas `IdentTpt` and `SelectTpt` do. The naming follows tag name literally (SELECTin), which is consistent. No collisions. No changes recommended.

### 7. Code quality - OK

No em-dashes, no semicolons used as statement separators, no `asInstanceOf`, no `Option`/`Some`/`None`, no default parameters in the diff. The `Some` usages present in Tasty.scala (line 251) are pre-existing and outside the diff. New ScalaDoc comments on the 10 case classes are concise and match the pattern of the surrounding type-form cases added in 18c.

---

## Recommendations

- NOTE (test helper, route Phase 22+): `containsApplyOrIdentOrLiteral` in TreeUnpicklerTest matches `_: Tasty.Tree.Ident` but not `TermRefDirect`, `TermRefPkg`, or `TermRefSymbol`. Extend the helper when consumer-style tests are added to avoid a latent false-failure if a fixture body has only TERMREF-variant nodes.
- NOTE (addr documentation): `TermRefDirect(addr: Int)` and the `addr` field on `TermRefSymbol`/`TypeRefSymbol`/`TypeRefDirect` should document that the value is an absolute byte offset into the TASTy section, keyed against `addrMap`. Add a single shared ScalaDoc note on the first such case or a section comment in the Tree ADT.
