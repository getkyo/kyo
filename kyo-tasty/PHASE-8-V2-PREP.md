# Phase 8 v2 Prep: G1 Tree Body Decode (TreeUnpickler + Symbol.body)

**Plan reference**: `execution-plan-v2.md` lines 323-373
**IMPROVEMENT-ANALYSIS.md**: G1 section (line 147)
**Status at prep time**: Reflect.scala already defines `sealed trait Tree` with all cases, `_bodyMemo`, and `Symbol.body`. `TreeUnpickler.scala` does not yet exist. The scaffold is complete; only the implementation file is missing.

---

## 1. Verbatim API Signatures

### Tree ADT (from Reflect.scala lines 228-326, already committed)

```scala
sealed trait Tree

object Tree:
    final case class Ident(name: Name, tpe: Type) extends Tree
    final case class Select(qualifier: Tree, name: Name, tpe: Type) extends Tree
    final case class Apply(fun: Tree, args: Chunk[Tree]) extends Tree
    final case class TypeApply(fun: Tree, args: Chunk[Type]) extends Tree
    final case class Block(stats: Chunk[Tree], expr: Tree) extends Tree
    final case class If(cond: Tree, thenp: Tree, elsep: Tree) extends Tree
    final case class Match(selector: Tree, cases: Chunk[CaseDef]) extends Tree
    final case class CaseDef(pattern: Tree, guard: Maybe[Tree], body: Tree) extends Tree
    final case class Literal(constant: Constant) extends Tree
    final case class New(tpe: Type) extends Tree
    final case class Assign(lhs: Tree, rhs: Tree) extends Tree
    final case class Return(expr: Maybe[Tree], from: Symbol) extends Tree
    final case class Throw(expr: Tree) extends Tree
    final case class Lambda(method: Tree, tpe: Maybe[Type]) extends Tree
    final case class Typed(expr: Tree, tpe: Type) extends Tree
    final case class Inlined(call: Maybe[Tree], bindings: Chunk[Tree], body: Tree) extends Tree
    final case class Try(expr: Tree, cases: Chunk[CaseDef], finalizer: Maybe[Tree]) extends Tree
    final case class While(cond: Tree, body: Tree) extends Tree
    final case class Bind(name: Name, pattern: Tree) extends Tree
    final case class Alternative(patterns: Chunk[Tree]) extends Tree
    final case class Unapply(fun: Tree, implicits: Chunk[Tree], patterns: Chunk[Tree]) extends Tree
    final case class ValDef(sym: Symbol, tpt: Type, rhs: Maybe[Tree]) extends Tree
    final case class DefDef(sym: Symbol, paramss: Chunk[Chunk[Tree]], tpt: Type, rhs: Maybe[Tree]) extends Tree
    final case class TypeDef(sym: Symbol, rhs: Type) extends Tree
    final case class PackageDef(sym: Symbol, stats: Chunk[Tree]) extends Tree
    final case class ClassDef(sym: Symbol, template: Template) extends Tree
    final case class Template(parents: Chunk[Tree], self: Maybe[Symbol], body: Chunk[Tree]) extends Tree
    final case class Super(qual: Tree, mix: Maybe[Name]) extends Tree
    final case class This(cls: Symbol) extends Tree
    final case class NamedArg(name: Name, value: Tree) extends Tree
    final case class Annotated(expr: Tree, annotation: Tree) extends Tree
    final case class Unknown(tag: Int, length: Int) extends Tree
end Tree
```

Note: `NamedArg` (NAMEDARG=119) and `Annotated` (ANNOTATEDtpt/ANNOTATEDtype=153/154) are in the
ADT but not mentioned by name in the plan's case list. They are present in Reflect.scala and must
be decoded. `Unknown` is the catch-all for forward-compatible unknown tags.

### Symbol._bodyMemo type (from Reflect.scala lines 355-367)

```scala
private[kyo] val _bodyMemo: kyo.internal.reflect.symbol.Memo[Tree] =
    new kyo.internal.reflect.symbol.Memo[Tree](() =>
        origin match
            case Reflect.Symbol.JavaOrigin =>
                throw new kyo.internal.reflect.tasty.TreeUnpickler.DecodeException(
                    "body not available for Java symbols"
                )
            case o: Reflect.Symbol.TastyOrigin =>
                kyo.internal.reflect.tasty.TreeUnpickler.decodeSync(o, this)
        end match
    )
```

