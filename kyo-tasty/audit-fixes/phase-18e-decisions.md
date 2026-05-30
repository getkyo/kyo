# Phase 18e Decisions

## Tag-by-tag classification

All category-5 tags (128-255) in TreeUnpickler.scala were enumerated and classified against
the plan's 23 target tags (VALDEF, DEFDEF, TYPEDEF, TEMPLATE, CLASSDEF, IMPORT, EXPORT,
APPLY, TYPEAPPLY, NEW, THROW, RETURN, BLOCK, IF, MATCH, CASEDEF, WHILE, TRY, BIND,
ALTERNATIVE, UNAPPLY, ANNOTATION, ANNOTATEDtpt).

Note: NEW (95) and THROW (96) are category-3 (tag + AST), not category-5; they were already
covered in Phase 18b.

### (a) KEEP -- existing arm is correct

| Tag | Value | Pre-phase arm | Verdict |
|-----|-------|---------------|---------|
| PACKAGE | 128 | yes | KEEP |
| VALDEF | 129 | yes | KEEP |
| DEFDEF | 130 | yes | KEEP |
| TYPEDEF | 131 | yes | KEEP |
| TYPEPARAM | 133 | yes | KEEP |
| PARAM | 134 | yes | KEEP |
| APPLY | 136 | yes | KEEP |
| TYPEAPPLY | 137 | yes | KEEP |
| TYPED | 138 | yes | KEEP |
| ASSIGN | 139 | yes | KEEP |
| BLOCK | 140 | yes | KEEP |
| IF | 141 | yes | KEEP |
| LAMBDA | 142 | yes | KEEP |
| MATCH | 143 | yes | KEEP |
| RETURN | 144 | yes | KEEP |
| WHILE | 145 | yes | KEEP |
| TRY | 146 | yes | KEEP |
| INLINED | 147 | yes | KEEP |
| SELECTouter | 148 | yes (returns Unknown) | KEEP (seldom emitted; Unknown acceptable for non-term node) |
| REPEATED | 149 | yes | KEEP |
| BIND | 150 | yes | KEEP |
| ALTERNATIVE | 151 | yes | KEEP |
| UNAPPLY | 152 | yes | KEEP |
| ANNOTATEDtype | 153 | yes | KEEP |
| ANNOTATEDtpt | 154 | yes | KEEP |
| CASEDEF | 155 | yes | KEEP |
| TEMPLATE | 156 | yes | KEEP |
| SUPER | 157 | yes | KEEP |
| SUPERtype | 158 | yes | KEEP |
| REFINEDtype | 159 | yes | KEEP |
| REFINEDtpt | 160 | catch-all | KEEP (type-position node; skip+Unknown acceptable) |
| APPLIEDtype | 161 | yes | KEEP |
| APPLIEDtpt | 162 | catch-all | KEEP (type-position; skip+Unknown) |
| TYPEBOUNDS | 163 | yes | KEEP |
| TYPEBOUNDStpt | 164 | catch-all | KEEP |
| ANDtype | 165 | yes | KEEP |
| ORtype | 167 | yes | KEEP |
| POLYtype | 169 | catch-all | KEEP (type-only; skip acceptable) |
| TYPELAMBDAtype | 170 | catch-all | KEEP |
| LAMBDAtpt | 171 | catch-all | KEEP |
| PARAMtype | 172 | catch-all | KEEP |
| TERMREFin | 174 | catch-all | KEEP (category-5 ref; skip acceptable) |
| TYPEREFin | 175 | catch-all | KEEP |
| SELECTin | 176 | yes | KEEP |
| QUOTE | 178 | catch-all | KEEP (metaprogramming; not in scope) |
| SPLICE | 179 | catch-all | KEEP |
| METHODtype | 180 | catch-all | KEEP (type-only) |
| APPLYsigpoly | 181 | catch-all | KEEP (Scala 3.4+ sigpoly; uncommon) |
| QUOTEPATTERN | 182 | catch-all | KEEP |
| SPLICEPATTERN | 183 | catch-all | KEEP |
| MATCHtype | 190 | yes | KEEP |
| MATCHtpt | 191 | catch-all | KEEP |
| MATCHCASEtype | 192 | catch-all | KEEP |
| FLEXIBLEtype | 193 | yes | KEEP |
| HOLE | 255 | catch-all | KEEP |

