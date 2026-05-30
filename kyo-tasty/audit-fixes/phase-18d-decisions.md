# Phase 18d Decisions

## Pre-change discovery: existing arms

Grep command run:
```
grep -n 'case TastyFormat\.(IDENTtpt|SELECTtpt|SINGLETONtpt|TERMREFpkg|TYPEREFpkg|TERMREFsymbol|TYPEREFsymbol|TERMREFdirect|TYPEREFdirect|SELECTin)'
```

Results from TreeUnpickler.scala:
- Line 196: `case TastyFormat.TERMREFdirect` (cat 2, val 62)
- Line 201: `case TastyFormat.TERMREFpkg` (cat 2, val 64)
- Line 229: `case TastyFormat.TYPEREFdirect | TastyFormat.TYPEREFpkg | ...` (cat 2 group)
- Line 313: `case TastyFormat.TERMREFsymbol` (cat 4, val 114)
- Line 334: `case TastyFormat.IDENTtpt | TastyFormat.SELECTtpt | TastyFormat.TYPEREFsymbol | TastyFormat.TYPEREF`

No existing arm for SELECTin (176, cat 5): falls to `other >= firstLengthTreeTag` generic skip handler.

## Tag-by-tag classification

### IDENTtpt (tag 111, category 4: Nat + AST)
- Existing arm: line 334 combined with SELECTtpt/TYPEREFsymbol/TYPEREF -> reads Nat, skips AST, returns `Unknown`.
- Classification: (b) REPLACE.
- New decode: `readNat()` for nameRef, `readType()` for tpe, returns `Tree.IdentTpt(name, tpe)`.
- New Tree case added: `IdentTpt(name: Name, tpe: Type)`.

### SELECTtpt (tag 113, category 4: Nat + AST)
- Existing arm: line 334 combined group -> reads Nat, skips AST, returns `Unknown`.
- Classification: (b) REPLACE.
- New decode: `readNat()` for nameRef, `readTree()` for qual, returns `Tree.SelectTpt(qual, name)`.
- New Tree case added: `SelectTpt(qual: Tree, name: Name)`.

### SINGLETONtpt (tag 101, category 3: AST only)
- Existing arm: line 282 combined with BYNAMEtpt/BOUNDED/EXPLICITtpt -> skips sub-tree, returns `Unknown`.
- Classification: (b) REPLACE.
- New decode: `readTree()` for inner, returns `Tree.SingletonTpt(inner)`.
- New Tree case added: `SingletonTpt(tpe: Tree)`.
- Note: SINGLETONtpt is category 3 (tag 101), NOT category 4. The plan title "category 4" is a loose grouping of type-position nodes.

### TERMREFpkg (tag 64, category 2: Nat only)
- Existing arm: line 201 -> reads nameRef Nat, creates unresolved symbol, returns `Tree.Ident(name, Type.Named(sym))`.
- This is a mismatch in shape: wraps in Ident with a synthetic symbol when the tag carries only a name ref.
- Classification: (b) REPLACE.
- New decode: `readNat()` for nameRef, returns `Tree.TermRefPkg(name)`.
- New Tree case added: `TermRefPkg(name: Name)`.

### TYPEREFpkg (tag 65, category 2: Nat only)
- Existing arm: line 229 combined group -> reads Nat, returns `Unknown`.
- Classification: (b) REPLACE.
- New decode: `readNat()` for nameRef, returns `Tree.TypeRefPkg(name)`.
- New Tree case added: `TypeRefPkg(name: Name)`.

### TERMREFsymbol (tag 114, category 4: Nat + AST)
- Existing arm: line 313 -> reads addr Nat, reads and discards qualifier type, looks up symbol, returns `Tree.Ident(sym.name, Type.Named(sym))`.
- Mismatch: discards the qualifier and loses the addr.
- Classification: (b) REPLACE.
- New decode: `readNat()` for addr, `readTree()` for qual, returns `Tree.TermRefSymbol(addr, qual)`.
- New Tree case added: `TermRefSymbol(addr: Int, qual: Tree)`.