`Memo[A]` is `kyo.internal.reflect.symbol.Memo[A]` (file: `shared/.../symbol/Memo.scala`). It
uses `AtomicReference[AnyRef]` + a `Memo.Unset` sentinel; `get()(using AllowUnsafe): A` computes
`init()` at most once (under CAS). If two threads race, both call `init()` but only one stored value
survives. This matters for TreeUnpickler: `decodeSync` is allowed to be called twice under concurrent
access (idempotent decode from immutable byte slice).

### Memo.get signature

```scala
// kyo/internal/reflect/symbol/Memo.scala line 22
def get()(using AllowUnsafe): A
```

### Symbol.body accessor (from Reflect.scala lines 515-551, already committed)

```scala
def body(using Frame): Tree < (Sync & Abort[ReflectError])
```

Returns `Abort.fail(ReflectError.NotImplemented(...))` for Java symbols and symbols without a body
slice (`bodyStart == 0 || bodyEnd == 0 || kind == SymbolKind.Package`). Returns
`Abort.fail(ReflectError.ClasspathClosed)` if the classpath has been closed. Returns
`Abort.fail(ReflectError.MalformedSection("ASTs", ...))` on `DecodeException` or
`ArrayIndexOutOfBoundsException` thrown by `_bodyMemo.get()`.

### TreeUnpickler.decodeSync signature (to implement)

```scala
// kyo/internal/reflect/tasty/TreeUnpickler.scala
object TreeUnpickler:
    final class DecodeException(msg: String) extends RuntimeException(msg)

    /** Synchronously decode the body byte slice for `sym` into a Tree.
     *
     * Called from the Memo init lambda; must not use Kyo effects.
     * Throws DecodeException or ArrayIndexOutOfBoundsException on error.
     *
     * @param origin  the TastyOrigin carrying addrMap, bodyStart, bodyEnd
     * @param sym     the symbol whose body is being decoded (used for error messages)
     */
    def decodeSync(origin: Reflect.Symbol.TastyOrigin, sym: Reflect.Symbol): Reflect.Tree
```

The public Kyo-effect-returning entry point from the plan spec is:

```scala
def readTree(
    view: ByteView,
    names: Array[Reflect.Name],
    addrMap: Map[Int, Reflect.Symbol],
    arena: TypeArena
): Reflect.Tree < (Sync & Abort[ReflectError])
```

However, `decodeSync` is what `Symbol._bodyMemo` already calls. `readTree` can be implemented on
top as a thin Kyo wrapper (wrapping `decodeSync` result in `Sync.defer`).

---

## 2. File:line Anchors

### bodyStart / bodyEnd captured on Symbol

In `AstUnpickler.scala` (the Pass 1 walker), the body slice is captured at the point each
definition node is processed:

| Definition kind | Line (AstUnpickler.scala) | What is stored |
|-----------------|--------------------------|----------------|
| VALDEF | line 223 | `TastyOrigin(Map.empty, payloadBody, payloadEnd)` where `payloadBody = view.position` after reading NameRef |
| DEFDEF | line 240 | `TastyOrigin(Map.empty, payloadBody, payloadEnd)` where `payloadBody = view.position` after reading NameRef |
| TYPEDEF (class-like) | line 303 | `TastyOrigin(Map.empty, templateBodyStart, templatePayloadEnd)` where `templateBodyStart` is the start of the TEMPLATE payload |
| TYPEDEF (type-level) | line 344 | `TastyOrigin(Map.empty, payloadEnd, payloadEnd)` -- no body (bodyStart == bodyEnd) |
| TYPEPARAM | line 363 | `TastyOrigin(Map.empty, payloadEnd, payloadEnd)` -- no body |
| PARAM | line 381 | `TastyOrigin(Map.empty, payloadEnd, payloadEnd)` -- no body |
| PACKAGE | line 182 | `TastyOrigin(Map.empty, view.position, payloadEnd)` |