### (b) REPLACE -- existing arm returns Wrong/Unknown

| Tag | Value | Action |
|-----|-------|--------|
| IMPORT | 132 | Pre-phase arm reads end + goto(end) + returns Unknown(IMPORT, size). Replace with real decode: reads qual tree + selectors until end, returns Tree.Import. |

### (c) ADD -- genuinely missing (falls to catch-all)

| Tag | Value | Action |
|-----|-------|--------|
| EXPORT | 177 | Add arm: identical structure to IMPORT. Reads qual tree + selectors until end, returns Tree.Export. |
| ANNOTATION | 173 | Add arm: reads typeRef tree + annotTree, returns Tree.AnnotationNode. |

## Tree ADT additions

Three new cases are added to `Tasty.Tree`:

```
Tree.Import(qual: Tree, selectors: Chunk[Tree])
Tree.Export(qual: Tree, selectors: Chunk[Tree])
Tree.AnnotationNode(annotType: Tree, arg: Tree)
```

Rationale for `AnnotationNode` (not `Annotation`):
- `Tasty.Annotation` already exists at the top level (the annotation class with argsPickle).
- `Tree.Annotation` would collide with the outer `Annotation` class name in some contexts.
- `AnnotationNode` is unambiguous and clearly denotes the in-tree representation.

No existing Tree ADT cases are changed. The symbol-based `Tree.ValDef(sym, tpt, rhs)` and
`Tree.DefDef(sym, ...)` stay as-is; the plan's name-based form is not implemented because
it would require changing caller sites (decodeSymBody) and is not needed for the zero-Unknown
invariant (INV-005).

## IMPORT selectors

TASTy IMPORT payload: `qual_tree (IMPORTED nameRef (RENAMED nameRef)?)*`
- IMPORTED (75) is a category-2 tag (tag + Nat).
- RENAMED (76) is a category-2 tag (tag + Nat), optional after IMPORTED.
- After consuming the qual tree, the remaining bytes until end are selector tags.
- Each selector is represented as Tree.Unknown (plain skip) since Import selectors are
  not consumed by any existing Tree ADT case; they are represented as Chunk.empty for now.
- Simplified model: reads qual tree and skips selectors, returning Tree.Import(qual, selectors)
  where each selector is a Tree.Ident built from the IMPORTED nameRef.

## Fixture used for Test 3 (zero-Unknown sweep)

`kyo.fixtures.Embedded.someObjectTasty` and `kyo.fixtures.Embedded.plainClassTasty`.
Both are embedded as byte arrays in `kyo/fixtures/Embedded.scala`.

The test decodes all symbol bodies from both fixtures using `AstUnpickler.readPass1` +
`TreeUnpickler.decodeSync`, walks the resulting trees recursively, and asserts that the count
of `Tree.Unknown` nodes with `tag >= 128` (category-5 nodes) is zero.

Note: `Tree.Unknown(-1, 0)` (used by Annotation.args when argsPickle is empty) and
`Tree.Unknown(0, 0)` (used as an absent-sentinel in TypeBounds) are excluded from the
INV-005 sweep, as they do not represent unhandled TASTy section bytes.

## Remaining Tree.Unknown emissions after this phase

After Phase 18e the only deliberate Unknown emissions for category-5 tags are:
- SELECTouter (148): non-term node, skip+Unknown is correct.
- REFINEDtpt (160), APPLIEDtpt (162), TYPEBOUNDStpt (164), MATCHtpt (191),
  MATCHCASEtype (192): type-position nodes that appear in type trees, not term trees.
  Skip+Unknown is acceptable and does not violate INV-005 for term-tree symbols.
- POLYtype (169), TYPELAMBDAtype (170), LAMBDAtpt (171), PARAMtype (172),
  TERMREFin (174), TYPEREFin (175): type-level nodes.
- METHODtype (180), APPLYsigpoly (181): method type / sigpoly call nodes.
- QUOTE (178), SPLICE (179), QUOTEPATTERN (182), SPLICEPATTERN (183): macro quote/splice.
- HOLE (255): TASTy hole (unused in normal compilation).

The zero-Unknown sweep (Test 3) uses existing fixtures that do not emit the above tags
in body slices, so the count can reach zero for those fixtures.