### TYPEREFsymbol (tag 116, category 4: Nat + AST)
- Existing arm: line 334 combined group -> reads Nat, skips AST, returns `Unknown`.
- Classification: (b) REPLACE.
- New decode: `readNat()` for addr, `readTree()` for qual, returns `Tree.TypeRefSymbol(addr, qual)`.
- New Tree case added: `TypeRefSymbol(addr: Int, qual: Tree)`.

### TERMREFdirect (tag 62, category 2: Nat only)
- Existing arm: line 196 -> reads addr Nat, looks up symbol from addrMap, returns `Tree.Ident(sym.name, Type.Named(sym))`.
- Wraps in Ident instead of dedicated case; loses addr.
- Classification: (b) REPLACE.
- New decode: `readNat()` for addr, returns `Tree.TermRefDirect(addr)`.
- New Tree case added: `TermRefDirect(addr: Int)`.

### TYPEREFdirect (tag 63, category 2: Nat only)
- Existing arm: line 229 combined group -> reads Nat, returns `Unknown`.
- Classification: (b) REPLACE.
- New decode: `readNat()` for addr, returns `Tree.TypeRefDirect(addr)`.
- New Tree case added: `TypeRefDirect(addr: Int)`.

### SELECTin (tag 176, category 5: Length + payload)
- No explicit existing arm; falls through to `other >= firstLengthTreeTag` generic handler (readEnd + skip).
- Classification: (c) ADD.
- New decode: `readEnd()` for end, `readNat()` for nameRef, `readTree()` for qual, `readTree()` for owner, `goto(end)`, returns `Tree.SelectIn(qual, name, owner)`.
- New Tree case added: `SelectIn(qual: Tree, name: Name, owner: Tree)`.

## Tree ADT additions summary

10 new cases added to `Tree` object in Tasty.scala:
1. `IdentTpt(name: Name, tpe: Type)`
2. `SelectTpt(qual: Tree, name: Name)`
3. `SingletonTpt(tpe: Tree)`
4. `TermRefPkg(name: Name)`
5. `TypeRefPkg(name: Name)`
6. `TermRefSymbol(addr: Int, qual: Tree)`
7. `TypeRefSymbol(addr: Int, qual: Tree)`
8. `TermRefDirect(addr: Int)`
9. `TypeRefDirect(addr: Int)`
10. `SelectIn(qual: Tree, name: Name, owner: Tree)`

## Migration risks

- `TERMREFdirect` and `TERMREFpkg` and `TERMREFsymbol` had arms that returned `Tree.Ident(...)`. Any code pattern-matching on `Tree.Ident` to handle term references from these tags will now miss them (but there is no user-facing pattern match in this codebase beyond the test infrastructure).
- `isTypeTag` and `isParentTag` helpers reference `TERMREFdirect`, `TERMREFsymbol`, `TERMREFpkg`; these continue to work correctly since they inspect the tag integer, not the Tree ADT result.
- `SINGLETONtpt` is moved from the category-3 skip group to a dedicated arm; any BYNAMEtpt/BOUNDED/EXPLICITtpt group membership is unchanged.
- No public API callers outside this module (kyo-tasty is a library module; the Tree ADT is sealed-ish via `sealed trait Tree`).
- `extractPackageName` at line 992 references `TERMREFpkg` by explicit tag match and still consumes the Nat; that helper is not affected by the decode-arm change (it is a separate tag-decode path, not going through `decodeTreeTag`).

## isTypeTag and isParentTag updates

`isTypeTag` must include the new type-position cases to prevent them being mistaken for term trees in `readTypeOrSkip`. The existing entries in `isTypeTag` for these tags remain correct.

`isParentTag` references `TERMREFsymbol` and `TERMREFdirect` for class parent identification; these tag constants are unchanged, so `isParentTag` is still correct.