The addrMap field on TastyOrigin is currently always `Map.empty` at construction time. The live
addrMap is not stored on the origin. This is a key concern (see section 7).

### TastyOrigin case class (Reflect.scala lines 632-636)

```scala
final case class TastyOrigin(
    addrMap: Map[Int, Reflect.Symbol],
    bodyStart: Int,
    bodyEnd: Int
) extends Origin
```

The `addrMap` field exists on TastyOrigin. Currently set to `Map.empty` at all construction sites
in AstUnpickler. TreeUnpickler.decodeSync receives the TastyOrigin and will need the addrMap to
resolve `TERMREFdirect`, `TYPEREFdirect`, and `SHAREDterm` back-references within the body.

### Where the body bytes live

The original TASTy `Array[Byte]` that was passed to `ByteView` in `decodeTastyBytes` is heap-resident.
`ByteView` wraps it. The `bodyStart` and `bodyEnd` values are absolute byte offsets into this array.
However, `ByteView` is not stored anywhere after `decodeTastyBytes` returns; only the `TastyOrigin`
int offsets survive. TreeUnpickler needs access to the original byte array to construct a `ByteView`
for the body slice. This is the central wiring problem for Phase 8.

There are two paths to solve this:

**Option A (preferred)**: Store the original TASTy `Array[Byte]` (or a `ByteView` of the full AST
section) in `TastyOrigin` alongside `bodyStart`/`bodyEnd`. `decodeSync` constructs
`view.subView(origin.bodyStart, origin.bodyEnd)` directly.

**Option B**: Pass the full file bytes to `_bodyMemo` by closing over them in `Symbol`'s constructor.
Requires threading the byte array through symbol construction in AstUnpickler, which is invasive.

The plan spec says `TastyOrigin` holds `bodyStart`/`bodyEnd` and the body accessor uses them, so
Option A is implied. Phase 8 will need to add the byte array (or a full-file ByteView) to
TastyOrigin.

---

## 3. TASTy Tree Tag Categories and Tree Case Mapping

From `TastyFormat.scala` (all constants verbatim):

### Category 1: tag only (1-59) -- modifiers and flags; no Tree node to decode

These are modifier/flag tags (PRIVATE=6, PROTECTED=8, ABSTRACT=9, FINAL=10, SEALED=11, etc.) plus
structural markers EMPTYCLAUSE=45, SPLITCLAUSE=46. They appear inside definition payloads but are
NOT standalone Tree nodes. If the decoder encounters them at a Tree position, it signals an error.

Literal constants in this range:
- `UNITconst=2` -> `Literal(Constant.Unit)`
- `FALSEconst=3` -> `Literal(Constant.Boolean(false))`
- `TRUEconst=4` -> `Literal(Constant.Boolean(true))`
- `NULLconst=5` -> `Literal(Constant.Null)`

### Category 2: tag + Nat (60-89) -- references and small literals

| Tag | Constant | Tree case |
|-----|----------|-----------|
| 60 | SHAREDterm | Back-reference: Nat is an absolute address; look up addrCache and return previously decoded Tree |
| 61 | SHAREDtype | Type back-reference (used in type position, not tree position; typically not encountered here) |
| 62 | TERMREFdirect | Nat is an absolute addr; `Ident(names(addrMap(addr).name), ...)` or resolved symbol ref |
| 63 | TYPEREFdirect | Nat is absolute addr; type reference, not a term Tree |
| 64 | TERMREFpkg | Nat is NameRef; `Ident(names(nameRef), ...)` for a package-level term ref |
| 65 | TYPEREFpkg | Type reference; not a term Tree |
| 66 | RECthis | Nat is addr of enclosing RECtype; `This(...)` or a recursive self reference |
| 67 | BYTEconst | Nat is value; `Literal(Constant.Byte(nat.toByte))` |
| 68 | SHORTconst | Nat is value; `Literal(Constant.Short(nat.toShort))` |
| 69 | CHARconst | Nat is value; `Literal(Constant.Char(nat.toChar))` |
| 70 | INTconst | Nat is value; `Literal(Constant.Int(nat))` |
| 71 | LONGconst | Nat is value; `Literal(Constant.Long(nat.toLong))` |
| 72 | FLOATconst | Nat is raw bits; `Literal(Constant.Float(java.lang.Float.intBitsToFloat(nat)))` |
| 73 | DOUBLEconst | Nat is raw bits (hi 32) + second Nat (lo 32); `Literal(Constant.Double(...))` |
| 74 | STRINGconst | Nat is NameRef; `Literal(Constant.String(names(nameRef).asString))` |
| 75 | IMPORTED | Nat is NameRef; import selector -- likely not seen in body position |
| 76 | RENAMED | Nat is NameRef; import renaming -- not a body Tree |

### Category 3: tag + sub-AST (90-109) -- single-sub-tree expressions

| Tag | Constant | Tree case |
|-----|----------|-----------|
| 90 | THIS | sub-AST is a type (TypeRef or TypeRefDirect for the class); `This(sym)` |
| 91 | QUALTHIS | sub-AST is a TypeIdent; `This` with an explicit qualification |
| 92 | CLASSconst | sub-AST is a type; `Literal(Constant.ClassOf(tpe))` |
| 93 | BYNAMEtype | type node; not a term Tree |
| 94 | BYNAMEtpt | type tree node; not a term Tree |
| 95 | NEW | sub-AST is a type; `New(readType(...))` |
| 96 | THROW | sub-AST is an expression Tree; `Throw(readTree(...))` |
| 97 | IMPLICITarg | sub-AST is a Tree; implicit argument -- treat as transparent wrapper; return inner tree |
| 98 | PRIVATEqualified | modifier with sub-AST (qualifier type); skip as modifier, not a Tree |
| 99 | PROTECTEDqualified | modifier with sub-AST; skip as modifier |
| 100 | RECtype | recursive type; not a term Tree |
| 101 | SINGLETONtpt | type tree; not a term Tree |
| 102 | BOUNDED | bounded type; not a term Tree |
| 103 | EXPLICITtpt | explicit type tree wrapper; unwrap to get underlying type |
| 104 | ELIDED | placeholder for an elided expression; return `Unknown(104, 0)` or skip |

### Category 4: tag + Nat + sub-AST (110-127)

| Tag | Constant | Tree case |
|-----|----------|-----------|
| 110 | IDENT | Nat=NameRef, sub-AST=type; `Ident(names(nameRef), readType(...))` |
| 111 | IDENTtpt | Nat=NameRef, sub-AST=type; type-position identifier; not a term Tree in body |
| 112 | SELECT | Nat=NameRef, sub-AST=qualifier Tree; `Select(readTree(...), names(nameRef), tpe)` -- note: tpe is not encoded at this tag; use Type.Wildcard or read from enclosing TYPED |
| 113 | SELECTtpt | type-position select; not a term Tree |
| 114 | TERMREFsymbol | Nat=addr, sub-AST=type; `Ident(addrMap(addr).name, readType(...))` |
| 115 | TERMREF | Nat=NameRef, sub-AST=prefix type; reference to a member by name from prefix |
| 116 | TYPEREFsymbol | type reference; not a term Tree |
| 117 | TYPEREF | type reference; not a term Tree |
| 118 | SELFDEF | Nat=NameRef, sub-AST=type; self-definition inside TEMPLATE; special case -- skip in body position |
| 119 | NAMEDARG | Nat=NameRef, sub-AST=expr Tree; `NamedArg(names(nameRef), readTree(...))` |

### Category 5: tag + Length + payload (128-255) -- length-prefixed structural nodes

| Tag | Constant | Tree case |
|-----|----------|-----------|
| 128 | PACKAGE | `PackageDef(sym, stats)` |
| 129 | VALDEF | `ValDef(sym, readType, Maybe(readTree))` |
| 130 | DEFDEF | `DefDef(sym, paramss, readType, Maybe(readTree))` |
| 131 | TYPEDEF (class) | `ClassDef(sym, readTemplate)` |
| 131 | TYPEDEF (type alias) | `TypeDef(sym, readType)` |
| 132 | IMPORT | Import statement; often skipped in body position (return Unknown) |
| 133 | TYPEPARAM | Type parameter def; decoded into DefDef.paramss entries |
| 134 | PARAM | Value parameter def; decoded into DefDef.paramss entries |
| 136 | APPLY | `Apply(readTree, args=readTrees_until_end)` |
| 137 | TYPEAPPLY | `TypeApply(readTree, typeArgs=readTypes_until_end)` |
| 138 | TYPED | `Typed(readTree, readType)` |
| 139 | ASSIGN | `Assign(lhs=readTree, rhs=readTree)` |
| 140 | BLOCK | `Block(stats=readTrees_until_expr, expr=readTree)` -- last tree before end is the expr |
| 141 | IF | `If(cond=readTree, thenp=readTree, elsep=readTree)` |
| 142 | LAMBDA | `Lambda(method=readTree, tpe=Maybe(readType))` |
| 143 | MATCH | `Match(selector=readTree, cases=readCaseDefs)` |
| 144 | RETURN | `Return(expr=Maybe(readTree), from=addrMap(readNat))` |
| 145 | WHILE | `While(cond=readTree, body=readTree)` |
| 146 | TRY | `Try(expr=readTree, cases=readCaseDefs, finalizer=Maybe(readTree))` |
| 147 | INLINED | `Inlined(call=Maybe(readTree), bindings=readTrees, body=readTree)` |
| 148 | SELECTouter | outer select; `Unknown(148, length)` or decode as Select variant |
| 149 | REPEATED | repeated arg; decode as Apply to toSeq or Unknown |
| 150 | BIND | `Bind(name=names(readNat), pattern=readTree)` |
| 151 | ALTERNATIVE | `Alternative(patterns=readTrees)` |
| 152 | UNAPPLY | `Unapply(fun=readTree, implicits=readImplicits, patterns=readTrees)` |
| 153 | ANNOTATEDtype | `Annotated(expr=readTree, annotation=readTree)` in type position |
| 154 | ANNOTATEDtpt | `Annotated` in type-tree position |
| 155 | CASEDEF | `CaseDef(pattern=readTree, guard=Maybe(readTree), body=readTree)` |
| 156 | TEMPLATE | `Template(parents=readTrees, self=Maybe(selfSym), body=readStats)` |
| 157 | SUPER | `Super(qual=readTree, mix=Maybe(names(readNat)))` |
| 158 | SUPERtype | supertype node (type position) |
| 163 | TYPEBOUNDS | type bounds |
| 169 | POLYtype | polymorphic type |
| 173 | ANNOTATION | annotation; skip or decode as Annotated |
| 174 | TERMREFin | qualified term ref with additional type; decode as Select variant |
| 176 | SELECTin | qualified select; decode as Select |
| 177 | EXPORT | export clause; `Unknown(177, length)` |
| 178 | QUOTE | quoted expression; `Unknown(178, length)` |
| 179 | SPLICE | spliced expression; `Unknown(179, length)` |
| 255 | HOLE | hole; `Unknown(255, length)` |

Tags 159-193 are mostly type-level tags (REFINEDtype, APPLIEDtype, etc.); encountered in type
position during `readType`, not in term Tree position.

---

## 4. Edge Cases

### SHAREDterm (tag=60, Category 2)

A `SHAREDterm` node encodes a Nat that is an absolute byte offset in the AST section pointing to a
previously decoded tree node. The decoder must maintain a `treeAddrCache: mutable.HashMap[Int, Tree]`
(analogous to TypeUnpickler's `addrCache`). On decoding any tree node, record
`treeAddrCache(startAddr) = result` after decoding. On encountering `SHAREDterm`, look up the Nat
address in the cache and return the cached Tree directly without re-decoding.

This is critical for correctness: TASTy uses SHAREDterm to share subtrees (e.g., repeated
occurrences of the same method reference in multiple call sites within the same body). Without
caching, a SHAREDterm lookup will fail (address not in cache) or produce duplicated work.

Cross-file SHAREDterm references (address points outside the body slice range) should produce
`Unknown(60, 0)` rather than panic.

### Inlined trees (INLINED=147)

Format: `INLINED Length call? binding* expr`
- The first child may be `EMPTYTREE` (a zero-length marker) if the call origin is elided; read as `Maybe[Tree]`.
- Bindings are `VALDEF` or `DEFDEF` trees defined at the inline site; collect until the last child.
- The body is the last child Tree before the end of the payload.

The `Inlined` case carries `call: Maybe[Tree]` -- the supervisor-approved representation. The TASTy
format places the call first; if the call position holds a zero-length EMPTYTREE marker, treat as `Absent`.

### Lambda (LAMBDA=142)

Format: `LAMBDA Length method tpe?`
- `method` is a Tree (usually a SELECT pointing to the anonymous method symbol).
- `tpe` is an optional type tree (the function type); may be absent if inferred.

The `Lambda` case holds `method: Tree, tpe: Maybe[Type]`. The type is a `Type`, not a `Tree`; decode
it with `readType` if present (peek at the next tag; if it is a type tag, read it, else `Absent`).

### Recursive types within Tree

Type nodes embedded inside Tree nodes (e.g., the type argument to `IDENT`, the type ascription in
`TYPED`, the type arg in `TYPEAPPLY`) are decoded via `TypeUnpickler.readTypeNode` (or an equivalent
synchronous call). The `decodeSync` context must carry the same `arena`, `names`, and `addrMap` as
Pass 1 used for type decode. The canonical `TypeArena` must be accessible at decode time.

The `TypeArena` is stored in the `Classpath` state after Phase C; `decodeSync` can access it via
`sym.home.get().arena`. Alternatively, the arena can be stored on `TastyOrigin` alongside the byte
array.

### Match pattern hygiene (no recursive Tree.pattern decode loop in CaseDef)

The `CaseDef` pattern is a Tree (can be `Bind`, `Alternative`, `Ident`, `Unapply`, or any literal).
The pattern decoder is the same `readTree` function. Since CASEDEF is length-prefixed (category 5),
the recursion terminates naturally when the ByteView cursor reaches the CASEDEF payload end.

A `Match` containing `CaseDef`s containing `Alternative`s containing sub-patterns is decoded by
the same recursive `readTree` calls. There is no special pattern-mode; the standard tree decoder
handles all pattern positions because TASTy encodes patterns as ordinary tree nodes.

The only hygiene concern is that `ALTERNATIVE` (tag=151) should not be decoded outside a CaseDef
context. In practice the TASTy format guarantees this; the decoder does not need to enforce it.

---

## 5. Test-Data Suggestions

### Test fixtures (already present in kyo-reflect test resources)

- `PlainClass.tasty` -- contains a TYPEDEF(class) with TEMPLATE, VALDEF, DEFDEF nodes.
  Use for: ClassDef + ValDef body decode (rhs=None for `val x: Int`), simple DefDef body (e.g.
  `def greet: String = "hello"`).

- Base class TASTy with a method body containing arithmetic -- Use for: `def foo(x: Int): Int = x + 1`
  decodes to `Block(Nil, Apply(Select(Ident("x"), "+"), Chunk(Literal(IntConst(1)))))` or similar.

- `ChildClass.tasty` -- contains TYPEDEF(class) with parents. Use for: Template.parents decode,
  Super reference.

- Any case class TASTy -- contains synthetically generated VALDEF (copy method), DEFDEF (apply,
  unapply). Use for: Lambda in apply site, Unapply in pattern position.

### New fixture recommendations

- `def foo: Int = 42` -- simplest body: `Literal(Constant.Int(42))`.
- `def bar(x: Int): Int = x + 1` -- Apply with Ident and arithmetic.
- `def baz(x: Int): String = if x > 0 then "pos" else "neg"` -- If tree.
- `def qux(x: Int): String = x match { case 1 => "one"; case _ => "other" }` -- Match + CaseDef.
- A recursive method `def fact(n: Int): Int = if n <= 1 then 1 else n * fact(n - 1)` -- for the
  no-stack-overflow test (test 5). Recursion in the TASTy body is encoded as a plain APPLY/SELECT
  to the method symbol; it does not require stack on the decode path as long as the decoder is
  iterative for list-shaped structures (BLOCK stats, APPLY args) or uses a local stack frame per
  nested call.

---

## 6. Anti-Flakiness Deltas

### Do NOT compare Tree nodes by reference (`eq`) for structural correctness

Two calls to `sym.body` on the same symbol MUST return reference-equal Trees (test 9: `tree1 eq tree2`).
This works because `Memo.get()` caches the result; the CAS ensures at most one value survives. Use
`eq` only for the memoization test. For structural tests, use `==` (case class equality).

### Do NOT assert specific tree shapes that depend on optimizer passes

The TASTy tree shape reflects the compiler's intermediate representation after typing but before
most optimizations. `def foo: Int = 42` may produce `Literal(IntConst(42))` directly or wrapped in
`Block(Nil, Literal(...))`. Assertions should accept both forms or constrain only the innermost
literal value rather than the outer wrapper shape.

Pattern: extract the deepest leaf from the tree and check its value, not the path to reach it.

### Do NOT use `addrMap` identity between calls

`TastyOrigin.addrMap` is currently `Map.empty` at all construction sites. If Phase 8 populates it,
the map is rebuilt per-call-to-decodeSync (it is not interned). Do not assert `addrMap1 eq addrMap2`.

### Timer: avoid sleep-based timing in concurrency tests

Test 9 (Memo caching) should use reference equality (`tree1 eq tree2`), not a timing assumption.
The Memo implementation guarantees at most one stored value via CAS regardless of thread count.

---

## 7. Concerns

### CONCERN-1 (HIGH): TastyOrigin.addrMap is currently always Map.empty

`AstUnpickler` constructs `TastyOrigin(Map.empty, bodyStart, bodyEnd)` at every definition site
(lines 182, 223, 240, 303, 344, 363, 381 of AstUnpickler.scala). The `addrMap` field on
`TastyOrigin` is therefore empty at decode time.

`TreeUnpickler.decodeSync` receives the `TastyOrigin` and must use the `addrMap` to resolve
`TERMREFdirect` (tag=62), `TERMREFsymbol` (tag=114), and `SHAREDterm` (tag=60) references within
the body. With an empty map, all such references will fail to resolve and fall back to `Unknown`.

Resolution: Pass 1 must store the live `addrMap` snapshot into `TastyOrigin` at definition-node
construction time. This requires changing the `AstUnpickler.walkStats` calls that currently pass
`Map.empty`. The snapshot must be taken AFTER the full file walk completes (so that forward
references within the same file are visible). Since `walkStats` builds the map incrementally during
the walk, a per-definition snapshot would be stale for forward references.

Recommended approach: store the full `Pass1Result.addrMap` (the complete post-walk map) on the
`TastyOrigin` of every symbol, after the walk completes. This requires a second pass over all symbols
to update their `origin.addrMap` before returning `Pass1Result`. `TastyOrigin` is a `final case class`
so it can be copied: `sym._origin = sym.origin.asInstanceOf[TastyOrigin].copy(addrMap = fullMap)`.
However, `Symbol.origin` is declared `val` (not var), so the update requires either making origin
a `var private[kyo]` or storing the addrMap externally (e.g., on a per-file context object passed
to `decodeSync`).

Alternative: pass `Pass1Result.addrMap` as an explicit argument to `decodeSync` rather than
embedding it in `TastyOrigin`. This avoids mutating origin values.

### CONCERN-2 (HIGH): Body byte array not stored on TastyOrigin

As noted in section 2, `TastyOrigin` stores only `bodyStart` and `bodyEnd` (int offsets). The
original `Array[Byte]` from `decodeTastyBytes` is not retained after the file decode returns. When
`Symbol.body` calls `_bodyMemo.get()`, which calls `TreeUnpickler.decodeSync(origin, sym)`, there
are no bytes to decode from.

The `TastyOrigin` case class must be extended to also hold either:
(a) the full TASTy `Array[Byte]` (carries ~10-100KB per file but allows arbitrary re-decode), or
(b) a pre-extracted `Array[Byte]` of just the body slice (`bytes.slice(bodyStart, bodyEnd)`).

Option (b) wastes memory for symbols without meaningful bodies (type params, params) but allows
the origin to be self-contained. Option (a) keeps origin lightweight in terms of allocations
(shared byte array, not per-symbol copies) but holds a reference to the full file.

The STEERING-blessed approach is option (a): store the full byte array on TastyOrigin. This matches
the AstUnpickler pattern of keeping the full `view` in scope. The `TastyOrigin` field name could
be `bytes: Array[Byte]`.

### CONCERN-3 (MEDIUM): Memo race allows init() to run twice

`Memo.get()` calls `init()` and then does `compareAndSet(Unset, v)`. If two threads race, both
call `init()` (two `decodeSync` calls on the same byte slice). Both are decoding from the same
immutable byte array from independent `ByteView` instances, so the results are structurally equal
but not reference-equal. The CAS ensures only one result is stored. Both threads return the stored
result.

This means test 9 (`tree1 eq tree2`) is correct ONLY when both calls happen from the same thread
(no race). Under concurrent access, `tree1` and `tree2` are both the stored value (eq) because
both reads happen after the first CAS succeeds. If the first call wins the CAS and the second call
also calls `init()` before reading, the second call will see its own init result from `ref.get()` --
wait, actually `ref.get()` in `get()` is called AFTER `compareAndSet`, so it returns the winner's
value. Both threads return the same stored AnyRef, so `eq` holds. Test 9 is safe.

### CONCERN-4 (MEDIUM): BLOCK stats vs. expr boundary detection

A BLOCK payload contains zero or more statement Trees followed by exactly one expression Tree (the
last child). There is no explicit separator. The decoder must read Trees until the payload is
exhausted, treating the last one as the expression and all prior ones as stats.

Implementation: collect all Trees from position to payloadEnd into a buffer. If the buffer has one
element, `Block(Chunk.empty, last)`. If it has multiple, `Block(Chunk.from(init), last)`. The
`init` / `last` split requires a non-empty buffer; an empty BLOCK payload is an error.

### CONCERN-5 (LOW): Snapshot-loaded symbols have no body bytes

IMPROVEMENT-ANALYSIS.md G14 (line 77): `SnapshotWriter.serialize` sets `bodyBytes = Array.empty[Byte]`
and `SnapshotReader` never reads them back. Symbols loaded from snapshots will have `bodyStart`
and `bodyEnd` values but no byte array. `Symbol.body` will fail for snapshot-loaded symbols.

Phase 8 does not address G14. The `body` accessor should detect this case and return
`Abort.fail(ReflectError.NotImplemented("body not available for snapshot-loaded symbols"))` rather
than panicking. Check: if the byte array is null or empty and bodyStart > 0, return NotImplemented.

---

## 8. dotty TreeUnpickler Reference

The canonical dotty implementation is at:

```
https://github.com/scala/scala3/blob/main/compiler/src/dotty/tools/dotc/core/tasty/TreeUnpickler.scala
```

Key methods in dotty's TreeUnpickler relevant to Phase 8:
- `readTerm()` -- term Tree dispatch (corresponds to the main `readTree` function in TreeUnpickler)
- `readPattern()` -- pattern dispatch (same function in kyo-reflect; no separate mode needed)
- `readStats(end: Addr, exprOwner: Symbol)` -- collects stats up to `end` for BLOCK/TEMPLATE
- `readTrees(end: Addr)` -- collects multiple Trees to `end`; used for APPLY args, ALTERNATIVE patterns
- `indexStats(end: Addr)` -- the pre-scan counterpart to kyo-reflect's `walkStats` (already implemented)
- `readSelf()` -- reads the optional SELFDEF node inside TEMPLATE
- `forkAt(addr: Addr).indexStats()` -- the pattern for indexing template params before walking body

The dotty decoder is organized around a `TastyReader` cursor; kyo-reflect uses `ByteView` with
the same category-based skip logic already implemented in `AstUnpickler.skipTreeBody`.

Phase 8's `TreeUnpickler.decodeSync` should follow the same dispatch pattern as dotty's `readTerm`,
using `TastyFormat` tag constants to match on the first byte, then reading the payload according
to the category rules documented in section 3 above.
